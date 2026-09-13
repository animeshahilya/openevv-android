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
}
