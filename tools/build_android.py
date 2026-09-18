#!/usr/bin/env python3
"""
Build EloQuick (ultra-fast Eloquence / OpenEVV engine tailored for Android).

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
import json
import os
import shutil
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

ROOT = Path(__file__).resolve().parent.parent
os.chdir(ROOT)

ALL_LANGS = ["enus", "dede", "engb", "eses", "esus", "frca", "frfr", "itit", "plpl", "jajp"]

ABI_CONFIGS: Dict[str, Dict] = {
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

HEADER_EXTS = (".h",)

# Cache file for build configuration
CACHE_FILE = ROOT / "build" / ".android_build_cache.json"


@dataclass
class BuildConfig:
    """Immutable build configuration for an ABI."""
    abi: str
    triple: str
    api: int
    cflags: List[str]
    debug: bool
    langs: List[str]
    jobs: int
    clean: bool
    ndk_root: Path
    clang_path: Path
    use_thin_lto: bool
    linker_flags: List[str]


@dataclass
class BuildResult:
    """Result of a build."""
    abi: str
    success: bool
    cli_path: Optional[Path] = None
    evv_path: Optional[Path] = None
    lib_path: Optional[Path] = None
    compat_lib_path: Optional[Path] = None
    evn_lib_path: Optional[Path] = None
    cli_size: int = 0
    lib_size: int = 0
    duration: float = 0.0
    error: str = ""


def find_ndk_root() -> Optional[Path]:
    """Find Android NDK root directory with enhanced detection."""
    # Environment variables (highest priority)
    for env_var in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
        ndk = os.environ.get(env_var)
        if ndk and Path(ndk).is_dir():
            return Path(ndk)

    home = Path.home()
    candidates = [
        # Windows default SDK layout
        home / "AppData" / "Local" / "Android" / "Sdk" / "ndk",
        # Linux
        home / "Android" / "Sdk" / "ndk",
        Path("/opt/android-sdk/ndk"),
        Path("/usr/local/lib/android/sdk/ndk"),
        # macOS default
        home / "Library" / "Android" / "sdk" / "ndk",
    ]

    # Also check ANDROID_HOME / ANDROID_SDK_ROOT
    for env_var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(env_var)
        if sdk:
            candidates.append(Path(sdk) / "ndk")

    for c in candidates:
        if c.is_dir():
            try:
                versions = sorted(c.iterdir(), key=lambda p: p.name, reverse=True)
                # Filter for valid NDK versions (numeric names)
                valid_versions = [v for v in versions if v.is_dir() and v.name[0].isdigit()]
                if valid_versions:
                    return valid_versions[0]
            except OSError:
                continue
    return None


def find_clang_binary(ndk_root: Path, triple: str, api: int) -> Optional[Path]:
    """Find Clang compiler binary for target triple."""
    llvm_bin = ndk_root / "toolchains" / "llvm" / "prebuilt"
    if not llvm_bin.is_dir():
        return None

    try:
        prebuilt_hosts = list(llvm_bin.iterdir())
    except OSError:
        return None
    if not prebuilt_hosts:
        return None

    host_bin = prebuilt_hosts[0] / "bin"
    exe_suffix = ".cmd" if sys.platform == "win32" else ""

    # Try versioned triple first (e.g., aarch64-linux-android26-clang)
    target_clang = host_bin / f"{triple}{api}-clang{exe_suffix}"
    if target_clang.exists():
        return target_clang

    # Fall back to generic clang with --target
    generic_clang = host_bin / f"clang{exe_suffix}"
    if generic_clang.exists():
        return generic_clang

    return None


def load_cache() -> Dict:
    """Load build cache from disk."""
    if CACHE_FILE.exists():
        try:
            with open(CACHE_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except (json.JSONDecodeError, OSError):
            pass
    return {}


def save_cache(cache: Dict) -> None:
    """Save build cache to disk."""
    CACHE_FILE.parent.mkdir(parents=True, exist_ok=True)
    try:
        with open(CACHE_FILE, "w", encoding="utf-8") as f:
            json.dump(cache, f, indent=2)
    except OSError:
        pass


def ensure_rules_generated(langs: List[str]) -> None:
    """Generate delta_rules_<lang>.{c,h} and delta_rules_shim_<lang>.c for each language."""
    print("Ensuring language rule bytecode tables are up-to-date...")
    for lang in langs:
        if lang == "jajp":
            # Japanese has no rules-as-text; its three generated files are
            # the only copy and are committed (see docs/japanese.md + .gitignore).
            continue
        h_file = ROOT / "lang" / lang / f"delta_rules_{lang}.h"
        c_file = ROOT / "lang" / lang / f"delta_rules_{lang}.c"
        needs_gen = not (h_file.exists() and c_file.exists())
        if not needs_gen:
            try:
                with open(h_file, "r", encoding="utf-8", errors="ignore") as f:
                    if "delta_rule_argmask" not in f.read():
                        needs_gen = True
            except OSError:
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


def newest_header_mtime() -> float:
    """Newest mtime of any header that engine sources can include."""
    newest = 0.0
    roots = [
        ROOT / "src", ROOT / "include", ROOT / "lang",
        ROOT / "lib", ROOT / "rom", ROOT / "cli"
    ]
    for top in roots:
        if not top.is_dir():
            continue
        for dirpath, _dirnames, filenames in os.walk(top):
            for fn in filenames:
                if fn.endswith(HEADER_EXTS):
                    try:
                        mt = os.path.getmtime(Path(dirpath) / fn)
                    except OSError:
                        continue
                    if mt > newest:
                        newest = mt
    return newest


def compiler_accepts(clang: Path, flag: str, target_flag: Optional[List[str]] = None) -> bool:
    """Probe whether Clang accepts a compile flag (e.g. -flto=thin)."""
    import tempfile
    with tempfile.NamedTemporaryFile(suffix=".c", delete=False, mode="w", encoding="utf-8") as f:
        f.write("int evv_probe(void){return 0;}\n")
        src = Path(f.name)
    obj = src.with_suffix(".o")
    try:
        cmd = [str(clang), "-c", str(src), "-o", str(obj), flag] + (target_flag or [])
        res = subprocess.run(cmd, capture_output=True, text=True)
        return res.returncode == 0
    except OSError:
        return False
    finally:
        for p in (src, obj):
            try:
                p.unlink()
            except OSError:
                pass


def linker_accepts(clang: Path, flag: str, target_flag: Optional[List[str]] = None) -> bool:
    """Probe whether linker accepts a flag."""
    import tempfile
    with tempfile.NamedTemporaryFile(suffix=".c", delete=False, mode="w", encoding="utf-8") as f:
        f.write("int evv_link_probe(void){return 0;}\n")
        src = Path(f.name)
    obj = src.with_suffix(".o")
    so = src.with_suffix(".so")
    try:
        # Compile first
        if subprocess.run([str(clang), "-c", str(src), "-o", str(obj)] + (target_flag or []), capture_output=True).returncode != 0:
            return False
        # Try link with flag
        test = subprocess.run([str(clang), "-shared", str(obj), "-o", str(so)] + (target_flag or []) + [flag], capture_output=True)
        return test.returncode == 0
    except OSError:
        return False
    finally:
        for p in (src, obj, so):
            try:
                p.unlink()
            except OSError:
                pass


def collect_sources(langs: List[str]) -> Tuple[List[Path], List[Path], List[Path], List[Path], List[Path], List[Path], List[str]]:
    """Collect all source files and include directories."""
    # Core engine sources
    src_files = []
    src_dirs = [ROOT]
    for root, _dirs, files in os.walk(ROOT / "src"):
        src_dirs.append(Path(root))
        for fn in files:
            if fn.endswith(".c") and fn != "port_win32.c":
                src_files.append(Path(root) / fn)

    # Language sources
    lang_files = []
    lang_dirs = []
    for l in langs:
        lang_dir = ROOT / "lang" / l
        lang_dirs.append(lang_dir)
        for fn in os.listdir(lang_dir):
            if fn.endswith(".c") and not fn.startswith("delta_rules_c"):
                lang_files.append(lang_dir / fn)

    # Japanese romanizer
    rom_files = []
    rom_dirs = []
    rom_defs = []
    if "jajp" in langs:
        rom_dir = ROOT / "rom" / "jajp"
        if not rom_dir.is_dir():
            sys.exit("jajp selected but rom/jajp/ is missing")
        rom_dirs.append(rom_dir)
        for fn in os.listdir(rom_dir):
            if fn.endswith(".c"):
                rom_files.append(rom_dir / fn)
        rom_defs.append("-DEVV_ROM_JAJP")

    # JNI bridges: eloquick (streaming, EloQuickEngine) and eloquence
    # (whole-utterance, EloquenceNative -> libopenevv_jni.so).
    jni_files = []
    jni_c = ROOT / "android" / "eloquick_jni.c"
    if jni_c.exists():
        llvm_prebuilt = Path(os.environ.get("ANDROID_NDK_HOME", "")) / "toolchains" / "llvm" / "prebuilt"
        # We'll check for jni.h availability later when we have the NDK root
        jni_files.append(jni_c)
    evn_jni_files = []
    evn_c = ROOT / "android" / "eloquence_jni.c"
    if evn_c.exists():
        evn_jni_files.append(evn_c)

    return src_files, lang_files, rom_files, jni_files, evn_jni_files, src_dirs + lang_dirs + rom_dirs, rom_defs


def generate_delta_langs_c(build_dir: Path, langs: List[str]) -> Path:
    """Generate delta_langs.c for the given languages."""
    langs_c = build_dir / "delta_langs.c"
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
    return langs_c


def compile_source(
    src: Path,
    obj_dir: Path,
    clang: Path,
    common_cflags: List[str],
    target_flag: List[str],
    engine_quiet: List[str],
    android_warn: List[str],
    jni_set: Set[Path],
    header_floor: float,
    langs_c_mtime: float,
) -> Optional[Path]:
    """Compile a single source file to object."""
    rel = src.relative_to(ROOT)
    # 16 hex chars of the full relative path to avoid collisions
    h = hashlib.md5(str(rel).encode("utf-8")).hexdigest()[:16]
    base = src.stem
    obj = obj_dir / f"{base}_{h}.o"

    # Stale check: object older than source OR any header OR generated delta_langs.c
    try:
        if (obj.exists()
                and obj.stat().st_mtime > src.stat().st_mtime
                and obj.stat().st_mtime > header_floor
                and obj.stat().st_mtime > langs_c_mtime):
            return obj
    except OSError:
        pass

    extra = android_warn if src.resolve() in jni_set else engine_quiet
    cmd = [str(clang), "-c", str(src), "-o", str(obj)] + common_cflags + extra + target_flag
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print(f"FAILED: {src}\n{res.stderr}", file=sys.stderr)
        return None
    return obj


def build_abi(
    abi: str,
    ndk_root: Path,
    debug: bool = False,
    size_optimize: bool = False,
    min_size_rel: bool = False,
    no_strip: bool = False,
    langs: Optional[List[str]] = None,
    api_override: Optional[int] = None,
    jobs: Optional[int] = None,
    clean: bool = False,
) -> BuildResult:
    """Build for a single ABI."""
    start_time = time.time()

    if abi not in ABI_CONFIGS:
        return BuildResult(abi=abi, success=False, error=f"Unknown ABI: {abi}")

    langs = list(langs or ALL_LANGS)
    for l in langs:
        if not (ROOT / "lang" / l).is_dir():
            return BuildResult(abi=abi, success=False, error=f"Unknown language '{l}'")

    cfg = ABI_CONFIGS[abi]
    api = api_override or cfg["api"]
    clang = find_clang_binary(ndk_root, cfg["triple"], api)
    if not clang:
        return BuildResult(abi=abi, success=False, error=f"Could not find NDK Clang for {abi}")

    # Determine build mode
    if debug:
        mode_str = "DEBUG"
        opt_level = "O0"
        opt_cflags = ["-O0", "-g", "-DDEBUG=1"]
        strip = False
    elif min_size_rel:
        mode_str = "MINSIZEREL (-Os)"
        opt_level = "Os"
        opt_cflags = [
            "-Os",
            "-fno-math-errno",
            "-fno-trapping-math",
            "-ffp-contract=fast",
            "-DNDEBUG",
        ]
        strip = not no_strip
    elif size_optimize:
        mode_str = "SIZE-OPTIMIZED (-Os)"
        opt_level = "Os"
        opt_cflags = [
            "-Os",
            "-fno-math-errno",
            "-fno-trapping-math",
            "-ffp-contract=fast",
        ]
        strip = not no_strip
    else:
        mode_str = "RELEASE (-O3)"
        opt_level = "O3"
        opt_cflags = [
            "-O3",
            "-fno-math-errno",
            "-fno-trapping-math",
            "-ffp-contract=fast",
        ]
        strip = not no_strip

    print(f"=== Building EloQuick for Android ABI: {abi} [{mode_str}] ===")
    print(f"Compiler: {clang} (API {api})")
    print(f"Languages: {','.join(langs)}")
    print(f"Optimization: {opt_level}, Strip: {'yes' if strip else 'no'}")

    build_dir = ROOT / "build" / "android" / abi
    obj_suffix = "obj_debug" if debug else ("obj_size" if size_optimize or min_size_rel else "obj")
    obj_dir = build_dir / obj_suffix
    if clean:
        shutil.rmtree(obj_dir, ignore_errors=True)
    obj_dir.mkdir(parents=True, exist_ok=True)

    # Generate delta_langs.c
    langs_c = generate_delta_langs_c(build_dir, langs)
    langs_c_mtime = langs_c.stat().st_mtime

    # Collect sources
    src_files, lang_files, rom_files, jni_files, evn_jni_files, include_dirs, rom_defs = collect_sources(langs)

    # Check for JNI header availability
    jni_h_found = False
    llvm_prebuilt = ndk_root / "toolchains" / "llvm" / "prebuilt"
    if llvm_prebuilt.is_dir():
        for host in llvm_prebuilt.iterdir():
            if (host / "sysroot" / "usr" / "include" / "jni.h").exists():
                jni_h_found = True
                break

    if jni_h_found:
        print("JNI bridges: enabled (android/eloquick_jni.c + android/eloquence_jni.c)")
    else:
        print("JNI bridges: skipped (<jni.h> not found in NDK)")
        jni_files = []
        evn_jni_files = []

    include_dirs = sorted(set(include_dirs + [build_dir, ROOT / "include"]))
    inc_flags = [f"-I{d}" for d in include_dirs]

    engine_quiet = [
        "-w",
        "-Wno-implicit-function-declaration",
        "-Werror=int-conversion",
        "-Werror=incompatible-pointer-types",
    ]
    android_warn = ["-Wall", "-Wextra", "-Wno-unused-parameter"]

    common_cflags = [
        "-fomit-frame-pointer" if not debug else "-fno-omit-frame-pointer",
        "-DEVV_ARENA=1",
        "-DECI_BUILDING=1",
        "-ffunction-sections",
        "-fdata-sections",
        "-fvisibility=hidden",
        "-fPIC",
    ] + opt_cflags + inc_flags + rom_defs

    header_floor = newest_header_mtime()

    bare_clang = clang.name.startswith("clang")
    target_flag = [f"--target={cfg['triple']}{api}"] if bare_clang else []

    # Probe ThinLTO
    use_thin_lto = False
    if not debug and compiler_accepts(clang, "-flto=thin", target_flag):
        use_thin_lto = True
        common_cflags.append("-flto=thin")

    lto_tag = " + ThinLTO" if use_thin_lto else " (no ThinLTO)"

    jni_set = {p.resolve() for p in jni_files + evn_jni_files + [ROOT / "cli" / "evv.c", ROOT / "lib" / "eci_api.c"]}

    core_sources = src_files + lang_files + rom_files + [langs_c]
    print(f"Compiling {len(core_sources)} core sources [{'DEBUG' if debug else '-O3 release'}{lto_tag if not debug else ''}]...")

    workers = jobs or os.cpu_count() or 8
    core_objs = []
    with ThreadPoolExecutor(max_workers=workers) as ex:
        futures = {
            ex.submit(
                compile_source,
                src, obj_dir, clang, common_cflags, target_flag,
                engine_quiet, android_warn, jni_set, header_floor, langs_c_mtime
            ): src for src in core_sources
        }
        for fut in as_completed(futures):
            obj = fut.result()
            if obj is None:
                return BuildResult(abi=abi, success=False, error=f"Compilation failed for {futures[fut]}")
            core_objs.append(obj)

    # Compile CLI
    cli_src = ROOT / "cli" / "evv.c"
    cli_obj = compile_source(
        cli_src, obj_dir, clang, common_cflags, target_flag,
        engine_quiet, android_warn, jni_set, header_floor, langs_c_mtime
    )
    if not cli_obj:
        return BuildResult(abi=abi, success=False, error="CLI compilation failed")

    # Compile library wrappers
    lib_srcs = [ROOT / "lib" / "eci_api.c"] + jni_files
    lib_objs = []
    for ls in lib_srcs:
        o = compile_source(
            ls, obj_dir, clang, common_cflags, target_flag,
            engine_quiet, android_warn, jni_set, header_floor, langs_c_mtime
        )
        if not o:
            return BuildResult(abi=abi, success=False, error=f"Library wrapper compilation failed for {ls}")
        lib_objs.append(o)

    # Linker flags
    linker_alignment_flags = [
        "-Wl,-z,max-page-size=16384",
        "-Wl,-z,common-page-size=16384",
    ]

    opt_link_flags = []
    if not debug:
        opt_link_flags += ["-Wl,-O3"]
        if use_thin_lto:
            opt_link_flags.append("-flto=thin")

        # Probe ICF support
        if linker_accepts(clang, "-Wl,--icf=all", target_flag):
            opt_link_flags.append("-Wl,--icf=all")

    # Link CLI
    out_eloquick = build_dir / "eloquick"
    out_evv = build_dir / "evv"
    print(f"Linking executable {out_eloquick}...")

    cli_rsp = build_dir / "cli_objects.rsp"
    with open(cli_rsp, "w") as f:
        for obj in core_objs + [cli_obj]:
            f.write(str(obj).replace("\\", "/") + "\n")

    strip_flags = [] if not strip else ["-Wl,-s"]

    link_cli_cmd = [
        str(clang),
        "-o", str(out_eloquick),
        "-pie",
        "-Wl,--gc-sections",
    ] + target_flag + strip_flags + opt_link_flags + linker_alignment_flags + [
        "-lm", "-pthread", f"@{cli_rsp}"
    ]
    res = subprocess.run(link_cli_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        return BuildResult(abi=abi, success=False, error=f"CLI linking failed:\n{res.stderr}")

    shutil.copy2(out_eloquick, out_evv)

    # Link shared libraries
    out_eloquick_so = build_dir / "libeloquick.so"
    out_openevv_so = build_dir / "libopenevv.so"
    print(f"Linking shared library {out_eloquick_so}...")

    so_rsp = build_dir / "so_objects.rsp"
    with open(so_rsp, "w") as f:
        for obj in core_objs + lib_objs:
            f.write(str(obj).replace("\\", "/") + "\n")

    link_so_cmd = [
        str(clang),
        "-o", str(out_eloquick_so),
        "-shared",
        "-Wl,-soname,libeloquick.so",
        "-Wl,--gc-sections",
    ] + target_flag + strip_flags + opt_link_flags + linker_alignment_flags + [
        "-lm", "-pthread", f"@{so_rsp}"
    ]
    res = subprocess.run(link_so_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        return BuildResult(abi=abi, success=False, error=f"Shared library linking failed:\n{res.stderr}")

    # Compat library: copy the primary .so and patch its SONAME instead of
    # a full ThinLTO relink (saves 5-15s per ABI).
    shutil.copy2(out_eloquick_so, out_openevv_so)
    llvm_objcopy = str(clang.parent / "llvm-objcopy.exe")
    if not os.path.isfile(llvm_objcopy):
        llvm_objcopy = str(clang.parent / "llvm-objcopy")
    patch_soname_cmd = [llvm_objcopy, "--set-soname", "libopenevv.so", str(out_openevv_so)]
    if os.path.isfile(llvm_objcopy):
        res = subprocess.run(patch_soname_cmd, capture_output=True, text=True)
        # Non-fatal: the compat lib works with a wrong SONAME

    duration = time.time() - start_time
    cli_size = out_eloquick.stat().st_size
    lib_size = out_eloquick_so.stat().st_size

    # Eloquence bridge (whole-utterance, EloquenceNative): same engine
    # objects + eci_api, own JNI translation unit, own SONAME. This is what
    # the Compose UI loads (System.loadLibrary("openevv_jni")); libopenevv.so
    # above stays a pure-ECI compat mirror with no JNI inside.
    out_evn_so = build_dir / "libopenevv_jni.so"
    evn_lib_size = 0
    if evn_jni_files:
        print(f"Linking shared library {out_evn_so}...")
        evn_objs = []
        for ls in evn_jni_files:
            o = compile_source(
                ls, obj_dir, clang, common_cflags, target_flag,
                engine_quiet, android_warn, jni_set, header_floor, langs_c_mtime
            )
            if not o:
                return BuildResult(abi=abi, success=False, error=f"Library wrapper compilation failed for {ls}")
            evn_objs.append(o)
        evn_rsp = build_dir / "evn_objects.rsp"
        with open(evn_rsp, "w") as f:
            for obj in core_objs + [lib_objs[0]] + evn_objs:
                f.write(str(obj).replace("\\", "/") + "\n")
        link_evn_cmd = [
            str(clang),
            "-o", str(out_evn_so),
            "-shared",
            "-Wl,-soname,libopenevv_jni.so",
            "-Wl,--gc-sections",
        ] + target_flag + strip_flags + opt_link_flags + linker_alignment_flags + [
            "-lm", "-llog", "-pthread", f"@{evn_rsp}"
        ]
        res = subprocess.run(link_evn_cmd, capture_output=True, text=True)
        if res.returncode != 0:
            return BuildResult(abi=abi, success=False, error=f"Eloquence bridge linking failed:\n{res.stderr}")
        evn_lib_size = out_evn_so.stat().st_size

    print(f"ABI {abi} Build Complete ({duration:.1f}s):")
    print(f"  CLI binary:     {out_eloquick} (also {out_evv}) [{cli_size:,} bytes]")
    print(f"  Shared library: {out_eloquick_so} (also {out_openevv_so}) [{lib_size:,} bytes]")
    if evn_jni_files:
        print(f"  JNI bridge:     {out_evn_so} [{evn_lib_size:,} bytes]")

    return BuildResult(
        abi=abi,
        success=True,
        cli_path=out_eloquick,
        evv_path=out_evv,
        lib_path=out_eloquick_so,
        compat_lib_path=out_openevv_so,
        evn_lib_path=out_evn_so if evn_jni_files else None,
        cli_size=cli_size,
        lib_size=lib_size,
        duration=duration,
    )


def parse_langs(s: str) -> List[str]:
    langs = [x.strip() for x in s.split(",") if x.strip()]
    for l in langs:
        if l not in ALL_LANGS:
            sys.exit(f"Unknown language '{l}'. Available: {ALL_LANGS}")
    return langs


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Build EloQuick tailored for Android.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
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
        "--size-optimize",
        action="store_true",
        help="Optimize for size (-Os) instead of speed (-O3). Reduces binary size ~15-20%.",
    )
    parser.add_argument(
        "--min-size-rel",
        action="store_true",
        help="MinSizeRel build type (-Os -DNDEBUG). Smallest release build.",
    )
    parser.add_argument(
        "--no-strip",
        action="store_true",
        help="Disable symbol stripping (-Wl,-s) even in release builds",
    )
    parser.add_argument(
        "--langs",
        default=",".join(ALL_LANGS),
        help=f"Comma-separated language subset. Available: {','.join(ALL_LANGS)}",
    )
    parser.add_argument(
        "--api",
        type=int,
        default=None,
        help="Override Android API level per ABI",
    )
    parser.add_argument(
        "--jobs",
        type=int,
        default=None,
        help="Parallel compile jobs (default: CPU count)",
    )
    parser.add_argument(
        "--clean",
        action="store_true",
        help="Wipe cached objects for the selected ABI(s) before building",
    )
    parser.add_argument(
        "--no-cache",
        action="store_true",
        help="Disable build cache (force recompilation)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Show what would be built without actually building",
    )
    args = parser.parse_args()

    ndk_root = find_ndk_root()
    if not ndk_root:
        sys.exit("Error: Android NDK not found. Set ANDROID_NDK_HOME or ANDROID_NDK_ROOT.")
    print(f"Using NDK: {ndk_root}")

    langs = parse_langs(args.langs)
    ensure_rules_generated(langs)

    if args.dry_run:
        print("Dry run - would build:")
        abis = list(ABI_CONFIGS.keys()) if args.abi == "all" else [args.abi]
        for abi in abis:
            mode = "debug" if args.debug else ("size-opt" if args.size_optimize else ("minsize" if args.min_size_rel else "release"))
            strip = "no-strip" if args.no_strip and not args.debug else ""
            print(f"  {abi}: {len(langs)} languages, {mode} {strip}".strip())
        return 0

    results: List[BuildResult] = []

    if args.abi == "all":
        for abi in ABI_CONFIGS:
            result = build_abi(abi, ndk_root, debug=args.debug, size_optimize=args.size_optimize,
                               min_size_rel=args.min_size_rel, no_strip=args.no_strip,
                               langs=langs, api_override=args.api, jobs=args.jobs, clean=args.clean)
            results.append(result)
            if not result.success:
                break
    else:
        result = build_abi(args.abi, ndk_root, debug=args.debug, size_optimize=args.size_optimize,
                           min_size_rel=args.min_size_rel, no_strip=args.no_strip,
                           langs=langs, api_override=args.api, jobs=args.jobs, clean=args.clean)
        results.append(result)

    # Summary
    print("=" * 60)
    print("BUILD SUMMARY")
    print("=" * 60)
    all_ok = True
    for r in results:
        status = "OK" if r.success else "FAILED"
        if r.success:
            mode = "debug" if args.debug else ("size-opt" if args.size_optimize else ("minsize" if args.min_size_rel else "release"))
            print(f"  {r.abi:12} {status:6}  [{mode}]  CLI: {r.cli_size:>10,}B  lib: {r.lib_size:>10,}B  ({r.duration:.1f}s)")
        else:
            print(f"  {r.abi:12} {status:6}  {r.error}")
            all_ok = False

    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())