package com.eloquick.tts

/**
 * A curated set of text-normalization fixes for known crash/mispronunciation
 * patterns in the Eloquence/ViaVoice engine family, ported from two NVDA
 * drivers for this engine - davidacm/NVDA-IBMTTS-Driver (maintained since
 * 2009) and fastfinge/eloquence_64's newer, more refined
 * _text_preprocessing.py - both GPL, both years of accumulated community
 * bug-hunting against the real IBM engine. openevv is an independent
 * reimplementation, so not every one of these is guaranteed to still apply -
 * they're preventive and low-risk (plain text substitution) rather than
 * verified against openevv specifically the way [processPunctuation]'s own
 * QUIET-mode stripping or the crash this app itself found and filed was. Larger, more
 * language-specific sets exist upstream in both drivers if more are ever
 * worth porting.
 */
/**
 * A trailing comma is silence to this engine (no pause at all, NVDA-driver
 * changelog 2018 - still true of this engine family), while an attached
 * trailing dash pauses. The dash is attached deliberately ("word-", not
 * "word -"): a detached dash is an isolated token the QUIET/SENTENCES strip
 * would delete right back off, losing the pause this exists to keep.
 */
private val TRAILING_COMMA_RE = Regex(""",\s*$""")

private class TextFix(
    val pattern: Regex,
    val replacement: String,
    val isApplicable: (String) -> Boolean = { true },
) {
    fun apply(text: String): String = if (isApplicable(text)) pattern.replace(text, replacement) else text
}

private val ENGINE_TEXT_FIXES: List<TextFix> = listOf(
    // A trailing em dash can make the engine finish an utterance without
    // ever delivering the index mark queued after it - ported from
    // fastfinge/eloquence_64's newer _text_preprocessing.py.
    TextFix(Regex("—(?=[\"'’”)\\]}]*\\s*$)"), ",") { it.contains('—') },
    // Capital sharp S (ẞ) isn't representable in Windows-1252.
    TextFix(Regex("ẞ"), "ß") { it.contains('ẞ') },
    // "Mc Names" split by whitespace can crash a dictionary-driven lookup.
    TextFix(Regex("\\b(Mc)\\s+([A-Z][a-z]|[A-Z][A-Z]+)", RegexOption.IGNORE_CASE), "$1$2") {
        it.contains("mc", ignoreCase = true)
    },
    // A word directly followed by certain symbols (no space) can make the
    // engine spell the rest of the text out letter by letter.
    TextFix(Regex("""([a-zA-Z]+)([~#$%^*(\{|\}\[<\\•])"""), "$1 $2") { text ->
        text.any { isSymbolFollowedChar(it) }
    },
    // A specific known crash word. The trailing (e)? is preserved:
    // "caesure" must become "seizure", not "seizur".
    TextFix(Regex("(?i)c(ae|æ)sur(e)?"), "seizur$2") { it.contains("sur", ignoreCase = true) },
    // A time-like digit:digit[st|nd|rd|th] pattern (e.g. "3:30st") can crash.
    TextFix(Regex("(?i)(?<!\\d)(\\d{1,2}):(\\d\\d(?:st|nd|rd|th))"), "$1 $2") {
        it.contains(':') && (it.contains("st", ignoreCase = true) || it.contains("nd", ignoreCase = true) || it.contains("rd", ignoreCase = true) || it.contains("th", ignoreCase = true))
    },
    // Three-part clock time with ordinals (e.g. "0:21:21st") which triggers delta duration faults.
    TextFix(Regex("(?i)(?<!\\d)(\\d{1,2}):(\\d{1,2}):(\\d{1,2}(?:st|nd|rd|th))"), "$1:$2 $3") {
        it.contains(':') && (it.contains("st", ignoreCase = true) || it.contains("nd", ignoreCase = true) || it.contains("rd", ignoreCase = true) || it.contains("th", ignoreCase = true))
    },
    // Misspellings of Wednesday that trigger Delta machine deletion faults
    // (chkdelnonseq) in stock Eloquence and openevv, causing the remainder
    // of the utterance to be dropped in screen readers.
    TextFix(Regex("(?i)\\b(w?edhesday|w?enhesday|w?ennesday|w?edesday|edhesday|enhesday)\\b"), "Wednesday") {
        it.contains("esday", ignoreCase = true)
    },
    // "recosp"/"uncosp"/"noncosp"/"anticosp"-shaped words can crash.
    TextFix(Regex("(?i)(?<![a-z])(re|un|non|anti)cosp"), "$1kosp") { it.contains("cosp", ignoreCase = true) },
    // A currency code fused directly onto digits can crash.
    TextFix(Regex("(?<![A-Z])((?:EUR|USD|GBP|JPY|INR|CAD|AUD|CHF|CNY)[A-Z]*)(\\d+)"), "$1 $2") { text ->
        text.any { it.isDigit() } && CURRENCY_ISO_CODES.any { text.contains(it) }
    },
    // "books (s)" reads as "books, parenthesis, s...".
    TextFix(Regex("([A-Za-z]+)\\s+(\\(s\\))", RegexOption.IGNORE_CASE), "$1$2") { it.contains("(s)", ignoreCase = true) },
    // ViaVoice doesn't tolerate a space before trailing punctuation.
    // [A-Za-z]: shouting/headings ("HELLO !") carry the same stray spaces
    // as lowercase, and [^\w\s]+ (punctuation only) avoids the old \W+\s+
    // overlap, which could backtrack across long punct/space runs.
    TextFix(Regex("([A-Za-z]+|\\d+|[^\\w\\s]+)\\s+([:.!;,?](?![A-Za-z]|\\d))"), "$1$2") { text ->
        text.any { isTrailingPunctuationChar(it) }
    },
    // "2:30:15" or "14:30:15" otherwise announces only the hour and minute.
    TextFix(Regex("(?<!\\d)(\\d{1,2}):(\\d+):(\\d+)"), "$1:$2 $3") { it.contains(':') },
    // A letter run fused directly onto a digit ("teamtalk5") makes the
    // engine spell the tail out; splitting keeps it spoken as a word plus
    // number. Digit-first forms ("1st", "5G", "mp3"-class) are untouched by
    // construction. Measured on trypsynth/evvdroid's TextFixes.
    TextFix(Regex("([A-Za-z])(\\d)"), "$1 $2") { text ->
        text.any { it.isDigit() } && text.any { it.isLetter() }
    },
)

