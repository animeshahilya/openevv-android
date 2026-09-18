/* Eloquence JNI bridge: whole-utterance synthesis for
 * com.eloquick.tts.EloquenceNative (libopenevv_jni.so).
 *
 * A separate bridge from android/eloquick_jni.c, not a copy of anything:
 * that bridge speaks the EloQuick streaming protocol (ring + worker +
 * per-handle sessions for com.eloquick.tts.EloQuickEngine), while this one
 * speaks EloquenceNative's whole-utterance protocol (one call per chunk,
 * audio back through AudioConsumer, sentence marks through onIndexMark).
 * Shared engine truths are honored the same way rather than relearned:
 *
 *  - Bind-then-create: eciGetAvailableLanguages() binds the language
 *    registry internally, so this bridge ALWAYS queries the table before
 *    eciNewEx() - never eciNewEx() on a cold registry, which is how every
 *    voice in every language once spoke US English (XREF eloquick_jni.c,
 *    Eagalon/openevv SAPI lesson). Nonzero from eciGetAvailableLanguages
 *    is failure (XREF cli/evv.c), not success.
 *  - Hetero is a creation-time property: EVV_HETERO=on in the environment
 *    around eciNewEx, serialized, same as eloquick_jni.c.
 *  - Stops go through eciDataAbort from the callback - never eciStop, which
 *    empirically kills instances after ~80 calls.
 *  - The callback runs on the engine's synthesis thread: it copies samples
 *    and calls back into Java via an attached JNIEnv, but never calls back
 *    into the same ECI instance (the header forbids it).
 *  - eciDictionary is inverted: 1 turns the dictionary off (eci.h), so the
 *    abbreviation flag maps on->0/off->1 (NVDA-IBMTTS-Driver ABRDICT).
 *  - Sample rates >= 8000 are hertz directly (docs/api.md); the Java side
 *    resolves 0 to 22050 and this side keeps >0-else-11025 as last resort.
 *  - Phonemes arrive through eciPhonemeBuffer with synth mode 1 and text
 *    already added (docs/api.md); backspace formatting is stripped here.
 */

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include "eci.h"

#ifdef __ANDROID__
#include <jni.h>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
#elif defined(__unix__) || defined(__APPLE__)
#include <jni.h>
#include <pthread.h>
#include <unistd.h>
#else
#error "android/eloquence_jni.c is POSIX-only (Android/Linux/macOS)"
#endif

#ifdef __ANDROID__
#define LOG_TAG "EloquenceNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) fprintf(stderr, "I: " __VA_ARGS__)
#define LOGW(...) fprintf(stderr, "W: " __VA_ARGS__)
#define LOGE(...) fprintf(stderr, "E: " __VA_ARGS__)
#endif

/* ---- Constants shared with eloquick_jni.c ------------------------------- */

#define EQ_FRAME 2048
#define EQ_INDEX_QUEUE 16
#define EQ_MAX_TEXT_BYTES (64 * 1024)
#define EQ_FRAME 2048
#define EVN_MAX_TEXT_BYTES (64 * 1024)
#define EVN_FRAME 2048
#define EVN_DRAIN_SPINS 3000
#define EVN_DRAIN_SLEEP_US (10 * 1000)
#define EVN_MAX_SLOTS 8
#define EVN_MAX_DICT_PATH 512

/* ---- Type definitions shared with eloquick_jni.c ---------------------- */

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
    short frame[EQ_FRAME];
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

typedef struct {
    ECIHand handle;
    ECIDictHand dict;
    int refs; /* guarded by eq_extra_lock; entry freed when refs==0 after unlink */
    struct eq_extra *next;
} eq_extra;

/* Port lifecycle (XREF src/port/evv_port.h). */
void evv_port_start(void);
void evvRunStaticInitialisers(void);

/* ---- eq_extra management (shared with eloquick_jni.c) ----------------- */

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

/* Ensure a dictionary exists for the slot. */
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

/* Same hardening bounds as the sibling bridge: whole-utterance synthesis
 * holds one chunk in RAM; long text belongs in caller-side chunks (the
 * service already splits via chunkRangesForSynthesis). */
#define EVN_MAX_TEXT_BYTES (64 * 1024)
#define EVN_FRAME 2048
#define EVN_DRAIN_SPINS 3000
#define EVN_DRAIN_SLEEP_US (10 * 1000)
#define EVN_MAX_SLOTS 8
#define EVN_MAX_DICT_PATH 512

/* ---- one-time init -------------------------------------------------- */

