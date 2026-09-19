package com.eloquick.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clause splitting, sentence-end detection and script segmentation for text
 * handed to the engine.
 *
 * <p>Ported from tgspeechbox-ref's
 * {@code nvdaAddon/synthDrivers/tgSpeechBox/text_utils.py} (the
 * {@code re_textPause} clause splitter, {@code normalizeTextForEspeak} and
 * {@code splitByScript} with its neutral-attachment passes). Only the
 * engine-agnostic text logic is taken; eSpeak IPA routing is not - this
 * engine reads one language per utterance, so script segments are exposed
 * for chunking/diagnostics rather than per-segment voice switching.
 *
 * <p>Pure Java, no Android dependencies. Flexible: {@link #ENABLED} is a
 * global kill-switch and every stage is public static for a-la-carte use.
 */
public final class EqClauses {
    private EqClauses() {}

    /** Global kill-switch; when false every method returns its input. */
    public static boolean ENABLED = true;

    // Split on punctuation+space for clause pauses. Closing quotes/brackets
    // between the mark and the whitespace count as part of the boundary
    // (." The). The mark itself is consumed (not just asserted) so the
    // digit guard sees the character BEFORE the dot: a dot preceded by a
    // digit never splits ("3. Mai" ordinals); ?!:,; always split. (The
    // donor's zero-width form asserts at the whitespace, where the previous
    // character is the dot itself - which can never be a digit, so its guard
    // could not fire; this form implements the documented intent.)
    private static final Pattern TEXT_PAUSE = Pattern.compile(
            "(?:(?<!\\d)[.]|[?!,:;])[)\\]\"\u2019\u201D']*\\s",
            Pattern.DOTALL | Pattern.UNICODE_CASE);

    private static final Pattern LINE_BREAKS = Pattern.compile("[\r\n\u2028\u2029]+");
    private static final Pattern SPACE_RUNS = Pattern.compile("[\t \u00A0]+");
    private static final Pattern ELLIPSIS = Pattern.compile("\u2026");
    private static final Pattern ELLIPSIS_SPACE =
            Pattern.compile("\\.{2,}(?=[A-Za-z0-9\u00C0-\u024F])");
    private static final Pattern SENT_END =
            Pattern.compile("(?:[.!?]+|\\.{3})[)\\]\"']*\\s*$");

    /**
     * Normalizes whitespace and ellipses: U+2026 to "...", a space after a
     * "..." glued to a word (so clause splitting sees the boundary),
     * newlines to spaces, collapsed space runs, trimmed.
     * Optimized: single pass with StringBuilder for all replacements.
     */
    public static String normalize(String text) {
        if (!ENABLED || text == null || text.isEmpty()) return text == null ? "" : text;
        // Single-pass normalization for better performance
        StringBuilder sb = new StringBuilder(text.length() + 16);
        int len = text.length();
        for (int i = 0; i < len; ) {
            char c = text.charAt(i);
            if (c == '\u2026') { // ellipsis char
                sb.append("...");
                i++;
            } else if (c == '.' && i + 2 < len && text.charAt(i + 1) == '.' && text.charAt(i + 2) == '.') {
                // Handle ... sequence
                sb.append("...");
                i += 3;
                // Add space if followed by alphanumeric
                if (i < len && Character.isLetterOrDigit(text.charAt(i))) {
                    sb.append(' ');
                }
            } else if (c == '\r' || c == '\n' || c == '\u2028' || c == '\u2029') {
                // Line breaks to space
                sb.append(' ');
                i++;
                // Skip consecutive line breaks
                while (i < len && (text.charAt(i) == '\r' || text.charAt(i) == '\n' || text.charAt(i) == '\u2028' || text.charAt(i) == '\u2029')) {
                    i++;
                }
            } else if (c == '\t' || c == ' ' || c == '\u00A0') {
                // Collapse whitespace runs
                sb.append(' ');
                i++;
                while (i < len && (text.charAt(i) == '\t' || text.charAt(i) == ' ' || text.charAt(i) == '\u00A0')) {
                    i++;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        // Trim
        int start = 0;
        int end = sb.length();
        while (start < end && Character.isWhitespace(sb.charAt(start))) start++;
        while (end > start && Character.isWhitespace(sb.charAt(end - 1))) end--;
        return sb.substring(start, end);
    }

    /**
     * Splits normalized text into clauses at pause boundaries. The boundary
     * characters stay with the preceding clause; whitespace between clauses
     * is dropped. Never returns an empty list.
     */
    public static List<String> splitClauses(String text) {
        if (!ENABLED || text == null || text.isEmpty()) {
            return Collections.singletonList(text == null ? "" : text);
        }
        List<String> out = new ArrayList<>();
        Matcher m = TEXT_PAUSE.matcher(text);
        int start = 0;
        while (m.find()) {
            int end = m.end();
            // Keep the boundary, drop the trailing whitespace run.
            int trim = end;
            while (trim > start && Character.isWhitespace(text.charAt(trim - 1))) trim--;
            if (trim > start) out.add(text.substring(start, trim));
            start = end;
        }
        if (start < text.length()) {
            String tail = text.substring(start).trim();
            if (!tail.isEmpty()) out.add(tail);
        }
        if (out.isEmpty()) out.add(text.trim());
        return out;
    }

    /** True when the string ends with sentence-ending punctuation (Say-All
     * coalescing aid). */
    public static boolean looksLikeSentenceEnd(String text) {
        if (!ENABLED || text == null || text.isEmpty()) return false;
        return SENT_END.matcher(text.strip()).find();
    }

    /** One script segment: {@link #latin} false means the base language's
     * own script, true means a Latin-script run inside it. */
    public static final class Segment {
        public final String text;
        public final boolean latin;
        public Segment(String text, boolean latin) {
            this.text = text;
            this.latin = latin;
        }
        @Override public String toString() {
            return (latin ? "[L]" : "[N]") + text;
        }
    }

    // Base language codes (before any hyphen) whose primary script is not
    // Latin. Anything else returns the text as one native segment.
    private static final Set<String> NON_LATIN_LANGS = buildNonLatin();

    private static Set<String> buildNonLatin() {
        Set<String> s = new HashSet<>();
        Collections.addAll(s, "ru bg uk sr mk be kk ky mn tg ba".split(" ")); // Cyrillic
        s.add("el"); // Greek
        Collections.addAll(s, "ar fa ur ps ku".split(" ")); // Arabic script
        Collections.addAll(s, "he yi".split(" ")); // Hebrew
        s.add("ka"); // Georgian
        s.add("hy"); // Armenian
        Collections.addAll(s, "zh ja ko".split(" ")); // CJK
        Collections.addAll(s, "th km lo my".split(" ")); // SE Asia
        Collections.addAll(s, "hi mr ne sa bn gu pa ta te kn ml si".split(" ")); // Indic
        Collections.addAll(s, "am ti".split(" ")); // Ethiopic
        return Collections.unmodifiableSet(s);
    }

    private static boolean isLatinLetter(int cp) {
        return (cp >= 0x0041 && cp <= 0x005A)
                || (cp >= 0x0061 && cp <= 0x007A)
                || (cp >= 0x00C0 && cp <= 0x024F);
    }

    private static boolean isNeutral(int cp) {
        if (Character.isWhitespace(cp)) return true;
        if (Character.isDigit(cp)) return true;
        if (cp < 0x0041) return true;
        if (cp >= 0x2000 && cp <= 0x206F) return true;
        if (cp >= 0x2200 && cp <= 0x22FF) return true;
        return "[]{}()\u00AB\u00BB\u2039\u203A\"''\u2014\u2013\u2026\u00B7\u2022\u00A7\u00B6\u00A9\u00AE\u2122\u00B0\u00B1\u00D7\u00F7/\\|@#$%^&*~`".indexOf(cp) >= 0;
    }

    /**
     * Splits text into native/Latin script segments. Neutrals attach to the
     * surrounding script (forward pass), neutrals before a script change
     * attach forward (backward pass), and digits are always native - numbers
     * must be spoken by the base voice. Latin-script base languages return a
     * single native segment.
     * Optimized: combines classification, forward pass, and digit override in one loop.
     */
    public static List<Segment> splitByScript(String text, String baseLang) {
        List<Segment> single =
                Collections.singletonList(new Segment(text == null ? "" : text, false));
        if (!ENABLED || text == null || text.isEmpty()) return single;
        String base = baseLang == null ? "" : baseLang.trim().toLowerCase(java.util.Locale.ROOT);
        int dash = base.indexOf('-');
        if (dash >= 0) base = base.substring(0, dash);
        if (!NON_LATIN_LANGS.contains(base)) return single;

        int n = text.length();
        // Classify per char (0 = native, 1 = latin, -1 = neutral) + forward pass + digit override.
        // byte[] is enough for {-1,0,1} and quarters the temp allocation vs int[].
        byte[] kind = new byte[n];
        byte[] resolved = new byte[n];
        int last = 0;
        for (int i = 0; i < n;) {
            int cp = text.codePointAt(i);
            int cl = Character.charCount(cp);
            int k = isLatinLetter(cp) ? 1 : (isNeutral(cp) ? -1 : 0);
            // Digit override: digits are always native (0)
            if (Character.isDigit(cp)) k = 0;
            byte kb = (byte) k;
            for (int j = i; j < i + cl && j < n; j++) {
                kind[j] = kb;
            }
            // Forward pass inline
            if (k >= 0) last = k;
            byte lb = (byte) last;
            for (int j = i; j < i + cl && j < n; j++) {
                resolved[j] = lb;
            }
            i += cl;
        }
        // Backward pass: a neutral followed by the native script attaches
        // forward to it; neutrals between two Latin runs stay Latin.
        int next = resolved[n - 1];
        for (int i = n - 1; i >= 0; i--) {
            if (kind[i] < 0 && next == 0 && resolved[i] != 0) resolved[i] = 0;
            else if (kind[i] >= 0) next = resolved[i];
        }
        // Group consecutive same-script characters.
        List<Segment> out = new ArrayList<>(Math.min(n / 20 + 2, 64));
        int start = 0;
        for (int i = 1; i <= n; i++) {
            if (i == n || resolved[i] != resolved[start]) {
                out.add(new Segment(text.substring(start, i), resolved[start] == 1));
                start = i;
            }
        }
        return out;
    }
}
