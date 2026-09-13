package com.eloquick.debug;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import com.eloquick.tts.EloQuickEngine;

/** One blocking utterance: synth via JNI, optionally play via AudioTrack.
 * Everything here runs off the UI thread; callers post results back. */
public final class Tts {
    private static final String TAG = "EloQuick";
    public static final int SAMPLE_RATE = 11025;

    public static final class Result {
        public final boolean ok;
        public final int samples;
        public final long synthMs;
        public final long playMs;
        public final String error;
        Result(boolean ok, int samples, long synthMs, long playMs, String error) {
            this.ok = ok;
            this.samples = samples;
            this.synthMs = synthMs;
            this.playMs = playMs;
            this.error = error;
        }
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

    private static String playPcm(short[] pcm) {
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            return "getMinBufferSize refused 11025Hz mono: " + minBuf;
        }
        AudioTrack track;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuf, pcm.length * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (Exception e) {
            return "AudioTrack build failed: " + e;
        }
        try {
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                return "AudioTrack not initialized";
            }
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
            // stop() drops whatever has not played yet, so wait for the
            // head to reach the last frame first (generous timeout: the
            // device may route slowly on first use after boot).
            long deadline = System.currentTimeMillis()
                    + (pcm.length * 1000L / SAMPLE_RATE) + 8000;
            while (track.getPlaybackHeadPosition() < pcm.length
                    && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "playback wait interrupted";
                }
            }
            track.stop();
            return null;
        } catch (Exception e) {
            return "playback failed: " + e;
        } finally {
            track.release();
        }
    }
}
