package com.eloquick.tts

/**
 * Works around [openevv#4](https://github.com/Mudb0y/openevv/issues/4):
 * standalone punctuation marks bounded by whitespace (e.g. a lone "-" or "*"
 * used as a bullet or separator) get read aloud as words by this engine,
 * where IBM's original library stayed silent on them. This can't be fixed
 * in the engine from here, so [processPunctuation] strips such tokens from
 * the text before handing it to [EloquenceNative.nativeSynthesize] (in
 * [PunctuationMode.QUIET], its default mode).
 *
 * Only whitespace-delimited tokens made entirely of punctuation count -
 * punctuation attached to a word (e.g. "wait..." or "Mr.") is left alone.
 * A standalone `stripIsolatedPunctuation(text)` function used to live here
 * as the only way to apply this rule; [processPunctuation] below grew the
 * exact same QUIET-mode stripping inline (it needs this per-token check
 * anyway, to decide `alwaysReadSymbols`/`readSymbolsByName` per token) and
 * the standalone function was never updated to route through it, leaving
 * two copies of the same "strip pure-punctuation tokens" logic - one dead
 * (`stripIsolatedPunctuation` had no caller left anywhere in the app or its
 * tests), one live. Removed rather than kept as a wrapper: nothing needs
 * the bare behavior on its own without the mode/allowlist/naming
 * [processPunctuation] already covers.
 */
private fun isPurePunctuationToken(token: String): Boolean =
    token.isNotEmpty() && token.none { it.isLetterOrDigit() }

/**
 * How much standalone punctuation the engine should speak - the classic
 * Eloquence verbosity idea, implemented as preprocessing because this
 * engine exposes no punctuation-level control of its own. Screen-reader
 * users proofreading code want every mark read; people listening to prose
 * want bullets and separators silent.
 */
enum class PunctuationMode(val label: String) {
    /** Strip every whitespace-bounded pure-punctuation token (default:
     * closest to IBM's original silence; see [processPunctuation]). */
    QUIET("Quiet"),
    /** Strip isolated marks, but keep sentence punctuation (. ! ? …) in
     * place so the engine still pauses naturally at sentence ends. */
    SENTENCES("Sentences"),
    /** Read everything: no stripping, and runs of the same mark ("!!!")
     * are spaced out so each one is spoken instead of swallowed. */
    VERBOSE("Verbose"),
}

private val SENTENCE_MARKS = setOf('.', '!', '?', '…')

/**
 * Runs of the same spoken mark ("!!!", "...") that VERBOSE mode spaces out.
 * Any non-letter, non-digit, non-whitespace character - not a hand-picked
 * list of marks - the same "pure punctuation" definition
 * [isPurePunctuationToken] already uses, deliberately, rather than a second
 * character class that has to be kept in sync with [SYMBOL_NAMES] by hand.
 * A hand-picked class here once genuinely drifted: it covered `?!.,;:*#-–—…~^|`
 * but not `@&%+=_\/<>$£€\``, every one of which [SYMBOL_NAMES] has a name
 * for - so a run like "@@@" or "$$$" silently passed through VERBOSE mode
 * completely untouched (this whole branch returns immediately below when
 * naming is off), the opposite of what "spaces out every repeated mark"
 * promises.
 */
private val REPEATED_MARK_RE = Regex("([^\\s\\p{L}\\p{N}])\\1+")

/**
 * Curated spoken names for symbols worth announcing explicitly rather than
 * relying on however the engine happens to pronounce a lone mark - the
 * exact kind [openevv#4](https://github.com/Mudb0y/openevv/issues/4) was
 * filed about (a lone "-" or "*" used as a bullet). Deliberately excludes
 * ordinary sentence punctuation ([SENTENCE_MARKS], plus `,;:`) - those
 * already read reliably on their own, and naming "period"/"comma" every
 * time one turns up isolated (SENTENCES/VERBOSE) would be noise, not help.
 */
