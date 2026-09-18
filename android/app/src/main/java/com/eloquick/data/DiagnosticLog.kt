package com.eloquick.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A small, app-owned diagnostic log - not a logcat scrape. Reading another
 * process's logcat needs READ_LOGS (a signature/system permission no normal
 * app can hold since API 16), and even a process's *own* logcat output is an
 * OS ring buffer this app doesn't control the size, retention, or format of
 * - some OEM builds throttle or restrict `logcat` for third-party apps
 * outright. Not something to build a support feature's only data source on.
 * Every entry below is one this app itself chose to write, to a file this
 * app itself owns, so what a user exports is deterministic and portable
 * across every device.
 *
 * This app is two processes (see EloquenceTtsService's own manifest
 * comment: the UI/default process and the isolated ":tts" process that
 * actually speaks), so there are two log files, one per process ([init]'s
 * processTag) - never one file two processes could corrupt appending to
 * concurrently. [snapshot] reads and chronologically merges both when they
 * exist, so a Troubleshoot session started in the UI process still shows
 * what the :tts process logged, in the actual order the two interleaved.
 *
 * Writes are handed to a single background thread by default, so a routine
 * [i]/[w]/[e] call on EloquenceTtsService's synthesis thread - which runs at
 * THREAD_PRIORITY_URGENT_AUDIO specifically to stay latency-sensitive, see
 * that service's own comment - never blocks on disk I/O. [crashSync] is the
 * deliberate exception: used only for the two moments this app ends a
 * process right after logging (the hang watchdog's own Process.killProcess,
 * a global uncaught-exception handler installed from EloquenceRevivedApp) -
 * a queued async write racing a kernel process kill is not a durability
 * guarantee, and those are exactly the entries a Troubleshoot export needs
 * most to actually survive.
 */
object DiagnosticLog {
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val UI_FILE_NAME = "diag_ui.log"
    private const val TTS_FILE_NAME = "diag_tts.log"

    // SimpleDateFormat isn't thread-safe - a ThreadLocal instance per caller
    // thread, rather than a lock, since this runs from whichever thread
    // logs (the calling thread for crashSync, the single writer thread
    // otherwise) with no reason to ever contend.
    private val timestampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    // A single background thread, not a pool: entries must land in the
    // order they were written (this only ever appends, never rewrites out
    // of order), and diagnostic logging is never hot enough to need more
    // than one.
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DiagnosticLog").apply { isDaemon = true }
    }
    private val writeLock = Any()

    @Volatile private var file: File? = null
    @Volatile private var processTag: String = "app"

    /**
     * Must be called once per process, as early as possible - see
     * EloquenceRevivedApp.onCreate (runs once per process regardless of
     * which component actually started it, so it also runs first in the
     * :tts process) and EloquenceTtsService.onCreate, which re-inits with
     * "tts" immediately after - correcting the "ui" tag Application.onCreate
     * already set for that process, see that call site's own comment.
     *
     * Device-protected storage, not the regular files dir: this app is
     * directBootAware end to end (see AppPreferences' own doc comment on
     * why), and a pre-unlock crash or hang is exactly the kind of thing
     * worth being able to diagnose afterward - this can't depend on
     * credential-encrypted storage being available yet either.
     */
    fun init(context: Context, processTag: String) {
        this.processTag = processTag
        val dir = context.applicationContext.createDeviceProtectedStorageContext().filesDir
        file = File(dir, if (processTag == "tts") TTS_FILE_NAME else UI_FILE_NAME)
    }

    fun i(tag: String, message: String) = log('I', tag, message, null, sync = false)
    fun w(tag: String, message: String, t: Throwable? = null) = log('W', tag, message, t, sync = false)
    fun e(tag: String, message: String, t: Throwable? = null) = log('E', tag, message, t, sync = false)

    /** See this object's own doc comment - synchronous, for the moments right before this
     * process deliberately ends. Safe to call from any thread, including one about to die: a
     * plain blocking file append, not a coroutine or handler post that could be abandoned. */
    fun crashSync(tag: String, message: String, t: Throwable? = null) = log('E', tag, message, t, sync = true)

    private fun log(level: Char, tag: String, message: String, t: Throwable?, sync: Boolean) {
        // Logcat still gets every call, same as before this existed - this
        // object adds durability on top, it doesn't replace the normal
        // debug path.
        when (level) {
            'W' -> Log.w(tag, message, t)
            'E' -> Log.e(tag, message, t)
            else -> Log.i(tag, message)
        }
        val f = file ?: return // init() not called yet - shouldn't happen past Application.onCreate
        val stackTrace = t?.let { Log.getStackTraceString(it) }
        val line = formatEntry(timestampFormat.get()!!.format(Date()), level, processTag, tag, message, stackTrace)
        if (sync) {
            runCatching { appendCapped(f, line) }
        } else {
            writer.execute { runCatching { appendCapped(f, line) } }
        }
    }

    private fun appendCapped(f: File, line: String) = synchronized(writeLock) {
        // Checked before appending, not after: a check-after would let one
        // write barely exceed the cap every single time, unbounded.
        if (f.exists() && f.length() > MAX_FILE_BYTES) {
            val kept = splitEntries(f.readText()).let { it.takeLast(maxOf(1, it.size / 2)) }
            f.writeText(kept.joinToString("\n") + "\n")
        }
        f.appendText(line + "\n")
    }

    /**
     * Deletes both process files - not just this process's own [file] - so
     * "Clear captured logs" from the Troubleshoot screen (always running in
     * the UI process) actually clears what the :tts process logged too.
     *
     * Synchronous, like [crashSync], and for a related reason: this is a
     * rare, deliberate user action the Troubleshoot screen immediately
     * re-reads [snapshot] after, on a *different* thread than the single
     * background [writer] - queuing the delete there instead would leave a
     * real window where that re-read runs before the delete actually has.
     * Callers already run this off the main thread (a Compose call site
     * wraps it in a background dispatcher, the same as any other file I/O
     * here) - see TroubleshootScreen.
     */
    fun clear(context: Context) {
        val dir = context.applicationContext.createDeviceProtectedStorageContext().filesDir
        runCatching { File(dir, UI_FILE_NAME).delete() }
        runCatching { File(dir, TTS_FILE_NAME).delete() }
    }

    /** This process's own log, merged chronologically with the other
     * process's file if it exists - see this object's own doc comment for
     * why there are two and why a real interleave, not two separate blocks.
     * Read fresh from disk on every call: a Troubleshoot screen left open
     * for a while should reflect what just happened, not a stale snapshot
     * from when it was opened. */
    fun snapshot(context: Context): String {
        val dir = context.applicationContext.createDeviceProtectedStorageContext().filesDir
        val ui = File(dir, UI_FILE_NAME)
        val tts = File(dir, TTS_FILE_NAME)
        return mergeEntries(
            if (ui.exists()) runCatching { ui.readText() }.getOrDefault("") else "",
            if (tts.exists()) runCatching { tts.readText() }.getOrDefault("") else "",
        )
    }
}

