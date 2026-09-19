/* EloQuick JNI bridge: IBM Eloquence / Embedded ViaVoice engine for Android.
 *
 * Java side: com.eloquick.tts.EloQuickEngine (see docs/android.md for the
 * Kotlin skeleton). All engine access goes through the published eci.h API;
 * nothing here reaches the engine's internal eo_ / es_ names, so the bridge
 * keeps working whatever the internals do.
 *
 * Two lessons from Eagalon/openevv's SAPI wrapper are built in rather than
 * relearned:
 *  1. delta_languages[] reads 0 for every language until delta_lang_bind_all()
 *     has run. eciGetAvailableLanguages() binds internally, so this bridge
 *     ALWAYS queries the language table (bind) before eciNewEx() -- never
 *     eciNewEx() on a cold registry, which is how eighty voices in ten
 *     languages all spoke US English over there.
 *  2. The engine once built room for 8 languages while carrying 10. This
 *     bridge makes no fixed-size assumption: it asks with no room first,
 *     sizes to the answer, then fills.
 *
 * Streaming (nativeStreamCreate/Speak/Read/Stop/Destroy) follows the protocol
 * proven in trypsynth/evvdroid's evv_jni.c (MIT), reimplemented here for this
 * bridge's handle model: a ring between the engine's synthesis thread and
 * the reader, a worker thread for the two-phase end detection (neither
 * eciSpeaking nor eciSynchronize alone marks the end), and stops via
 * eciDataAbort from the callback -- never eciStop, which empirically kills
 * instances after ~80 calls. No JNI is ever made from the engine thread.
 *
 * Threading: the engine calls the waveform callback on its own synthesis
 * thread. Callbacks only move samples and return; they never call back
 * into the engine or into the JVM.
 */

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <errno.h>

#include "eci.h"

#if defined(__aarch64__) || defined(__ARM_NEON)
#include <arm_neon.h>
#endif

#ifdef __ANDROID__
#include <jni.h>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
#elif defined(__unix__) || defined(__APPLE__)
/* Host builds (CMake without NDK) still type-check this file when a JDK is
 * present: jni.h comes from find_package(JNI) there. */
#include <jni.h>
#include <pthread.h>
#include <unistd.h>
#else
#error "android/eloquick_jni.c is POSIX-only (Android/Linux/macOS)"
#endif

/* Logging */
#ifdef __ANDROID__
#define LOG_TAG "EloQuick"
#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGV(...) fprintf(stderr, "V: " __VA_ARGS__)
#define LOGD(...) fprintf(stderr, "D: " __VA_ARGS__)
#define LOGI(...) fprintf(stderr, "I: " __VA_ARGS__)
#define LOGW(...) fprintf(stderr, "W: " __VA_ARGS__)
#define LOGE(...) fprintf(stderr, "E: " __VA_ARGS__)
#endif

/* Port lifecycle: evv_port_start() once before the first instance, and
 * evv_port_finish() after the last. XREF src/port/evv_port.h. */
void evv_port_start(void);
void evv_port_finish(void);
void evvRunStaticInitialisers(void);

#define EQ_FRAME 2048

/* Service hardening limits. Whole-utterance nativeSynth holds the full PCM
 * in RAM before returning: cap both ends so a TalkBack paragraph cannot
 * ANR (300 s spin) or OOM the service. Long text belongs on the streaming
 * path (nativeStreamSpeak/Read), which pages through a 128 KB ring and is
 * stoppable mid-flight. */
#define EQ_MAX_TEXT_BYTES (64 * 1024)
#define EQ_MAX_PCM_SAMPLES (11025 * 60 * 2)

/* Encoding contract (lesson pulled from stormdragon2976/openevv
 * speech-dispatcher-module: Polish UTF-8 vs Latin-1 byte walks collide at
 * 0xC0-0xDF/0xA1-0xBF, so "above 0x7F in UTF-8 a byte is not a character to
 * judge"). Engine expectation per language today:
 *  - plpl: UTF-8 natively (engine converts itself);
 *  - jajp: romanizer path, not raw kana/kanji bytes;
 *  - all others: single-byte Latin-1-ish. Java callers pass UTF-8; non-ASCII
 *    Latin-1 text must be converted before eciAddText (see EqText.java and
 *    docs/android.md). This helper at least rejects malformed UTF-8 early
 *    rather than feeding truncation artifacts to the engine. */
static int eq_utf8_valid(const char *s, size_t n)
{
    size_t i = 0;
    /* ASCII fast-path: if no bytes with the high bit set, valid UTF-8.
       (memchr for a single 0x80 value is wrong: most non-ASCII bytes are
       != 0x80, e.g. 0xC3 0xA9 for U+00E9.) */
    size_t k = 0;
    while (k < n && ((unsigned char)s[k] & 0x80) == 0)
        k++;
    if (k == n)
        return 1;
    i = k;
    while (i < n) {
        unsigned char c = (unsigned char)s[i];
        size_t need;
        if (c < 0x80) { i++; continue; }
        else if ((c & 0xE0) == 0xC0) need = 1;
        else if ((c & 0xF0) == 0xE0) need = 2;
        else if ((c & 0xF8) == 0xF0) need = 3;
        else return 0;
        if (i + need >= n) return 0;
        for (size_t j = 1; j <= need; j++)
            if (((unsigned char)s[i + j] & 0xC0) != 0x80) return 0;
        i += need + 1;
    }
    return 1;
}

typedef struct {
    short *pcm;
    size_t len;
    size_t cap;
    short frame[EQ_FRAME];
} eq_session;

/* Global port lock for reference-counted engine initialization */
static pthread_mutex_t eq_port_lock = PTHREAD_MUTEX_INITIALIZER;
static int eq_live_instances = 0;

/* Dedicated mutex for EVV_HETERO environment variable manipulation in
 * eq_new_ex_hetero. This avoids deadlock with eq_port_lock which is held
 * across eq_create_for_language calls from nativeCreate/nativeStreamCreate. */
static pthread_mutex_t eq_hetero_env_lock = PTHREAD_MUTEX_INITIALIZER;

/* Forward declarations */
static int eq_extra_drop(ECIHand h);
static ECIHand eq_new_ex_hetero(int language);

static void eq_port_acquire(void)
{
    pthread_mutex_lock(&eq_port_lock);
    if (eq_live_instances == 0) {
        evv_port_start();
        evvRunStaticInitialisers();
    }
    eq_live_instances++;
    pthread_mutex_unlock(&eq_port_lock);
}

static void eq_port_release(void)
{
    pthread_mutex_lock(&eq_port_lock);
    if (eq_live_instances > 0) {
        eq_live_instances--;
        if (eq_live_instances == 0)
            evv_port_finish();
    }
    pthread_mutex_unlock(&eq_port_lock);
}

static void eq_keep(eq_session *s, const short *p, size_t n)
{
    if (s->len + n > s->cap) {
        size_t want = (s->len + n) * 2 + EQ_FRAME;
        short *np = (short *)realloc(s->pcm, want * sizeof(*np));
        if (!np)
            return; /* OOM: drop the tail rather than crash the synth thread. */
        s->pcm = np;
        s->cap = want;
    }
    memcpy(s->pcm + s->len, p, n * sizeof(*p));
    s->len += n;
}

static int ECICALL eq_on_message(ECIHand h, ECIMessage msg, int param, void *data)
{
    (void)h;
    if (msg == eciWaveformBuffer && param > 0 && data)
        eq_keep((eq_session *)data, ((eq_session *)data)->frame, (size_t)param);
    return eciDataProcessed;
}

/* Bind-then-create. Returns NULL when the language is absent. */
static ECIHand eq_create_for_language(int language_or_zero)
{
    unsigned int langs[64];
    int n = 64, k;

    /* The bind: reading the registry is what binds it. NOTE: nonzero is
       failure here (XREF cli/evv.c: eo_getAvailableLanguages), not success. */
    if (eciGetAvailableLanguages(langs, &n) != 0 || n < 1)
        return (ECIHand)0;
    if (language_or_zero == 0)
        return eq_new_ex_hetero((int)langs[0]);
    for (k = 0; k < n; k++) {
        if ((int)langs[k] == language_or_zero)
            return eq_new_ex_hetero(language_or_zero);
    }
    return (ECIHand)0;
}

static int eq_speak_to_session(ECIHand h, eq_session *s, const char *text)
{
    int spins;

    s->len = 0;
    eciRegisterCallback(h, eq_on_message, s);
    if (!eciSetOutputBuffer(h, EQ_FRAME, s->frame))
        return 0;
    if (!eciAddText(h, text))
        return 0;
    if (!eciSynthesize(h))
        return 0;
    /* Pumping the queue is what drains it (XREF cli/evv.c). Bounded: a TalkBack
     * paragraph that never drains must fail fast, not hold a binder thread
     * for 300 s. Streaming is the unbounded path. */
    for (spins = 0; spins < 3000 && eciSpeaking(h); spins++)
        usleep(10 * 1000);
    if (eciSpeaking(h))
        return 0;
    eciSynchronize(h);
    return 1;
}

/* ========================================================================
 * Core Instance Management
 * ======================================================================== */

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeCreate
 * Signature: (I)J
 */
