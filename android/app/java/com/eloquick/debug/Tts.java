package com.eloquick.debug;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import com.eloquick.tts.EloQuickEngine;

/** Blocking utterances off the UI thread. Two shapes:
 * - speak: whole-utterance synth, then play. Simple; unstoppable.
 * - speakStream: streaming synth with a StopFlag the UI's Stop button
 *   trips. TalkBack-grade interruptibility for the demo screen.
 * Callers post results back themselves. */
public final class Tts {
    private static final String TAG = "EloQuick";
    public static final int SAMPLE_RATE = 11025;

    public static final class Result {
        public final boolean ok;
        public final boolean stopped;
        public final int samples;
        public final long synthMs;
        public final long playMs;
        public final String error;
        Result(boolean ok, int samples, long synthMs, long playMs, String error) {
            this(ok, false, samples, synthMs, playMs, error);
        }
        Result(boolean ok, boolean stopped, int samples, long synthMs, long playMs, String error) {
            this.ok = ok;
            this.stopped = stopped;
            this.samples = samples;
            this.synthMs = synthMs;
            this.playMs = playMs;
            this.error = error;
        }
    }

    /** Tripped by the Stop button; checked between stream reads. */
    public static final class StopFlag {
        volatile boolean stop = false;
    }

    private Tts() {}

    public static Result speak(int language, int voice, String text, boolean play) {
        long h = EloQuickEngine.nativeCreate(language);
        if (h == 0) {
            return new Result(false, 0, 0, 0, "nativeCreate refused language 0x"
                    + Integer.toHexString(language));
        }
        try {
            if (voice >= 1 && voice <= 8) {
                EloQuickEngine.nativeCopyVoice(h, voice, 0);
            }
            long t0 = System.currentTimeMillis();
            short[] pcm = EloQuickEngine.nativeSynth(h, text);
            long synthMs = System.currentTimeMillis() - t0;
            if (pcm == null || pcm.length == 0) {
                return new Result(false, 0, synthMs, 0, "synth returned no samples");
            }
            long playMs = 0;
            if (play) {
                t0 = System.currentTimeMillis();
                String err = playPcm(pcm);
                playMs = System.currentTimeMillis() - t0;
                if (err != null) {
                    return new Result(false, pcm.length, synthMs, playMs, err);
                }
            }
            return new Result(true, pcm.length, synthMs, playMs, null);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "native link missing", e);
            return new Result(false, 0, 0, 0, "UnsatisfiedLinkError: " + e.getMessage());
        } finally {
            EloQuickEngine.nativeDestroy(h);
        }
    }

    /** Streaming synth + streaming play, abortable via flag. */
    public static Result speakStream(int language, int voice, String text, boolean play,
                                     StopFlag flag) {
        long st = 0;
        AudioTrack track = null;
        try {
            st = EloQuickEngine.nativeStreamCreate(language);
            if (st == 0) {
                return new Result(false, 0, 0, 0, "stream create refused");
            }
            if (voice >= 1 && voice <= 8) {
                EloQuickEngine.nativeStreamCopyVoice(st, voice, 0);
            }
            if (!EloQuickEngine.nativeStreamSpeak(st, text)) {
                return new Result(false, 0, 0, 0, "stream speak refused");
            }
            long t0 = System.currentTimeMillis();
            byte[] buf = new byte[8192];
            int totalBytes = 0;
            boolean done = false;
            boolean wasStopped = false;
            if (play) {
                track = openTrack(SAMPLE_RATE, buf.length * 2);
                if (track == null) {
                    return new Result(false, 0, 0, 0, "AudioTrack refused 11025Hz mono");
                }
                track.play();
            }
            while (!done) {
                if (flag != null && flag.stop) {
                    Log.i(TAG, "stream stop observed after " + totalBytes + " bytes");
                    EloQuickEngine.nativeStreamStop(st);
                    wasStopped = true;
                    break;
                }
                int n = EloQuickEngine.nativeStreamRead(st, buf, buf.length);
                if (n < 0) {
                    wasStopped = true;
                    break;
                }
                if (n == 0) {
                    done = true;
                    break;
                }
                totalBytes += n;
                if (track != null) {
                    int off = 0;
                    while (off < n) {
                        int w = track.write(buf, off, n - off);
                        if (w < 0) {
                            return new Result(false, false, totalBytes / 2,
                                    System.currentTimeMillis() - t0, 0,
                                    "AudioTrack.write error " + w);
                        }
                        if (w == 0) break;
                        off += w;
                    }
                }
            }
            long ms = System.currentTimeMillis() - t0;
            if (track != null) {
                if (!wasStopped) waitDrained(track, totalBytes / 2, SAMPLE_RATE);
                try {
                    track.stop();
                } catch (Exception ignored) {
                }
            }
            return new Result(!wasStopped, wasStopped, totalBytes / 2, ms, ms, null);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "native link missing", e);
            return new Result(false, 0, 0, 0, "UnsatisfiedLinkError: " + e.getMessage());
        } finally {
            if (track != null) track.release();
            if (st != 0) {
                try {
                    EloQuickEngine.nativeStreamDestroy(st);
                } catch (UnsatisfiedLinkError ignored) {
                }
            }
        }
    }

    private static AudioTrack openTrack(int rateHz, int minBytes) {
        int minBuf = AudioTrack.getMinBufferSize(rateHz,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) return null;
        try {
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rateHz)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuf, minBytes))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            return track.getState() == AudioTrack.STATE_INITIALIZED ? track : null;
        } catch (Exception e) {
            Log.e(TAG, "AudioTrack build failed", e);
            return null;
        }
    }

    private static void waitDrained(AudioTrack track, int samples, int rateHz) {
        // stop() drops whatever has not played yet, so wait for the head
        // to reach the last frame first.
        long deadline = System.currentTimeMillis()
                + (samples * 1000L / rateHz) + 8000;
        while (track.getPlaybackHeadPosition() < samples
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static String playPcm(short[] pcm) {
        AudioTrack track = openTrack(SAMPLE_RATE, pcm.length * 2);
        if (track == null) {
            return "AudioTrack refused 11025Hz mono";
        }
        try {
            track.play();
            int off = 0;
            while (off < pcm.length) {
                int n = track.write(pcm, off, pcm.length - off);
                if (n < 0) {
                    return "AudioTrack.write error " + n;
                }
                if (n == 0) {
                    break;
                }
                off += n;
            }
            waitDrained(track, pcm.length, SAMPLE_RATE);
            track.stop();
            return null;
        } catch (Exception e) {
            return "playback failed: " + e;
        } finally {
            track.release();
        }
    }
}
