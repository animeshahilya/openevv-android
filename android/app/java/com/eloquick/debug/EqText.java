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

    // ---- currency ------------------------------------------------------

    private static final char[] CURRENCY_SYMBOLS = {'$', 0x20AC, 0x00A3, 0x00A5, 0x20B9};
    private static final String[] CURRENCY_WORDS = {"dollars", "euros", "pounds", "yen", "rupees"};

    /** "$5" -> "5 dollars", "5\u20AC" -> "5 euros" (English names). */
    public static String expandCurrency(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        String out = text;
        for (int s = 0; s < CURRENCY_SYMBOLS.length; s++) {
            char sym = CURRENCY_SYMBOLS[s];
            String word = CURRENCY_WORDS[s];
            // Symbol before digits: "$5", "$ 5".
            out = out.replaceAll(Pattern.quote(String.valueOf(sym)) + "\\s*(\\d[\\d.,]*)",
                    "$1 " + word);
            // Symbol after digits: "5$".
            out = out.replaceAll("(\\d[\\d.,]*)\\s*" + Pattern.quote(String.valueOf(sym)),
                    "$1 " + word);
        }
        return out;
    }

    // ---- time and date ---------------------------------------------------

    private static final String[] MONTHS = {"January", "February", "March", "April",
            "May", "June", "July", "August", "September", "October", "November", "December"};

    private static final Pattern TIME_HM =
            Pattern.compile("\\b(\\d{1,2}):(\\d{2})(\\s*[aApP]\\.?[mM]\\.?)?\\b");
    private static final Pattern DATE_ISO =
            Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");

    /** "3:30" -> "3 30", "3:00" -> "3 o'clock"; "2026-09-16" ->
     *  "16 September 2026" (English names). */
    public static String expandTimeDate(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        java.util.regex.Matcher tm = TIME_HM.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (tm.find()) {
            int h;
            try {
                h = Integer.parseInt(tm.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            String min = tm.group(2);
            String ampm = tm.group(3) == null ? "" : tm.group(3).trim().toLowerCase(
                    java.util.Locale.US).replace(".", "");
            String say;
            if ("00".equals(min)) {
                say = h + " o'clock" + ("".equals(ampm) ? "" : " " + ampm);
            } else {
                say = h + " " + min + ("".equals(ampm) ? "" : " " + ampm);
            }
            tm.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(say));
        }
        tm.appendTail(sb);
        java.util.regex.Matcher dm = DATE_ISO.matcher(sb.toString());
        sb = new StringBuffer();
        while (dm.find()) {
            int y, m, d;
            try {
                y = Integer.parseInt(dm.group(1));
                m = Integer.parseInt(dm.group(2));
                d = Integer.parseInt(dm.group(3));
            } catch (NumberFormatException e) {
                continue;
            }
            String say = dm.group(0);
            if (m >= 1 && m <= 12 && d >= 1 && d <= 31) {
                say = d + " " + MONTHS[m - 1] + " " + y;
            }
            dm.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(say));
        }
        dm.appendTail(sb);
        return sb.toString();
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
}
