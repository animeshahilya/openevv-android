#!/usr/bin/env python3
"""Build the EloQuick debug APK (no Gradle: aapt2 + d8 + apksigner directly).

Bundles build/android/<abi>/libeloquick.so (which already contains the
android/eloquick_jni.c bridge) with the Java UI + self-test in android/app.

  py tools/build_debug_apk.py [--abi arm64-v8a] [--install] [--launch]

Needs: Android SDK (platforms + build-tools), JDK (javac/keytool), adb for
--install. The NDK .so must already be built, e.g.
  py tools/build_android.py --abi arm64-v8a --debug
"""

import argparse
import glob
import os
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "android", "app")
MIN_SDK = 26


def die(msg):
    sys.exit(f"build_debug_apk: error: {msg}")


def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        die(f"{' '.join(cmd)}\n{r.stdout}\n{r.stderr}")
    return r


def find_sdk():
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(env)
        if v and os.path.isdir(v):
            return v
    home = os.path.expanduser("~")
    for c in (os.path.join(home, "AppData", "Local", "Android", "Sdk"),
              os.path.join(home, "Android", "Sdk"),
              os.path.join(home, "Library", "Android", "sdk"),
              "/opt/android-sdk", "/usr/lib/android-sdk"):
        if os.path.isdir(c):
            return c
    die("Android SDK not found (set ANDROID_HOME)")


def pick_build_tools(sdk):
    bt = os.path.join(sdk, "build-tools")
    if not os.path.isdir(bt):
        die("no build-tools in SDK")
    vers = sorted(os.listdir(bt), reverse=True)
    for v in vers:
        d = os.path.join(bt, v)
        if os.path.exists(os.path.join(d, "aapt2.exe")) or \
           os.path.exists(os.path.join(d, "aapt2")):
            return d
    die("aapt2 not found in any build-tools")


def tool(d, name):
    for cand in (name + ".exe", name + ".bat", name):
        p = os.path.join(d, cand)
        if os.path.exists(p):
            return p
    die(f"{name} not found in {d}")


def pick_platform(sdk):
    plats = os.path.join(sdk, "platforms")
    vers = []
    for d in os.listdir(plats):
        if d.startswith("android-"):
            try:
                vers.append(int(d.split("-")[1].split(".")[0]))
            except ValueError:
                pass
    if not vers:
        die("no android platforms in SDK")
    v = max(vers)
    jar = os.path.join(plats, f"android-{v}", "android.jar")
    if not os.path.exists(jar):
        # e.g. android-37.0 directory for level 37
        hits = glob.glob(os.path.join(plats, "android-*", "android.jar"))
        if not hits:
            die("android.jar missing")
        jar = sorted(hits)[-1]
        v = int([p for p in jar.split(os.sep) if p.startswith("android-")][0]
                .split("-")[1].split(".")[0])
    return v, jar


def adb_path():
    return os.environ.get("ADB") or shutil.which("adb") or \
        os.path.join(find_sdk(), "platform-tools", "adb")


