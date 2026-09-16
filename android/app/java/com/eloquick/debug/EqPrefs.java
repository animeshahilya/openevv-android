package com.eloquick.debug;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted engine settings, in device-protected storage so the TTS
 * service (directBootAware) can read them before first unlock.
 *
 * Shape of the settings screen in eloquence-revived, storage here is
 * deliberately plain SharedPreferences: the service process reads them
 * directly, no cross-process handoff (which is what overflows Binder when
 * a dictionary rides along -- eloquence-revived's own fix history). */
public final class EqPrefs {
    private static final String NAME = "eloquick_prefs";

    public static final String KEY_PRESET = "voice_preset";
    public static final String KEY_SPEED = "voice_speed";
    public static final String KEY_HETERO = "heteronym_filter";
    public static final String KEY_RATE_HZ = "sample_rate_hz";
    public static final String KEY_WEDNESDAY = "wednesday_guard";
    public static final String KEY_DICT_REV = "dict_rev";

    // Post-synthesis tone shaping (two-band presence/warmth + leveler +
    // clip guard, one instance per utterance). Off by default: it changes
    // the voice's timbre, and must be an explicit choice.
    public static final String KEY_OPTIMIZER = "audio_optimizer";
    public static final String KEY_OPTIMIZER_PROFILE = "audio_optimizer_profile";

    // Pre-synthesis text pipeline (all Java-side, before annotations).
    public static final String KEY_UNICODE_NORM = "unicode_normalize";
    public static final String KEY_EMOJI = "emoji_mode";
    public static final String KEY_DIGIT_GROUP = "digit_grouping";
    public static final String KEY_DIGIT_THRESHOLD = "digit_group_threshold";
    public static final String KEY_CURRENCY = "currency_expand";
    public static final String KEY_TIME_DATE = "time_date_expand";
    public static final String KEY_READING_MODE = "reading_mode";
    public static final String KEY_PROG_SYMBOLS = "programming_symbols";
    public static final String KEY_PUNCT_PRESET = "punctuation_preset";
    public static final String KEY_PUNCT_CUSTOM = "punctuation_custom";
    public static final String KEY_USER_RULES = "user_rules";

    // Locks ignoring caller-app rate/pitch (TalkBack sliders etc.).
    public static final String KEY_FORCE_RATE = "force_rate";
    public static final String KEY_FORCE_PITCH = "force_pitch";

    /** Emoji handling. */
    public static final String EMOJI_ANNOUNCE = "announce";
    public static final String EMOJI_IGNORE = "ignore";

    /** Digit grouping modes for long numbers. */
    public static final String DIGIT_OFF = "off";
    public static final String DIGIT_SINGLE = "single";
    public static final String DIGIT_DOUBLE = "double";
    public static final String DIGIT_TRIPLE = "triple";

    /** Reading modes (single-select). */
    public static final String READING_NORMAL = "normal";
    public static final String READING_SPELLING = "spelling";
    public static final String READING_PHONETIC = "phonetic";
    public static final String READING_CODE = "code";

    /** Punctuation presets. */
    public static final String PUNCT_NONE = "none";
    public static final String PUNCT_SOME = "some";
    public static final String PUNCT_MOST = "most";
    public static final String PUNCT_ALL = "all";
    public static final String PUNCT_CUSTOM = "custom";

    /** "Some": the punctuation needed to follow a sentence's shape. */
    public static final String PUNCT_CHARS_SOME = ".,!?;:'\"-";
    /** "Most": Some plus symbols common in ordinary text. */
    public static final String PUNCT_CHARS_MOST = PUNCT_CHARS_SOME + "()[]{}/@#$%&*+=<>_~^|\\";

    private EqPrefs() {}

    public static SharedPreferences of(Context c) {
        Context storage = c;
        try {
            // Before first unlock only device-protected storage exists.
            storage = c.createDeviceProtectedStorageContext();
        } catch (Exception ignored) {
        }
        return storage.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static int preset(Context c) {
        return of(c).getInt(KEY_PRESET, 0);
    }

    public static int speed(Context c) {
        return of(c).getInt(KEY_SPEED, 50);
    }

    public static boolean hetero(Context c) {
        return of(c).getBoolean(KEY_HETERO, false);
    }

    public static int rateHz(Context c) {
        return of(c).getInt(KEY_RATE_HZ, 22050);
    }

    public static boolean wednesdayGuard(Context c) {
        return of(c).getBoolean(KEY_WEDNESDAY, true);
    }

    public static boolean optimizer(Context c) {
        return of(c).getBoolean(KEY_OPTIMIZER, false);
    }

    public static String optimizerProfile(Context c) {
        return of(c).getString(KEY_OPTIMIZER_PROFILE, "balanced");
    }

    public static boolean unicodeNorm(Context c) {
        return of(c).getBoolean(KEY_UNICODE_NORM, true);
    }

    public static String emojiMode(Context c) {
        return of(c).getString(KEY_EMOJI, EMOJI_ANNOUNCE);
    }

    public static String digitGrouping(Context c) {
        return of(c).getString(KEY_DIGIT_GROUP, DIGIT_OFF);
    }

    public static int digitThreshold(Context c) {
        try {
            int v = Integer.parseInt(of(c).getString(KEY_DIGIT_THRESHOLD, "7"));
            return Math.max(4, Math.min(12, v));
        } catch (NumberFormatException e) {
            return 7;
        }
    }

    public static boolean currency(Context c) {
        return of(c).getBoolean(KEY_CURRENCY, false);
    }

    public static boolean timeDate(Context c) {
        return of(c).getBoolean(KEY_TIME_DATE, false);
    }

    public static String readingMode(Context c) {
        return of(c).getString(KEY_READING_MODE, READING_NORMAL);
    }

    public static boolean progSymbols(Context c) {
        return of(c).getBoolean(KEY_PROG_SYMBOLS, false);
    }

    public static String punctPreset(Context c) {
        return of(c).getString(KEY_PUNCT_PRESET, PUNCT_NONE);
    }

    public static String punctCustom(Context c) {
        return of(c).getString(KEY_PUNCT_CUSTOM, "");
    }

    /** Characters to expand for the current preset ("" = none). */
    public static String punctChars(Context c) {
        String preset = punctPreset(c);
        if (PUNCT_SOME.equals(preset)) return PUNCT_CHARS_SOME;
        if (PUNCT_MOST.equals(preset)) return PUNCT_CHARS_MOST;
        if (PUNCT_ALL.equals(preset)) return PUNCT_CHARS_MOST + "`";
        if (PUNCT_CUSTOM.equals(preset)) return punctCustom(c);
        return "";
    }

    public static boolean userRules(Context c) {
        return of(c).getBoolean(KEY_USER_RULES, true);
    }

    public static boolean forceRate(Context c) {
        return of(c).getBoolean(KEY_FORCE_RATE, false);
    }

    public static boolean forcePitch(Context c) {
        return of(c).getBoolean(KEY_FORCE_PITCH, false);
    }

    /** Bumped whenever the dictionary file changes; the service reloads
     *  when the revision it loaded differs. */
    public static int dictRev(Context c) {
        return of(c).getInt(KEY_DICT_REV, 0);
    }

    public static void setPreset(Context c, int preset) {
        of(c).edit().putInt(KEY_PRESET, preset).apply();
    }

    public static void setSpeed(Context c, int speed) {
        of(c).edit().putInt(KEY_SPEED, speed).apply();
    }

    public static void setHetero(Context c, boolean on) {
        of(c).edit().putBoolean(KEY_HETERO, on).apply();
    }

    public static void setRateHz(Context c, int hz) {
        of(c).edit().putInt(KEY_RATE_HZ, hz).apply();
    }

    public static void setWednesdayGuard(Context c, boolean on) {
        of(c).edit().putBoolean(KEY_WEDNESDAY, on).apply();
    }

    public static void setOptimizer(Context c, boolean on) {
        of(c).edit().putBoolean(KEY_OPTIMIZER, on).apply();
    }

    public static void setOptimizerProfile(Context c, String profile) {
        of(c).edit().putString(KEY_OPTIMIZER_PROFILE, profile).apply();
    }

    public static void setUnicodeNorm(Context c, boolean on) {
        of(c).edit().putBoolean(KEY_UNICODE_NORM, on).apply();
    }

    public static void setEmojiMode(Context c, String mode) {
        of(c).edit().putString(KEY_EMOJI, mode).apply();
    }

    public static void setDigitGrouping(Context c, String mode) {
        of(c).edit().putString(KEY_DIGIT_GROUP, mode).apply();
    }

    public static void setDigitThreshold(Context c, String value) {
        of(c).edit().putString(KEY_DIGIT_THRESHOLD, value).apply();
    }

    public static void setCurrency(Context c, boolean on) {
        of(c).edit().putBoolean(KEY_CURRENCY, on).apply();
    }

    public static void setTimeDate(Context c, boolean on) {
        of(c).edit().putBoolean(KEY_TIME_DATE, on).apply();
    }

    public static void setReadingMode(Context c, String mode) {
        of(c).edit().putString(KEY_READING_MODE, mode).apply();
    }

    public static void setProgSymbols(Context c, boolean on) {
        of(c).edit().putBoolean(KEY_PROG_SYMBOLS, on).apply();
    }

    public static void setPunctPreset(Context c, String preset) {
        of(c).edit().putString(KEY_PUNCT_PRESET, preset).apply();
    }

    public static void setPunctCustom(Context c, String chars) {
        of(c).edit().putString(KEY_PUNCT_CUSTOM, chars).apply();
    }

    public static void setUserRules(Context c, boolean on) {
        of(c).edit().putBoolean(KEY_USER_RULES, on).apply();
    }

    public static void setForceRate(Context c, boolean on) {
        of(c).edit().putBoolean(KEY_FORCE_RATE, on).apply();
    }

    public static void setForcePitch(Context c, boolean on) {
        of(c).edit().putBoolean(KEY_FORCE_PITCH, on).apply();
    }

    public static void bumpDictRev(Context c) {
        SharedPreferences p = of(c);
        p.edit().putInt(KEY_DICT_REV, p.getInt(KEY_DICT_REV, 0) + 1).apply();
    }
}
