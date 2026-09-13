package com.eloquick.debug;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import android.util.Log;

import com.eloquick.tts.Eci;
import com.eloquick.tts.EloQuickEngine;
import com.eloquick.tts.SpeechRate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The engine Android talks to: EloQuick as a system TTS service.
 *
 * Protocol debts, all measured the hard way by trypsynth/evvdroid (MIT)
 * and reimplemented here for this bridge:
 * - the framework is told an utterance began only once samples exist
 *   (early start + silence reads as a dry queue and kills TalkBack items);
 * - this engine synthesizes ~100x realtime, so handover is paced to at
 *   most LEAD_MS ahead or rapid swipes queue unheard items that all die
 *   on the next silence;
 * - stops go through the streaming abort flag, never eciStop;
 * - rate/pitch travel as `vs/`vb annotations in front of the words,
 *   because the engine refuses parameter writes while speaking.
 */
public class EloQuickTtsService extends TextToSpeechService {
    private static final String TAG = "EQTTS";

    private static final int MIN_CHUNK = 1024;
    private static final int MAX_CHUNK = 8192;
    private static final long LEAD_MS = 300;
    private static final long SLICE_MS = 50;
    private static final int DEFAULT_RATE_HZ = 22050;

    private final Object guard = new Object();
    private volatile boolean stopped = false;

    private long stream = 0;
    private int streamLang = 0;
    private int streamPreset = -1;
    private int streamSpeed = -1;
    private boolean streamHetero = false;
    private int streamRateHz = 0;
    private int streamDictRev = -1;
    private int baseSpeed = Eci.DEFAULT_SPEED;
    private int basePitch = 50;
    private int rateHz = DEFAULT_RATE_HZ;

