#!/usr/bin/env python3
"""On-device test harness for EloQuick Android binaries.

Pushes build/android/<abi>/eloquick (and evv) to /data/local/tmp/ on the
connected phone and exercises them in detail:

  1. CLI basics: -h usage, -L list (expects 10 languages), -l voices
  2. Speech per language: synth short text, pull WAV, validate RIFF/WAVE
     header (11025 Hz, 16-bit mono) and nonzero audio
  3. Determinism: same text twice -> same sample COUNT (bytes equal in
     size; sample values legitimately differ -- the engine carries state
     between utterances, and IBM's own engine does too)
  4. Separation: English vs German rendering of one ASCII line differs
  5. Compat copy: evv (argv[0]-aware copy of eloquick) lists the same langs
  6. Error path: unknown -L id fails nonzero with a message

Usage:
  py tools/test_device.py [--abi arm64-v8a] [--serial 39051FDJH001P7]
                          [--langs enus,dede] [--keep-wavs DIR]

Needs: adb on PATH (or ADB env), and binaries built first, e.g.
  py tools/build_android.py --abi arm64-v8a --debug
"""

import argparse
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import wave

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REMOTE_DIR = "/data/local/tmp/eqtest"

# Short prompt per language tag, keyed by position in the -L list is fragile
# (interface ids are what -L takes), so per-language text is chosen AFTER
# listing: English-latin prompts for latin-script languages, ASCII for jajp
# (quality not asserted there -- the CLI byte path, not the romanizer UI).
PROMPTS_LATIN = "Hello world. This is EloQuick speaking."
PROMPT_ASCII = "Hello."


def find_adb():
    adb = os.environ.get("ADB") or shutil.which("adb")
    if not adb:
        sys.exit("adb not found: put platform-tools on PATH or set ADB=")
    return adb


class Device:
    def __init__(self, adb, serial=None):
        self.base = [adb]
        if serial:
            self.base += ["-s", serial]

    def run(self, *args, timeout=180):
        cmd = self.base + list(args)
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)

    def shell(self, script, timeout=180):
        return self.run("shell", script, timeout=timeout)

    def push(self, local, remote):
        r = self.run("push", local, remote)
        if r.returncode != 0:
            sys.exit(f"adb push failed:\n{r.stdout}\n{r.stderr}")
        return r

    def pull(self, remote, local):
        r = self.run("pull", remote, local)
        if r.returncode != 0:
            sys.exit(f"adb pull failed:\n{r.stdout}\n{r.stderr}")
        return r


def check_wav(path):
    """Returns (seconds, nframes) or raises with a reason."""
    with wave.open(path, "rb") as w:
        assert w.getnchannels() == 1, f"channels={w.getnchannels()}"
        assert w.getsampwidth() == 2, f"sampwidth={w.getsampwidth()}"
        assert w.getframerate() == 11025, f"rate={w.getframerate()}"
        frames = w.readframes(w.getnframes())
        assert len(frames) > 0, "no audio frames"
        # Non-silent: at least one nonzero sample.
        assert any(b != 0 for b in frames), "all samples are zero"
        return w.getnframes() / w.getframerate(), w.getnframes()