JNIEXPORT jlong JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeCreate(JNIEnv *env, jclass cls, jint language)
{
    ECIHand h;
    (void)env;
    (void)cls;
    eq_port_acquire();
    h = eq_create_for_language((int)language);
    if (!h)
        eq_port_release();
    return (jlong)(intptr_t)h;
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeDestroy
 * Signature: (J)V
 */
JNIEXPORT void JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeDestroy(JNIEnv *env, jclass cls, jlong handle)
{
    ECIHand h = (ECIHand)(intptr_t)handle;
    (void)env;
    (void)cls;
    if (!h)
        return;
    eq_extra_drop(h);
    eciDelete(h);
    eq_port_release();
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeGetLanguages
 * Signature: ()[I
 */
JNIEXPORT jintArray JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeGetLanguages(JNIEnv *env, jclass cls)
{
    unsigned int langs[64];
    int n = 64, k;
    jintArray out;
    (void)cls;
    eq_port_acquire();
    /* Nonzero is failure (see above). */
    if (eciGetAvailableLanguages(langs, &n) != 0 || n < 1) {
        eq_port_release();
        return NULL;
    }
    out = (*env)->NewIntArray(env, n);
    if (out) {
        jint *buf = (jint *)malloc((size_t)n * sizeof(*buf));
        if (buf) {
            for (k = 0; k < n; k++)
                buf[k] = (jint)langs[k];
            (*env)->SetIntArrayRegion(env, out, 0, n, buf);
            free(buf);
        }
    }
    eq_port_release();
    return out;
}

/* ========================================================================
 * Synthesis (Whole-Utterance)
 * ======================================================================== */

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeSynth
 * Signature: (JLjava/lang/String;)[S
 *
 * Returns 11025 Hz mono PCM, or null on failure. The caller picks the voice
 * with nativeSetParam(handle, voiceParam, value) beforehand.
 */
JNIEXPORT jshortArray JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeSynth(JNIEnv *env, jclass cls,
                                                 jlong handle, jstring text)
{
    ECIHand h = (ECIHand)(intptr_t)handle;
    const char *utf8 = NULL;
    eq_session s;
    jshortArray out = NULL;
    size_t nbytes;
    (void)cls;
    if (!h || !text)
        return NULL;
    utf8 = (*env)->GetStringUTFChars(env, text, NULL);
    if (!utf8)
        return NULL;
    /* Fail fast on oversize/malformed input: service callers must chunk long
     * text onto the streaming path (see EQ_MAX_* above). */
    nbytes = strlen(utf8);
    if (nbytes == 0 || nbytes > EQ_MAX_TEXT_BYTES ||
        !eq_utf8_valid(utf8, nbytes)) {
        (*env)->ReleaseStringUTFChars(env, text, utf8);
        return NULL;
    }
    memset(&s, 0, sizeof(s));
    if (eq_speak_to_session(h, &s, utf8) && s.len > 0) {
        size_t capped = s.len > EQ_MAX_PCM_SAMPLES ? EQ_MAX_PCM_SAMPLES : s.len;
        out = (*env)->NewShortArray(env, (jsize)capped);
        if (out)
            (*env)->SetShortArrayRegion(env, out, 0, (jsize)capped, (const jshort *)s.pcm);
    }
    free(s.pcm);
    (*env)->ReleaseStringUTFChars(env, text, utf8);
    return out;
}

/* ========================================================================
 * Parameter Control (Engine & Voice)
 * ======================================================================== */

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeSetParam
 * Signature: (JII)I
 */
JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeSetParam(JNIEnv *env, jclass cls,
                                                    jlong handle, jint param, jint value)
{
    (void)env;
    (void)cls;
    if (!(ECIHand)(intptr_t)handle)
        return 0;
    return (jint)eciSetParam((ECIHand)(intptr_t)handle, (int)param, (int)value);
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeGetParam
 * Signature: (JI)I
 */
JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeGetParam(JNIEnv *env, jclass cls,
                                                    jlong handle, jint param)
{
    (void)env;
    (void)cls;
    if (!(ECIHand)(intptr_t)handle)
        return 0;
    return (jint)eciGetParam((ECIHand)(intptr_t)handle, (int)param);
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeCopyVoice
 * Signature: (JII)I
 *
 * Copies preset voice `from` (1-8) into the speaking voice 0, like the
 * CLI's -v option. Answers nonzero on success.
 */
JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeCopyVoice(JNIEnv *env, jclass cls,
                                                     jlong handle, jint from, jint to)
{
    (void)env;
    (void)cls;
    if (!(ECIHand)(intptr_t)handle)
        return 0;
    return (jint)eciCopyVoice((ECIHand)(intptr_t)handle, (int)from, (int)to);
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeSetVoiceParam
 * Signature: (JIII)I
 */
JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeSetVoiceParam(JNIEnv *env, jclass cls,
                                                         jlong handle, jint voice,
                                                         jint param, jint value)
{
    (void)env;
    (void)cls;
    if (!(ECIHand)(intptr_t)handle)
        return 0;
    return (jint)eciSetVoiceParam((ECIHand)(intptr_t)handle, (int)voice,
                                  (int)param, (int)value);
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeGetVoiceParam
 * Signature: (JII)I
 */
JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeGetVoiceParam(JNIEnv *env, jclass cls,
                                                         jlong handle, jint voice,
                                                         jint param)
{
    (void)env;
    (void)cls;
    if (!(ECIHand)(intptr_t)handle)
        return 0;
    return (jint)eciGetVoiceParam((ECIHand)(intptr_t)handle, (int)voice, (int)param);
}

/* ---- sample rate -------------------------------------------------------
 *
 * The engine's PARAM_SAMPLE_RATE takes an index, not hertz. The order is
 * IBM's numbering first (0-3) and openevv's after (4-6); XREF cli/evv.c -R.
 */

static const int eq_rate_hz[] = { 8000, 11025, 22050, 16000, 32000, 44100, 48000 };
#define EQ_RATES (sizeof(eq_rate_hz) / sizeof(eq_rate_hz[0]))

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeSetSampleRateHz
 * Signature: (JI)I
 *
 * Answers the rate the engine is now running at. Above 11025 the engine
 * still synthesizes at 11025 and upsamples itself (sinc by default), so
 * the voice is the same one at every setting -- at the cost of more
 * samples per utterance. Unknown rates fall back to 11025.
 */
JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeSetSampleRateHz(JNIEnv *env, jclass cls,
                                                           jlong handle, jint hz)
{
    size_t i;
    int index = 1;
    (void)env;
    (void)cls;
    if (!(ECIHand)(intptr_t)handle)
        return 0;
    for (i = 0; i < EQ_RATES; i++) {
        if (eq_rate_hz[i] == (int)hz) {
            index = (int)i;
            break;
        }
    }
    if (eciSetParam((ECIHand)(intptr_t)handle, 5 /* P_SAMPLE_RATE */, index) < 0)
        return 0;
    return (jint)eq_rate_hz[index];
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeGetSampleRateHz
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeGetSampleRateHz(JNIEnv *env, jclass cls,
                                                           jlong handle)
{
    (void)env;
    (void)cls;
    if (!(ECIHand)(intptr_t)handle)
        return 0;
    int idx = eciGetParam((ECIHand)(intptr_t)handle, 5 /* P_SAMPLE_RATE */);
    if (idx < 0 || idx >= EQ_RATES)
        return 11025;
    return (jint)eq_rate_hz[idx];
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeSetUpsampleMethod
 * Signature: (JI)I
 *
 * Sets the upsampling method for rates above 11025 Hz.
 * 0 = sinc (default, best quality), 1 = cubic, 2 = linear, 3 = hold, 4 = zeros, 5 = none (synthesize at target rate).
 */
JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeSetUpsampleMethod(JNIEnv *env, jclass cls,
                                                              jlong handle, jint method)
{
    (void)env;
    (void)cls;
    if (!(ECIHand)(intptr_t)handle)
        return -1;
    if (method < 0 || method > 5)
        return -1;
    return (jint)eciSetParam((ECIHand)(intptr_t)handle, 10 /* P_UPSAMPLE_METHOD */, (int)method);
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeVersion
 * Signature: ()Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeVersion(JNIEnv *env, jclass cls)
{
    char buffer[20];
    (void)cls;
    memset(buffer, 0, sizeof(buffer));
    eciVersion(buffer);
    buffer[sizeof(buffer) - 1] = 0;
    return (*env)->NewStringUTF(env, buffer);
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeSpeaking
 * Signature: (J)Z
 */
JNIEXPORT jboolean JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeSpeaking(JNIEnv *env, jclass cls,
                                                     jlong handle)
{
    (void)env;
    (void)cls;
    if (!(ECIHand)(intptr_t)handle)
        return JNI_FALSE;
    return eciSpeaking((ECIHand)(intptr_t)handle) ? JNI_TRUE : JNI_FALSE;
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeStop
 * Signature: (J)V
 */
JNIEXPORT void JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStop(JNIEnv *env, jclass cls,
                                                jlong handle)
{
    (void)env;
    (void)cls;
    if (!(ECIHand)(intptr_t)handle)
        return;
    eciStop((ECIHand)(intptr_t)handle);
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeReset
 * Signature: (J)V
 */
JNIEXPORT void JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeReset(JNIEnv *env, jclass cls,
                                                 jlong handle)
{
    (void)env;
    (void)cls;
    if (!(ECIHand)(intptr_t)handle)
        return;
    eciReset((ECIHand)(intptr_t)handle);
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeSynchronize
 * Signature: (J)V
 */
JNIEXPORT void JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeSynchronize(JNIEnv *env, jclass cls,
                                                        jlong handle)
{
    (void)env;
    (void)cls;
    if (!(ECIHand)(intptr_t)handle)
        return;
    eciSynchronize((ECIHand)(intptr_t)handle);
}

/* ========================================================================
 * Dictionary Support (Per-Instance Extras)
 * ======================================================================== */

typedef struct eq_extra {
    ECIHand handle;
    ECIDictHand dict;
    int refs; /* guarded by eq_extra_lock; entry freed when refs==0 after unlink */
    struct eq_extra *next;
} eq_extra;

static pthread_mutex_t eq_extra_lock = PTHREAD_MUTEX_INITIALIZER;
static eq_extra *eq_extras = NULL;

static eq_extra *eq_extra_find_locked(ECIHand h)
{
    eq_extra *e = eq_extras;
    while (e) {
        if (e->handle == h)
            return e;
        e = e->next;
    }
    return NULL;
}

/* Acquire a reference: safe to use after unlock until eq_extra_release. */
static eq_extra *eq_extra_get(ECIHand h)
{
    eq_extra *e;
    pthread_mutex_lock(&eq_extra_lock);
    e = eq_extra_find_locked(h);
    if (!e) {
        e = (eq_extra *)calloc(1, sizeof(*e));
        if (e) {
            e->handle = h;
            e->refs = 1;
            e->next = eq_extras;
            eq_extras = e;
        }
    } else {
        e->refs++;
    }
    pthread_mutex_unlock(&eq_extra_lock);
    return e;
}

static void eq_extra_release(eq_extra *e)
{
    int free_now = 0;
    pthread_mutex_lock(&eq_extra_lock);
    if (--e->refs == 0) {
        free_now = 1;
    }
    pthread_mutex_unlock(&eq_extra_lock);
    if (free_now)
        free(e);
}

/* Snapshot the dict handle under lock; engine calls run on the copy after
 * unlock, so concurrent destroy/forget cannot dangle the caller. */
static ECIDictHand eq_extra_dict_snapshot(eq_extra *e)
{
    ECIDictHand d;
    pthread_mutex_lock(&eq_extra_lock);
    d = e->dict;
    pthread_mutex_unlock(&eq_extra_lock);
    return d;
}

/* Drops the extras for h, releasing engine-side state. Unlinks under lock,
 * then makes engine calls on local copies with no lock held. Answers 1 when
 * something was dropped. Refcounted so a concurrent teach/lookup holding a
 * reference cannot see a freed entry. */
static int eq_extra_drop(ECIHand h)
{
    eq_extra *e, *prev = NULL;
    ECIDictHand dict = NULL_DICT_HAND;
    int dropped = 0;
    pthread_mutex_lock(&eq_extra_lock);
    e = eq_extras;
    while (e) {
        if (e->handle == h) {
            if (prev)
                prev->next = e->next;
            else
                eq_extras = e->next;
            dict = e->dict;
            e->dict = NULL_DICT_HAND;
            e->refs--;
            if (e->refs == 0) {
                pthread_mutex_unlock(&eq_extra_lock);
                free(e);
            } else {
                pthread_mutex_unlock(&eq_extra_lock);
            }
            dropped = 1;
            break;
        }
        prev = e;
        e = e->next;
    }
    if (!dropped)
        pthread_mutex_unlock(&eq_extra_lock);
    if (!dropped)
        return 0;
    if (dict) {
        eciSetDict(h, NULL_DICT_HAND);
        eciDeleteDict(h, dict);
    }
    return 1;
}

static int eq_dict_ensure(ECIHand h, eq_extra *e)
{
    ECIDictHand existing = eq_extra_dict_snapshot(e);
    ECIDictHand fresh;
    if (existing)
        return 1;
    fresh = eciNewDict(h);
    if (!fresh)
        return 0;
    if (eciSetDict(h, fresh) != 0) {
        eciDeleteDict(h, fresh);
        return 0;
    }
    pthread_mutex_lock(&eq_extra_lock);
    if (e->dict) {
        ECIDictHand winner = e->dict;
        pthread_mutex_unlock(&eq_extra_lock);
        eciSetDict(h, winner);
        eciDeleteDict(h, fresh);
        return 1;
    }
    e->dict = fresh;
    pthread_mutex_unlock(&eq_extra_lock);
    return 1;
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeDictTeach
 * Signature: (JILjava/lang/String;Ljava/lang/String;)I
 *
 * Teaches one word in a dictionary volume (0 main, 1 root, 2 abbreviation).
 * Text files of "key TAB say" lines (Latin-1) are taught a word at a time
 * rather than via eciLoadDict, whose loader demands IBM's own saved binary
 * form and refuses text (evvdroid's measured finding). Answers 0 on success.
 */
JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeDictTeach(JNIEnv *env, jclass cls,
                                                     jlong handle, jint volume,
                                                     jstring key, jstring say)
{
    ECIHand h = (ECIHand)(intptr_t)handle;
    eq_extra *e;
    ECIDictHand dict;
    const char *k, *s;
    size_t kn, sn;
    int answer;
    (void)cls;
    if (!h || !key || !say)
        return -1;
    e = eq_extra_get(h);
    if (!e || !eq_dict_ensure(h, e)) {
        if (e)
            eq_extra_release(e);
        return -1;
    }
    dict = eq_extra_dict_snapshot(e);
    eq_extra_release(e);
    if (!dict)
        return -1;
    k = (*env)->GetStringUTFChars(env, key, NULL);
    s = (*env)->GetStringUTFChars(env, say, NULL);
    if (!k || !s) {
        if (k)
            (*env)->ReleaseStringUTFChars(env, key, k);
        if (s)
            (*env)->ReleaseStringUTFChars(env, say, s);
        return -1;
    }
    kn = strlen(k);
    sn = strlen(s);
    /* Optimized: stack allocation for small pairs */
    char stack_pair[256];
    char *pair = (kn + sn + 2 <= 256) ? stack_pair
                                       : (char *)malloc(kn + sn + 2);
    if (!pair) {
        (*env)->ReleaseStringUTFChars(env, key, k);
        (*env)->ReleaseStringUTFChars(env, say, s);
        return -1;
    }
    memcpy(pair, k, kn + 1);
    memcpy(pair + kn + 1, s, sn + 1);
    answer = eciUpdateDict(h, dict, (int)volume, pair, pair + kn + 1);
    if (pair != stack_pair)
        free(pair);
    (*env)->ReleaseStringUTFChars(env, key, k);
    (*env)->ReleaseStringUTFChars(env, say, s);
    return (jint)answer;
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeDictLookup
 * Signature: (JILjava/lang/String;)Ljava/lang/String;
 *
 * What a key was taught, or null. How a test says a word really went in
 * rather than that a call answered nought.
 */
JNIEXPORT jstring JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeDictLookup(JNIEnv *env, jclass cls,
                                                      jlong handle, jint volume,
                                                      jstring key)
{
    ECIHand h = (ECIHand)(intptr_t)handle;
    eq_extra *e;
    ECIDictHand dict;
    const char *k;
    const char *found;
    jstring answer;
    (void)cls;
    if (!h || !key)
        return NULL;
    e = eq_extra_get(h);
    if (!e)
        return NULL;
    dict = eq_extra_dict_snapshot(e);
    eq_extra_release(e);
    if (!dict)
        return NULL;
    k = (*env)->GetStringUTFChars(env, key, NULL);
    if (!k)
        return NULL;
    found = eciDictLookup(h, dict, (int)volume, k);
    answer = found ? (*env)->NewStringUTF(env, found) : NULL;
    (*env)->ReleaseStringUTFChars(env, key, k);
    return answer;
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeDictForget
 * Signature: (J)V
 *
 * Puts the dictionary set aside: the engine is back to the language's own
 * dictionary.
 */
JNIEXPORT void JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeDictForget(JNIEnv *env, jclass cls,
                                                      jlong handle)
{
    ECIHand h = (ECIHand)(intptr_t)handle;
    eq_extra *e;
    ECIDictHand dict;
    (void)env;
    (void)cls;
    if (!h)
        return;
    e = eq_extra_get(h);
    if (!e)
        return;
    pthread_mutex_lock(&eq_extra_lock);
    dict = e->dict;
    e->dict = NULL_DICT_HAND;
    pthread_mutex_unlock(&eq_extra_lock);
    eq_extra_release(e);
    if (!dict)
        return;
    eciSetDict(h, NULL_DICT_HAND);
    eciDeleteDict(h, dict);
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeDictLoad
 * Signature: (JILjava/lang/String;)I
 *
 * Hands a text file (key TAB say per line) to the engine's own volume
 * loader. Answers eciDictNoError (0) on success. Large files are the
 * caller's problem: community experience (eloquence-revived) is that
 * hundred-thousand-entry root dictionaries hang the engine on-device,
 * so the Java side caps what it will hand over.
 */
JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeDictLoad(JNIEnv *env, jclass cls,
                                                    jlong handle, jint volume,
                                                    jstring path)
{
    ECIHand h = (ECIHand)(intptr_t)handle;
    eq_extra *e;
    ECIDictHand dict;
    const char *name;
    int answer;
    (void)cls;
    if (!h || !path)
        return 6; /* eciDictAccessError */
    e = eq_extra_get(h);
    if (!e)
        return 6;
    if (!eq_dict_ensure(h, e)) {
        eq_extra_release(e);
        return 6;
    }
    dict = eq_extra_dict_snapshot(e);
    eq_extra_release(e);
    if (!dict)
        return 6;
    name = (*env)->GetStringUTFChars(env, path, NULL);
    if (!name)
        return 2; /* eciDictOutOfMemory */
    answer = eciLoadDict(h, dict, (int)volume, name);
    (*env)->ReleaseStringUTFChars(env, path, name);
    return (jint)answer;
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeDictSave
 * Signature: (JILjava/lang/String;)I
 *
 * Saves the current dictionary volume to a file (binary format).
 */
JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeDictSave(JNIEnv *env, jclass cls,
                                                    jlong handle, jint volume,
                                                    jstring path)
{
    ECIHand h = (ECIHand)(intptr_t)handle;
    eq_extra *e;
    ECIDictHand dict;
    const char *name;
    int answer;
    (void)cls;
    if (!h || !path)
        return 6;
    e = eq_extra_get(h);
    if (!e)
        return 6;
    dict = eq_extra_dict_snapshot(e);
    eq_extra_release(e);
    if (!dict)
        return 6;
    name = (*env)->GetStringUTFChars(env, path, NULL);
    if (!name)
        return 2;
    answer = eciSaveDict(h, dict, (int)volume, name);
    (*env)->ReleaseStringUTFChars(env, path, name);
    return (jint)answer;
}

/* ========================================================================
 * Heteronym Filter (Creation-Time Property)
 * ======================================================================== */

static pthread_mutex_t eq_hetero_lock = PTHREAD_MUTEX_INITIALIZER;
static int eq_hetero_default = 0;

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeSetHeteroDefault
 * Signature: (Z)V
 */
JNIEXPORT void JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeSetHeteroDefault(JNIEnv *env, jclass cls,
                                                            jboolean on)
{
    (void)env;
    (void)cls;
    pthread_mutex_lock(&eq_hetero_lock);
    eq_hetero_default = on ? 1 : 0;
    pthread_mutex_unlock(&eq_hetero_lock);
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeGetHeteroDefault
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeGetHeteroDefault(JNIEnv *env, jclass cls)
{
    int val;
    (void)env;
    (void)cls;
    pthread_mutex_lock(&eq_hetero_lock);
    val = eq_hetero_default;
    pthread_mutex_unlock(&eq_hetero_lock);
    return val ? JNI_TRUE : JNI_FALSE;
}

/* eciNewEx with the hetero default in force. Serialized: the env is
   process-global, so two creations with different wants must not overlap.
   Uses eq_hetero_env_lock (not eq_port_lock) to avoid deadlock with
   eq_port_acquire which is held across this call from nativeCreate/
   nativeStreamCreate. */
static ECIHand eq_new_ex_hetero(int language)
{
    ECIHand h;
    int want;
    char *had = NULL;
    const char *prev;
    pthread_mutex_lock(&eq_hetero_lock);
    want = eq_hetero_default;
    pthread_mutex_lock(&eq_hetero_env_lock);
    if (want) {
        prev = getenv("EVV_HETERO");
        if (prev) {
            had = (char *)malloc(strlen(prev) + 1);
            if (had)
                strcpy(had, prev);
        }
        setenv("EVV_HETERO", "on", 1);
    }
    h = eciNewEx(language);
    if (want) {
        if (had) {
            setenv("EVV_HETERO", had, 1);
            free(had);
        } else {
            unsetenv("EVV_HETERO");
        }
    }
    pthread_mutex_unlock(&eq_hetero_env_lock);
    pthread_mutex_unlock(&eq_hetero_lock);
    return h;
}

/* ========================================================================
 * Streaming API (Stoppable, Low-Latency)
 * ======================================================================== */

#define EQ_STREAM_FRAME 1024
#define EQ_RING_BYTES (128 * 1024)
#define EQ_START_SPINS 200
#define EQ_INDEX_QUEUE 16
/* With index marks the engine may run no further ahead of the reader than
   this, so a mark is queued while its audio is still close to the listener
   instead of minutes ahead (the engine synthesizes ~100x realtime; without
   a cap it parks a full 128 KB ring of audio before the first mark). */
#define EQ_INDEX_LEAD_BYTES (16 * 1024)

typedef struct {
    ECIHand handle;
    pthread_mutex_t lock;
    pthread_cond_t room;
    pthread_cond_t filled;
    pthread_cond_t work;
    unsigned char *ring;
    size_t head;
    size_t tail;
    size_t count;
    int aborted;
    int started;
    int done;
    int pending;
    int busy;
    int quitting;
    int running;
    unsigned char *text;
    short frame[EQ_STREAM_FRAME];
    pthread_t worker;

    /* Index marks (eciInsertIndex): values the app handed in with its text
       (byte/char offsets of its own choosing -- the engine never reads
       them, it just reports back the ones it has passed). Queued here for
       the reader to drain between reads; guarded by lock. The session's
       one-utterance-at-a-time contract keeps the small fixed queue safe. */
    int want_indices;        /* session created for mark tracking */
    int indices_enabled;     /* marks expected this utterance */
    int mark_queue[EQ_INDEX_QUEUE];
    int mark_count;

    /* prosody / pacing / pause / dict (evvdroid-style) */
    int pause_mode;          /* 0=keep 1=end-only 2=all */
    int phrase_prediction;
    int abbreviations;
    int speed;               /* override for paced synthesis */
    int pitch;
    int lead_ms;             /* ms of audio to keep ahead (default 300) */
    int64_t last_speak_ms;   /* timestamp of last speak call */
    /* dictionary: up to 8 loaded paths */
    #define EQ_MAX_DICTS 8
    char *dict_paths[EQ_MAX_DICTS];
    int   dict_count;
} eq_stream;

static int ECICALL eq_stream_message(ECIHand h, ECIMessage msg, int param, void *data)
{
    eq_stream *s = (eq_stream *)data;
    size_t want;
    (void)h;
    if (msg == eciIndexReply) {
        /* The engine reports marks the moment it has synthesized the audio
           in front of them (stb_synthIndexCallback flushes those samples
           to us first), so with the paced ring below a queued mark means
           "this offset is being heard about now". param is whatever value
           the app gave eciInsertIndex -- here a byte offset into its text.
           No JNI is ever made from this thread; the reader drains the
           queue between nativeStreamRead calls. A full queue drops the
           mark: speech never stops for a lost highlight. */
        if (!s->want_indices)
            return eciDataProcessed;
        pthread_mutex_lock(&s->lock);
        if (s->mark_count < EQ_INDEX_QUEUE) {
            s->mark_queue[s->mark_count++] = param < 0 ? 0 : param;
            pthread_cond_broadcast(&s->filled);
        }
        pthread_mutex_unlock(&s->lock);
        return eciDataProcessed;
    }
    if (msg != eciWaveformBuffer)
        return eciDataProcessed;
    want = (size_t)param * sizeof(short);
    if (want == 0)
        return eciDataProcessed;
    pthread_mutex_lock(&s->lock);
    /* Normally the engine fills the whole ring before it waits for room;
       with index marks it is held to EQ_INDEX_LEAD_BYTES so marks stay
       near the audio they belong to. The reader always keeps draining, so
       this paces the engine rather than stopping it. */
    while (!s->aborted
           && (EQ_RING_BYTES - s->count < want
               || (s->indices_enabled && s->count + want > EQ_INDEX_LEAD_BYTES)))
        pthread_cond_wait(&s->room, &s->lock);
    if (s->aborted) {
        pthread_mutex_unlock(&s->lock);
        return eciDataAbort;
    }
    {
        size_t first = EQ_RING_BYTES - s->head;
        if (first > want)
            first = want;
#if defined(__aarch64__) || defined(__ARM_NEON)
        /* NEON ring copy. first/want/head are BYTE counts; frame is
           short[] so all addresses must be computed in bytes first
           ((short*)(frame)+i would scale i by 2). */
        if (first >= 32) {
            const unsigned char *fbase = (const unsigned char *)s->frame;
            size_t i = 0;
            for (; i + 15 < first; i += 16) {
                int16x8_t v0 = vld1q_s16((const int16_t *)(fbase + i));
                int16x8_t v1 = vld1q_s16((const int16_t *)(fbase + i + 16));
                vst1q_s16((int16_t *)(s->ring + s->head + i), v0);
                vst1q_s16((int16_t *)(s->ring + s->head + i + 16), v1);
            }
            for (; i < first; i += 2) {
                *(int16_t *)(s->ring + s->head + i) = *(const int16_t *)(fbase + i);
            }
            size_t rest = want - first;
            if (rest >= 32) {
                size_t j = 0;
                for (; j + 15 < rest; j += 16) {
                    int16x8_t v0 = vld1q_s16((const int16_t *)(fbase + first + j));
                    int16x8_t v1 = vld1q_s16((const int16_t *)(fbase + first + j + 16));
                    vst1q_s16((int16_t *)(s->ring + j), v0);
                    vst1q_s16((int16_t *)(s->ring + j + 16), v1);
                }
                for (; j < rest; j += 2) {
                    *(int16_t *)(s->ring + j) = *(const int16_t *)(fbase + first + j);
                }
            } else {
                memcpy(s->ring, (unsigned char *)s->frame + first, rest);
            }
        } else
#endif
        {
            memcpy(s->ring + s->head, s->frame, first);
            memcpy(s->ring, (unsigned char *)s->frame + first, want - first);
        }
        s->head = (s->head + want) % EQ_RING_BYTES;
        s->count += want;
    }
    s->started = 1;
    pthread_cond_signal(&s->filled);
    pthread_mutex_unlock(&s->lock);
    return eciDataProcessed;
}

static void *eq_stream_worker(void *data)
{
    eq_stream *s = (eq_stream *)data;
    for (;;) {
        int spins;
        pthread_mutex_lock(&s->lock);
        while (!s->pending && !s->quitting)
            pthread_cond_wait(&s->work, &s->lock);
        if (s->quitting) {
            pthread_mutex_unlock(&s->lock);
            return NULL;
        }
        s->pending = 0;
        pthread_mutex_unlock(&s->lock);
        /* Two-phase end: first the engine must visibly take the work
           (a buffer delivered, or eciSpeaking yes), then eciSynchronize,
           which is exact once there is something to wait on. Neither half
           marks the end alone. */
        for (spins = 0; spins < EQ_START_SPINS; spins++) {
            int begun;
            pthread_mutex_lock(&s->lock);
            begun = s->started || s->aborted;
            pthread_mutex_unlock(&s->lock);
            if (begun || eciSpeaking(s->handle))
                break;
            usleep(1000);
        }
        eciSynchronize(s->handle);
        pthread_mutex_lock(&s->lock);
        s->done = 1;
        s->busy = 0;
        pthread_cond_broadcast(&s->filled);
        pthread_cond_broadcast(&s->work);
        pthread_mutex_unlock(&s->lock);
    }
}

static void eq_stream_idle(eq_stream *s)
{
    pthread_mutex_lock(&s->lock);
    while (s->busy)
        pthread_cond_wait(&s->work, &s->lock);
    pthread_mutex_unlock(&s->lock);
}

static jboolean eq_stream_finished(eq_stream *s);

JNIEXPORT jlong JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamCreateWithIndices(JNIEnv *env,
                                                        jclass cls, jint language,
                                                        jboolean wantIndices);
JNIEXPORT jboolean JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamSpeakWithMarks(JNIEnv *env,
                                                       jclass cls, jlong shandle,
                                                       jstring text,
                                                       jintArray marks, jint nmarks);

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeStreamCreate
 * Signature: (I)J
 *
 * A streaming session on a fresh instance of the language (0 = default).
 * Destroy with nativeStreamDestroy, not nativeDestroy.
 */
JNIEXPORT jlong JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamCreate(JNIEnv *env, jclass cls,
                                                        jint language)
{
    return Java_com_eloquick_tts_EloQuickEngine_nativeStreamCreateWithIndices(
            env, cls, language, JNI_FALSE);
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeStreamCreateWithIndices
 * Signature: (IZ)J
 *
 * nativeStreamCreate, plus ECI index marks: when wantIndices is true the
 * session's callback queues the marks the engine reports (values handed
 * in per utterance via nativeStreamSpeakWithMarks) for the reader to
 * drain between nativeStreamRead calls; marks ride with their audio
 * because the ring is paced while they are enabled. A session created
 * with JNI_FALSE behaves exactly like one from nativeStreamCreate.
 */
JNIEXPORT jlong JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamCreateWithIndices(JNIEnv *env,
                                                        jclass cls, jint language,
                                                        jboolean wantIndices)
{
    eq_stream *s;
    ECIHand h;
    pthread_attr_t attr;
    (void)cls;
    eq_port_acquire();
    h = eq_create_for_language((int)language);
    if (!h) {
        eq_port_release();
        return 0;
    }
    s = (eq_stream *)calloc(1, sizeof(*s));
    if (!s) {
        eciDelete(h);
        eq_port_release();
        return 0;
    }
    s->ring = (unsigned char *)malloc(EQ_RING_BYTES);
    if (!s->ring) {
        free(s);
        eciDelete(h);
        eq_port_release();
        return 0;
    }
    s->handle = h;
    pthread_mutex_init(&s->lock, NULL);
    pthread_cond_init(&s->room, NULL);
    pthread_cond_init(&s->filled, NULL);
    pthread_cond_init(&s->work, NULL);
    s->want_indices = wantIndices ? JNI_TRUE : JNI_FALSE;
    s->indices_enabled = 0;
    eciRegisterCallback(h, eq_stream_message, s);
    if (!eciSetOutputBuffer(h, EQ_STREAM_FRAME, s->frame)) {
        eciDelete(h);
        free(s->ring);
        free(s);
        eq_port_release();
        return 0;
    }
    /* Service-grade defaults, mirroring evvdroid's configure: annotations
       always on (the `vs/`vb/`pp rate/pitch/intonation prefixes and the
       heteronym filter all travel as annotations), engine units, and the
       language dialect nailed to the instance's language. */
    eciSetParam(h, 1 /* P_INPUT_TYPE */, 1);
    eciSetParam(h, 8 /* P_REAL_WORLD_UNITS */, 0);
    eciSetParam(h, 9 /* P_LANGUAGE_DIALECT */, (int)language);
    /* evvdroid-style defaults for prosody / pacing / pause.  These live in
       the struct (not the engine) because the engine refuses parameter
       writes while speaking; the Java side reads them for annotation
       prepending. */
    s->pause_mode       = 1;   /* end-only by default */
    s->phrase_prediction = 0;  /* off unless asked */
    s->abbreviations     = 1;  /* on by default */
    s->speed             = -1; /* -1 = not overridden */
    s->pitch             = -1;
    s->lead_ms           = 300;
    s->last_speak_ms     = 0;
    s->dict_count        = 0;
    s->mark_count        = 0;
    memset(s->dict_paths, 0, sizeof(s->dict_paths));
    /* The engine runs on whichever thread calls eciSynchronize -- here the
       worker below -- so it gets room for the rule dispatcher's frames:
       8MB, virtual until touched, one per stream. */
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 8 * 1024 * 1024);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);
    if (pthread_create(&s->worker, &attr, eq_stream_worker, s) != 0) {
        pthread_attr_destroy(&attr);
        eciDelete(h);
        free(s->ring);
        free(s);
        eq_port_release();
        return 0;
    }
    pthread_attr_destroy(&attr);
    s->running = 1;
    (void)env;
    return (jlong)(intptr_t)s;
}

static jboolean eq_stream_finished(eq_stream *s)
{
    pthread_mutex_lock(&s->lock);
    s->done = 1;
    s->busy = 0;
    pthread_cond_broadcast(&s->filled);
    pthread_cond_broadcast(&s->work);
    pthread_mutex_unlock(&s->lock);
    return JNI_FALSE;
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeStreamSpeak
 * Signature: (JLjava/lang/String;)Z
 *
 * Queues text and starts the engine; answers at once, samples come out of
 * nativeStreamRead. The bytes are kept until the next utterance because
 * nothing here knows whether the engine copied them. Plain nativeStream-
 * SpeakWithMarks: no marks.
 */
JNIEXPORT jboolean JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamSpeak(JNIEnv *env, jclass cls,
                                                       jlong shandle, jstring text)
{
    return Java_com_eloquick_tts_EloQuickEngine_nativeStreamSpeakWithMarks(
            env, cls, shandle, text, NULL, 0);
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeStreamSpeakWithMarks
 * Signature: (JLjava/lang/String;[II)Z
 *
 * nativeStreamSpeak, plus index marks: marks[i] is a byte offset into
 * `text`; the text is fed to the engine in segments split at those
 * offsets with eciInsertIndex(offset) between segments, so the engine
 * reports each mark exactly when it has spoken up to it. The value comes
 * back unchanged through nativeStreamReadIndices -- the engine never
 * reads it, so callers pick the meaning (here: byte offsets, convertible
 * back to text positions). Offsets must ascend and stay inside the text;
 * annotations in the text must not straddle an offset (a split one is
 * spoken instead of obeyed). Marks only work on sessions created with
 * nativeStreamCreateWithIndices; elsewhere they are silently ignored.
 */
JNIEXPORT jboolean JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamSpeakWithMarks(JNIEnv *env,
                                                       jclass cls, jlong shandle,
                                                       jstring text,
                                                       jintArray marks, jint nmarks)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    const char *utf8;
    unsigned char *buf;
    size_t n;
    /* Marks via a stack snapshot, not a pinned array: GetIntArrayElements
       would pin the Java array across eq_stream_idle's blocking wait. */
    jint mv[EQ_INDEX_QUEUE];
    jsize nm = 0;
    int have_mv = 0;
    (void)cls;
    if (!s || !text)
        return JNI_FALSE;
    utf8 = (*env)->GetStringUTFChars(env, text, NULL);
    if (!utf8)
        return JNI_FALSE;
    n = strlen(utf8);
    if (n == 0 || n > EQ_MAX_TEXT_BYTES || !eq_utf8_valid(utf8, n)) {
        (*env)->ReleaseStringUTFChars(env, text, utf8);
        return JNI_FALSE;
    }
    /* Optimized: reuse existing text buffer if large enough */
    if (s->text && strlen((char *)s->text) >= n) {
        buf = s->text;
    } else {
        buf = (unsigned char *)malloc(n + 1);
        if (!buf) {
            (*env)->ReleaseStringUTFChars(env, text, utf8);
            return JNI_FALSE;
        }
    }
    memcpy(buf, utf8, n + 1);
    (*env)->ReleaseStringUTFChars(env, text, utf8);
    if (marks && s->want_indices) {
        nm = (*env)->GetArrayLength(env, marks);
        if (nmarks >= 0 && nm > nmarks) nm = nmarks;
        if (nm > EQ_INDEX_QUEUE) nm = EQ_INDEX_QUEUE;
        if (nm > 0) {
            (*env)->GetIntArrayRegion(env, marks, 0, nm, mv);
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
                nm = 0;
            } else {
                have_mv = 1;
            }
        }
    }
    eq_stream_idle(s);
    /* ABRDICT (NVDA-IBMTTS-Driver): eciDictionary 0 = abbreviation expansion
     * on, 1 = off ("1 turns the dictionary off, not on" - eci.h). Applied at
     * every speak entry while idle: the engine refuses eciSetParam while
     * speaking, and this is the one point each utterance passes through after
     * the previous one has drained. Until now the flag was stored by
     * nativeStreamSetAbbreviations but never applied. A refusal (-1) is
     * harmless - the last applied value stays in force. Default stays on,
     * matching this file's historical default (the driver's own default is
     * off); flip with nativeStreamSetAbbreviations. */
    eciSetParam(s->handle, 3 /* P_DICTIONARY */, s->abbreviations ? 0 : 1);
    pthread_mutex_lock(&s->lock);
    s->aborted = 0;
    s->started = 0;
    s->done = 0;
    s->busy = 1;
    s->head = s->tail = s->count = 0;
    s->mark_count = 0;
    s->indices_enabled = have_mv;
    pthread_mutex_unlock(&s->lock);
    /* buf may reuse s->text (see above): only free the old buffer when
       a fresh one was allocated, otherwise buf IS s->text already. */
    if (buf != s->text) {
        free(s->text);
        s->text = buf;
    }
    if (!s->running) {
        return eq_stream_finished(s);
    }
    if (have_mv) {
        /* Feed the text in segments, a mark between each: the engine
           enqueues text and marks in call order, and reports a mark when
           synthesis reaches it. One eciAddText per segment also keeps
           every annotation whole inside its own call. */
        size_t last = 0;
        int i;
        int placed = 0;
        for (i = 0; i < nm; i++) {
            int at = mv[i];
            if (at <= (int)last || (size_t)at >= n) continue;
            if (!eciAddText(s->handle, buf + last)) break;
            if (!eciInsertIndex(s->handle, at)) break;
            last = (size_t)at;
            placed++;
        }
        (void)placed;
        if (!eciAddText(s->handle, buf + last)) {
            return eq_stream_finished(s);
        }
    } else {
        if (!eciAddText(s->handle, buf))
            return eq_stream_finished(s);
    }
    if (!eciSynthesize(s->handle))
        return eq_stream_finished(s);
    pthread_mutex_lock(&s->lock);
    s->pending = 1;
    pthread_cond_broadcast(&s->work);
    pthread_mutex_unlock(&s->lock);
    return JNI_TRUE;
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeStreamRead
 * Signature: (J[BI)I
 *
 * Fills dst with PCM bytes, blocking until some arrive. Answers 0 at the
 * end of the utterance and -1 when stopped or on error.
 */
JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamRead(JNIEnv *env, jclass cls,
                                                       jlong shandle, jbyteArray dst,
                                                       jint max)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    size_t room, got;
    (void)cls;
    if (!s || !dst || max <= 0)
        return -1;
    room = (size_t)max;
    if ((size_t)(*env)->GetArrayLength(env, dst) < room)
        room = (size_t)(*env)->GetArrayLength(env, dst);
    if (room == 0)
        return -1;
    pthread_mutex_lock(&s->lock);
    while (s->count == 0 && !s->done && !s->aborted)
        pthread_cond_wait(&s->filled, &s->lock);
    if (s->aborted) {
        pthread_mutex_unlock(&s->lock);
        return -1;
    }
    if (s->count == 0) {
        pthread_mutex_unlock(&s->lock);
        return 0;
    }
    got = s->count < room ? s->count : room;
    /* With marks on, never starve the engine of room mid-utterance: hold
       back one frame's worth so the paced ring cannot deadlock (the writer
       waiting for lead room while the reader waits for a fuller buffer).
       The tail end of the utterance still drains fully -- done arrives
       with the ring already empty. */
    if (s->indices_enabled && got > EQ_STREAM_FRAME * sizeof(short)
            && !s->done)
        got -= EQ_STREAM_FRAME * sizeof(short);
    /* Copy directly from ring to Java array in 1-2 calls (avoids malloc). */
    {
        size_t first = EQ_RING_BYTES - s->tail;
        if (first > got)
            first = got;
        (*env)->SetByteArrayRegion(env, dst, 0, (jsize)first,
                                   (const jbyte *)(s->ring + s->tail));
        if (got > first)
            (*env)->SetByteArrayRegion(env, dst, (jsize)first, (jsize)(got - first),
                                       (const jbyte *)s->ring);
        s->tail = (s->tail + got) % EQ_RING_BYTES;
        s->count -= got;
    }
    pthread_cond_signal(&s->room);
    pthread_mutex_unlock(&s->lock);
    return (jint)got;
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeStreamStop
 * Signature: (J)V
 *
 * Wakes the callback before flagging: it may be waiting for room that a
 * stopped reader will never make. eciStop is deliberately NOT called --
 * the abort flag makes the callback answer eciDataAbort next offer, which
 * unwinds the engine from inside its own call and leaves it fit for the
 * next utterance.
 */
JNIEXPORT void JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamStop(JNIEnv *env, jclass cls,
                                                      jlong shandle)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    (void)env;
    (void)cls;
    if (!s)
        return;
    pthread_mutex_lock(&s->lock);
    s->aborted = 1;
    s->head = s->tail = s->count = 0;
    s->mark_count = 0;
    pthread_cond_broadcast(&s->room);
    pthread_cond_broadcast(&s->filled);
    pthread_mutex_unlock(&s->lock);
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeStreamReadIndices
 * Signature: (J[II)I
 *
 * Takes up to max index marks reached by the engine (values as handed to
 * eciInsertIndex -- byte offsets the app chose) into dst. Answers how many
 * were taken; 0 when none yet. Call between nativeStreamRead calls on the
 * reader's thread: mark values arrive with (just ahead of) their audio.
 */
JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamReadIndices(JNIEnv *env,
                                                             jclass cls,
                                                             jlong shandle,
                                                             jintArray dst,
                                                             jint max)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    jint out = 0;
    (void)cls;
    if (!s || !dst || max <= 0)
        return 0;
    if ((*env)->GetArrayLength(env, dst) < max)
        max = (*env)->GetArrayLength(env, dst);
    pthread_mutex_lock(&s->lock);
    while (out < max && s->mark_count > 0) {
        (*env)->SetIntArrayRegion(env, dst, out, 1, &s->mark_queue[0]);
        memmove(s->mark_queue, s->mark_queue + 1,
                (size_t)(s->mark_count - 1) * sizeof(int));
        s->mark_count--;
        out++;
    }
    pthread_mutex_unlock(&s->lock);
    return out;
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeStreamClearIndices
 * Signature: (J)V
 */
JNIEXPORT void JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamClearIndices(JNIEnv *env,
                                                              jclass cls,
                                                              jlong shandle)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    (void)env;
    (void)cls;
    if (!s)
        return;
    pthread_mutex_lock(&s->lock);
    s->mark_count = 0;
    s->indices_enabled = 0;
    pthread_mutex_unlock(&s->lock);
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeStreamDestroy
 * Signature: (J)V
 */
JNIEXPORT void JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamDestroy(JNIEnv *env, jclass cls,
                                                         jlong shandle)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    (void)env;
    (void)cls;
    if (!s)
        return;
    eq_extra_drop(s->handle);
    pthread_mutex_lock(&s->lock);
    s->aborted = 1;
    s->quitting = 1;
    pthread_cond_broadcast(&s->room);
    pthread_cond_broadcast(&s->filled);
    pthread_cond_broadcast(&s->work);
    pthread_mutex_unlock(&s->lock);
    if (s->running) {
        pthread_join(s->worker, NULL);
        s->running = 0;
    }
    eciDelete(s->handle);
    eq_port_release();
    pthread_cond_destroy(&s->room);
    pthread_cond_destroy(&s->filled);
    pthread_cond_destroy(&s->work);
    pthread_mutex_destroy(&s->lock);
    free(s->text);
    free(s->ring);
    for (int i = 0; i < s->dict_count && i < EQ_MAX_DICTS; i++)
        free(s->dict_paths[i]);
    free(s);
}

/* ========================================================================
 * Stream Control (Voice, Rate, Dictionary)
 * ======================================================================== */

JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamCopyVoice(JNIEnv *env, jclass cls,
                                                           jlong shandle, jint from,
                                                           jint to)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    (void)env;
    (void)cls;
    if (!s)
        return 0;
    return (jint)eciCopyVoice(s->handle, (int)from, (int)to);
}

JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamSetVoiceParam(JNIEnv *env, jclass cls,
                                                               jlong shandle, jint voice,
                                                               jint param, jint value)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    (void)env;
    (void)cls;
    if (!s)
        return -1;
    return (jint)eciSetVoiceParam(s->handle, (int)voice, (int)param, (int)value);
}

JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamGetVoiceParam(JNIEnv *env, jclass cls,
                                                               jlong shandle, jint voice,
                                                               jint param)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    (void)env;
    (void)cls;
    if (!s)
        return -1;
    return (jint)eciGetVoiceParam(s->handle, (int)voice, (int)param);
}

JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamSetSampleRateHz(JNIEnv *env, jclass cls,
                                                                 jlong shandle, jint hz)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    size_t i;
    int index = 1;
    (void)env;
    (void)cls;
    if (!s)
        return 0;
    for (i = 0; i < EQ_RATES; i++) {
        if (eq_rate_hz[i] == (int)hz) {
            index = (int)i;
            break;
        }
    }
    if (eciSetParam(s->handle, 5 /* P_SAMPLE_RATE */, index) < 0)
        return 0;
    return (jint)eq_rate_hz[index];
}

JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamGetSampleRateHz(JNIEnv *env, jclass cls,
                                                                  jlong shandle)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    (void)env;
    (void)cls;
    if (!s)
        return 0;
    int idx = eciGetParam(s->handle, 5 /* P_SAMPLE_RATE */);
    if (idx < 0 || idx >= EQ_RATES)
        return 11025;
    return (jint)eq_rate_hz[idx];
}

JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamSetUpsampleMethod(JNIEnv *env, jclass cls,
                                                                    jlong shandle, jint method)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    (void)env;
    (void)cls;
    if (!s)
        return -1;
    if (method < 0 || method > 5)
        return -1;
    return (jint)eciSetParam(s->handle, 10 /* P_UPSAMPLE_METHOD */, (int)method);
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeStreamDictLoad
 * Signature: (JILjava/lang/String;)I
 *
 * File-backed user dictionary for a stream's instance (see nativeDictLoad
 * for the format and the size caution).
 */
JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamDictLoad(JNIEnv *env, jclass cls,
                                                          jlong shandle, jint volume,
                                                          jstring path)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    eq_extra *e;
    ECIDictHand dict;
    const char *name;
    int answer;
    (void)cls;
    if (!s || !path)
        return 6;
    e = eq_extra_get(s->handle);
    if (!e)
        return 6;
    if (!eq_dict_ensure(s->handle, e)) {
        eq_extra_release(e);
        return 6;
    }
    dict = eq_extra_dict_snapshot(e);
    eq_extra_release(e);
    if (!dict)
        return 6;
    name = (*env)->GetStringUTFChars(env, path, NULL);
    if (!name)
        return 2;
    answer = eciLoadDict(s->handle, dict, (int)volume, name);
    (*env)->ReleaseStringUTFChars(env, path, name);
    return (jint)answer;
}

JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamDictSave(JNIEnv *env, jclass cls,
                                                          jlong shandle, jint volume,
                                                          jstring path)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    eq_extra *e;
    ECIDictHand dict;
    const char *name;
    int answer;
    (void)cls;
    if (!s || !path)
        return 6;
    e = eq_extra_get(s->handle);
    if (!e)
        return 6;
    dict = eq_extra_dict_snapshot(e);
    eq_extra_release(e);
    if (!dict)
        return 6;
    name = (*env)->GetStringUTFChars(env, path, NULL);
    if (!name)
        return 2;
    answer = eciSaveDict(s->handle, dict, (int)volume, name);
    (*env)->ReleaseStringUTFChars(env, path, name);
    return (jint)answer;
}

JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamDictTeach(JNIEnv *env, jclass cls,
                                                           jlong shandle, jint volume,
                                                           jstring key, jstring say)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    eq_extra *e;
    ECIDictHand dict;
    const char *k, *s_say;
    size_t kn, sn;
    int answer;
    (void)cls;
    if (!s || !key || !say)
        return -1;
    e = eq_extra_get(s->handle);
    if (!e || !eq_dict_ensure(s->handle, e)) {
        if (e)
            eq_extra_release(e);
        return -1;
    }
    dict = eq_extra_dict_snapshot(e);
    eq_extra_release(e);
    if (!dict)
        return -1;
    k = (*env)->GetStringUTFChars(env, key, NULL);
    s_say = (*env)->GetStringUTFChars(env, say, NULL);
    if (!k || !s_say) {
        if (k)
            (*env)->ReleaseStringUTFChars(env, key, k);
        if (s_say)
            (*env)->ReleaseStringUTFChars(env, say, s_say);
        return -1;
    }
    kn = strlen(k);
    sn = strlen(s_say);
    /* Optimized: stack allocation for small pairs */
    char stack_pair[256];
    char *pair = (kn + sn + 2 <= 256) ? stack_pair
                                       : (char *)malloc(kn + sn + 2);
    if (!pair) {
        (*env)->ReleaseStringUTFChars(env, key, k);
        (*env)->ReleaseStringUTFChars(env, say, s_say);
        return -1;
    }
    memcpy(pair, k, kn + 1);
    memcpy(pair + kn + 1, s_say, sn + 1);
    answer = eciUpdateDict(s->handle, dict, (int)volume, pair, pair + kn + 1);
    if (pair != stack_pair)
        free(pair);
    (*env)->ReleaseStringUTFChars(env, key, k);
    (*env)->ReleaseStringUTFChars(env, say, s_say);
    return (jint)answer;
}

JNIEXPORT jstring JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamDictLookup(JNIEnv *env, jclass cls,
                                                            jlong shandle, jint volume,
                                                            jstring key)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    eq_extra *e;
    ECIDictHand dict;
    const char *k;
    const char *found;
    jstring answer;
    (void)cls;
    if (!s || !key)
        return NULL;
    e = eq_extra_get(s->handle);
    if (!e)
        return NULL;
    dict = eq_extra_dict_snapshot(e);
    eq_extra_release(e);
    if (!dict)
        return NULL;
    k = (*env)->GetStringUTFChars(env, key, NULL);
    if (!k)
        return NULL;
    found = eciDictLookup(s->handle, dict, (int)volume, k);
    answer = found ? (*env)->NewStringUTF(env, found) : NULL;
    (*env)->ReleaseStringUTFChars(env, key, k);
    return answer;
}

JNIEXPORT void JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamDictForget(JNIEnv *env, jclass cls,
                                                            jlong shandle)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    eq_extra *e;
    ECIDictHand dict;
    (void)env;
    (void)cls;
    if (!s)
        return;
    e = eq_extra_get(s->handle);
    if (!e)
        return;
    pthread_mutex_lock(&eq_extra_lock);
    dict = e->dict;
    e->dict = NULL_DICT_HAND;
    pthread_mutex_unlock(&eq_extra_lock);
    eq_extra_release(e);
    if (!dict)
        return;
    eciSetDict(s->handle, NULL_DICT_HAND);
    eciDeleteDict(s->handle, dict);
}

/* ========================================================================
 * Phoneme Generation
 * ======================================================================== */

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeGeneratePhonemes
 * Signature: (JLjava/lang/String;)[B
 *
 * Generates phoneme data for the given text. Returns byte array with
 * phoneme codes, or null on failure.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeGeneratePhonemes(JNIEnv *env, jclass cls,
                                                             jlong handle, jstring text)
{
    ECIHand h = (ECIHand)(intptr_t)handle;
    const char *utf8 = NULL;
    jbyteArray out = NULL;
    (void)cls;
    if (!h || !text)
        return NULL;
    utf8 = (*env)->GetStringUTFChars(env, text, NULL);
    if (!utf8)
        return NULL;
    if (!eq_utf8_valid(utf8, strlen(utf8))) {
        (*env)->ReleaseStringUTFChars(env, text, utf8);
        return NULL;
    }
    if (!eciAddText(h, utf8)) {
        (*env)->ReleaseStringUTFChars(env, text, utf8);
        return NULL;
    }
    /* eciGeneratePhonemes needs a buffer; allocate a reasonable size */
    char phoneme_buf[4096];
    if (eciGeneratePhonemes(h, (int)sizeof(phoneme_buf), phoneme_buf) > 0) {
        size_t len = strlen(phoneme_buf);
        out = (*env)->NewByteArray(env, (jsize)len);
        if (out)
            (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)phoneme_buf);
    }
    (*env)->ReleaseStringUTFChars(env, text, utf8);
    return out;
}

/* ========================================================================
 * Stream Control (Voice, Rate, Dictionary)
 * ======================================================================== */

/* ========================================================================
 * Audio Format Query
 * ======================================================================== */

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeGetAudioFormat
 * Signature: (J)[I
 *
 * Returns int array: [sampleRate, channels, bitsPerSample]
 */
JNIEXPORT jintArray JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeGetAudioFormat(JNIEnv *env, jclass cls,
                                                           jlong handle)
{
    jintArray out;
    jint format[3];
    int rate_idx;
    (void)cls;
    if (!(ECIHand)(intptr_t)handle)
        return NULL;
    rate_idx = eciGetParam((ECIHand)(intptr_t)handle, 5 /* P_SAMPLE_RATE */);
    if (rate_idx < 0 || rate_idx >= EQ_RATES)
        rate_idx = 1;
    format[0] = eq_rate_hz[rate_idx];
    format[1] = 1;  // mono
    format[2] = 16; // 16-bit
    out = (*env)->NewIntArray(env, 3);
    if (out)
        (*env)->SetIntArrayRegion(env, out, 0, 3, format);
    return out;
}

/* ========================================================================
 * Voice Information
 * ======================================================================== */

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeGetVoiceName
 * Signature: (JI)Ljava/lang/String;
 *
 * Returns the name of a voice preset (1-8), or null.
 */
JNIEXPORT jstring JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeGetVoiceName(JNIEnv *env, jclass cls,
                                                         jlong handle, jint voice)
{
    (void)env;
    (void)cls;
    if (!(ECIHand)(intptr_t)handle || voice < 1 || voice > 8)
        return NULL;
    /* Voice names are fixed in the engine */
    static const char *voice_names[] = {
        "Reed", "Bobby", "Grandma", "Grandpa",
        "Kathy", "Princess", "Huge", "Tiny"
    };
    return (*env)->NewStringUTF(env, voice_names[voice - 1]);
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeGetVoiceGender
 * Signature: (JI)I
 *
 * Returns gender: 0=neutral, 1=male, 2=female
 */
JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeGetVoiceGender(JNIEnv *env, jclass cls,
                                                           jlong handle, jint voice)
{
    (void)env;
    (void)cls;
    if (!(ECIHand)(intptr_t)handle || voice < 1 || voice > 8)
        return 0;
    static const int voice_gender[] = { 1, 1, 2, 1, 2, 2, 1, 1 };
    return voice_gender[voice - 1];
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeGetVoiceAge
 * Signature: (JI)I
 *
 * Returns approximate age for voice preset.
 */
JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeGetVoiceAge(JNIEnv *env, jclass cls,
                                                        jlong handle, jint voice)
{
    (void)env;
    (void)cls;
    if (!(ECIHand)(intptr_t)handle || voice < 1 || voice > 8)
        return 0;
    static const int voice_age[] = { 30, 10, 70, 75, 25, 8, 40, 5 };
    return voice_age[voice - 1];
}

/* ========================================================================
 * Prosody / Pause / Pacing / Abbreviation Control
 * ========================================================================
 *
 * These store configuration in the stream struct.  The Java side reads them
 * to prepare annotation prefixes (`vs`, `vb`, `pp`, `ap`) because the
 * engine refuses eciSetParam while speaking (evvdroid's hard-won lesson).
 */

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeStreamSetPauseMode
 * Signature: (JI)V
 */
JNIEXPORT void JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamSetPauseMode(JNIEnv *env, jclass cls,
                                                               jlong shandle, jint mode)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    (void)env;
    (void)cls;
    if (s) s->pause_mode = (int)mode;
}

JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamGetPauseMode(JNIEnv *env, jclass cls,
                                                               jlong shandle)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    (void)env;
    (void)cls;
    return s ? (jint)s->pause_mode : 0;
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeStreamSetPhrasePrediction
 * Signature: (JI)V
 */
JNIEXPORT void JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamSetPhrasePrediction(JNIEnv *env, jclass cls,
                                                                      jlong shandle, jint on)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    (void)env;
    (void)cls;
    if (s) s->phrase_prediction = (int)on;
}

JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamGetPhrasePrediction(JNIEnv *env, jclass cls,
                                                                      jlong shandle)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    (void)env;
    (void)cls;
    return s ? (jint)s->phrase_prediction : 0;
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeStreamSetAbbreviations
 * Signature: (JI)V
 */
JNIEXPORT void JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamSetAbbreviations(JNIEnv *env, jclass cls,
                                                                   jlong shandle, jint on)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    (void)env;
    (void)cls;
    if (s) s->abbreviations = (int)on;
}

JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamGetAbbreviations(JNIEnv *env, jclass cls,
                                                                   jlong shandle)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    (void)env;
    (void)cls;
    return s ? (jint)s->abbreviations : 1;
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeStreamSetSpeed
 * Signature: (JI)V
 *
 * Store a speed override in the stream for paced synthesis.  -1 means "use
 * whatever the voice has".
 */
JNIEXPORT void JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamSetSpeed(JNIEnv *env, jclass cls,
                                                           jlong shandle, jint speed)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    (void)env;
    (void)cls;
    if (s) s->speed = (int)speed;
}

JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamGetSpeed(JNIEnv *env, jclass cls,
                                                           jlong shandle)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    (void)env;
    (void)cls;
    return s ? (jint)s->speed : -1;
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeStreamSetPitch
 * Signature: (JI)V
 */
JNIEXPORT void JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamSetPitch(JNIEnv *env, jclass cls,
                                                           jlong shandle, jint pitch)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    (void)env;
    (void)cls;
    if (s) s->pitch = (int)pitch;
}

JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamGetPitch(JNIEnv *env, jclass cls,
                                                           jlong shandle)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    (void)env;
    (void)cls;
    return s ? (jint)s->pitch : -1;
}

JNIEXPORT void JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamSetLeadMs(JNIEnv *env, jclass cls,
                                                            jlong shandle, jint ms)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    (void)env;
    (void)cls;
    if (s && ms >= 0) s->lead_ms = (int)ms;
}

JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamGetLeadMs(JNIEnv *env, jclass cls,
                                                            jlong shandle)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    (void)env;
    (void)cls;
    return s ? (jint)s->lead_ms : 300;
}

/* ========================================================================
 * Dictionary File Loading (ISO-8859-1 tab-separated text files)
 * ========================================================================
 *
 * evvdroid's dictionary files are plain text: one "key<TAB>say" per line,
 * ISO-8859-1 encoded.  This parses them in C for speed (large user
 * dictionaries can have thousands of entries) and feeds each pair to the
 * engine through eciAddText with annotation markers.
 *
 * The file format:
 *   - Lines starting with '#' are comments
 *   - Empty lines are skipped
 *   - Each entry is: key<TAB>pronunciation
 *   - Encoding is ISO-8859-1 (Latin-1)
 */

/* Read a whole file into a malloc'd buffer.  Returns NULL on error. */
static char *eq_read_file(const char *path, size_t *out_len)
{
    FILE *f;
    long len;
    char *buf;
    size_t n;
    f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    len = ftell(f);
    if (len <= 0 || len > 4 * 1024 * 1024) { fclose(f); return NULL; }
    rewind(f);
    buf = (char *)malloc((size_t)len + 1);
    if (!buf) { fclose(f); return NULL; }
    n = fread(buf, 1, (size_t)len, f);
    fclose(f);
    buf[n] = '\0';
    if (out_len) *out_len = n;
    return buf;
}

/*
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeStreamDictLoadFile
 * Signature: (JLjava/lang/String;)I
 *
 * Load a tab-separated text dictionary file into the stream's engine
 * instance.  Returns the number of entries loaded, or -1 on error.
 */
JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamDictLoadFile(JNIEnv *env, jclass cls,
                                                               jlong shandle, jstring path)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    const char *cpath;
    char *buf;
    size_t len;
    int count = 0;
    char stored_path[1024];
    (void)cls;
    if (!s || !path) return -1;
    cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (!cpath) return -1;
    /* Copy the path before releasing JNI string */
    strncpy(stored_path, cpath, sizeof(stored_path) - 1);
    stored_path[sizeof(stored_path) - 1] = '\0';
    buf = eq_read_file(cpath, &len);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    if (!buf) return -1;

    /* Parse line by line: key\tpronunciation
     * Optimized: stack-allocated buffer for small entries, single malloc for large */
    char *line = buf;
    while (*line) {
        char *eol;
        char *tab;
        /* skip BOM on first line */
        if ((unsigned char)line[0] == 0xEF && (unsigned char)line[1] == 0xBB
                && (unsigned char)line[2] == 0xBF) {
            line += 3;
            continue;
        }
        eol = strchr(line, '\n');
        if (!eol) eol = line + strlen(line);
        /* trim \r */
        if (eol > line && *(eol - 1) == '\r') eol--;
        if (eol == line) { line = eol + 1; continue; }
        /* skip comments */
        if (*line == '#') { line = (*eol) ? eol + 1 : eol; continue; }
        tab = memchr(line, '\t', (size_t)(eol - line));
        if (tab && tab > line) {
            size_t klen = (size_t)(tab - line);
            size_t slen = (size_t)(eol - tab - 1);
            /* Build a key\0say pair for eciAddText annotation:
               `dkey\0say` -- but we add them via the annotation path.
               Optimized: use stack buffer for entries <= 256 bytes total. */
            char stack_pair[256];
            char *pair = (klen + slen + 2 <= 256) ? stack_pair
                                                   : (char *)malloc(klen + slen + 2);
            if (pair) {
                memcpy(pair, line, klen);
                pair[klen] = '\0';
                memcpy(pair + klen + 1, tab + 1, slen);
                pair[klen + 1 + slen] = '\0';
                /* Use eciAddText to teach the engine the pronunciation.
                   The `d annotation adds to the user dictionary. */
                eciAddText(s->handle, "`d");
                eciAddText(s->handle, pair);
                if (pair != stack_pair)
                    free(pair);
                count++;
            }
        }
        line = (*eol) ? eol + 1 : eol;
    }

    /* Store the path for reload-on-reconnect */
    if (s->dict_count < EQ_MAX_DICTS) {
        char *stored = strdup(stored_path);
        if (stored) {
            free(s->dict_paths[s->dict_count]);
            s->dict_paths[s->dict_count] = stored;
            s->dict_count++;
        }
    }
    free(buf);
    return (jint)count;
}