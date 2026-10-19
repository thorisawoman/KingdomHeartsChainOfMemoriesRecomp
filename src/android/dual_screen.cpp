#include "android/dual_screen.h"

#include <jni.h>

#include <algorithm>
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
constexpr uint32_t kDeckName = 0xC6;              // u8 name[0x14]
constexpr int kDeckNameSize = 0x14;
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
    std::array<uint8_t, kDeckNameSize> name{};
};
DeckSnapshot g_deck;

// Battle card lists (USA), located through cardbattle_0 (0x08076CB4) and its Riku twin
// (0x0807E910); CardBattleWork / CardSlot layout from the decompilation.
constexpr uint32_t kWorkSlots = 0x44;              // CardSlot* slots[4]
constexpr uint32_t kWorkCursors = 0x94;            // u16 cursors[4]
constexpr uint32_t kWorkSlotCounts = 0xA8;         // s16 slotCounts[4]
constexpr uint32_t kWorkCardsLeft = 0xB0;          // s16 cardsLeft[4]
constexpr uint32_t kWorkListIndex = 0xB8;          // s8
constexpr uint32_t kWorkStockCount = 0xB9;         // u8
constexpr uint32_t kSlotSize = 0xC;                // CardSlot: u32 cardId, u16 index, u8 pad,
constexpr uint32_t kSlotPadding = 0x6;             //   u8 stocked, used, restoreOnReload, removed
constexpr uint32_t kSlotStocked = 0x7;
constexpr uint32_t kSlotUsed = 0x8;
constexpr uint32_t kSlotRemoved = 0xA;
constexpr int kMaxHandSlots = kDeckSlots + 15;     // main list: deck + Reload + padding
constexpr uint32_t kHandEvery = 3;
// gCardBattleState->soraWork is not usable: cardbattle_0 stores the work pointer before
// CreateCardBattleState allocates (and zeroes) the state, so the live state never has it. The
// selected card's display work does: sSoraSelectedCard (static in card_battle.c, located from its
// 79 literal uses there and checked against live memory) -> CardDisplayArgs { ListPool* pool
// (= &work->cardDisplays[list]), CardSlot* slot, variant, u16 index, u8 listIndex }.
constexpr uint32_t kSoraSelectedCard = 0x02034A98;  // CardDisplayWork*
// Riku's twin in card_battle_riku.c: declared after its two request words (0x02034AA4 / AA8, 13 and
// 8 uses), 63 literal uses in Riku's battle code against Sora's 79.
constexpr uint32_t kRikuSelectedCard = 0x02034AAC;
constexpr uint32_t kDisplayArgsPool = 0x38;          // ListPool*
constexpr uint32_t kDisplayArgsIndex = 0x44;         // u16 selected slot
constexpr uint32_t kDisplayArgsList = 0x46;          // u8
constexpr uint32_t kWorkCardDisplays = 0x54;         // ListPool cardDisplays[4], 0x10 each

struct HandSnapshot {
    bool valid = false;
    int side = 0;                                  // 0 Sora, 1 Riku
    int list = 0;                                  // 0 main, 1 magic, 2 item, 3 enemy
    int cursor = -1;
    int cards_left = 0;
    int stock_count = 0;
    int slots = 0;
    // card ID | used << 16 | stocked << 17 | removed << 18
    std::array<uint32_t, kMaxHandSlots> cards{};
};
HandSnapshot g_hand;

// Floor map state (USA), located through CopyMapProgress (0x080E92B8) and GetMapFloorDef
// (0x080DED64); MapFloorState / MapFloorRoom layout from the decompilation.
constexpr uint32_t kGameState = 0x02039BB0;        // gGameState
constexpr uint32_t kGameFlags = 0x8;               // u32, 0x8 = playing as Riku
constexpr uint32_t kGameFloor = 0xE;               // s8
constexpr uint32_t kMapFloorState = 0x0203C590;    // gMapFloorState
constexpr uint32_t kFloorWorld = 0x4;              // u8 WorldId of this floor (gGameState's world
                                                   // byte is a stale default until a world is set)
