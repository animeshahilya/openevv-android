# EloQuick on Android

EloQuick is an ultra-fast C reimplementation of IBM's Embedded ViaVoice / ETI Eloquence text-to-speech engine. This repository provides build toolchains, CMake scripts, and optimizations tailored specifically for Android development, screen readers (TalkBack), and modern mobile devices.

---

## Key Android Features & Speed Enhancements

1. **Ultra-Fast Formant Synthesis**:
   Synthesizes speech at extreme rates with negligible CPU footprint. DSP loops are optimized with register-caching of resonator states, loop-invariant hoisting, branch prediction hints (`__builtin_expect`), and Clang `-O3` auto-vectorization.
2. **Dual Standalone Shared Libraries (`libeloquick.so` & `libopenevv.so`)**:
   Exposes the full standard ECI API (`include/eci.h`) for easy integration into Android `TextToSpeechService` implementations, JNI bridges, or accessibility services. Both new (`eloquick`) and legacy (`openevv`) library names are output.
3. **Standalone Command-Line Binary (`eloquick` & `evv`)**:
   Position-independent executable (`-pie`) for direct testing via `adb shell` or running inside Termux on Android devices.
4. **Android 15+ 16KB Page Size Alignment**:
   Linked with `-Wl,-z,max-page-size=16384` to guarantee compatibility with Android 15+ devices requiring 16KB memory pages.
5. **All 9 Bundled Languages Pre-Compiled**:
   US English (`enus`), German (`dede`), British English (`engb`), Castilian Spanish (`eses`), Latin American Spanish (`esus`), Canadian French (`frca`), European French (`frfr`), Italian (`itit`), and Polish (`plpl`) are directly bundled and bound.
6. **Bit-Exact Speech Output with Memory Arena**:
   Uses `-DEVV_ARENA=1` with zero runtime allocations during synthesis to preserve certified Eloquence audio samples across ARM and x86 architectures with zero latency.

---

## Building with Python Build Script

A dedicated multi-ABI builder is provided in `tools/build_android.py`.

### Prerequisites

- **Android NDK**: Version r26 or newer (NDK 27+ recommended). Set `ANDROID_NDK_HOME` or `ANDROID_NDK_ROOT` to your NDK installation directory.
- **Python**: Python 3.8+ with UTF-8 support.

### Compilation

Build for the default target (`arm64-v8a`):
```bash
python tools/build_android.py
```

Build for a specific ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`, or `x86`):
```bash
python tools/build_android.py --abi arm64-v8a
python tools/build_android.py --abi armeabi-v7a
python tools/build_android.py --abi x86_64
python tools/build_android.py --abi x86
```

Build all 4 ABIs at once:
```bash
python tools/build_android.py --abi all
```

Output binaries are placed in:
```
build/android/
  ├── arm64-v8a/
  │   ├── libeloquick.so
  │   ├── libopenevv.so
  │   ├── eloquick
  │   └── evv
  ├── armeabi-v7a/
  ├── x86_64/
  └── x86/
```

---

## Integrating via CMake / Android Studio Gradle

Add OpenEVV directly to your Android project's `CMakeLists.txt`:

```cmake
# In your app's CMakeLists.txt:
add_subdirectory(path/to/openevv-android ${CMAKE_CURRENT_BINARY_DIR}/openevv)

# Link against libopenevv.so:
target_link_libraries(your_tts_jni_lib PRIVATE openevv)
```

In your app's `build.gradle.kts`:
```kotlin
android {
    defaultConfig {
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }
}
```

---

## Running Standalone CLI on Device via ADB

You can push and execute the standalone CLI on an Android phone via ADB:

```bash
# Push binary to temporary execution directory
adb push build/android/arm64-v8a/evv /data/local/tmp/evv
adb shell chmod +x /data/local/tmp/evv

# Synthesize speech to a WAV file
adb shell "/data/local/tmp/evv -o /data/local/tmp/hello.wav 'Hello from OpenEVV on Android.'"

# Pull audio back to your host
adb pull /data/local/tmp/hello.wav .
```

---

## Termux Usage

If running inside Termux on an ARM64 Android device:
```bash
# Copy evv to your bin folder
cp build/android/arm64-v8a/evv $PREFIX/bin/evv
chmod +x $PREFIX/bin/evv

# Speak or generate audio
evv -o test.wav "OpenEVV running natively on Android via Termux."
```