private val CURRENCY_ISO_CODES = arrayOf("EUR", "USD", "GBP", "JPY", "INR", "CAD", "AUD", "CHF", "CNY")

private fun isSymbolFollowedChar(c: Char): Boolean = when (c) {
    '~', '#', '$', '%', '^', '*', '(', '{', '|', '}', '[', '<', '\\', '•' -> true
    else -> false
}

private fun isTrailingPunctuationChar(c: Char): Boolean = when (c) {
    ':', '.', '!', ';', ',', '?' -> true
    else -> false
}

/**
 * Collapses a run of the same letter, repeated at least [minRepeats] times
 * in a row, down to one instance - "hmmmmm" becomes "hm", "noooooo" becomes
 * "no". Matches CodeFactory's ETI-Eloquence TTS (es.codefactory.eloquencetts,
 * the actual Nuance-licensed product), whose own settings screen offers this
 * as "Eliminate repeating characters" with a "Minimum characters to ignore"
 * threshold - added here under the same concept. Off by default, same as
 * there.
 *
 * Scoped to letters only (`\p{L}`), never digits or punctuation: a digit run
 * is a real number ("1000000" must never lose its zeros - joinThousandsCommas
 * and the digit-by-digit reading mode both depend on every digit surviving
 * intact), and a punctuation run already has its own, more deliberate
 * handling in [processPunctuation]/[PunctuationMode] - VERBOSE mode spaces
 * repeated marks out to be heard individually, the literal opposite of what
 * this feature does, rather than collapsing them, so applying both to the
 * same characters would fight each other. Letters are the one category with
 * no existing repeat-handling of their own, and are also the actual
 * complaint this answers: an elongated informal spelling ("soooo", "yaaas")
 * read one hesitant letter at a time is worse than the same word collapsed
 * back to its ordinary spelling.
 *
 * [minRepeats] is clamped to at least 3: English (and most Latin-script
 * languages) has essentially no genuine word with three of the same letter
 * in a row - "bookkeeper" tops out at two ("kk") - so 3 is the lowest
 * threshold that can never mangle a real word's legitimate double letter
 * ("book", "off", "committee"). The UI doesn't offer anything lower.
 */
fun eliminateRepeatingCharacters(text: String, minRepeats: Int): String {
    val threshold = minRepeats.coerceAtLeast(3)
    if (text.length < threshold) return text
    return REPEATED_LETTER_RUN_RE.replace(text) { m ->
        if (m.value.length >= threshold) m.value.substring(0, 1) else m.value
    }
}

private val REPEATED_LETTER_RUN_RE = Regex("(\\p{L})\\1+")

private val CAMEL_ACRONYM_TO_WORD_RE = Regex("([A-Z])([A-Z][a-z])")
private val CAMEL_LOWER_TO_UPPER_RE = Regex("([a-z0-9])(?<!Mc)(?=[A-Z])")