private val ENTRY_START = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} """)

/** Pure - one log line, no file or Context involved, so the format is unit-testable directly. */
internal fun formatEntry(
    timestamp: String,
    level: Char,
    processTag: String,
    tag: String,
    message: String,
    stackTrace: String?,
): String {
    val head = "$timestamp $level $processTag/$tag: $message"
    return if (stackTrace.isNullOrBlank()) head else head + "\n" + stackTrace.trimEnd()
}

/**
 * Splits a log file's raw text into entries - one timestamp-starting line
 * plus whatever non-timestamp continuation lines (a stack trace) follow it,
 * up to the next entry or end of file. A stray continuation line with no
 * entry yet started (a truncated/corrupt file) is silently dropped rather
 * than crashing the merge. Pure, so the split/merge logic is unit-testable
 * without any real file.
 */
internal fun splitEntries(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val entries = mutableListOf<StringBuilder>()
    for (line in text.split("\n")) {
        if (ENTRY_START.containsMatchIn(line)) {
            entries.add(StringBuilder(line))
        } else if (entries.isNotEmpty()) {
            entries.last().append('\n').append(line)
        }
    }
    return entries.map { it.toString() }
}

/**
 * Chronological merge of two processes' log text - a real interleave, not
 * two separate blocks: the two processes' relative timing is often the
 * actual diagnostic question ("did the watchdog fire before or after this
 * UI action"). Each entry's own timestamp format sorts correctly as a plain
 * string (fixed-width, zero-padded, 23 characters), so no date parsing is
 * needed to merge in order - [sortedBy] is stable, so two entries that
 * happen to share a millisecond keep their original file's relative order.
 */
internal fun mergeEntries(uiText: String, ttsText: String): String {
    val all = splitEntries(uiText) + splitEntries(ttsText)
    if (all.isEmpty()) return "(no diagnostic log entries yet)"
    return all.sortedBy { it.take(23) }.joinToString("\n")
}
