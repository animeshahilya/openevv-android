package com.eloquick.tts

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One user-defined pronunciation override: whenever [find] appears as a
 * word, it's read as [replacement] instead. Applied by openevv's own
 * dictionary engine (`eciNewDict`/`eciLoadDict`/`eciSetDict`) rather than
 * by this app - see [dictionaryFileFor]. An earlier version of this feature
 * did the substitution in Kotlin instead, because that native path used to
 * segfault on every synthesis call regardless of content
 * (Mudb0y/openevv#23); the confirmed root cause (a 64-bit struct-offset bug
 * in dictionary activation) was fixed upstream in openevv commit 8159386,
 * and this app's engine pin moved past it on 2026-09-09.
 */
data class PronunciationEntry(
    val find: String,
    val replacement: String,
    val caseSensitive: Boolean = false,
)

/**
 * Writes [entries] out as a file `eciLoadDict` can read directly.
 * openevv's own docs/api.md: "The file is a line an entry: the key, a tab,
 * and what to say instead" - the same shape [encodePronunciationDictionary]
 * already produces for this app's own persistence, just Windows-1252-coded
 * (via [encodeForEngine]) since this file is read by the engine, not by
 * this app, and every other string reaching the engine already goes
 * through that encoding.
 *
 * Returns null for an empty dictionary (nothing to load - a caller should
 * pass that straight through as "no dictionary" rather than an empty file).
 *
 * The file name is content-addressed (a short hash of the encoded body)
 * rather than fixed, and any other `pronunciation_dict_*` file already in
 * [cacheDir] is deleted - so a caller that always requests the current
 * dictionary's file naturally gets a new path exactly when the content
 * actually changed, which is what tells the native side (`apply_dictionary`
 * in evv_tts_jni.c) to reload rather than reuse what's already loaded for a
 * given engine slot. A file matching the target name already existing is
 * left untouched rather than rewritten - the content that produced that
 * hash can only be this content.
 *
 * Case variants: openevv's own dictionary lookup is an exact byte
 * comparison (`src/eci/dict/eci_key.c`'s `key_match`, plain `strncmp`, no
 * case folding anywhere in the lookup path) - unlike this feature's
 * previous Kotlin-regex implementation, which matched case-insensitively.
 * For an entry with [PronunciationEntry.caseSensitive] false (the default),
 * four case forms of [find] are written (as-typed, lowercase, UPPERCASE,
 * Capitalized) so a word overridden once still matches at a sentence's
 * start or in a heading, the same as this app's old Kotlin-regex behavior.
 * This is also a real, independently-reported community pain point with
 * Eloquence-family dictionaries specifically (e.g. NVDA-IBMTTS-Driver
 * issues #164/#155/#165, nvda.groups.io - "SCAN" read as "South Carolina
 * an" because a dictionary entry only matched one case), not a hypothetical
 * one, so this earns its keep beyond parity with this app's old behavior.
 *
 * [caseSensitive] true writes only the literal, as-typed form - the exact
 * per-entry escape hatch CodeFactory's ETI-Eloquence TTS (the actual
 * Nuance-licensed product) exposes as a "Case sensitive word" checkbox on
 * its own Add Word screen, for entries where case genuinely distinguishes
 * meaning (e.g. an acronym "CAT" vs. the word "cat") and the four-variant
 * default would be wrong.
 */
/**
 * Which of the two dictionary screens/files a call is for - the two are
 * built and cached exactly the same way (same [PronunciationEntry] shape,
 * same case-variant/encoding rules), only their file-name prefix and
 * target engine volume differ, so one writer serves both rather than a
 * near-duplicate second file. Kept as a small closed type (not a bare
 * String prefix) so a caller can't typo a third, orphaned prefix into
 * existence.
 */
