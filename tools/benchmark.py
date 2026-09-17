#!/usr/bin/env python3
"""
Benchmark Tool for EloQuick Android.

Measures synthesis latency, throughput, and memory usage.
Can run on host (x86_64 Linux) or push to Android device via ADB.
"""

import argparse
import json
import os
import statistics
import subprocess
import sys
import time
from pathlib import Path
from typing import Dict, List, Optional

ROOT = Path(__file__).resolve().parent.parent
os.chdir(ROOT)

# Test texts of varying lengths
TEST_TEXTS = {
    "short": "Hello world.",
    "medium": "The quick brown fox jumps over the lazy dog. " * 3,
    "long": ("Lorem ipsum dolor sit amet, consectetur adipiscing elit. " * 10)[:1000],
    "very_long": ("Sed ut perspiciatis unde omnis iste natus error sit voluptatem. " * 20)[:4000],
}

# Sample rates to test
SAMPLE_RATES = [8000, 11025, 16000, 22050, 32000, 44100, 48000]

# Languages to test (if available)
TEST_LANGUAGES = ["enus", "dede", "engb", "eses", "frfr", "itit", "plpl"]


def find_evv_binary(abi: str, debug: bool = False) -> Optional[Path]:
    """Find the evv binary for the given ABI."""
    build_dir = ROOT / "build" / "android" / abi
    binary = build_dir / "evv"
    if binary.exists():
        return binary
    return None


def run_evv_on_host(binary: Path, text: str, rate: int = 11025, language: Optional[str] = None,
                    voice: int = 1, output: Optional[Path] = None) -> Dict:
    """Run evv binary on host (Linux x86_64 only)."""
    cmd = [str(binary)]
    if output:
        cmd.extend(["-o", str(output)])
    else:
        cmd.extend(["-o", "/dev/null"])
    if rate != 11025:
        # Find rate index
        rate_idx = SAMPLE_RATES.index(rate) if rate in SAMPLE_RATES else 1
        cmd.extend(["-R", str(rate_idx)])
    if language:
        cmd.extend(["-L", language])
    if voice != 1:
        cmd.extend(["-v", str(voice)])
    cmd.append(text)

    start = time.perf_counter()
    try:
        result = subprocess.run(cmd, capture_output=True, timeout=30)
        elapsed = time.perf_counter() - start
        return {
            "success": result.returncode == 0,
            "time_sec": elapsed,
            "stdout": result.stdout.decode() if result.stdout else "",
            "stderr": result.stderr.decode() if result.stderr else "",
            "returncode": result.returncode,
        }
    except subprocess.TimeoutExpired:
        return {"success": False, "time_sec": 30, "error": "timeout"}
    except Exception as e:
        return {"success": False, "time_sec": time.perf_counter() - start, "error": str(e)}


def run_evv_on_device(abi: str, text: str, rate: int = 11025, language: Optional[str] = None,
                      voice: int = 1, output: str = "/data/local/tmp/bench.wav") -> Dict:
    """Run evv binary on Android device via ADB."""
    # Push binary if needed
    device_binary = f"/data/local/tmp/evv_{abi}"
    host_binary = find_evv_binary(abi)
    if not host_binary:
        return {"success": False, "error": f"Binary not found for {abi}"}

    # Push binary
    push_result = subprocess.run(["adb", "push", str(host_binary), device_binary], capture_output=True)
    if push_result.returncode != 0:
        return {"success": False, "error": f"ADB push failed: {push_result.stderr.decode()}"}

    # Make executable
    subprocess.run(["adb", "shell", f"chmod +x {device_binary}"], capture_output=True)

    # Build command
    cmd = [device_binary]
    cmd.extend(["-o", output])
    if rate != 11025:
        rate_idx = SAMPLE_RATES.index(rate) if rate in SAMPLE_RATES else 1
        cmd.extend(["-R", str(rate_idx)])
    if language:
        cmd.extend(["-L", language])
    if voice != 1:
        cmd.extend(["-v", str(voice)])
    cmd.append(text)

    # Run on device
    start = time.perf_counter()
    try:
        result = subprocess.run(["adb", "shell"] + cmd, capture_output=True, timeout=60)
        elapsed = time.perf_counter() - start
        return {
            "success": result.returncode == 0,
            "time_sec": elapsed,
            "stdout": result.stdout.decode() if result.stdout else "",
            "stderr": result.stderr.decode() if result.stderr else "",
            "returncode": result.returncode,
        }
    except subprocess.TimeoutExpired:
        return {"success": False, "time_sec": 60, "error": "timeout"}
    except Exception as e:
        return {"success": False, "time_sec": time.perf_counter() - start, "error": str(e)}