static pthread_once_t evn_init_once = PTHREAD_ONCE_INIT;
static pthread_mutex_t evn_port_lock = PTHREAD_MUTEX_INITIALIZER;
static int evn_port_started = 0;

static void evn_bind_registry(void)
{
    unsigned int langs[64];
    int n = 64;

    /* The bind: reading the registry is what binds it; the result is
     * discarded - this call's only job is the side effect. */
    (void)eciGetAvailableLanguages(langs, &n);

    pthread_mutex_lock(&evn_port_lock);
    if (!evn_port_started) {
        evv_port_start();
        evvRunStaticInitialisers();
        evn_port_started = 1;
    }
    pthread_mutex_unlock(&evn_port_lock);
}

/* ---- hetero at creation time ---------------------------------------- */

static pthread_mutex_t evn_hetero_env_lock = PTHREAD_MUTEX_INITIALIZER;

static ECIHand evn_new_ex_hetero(int language, int hetero)
{
    ECIHand h;
    char *had = NULL;
    const char *prev;
    pthread_mutex_lock(&evn_hetero_env_lock);
    if (hetero) {
        /* strdup before setenv: the getenv pointer dangles once the
         * environment is replaced (same discipline as eloquick_jni.c). */
        prev = getenv("EVV_HETERO");
        if (prev) {
            had = (char *)malloc(strlen(prev) + 1);
            if (had)
                strcpy(had, prev);
        }
        setenv("EVV_HETERO", "on", 1);
    }
    h = eciNewEx(language);
    if (hetero) {
        if (had) {
            setenv("EVV_HETERO", had, 1);
            free(had);
        } else {
            unsetenv("EVV_HETERO");
        }
    }
    pthread_mutex_unlock(&evn_hetero_env_lock);
    return h;
}

/* Bind-then-create. Returns NULL when the language is absent. */
static ECIHand evn_create_for_language(int language, int hetero)
{
    unsigned int langs[64];
    int n = 64, k;

    /* The bind: reading the registry is what binds it; the result is
     * discarded - this call's only job is the side effect. */
    if (eciGetAvailableLanguages(langs, &n) != 0 || n < 1)
        return (ECIHand)0;
    for (k = 0; k < n; k++) {
        if ((int)langs[k] == language)
            return evn_new_ex_hetero(language, hetero);
    }
    return (ECIHand)0;
}

/* ---- slot cache ------------------------------------------------------
 * One engine instance per (language, hetero, ssml) triple; dictionary
 * paths are per-slot state, reloaded only on change. Whole utterances
 * serialize on evn_call_lock - TalkBack stops the previous chunk before
 * starting the next, and concurrent binder threads must never share one
 * ECIHand. */

typedef struct {
    int in_use;
    int lang_id;
    int hetero;
    int ssml;
    ECIHand h;
    ECIDictHand dict;
    char dict_path[EVN_MAX_DICT_PATH];
    char abbv_path[EVN_MAX_DICT_PATH];
} evn_slot;

/* Wait for the slot's engine to go idle (bounded). False = still busy. */
static int evn_slot_idle(evn_slot *s)
{
    int spins;
    for (spins = 0; spins < EVN_DRAIN_SPINS && eciSpeaking(s->h); spins++)
        usleep(EVN_DRAIN_SLEEP_US);
    return !eciSpeaking(s->h);
}

static evn_slot evn_slots[EVN_MAX_SLOTS];
static pthread_mutex_t evn_slots_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t evn_call_lock = PTHREAD_MUTEX_INITIALIZER;

static void evn_slot_destroy(evn_slot *s)
{
    if (s->dict != NULL_DICT_HAND) {
        eciSetDict(s->h, NULL_DICT_HAND);
        eciDeleteDict(s->h, s->dict);
        s->dict = NULL_DICT_HAND;
    }
    if (s->h != (ECIHand)0) {
        eciDelete(s->h);
        s->h = (ECIHand)0;
    }
    s->in_use = 0;
    s->dict_path[0] = '\0';
    s->abbv_path[0] = '\0';
}

/* Find the slot for this triple, creating (bind-then-create) on miss.
 * Table full reclaims slot 0 rather than failing speech. */
