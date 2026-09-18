package com.eloquick.tts

import java.net.HttpURLConnection
import java.net.URL

/**
 * The upstream source the bundled English community dictionary
 * ([bundledEnglishCommunityDictionary]) is snapshotted from - a manual
 * "check for updates" button reads this same file's current state on
 * GitHub, never automatically and never in the background (see
 * [checkCommunityDictionaryUpdate]'s own doc comment for why this is a
 * network call at all, which nothing else in this app makes).
 */
internal const val COMMUNITY_DICTIONARY_REPO = "eigencrow/IBMTTSDictionaries"
// ENUmain.dic (~1,200 entries), not the much larger ENURoot.dic (~68,000):
// confirmed on a physical device that ENURoot.dic's size hangs eciLoadDict
// indefinitely on a cold engine slot, for every language including English
// - see the "still doesn't speak after a clean install" investigation this
// reverted. ENUmain.dic has been verified safe across many device runs.
internal const val COMMUNITY_DICTIONARY_PATH = "ENUmain.dic"

/**
 * The git blob SHA-1 of the exact bytes bundled as
 * `community-dictionary-enus.dic` - GitHub's Contents API reports the same
 * hash for the file at HEAD, so comparing this one short string tells
 * whether upstream has moved on without downloading the (multi-megabyte)
 * file itself just to find out. Update this constant whenever the bundled
 * file is refreshed from upstream - [PronunciationDictionaryTest] pins the
 * two together so they can't silently drift apart.
 */
internal const val BUNDLED_COMMUNITY_DICTIONARY_SHA = "cf87725696fd6f7bd9d0c58972a818f1756b98f9"

/** Result of asking upstream whether a newer community dictionary exists. */
sealed class CommunityDictionaryUpdateCheck {
    /** [knownSha] already matches what's on GitHub - nothing to offer. */
    object UpToDate : CommunityDictionaryUpdateCheck()

    /** Upstream's content differs from [knownSha]. [sizeBytes] is shown to
     * the user before they choose to download it. */
    data class Available(val downloadUrl: String, val sha: String, val sizeBytes: Long) : CommunityDictionaryUpdateCheck()

    /** The request itself failed (no connection, GitHub error, unexpected
     * response shape) - [message] is safe to show as-is. */
    data class CheckFailed(val message: String) : CommunityDictionaryUpdateCheck()
}

/**
 * Asks GitHub's Contents API for [COMMUNITY_DICTIONARY_PATH]'s current
 * metadata and compares its `sha` against [knownSha] - the *only* network
 * call anywhere in this app, and only ever made from an explicit "check for
 * updates" tap (see [PronunciationDictionaryScreen]), never on a timer and
 * never on startup. The API response for a single file is small (its own
 * metadata, not the file's content), so a check costs a few hundred bytes
 * even for a multi-megabyte dictionary - the file itself is only ever
 * fetched by [downloadCommunityDictionaryUpdate], after the user has seen
 * the size and explicitly agreed to download it.
 *
 * Blocking I/O - call from [kotlinx.coroutines.Dispatchers.IO].
 */
fun checkCommunityDictionaryUpdate(knownSha: String): CommunityDictionaryUpdateCheck {
    val url = "https://api.github.com/repos/$COMMUNITY_DICTIONARY_REPO/contents/$COMMUNITY_DICTIONARY_PATH"
    return try {
        val json = httpGetText(url, mapOf("Accept" to "application/vnd.github+json"))
        parseUpdateCheckResponse(json, knownSha)
    } catch (e: Exception) {
        CommunityDictionaryUpdateCheck.CheckFailed(e.message ?: "Couldn't reach GitHub")
    }
}

/**
 * The decision [checkCommunityDictionaryUpdate] makes once it has a GitHub
 * Contents API response body, pulled out as its own pure function so it's
 * exercisable against a sample response without a real network call.
 */
internal fun parseUpdateCheckResponse(json: String, knownSha: String): CommunityDictionaryUpdateCheck {
    val sha = extractJsonStringField(json, "sha")
        ?: return CommunityDictionaryUpdateCheck.CheckFailed("Unexpected response from GitHub")
    val downloadUrl = extractJsonStringField(json, "download_url")
        ?: return CommunityDictionaryUpdateCheck.CheckFailed("Unexpected response from GitHub")
    val size = extractJsonNumberField(json, "size") ?: 0L
    return if (sha == knownSha) {
        CommunityDictionaryUpdateCheck.UpToDate
    } else {
        CommunityDictionaryUpdateCheck.Available(downloadUrl, sha, size)
    }
}

/**
 * Downloads the raw dictionary bytes from [downloadUrl] (the
 * `download_url` a prior [checkCommunityDictionaryUpdate] returned) - the
 * actual multi-megabyte fetch, only ever called after the user has agreed
 * to it. Same encoding contract as any other imported file: the caller
 * still runs this through [decodeImportedDictionaryBytes], since upstream's
 * file is Windows-1252 like every other file this app can import.
 *
 * Blocking I/O - call from [kotlinx.coroutines.Dispatchers.IO].
 */
fun downloadCommunityDictionaryUpdate(downloadUrl: String): ByteArray = httpGetBytes(downloadUrl)

private fun httpGetBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        connection.setRequestProperty("User-Agent", "eloquence-revived")
        if (connection.responseCode !in 200..299) {
            throw java.io.IOException("HTTP ${connection.responseCode}")
        }
        return connection.inputStream.use { it.readBytes() }
    } finally {
        connection.disconnect()
    }
}

private fun httpGetText(url: String, headers: Map<String, String> = emptyMap()): String =
    httpGetBytes(url, headers).toString(Charsets.UTF_8)

/**
 * Pulls one flat string field out of a JSON object by regex rather than
 * pulling in a JSON library for the two fields this app ever reads out of a
 * GitHub API response - org.json is Android-only and throws under a plain
 * JVM unit test without Robolectric (which this app otherwise has no need
 * for), and every field this app actually reads is a plain top-level string,
 * never nested or requiring escape handling.
 */
internal fun extractJsonStringField(json: String, field: String): String? =
    Regex("\"${Regex.escape(field)}\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)

/** Numeric counterpart to [extractJsonStringField], for `size`. */
internal fun extractJsonNumberField(json: String, field: String): Long? =
    Regex("\"${Regex.escape(field)}\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()
