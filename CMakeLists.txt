cmake_minimum_required(VERSION 3.22.1)
project(eloquick LANGUAGES C VERSION 1.0.0 DESCRIPTION "EloQuick: IBM Eloquence / Embedded ViaVoice TTS for Android")

# =============================================================================
# CMake Policy Settings (modern CMake)
# =============================================================================
cmake_policy(SET CMP0077 NEW)  # option() honors normal variables
cmake_policy(SET CMP0091 NEW)  # find_package() caller sets <PackageName>_ROOT
cmake_policy(SET CMP0135 NEW)  # file(GLOB) with CONFIGURE_DEPENDS

# =============================================================================
# C Standard Configuration
# =============================================================================
set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

# Default to Release build type
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

# Add SizeOptimize build type
set(CMAKE_CONFIGURATION_TYPES "Debug;Release;RelWithDebInfo;MinSizeRel;SizeOptimize")

# =============================================================================
# Configuration Options
# =============================================================================
option(OPENEVV_BUILD_CLI "Build standalone CLI executables (eloquick/evv)" ON)
option(OPENEVV_ENABLE_JNI "Build JNI bridge (android/eloquick_jni.c) into shared libs" ON)
option(OPENEVV_ENABLE_THIN_LTO "Enable ThinLTO when toolchain supports it" ON)
option(OPENEVV_ENABLE_ICF "Enable Identical Code Folding (--icf=all) when supported" ON)
option(OPENEVV_STRIP_RELEASE "Strip release binaries (-Wl,-s)" ON)
option(OPENEVV_HARDENING "Enable additional hardening flags" ON)

# Languages bundled into binaries (semicolon-separated list for CMake)
set(OPENEVV_LANGS "enus;dede;engb;eses;esus;frca;frfr;itit;plpl;jajp"
    CACHE STRING "Language modules bundled into EloQuick binaries (semicolon-separated)")

# Android-specific: minimum API level
if(ANDROID AND NOT ANDROID_NATIVE_API_LEVEL)
    set(ANDROID_NATIVE_API_LEVEL 26 CACHE STRING "Minimum Android API level")
endif()

# =============================================================================
# Platform Detection & Toolchain Setup
# =============================================================================
if(ANDROID)
    # Android NDK build - toolchain file provides compiler
    set(IS_ANDROID TRUE)
else()
    set(IS_ANDROID FALSE)
    # Host build - detect if cross-compiling for Android manually
    if(CMAKE_SYSTEM_NAME STREQUAL "Android")
        set(IS_ANDROID TRUE)
    endif()
endif()

# =============================================================================
# Compiler Flag Management (modern approach with target_compile_options)
# =============================================================================
# Base flags for all targets
set(OPENEVV_BASE_COMPILE_OPTIONS
    -DEVV_ARENA=1
    -DECI_BUILDING=1
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
    -fPIC
    -fsigned-char
    -fno-math-errno
    -fno-trapping-math
    -ffp-contract=fast
)

# Engine-specific flags (legacy code, suppress warnings)
set(OPENEVV_ENGINE_COMPILE_OPTIONS
    -w
    -Wno-implicit-function-declaration
    -Werror=int-conversion
    -Werror=incompatible-pointer-types
    -fno-strict-aliasing  # Engine code has type-punning, disable strict aliasing for it
)

# Modern code flags (JNI bridge, CLI - keep warnings visible)
set(OPENEVV_MODERN_COMPILE_OPTIONS
    -Wall
    -Wextra
    -Wno-unused-parameter
    -Wno-missing-field-initializers
    -fstrict-aliasing
)

# Optimization flags
if(CMAKE_BUILD_TYPE STREQUAL "Release" OR CMAKE_BUILD_TYPE STREQUAL "MinSizeRel")
    set(OPENEVV_OPT_COMPILE_OPTIONS -O3 -fomit-frame-pointer
        -fstrict-aliasing
        -fstrict-overflow
        -fmerge-all-constants
        -fno-semantic-interposition
        -fno-trapping-math
        -ffp-contract=fast
        -fno-math-errno
        -funroll-loops
        -ftree-vectorize
        -fvect-cost-model=very-cheap
        -fno-ident
        -fno-unwind-tables
        -fno-asynchronous-unwind-tables
    )
