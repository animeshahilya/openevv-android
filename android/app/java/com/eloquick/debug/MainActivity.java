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
 *
 * Self-test logs EQTEST lines and a final EQTEST RESULT ok=N fail=M:
 * every listed language synths (and optionally plays), an unknown language
 * is refused, and voice presets 1-8 each copy. Drive it with logcat:
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
}