enum class DictionaryKind(
    internal val filePrefix: String,
    val title: String,
    val description: String,
    val findLabel: String,
    val replaceLabel: String,
    val emptyStateText: String,
    val exportFileName: String,
    val supportsPhoneticSuggestions: Boolean,
) {
    MAIN(
        filePrefix = "pronunciation_dict_",
        title = "Pronunciation dictionary",
        description = "Override how a word is spoken. Matches whole words only, regardless of " +
            "capitalization by default - \"gif\" also matches \"GIF\" - unless you turn " +
            "on Case sensitive for an entry where case changes the meaning.",
        findLabel = "Find",
        replaceLabel = "Replace with",
        emptyStateText = "No custom pronunciations yet.",
        exportFileName = "pronunciation_dictionary.txt",
        supportsPhoneticSuggestions = true,
    ),
    ABBREVIATION(
        filePrefix = "abbreviation_dict_",
        title = "Abbreviation dictionary",
        description = "Expand your own abbreviations before they're spoken - \"kg\" read " +
            "as \"kilogram\" instead of spelled out. Kept separate from the Pronunciation " +
            "dictionary as its own list, for the engine's own abbreviation lookup.",
        findLabel = "Abbreviation",
        replaceLabel = "Expands to",
        emptyStateText = "No custom abbreviations yet.",
        exportFileName = "abbreviation_dictionary.txt",
        supportsPhoneticSuggestions = false,
    ),
}

/**
 * Single-entry memo for [dictionaryFileFor]: callers (the TTS service on
 * every utterance, the preview path on every tap) pass the same entries
 * list over and over, and rebuilding the 4× variant lines plus a SHA-256
 * over all of them each time is pure waste once the file already exists.
 * Keyed by content (data-class list equality) plus cache dir plus [kind] -
 * without [kind], an empty (or coincidentally identical) Abbreviation
 * dictionary would collide with the Pronunciation dictionary's own cache
 * entry for the same cacheDir and return the wrong file. Guarded for the
 * binder thread and the preview thread racing each other. A config change
 * naturally misses and recomputes exactly once.
 */
private data class DictionaryFileKey(val entries: List<PronunciationEntry>, val cacheDir: String, val kind: DictionaryKind)

// Capacity 4, not 2: the binder thread (EloquenceTtsService) and the preview thread
// (AppViewModel) both call this, and now for two dictionaries (Pronunciation, Abbreviation) each -
// any one of those four changing while another's request is in flight would otherwise evict and
// rebuild a still-current .dic file.
private val dictionaryFileCache = object : LinkedHashMap<DictionaryFileKey, String?>(4, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<DictionaryFileKey, String?>): Boolean = size > 4
}

// Guards the empty-entries sweep below so it runs at most once per process *per [DictionaryKind]* -
// EloquenceTtsService calls dictionaryFileFor on every single utterance from its synthesis thread
// (deliberately run at THREAD_PRIORITY_URGENT_AUDIO, see that service's own comment), and a user
// with no dictionary of a given kind at all - the common case - would otherwise pay a
// cacheDir.listFiles() on every one of them for nothing. compareAndSet, not a plain Boolean: the
// binder thread and the preview thread can both reach this on a process's first call.
private val orphanedFilesSwept = java.util.concurrent.ConcurrentHashMap<DictionaryKind, AtomicBoolean>()

fun dictionaryFileFor(entries: List<PronunciationEntry>, cacheDir: File, kind: DictionaryKind = DictionaryKind.MAIN): String? {
    if (entries.isEmpty()) {
        // Sweeps away a stale <prefix>*.dic even though there's nothing new
        // to build - buildDictionaryFile below only ever cleans up on its
        // way to writing a *new* file, so a user who updates from a build
        // that had entries of this kind (most commonly: Word Wisdom's
        // bundled list, removed 2026-09-11, but also just a personal
        // dictionary someone since cleared out) and has none left of their
        // own would otherwise keep an orphaned file - hundreds of KB, in
        // the Word Wisdom case - sitting in cacheDir forever: nothing ever
        // requests its path again to trigger the cleanup this same function
        // does for the non-empty case below.
        val swept = orphanedFilesSwept.getOrPut(kind) { AtomicBoolean(false) }
        if (swept.compareAndSet(false, true)) cleanupOrphanedDictionaryFiles(cacheDir, kind = kind)
        return null
    }

    val key = DictionaryFileKey(entries.toList(), cacheDir.absolutePath, kind)
    synchronized(dictionaryFileCache) {
        if (dictionaryFileCache.containsKey(key)) {
            val cached = dictionaryFileCache[key]
            // The file itself can still vanish underneath (user clears the
            // app cache, storage pressure) - a cached path to a missing
            // file must rebuild, not fail native-side.
            if (cached == null || File(cached).exists()) return cached
            dictionaryFileCache.remove(key)
        }
    }
    val path = buildDictionaryFile(entries, cacheDir, kind)
    synchronized(dictionaryFileCache) {
        dictionaryFileCache[key] = path
    }
    return path
}

