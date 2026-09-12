# OpenEVV on Android

OpenEVV is a portable C reimplementation of IBM's Embedded ViaVoice / ETI Eloquence text-to-speech engine. This repository provides build toolchains, CMake scripts, and optimizations tailored specifically for Android development and modern Android devices.

---

## Key Android Features

1. **Standalone Shared Library (`libopenevv.so`)**:
   Exposes the full, standard ECI API (`include/eci.h`) for easy integration into Android `TextToSpeechService` implementations, JNI bridges, or native engines.
2. **Standalone Command-Line Binary (`evv`)**:
   Position-independent executable (`-pie`) for direct testing via `adb shell` or running inside Termux on Android devices.
3. **Android 15+ 16KB Page Size Alignment**:
   Linked with `-Wl,-z,max-page-size=16384` to guarantee compatibility with upcoming Android devices requiring 16KB memory pages.
4. **All 9 Bundled Languages Pre-Compiled**:
   US English (`enus`), German (`dede`), British English (`engb`), Castilian Spanish (`eses`), Latin American Spanish (`esus`), Canadian French (`frca`), European French (`frfr`), Italian (`itit`), and Polish (`plpl`) are directly bundled and bound.
5. **Bit-Exact Speech Output**:
   Compiles at `-O2` with safe floating point and memory arena (`-DEVV_ARENA=1`) to preserve certified Eloquence audio samples across ARM and x86 architectures.

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
  │   ├── libopenevv.so
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
