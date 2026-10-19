#include <string>
#include <vector>
#include "runtime.h"
#if defined(RECOMP_LAUNCHER)
#include "launcher_seam.h"
#endif
#define GBAIMP_HOST_MAIN_GBARECOMP
#include "gbaimp/host_main.h"
#include "game_config.h"
#include "ram_overlay_dispatch.h"
#include "gbaimp/capture_harness.h"
#if defined(__ANDROID__)
#include "mobile_platform.h"
#endif

// sdl_intercepts.cpp: registers the SDL handlers (Linux: --wrap shims; Windows: IAT patching).
extern "C" bool khcom_install_sdl_interposers();

static int khcom_host_main(int argc, char** argv) {
    // Capture harness variables (KHCOM_*): parsed first, before anything can ask whether the harness is on.
    khcom::configure_capture_harness();
    gbaimp::HostMainConfig<gbarecomp::RunOptions> cfg;
    cfg.title = khcom::GAME_TITLE.data();
    cfg.banner_name = "KHCOMRecomp";
    // Must run before the runtime makes its first SDL call.
    cfg.install_sdl_interposers = &khcom_install_sdl_interposers;
    cfg.install_ram_dispatch = &khcom_install_ram_dispatch;
    // Headless harness (env-gated, no-op without KHCOM_* variables): savestate load/save, fast-forward.
    cfg.extra_args = [] { return gbaimp::capture::extra_runtime_args(); };
    cfg.create_options = [] { return khcom::create_run_options(); };
    return gbaimp::run_gbarecomp_host(cfg, argc, argv);
}

#if defined(__ANDROID__)
// SDLActivity loads libmain.so and calls SDL_main with no arguments. The shell has already installed
// the payload (variants/khcom/game.toml) and the verified ROM into internal storage; the runtime
// chdir()s there and runs the game on a thread with the desktop-sized stack.
extern "C" __attribute__((visibility("default"))) int SDL_main(int argc, char** argv) {
    std::vector<std::string> args;
    for (int i = 0; i < argc; ++i) args.emplace_back(argv[i] ? argv[i] : "");
    gbarecomp::MobileProcessOptions mobile;
    mobile.game_config = "variants/khcom/game.toml";
    mobile.program_name = "./KHCOMRecomp";
    gbarecomp::mobile_prepare_process(args, mobile);
    std::vector<char*> argv_out;
    for (auto& a : args) argv_out.push_back(a.data());
    argv_out.push_back(nullptr);
    return gbarecomp::mobile_run_with_stack(&khcom_host_main, static_cast<int>(args.size()),
                                            argv_out.data());
}
#else
int main(int argc, char** argv) { return khcom_host_main(argc, argv); }
#endif