/** Deletes every `<kind's prefix>*.dic` in [cacheDir] except [keep] (if given) - the two kinds'
 * files never share a prefix, so sweeping one kind never touches the other's current file. Skips
 * any in-flight temp file ("*.tmp-*") - the other process's own write-then-rename in progress, see
 * [buildDictionaryFile]'s own comment on why deleting one out from under its writer is unsafe.
 * Internal, not private: lets a test exercise the sweep directly rather than through
 * [dictionaryFileFor]'s own once-per-process guard, which only ever fires on a real process's
 * first call and so can't be asserted on deterministically from a test. */
internal fun cleanupOrphanedDictionaryFiles(cacheDir: File, kind: DictionaryKind = DictionaryKind.MAIN, keep: String? = null) {
    cacheDir.listFiles { f ->
        f.name.startsWith(kind.filePrefix) && f.name != keep && ".tmp-" !in f.name
    }?.forEach { it.delete() }
}

private fun buildDictionaryFile(entries: List<PronunciationEntry>, cacheDir: File, kind: DictionaryKind): String? {
    val seen = HashSet<String>()
    val lines = ArrayList<String>()
    for (entry in entries) {
        val find = entry.find.trim()
        if (find.isEmpty()) continue
        // Locale.ROOT throughout: the default locale's case mapping must
        // never decide matching (Turkish "I"/"ı" would otherwise generate
        // - and merge against - different variants than every other
        // locale), same rule mergeDictionaries below follows.
        val variants = if (entry.caseSensitive) {
            listOf(find)
        } else {
            listOf(
                find,
                find.lowercase(Locale.ROOT),
                find.uppercase(Locale.ROOT),
                find.replaceFirstChar { it.uppercase(Locale.ROOT) },
            )
        }
        for (variant in variants) {
            if (seen.add(variant)) lines += "$variant\t${entry.replacement}"
        }
    }
    if (lines.isEmpty()) return null

    val bytes = encodeForEngine(lines.joinToString("\n"), 0)
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }.take(16)
    val fileName = "${kind.filePrefix}$hash.dic"
    val target = File(cacheDir, fileName)
    if (!target.exists()) {
        cleanupOrphanedDictionaryFiles(cacheDir, kind = kind, keep = fileName)
        // Write-then-rename, not a direct write: this app's two processes
        // (see EloquenceTtsService's own :tts split) both independently
        // call this function for the same content and can race each other
        // here - a direct target.writeBytes() truncates the file in place,
        // so the native side's own apply_dictionary in the *other* process
        // could fopen()/fread() it mid-write and load a truncated/corrupt
        // dictionary. A rename from a temp file on the same directory (same
        // filesystem, so an atomic rename rather than a copy) means any
        // reader only ever sees the old complete file or the new complete
        // one, never a partial one - the other process deleting this exact
        // file out from under an in-progress read is not a real risk on its
        // own (POSIX unlink leaves an already-open file descriptor's read
        // unaffected), but a torn write is.
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val tmp = File(cacheDir, "$fileName.tmp-${System.nanoTime()}")
        tmp.writeBytes(bytes)
        // rename() on Android's (Linux) filesystem atomically replaces an
        // existing destination - the normal case whenever the other
        // process already won this same race with the exact same content.
        if (!tmp.renameTo(target)) tmp.delete()
    }
    return target.absolutePath
}