/**
 * Splits a mixed-case word like "macOS" or "StellarTrek" into "mac OS" /
 * "Stellar Trek" instead of letting the engine mumble two run-together
 * words as one blob - requested against this exact engine family
 * (davidacm/NVDA-IBMTTS-Driver#164, "add an option for mixed case
 * processing... macOS, StellarTrek", closed with no fix upstream: "please
 * add an update to fix this asap, or at least direct me to an addon that
 * has this feature"). Also the shape a screen-reader user hits reading
 * code out loud (camelCase/PascalCase identifiers), which this app's own
 * punctuation-verbosity feature already targets that use case for.
 *
 * Two deliberate exceptions:
 * - An ALL-CAPS acronym with no lowercase letter at all ("NASA") - nothing
 *   here to split, [CAMEL_LOWER_TO_UPPER_RE] never fires without a
 *   lowercase/digit before the uppercase run.
 * - A "Mc" name ("McDonald", "McKinsey") - [ENGINE_TEXT_FIXES]'s own
 *   Mc-name fix a few lines up *rejoins* exactly that shape to dodge a
 *   dictionary-lookup crash, so splitting it back apart here would undo
 *   that; the negative lookbehind in [CAMEL_LOWER_TO_UPPER_RE] carves out
 *   only that specific two-letter prefix, not every short word starting
 *   with "M" (an "M1Pro"-style name still splits normally).
 */
fun splitMixedCase(text: String): String {
    // Fast path: both patterns need an uppercase letter, so all-lowercase
    // prose (the common case) skips both scans entirely.
    if (!text.any { it.isUpperCase() }) return text
    val acronymSplit = CAMEL_ACRONYM_TO_WORD_RE.replace(text, "$1 $2")
    return CAMEL_LOWER_TO_UPPER_RE.replace(acronymSplit, "$1 ")
}

/**
 * Joins thousands-grouping commas of any length ("1,000,000,000" ->
 * "1000000000") so the engine never reads a "comma hundred" mid-number.
 * Only strict Western grouping is touched (`\d{1,3}` followed by one or
 * more `,\d{3}` groups) - a European decimal comma ("3,14") or anything
 * else oddly-shaped is left exactly alone rather than guessed at.
 */
fun joinThousandsCommas(text: String): String {
    if (!text.contains(',')) return text
    return THOUSANDS_GROUP_RE.replace(text) { m -> m.value.replace(",", "") }
}

private val THOUSANDS_GROUP_RE = Regex("""\b\d{1,3}((?:,\d{3})+)\b""")

/**
 * How a large number gets spoken - the one thing about a number that
 * varies by convention rather than by anything the text itself says.
 * [NATURAL] is [joinThousandsCommas]'s existing behavior: strip the commas
 * and let the engine's own Western-scale reader take it from there.
 *
 * [label] carries its own display text, the same pattern [PunctuationMode]
 * uses - a separate `NUMBER_READING_STYLES: List<Pair<String, String>>`
 * lookup table used to duplicate this mapping in UiStrings.kt (keyed by
 * [name], the exact shape `PUNCTUATION_MODES` was before it was folded into
 * [PunctuationMode.label] the same way), so this enum and that table could
 * silently drift apart - a new entry added to one and not the other, or a
 * label edited in only one place. One source of truth instead.
 */
enum class NumberReadingStyle(val label: String) {
    NATURAL("Natural"),
    INDIAN("Indian numbering (lakh/crore)"),
    DIGIT_BY_DIGIT("Digit by digit"),
}

/**
 * Indian numbering (lakh, crore) as an alternative to Western thousand/
 * million/billion grouping - "1,23,45,678" or "12345678" either way becomes
 * "1 crore 23 lakh 45 thousand 678" instead of the engine's own "twelve
 * million three hundred forty-five thousand..." reading. No number-to-words
 * conversion of its own: the base group (rightmost 3 digits) and every
 * 2-digit group above it are always under 1000, so each one already reads
 * correctly through the engine's own native number reading - this only
 * decides where to insert "thousand"/"lakh"/"crore" and strips the commas
 * and leading zeros that would otherwise confuse it. English only, since
 * the inserted words are English.
 *
 * Below 100,000 there is nothing to regroup - Western and Indian numbering
 * agree exactly up to "99 thousand" - so those numbers pass straight
 * through unconverted (just comma-stripped, same as [joinThousandsCommas]).
 * And only comma-grouped numbers are touched at all, same scope
 * [joinThousandsCommas] already had: a bare digit run with no comma could
 * just as easily be a phone number or an ID, not a quantity, and this app
 * has no way to tell the difference from the text alone.
 */
