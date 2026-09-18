package com.eloquick.tts

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
                if (prev == '.' || prev == '!' || prev == '?' || prev == '…') {
                    sentenceBreak = k - start
                    break
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
