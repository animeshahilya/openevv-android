package com.eloquick.tts

private val MONTH_NAMES = arrayOf(
    "", "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

private val DATE_RE = Regex("""\b(\d{1,4})([/\-])(\d{1,4})(?:([/\-])(\d{1,4}))?\b""")

/**
 * Numeric pairs that pass the day/month unambiguity check below (one part
 * over 12, the other 1-12) but read as a date almost nowhere in practice -
 * "24/7" (round-the-clock availability) and "16/9" (a screen aspect ratio)
 * are common enough, and specific enough, that a real writer meaning a date
 * this way is the rare case, not the common one. Narrow and explicit rather
 * than a broader heuristic, which would have to guess at intent this
 * function otherwise never needs to - see this function's own doc comment
 * on why an ambiguous pair is left alone entirely instead of guessed at;
 * this is the same principle applied to a pair that isn't numerically
 * ambiguous but is idiomatically one anyway.
 */
private val NON_DATE_IDIOMS = setOf("24/7", "16/9")

/**
 * Which of a date's two ambiguous parts (both 1-12, so magnitude alone
 * can't tell day from month - see [applyNaturalDateReading]'s own doc
 * comment on why that case is normally left untouched) to read as the day.
 * [AS_WRITTEN] keeps today's behavior exactly: an ambiguous pair stays
 * unconverted rather than guessed at - the safe, conservative default, and
 * still what every existing install gets with no setting touched.
 * [DAY_FIRST]/[MONTH_FIRST] are an explicit, opt-in user preference for
 * resolving *only* that ambiguous case - a numerically unambiguous date
 * (one part over 12) is read correctly from the digits alone regardless of
 * this setting, the same as today, since there's nothing to prefer when
 * the digits already say which part is which. [label] follows the
 * [PunctuationMode]/[NumberReadingStyle] pattern - one source of truth for
 * the display string next to the enum it names, not a second lookup table
 * that can drift from it.
 */
enum class DateOrder(val label: String) {
    AS_WRITTEN("As written (safest - skips ambiguous dates)"),
    DAY_FIRST("Day before month (31/12/2024)"),
    MONTH_FIRST("Month before day (12/31/2024)"),
}

/**
 * Reads a numeric date (`/` or `-` separated only - a `.` separator is left
 * alone, since it collides too often with decimals, IP addresses, and
 * version numbers to touch safely) the way it was written, without forcing
 * any one order: "14/07/2024" -> "fourteenth July twenty twenty-four",
 * "07/14/2024" -> "July fourteenth twenty twenty-four", "2024-07-14" ->
 * "twenty twenty-four July fourteenth". The three parts keep whatever
 * order they were written in - this reads the date, it doesn't decide
 * whether the writer meant day-first or month-first.
 *
 * Only converts when the date is unambiguous from the digits alone: a
 * four-digit part is the year; of the remaining two 1-2 digit parts, at
 * least one has to be over 12 to know for certain which is the day and
 * which is the month, since a month can never exceed 12. Something like
 * "05/08/2024", where both remaining parts could be either, used to be left
 * completely untouched rather than guessed at - most real dates clear this
 * bar anyway, since more than half the days in any month are over 12. It
 * still is by default: [order] only kicks in for that specific leftover
 * case, and only when the user has explicitly moved it off [DateOrder
 * .AS_WRITTEN] - seconding [PunctuationMode]/[NumberReadingStyle]'s own
 * house rule of a safe default with an opt-in escape hatch, rather than
 * quietly changing what an existing install reads out loud.
 */
fun applyNaturalDateReading(text: String, order: DateOrder = DateOrder.AS_WRITTEN): String {
    if (!text.any { it == '/' || it == '-' }) return text
    return DATE_RE.replace(text) { m ->
        val raw = m.value
        if (raw in NON_DATE_IDIOMS) return@replace raw
        val sep1 = m.groupValues[2]
        val sep2 = m.groupValues[4]
        val p1 = m.groupValues[1].toIntOrNull() ?: return@replace raw
        val p2 = m.groupValues[3].toIntOrNull() ?: return@replace raw
        val g5 = m.groupValues[5]
        val p3 = if (g5.isNotEmpty()) g5.toIntOrNull() else null
        val partsCount = if (p3 != null) 3 else 2
        // A two-part hyphenated expression ("5-3", "10-5", "1-4") is a subtraction,
        // score, or range, never a date in English. Hyphenated dates are strictly
        // three-part (e.g. ISO-8601 "2024-07-14").
        if (partsCount == 2 && sep1 == "-") return@replace raw

        // With three parts, both separators have to match - "07/14-2024" is
        // not a date shape worth touching.
        if (partsCount == 3 && sep1 != sep2) return@replace raw

        var yearIndex = -1
        var fullYear: Int? = null
        if (p1 in 1000..9999) {
            yearIndex = 0
            fullYear = p1
        } else if (p2 in 1000..9999) {
            yearIndex = 1
            fullYear = p2
        } else if (p3 != null && p3 in 1000..9999) {
            yearIndex = 2
            fullYear = p3
        } else if (partsCount == 3 && p3 != null && p3 in 0..99) {
            // A three-part date where the third part is a two-digit year
            // (e.g. "14/07/24", "07/14/24"). Standard 50-pivot: >=50 is 19xx, <50 is 20xx.
            yearIndex = 2
            fullYear = if (p3 >= 50) 1900 + p3 else 2000 + p3
        }

        val a: Int
        val b: Int
        if (partsCount == 3) {
            when (yearIndex) {
                0 -> { a = 1; b = 2 }
                1 -> { a = 0; b = 2 }
                2 -> { a = 0; b = 1 }
                else -> return@replace raw
            }
        } else {
            if (yearIndex != -1) return@replace raw
            a = 0
            b = 1
        }

        fun getPart(idx: Int): Int = when (idx) {
            0 -> p1
            1 -> p2
            2 -> p3 ?: 0
            else -> 0
        }

        val partA = getPart(a)
        val partB = getPart(b)
        val dayIndex = when {
            partA in 13..31 -> a
            partB in 13..31 -> b
            // Neither part is unambiguously the day by magnitude alone -
            // the case this whole function used to always bail out on.
            // Only [order] can resolve it now, and only when both parts are
            // still valid as *some* day/month assignment (1-12 each); a
            // part outside 1-12 here (e.g. "35") is not a real date at all,
            // ambiguous or otherwise, and must still fall through untouched
            // regardless of the user's preference.
            partA in 1..12 && partB in 1..12 -> when (order) {
                DateOrder.AS_WRITTEN -> return@replace raw
                DateOrder.DAY_FIRST -> a
                DateOrder.MONTH_FIRST -> b
            }
            else -> return@replace raw
        }
        val monthIndex = if (a == dayIndex) b else a
        val partMonth = getPart(monthIndex)
        if (partMonth !in 1..12) return@replace raw

        fun partWord(idx: Int): String = when (idx) {
            yearIndex -> yearWord(fullYear ?: getPart(idx))
            dayIndex -> ordinalWord(getPart(idx))
            monthIndex -> MONTH_NAMES[partMonth]
            else -> ""
        }

        if (partsCount == 3) {
            "${partWord(0)} ${partWord(1)} ${partWord(2)}"
        } else {
            "${partWord(0)} ${partWord(1)}"
        }
    }
}
