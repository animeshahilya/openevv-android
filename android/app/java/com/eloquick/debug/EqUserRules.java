package com.eloquick.debug;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Java-side pre-synthesis text rules ("My Words"), applied in the TTS
 * service before the text reaches the engine.
 *
 * This complements the engine-side dictionary (EqDictionary, loaded into
 * the instance per language): rules here are regex-capable, case/whole-word
 * aware, and language-scoped, matching NVDA speech-dictionary semantics.
 * Engine file loading stays untouched; these run first, in Java.
 *
 * Persisted as JSON in device-protected storage so the directBootAware
 * service reads them before first unlock. Saves are atomic (tmp + rename)
 * off the caller thread.
 */
public final class EqUserRules {
    private static final String TAG = "EQTTS";
    private static final String FILE_NAME = "user-rules.json";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class Rule {
        public final String pattern;
        public final String replacement;
        public final boolean caseSensitive;
        public final boolean isRegex;
        public final boolean wholeWord;
        /** BCP-47-ish tag ("eng", "eng-usa") or "" for every language. */
        public final String language;
        private final Pattern compiled;

        public Rule(String pattern, String replacement, boolean caseSensitive,
                    boolean isRegex, boolean wholeWord, String language) {
            this.pattern = pattern == null ? "" : pattern;
            this.replacement = replacement == null ? "" : replacement;
            this.caseSensitive = caseSensitive;
            this.isRegex = isRegex;
            this.wholeWord = wholeWord;
            this.language = language == null ? ""
                    : language.trim().toLowerCase(java.util.Locale.ROOT);
            this.compiled = compile(this.pattern, caseSensitive, isRegex, wholeWord);
        }

        /** A rule scoped to a base language ("eng") also matches its
         *  variants ("eng-usa"); an unscoped rule matches everything. */
        public boolean appliesTo(String requestLanguage) {
            if (language.isEmpty()) return true;
            if (requestLanguage == null) return false;
            String r = requestLanguage.trim().toLowerCase(java.util.Locale.ROOT);
            return r.equals(language) || r.startsWith(language + "-");
        }

        public String apply(String text) {
            if (text == null || text.isEmpty() || compiled == null) return text;
            try {
                return compiled.matcher(text).replaceAll(replacement);
            } catch (Throwable t) {
                return text;
            }
        }

        private static Pattern compile(String pattern, boolean caseSensitive,
                                       boolean isRegex, boolean wholeWord) {
            if (pattern.isEmpty()) return null;
            try {
                int flags = 0;
                if (!caseSensitive) flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                String re;
                if (isRegex) {
                    re = pattern;
                } else if (wholeWord) {
                    re = "\\b" + Pattern.quote(pattern) + "\\b";
                } else {
                    re = Pattern.quote(pattern);
                }
                return Pattern.compile(re, flags);
            } catch (PatternSyntaxException e) {
                return null;
            }
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("pattern", pattern);
            o.put("replacement", replacement);
            o.put("caseSensitive", caseSensitive);
            o.put("isRegex", isRegex);
            o.put("wholeWord", wholeWord);
            o.put("language", language);
            return o;
        }

        static Rule fromJson(JSONObject o) {
            if (o == null) return null;
            Rule r = new Rule(o.optString("pattern", ""), o.optString("replacement", ""),
                    o.optBoolean("caseSensitive", false), o.optBoolean("isRegex", false),
                    o.optBoolean("wholeWord", true), o.optString("language", ""));
            return r.pattern.isEmpty() ? null : r;
        }
    }

    private EqUserRules() {}

    private static File file(Context c) {
        Context storage = c;
        try {
            storage = c.createDeviceProtectedStorageContext();
        } catch (Exception ignored) {
        }
        return new File(storage.getFilesDir(), FILE_NAME);
    }

    public static List<Rule> read(Context c) {
        List<Rule> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), UTF8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                Rule rule = Rule.fromJson(arr.optJSONObject(i));
                if (rule != null) out.add(rule);
            }
        } catch (Exception e) {
            Log.e(TAG, "cannot read user rules", e);
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

    /** Atomic write (tmp + rename); call off the binder thread. */
    public static void write(Context c, List<Rule> rules) {
        File f = file(c);
        File tmp = new File(f.getParent(), FILE_NAME + ".tmp");
        OutputStreamWriter w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(tmp, false), UTF8);
            JSONArray arr = new JSONArray();
            for (Rule r : rules) {
                if (r != null && !r.pattern.isEmpty()) arr.put(r.toJson());
            }
            w.write(arr.toString(2));
            w.flush();
            w.close();
            w = null;
            if (!tmp.renameTo(f) && !(f.delete() && tmp.renameTo(f))) {
                Log.w(TAG, "could not atomically replace user rules");
            }
        } catch (Exception e) {
            Log.e(TAG, "cannot write user rules", e);
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Applies every in-scope rule, in order. Null-safe no-op when empty. */
    public static String apply(Context c, String text, String languageTag) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        List<Rule> rules = read(c);
        if (rules.isEmpty()) return text;
        String out = text;
        for (Rule r : rules) {
            if (r.appliesTo(languageTag)) out = r.apply(out);
        }
        return out;
    }

    public static List<Rule> unmodifiable(List<Rule> rules) {
        return Collections.unmodifiableList(new ArrayList<>(rules));
    }
}