constexpr uint32_t kFloorCurrentRoom = 0x6;        // u8
constexpr uint32_t kFloorRooms = 0x1C;             // MapFloorRoom rooms[32], 0x10 bytes each
constexpr uint32_t kFloorRoomSize = 0x10;          // u16 flags, u32 seed, u8 nameId, roomType, ...
constexpr int kFloorRoomCount = 32;
constexpr uint32_t kFloorFlags = 0x2;              // u16 live flags of the current floor
constexpr uint32_t kGameFloors = 0x184;            // GameFloor gGameState.floors[13]: u16 flags, u8 world
constexpr int kFloorCount = 13;

struct MapSnapshot {
    int world = -1, floor = -1, riku = 0, room = -1;
    std::array<uint32_t, kFloorRoomCount> rooms{};   // flags | nameId << 16 | roomType << 24
    uint32_t live_floor = 0;                          // current floor: flags | world << 16 (gMapFloorState)
    std::array<uint32_t, kFloorCount> floors{};       // stored floors: flags | world << 16
};
MapSnapshot g_map;

void read_map(MapSnapshot& m) {
    m.world = bus_read_u8(kMapFloorState + kFloorWorld);
    m.floor = static_cast<int8_t>(bus_read_u8(kGameState + kGameFloor));
    m.riku = (bus_read_u32(kGameState + kGameFlags) & 0x8u) ? 1 : 0;
    m.room = bus_read_u8(kMapFloorState + kFloorCurrentRoom);
    for (int i = 0; i < kFloorRoomCount; ++i) {
        const uint32_t r = kMapFloorState + kFloorRooms + static_cast<uint32_t>(i) * kFloorRoomSize;
        m.rooms[i] = bus_read_u16(r) | static_cast<uint32_t>(bus_read_u8(r + 8)) << 16 |
                     static_cast<uint32_t>(bus_read_u8(r + 9)) << 24;
    }
    m.live_floor = bus_read_u16(kMapFloorState + kFloorFlags) |
                   static_cast<uint32_t>(bus_read_u8(kMapFloorState + kFloorWorld)) << 16;
    for (int i = 0; i < kFloorCount; ++i) {
        const uint32_t f = kGameState + kGameFloors + 4u * static_cast<uint32_t>(i);
        m.floors[i] = bus_read_u16(f) | static_cast<uint32_t>(bus_read_u8(f + 2)) << 16;
    }
}

// Cards picked up recently (rolling, newest last). ObtainCard adds each new card to
// gCardCollection at pickup time (battle drops, and rewards collected in the field afterwards).
// Battles also take deck cards out of the collection and put them back, so slot changes alone are
// not pickups: per card (ID + premium) the tracker keeps the highest count ever seen, and only a
// count above that high-water mark is a new card. Cards that leave and return never count.
constexpr uint16_t kCollectionEmpty = 0xFFF;
constexpr int kMaxPickups = 16;
constexpr uint32_t kPickupEvery = 6;
constexpr int kCardKeys = 2048;                         // (ID & 0xFFF) + 1024 if premium
constexpr int kLoadJump = 6;                            // more new cards than this in one scan = load

struct PickupTracker {
    bool armed = false;
    std::array<uint16_t, kCardKeys> most{};             // high-water count per card
    std::array<uint16_t, kCardKeys> now{};
    int count = 0;
    std::array<uint16_t, kMaxPickups> cards{};
};
PickupTracker g_pickups;

int card_key(uint16_t entry) {
    const int id = entry & 0xFFF;
    return id >= 1024 ? -1 : id + ((entry & 0x8000) ? 1024 : 0);
}

void track_pickups(PickupTracker& p) {
    p.now.fill(0);
    for (int i = 0; i < kCollectionSize; ++i) {
        const uint16_t e = bus_read_u16(kCardCollection + 2u * static_cast<uint32_t>(i));
        if ((e & 0xFFF) == kCollectionEmpty) continue;
        const int k = card_key(e);
        if (k >= 0) ++p.now[k];
    }
    int total = 0, gained = 0;
    for (int k = 0; k < kCardKeys; ++k) {
        total += p.now[k];
        if (p.now[k] > p.most[k]) gained += p.now[k] - p.most[k];
    }
    if (total == 0) {                                    // title screen / no game in memory yet
        p.armed = false;
        return;
    }
    // Loading a save or starting a game swaps the whole collection in one frame; real pickups come
    // one to a few at a time. A big jump is a new baseline, not a list of new cards.
    if (!p.armed || gained > kLoadJump) {
        p.most = p.now;
        p.armed = true;
        return;
    }
    for (int k = 0; k < kCardKeys; ++k) {
        while (p.now[k] > p.most[k]) {
            ++p.most[k];
            if (p.count == kMaxPickups) {                // drop the oldest
                for (int j = 1; j < kMaxPickups; ++j) p.cards[j - 1] = p.cards[j];
                --p.count;
            }
            p.cards[p.count++] = static_cast<uint16_t>((k & 1023) | ((k & 1024) ? 0x8000 : 0));
        }
    }
}

