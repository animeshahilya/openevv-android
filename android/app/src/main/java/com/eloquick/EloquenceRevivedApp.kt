package com.eloquick

import android.app.Application
import com.eloquick.data.AppPreferences
import com.eloquick.data.DiagnosticLog
import com.eloquick.tts.EloquenceNative

class EloquenceRevivedApp : Application() {
    val preferences: AppPreferences by lazy { AppPreferences(this) }

    // Resolved once and shared by both callers that build a dictionary .dic file
    // (EloquenceTtsService's synthesis path and AppViewModel's preview path) - the
    // device-protected cache dir never moves for a running process, so re-wrapping the
    // context per call (or per utterance, on the synthesis path) was pointless allocation.
    val dictCacheDir: java.io.File by lazy { createDeviceProtectedStorageContext().cacheDir }

    override fun onCreate() {
        super.onCreate()
        // Application.onCreate runs once per process regardless of which
        // component actually triggered that process's creation, so this
        // covers both the UI/default process (MainActivity,
        // TtsConfigProvider) and, first, the :tts process too -
        // EloquenceTtsService.onCreate re-inits with "tts" right after,
        // correcting this default "ui" tag for that process specifically.
        // Same Direct Boot constraint as everything else in this method
        // (see below): DiagnosticLog.init only opens a File handle under
        // device-protected storage, no disk I/O yet.
        DiagnosticLog.init(this, "ui")
        installCrashLogging()

        // Cheap and idempotent (guarded by a flag on the native side) - safe
        // to call again from the TTS service's onCreate, whichever process
        // entry point Android happens to start first.
        //
        // Direct Boot constraint on everything in this method: the service
        // and provider are directBootAware, so this runs before first unlock
        // too - nothing here may touch credential-encrypted storage
        // (SharedPreferences, cacheDir, filesDir). nativeInit touches none
        // (models are linked into libopenevv.so itself), and AppPreferences
        // stays lazy below so no prefs file opens until first actual use.
        if (EloquenceNative.isLoaded) {
            EloquenceNative.nativeInit()
        }
    }

    /**
     * A crash this app never used to have any record of once the process
     * that hit it was gone - the same durability gap the hang watchdog's
     * own DiagnosticLog.crashSync closes for a hang (see
     * EloquenceTtsService.startHangWatchdog), just for an uncaught
     * exception instead of a stall. Delegates to whatever handler was
     * already installed (the platform's own crash reporter, or none) after
     * logging - this never suppresses or changes the actual crash, only
     * makes sure it left a durable trace first.
     */
    private fun installCrashLogging() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            DiagnosticLog.crashSync("UncaughtException", "Fatal on ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
