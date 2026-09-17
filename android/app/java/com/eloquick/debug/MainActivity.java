package com.eloquick.debug;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.eloquick.tts.AudioOptimizer;
import com.eloquick.tts.Eci;
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
    private Tts.StopFlag currentSpeech;
    private SeekBar speedBar;
    private TextView speedLabel;
    private EditText dictKey;
    private EditText dictSay;
    private ArrayAdapter<String> dictAdapter;
    private java.util.List<EqDictionary.Entry> dictEntries = new java.util.ArrayList<>();
    private EditText rulePattern;
    private EditText ruleSay;
    private CheckBox ruleWhole;
    private CheckBox ruleCase;
    private CheckBox ruleRegex;
    private ArrayAdapter<String> rulesAdapter;
    private java.util.List<EqUserRules.Rule> ruleEntries = new java.util.ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        langIds = safeGetLanguages();

        ScrollView page = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        page.addView(root);

        langSpinner = new Spinner(this);
        voiceSpinner = new Spinner(this);
        textInput = new EditText(this);
        Button speak = new Button(this);
        status = new TextView(this);

        langSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, langLabels()));
        List<String> voices = new ArrayList<>();
        for (int v = 0; v < Eci.PRESET_NAMES.length; v++) {
            voices.add((v + 1) + " " + Eci.PRESET_NAMES[v]);
        }
        voiceSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, voices));
        voiceSpinner.setSelection(Math.max(0, Math.min(7, EqPrefs.preset(this))));
        voiceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                EqPrefs.setPreset(MainActivity.this, pos);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        // ---- Say: what to hear ----
        root.addView(sectionHeader("🗣  Say"));
        textInput.setHint("Type something to hear it");
        textInput.setText("Hello from EloQuick on Android.");
        root.addView(labeled("Text", textInput));
        speak.setText("Speak");
        speak.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSpeakPressed();
            }
        });
        root.addView(speak);
        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onStopPressed();
            }
        });
        root.addView(stop);

        status.setText(statusLine("ready", langIds.length));

        // ---- Voice: who says it ----
        root.addView(sectionHeader("🎙  Voice"));
        root.addView(labeled("Language", langSpinner));
        root.addView(labeled("Voice", voiceSpinner));
        speedLabel = new TextView(this);
        updateSpeedLabel(EqPrefs.speed(this));
        root.addView(labeled("Speed", speedLabel));
        speedBar = new SeekBar(this);
        speedBar.setMax(Eci.SPEED_MAX);
        speedBar.setContentDescription("Speech speed");
        speedBar.setProgress(EqPrefs.speed(this));
        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                updateSpeedLabel(value);
                EqPrefs.setSpeed(MainActivity.this, value);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        root.addView(speedBar);

        // ---- Pronunciation: teach words, tap one to forget it ----
        root.addView(sectionHeader("📖  Pronunciation"));
        TextView dictHelp = new TextView(this);
        dictHelp.setText("Teach the engine a word, then tap it below to forget it.");
        root.addView(dictHelp);
        dictKey = new EditText(this);
        dictKey.setHint("word as written");
        root.addView(labeled("Word", dictKey));
        dictSay = new EditText(this);
        dictSay.setHint("say instead");
        root.addView(labeled("Say instead", dictSay));
        Button dictAdd = new Button(this);
        dictAdd.setText("Teach word");
        dictAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onDictAdd();
            }
        });
        root.addView(dictAdd);
        ListView dictList = new ListView(this);
        dictAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                new ArrayList<String>());
        dictList.setAdapter(dictAdapter);
        // Fixed height: a ListView inside a ScrollView would otherwise
        // collapse to one row.
        float density = getResources().getDisplayMetrics().density;
        dictList.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (160 * density)));
        dictList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                onDictDelete(pos);
            }
        });
        root.addView(dictList);
        refreshDictList();

        // ---- My words: regex-capable rules applied before the engine ----
        root.addView(sectionHeader("✏️  My Words"));
        TextView rulesHelp = new TextView(this);
        rulesHelp.setText("Rewrite text before the engine hears it, then tap a rule below to forget it.");
        root.addView(rulesHelp);
        CheckBox rulesBox = new CheckBox(this);
        rulesBox.setText("Apply my words");
        rulesBox.setChecked(EqPrefs.userRules(this));
        rulesBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                EqPrefs.setUserRules(MainActivity.this, on);
            }
        });
        root.addView(rulesBox);
        rulePattern = new EditText(this);
        rulePattern.setHint("text (tick regex below for patterns)");
        root.addView(labeled("Find", rulePattern));
        ruleSay = new EditText(this);
        ruleSay.setHint("say instead");
        root.addView(labeled("Say instead", ruleSay));
        ruleWhole = new CheckBox(this);
        ruleWhole.setText("Whole word");
        ruleWhole.setChecked(true);
        root.addView(ruleWhole);
        ruleCase = new CheckBox(this);
        ruleCase.setText("Match case");
        root.addView(ruleCase);
        ruleRegex = new CheckBox(this);
        ruleRegex.setText("Regular expression");
        ruleRegex.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (on) ruleWhole.setChecked(false);
            }
        });
        root.addView(ruleRegex);
        Button rulesAdd = new Button(this);
        rulesAdd.setText("Add rule");
        rulesAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRulesAdd();
            }
        });
        root.addView(rulesAdd);
        ListView rulesList = new ListView(this);
        rulesAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                new ArrayList<String>());
        rulesList.setAdapter(rulesAdapter);
        rulesList.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (160 * density)));
        rulesList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                onRulesDelete(pos);
            }
        });
        root.addView(rulesList);
        refreshRulesList();

        // ---- Reading: how it reads ----
        root.addView(sectionHeader("📖  Reading"));
        CheckBox heteroBox = new CheckBox(this);
        heteroBox.setText("Say 'transport' right, noun and verb (experimental)");
        heteroBox.setChecked(EqPrefs.hetero(this));
        heteroBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                EqPrefs.setHetero(MainActivity.this, on);
            }
        });
        root.addView(heteroBox);
        CheckBox wedBox = new CheckBox(this);
        wedBox.setText("Hear 'edhesday' as 'Wednesday'");
        wedBox.setChecked(EqPrefs.wednesdayGuard(this));
        wedBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                EqPrefs.setWednesdayGuard(MainActivity.this, on);
            }
        });
        root.addView(wedBox);
        CheckBox optBox = new CheckBox(this);
        optBox.setText("Polish the sound (presence + warmth, experimental)");
        optBox.setChecked(EqPrefs.optimizer(this));
        optBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                EqPrefs.setOptimizer(MainActivity.this, on);
            }
        });
        root.addView(optBox);
        final String[] optProfiles = {"gentle", "balanced", "full"};
        Spinner optProfile = new Spinner(this);
        optProfile.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, optProfiles));
        root.addView(labeled("Polish strength",
                spinPick(optProfile, indexOf(optProfiles, EqPrefs.optimizerProfile(this)),
                        new SpinChoice() {
                            @Override
                            public void picked(int pos) {
                                EqPrefs.setOptimizerProfile(MainActivity.this, optProfiles[pos]);
                            }
                        })));
        CheckBox normBox = new CheckBox(this);
        normBox.setText("Read styled text as words (fancy fonts to plain)");
        normBox.setChecked(EqPrefs.unicodeNorm(this));
        normBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                EqPrefs.setUnicodeNorm(MainActivity.this, on);
            }
        });
        root.addView(normBox);
        final String[] emojiModes = {"announce", "ignore"};
        final String[] emojiValues = {EqPrefs.EMOJI_ANNOUNCE, EqPrefs.EMOJI_IGNORE};
        Spinner emojiSpinner = new Spinner(this);
        emojiSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, emojiModes));
        root.addView(labeled("Emoji",
                spinPick(emojiSpinner, indexOf(emojiValues, EqPrefs.emojiMode(this)),
                        new SpinChoice() {
                            @Override
                            public void picked(int pos) {
                                EqPrefs.setEmojiMode(MainActivity.this, emojiValues[pos]);
                            }
                        })));
        final String[] digitNames = {"off", "single", "double", "triple"};
        final String[] digitValues = {EqPrefs.DIGIT_OFF, EqPrefs.DIGIT_SINGLE,
                EqPrefs.DIGIT_DOUBLE, EqPrefs.DIGIT_TRIPLE};
        Spinner digitSpinner = new Spinner(this);
        digitSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, digitNames));
        root.addView(labeled("Long numbers",
                spinPick(digitSpinner, indexOf(digitValues, EqPrefs.digitGrouping(this)),
                        new SpinChoice() {
                            @Override
                            public void picked(int pos) {
                                EqPrefs.setDigitGrouping(MainActivity.this, digitValues[pos]);
                            }
                        })));
        CheckBox currBox = new CheckBox(this);
        currBox.setText("Say '$5' as '5 dollars'");
        currBox.setChecked(EqPrefs.currency(this));
        currBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                EqPrefs.setCurrency(MainActivity.this, on);
            }
        });
        root.addView(currBox);
        CheckBox timeBox = new CheckBox(this);
        timeBox.setText("Say '3:30' and '2026-09-16' as words");
        timeBox.setChecked(EqPrefs.timeDate(this));
        timeBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                EqPrefs.setTimeDate(MainActivity.this, on);
            }
        });
        root.addView(timeBox);
        final String[] modeNames = {"normal", "spelling", "phonetic (NATO)", "code"};
        final String[] modeValues = {EqPrefs.READING_NORMAL, EqPrefs.READING_SPELLING,
                EqPrefs.READING_PHONETIC, EqPrefs.READING_CODE};
        Spinner modeSpinner = new Spinner(this);
        modeSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, modeNames));
        root.addView(labeled("Reading mode",
                spinPick(modeSpinner, indexOf(modeValues, EqPrefs.readingMode(this)),
                        new SpinChoice() {
                            @Override
                            public void picked(int pos) {
                                EqPrefs.setReadingMode(MainActivity.this, modeValues[pos]);
                            }
                        })));
        CheckBox progBox = new CheckBox(this);
        progBox.setText("Say every { } [ ] symbol (code reading)");
        progBox.setChecked(EqPrefs.progSymbols(this));
        progBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                EqPrefs.setProgSymbols(MainActivity.this, on);
            }
        });
        root.addView(progBox);
        final String[] punctNames = {"none", "some", "most", "all", "custom"};
        final String[] punctValues = {EqPrefs.PUNCT_NONE, EqPrefs.PUNCT_SOME,
                EqPrefs.PUNCT_MOST, EqPrefs.PUNCT_ALL, EqPrefs.PUNCT_CUSTOM};
        Spinner punctSpinner = new Spinner(this);
        punctSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, punctNames));
        root.addView(labeled("Speak punctuation",
                spinPick(punctSpinner, indexOf(punctValues, EqPrefs.punctPreset(this)),
                        new SpinChoice() {
                            @Override
                            public void picked(int pos) {
                                EqPrefs.setPunctPreset(MainActivity.this, punctValues[pos]);
                            }
                        })));
        final EditText punctEdit = new EditText(this);
        punctEdit.setHint("custom punctuation characters");
        punctEdit.setText(EqPrefs.punctCustom(this));
        punctEdit.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    EqPrefs.setPunctCustom(MainActivity.this,
                            punctEdit.getText().toString());
                }
            }
        });
        root.addView(labeled("Custom characters", punctEdit));
        CheckBox forceRateBox = new CheckBox(this);
        forceRateBox.setText("Lock speed against caller apps");
        forceRateBox.setChecked(EqPrefs.forceRate(this));
        forceRateBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                EqPrefs.setForceRate(MainActivity.this, on);
            }
        });
        root.addView(forceRateBox);
        CheckBox forcePitchBox = new CheckBox(this);
        forcePitchBox.setText("Lock pitch against caller apps");
        forcePitchBox.setChecked(EqPrefs.forcePitch(this));
        forcePitchBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                EqPrefs.setForcePitch(MainActivity.this, on);
            }
        });
        root.addView(forcePitchBox);
        final String[] pauseNames = {"as written", "trim end only", "shorten all"};
        final String[] pauseValues = {EqPrefs.PAUSES_KEEP, EqPrefs.PAUSES_END_ONLY,
                EqPrefs.PAUSES_ALL};
        Spinner pauseSpinner = new Spinner(this);
        pauseSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, pauseNames));
        root.addView(labeled("Engine pauses",
                spinPick(pauseSpinner, indexOf(pauseValues, EqPrefs.pauses(this)),
                        new SpinChoice() {
                            @Override
                            public void picked(int pos) {
                                EqPrefs.setPauses(MainActivity.this, pauseValues[pos]);
                            }
                        })));
        CheckBox phraseBox = new CheckBox(this);
        phraseBox.setText("Phrase tune-up (prose intonation, not fragments)");
        phraseBox.setChecked(EqPrefs.phrasePrediction(this));
        phraseBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                EqPrefs.setPhrasePrediction(MainActivity.this, on);
            }
        });
        root.addView(phraseBox);
        CheckBox quietBox = new CheckBox(this);
        quietBox.setText("Silence bullet separators ('-' '*' lines)");
        quietBox.setChecked(EqPrefs.quietPunct(this));
        quietBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                EqPrefs.setQuietPunct(MainActivity.this, on);
            }
        });
        root.addView(quietBox);
        TextView rateLabel = new TextView(this);
        rateLabel.setText("Sound quality");
        root.addView(rateLabel);
        Spinner rateSpinner = new Spinner(this);
        final int[] rates = {11025, 22050, 44100, 48000};
        final String[] rateNames = {"Standard (11 kHz)", "Clear (22 kHz)",
                "Clearer (44.1 kHz)", "Studio (48 kHz)"};
        rateSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, rateNames));
        int savedRate = EqPrefs.rateHz(this);
        int ratePos = 1;
        for (int i = 0; i < rates.length; i++) {
            if (rates[i] == savedRate) ratePos = i;
        }
        rateSpinner.setSelection(ratePos);
        rateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean first = true;

            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (first) {
                    first = false;
                    return;
                }
                EqPrefs.setRateHz(MainActivity.this, rates[pos]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        root.addView(rateSpinner);

        // ---- Audio ----
        root.addView(sectionHeader("🔊  Audio"));

        // Becoming the system voice is a Settings act, not ours: one tap
        // to the right screen.
        Button ttsSettings = new Button(this);
        ttsSettings.setText("System speech settings");
        ttsSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new android.content.Intent(
                            "com.android.settings.TTS_SETTINGS"));
                } catch (Exception e) {
                    setStatus("system speech settings not found");
                }
            }
        });
        root.addView(ttsSettings);

        root.addView(status);
        setContentView(page);

        Bundle ex = getIntent() != null ? getIntent().getExtras() : null;
        handleExtras(ex);
        try {
            android.content.IntentFilter commands =
                    new android.content.IntentFilter("com.eloquick.debug.STOP");
            // EXPORTED so automation (adb shell, uid 2000) can reach it;
            // the command only stops our own speech, nothing privileged.
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(commandReceiver, commands, RECEIVER_EXPORTED);
            } else {
                registerReceiver(commandReceiver, commands);
            }
        } catch (Exception e) {
            Log.e(TAG, "command receiver failed", e);
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Bundle b = intent != null ? intent.getExtras() : null;
        Log.i(TAG, "onNewIntent extras=" + (b == null ? "null" : b.keySet().toString()));
        handleExtras(b);
    }

    private void handleExtras(Bundle ex) {
        if (ex != null && ex.getBoolean("selftest", false)) {
            boolean play = ex.getBoolean("playaudio", true);
            runSelfTest(play);
        } else if (ex != null && ex.getBoolean("fwtest", false)) {
            runFrameworkTest();
        } else if (ex != null && ex.getBoolean("stop", false)) {
            onStopPressed();
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
        try {
            unregisterReceiver(commandReceiver);
        } catch (Exception ignored) {
        }
        bg.shutdownNow();
        super.onDestroy();
    }

    /** Automation commands that must arrive even when activity-intent
     *  redelivery stalls (observed: onNewIntent not firing mid-speech
     *  behind the lock screen). am broadcast -a com.eloquick.debug.STOP */
    private final android.content.BroadcastReceiver commandReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent i) {
                    if (i == null || i.getAction() == null) return;
                    Log.i(TAG, "command " + i.getAction());
                    if ("com.eloquick.debug.STOP".equals(i.getAction())) onStopPressed();
                }
            };

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
            // Plain names only: engine ids stay in logs, not on screen.
            out.add(i < names.length ? names[i] : ("Language " + (i + 1)));
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
        final Tts.StopFlag flag = new Tts.StopFlag();
        currentSpeech = flag;
        setStatus("speaking lang=0x" + Integer.toHexString(lang)
                + " voice=" + voice + " ...");
        bg.execute(new Runnable() {
            @Override
            public void run() {
                final Tts.Result r = Tts.speakStream(lang, voice, text, true, flag);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStatus(r.stopped ? "stopped"
                                : r.ok
                                ? ("done: " + r.samples + " samples, " + r.synthMs + "ms")
                                : ("FAILED: " + r.error));
                    }
                });
                Log.i(TAG, "speak lang=0x" + Integer.toHexString(lang)
                        + " voice=" + voice + " ok=" + r.ok + " stopped=" + r.stopped
                        + " samples=" + r.samples + " ms=" + r.synthMs
                        + (r.error != null ? " err=" + r.error : ""));
            }
        });
    }

    private void onStopPressed() {
        Tts.StopFlag flag = currentSpeech;
        Log.i(TAG, "stop pressed, current=" + (flag != null));
        if (flag != null) flag.stop = true;
        setStatus("stopping ...");
    }

    private void setStatus(String s) {
        status.setText(statusLine(s, langIds.length));
    }

    private TextView sectionHeader(String title) {
        TextView h = new TextView(this);
        h.setText(title);
        h.setTextSize(18);
        float density = getResources().getDisplayMetrics().density;
        h.setPadding(0, (int) (12 * density), 0, (int) (4 * density));
        return h;
    }

    /** A small caption above a control, wired for TalkBack via labelFor. */
    private LinearLayout labeled(String caption, View control) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(caption);
        if (control.getId() == View.NO_ID) control.setId(View.generateViewId());
        label.setLabelFor(control.getId());
        row.addView(label);
        row.addView(control);
        return row;
    }

    private void updateSpeedLabel(int value) {
        if (speedLabel != null) speedLabel.setText(value + " (voices ship at 50)");
    }

    private void onDictAdd() {
        String key = dictKey.getText().toString().trim();
        String say = dictSay.getText().toString().trim();
        if (key.isEmpty() || say.isEmpty()) {
            setStatus("dictionary: need both a word and what to say");
            return;
        }
        dictEntries.add(new EqDictionary.Entry(key, say));
        EqDictionary.write(this, dictEntries);
        dictKey.setText("");
        dictSay.setText("");
        refreshDictList();
        setStatus("dictionary: taught '" + key + "' (" + dictEntries.size() + " entries)");
    }

    private void onDictDelete(int pos) {
        if (pos < 0 || pos >= dictEntries.size()) return;
        EqDictionary.Entry removed = dictEntries.remove(pos);
        EqDictionary.write(this, dictEntries);
        refreshDictList();
        setStatus("dictionary: forgot '" + removed.key + "'");
    }

    private void refreshDictList() {
        dictEntries = EqDictionary.read(this);
        if (dictAdapter == null) return;
        dictAdapter.clear();
        for (EqDictionary.Entry e : dictEntries) {
            dictAdapter.add(e.key + "  ->  " + e.say);
        }
        dictAdapter.notifyDataSetChanged();
    }

    /** Spinner choice callback, without the initial-layout firing. */
    private interface SpinChoice {
        void picked(int pos);
    }

    private static int indexOf(String[] options, String value) {
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(value)) return i;
        }
        return 0;
    }

    /** Attaches a choice listener that skips the layout-time firing. */
    private static Spinner spinPick(Spinner spinner, int selected, final SpinChoice onPick) {
        spinner.setSelection(selected);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean first = true;

            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (first) {
                    first = false;
                    return;
                }
                onPick.picked(pos);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        return spinner;
    }

    private void onRulesAdd() {
        String pattern = rulePattern.getText().toString();
        String say = ruleSay.getText().toString();
        if (pattern.isEmpty()) {
            setStatus("my words: need text to find");
            return;
        }
        ruleEntries.add(new EqUserRules.Rule(pattern, say, ruleCase.isChecked(),
                ruleRegex.isChecked(), ruleWhole.isChecked(), ""));
        EqUserRules.write(this, ruleEntries);
        rulePattern.setText("");
        ruleSay.setText("");
        refreshRulesList();
        setStatus("my words: added '" + pattern + "' (" + ruleEntries.size() + " rules)");
    }

    private void onRulesDelete(int pos) {
        if (pos < 0 || pos >= ruleEntries.size()) return;
        EqUserRules.Rule removed = ruleEntries.remove(pos);
        EqUserRules.write(this, ruleEntries);
        refreshRulesList();
        setStatus("my words: forgot '" + removed.pattern + "'");
    }

    private void refreshRulesList() {
        ruleEntries = EqUserRules.read(this);
        if (rulesAdapter == null) return;
        rulesAdapter.clear();
        for (EqUserRules.Rule r : ruleEntries) {
            String flags = (r.isRegex ? "re" : "tx") + (r.wholeWord ? ",w" : "")
                    + (r.caseSensitive ? ",c" : "");
            rulesAdapter.add(r.pattern + "  ->  " + r.replacement + "  [" + flags + "]");
        }
        rulesAdapter.notifyDataSetChanged();
    }

    /** One named pure-Java pipeline check; always logs, never throws. */
    private boolean checkText(String name, boolean cond) {
        Log.i(TAG, "selftest text " + name + " status=" + (cond ? "OK" : "FAIL"));
        return cond;
    }

    /** Deterministic checks for the pre/post-synthesis helpers: no audio,
     *  no device state, safe to run anywhere the self-test runs. */
    private boolean selftestTextPipeline() {
        boolean pass = true;
        pass &= checkText("normalize",
                EqText.normalize("Ｈｅｌｌｏ").equals("Hello"));
        pass &= checkText("digits-single",
                EqText.groupDigits("call 12345678 now", 1, 7).equals("call 1 2 3 4 5 6 7 8 now"));
        pass &= checkText("digits-triple",
                EqText.groupDigits("1234567890", 3, 7).equals("1 234 567 890"));
        pass &= checkText("digits-short",
                EqText.groupDigits("call 123 now", 1, 7).equals("call 123 now"));
        pass &= checkText("currency",
                EqText.expandCurrency("it costs $5.00").equals("it costs 5.00 dollars"));
        pass &= checkText("time",
                EqText.expandTimeDate("at 3:30").equals("at three thirty"));
        pass &= checkText("time-am",
                EqText.expandTimeDate("at 10:30 AM").equals("at ten thirty AM"));
        pass &= checkText("time-oh",
                EqText.expandTimeDate("at 12:05").equals("at twelve oh five"));
        pass &= checkText("time-midnight",
                EqText.expandTimeDate("at 00:30").equals("at midnight thirty"));
        pass &= checkText("time-oclock",
                EqText.expandTimeDate("at 3:00").equals("at three o'clock"));
        pass &= checkText("time-bad",
                EqText.expandTimeDate("at 13:00 PM").equals("at 13:00 PM"));
        pass &= checkText("date",
                EqText.expandTimeDate("on 2026-09-16")
                        .equals("on twenty twenty-six September sixteenth"));
        pass &= checkText("date-dmy",
                EqText.expandTimeDate("on 14/07/2024")
                        .equals("on fourteenth July twenty twenty-four"));
        pass &= checkText("date-mdy",
                EqText.expandTimeDate("on 07/14/2024")
                        .equals("on July fourteenth twenty twenty-four"));
        pass &= checkText("date-2digit",
                EqText.expandTimeDate("on 14/07/24")
                        .equals("on fourteenth July twenty twenty-four"));
        pass &= checkText("date-ambiguous",
                EqText.expandTimeDate("on 05/08/2024").equals("on 05/08/2024"));
        pass &= checkText("date-idiom",
                EqText.expandTimeDate("open 24/7").equals("open 24/7"));
        pass &= checkText("words",
                (EqText.cardinalWord(22).equals("twenty-two")
                        && EqText.ordinalWord(22).equals("twenty-second")
                        && EqText.yearWord(1900).equals("nineteen hundred")
                        && EqText.yearWord(2000).equals("two thousand")
                        && EqText.yearWord(1905).equals("nineteen oh five")));
        pass &= checkText("roman-section",
                EqText.expandRomanNumerals("read Chapter IV").equals("read Chapter 4"));
        pass &= checkText("roman-monarch",
                EqText.expandRomanNumerals("Henry VIII").equals("Henry the eighth"));
        pass &= checkText("roman-prose",
                EqText.expandRomanNumerals("I V said").equals("I V said"));
        pass &= checkText("flatten",
                EqText.flattenWestern("\u201Chi\u201D \u2014 ok\u2026")
                        .equals("\"hi\" - ok..."));
        pass &= checkText("fix-digit",
                EqText.fixEngineText("teamtalk5").equals("teamtalk 5"));
        pass &= checkText("fix-opener",
                EqText.fixEngineText("abc(def").equals("abc (def"));
        pass &= checkText("fix-plural",
                EqText.fixEngineText("books (s)").equals("books(s)"));
        pass &= checkText("fix-space-mark",
                EqText.fixEngineText("wait .").equals("wait."));
        pass &= checkText("fix-thousands",
                EqText.fixEngineText("Pay 1,000,000 now").equals("Pay 1000000 now"));
        pass &= checkText("pauses-keep",
                EqText.shortenPauses("Hello. World", EqText.PAUSES_KEEP, true)
                        .equals("Hello. World"));
        pass &= checkText("pauses-end",
                EqText.shortenPauses("Hello. World", EqText.PAUSES_END_ONLY, true)
                        .equals("Hello. World `p100"));
        pass &= checkText("pauses-all",
                EqText.shortenPauses("Hello. World", EqText.PAUSES_ALL, true)
                        .equals("Hello `p1. World `p100"));
        pass &= checkText("pauses-decimal",
                EqText.shortenPauses("Pi is 3.14", EqText.PAUSES_ALL, false)
                        .equals("Pi is 3.14"));
        pass &= checkText("quiet-bullet",
                EqText.stripIsolatedPunctuation("a - b").equals("a b"));
        pass &= checkText("quiet-attached",
                EqText.stripIsolatedPunctuation("wait...").equals("wait..."));
        pass &= checkText("spelling",
                EqText.expandSpelling("ab").equals("a b"));
        pass &= checkText("phonetic",
                EqText.expandPhonetic("ab").equals("Alpha Bravo"));
        pass &= checkText("symbols",
                EqText.expandProgrammingSymbols("a+b").equals("a plus b"));
        pass &= checkText("punct",
                EqText.expandPunctuation("hi!", "!").equals("hi exclamation"));
        pass &= checkText("emoji-ignore",
                EqText.filterEmojis("hi 😀!").equals("hi !"));
        pass &= checkText("emoji-announce",
                EqText.clarifyEmojis("hi 😀!").equals("hi  emoji !"));
        pass &= checkText("surrogates",
                EqText.stripUnpairedSurrogates("a\uD83Db").equals("ab"));
        pass &= checkText("controls",
                EqText.sanitizeControls("a\u0001b").equals("ab"));
        pass &= checkText("wednesday",
                EqText.wednesdayGuard("see you edhesday").equals("see you Wednesday"));
        EqUserRules.Rule scoped = new EqUserRules.Rule(
                "colour", "color", false, false, true, "eng");
        pass &= checkText("rule-scope",
                scoped.appliesTo("eng-usa") && !scoped.appliesTo("deu-deu"));
        pass &= checkText("rule-apply",
                scoped.apply("the colour").equals("the color"));
        EqUserRules.Rule unscoped = new EqUserRules.Rule(
                "x", "y", false, false, true, "");
        pass &= checkText("rule-unscoped", unscoped.appliesTo("jpn-jpn"));
        EqUserRules.Rule regex = new EqUserRules.Rule("a+", "b", false, true, false, "");
        pass &= checkText("rule-regex", regex.apply("aa a").equals("b b"));
        try {
            AudioOptimizer opt = new AudioOptimizer(22050,
                    AudioOptimizer.Profile.BALANCED);
            byte[] pcm = new byte[2048];
            for (int i = 0; i < pcm.length; i += 2) {
                pcm[i] = (byte) (i & 0xFF);
                pcm[i + 1] = 0;
            }
            opt.process(pcm, pcm.length);
            pass &= checkText("optimizer", true);
        } catch (Throwable t) {
            Log.e(TAG, "selftest optimizer threw", t);
            pass = false;
            checkText("optimizer", false);
        }
        return pass;
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
                // Dictionary file load: write two entries, load the file,
                // look both up (one mid-sentence case), forget.
                if (ids.length > 0) {
                    boolean fileOk = false;
                    long h = 0;
                    try {
                        java.util.List<EqDictionary.Entry> entries =
                                new java.util.ArrayList<>();
                        entries.add(new EqDictionary.Entry("eqfileone", "hello"));
                        entries.add(new EqDictionary.Entry("EqFileTwo", "world"));
                        EqDictionary.write(MainActivity.this, entries);
                        h = EloQuickEngine.nativeCreate(ids[0]);
                        if (h != 0) {
                            int rc = EloQuickEngine.nativeDictLoad(h, 0,
                                    EqDictionary.file(MainActivity.this)
                                            .getAbsolutePath());
                            String a = EloQuickEngine.nativeDictLookup(h, 0, "eqfileone");
                            String b = EloQuickEngine.nativeDictLookup(h, 0, "EQFILETWO");
                            fileOk = rc == 0 && "hello".equals(a) && "world".equals(b);
                            Log.i(TAG, "selftest dictfile rc=" + rc + " a=" + a + " b=" + b);
                            EloQuickEngine.nativeDictForget(h);
                            EloQuickEngine.nativeDestroy(h);
                            h = 0;
                        }
                        EqDictionary.write(MainActivity.this,
                                new java.util.ArrayList<EqDictionary.Entry>());
                    } catch (Throwable t) {
                        Log.e(TAG, "selftest dictfile failed", t);
                    } finally {
                        if (h != 0) {
                            try {
                                EloQuickEngine.nativeDestroy(h);
                            } catch (UnsatisfiedLinkError ignored) {
                            }
                        }
                    }
                    if (fileOk) {
                        ok++;
                    } else {
                        fail++;
                    }
                    Log.i(TAG, "selftest dictfile status=" + (fileOk ? "OK" : "FAIL"));
                }
                // Wednesday guard: pure-text unit check.
                {
                    boolean wedOk = "I leave Wednesday.".equals(
                            EqText.wednesdayGuard("I leave edhesday."))
                            && "Hello.".equals(EqText.wednesdayGuard("Hello."));
                    if (wedOk) {
                        ok++;
                    } else {
                        fail++;
                    }
                    Log.i(TAG, "selftest wednesday status=" + (wedOk ? "OK" : "FAIL"));
                }
                // SpeechRate curve: unit checks on the ported table.
                {
                    boolean rate_curveOk = com.eloquick.tts.SpeechRate.speedForPercent(50, 100) == 50
                            && Math.abs(com.eloquick.tts.SpeechRate.timesFor(50) - 1.0) < 1e-9
                            && com.eloquick.tts.SpeechRate.speedFor(1.0) == 50
                            && com.eloquick.tts.SpeechRate.speedForPercent(50, 200)
                            == com.eloquick.tts.SpeechRate.speedFor(
                                    com.eloquick.tts.SpeechRate.timesFor(50) * 2.0);
                    if (rate_curveOk) {
                        ok++;
                    } else {
                        fail++;
                    }
                    Log.i(TAG, "selftest speechrate status=" + (rate_curveOk ? "OK" : "FAIL"));
                }
                // Interrupt endurance: 20 rapid speak/partial/stop cycles on
                // one language. evvdroid's InterruptEnduranceTest thinking:
                // stops must keep working hundreds of times over.
                if (ids.length > 0) {
                    int cyclesOk = 0;
                    String longText = "Hello world. This is EloQuick speaking. "
                            + "The quick brown fox jumps over the lazy dog. "
                            + "Pack my box with five dozen liquor jugs.";
                    for (int c = 0; c < 20; c++) {
                        long st = 0;
                        try {
                            st = EloQuickEngine.nativeStreamCreate(ids[0]);
                            if (st == 0) break;
                            if (!EloQuickEngine.nativeStreamSpeak(st, longText)) break;
                            byte[] buf = new byte[4096];
                            int first = EloQuickEngine.nativeStreamRead(st, buf, buf.length);
                            EloQuickEngine.nativeStreamStop(st);
                            int after = EloQuickEngine.nativeStreamRead(st, buf, buf.length);
                            if (first > 0 && after == -1) cyclesOk++;
                        } catch (UnsatisfiedLinkError e) {
                            Log.e(TAG, "selftest endurance missing", e);
                            break;
                        } finally {
                            if (st != 0) {
                                try {
                                    EloQuickEngine.nativeStreamDestroy(st);
                                } catch (UnsatisfiedLinkError ignored) {
                                }
                            }
                        }
                    }
                    if (cyclesOk == 20) {
                        ok++;
                    } else {
                        fail++;
                    }
                    Log.i(TAG, "selftest endurance status="
                            + (cyclesOk == 20 ? "OK" : "FAIL") + " cycles=" + cyclesOk + "/20");
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
                // Text pipeline: pure-Java transforms, deterministic.
                boolean textOk = selftestTextPipeline();
                if (textOk) {
                    ok++;
                } else {
                    fail++;
                }
                Log.i(TAG, "selftest text status=" + (textOk ? "OK" : "FAIL"));
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
