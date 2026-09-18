package com.eloquick.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.eloquick.EloquenceRevivedApp

/**
 * Answers [AppPreferences.currentConfig] with the live, always-current
 * config from whichever process actually holds it - see [AppPreferences]'s
 * own doc comment for the bug this exists to fix (a `SharedPreferences`
 * read in a long-lived process is cached in memory after its first read and
 * never picks up a write made by a *different* process, so
 * [com.eloquick.service.EloquenceTtsService]'s own
 * `:tts` process could keep speaking with whatever settings were on disk
 * when it started, no matter how many settings a user changed afterward).
 *
 * Declared with no `android:process` override in the manifest, so this
 * always runs in the app's *default* process - the same one
 * [EloquenceRevivedApp.preferences] and its `StateFlow` live in - meaning
 * `call()` below is answering from the one true in-memory source of truth,
 * not from a second copy of it. A `ContentProvider` (rather than, say, a
 * bound `Messenger`/AIDL service) purely because `call()` is the smallest
 * amount of IPC plumbing that does the job: one request, one `Bundle`
 * reply, no connection lifecycle for either side to manage.
 */
class TtsConfigProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_GET_CONFIG) return null
        val app = context?.applicationContext as? EloquenceRevivedApp ?: return null
        // liveConfig, not config.value: this process can be born before
        // first unlock to serve a Direct Boot synthesis request and live on
        // past unlock - the plain StateFlow would still hold the defaults
        // it loaded while locked (see AppPreferences.liveConfig).
        //
        // Never throws out of the binder: a corrupt-prefs or locked-storage
        // failure answers null (the :tts side falls back to defaults) rather
        // than an exception the caller's process can't handle.
        return runCatching { app.preferences.liveConfig().toBundle(app.dictCacheDir) }.getOrNull()
    }

    // No actual table behind this provider - call() above is its entire
    // surface. These five are only implemented because ContentProvider
    // declares them abstract.
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val METHOD_GET_CONFIG = "get_config"
    }
}
