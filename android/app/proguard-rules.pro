# EloquenceNative bridges to native code by exact class/method name and
# signature, not by anything R8 can see as a reference:
#
# - nativeInit/nativeGetLanguages/nativeSynthesize are `external fun`s that
#   libopenevv_jni.so exports as literal JNI symbols
#   (Java_com_eloquick_tts_EloquenceNative_nativeXxx after the
#   com.animeshahilya.eloquencerevived -> com.eloquick repackage;
#   see evv_tts_jni.c - TODO: bring evv_tts_jni.c over and rename its
#   Java_com_animeshahilya_* symbols to Java_com_eloquick_*). Renaming the class or any of these methods leaves
#   the .so's exported symbols pointing at names that no longer exist -
#   System.loadLibrary still succeeds, but every native call then throws
#   UnsatisfiedLinkError.
# - AudioConsumer.onAudioChunk/onIndexMark are looked up once in JNI_OnLoad
#   via FindClass + GetMethodID against those exact string names (again
#   evv_tts_jni.c), cached, and invoked directly on every synthesis
#   callback rather than re-resolved per call. Renaming either method
#   leaves JNI_OnLoad's GetMethodID returning null, which fails that
#   lookup at library-load time - silent unless something reads logcat for
#   "AudioConsumer method resolution failed".
#
# `-keepclasseswithmembernames` alone is not enough here: it keeps the
# class reachable but still lets R8 rename members that aren't themselves
# `native`, which is exactly what AudioConsumer's two methods are - regular
# Kotlin method declarations, only ever called through JNI's own separate
# lookup path, invisible to R8's reachability analysis.
-keep class com.eloquick.tts.EloquenceNative {
    *;
}
-keep interface com.eloquick.tts.EloquenceNative$AudioConsumer {
    *;
}
