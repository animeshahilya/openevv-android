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

Produces per ABI under build/android/<abi>/:
  - eloquick           (standalone CLI executable)
  - evv                (backwards compatibility copy; CLI is argv[0]-aware)
  - libeloquick.so     (shared library exposing standard ECI API)
  - libopenevv.so      (backwards compatibility rebuild with own SONAME)

Languages: all ten upstream modules (enus dede engb eses esus frca frfr
itit plpl jajp). Trim with --langs for smaller APKs, e.g.
  python tools/build_android.py --langs enus,dede
"""

import argparse
import hashlib
import os
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)

ALL_LANGS = ["enus", "dede", "engb", "eses", "esus", "frca", "frfr", "itit", "plpl", "jajp"]

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

# Header extensions that invalidate a cached object when newer than it.
# Lesson from beyondsighttech/openevv: the build once compared each source
# against its own object alone, so a merge changing delta_lang.h left stale
# objects linking modules that disagree about a struct -> segfault. A wrong
# rebuild costs a minute; a missed one costs an hour. We compare against the
# newest header in the tree (cheap: one walk per ABI build).
HEADER_EXTS = (".h",)


def find_ndk_root():
    ndk = os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK_ROOT")
    if ndk and os.path.isdir(ndk):
        return ndk

    home = os.path.expanduser("~")
    candidates = [
        # Windows default SDK layout (user-agnostic: never hardcode a username)
        os.path.join(home, "AppData", "Local", "Android", "Sdk", "ndk"),
        # Linux
        os.path.join(home, "Android", "Sdk", "ndk"),
        "/opt/android-sdk/ndk",
        "/usr/local/lib/android/sdk/ndk",
        # macOS default
        os.path.join(home, "Library", "Android", "sdk", "ndk"),
    ]
    for c in candidates:
        if os.path.isdir(c):
            try:
                versions = sorted(os.listdir(c), reverse=True)
            except OSError:
                continue
            if versions:
                return os.path.join(c, versions[0])
    # Also accept a direct NDK dir pointed at by ANDROID_HOME/sdk layout
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(env)
        if sdk:
            ndk_dir = os.path.join(sdk, "ndk")
            if os.path.isdir(ndk_dir):
                try:
                    versions = sorted(os.listdir(ndk_dir), reverse=True)
                except OSError:
                    continue
                if versions:
                    return os.path.join(ndk_dir, versions[0])
    return None


def find_clang_binary(ndk_root, triple, api):
    llvm_bin = os.path.join(ndk_root, "toolchains", "llvm", "prebuilt")
    if not os.path.isdir(llvm_bin):
        return None
    try:
        prebuilt_hosts = os.listdir(llvm_bin)
    except OSError:
        return None
    if not prebuilt_hosts:
        return None
    host_bin = os.path.join(llvm_bin, prebuilt_hosts[0], "bin")

    exe_suffix = ".cmd" if sys.platform == "win32" else ""
    target_clang = os.path.join(host_bin, f"{triple}{api}-clang{exe_suffix}")
    if os.path.exists(target_clang):
        return target_clang
    # Fall back to versioned triples some NDKs ship (e.g. armv7a needs
    # androideabi suffix handling) and finally bare clang with --target.
    generic_clang = os.path.join(host_bin, f"clang{exe_suffix}")
    if os.path.exists(generic_clang):
        return generic_clang
    return None


def ensure_rules_generated(langs):
    """Generate delta_rules_<lang>.{c,h} and delta_rules_shim_<lang>.c for each language."""
    print("Ensuring language rule bytecode tables are up-to-date...")
    for lang in langs:
        if lang == "jajp":
            # Japanese has no rules-as-text; its three generated files are the
            # only copy and are committed (see docs/japanese.md + .gitignore).
            continue
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


def newest_header_mtime():
    """Newest mtime of any header that engine sources can include."""
    newest = 0.0
    roots = [os.path.join(ROOT, "src"), os.path.join(ROOT, "include"),
             os.path.join(ROOT, "lang"), os.path.join(ROOT, "lib"),
             os.path.join(ROOT, "rom"), os.path.join(ROOT, "cli")]
    for top in roots:
        if not os.path.isdir(top):
            continue
        for dirpath, _dirnames, filenames in os.walk(top):
            for fn in filenames:
                if fn.endswith(HEADER_EXTS):
                    try:
                        mt = os.path.getmtime(os.path.join(dirpath, fn))
                    except OSError:
                        continue
                    if mt > newest:
                        newest = mt
    return newest


def compiler_accepts(clang, flag, target_flag=None):
    """Probe whether Clang accepts a compile flag (e.g. -flto=thin)."""
    import tempfile
    with tempfile.NamedTemporaryFile(suffix=".c", delete=False,
                                     mode="w", encoding="utf-8") as f:
        f.write("int evv_probe(void){return 0;}\n")
        src = f.name
    obj = src + ".o"
    try:
        cmd = [clang, "-c", src, "-o", obj, flag] + (target_flag or [])
        res = subprocess.run(cmd, capture_output=True, text=True)
        return res.returncode == 0
    except OSError:
        return False
    finally:
        for p in (src, obj):
            try:
                os.unlink(p)
            except OSError:
                pass


def build_abi(abi, ndk_root, debug=False, langs=None, api_override=None,
              jobs=None, clean=False):
    if abi not in ABI_CONFIGS:
        sys.exit(f"Unknown ABI: {abi}. Available: {list(ABI_CONFIGS.keys())}")

    langs = list(langs or ALL_LANGS)
    for l in langs:
        if not os.path.isdir(os.path.join(ROOT, "lang", l)):
            sys.exit(f"Unknown language '{l}'. Available: {ALL_LANGS}")

    cfg = ABI_CONFIGS[abi]
    api = api_override or cfg["api"]
    clang = find_clang_binary(ndk_root, cfg["triple"], api)
    if not clang:
        sys.exit(f"Could not find NDK Clang compiler for {abi} in {ndk_root}")

    mode_str = "DEBUG" if debug else "RELEASE (-O3)"
    print(f"=== Building EloQuick for Android ABI: {abi} [{mode_str}] ===")
    print(f"Compiler: {clang} (api {api})")
    print(f"Languages: {','.join(langs)}")

    build_dir = os.path.join(ROOT, "build", "android", abi)
    obj_dir = os.path.join(build_dir, "obj_debug" if debug else "obj")
    if clean:
        import shutil
        shutil.rmtree(obj_dir, ignore_errors=True)
    os.makedirs(obj_dir, exist_ok=True)

    langs_c = os.path.join(build_dir, "delta_langs.c")
    with open(langs_c, "w", encoding="utf-8") as f:
        f.write('/* Generated for Android build */\n#include "delta_lang.h"\n\n')
        for l in langs:
            f.write(f'extern delta_language delta_lang_{l};\n')
            f.write(f'void delta_lang_bind_{l}(void);\n')
        f.write('\nconst delta_language *const delta_languages[] = {\n')
        for l in langs:
            f.write(f'    &delta_lang_{l},\n')
        f.write('    0,\n};\n\n')
        f.write('void delta_lang_bind_all(void)\n{\n')
        for l in langs:
            f.write(f'    delta_lang_bind_{l}();\n')
        f.write('}\n')

    src_files = []
    src_dirs = [ROOT]
    for root, _dirs, files in os.walk(os.path.join(ROOT, "src")):
        src_dirs.append(root)
        for fn in files:
            if fn.endswith(".c") and fn != "port_win32.c" and not (fn == "klatt_fx_neon.c" and abi != "arm64-v8a"):
                src_files.append(os.path.join(root, fn))

    lang_files = [langs_c]
    lang_dirs = []
    for l in langs:
        lang_dir = os.path.join(ROOT, "lang", l)
        lang_dirs.append(lang_dir)
        for fn in os.listdir(lang_dir):
            if fn.endswith(".c") and not fn.startswith("delta_rules_c"):
                lang_files.append(os.path.join(lang_dir, fn))

    # Japanese romanizer (rom/jajp/): required when jajp is bundled.
    rom_files = []
    rom_dirs = []
    rom_defs = []
    if "jajp" in langs:
        rom_dir = os.path.join(ROOT, "rom", "jajp")
        if not os.path.isdir(rom_dir):
            sys.exit("jajp selected but rom/jajp/ is missing")
        rom_dirs.append(rom_dir)
        for fn in os.listdir(rom_dir):
            if fn.endswith(".c"):
                rom_files.append(os.path.join(rom_dir, fn))
        rom_defs.append("-DEVV_ROM_JAJP")

    # JNI bridge, when the NDK sysroot provides <jni.h>. Checked by direct
    # path (sysroot/usr/include/jni.h), not a tree walk -- the NDK is huge.
    jni_files = []
    jni_c = os.path.join(ROOT, "android", "eloquick_jni.c")
    if os.path.exists(jni_c):
        llvm_prebuilt = os.path.join(ndk_root, "toolchains", "llvm", "prebuilt")
        found_jni = False
        try:
            hosts = os.listdir(llvm_prebuilt)
        except OSError:
            hosts = []
        for host in hosts:
            if os.path.exists(os.path.join(llvm_prebuilt, host, "sysroot",
                                           "usr", "include", "jni.h")):
                found_jni = True
                break
        if found_jni:
            jni_files.append(jni_c)
            print("JNI bridge: enabled (android/eloquick_jni.c)")
        else:
            print("JNI bridge: skipped (<jni.h> not found in NDK)")

    include_dirs = sorted(set(src_dirs + lang_dirs + rom_dirs
                              + [build_dir, os.path.join(ROOT, "include")]))
    inc_flags = [f"-I{d}" for d in include_dirs]

    opt_cflags = [
        "-O0",
        "-g",
        "-DDEBUG=1",
    ] if debug else [
        "-O3",
        # ThinLTO only when this exact Clang accepts it (older NDKs warn or
        # fail; assuming it breaks hermetic builds). Probed below.
        "-fno-math-errno",
        "-fno-trapping-math",
        "-ffp-contract=fast",
        # No -g in release: -Wl,-s strips it at link time, so generating
        # debug info for ~200 files would be pure build time for nothing.
    ]

    engine_quiet = [
        "-w",
        # The engine predates prototypes in places (missing headers and
        # K&R-era cross-file calls); NDK r28+ Clang errors on those by
        # default. Upstream builds with the same suppression.
        "-Wno-implicit-function-declaration",
        "-Werror=int-conversion",
        "-Werror=incompatible-pointer-types",
    ]
    # New Android code (JNI bridge, CLI) stays warning-visible.
    android_warn = ["-Wall", "-Wextra", "-Wno-unused-parameter"]

    common_cflags = [
        "-fomit-frame-pointer" if not debug else "-fno-omit-frame-pointer",
        "-DEVV_ARENA=1",
        "-DECI_BUILDING=1",
        "-ffunction-sections",
        "-fdata-sections",
        "-fvisibility=hidden",
        "-fPIC",
    ] + opt_cflags + rom_defs + cfg["cflags"] + inc_flags

    header_floor = newest_header_mtime()

    # Bare-clang fallback (no versioned triple binary in this NDK) needs an
    # explicit --target on every compile AND link; versioned triples imply it.
    bare_clang = os.path.basename(clang).startswith("clang")
    target_flag = [f"--target={cfg['triple']}{api}"] if bare_clang else []

    # Feature-probe ThinLTO instead of assuming it (older NDK Clangs warn or
    # error; assuming it breaks hermetic builds). Same guard as CMakeLists.
    use_thin_lto = False
    if not debug and compiler_accepts(clang, "-flto=thin", target_flag):
        use_thin_lto = True
        common_cflags.append("-flto=thin")
    lto_tag = " + ThinLTO" if use_thin_lto else " (no ThinLTO: toolchain refused -flto=thin)"

    jni_set = {os.path.normcase(os.path.normpath(p)) for p in
               (jni_files + [os.path.join(ROOT, "cli", "evv.c"),
                             os.path.join(ROOT, "lib", "eci_api.c")])}

    def compile_source(src):
        rel = os.path.relpath(src, ROOT)
        # 16 hex chars of the full relative path (not 8 of basename): ~200
        # sources share basenames across src/lang/rom, so short hashes risk
        # collisions that silently reuse the wrong object.
        h = hashlib.md5(rel.encode("utf-8")).hexdigest()[:16]
        base = os.path.splitext(os.path.basename(src))[0]
        obj = os.path.join(obj_dir, f"{base}_{h}.o")

        # Stale when the source OR any header is newer (see header_floor).
        try:
            if (os.path.exists(obj)
                    and os.path.getmtime(obj) > os.path.getmtime(src)
                    and os.path.getmtime(obj) > header_floor
                    and os.path.getmtime(obj) > os.path.getmtime(langs_c)):
                return obj
        except OSError:
            pass

        extra = (android_warn if os.path.normcase(os.path.normpath(src)) in jni_set
                 else engine_quiet)
        cmd = [clang, "-c", src, "-o", obj] + common_cflags + extra + target_flag
        res = subprocess.run(cmd, capture_output=True, text=True)
        if res.returncode != 0:
            print(f"FAILED: {src}\n{res.stderr}", file=sys.stderr)
            return None
        return obj

    # Compile once; link three times (two SONAMEs + one CLI). The old CMake
    # compiled every source 4x; this script never did -- keep it that way.
    core_sources = src_files + lang_files + rom_files
    print(f"Compiling {len(core_sources)} core sources [{'DEBUG' if debug else '-O3 release'}{lto_tag if not debug else ''}]...")
    workers = jobs or os.cpu_count() or 8
    with ThreadPoolExecutor(max_workers=workers) as ex:
        core_objs = list(ex.map(compile_source, core_sources))

    if any(o is None for o in core_objs):
        sys.exit(f"Core compilation failed for ABI: {abi}")

    cli_src = os.path.join(ROOT, "cli", "evv.c")
    cli_obj = compile_source(cli_src)
    if not cli_obj:
        sys.exit(f"CLI compilation failed for ABI: {abi}")

    lib_srcs = [os.path.join(ROOT, "lib", "eci_api.c")] + jni_files
    lib_objs = []
    for ls in lib_srcs:
        o = compile_source(ls)
        if not o:
            sys.exit(f"Shared library wrapper compilation failed for ABI: {abi}")
        lib_objs.append(o)

    linker_alignment_flags = [
        "-Wl,-z,max-page-size=16384",
        "-Wl,-z,common-page-size=16384",
    ]

    # Link-time opts mirror the compile probe: only pass what this linker
    # accepts. -Wl,--icf=all is NDK-r26+; older linkers fail the whole link.
    opt_link_flags = []
    if not debug:
        opt_link_flags += ["-Wl,-O3"]
        if use_thin_lto:
            opt_link_flags.append("-flto=thin")
        import tempfile
        with tempfile.NamedTemporaryFile(suffix=".o", delete=False) as tf:
            probe_obj = tf.name
        try:
            probe_src = os.path.join(build_dir, "_link_probe.c")
            with open(probe_src, "w", encoding="utf-8") as f:
                f.write("int evv_link_probe(void){return 0;}\n")
            if subprocess.run([clang, "-c", probe_src, "-o", probe_obj] +
                              target_flag, capture_output=True).returncode == 0:
                for flag in ("-Wl,--icf=all",):
                    probe_so = os.path.join(build_dir, "_link_probe.so")
                    test = subprocess.run(
                        [clang, "-shared", probe_obj, "-o", probe_so] +
                        target_flag + [flag], capture_output=True)
                    try:
                        os.unlink(probe_so)
                    except OSError:
                        pass
                    if test.returncode == 0:
                        opt_link_flags.append(flag)
        finally:
            for p in (probe_obj, os.path.join(build_dir, "_link_probe.c")):
                try:
                    os.unlink(p)
                except OSError:
                    pass

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
    ] + target_flag + strip_flags + opt_link_flags + linker_alignment_flags + [
        "-lm",
        "-pthread",
        f"@{cli_rsp}",
    ]
    res = subprocess.run(link_cli_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        sys.exit(f"CLI linking failed for ABI {abi}:\n{res.stderr}")

    import shutil
    # CLI is argv[0]-aware: a copy is behaviour-identical, no second link.
    shutil.copy2(out_eloquick, out_evv)

    out_eloquick_so = os.path.join(build_dir, "libeloquick.so")
    out_openevv_so = os.path.join(build_dir, "libopenevv.so")
    print(f"Linking shared library {out_eloquick_so}...")
    so_rsp = os.path.join(build_dir, "so_objects.rsp")
    with open(so_rsp, "w") as f:
        for obj in core_objs + lib_objs:
            f.write(obj.replace("\\", "/") + "\n")

    link_so_cmd = [
        clang,
        "-o", out_eloquick_so,
        "-shared",
        "-Wl,-soname,libeloquick.so",
        "-Wl,--gc-sections",
    ] + target_flag + strip_flags + opt_link_flags + linker_alignment_flags + [
        "-lm",
        "-pthread",
        f"@{so_rsp}",
    ]
    res = subprocess.run(link_so_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        sys.exit(f"Shared library linking failed for ABI {abi}:\n{res.stderr}")

    # Compat .so needs its own link (SONAME differs); same objects, no recompile.
    link_so_compat_cmd = [
        clang,
        "-o", out_openevv_so,
        "-shared",
        "-Wl,-soname,libopenevv.so",
        "-Wl,--gc-sections",
    ] + target_flag + strip_flags + opt_link_flags + linker_alignment_flags + [
        "-lm",
        "-pthread",
        f"@{so_rsp}",
    ]
    res = subprocess.run(link_so_compat_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        sys.exit(f"Compat library linking failed for ABI {abi}:\n{res.stderr}")

    print(f"ABI {abi} Build Complete:")
    print(f"  CLI binary:     {out_eloquick} (also mirrored as {out_evv}) [{os.path.getsize(out_eloquick):,} bytes]")
    print(f"  Shared library: {out_eloquick_so} (also mirrored as {out_openevv_so}) [{os.path.getsize(out_eloquick_so):,} bytes]\n")


def parse_langs(s):
    langs = [x.strip() for x in s.split(",") if x.strip()]
    for l in langs:
        if l not in ALL_LANGS:
            sys.exit(f"Unknown language '{l}'. Available: {ALL_LANGS}")
    return langs


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
    parser.add_argument(
        "--langs",
        default=",".join(ALL_LANGS),
        help=f"Comma-separated language subset (default: all ten). Available: {','.join(ALL_LANGS)}",
    )
    parser.add_argument(
        "--api",
        type=int,
        default=None,
        help="Override Android API level per ABI (default: per-ABI config)",
    )
    parser.add_argument(
        "--jobs",
        type=int,
        default=None,
        help="Parallel compile jobs (default: cpu count)",
    )
    parser.add_argument(
        "--clean",
        action="store_true",
        help="Wipe cached objects for the selected ABI(s) before building",
    )
    args = parser.parse_args()

    ndk_root = find_ndk_root()
    if not ndk_root:
        sys.exit("Error: Android NDK not found. Set ANDROID_NDK_HOME or ANDROID_NDK_ROOT.")

    langs = parse_langs(args.langs)
    ensure_rules_generated(langs)

    if args.abi == "all":
        for abi in ABI_CONFIGS:
            build_abi(abi, ndk_root, debug=args.debug, langs=langs,
                      api_override=args.api, jobs=args.jobs, clean=args.clean)
    else:
        build_abi(args.abi, ndk_root, debug=args.debug, langs=langs,
                  api_override=args.api, jobs=args.jobs, clean=args.clean)


if __name__ == "__main__":
    main()
