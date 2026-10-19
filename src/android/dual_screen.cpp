#include "android/dual_screen.h"

#include <jni.h>

#include <array>
#include <atomic>
#include <cstdint>
#include <mutex>

#include "game_config.h"
#include "widescreen_adapter.h"

extern "C" uint32_t bus_read_u32(uint32_t addr);
extern "C" uint16_t bus_read_u16(uint32_t addr);
extern "C" uint8_t bus_read_u8(uint32_t addr);

namespace khcom::dual_screen {
namespace {

// Guest words the bottom screen may watch. Only EWRAM / IWRAM: plain memory, no IO side effects.
constexpr int kMaxWatches = 64;

bool is_plain_ram(uint32_t addr) {
    return (addr >= 0x02000000u && addr + 4 <= 0x02040000u) ||
           (addr >= 0x03000000u && addr + 4 <= 0x03008000u);
}

std::mutex g_mutex;
std::array<uint32_t, kMaxWatches> g_watch_addrs{};
std::array<uint32_t, kMaxWatches> g_watch_values{};
int g_watch_count = 0;
uint32_t g_frame = 0;
int g_battle = -1;
std::atomic<bool> g_toggle_menu{false};

// Card state (USA B8CE), located through WriteCardSaveSlice (0x080A324C) and the active-deck
// accessors (0x08085FA4 / 0x08085B50); struct layout from the pheenoh/khcom decompilation.
constexpr uint32_t kDecks = 0x02039DE0;           // Deck gDecks[3], 0xE0 bytes each
constexpr uint32_t kDeckSize = 0xE0;
constexpr uint32_t kDeckCpCost = 0xDA;            // u16
constexpr uint32_t kDeckCardCount = 0xDC;         // u16
constexpr uint32_t kCardCollection = 0x0203A080;  // u16 gCardCollection[999]
constexpr uint32_t kCardCount = 0x0203A858;       // u16 gCardCount
constexpr uint32_t kActiveDeck = 0x02034AB0;      // u8
constexpr int kDeckSlots = 99;
constexpr int kCollectionSize = 999;
constexpr uint16_t kCardNone = 0xFFFF;
constexpr uint32_t kDeckEvery = 10;               // polls between deck snapshots

struct DeckSnapshot {
    int active = -1;
    int cp_cost = 0;
    int card_count = 0;
    int collection_count = 0;
    int slots = 0;                                 // entries in cards
    std::array<uint16_t, kDeckSlots> cards{};      // collection entries (ID | flags)
};
DeckSnapshot g_deck;

void read_deck(DeckSnapshot& d) {
    d.active = bus_read_u8(kActiveDeck);
    d.collection_count = bus_read_u16(kCardCount);
    d.slots = 0;
    if (d.active < 0 || d.active > 2) return;
    const uint32_t deck = kDecks + static_cast<uint32_t>(d.active) * kDeckSize;
    d.cp_cost = bus_read_u16(deck + kDeckCpCost);
    d.card_count = bus_read_u16(deck + kDeckCardCount);
    for (int i = 0; i < kDeckSlots; ++i) {
        const uint16_t index = bus_read_u16(deck + 2u * static_cast<uint32_t>(i));
        if (index == kCardNone || index >= kCollectionSize) continue;
        d.cards[d.slots++] = bus_read_u16(kCardCollection + 2u * index);
    }
}

// -1 before the game has set its mode words (early boot), then field (0) / battle (1).
int read_battle_context() {
    if (bus_read_u32(0x03007488u) == 0 && bus_read_u32(0x03007494u) == 0) return -1;
    return khcom_is_battle_active() ? 1 : 0;
}

} // namespace

void on_poll() {
    if (g_toggle_menu.exchange(false)) khcom::toggle_runtime_menu();
    std::lock_guard<std::mutex> lock(g_mutex);
    ++g_frame;
    g_battle = read_battle_context();
    for (int i = 0; i < g_watch_count; ++i) g_watch_values[i] = bus_read_u32(g_watch_addrs[i]);
    if (g_frame % kDeckEvery == 0) read_deck(g_deck);
}

} // namespace khcom::dual_screen

using namespace khcom::dual_screen;

extern "C" {

JNIEXPORT void JNICALL Java_org_khcomrecomp_KhNative_toggleMenu(JNIEnv*, jclass) {
    g_toggle_menu.store(true);
}

// Replaces the watched address list (addresses outside EWRAM / IWRAM are dropped).
JNIEXPORT void JNICALL Java_org_khcomrecomp_KhNative_setWatches(JNIEnv* env, jclass, jintArray addrs) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_watch_count = 0;
    if (!addrs) return;
    const jsize n = env->GetArrayLength(addrs);
    jint* a = env->GetIntArrayElements(addrs, nullptr);
    for (jsize i = 0; i < n && g_watch_count < kMaxWatches; ++i) {
        const uint32_t addr = static_cast<uint32_t>(a[i]);
        if (is_plain_ram(addr)) g_watch_addrs[g_watch_count++] = addr;
    }
    env->ReleaseIntArrayElements(addrs, a, JNI_ABORT);
}

// out = [frame, battle (-1 unknown / 0 field / 1 battle), watch values...]; returns entries written.
JNIEXPORT jint JNICALL Java_org_khcomrecomp_KhNative_snapshot(JNIEnv* env, jclass, jintArray out) {
    if (!out) return 0;
    std::array<jint, 2 + kMaxWatches> buf{};
    int n;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        buf[0] = static_cast<jint>(g_frame);
        buf[1] = g_battle;
        for (int i = 0; i < g_watch_count; ++i) buf[2 + i] = static_cast<jint>(g_watch_values[i]);
        n = 2 + g_watch_count;
    }
    const jsize cap = env->GetArrayLength(out);
    if (n > cap) n = cap;
    env->SetIntArrayRegion(out, 0, n, buf.data());
    return n;
}

// out = [active deck (0-2, -1 unknown), deck CP cost, deck card count, collection count, n,
//        n collection entries (card ID | 0x8000 premium | deck flags)]; returns entries written.
JNIEXPORT jint JNICALL Java_org_khcomrecomp_KhNative_deckSnapshot(JNIEnv* env, jclass, jintArray out) {
    if (!out) return 0;
    std::array<jint, 5 + kDeckSlots> buf{};
    int n;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        buf[0] = g_deck.active;
        buf[1] = g_deck.cp_cost;
        buf[2] = g_deck.card_count;
        buf[3] = g_deck.collection_count;
        buf[4] = g_deck.slots;
        for (int i = 0; i < g_deck.slots; ++i) buf[5 + i] = g_deck.cards[i];
        n = 5 + g_deck.slots;
    }
    const jsize cap = env->GetArrayLength(out);
    if (n > cap) n = cap;
    env->SetIntArrayRegion(out, 0, n, buf.data());
    return n;
}

} // extern "C"
