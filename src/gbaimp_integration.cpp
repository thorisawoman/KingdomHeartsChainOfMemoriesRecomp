#include "gbaimp_integration.h"
#include "gbaimp/hooks.h"
#include "gbaimp/hud_anchoring.h"
#include "gbaimp/text_history.h"
#include "game_config.h"
#include "gbaimp/widescreen.h"
#include "gbaimp/m4a_music_hook.h"
#include "armv4t/runtime_arm.h"
#include "runtime_bus_bridge.h"
#include "gba/gba_bus.h"

#include <cstdio>

namespace khcom {

void register_gbaimp_integration() {
    auto& h = gbaimp::hooks();
    h.save_settings = [] { save_khcom_config(); };
    h.is_input_blocked = [] {
        return is_runtime_menu_open() || gbaimp::TextHistory::instance().is_open();
    };
    h.frame_counter = [] { return gbaimp::Widescreen::instance().frame_counter(); };

    auto& anchoring = gbaimp::HudAnchoring::instance();
    anchoring.clear_zones();

    // Conversation log: the library defaults (panel, colors, captions, F2 / Back, 200 entries) are
    // this game's look; only the two named speakers get their own color.
    gbaimp::TextHistoryStyle& style = gbaimp::TextHistory::instance().config().style;
    style.speaker_colors = {{"Sora", {56, 189, 248, 255}}, {"Marluxia", {244, 114, 182, 255}}};
#if defined(__ANDROID__)
    // Handheld (AYN Thor): Start arrives as raw joystick button 6 and BACK as the controller Back
    // button, both of which toggled the log over the game. Start stays the game's pause; BACK opens
    // the settings menu (android/.../KhDualScreen.java). The log is still in the settings menu.
    gbaimp::TextHistoryControls& controls = gbaimp::TextHistory::instance().config().controls;
    controls.toggle_buttons.clear();
    controls.toggle_joy_buttons.clear();
#endif

    // Health bar region (top-left): pushed to the left edge of the widescreen view.
    gbaimp::HudZone health;
    health.direction = -1;
    health.coord_x_max = 90;
    health.coord_y_max = 45;
    health.oam_x_min = 0;
    health.oam_x_max = 85;
    health.oam_y_min = 0;
    health.oam_y_max = 40;
    health.oam_clamp_enabled = true;
    health.oam_clamp_x = -40;
    anchoring.add_zone(health);

    // Card deck / active deck selection (bottom-right): pushed to the right edge.
    gbaimp::HudZone deck;
    deck.direction = +1;
    deck.coord_x_min = 130;
    deck.coord_y_min = 95;
    deck.oam_x_min = 135;
    deck.oam_x_max = 240;
    deck.oam_y_min = 100;
    deck.oam_y_max = 160;
    deck.oam_clamp_enabled = true;
    deck.oam_clamp_x = 300;
    anchoring.add_zone(deck);
}

static gbaimp::M4aSetupSource s_setup_source = gbaimp::M4aSetupSource::None;

void hd_music_setup() {
    static bool s_done = false;
    if (s_done) return;
    gba::GbaBus* bus = gbarecomp::active_bus();
    if (!bus || !bus->rom_ptr() || bus->rom_size() == 0) return;   // not mapped yet: try again at the next call
    s_done = true;

    gbaimp::M4aAddresses known;   // fallback when the locator does not find the group
    known.song_num_start = kM4aSongNumStart;
    known.song_num_start_or_change = kM4aSongNumStartOrChange;
    known.song_num_start_or_continue = kM4aSongNumStartOrContinue;
    known.song_num_stop = kM4aSongNumStop;
    known.mplay_all_stop = kM4aMPlayAllStop;
    known.song_table = kM4aSongTable;
    known.song_table_entries = kM4aSongTableEntries;
    gbaimp::M4aSetupOptions opts;
    opts.log_tag = "khcom";
    opts.env_prefix = "KHCOM_";   // KHCOM_LOG_INTERPOSE=1 prints the first song starts
    opts.fallback = &known;
    opts.bus_read_u32 = &bus_read_u32;   // song table lookups through the guest bus
    s_setup_source = gbaimp::M4aMusicHook::instance().setup_from_rom(bus->rom_ptr(), bus->rom_size(), opts);
}

gbaimp::M4aSetupSource hd_music_setup_source() { return s_setup_source; }

} // namespace khcom