/**
 * Persisted form of a dictionary: one entry per line, [PronunciationEntry.find],
 * [PronunciationEntry.replacement], [PronunciationEntry.caseSensitive], and
 * [PronunciationEntry.isCommunity] (each flag "1"/"0") separated by tabs -
 * the same tab-separated shape `emoji_names.txt` already uses for a
 * comparable key/value list (see [EmojiSpeech.kt]'s `loadEmojiNames`), kept
 * as a single `String` in `SharedPreferences` rather than a real list-typed
 * pref, which Android doesn't offer. Both flags are always written (not only
 * when true) so [decodePronunciationDictionary] can tell "explicitly off"
 * apart from data saved before that field existed.
 */
fun encodePronunciationDictionary(entries: List<PronunciationEntry>): String =
    entries.joinToString("\n") {
        "${it.find}\t${it.replacement}\t${if (it.caseSensitive) "1" else "0"}"
    }

/**
 * Decodes an imported dictionary file's raw bytes into text, for
 * [decodePronunciationDictionary] to parse. Tries strict UTF-8 first - this
 * app's own [encodePronunciationDictionary] export format - and falls back to
 * Windows-1252 only when the bytes aren't valid UTF-8 at all. Real-world
 * community dictionaries (IBMTTS's own included) are Windows-1252, not UTF-8;
 * decoding those as UTF-8 doesn't fail loudly, it silently turns every
 * accented byte into U+FFFD, corrupting whichever entries happen to contain
 * one rather than the whole file, which is what made this worth catching
 * before parsing rather than after.
 */
fun decodeImportedDictionaryBytes(bytes: ByteArray): String {
    val strictUtf8 = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    return try {
        strictUtf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        String(bytes, charset("windows-1252"))
    }
}

/** Anchor class purely to resolve [BUNDLED_ENUS_COMMUNITY_DICTIONARY_RESOURCE] via the classloader. */
private class BundledCommunityDictionaryAnchor
private const val BUNDLED_ENUS_COMMUNITY_DICTIONARY_RESOURCE = "/community-dictionary-enus.dic"

/**
 * The English community dictionary (IBMTTS's own `ENUmain.dic`, CC0 - see
 * the license file bundled beside it, and
 * [com.eloquick.tts.COMMUNITY_DICTIONARY_PATH] for
 * where it's kept upstream) shipped inside the app itself as a snapshot -
 * a few hundred informal-word and acronym pronunciation fixes. Deliberately
 * not IBMTTS's much larger `ENURoot.dic` (~68,000 entries): confirmed on a
 * physical device that a dictionary that size hangs `eciLoadDict` on a cold
 * engine slot indefinitely, for every language including English - this
 * app spoke nothing at all until that was reverted back down to this
 * smaller file. Runs through the exact same [decodeImportedDictionaryBytes]
 * -> [decodePronunciationDictionary] path a manually-picked file would -
 * the acronym case-sensitivity default protects it identically. A manual
 * "check for updates" (see [checkCommunityDictionaryUpdate]) can bring in
 * whatever changed upstream since this snapshot was taken, on top of what
 * this function returns; it never runs on its own. Null only if the
 * resource is somehow missing from the build, which a test guards against.
 */
fun bundledEnglishCommunityDictionary(): List<PronunciationEntry>? {
    val bytes = classpathBytes(BundledCommunityDictionaryAnchor::class.java, BUNDLED_ENUS_COMMUNITY_DICTIONARY_RESOURCE)
        ?: return null
    return decodePronunciationDictionary(decodeImportedDictionaryBytes(bytes))
}

/**
 * Lowercased [PronunciationEntry.find]s the bundled English community
 * dictionary contributed, computed once (re-parsing it on every synthesis
 * request would be its own performance bug) and reused by
 * [entriesForLanguage] to keep English-only content out of every other
 * language's engine instance.
 */
private val bundledCommunityFinds: Set<String> by lazy {
    bundledEnglishCommunityDictionary()?.mapTo(HashSet()) { it.find.lowercase(Locale.ROOT) } ?: emptySet()
}