// Sora's status (USA). PlayerProgression at gGameState + 0xF8 (u64 fields are 4-byte aligned by
// agbcc); live battle HP from the player actor *(gBtlWork + 0x7C) (BtlObj hp +0x2C, maxHp +0x2E),
// checked against battle dumps where it fell 80 -> 76 -> 70 -> 64 as Sora took hits.
constexpr uint32_t kGameFieldHp = 0x32;           // s16 gGameState.hp
constexpr uint32_t kProgression = 0xF8;           // PlayerProgression
constexpr uint32_t kProgMaxHp = 0x0, kProgCp = 0x2, kProgExp = 0x8, kProgNextExp = 0xC, kProgLevel = 0x10;
constexpr uint32_t kProgLearnedStocks = 0x14;     // u64 x 2
constexpr uint32_t kProgMooglePoints = 0x7C;      // u32
constexpr uint32_t kBtlWorkPtr = 0x02039B84;      // BtlWork* gBtlWork
constexpr uint32_t kBtlWorkPlayer = 0x7C;         // BtlObj* (Sora)
constexpr uint32_t kBtlObjHp = 0x2C, kBtlObjMaxHp = 0x2E;

struct StatusSnapshot {
    int in_battle = 0, hp = 0, max_hp = 0, level = 0, cp = 0, exp = 0, next_exp = 0, moogle = 0, sleights = 0;
    std::array<uint32_t, 4> learned{};   // learnedStocks (sleights 0-30), learnedStocks2 (31+), lo/hi words
};
StatusSnapshot g_status;

bool in_ewram(uint32_t addr, uint32_t size);

void read_status(StatusSnapshot& st, bool battle) {
    const uint32_t prog = kGameState + kProgression;
    st.max_hp = static_cast<int16_t>(bus_read_u16(prog + kProgMaxHp));
    st.cp = static_cast<int16_t>(bus_read_u16(prog + kProgCp));
    st.exp = static_cast<int>(bus_read_u32(prog + kProgExp));
    st.next_exp = static_cast<int>(bus_read_u32(prog + kProgNextExp));
    st.level = bus_read_u8(prog + kProgLevel);
    st.moogle = static_cast<int>(bus_read_u32(prog + kProgMooglePoints));
    st.sleights = 0;
    for (uint32_t w = 0; w < 4; ++w) {
        st.learned[w] = bus_read_u32(prog + kProgLearnedStocks + 4 * w);
        st.sleights += __builtin_popcount(st.learned[w]);
    }
    st.hp = static_cast<int16_t>(bus_read_u16(kGameState + kGameFieldHp));
    st.in_battle = 0;
    if (battle) {
        const uint32_t work = bus_read_u32(kBtlWorkPtr);
        const uint32_t actor = in_ewram(work, 0x80) ? bus_read_u32(work + kBtlWorkPlayer) : 0;
        if (in_ewram(actor, 0x30)) {
            const int max_hp = static_cast<int16_t>(bus_read_u16(actor + kBtlObjMaxHp));
            if (max_hp > 0) {
                st.hp = static_cast<int16_t>(bus_read_u16(actor + kBtlObjHp));
                st.max_hp = max_hp;
                st.in_battle = 1;
            }
        }
    }
}

bool in_ewram(uint32_t addr, uint32_t size = 4) {
    return addr >= 0x02000000u && addr + size <= 0x02040000u;
}

