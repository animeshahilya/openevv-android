package com.eloquick.tts

/**
 * Speaks emoji instead of silently losing them. [encodeForEngine] maps the
 * whole utterance down to Windows-1252, which has no emoji at all - every
 * one used to become a literal '?' (or, before that function's own fix,
 * "??": most emoji are supplementary-plane code points, a surrogate *pair*
 * in a Kotlin [Char] sequence). A blind listener heard nothing where there
 * was an emoji, or a stray question mark that looked like a typo. A spoken
 * description survives QUIET-mode punctuation stripping too (see
 * [processPunctuation]) - it's ordinary letters, so it never looks like an
 * isolated punctuation token to strip the way the bare glyph would have.
 *
 * Descriptions come from Unicode's own `emoji-test.txt` (CLDR short names -
 * the same wording iOS/Android's own screen readers use for a lone emoji;
 * see [EMOJI_NAMES]), bundled as a plain classpath resource rather than an
 * Android asset so this file stays as Context-free and directly
 * JVM-unit-testable as the rest of this package. Matching is greedy
 * longest-code-point-sequence-first, so a flag, a ZWJ family, or a
 * skin-toned face gets its own combined name instead of one word per
 * component landing back to back.
 */
private const val EMOJI_RESOURCE = "/emoji_names.txt"

/**
 * Below this code point, [EMOJI_NAMES] has no single-code-point entry left
 * to miss - keycap sequences ("#", "*", digits + U+20E3) start lower still,
 * but always alongside U+20E3 itself, which clears this bar on its own, so
 * the sequence as a whole is never missed. [COPYRIGHT_LIKE] covers the two
 * genuine exceptions: (c)/(r) are single code points below this line with
 * no such companion. A cheap single-pass filter so ordinary prose, the
 * common case, skips the code-point-by-code-point scan in [describeEmoji]
 * entirely instead of doing a wasted lookup at every single character.
 */
private const val EMOJI_TRIGGER_THRESHOLD = 0x2000

/** The two [EMOJI_NAMES] entries below [EMOJI_TRIGGER_THRESHOLD] that
 * aren't part of a keycap sequence - see there for why they need this. */
private val COPYRIGHT_LIKE = charArrayOf('©', '®')

private fun mightContainEmoji(text: String): Boolean {
    for (ch in text) {
        if (ch.code >= EMOJI_TRIGGER_THRESHOLD || ch in COPYRIGHT_LIKE) return true
    }
    return false
}

/** Anchor class purely to resolve [EMOJI_RESOURCE] via the classloader. */
private class EmojiNamesAnchor

private class EmojiData(
    val names: Map<String, String>,
    val starters: Set<Int>,
    val maxCodePoints: Int,
)

private val EMOJI_DATA: EmojiData by lazy { loadEmojiData() }
private val EMOJI_NAMES: Map<String, String> get() = EMOJI_DATA.names
private val EMOJI_STARTERS: Set<Int> get() = EMOJI_DATA.starters
private val MAX_EMOJI_CODEPOINTS: Int get() = EMOJI_DATA.maxCodePoints

private fun loadEmojiData(): EmojiData {
    val map = HashMap<String, String>(6000)
    val starters = HashSet<Int>(1500)
    var maxCp = 1
    // Shared classpathLines loader (see ClasspathResources.kt), not a
    // second copy of the anchor/stream/reader shape.
    classpathLines(EmojiNamesAnchor::class.java, EMOJI_RESOURCE).forEach { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) return@forEach
        // A single malformed line (bad hex, empty name) skips itself -
        // it must never fail the whole 5,000-entry load the way
        // hex.toInt(16)'s throw once did.
        val name = line.substring(tab + 1).trim()
        if (name.isEmpty()) return@forEach
        val codePoints = line.substring(0, tab).split('-')
            .map { it.toIntOrNull(16)?.takeIf { cp -> cp in 0..0x10FFFF } ?: return@forEach }
        if (codePoints.isEmpty()) return@forEach
        starters.add(codePoints[0])
        if (codePoints.size > maxCp) maxCp = codePoints.size
        val key = StringBuilder(8)
        for (cp in codePoints) {
            key.appendCodePoint(cp)
        }
        map[key.toString()] = name
    }
    return EmojiData(map, starters, maxCp)
}

/** One matched emoji sequence: [end] is the index just past it in the
 * source string, [name] its spoken description. */
private class EmojiMatch(val end: Int, val name: String)

/** Longest-code-point-sequence-first match starting exactly at [i], or null
 * if no known emoji sequence starts there. */
private fun matchEmojiAt(text: String, i: Int): EmojiMatch? {
    val firstCp = text.codePointAt(i)
    if (firstCp !in EMOJI_STARTERS) return null

    val textLen = text.length
    var cur = i
    var available = 0
    while (cur < textLen && available < MAX_EMOJI_CODEPOINTS) {
        val cp = text.codePointAt(cur)
        cur += Character.charCount(cp)
        available++
    }
    var n = available
    while (n >= 1) {
        val end = text.offsetByCodePoints(i, n)
        val name = EMOJI_NAMES[text.substring(i, end)]
        if (name != null) return EmojiMatch(end, name)
        n--
    }
    return null
}

/**
 * Replaces every recognised emoji sequence in [text] with its spoken name.
 * Everything else, including an emoji this data set doesn't (yet) know
 * about, is left untouched - those still fall through to
 * [encodeForEngine]'s own '?' handling same as before this existed.
 *
 * A run of the *same* emoji repeated back to back - "🎉🎉🎉🎉🎉", a common
 * way to type emphasis, or "🎉 🎉 🎉" with spaces - collapses to one name
 * plus a count ("party popper, 5 times") instead of speaking the same
 * phrase five times over. Hearing a name once with a count is real
 * information; hearing it repeated is just noise once you already caught it
 * the first time - the same reasoning [PunctuationMode.VERBOSE] applies to
 * runs of the same punctuation mark, just counted here instead of spaced
 * out, since unlike "!!!" the count itself (not each repetition) is what a
 * listener needs from a run of identical emoji. Repeats are matched by
 * description, not by exact code points, so a plain and an
 * explicitly-variation-selected copy of the same emoji still count
 * together - what they render as is identical to a listener either way.
 */
fun describeEmoji(text: String): String {
    // Cheap filter first: EMOJI_NAMES is a ~6k-entry lazy load, and the
    // first-ever plain-prose utterance must not pay a full resource parse
    // for nothing. (Empty after the filter still returns text, so a failed
    // load behaves exactly as before.)
    if (text.isEmpty() || !mightContainEmoji(text)) return text
    if (EMOJI_NAMES.isEmpty()) return text

    val sb = StringBuilder(text.length + 16)
    val len = text.length
    var i = 0
    while (i < len) {
        val match = matchEmojiAt(text, i)
        if (match != null) {
            var count = 1
            var runEnd = match.end
            while (true) {
                var next = runEnd
                while (next < len && text[next].isWhitespace()) next++
                val repeat = if (next < len) matchEmojiAt(text, next) else null
                if (repeat == null || repeat.name != match.name) break
                count++
                runEnd = repeat.end
            }
            if (sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
            sb.append(match.name)
            if (count > 1) sb.append(", ").append(count).append(" times")
            i = runEnd
            if (i < len && !text[i].isWhitespace()) sb.append(' ')
        } else {
            val cp = text.codePointAt(i)
            sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
    }
    return sb.toString()
}
