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
 * Threading: the engine calls the waveform callback on its own synthesis
 * thread. The callback only appends samples and returns; it never calls back
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

    /* The bind: reading the registry is what binds it. */
    if (eciGetAvailableLanguages(langs, &n) == 0 || n < 1)
        return (ECIHand)0;
    if (language_or_zero == 0)
        return eciNewEx((int)langs[0]);
    for (k = 0; k < n; k++) {
        if ((int)langs[k] == language_or_zero)
            return eciNewEx(language_or_zero);
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
    if (eciGetAvailableLanguages(langs, &n) == 0 || n < 1) {
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