elseif(CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(OPENEVV_OPT_COMPILE_OPTIONS -O0 -g -DDEBUG=1)
elseif(CMAKE_BUILD_TYPE STREQUAL "RelWithDebInfo")
    set(OPENEVV_OPT_COMPILE_OPTIONS -O2 -g -fomit-frame-pointer
        -ftree-vectorize
    )
else()
    set(OPENEVV_OPT_COMPILE_OPTIONS -O2)
endif()

# PGO support (Profile-Guided Optimization)
option(OPENEVV_PGO_GENERATE "Enable profile generation for PGO" OFF)
option(OPENEVV_PGO_USE "Enable profile use for PGO" OFF)

if(OPENEVV_PGO_GENERATE)
    list(APPEND OPENEVV_OPT_COMPILE_OPTIONS -fprofile-generate)
    list(APPEND OPENEVV_BASE_LINK_OPTIONS -fprofile-generate)
elseif(OPENEVV_PGO_USE)
    list(APPEND OPENEVV_OPT_COMPILE_OPTIONS -fprofile-use)
    list(APPEND OPENEVV_BASE_LINK_OPTIONS -fprofile-use)
endif()

# Architecture-specific flags for ARM64 (Pixel 8 target)
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
    list(APPEND OPENEVV_OPT_COMPILE_OPTIONS
        -march=armv8.2-a
        -mtune=cortex-a76
        -moutline-atomics
    )
endif()

# Size optimization flags
if(CMAKE_BUILD_TYPE STREQUAL "MinSizeRel" OR CMAKE_BUILD_TYPE STREQUAL "SizeOptimize")
    list(APPEND OPENEVV_OPT_COMPILE_OPTIONS
        -fno-unroll-loops
        -fno-inline-functions
        -fno-peel-loops
        -fno-toplevel-reorder
    )
endif()

# Hardening flags (when enabled)
if(OPENEVV_HARDENING)
    set(OPENEVV_HARDENING_COMPILE_OPTIONS
        -fstack-protector-strong
        -D_FORTIFY_SOURCE=2
    )
    set(OPENEVV_HARDENING_LINK_OPTIONS
        -Wl,-z,relro,-z,now
    )
else()
    set(OPENEVV_HARDENING_COMPILE_OPTIONS)
    set(OPENEVV_HARDENING_LINK_OPTIONS)
endif()

# Combine for engine targets
set(OPENEVV_ENGINE_ALL_COMPILE_OPTIONS
    ${OPENEVV_BASE_COMPILE_OPTIONS}
    ${OPENEVV_ENGINE_COMPILE_OPTIONS}
    ${OPENEVV_OPT_COMPILE_OPTIONS}
    ${OPENEVV_HARDENING_COMPILE_OPTIONS}
)

# Combine for modern targets (JNI, CLI)
set(OPENEVV_MODERN_ALL_COMPILE_OPTIONS
    ${OPENEVV_BASE_COMPILE_OPTIONS}
    ${OPENEVV_MODERN_COMPILE_OPTIONS}
    ${OPENEVV_OPT_COMPILE_OPTIONS}
    ${OPENEVV_HARDENING_COMPILE_OPTIONS}
)

# =============================================================================
# Linker Flag Management
# =============================================================================
set(OPENEVV_BASE_LINK_OPTIONS
    -Wl,--gc-sections
    ${OPENEVV_HARDENING_LINK_OPTIONS}
)

if(IS_ANDROID)
    # Android 15+ 16KB ELF page size alignment
    set(OPENEVV_ANDROID_LINK_OPTS
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384
    )

    # Check for ICF support
    if(OPENEVV_ENABLE_ICF)
        include(CheckLinkerFlag)
        check_linker_flag(C "-Wl,--icf=all" HAVE_ICF_ALL)
        if(HAVE_ICF_ALL)
            list(APPEND OPENEVV_ANDROID_LINK_OPTS -Wl,--icf=all -Wl,-O3)
        endif()
    endif()

    list(APPEND OPENEVV_BASE_LINK_OPTIONS ${OPENEVV_ANDROID_LINK_OPTS})
endif()

# ThinLTO (probe at configure time)
if(OPENEVV_ENABLE_THIN_LTO AND NOT IS_ANDROID)
    include(CheckCCompilerFlag)
    check_c_compiler_flag("-flto=thin" HAVE_THIN_LTO)
    if(HAVE_THIN_LTO)
        list(APPEND OPENEVV_BASE_COMPILE_OPTIONS -flto=thin)
        list(APPEND OPENEVV_BASE_LINK_OPTIONS -flto=thin)
    endif()
elseif(OPENEVV_ENABLE_THIN_LTO AND IS_ANDROID)
    # On Android, defer to build_android.py which probes at build time
    # CMake will add -flto=thin if toolchain supports it via add_link_options
    include(CheckCCompilerFlag)
    check_c_compiler_flag("-flto=thin" HAVE_THIN_LTO)
    if(HAVE_THIN_LTO)
        list(APPEND OPENEVV_BASE_COMPILE_OPTIONS -flto=thin)
        list(APPEND OPENEVV_BASE_LINK_OPTIONS -flto=thin)
    endif()
endif()

# Strip release binaries
if(OPENEVV_STRIP_RELEASE AND CMAKE_BUILD_TYPE STREQUAL "Release")
    list(APPEND OPENEVV_BASE_LINK_OPTIONS -Wl,-s)
endif()

# =============================================================================
# Include Directories
# =============================================================================
set(OPENEVV_CORE_INCLUDE_DIRS
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_BINARY_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# =============================================================================
# Source Collection
# =============================================================================
# Core engine sources (excluding Win32 port)
file(GLOB_RECURSE OPENEVV_SRC_FILES CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/src/*.c"
)
list(FILTER OPENEVV_SRC_FILES EXCLUDE REGEX ".*/port_win32\\.c$")

# Language sources
set(OPENEVV_LANG_FILES "")
set(OPENEVV_LANG_INCLUDE_DIRS "")
foreach(LANG ${OPENEVV_LANGS})
    set(LANG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/lang/${LANG}")
    if(NOT EXISTS "${LANG_DIR}")
        message(FATAL_ERROR "Language directory missing: ${LANG_DIR}")
    endif()
    list(APPEND OPENEVV_LANG_INCLUDE_DIRS "${LANG_DIR}")
    file(GLOB LANG_SOURCES CONFIGURE_DEPENDS "${LANG_DIR}/*.c")
    # Exclude generated delta_rules_c*.c files (built from bytecode)
    list(FILTER LANG_SOURCES EXCLUDE REGEX ".*/delta_rules_c.*\\.c$")
    list(APPEND OPENEVV_LANG_FILES ${LANG_SOURCES})
endforeach()

# Japanese romanizer
set(OPENEVV_ROM_FILES "")
set(OPENEVV_ROM_INCLUDE_DIRS "")
set(OPENEVV_ROM_DEFINITIONS "")
if("jajp" IN_LIST OPENEVV_LANGS)
    file(GLOB OPENEVV_ROM_FILES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/rom/jajp/*.c")
    list(APPEND OPENEVV_ROM_INCLUDE_DIRS "${CMAKE_CURRENT_SOURCE_DIR}/rom/jajp")
    set(OPENEVV_ROM_DEFINITIONS -DEVV_ROM_JAJP)
endif()

# =============================================================================
# Generated Sources: delta_langs.c
# =============================================================================
set(DELTA_LANGS_C "${CMAKE_CURRENT_BINARY_DIR}/delta_langs.c")
file(WRITE "${DELTA_LANGS_C}" "/* Generated by CMake */\n#include \"delta_lang.h\"\n\n")
foreach(LANG ${OPENEVV_LANGS})
    file(APPEND "${DELTA_LANGS_C}" "extern delta_language delta_lang_${LANG};\n")
    file(APPEND "${DELTA_LANGS_C}" "void delta_lang_bind_${LANG}(void);\n")
endforeach()
file(APPEND "${DELTA_LANGS_C}" "\nconst delta_language *const delta_languages[] = {\n")
foreach(LANG ${OPENEVV_LANGS})
    file(APPEND "${DELTA_LANGS_C}" "    &delta_lang_${LANG},\n")
endforeach()
file(APPEND "${DELTA_LANGS_C}" "    0,\n};\n\n")
file(APPEND "${DELTA_LANGS_C}" "void delta_lang_bind_all(void)\n{\n")
foreach(LANG ${OPENEVV_LANGS})
    file(APPEND "${DELTA_LANGS_C}" "    delta_lang_bind_${LANG}();\n")
endforeach()
file(APPEND "${DELTA_LANGS_C}" "}\n")

list(APPEND OPENEVV_LANG_FILES "${DELTA_LANGS_C}")

# =============================================================================
# JNI Bridge Sources
# =============================================================================
set(OPENEVV_JNI_FILES "")
set(OPENEVV_JNI_DEFINITIONS "")
if(OPENEVV_ENABLE_JNI AND NOT WIN32 AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/android/eloquick_jni.c")
    if(IS_ANDROID)
        # On Android, JNI headers come from the NDK toolchain
        set(OPENEVV_JNI_FILES "${CMAKE_CURRENT_SOURCE_DIR}/android/eloquick_jni.c")
    else()
        # Host build: try to find JNI via find_package
        find_package(JNI QUIET)
        if(JNI_FOUND)
            set(OPENEVV_JNI_FILES "${CMAKE_CURRENT_SOURCE_DIR}/android/eloquick_jni.c")
            set(OPENEVV_JNI_DEFINITIONS -DJNI_AVAILABLE)
        else()
            message(STATUS "JNI not found; building shared libs without android/eloquick_jni.c")
        endif()
    endif()
endif()

# =============================================================================
# Object Library (compile engine once, link many times)
# =============================================================================
add_library(eloquick_objs OBJECT
    ${OPENEVV_SRC_FILES}
    ${OPENEVV_LANG_FILES}
    ${OPENEVV_ROM_FILES}
)

target_include_directories(eloquick_objs PUBLIC
    ${OPENEVV_CORE_INCLUDE_DIRS}
    ${OPENEVV_LANG_INCLUDE_DIRS}
    ${OPENEVV_ROM_INCLUDE_DIRS}
)

target_compile_options(eloquick_objs PRIVATE ${OPENEVV_ENGINE_ALL_COMPILE_OPTIONS})

if(OPENEVV_ROM_DEFINITIONS)
    target_compile_definitions(eloquick_objs PUBLIC ${OPENEVV_ROM_DEFINITIONS})
endif()

# Apply modern compile options to JNI bridge source specifically
if(OPENEVV_JNI_FILES)
    set_source_files_properties(${OPENEVV_JNI_FILES}
        PROPERTIES COMPILE_OPTIONS "${OPENEVV_MODERN_ALL_COMPILE_OPTIONS}"
    )
endif()

# =============================================================================
# Shared Library: libeloquick.so (primary, with JNI bridge)
# =============================================================================
add_library(eloquick SHARED
    $<TARGET_OBJECTS:eloquick_objs>
    ${CMAKE_CURRENT_SOURCE_DIR}/lib/eci_api.c
    ${OPENEVV_JNI_FILES}
)

target_include_directories(eloquick PUBLIC
    ${OPENEVV_CORE_INCLUDE_DIRS}
    ${OPENEVV_LANG_INCLUDE_DIRS}
    ${OPENEVV_ROM_INCLUDE_DIRS}
)

target_link_libraries(eloquick PUBLIC m pthread log)

    if(OPENEVV_ROM_DEFINITIONS)
    target_compile_definitions(eloquick PUBLIC ${OPENEVV_ROM_DEFINITIONS})
endif()
if(OPENEVV_JNI_DEFINITIONS)
    target_compile_definitions(eloquick PUBLIC ${OPENEVV_JNI_DEFINITIONS})
endif()

target_link_options(eloquick PRIVATE ${OPENEVV_BASE_LINK_OPTIONS})
set_target_properties(eloquick PROPERTIES
    OUTPUT_NAME "eloquick"
    SOVERSION 1
    VERSION ${PROJECT_VERSION}
    ANDROID_SO_UNDEFINED OFF  # Allow undefined symbols on Android (resolved at runtime)
)

# =============================================================================
# Shared Library: libopenevv_jni.so (whole-utterance bridge for
# com.eloquick.tts.EloquenceNative - the Compose UI's engine path)
# =============================================================================
set(OPENEVV_EVN_JNI_FILE "${CMAKE_CURRENT_SOURCE_DIR}/android/eloquence_jni.c")
if(OPENEVV_ENABLE_JNI AND EXISTS "${OPENEVV_EVN_JNI_FILE}")
    set_source_files_properties(${OPENEVV_EVN_JNI_FILE}
        PROPERTIES COMPILE_OPTIONS "${OPENEVV_MODERN_ALL_COMPILE_OPTIONS}"
    )
    add_library(openevv_jni SHARED
        $<TARGET_OBJECTS:eloquick_objs>
        ${CMAKE_CURRENT_SOURCE_DIR}/lib/eci_api.c
        ${OPENEVV_EVN_JNI_FILE}
    )

    target_include_directories(openevv_jni PUBLIC
        ${OPENEVV_CORE_INCLUDE_DIRS}
        ${OPENEVV_LANG_INCLUDE_DIRS}
        ${OPENEVV_ROM_INCLUDE_DIRS}
    )

    target_link_libraries(openevv_jni PUBLIC m pthread log)

    if(OPENEVV_ROM_DEFINITIONS)
        target_compile_definitions(openevv_jni PUBLIC ${OPENEVV_ROM_DEFINITIONS})
    endif()

    target_link_options(openevv_jni PRIVATE ${OPENEVV_BASE_LINK_OPTIONS})
    set_target_properties(openevv_jni PROPERTIES
        OUTPUT_NAME "openevv_jni"
        SOVERSION 1
        VERSION ${PROJECT_VERSION}
        ANDROID_SO_UNDEFINED OFF
    )
else()
    message(STATUS "libopenevv_jni skipped (android/eloquence_jni.c missing or OPENEVV_ENABLE_JNI=OFF)")
endif()

# =============================================================================
# Shared Library: libopenevv.so (compatibility, no JNI)
# =============================================================================
add_library(openevv SHARED
    $<TARGET_OBJECTS:eloquick_objs>
    ${CMAKE_CURRENT_SOURCE_DIR}/lib/eci_api.c
)

target_include_directories(openevv PUBLIC
    ${OPENEVV_CORE_INCLUDE_DIRS}
    ${OPENEVV_LANG_INCLUDE_DIRS}
    ${OPENEVV_ROM_INCLUDE_DIRS}
)

target_link_libraries(openevv PUBLIC m pthread)

if(OPENEVV_ROM_DEFINITIONS)
    target_compile_definitions(openevv PUBLIC ${OPENEVV_ROM_DEFINITIONS})
endif()

target_link_options(openevv PRIVATE ${OPENEVV_BASE_LINK_OPTIONS})
set_target_properties(openevv PROPERTIES
    OUTPUT_NAME "openevv"
    SOVERSION 1
    VERSION ${PROJECT_VERSION}
    ANDROID_SO_UNDEFINED OFF
)

# =============================================================================
# CLI Executable: eloquick (and evv copy)
# =============================================================================
if(OPENEVV_BUILD_CLI)
    add_executable(eloquick_cli
        $<TARGET_OBJECTS:eloquick_objs>
        ${CMAKE_CURRENT_SOURCE_DIR}/cli/evv.c
    )

    target_include_directories(eloquick_cli PRIVATE
        ${OPENEVV_CORE_INCLUDE_DIRS}
        ${OPENEVV_LANG_INCLUDE_DIRS}
        ${OPENEVV_ROM_INCLUDE_DIRS}
    )

    target_link_libraries(eloquick_cli PRIVATE m pthread)

    if(OPENEVV_ROM_DEFINITIONS)
        target_compile_definitions(eloquick_cli PRIVATE ${OPENEVV_ROM_DEFINITIONS})
    endif()

    target_compile_options(eloquick_cli PRIVATE ${OPENEVV_MODERN_ALL_COMPILE_OPTIONS})
    target_link_options(eloquick_cli PRIVATE ${OPENEVV_BASE_LINK_OPTIONS})

    if(IS_ANDROID)
        set_target_properties(eloquick_cli PROPERTIES
            POSITION_INDEPENDENT_CODE TRUE
        )
    endif()

    set_target_properties(eloquick_cli PROPERTIES
        OUTPUT_NAME "eloquick"
    )

    # Mirror as 'evv' (argv[0]-aware compat binary)
    add_custom_command(TARGET eloquick_cli POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            $<TARGET_FILE:eloquick_cli>
            $<TARGET_FILE_DIR:eloquick_cli>/evv${CMAKE_EXECUTABLE_SUFFIX}
        COMMENT "Mirroring eloquick CLI as evv (argv[0]-aware compat binary)"
    )
endif()

# =============================================================================
# Installation
# =============================================================================
install(TARGETS eloquick openevv
    LIBRARY DESTINATION lib
    RUNTIME DESTINATION bin
)

if(OPENEVV_BUILD_CLI)
    install(TARGETS eloquick_cli
        RUNTIME DESTINATION bin
    )
    install(FILES ${CMAKE_CURRENT_BINARY_DIR}/evv${CMAKE_EXECUTABLE_SUFFIX}
        DESTINATION bin
        RENAME evv${CMAKE_EXECUTABLE_SUFFIX}
    )
endif()

install(FILES ${CMAKE_CURRENT_SOURCE_DIR}/include/eci.h
    DESTINATION include
)

# =============================================================================
# Testing Support (if enabled)
# =============================================================================
option(OPENEVV_BUILD_TESTS "Build test executables" OFF)
if(OPENEVV_BUILD_TESTS)
    enable_testing()
    # Add test targets here if needed
endif()

# =============================================================================
# Summary Output
# =============================================================================
message(STATUS "============================================")
message(STATUS "EloQuick Configuration Summary")
message(STATUS "============================================")
message(STATUS "  Version:           ${PROJECT_VERSION}")
message(STATUS "  Build Type:        ${CMAKE_BUILD_TYPE}")
message(STATUS "  Android Build:     ${IS_ANDROID}")
message(STATUS "  Languages:         ${OPENEVV_LANGS}")
message(STATUS "  Build CLI:         ${OPENEVV_BUILD_CLI}")
message(STATUS "  Enable JNI:        ${OPENEVV_ENABLE_JNI}")
message(STATUS "  ThinLTO:           ${OPENEVV_ENABLE_THIN_LTO} (detected: ${HAVE_THIN_LTO})")
message(STATUS "  ICF:               ${OPENEVV_ENABLE_ICF} (detected: ${HAVE_ICF_ALL})")
message(STATUS "  Strip Release:     ${OPENEVV_STRIP_RELEASE}")
message(STATUS "  Hardening:         ${OPENEVV_HARDENING}")
message(STATUS "============================================")