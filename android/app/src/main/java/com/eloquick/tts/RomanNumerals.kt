package com.eloquick.tts

/**
 * Decodes a Roman numeral string (e.g. "IV", "VIII", "LVIII") into an integer,
 * or returns null if invalid or outside 1..3999.
 */
internal fun parseRomanNumeral(s: String): Int? {
    if (s.isEmpty() || s.length > 15) return null
    var total = 0
    var prev = 0
    for (i in s.length - 1 downTo 0) {
        val curr = when (s[i]) {
            'I', 'i' -> 1
            'V', 'v' -> 5
            'X', 'x' -> 10
            'L', 'l' -> 50
            'C', 'c' -> 100
            'D', 'd' -> 500
            'M', 'm' -> 1000
            else -> return null
        }
        if (curr < prev) total -= curr else total += curr
        prev = curr
    }
    return if (total in 1..3999) total else null
}

private val SECTION_ROMAN_RE = Regex(
    """\b(Chapter|Section|Part|Book|Volume|Vol\.|Title|Article|Act|Scene|World\s+War|War|Phase|Tier|Grade|Level|Class|Division|Type|Mark|Apollo|Voyager|PlayStation|Final\s+Fantasy|Super\s+Bowl)\s+([IVXLCDM]+)\b""",
    RegexOption.IGNORE_CASE,
)

private const val ROYAL_TITLES = "King|Queen|Pope|Emperor|Empress|Prince|Princess|Tsar|Czar|Kaiser|Archduke"
private const val ROYAL_NAMES = "Henry|Elizabeth|George|Charles|Louis|Edward|James|William|Richard|Philip|John\\s+Paul|Benedict|Francis|Alexander|Nicholas|Peter|Mary|Victoria|Napoleon|Catherine|Frederick|Ferdinand|Paul|John|Leo|Gregory|Pius|Innocent"

private val MONARCH_WITH_TITLE_RE = Regex(
    """\b($ROYAL_TITLES)\s+([A-Z][a-z]+)\s+([IVXLCDM]+)\b""",
)

private val MONARCH_NAME_ONLY_RE = Regex(
    """\b($ROYAL_NAMES)\s+([IVXLCDM]+)\b""",
)

/**
 * Converts Roman numerals in contextual phrases (headings, works, series,
 * monarchs, popes) into spoken digits or ordinal words, avoiding the classic
 * screen-reader complaint of spelling out letters ("Chapter eye vee",
 * "Henry vee eye eye eye").
 *
 * Scoped strictly to recognized contexts so normal capital letters ("I", "V")
 * in prose are never disturbed.
 */
fun applyContextualRomanNumerals(text: String): String {
    // Fast path: if text has no Roman-numeral letters, skip regex passes
    if (!text.any { it == 'I' || it == 'V' || it == 'X' || it == 'L' || it == 'C' || it == 'D' || it == 'M' }) {
        return text
    }

    // 1. Headings / Sections / Events / Works -> Cardinal numbers ("Chapter 4", "World War 2")
    var result = SECTION_ROMAN_RE.replace(text) { m ->
        val prefix = m.groupValues[1]
        val roman = m.groupValues[2].uppercase()
        val num = parseRomanNumeral(roman) ?: return@replace m.value
        "$prefix $num"
    }

    // 2. Monarchs with royal titles ("King George VI" -> "King George the sixth")
    result = MONARCH_WITH_TITLE_RE.replace(result) { m ->
        val title = m.groupValues[1]
        val name = m.groupValues[2]
        val roman = m.groupValues[3].uppercase()
        val num = parseRomanNumeral(roman) ?: return@replace m.value
        if (num in 1..31) "$title $name the ${ordinalWord(num)}" else "$title $name $num"
    }

    // 3. Known monarch/papal names without explicit title ("Henry VIII" -> "Henry the eighth")
    result = MONARCH_NAME_ONLY_RE.replace(result) { m ->
        val name = m.groupValues[1]
        val roman = m.groupValues[2].uppercase()
        val num = parseRomanNumeral(roman) ?: return@replace m.value
        if (num in 1..31) "$name the ${ordinalWord(num)}" else "$name $num"
    }

    return result
}
