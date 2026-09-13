package com.eloquick.debug;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import com.eloquick.tts.Eci;
import com.eloquick.tts.EloQuickEngine;

import java.util.ArrayList;

/** Answers the framework's CHECK_TTS_DATA: which voices are installed.
 * No files to check -- every voice ships inside the native library. */
public class CheckVoiceDataActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ArrayList<String> available = new ArrayList<>();
        try {
            int[] ids = EloQuickEngine.nativeGetLanguages();
            if (ids != null) {
                for (int id : ids) {
                    String[] loc = Eci.localeOf(id);
                    if (loc == null) continue;
                    for (String preset : Eci.PRESET_NAMES) {
                        available.add(loc[0] + "-" + loc[1] + "-" + preset);
                    }
                }
            }
        } catch (UnsatisfiedLinkError ignored) {
        }
        Intent result = new Intent();
        result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available);
        result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
                new ArrayList<String>());
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result);
        finish();
    }
}
