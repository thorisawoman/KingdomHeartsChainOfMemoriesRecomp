cmake_minimum_required(VERSION 3.20)
set(CMAKE_CXX_SCAN_FOR_MODULES OFF)
project(KHCOMRecomp C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD_REQUIRED ON)

# Reusable gba-improvements library (namespace gbaimp). It lives in the
# external/gba-improvements git submodule; -DGBAIMP_ROOT=<path> overrides it
# (e.g. a local checkout). Its own unit tests are not built as a subproject.
set(GBAIMP_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/external/gba-improvements"
    CACHE PATH "gba-improvements library root (git submodule)")

if(MSVC)
    # src/msvc_compat.h includes gbaimp/msvc_compat.h; the library headers must be reachable from
    # every translation unit (the force-include applies to the C runtime sources too).
    include_directories("${GBAIMP_ROOT}/include")
    file(TO_NATIVE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/src/msvc_compat.h" MSVC_COMPAT_HEADER)
    add_compile_options("/FI${MSVC_COMPAT_HEADER}")
endif()

# macOS: Apple's linker has no -Wl,--wrap, so the interposed functions are renamed at compile
# time in every translation unit (gbarecomp, recomp-ui, the library and the game). The wraps keep
# their usual names; src/apple/apple_ld_wraps.c (compiled without the renames) supplies __real_*.
if(APPLE)
    set(KHCOM_APPLE_WRAPPED SDL_PollEvent SDL_UpdateTexture SDL_RenderCopy SDL_RenderPresent
        SDL_QueueAudio SDL_OpenAudioDevice recomp_runtime_ui_create_standard)
    foreach(_fn IN LISTS KHCOM_APPLE_WRAPPED)
        add_compile_definitions(${_fn}=__wrap_${_fn})
    endforeach()
endif()

set(GBARECOMP_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/gbarecomp"
    CACHE PATH "Path to gbarecomp platform core (git submodule)")

if(NOT EXISTS "${GBARECOMP_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR
        "GBARECOMP_ROOT does not point to a gbarecomp checkout: ${GBARECOMP_ROOT}")
endif()

# SDL2 dependency configuration
if(WIN32 AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/third_party/sdl2/include/SDL.h")
    set(SDL2_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/sdl2/include" CACHE PATH "SDL2 include dir" FORCE)
    set(SDL2_LIBRARY "${CMAKE_CURRENT_SOURCE_DIR}/third_party/sdl2/lib/x64/SDL2.lib" CACHE FILEPATH "SDL2 library" FORCE)
endif()

# recomp-ui in-game overlay dependency configuration
set(RECOMP_UI_DIR "${CMAKE_CURRENT_SOURCE_DIR}/external/recomp-ui")
if(EXISTS "${RECOMP_UI_DIR}/src/recomp_runtime_ui.h")
    set(GBARECOMP_RUNTIME_UI_ROOT "${RECOMP_UI_DIR}" CACHE PATH "Path to recomp-ui" FORCE)
    set(RECOMP_UI_ROOT "${RECOMP_UI_DIR}" CACHE PATH "recomp-ui root" FORCE)
    include("${RECOMP_UI_DIR}/recomp_ui.cmake")
endif()

# Statically recompiled BIOS source directory
set(GBARECOMP_GENERATED_BIOS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/generated_bios"
    CACHE PATH "Directory containing locally generated BIOS recompilation sources" FORCE)

add_subdirectory("${GBARECOMP_ROOT}" gbarecomp_build EXCLUDE_FROM_ALL)

if(NOT EXISTS "${GBAIMP_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR
        "GBAIMP_ROOT does not point to a gba-improvements checkout: ${GBAIMP_ROOT}\n"
        "Run: git submodule update --init external/gba-improvements\n"
        "or pass -DGBAIMP_ROOT=<path to gba-improvements>.")
endif()

# The library calls find_package(SDL2 REQUIRED). On Windows/MSVC KHR ships the SDL2 "VC"
# devel package in third_party/sdl2, which provides cmake/sdl2-config.cmake (SDL2::SDL2).
# Point find_package at it so the library target gets valid include/link settings.
# (Not buildable in the Docker/Linux environment; validate on Windows.)
if(WIN32 AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/third_party/sdl2/cmake/sdl2-config.cmake")
    set(SDL2_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/sdl2/cmake" CACHE PATH "SDL2 config dir" FORCE)
endif()

add_subdirectory("${GBAIMP_ROOT}" gba_improvements_build EXCLUDE_FROM_ALL)

include("${GBAIMP_ROOT}/cmake/GbaimpHost.cmake")

# Game-specific sources only; the executable, the unit-test executable, the link options, the generated /
# BIOS sources and the post-build copies are done by gbaimp_add_host().
set(KHCOM_COMMON_SOURCES
    src/game_config.cpp
    src/gbaimp_integration.cpp
    src/sdl_intercepts.cpp
    src/runtime_ui_standard.cpp
    src/dialogue_backlog.cpp
    src/text_fonts_data.cpp
    src/text_overlay/overlay_fonts_data.cpp
    src/text_overlay/text_adapter.cpp
    src/ram_overlay_dispatch.cpp
    src/widescreen_adapter.cpp
    src/text_overlay/engine_hooks.cpp
    src/text_overlay/battle_text.cpp
    src/text_overlay/fade_sampler.cpp
)

if(ANDROID)
    # Android: SDLActivity loads libmain.so, so the game is a shared library named `main` (see
    # gbarecomp/platform/android/cpp/CMakeLists.txt). Same sources and link setup as the desktop
    # executable that gbaimp_add_host() makes; the entry point is SDL_main in src/main.cpp.
    set(_GBAIMP_HOST_GENERATED_DIR "${CMAKE_CURRENT_SOURCE_DIR}/generated")
    set(_GBAIMP_HOST_INCLUDE_DIRS "")
    set(_GBAIMP_HOST_ENHANCED_IMGUI "${GBAIMP_ENHANCED_IMGUI_SOURCE_DEFAULT}")
    set(_GBAIMP_HOST_RUI_SETTINGS_C "")
    file(GLOB KHCOM_GENERATED_SOURCES "${_GBAIMP_HOST_GENERATED_DIR}/*.c" "${_GBAIMP_HOST_GENERATED_DIR}/*.cpp")
    set_source_files_properties(${KHCOM_GENERATED_SOURCES} PROPERTIES COMPILE_OPTIONS "-w")
    add_library(main SHARED src/main.cpp src/android/dual_screen.cpp ${KHCOM_COMMON_SOURCES} ${KHCOM_GENERATED_SOURCES})
    _gbaimp_host_configure_target(main FALSE khcom)
    target_link_libraries(main PRIVATE SDL2::SDL2 log android)
else()
gbaimp_add_host(KHCOMRecomp
    SYMBOL_PREFIX khcom
    SOURCES
        src/main.cpp
        ${KHCOM_COMMON_SOURCES}
    TEST_TARGET khcom_unit_tests
    TEST_SOURCES
        tests/test_all_modules.cpp
        tests/test_text_adapter.cpp
        tests/test_overlay_engine.cpp
        ${KHCOM_COMMON_SOURCES}
        src/scaffold_stub.cpp
)
# The unit tests link the real game_config.cpp (settings registry, khcom_config.ini); scaffold_stub.cpp then
# only provides the empty dispatch table.
target_compile_definitions(khcom_unit_tests PRIVATE KHCOM_TEST_REAL_CONFIG)
endif()

if(APPLE)
    target_sources(KHCOMRecomp PRIVATE src/apple/apple_ld_wraps.c)
    # recomp-ui's definition of the menu constructor becomes the __real_ symbol.
    if(RECOMP_UI_DIR AND EXISTS "${RECOMP_UI_DIR}/src/common/recomp_runtime_settings.c")
        set_source_files_properties("${RECOMP_UI_DIR}/src/common/recomp_runtime_settings.c" PROPERTIES
            COMPILE_OPTIONS "-Urecomp_runtime_ui_create_standard;-Drecomp_runtime_ui_create_standard=__real_recomp_runtime_ui_create_standard")
    endif()
endif()

# ---- SDL interception ---------------------------------------------------------------
# (the link options and the MSVC symbol rename below are applied by gbaimp_add_host())
# GNU ld uses `-Wl,--wrap` (flags set by gbaimp_add_host()). MSVC has no equivalent, so:
#  * SDL_PollEvent / SDL_UpdateTexture / SDL_RenderCopy / SDL_RenderPresent /
#    SDL_QueueAudio / SDL_OpenAudioDevice (imported from SDL2.dll) are interposed at run time by
#    gbaimp::install_sdl_interposers() (Import Address Table patching), called from
#    main() through khcom_install_sdl_interposers() in src/sdl_intercepts.cpp. No
#    link option is needed.
#  * recomp_runtime_ui_create_standard (a plain function compiled into this target
#    from recomp-ui) is interposed by renaming the symbol while compiling that one
#    third-party translation unit (no third-party source is modified); the host
#    definition lives in src/runtime_ui_standard.cpp.
