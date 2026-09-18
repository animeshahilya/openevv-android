package com.eloquick.tts

private val ONES = arrayOf(
    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
)
private val TEENS = arrayOf(
    "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
    "sixteen", "seventeen", "eighteen", "nineteen",
)
private val TENS = arrayOf(
    "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
)
private val ORDINAL_ONES = arrayOf(
    "zeroth", "first", "second", "third", "fourth", "fifth",
    "sixth", "seventh", "eighth", "ninth",
)
private val ORDINAL_TEENS = arrayOf(
    "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth",
    "sixteenth", "seventeenth", "eighteenth", "nineteenth",
)
private val ORDINAL_TENS = arrayOf(
    "", "", "twentieth", "thirtieth",
)

/**
 * Spells out 0-99 as words ("five", "twenty-two") - shared by
 * [naturalTimeReading]'s hours/minutes and [naturalDateReading]'s day/year
 * halves, rather than duplicating a tens/ones table in each.
 */
fun cardinalWord(n: Int): String {
    require(n in 0..99) { "cardinalWord only covers 0-99, got $n" }
    return when {
        n < 10 -> ONES[n]
        n < 20 -> TEENS[n - 10]
        n % 10 == 0 -> TENS[n / 10]
        else -> "${TENS[n / 10]}-${ONES[n % 10]}"
    }
}

/**
 * The "oh five" / "oh nine" reading a single-digit count of minutes past
 * the hour takes ("12:05" -> "twelve oh five"), as opposed to [cardinalWord]
 * alone, which would read the same digit as just "five" - ambiguous next to
 * an hour with nothing marking it as a minutes count.
 */
fun minutesPastWord(n: Int): String {
    require(n in 0..59) { "minutesPastWord only covers 0-59, got $n" }
    return if (n in 1..9) "oh ${ONES[n]}" else cardinalWord(n)
}

/**
 * Spells out 1-31 as an ordinal ("first", "twenty-second") - the day-of-month
 * range [naturalDateReading] needs; irregular enough (first/second/third,
 * twentieth not twenty-th) that it isn't derivable from [cardinalWord] with
 * a suffix rule alone.
 */
fun ordinalWord(n: Int): String {
    require(n in 1..31) { "ordinalWord only covers 1-31, got $n" }
    return when {
        n < 10 -> ORDINAL_ONES[n]
        n < 20 -> ORDINAL_TEENS[n - 10]
        n % 10 == 0 -> ORDINAL_TENS[n / 10]
        else -> "${TENS[n / 10]}-${ORDINAL_ONES[n % 10]}"
    }
}

/**
 * A four-digit year the way people actually say it, not a plain number
 * reading ("1998" as "nineteen ninety-eight", not "one thousand nine
 * hundred ninety-eight"). Three shapes:
 * - An exact multiple of 1000 ("2000", "3000") says "two thousand" - the one
 *   case nobody reads as two two-digit halves.
 * - A whole century ("1900", "1800", "2100") says "nineteen hundred" -
 *   English's own convention for a year ending in two zeros.
 * - Anything else splits into two two-digit halves ("1998" -> "nineteen" +
 *   "ninety-eight", "2024" -> "twenty" + "twenty-four"), with the second
 *   half getting [minutesPastWord]'s "oh five" treatment when it's under
 *   ten ("1905" -> "nineteen oh five") so it doesn't read as a bare "five".
 */
fun yearWord(year: Int): String {
    require(year in 1000..9999) { "yearWord only covers four-digit years, got $year" }
    val firstHalf = year / 100
    val secondHalf = year % 100
    return when {
        secondHalf == 0 && firstHalf % 10 == 0 -> "${cardinalWord(firstHalf / 10)} thousand"
        secondHalf == 0 -> "${cardinalWord(firstHalf)} hundred"
        secondHalf < 10 -> "${cardinalWord(firstHalf)} oh ${ONES[secondHalf]}"
        else -> "${cardinalWord(firstHalf)} ${cardinalWord(secondHalf)}"
    }
}
