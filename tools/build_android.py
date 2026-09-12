#!/usr/bin/env python3
"""Build EloQuick (ultra-fast Eloquence / OpenEVV engine tailored for Android).

Compiles with Clang from the Android NDK using -O3, -fvisibility=hidden,
-ffunction-sections, -fdata-sections, -fno-math-errno, -ffp-contract=fast,
and links with --gc-sections, symbol stripping, and 16KB page alignment
(-Wl,-z,max-page-size=16384) compliant with Android 15+.

Supports target ABIs:
  - arm64-v8a (default)
  - armeabi-v7a
  - x86_64
  - x86

Produces:
  - build/android/<abi>/eloquick      (standalone CLI executable)
  - build/android/<abi>/libeloquick.so (shared library exposing standard ECI API)
  - build/android/<abi>/evv          (backwards compatibility alias)
  - build/android/<abi>/libopenevv.so (backwards compatibility alias)
"""

import argparse
import hashlib
import os
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)

LANGS = ["enus", "dede", "engb", "eses", "esus", "frca", "frfr", "itit", "plpl"]

ABI_CONFIGS = {
    "arm64-v8a": {
        "triple": "aarch64-linux-android",
        "api": 26,
        "cflags": ["-march=armv8-a"],
    },
    "armeabi-v7a": {
        "triple": "armv7a-linux-androideabi",
        "api": 26,
        "cflags": ["-march=armv7-a", "-mfloat-abi=softfp", "-mfpu=neon"],
    },
    "x86_64": {
        "triple": "x86_64-linux-android",
        "api": 26,
        "cflags": ["-march=x86-64"],
    },
    "x86": {
        "triple": "i686-linux-android",
        "api": 26,
        "cflags": ["-march=i686"],
    },
}

def find_ndk_root():
    ndk = os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK_ROOT")
    if ndk and os.path.isdir(ndk):
        return ndk
    
    candidates = [
        r"C:\Users\alex\AppData\Local\Android\Sdk\ndk",
        os.path.expanduser("~/AppData/Local/Android/Sdk/ndk"),
        os.path.expanduser("~/Android/Sdk/ndk"),
        "/opt/android-sdk/ndk",
        "/usr/local/lib/android/sdk/ndk",
    ]
    for c in candidates:
        if os.path.isdir(c):
            versions = sorted(os.listdir(c), reverse=True)
            if versions:
                return os.path.join(c, versions[0])
    return None

def find_clang_binary(ndk_root, triple, api):
    llvm_bin = os.path.join(ndk_root, "toolchains", "llvm", "prebuilt")
    if not os.path.isdir(llvm_bin):
        return None
    prebuilt_hosts = os.listdir(llvm_bin)
    if not prebuilt_hosts:
        return None
    host_bin = os.path.join(llvm_bin, prebuilt_hosts[0], "bin")

    exe_suffix = ".cmd" if sys.platform == "win32" else ""
    target_clang = os.path.join(host_bin, f"{triple}{api}-clang{exe_suffix}")
    if os.path.exists(target_clang):
        return target_clang
    generic_clang = os.path.join(host_bin, f"clang{exe_suffix}")
    if os.path.exists(generic_clang):
        return generic_clang
    return None

def ensure_rules_generated():
    """Generate delta_rules_<lang>.{c,h} and delta_rules_shim_<lang>.c for each language."""
    print("Ensuring language rule bytecode tables are up-to-date...")
    for lang in LANGS:
        h_file = os.path.join(ROOT, "lang", lang, f"delta_rules_{lang}.h")
        c_file = os.path.join(ROOT, "lang", lang, f"delta_rules_{lang}.c")
        needs_gen = not (os.path.exists(h_file) and os.path.exists(c_file))
        if not needs_gen:
            with open(h_file, "r", encoding="utf-8", errors="ignore") as f:
                if "delta_rule_argmask" not in f.read():
                    needs_gen = True
        if needs_gen:
            print(f"  Generating rule tables for language: {lang}...")
            env = dict(os.environ, EVV_NOTATION_LANG=lang, PYTHONUTF8="1")
            res = subprocess.run(
                [sys.executable, "tools/rules/notation.py", "build"],
                cwd=ROOT,
                env=env,
                capture_output=True,
                text=True,
            )
            if res.returncode != 0:
                sys.exit(f"Rule generation failed for {lang}:\n{res.stdout}\n{res.stderr}")
    print("Rule tables ready.\n")