// The work pointers outlive the battle, so this only runs while the battle flag is set and
// validates every pointer and count before following it.
void read_hand(HandSnapshot& h) {
    h.valid = false;
    h.slots = 0;
    uint32_t work = 0;
    int selected = -1;
    h.side = (bus_read_u32(kGameState + kGameFlags) & 0x8u) ? 1 : 0;   // playing as Riku
    const uint32_t display = bus_read_u32(h.side ? kRikuSelectedCard : kSoraSelectedCard);
    if (in_ewram(display, 0xA8)) {
        const uint32_t pool = bus_read_u32(display + kDisplayArgsPool);
        const int dlist = bus_read_u8(display + kDisplayArgsList);
        if (dlist <= 3 && in_ewram(pool, 0x10)) {
            work = pool - kWorkCardDisplays - 0x10u * static_cast<uint32_t>(dlist);
            selected = bus_read_u16(display + kDisplayArgsIndex);
        }
    }
    if (!in_ewram(work, 0xCC)) return;
    const int list = static_cast<int8_t>(bus_read_u8(work + kWorkListIndex));
    if (list < 0 || list > 3) return;
    const uint32_t slots = bus_read_u32(work + kWorkSlots + 4u * static_cast<uint32_t>(list));
    const int count = static_cast<int16_t>(bus_read_u16(work + kWorkSlotCounts + 2u * static_cast<uint32_t>(list)));
    if (count < 0 || count > kMaxHandSlots) return;
    if (count > 0 && !in_ewram(slots, static_cast<uint32_t>(count) * kSlotSize)) return;
    h.list = list;
    h.cursor = selected >= 0 ? selected : bus_read_u16(work + kWorkCursors + 2u * static_cast<uint32_t>(list));
    h.cards_left = static_cast<int16_t>(bus_read_u16(work + kWorkCardsLeft + 2u * static_cast<uint32_t>(list)));
    h.stock_count = bus_read_u8(work + kWorkStockCount);
    for (int i = 0; i < count; ++i) {
        const uint32_t slot = slots + static_cast<uint32_t>(i) * kSlotSize;
        if (bus_read_u8(slot + kSlotPadding) != 0) continue;   // spare slots after Reload
        const uint32_t id = bus_read_u32(slot) & 0xFFFFu;
        h.cards[h.slots++] = id | (bus_read_u8(slot + kSlotUsed) ? 1u << 16 : 0u) |
                             (bus_read_u8(slot + kSlotStocked) ? 1u << 17 : 0u) |
                             (bus_read_u8(slot + kSlotRemoved) ? 1u << 18 : 0u);
    }
    h.valid = true;
}

void read_deck(DeckSnapshot& d) {
    d.active = bus_read_u8(kActiveDeck);
    d.collection_count = bus_read_u16(kCardCount);
    d.slots = 0;
    if (d.active < 0 || d.active > 2) return;
    const uint32_t deck = kDecks + static_cast<uint32_t>(d.active) * kDeckSize;
    d.cp_cost = bus_read_u16(deck + kDeckCpCost);
    for (int i = 0; i < kDeckNameSize; ++i) d.name[i] = bus_read_u8(deck + kDeckName + static_cast<uint32_t>(i));
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
    if (g_frame % kDeckEvery == 0) {
        read_deck(g_deck);
        read_map(g_map);
    }
    // Early boot (battle state unknown) waits, so the baseline is the loaded save, not uninitialised RAM.
    if (g_battle >= 0 && g_frame % kPickupEvery == 0) track_pickups(g_pickups);
    if (g_frame % kHandEvery == 0) read_status(g_status, g_battle == 1);
    if (g_battle != 1) g_hand.valid = false;
    else if (g_frame % kHandEvery == 0) read_hand(g_hand);
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
//        n collection entries (card ID | 0x8000 premium | deck flags), 20 deck name bytes];
// returns entries written.
JNIEXPORT jint JNICALL Java_org_khcomrecomp_KhNative_deckSnapshot(JNIEnv* env, jclass, jintArray out) {
    if (!out) return 0;
    std::array<jint, 5 + kDeckSlots + kDeckNameSize> buf{};
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
        for (int i = 0; i < kDeckNameSize; ++i) buf[n++] = g_deck.name[i];
    }
    const jsize cap = env->GetArrayLength(out);
    if (n > cap) n = cap;
    env->SetIntArrayRegion(out, 0, n, buf.data());
    return n;
}