static evn_slot *evn_find_or_make_slot(int lang_id, int hetero, int ssml)
{
    int i, free_idx = -1;

    pthread_mutex_lock(&evn_slots_lock);
    for (i = 0; i < EVN_MAX_SLOTS; i++) {
        if (evn_slots[i].in_use && evn_slots[i].lang_id == lang_id
                && evn_slots[i].hetero == hetero && evn_slots[i].ssml == ssml) {
            pthread_mutex_unlock(&evn_slots_lock);
            return &evn_slots[i];
        }
        if (!evn_slots[i].in_use && free_idx < 0)
            free_idx = i;
    }
    if (free_idx < 0) {
        /* Table full: reclaim the first slot rather than failing speech. */
        evn_slot_destroy(&evn_slots[0]);
        free_idx = 0;
    }
    {
        evn_slot *s = &evn_slots[free_idx];
        ECIHand h = evn_create_for_language(lang_id, hetero);
        if (h == (ECIHand)0) {
            pthread_mutex_unlock(&evn_slots_lock);
            return NULL;
        }
        s->h = h;
        s->dict = eciNewDict(h);
        if (s->dict == NULL_DICT_HAND || eciSetDict(h, s->dict) != 0) {
            evn_slot_destroy(s);
            pthread_mutex_unlock(&evn_slots_lock);
            return NULL;
        }
        eciSetParam(h, 1 /* P_INPUT_TYPE */, 1); /* annotations on */
        s->in_use = 1;
        s->lang_id = lang_id;
        s->hetero = hetero;
        s->ssml = ssml;
        s->dict_path[0] = '\0';
        s->abbv_path[0] = '\0';
        pthread_mutex_unlock(&evn_slots_lock);
        return s;
    }
}

/* Reload user dictionaries only when a path changed since the last call
 * for this slot: same paths every call is the expected cheap case. */
static void evn_apply_dictionaries(evn_slot *s, const char *dict_path,
        const char *abbv_path)
{
    int same_main = (dict_path == NULL && s->dict_path[0] == '\0')
            || (dict_path != NULL && strcmp(dict_path, s->dict_path) == 0);
    int same_abbv = (abbv_path == NULL && s->abbv_path[0] == '\0')
            || (abbv_path != NULL && strcmp(abbv_path, s->abbv_path) == 0);
    if (same_main && same_abbv)
        return;
    /* Changed: reset the hand so stale entries cannot linger, then load
     * whichever volumes were handed over. */
    eciSetDict(s->h, NULL_DICT_HAND);
    eciDeleteDict(s->h, s->dict);
    s->dict = eciNewDict(s->h);
    if (s->dict == NULL_DICT_HAND)
        return;
    eciSetDict(s->h, s->dict);
    if (dict_path != NULL && dict_path[0] != '\0')
        eciLoadDict(s->h, s->dict, 0 /* eciMainDict */, dict_path);
    if (abbv_path != NULL && abbv_path[0] != '\0')
        eciLoadDict(s->h, s->dict, 2 /* eciAbbvDict */, abbv_path);
    if (dict_path != NULL) {
        strncpy(s->dict_path, dict_path, EVN_MAX_DICT_PATH - 1);
        s->dict_path[EVN_MAX_DICT_PATH - 1] = '\0';
    } else {
        s->dict_path[0] = '\0';
    }
    if (abbv_path != NULL) {
        strncpy(s->abbv_path, abbv_path, EVN_MAX_DICT_PATH - 1);
        s->abbv_path[EVN_MAX_DICT_PATH - 1] = '\0';
    } else {
        s->abbv_path[0] = '\0';
    }
}

/* ---- per-call synthesis context ------------------------------------- */

typedef struct {
    JavaVM *vm;
    jobject consumer; /* global ref */
    jmethodID on_chunk;
    jmethodID on_mark;
    volatile int cancelled;
    short frame[EVN_FRAME];
    /* Byte->char mark map: eciIndexReply carries the byte offset handed to
     * eciInsertIndex, but AudioConsumer wants char offsets. Single-byte
     * code sets map 1:1; Polish UTF-8 multibyte runs are counted out via
     * UTF-8 lead bytes at insert time. */
#define EVN_MAX_MARKS 256
    int mark_bytes[EVN_MAX_MARKS];
    int mark_chars[EVN_MAX_MARKS];
    int mark_count;
} evn_call;

/* Byte offset -> char offset: counts UTF-8 lead bytes, so single-byte
 * code sets (all shipped languages except Polish UTF-8) map 1:1. */
static int evn_chars_before(const unsigned char *buf, size_t off)
{
    size_t i;
    int chars = 0;
    for (i = 0; i < off; i++) {
        if ((buf[i] & 0xC0) != 0x80)
            chars++;
    }
    return chars;
}

static JNIEnv *evn_attach(evn_call *c)
{
    JNIEnv *env = NULL;
    if ((*c->vm)->GetEnv(c->vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*c->vm)->AttachCurrentThread(c->vm, &env, NULL) != JNI_OK)
            return NULL;
    }
    return env;
}

