# EloQuick (Android Port & Performance Toolchain)

> **EloQuick**: Ultra-fast reimplementation of IBM's Eloquence / Embedded ViaVoice text-to-speech engine tailored for Android and screen-reading performance. Features **-O3 DSP & formant vectorization**, **16KB page alignment** (Android 15+ compatible), multi-ABI support (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`), CMake integration for Android Studio/Gradle, and standalone CLI binaries for device shell and Termux.

[![Android Build](https://github.com/animeshahilya/openevv-android/actions/workflows/android.yml/badge.svg)](https://github.com/animeshahilya/openevv-android/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

*Upstream engine by [Mudb0y/openevv](https://github.com/Mudb0y/openevv).*

---

## Tailored for Android & Speed

This repository packages and optimizes Eloquence / OpenEVV specifically for Android applications, screen-reader services (like TalkBack engines), and standalone on-device command-line environments:

- **Ultra-Fast Formant Synthesis**: Klatt DSP resonators optimized with register caching, loop-invariant hoisting, branch prediction, and `-O3` auto-vectorization for instant, zero-latency speech.
- **16KB Page Size Alignment**: Linked with `-Wl,-z,max-page-size=16384` to guarantee full compatibility with Android 15+ devices.
- **Shared Libraries (`libeloquick.so` & `libopenevv.so`)**: Ready for direct dynamic linking or JNI bridging inside Android apps (`TextToSpeechService`). Both primary and legacy naming supported out-of-the-box, with a bundled `com.eloquick.tts.EloQuickEngine` JNI bridge (see `android/eloquick_jni.c` and `docs/android.md`).
- **Standalone CLI Executable (`eloquick` & `evv`)**: Position-independent binary (`-pie`) that can be pushed via `adb` or run directly inside Termux. Multi-language aware via `-L list` / `-L <id>` (a build holds ten languages but speaks the first unless told which).
- **All 10 Bundled Languages Pre-Linked**: Includes US English, British English, German, Castilian Spanish, Latin American Spanish, European French, Canadian French, Italian, Polish, and Japanese (with `rom/jajp` romanizer).
- **Full CMake & Python NDK Build Toolchain**: Build with a single command or integrate via Gradle `externalNativeBuild`. The engine compiles once per ABI (object library); both `.so`s and the CLI link the same objects. Trim with `--langs` / `-DOPENEVV_LANGS` for smaller APKs.

### Android Quickstart

Build shared libraries and CLI for Android (`arm64-v8a`):
```bash
python tools/build_android.py --abi arm64-v8a
```

Build for all 4 Android architectures (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`):
```bash
python tools/build_android.py --abi all
```

Output files are placed under `build/android/<abi>/`:
- `libeloquick.so` (Primary shared library exposing standard `include/eci.h` API)
- `libopenevv.so`  (Compatibility mirror for existing OpenEVV apps)
- `eloquick`       (Primary standalone command-line synthesis tool)
- `evv`            (Compatibility mirror)

For detailed Android integration instructions, see [docs/android.md](docs/android.md).

---

# openevv

A portable Eloquence. IBM's Embedded ViaVoice text-to-speech engine, taken out of its 1999 Windows objects and rebuilt as C that compiles and speaks on a machine it was never meant to run on.

It speaks, and it spoke IBM's own samples: the audio came out byte for byte identical to IBM's binary across every test case of every language, from both a thirty-two and a sixty-four bit build, and across all nine languages the SDK shipped. That is what the transcription was proved by; the engine is being changed on purpose now, so what is held to from here is a recorded answer for each of 979 cases rather than IBM's. Nothing is borrowed at build time. No DLL, no SDK, no Wine.

    make
    ./build/evv -o hello.wav "Hello from Eloquence."

That wants a C compiler, Python, and about a quarter of an hour, most of it compiling the rules. `make RULES=bytecode` is the same engine in half a minute, saying the same samples; it runs the rules interpreted rather than compiled, which costs rather more than half the speed. On Linux this command writes a wave file rather than playing it; pipe it into a player to hear it at once:

    ./build/evv "Hello from Eloquence." | aplay -q -

For desktop and screen-reader speech there is a native Speech Dispatcher output module: `make speechd` builds it, it hands its samples back to Speech Dispatcher rather than opening a device, and it offers every language in the build with its eight voices. `docs/speech-dispatcher.md` says how to install it, how to try it without installing it, and what it does not do.

On Windows there is a speak window. Take `evvspeak.exe` from the latest release, type something, pick one of the eight voices, and hear it; `evv.exe` beside it is the same engine on the command line. One file each, nothing to install, and `make win` builds both from here with mingw.

The engine is a library as well as a command, under the names IBM published, so a program written against IBM's can load ours instead -- a screen reader add-on, for instance. `make so` builds `build/libeci.so` and `include/eci.h` is what to compile against; `make win` and `make win32` build `build/eci.dll` and `build/eci32.dll` from the same source. The Windows pair is in the release, in folders that say which bitness is which: an add-on that loads the engine into the reader's own process wants the reader's bitness, and the most used one hosts the engine in a thirty-two bit process of its own whatever the reader is. `docs/using.md` is how to get from a checkout to speech in your own program, `docs/api.md` the call-by-call reference, and `docs/quirks.md` what will trip you.

It reads SSML. A document goes in and the annotations the engine already understands come out -- say-as for numbers, ordinals, dates, times, telephone numbers and currency, prosody for rate, pitch, range and volume, emphasis, voice selection by gender and age, pronunciations in IPA or in the engine's own alphabet, pauses, marks and language switching. It is IBM's own reader, transcribed, and it answers what IBM's answers over 176 documents. Turning it on takes three calls of the published interface and `test/lib/dll.c` is the shortest example of them.

`./build/evv -h` says what the options are, and `./build/evv -l` says what each of the eight voices is set to.

## What is here

`src` is the engine: hand-written C, one file per object in IBM's own module decomposition, so that a file can be checked against the object it came from. It is in four groups -- `delta` for the machine that runs a language's rules, `klatt` for the formant synthesiser that makes the sound, `port` for what the port supplies itself, and `eci` for the published interface and the machinery behind it, which is most of it and has groups of its own.

`lang/enus` is US English: the rules, the constants they read, the sets, the link tables, the voice presets and the dictionary. This is the part lifted out of IBM's objects rather than written, and it is in the tree so that the engine builds without the SDK. The rules are text there, one file to an object in `lang/enus/rules`, and what the engine runs is written out of that text by every build. `lang/dede` is German, lifted the same way. A build takes as many languages as it is given -- `make LANGS="lang/enus lang/dede"` puts both in one binary and the caller picks between them. English is the one that is finished; German matches IBM over the cases there are for it. `docs/status.md` says in which configurations.

`cli/evv.c` is the command above and `win/speak.c` is the speak window. `cli/probe.c` is the same engine behind a front that reports what it answered at every step, which is what `test` sets against IBM's binary case for case. `lib` is the engine under the names IBM published and `include/eci.h` is what a program compiles against. `tools` is grouped by what a tool acts on: `rules` for the whole rules toolchain, `module` for everything else in a language, `engine` for what acts on `src`, `rom` for the Japanese romanizer, `sdk` for unpacking IBM's libraries, and `measure` for the three that answer a question about the sound in numbers. `reference` builds IBM's own binary under Wine, which is what the tests compare against.

## Documentation

`docs/using.md` is how to use the engine in your own program, `docs/api.md` the published interface call by call, and `docs/quirks.md` the things that cost an afternoon if nobody says them first; those three are for someone building against this rather than on it. `docs/building.md` is what you need, what to build, and what every variable does. `docs/rules.md` is the rules, in all three forms, and how a rule of ours is written and proved. `docs/language.md` is everything else in a language module, and what it takes to add one. `docs/testing.md` is what proves any of it. `docs/windows.md` is the Windows side, the library and the screen reader add-on. `docs/speech-dispatcher.md` is the Linux one: the Speech Dispatcher output module, how to install it and what it does not do. `docs/tree.md` says what every directory is for, and `docs/status.md` what works, what does not, and what has not been started. `docs/notes` is the finished results, one to a file, which are the answers rather than the state: Polish, SSML, the crashing strings, the sample rates, a language module as text, the comparison against Apple's Eloquence, and the way towards an engine with no virtual machine in it.

## Licence and provenance

Our own work -- the engine, the two front ends, the tools, the tests and the documents -- is under the MIT licence in LICENSE. Two files in `src` are the exception and are data rather than code: `klatt_tables.c` is the synthesiser's own tables and `eci_xmltok_tables.c` is the eight tables the XML scanner is, both lifted out of IBM's objects by tools in `tools`, and both IBM's on the terms below.

The language data under `lang` is not ours. It is transcribed out of IBM's Embedded ViaVoice objects, byte for byte where the engine's arithmetic depends on it, and it is IBM's work. The MIT licence does not cover it and we are in no position to license it to anyone. NOTICE says what it is, whose it is, and who the rights in it may belong to today.

Nothing else of IBM's is here. The objects the port was read out of, and the headers and symbol tables it was read with, are not in the tree and are not needed to build. IBM still serves the SDK they came out of from its own public download host, and `docs/building.md` says where it is and what to do with it -- which is what anyone wanting to check this work against the original would start from.
