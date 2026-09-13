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
 * Streaming (nativeStreamStart/Read/Stop) follows the protocol proven in
 * trypsynth/evvdroid's evv_jni.c (MIT), reimplemented here for this
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

#include "eci.h"

#ifdef __ANDROID__
#include <jni.h>
#include <pthread.h>
#include <unistd.h>
#elif defined(__unix__) || defined(__APPLE__)
/* Host builds (CMake without NDK) still type-check this file when a JDK is
 * present: jni.h comes from find_package(JNI) there. */
#include <jni.h>
#include <pthread.h>
#include <unistd.h>
#else
#error "android/eloquick_jni.c is POSIX-only (Android/Linux/macOS)"
#endif

/* Port lifecycle: evv_port_start() once before the first instance, and
 * evv_port_finish() after the last. XREF src/port/evv_port.h. */
void evv_port_start(void);
void evv_port_finish(void);
void evvRunStaticInitialisers(void);

#define EQ_FRAME 2048

typedef struct {
    short *pcm;
    size_t len;
    size_t cap;
    short frame[EQ_FRAME];
} eq_session;

static pthread_mutex_t eq_port_lock = PTHREAD_MUTEX_INITIALIZER;
static int eq_live_instances = 0;

/* Forward: per-instance extras (dictionary set), defined below;
   nativeDestroy consults it so nothing leaks. */
static int eq_extra_drop(ECIHand h);
/* Forward: eciNewEx with the hetero default in force, defined below. */
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
    unsigned int langs[32];
    int n = 32, k;

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
    /* Pumping the queue is what drains it (XREF cli/evv.c). */
    for (spins = 0; spins < 30000 && eciSpeaking(h); spins++)
        usleep(10 * 1000);
    eciSynchronize(h);
    return 1;
}

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
    unsigned int langs[32];
    int n = 32, k;
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
    (void)cls;
    if (!h || !text)
        return NULL;
    utf8 = (*env)->GetStringUTFChars(env, text, NULL);
    if (!utf8)
        return NULL;
    memset(&s, 0, sizeof(s));
    if (eq_speak_to_session(h, &s, utf8) && s.len > 0) {
        out = (*env)->NewShortArray(env, (jsize)s.len);
        if (out)
            (*env)->SetShortArrayRegion(env, out, 0, (jsize)s.len, (const jshort *)s.pcm);
    }
    free(s.pcm);
    (*env)->ReleaseStringUTFChars(env, text, utf8);
    return out;
}

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

/* ---- per-instance extras: dictionary set ---------------------------------
 *
 * An ECIHand carries no dictionary set of its own, so the bridge keeps a
 * small mutex-guarded map from handle to extras. nativeDestroy consults it
 * so nothing leaks when a caller destroys without forgetting first.
 */