fun applyIndianNumbering(text: String): String {
    if (!text.contains(',')) return text
    return GROUPED_NUMBER_RE.replace(text) { m ->
        val digits = m.value.replace(",", "")
        if (digits.length < 6) digits else indianGrouped(digits) ?: digits
    }
}

// Unlike THOUSANDS_GROUP_RE, the middle groups accept 2 OR 3 digits, so a
// number already typed in Indian style ("12,34,567") is recognised too,
// not just Western triples.
private val GROUPED_NUMBER_RE = Regex("""\b\d{1,3}(?:,\d{2,3})*,\d{3}\b""")

private val INDIAN_SCALE_WORDS = listOf("thousand", "lakh", "crore", "arab", "kharab", "neel", "padma", "shankh")

/** Null for a number too large for [INDIAN_SCALE_WORDS] to label - the
 * caller falls back to the plain joined digits rather than guess. */
private fun indianGrouped(digits: String): String? {
    val base = digits.takeLast(3).trimStart('0')
    var remaining = digits.dropLast(3)
    val groups = mutableListOf<Pair<String, String>>()
    var scaleIndex = 0
    while (remaining.isNotEmpty()) {
        if (scaleIndex >= INDIAN_SCALE_WORDS.size) return null
        val take = minOf(2, remaining.length)
        groups.add(remaining.takeLast(take) to INDIAN_SCALE_WORDS[scaleIndex])
        remaining = remaining.dropLast(take)
        scaleIndex++
    }
    val sb = StringBuilder()
    for ((group, label) in groups.asReversed()) {
        val stripped = group.trimStart('0')
        if (stripped.isEmpty()) continue // an all-zero group ("0 lakh") is silent, not spoken
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(stripped).append(' ').append(label)
    }
    if (base.isNotEmpty()) {
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(base)
    }
    return sb.ifEmpty { "0" }.toString()
}

/**
 * Splits a digit run into chunks of [groupSize] digits, each chunk left to
 * read naturally on its own ("1234" at [groupSize] 1 -> "1 2 3 4", read as
 * "one two three four"; at 2 -> "12 34", read as "twelve thirty-four") -
 * the classic screen-reader option for content dense with codes, PINs,
 * order numbers, or phone numbers, where a single natural "one thousand two
 * hundred thirty-four" reading is actively unhelpful, but a chunk size a
 * person actually asked for (a card number's groups of 4, say) reads better
 * than every last digit spoken alone. Unlike [applyIndianNumbering] this
 * deliberately does apply to a bare digit run with no comma at all - that
 * is the entire point of the mode - and works for every language, since it
 * inserts no words of its own, only spaces between chunks; each voice still
 * speaks its own number names natively for whatever ends up in a chunk.
 * Decimal numbers ("3.14") are left alone: splitting the digits on either
 * side of the point would separate them from the point itself, and this
 * app has no per-language rule for what that should sound like.
 *
 * Chunks left to right, so a remainder shorter than [groupSize] lands last
 * ("12345" at 2 -> "12 34 5") rather than first - matches how a person
 * reads a number off the page, left to right, not how thousands-grouping
 * commas count from the right.
 *
 * Applied last in [applyEngineTextFixes], after every crash-prevention fix
 * above that expects a number to still be one contiguous run (the date
 * parser fix, the time-ordinal fix, the currency-code separator) - those
 * see and fix the original digits first, exactly as if this mode were off,
 * and only the number that survives that gauntlet gets split afterward.
 */
fun applyDigitByDigit(text: String, groupSize: Int = 1): String {
    if (!text.any { it.isDigit() }) return text
    val size = groupSize.coerceAtLeast(1)
    return DIGIT_RUN_RE.replace(text) { m -> m.value.replace(",", "").chunked(size).joinToString(" ") }
}

private val DIGIT_RUN_RE = Regex(
    """(?<!\.)\b\d{1,3}(?:,\d{2,3})+\b(?!\.\d)|(?<!\.)\b\d{2,}\b(?!\.\d)""",
)

/**
 * tzsche crash-word family, multi-group form (fastfinge/eloquence_64). The
 * prefix is five independently optional pieces (leading digits/punctuation,
 * a word ending in underscore, a bare underscore run, a consonant run,
 * trailing digits) - a static "$1 $2 $3 $4 $5 tz sche" template would emit a
 * literal separator space for every group that didn't match, e.g. "tzsche"
 * alone would become "     tz sche". Applied with a transform instead so
 * only the groups that actually matched contribute a space.
 */
private val TZSCHE_RE = Regex(
    "\\b(\\d+|\\W+)?(\\w+_+)?(_+)?([bcdfghjklmnpqrstvwxz]+)?(\\d+)?t+z[s]che",
    RegexOption.IGNORE_CASE,
)