def benchmark_synthesis(abi: str, on_device: bool = False, iterations: int = 5,
                        debug: bool = False) -> Dict:
    """Run synthesis benchmarks."""
    binary = find_evv_binary(abi, debug)
    if not binary:
        return {"error": f"No binary found for {abi} {'(debug)' if debug else ''}"}

    print(f"Benchmarking {abi} {'(device)' if on_device else '(host)'}...")
    print(f"Binary: {binary} ({binary.stat().st_size:,} bytes)")

    results = {
        "abi": abi,
        "on_device": on_device,
        "iterations": iterations,
        "tests": [],
    }

    # Test each text length
    for text_name, text in TEST_TEXTS.items():
        print(f"  Testing {text_name} ({len(text)} chars)...")

        test_result = {
            "text_name": text_name,
            "text_length": len(text),
            "iterations": [],
        }

        for i in range(iterations):
            if on_device:
                res = run_evv_on_device(abi, text)
            else:
                res = run_evv_on_host(binary, text)

            test_result["iterations"].append(res)
            status = "✓" if res.get("success") else "✗"
            print(f"    Iter {i+1}/{iterations}: {status} {res.get('time_sec', 0):.3f}s")

        # Compute statistics
        times = [r["time_sec"] for r in test_result["iterations"] if r.get("success")]
        if times:
            test_result["stats"] = {
                "min": min(times),
                "max": max(times),
                "mean": statistics.mean(times),
                "median": statistics.median(times),
                "stdev": statistics.stdev(times) if len(times) > 1 else 0,
            }
            print(f"    Stats: mean={test_result['stats']['mean']:.3f}s "
                  f"median={test_result['stats']['median']:.3f}s "
                  f"stdev={test_result['stats']['stdev']:.3f}s")

        results["tests"].append(test_result)

    # Test sample rates (short text only)
    print("  Testing sample rates...")
    rate_results = []
    for rate in SAMPLE_RATES:
        times = []
        for i in range(min(3, iterations)):
            if on_device:
                res = run_evv_on_device(abi, TEST_TEXTS["short"], rate=rate)
            else:
                res = run_evv_on_host(binary, TEST_TEXTS["short"], rate=rate)
            if res.get("success"):
                times.append(res["time_sec"])

        if times:
            rate_results.append({
                "rate": rate,
                "mean_time": statistics.mean(times),
                "iterations": len(times),
            })
            print(f"    {rate} Hz: {statistics.mean(times):.3f}s")

    results["sample_rates"] = rate_results

    # Test languages (if available)
    print("  Testing languages...")
    lang_results = []
    for lang in TEST_LANGUAGES:
        times = []
        for i in range(min(2, iterations)):
            if on_device:
                res = run_evv_on_device(abi, TEST_TEXTS["short"], language=lang)
            else:
                res = run_evv_on_host(binary, TEST_TEXTS["short"], language=lang)
            if res.get("success"):
                times.append(res["time_sec"])

        if times:
            lang_results.append({
                "language": lang,
                "mean_time": statistics.mean(times),
                "iterations": len(times),
            })
            print(f"    {lang}: {statistics.mean(times):.3f}s")

    results["languages"] = lang_results

    return results


def benchmark_streaming(abi: str, on_device: bool = False, iterations: int = 3) -> Dict:
    """Benchmark streaming API (requires Kotlin/JNI test app)."""
    # This would require a test app with the streaming API
    # For now, return placeholder
    return {"note": "Streaming benchmark requires test app with JNI bridge"}


def benchmark_memory(abi: str, on_device: bool = False) -> Dict:
    """Estimate memory usage (binary size + runtime estimate)."""
    binary = find_evv_binary(abi)
    if not binary:
        return {"error": "Binary not found"}

    # Static analysis
    lib_size = (ROOT / "build" / "android" / abi / "libeloquick.so").stat().st_size if (ROOT / "build" / "android" / abi / "libeloquick.so").exists() else 0

    return {
        "abi": abi,
        "cli_binary_size": binary.stat().st_size,
        "shared_lib_size": lib_size,
        "estimated_runtime_ram_kb": 2048,  # Rough estimate: arena + buffers
        "note": "Actual runtime memory depends on text length, sample rate, and language",
    }


def main():
    parser = argparse.ArgumentParser(description="Benchmark EloQuick Android builds")
    parser.add_argument("--abi", default="all", choices=["all"] + ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"])
    parser.add_argument("--device", action="store_true", help="Run on Android device via ADB")
    parser.add_argument("--iterations", type=int, default=5, help="Iterations per test")
    parser.add_argument("--debug", action="store_true", help="Use debug build")
    parser.add_argument("--output", type=Path, help="Output JSON file")
    parser.add_argument("--memory-only", action="store_true", help="Only run memory analysis")
    args = parser.parse_args()

    abis = ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"] if args.abi == "all" else [args.abi]

    all_results = {
        "metadata": {
            "iterations": args.iterations,
            "on_device": args.device,
            "debug": args.debug,
        },
        "benchmarks": [],
        "memory": [],
    }

    if args.memory_only:
        for abi in abis:
            mem = benchmark_memory(abi, args.device)
            all_results["memory"].append(mem)
            print(f"{abi}: CLI={format_size(mem.get('cli_binary_size', 0))} "
                  f"lib={format_size(mem.get('shared_lib_size', 0))} "
                  f"est. RAM={mem.get('estimated_runtime_ram_kb', 0)} KB")
    else:
        for abi in abis:
            bench = benchmark_synthesis(abi, args.device, args.iterations, args.debug)
            all_results["benchmarks"].append(bench)

            mem = benchmark_memory(abi, args.device)
            all_results["memory"].append(mem)

    # Print summary
    print("\n" + "="*60)
    print("BENCHMARK SUMMARY")
    print("="*60)

    for bench in all_results["benchmarks"]:
        abi = bench.get("abi", "unknown")
        print(f"\n{abi}:")
        for test in bench.get("tests", []):
            stats = test.get("stats", {})
            if stats:
                print(f"  {test['text_name']:12} ({test['text_length']:4} chars): "
                      f"mean={stats['mean']:.3f}s median={stats['median']:.3f}s")

        if "sample_rates" in bench:
            print("  Sample rates:")
            for r in bench["sample_rates"]:
                print(f"    {r['rate']:5} Hz: {r['mean_time']:.3f}s")

    if args.output:
        with open(args.output, "w") as f:
            json.dump(all_results, f, indent=2)
        print(f"\nResults written to {args.output}")

    return 0


def format_size(bytes_: int) -> str:
    for unit in ['B', 'KB', 'MB']:
        if bytes_ < 1024:
            return f"{bytes_:.1f} {unit}"
        bytes_ /= 1024
    return f"{bytes_:.1f} GB"


if __name__ == "__main__":
    sys.exit(main())