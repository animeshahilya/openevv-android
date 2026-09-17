#!/usr/bin/env python3
"""
Binary Size Analysis Tool for EloQuick Android Builds.

Analyzes binary sizes, identifies bloat, and suggests optimizations.
"""

import argparse
import os
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Tuple

ROOT = Path(__file__).resolve().parent.parent
os.chdir(ROOT)

ABIS = ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"]
BINARIES = ["libeloquick.so", "libopenevv.so", "eloquick", "evv"]


def run_cmd(cmd: List[str]) -> str:
    """Run command and return stdout."""
    try:
        return subprocess.run(cmd, capture_output=True, text=True, check=True).stdout
    except subprocess.CalledProcessError as e:
        return e.stdout + e.stderr


def get_binary_size(path: Path) -> int:
    """Get file size in bytes."""
    return path.stat().st_size if path.exists() else 0


def format_size(bytes_: int) -> str:
    """Format bytes as human-readable string."""
    for unit in ['B', 'KB', 'MB', 'GB']:
        if bytes_ < 1024:
            return f"{bytes_:.1f} {unit}"
        bytes_ /= 1024
    return f"{bytes_:.1f} TB"


def analyze_symbols(so_path: Path, ndk_root: Path) -> Dict:
    """Analyze exported symbols from a shared library."""
    llvm_nm = ndk_root / "toolchains" / "llvm" / "prebuilt" / "linux-x86_64" / "bin" / "llvm-nm"
    if not llvm_nm.exists():
        # Try to find it
        for host in (ndk_root / "toolchains" / "llvm" / "prebuilt").iterdir():
            candidate = host / "bin" / "llvm-nm"
            if candidate.exists():
                llvm_nm = candidate
                break

    if not llvm_nm.exists():
        return {"error": "llvm-nm not found"}

    # Get dynamic symbols
    out = run_cmd([str(llvm_nm), "-D", str(so_path)])

    symbols = {"total": 0, "text": 0, "data": 0, "bss": 0, "jni": 0, "eci": 0, "other": 0}
    for line in out.strip().split('\n'):
        if not line.strip():
            continue
        parts = line.split()
        if len(parts) >= 3:
            sym_type = parts[1]
            sym_name = parts[2]
            symbols["total"] += 1
            if sym_type == 'T':
                symbols["text"] += 1
            elif sym_type in ('D', 'B'):
                symbols["data"] += 1
                if sym_type == 'B':
                    symbols["bss"] += 1

            if "Java_com_eloquick_tts" in sym_name:
                symbols["jni"] += 1
            elif sym_name.startswith(("eci", "ECI")):
                symbols["eci"] += 1
            else:
                symbols["other"] += 1

    return symbols


def analyze_sections(so_path: Path, ndk_root: Path) -> Dict:
    """Analyze ELF sections."""
    llvm_readelf = ndk_root / "toolchains" / "llvm" / "prebuilt" / "linux-x86_64" / "bin" / "llvm-readelf"
    if not llvm_readelf.exists():
        for host in (ndk_root / "toolchains" / "llvm" / "prebuilt").iterdir():
            candidate = host / "bin" / "llvm-readelf"
            if candidate.exists():
                llvm_readelf = candidate
                break

    if not llvm_readelf.exists():
        return {"error": "llvm-readelf not found"}

    out = run_cmd([str(llvm_readelf), "-S", str(so_path)])

    sections = {}
    for line in out.strip().split('\n'):
        if ']' in line and ('PROGBITS' in line or 'NOBITS' in line or 'DYNAMIC' in line):
            parts = line.split()
            if len(parts) >= 7:
                try:
                    name = parts[2]
                    size = int(parts[5], 16)
                    if size > 0:
                        sections[name] = size
                except (ValueError, IndexError):
                    pass

    return sections


def check_page_alignment(so_path: Path, ndk_root: Path) -> Dict:
    """Check 16KB page alignment."""
    llvm_readelf = ndk_root / "toolchains" / "llvm" / "prebuilt" / "linux-x86_64" / "bin" / "llvm-readelf"
    if not llvm_readelf.exists():
        for host in (ndk_root / "toolchains" / "llvm" / "prebuilt").iterdir():
            candidate = host / "bin" / "llvm-readelf"
            if candidate.exists():
                llvm_readelf = candidate
                break

    if not llvm_readelf.exists():
        return {"error": "llvm-readelf not found"}

    out = run_cmd([str(llvm_readelf), "-l", str(so_path)])

    result = {"max_page_size": False, "common_page_size": False, "load_segments": []}
    for line in out.strip().split('\n'):
        if "max-page-size" in line and "16384" in line:
            result["max_page_size"] = True
        if "common-page-size" in line and "16384" in line:
            result["common_page_size"] = True
        if line.strip().startswith("LOAD"):
            parts = line.split()
            if len(parts) >= 7:
                align = parts[6]
                result["load_segments"].append(align)

    return result


