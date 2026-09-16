package com.eloquick.debug;

import java.util.regex.Pattern;

/** Small text guards applied before synthesis, each attested in this
 * repo's own tree rather than borrowed from a driver:
 *
 * - Wednesday misspellings ("edhesday", "w?enhesday", ...) are crashers in
 *   test/cases/crashers.txt: the engine survives them now (forced-backtrack
 *   landing), but the utterance is abandoned mid-sentence. Normalising the
 *   common misspellings to Wednesday keeps the sentence.
 *
 * Reading helpers below (normalization, emoji, digit grouping, currency,
 * time/date, spelling/phonetic/code, punctuation) are the engine-agnostic
 * half of what screen-reader users expect from a TTS service: they turn
 * text the engine would spell, skip, or stall on into words it can say.
 * All are opt-in behind EqPrefs except normalization, which only maps
 * exotic codepoints to readable ASCII.
 */
public final class EqText {
    private EqText() {}

    private static final Pattern WEDNESDAY =
            Pattern.compile("(?i)\\b(w?edhesday|w?enhesday|w?ennesday|w?edesday|edhesday|enhesday)\\b");

    public static String wednesdayGuard(String text) {
        if (text == null) return "";
        if (!text.toLowerCase(java.util.Locale.US).contains("esday")) return text;
        return WEDNESDAY.matcher(text).replaceAll("Wednesday");
    }

