package com.eloquick.tts

import android.content.Context

/**
 * Emoji -> spoken description, sourced from
 * [emojibase](https://github.com/milesj/emojibase) (MIT), itself built from
 * Unicode CLDR's own short names - the same source real screen readers draw
 * their emoji announcements from. Bundled as a plain `emoji<TAB>description`
 * asset (`emoji_descriptions.tsv`, ~1949 entries, ~41KB): every standard
 * emoji, including the multi-codepoint sequences (flags, ZWJ families,
 * skin-tone-modified gestures) that a naive single-codepoint lookup would
 * mangle - "flag: United States" rather than two separate "regional
 * indicator" letters, "family: man, woman, boy" rather than three unrelated
 * announcements.
 *
 * This matters for more than politeness: almost every emoji sits well
 * outside Windows-1252 (see [encodeForEngine]), so without this, a message
 * full of emoji would reach the engine as a wall of '?' - not just
 * unhelpful but actively wrong for a screen-reader engine, whose whole
 * point is that what's heard reflects what's actually there.
 */
class EmojiDescriptions private constructor(entries: List<Pair<String, String>>) {
    private val lookup: Map<String, String> = entries.toMap()
    private val maxLength: Int = entries.maxOfOrNull { it.first.length } ?: 0

    /**
     * Replaces every recognised emoji in [text] with " <description> ",
     * longest sequence first at each position so a multi-codepoint emoji
     * matches as the one thing it is rather than as several smaller, less
     * meaningful pieces (a national flag as "flag: Japan", not "regional
     * indicator J" followed by "regional indicator P"). An emoji-like
     * character with no dictionary entry (new/unassigned codepoints) is left
     * untouched, same as any other character this app doesn't recognise.
     */
    fun describe(text: String): String {
        if (text.isEmpty() || maxLength == 0) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            var matched = false
            val limit = minOf(maxLength, text.length - i)
            for (len in limit downTo 1) {
                val label = lookup[text.substring(i, i + len)]
                if (label != null) {
                    out.append(' ').append(label).append(' ')
                    i += len
                    matched = true
                    break
                }
            }
            if (!matched) {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    companion object {
        @Volatile private var instance: EmojiDescriptions? = null

        /** Loads and caches the dictionary from assets on first use - a
         * one-time cost (~1949 lines), never repeated across the process's
         * remaining synthesis calls. */
        fun get(context: Context): EmojiDescriptions {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val entries = context.applicationContext.assets
                    .open("emoji_descriptions.tsv")
                    .bufferedReader(Charsets.UTF_8)
                    .useLines { lines ->
                        lines.mapNotNull { line ->
                            val tab = line.indexOf('\t')
                            if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
                        }.toList()
                    }
                val created = EmojiDescriptions(entries)
                instance = created
                return created
            }
        }
    }
}