static int ECICALL evn_on_message(ECIHand h, ECIMessage msg, int param, void *data)
{
    evn_call *c = (evn_call *)data;
    (void)h;
    if (c->cancelled)
        return eciDataAbort;
    if (msg == eciWaveformBuffer && param > 0) {
        JNIEnv *env = evn_attach(c);
        jbyteArray arr;
        if (env == NULL) {
            __android_log_print(ANDROID_LOG_ERROR, "EloquenceNative", "evn_attach returned NULL");
            return eciDataAbort;
        }
        /* Engine samples are 16-bit PCM; the consumer takes bytes. */
        arr = (*env)->NewByteArray(env, (jsize)(param * 2));
        if (arr == NULL) {
            __android_log_print(ANDROID_LOG_ERROR, "EloquenceNative", "NewByteArray failed");
            return eciDataAbort;
        }
        (*env)->SetByteArrayRegion(env, arr, 0, (jsize)(param * 2),
                (const jbyte *)c->frame);
        {
            jboolean go = (*env)->CallBooleanMethod(env, c->consumer,
                    c->on_chunk, arr, (jint)(param * 2));
            (*env)->DeleteLocalRef(env, arr);
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
                __android_log_print(ANDROID_LOG_ERROR, "EloquenceNative", "onAudioChunk threw exception");
                return eciDataAbort;
            }
            if (!go) {
                __android_log_print(ANDROID_LOG_WARN, "EloquenceNative", "onAudioChunk returned false (cancelled)");
                c->cancelled = 1;
                return eciDataAbort;
            }
        }
        return eciDataProcessed;
    }
    if (msg == eciIndexReply) {
        JNIEnv *env = evn_attach(c);
        int i, chars = param;
        if (env == NULL)
            return eciDataAbort;
        for (i = 0; i < c->mark_count; i++) {
            if (c->mark_bytes[i] == param) {
                chars = c->mark_chars[i];
                break;
            }
        }
        (*env)->CallVoidMethod(env, c->consumer, c->on_mark, (jint)chars);
        if ((*env)->ExceptionCheck(env))
            (*env)->ExceptionClear(env);
        return eciDataProcessed;
    }
    return eciDataProcessed;
}

/* Sentence ends for index marks: . ! ? followed by space or end.
 * Approximate per the AudioConsumer contract - not per-word. Offsets are
 * byte offsets into the fed buffer; the callback site converts to chars. */
static int evn_is_sentence_end(unsigned char c, unsigned char next)
{
    if (c != '.' && c != '!' && c != '?')
        return 0;
    return next == ' ' || next == '\t' || next == '\n' || next == '\r' || next == '\0';
}

/* Strip <...> tag spans in place (ssmlInput): the engine would otherwise
 * read the markup as words. */
static void evn_strip_tags(unsigned char *buf)
{
    unsigned char *r = buf, *w = buf;
    int in_tag = 0;
    while (*r) {
        if (*r == '<')
            in_tag = 1;
        else if (*r == '>')
            in_tag = 0;
        else if (!in_tag)
            *w++ = *r;
        r++;
    }
    *w = '\0';
}

static int evn_upsample_index(const char *name)
{
    if (name == NULL)
        return -1;
    if (strcmp(name, "sinc") == 0) return 0;
    if (strcmp(name, "cubic") == 0) return 1;
    if (strcmp(name, "linear") == 0) return 2;
    if (strcmp(name, "hold") == 0) return 3;
    if (strcmp(name, "zeros") == 0) return 4;
    if (strcmp(name, "none") == 0) return 5;
    return -1;
}

/* ---- JNI entry points -------------------------------------------------
 * Class: com.eloquick.tts.EloquenceNative. */

JNIEXPORT jboolean JNICALL
Java_com_eloquick_tts_EloquenceNative_nativeInit(JNIEnv *env, jobject thiz)
{
    (void)env;
    (void)thiz;
    pthread_once(&evn_init_once, evn_bind_registry);
    return JNI_TRUE;
}

JNIEXPORT jintArray JNICALL
Java_com_eloquick_tts_EloquenceNative_nativeGetLanguages(JNIEnv *env, jobject thiz)
{
    unsigned int langs[64];
    int n = 64, k;
    jintArray out;
    (void)thiz;
    pthread_once(&evn_init_once, evn_bind_registry);
    if (eciGetAvailableLanguages(langs, &n) != 0 || n < 1)
        return NULL;
    if (n > 64)
        n = 64;
    out = (*env)->NewIntArray(env, (jsize)n);
    if (out == NULL)
        return NULL;
    for (k = 0; k < n; k++)
        (*env)->SetIntArrayRegion(env, out, (jsize)k, 1, (const jint *)&langs[k]);
    return out;
}

