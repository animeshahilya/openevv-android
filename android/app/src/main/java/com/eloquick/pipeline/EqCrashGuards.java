package com.eloquick.pipeline;

import java.util.regex.Pattern;

/**
 * Crash-guard pre-filters applied to text before it reaches the ECI engine.
 *
 * <p>Ported from NVDA-IBMTTS-Driver's {@code addon/synthDrivers/ibmeci.py}
 * (davidacm) - specifically the {@code *_ibm_fixes} tables and the
 * {@code processText()} pipeline order, which target the same IBM
 * ViaVoice/Eloquence core this repo ships. Sibling check: openevv-android's
 * own {@code EqText} (HEAD) covers Wednesday/emoji/digits/currency/Romans but
 * has none of these engine-crash patterns, and the JNI bridge has dict-load
 * and abbreviation plumbing but no ABRDICT toggle yet - see the notes at the
 * bottom of this file.
 *
 * <p>Donor pipeline order is preserved: rstrip, then backquote strip (unless
 * annotations are kept), then global fixes, then exactly one language family
 * table. Language dispatch uses the engine language word's family half
 * ({@code language & 0xFFFF0000}); families follow {@code Eci.java} in
 * {@code com.eloquick.tts}. Mandarin/Korean/Cantonese get the English table
 * per the donor (dual-language support), matching upstream behaviour.
 *
 * <p>Byte-level donor patterns (cp1252) are mapped to their Unicode
 * equivalents: {@code \x95} bullet to U+2022, {@code \xaa} ordinal to U+00AA,
 * {@code \xb0} degree to U+00B0, {@code \xe6} ae-ligature to U+00E6,
 * {@code \x80} euro to U+20AC, {@code \xa3} pound to U+00A3.
 *
 * <p>Flexibility: {@link #ENABLED} is a global kill-switch, {@link
 * #KEEP_ANNOTATIONS} preserves embedded backquote commands (donor's
 * {@code _backquoteVoiceTags}), and every family table is public static for
 * a-la-carte use. Pure Java, no Android dependencies, so it compiles and
 * tests on any JDK.
 *
 * <p>TODO before release: confirm licence compatibility of the ported
 * patterns with the NVDA add-on's licence, and byte-verify the Unicode
 * mappings above against cp1252-encoded engine input on device.
 */
public final class EqCrashGuards {
    private EqCrashGuards() {}

    /** Global kill-switch; when false every method returns its input. */
    public static boolean ENABLED = true;

    // Precompiled rstrip (String.replaceAll compiles a Pattern per call).
    private static final Pattern RSTRIP = Pattern.compile("\\s+$");

    /**
     * Guarded replace: returns {@code text} untouched (no copy) when the
     * pattern does not occur, instead of paying replaceAll's full copy.
     * Same output as {@code p.matcher(text).replaceAll(repl)} on a hit.
     */
    private static String rep(Pattern p, String text, String repl) {
        java.util.regex.Matcher m = p.matcher(text);
        if (!m.find()) return text;
        return m.replaceAll(repl);
    }

    /**
     * When true, embedded backquote commands ({@code `vs}}, {@code `p1},
     * voice tags) are preserved and the backquote strip is skipped. Default
     * false, matching the donor: stripping must run BEFORE the regexes so
     * hyphen-based crash words stay fixed.
     */
    public static boolean KEEP_ANNOTATIONS = false;

