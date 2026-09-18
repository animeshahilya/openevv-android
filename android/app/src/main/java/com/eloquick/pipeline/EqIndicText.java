package com.eloquick.pipeline;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Indian-text preprocessing for synthesis input: Indic digits, danda
 * spacing, banking slash tokens, rupee/paise verbalization, lakh/crore
 * grouping and shorthand (k/L/cr).
 *
 * <p>Ported from the espeak-ng Android fork's {@code TtsService}
 * (same-house repo, not the revived one): {@code preprocessIndianText} and
 * its helpers. Currency/time/date, emoji framing and OTP digit-splitting
 * already exist in this tree's own pipeline and are deliberately NOT
 * duplicated here - except {@link #spaceSeparateSmartCodes}, kept as an
 * explicit opt-in for callers that want keyword-gated digit-by-digit codes.
 *
 * <p>Pure Java, no Android dependencies. Flexible: {@link #ENABLED} is a
 * global kill-switch, {@link #apply} is a no-op unless the text carries
 * Indian nuance characters (donor's gate), and every stage is public static.
 */
public final class EqIndicText {
    private EqIndicText() {}

    /** Global kill-switch; when false every method returns its input. */
    public static boolean ENABLED = true;

    // "code" is deliberately not a keyword on its own (donor's note): an
    // ordinary word ("dress code") that false-positives on plain sentences.
    private static final Pattern SMART_CODE_KEYWORD = Pattern.compile(
            "(?i)\\b(otp|pin|passcode|password|secret|verification|security|token|login|id|txn|ref|vpa|cvv)\\b");
    private static final Pattern DANDA_BOUNDARY =
            Pattern.compile("([\u0964\u0965])([^\\s])");
    private static final Pattern BANKING_SLASH_TXN = Pattern.compile(
            "(?i)\\b(UPI|TXN|REF|IMPS|NEFT|RTGS)/([A-Za-z0-9/]+)");
    private static final Pattern SLASH_RUN = Pattern.compile("/+");
    private static final Pattern CURRENCY_PREFIX = Pattern.compile(
            "(?i)(?:\u20B9|\\b(?:Rs\\.?|INR)\\s*)([0-9]+(?:,[0-9]+)*(?:\\.[0-9]+)?)");
    private static final Pattern INDIAN_NUMBER_COMMAS =
            Pattern.compile("\\b(\\d{1,2}(?:,\\d{2})+),(\\d{3})\\b");
    private static final Pattern SHORTHAND_THOUSAND =
            Pattern.compile("(?i)\\b(\\d+(?:\\.\\d+)?)\\s*k\\b");
    private static final Pattern SHORTHAND_LAKH = Pattern.compile(
            "(?i)\\b(\\d+(?:\\.\\d+)?)\\s*(?:l|lac|lakh|lakhs)\\b");
    private static final Pattern SHORTHAND_CRORE = Pattern.compile(
            "(?i)\\b(\\d+(?:\\.\\d+)?)\\s*(?:cr|crore|crores)\\b");

    /** True when the text carries anything this stage could act on. */
    public static boolean containsIndianNuanceChars(String text) {
        if (text == null || text.isEmpty()) return false;
        final int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if ((c >= 0x0900 && c <= 0x0D7F) || c == 0x20B9 || c == '/' || c == ','
                    || c == 'k' || c == 'K' || c == 'l' || c == 'L'
                    || c == 'c' || c == 'C' || c == 'r' || c == 'R'
                    || c == 's' || c == 'S' || c == 'i' || c == 'I') {
                return true;
            }
        }
        return false;
    }

    /** True for Devanagari-script number languages (hi/mr/ne/sa/kok):
     * units come out as लाख/करोड़/रुपये/पैसे, otherwise Latin. */
    public static boolean isDevanagariNumberLang(String languageTag) {
        if (languageTag == null || languageTag.isEmpty()) return false;
        String base = languageTag.trim().toLowerCase(java.util.Locale.ROOT);
        int dash = base.indexOf('-');
        if (dash >= 0) base = base.substring(0, dash);
        return base.equals("hi") || base.equals("mr") || base.equals("ne")
                || base.equals("sa") || base.equals("kok");
    }

    /** Folds native Indic numerals across 9 scripts to ASCII 0-9. */
    public static String normalizeIndicDigits(String text) {
        if (!ENABLED || text == null || text.isEmpty()) return text == null ? "" : text;
        final int len = text.length();
        StringBuilder sb = null;
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            char ascii = 0;
            if (c >= 0x0966 && c <= 0x0D6F) {
                if (c <= 0x096F) ascii = (char) ('0' + (c - 0x0966));
                else if (c >= 0x09E6 && c <= 0x09EF) ascii = (char) ('0' + (c - 0x09E6));
                else if (c >= 0x0A66 && c <= 0x0A6F) ascii = (char) ('0' + (c - 0x0A66));
                else if (c >= 0x0AE6 && c <= 0x0AEF) ascii = (char) ('0' + (c - 0x0AE6));
                else if (c >= 0x0B66 && c <= 0x0B6F) ascii = (char) ('0' + (c - 0x0B66));
                else if (c >= 0x0BE6 && c <= 0x0BEF) ascii = (char) ('0' + (c - 0x0BE6));
                else if (c >= 0x0C66 && c <= 0x0C6F) ascii = (char) ('0' + (c - 0x0C66));
                else if (c >= 0x0CE6 && c <= 0x0CEF) ascii = (char) ('0' + (c - 0x0CE6));
                else if (c >= 0x0D66 && c <= 0x0D6F) ascii = (char) ('0' + (c - 0x0D66));
            }
            if (ascii != 0) {
                if (sb == null) {
                    sb = new StringBuilder(len);
                    sb.append(text, 0, i);
                }
                sb.append(ascii);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb != null ? sb.toString() : text;
    }

    /**
     * Verbalizes an Indian-comma-grouped figure ("1,00,000" to "1 lakh").
     * Remainders below one lakh stay digits; below one lakh or above 999
     * crore the figure just strips to digits.
     */
    public static String indianGroupedNumberToWords(String grouped, boolean devanagari) {
        if (!ENABLED) return grouped;
        String lakhWord = devanagari ? "\u0932\u093E\u0916" : "lakh";
        String croreWord = devanagari ? "\u0915\u0930\u094B\u0921\u093C" : "crore";
        if (grouped == null || grouped.isEmpty()) return grouped;
        String digits = grouped.replace(",", "");
        long value;
        try {
            value = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return digits;
        }
        if (value < 100000 || value > 9999999999L) return digits;
        StringBuilder out = new StringBuilder();
        long crore = value / 10000000L;
        long rest = value % 10000000L;
        if (crore > 0) {
            out.append(crore).append(' ').append(croreWord);
            if (rest > 0) out.append(' ');
        }
        if (rest > 0) {
            long lakh = rest / 100000L;
            long rest2 = rest % 100000L;
            if (lakh > 0) {
                out.append(lakh).append(' ').append(lakhWord);
                if (rest2 > 0) out.append(' ').append(rest2);
            } else {
                out.append(rest2);
            }
        }
        return out.toString();
    }

    /**
     * "1,00,000" to "1 lakh rupees", "10.50" to "10 rupees 50 paise", "500"
     * to "500 rupees". A 1-2 digit nonzero fraction becomes paise; longer
     * fractions stay decimal for the engine.
     */
    public static String indianRupeeAmountToWords(String amount, boolean devanagari) {
        if (!ENABLED) return amount;
        String rupeesWord = devanagari ? "\u0930\u0941\u092A\u092F\u0947" : "rupees";
        String paiseWord = devanagari ? "\u092A\u0948\u0938\u0947" : "paise";
        if (amount == null || amount.isEmpty()) return " " + rupeesWord;
        int dot = amount.indexOf('.');
        String intPart = dot >= 0 ? amount.substring(0, dot) : amount;
        String fracPart = dot >= 0 ? amount.substring(dot + 1) : "";
        String intWords = intPart.contains(",")
                ? indianGroupedNumberToWords(intPart, devanagari)
                : intPart.replace(",", "");
        if (intWords.isEmpty()) intWords = "0";
        StringBuilder out = new StringBuilder(intWords).append(' ').append(rupeesWord);
        if (fracPart.length() >= 1 && fracPart.length() <= 2) {
            int paise = -1;
            try {
                paise = Integer.parseInt(fracPart);
            } catch (NumberFormatException ignored) {
            }
            if (paise > 0) {
                out.append(' ').append(paise).append(' ').append(paiseWord);
            } else if (paise < 0) {
                out.append('.').append(fracPart);
            }
        } else if (!fracPart.isEmpty()) {
            return intWords + "." + fracPart + " " + rupeesWord;
        }
        return out.toString();
    }

    /**
     * Full pass: digits, danda spacing, banking slashes, rupee amounts,
     * grouped figures, shorthand. No-op unless nuance characters are
     * present. Devanagari-script voice tags get native units.
     */
    public static String apply(String text, String languageTag) {
        if (!ENABLED || text == null || text.isEmpty()
                || !containsIndianNuanceChars(text)) {
            return text == null ? "" : text;
        }
        final boolean devanagari = isDevanagariNumberLang(languageTag);
        text = normalizeIndicDigits(text);
        text = DANDA_BOUNDARY.matcher(text).replaceAll("$1 $2");
        Matcher txnMatcher = BANKING_SLASH_TXN.matcher(text);
        if (txnMatcher.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                String expanded = SLASH_RUN.matcher(txnMatcher.group(0)).replaceAll(" / ");
                txnMatcher.appendReplacement(sb, Matcher.quoteReplacement(expanded));
            } while (txnMatcher.find());
            txnMatcher.appendTail(sb);
            text = sb.toString();
        }
        Matcher currMatcher = CURRENCY_PREFIX.matcher(text);
        if (currMatcher.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                currMatcher.appendReplacement(sb, Matcher.quoteReplacement(
                        indianRupeeAmountToWords(currMatcher.group(1), devanagari)));
            } while (currMatcher.find());
            currMatcher.appendTail(sb);
            text = sb.toString();
        }
        Matcher numMatcher = INDIAN_NUMBER_COMMAS.matcher(text);
        if (numMatcher.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                numMatcher.appendReplacement(sb, Matcher.quoteReplacement(
                        indianGroupedNumberToWords(numMatcher.group(0), devanagari)));
            } while (numMatcher.find());
            numMatcher.appendTail(sb);
            text = sb.toString();
        }
        if (devanagari) {
            text = SHORTHAND_THOUSAND.matcher(text).replaceAll("$1 \u0939\u091C\u093C\u093E\u0930");
            text = SHORTHAND_LAKH.matcher(text).replaceAll("$1 \u0932\u093E\u0916");
            text = SHORTHAND_CRORE.matcher(text).replaceAll("$1 \u0915\u0930\u094B\u0921\u093C");
        } else {
            text = SHORTHAND_THOUSAND.matcher(text).replaceAll("$1 thousand");
            text = SHORTHAND_LAKH.matcher(text).replaceAll("$1 lakh");
            text = SHORTHAND_CRORE.matcher(text).replaceAll("$1 crore");
        }
        return text;
    }

    /**
     * Opt-in only: space-separates 4-8 digit runs (configurable) for
     * digit-by-digit reading when an OTP/PIN-style keyword sits within 25
     * characters, and expands a bare rupee sign. Ordinary numbers ("year
     * 2024") are untouched. NOT part of {@link #apply} - callers enable it
     * deliberately.
     */
    public static String spaceSeparateSmartCodes(String text, int minLen, int maxLen) {
        if (!ENABLED || text == null || text.isEmpty()) return text == null ? "" : text;
        final int len = text.length();
        StringBuilder out = new StringBuilder(len + 16);
        int i = 0;
        while (i < len) {
            int cp = text.codePointAt(i);
            if (Character.isDigit(cp)) {
                int runStart = i;
                int digitCount = 0;
                while (i < len) {
                    int c = text.codePointAt(i);
                    if (!Character.isDigit(c)) break;
                    digitCount++;
                    i += Character.charCount(c);
                }
                int runEnd = i;
                boolean separate = false;
                if (digitCount >= Math.max(2, minLen) && digitCount <= Math.max(minLen, maxLen)) {
                    String prefix = text.substring(Math.max(0, runStart - 25), runStart);
                    String suffix = text.substring(runEnd, Math.min(len, runEnd + 25));
                    if (SMART_CODE_KEYWORD.matcher(prefix).find()
                            || SMART_CODE_KEYWORD.matcher(suffix).find()) {
                        separate = true;
                    }
                }
                if (separate) {
                    for (int j = runStart; j < runEnd;) {
                        int c = text.codePointAt(j);
                        if (j > runStart) out.append(' ');
                        out.appendCodePoint(c);
                        j += Character.charCount(c);
                    }
                } else {
                    out.append(text, runStart, runEnd);
                }
            } else {
                if (cp == 0x20B9) {
                    out.append(" rupees ");
                } else {
                    out.appendCodePoint(cp);
                }
                i += Character.charCount(cp);
            }
        }
        return out.toString();
    }
}
