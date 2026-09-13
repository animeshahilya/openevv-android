package com.eloquick.debug;

import android.content.Context;
import android.util.Log;

import com.eloquick.tts.EloQuickEngine;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** The user's pronunciation dictionary: entries kept as key TAB say lines
 * (the engine's own volume-loader format -- src/eci/dict/eci_usrdct.c
 * splits lines on TAB), persisted to a device-protected file, loaded per
 * instance. Teach-a-word stays as the fallback path (nativeDictTeach).
 *
 * Two deliberate guards, both from measured pain elsewhere:
 * - case expansion: one entry is written per case form (see EqText), or
 *   "SCAN" keeps reading as an acronym mid-sentence;
 * - size cap: files over MAX_BYTES are refused with a message rather than
 *   handed to the engine (hundred-thousand-entry root dictionaries hang
 *   it on-device -- eloquence-revived's ENURoot.dic incident).
 */
public final class EqDictionary {
    private static final String TAG = "EQTTS";
    private static final String FILE_NAME = "user-dict.txt";
    /** Refuse to load anything bigger; tens of thousands of entries is a
     *  settings screen's worth, hundreds of thousands is a hang. */
    public static final int MAX_BYTES = 256 * 1024;
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    public static final class Entry {
        public final String key;
        public final String say;
        Entry(String key, String say) {
            this.key = key;
            this.say = say;
        }
    }

    private EqDictionary() {}

    public static File file(Context c) {
        Context storage = c;
        try {
            storage = c.createDeviceProtectedStorageContext();
        } catch (Exception ignored) {
        }
        return new File(storage.getFilesDir(), FILE_NAME);
    }

    public static List<Entry> read(Context c) {
        List<Entry> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), LATIN1));
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    String key = line.substring(0, tab).trim();
                    String say = line.substring(tab + 1).trim();
                    if (!key.isEmpty() && !say.isEmpty()) out.add(new Entry(key, say));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "cannot read dictionary", e);
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    /** Writes user entries, expanding each key to its case forms. */
    public static void write(Context c, List<Entry> entries) {
        File f = file(c);
        OutputStreamWriter w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f, false), LATIN1);
            for (Entry e : entries) {
                for (String form : EqText.caseForms(e.key)) {
                    w.write(form);
                    w.write('\t');
                    w.write(e.say);
                    w.write('\n');
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "cannot write dictionary", e);
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Exception ignored) {
                }
            }
        }
        EqPrefs.bumpDictRev(c);
    }

    /** Loads the file into volume 0 of the instance. Answers eciDictNoError
     *  (0) on success, or a negative sentinel when refused before the call:
     *  -10 no file (nothing to load -- not an error), -11 over the cap. */
    public static int loadInto(Context c, long handle) {
        File f = file(c);
        if (!f.exists()) return -10;
        if (f.length() > MAX_BYTES) {
            Log.e(TAG, "dictionary over cap (" + f.length() + " bytes), refusing");
            return -11;
        }
        try {
            return EloQuickEngine.nativeDictLoad(handle, 0, f.getAbsolutePath());
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "native lib missing", e);
            return -12;
        }
    }
}