private fun applyTzscheFix(text: String): String {
    // Fast path: the pattern always ends in a literal "zsche" run, so text
    // without one skips the five-group scan entirely.
    if (!text.contains("zsche", ignoreCase = true)) return text
    return TZSCHE_RE.replace(text) { match ->
        val prefix = (1..5).joinToString("") { match.groupValues[it] }
        if (prefix.isEmpty()) "tz sche" else "$prefix tz sche"
    }
}

/**
 * Per-language extra fixes beyond [ENGINE_TEXT_FIXES], mirroring how
 * davidacm/NVDA-IBMTTS-Driver keeps per-language dicts (spanish_fixes,
 * french_fixes, ...) alongside the shared ones. Only substitutions that
 * are inert-or-better outside their language live in the shared list;
 * anything that could misfire elsewhere is gated here on [LanguageFamily].
 */
/**
 * Email @ spoken per voice language ("at" / "arobase" / "arroba", like
 * upstream) - one factory instead of five copies of the same pattern that
 * would otherwise drift apart one edit at a time.
 *
 * The three plain-"at" call sites (English, German, Other) deliberately
 * stay per-language rather than hoisted into the shared list above: shared
 * fixes run first, so a shared "at" would consume every @ before French's
 * "arobase" and Spanish's "arroba" ever see it. Order is the feature.
 */
private fun atRule(word: String) = TextFix(
    Regex("([a-zA-Z0-9_.+-]+)@(\\w+)"),
    "$1 $word $2",
) { it.contains('@') }

