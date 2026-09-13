package com.eloquick.tts;

/** Published ECI engine behind a Java handle. Method names must stay in
 * sync with android/eloquick_jni.c (Java_com_eloquick_tts_EloQuickEngine_*).
 * nativeCreate returns 0 on failure (unknown language); every other method
 * tolerates handle 0 by answering failure/0/null. */
public final class EloQuickEngine {
    static {
        System.loadLibrary("eloquick");
    }

    private EloQuickEngine() {}

    /** Language id, or 0 for the build default. Use nativeGetLanguages. */
    public static native long nativeCreate(int language);
    public static native void nativeDestroy(long handle);
    public static native int[] nativeGetLanguages();
    /** 11025 Hz mono PCM, or null on failure. */
    public static native short[] nativeSynth(long handle, String text);
    public static native int nativeSetParam(long handle, int param, int value);
    public static native int nativeGetParam(long handle, int param);
    /** Copies preset voice (1-8) into the speaking voice (0), like -v. */
    public static native int nativeCopyVoice(long handle, int from, int to);
    public static native int nativeSetVoiceParam(long handle, int voice, int param, int value);
    public static native int nativeGetVoiceParam(long handle, int voice, int param);
    /** Engine output rate in Hz (8000/11025/22050/16000/32000/44100/48000);
     *  answers the rate now in force. Above 11025 the engine upsamples itself. */
    public static native int nativeSetSampleRateHz(long handle, int hz);
    public static native String nativeVersion();
    /** Teach one word in a dictionary volume (0 main, 1 root, 2 abbrev);
     *  0 is success. Prefer over file loads: the engine loader wants IBM's
     *  binary form, taught words take text. */
    public static native int nativeDictTeach(long handle, int volume, String key, String say);
    /** What a key was taught, or null. */
    public static native String nativeDictLookup(long handle, int volume, String key);
    /** Back to the language's own dictionary. */
    public static native void nativeDictForget(long handle);
    /** Heteronym correction default for instances created afterwards
     *  (creation-time property: hetero_install runs at instance setup, so
     *  toggling wants a new instance). Off default: a loaded filter turns
     *  annotation reading on, so backticks in plain text get interpreted. */
    public static native void nativeSetHeteroDefault(boolean on);
    /** Streaming: queue text (answers at once), pull PCM bytes (0 = end,
     *  -1 = stopped/error), abort mid-flight, destroy the session. */
    public static native long nativeStreamCreate(int language);
    public static native boolean nativeStreamSpeak(long stream, String text);
    public static native int nativeStreamRead(long stream, byte[] dst, int max);
    public static native void nativeStreamStop(long stream);
    public static native void nativeStreamDestroy(long stream);
    /** Stream-instance controls for the TTS service (voice shaping, rate). */
    public static native int nativeStreamCopyVoice(long stream, int from, int to);
    public static native int nativeStreamSetVoiceParam(long stream, int voice, int param, int value);
    public static native int nativeStreamGetVoiceParam(long stream, int voice, int param);
    public static native int nativeStreamSetSampleRateHz(long stream, int hz);
}