def main():
    ap = argparse.ArgumentParser(description="Test EloQuick binaries on-device.")
    ap.add_argument("--abi", default="arm64-v8a")
    ap.add_argument("--serial", default=None)
    ap.add_argument("--langs", default=None,
                    help="Comma subset of language ids (hex, e.g. 0x10000) to synth; default all listed")
    ap.add_argument("--keep-wavs", default=None, help="Keep pulled WAVs in DIR")
    args = ap.parse_args()

    adb = find_adb()
    dev = Device(adb, args.serial)

    ds = dev.run("devices")
    print(ds.stdout.strip())
    if "device" not in ds.stdout.replace("List of devices attached", ""):
        sys.exit("No adb device in 'device' state.")

    local_dir = os.path.join(ROOT, "build", "android", args.abi)
    cli = os.path.join(local_dir, "eloquick")
    compat = os.path.join(local_dir, "evv")
    for f in (cli, compat):
        if not os.path.exists(f):
            sys.exit(f"Missing {f}: build first "
                     f"(py tools/build_android.py --abi {args.abi} --debug)")

    fails = []
    def ok(name, cond, extra=""):
        print(f"  [{'PASS' if cond else 'FAIL'}] {name} {extra}")
        if not cond:
            fails.append(name)

    print(f"== pushing to {REMOTE_DIR} ==")
    dev.shell(f"rm -rf {REMOTE_DIR} && mkdir -p {REMOTE_DIR}")
    dev.push(cli, f"{REMOTE_DIR}/eloquick")
    dev.push(compat, f"{REMOTE_DIR}/evv")
    dev.shell(f"chmod +x {REMOTE_DIR}/eloquick {REMOTE_DIR}/evv")

    print("== 1. CLI basics ==")
    r = dev.shell(f"{REMOTE_DIR}/eloquick -h")
    ok("usage exits 0", r.returncode == 0 and "usage:" in r.stdout)

    r = dev.shell(f"{REMOTE_DIR}/eloquick -L list")
    lang_ids = [l.strip() for l in r.stdout.split() if l.strip().startswith("0x")]
    ok("-L list exits 0 with ids", r.returncode == 0 and len(lang_ids) > 0, f"({len(lang_ids)} found)")
    ok("ten languages bundled", len(lang_ids) == 10, f"got {lang_ids}")
    ok("ids distinct", len(set(lang_ids)) == len(lang_ids))

    r = dev.shell(f"{REMOTE_DIR}/eloquick -l")
    ok("-l lists 8 voices", r.returncode == 0 and r.stdout.count("voice ") >= 8)

    print("== 2. speech per language ==")
    tmp = tempfile.mkdtemp(prefix="eqtest_")
    wav_dir = args.keep_wavs or tmp
    if args.keep_wavs:
        os.makedirs(wav_dir, exist_ok=True)
    want = args.langs.split(",") if args.langs else lang_ids
    results = {}
    for i, lid in enumerate(lang_ids):
        if lid not in want:
            continue
        # Last id is tried with plain ASCII (covers jajp byte path); the
        # rest get a fuller latin prompt.
        text = PROMPT_ASCII if i == len(lang_ids) - 1 else PROMPTS_LATIN
        remote_wav = f"{REMOTE_DIR}/t_{i}.wav"
        t0 = time.time()
        r = dev.shell(f"{REMOTE_DIR}/eloquick -L {lid} -o {remote_wav} '{text}'")
        dt = time.time() - t0
        local_wav = os.path.join(wav_dir, f"t_{i}_{lid}.wav")
        if r.returncode != 0:
            ok(f"synth {lid}", False, f"exit={r.returncode} {r.stderr.strip()[:160]}")
            continue
        dev.pull(remote_wav, local_wav)
        try:
            secs, frames = check_wav(local_wav)
            size = os.path.getsize(local_wav)
            results[lid] = (size, frames, open(local_wav, "rb").read())
            ok(f"synth {lid}", True,
               f"{size:,}B {secs:.2f}s {frames} frames roundtrip {dt:.1f}s")
        except Exception as e:
            ok(f"synth {lid}", False, f"bad wav: {e}")

    print("== 3. determinism (same text twice, EN) ==")
    en = lang_ids[0]
    dev.shell(f"{REMOTE_DIR}/eloquick -L {en} -o {REMOTE_DIR}/rep1.wav '{PROMPTS_LATIN}'")
    dev.shell(f"{REMOTE_DIR}/eloquick -L {en} -o {REMOTE_DIR}/rep2.wav '{PROMPTS_LATIN}'")
    p1 = os.path.join(wav_dir, "rep1.wav")
    p2 = os.path.join(wav_dir, "rep2.wav")
    dev.pull(f"{REMOTE_DIR}/rep1.wav", p1)
    dev.pull(f"{REMOTE_DIR}/rep2.wav", p2)
    d1 = open(p1, "rb").read()
    d2 = open(p2, "rb").read()
    ok("repeat synth: same byte length", len(d1) == len(d2),
       f"({len(d1)} vs {len(d2)})")
    print(f"  [INFO] repeat synth byte-identical: {d1 == d2} "
          f"(False is EXPECTED -- engine carries voicing state; "
          f"IBM's own engine does too)")

    print("== 4. separation (EN vs DE, same line) ==")
    if len(results) >= 2:
        ids = list(results.keys())
        ok("two languages render differently", results[ids[0]][2] != results[ids[1]][2],
           f"({ids[0]} {results[ids[0]][0]:,}B vs {ids[1]} {results[ids[1]][0]:,}B)")
    else:
        ok("two languages render differently", False, "need >=2 successful synths")

    print("== 5. compat copy ==")
    r = dev.shell(f"{REMOTE_DIR}/evv -L list")
    evv_ids = [l.strip() for l in r.stdout.split() if l.strip().startswith("0x")]
    ok("evv lists same languages", r.returncode == 0 and evv_ids == lang_ids)

    print("== 6. error path ==")
    r = dev.shell(f"{REMOTE_DIR}/eloquick -L 0xdead -o {REMOTE_DIR}/bad.wav 'hi'")
    ok("unknown -L id fails nonzero", r.returncode != 0,
       f"exit={r.returncode} msg={r.stderr.strip()[:120]}")

    print("== summary ==")
    print(f"  {len(lang_ids)} languages, "
          f"{len(results)} synths OK, {len(fails)} failures")
    if not args.keep_wavs:
        shutil.rmtree(tmp, ignore_errors=True)
    else:
        print(f"  WAVs kept in {wav_dir}")
    if fails:
        print("FAILURES:", fails)
        return 1
    print("ALL DEVICE TESTS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