private val EXTRA_FIXES: Map<LanguageFamily, List<TextFix>> = mapOf(
    // fastfinge/eloquence_64 ports (its _text_preprocessing.py): a word run
    // straight into a dotted word ("end.Start") garbles as one token.
    LanguageFamily.ENGLISH to listOf(
        TextFix(Regex("(\\w+)\\.([a-zA-Z]+)"), "$1 dot $2") { it.contains('.') },
        // Email @ spoken out (was shared for all languages; English says "at").
        atRule("at"),
        // juar + long suffix crash shape.
        TextFix(Regex("(juar)([a-z']{9,})", RegexOption.IGNORE_CASE), "$1 $2") { it.contains("juar", ignoreCase = true) },
        // ECI date-parser bug (fastfinge, with its own unit test upstream)
        TextFix(
            Regex(
                "\\b(\\d+)((?:st|nd|rd|th)?) (?!(?:January|February|March|April|May|June|July|August" +
                    "|September|October|November|December)\\b)" +
                    "(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)([a-z]+)",
                RegexOption.IGNORE_CASE,
            ),
            "$1$2  $3$4",
        ) { it.any { ch -> ch.isDigit() } },
        // h' + r/v + e crash shape.
        TextFix(Regex("\\b(|\\d+|\\W+)h'(r|v)[e]", RegexOption.IGNORE_CASE), "$1h $2e") { it.contains("h'", ignoreCase = true) },
        // Consonant cluster + "hhes"/"hes" + word continuation
        TextFix(
            Regex(
                "\\b(\\w+[bdfhjlmnqrvz])(h+[he]s)([abcdefghjklmnopqrstvwy]\\w+)\\b",
                RegexOption.IGNORE_CASE,
            ),
            "$1 $2$3",
        ) { it.contains("hes", ignoreCase = true) },
        TextFix(
            Regex(
                "\\b(\\w+[bdfhjlmnqrvz])(h+[he]s)(iron+[degins]?)",
                RegexOption.IGNORE_CASE,
            ),
            "$1 $2$3",
        ) { it.contains("hes", ignoreCase = true) && it.contains("iron", ignoreCase = true) },
        // Same crash shape, but with an apostrophe somewhere in the cluster
        TextFix(
            Regex(
                "\\b(\\w+'{1,}[bcdfghjklmnpqrstvwxyz])'*(h+[he]s)([abcdefghijklmnopqrstvwy]\\w+)\\b",
                RegexOption.IGNORE_CASE,
            ),
            "$1 $2$3",
        ) { it.contains('\'') && it.contains("hes", ignoreCase = true) },
        TextFix(
            Regex(
                "\\b(\\w+[bcdfghjklmnpqrstvwxyz])('{1,}h+[he]s)([abcdefghijklmnopqrstvwy]\\w+)\\b",
                RegexOption.IGNORE_CASE,
            ),
            "$1 $2$3",
        ) { it.contains('\'') && it.contains("hes", ignoreCase = true) },
        // A run of consonants broken by two apostrophes crashes.
        TextFix(
            Regex(
                "\\b([bcdfghjklmnpqrstvwxz]+)'([bcdefghjklmnpqrstvwxz']+)'([drtv][aeiou]?)",
                RegexOption.IGNORE_CASE,
            ),
            "$1 $2 $3",
        ) { it.contains('\'') },
        // "you're'd"-shaped double-contractions crash.
        TextFix(Regex("\\b(you+)'(re)+'([drv]e?)", RegexOption.IGNORE_CASE), "$1 $2 $3") { it.contains('\'') },
    ),
    LanguageFamily.GERMAN to listOf(
        // Compound-word crash shapes
        TextFix(Regex("\\bdane-ben\\b", RegexOption.IGNORE_CASE), "dane- ben") { it.contains("dane-ben", ignoreCase = true) },
        TextFix(Regex("\\bdage-gen\\b", RegexOption.IGNORE_CASE), "dage- gen") { it.contains("dage-gen", ignoreCase = true) },
        TextFix(
            Regex(
                "(audio|video|macro|general)(-)(en[a-z]*)",
                RegexOption.IGNORE_CASE,
            ),
            "$1 $3",
        ) { it.contains('-') },
        // Email @ in German voices.
        atRule("at"),
    ),
    LanguageFamily.SPANISH to listOf(
        // Email @ in Spanish voices ("arroba", like upstream).
        atRule("arroba"),
        // Ordinal feminine marker after very long numbers
        TextFix(Regex("(\\d{12,}[123679])(ª)"), "$1 $2") { it.contains('ª') },
        // ViaVoice's Spanish time parser crashes when the minute part runs 20-59 with dots
        TextFix(Regex("([01]?[0-9]|2[0-3])\\.([2-5][0-9])\\.([0-5][0-9])"), "$1:$2:$3") { it.contains('.') },
        // Space-separated thousands ("3 456") run together without this
        TextFix(Regex("(\\d+) (\\d{3})(?!\\d)"), "$1  $2") { it.contains(' ') && it.any { ch -> ch.isDigit() } },
        // Currency amounts with grouped thousands
        TextFix(Regex("([€$£¥]\\d{1,3})((\\s\\d{3})+\\.\\d{2})"), "$1 $2") { text ->
            text.any { it == '€' || it == '$' || it == '£' || it == '¥' }
        },
    ),
    LanguageFamily.FRENCH to listOf(
        // Email @ in French voices ("arobase", like upstream).
        atRule("arobase"),
        // "tranquille" crash shape (fastfinge).
        TextFix(Regex("(?<=anq)uil(?=l)", RegexOption.IGNORE_CASE), "i") { it.contains("anq", ignoreCase = true) },
        // "F1".."F12" otherwise read as "1 franc".."12 francs"
        TextFix(Regex("f(?=\\s?\\d)", RegexOption.IGNORE_CASE), "f ") { it.contains('f', ignoreCase = true) && it.any { ch -> ch.isDigit() } },
        // "quil" at a word edge mispronounces
        TextFix(Regex("quil(?=\\W|$)", RegexOption.IGNORE_CASE), "kil") { it.contains("quil", ignoreCase = true) },
        // A right paren inside a word is always spoken despite the punctuation level
        TextFix(Regex("(\\w\\))(?=\\w)"), "$1 ") { it.contains(')') },
        // Masculine ordinal abbreviation
        TextFix(Regex("\\bn°", RegexOption.IGNORE_CASE), "numéro") { it.contains('°') },
    ),
    LanguageFamily.OTHER to listOf(
        // Email @ fallback for Italian/Polish voices ("at").
        atRule("at"),
    ),
)

private val VULGAR_FRACTIONS: Map<Char, String> = mapOf(
    '½' to "half",
    '¼' to "one quarter",
    '¾' to "three quarters",
    '⅓' to "one third",
    '⅔' to "two thirds",
    '⅛' to "one eighth",
    '⅜' to "three eighths",
    '⅝' to "five eighths",
    '⅞' to "seven eighths",
)

private val FRACTION_NAME_MAP: Map<String, String> = mapOf(
    "1/2" to "half", "½" to "half",
    "1/4" to "quarter", "¼" to "quarter",
    "3/4" to "three quarters", "¾" to "three quarters",
    "1/3" to "third", "⅓" to "third",
    "2/3" to "two thirds", "⅔" to "two thirds",
    "1/8" to "one eighth", "⅛" to "one eighth",
    "3/8" to "three eighths", "⅜" to "three eighths",
    "5/8" to "five eighths", "⅝" to "five eighths",
    "7/8" to "seven eighths", "⅞" to "seven eighths",
)