/**
 * [entries] as they should reach [langId]'s own engine instance: unchanged
 * for English, with the bundled community dictionary's contribution
 * filtered back out for every other language.
 *
 * The auto-merge that folds the bundled dictionary into a user's own
 * Pronunciation dictionary (see
 * [com.eloquick.data.AppPreferences]'s own
 * `ensureCommunityDictionaryMerged`) has no language of its own to store -
 * this app's Pronunciation dictionary has always been one global list,
 * applied to whichever voice is speaking. That was never a problem while
 * every entry was hand-typed and small in number; a much larger bundled
 * dictionary (this app briefly shipped IBMTTS's ~68,000-entry `ENURoot.dic`
 * before reverting - see [bundledEnglishCommunityDictionary]'s own doc
 * comment) made it a real one, since English phonetic-annotation entries
 * reaching a French or German engine instance in the same call are exactly
 * the content that hung `eciLoadDict` on a physical device, with no
 * watchdog covering the in-app preview path that hit it. Kept as a safety
 * net even with the smaller dictionary now bundled: filtering by find text
 * (rather than a per-entry flag this app doesn't otherwise keep) needs no
 * new persisted state and no format change to what's already been merged
 * into existing installs' own saved dictionaries: it just recomputes, from
 * the same
 * bundled resource, which finds are the community's rather than the user's
 * own. A user's own entry with the same find as a bundled one is
 * indistinguishable from it here and gets filtered too for a non-English
 * language - the same outcome as if the bundled entry alone were removed,
 * since [mergeDictionaries] never lets an import overwrite a user's prior
 * entry in the first place, so the two would already say the same thing.
 */
fun entriesForLanguage(entries: List<PronunciationEntry>, langId: Int): List<PronunciationEntry> {
    if (familyForLangId(langId) == LanguageFamily.ENGLISH) return entries
    if (bundledCommunityFinds.isEmpty()) return entries
    return entries.filterNot { it.find.lowercase(Locale.ROOT) in bundledCommunityFinds }
}

/**
 * Inverse of [encodePronunciationDictionary]. A line with no tab, or a blank
 * [PronunciationEntry.find], is skipped rather than treated as a load
 * failure - same defensive posture bad persisted data gets elsewhere in
 * [com.eloquick.data.AppPreferences].
 *
 * Trailing flags are optional on read: data saved with only two fields (find,
 * replacement) has no explicit [PronunciationEntry.caseSensitive] of its own,
 * so [looksLikeAcronym] decides it instead of a blanket `false` - see that
 * function's own doc comment for why. Legacy community entries (indicated by
 * a 4th column "1") are discarded.
 */
fun decodePronunciationDictionary(raw: String): List<PronunciationEntry> {
    if (raw.isBlank()) return emptyList()
    return raw.split("\n").mapNotNull { line ->
        // A stray carriage return (CRLF-saved import file) would otherwise
        // poison the trailing "1"/"0" flag checks below ("1\r" != "1").
        val parts = line.trimEnd('\r').split("\t")
        // More than find + replacement + two flags means a tab leaked into
        // a field - skip rather than misread a replacement fragment as a flag.
        if (parts.size < 2 || parts.size > 4) return@mapNotNull null
        // Legacy community entry cleanup: if token 3 is "1", drop it
        if (parts.getOrNull(3) == "1") return@mapNotNull null
        val find = parts[0]
        if (find.isBlank()) return@mapNotNull null
        val explicitCaseSensitive = parts.getOrNull(2)
        PronunciationEntry(
            find = find,
            replacement = parts[1],
            caseSensitive = if (explicitCaseSensitive != null) {
                explicitCaseSensitive == "1"
            } else {
                looksLikeAcronym(find)
            },
        )
    }
}

