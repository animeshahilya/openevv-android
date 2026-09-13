package com.eloquick.debug;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.eloquick.tts.Eci;
import com.eloquick.tts.EloQuickEngine;

/** Answers GET_SAMPLE_TEXT with a short line in the requested language. */
public class GetSampleTextActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String want = getIntent() != null ? getIntent().getStringExtra("language") : null;
        String sample = "Hello from EloQuick.";
        if (want != null && want.length() >= 3) {
            String iso = want.substring(0, 3).toLowerCase(java.util.Locale.US);
            try {
                int[] ids = EloQuickEngine.nativeGetLanguages();
                if (ids != null) {
                    for (int id : ids) {
                        String[] loc = Eci.localeOf(id);
                        if (loc != null && loc[0].equalsIgnoreCase(iso)) {
                            sample = sampleFor(loc[0]);
                            break;
                        }
                    }
                }
            } catch (UnsatisfiedLinkError ignored) {
            }
        }
        Intent result = new Intent();
        result.putExtra("sampleText", sample);
        setResult(RESULT_OK, result);
        finish();
    }

    private static String sampleFor(String iso3) {
        if ("deu".equals(iso3)) return "Guten Tag. Wie geht es Ihnen?";
        if ("spa".equals(iso3)) return "Hola. Buenos dias.";
        if ("fra".equals(iso3)) return "Bonjour. Comment allez-vous?";
        if ("ita".equals(iso3)) return "Buongiorno. Come stai?";
        if ("pol".equals(iso3)) return "Dzien dobry. Jak sie masz?";
        if ("jpn".equals(iso3)) return "Hello.";
        if ("eng".equals(iso3)) return "Hello from EloQuick.";
        return "Hello from EloQuick.";
    }
}
