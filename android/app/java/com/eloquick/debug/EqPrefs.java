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

    public static void bumpDictRev(Context c) {
        SharedPreferences p = of(c);
        p.edit().putInt(KEY_DICT_REV, p.getInt(KEY_DICT_REV, 0) + 1).apply();
    }
}
