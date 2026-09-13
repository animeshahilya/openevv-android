package com.eloquick.debug;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.eloquick.tts.EloQuickEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** EloQuick debug activity: manual speak UI plus an intent-driven self-test.
 *
 * Manual: pick language + voice, type text, Speak.
 *
 * Automation (adb):
 *   am start -n com.eloquick.debug/com.eloquick.debug.MainActivity \
 *     --es text "Hello" --es lang 0x10000 --ei voice 2
 *   am start ... --ez selftest true [--ez playaudi false]
 *   am start ... --ez fwtest true   (full framework loop through our service)
 *
 * Self-test logs EQTEST lines and a final EQTEST RESULT ok=N fail=M:
 * every listed language synths (and optionally plays), an unknown language
 * is refused, voice presets 1-8 each copy, plus streaming/dictionary/
 * heteronym/rate checks. Drive it with logcat:
 *   adb logcat -s EQTEST -e "EQTEST"   (after clearing: logcat -c)
 */
public class MainActivity extends Activity {
    private static final String TAG = "EQTEST";
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private int[] langIds = new int[0];
    private Spinner langSpinner;
    private Spinner voiceSpinner;
    private EditText textInput;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        langIds = safeGetLanguages();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        langSpinner = new Spinner(this);
        voiceSpinner = new Spinner(this);
        textInput = new EditText(this);
        Button speak = new Button(this);
        status = new TextView(this);

        langSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, langLabels()));
        List<String> voices = new ArrayList<>();
        for (int v = 1; v <= 8; v++) {
            voices.add("Voice " + v);
        }
        voiceSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, voices));
        textInput.setText("Hello from EloQuick on Android.");
        speak.setText("Speak");
        speak.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSpeakPressed();
            }
        });

        status.setText(statusLine("ready", langIds.length));

        root.addView(langSpinner);
        root.addView(voiceSpinner);
        root.addView(textInput);
        root.addView(speak);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(status);
        root.addView(scroll);
        setContentView(root);

        Bundle ex = getIntent() != null ? getIntent().getExtras() : null;
        handleExtras(ex);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleExtras(intent != null ? intent.getExtras() : null);
    }

    private void handleExtras(Bundle ex) {
        if (ex != null && ex.getBoolean("selftest", false)) {
            boolean play = ex.getBoolean("playaudio", true);
            runSelfTest(play);
        } else if (ex != null && ex.getBoolean("fwtest", false)) {
            runFrameworkTest();
        } else if (ex != null && ex.containsKey("text")) {
            String t = ex.getString("text");
            int lang = parseLang(ex.getString("lang"), langIds.length > 0 ? langIds[0] : 0);
            int voice = ex.getInt("voice", 1);
            if (t != null) {
                textInput.setText(t);
                selectLang(lang);
                voiceSpinner.setSelection(Math.max(0, Math.min(7, voice - 1)));
                onSpeakPressed();
            }
        }
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        super.onDestroy();
    }

    private int[] safeGetLanguages() {
        try {
            int[] ids = EloQuickEngine.nativeGetLanguages();
            return ids != null ? ids : new int[0];
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "native lib missing", e);
            return new int[0];
        }
    }

    private List<String> langLabels() {
        String[] names = {"English (US)", "German", "English (UK)",
                "Spanish (ES)", "Spanish (US)", "French (CA)", "French (FR)",
                "Italian", "Polish", "Japanese"};
        List<String> out = new ArrayList<>();
        for (int i = 0; i < langIds.length; i++) {
            String n = i < names.length ? names[i] : ("Language " + i);
            out.add(n + " [0x" + Integer.toHexString(langIds[i]) + "]");
        }
        if (out.isEmpty()) {
            out.add("(engine missing: no languages)");
        }
        return out;
    }

    private String statusLine(String what, int nlangs) {
        return "EloQuick debug: " + what + " | languages: " + nlangs;
    }

    private int selectedLang() {
        int i = langSpinner.getSelectedItemPosition();
        if (i >= 0 && i < langIds.length) {
            return langIds[i];
        }
        return 0;
    }

    private void selectLang(int id) {
        for (int i = 0; i < langIds.length; i++) {
            if (langIds[i] == id) {
                langSpinner.setSelection(i);
                return;
            }
        }
    }

    private static int parseLang(String s, int dflt) {
        if (s == null) {
            return dflt;
        }
        try {
            return (int) Long.decode(s).longValue();
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private void onSpeakPressed() {
        final int lang = selectedLang();
        final int voice = voiceSpinner.getSelectedItemPosition() + 1;
        final String text = textInput.getText().toString();
        setStatus("speaking lang=0x" + Integer.toHexString(lang)
                + " voice=" + voice + " ...");
        bg.execute(new Runnable() {
            @Override
            public void run() {
                final Tts.Result r = Tts.speak(lang, voice, text, true);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStatus(r.ok
                                ? ("done: " + r.samples + " samples, synth "
                                        + r.synthMs + "ms, play " + r.playMs + "ms")
                                : ("FAILED: " + r.error));
                    }
                });
                Log.i(TAG, "speak lang=0x" + Integer.toHexString(lang)
                        + " voice=" + voice + " ok=" + r.ok
                        + " samples=" + r.samples + " synthMs=" + r.synthMs
                        + " playMs=" + r.playMs
                        + (r.error != null ? " err=" + r.error : ""));
            }
        });
    }

    private void setStatus(String s) {
        status.setText(statusLine(s, langIds.length));
    }

    private void runSelfTest(final boolean play) {
        setStatus("self-test running (play=" + play + ") ...");
        bg.execute(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                int fail = 0;
                int[] ids = safeGetLanguages();
                Log.i(TAG, "selftest languages=" + ids.length + " play=" + play);
                for (int i = 0; i < ids.length; i++) {
                    String prompt = (i == ids.length - 1)
                            ? "Hello."  // last slot: ASCII byte path (jajp)
                            : "Hello world. This is EloQuick speaking.";
                    Tts.Result r = Tts.speak(ids[i], 1, prompt, play);
                    if (r.ok && r.samples > 0) {
                        ok++;
                    } else {
                        fail++;
                    }
                    Log.i(TAG, "selftest lang=0x" + Integer.toHexString(ids[i])
                            + " status=" + (r.ok ? "OK" : "FAIL")
                            + " samples=" + r.samples
                            + " synthMs=" + r.synthMs + " playMs=" + r.playMs
                            + (r.error != null ? " err=" + r.error : ""));
                }
                // Negative: unknown language must be refused (handle 0).
                long bad = 0;
                try {
                    bad = EloQuickEngine.nativeCreate(0xdead);
                    if (bad != 0) {
                        EloQuickEngine.nativeDestroy(bad);
                    }
                } catch (UnsatisfiedLinkError e) {
                    Log.e(TAG, "selftest native missing", e);
                }
                if (bad == 0) {
                    ok++;
                    Log.i(TAG, "selftest badlang status=OK (refused 0xdead)");
                } else {
                    fail++;
                    Log.i(TAG, "selftest badlang status=FAIL (accepted 0xdead)");
                }
                // Voices 1-8 copy onto the first language.
                if (ids.length > 0) {
                    for (int v = 1; v <= 8; v++) {
                        Tts.Result r = Tts.speak(ids[0], v, "Hello.", false);
                        if (r.ok && r.samples > 0) {
                            ok++;
                        } else {
                            fail++;
                        }
                        Log.i(TAG, "selftest voice=" + v
                                + " status=" + (r.ok ? "OK" : "FAIL")
                                + " samples=" + r.samples);
                    }
                }
                // Streaming: queue once, drain to the end, count bytes.
                if (ids.length > 0) {
                    long st = 0;
                    int total = 0;
                    int chunks = 0;
                    boolean streamOk = false;
                    try {
                        st = EloQuickEngine.nativeStreamCreate(ids[0]);
                        if (st != 0 && EloQuickEngine.nativeStreamSpeak(st,
                                "Hello world. This is EloQuick speaking.")) {
                            byte[] buf = new byte[8192];
                            for (;;) {
                                int n = EloQuickEngine.nativeStreamRead(st, buf, buf.length);
                                if (n < 0) break;
                                if (n == 0) {
                                    streamOk = total > 0;
                                    break;
                                }
                                total += n;
                                chunks++;
                            }
                        }
                    } catch (UnsatisfiedLinkError e) {
                        Log.e(TAG, "selftest stream missing", e);
                    } finally {
                        if (st != 0) {
                            try {
                                EloQuickEngine.nativeStreamDestroy(st);
                            } catch (UnsatisfiedLinkError ignored) {
                            }
                        }
                    }
                    if (streamOk) {
                        ok++;
                    } else {
                        fail++;
                    }
                    Log.i(TAG, "selftest stream status=" + (streamOk ? "OK" : "FAIL")
                            + " bytes=" + total + " chunks=" + chunks);
                }
                // Stop mid-flight: speak long, read once, stop, read => -1.
                if (ids.length > 0) {
                    long st = 0;
                    boolean stopOk = false;
                    try {
                        st = EloQuickEngine.nativeStreamCreate(ids[0]);
                        if (st != 0 && EloQuickEngine.nativeStreamSpeak(st,
                                "Hello world. This is EloQuick speaking. "
                                        + "The quick brown fox jumps over the lazy dog.")) {
                            byte[] buf = new byte[8192];
                            int first = EloQuickEngine.nativeStreamRead(st, buf, buf.length);
                            EloQuickEngine.nativeStreamStop(st);
                            int after = EloQuickEngine.nativeStreamRead(st, buf, buf.length);
                            stopOk = first > 0 && after == -1;
                            Log.i(TAG, "selftest stop first=" + first + " after=" + after);
                        }
                    } catch (UnsatisfiedLinkError e) {
                        Log.e(TAG, "selftest stop missing", e);
                    } finally {
                        if (st != 0) {
                            try {
                                EloQuickEngine.nativeStreamDestroy(st);
                            } catch (UnsatisfiedLinkError ignored) {
                            }
                        }
                    }
                    if (stopOk) {
                        ok++;
                    } else {
                        fail++;
                    }
                    Log.i(TAG, "selftest stop status=" + (stopOk ? "OK" : "FAIL"));
                }
                // Dictionary round-trip: teach, look up, forget.
                if (ids.length > 0) {
                    long h = 0;
                    boolean dictOk = false;
                    try {
                        h = EloQuickEngine.nativeCreate(ids[0]);
                        if (h != 0
                                && EloQuickEngine.nativeDictTeach(h, 0, "eqwtest", "hello") == 0) {
                            String got = EloQuickEngine.nativeDictLookup(h, 0, "eqwtest");
                            EloQuickEngine.nativeDictForget(h);
                            String gone = EloQuickEngine.nativeDictLookup(h, 0, "eqwtest");
                            dictOk = "hello".equals(got) && gone == null;
                            Log.i(TAG, "selftest dict lookup=" + got + " afterForget=" + gone);
                        }
                    } catch (UnsatisfiedLinkError e) {
                        Log.e(TAG, "selftest dict missing", e);
                    } finally {
                        if (h != 0) {
                            try {
                                EloQuickEngine.nativeDestroy(h);
                            } catch (UnsatisfiedLinkError ignored) {
                            }
                        }
                    }
                    if (dictOk) {
                        ok++;
                    } else {
                        fail++;
                    }
                    Log.i(TAG, "selftest dict status=" + (dictOk ? "OK" : "FAIL"));
                }
                // Heteronym filter: creation-time property. Default on for one
                // instance, off for another; a heteronym-heavy line must
                // render differently (either probe suffices).
                if (ids.length > 0) {
                    boolean hetOk = false;
                    try {
                        String[] probes = {"They will transport it.",
                                "I produce music at home."};
                        boolean differ = false;
                        for (String probe : probes) {
                            EloQuickEngine.nativeSetHeteroDefault(false);
                            long hOff = EloQuickEngine.nativeCreate(ids[0]);
                            EloQuickEngine.nativeSetHeteroDefault(true);
                            long hOn = EloQuickEngine.nativeCreate(ids[0]);
                            EloQuickEngine.nativeSetHeteroDefault(false);
                            short[] a = hOff != 0
                                    ? EloQuickEngine.nativeSynth(hOff, probe) : null;
                            short[] b = hOn != 0
                                    ? EloQuickEngine.nativeSynth(hOn, probe) : null;
                            if (hOff != 0) EloQuickEngine.nativeDestroy(hOff);
                            if (hOn != 0) EloQuickEngine.nativeDestroy(hOn);
                            boolean d = a != null && b != null
                                    && !java.util.Arrays.equals(a, b);
                            if (d) differ = true;
                            Log.i(TAG, "selftest hetero probe='" + probe + "' differ=" + d
                                    + " offLen=" + (a == null ? -1 : a.length)
                                    + " onLen=" + (b == null ? -1 : b.length));
                        }
                        hetOk = differ;
                    } catch (UnsatisfiedLinkError e) {
                        Log.e(TAG, "selftest hetero missing", e);
                    } finally {
                        try {
                            EloQuickEngine.nativeSetHeteroDefault(false);
                        } catch (UnsatisfiedLinkError ignored) {
                        }
                    }
                    if (hetOk) {
                        ok++;
                    } else {
                        fail++;
                    }
                    Log.i(TAG, "selftest hetero status=" + (hetOk ? "OK" : "FAIL"));
                }
                // Sample rate: 22050 holds and roughly doubles the frames;
                // unknown rates fall back to 11025.
                if (ids.length > 0) {
                    boolean rateOk = false;
                    try {
                        long h = EloQuickEngine.nativeCreate(ids[0]);
                        if (h != 0) {
                            int got = EloQuickEngine.nativeSetSampleRateHz(h, 22050);
                            short[] hi = EloQuickEngine.nativeSynth(h, "Hello world.");
                            EloQuickEngine.nativeDestroy(h);
                            long h2 = EloQuickEngine.nativeCreate(ids[0]);
                            int fallback = EloQuickEngine.nativeSetSampleRateHz(h2, 12345);
                            short[] lo = EloQuickEngine.nativeSynth(h2, "Hello world.");
                            EloQuickEngine.nativeDestroy(h2);
                            double ratio = (lo != null && lo.length > 0 && hi != null)
                                    ? (double) hi.length / lo.length : 0;
                            rateOk = got == 22050 && fallback == 11025
                                    && ratio > 1.9 && ratio < 2.1;
                            Log.i(TAG, "selftest rate got=" + got + " fallback=" + fallback
                                    + " ratio=" + ratio);
                        }
                    } catch (UnsatisfiedLinkError e) {
                        Log.e(TAG, "selftest rate missing", e);
                    }
                    if (rateOk) {
                        ok++;
                    } else {
                        fail++;
                    }
                    Log.i(TAG, "selftest rate status=" + (rateOk ? "OK" : "FAIL"));
                }
                final int fok = ok;
                final int ffail = fail;
                Log.i(TAG, "RESULT ok=" + fok + " fail=" + ffail);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("self-test RESULT ok=" + fok + " fail=" + ffail);
                    }
                });
            }
        });
    }

    /** Full framework loop through our own TTS service: client -> binder ->
     *  EloQuickTtsService -> engine -> audioAvailable. No system default is
     *  changed; the client binds by explicit package. */
    private void runFrameworkTest() {
        setStatus("framework test running ...");
        bg.execute(new Runnable() {
            @Override
            public void run() {
                final java.util.concurrent.CountDownLatch done =
                        new java.util.concurrent.CountDownLatch(1);
                final boolean[] passed = {false};
                final String[] detail = {""};
                try {
                    final android.speech.tts.TextToSpeech[] holder =
                            new android.speech.tts.TextToSpeech[1];
                    holder[0] = new android.speech.tts.TextToSpeech(MainActivity.this,
                            new android.speech.tts.TextToSpeech.OnInitListener() {
                                @Override
                                public void onInit(int status) {
                                    if (status != android.speech.tts.TextToSpeech.SUCCESS) {
                                        detail[0] = "init status=" + status;
                                        done.countDown();
                                        return;
                                    }
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            speakViaFramework(holder[0], done, passed, detail);
                                        }
                                    });
                                }
                            }, "com.eloquick.debug");
                    if (!done.await(45, java.util.concurrent.TimeUnit.SECONDS)) {
                        detail[0] = "timeout waiting for utterance";
                    }
                    try {
                        holder[0].shutdown();
                    } catch (Exception ignored) {
                    }
                } catch (Exception e) {
                    detail[0] = "exception: " + e;
                    done.countDown();
                }
                Log.i(TAG, "FW status=" + (passed[0] ? "OK" : "FAIL") + " " + detail[0]);
                final boolean fp = passed[0];
                final String fd = detail[0];
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("framework test " + (fp ? "OK " : "FAIL ") + fd);
                    }
                });
            }
        });
    }

    private void speakViaFramework(final android.speech.tts.TextToSpeech tts,
                                   final java.util.concurrent.CountDownLatch done,
                                   final boolean[] passed, final String[] detail) {
        tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
                Log.i(TAG, "FW onStart " + id);
            }

            @Override
            public void onDone(String id) {
                passed[0] = true;
                detail[0] = "utterance " + id + " done";
                done.countDown();
            }

            @Override
            public void onError(String id) {
                detail[0] = "utterance " + id + " error";
                done.countDown();
            }
        });
        int lang = tts.setLanguage(java.util.Locale.US);
        Log.i(TAG, "FW setLanguage(US)=" + lang);
        int rc = tts.speak("Hello from the framework loop.", android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                null, "eq-fw-1");
        Log.i(TAG, "FW speak rc=" + rc);
        if (rc != android.speech.tts.TextToSpeech.SUCCESS) {
            detail[0] = "speak rc=" + rc;
            done.countDown();
        }
    }
}
