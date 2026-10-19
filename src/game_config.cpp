#include "game_config.h"
#include "gbaimp_integration.h"
#include "gbaimp/host_settings.h"
#include "gbaimp/input_enhancements.h"
#include "text_overlay/text_adapter.h"
#include "gbaimp/text_history.h"
#include "widescreen_adapter.h"
#include "gbaimp/capture_harness.h"
#include "runtime_bus_bridge.h"
#include "gba/gba_bus.h"
#include "gba/gba_io.h"
#include <algorithm>
#include <cmath>
#include <vector>
#include <cstring>
#include <ostream>
#include <string>

#if defined(GBARECOMP_RUNTIME_UI)
#include "recomp_runtime_ui.h"
#include "gbaimp/runtime_ui_settings.h"
#include "runtime_ui_standard.h"
#endif

namespace khcom {

namespace {

const char* const kAspectLabels[] = {
    "3:2 (Native)",
    "16:9 (Battles only)"
};

const std::uint16_t kAspectWidths[] = {
    240,
    284
};

#if defined(GBARECOMP_RUNTIME_UI)
using gbaimp::Setting;
using gbaimp::SettingKind;
using gbaimp::SettingsRegistry;

// ---- Choice labels (the wording of this game's menu) ----------------------------------------------
const char* const kFpsTargetChoices[] = {
    "60 FPS (Original GBA)",
    "120 FPS (2x High Rate)",
    "144 FPS",
    "Display Native (Auto)",
    "Uncapped"
};

const char* const kMotionSmoothingChoices[] = {
    "Off (Direct Presentation)",
    "Smooth Blend (50% Lerp)",
    "Motion-Adaptive"
};

const char* const kXbrzChoices[] = {
    "Off (Original Pixels)",
    "2x Pixel-Art Edges (Enhanced Edges)",
    "3x Pixel-Art Edges (Crisp Vectors)",
    "4x Pixel-Art Edges (Ultra Detail)",
    "5x Pixel-Art Edges (Retina Smoothing)"
};

const char* const kColorProfileChoices[] = {
    "Raw (Uncorrected)",
    "AGB-001 (Reflective TFT)",
    "AGS-001 (SP Frontlit)",
    "AGS-101 (SP Backlit)",
    "MiSTer Gamma 1.6",
    "MiSTer Gamma 2.2"
};

const char* const kScreenMaskChoices[] = {
    "Off (Clean Pixels)",
    "MiSTer LCD Grid (TFT Matrix)",
    "MiSTer Subpixel RGB (Vertical)",
    "MiSTer Subpixel BGR",
    "MiSTer Diffusion (AGS-001)",
    "Game Boy Player Scanlines",
    "CRT Trinitron Aperture Grille"
};

const char* const kAnalogChoices[] = {
    "Digital (Original 8-Way)",
    "Analog 8-Way (Smooth Stick)",
    "True 360 Walk & Run"
};

const char* const kGamepadProfileChoices[] = {
    "Xbox / Standard (A: Atk, B: Jump, LB/RB: Deck)",
    "Nintendo Style (B: Atk, A: Jump, LB/RB: Deck)",
    "PlayStation Style (Square/X: Atk, Cross/A: Jump)",
    "Swapped Shoulders (RB: Deck L, LB: Deck R)",
    "Triggers for Deck (LT: Deck L, RT: Deck R)",
    "Custom Profile"
};

const char* const kPadButtonChoices[] = {
    "Button A (Bottom / Cross)",
    "Button B (Right / Circle)",
    "Button X (Left / Square)",
    "Button Y (Top / Triangle)",
    "Left Bumper (LB / L1)",
    "Right Bumper (RB / R1)",
    "Left Trigger (LT / L2)",
    "Right Trigger (RT / R2)"
};

const char* const kKeyboardPresetChoices[] = {
    "Default (X, Z, C, V, Return, RShift)",
    "WASD + J/K/U/I (Modern PC)",
    "Classic Emulation (Z, X, A, S)",
    "Arrows + Z/X/Q/W",
    "Custom"
};

const char* const kHdTextModeChoices[] = {
    "Original (GBA font)",
    "HD overlay (output resolution)"
};

const char* const kHdWrapChoices[] = {
    "Keep engine line breaks",
    "Reflow to the text box"
};

const char* const kHdStyleChoices[] = {
    "Plain",
    "Drop shadow",
    "Outline"
};

const std::uint32_t kHdTextColors[] = {0xFFFFFFFFu, 0xFFF3D6FFu, 0xFFE9A0FFu, 0xBFE3FFFFu, 0xC8F5C8FFu, 0xD8D8D8FFu, 0x202020FFu, 0u};   // 0 = match the game's own colour
const char* const kHdTextColorChoices[] = {
    "White", "Cream", "Light yellow", "Light blue", "Pale green", "Light grey", "Near black", "Match game (brown)"
};

const std::uint32_t kHdShadowColors[] = {0x000000B0u, 0x000000FFu, 0x1A1A3CE0u, 0x3A2410D0u, 0x303030C0u, 0xFFFFFF60u, 0u};
const char* const kHdShadowColorChoices[] = {
    "Black (soft)", "Black", "Navy", "Dark brown", "Dark grey", "White glow", "Match game (tan)"
};

const std::uint32_t kHdOutlineColors[] = {0x000000FFu, 0x1A1A3CFFu, 0x3A2410FFu, 0xFFFFFFFFu};
const char* const kHdOutlineColorChoices[] = {
    "Black", "Navy", "Dark brown", "White"
};

const char* const kEqChoices[] = {
    "Flat (Authentic)",
    "Warm Retro",
    "Crisp Modern",
    "Bass Boost"
};

const char* const kPerfModeChoices[] = {
    "Disabled",
    "FPS Counter Only",
    "FPS + Frametime ms",
    "Full HUD + Frametime Graph"
};

const char* const kPerfPositionChoices[] = {
    "Top-Left",
    "Top-Right",
    "Bottom-Left",
    "Bottom-Right",
    "Bottom Black Bar (Letterbox)",
    "Custom (Drag with Mouse)"
};

const char* const kPerfThemeChoices[] = {
    "Glassmorphism Dark",
    "Neon Cyberpunk",
    "Solid Dark",
    "Minimal (Transparent)"
};


// Family names are filled in at start-up from the text renderer's family_names() (built-ins, then fonts/*.ttf).
constexpr std::size_t kMaxFontChoices = 8 + 8;   // built-in families + user fonts (gbaimp::text::FontLibrary::kMaxUserFonts)

// ---- HD dialogue text (text_overlay) settings <-> integer UI values ---------------------------------
template <size_t N>
int colour_index(const std::uint32_t (&tbl)[N], std::uint32_t rgba) {
    for (size_t i = 0; i < N; ++i) if (tbl[i] == rgba) return static_cast<int>(i);
    return 0;
}

int tenths(float v) { return static_cast<int>(std::lround(v * 10.0f)); }

// Returns 1 when `key` is a hdtext.* key (value written to *out), 0 otherwise.
int hdtext_get(const char* key, int* out) {
    if (std::strncmp(key, "hdtext.", 7) != 0) return 0;
    const overlay::TextSettings& o = overlay::settings();
    const char* k = key + 7;
    if (!std::strcmp(k, "enabled")) *out = o.enabled ? 1 : 0;
    else if (!std::strcmp(k, "family")) *out = o.family;
    else if (!std::strcmp(k, "size")) *out = tenths(o.size_em);
    else if (!std::strcmp(k, "line_spacing")) *out = static_cast<int>(std::lround(o.line_spacing * 100.0f));
    else if (!std::strcmp(k, "letter_spacing")) *out = tenths(o.letter_spacing);
    else if (!std::strcmp(k, "wrap")) *out = static_cast<int>(o.wrap);
    else if (!std::strcmp(k, "style")) *out = static_cast<int>(o.style);
    else if (!std::strcmp(k, "shadow_offset")) *out = tenths(o.shadow_offset);
    else if (!std::strcmp(k, "outline_width")) *out = tenths(o.outline_width);
    else if (!std::strcmp(k, "color")) *out = colour_index(kHdTextColors, o.text_rgba);
    else if (!std::strcmp(k, "shadow_color")) *out = colour_index(kHdShadowColors, o.shadow_rgba);
    else if (!std::strcmp(k, "outline_color")) *out = colour_index(kHdOutlineColors, o.outline_rgba);
    else if (!std::strcmp(k, "opacity")) *out = static_cast<int>(std::lround(o.opacity * 100.0f));
    else if (!std::strcmp(k, "padding")) *out = tenths(o.box_padding);
    else if (!std::strcmp(k, "auto_fit")) *out = o.auto_fit ? 1 : 0;
    else if (!std::strcmp(k, "min_size")) *out = tenths(o.min_size_em);
    else return 0;
    return 1;
}

// Returns 1 when handled. Values are clamped to the ranges of the UI items. Layout and glyph caches are
// keyed by the settings, so no explicit invalidation is needed (changes apply live).
int hdtext_set(const char* key, int v) {
    if (std::strncmp(key, "hdtext.", 7) != 0) return 0;
    overlay::TextSettings& o = overlay::settings();
    const char* k = key + 7;
    const float f = static_cast<float>(v);
    if (!std::strcmp(k, "enabled")) o.enabled = v != 0;
    else if (!std::strcmp(k, "family")) {
        const int n = static_cast<int>(overlay::renderer().family_names().size());
        o.family = std::clamp(v, 0, std::max(0, n - 1));
    }
    else if (!std::strcmp(k, "size")) o.size_em = std::clamp(f, 30.0f, 200.0f) / 10.0f;
    else if (!std::strcmp(k, "line_spacing")) o.line_spacing = std::clamp(f, 80.0f, 200.0f) / 100.0f;
    else if (!std::strcmp(k, "letter_spacing")) o.letter_spacing = std::clamp(f, -10.0f, 30.0f) / 10.0f;
    else if (!std::strcmp(k, "wrap")) o.wrap = v ? overlay::WrapMode::Reflow : overlay::WrapMode::KeepSourceBreaks;
    else if (!std::strcmp(k, "style")) o.style = static_cast<overlay::TextStyle>(std::clamp(v, 0, 2));
    else if (!std::strcmp(k, "shadow_offset")) o.shadow_offset = std::clamp(f, 2.0f, 30.0f) / 10.0f;
    else if (!std::strcmp(k, "outline_width")) o.outline_width = std::clamp(f, 2.0f, 30.0f) / 10.0f;
    else if (!std::strcmp(k, "color")) o.text_rgba = kHdTextColors[std::clamp(v, 0, 7)];
    else if (!std::strcmp(k, "shadow_color")) o.shadow_rgba = kHdShadowColors[std::clamp(v, 0, 6)];
    else if (!std::strcmp(k, "outline_color")) o.outline_rgba = kHdOutlineColors[std::clamp(v, 0, 3)];
    else if (!std::strcmp(k, "opacity")) o.opacity = std::clamp(f, 20.0f, 100.0f) / 100.0f;
    else if (!std::strcmp(k, "padding")) o.box_padding = std::clamp(f, 0.0f, 80.0f) / 10.0f;
    else if (!std::strcmp(k, "auto_fit")) o.auto_fit = v != 0;
    else if (!std::strcmp(k, "min_size")) o.min_size_em = std::clamp(f, 30.0f, 120.0f) / 10.0f;
    else return 0;
    return 1;
}

// ---- Menu rows: this game's labels, tooltips and ranges ---------------------------------------------------
// Applied over the standard settings of the library and used to create the rows that only this game has
// (hdtext.*). `choices` is null for the dynamic font list.
struct Row {
    const char* id;
    const char* label;
    const char* tip;
    SettingKind kind;
    int min, max, step;
    const char* const* choices;
    std::size_t choice_count;
};

const Row kRows[] = {
    {"video.fps_target", "Target Framerate", "Target refresh rate without altering speed",
     SettingKind::Choice, 0, 4, 1, kFpsTargetChoices, 5},
    {"video.motion_smoothing", "Motion Smoothing", "Temporal blending between simulation frames",
     SettingKind::Choice, 0, 2, 1, kMotionSmoothingChoices, 3},
    {"video.xbrz_scale", "Pixel-Art Upscaler", "Edge-aware pixel-art upscaler, Scale2x/EPX style (2x to 5x)",
     SettingKind::Choice, 0, 4, 1, kXbrzChoices, 5},
    {"video.color_profile", "Color Profile / Gamma", "Hardware color grading and gamma curve",
     SettingKind::Choice, 0, 5, 1, kColorProfileChoices, 6},
    {"video.screen_mask", "Screen Mask Type", "LCD grid, RGB subpixel stripes, scanlines",
     SettingKind::Choice, 0, 6, 1, kScreenMaskChoices, 7},
    {"video.mask_intensity", "Mask Intensity (%)", "Grid darkness: 10% (subtle) to 100% (dense)",
     SettingKind::Int, 10, 100, 5, nullptr, 0},
    {"input.gamepad_profile", "Controller Preset", "Quick layout profile preset",
     SettingKind::Choice, 0, 5, 1, kGamepadProfileChoices, 6},
    {"input.pad_attack", "Attack / Card Action", "Play card, attack, menu confirm",
     SettingKind::Choice, 0, 7, 1, kPadButtonChoices, 8},
    {"input.pad_jump", "Jump / Dodge Roll", "Jump, dodge roll, menu cancel",
     SettingKind::Choice, 0, 7, 1, kPadButtonChoices, 8},
    {"input.pad_deck_l", "Cycle Deck Left", "Scroll deck cards to the left",
     SettingKind::Choice, 0, 7, 1, kPadButtonChoices, 8},
    {"input.pad_deck_r", "Cycle Deck Right / Lock-on", "Scroll deck right and lock-on",
     SettingKind::Choice, 0, 7, 1, kPadButtonChoices, 8},
    {"input.analog_mode", "Stick Movement Mode", "360 walk/run or 8-way digital",
     SettingKind::Choice, 0, 2, 1, kAnalogChoices, 3},
    {"input.deadzone", "Stick Deadzone (%)", "Stick drift margin (5% to 50%)",
     SettingKind::Int, 5, 50, 5, nullptr, 0},
    {"input.reset_pad", "Reset Gamepad to Defaults", "Restore default gamepad mapping",
     SettingKind::Action, 0, 0, 0, nullptr, 0},
    {"input.kb_preset", "Keyboard Preset", "Keyboard layout preset",
     SettingKind::Choice, 0, 4, 1, kKeyboardPresetChoices, 5},
    {"input.key_a", "Attack / Card Action", "Play card, attack, menu confirm",
     SettingKind::Text, 0, 0, 0, nullptr, 0},
    {"input.key_b", "Jump / Dodge Roll", "Jump, dodge roll, menu cancel",
     SettingKind::Text, 0, 0, 0, nullptr, 0},
    {"input.key_l", "Cycle Deck Left", "Scroll deck cards to the left",
     SettingKind::Text, 0, 0, 0, nullptr, 0},
    {"input.key_r", "Cycle Deck Right / Lock-on", "Scroll deck right and lock-on",
     SettingKind::Text, 0, 0, 0, nullptr, 0},
    {"input.key_start", "Pause / Camp Menu", "Open journal and pause game",
     SettingKind::Text, 0, 0, 0, nullptr, 0},
    {"input.key_select", "Switch Deck / Reload", "Toggle enemy cards and reload",
     SettingKind::Text, 0, 0, 0, nullptr, 0},
    {"input.key_up", "Move Up", "Move character upwards",
     SettingKind::Text, 0, 0, 0, nullptr, 0},
    {"input.key_down", "Move Down", "Move character downwards",
     SettingKind::Text, 0, 0, 0, nullptr, 0},
    {"input.key_left", "Move Left", "Move character to the left",
     SettingKind::Text, 0, 0, 0, nullptr, 0},
    {"input.key_right", "Move Right", "Move character to the right",
     SettingKind::Text, 0, 0, 0, nullptr, 0},
    {"input.reset_kb", "Reset Keyboard to Defaults", "Restore default keyboard mapping",
     SettingKind::Action, 0, 0, 0, nullptr, 0},
    {"gameplay.turbo_dialog", "Turbo Dialog & Cutscene Skip", "Hold Tab or Y to advance text at 60Hz",
     SettingKind::Bool, 0, 1, 1, nullptr, 0},
    {"hdtext.enabled", "Dialogue Text", "Original keeps the game text untouched. HD overlay draws the dialogue at output resolution",
     SettingKind::Choice, 0, 1, 1, kHdTextModeChoices, 2},
    {"hdtext.family", "Font Family", "Built-in families, then .ttf files placed in the fonts/ folder (listed at start-up)",
     SettingKind::Choice, 0, 7, 1, nullptr, 0},
    {"hdtext.size", "Font Size", "In tenths of a GBA pixel (100 = 10 px, about the original cap height)",
     SettingKind::Int, 30, 200, 5, nullptr, 0},
    {"hdtext.line_spacing", "Line Spacing %", "Line pitch as a percentage of the font size (120 = 12 px at size 10)",
     SettingKind::Int, 80, 200, 5, nullptr, 0},
    {"hdtext.letter_spacing", "Letter Spacing", "Extra advance per letter in tenths of a GBA pixel",
     SettingKind::Int, -10, 30, 1, nullptr, 0},
    {"hdtext.wrap", "Wrapping", "Keep the engine lines (same words per line) or re-wrap the page to the text box",
     SettingKind::Choice, 0, 1, 1, kHdWrapChoices, 2},
    {"hdtext.style", "Text Style", "Plain, drop shadow or outline",
     SettingKind::Choice, 0, 2, 1, kHdStyleChoices, 3},
    {"hdtext.shadow_offset", "Shadow Offset", "Tenths of a GBA pixel (the game uses 10)",
     SettingKind::Int, 2, 30, 1, nullptr, 0},
    {"hdtext.outline_width", "Outline Width", "Tenths of a GBA pixel",
     SettingKind::Int, 2, 30, 1, nullptr, 0},
    {"hdtext.color", "Text Colour", "Preset text colours",
     SettingKind::Choice, 0, 7, 1, kHdTextColorChoices, 8},
    {"hdtext.shadow_color", "Shadow Colour", "Preset shadow colours",
     SettingKind::Choice, 0, 6, 1, kHdShadowColorChoices, 7},
    {"hdtext.outline_color", "Outline Colour", "Preset outline colours",
     SettingKind::Choice, 0, 3, 1, kHdOutlineColorChoices, 4},
    {"hdtext.opacity", "Text Opacity %", "Opacity of the dialogue text",
     SettingKind::Int, 20, 100, 5, nullptr, 0},
    {"hdtext.padding", "Box Padding", "Free space kept inside the text area, tenths of a GBA pixel",
     SettingKind::Int, 0, 80, 5, nullptr, 0},
    {"hdtext.auto_fit", "Auto-Fit", "Shrink the text until the page fits the box; pages that cannot fit show the original text",
     SettingKind::Bool, 0, 1, 1, nullptr, 0},
    {"hdtext.min_size", "Minimum Size", "Auto-fit lower bound in tenths of a GBA pixel",
     SettingKind::Int, 30, 120, 5, nullptr, 0},
    {"audio.hd_enabled", "HD Re:CoM Soundtrack", "High-fidelity PS2 Re:CoM soundtrack",
     SettingKind::Bool, 0, 1, 1, nullptr, 0},
    {"audio.bgm_volume", "Music (BGM) Volume (%)", "Independent music volume (0% to 150%)",
     SettingKind::Int, 0, 150, 5, nullptr, 0},
    {"audio.sfx_volume", "Effects (SFX) Volume (%)", "Independent sound effects volume (0-150%)",
     SettingKind::Int, 0, 150, 5, nullptr, 0},
    {"perf.mode", "Display Mode", "FPS and frametime overlay (or press F10)",
     SettingKind::Choice, 0, 3, 1, kPerfModeChoices, 4},
    {"perf.position", "Screen Position", "Screen corner or drag anywhere with mouse",
     SettingKind::Choice, 0, 5, 1, kPerfPositionChoices, 6},
    {"perf.theme", "Visual Theme", "Color palette and background opacity",
     SettingKind::Choice, 0, 3, 1, kPerfThemeChoices, 4},
    {"audio.eq_preset", "Equalizer Profile", "Real-time DSP sound equalizer profile",
     SettingKind::Choice, 0, 3, 1, kEqChoices, 4},
    {"audio.stereo_width", "Stereo Width (%)", "Stereo width: 0% mono to 200% expanded",
     SettingKind::Int, 0, 200, 10, nullptr, 0},
    {"audio.anti_aliasing", "DAC Anti-Aliasing", "Biquad filter to cut GBA audio hiss",
     SettingKind::Bool, 0, 1, 1, nullptr, 0},
    {"audio.limiter", "Dynamic Peak Limiter", "Dynamic limiter preventing audio clipping",
     SettingKind::Bool, 0, 1, 1, nullptr, 0},
};

const struct { const char* ini; const char* ui; } kHdTextKeys[] = {
    {"hdtext_enabled", "hdtext.enabled"},             {"hdtext_size", "hdtext.size"},
    {"hdtext_line_spacing", "hdtext.line_spacing"},   {"hdtext_letter_spacing", "hdtext.letter_spacing"},
    {"hdtext_wrap", "hdtext.wrap"},                   {"hdtext_style", "hdtext.style"},
    {"hdtext_shadow_offset", "hdtext.shadow_offset"}, {"hdtext_outline_width", "hdtext.outline_width"},
    {"hdtext_color", "hdtext.color"},                 {"hdtext_shadow_color", "hdtext.shadow_color"},
    {"hdtext_outline_color", "hdtext.outline_color"}, {"hdtext_opacity", "hdtext.opacity"},
    {"hdtext_padding", "hdtext.padding"},             {"hdtext_auto_fit", "hdtext.auto_fit"},
    {"hdtext_min_size", "hdtext.min_size"},
};


std::vector<std::string> g_font_choice_names;

std::vector<std::string> to_vec(const char* const* a, std::size_t n) {
    std::vector<std::string> v;
    if (a) for (std::size_t i = 0; i < n; ++i) v.emplace_back(a[i]);
    return v;
}

// Fills the dynamic family list of the "hdtext.family" menu item (built-in + fonts/ folder).
void init_font_choices() {
    g_font_choice_names = overlay::renderer().family_names();
    if (g_font_choice_names.size() > kMaxFontChoices) g_font_choice_names.resize(kMaxFontChoices);
}

// Legacy keys of older config files / UI (dialogue.text_mode, font_*, line_capacity, soft_word_wrap): they described the
// removed in-engine enhanced text and are accepted but ignored.
const char* const kLegacyTextIds[] = {
    "dialogue.text_mode", "dialogue.font_family", "dialogue.font_size", "dialogue.font_density",
    "dialogue.font_scale", "dialogue.font_style", "dialogue.line_capacity", "dialogue.soft_word_wrap"};
const char* const kLegacyTextIniKeys[] = {
    "text_mode", "font_family", "font_size", "font_density", "font_scale", "font_style", "line_capacity",
    "soft_word_wrap"};

const char* hdtext_ini_for(const char* ui_id) {
    for (const auto& m : kHdTextKeys) if (std::strcmp(m.ui, ui_id) == 0) return m.ini;
    return nullptr;
}

// Menu order of this game's sections (the ini has its own order, see set_section_order below). Settings with
// no menu row (the dragged HUD position) follow the section of their ini group.
int menu_rank(const Setting& s) {
    static const char* const kOrder[] = {"Graphics", "Controller (Gamepad)", "Keyboard Controls", "Gameplay & Assist",
                                         "Dialogue Text (HD)", "Dialogue & Story", "Audio", "Diagnostics & HUD"};
    constexpr int kCount = static_cast<int>(sizeof(kOrder) / sizeof(kOrder[0]));
    if (s.menu_section.empty()) return s.ini_section == "Diagnostics" ? kCount - 1 : kCount;
    for (int i = 0; i < kCount; ++i)
        if (s.menu_section == kOrder[i]) return i;
    return kCount;
}

Setting make_host_row(const Row& r) {
    Setting s;
    s.id = r.id;
    const bool hd = std::strncmp(r.id, "hdtext.", 7) == 0;
    s.menu_section = hd ? "Dialogue Text (HD)" : "Dialogue & Story";
    s.label = r.label;
    s.tooltip = r.tip;
    s.kind = r.kind;
    s.min = r.min; s.max = r.max; s.step = r.step;
    s.choices = to_vec(r.choices, r.choice_count);
    return s;
}

void build_registry(SettingsRegistry& reg) {
    init_font_choices();

    reg.set_ini_path("khcom_config.ini");
    reg.set_section_order({"Video", "Audio", "Dialogue", "HdText", "Controls", "Gameplay", "Diagnostics"});
    // Like the original loader: a missing file is not created just by starting the game.
    reg.set_write_defaults_if_missing(false);
    // Headless harness runs never persist their overrides.
    reg.set_save_blocked([] { return gbaimp::capture::active(); });

    gbaimp::StandardSettingsOptions opt;
    opt.exclude = {"audio.master_volume", "audio.sample_rate"};   // this game has neither
    gbaimp::register_standard_settings(reg, gbaimp::SettingsTargets::shared(), opt);
    reg.rename_menu_section("Display and Graphics", "Graphics");

    // This game's wording and ranges over the standard settings.
    for (const Row& r : kRows) {
        if (Setting* s = reg.find(r.id)) {
            s->label = r.label;
            s->tooltip = r.tip;
            if (s->kind == SettingKind::Int || s->kind == SettingKind::Choice) { s->min = r.min; s->max = r.max; s->step = r.step; }
            if (r.choices) s->choices = to_vec(r.choices, r.choice_count);
        }
    }

    // Host-only rows: HD dialogue text (hdtext.*).
    for (const Row& r : kRows) {
        if (std::strncmp(r.id, "hdtext.", 7) != 0) continue;
        Setting s = make_host_row(r);
        const std::string id = r.id;
        s.ini_section = "HdText";
        if (id == "hdtext.family") {
            // Dynamic list (built-ins, then fonts/*.ttf). Stored by name (hdtext_font, below); the index
            // form (hdtext_family) is accepted for the capture harness (integer overrides only) and never written.
            s.choices = g_font_choice_names;
            s.min = 0;
            s.max = std::max(0, static_cast<int>(g_font_choice_names.size()) - 1);
            s.ini_key = "hdtext_family";
            s.ini_save = false;
        } else if (const char* ik = hdtext_ini_for(r.id)) {
            s.ini_key = ik;
        }
        s.get = [id] { int v = 0; hdtext_get(id.c_str(), &v); return v; };
        s.set = [id](int v) { hdtext_set(id.c_str(), v); };
        reg.add(std::move(s));
    }
    // The font family is stored by name so adding or removing fonts/*.ttf does not shift the selection.
    gbaimp::IniExtension font;
    font.section = "HdText";
    font.write = [](std::ostream& o) {
        const std::vector<std::string> fams = overlay::renderer().family_names();
        const int fi = overlay::settings().family;
        if (fi >= 0 && fi < static_cast<int>(fams.size())) o << "hdtext_font=" << fams[fi] << "\n";
    };
    font.read = [](const std::string& key, const std::string& raw) {
        if (key != "hdtext_font") return false;
        std::string name = raw;
        while (!name.empty() && (name.back() == '\r' || name.back() == ' ')) name.pop_back();
        const int idx = overlay::renderer().family_index(name);
        if (idx >= 0) overlay::settings().family = idx;
        return true;
    };
    reg.add_ini_extension(std::move(font));

    // Conversation log rows (dialogue.backlog_enable / dialogue.open_backlog): ids, labels, menu section and
    // [Dialogue] ini key are the defaults of the library helper.
    gbaimp::register_text_history_settings(reg, gbaimp::TextHistory::instance());

    for (const char* id : kLegacyTextIds) reg.add_ignored_id(id);
    for (const char* key : kLegacyTextIniKeys) reg.add_ignored_ini_key(key);

    // Menu order: this game's sections in its own order (the standard block comes in the library's order).
    // The relative order inside a section, which is also the key order of the ini, is kept.
    std::vector<Setting> all = reg.settings();
    for (const Setting& s : all) reg.remove(s.id);
    std::stable_sort(all.begin(), all.end(), [](const Setting& a, const Setting& b) { return menu_rank(a) < menu_rank(b); });
    for (Setting& s : all) reg.add(std::move(s));
}

SettingsRegistry& settings_registry() {
    static SettingsRegistry reg;
    static const bool built = (build_registry(reg), true);
    (void)built;
    return reg;
}

gbaimp::RuntimeUiBinding& ui_binding() {
    static gbaimp::RuntimeUiBinding binding(settings_registry());
    return binding;
}

// Live setting changes requested by the capture harness (KHCOM_CAPTURE_VARIANTS): khcom_config.ini
// keys mapped onto the registry, restricted to the display / HD text / performance HUD keys.
int harness_set_setting(const char* ini_key, int value) {
    static const char* const kKeys[] = {"screen_mask", "mask_intensity", "color_profile", "xbrz_scale",
                                        "motion_smoothing", "perf_mode"};
    bool allowed = std::strncmp(ini_key, "hdtext_", 7) == 0;
    for (const char* k : kKeys) if (std::strcmp(ini_key, k) == 0) allowed = true;
    if (!allowed) return 0;
    SettingsRegistry& reg = settings_registry();
    for (const Setting& s : reg.settings())
        if (!s.ini_key.empty() && s.ini_key == ini_key) return reg.set(s.id, value) ? 1 : 0;
    return 0;
}
#endif

} // namespace

void configure_capture_harness() {
    gbaimp::capture::CaptureConfig c;
    c.env_prefix = "KHCOM";
    c.log_tag = "capture";
#if defined(GBARECOMP_RUNTIME_UI)
    c.apply_setting = &harness_set_setting;   // KHCOM_CAPTURE_VARIANTS: display / HD text / performance HUD keys
#endif
    gbaimp::capture::configure(c);
}

void save_khcom_config() {
#if defined(GBARECOMP_RUNTIME_UI)
    settings_registry().save_ini();
#endif
}

void load_khcom_config() {
#if defined(GBARECOMP_RUNTIME_UI)
    // Headless harness overrides (KHCOM_SET), same keys as the ini; applied after the file.
    std::string overrides;
    for (const auto& kv : gbaimp::capture::settings_overrides()) overrides += kv.first + "=" + std::to_string(kv.second) + '\n';
    settings_registry().load_ini(overrides);
#endif
}

#if defined(GBARECOMP_RUNTIME_UI)
bool is_runtime_menu_open() { return gbaimp::runtime_menu_is_open(g_active_runtime_ui); }
void open_runtime_menu() { gbaimp::runtime_menu_open(g_active_runtime_ui); }
void close_runtime_menu() { gbaimp::runtime_menu_close(g_active_runtime_ui); }
void toggle_runtime_menu() { gbaimp::runtime_menu_toggle(g_active_runtime_ui); }
#else
bool is_runtime_menu_open() {
    return false;
}
void open_runtime_menu() {}
void close_runtime_menu() {}
void toggle_runtime_menu() {}
#endif

gbarecomp::RunOptions create_run_options() {
#if defined(GBARECOMP_RUNTIME_UI)
#endif
    // Connect the gba-improvements library (hooks + HUD anchoring zones) before any
    // module can run or persist settings.
    register_gbaimp_integration();

    gbarecomp::RunOptions opts;
    opts.builtin_game_name = GAME_TITLE.data();
    opts.builtin_rom_sha1 = ROM_SHA1_USA.data();
    opts.launcher_region = "USA";
    opts.launcher_game_config = "game.toml";
#if defined(__ANDROID__)
    // The Android build drives touch from the bottom-screen panel (src/android/dual_screen.cpp);
    // keep the engine's on-screen pad off the game image. Built-in controls still work.
    opts.touch_pad_default = 0;
#endif

    // Assist tools: save states, fast-forward, and rewind
    opts.expose_assist_tools = true;
    opts.assist_tools_enabled_by_default = true;
    opts.assist_fast_forward_multiplier_default = 4;
    opts.save_state_slot_count = 10;
    opts.rewind_history_seconds = 60;
    opts.rewind_capture_interval_frames = 15;

    // Video display and viewport configuration:
    // Fixed 16:9 widescreen active ONLY during battle arenas; authentic 3:2 elsewhere
    opts.freely_resizable_window = true;
    opts.resize_driven_view = false;
    opts.max_view_width = 284;
    opts.max_resize_view_width = 284;
    opts.max_resize_view_height = 160;
    opts.launcher_expose_widescreen = true;
    opts.launcher_expose_adaptive_view = false;
    opts.widescreen_view_width = 284;
    opts.launcher_aspect_labels = kAspectLabels;
    opts.launcher_aspect_view_widths = kAspectWidths;
    opts.launcher_num_aspects = sizeof(kAspectWidths) / sizeof(kAspectWidths[0]);
    opts.launcher_expose_sharp_filter = true;
    opts.launcher_default_sharp_filter = true;
    opts.extended_view_init = &khcom_install_widescreen_adapter;
    opts.extended_view_frame = [](const gbarecomp::ExtendedViewFrameInfo* info) {
        khcom_update_widescreen_state();
        auto* bus = gbarecomp::active_bus();
        if (bus) {
            uint16_t host = bus->io().host_keyinput();
            uint16_t modified = gbaimp::InputEnhancements::instance().process_keyinput(host);
            if (gbaimp::capture::active()) gbaimp::capture::on_emulated_frame(info ? info->frame_count : 0);   // headless harness
            bus->io().set_keyinput(modified);
        }
    };

    // Immediately install widescreen hooks so battle state tracking is active from frame 0
    khcom_install_widescreen_adapter(22, 22);

#if defined(GBARECOMP_RUNTIME_UI)
    // The registry (menu rows, callbacks, khcom_config.ini) is built on first use; the binding turns it into
    // the runtime menu items and the ui_* callbacks. Both live for the whole process.
    gbaimp::RuntimeUiBinding& ui = ui_binding();
    ui.rebuild();
    ui.apply(opts);

    // Restore saved user configurations across game sessions
    load_khcom_config();
#endif

    return opts;
}

} // namespace khcom