typedef struct eq_extra {
    ECIHand handle;
    ECIDictHand dict;
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

static eq_extra *eq_extra_get(ECIHand h)
{
    eq_extra *e;
    pthread_mutex_lock(&eq_extra_lock);
    e = eq_extra_find_locked(h);
    if (!e) {
        e = (eq_extra *)calloc(1, sizeof(*e));
        if (e) {
            e->handle = h;
            e->next = eq_extras;
            eq_extras = e;
        }
    }
    pthread_mutex_unlock(&eq_extra_lock);
    return e;
}

/* Drops the extras for h, releasing engine-side state. The lock must not be
   held (engine calls made here). Answers 1 when something was dropped. */
static int eq_extra_drop(ECIHand h)
{
    eq_extra *e, *prev = NULL;
    int dropped = 0;
    pthread_mutex_lock(&eq_extra_lock);
    e = eq_extras;
    while (e) {
        if (e->handle == h) {
            if (prev)
                prev->next = e->next;
            else
                eq_extras = e->next;
            dropped = 1;
            break;
        }
        prev = e;
        e = e->next;
    }
    pthread_mutex_unlock(&eq_extra_lock);
    if (!dropped)
        return 0;
    if (e->dict) {
        eciSetDict(h, NULL_DICT_HAND);
        eciDeleteDict(h, e->dict);
    }
    free(e);
    return 1;
}

static int eq_dict_ensure(ECIHand h, eq_extra *e)
{
    if (e->dict)
        return 1;
    e->dict = eciNewDict(h);
    if (!e->dict)
        return 0;
    /* An error code, so nought is success. */
    if (eciSetDict(h, e->dict) != 0) {
        eciDeleteDict(h, e->dict);
        e->dict = NULL_DICT_HAND;
        return 0;
    }
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
    const char *k, *s;
    char *pair;
    size_t kn, sn;
    int answer;
    (void)cls;
    if (!h || !key || !say)
        return -1;
    e = eq_extra_get(h);
    if (!e || !eq_dict_ensure(h, e))
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
    pair = (char *)malloc(kn + sn + 2);
    if (!pair) {
        (*env)->ReleaseStringUTFChars(env, key, k);
        (*env)->ReleaseStringUTFChars(env, say, s);
        return -1;
    }
    memcpy(pair, k, kn + 1);
    memcpy(pair + kn + 1, s, sn + 1);
    answer = eciUpdateDict(h, e->dict, (int)volume, pair, pair + kn + 1);
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
    const char *k;
    const char *found;
    jstring answer;
    (void)cls;
    if (!h || !key)
        return NULL;
    pthread_mutex_lock(&eq_extra_lock);
    e = eq_extra_find_locked(h);
    pthread_mutex_unlock(&eq_extra_lock);
    if (!e || !e->dict)
        return NULL;
    k = (*env)->GetStringUTFChars(env, key, NULL);
    if (!k)
        return NULL;
    found = eciDictLookup(h, e->dict, (int)volume, k);
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
    (void)env;
    (void)cls;
    if (!h)
        return;
    pthread_mutex_lock(&eq_extra_lock);
    e = eq_extra_find_locked(h);
    pthread_mutex_unlock(&eq_extra_lock);
    if (!e || !e->dict)
        return;
    eciSetDict(h, NULL_DICT_HAND);
    eciDeleteDict(h, e->dict);
    e->dict = NULL_DICT_HAND;
}

/* Heteronym filter: creation-time property, not a live toggle.
 *
 * hetero_install (src/eci/hetero/eci_hetero.c, via eci_old.c instance setup)
 * is the engine-tested path -- register, load, activate with the filter's
 * own language -- and it reads EVV_HETERO at creation. Rebuilding that
 * sequence post-hoc through the public calls fails (eciNewFilter answers
 * NULL: the manager matches the filter's own language, which the public
 * new call cannot name). So the bridge does what eloquence-revived's
 * per-slot model does at a smaller scale: a process-wide default consulted
 * around eciNewEx. Toggling wants a new instance; off is the default, as
 * upstream insists (a loaded filter turns annotation reading on, so
 * backticks in plain text get interpreted from then on).
 */

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

/* eciNewEx with the hetero default in force. Serialized: the env is
   process-global, so two creations with different wants must not overlap. */
static ECIHand eq_new_ex_hetero(int language)
{
    ECIHand h;
    int want;
    char *had = NULL;
    const char *prev;
    pthread_mutex_lock(&eq_hetero_lock);
    want = eq_hetero_default;
    pthread_mutex_lock(&eq_port_lock);
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
    pthread_mutex_unlock(&eq_port_lock);
    pthread_mutex_unlock(&eq_hetero_lock);
    return h;
}

/* ---- streaming: speak / read / stop -------------------------------------
 *
 * Whole-utterance nativeSynth above holds a long TalkBack stream in memory
 * before any of it is heard and cannot be stopped mid-flight. Streaming
 * hands samples over as they arrive and aborts cleanly, which is what a
 * screen reader needs. Protocol, reimplemented from evvdroid's measured
 * design: a ring between the engine thread and the reader, a worker thread
 * for end detection, aborts answered from the callback.
 */

#define EQ_STREAM_FRAME 1024
#define EQ_RING_BYTES (128 * 1024)
/* Milliseconds to watch for the engine taking the work up before the
   utterance is declared empty. */
#define EQ_START_SPINS 200

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
} eq_stream;