def main():
    ap = argparse.ArgumentParser(description="Build EloQuick debug APK.")
    ap.add_argument("--abi", default="arm64-v8a")
    ap.add_argument("--so", default=None, help="libeloquick.so override")
    ap.add_argument("--install", action="store_true")
    ap.add_argument("--launch", action="store_true")
    ap.add_argument("--serial", default=None)
    args = ap.parse_args()

    sdk = find_sdk()
    btdir = pick_build_tools(sdk)
    level, android_jar = pick_platform(sdk)
    aapt2, d8, zipalign = tool(btdir, "aapt2"), tool(btdir, "d8"), tool(btdir, "zipalign")
    apksigner = tool(btdir, "apksigner")
    keytool = shutil.which("keytool")
    if not keytool:
        die("keytool (JDK) not on PATH")

    soname = args.so or os.path.join(ROOT, "build", "android", args.abi, "libeloquick.so")
    if not os.path.exists(soname):
        die(f"missing {soname}: build it first "
            f"(py tools/build_android.py --abi {args.abi} --debug)")
    print(f"native lib: {soname} ({os.path.getsize(soname):,} bytes)")

    work = os.path.join(ROOT, "build", "debug-apk")
    cls = os.path.join(work, "classes")
    dex = os.path.join(work, "dex")
    shutil.rmtree(cls, ignore_errors=True)
    shutil.rmtree(dex, ignore_errors=True)
    os.makedirs(cls, exist_ok=True)
    os.makedirs(dex, exist_ok=True)  # d8 requires an existing output dir

    javas = glob.glob(os.path.join(APP, "java", "**", "*.java"), recursive=True)
    if not javas:
        die("no java sources under android/app/java")
    javac = shutil.which("javac")
    if not javac:
        die("javac not on PATH")
    print(f"javac ({len(javas)} files, targetSdk {level})...")
    run([javac, "--release", "8", "-nowarn",
         "-cp", android_jar, "-d", cls] + javas)

    print("d8...")
    run([d8, "--min-api", str(MIN_SDK), "--lib", android_jar,
         "--output", dex] + glob.glob(os.path.join(cls, "**", "*.class"), recursive=True))

    print("aapt2 link...")
    unaligned = os.path.join(work, "unaligned.apk")
    if os.path.exists(unaligned):
        os.remove(unaligned)
    link_cmd = [aapt2, "link", "-o", unaligned, "-I", android_jar,
                "--manifest", os.path.join(APP, "AndroidManifest.xml"),
                "--min-sdk-version", str(MIN_SDK),
                "--target-sdk-version", str(level),
                "--version-code", "1", "--version-name", "1.0-debug"]
    res_dir = os.path.join(APP, "res")
    if os.path.isdir(res_dir):
        res_zip = os.path.join(work, "res.zip")
        if os.path.exists(res_zip):
            os.remove(res_zip)
        run([aapt2, "compile", "--dir", res_dir, "-o", res_zip])
        link_cmd += ["-R", res_zip]
    run(link_cmd)

    dex_files = glob.glob(os.path.join(dex, "**", "*.dex"), recursive=True)
    if not dex_files:
        die("d8 produced no dex")
    print(f"packing ({len(dex_files)} dex + {args.abi}/libeloquick.so)...")
    with zipfile.ZipFile(unaligned, "a", zipfile.ZIP_DEFLATED) as z:
        for i, d in enumerate(sorted(dex_files)):
            name = "classes.dex" if i == 0 else f"classes{i + 1}.dex"
            z.write(d, name)
        z.write(soname, f"lib/{args.abi}/libeloquick.so")

    aligned = os.path.join(work, "aligned.apk")
    print("zipalign...")
    run([zipalign, "-f", "4", unaligned, aligned])

    ks = os.path.join(work, "debug.keystore")
    if not os.path.exists(ks):
        print("generating debug keystore...")
        run([keytool, "-genkeypair", "-keystore", ks, "-alias", "androiddebugkey",
             "-storepass", "android", "-keypass", "android", "-keyalg", "RSA",
             "-keysize", "2048", "-validity", "10950",
             "-dname", "CN=Android Debug, OU=EloQuick, O=Debug"])

    apk = os.path.join(ROOT, "build", f"eloquick-debug-{args.abi}.apk")
    print("apksigner...")
    run([apksigner, "sign", "--ks", ks, "--ks-pass", "pass:android",
         "--key-pass", "pass:android", "--out", apk, aligned])
    run([apksigner, "verify", "--print-certs", apk])
    print(f"APK: {apk} ({os.path.getsize(apk):,} bytes)")

    if args.install or args.launch:
        adb = adb_path()
        pre = [adb]
        if args.serial:
            pre += ["-s", args.serial]
        print("installing...")
        run(pre + ["install", "-r", "-t", apk])
        if args.launch:
            run(pre + ["shell", "am", "start",
                       "-n", "com.eloquick.debug/com.eloquick.debug.MainActivity"])
    print("done.")
    print("self-test on device:")
    print("  adb shell am start -n com.eloquick.debug/com.eloquick.debug.MainActivity --ez selftest true")
    print("  adb logcat -s EQTEST")


if __name__ == "__main__":
    main()
