package com.eloquick.tts

// Common abbreviations whose trailing dot must not end a chunk: cutting
// after "Mr." leaves a splinter utterance and (measured on the same engine
// family by trypsynth/evvdroid's TextPieces) costs ~1.5s of extra pause.
// Keep this list to unambiguous title/initial forms; "e.g."/"U.S." are
// caught by the inner-dot/single-letter rules below.
private val CHUNK_ABBREVIATIONS = setOf(
    "mr", "mrs", "ms", "dr", "st", "sr", "jr", "prof", "rev", "gen",
    "sen", "rep", "gov", "lt", "col", "sgt", "capt", "cmdr", "adm",
    "vs", "etc", "inc", "ltd", "co", "corp", "ave", "blvd", "rd"
)

/**
 * True when the '.' at [dotIndex] (followed by whitespace/end) ends a
 * sentence. Mirrors evvdroid's endsSentence guards: closers stripped by the
 * caller scan, single initials ("J."), inner-dot tokens ("e.g.", "U.S.",
 * "3.14"), short capitalized words ("Mr.") and digit stops ("3.") never end.
 */
internal fun isChunkSentenceEnd(text: String, dotIndex: Int): Boolean {
    if (dotIndex <= 0) return false
    // Walk back over the token preceding the dot.
    var tokEnd = dotIndex - 1
    // Token with an inner dot ("e.g", "U.S", "3.14") is never a sentence end.
    var i = tokEnd
    while (i >= 0 && !text[i].isWhitespace()) {
        if (text[i] == '.') return false
        i--
    }
    val tokStart = i + 1
    val tokLen = tokEnd - tokStart + 1
    if (tokLen <= 0) return false
    val tok = text.substring(tokStart, dotIndex)
    // Known abbreviation in any casing ("Mr.", "mr.", "PROF.") never ends.
    if (tok.lowercase(java.util.Locale.ROOT) in CHUNK_ABBREVIATIONS) return false
    // Single letter initial ("J. Smith").
    if (tokLen == 1 && tok[0].isLetter()) return false
    // Digit stop ("version 3. next", "item 12. done").
    if (tok.all { it.isDigit() }) return false
    // Short capitalized word ("Th.", "Jas.", "No.") - suspect, not a sentence.
    if (tokLen <= 3 && tok[0].isUpperCase() && tok.all { it.isLetter() }) return false
    // Two-letter all-caps ("TV.", "PC.").
    if (tokLen == 2 && tok.all { it.isUpperCase() }) return false
    return true
}

fun chunkRangesForSynthesis(text: String, maxChars: Int = 800): List<IntRange> {
    // A non-positive budget would loop forever below (breakAt 0, start never
    // advances) - clamp to at least one character; callers never pass one,
    // this is defense against a programming error, not a tuning knob.
    val budget = maxChars.coerceAtLeast(1)
    if (text.length <= budget) return listOf(0 until text.length)

    val ranges = mutableListOf<IntRange>()
    var start = 0
    val textLen = text.length
    while (start < textLen) {
        val remaining = textLen - start
        if (remaining <= budget) {
            ranges.add(start until textLen)
            break
        }
        val end = start + budget
        var sentenceBreak = -1
        var lastWhitespace = -1
        var k = end - 1
        while (k > start) {
            val c = text[k]
            if (c.isWhitespace()) {
                if (lastWhitespace == -1) {
                    lastWhitespace = k - start
                }
                val prev = text[k - 1]
                if (prev == '.' || prev == '!' || prev == '?' || prev == '…'
                    || prev == '。' || prev == '！' || prev == '？') {
                    // A dot needs the abbreviation guards; other enders
                    // (!, ?, …, 。！？) always break.
                    if (prev != '.' || isChunkSentenceEnd(text, k - 1)) {
                        sentenceBreak = k - start
                        break
                    }
                }
            }
            k--
        }
        val breakAt = if (sentenceBreak != -1) {
            sentenceBreak
        } else if (lastWhitespace > 0) {
            lastWhitespace
        } else {
            budget
        }

        ranges.add(start until (start + breakAt))
        start += breakAt
        // Skip the whitespace the break landed on so the next chunk doesn't
        // open with a leading space that would otherwise become a tiny,
        // near-silent utterance of its own.
        while (start < textLen && text[start].isWhitespace()) start++
    }
    return ranges
}