def find_ndk() -> Path:
    """Find NDK root."""
    for env in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
        if env in os.environ:
            return Path(os.environ[env])

    home = Path.home()
    candidates = [
        home / "AppData" / "Local" / "Android" / "Sdk" / "ndk",
        home / "Android" / "Sdk" / "ndk",
        home / "Library" / "Android" / "sdk" / "ndk",
        Path("/opt/android-sdk/ndk"),
    ]
    for c in candidates:
        if c.exists():
            versions = sorted([v for v in c.iterdir() if v.is_dir() and v.name[0].isdigit()], reverse=True)
            if versions:
                return versions[0]
    return Path("")


def main():
    parser = argparse.ArgumentParser(description="Analyze EloQuick Android binary sizes")
    parser.add_argument("--abi", default="all", choices=ABIS + ["all"], help="Target ABI")
    parser.add_argument("--ndk", type=Path, help="NDK root path")
    parser.add_argument("--json", action="store_true", help="Output JSON")
    parser.add_argument("--compare", type=Path, help="Compare with another build directory")
    args = parser.parse_args()

    ndk_root = args.ndk or find_ndk()
    if not ndk_root.exists():
        print("Warning: NDK not found, symbol analysis will be skipped", file=sys.stderr)

    abis = ABIS if args.abi == "all" else [args.abi]
    results = {}

    for abi in abis:
        build_dir = ROOT / "build" / "android" / abi
        if not build_dir.exists():
            print(f"Build directory not found: {build_dir}")
            continue

        abi_results = {}
        print(f"\n{'='*60}")
        print(f"ABI: {abi}")
        print(f"{'='*60}")

        for bin_name in BINARIES:
            bin_path = build_dir / bin_name
            if not bin_path.exists():
                continue

            size = get_binary_size(bin_path)
            print(f"\n  {bin_name}: {format_size(size)} ({size:,} bytes)")

            bin_info = {"size": size, "size_human": format_size(size)}

            if bin_name.endswith(".so") and ndk_root.exists():
                # Symbol analysis
                syms = analyze_symbols(bin_path, ndk_root)
                bin_info["symbols"] = syms
                if "error" not in syms:
                    print(f"    Symbols: {syms['total']} total ({syms['text']} text, {syms['data']} data, {syms['jni']} JNI, {syms['eci']} ECI)")

                # Section analysis
                sections = analyze_sections(bin_path, ndk_root)
                bin_info["sections"] = sections
                if "error" not in sections:
                    top_sections = sorted(sections.items(), key=lambda x: x[1], reverse=True)[:10]
                    print(f"    Top sections:")
                    for name, sz in top_sections:
                        print(f"      {name}: {format_size(sz)}")

                # Page alignment
                align = check_page_alignment(bin_path, ndk_root)
                bin_info["alignment"] = align
                if "error" not in align:
                    print(f"    Page alignment: max-page-size={'✓' if align['max_page_size'] else '✗'} common-page-size={'✓' if align['common_page_size'] else '✗'}")

            abi_results[bin_name] = bin_info

        results[abi] = abi_results

    # Summary table
    print(f"\n{'='*60}")
    print("SUMMARY")
    print(f"{'='*60}")
    print(f"{'ABI':<12} {'Variant':<10} {'libeloquick.so':>15} {'libopenevv.so':>15} {'eloquick':>12} {'evv':>12}")
    print("-" * 80)

    for abi in abis:
        if abi not in results:
            continue
        r = results[abi]
        # Determine variant from directory name or size
        variant = "release"
        print(f"{abi:<12} {variant:<10} "
              f"{r.get('libeloquick.so', {}).get('size_human', 'N/A'):>15} "
              f"{r.get('libopenevv.so', {}).get('size_human', 'N/A'):>15} "
              f"{r.get('eloquick', {}).get('size_human', 'N/A'):>12} "
              f"{r.get('evv', {}).get('size_human', 'N/A'):>12}")

    # Size optimization suggestions
    print(f"\n{'='*60}")
    print("OPTIMIZATION SUGGESTIONS")
    print(f"{'='*60}")

    for abi in abis:
        if abi not in results:
            continue
        r = results[abi]
        lib_size = r.get('libeloquick.so', {}).get('size', 0)

        if lib_size > 3_000_000:  # > 3MB
            print(f"  ⚠ {abi}: libeloquick.so is {format_size(lib_size)} - consider trimming languages")
            print(f"     Run: python tools/build_android.py --abi {abi} --langs enus,dede")

        symbols = r.get('libeloquick.so', {}).get('symbols', {})
        if symbols.get('jni', 0) == 0:
            print(f"  ⚠ {abi}: libeloquick.so has NO JNI symbols - JNI bridge not built!")
        if r.get('libopenevv.so', {}).get('symbols', {}).get('jni', 0) > 0:
            print(f"  ✗ {abi}: libopenevv.so has JNI symbols - violates single-.so rule!")

        align = r.get('libeloquick.so', {}).get('alignment', {})
        if not align.get('max_page_size') or not align.get('common_page_size'):
            print(f"  ✗ {abi}: Missing 16KB page alignment - Android 15+ may fail!")

    if args.json:
        import json
        print(json.dumps(results, indent=2))

    return 0


if __name__ == "__main__":
    sys.exit(main())