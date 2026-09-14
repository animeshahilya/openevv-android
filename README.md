# EloQuick for Android

IBM Eloquence / Embedded ViaVoice TTS as portable C, built for Android. NDK + CMake, 16KB-page clean (Android 15+), `arm64-v8a` / `armeabi-v7a` / `x86_64` / `x86`. Upstream engine: [Mudb0y/openevv](https://github.com/Mudb0y/openevv).

[![Android Build](https://github.com/animeshahilya/openevv-android/actions/workflows/android.yml/badge.svg)](https://github.com/animeshahilya/openevv-android/actions/workflows/android.yml) [![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Scope is Android only. Blessed paths are `tools/build_android.py`, `CMakeLists.txt`, and `android/`. The inherited desktop tree (`Makefile`, `win/`, `speechd/`, `nvda/`, `reference/`, desktop docs) stays for provenance and merges; it is not built, tested, or supported here.

## Build

```bash
python tools/build_android.py --abi arm64-v8a
python tools/build_android.py --abi all
python tools/build_android.py --langs enus,dede
```

Per ABI, under `build/android/<abi>/`: `libeloquick.so`, `libopenevv.so`, `eloquick`, `evv`. The engine compiles once; both `.so` files and the CLI link the same objects. `evv` is a copy of `eloquick` (argv[0]-aware). Flags: `-O3 -ffp-contract=fast`, `-Wl,-z,max-page-size=16384`, ThinLTO where the toolchain accepts it.

## Integrate

```cmake
add_subdirectory(path/to/openevv-android ${CMAKE_CURRENT_BINARY_DIR}/openevv)
target_link_libraries(your_tts_jni_lib PRIVATE eloquick)
```

```kotlin
init { System.loadLibrary("eloquick") }
@JvmStatic external fun nativeCreate(language: Int): Long
@JvmStatic external fun nativeSynth(handle: Long, text: String): ShortArray?
```

Full bridge, service, ADB/Termux, and app docs: [docs/android.md](docs/android.md).

## Contracts

- Load one `.so` per process: `eloquick`. `libopenevv.so` is a pure-ECI compat mirror with no JNI inside.
- Resolve languages first: `nativeGetLanguages()` then `nativeCreate(id)`. `0` return means unknown; call `nativeDestroy` when done.
- `nativeSynth` is bounded: 64KB text, ~2min PCM, 30s drain. Long or stoppable speech goes on `nativeStreamSpeak/Read/Stop`.
- Encoding: input is UTF-8, validated at the boundary. `plpl` takes UTF-8, `jajp` takes romanized input, all others take Latin-1 bytes. Malformed input returns null/false.

## Languages

Ten, pre-linked: `enus dede engb eses esus frca frfr itit plpl jajp` (`jajp` via `rom/jajp`). Trim with `--langs` / `-DOPENEVV_LANGS` to cut APK size. A build holds all listed languages but speaks the first unless selected (`-L list` / `-L <id>`, or the JNI language id).

## Check on device

```bash
py tools/build_android.py --abi arm64-v8a --debug
py tools/test_device.py --abi arm64-v8a
py tools/build_debug_apk.py --abi arm64-v8a --install
```

`test_device.py` gates usage, language list, per-language WAV shape, determinism, EN/DE separation, and compat parity. Debug APK self-test logs `EQTEST RESULT ok=N fail=M`.

## Provenance

Engine, tools, and docs are MIT in LICENSE, except `src/klatt_tables.c` and `src/eci_xmltok_tables.c` (IBM data) and all of `lang/` (transcribed IBM language data, not MIT-licensable). See NOTICE. No SDK, DLL, or Wine needed to build. Community fixes folded in from the same upstream family (`stormdragon2976`, `Eagalon`, `beyondsighttech`, `evvdroid`, `eloquence-revived`) are credited in `docs/android.md`.

<details>
<summary>Upstream desktop reference (not supported here)</summary>

Desktop build, Speech Dispatcher module, Windows `eci.dll`/`evvspeak.exe`, SSML, and the 979-case `test/matrix.sh` gate live upstream and in the inherited tree. This fork does not run them; see `docs/building.md`, `docs/testing.md`, `docs/windows.md`, `docs/speech-dispatcher.md` for reference.

</details>
