package com.eloquick.tts;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** The numbers the published ECI interface takes, and the tables turning
 * them into something Android understands.
 *
 * Locale/family numbering, preset names, voice scales and the sample-rate
 * order follow trypsynth/evvdroid's Eci (MIT, Quin Gillespie), itself read
 * off IBM's interface and openevv's transcription: a language word is the
 * family in the top half, the code set in the third byte, the dialect in
 * the bottom one. Kept in this fork because the TTS service answers
 * Android in locales, while the engine answers in families. */
public final class Eci {
    private Eci() {}

    public static final int PARAM_SYNTH_MODE = 0;
    public static final int PARAM_INPUT_TYPE = 1;
    public static final int PARAM_TEXT_MODE = 2;
    public static final int PARAM_DICTIONARY = 3;
    public static final int PARAM_SAMPLE_RATE = 5;
    public static final int PARAM_REAL_WORLD_UNITS = 8;
    public static final int PARAM_LANGUAGE_DIALECT = 9;
    public static final int PARAM_NUMBER_MODE = 10;

    public static final int VOICE_GENDER = 0;
    public static final int VOICE_HEAD_SIZE = 1;
    public static final int VOICE_PITCH_BASELINE = 2;
    public static final int VOICE_PITCH_FLUCTUATION = 3;
    public static final int VOICE_ROUGHNESS = 4;
    public static final int VOICE_BREATHINESS = 5;
    public static final int VOICE_SPEED = 6;
    public static final int VOICE_VOLUME = 7;

    /** Voice 0 is spoken in; 1-8 are read-only presets; 9-16 are scratch. */
    public static final int VOICE_CURRENT = 0;
    public static final int FIRST_PRESET = 1;
    public static final int LAST_PRESET = 8;
    public static final int SCRATCH_VOICE = 9;

    public static final int NUM_VOICE_PARAMS = 8;

    /** Bit in the language word saying the text is UTF-16, not bytes. */
    public static final int UNICODE_CODE_SET = 0x800;

    public static final int DICT_MAIN = 0;
    public static final int DICT_ROOT = 1;
    public static final int DICT_ABBREVIATION = 2;

    /** Six presets ship at 50, Glen and Sandy at 70 (IBM's data, not a
     * fault) -- so speed is normalised to DEFAULT_SPEED on voice apply,
     * or changing voice changes pace unasked. */
    public static final int DEFAULT_SPEED = 50;
    public static final int SPEED_MAX = 250;
    public static final int PERCENT_MAX = 100;

    public static int voiceRangeTop(int param) {
        if (param == VOICE_GENDER) return 1;
        if (param == VOICE_SPEED) return SPEED_MAX;
        return PERCENT_MAX;
    }

    public static int clampVoice(int param, int value) {
        int top = voiceRangeTop(param);
        return Math.max(0, Math.min(top, value));
    }

    /** Engine sample-rate index order: IBM's 0-3, then openevv's 4-6. */
    public static final int[] SAMPLE_RATES = {8000, 11025, 22050, 16000, 32000, 44100, 48000};

    public static int sampleRateHz(int index) {
        if (index < 0 || index >= SAMPLE_RATES.length) return 11025;
        return SAMPLE_RATES[index];
    }

    /** The names the eight presets have gone by since Eloquence shipped. */
    public static final String[] PRESET_NAMES = {
        "Reed", "Shelley", "Bobby", "Rocko", "Glen", "Sandy", "Grandma", "Grandpa"
    };

    public static int family(int language) {
        return language & 0xFFFF0000;
    }

    public static int baseLanguage(int language) {
        return language & ~UNICODE_CODE_SET;
    }

    /** Family -> (ISO-3 language, ISO-3 country, variant). */
    private static final Map<Integer, String[]> LOCALES;
    static {
        Map<Integer, String[]> m = new HashMap<>();
        m.put(0x00010000, new String[]{"eng", "USA", ""});
        m.put(0x00010001, new String[]{"eng", "GBR", ""});
        m.put(0x00020000, new String[]{"spa", "ESP", ""});
        m.put(0x00020001, new String[]{"spa", "MEX", ""});
        m.put(0x00030000, new String[]{"fra", "FRA", ""});
        m.put(0x00030001, new String[]{"fra", "CAN", ""});
        m.put(0x00040000, new String[]{"deu", "DEU", ""});
        m.put(0x00050000, new String[]{"ita", "ITA", ""});
        m.put(0x00060000, new String[]{"cmn", "CHN", ""});
        m.put(0x00060001, new String[]{"cmn", "TWN", ""});
        m.put(0x00070000, new String[]{"por", "BRA", ""});
        m.put(0x00080000, new String[]{"jpn", "JPN", ""});
        m.put(0x00090000, new String[]{"fin", "FIN", ""});
        m.put(0x000a0000, new String[]{"kor", "KOR", ""});
        m.put(0x000b0000, new String[]{"yue", "CHN", ""});
        m.put(0x000b0001, new String[]{"yue", "HKG", ""});
        m.put(0x000c0000, new String[]{"nld", "NLD", ""});
        m.put(0x000d0000, new String[]{"nor", "NOR", ""});
        m.put(0x000e0000, new String[]{"swe", "SWE", ""});
        m.put(0x000f0000, new String[]{"dan", "DNK", ""});
        m.put(0x00110000, new String[]{"pol", "POL", ""});
        LOCALES = Collections.unmodifiableMap(m);
    }

    /** ISO-3 locale for an engine language word, or null. */
    public static String[] localeOf(int language) {
        return LOCALES.get(baseLanguage(language));
    }
}