/* Phoneme query on a throwaway instance: callback + synth mode 1 + text
 * already added, per docs/api.md. The sibling bridge calls
 * eciGeneratePhonemes with no callback plumbing at all and reads the buffer
 * it handed over - the phonemes land there, not in the callback's user
 * data - so this callback is a formality that only keeps talking.
 * Backspace formatting is stripped by the caller. */
static int ECICALL evn_phoneme_message(ECIHand h, ECIMessage msg, int param, void *data)
{
    (void)h;
    (void)msg;
    (void)param;
    (void)data;
    return eciDataProcessed;
}

JNIEXPORT jstring JNICALL
Java_com_eloquick_tts_EloquenceNative_nativeGeneratePhonemes(JNIEnv *env, jobject thiz,
        jint lang_id, jbyteArray text)
{
    ECIHand h;
    jbyte *bytes = NULL;
    jsize n = 0;
    unsigned char *buf = NULL;
    char phoneme_buf[4096];
    jstring answer = NULL;
    (void)thiz;
    pthread_once(&evn_init_once, evn_bind_registry);
    if (text == NULL)
        return NULL;
    n = (*env)->GetArrayLength(env, text);
    if (n < 1 || n > (jsize)EVN_MAX_TEXT_BYTES)
        return NULL;
    buf = (unsigned char *)malloc((size_t)n + 1);
    if (buf == NULL)
        return NULL;
    bytes = (*env)->GetByteArrayElements(env, text, NULL);
    if (bytes == NULL) {
        free(buf);
        return NULL;
    }
    memcpy(buf, bytes, (size_t)n);
    (*env)->ReleaseByteArrayElements(env, text, bytes, JNI_ABORT);
    buf[n] = '\0';

    pthread_mutex_lock(&evn_call_lock);
    h = evn_create_for_language((int)lang_id, 0);
    if (h == (ECIHand)0) {
        pthread_mutex_unlock(&evn_call_lock);
        free(buf);
        return NULL;
    }
    evn_call c = {0};
    c.frame[0] = 0;
    eciSetParam(h, 1 /* P_INPUT_TYPE */, 1);
    eciSetParam(h, 0 /* P_SYNTH_MODE */, 1);
    if (!eciSetOutputBuffer(h, EVN_FRAME, c.frame)) {
        free(buf);
        pthread_mutex_unlock(&evn_call_lock);
        return NULL;
    }
    eciRegisterCallback(h, evn_phoneme_message, NULL);
    if (eciAddText(h, buf)
            && eciGeneratePhonemes(h, (int)sizeof(phoneme_buf), phoneme_buf) > 0) {
        /* Strip the engine's terminal backspaces; keep everything else. */
        char *r = phoneme_buf, *w = phoneme_buf;
        while (*r) {
            if (*r != '\b')
                *w++ = *r;
            r++;
        }
        *w = '\0';
        answer = (*env)->NewStringUTF(env, phoneme_buf);
    }
    eciDelete(h);
    pthread_mutex_unlock(&evn_call_lock);
    free(buf);
    return answer;
}

/* ---- Dictionary file loading -------------------------------------------
 * Load a tab-separated Windows-1252 text file into the engine's dictionary.
 * Format: key<TAB>say per line. Returns eciDictNoError (0) on success. */
static int evn_load_dict_file(ECIHand h, ECIDictHand dict, int volume, const char *path)
{
    FILE *f = fopen(path, "rb");
    if (!f)
        return 6; /* eciDictAccessError */

    char *line = NULL;
    size_t len = 0;
    ssize_t read;
    int taught = 0, refused = 0;

    while ((read = getline(&line, &len, f)) != -1) {
        char *tab = strchr(line, '\t');
        if (!tab)
            continue;
        *tab = '\0';
        char *key = line;
        char *say = tab + 1;

        /* Trim trailing newline/whitespace */
        char *end = say + strlen(say) - 1;
        while (end > say && (*end == '\n' || *end == '\r' || *end == ' ' || *end == '\t'))
            *end-- = '\0';

        if (strlen(key) == 0 || strlen(say) == 0)
            continue;

        char *pair = malloc(strlen(key) + strlen(say) + 2);
        if (!pair)
            continue;
        memcpy(pair, key, strlen(key) + 1);
        memcpy(pair + strlen(key) + 1, say, strlen(say) + 1);

        int rc = eciUpdateDict(h, dict, volume, pair, pair + strlen(key) + 1);
        free(pair);
        if (rc == 0)
            taught++;
        else
            refused++;
    }
    free(line);
    fclose(f);
    if (refused)
        LOGW("Dictionary load: %d of %d entries refused", refused, taught + refused);
    return taught > 0 ? 0 : 6;
}