    private int[] languages() {
        try {
            int[] ids = EloQuickEngine.nativeGetLanguages();
            return ids != null ? ids : new int[0];
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "native lib missing", e);
            return new int[0];
        }
    }

    // ---- languages ------------------------------------------------------

    private int[] matchLanguage(String lang, String country) {
        // returns {engineId, availability} or null
        if (lang == null || lang.isEmpty()) return null;
        String want = iso3(lang);
        String wantCountry = (country == null || country.isEmpty()) ? null : iso3Country(country);
        Integer byLanguage = null;
        for (int candidate : languages()) {
            String[] loc = Eci.localeOf(candidate);
            if (loc == null || !loc[0].equalsIgnoreCase(want)) continue;
            if (wantCountry != null && loc[1].equalsIgnoreCase(wantCountry)) {
                return new int[]{candidate, TextToSpeech.LANG_COUNTRY_AVAILABLE};
            }
            if (byLanguage == null) byLanguage = candidate;
        }
        if (byLanguage == null) return null;
        return new int[]{byLanguage, TextToSpeech.LANG_AVAILABLE};
    }

    private static String iso3(String lang) {
        try {
            String o3 = new Locale(lang).getISO3Language();
            if (o3 != null && !o3.isEmpty()) return o3;
        } catch (Exception ignored) {
        }
        return lang;
    }

    private static String iso3Country(String country) {
        try {
            String o3 = new Locale("", country).getISO3Country();
            if (o3 != null && !o3.isEmpty()) return o3;
        } catch (Exception ignored) {
        }
        return country;
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        int[] found = matchLanguage(lang, country);
        return found == null ? TextToSpeech.LANG_NOT_SUPPORTED : found[1];
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        int[] found = matchLanguage(lang, country);
        if (found == null) return TextToSpeech.LANG_NOT_SUPPORTED;
        int preset = Math.max(0, Math.min(Eci.PRESET_NAMES.length - 1, EqPrefs.preset(this)));
        return ensureSession(found[0], preset) ? found[1] : TextToSpeech.LANG_NOT_SUPPORTED;
    }

    @Override
    protected String[] onGetLanguage() {
        synchronized (guard) {
            String[] loc = stream != 0 ? Eci.localeOf(streamLang) : null;
            if (loc == null) {
                int[] all = languages();
                loc = all.length > 0 ? Eci.localeOf(all[0]) : null;
            }
            if (loc == null) return new String[]{"eng", "USA", ""};
            return new String[]{loc[0], loc[1], loc[2]};
        }
    }

    // ---- voices ----------------------------------------------------------

    private static String voiceName(int language, int preset) {
        String[] loc = Eci.localeOf(language);
        if (loc == null) return "eq-" + preset;
        return loc[0] + "-" + loc[1] + "-" + Eci.PRESET_NAMES[preset];
    }

    @Override
    public List<Voice> onGetVoices() {
        List<Voice> out = new ArrayList<>();
        for (int language : languages()) {
            String[] loc = Eci.localeOf(language);
            if (loc == null) continue;
            Locale locale = new Locale(loc[0], loc[1]);
            for (int p = 0; p < Eci.PRESET_NAMES.length; p++) {
                out.add(new Voice(voiceName(language, p), locale,
                        Voice.QUALITY_NORMAL, Voice.LATENCY_VERY_LOW, false,
                        Collections.<String>emptySet()));
            }
        }
        return out;
    }

    private int[] findVoice(String name) {
        // returns {engineId, preset} or null
        if (name == null || name.isEmpty()) return null;
        for (int language : languages()) {
            for (int p = 0; p < Eci.PRESET_NAMES.length; p++) {
                if (voiceName(language, p).equals(name)) return new int[]{language, p};
            }
        }
        return null;
    }

    @Override
    public int onIsValidVoiceName(String name) {
        return findVoice(name) != null ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }

    @Override
    public int onLoadVoice(String name) {
        int[] found = findVoice(name);
        if (found == null) return TextToSpeech.ERROR;
        return ensureSession(found[0], found[1]) ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }

    @Override
    public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        int[] found = matchLanguage(lang, country);
        if (found == null) return null;
        int preset = Math.max(0, Math.min(Eci.PRESET_NAMES.length - 1, EqPrefs.preset(this)));
        return voiceName(found[0], preset);
    }

    // ---- session ----------------------------------------------------------

    /** Voice preset applied the engine-safe way: presets are read-only, so a
     *  preset is copied to scratch voice 9, shaped there (speed normalised
     *  to DEFAULT_SPEED -- Glen/Sandy ship faster), and copied to voice 0. */
    private boolean applyPresetLocked(long stream, int presetIndex, int speed) {
        int preset = Math.max(Eci.FIRST_PRESET,
                Math.min(Eci.LAST_PRESET, presetIndex + Eci.FIRST_PRESET));
        if (EloQuickEngine.nativeStreamCopyVoice(stream, preset, Eci.SCRATCH_VOICE) == 0)
            return false;
        int wantSpeed = Eci.clampVoice(Eci.VOICE_SPEED, speed);
        if (EloQuickEngine.nativeStreamSetVoiceParam(stream, Eci.SCRATCH_VOICE,
                Eci.VOICE_SPEED, wantSpeed) < 0) return false;
        if (EloQuickEngine.nativeStreamCopyVoice(stream, Eci.SCRATCH_VOICE,
                Eci.VOICE_CURRENT) == 0) return false;
        baseSpeed = EloQuickEngine.nativeStreamGetVoiceParam(stream, Eci.VOICE_CURRENT,
                Eci.VOICE_SPEED);
        basePitch = EloQuickEngine.nativeStreamGetVoiceParam(stream, Eci.VOICE_CURRENT,
                Eci.VOICE_PITCH_BASELINE);
        return true;
    }

    private boolean ensureSession(int language, int preset) {
        int speed = EqPrefs.speed(this);
        boolean hetero = EqPrefs.hetero(this);
        int wantHz = EqPrefs.rateHz(this);
        int dictRev = EqPrefs.dictRev(this);
        synchronized (guard) {
            if (stream != 0 && streamLang == language && streamPreset == preset
                    && streamSpeed == speed && streamHetero == hetero
                    && streamRateHz == wantHz && streamDictRev == dictRev) return true;
            if (stream != 0) {
                EloQuickEngine.nativeStreamDestroy(stream);
                stream = 0;
            }
            // Hetero is a creation-time property: hold the default across
            // creation, then put it back for everyone else.
            try {
                EloQuickEngine.nativeSetHeteroDefault(hetero);
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "native lib missing", e);
                return false;
            }
            long s = EloQuickEngine.nativeStreamCreate(language);
            try {
                EloQuickEngine.nativeSetHeteroDefault(false);
            } catch (UnsatisfiedLinkError ignored) {
            }
            if (s == 0) return false;
            int hz = EloQuickEngine.nativeStreamSetSampleRateHz(s, wantHz);
            rateHz = hz > 0 ? hz : 11025;
            if (!applyPresetLocked(s, preset, speed)) {
                EloQuickEngine.nativeStreamDestroy(s);
                return false;
            }
            EloQuickEngine.nativeStreamDictLoad(s, 0,
                    EqDictionary.file(this).getAbsolutePath());
            stream = s;
            streamLang = language;
            streamPreset = preset;
            streamSpeed = speed;
            streamHetero = hetero;
            streamRateHz = wantHz;
            streamDictRev = dictRev;
            Log.i(TAG, "session lang=0x" + Integer.toHexString(language)
                    + " preset=" + preset + " speed=" + speed + " hetero=" + hetero
                    + " rateHz=" + rateHz + " dictRev=" + dictRev);
            return true;
        }
    }

    @Override
    public void onDestroy() {
        synchronized (guard) {
            if (stream != 0) {
                EloQuickEngine.nativeStreamDestroy(stream);
                stream = 0;
            }
        }
        super.onDestroy();
    }

    // ---- speaking ----------------------------------------------------------

    @Override
    protected void onStop() {
        stopped = true;
        synchronized (guard) {
            if (stream != 0) EloQuickEngine.nativeStreamStop(stream);
        }
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        if (request == null || callback == null) return;
        stopped = false;
        int[] found = matchLanguage(request.getLanguage(), request.getCountry());
        if (found == null) {
            callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET);
            return;
        }
        // The voice name decides the language; the rate/pitch come from the
        // request (a reader holding a Voice across a settings change must not
        // pin the old voice's shaping).
        int[] wanted = findVoice(request.getVoiceName());
        int language = wanted != null ? wanted[0] : found[0];
        int preset = wanted != null ? wanted[1] : EqPrefs.preset(this);
        if (!ensureSession(language, preset)) {
            callback.error(TextToSpeech.ERROR_SERVICE);
            return;
        }
        long s;
        int hz;
        synchronized (guard) {
            s = stream;
            hz = rateHz;
        }
        int speed = SpeechRate.speedForPercent(baseSpeed, request.getSpeechRate());
        int pitch = (int) Math.max(0, Math.min(100, (long) basePitch * request.getPitch() / 100));
        String raw = request.getCharSequenceText() == null ? ""
                : request.getCharSequenceText().toString();
        if (EqPrefs.wednesdayGuard(this)) raw = EqText.wednesdayGuard(raw);
        String text = "`vs" + Eci.clampVoice(Eci.VOICE_SPEED, speed)
                + " `vb" + Eci.clampVoice(Eci.VOICE_PITCH_BASELINE, pitch)
                + " `pp0 " + raw;
        if (text.trim().isEmpty()) {
            callback.start(hz, AudioFormat.ENCODING_PCM_16BIT, 1);
            callback.done();
            return;
        }
        if (!EloQuickEngine.nativeStreamSpeak(s, text)) {
            callback.error(TextToSpeech.ERROR_SYNTHESIS);
            return;
        }
        Opening opening = new Opening(callback, hz);
        Pace pace = new Pace((long) hz * 2);
        int size = Math.max(MIN_CHUNK, Math.min(MAX_CHUNK, callback.getMaxBufferSize()));
        byte[] buffer = new byte[size];
        while (!stopped) {
            int n = EloQuickEngine.nativeStreamRead(s, buffer, size);
            if (n <= 0) break;
            if (!opening.open()) {
                EloQuickEngine.nativeStreamStop(s);
                return;
            }
            if (callback.audioAvailable(buffer, 0, n) != TextToSpeech.SUCCESS) {
                EloQuickEngine.nativeStreamStop(s);
                return;
            }
            pace.handed(n);
            if (!paceHold(pace)) return;
        }
        if (opening.began) callback.done();
    }

    /** How far handed audio runs ahead of wall clock, in ms. */
    private static final class Pace {
        private final long bytesPerSecond;
        private final long from = System.nanoTime();
        private long bytes = 0;

        Pace(long bytesPerSecond) {
            this.bytesPerSecond = bytesPerSecond;
        }

        void handed(int more) {
            bytes += more;
        }

        long aheadMs() {
            if (bytesPerSecond <= 0) return 0;
            return bytes * 1000 / bytesPerSecond - (System.nanoTime() - from) / 1000000;
        }
    }

    private boolean paceHold(Pace pace) {
        while (!stopped) {
            long ahead = pace.aheadMs() - LEAD_MS;
            if (ahead <= 0) return true;
            try {
                Thread.sleep(Math.min(ahead, SLICE_MS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Framework start owed exactly once, and only once samples exist. */
    private static final class Opening {
        private final SynthesisCallback callback;
        private final int rateHz;
        boolean began = false;

        Opening(SynthesisCallback callback, int rateHz) {
            this.callback = callback;
            this.rateHz = rateHz;
        }

        boolean open() {
            if (began) return true;
            began = callback.start(rateHz, AudioFormat.ENCODING_PCM_16BIT, 1)
                    == TextToSpeech.SUCCESS;
            return began;
        }
    }
}
