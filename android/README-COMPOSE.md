# Compose UI (ported from EloquenceRevived)

Source: `EloquenceRevived`, repackaged `com.animeshahilya.eloquencerevived` → `com.eloquick`.

## What was done
- Old View UI removed: `android/app/java/com/eloquick/debug/` (9 files),
  `android/app/src/main/res/layout/`, `res/menu/` (11 files).
  Backup: `_incoming/old-ui-backup/`.
- New UI: `android/app/src/main/java/com/eloquick/ui/` (14 files),
  plus `data/` (3), `service/` (1), `tts/` (16), `EloquenceRevivedApp.kt`.
- Res: `xml/file_paths.xml`, `xml/tts_engine.xml`
  (`settingsActivity=com.eloquick.ui.MainActivity`), launcher drawables/mipmaps,
  `splash_background` color, `Theme.App.Starting`, `app_name=Eloquence Revived`.
- Manifest (single copy at `src/main/AndroidManifest.xml`):
  MainActivity / GetSampleTextActivity / TtsConfigProvider / EloquenceTtsService / FileProvider.
- Gradle scaffold: `android/settings.gradle.kts`, `android/build.gradle.kts`,
  `android/app/build.gradle.kts`, `android/gradle/libs.versions.toml`,
  `android/gradle.properties` (Compose BOM 2026.09.00, AGP 9.4.0, Kotlin 2.4.20,
  minSdk 26, compileSdk 37, Java 17), plus `gradlew` wrapper.
- Lint clean (27 warnings removed: 26 unused resources + 1 obsolete dep).

## Native bridge: DONE (2026-09-18)
- `android/eloquence_jni.c` (new, ~750 lines, written against `include/eci.h` +
  `docs/api.md`, not copied from anywhere): `libopenevv_jni.so` for
  `com.eloquick.tts.EloquenceNative` - slot cache per (language, hetero,
  ssml), bind-then-create, creation-time hetero, eciDictionary ABR mapping,
  dictionary vols 0/2 reload-on-change, sentence index marks with byte→char
  map, silence tails, eciDataAbort cancels, bounded drains. Verified with NDK
  r29 arm64 (`-fsyntax-only`, project's warning set) AND a full
  `py tools/build_android.py --abi arm64-v8a` (27s, `libopenevv_jni.so`
  linked, self-contained: only libm/libdl/libc NEEDED).
- `tools/build_android.py` now also links `libopenevv_jni.so`
  (`evn_lib_path`); `CMakeLists.txt` gained an `openevv_jni` target.
- The built `.so` is synced to `android/app/src/main/jniLibs/arm64-v8a/`
  (gitignored build artifact - rebuild, don't commit); re-sync after any
  engine/bridge change with the same build command.
- `eloquick_jni.c` speak entry applies the stored abbreviations flag via
  `eciSetParam(h, 3, on ? 0 : 1)` (driver ABRDICT semantics) - previously
  stored but never applied. Default stays on; flip with the existing setter.

## Donor pipeline (no revived code) + cleanup (2026-09-18)
- `pipeline/`: `EqCrashGuards` (NVDA-IBMTTS-Driver IBM tables),
  `EqClauses` (tgspeechbox normalize/clauses/script segments),
  `EqIndicText` (espeak-ng fork Indian preprocessing; OTP digit-splitting
  stays opt-in). Wired in both synthesis paths (`EloquenceTtsService.onSynthesizeText`
  and the `AppViewModel` preview, so preview == TalkBack output), in remit
  order: normalize → indic → guards → `prepareTextForSynthesis`. All pure-Java,
  tested with `javac`+self-tests (20 + 26 checks green).
- Removed duplicates/dead weight (all in git HEAD + `_incoming/old-ui-backup/`):
  legacy `android/app/java/` + `android/app/res/` + legacy-path
  `AndroidManifest.xml`, orphaned `tts/EloQuickEngine.kt` facade (nothing
  referenced it), 25 dead `ic_baseline_*` drawables, `dimens.xml`, empty
  `values-night/`, pruned `strings.xml` to the two used entries, dropped
  `_incoming/EloquenceRevived-ui/` (originals live in the EloquenceRevived repo).

## On-device verification (Pixel 8, API 37, arm64-v8a)
- Engine gate: `tools/test_device.py --abi arm64-v8a` — 10/10 languages
  synthesize, determinism, separation, compat, error paths all pass.
- APK installs, `libopenevv_jni.so` loads in both processes.
- TTS service registered (Service #3 in `pm query-services`), set as
  `tts_default_synth=com.eloquick`.
- Framework TTS binding requires manual selection in system Settings →
  Speech → Text-to-speech output (can't be automated).

## Known framework integration status
The TTS service is registered (`pm query-services` shows Service #3),
`tts_default_synth=com.eloquick` is set, and the native library loads in
both processes. The framework invokes the service (onStop logs appear) but
synthesis is cancelled immediately - framework binding needs deeper
debugging (likely callback contract issue in the `:tts` process). The
engine gate (`tools/test_device.py`) passes 10/10, proving the native
pipeline works end-to-end.

## Build
```bash
cd android
./gradlew assembleDebug
```
Rebuild native after any C changes:
```bash
cd ..
ANDROID_NDK_HOME=... python tools/build_android.py --abi arm64-v8a
cp build/android/arm64-v8a/libopenevv_jni.so android/app/src/main/jniLibs/arm64-v8a/
cd android && ./gradlew assembleDebug
```

## Open items for next session
- Framework TTS binding: debug why synthesis is cancelled immediately
  (likely callback contract in `:tts` process)
- TalkBack end-to-end test (needs TalkBack installed + manual selection)
- ProGuard release build verification
- Version bump (currently 1.0.0 / versionCode 1)