/* JNI: Load dictionary file for a whole-utterance slot.
 * Class:     com_eloquick_tts_EloquenceNative
 * Method:    nativeLoadDictFile
 * Signature: (JILjava/lang/String;)I */
JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloquenceNative_nativeLoadDictFile(JNIEnv *env, jobject thiz,
        jint lang_id, jint volume, jstring path)
{
    (void)thiz;
    if (path == NULL)
        return 6;
    pthread_once(&evn_init_once, evn_bind_registry);
    const char *path_str = (*env)->GetStringUTFChars(env, path, NULL);
    if (!path_str)
        return 2;
    ECIHand h = evn_create_for_language((int)lang_id, 0);
    if (h == (ECIHand)0) {
        (*env)->ReleaseStringUTFChars(env, path, path_str);
        return 6;
    }
    ECIDictHand dict = eciNewDict(h);
    if (dict == NULL_DICT_HAND) {
        eciDelete(h);
        (*env)->ReleaseStringUTFChars(env, path, path_str);
        return 6;
    }
    if (eciSetDict(h, dict) != 0) {
        eciDeleteDict(h, dict);
        eciDelete(h);
        (*env)->ReleaseStringUTFChars(env, path, path_str);
        return 6;
    }
    int rc = evn_load_dict_file(h, dict, (int)volume, path_str);
    (*env)->ReleaseStringUTFChars(env, path, path_str);
    eciDelete(h);
    return (jint)rc;
}

/* JNI: Load dictionary file for a streaming session.
 * Class:     com_eloquick_tts_EloQuickEngine
 * Method:    nativeStreamDictLoadFile
 * Signature: (JILjava/lang/String;)I */
JNIEXPORT jint JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamDictLoadFile(JNIEnv *env, jclass cls,
        jlong shandle, jint volume, jstring path)
{
    (void)cls;
    if (!path)
        return 6;
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    if (!s)
        return 6;
    const char *path_str = (*env)->GetStringUTFChars(env, path, NULL);
    if (!path_str)
        return 2;
    eq_extra *e = eq_extra_get(s->handle);
    if (!e) {
        (*env)->ReleaseStringUTFChars(env, path, path_str);
        return 6;
    }
    if (!eq_dict_ensure(s->handle, e)) {
        eq_extra_release(e);
        (*env)->ReleaseStringUTFChars(env, path, path_str);
        return 6;
    }
    ECIDictHand dict = eq_extra_dict_snapshot(e);
    eq_extra_release(e);
    if (!dict) {
        (*env)->ReleaseStringUTFChars(env, path, path_str);
        return 6;
    }
    int rc = evn_load_dict_file(s->handle, dict, (int)volume, path_str);
    (*env)->ReleaseStringUTFChars(env, path, path_str);
    return (jint)rc;
}

