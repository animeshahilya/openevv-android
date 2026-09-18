package com.eloquick.tts

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Classpath tab-file loading both bundled resource readers share:
 * [EmojiSpeech]'s `emoji_names.txt` and [PronunciationDictionary]'s bundled
 * word lists were the same anchor-class + `getResourceAsStream` +
 * `BufferedReader`/UTF-8 + split-on-`\t` shape written twice, drifting
 * apart one hardening fix at a time. A plain JVM resource (not an Android
 * asset), so everything built on this stays Context-free and unit-testable.
 *
 * Returns one raw line per entry (line terminators stripped, `\r` included,
 * so CRLF-saved resources can't leak a carriage return into a parsed
 * field); empty when the resource is missing, same as both callers'
 * previous "no data" behavior.
 */
internal fun classpathLines(anchor: Class<*>, resourceName: String): List<String> =
    anchor.getResourceAsStream(resourceName)?.use { stream ->
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readLines()
    } ?: emptyList()

/**
 * Raw-bytes counterpart to [classpathLines], for a bundled resource that
 * isn't UTF-8 (the bundled community dictionary is Windows-1252 - see
 * [decodeImportedDictionaryBytes]) and so needs its own encoding decision
 * made by the caller rather than this file's own UTF-8 default. Null when
 * the resource is missing, same "no data" shape as [classpathLines]'s empty
 * list.
 */
internal fun classpathBytes(anchor: Class<*>, resourceName: String): ByteArray? =
    anchor.getResourceAsStream(resourceName)?.use { it.readBytes() }