    // ------------------------------------------------------------------
    // ibm_global_fixes: ViaVoice does not tolerate spaces before
    // punctuation, and double spaces around brackets add verbosity.
    // ------------------------------------------------------------------
    private static final Pattern G1_WORD_BEFORE_MARK =
            Pattern.compile("([a-z]+)([~#$%^*({|\\[<%\u2022])", Pattern.CASE_INSENSITIVE);
    private static final Pattern G2_PARENS_S =
            Pattern.compile("([a-z]+)\\s+(\\(s\\))", Pattern.CASE_INSENSITIVE);
    private static final Pattern G3_SPACE_BEFORE_PUNCT =
            Pattern.compile("([a-z]+|\\d+|\\W+)\\s+([:.!;,?](?![a-z]|\\d))",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern G4_OPEN_BRACKETS =
            Pattern.compile("([\\(\\[]+)  (.)");
    private static final Pattern G5_CLOSE_BRACKETS =
            Pattern.compile("(.)  ([\\)\\]]+)");

    public static String globalFixes(String text) {
        if (!ENABLED || text == null || text.isEmpty()) return text == null ? "" : text;
        text = rep(G1_WORD_BEFORE_MARK, text, "$1 $2");
        text = rep(G2_PARENS_S, text, "$1$2");
        text = rep(G3_SPACE_BEFORE_PUNCT, text, "$1$2");
        text = rep(G4_OPEN_BRACKETS, text, "$1$2");
        text = rep(G5_CLOSE_BRACKETS, text, "$1$2");
        return text;
    }

    // ------------------------------------------------------------------
    // english_ibm_fixes: crash words plus the "comma hundred" family.
    // ------------------------------------------------------------------
    private static final Pattern E_MC =
            Pattern.compile("\\b(Mc)\\s+([A-Z][a-z]|[A-Z][A-Z]+)");
    private static final Pattern E_CAESUR =
            Pattern.compile("c(ae|\u00E6)sur(e)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern E_H_APOS =
            Pattern.compile("\\b(\\d+|\\W+)?h'(r|v)[e]", Pattern.CASE_INSENSITIVE);
    private static final Pattern E_HHS =
            Pattern.compile("\\b(\\w+[bdfhjlmnqrvyz])(h[he]s)([abcdefghjklmnopqrstvwy]\\w+)\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern E_HHS_IRON =
            Pattern.compile("\\b(\\w+[bdfhjlmnqrvz])(h[he]s)(iron+[degins]?)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern E_APOS_HHS_A =
            Pattern.compile("\\b(\\w+'{1,}[bcdfghjklmnpqrstvwxyz])'*(h+[he]s)([abcdefghijklmnopqrstvwy]\\w+)\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern E_APOS_HHS_B =
            Pattern.compile("\\b(\\w+[bcdfghjklmnpqrstvwxyz])('{1,}h+[he]s)([abcdefghijklmnopqrstvwy]\\w+)\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern E_TIME_ORD =
            Pattern.compile("(\\d):(\\d\\d[snrt][tdh])", Pattern.CASE_INSENSITIVE);
    private static final Pattern E_CONS_APOS =
            Pattern.compile("\\b([bcdfghjklmnpqrstvwxz]+)'([bcdefghjklmnpqrstvwxz']+)'([drtv][aeiou]?)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern E_YOURE =
            Pattern.compile("\\b(you+)'(re)+'([drv]e?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern E_COSP =
            Pattern.compile("(re|un|non|anti)cosp", Pattern.CASE_INSENSITIVE);
    private static final Pattern E_TZSCHE =
            Pattern.compile("\\b(\\d+|\\W+)?(\\w+_+)?(_+)?([bcdfghjklmnpqrstvwxz]+)?(\\d+)?t+z[s]che",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern E_JUAR =
            Pattern.compile("(juar)([a-z']{9,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern E_URL =
            Pattern.compile("(http://|ftp://)([a-z]+)(\\W){1,3}([a-z]+)(/*\\W){1,3}([a-z]){1}",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern E_ARITH_A =
            Pattern.compile("(\\d+)([-+*^/])(\\d+)(\\.)(\\d+)(\\.)(0{2,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern E_ARITH_B =
            Pattern.compile("(\\d+)([-+*^/])(\\d+)(\\.)(\\d+)(\\.)(0\\W)", Pattern.CASE_INSENSITIVE);
    private static final Pattern E_ARITH_C =
            Pattern.compile("(\\d+)([-+*^/]+)(\\d+)([-+*^/]+)([,.+])(0{2,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern E_ARITH_D =
            Pattern.compile("(\\d+)(\\.+)(\\d+)(\\.+)(0{2,})(\\.\\d*)\\s*\\.*([-+*^/])",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern E_ARITH_E =
            Pattern.compile("(\\d+)\\s*([-+*^/])\\s*(\\d+)(,)(00\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern E_ARITH_F =
            Pattern.compile("(\\d+)\\s*([-+*^/])\\s*(\\d+)(,)(0{4,})", Pattern.CASE_INSENSITIVE);
// Combined comma pattern: matches 3-6 comma groups in one pass.
// The (?=\d{1,3},) lookahead requires at least one comma so bare
// numbers ("123") never match (all groups are optional otherwise,
// which would force a copy on every 1-3 digit number).
    private static final Pattern E_COMMA_COMBINED =
            Pattern.compile("\\b(?=\\d{1,3},)(\\d{1,3})(?:,(000))?(?:,(\\d{1,3}))?(?:,(\\d{1,3}))?(?:,(\\d{1,3}))?\\b");
    private static final String E_COMMA_REPLACEMENT = "$1$2$3$4$5";

    public static String englishIbmFixes(String text) {
        if (!ENABLED || text == null || text.isEmpty()) return text == null ? "" : text;
        // Combine some patterns for efficiency - use a single pass where possible
        text = rep(E_MC, text, "$1$2");
        text = rep(E_CAESUR, text, "seizur");
        text = rep(E_H_APOS, text, "$1h $2e");
        text = rep(E_HHS, text, "$1 $2$3");
        text = rep(E_HHS_IRON, text, "$1 $2$3");
        text = rep(E_APOS_HHS_A, text, "$1 $2$3");
        text = rep(E_APOS_HHS_B, text, "$1 $2$3");
        text = rep(E_TIME_ORD, text, "$1 $2");
        text = rep(E_CONS_APOS, text, "$1 $2 $3");
        text = rep(E_YOURE, text, "$1 $2 $3");
        text = rep(E_COSP, text, "$1kosp");
        text = rep(E_TZSCHE, text, "$1 $2 $3 $4 $5 tz sche");
        text = rep(E_JUAR, text, "$1 $2");
        text = rep(E_URL, text, "$1$2$3$4 $5$6");
        text = rep(E_ARITH_A, text, "$1$2$3$4$5$6 $7");
        text = rep(E_ARITH_B, text, "$1$2$3$4 $5$6$7");
        text = rep(E_ARITH_C, text, "$1$2$3$4$5 $6");
        text = rep(E_ARITH_D, text, "$1$2$3$4 $5$6$7");
        text = rep(E_ARITH_E, text, "$1$2$3$4 $5");
        text = rep(E_ARITH_F, text, "$1$2$3$4 $5");
        // Use combined comma pattern for single-pass replacement
        text = rep(E_COMMA_COMBINED, text, E_COMMA_REPLACEMENT);
        return text;
    }

    // ------------------------------------------------------------------
    // german_ibm_fixes plus the general-en anticrash (NVDA-IBMTTS-Driver
    // #160, also ported by the revived fork's 4945586 - re-derived here
    // from the driver, not from the fork).
    // ------------------------------------------------------------------
    private static final Pattern DE_DANE =
            Pattern.compile("dane-ben", Pattern.CASE_INSENSITIVE);
    private static final Pattern DE_DAGE =
            Pattern.compile("dage-gen", Pattern.CASE_INSENSITIVE);
    private static final Pattern DE_GENERAL_EN =
            Pattern.compile("(audio|general|macro|video)(-)(en[a-z]+)", Pattern.CASE_INSENSITIVE);

    public static String germanIbmFixes(String text) {
        if (!ENABLED || text == null || text.isEmpty()) return text == null ? "" : text;
        text = rep(DE_DANE, text, "dane `0 ben");
        text = rep(DE_DAGE, text, "dage `0 gen");
        text = rep(DE_GENERAL_EN, text, "$1 `0 $3");
        return text;
    }

    // ------------------------------------------------------------------
    // spanish_ibm_fixes + spanish_ibm_anticrash: ViaVoice's Spanish time
    // parser crashes on minutes 20-59 with dot separators; long ordinals
    // ending in the feminine marker need splitting.
    // ------------------------------------------------------------------
    private static final Pattern ES_TIME =
            Pattern.compile("([0-2]?[0-4])\\.([2-5][0-9])\\.([0-5][0-9])");
    private static final Pattern ES_GROUP =
            Pattern.compile("(\\d+) (\\d{3})");
    private static final Pattern ES_ORD_SHORT =
            Pattern.compile("\\b(0{1,12})(\u00AA)");
    private static final Pattern ES_ORD_LONG =
            Pattern.compile("(\\d{12,}[123679])(\u00AA)");

    public static String spanishIbmFixes(String text) {
        if (!ENABLED || text == null || text.isEmpty()) return text == null ? "" : text;
        text = rep(ES_TIME, text, "$1:$2:$3");
        text = rep(ES_GROUP, text, "$1  $2");
        text = rep(ES_ORD_SHORT, text, "$1 $2");
        text = rep(ES_ORD_LONG, text, "$1 $2");
        return text;
    }

    // ------------------------------------------------------------------
    // portuguese_ibm_fixes: HH:00:SS with a zero minute crashes.
    // ------------------------------------------------------------------
    private static final Pattern PT_TIME =
            Pattern.compile("(\\d{1,2}):(00):(\\d{1,2})");

    public static String portugueseIbmFixes(String text) {
        if (!ENABLED || text == null || text.isEmpty()) return text == null ? "" : text;
        return rep(PT_TIME, text, "$1:$2 $3");
    }

    // ------------------------------------------------------------------
    // french_ibm_fixes (currency spacing) plus the attested "quil"
    // anticrash and n-degree expansion from french_fixes. Deliberately NOT
    // ported: f+digit respacing, roman+e de-joining (EqText owns Romans),
    // paren/y pronunciation tweaks - opinionated, not crashes.
    // ------------------------------------------------------------------
    private static final Pattern FR_CURR_A =
            Pattern.compile("([$€£])\\s*(\\d+)\\s(000)");
    private static final Pattern FR_CURR_B =
            Pattern.compile("(\\d+)\\s(000)\\s*([$€£])");
    private static final Pattern FR_QUIL_ANQ =
            Pattern.compile("(?<=anq)uil(?=l)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FR_QUIL =
            Pattern.compile("quil(?=\\W)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FR_DEGREE =
            Pattern.compile("\\bn\u00B0", Pattern.CASE_INSENSITIVE);

    public static String frenchIbmFixes(String text) {
        if (!ENABLED || text == null || text.isEmpty()) return text == null ? "" : text;
        text = rep(FR_CURR_A, text, "$1$2$3");
        text = rep(FR_CURR_B, text, "$1$2$3");
        text = rep(FR_QUIL_ANQ, text, "i");
        text = rep(FR_QUIL, text, "kil");
        text = rep(FR_DEGREE, text, "num\u00E9ro");
        return text;
    }

    // ------------------------------------------------------------------
    // Family dispatch. Masks are the engine language word's family half;
    // values mirror com.eloquick.tts.Eci's LOCALES table without depending
    // on it, so this file stays dependency-free and unit-testable.
    // ------------------------------------------------------------------
    private static final int FAM_ENG = 0x00010000;
    private static final int FAM_SPA = 0x00020000;
    private static final int FAM_FRA = 0x00030000;
    private static final int FAM_DEU = 0x00040000;
    private static final int FAM_CMN = 0x00060000;
    private static final int FAM_POR = 0x00070000;
    private static final int FAM_KOR = 0x000A0000;
    private static final int FAM_YUE = 0x000B0000;

    /**
     * Full IBM-path pipeline for an engine language word: rstrip, optional
     * backquote strip, global fixes, then the one matching family table.
     */
    public static String apply(String text, int languageWord) {
        if (!ENABLED) return text == null ? "" : text;
        if (text == null) return "";
        text = rep(RSTRIP, text, "");
        if (!KEEP_ANNOTATIONS) text = text.replace('`', ' ');
        text = globalFixes(text);
        int family = languageWord & 0xFFFF0000;
        if (family == FAM_ENG || family == FAM_CMN || family == FAM_KOR || family == FAM_YUE) {
            text = englishIbmFixes(text);
        } else if (family == FAM_SPA) {
            text = spanishIbmFixes(text);
        } else if (family == FAM_FRA) {
            text = frenchIbmFixes(text);
        } else if (family == FAM_DEU) {
            text = germanIbmFixes(text);
        } else if (family == FAM_POR) {
            text = portugueseIbmFixes(text);
        }
        return text;
    }

    /** Same pipeline keyed by ISO-639-3 language code for callers that only
     * have a locale string (eng, spa, fra, deu, por, cmn, kor, yue). Unknown
     * codes get the global table only. */
    public static String apply(String text, String iso3) {
        if (iso3 == null) return apply(text, 0);
        String code = iso3.trim().toLowerCase(java.util.Locale.US);
        int family;
        switch (code) {
            case "eng": case "cmn": case "kor": case "yue": family = FAM_ENG; break;
            case "spa": family = FAM_SPA; break;
            case "fra": family = FAM_FRA; break;
            case "deu": family = FAM_DEU; break;
            case "por": family = FAM_POR; break;
            default: family = 0; break;
        }
        return apply(text, family);
    }
}

// ----------------------------------------------------------------------
// Companion notes (not code): dictionary + toggle gaps found in the same
// survey, for the next implementation step. Sourced from the driver's
// _ibmeci.py and the IBMTTSDictionaries repo contract - no revived code.
//
// 1. Dictionary volumes: eciNewDict/eciLoadDict take vols 0/1/2 =
//    main/root/abbr (Eci.java already names DICT_MAIN/ROOT/ABBREVIATION the
//    same way). IBMTTSDictionaries ships {ENU,DEU}{main,root,abbr}.dic as
//    TAB-separated key/translation lines in cp1252 (ANSI) - MUST be saved
//    as cp1252, not UTF-8, per that repo's README. JNI has
//    nativeStreamDictLoadFile; verify it maps vol + encoding accordingly.
// 2. ABRDICT toggle: the driver flips abbreviation-dict use via
//    `eciDictionary 0/1` (PARAM_DICTIONARY=3 in Eci.java). No JNI method
//    exposes this yet - add nativeStreamSetAbbreviations-style coverage or
//    confirm the existing abbreviations toggle reaches eciDictionary.
// 3. tgspeechbox-ref portable ideas (not yet in EqText): clause splitter
//    with digit-guard (3. Mai), script-span splitting for mixed-script
//    input, whitespace/ellipsis normalisation order.
// 4. espeak-ng fork portable ideas (partly in EqText already): Indian digit
//    grouping (1,00,000), OTP-gated digit splitting, danda spacing.
// ----------------------------------------------------------------------
