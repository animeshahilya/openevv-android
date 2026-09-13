package com.eloquick.debug;

import java.util.regex.Pattern;

/** Small text guards applied before synthesis, each attested in this
 * repo's own tree rather than borrowed from a driver:
 *
 * - Wednesday misspellings ("edhesday", "w?enhesday", ...) are crashers in
 *   test/cases/crashers.txt: the engine survives them now (forced-backtrack
 *   landing), but the utterance is abandoned mid-sentence. Normalising the
 *   common misspellings to Wednesday keeps the sentence.
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
}
