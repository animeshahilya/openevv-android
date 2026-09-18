package com.eloquick.tts

/**
 * A clock time as `HH:MM`, optionally with an AM/PM suffix, and not
 * immediately followed by a third `:NN` group (which would make it a
 * duration/timestamp like `1:30:45`, already handled by the existing
 * h:mm:ss fix in `EngineTextFixes.kt` rather than this one - a real clock
 * time never has a seconds field in ordinary prose).
 *
 * Ends in a lookahead for "not immediately followed by a letter or digit",
 * not `\b`: the suffix can legitimately end on a literal dot ("p.m."), and
 * `\b` can never sit between two non-word characters, so it would force the
 * engine to backtrack off that final dot and leave it dangling, unmatched,
 * right after "PM" in the output.
 */
private val TIME_RE = Regex(
    """\b([01]?\d|2[0-3]):([0-5]\d)(?!:\d)(\s*[AaPp]\.?[Mm]\.?)?(?![A-Za-z0-9])""",
)

/**
 * Reads a clock time the way a person says it out loud, instead of the
 * engine's own digit-by-digit or "ten colon thirty" reading of a bare
 * `HH:MM` pattern: "10:30 AM" -> "ten thirty AM", "12:05 PM" -> "twelve oh
 * five PM", "00:30" -> "midnight thirty", "14:20" -> "fourteen twenty".
 *
 * Deliberately does not convert between 12-hour and 24-hour: an AM/PM
 * suffix means the hour is already 1-12 and gets echoed with it; no suffix
 * means the hour is read exactly as written (0 and 12 as "midnight"/"noon",
 * everything else - including 13-23 - as its own number, matching how a
 * 24-hour clock display is actually read aloud). Guessing a conversion
 * either way would be a guess, not a reading - see the "no repro to verify
 * against" reasoning for date-order in EngineTextFixes.kt.
 */
fun applyNaturalTimeReading(text: String): String {
    if (!text.any { it == ':' }) return text
    return TIME_RE.replace(text) { m ->
        val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        val suffix = m.groupValues[3].trim()

        // TIME_RE's own hour group matches 0-23 unconditionally, needed for
        // the no-suffix 24-hour case below, but a suffix only ever makes
        // sense on a 12-hour display (1-12) - "13:00 PM"/"00:15 AM" aren't a
        // real clock reading either way, they're malformed input, and
        // confidently speaking "thirteen PM" for one would be a guess, not
        // a reading, the same reasoning this function's own doc comment
        // already applies to not guessing a 12-/24-hour conversion.
        if (suffix.isNotEmpty() && hour !in 1..12) return@replace m.value

        val hourWord = when {
            suffix.isNotEmpty() -> cardinalWord(hour) // 1-12 already, per a 12-hour clock's own display
            hour == 0 -> "midnight"
            hour == 12 && minute == 0 -> "noon"
            else -> cardinalWord(hour)
        }
        val minuteWord = if (minute == 0) null else minutesPastWord(minute)
        val suffixWord = when {
            suffix.isEmpty() -> null
            suffix.first().lowercaseChar() == 'a' -> "AM"
            else -> "PM"
        }
        buildString {
            append(hourWord)
            if (minuteWord != null) {
                append(' ')
                append(minuteWord)
            }
            if (suffixWord != null) {
                append(' ')
                append(suffixWord)
            }
        }
    }
}