def build_abi(abi, ndk_root, debug=False):
    if abi not in ABI_CONFIGS:
        sys.exit(f"Unknown ABI: {abi}. Available: {list(ABI_CONFIGS.keys())}")
    
    cfg = ABI_CONFIGS[abi]
    clang = find_clang_binary(ndk_root, cfg["triple"], cfg["api"])
    if not clang:
        sys.exit(f"Could not find NDK Clang compiler for {abi} in {ndk_root}")
    
    mode_str = "DEBUG" if debug else "RELEASE (-O3)"
    print(f"=== Building EloQuick for Android ABI: {abi} [{mode_str}] ===")
    print(f"Compiler: {clang}")

    build_dir = os.path.join(ROOT, "build", "android", abi)
    obj_dir = os.path.join(build_dir, "obj_debug" if debug else "obj")
    os.makedirs(obj_dir, exist_ok=True)

    langs_c = os.path.join(build_dir, "delta_langs.c")
    with open(langs_c, "w", encoding="utf-8") as f:
        f.write('/* Generated for Android build */\n#include "delta_lang.h"\n\n')
        for l in LANGS:
            f.write(f'extern delta_language delta_lang_{l};\n')
            f.write(f'void delta_lang_bind_{l}(void);\n')
        f.write('\nconst delta_language *const delta_languages[] = {\n')
        for l in LANGS:
            f.write(f'    &delta_lang_{l},\n')
        f.write('    0,\n};\n\n')
        f.write('void delta_lang_bind_all(void)\n{\n')
        for l in LANGS:
            f.write(f'    delta_lang_bind_{l}();\n')
        f.write('}\n')

    src_files = []
    src_dirs = [ROOT]
    for root, dirs, files in os.walk(os.path.join(ROOT, "src")):
        src_dirs.append(root)
        for f in files:
            if f.endswith(".c") and f != "port_win32.c":
                src_files.append(os.path.join(root, f))

    lang_files = [langs_c]
    lang_dirs = []
    for l in LANGS:
        lang_dir = os.path.join(ROOT, "lang", l)
        lang_dirs.append(lang_dir)
        for f in os.listdir(lang_dir):
            if f.endswith(".c") and not f.startswith("delta_rules_c"):
                lang_files.append(os.path.join(lang_dir, f))

    include_dirs = sorted(set(src_dirs + lang_dirs + [build_dir, os.path.join(ROOT, "include")]))
    inc_flags = [f"-I{d}" for d in include_dirs]

    opt_cflags = [
        "-O0",
        "-g",
        "-DDEBUG=1",
    ] if debug else [
        "-O3",
        "-fno-math-errno",
        "-fno-trapping-math",
        "-ffp-contract=fast",
    ]

    common_cflags = [
        "-fomit-frame-pointer" if not debug else "-fno-omit-frame-pointer",
        "-DEVV_ARENA=1",
        "-DECI_BUILDING=1",
        "-ffunction-sections",
        "-fdata-sections",
        "-fvisibility=hidden",
        "-fPIC",
        "-w",
        "-Wno-implicit-function-declaration",
        "-Werror=int-conversion",
        "-Werror=incompatible-pointer-types",
    ] + opt_cflags + cfg["cflags"] + inc_flags

    def compile_source(src):
        rel = os.path.relpath(src, ROOT)
        h = hashlib.md5(rel.encode("utf-8")).hexdigest()[:8]
        base = os.path.splitext(os.path.basename(src))[0]
        obj = os.path.join(obj_dir, f"{base}_{h}.o")

        if os.path.exists(obj) and os.path.getmtime(obj) > os.path.getmtime(src):
            return obj

        cmd = [clang, "-c", src, "-o", obj] + common_cflags
        res = subprocess.run(cmd, capture_output=True, text=True)
        if res.returncode != 0:
            print(f"FAILED: {src}\n{res.stderr}", file=sys.stderr)
            return None
        return obj

    core_sources = src_files + lang_files
    print(f"Compiling {len(core_sources)} core sources [{'DEBUG' if debug else '-O3 release'} + DSP optimizations]...")
    with ThreadPoolExecutor(max_workers=os.cpu_count() or 8) as ex:
        core_objs = list(ex.map(compile_source, core_sources))

    if any(o is None for o in core_objs):
        sys.exit(f"Core compilation failed for ABI: {abi}")

    cli_src = os.path.join(ROOT, "cli", "evv.c")
    cli_obj = compile_source(cli_src)
    if not cli_obj:
        sys.exit(f"CLI compilation failed for ABI: {abi}")

    lib_src = os.path.join(ROOT, "lib", "eci_api.c")
    lib_obj = compile_source(lib_src)
    if not lib_obj:
        sys.exit(f"Shared library wrapper compilation failed for ABI: {abi}")

    linker_alignment_flags = [
        "-Wl,-z,max-page-size=16384",
        "-Wl,-z,common-page-size=16384",
    ]

    out_eloquick = os.path.join(build_dir, "eloquick")
    out_evv = os.path.join(build_dir, "evv")
    print(f"Linking executable {out_eloquick}...")
    cli_rsp = os.path.join(build_dir, "cli_objects.rsp")
    with open(cli_rsp, "w") as f:
        for obj in core_objs + [cli_obj]:
            f.write(obj.replace("\\", "/") + "\n")

    strip_flags = [] if debug else ["-Wl,-s"]

    link_cli_cmd = [
        clang,
        "-o", out_eloquick,
        "-pie",
        "-Wl,--gc-sections",
    ] + strip_flags + linker_alignment_flags + [
        "-lm",
        "-pthread",
        f"@{cli_rsp}",
    ]
    res = subprocess.run(link_cli_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        sys.exit(f"CLI linking failed for ABI {abi}:\n{res.stderr}")

    import shutil
    shutil.copy2(out_eloquick, out_evv)

    out_eloquick_so = os.path.join(build_dir, "libeloquick.so")
    out_openevv_so = os.path.join(build_dir, "libopenevv.so")
    print(f"Linking shared library {out_eloquick_so}...")
    so_rsp = os.path.join(build_dir, "so_objects.rsp")
    with open(so_rsp, "w") as f:
        for obj in core_objs + [lib_obj]:
            f.write(obj.replace("\\", "/") + "\n")

    link_so_cmd = [
        clang,
        "-o", out_eloquick_so,
        "-shared",
        "-Wl,-soname,libeloquick.so",
        "-Wl,--gc-sections",
    ] + strip_flags + linker_alignment_flags + [
        "-lm",
        "-pthread",
        f"@{so_rsp}",
    ]
    res = subprocess.run(link_so_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        sys.exit(f"Shared library linking failed for ABI {abi}:\n{res.stderr}")

    # Also link/copy libopenevv.so for backwards compatibility
    link_so_compat_cmd = [
        clang,
        "-o", out_openevv_so,
        "-shared",
        "-Wl,-soname,libopenevv.so",
        "-Wl,--gc-sections",
    ] + strip_flags + linker_alignment_flags + [
        "-lm",
        "-pthread",
        f"@{so_rsp}",
    ]
    subprocess.run(link_so_compat_cmd, capture_output=True, text=True)

    print(f"ABI {abi} Build Complete:")
    print(f"  CLI binary:     {out_eloquick} (also mirrored as {out_evv}) [{os.path.getsize(out_eloquick):,} bytes]")
    print(f"  Shared library: {out_eloquick_so} (also mirrored as {out_openevv_so}) [{os.path.getsize(out_eloquick_so):,} bytes]\n")

def main():
    parser = argparse.ArgumentParser(description="Build EloQuick tailored for Android.")
    parser.add_argument(
        "--abi",
        default="arm64-v8a",
        choices=["arm64-v8a", "armeabi-v7a", "x86_64", "x86", "all"],
        help="Target Android ABI (default: arm64-v8a, or 'all')",
    )
    parser.add_argument(
        "--debug",
        action="store_true",
        help="Build with debug symbols (-O0 -g) and unstripped binaries",
    )
    args = parser.parse_args()

    ndk_root = find_ndk_root()
    if not ndk_root:
        sys.exit("Error: Android NDK not found. Set ANDROID_NDK_HOME or ANDROID_NDK_ROOT.")

    ensure_rules_generated()

    if args.abi == "all":
        for abi in ABI_CONFIGS:
            build_abi(abi, ndk_root, debug=args.debug)
    else:
        build_abi(args.abi, ndk_root, debug=args.debug)

if __name__ == "__main__":
    main()