    /** NFKC: stylized Unicode ("bold" social-media fonts, fullwidth) reads
     *  as words instead of codepoint-by-codepoint spelling. */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        try {
            return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC);
        } catch (Exception e) {
            return text;
        }
    }

    /** Drop unpaired UTF-16 surrogates (e.g. a paste truncated mid-emoji)
     *  before the JNI/native layer, which expects well-formed text. */
    public static String stripUnpairedSurrogates(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    out.append(c);
                    out.append(text.charAt(i + 1));
                    i++;
                }
            } else if (!Character.isLowSurrogate(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Zero-hang watchdog: strip controls and invisible bidi/zero-width
     *  runs that can stall an engine, keeping tab/newline and the
     *  backtick the annotation language uses. */
    public static String sanitizeControls(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            boolean drop = (cp < 0x20 && cp != '\t' && cp != '\n')
                    || cp == 0x7F
                    || (cp >= 0x200B && cp <= 0x200F)
                    || (cp >= 0x202A && cp <= 0x202E)
                    || (cp >= 0x2060 && cp <= 0x2064)
                    || cp == 0xFEFF;
            if (!drop) out.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static boolean isEmoji(int cp) {
        return (cp >= 0x1F600 && cp <= 0x1F64F)
                || (cp >= 0x1F300 && cp <= 0x1F5FF)
                || (cp >= 0x1F680 && cp <= 0x1F6FF)
                || (cp >= 0x1F900 && cp <= 0x1F9FF)
                || (cp >= 0x1FA70 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x26FF)
                || (cp >= 0x2700 && cp <= 0x27BF)
                || cp == 0xFE0F || cp == 0x200D;
    }

    /** Emoji-ignore: remove emoji, collapsing the gaps. */
    public static String filterEmojis(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (!isEmoji(cp)) out.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return out.toString().replaceAll("  +", " ");
    }

    /** Emoji-announce: name each emoji run so something is heard. */
    public static String clarifyEmojis(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        StringBuilder out = new StringBuilder(text.length() + 16);
        boolean inRun = false;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (isEmoji(cp)) {
                if (!inRun) {
                    out.append(" emoji ");
                    inRun = true;
                }
            } else {
                out.appendCodePoint(cp);
                inRun = false;
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    public static boolean containsEmoji(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (isEmoji(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /** Space long digit runs into groups of {@code size} from the right
     *  ("1234567890" single -> "1 2 3 4 5 6 7 8 9 0"), so the engine reads
     *  digits instead of a billions-scale number. Runs shorter than
     *  {@code threshold} are left alone. */
    public static String groupDigits(String text, int size, int threshold) {
        if (text == null || text.isEmpty() || size < 1) {
            return text == null ? "" : text;
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                int j = i;
                while (j < text.length() && text.charAt(j) >= '0' && text.charAt(j) <= '9') j++;
                String run = text.substring(i, j);
                if (run.length() >= threshold) {
                    out.append(groupRun(run, size));
                } else {
                    out.append(run);
                }
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String groupRun(String run, int size) {
        StringBuilder out = new StringBuilder(run.length() + run.length() / size + 1);
        int first = run.length() % size;
        if (first == 0) first = size;
        out.append(run.substring(0, first));
        for (int k = first; k < run.length(); k += size) {
            out.append(' ');
            out.append(run.substring(k, Math.min(k + size, run.length())));
        }
        return out.toString();
    }

    /** Case forms for one dictionary key. The engine's lookup is an exact
     *  byte comparison (src/eci/dict/eci_key.c key_match: plain strncmp, no
     *  folding), so one entry must be written per case to match at sentence
     *  starts and in headings. */
    public static String[] caseForms(String key) {
        if (key == null || key.isEmpty()) return new String[0];
        String lower = key.toLowerCase(java.util.Locale.US);
        String upper = key.toUpperCase(java.util.Locale.US);
        String cap = upper.isEmpty() ? key
                : Character.toUpperCase(key.charAt(0)) + key.substring(1).toLowerCase(
                        java.util.Locale.US);
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        out.add(key);
        out.add(lower);
        out.add(upper);
        out.add(cap);
        return out.toArray(new String[0]);
    }

    // ---- reading modes -----------------------------------------------------

    private static final String[] NATO = {"Alpha", "Bravo", "Charlie", "Delta",
            "Echo", "Foxtrot", "Golf", "Hotel", "India", "Juliett", "Kilo",
            "Lima", "Mike", "November", "Oscar", "Papa", "Quebec", "Romeo",
            "Sierra", "Tango", "Uniform", "Victor", "Whiskey", "X-ray",
            "Yankee", "Zulu"};
    private static final String[] DIGIT_WORDS = {"zero", "one", "two", "three",
            "four", "five", "six", "seven", "eight", "nine"};

    /** "abc" -> "a b c": single-character TalkBack navigation spelled out. */
    public static String expandSpelling(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        StringBuilder out = new StringBuilder(text.length() * 2);
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
                out.appendCodePoint(Character.toLowerCase(cp));
            } else if (!Character.isWhitespace(cp)) {
                out.append(' ');
                out.appendCodePoint(cp);
                out.append(' ');
            } else {
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return out.toString().replaceAll("  +", " ").trim();
    }

    /** "abc" -> "Alpha Bravo Charlie" (NATO phonetic alphabet). */
    public static String expandPhonetic(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        StringBuilder out = new StringBuilder(text.length() * 6);
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (cp >= 'a' && cp <= 'z') {
                out.append(' ').append(NATO[cp - 'a']);
            } else if (cp >= 'A' && cp <= 'Z') {
                out.append(' ').append("capital ").append(NATO[cp - 'A']);
            } else if (cp >= '0' && cp <= '9') {
                out.append(' ').append(DIGIT_WORDS[cp - '0']);
            } else {
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return out.toString().replaceAll("  +", " ").trim();
    }

    private static final char[] SYMBOL_CHARS = {'.', ',', '!', '?', ';', ':',
            '\'', '"', '-', '(', ')', '[', ']', '{', '}', '/', '@', '#', '$',
            '%', '&', '*', '+', '=', '<', '>', '_', '~', '^', '|', '\\', '`'};
    private static final String[] SYMBOL_WORDS = {"dot", "comma", "exclamation",
            "question", "semicolon", "colon", "apostrophe", "quote", "dash",
            "left paren", "right paren", "left bracket", "right bracket",
            "left brace", "right brace", "slash", "at", "hash", "dollar",
            "percent", "and", "star", "plus", "equals", "less", "greater",
            "underscore", "tilde", "caret", "bar", "backslash", "backtick"};

    static String symbolWord(char c) {
        for (int i = 0; i < SYMBOL_CHARS.length; i++) {
            if (SYMBOL_CHARS[i] == c) return SYMBOL_WORDS[i];
        }
        return "symbol";
    }

    /** Code reading: every programming symbol spoken ("{" -> "left brace"). */
    public static String expandProgrammingSymbols(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        StringBuilder out = new StringBuilder(text.length() + 32);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String word = null;
            for (int k = 0; k < SYMBOL_CHARS.length; k++) {
                if (SYMBOL_CHARS[k] == c) {
                    word = SYMBOL_WORDS[k];
                    break;
                }
            }
            if (word != null) {
                out.append(' ').append(word).append(' ');
            } else {
                out.append(c);
            }
        }
        return out.toString().replaceAll("  +", " ").trim();
    }

    /** Punctuation preset: expand each listed character to its spoken name. */
    public static String expandPunctuation(String text, String chars) {
        if (text == null || text.isEmpty() || chars == null || chars.isEmpty()) {
            return text == null ? "" : text;
        }
        StringBuilder out = new StringBuilder(text.length() + 32);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (chars.indexOf(c) >= 0) {
                out.append(' ').append(symbolWord(c)).append(' ');
            } else {
                out.append(c);
            }
        }
        return out.toString().replaceAll("  +", " ").trim();
    }

    // ---- currency ------------------------------------------------------

    private static final char[] CURRENCY_SYMBOLS = {'$', 0x20AC, 0x00A3, 0x00A5, 0x20B9};
    private static final String[] CURRENCY_WORDS = {"dollars", "euros", "pounds", "yen", "rupees"};

    // Pre-compiled patterns for each currency symbol: [prefix pattern, suffix pattern]
    private static final Pattern[] CURRENCY_PREFIX_PATTERNS;
    private static final Pattern[] CURRENCY_SUFFIX_PATTERNS;

    static {
        CURRENCY_PREFIX_PATTERNS = new Pattern[CURRENCY_SYMBOLS.length];
        CURRENCY_SUFFIX_PATTERNS = new Pattern[CURRENCY_SYMBOLS.length];
        for (int i = 0; i < CURRENCY_SYMBOLS.length; i++) {
            String sym = Pattern.quote(String.valueOf(CURRENCY_SYMBOLS[i]));
            CURRENCY_PREFIX_PATTERNS[i] = Pattern.compile(sym + "\\s*(\\d[\\d.,]*)");
            CURRENCY_SUFFIX_PATTERNS[i] = Pattern.compile("(\\d[\\d.,]*)\\s*" + sym);
        }
    }

    /** "$5" -> "5 dollars" (English names). Lone symbols without digits
     *  are left for flattenWestern's fallback below. */
    public static String expandCurrency(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        String out = text;
        for (int s = 0; s < CURRENCY_SYMBOLS.length; s++) {
            String word = CURRENCY_WORDS[s];
            out = CURRENCY_PREFIX_PATTERNS[s].matcher(out).replaceAll("$1 " + word);
            out = CURRENCY_SUFFIX_PATTERNS[s].matcher(out).replaceAll("$1 " + word);
        }
        return out;
    }

    // ---- Western flatten ---------------------------------------------------
    //
    // Android text turned into the bytes the engine reads. Every bundled
    // language but Polish and Japanese takes single Latin-1-ish bytes;
    // UTF-16 is not an option (the engine allows the wide code set only
    // for Chinese/Japanese/Korean). So typographic characters with a plain
    // equivalent are flattened before the engine sees them: a screen
    // reader is handed curly quotes, dashes and symbols Eloquence never
    // knew, and saying the nearest thing beats spelling bytes.
    //
    // Deliberately typographic-only: accented Latin-1 letters and Polish
    // diacritics pass through untouched.

    private static final char[] FLAT_FROM = {
            0x2018, 0x2019, 0x201A, 0x201B,
            0x201C, 0x201D, 0x201E, 0x201F, 0x00AB, 0x00BB, 0x2039, 0x203A,
            0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2015, 0x2212,
            0x2026,
            0x00A0, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2004,
            0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A, 0x202F, 0x205F,
            0x2022, 0x00B7, 0x2027,
            0x00A9, 0x00AE, 0x2122,
            0x00B0, 0x00D7, 0x00F7, 0x00B1,
            0x2264, 0x2265, 0x2260, 0x2248, 0x221E,
            0x00BC, 0x00BD, 0x00BE,
            0x20AC, 0x00A3, 0x00A5, 0x20B9,
    };
    private static final String[] FLAT_TO = {
            "'", "'", "'", "'",
            "\"", "\"", "\"", "\"", "\"", "\"", "'", "'",
            "-", "-", "-", "-", "-", "-", "-",
            "...",
            " ", " ", " ", " ", " ", " ", " ",
            " ", " ", " ", " ", " ", " ",
            " ", " ", " ",
            " copyright ", " registered ", " trademark ",
            " degrees ", "x", " divided by ", " plus or minus ",
            " less than or equal to ", " greater than or equal to ",
            " not equal to ", " about ", " infinity ",
            " quarter ", " half ", " three quarters ",
            " euros ", " pounds ", " yen ", " rupees ",
    };

    // Lookup table for O(1) flattenWestern: maps codepoint -> FLAT_TO index,
    // or -1 if no mapping. Covers all FLAT_FROM codepoints (max 0x2265).
    private static final short[] FLAT_LOOKUP;

    static {
        FLAT_LOOKUP = new short[0x2266]; // covers up to 0x2265 (≈)
        for (int i = 0; i < FLAT_LOOKUP.length; i++) {
            FLAT_LOOKUP[i] = -1;
        }
        for (int i = 0; i < FLAT_FROM.length; i++) {
            FLAT_LOOKUP[FLAT_FROM[i]] = (short) i;
        }
    }

    /** Typographic characters to their plain equivalents; everything else
     *  (including accented Latin-1 and Polish letters) passes through. */
    public static String flattenWestern(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (cp < 0x80) {
                out.append((char) cp);
            } else if (cp < FLAT_LOOKUP.length) {
                short idx = FLAT_LOOKUP[cp];
                if (idx >= 0) {
                    out.append(FLAT_TO[idx]);
                } else {
                    out.appendCodePoint(cp);
                }
            } else {
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return out.toString().replaceAll(" {2,}", " ");
    }

    // ---- engine text fixes ---------------------------------------------------
    //
    // Text the engine reads wrong, put right before it sees it. Eloquence
    // gives up on a word it cannot parse and spells it out letter by
    // letter ("teamtalk5" -> t e a m t a l k 5); a space in the right
    // place is enough to fix it. Each rule was measured against this
    // engine family first: where the engine already coped, the rewrite
    // is byte-identical in output and costs nothing.
    //
    // Runs BEFORE any annotation is added: a rule putting a space between
    // a letter and a digit would turn `p1 into `p 1, which the engine
    // speaks rather than obeys.

    private static final Pattern BEFORE_A_DIGIT =
            Pattern.compile("([A-Za-z])(\\d)");
    private static final Pattern BEFORE_AN_OPENER =
            Pattern.compile("([A-Za-z]+)([~#$%^*({\\[|<\u2022])");
    private static final Pattern LOOSE_S_SUFFIX =
            Pattern.compile("([A-Za-z]+)\\s+(\\(s\\))");
    private static final Pattern SPACE_BEFORE_A_MARK =
            Pattern.compile("([A-Za-z]+|\\d+|\\W+)\\s+([:.!;,?](?![A-Za-z]|\\d))");
    private static final Pattern GROUPED_THOUSANDS =
            Pattern.compile("\\b\\d{1,3},000(?:,\\d{3})+\\b");

    /** Words the engine mishandles, rewritten. Changes how a word is
     *  written for the engine, never what is said. */
    public static String fixEngineText(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        String out = BEFORE_A_DIGIT.matcher(text).replaceAll("$1 $2");
        out = BEFORE_AN_OPENER.matcher(out).replaceAll("$1 $2");
        out = LOOSE_S_SUFFIX.matcher(out).replaceAll("$1$2");
        out = SPACE_BEFORE_A_MARK.matcher(out).replaceAll("$1$2");
        java.util.regex.Matcher m = GROUPED_THOUSANDS.matcher(out);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, m.group(0).replace(",", ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---- pauses ---------------------------------------------------------------
    //
    // The gaps the engine leaves at punctuation and at the end of what it
    // is given. Eloquence pauses generously: fine in prose, dead air in a
    // screen reader where most utterances are a few words. The engine has
    // no setting for it, but takes an annotation (`p plus milliseconds),
    // and one placed in front of a mark replaces that mark's pause.
    //
    // The mark rule (after the NVDA IBMTTS driver's "JAWS-like" pauses):
    // a mark counts past a letter, digit or space and before whitespace,
    // a slash or the end -- which keeps the point in 3.14 and the colon
    // in 2:30 untouched.

    /** Leave the engine's own pauses alone. */
    public static final int PAUSES_KEEP = 0;
    /** Shorten only the gap after the last thing said. */
    public static final int PAUSES_END_ONLY = 1;
    /** Shorten the gaps at punctuation as well. */
    public static final int PAUSES_ALL = 2;

    private static final String PAUSE_MARKS = "-,.:;?!\u2013\u2014";
    private static final Pattern AT_A_MARK = Pattern.compile(
            "([A-Za-z0-9]|\\s)([-,.:;?!\u2013\u2014])(\\2*?)(\\s|[/\\\\]|$)");
    private static final String PAUSE_BRIEF = "`p1";
    /** The engine leaves ~400 ms after the last word; cutting all of it
     *  parks the final syllable against the buffer end, where playback
     *  clips it ("Google Gemini" -> "Google Gemin"). A hundred of those
     *  milliseconds stay, as somewhere for it to land. */
    private static final String PAUSE_AT_END = "`p100";

    /** Text with the pauses {@code mode} asks for; {@code last} says this
     *  is the end of what was asked for. Adds annotations: run last. */
    public static String shortenPauses(String text, int mode, boolean last) {
        if (text == null || text.isEmpty() || mode == PAUSES_KEEP) {
            return text == null ? "" : text;
        }
        String out = text;
        if (mode == PAUSES_ALL) {
            out = AT_A_MARK.matcher(out).replaceAll("$1 " + PAUSE_BRIEF + "$2$3$4");
        }
        if (last) {
            String trimmed = out.replaceAll("\\s+$", "");
            if (!trimmed.isEmpty()
                    && PAUSE_MARKS.indexOf(trimmed.charAt(trimmed.length() - 1)) < 0) {
                out = out + " " + PAUSE_AT_END;
            }
        }
        return out;
    }

    // ---- number words ------------------------------------------------------------

    private static final String[] ONES = {"zero", "one", "two", "three",
            "four", "five", "six", "seven", "eight", "nine"};
    private static final String[] TEENS = {"ten", "eleven", "twelve",
            "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty",
            "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
    private static final String[] ORDINAL_ONES = {"zeroth", "first",
            "second", "third", "fourth", "fifth", "sixth", "seventh",
            "eighth", "ninth"};
    private static final String[] ORDINAL_TEENS = {"tenth", "eleventh",
            "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth",
            "seventeenth", "eighteenth", "nineteenth"};
    private static final String[] ORDINAL_TENS = {"", "", "twentieth", "thirtieth"};
    private static final String[] MONTH_NAMES = {"", "January", "February",
            "March", "April", "May", "June", "July", "August", "September",
            "October", "November", "December"};

    /** 0-99 as words ("five", "twenty-two"). */
    public static String cardinalWord(int n) {
        if (n < 0 || n > 99) return String.valueOf(n);
        if (n < 10) return ONES[n];
        if (n < 20) return TEENS[n - 10];
        if (n % 10 == 0) return TENS[n / 10];
        return TENS[n / 10] + "-" + ONES[n % 10];
    }

    /** Minutes past the hour ("oh five" for 1-9, else the cardinal). */
    public static String minutesPastWord(int n) {
        if (n < 0 || n > 59) return String.valueOf(n);
        if (n >= 1 && n <= 9) return "oh " + ONES[n];
        return cardinalWord(n);
    }

    /** 1-31 as an ordinal ("first", "twenty-second"). */
    public static String ordinalWord(int n) {
        if (n < 1 || n > 31) return String.valueOf(n);
        if (n < 10) return ORDINAL_ONES[n];
        if (n < 20) return ORDINAL_TEENS[n - 10];
        if (n % 10 == 0) return ORDINAL_TENS[n / 10];
        return TENS[n / 10] + "-" + ORDINAL_ONES[n % 10];
    }

    /** A four-digit year the way people say it ("nineteen ninety-eight",
     *  "nineteen hundred", "two thousand", "nineteen oh five"). */
    public static String yearWord(int year) {
        if (year < 1000 || year > 9999) return String.valueOf(year);
        int firstHalf = year / 100;
        int secondHalf = year % 100;
        if (secondHalf == 0 && firstHalf % 10 == 0) {
            return cardinalWord(firstHalf / 10) + " thousand";
        }
        if (secondHalf == 0) return cardinalWord(firstHalf) + " hundred";
        if (secondHalf < 10) return cardinalWord(firstHalf) + " oh " + ONES[secondHalf];
        return cardinalWord(firstHalf) + " " + cardinalWord(secondHalf);
    }

    // ---- natural time and date -------------------------------------------------------
    //
    // Clock times and numeric dates read the way a person says them, not
    // digit by digit ("10:30 AM" -> "ten thirty AM", "12:05" ->
    // "twelve oh five", "14/07/2024" -> "fourteenth July twenty
    // twenty-four"). Only converts what the digits alone prove: a
    // 12-hour suffix on 13-23 is malformed input and passes through, and
    // an ambiguous pair ("05/08/2024") is left alone rather than guessed
    // at. "24/7" and "16/9" are idioms, not dates.

    private static final Pattern TIME_RE = Pattern.compile(
            "\\b([01]?\\d|2[0-3]):([0-5]\\d)(?!:\\d)(\\s*[AaPp]\\.?[Mm]\\.?)?(?![A-Za-z0-9])");
    private static final Pattern DATE_RE = Pattern.compile(
            "\\b(\\d{1,4})([/\\-])(\\d{1,4})(?:([/\\-])(\\d{1,4}))?\\b");

    /** Clock times and numeric dates as words (English names). */
    public static String expandTimeDate(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        if (text.indexOf(':') < 0 && text.indexOf('/') < 0 && text.indexOf('-') < 0) {
            return text;
        }
        return applyNaturalDateReading(applyNaturalTimeReading(text));
    }

    static String applyNaturalTimeReading(String text) {
        java.util.regex.Matcher m = TIME_RE.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int hour, minute;
            try {
                hour = Integer.parseInt(m.group(1));
                minute = Integer.parseInt(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            String suffix = m.group(3) == null ? "" : m.group(3).trim();
            if (!suffix.isEmpty() && (hour < 1 || hour > 12)) continue;
            String hourWord;
            if (!suffix.isEmpty()) {
                hourWord = cardinalWord(hour);
            } else if (hour == 0) {
                hourWord = "midnight";
            } else if (hour == 12 && minute == 0) {
                hourWord = "noon";
            } else {
                hourWord = cardinalWord(hour);
            }
            StringBuilder say = new StringBuilder(hourWord);
            if (minute == 0) {
                if (!hourWord.equals("midnight") && !hourWord.equals("noon")) {
                    say.append(" o'clock");
                }
            } else {
                say.append(' ').append(minutesPastWord(minute));
            }
            if (!suffix.isEmpty()) {
                char first = suffix.charAt(0);
                say.append(first == 'a' || first == 'A' ? " AM" : " PM");
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(say.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String applyNaturalDateReading(String text) {
        java.util.regex.Matcher m = DATE_RE.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String raw = m.group(0);
            if ("24/7".equals(raw) || "16/9".equals(raw)) continue;
            String sep1 = m.group(2);
            String sep2 = m.group(4);
            int p1, p2;
            try {
                p1 = Integer.parseInt(m.group(1));
                p2 = Integer.parseInt(m.group(3));
            } catch (NumberFormatException e) {
                continue;
            }
            String g5 = m.group(5);
            Integer p3 = null;
            if (g5 != null && !g5.isEmpty()) {
                try {
                    p3 = Integer.parseInt(g5);
                } catch (NumberFormatException e) {
                    continue;
                }
            }
            int partsCount = (p3 != null) ? 3 : 2;
            // A two-part hyphenation ("5-3") is a score or range, never
            // a date; hyphenated dates are strictly three-part (ISO).
            if (partsCount == 2 && "-".equals(sep1)) continue;
            if (partsCount == 3 && !sep1.equals(sep2)) continue;
            int yearIndex = -1;
            Integer fullYear = null;
            if (p1 >= 1000 && p1 <= 9999) {
                yearIndex = 0;
                fullYear = p1;
            } else if (p2 >= 1000 && p2 <= 9999) {
                yearIndex = 1;
                fullYear = p2;
            } else if (p3 != null && p3 >= 1000 && p3 <= 9999) {
                yearIndex = 2;
                fullYear = p3;
            } else if (partsCount == 3 && p3 != null && p3 >= 0 && p3 <= 99) {
                // Two-digit year, 50-pivot: >= 50 is 19xx, else 20xx.
                yearIndex = 2;
                fullYear = (p3 >= 50) ? 1900 + p3 : 2000 + p3;
            }
            int a, b;
            if (partsCount == 3) {
                if (yearIndex == 0) {
                    a = 1;
                    b = 2;
                } else if (yearIndex == 1) {
                    a = 0;
                    b = 2;
                } else if (yearIndex == 2) {
                    a = 0;
                    b = 1;
                } else {
                    continue;
                }
            } else {
                if (yearIndex != -1) continue;
                a = 0;
                b = 1;
            }
            int partA = (a == 0) ? p1 : (a == 1) ? p2 : p3;
            int partB = (b == 0) ? p1 : (b == 1) ? p2 : p3;
            int dayIndex;
            if (partA >= 13 && partA <= 31) {
                dayIndex = a;
            } else if (partB >= 13 && partB <= 31) {
                dayIndex = b;
            } else {
                // Ambiguous from the digits alone: left alone, not guessed.
                continue;
            }
            int monthIndex = (a == dayIndex) ? b : a;
            int partMonth = (monthIndex == 0) ? p1 : (monthIndex == 1) ? p2 : p3;
            if (partMonth < 1 || partMonth > 12) continue;
            StringBuilder say = new StringBuilder();
            for (int idx = 0; idx < partsCount; idx++) {
                int part = (idx == 0) ? p1 : (idx == 1) ? p2 : p3;
                if (say.length() > 0) say.append(' ');
                if (idx == yearIndex) {
                    say.append(yearWord(fullYear));
                } else if (idx == dayIndex) {
                    say.append(ordinalWord(part));
                } else {
                    say.append(MONTH_NAMES[partMonth]);
                }
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(say.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---- Roman numerals -----------------------------------------------------------
    //
    // Contextual only ("Chapter IV" -> "Chapter 4", "Henry VIII" ->
    // "Henry the eighth"): a bare "I" or "V" in prose is never touched.

    private static int parseRomanNumeral(String s) {
        if (s == null || s.isEmpty() || s.length() > 15) return -1;
        int total = 0;
        int prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            int curr;
            if (c == 'I' || c == 'i') curr = 1;
            else if (c == 'V' || c == 'v') curr = 5;
            else if (c == 'X' || c == 'x') curr = 10;
            else if (c == 'L' || c == 'l') curr = 50;
            else if (c == 'C' || c == 'c') curr = 100;
            else if (c == 'D' || c == 'd') curr = 500;
            else if (c == 'M' || c == 'm') curr = 1000;
            else return -1;
            if (curr < prev) total -= curr;
            else total += curr;
            prev = curr;
        }
        return (total >= 1 && total <= 3999) ? total : -1;
    }

    private static final Pattern SECTION_ROMAN = Pattern.compile(
            "\\b(Chapter|Section|Part|Book|Volume|Vol\\.|Title|Article|Act|Scene|"
                    + "World\\s+War|War|Phase|Tier|Grade|Level|Class|Division|Type|Mark|"
                    + "Apollo|Voyager|PlayStation|Final\\s+Fantasy|Super\\s+Bowl)"
                    + "\\s+([IVXLCDM]+)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final String ROYAL_TITLES =
            "King|Queen|Pope|Emperor|Empress|Prince|Princess|Tsar|Czar|Kaiser|Archduke";
    private static final String ROYAL_NAMES =
            "Henry|Elizabeth|George|Charles|Louis|Edward|James|William|Richard|Philip|"
                    + "John\\s+Paul|Benedict|Francis|Alexander|Nicholas|Peter|Mary|Victoria|"
                    + "Napoleon|Catherine|Frederick|Ferdinand|Paul|John|Leo|Gregory|Pius|Innocent";
    private static final Pattern MONARCH_WITH_TITLE = Pattern.compile(
            "\\b(" + ROYAL_TITLES + ")\\s+([A-Z][a-z]+)\\s+([IVXLCDM]+)\\b");
    private static final Pattern MONARCH_NAME_ONLY = Pattern.compile(
            "\\b(" + ROYAL_NAMES + ")\\s+([IVXLCDM]+)\\b");

    /** Roman numerals in headings, works and monarch names as words. */
    public static String expandRomanNumerals(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        boolean hint = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 'I' || c == 'V' || c == 'X' || c == 'L'
                    || c == 'C' || c == 'D' || c == 'M') {
                hint = true;
                break;
            }
        }
        if (!hint) return text;
        String out = replaceRomanSection(text);
        out = replaceRomanMonarch(out, true);
        out = replaceRomanMonarch(out, false);
        return out;
    }

    private static String replaceRomanSection(String text) {
        java.util.regex.Matcher m = SECTION_ROMAN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int num = parseRomanNumeral(m.group(2).toUpperCase(java.util.Locale.US));
            if (num < 0) continue;
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    m.group(1) + " " + num));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceRomanMonarch(String text, boolean withTitle) {
        java.util.regex.Matcher m = withTitle
                ? MONARCH_WITH_TITLE.matcher(text) : MONARCH_NAME_ONLY.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = withTitle ? m.group(1) + " " + m.group(2) : m.group(1);
            int num = parseRomanNumeral(withTitle ? m.group(3) : m.group(2));
            if (num < 0) continue;
            String say = (num >= 1 && num <= 31)
                    ? name + " the " + ordinalWord(num) : name + " " + num;
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(say));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---- isolated punctuation ---------------------------------------------------------
    //
    // Standalone marks bounded by whitespace (a lone "-" bullet or "***"
    // separator) that the engine reads aloud as words, where IBM's
    // original stayed silent. Only whitespace-delimited tokens made
    // entirely of punctuation count; marks attached to a word ("wait...",
    // "Mr.") are left alone.

    /** Text with isolated punctuation bullets silenced. */
    public static String stripIsolatedPunctuation(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        StringBuilder out = new StringBuilder(text.length());
        int start = 0;
        int len = text.length();
        boolean firstToken = true;
        while (start < len) {
            // Skip leading whitespace
            while (start < len && Character.isWhitespace(text.charAt(start))) {
                start++;
            }
            if (start >= len) break;
            // Find end of token
            int end = start;
            while (end < len && !Character.isWhitespace(text.charAt(end))) {
                end++;
            }
            // Check if token is pure punctuation
            boolean pure = true;
            for (int i = start; i < end; ) {
                int cp = text.codePointAt(i);
                if (Character.isLetterOrDigit(cp)) {
                    pure = false;
                    break;
                }
                i += Character.charCount(cp);
            }
            if (!pure) {
                if (!firstToken) out.append(' ');
                out.append(text, start, end);
                firstToken = false;
            }
            start = end;
        }
        return out.toString();
    }
}