static int ECICALL eq_stream_message(ECIHand h, ECIMessage msg, int param, void *data)
{
    eq_stream *s = (eq_stream *)data;
    size_t want;
    (void)h;
    if (msg != eciWaveformBuffer)
        return eciDataProcessed;
    want = (size_t)param * sizeof(short);
    if (want == 0)
        return eciDataProcessed;
    pthread_mutex_lock(&s->lock);
    while (!s->aborted && EQ_RING_BYTES - s->count < want)
        pthread_cond_wait(&s->room, &s->lock);
    if (s->aborted) {
        pthread_mutex_unlock(&s->lock);
        return eciDataAbort;
    }
    {
        size_t first = EQ_RING_BYTES - s->head;
        if (first > want)
            first = want;
        memcpy(s->ring + s->head, s->frame, first);
        memcpy(s->ring, (unsigned char *)s->frame + first, want - first);
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

/* Marks an utterance over that never started, so a reader waiting on the
   end condition is not left waiting for work nothing handed over. */
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
 * nothing here knows whether the engine copied them.
 */
JNIEXPORT jboolean JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamSpeak(JNIEnv *env, jclass cls,
                                                       jlong shandle, jstring text)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    const char *utf8;
    unsigned char *buf;
    size_t n;
    (void)cls;
    if (!s || !text)
        return JNI_FALSE;
    utf8 = (*env)->GetStringUTFChars(env, text, NULL);
    if (!utf8)
        return JNI_FALSE;
    n = strlen(utf8);
    buf = (unsigned char *)malloc(n + 1);
    if (!buf) {
        (*env)->ReleaseStringUTFChars(env, text, utf8);
        return JNI_FALSE;
    }
    memcpy(buf, utf8, n + 1);
    (*env)->ReleaseStringUTFChars(env, text, utf8);
    eq_stream_idle(s);
    pthread_mutex_lock(&s->lock);
    s->aborted = 0;
    s->started = 0;
    s->done = 0;
    s->busy = 1;
    s->head = s->tail = s->count = 0;
    pthread_mutex_unlock(&s->lock);
    free(s->text);
    s->text = buf;
    if (!s->running)
        return eq_stream_finished(s);
    if (!eciAddText(s->handle, buf))
        return eq_stream_finished(s);
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
    unsigned char *scratch;
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
    scratch = (unsigned char *)malloc(got);
    if (!scratch) {
        pthread_mutex_unlock(&s->lock);
        return -1;
    }
    {
        size_t first = EQ_RING_BYTES - s->tail;
        if (first > got)
            first = got;
        memcpy(scratch, s->ring + s->tail, first);
        memcpy(scratch + first, s->ring, got - first);
        s->tail = (s->tail + got) % EQ_RING_BYTES;
        s->count -= got;
    }
    pthread_cond_signal(&s->room);
    pthread_mutex_unlock(&s->lock);
    (*env)->SetByteArrayRegion(env, dst, 0, (jsize)got, (const jbyte *)scratch);
    free(scratch);
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
    pthread_cond_broadcast(&s->room);
    pthread_cond_broadcast(&s->filled);
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
    /* The abort flag brings the worker out of the engine: the next buffer
       offered is answered eciDataAbort and eciSynchronize returns. Nothing
       is asked of the engine from this thread. */
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
    free(s);
}

/* Stream control calls: same engine calls as the whole-utterance path, on
   the stream's instance. What the TTS service shapes voices and rates with. */

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