private val MIXED_NUMBER_RE = Regex(
    // Trailing (?![/-]): a fraction-shaped prefix of a date span
    // ("1/2/2024", left raw by applyNaturalDateReading under AS_WRITTEN)
    // must not rewrite to "1 and a half/2024".
    """\b(\d+)\s*(?:and\s+)?(1/2|1/4|3/4|1/3|2/3|1/8|3/8|5/8|7/8|[½¼¾⅓⅔⅛⅜⅝⅞])\b(?![/\-])""",
)

fun applyFractions(text: String): String {
    if (!text.any { it in "½¼¾⅓⅔⅛⅜⅝⅞" || it == '/' }) return text
    var result = MIXED_NUMBER_RE.replace(text) { m ->
        val whole = m.groupValues[1]
        val frac = m.groupValues[2]
        val name = FRACTION_NAME_MAP[frac] ?: frac
        val sep = if (name == "half" || name == "quarter" || name == "third") "and a" else "and"
        "$whole $sep $name"
    }
    if (result.any { it in "½¼¾⅓⅔⅛⅜⅝⅞" }) {
        val sb = StringBuilder(result.length + 16)
        var idx = 0
        val end = result.length
        while (idx < end) {
            val ch = result[idx]
            val name = VULGAR_FRACTIONS[ch]
            if (name != null) {
                if (sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
                sb.append(name)
                // Mirror the leading side: "½cup" must become "half cup",
                // not "halfcup" (see EmojiSpeech.describeEmoji's trailing gap).
                val next = idx + 1
                if (next < end && !result[next].isWhitespace()
                    && (result[next].isLetterOrDigit())) sb.append(' ')
            } else {
                sb.append(ch)
            }
            idx++
        }
        result = sb.toString()
    }
    return result
}

private val DIMENSION_RE = Regex("""\b(\d{1,5})\s*[xX]\s*(\d{1,5})\b""")

fun applyDimensions(text: String): String {
    if (!text.contains('x') && !text.contains('X')) return text
    return DIMENSION_RE.replace(text) { m ->
        "${m.groupValues[1]} by ${m.groupValues[2]}"
    }
}

private val TEMPERATURE_RE = Regex("""(?<!\w)([+-]?\d+)\s*°\s*([CcFf])\b""")

fun applyTemperatures(text: String): String {
    if (!text.contains('°')) return text
    return TEMPERATURE_RE.replace(text) { m ->
        val signAndNum = m.groupValues[1]
        val unit = m.groupValues[2].uppercase()
        val num = if (signAndNum.startsWith("-")) "minus ${signAndNum.substring(1)}" else signAndNum
        "$num degrees $unit"
    }
}

private val CURRENCY_MAGNITUDE_RE = Regex("""([$€£¥₹])(\d+(?:\.\d+)?)\s*([kKmMbBtT])\b""")

fun applyCurrencyMagnitudes(text: String): String {
    if (!text.any { it == '$' || it == '€' || it == '£' || it == '¥' || it == '₹' }) return text
    return CURRENCY_MAGNITUDE_RE.replace(text) { m ->
        val symbol = m.groupValues[1]
        val amount = m.groupValues[2]
        val mag = when (m.groupValues[3].uppercase()) {
            "K" -> "thousand"
            "M" -> "million"
            "B" -> "billion"
            "T" -> "trillion"
            else -> m.groupValues[3]
        }
        "$symbol$amount $mag"
    }
}

private val URL_PROTOCOL_RE = Regex("""\b(https?|ftp)://""", RegexOption.IGNORE_CASE)

fun applyUrlProtocols(text: String): String {
    if (!text.contains("://", ignoreCase = true)) return text
    return URL_PROTOCOL_RE.replace(text) { m ->
        "${m.groupValues[1]} colon slash slash "
    }
}

fun applyEngineTextFixes(
    text: String,
    family: LanguageFamily = LanguageFamily.OTHER,
    numberReadingStyle: NumberReadingStyle = NumberReadingStyle.NATURAL,
    digitGroupSize: Int = 1,
): String {
    var result = applyUrlProtocols(text)
    result = if (numberReadingStyle == NumberReadingStyle.INDIAN && family == LanguageFamily.ENGLISH) {
        applyIndianNumbering(result)
    } else {
        joinThousandsCommas(result)
    }
    if (family == LanguageFamily.ENGLISH) {
        result = applyCurrencyMagnitudes(result)
        result = applyDimensions(result)
        result = applyTemperatures(result)
        result = applyFractions(result)
        result = applyContextualRomanNumerals(result)
    }
    for (fix in ENGINE_TEXT_FIXES) {
        result = fix.apply(result)
    }
    for (fix in EXTRA_FIXES[family].orEmpty()) {
        result = fix.apply(result)
    }
    result = splitMixedCase(result)
    if (family == LanguageFamily.ENGLISH) result = applyTzscheFix(result)
    if (numberReadingStyle == NumberReadingStyle.DIGIT_BY_DIGIT) result = applyDigitByDigit(result, digitGroupSize)
    return result
}

/**
 * Every knob the shared text pipeline reads, in one value. This used to be
 * eleven positional parameters on [prepareTextForSynthesis] - four of them
 * adjacent Booleans - which meant swapping two arguments at either call site
 * compiled silently and changed what users hear (the service and the preview
 * each passed the full list positionally, in two places to keep in sync).
 * Callers now pass one of these, built with named arguments (see
 * `TtsConfig.pipelineOptions()` for the single production mapping), so a
 * transposition can't compile and a new knob gets a default here instead of
 * a twelfth parameter. Defaults match the old parameter defaults exactly.
 */
data class TextPipelineOptions(
    val mode: PunctuationMode = PunctuationMode.QUIET,
    val describeEmoji: Boolean = true,
    val numberReadingStyle: NumberReadingStyle = NumberReadingStyle.NATURAL,
    val alwaysReadSymbols: Set<Char> = emptySet(),
    val readSymbolsByName: Boolean = false,
    val naturalTimeReading: Boolean = true,
    val naturalDateReading: Boolean = true,
    val dateOrder: DateOrder = DateOrder.AS_WRITTEN,
    val digitGroupSize: Int = 1,
    val eliminateRepeats: Boolean = false,
    val repeatThreshold: Int = 3,
)

/**
 * The one text pipeline every synthesis path runs - the system TTS service
 * and the in-app voice preview alike (the preview used to skip the fixes
 * entirely, so what you heard sampling a voice wasn't what TalkBack would
 * get). The user's own pronunciation dictionary is not one of these steps -
 * it used to run first here, before even emoji description, back when it
 * was plain Kotlin substitution; it's applied by the engine's own
 * dictionary lookup now (see [PronunciationDictionary.dictionaryFileFor]
 * and the `dictPath` this pipeline's own caller passes to
 * [EloquenceNative.nativeSynthesize] alongside this function's output), so
 * there's nothing for this function to do with it at all. Emoji
 * descriptions run first here instead: a bare emoji glyph reads as
 * pure punctuation to [processPunctuation] (no letters or digits in it),
 * so QUIET mode would otherwise strip it silently before it ever became
 * words - describing it first turns it into ordinary text the rest of the
 * pipeline treats like any other word. Eliminate repeats
 * ([eliminateRepeatingCharacters]) runs right after that, before anything
 * else gets a look at the text - it only ever touches letters, never digits
 * or punctuation (see that function's own comment), so it can't disturb a
 * time/date span, a number, or anything the steps below look for; running
 * it this early just means every later step already sees the collapsed
 * spelling, same as if the text had been typed that way to begin with.
 * Natural time/date reading run next, before anything else gets a look at a
 * `10:30`/`14/07/2024`-shaped span - once one is recognised it becomes
 * ordinary words with no colons or slashes left in it at all, so none of
 * the punctuation handling or the other digit-oriented engine fixes below
 * (the h:mm:ss splitter, the digit-run crash guards) ever see it, and there
 * is nothing left for them to disagree with. Then trailing comma (Eloquence
 * ignores it - no pause at all - so it becomes a dash, which pauses;
 * skipped in VERBOSE, where hearing the literal comma is the point), then
 * punctuation handling (it deletes/reflows text), engine fixes last so they
 * see the text closest to what the engine receives - same ordering
 * rationale the service already documented.
 */
fun prepareTextForSynthesis(
    rawText: String,
    family: LanguageFamily,
    options: TextPipelineOptions = TextPipelineOptions(),
): String {
    val described = if (options.describeEmoji) describeEmoji(rawText) else rawText
    val deduped = if (options.eliminateRepeats) {
        eliminateRepeatingCharacters(described, options.repeatThreshold)
    } else {
        described
    }
    val timed = if (options.naturalTimeReading) applyNaturalTimeReading(deduped) else deduped
    val dated = if (options.naturalDateReading) applyNaturalDateReading(timed, options.dateOrder) else timed
    val dashed = if (options.mode == PunctuationMode.VERBOSE || !dated.contains(',')) dated
    else TRAILING_COMMA_RE.replace(dated, "-")
    val punctuated = processPunctuation(dashed, options.mode, options.alwaysReadSymbols, options.readSymbolsByName)
    return applyEngineTextFixes(punctuated, family, options.numberReadingStyle, options.digitGroupSize)
}