// Battle card list: out = [valid (0/1), side (0 Sora / 1 Riku), list (0 main, 1 magic,
// 2 item, 3 enemy), cursor, cards left, stock count, n, n slots (card ID | used << 16 |
// stocked << 17 | removed << 18; ID 0xFFFE is the Reload card)]; returns entries written.
JNIEXPORT jint JNICALL Java_org_khcomrecomp_KhNative_handSnapshot(JNIEnv* env, jclass, jintArray out) {
    if (!out) return 0;
    std::array<jint, 7 + kMaxHandSlots> buf{};
    int n;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        buf[0] = g_hand.valid ? 1 : 0;
        buf[1] = g_hand.side;
        buf[2] = g_hand.list;
        buf[3] = g_hand.cursor;
        buf[4] = g_hand.cards_left;
        buf[5] = g_hand.stock_count;
        buf[6] = g_hand.valid ? g_hand.slots : 0;
        for (int i = 0; i < buf[6]; ++i) buf[7 + i] = static_cast<jint>(g_hand.cards[i]);
        n = 7 + buf[6];
    }
    const jsize cap = env->GetArrayLength(out);
    if (n > cap) n = cap;
    env->SetIntArrayRegion(out, 0, n, buf.data());
    return n;
}

// Recently picked-up cards, newest last: out = [n, n collection entries
// (card ID | 0x8000 premium)]; returns entries written.
JNIEXPORT jint JNICALL Java_org_khcomrecomp_KhNative_pickupSnapshot(JNIEnv* env, jclass, jintArray out) {
    if (!out) return 0;
    std::array<jint, 1 + kMaxPickups> buf{};
    int n;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        buf[0] = g_pickups.count;
        for (int i = 0; i < g_pickups.count; ++i) buf[1 + i] = g_pickups.cards[i];
        n = 1 + g_pickups.count;
    }
    n = std::min<int>(n, env->GetArrayLength(out));
    env->SetIntArrayRegion(out, 0, n, buf.data());
    return n;
}

// Sora's status: out = [in battle (0/1), hp, max hp, level, cp, exp, next exp, moogle points,
// sleights learned, 4 learned-sleight words]; returns entries written.
JNIEXPORT jint JNICALL Java_org_khcomrecomp_KhNative_statusSnapshot(JNIEnv* env, jclass, jintArray out) {
    if (!out) return 0;
    std::array<jint, 13> buf{};
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        const StatusSnapshot& st = g_status;
        buf = {st.in_battle, st.hp, st.max_hp, st.level, st.cp, st.exp, st.next_exp, st.moogle, st.sleights,
               static_cast<jint>(st.learned[0]), static_cast<jint>(st.learned[1]),
               static_cast<jint>(st.learned[2]), static_cast<jint>(st.learned[3])};
    }
    const jsize n = std::min<jsize>(env->GetArrayLength(out), static_cast<jsize>(buf.size()));
    env->SetIntArrayRegion(out, 0, n, buf.data());
    return n;
}

// Floor map: out = [world, floor (-1 none), riku (0/1), current room (0xFC tutorial, 0xFD exit hall,
// 0xFE entrance hall), 32 rooms (flags | nameId << 16 | roomType << 24), live floor
// (flags | world << 16), 13 stored floors (flags | world << 16)]; returns entries written.
JNIEXPORT jint JNICALL Java_org_khcomrecomp_KhNative_mapSnapshot(JNIEnv* env, jclass, jintArray out) {
    if (!out) return 0;
    std::array<jint, 4 + kFloorRoomCount + 1 + kFloorCount> buf{};
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        buf[0] = g_map.world;
        buf[1] = g_map.floor;
        buf[2] = g_map.riku;
        buf[3] = g_map.room;
        for (int i = 0; i < kFloorRoomCount; ++i) buf[4 + i] = static_cast<jint>(g_map.rooms[i]);
        buf[4 + kFloorRoomCount] = static_cast<jint>(g_map.live_floor);
        for (int i = 0; i < kFloorCount; ++i) buf[5 + kFloorRoomCount + i] = static_cast<jint>(g_map.floors[i]);
    }
    const jsize n = std::min<jsize>(env->GetArrayLength(out), static_cast<jsize>(buf.size()));
    env->SetIntArrayRegion(out, 0, n, buf.data());
    return n;
}

} // extern "C"