/**
 * Whether an imported [find] with no explicit case-sensitivity column of its
 * own (a plain two-column line - the shape every real-world community
 * dictionary this app can import actually uses, IBMTTS's own included) looks
 * enough like an acronym that this app's default four-case-variant expansion
 * (see [buildDictionaryFile]'s own doc comment) would be actively wrong
 * rather than merely unnecessary.
 *
 * This is the exact collision this app spent two earlier features
 * ("Word Wisdom", then a hand-verified initialism list, both removed - see
 * [mergeDictionaries]'s own doc comment) trying and failing to solve by
 * curating *which* acronyms to trust: IBMTTS's own community dictionary
 * carries an entry mapping "WITH" to being spelled out letter by letter, and
 * without this, importing it case-insensitively expands that to the ordinary
 * lowercase word "with" everywhere it appears - indistinguishable, at import
 * time, from "AKA"/"LOL"/"OMG" correctly meaning to override only the
 * acronym. A plain-typed find of three or more letters, entirely uppercase
 * and with no lowercase letter anywhere in it, is acronym-shaped regardless
 * of which acronym it is; defaulting those to [PronunciationEntry.caseSensitive]
 * keeps the acronym's own reading intact while leaving the ordinary lowercase
 * word untouched, exactly the outcome the two removed features were chasing
 * word by word. Two letters is deliberately excluded - too many ordinary
 * words ("OK", "hi" typed as "HI", a shouted "NO") are two-letter runs for
 * this heuristic to be worth the false positives at that length.
 */
/**
 * How many of [entries] were kept case sensitive specifically because
 * [looksLikeAcronym] judged their find acronym-shaped - the count an import
 * flow surfaces to the user ("N entries kept case-sensitive to protect
 * ordinary words") rather than leaving the protection silent. Only counts
 * entries actually marked case sensitive: a caller passing entries that came
 * with their own explicit (non-acronym-default) flag doesn't inflate this.
 */
fun countAcronymProtectedEntries(entries: List<PronunciationEntry>): Int =
    entries.count { it.caseSensitive && looksLikeAcronym(it.find) }

private fun looksLikeAcronym(find: String): Boolean {
    if (find.length < 3) return false
    var hasLetter = false
    for (ch in find) {
        if (ch.isLowerCase()) return false
        if (ch.isUpperCase()) hasLetter = true
    }
    return hasLetter
}

/**
 * Adds [incoming] to [existing] for "import from a file": a find already
 * present in [existing] is never touched, matched case-insensitively
 * (deliberately looser than [dictionaryFileFor]'s own exact-case lookup -
 * this is "is the user already overriding this word at all", not the
 * engine's own matching). A user's own prior edit (however it got there)
 * always wins over anything an import would otherwise overwrite it with,
 * and a duplicate within [incoming]
 * itself keeps only its first occurrence. Order is preserved: [existing]
 * first, unchanged, then genuinely new entries appended in [incoming]'s own
 * order.
 *
 * Returns the merged list together with how many entries were actually
 * added, so a caller can tell the user something more useful than silence
 * ("added 40 new entries, 15 were already in your dictionary").
 *
 * This app tried auto-injecting a built-in word list on top of the user's
 * own dictionary twice, both removed 2026-09-11: first a bundled community
 * list ("Word Wisdom", automatically folded in per language), pulled after
 * its ALL-CAPS/mixed-case acronym entries kept re-surfacing the exact
 * letter-by-letter spell-out bug this dictionary feature exists to prevent
 * (AT/DOT/ID hijacking the plain words "at"/"dot"/"id"); then a much
 * smaller, hand-verified "known initialism" fix list built to replace it,
 * pulled at the user's explicit follow-up request - any reading this app
 * alters on its own, beyond what a person actually typed into their own
 * Pronunciation dictionary, is unwanted, full stop, regardless of how
 * carefully verified. This app's synthesis path now matches upstream
 * openevv's own default reading exactly except for a user's own explicit
 * entries - this function's only remaining use.
 */
data class DictionaryMergeResult(val entries: List<PronunciationEntry>, val added: Int)

fun mergeDictionaries(existing: List<PronunciationEntry>, incoming: List<PronunciationEntry>): DictionaryMergeResult {
    // Locale.ROOT, matching dictionaryFileFor's own variant expansion:
    // merge keys and file variants must fold identically.
    val known = HashSet<String>(existing.size + incoming.size)
    existing.forEach { known.add(it.find.lowercase(Locale.ROOT)) }
    val toAdd = ArrayList<PronunciationEntry>()
    for (entry in incoming) {
        val key = entry.find.lowercase(Locale.ROOT)
        if (known.add(key)) toAdd.add(entry)
    }
    return DictionaryMergeResult(existing + toAdd, toAdd.size)
}
