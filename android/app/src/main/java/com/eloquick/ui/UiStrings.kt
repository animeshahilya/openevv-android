package com.eloquick.ui

internal const val PUNCTUATION_MODE_DESCRIPTION =
    "How much stray punctuation gets read aloud on its own. Quiet skips it all; Sentences keeps " +
        "sentence-ending marks (. ! ? …) so pauses still land naturally; Verbose reads every mark, " +
        "even spacing out runs like \"!!!\" so each one is heard. Punctuation stuck to a word, like " +
        "\"wait...\" or \"Mr.\", is always read as-is, in every mode."

internal const val ABBREVIATION_EXPANSION_DESCRIPTION =
    "Expands common abbreviations using the engine's own built-in dictionary - \"Dr.\" becomes " +
        "\"Doctor\", for example. Separate from the Pronunciation dictionary screen further down " +
        "this list - your own custom words there work independently of this switch either way. " +
        "On by default."

internal const val DESCRIBE_EMOJI_DESCRIPTION =
    "Says what an emoji means instead of skipping it silently - 😀 becomes \"grinning face\". " +
        "Covers flags, skin tones, and emoji families too, using their official names. On by default."

internal const val NUMBER_READING_STYLE_DESCRIPTION =
    "How a big number gets read aloud. Natural reads it the usual way (\"one million\"). Indian " +
        "numbering uses lakh/crore instead - \"10,00,000\" as \"ten lakh\" - for English voices, " +
        "and only when the number is already grouped with commas (so a bare digit run isn't " +
        "mistaken for one). Digit by digit reads each digit on its own, grouped however many at a " +
        "time you choose below - handy for PINs, codes, and order numbers, and works in any " +
        "language. Decimal numbers are always left alone, in every style."

internal const val DIGIT_GROUP_SIZE_DESCRIPTION =
    "How many digits Digit by digit reads together at once. 1 spells out every digit on its own " +
        "(\"1234\" as \"one two three four\"); a higher number reads small chunks naturally " +
        "instead (\"1234\" at 2 as \"twelve thirty-four\")."

/**
 * The three most common chunk sizes get their familiar screen-reader names
 * (Digits/Pairs/Triplets) instead of a bare number, since that's how this
 * setting is normally talked about; 4 and 5 stay plain numbers rather than
 * strain for names ("Quads"/"Quints") nobody actually uses for this.
 */
internal val DIGIT_GROUP_SIZE_LABELS: List<Pair<Int, String>> = listOf(
    1 to "Digits",
    2 to "Pairs",
    3 to "Triplets",
    4 to "4",
    5 to "5",
)

internal const val ALWAYS_READ_SYMBOLS_DESCRIPTION =
    "Symbols that stay spoken even in Quiet or Sentences mode, where standalone punctuation would " +
        "otherwise go silent - e.g. \"#&\" to always hear a hashtag or ampersand on its own. Has no " +
        "effect in Verbose mode, which already reads everything. Empty by default."

internal const val READ_SYMBOLS_BY_NAME_DESCRIPTION =
    "Says a symbol's name - \"asterisk\", \"hash\" - instead of leaving it to the engine's own " +
        "pronunciation, for any symbol that ends up spoken at all. Ordinary sentence punctuation " +
        "(. ! ? , ; :) is never affected. Off by default."

internal const val NATURAL_TIME_READING_DESCRIPTION =
    "Reads clock times the way a person would say them - \"10:30 AM\" as \"ten thirty AM\", " +
        "\"14:20\" as \"fourteen twenty\" - instead of spelling out the digits and colon. Midnight " +
        "and noon are read by name. On by default."

internal const val NATURAL_DATE_READING_DESCRIPTION =
    "Reads a numeric date the way it's written - \"14/07/2024\" as \"fourteenth July twenty " +
        "twenty-four\" - in whatever day/month/year order it appears. Only converts when there's " +
        "no real ambiguity; anything genuinely unclear, like \"05/08/2024\", is left exactly as " +
        "typed rather than guessed at. On by default."

internal const val DATE_ORDER_DESCRIPTION =
    "How to read a date whose day and month can't be told apart from the digits alone, like " +
        "\"05/08/2024\" - a date like \"14/07/2024\" is read correctly either way, since 14 can " +
        "only be a day. As written leaves an ambiguous date untouched rather than guess - the " +
        "safest choice if your text mixes conventions. The other two options read every " +
        "ambiguous date assuming one fixed order instead."

internal const val AUDIO_QUALITY_DESCRIPTION =
    "How clear the voice sounds. Higher settings use a little more battery and storage per " +
        "utterance, but speech starts just as fast either way."

internal val AUDIO_QUALITY_LEVELS: List<Pair<Int, String>> = listOf(
    0 to "Standard (fastest)",
    22050 to "Clear",
    44100 to "Clearest",
)

/** Display label for a persisted sample-rate value - the single formatting
 * rule behind both the flat-list summary and the Audio section's own row,
 * which used to repeat this exact expression. */
internal fun audioQualityLabelFor(sampleRateHz: Int): String =
    AUDIO_QUALITY_LEVELS.firstOrNull { it.first == sampleRateHz }?.second ?: "$sampleRateHz Hz"

internal const val ELIMINATE_REPEATS_DESCRIPTION =
    "Collapses a run of the same letter, repeated enough times in a row, back to a single letter - " +
        "\"hmmmmm\" becomes \"hm\", \"noooooo\" becomes \"no\" - instead of the engine spelling every " +
        "repeat out. Never touches digits (a real number keeps every digit) or punctuation, which " +
        "already has its own Punctuation & symbols setting above. Matches CodeFactory's ETI-Eloquence " +
        "TTS, which offers this same feature as \"Eliminate repeating characters\". Off by default."

internal const val REPEAT_THRESHOLD_DESCRIPTION =
    "How many times a letter has to repeat in a row before it's collapsed. 3 is the lowest offered - " +
        "low enough to catch an elongated spelling, but high enough that an ordinary doubled letter " +
        "(\"book\", \"off\") is never touched."

internal const val AUDIO_OPTIMIZER_DESCRIPTION =
    "Adds vocal presence and warmth for a richer, fuller voice, tuned for speech rather than music " +
        "so it stays clear instead of harsh - with a built-in limiter so it never clips. One " +
        "switch, not a pile of sliders - it's meant to just sound better."

// Plain-language explanations for the tuning sliders - what each one
// actually changes about the voice, not just its engine name.
internal const val SPEED_DESCRIPTION = "How fast the voice talks."
internal const val VOLUME_DESCRIPTION = "How loud the voice is."
internal const val PITCH_BASELINE_DESCRIPTION = "How high or low the voice sounds overall."
internal const val PITCH_FLUCTUATION_DESCRIPTION =
    "How much pitch rises and falls while talking. Higher sounds more expressive; lower sounds flatter and more monotone."
internal const val HEAD_SIZE_DESCRIPTION =
    "Simulates a bigger or smaller speaker. Lower makes the voice sound larger and deeper; higher makes it sound smaller and more nasal."
internal const val ROUGHNESS_DESCRIPTION = "Adds a gravelly, raspy quality to the voice."
internal const val BREATHINESS_DESCRIPTION = "Adds a soft, airy, breathy quality to the voice."