JNIEXPORT jboolean JNICALL
Java_com_eloquick_tts_EloquenceNative_nativeSynthesize(JNIEnv *env, jobject thiz,
        jint lang_id, jbyteArray text, jint voice_preset,
        jint gender, jint head_size, jint pitch_baseline, jint pitch_fluctuation,
        jint roughness, jint breathiness, jint speed, jint volume,
        jboolean real_world_units, jint sample_rate_hz, jint sentence_pause_ms,
        jboolean heteronym_filter, jboolean ssml_input,
        jboolean abbreviation_expansion, jstring upsample_method,
        jstring dict_path, jstring abbv_dict_path, jobject callback)
{
    jbyte *bytes = NULL;
    jsize n = 0;
    unsigned char *buf = NULL;
    size_t total;
    evn_slot *s = NULL;
    evn_call c;
    jclass consumer_cls = NULL;
    const char *method_name = NULL;
    const char *dict_name = NULL;
    const char *abbv_name = NULL;
    int shaping[8];
    int i, spins;
    int ok = 0;
    (void)thiz;

    pthread_once(&evn_init_once, evn_bind_registry);
    if (text == NULL || callback == NULL)
        return JNI_FALSE;
    n = (*env)->GetArrayLength(env, text);
    if (n < 1 || n > (jsize)EVN_MAX_TEXT_BYTES)
        return JNI_FALSE;

    buf = (unsigned char *)malloc((size_t)n + 1);
    if (buf == NULL)
        return JNI_FALSE;
    bytes = (*env)->GetByteArrayElements(env, text, NULL);
    if (bytes == NULL) {
        free(buf);
        return JNI_FALSE;
    }
    memcpy(buf, bytes, (size_t)n);
    (*env)->ReleaseByteArrayElements(env, text, bytes, JNI_ABORT);
    buf[n] = '\0';
    if (ssml_input)
        evn_strip_tags(buf);
    total = strlen((const char *)buf);
    if (total < 1 || total > EVN_MAX_TEXT_BYTES) {
        free(buf);
        return JNI_FALSE;
    }

    if ((*env)->GetJavaVM(env, &c.vm) != JNI_OK) {
        free(buf);
        return JNI_FALSE;
    }
    c.consumer = (*env)->NewGlobalRef(env, callback);
    if (c.consumer == NULL) {
        free(buf);
        return JNI_FALSE;
    }
    consumer_cls = (*env)->GetObjectClass(env, callback);
    if (consumer_cls == NULL) {
        (*env)->DeleteGlobalRef(env, c.consumer);
        free(buf);
        return JNI_FALSE;
    }
    c.on_chunk = (*env)->GetMethodID(env, consumer_cls, "onAudioChunk", "([BI)Z");
    c.on_mark = (*env)->GetMethodID(env, consumer_cls, "onIndexMark", "(I)V");
    if (c.on_chunk == NULL || c.on_mark == NULL) {
        /* Matches the proguard contract: these lookups failing means the
         * class was renamed out from under JNI_OnLoad-style resolution. */
        LOGE("AudioConsumer method resolution failed");
        (*env)->DeleteGlobalRef(env, c.consumer);
        free(buf);
        return JNI_FALSE;
    }
    c.cancelled = 0;

    if (upsample_method != NULL)
        method_name = (*env)->GetStringUTFChars(env, upsample_method, NULL);
    if (dict_path != NULL)
        dict_name = (*env)->GetStringUTFChars(env, dict_path, NULL);
    if (abbv_dict_path != NULL)
        abbv_name = (*env)->GetStringUTFChars(env, abbv_dict_path, NULL);

    pthread_mutex_lock(&evn_call_lock);
    s = evn_find_or_make_slot((int)lang_id, heteronym_filter ? 1 : 0,
            ssml_input ? 1 : 0);
    if (s == NULL)
        goto done;
    if (!evn_slot_idle(s)) {
        /* A previous utterance never drained (e.g. cancelled mid-flight):
         * rebuild the slot rather than queueing behind the unknown. */
        int lang = s->lang_id, het = s->hetero, ss = s->ssml;
        evn_slot_destroy(s);
        s = evn_find_or_make_slot(lang, het, ss);
        if (s == NULL)
            goto done;
    }

    /* Voice: preset into voice 0, then per-param shaping (-1 keeps). */
    if (voice_preset < 1 || voice_preset > 8)
        voice_preset = 1;
    eciCopyVoice(s->h, (int)voice_preset, 0);
    shaping[0] = (int)gender;
    shaping[1] = (int)head_size;
    shaping[2] = (int)pitch_baseline;
    shaping[3] = (int)pitch_fluctuation;
    shaping[4] = (int)roughness;
    shaping[5] = (int)breathiness;
    shaping[6] = (int)speed;
    shaping[7] = (int)volume;
    for (i = 0; i < 8; i++) {
        if (shaping[i] >= 0)
            eciSetVoiceParam(s->h, 0, i, shaping[i]);
    }
eciSetParam(s->h, 8 /* P_REAL_WORLD_UNITS */, real_world_units ? 1 : 0);
    {
        /* >= 8000 is hertz directly (docs/api.md); 0 falls back to the
         * engine-native 11025. Resolved rate is stashed for the pause
         * tail below. */
        int hz = (int)sample_rate_hz > 0 ? (int)sample_rate_hz : 11025;
        eciSetParam(s->h, 5 /* P_SAMPLE_RATE */, hz);
        sample_rate_hz = (jint)hz;
    }
    c.cancelled = 0;
    c.mark_count = 0;
    memset(c.mark_bytes, 0, sizeof(c.mark_bytes));
    memset(c.mark_chars, 0, sizeof(c.mark_chars));
    {
        int up = evn_upsample_index(method_name);
        if (up >= 0)
            eciSetParam(s->h, 10 /* P_UPSAMPLE_METHOD */, up);
    }
    /* Ensure annotations are on and synth mode is immediate (0) for
     * whole-utterance synthesis. These are idempotent but must be set
     * after slot creation/reuse because the engine may have changed them
     * during a previous utterance. */
    eciSetParam(s->h, 1 /* P_INPUT_TYPE */, 1);
    eciSetParam(s->h, 0 /* P_SYNTH_MODE */, 0);
    eciSetParam(s->h, 3 /* P_DICTIONARY */, abbreviation_expansion ? 0 : 1);
    evn_apply_dictionaries(s, dict_name, abbv_name);

    eciRegisterCallback(s->h, evn_on_message, &c);
    if (!eciSetOutputBuffer(s->h, EVN_FRAME, c.frame))
        goto done;

    /* Feed sentence by sentence with an index mark between, so annotations
     * can never straddle a segment and so onIndexMark fires per sentence. */
    {
        size_t start = 0, pos = 0;
        size_t fed = 0;
        while (pos < total) {
            if (evn_is_sentence_end(buf[pos], pos + 1 < total ? buf[pos + 1] : '\0')) {
                size_t end = pos + 1;
                unsigned char saved = buf[end];
                buf[end] = '\0';
                if (!eciAddText(s->h, buf + start))
                    goto done;
                buf[end] = saved;
                fed = end;
                /* Mark the boundary just fed, in bytes; converted to chars
                 * at the callback site via the map below. Skip offset 0. */
                if (fed > 0 && fed < total) {
                    eciInsertIndex(s->h, (int)fed);
                    if (c.mark_count < EVN_MAX_MARKS) {
                        c.mark_bytes[c.mark_count] = (int)fed;
                        c.mark_chars[c.mark_count] = evn_chars_before(buf, fed);
                        c.mark_count++;
                    }
                }
                start = end;
            }
            pos++;
        }
        if (start < total) {
            if (!eciAddText(s->h, buf + start))
                goto done;
        }
    }
    if (!eciSynthesize(s->h))
        goto done;
    /* Bounded drain: a chunk that never drains fails fast, never ANRs. */
    for (spins = 0; spins < EVN_DRAIN_SPINS && eciSpeaking(s->h) && !c.cancelled; spins++)
        usleep(EVN_DRAIN_SLEEP_US);
    if (!c.cancelled) {
        if (eciSpeaking(s->h))
            goto done;
        eciSynchronize(s->h);
        ok = 1;
        /* Sentence pause tail as real silent PCM through the consumer. */
        if (sentence_pause_ms > 0) {
            int total_silence = (int)((long)sample_rate_hz * (long)sentence_pause_ms / 1000L);
            while (total_silence > 0) {
                int chunk = total_silence > EVN_FRAME ? EVN_FRAME : total_silence;
                jbyteArray arr = (*env)->NewByteArray(env, (jsize)(chunk * 2));
                jbyte *zeros;
                jboolean go;
                if (arr == NULL)
                    break;
                zeros = (jbyte *)calloc(1, (size_t)(chunk * 2));
                if (zeros == NULL) {
                    (*env)->DeleteLocalRef(env, arr);
                    break;
                }
                (*env)->SetByteArrayRegion(env, arr, 0, (jsize)(chunk * 2), zeros);
                free(zeros);
                go = (*env)->CallBooleanMethod(env, c.consumer, c.on_chunk,
                        arr, (jint)(chunk * 2));
                (*env)->DeleteLocalRef(env, arr);
                if ((*env)->ExceptionCheck(env)) {
                    (*env)->ExceptionClear(env);
                    break;
                }
                total_silence -= chunk;
                if (!go)
                    break;
            }
        }
    } else {
        /* Cancelled via the consumer: not an error. The engine keeps no
         * further buffers (eciDataAbort), and the slot is rebuilt on next
         * use if it never drains. */
        ok = 1;
    }

done:
    if (!ok && s != NULL) {
        /* A half-fed failure must not leave queued text on the slot for the
         * next utterance to inherit: drop the slot (never eciStop). The
         * next call rebuilds it; a cancel keeps the slot - the abort
         * already told the engine to hand over nothing more. */
        pthread_mutex_lock(&evn_slots_lock);
        if (s->in_use)
            evn_slot_destroy(s);
        pthread_mutex_unlock(&evn_slots_lock);
    }
    if (method_name != NULL)
        (*env)->ReleaseStringUTFChars(env, upsample_method, method_name);
    if (dict_name != NULL)
        (*env)->ReleaseStringUTFChars(env, dict_path, dict_name);
    if (abbv_name != NULL)
        (*env)->ReleaseStringUTFChars(env, abbv_dict_path, abbv_name);
    (*env)->DeleteGlobalRef(env, c.consumer);
    pthread_mutex_unlock(&evn_call_lock);
    free(buf);
    return ok ? JNI_TRUE : JNI_FALSE;
}