private val SYMBOL_NAMES: Map<Char, String> = mapOf(
    '*' to "asterisk",
    // The exact bullet openevv#4 was filed about: without a name here a
    // kept "-" (VERBOSE, or pinned via alwaysReadSymbols) reached the
    // engine as a bare dash, which stays silent - the opposite of what
    // keeping it means.
    '-' to "dash",
    '#' to "hash",
    '@' to "at sign",
    '&' to "ampersand",
    '%' to "percent",
    '+' to "plus",
    '=' to "equals",
    '~' to "tilde",
    '^' to "caret",
    '_' to "underscore",
    '|' to "pipe",
    '\\' to "backslash",
    '/' to "slash",
    '<' to "less than",
    '>' to "greater than",
    '$' to "dollar sign",
    '£' to "pound sign",
    '€' to "euro sign",
    '¥' to "yen sign",
    '¢' to "cent sign",
    '•' to "bullet",
    '`' to "backtick",
    // Math/misc symbols a reference competitor app (com.karan.eloquence,
    // reverse-engineered 2026-09-11) names but this app's SYMBOL_NAMES
    // didn't yet - genuinely useful beyond the openevv#4 bullet/dash case
    // this map started from: math notation and currency/reference marks are
    // exactly the content (formulas, prices, footnotes) a symbol-naming
    // toggle exists for, and silently mispronouncing or dropping them is the
    // same "worse reading" complaint this whole file's own doc comment
    // already describes for a lone "-" or "*".
    '±' to "plus minus",
    '×' to "times",
    '÷' to "divided by",
    '≤' to "less than or equal to",
    '≥' to "greater than or equal to",
    '≠' to "not equal to",
    '≈' to "about",
    '∞' to "infinity",
    '→' to "to",
    '←' to "from",
    '™' to "trademark",
    '№' to "number",
    '°' to "degrees",
    '§' to "paragraph",
    '·' to "middle dot",
    '«' to "open double angle bracket",
    '»' to "close double angle bracket",
    '¡' to "inverted exclamation mark",
    '¿' to "inverted question mark",
    '(' to "left paren",
    ')' to "right paren",
    '[' to "left bracket",
    ']' to "right bracket",
    '{' to "left brace",
    '}' to "right brace",
    '₹' to "rupee sign",
    '©' to "copyright",
    '®' to "registered trademark",
    '…' to "ellipsis",
)

private val MULTI_CHAR_OPERATORS: Map<String, String> = mapOf(
    "!=" to "not equal to",
    "==" to "equals equals",
    "===" to "strictly equals",
    "!==" to "strictly not equal",
    "<=" to "less than or equal to",
    ">=" to "greater than or equal to",
    "->" to "arrow",
    "=>" to "fat arrow",
    "&&" to "and and",
    "||" to "or or",
    "++" to "plus plus",
    "--" to "minus minus",
    "..." to "ellipsis",
    "+=" to "plus equals",
    "-=" to "minus equals",
    "*=" to "times equals",
    "/=" to "divided by equals",
    ":=" to "colon equals",
    "::" to "double colon",
    "//" to "double slash",
    "/*" to "slash asterisk",
    "*/" to "asterisk slash",
)

/**
 * Four familiar named levels (the classic screen-reader "punctuation
 * verbosity" idea, as None/Some/Most/All) layered on top of the three
 * controls above as one-tap conveniences, rather than a fourth axis
 * competing with them. Each preset is just a canonical combination of an
 * existing [PunctuationMode], [alwaysReadSymbols] allowlist, and
 * [readSymbolsByName] toggle:
 * - **None**: [PunctuationMode.QUIET], nothing extra kept, no naming.
 * - **Some**: [PunctuationMode.SENTENCES] - sentence pacing survives,
 *   nothing else does.
 * - **Most**: [PunctuationMode.SENTENCES] plus every symbol this app has a
 *   spoken name for ([SYMBOL_NAMES]) always kept and named.
 * - **All**: [PunctuationMode.VERBOSE] with naming on - nothing is ever
 *   stripped, and every kept symbol gets a clear spoken name.
 *
 * Picking a preset just writes these three existing settings, so the
 * fine-grained controls underneath stay fully available afterward for
 * anyone who wants to start from a preset and hand-tune from there -
 * [punctuationPresetFor] answers which preset (if any) the *current*
 * combination still matches, purely by comparison, so there is no separate
 * "which preset is active" state to fall out of sync with the real settings.
 */
enum class PunctuationPreset(
    val label: String,
    val mode: PunctuationMode,
    val alwaysReadSymbols: Set<Char>,
    val readSymbolsByName: Boolean,
) {
    NONE("None", PunctuationMode.QUIET, emptySet(), false),
    SOME("Some", PunctuationMode.SENTENCES, emptySet(), false),
    MOST("Most", PunctuationMode.SENTENCES, SYMBOL_NAMES.keys, true),
    ALL("All", PunctuationMode.VERBOSE, emptySet(), true),
}

/** The preset [PunctuationPreset] whose settings exactly match the ones
 * given, or null if the current combination is custom (either never came
 * from a preset, or was hand-tuned away from one since). */
fun punctuationPresetFor(
    mode: PunctuationMode,
    alwaysReadSymbols: Set<Char>,
    readSymbolsByName: Boolean,
): PunctuationPreset? = PunctuationPreset.entries.firstOrNull {
    it.mode == mode && it.alwaysReadSymbols == alwaysReadSymbols && it.readSymbolsByName == readSymbolsByName
}

/**
 * Spoken form of a whitespace-isolated punctuation token, if every
 * character in it is the *same*, named symbol ("**" -> "asterisk
 * asterisk", each repeat named separately so it's still heard as a repeat,
 * not collapsed away). Null for a token with no name to give it - an
 * unlisted symbol, or a mix of different marks - and the caller leaves
 * those exactly as they were, same as if this setting were off.
 */
private fun spokenNameFor(token: String): String? {
    val multi = MULTI_CHAR_OPERATORS[token]
    if (multi != null) return multi

    val first = token.firstOrNull() ?: return null
    for (idx in 1 until token.length) {
        if (token[idx] != first) return null
    }
    val name = SYMBOL_NAMES[first] ?: return null
    if (token.length == 1) return name
    val sb = StringBuilder(token.length * (name.length + 1))
    for (k in 0 until token.length) {
        if (k > 0) sb.append(' ')
        sb.append(name)
    }
    return sb.toString()
}

/**
 * @param alwaysReadSymbols characters that are never stripped in QUIET or
 *   SENTENCES mode even though they'd otherwise count as isolated
 *   punctuation - e.g. keeping a lone "#" that marks a hashtag while
 *   everything else stays quiet. Has no effect in VERBOSE mode, which
 *   already keeps everything.
 * @param readSymbolsByName when a symbol is going to be spoken at all
 *   (VERBOSE, a sentence mark kept by SENTENCES, or one kept by
 *   [alwaysReadSymbols]), speaks its name from [SYMBOL_NAMES] instead of
 *   leaving the bare character for the engine to pronounce however it does.
 */
fun processPunctuation(
    text: String,
    mode: PunctuationMode,
    alwaysReadSymbols: Set<Char> = emptySet(),
    readSymbolsByName: Boolean = false,
): String {
    if (text.isEmpty()) return text
    // Fast path: plain prose with no punctuation at all is a no-op in
    // every mode and under every combination of the settings above - there
    // is nothing here for any of them to act on - so skip tokenizing and
    // every branch below, the common TalkBack case.
    if (text.all { it.isLetterOrDigit() || it.isWhitespace() }) {
        return text
    }

    // VERBOSE's repeated-marks spacing runs first regardless of naming -
    // exactly the pre-existing behavior when readSymbolsByName is off, so
    // turning that setting off is a true no-op here, not an approximation.
    val spaced = if (mode == PunctuationMode.VERBOSE) {
        REPEATED_MARK_RE.replace(text) { m ->
            (if (readSymbolsByName) spokenNameFor(m.value) else null)
                ?: m.value.toCharArray().joinToString(" ")
        }
    } else {
        text
    }
    if (mode == PunctuationMode.VERBOSE && !readSymbolsByName) return spaced

    val sb = StringBuilder(spaced.length)
    // Tracks whether the last thing actually appended was whitespace, so
    // dropping a punctuation token between two whitespace tokens (the
    // common QUIET/SENTENCES case - "text - more" tokenizes as
    // ["text", " ", "-", " ", "more"]) doesn't leave both of them in the
    // output as a double space. Slicing only ever produces two adjacent
    // whitespace runs when something between them was dropped - a
    // genuine double space already typed in the source text tokenizes as
    // one single whitespace run to begin with - so this can never
    // collapse spacing the original text actually had.
    var lastAppendedWasSpace = false
    var i = 0
    val len = spaced.length
    while (i < len) {
        val start = i
        val isSpace = spaced[i].isWhitespace()
        while (i < len && spaced[i].isWhitespace() == isSpace) {
            i++
        }
        val token = spaced.substring(start, i)
        if (isSpace) {
            if (!lastAppendedWasSpace) {
                sb.append(token)
                lastAppendedWasSpace = true
            }
            continue
        }
        val pure = isPurePunctuationToken(token)
        val alwaysKept = pure && token.any { it in alwaysReadSymbols }
        val isNegativeSign = (token == "-" || token == "−") && run {
            var j = i
            while (j < len && spaced[j].isWhitespace()) j++
            j < len && spaced[j].isDigit()
        }
        val keep = when (mode) {
            PunctuationMode.QUIET -> !pure || alwaysKept || isNegativeSign
            PunctuationMode.SENTENCES -> !pure || alwaysKept || isNegativeSign || token.any { it in SENTENCE_MARKS }
            PunctuationMode.VERBOSE -> true // nothing is ever stripped in Verbose
        }
        if (!keep) continue
        val spoken = when {
            isNegativeSign -> "minus"
            pure && readSymbolsByName -> spokenNameFor(token) ?: token
            else -> token
        }
        sb.append(spoken)
        lastAppendedWasSpace = false
    }
    return sb.toString()
}
