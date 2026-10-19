# KHCOM on the AYN Thor: Session One

A personal, dual-screen Android port of **KHCOMRecomp** (the static recompilation of *Kingdom Hearts:
Chain of Memories*, USA) for the **AYN Thor**. The game runs natively on the top screen. The bottom
screen is a companion panel built from the game's own art and live game state: deck, battle cards,
floor map, Sora's status and touch controls.

This document records Session One: what was built, how to build and run it, how it fits together,
every game address it relies on and how each was verified, and what is left.

> Personal use only. No ROM, BIOS, music or game art is in this repository or in the APK. The app
> asks for your own dumps and decodes the game's art from your ROM at runtime. The recomp is
> PolyForm Noncommercial.

---

## 1. What you get

| Screen | Contents |
|---|---|
| Top (1920×1080) | The game, full screen, from the recompiled code. The engine's on-screen touch pad is off. Physical controls work, and **the left stick moves like the d-pad**. **Start** is the game's pause; the conversation log no longer pops up on Start. **BACK** (bottom right) opens and closes the settings menu. |
| Bottom (1240×1080) | **Title logo** until a game is in progress (boot, intro, title, file select). Then: **Header**: the game's *DECK n* panel with the deck name (or battle state) and live CARDS / CP. **Status strip**: Lv, animated HP gauge, CP, EXP; tap it for a status card. **Views**: **DECK** and **MAP**, switched with the header button or **the right stick click (R3)**. |

- **DECK**: the equipped deck as real cards (art, category border, value) decoded from the ROM. Tap
  a card for a close-up. A **New cards** strip shows the latest pickups.
- **Battle (automatic)**: the live battle card list. Used cards fly away, Reload deals them back
  in, friend cards (Donald, Goofy, …) flip in from above, and stocked cards ride high. **Tap a card
  to select it** (the panel presses L/R until the game's selection lands on it). **Tap the selected
  card to use it** (A).
- **MAP**: the current floor drawn the way the game's map screen draws it (room shapes from doors,
  unvisited rooms dimmed, event rooms, the current room pulsing, animated door markers for closed
  doors and hall exits), with the world, the floor and the current room's name. **Tap a room** to
  see its name and state. **In the castle halls between floors** it shows Castle Oblivion's 13
  floors as a tower, with the world chosen for each and whether it's cleared, and the bottom screen
  switches there automatically when you enter a hall.
- Everything else is on the physical controls. **BACK** opens the settings menu and **Start**
  pauses. The touch pad, PAD view and MENU button were removed in 0.18.

Verified on the device: deck view, battle list and motion, friend cards, New cards, tap-to-select
and use, map, card art and font, status strip, Start = pause only, BACK → settings menu.

---

## 2. Build and install

Built on macOS (Apple Silicon). The port also keeps the desktop build working on macOS.

**Toolchain** (Homebrew + Android command-line tools):

```bash
brew install cmake ninja sdl2 openjdk@17 android-commandlinetools gradle
sdkmanager --sdk_root="$HOME/Library/Android/sdk" "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;27.1.12297006" "cmake;3.22.1"
```

**Android APK** (arm64, debug-signed):

```bash
cd android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The first native build takes a
few minutes (the recompiled corpus is ~270 MB of C++); later builds are incremental. Bump
`versionCode` / `versionName` in `app/build.gradle` for every build you hand out, so the installed
build can be checked in *Settings → Apps*.

**Install** (Thor connected over USB, USB debugging on):

```bash
adb install -r KHCOM-Thor-<version>.apk
```

On first launch the setup screen asks for your ROM (USA, SHA-1
`10729bd884f8fdca7a310b6d606c52e46657aa48`) and BIOS (`gba_bios.bin`, SHA-1
`300c20df6731a33952ded8c436f7f186d25d3492`). **The BIOS is required**: the runtime executes the real
(recompiled) BIOS and will not boot without it, whatever the upstream README says.

**Saves.** The game saves to 32 KB SRAM (Nintendo's `SRAM_F_V103` library; `save.c` writes from
`0x0E000010`). Upstream's configs declared EEPROM, so in-game saves never left memory. All three
configs now say `type = "sram"`, `size = 32768`. The save file is `files/roms/khcom_usa.sav` in app
storage (`roms/<rom>.sav` on desktop), written about a second after each in-game save. Bump
`payloadVersion` in `app/build.gradle` whenever `android/payload` changes, because the shell only
re-copies the payload on a new version.

**macOS desktop build** (for quick checks):

```bash
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release && cmake --build build --target KHCOMRecomp
```

Put the ROM in `roms/` and the BIOS at `bios/gba_bios.bin` (both git-ignored).

---

## 3. How it fits together

```
recomp/
├── CMakeLists.txt            if(ANDROID): game -> libmain.so; if(APPLE): compile-time SDL renames
├── src/main.cpp              SDL_main for Android (payload TOML, 256 MiB game-thread stack)
├── src/game_config.cpp       Android: touch_pad_default = 0 (engine pad off)
├── src/sdl_intercepts.cpp    per-poll hook -> khcom::dual_screen::on_poll()
├── src/android/dual_screen.* reads guest RAM on the game thread; JNI snapshots for Java
├── src/apple/apple_ld_wraps.c  macOS: __real_SDL_* (Apple ld has no --wrap)
├── android/                  Gradle project on gbarecomp's shared Android template
│   ├── app/build.gradle      game block: ROM/BIOS hashes, payload, companion class, version
│   ├── payload/game.toml     Android runtime config (paths into app storage)
│   └── app/src/main/java/org/khcomrecomp/
│       ├── KhDualScreen.java        GbaCompanion: shows the panel on the secondary display
│       ├── KhBottomPresentation.java  the panel: views, header, status, taps, touch pad
│       ├── KhRomArt.java            decodes cards, font, deck panels, floor map from the ROM
│       ├── KhHandAnimator.java      battle card motion
│       ├── KhCards.java             card names/categories (from the decomp's card enums)
│       └── KhNative.java            JNI declarations
├── gbarecomp/ (submodule)    + GbaCompanion hook in the shared Android shell; left stick -> d-pad
└── external/gba-improvements/ (submodule)  + link fixes for Android (no -lpthread) and macOS
```

**Threading rule.** Java never touches guest state. `dual_screen.cpp` reads RAM on the game thread
(from the per-poll hook) into snapshots. Java polls them over JNI (every 33 ms in battle, 100 ms
otherwise). Input goes back through the normal paths: injected keys are mapped by `keybinds.ini`
(A=X, B=Z, L=C, R=V, Start=Enter, Select=Right Shift), and the settings menu is toggled on the game
thread.

**Art rule.** `KhRomArt` reads the installed ROM from app-private storage and builds images exactly
as the game does (OAM frame records + 4bpp tiles + BGR555 palettes, or BG tilemaps). Nothing from
the game is bundled.

**Buttons (Thor).** The built-in "Odin Controller" sends `BTN_START`, `BTN_SELECT` and `KEY_BACK`
(measured with `getevent -l`). SDL turns a gamepad's BACK key into the controller Back button (GBA
Select), and the conversation log (gba-improvements' `TextHistory`) toggled on that button and on
raw joystick buttons 4, 6 and 8, where Start lands. On Android the log's button toggles are cleared
(`src/gbaimp_integration.cpp`; it is still in the settings menu and on F2). `KhDualScreen.onKeyEvent`
consumes `KEYCODE_BACK` before SDL sees it and toggles the settings menu.

**Engine changes** are small and generic, so they can go upstream:
- an optional `GbaCompanion` per-game hook (`gbaGame.companion`) on the game Activity, including
  `onKeyEvent` (sees keys before SDL);
- left-stick → d-pad in the keypad builder;
- the link fixes.

---

## 4. Game addresses (USA B8CE)

Found by combining three sources:
- the **pheenoh/khcom** decompilation (struct layouts and asset tables, `config/assets/*.yaml`);
- the **recomp's own `generated/*.cpp`**, whose per-instruction disassembly comments were turned into
  a listing with PC-relative literals resolved from the ROM;
- **live memory reads** on the Thor.

### RAM

| What | Address / path | Found via |
|---|---|---|
| `gDecks[3]` (0xE0 each: cards u16[99] = collection indices, name UTF-16 ×10 @+0xC6, cpCost @+0xDA, count @+0xDC) | `0x02039DE0` | `WriteCardSaveSlice` 0x080A324C |
| `gCardCollection[999]` (card ID \| 0x8000 premium; 0xFFF empty) | `0x0203A080` | same |
| `gCardCount` / active deck index | `0x0203A858` / `0x02034AB0` | same; `GetActiveDeckIndex` 0x08085FA4 |
| `gGameState` (flags +8, 0x8 = Riku; floor s8 +0xE; field HP s16 +0x32) | `0x02039BB0` | `CopyMapProgress` 0x080E92B8 |
| `PlayerProgression` (maxHp +0, cp +2, exp +8, nextExp +0xC, level +0x10, learnedStocks +0x14, moogle +0x7C) | `gGameState + 0xF8` | decomp assert + live dumps |
| `gMapFloorState` (flags +2, world +4, current room +6: `0xFC` tutorial / `0xFD` exit hall / `0xFE` entrance hall; rooms[32] ×16 bytes @+0x1C) | `0x0203C590` | `CopyMapProgress` |
| `gGameState.floors[13]` (`GameFloor`: flags u16 (`0x1` cleared), world u8) | `gGameState + 0x184` | `CopyMapProgress` |
| `gBtlWork` pointer → Sora's `BtlObj` at +0x7C (hp +0x2C, maxHp +0x2E) | `0x02039B84` | live dumps (HP 80→64) |
| Riku's battle cards: `sRikuSelectedCard` (same layout; chosen by the Riku flag) | `0x02034AAC` | literal counts in Riku's battle code (63 uses; its request words `0x02034AA4` / `AA8` come first in the file) |
| **Battle cards**: `sSoraSelectedCard` → `CardDisplayArgs` (pool +0x38 = `&work->cardDisplays[list]`, index +0x44, list +0x46) → `CardBattleWork` (slots +0x44, slotCounts +0xA8, cardsLeft +0xB0, listIndex +0xB8, stockCount +0xB9); `CardSlot` 0xC bytes (id u32, pad +6, stocked +7, used +8, removed +0xA); Reload = 0xFFFE; friend cards follow Reload | `0x02034A98` | live dumps |
| Battle-mode words (via `khcom_is_battle_active`) | `0x03007488` / `0x03007494` | upstream widescreen adapter |

**Do not use `gCardBattleState->soraWork` (`0x02039DD4` + 0x5C).** `cardbattle_0` stores the work
pointer before `CreateCardBattleState` allocates and zeroes the state, so the live state never holds
it. This is a bug in the game itself, and it is why the first battle view showed nothing.
`cursors[]` also stays 0; the selection is `CardDisplayArgs.index`.

**Pickups.** `ObtainCard` adds each new card to the collection at pickup time. Battle rewards are
collected in the field after the battle, not during it. Battles also take deck cards out of the
collection and put them back, so the tracker keeps the highest count ever seen for each card (ID +
premium). Only a count above that counts as a new card, which avoids false "new cards" when cards
come back. Loading a save changes the whole collection at once, so a scan with more than 6 gains
resets the baseline instead of reporting them.

### ROM

| What | Address |
|---|---|
| `gCardDefs[950]` (0x34 each: gfx, tiles, palette, …; flags +30, value +32, category +42) | `0x08F70AB0` |
| `gCardBacks[5]` (border / outline / back frames + tiles) · `gCard00Palette` | `0x08F709B0` · `0x09611AB8` |
| Card value digits: frames · tiles | `0x09EE981C` · `0x0905EAE8` |
| Dialogue font: widths u16[256] · frames · tiles (16×16, colour 1 body / 2 shadow) | `0x08F7D438` · `0x09EEC134` · `0x090CBFB2` |
| Deck panels (tiles, map) 1/2/3 · `gDeckMenuPalettes[15]` | `0x09406F78/0x09519AB8`, `0x09407B78/0x0951A2B8`, `0x0940B998/0x0951AAB8` · `0x09614118` |
| `gMapFloorDefs` (12 bytes: entry, exit, links* +4, eventDoors* +8) Sora · Riku | `0x0984C868` · `0x0984CBD0` |
| Map room tiles (18 shapes × 32×32) · room palettes · map BG palettes | `0x0976B340` · `0x0984A0F8` · `0x09849F78` |
| Map door markers: closed-door frames / sheet · hall-door frames / sheet (tile data at sheet + 248) | `0x09EF654C` / `0x0976E3DC` · `0x09EF64FC` / `0x0976DDE4` |
| Title logo (drawn at (80, 70), back to front): heart · KINGDOM HEARTS · crown · CHAIN of MEMORIES, as frames table / sheet / palette (tile data = whole tiles ending at the sheet end) | `0x09EF65C4/0x0976FCB0/0x0984A738` (2 banks) · `0x09EF65BC/0x0976F1B8/0x0984A718` · `0x09EF65B4/0x0976F0DC/0x0984A6F8` · `0x09EF65AC/0x0976ED34/0x0984A6F8` |

Names (in `KhNames.java`, generated from the decomp): room names in `gRoomNames` order (indexed by
`MapFloorRoom.nameId`), and sleights in `LearnedStock` order (bits 0–30 of `learnedStocks`, then
`learnedStocks2` from bit 0).

---

## 5. Debugging on the device

The debug APK is `debuggable`, so with the Thor on USB you can read the running game's memory:

```bash
adb exec-out run-as org.khcomrecomp.android dd if=/proc/<pid>/mem bs=4096 skip=<addr/4096> count=<pages>
```

To locate guest memory:
1. Find the address of `gbarecomp::g_active_bus` with `llvm-nm` on the **unstripped** library in
   `app/build/intermediates/cxx/.../obj/arm64-v8a/libmain.so`. The offset changes with every build.
2. Add it to the `libmain.so` base address from `/proc/<pid>/maps`, then read the 8-byte pointer
   stored there.
3. EWRAM starts at that pointer + 0x84, and IWRAM follows at +0x40000.

Screenshots of each screen:

```bash
adb exec-out screencap -p -d 4630946482288158084 > bottom.png
adb exec-out screencap -p -d 4630946441858561667 > top.png
```

The display IDs are the Thor's physical IDs; get them from `dumpsys SurfaceFlinger --display-id`.
The game log is at `files/android-runtime.log` (`run-as … cat`).

A small recorder script was used to poll the battle-mode word and save RAM dumps plus screenshots
whenever a battle was active. That's how the battle work and the `gCardBattleState` bug were found.
The Thor's launcher can force-stop the app when it is reopened from the launcher, so re-read the
pid after any restart.

---

## 6. Version history (Session One)

| Version | Change |
|---|---|
| 0.1 | First APK: game on Android, bottom-screen Presentation with touch buttons and RAM watches |
| 0.4 | Real card art decoded from the ROM; versioned APK names |
| 0.5 | Game font and the deck menu's DECK panels |
| 0.6 | Floor map view |
| 0.7 | Map world fix (`gMapFloorState.world`); first pickup tracking |
| 0.8 | Engine touch pad hidden; left stick → d-pad; pickups tracked after battle |
| 0.9 | Battle card motion |
| 0.10 | **Battle list actually read** (via `sSoraSelectedCard`); tap-to-select / use; deck close-up |
| 0.11 | Category borders for friend cards; shorter battle name bar; UTF-16 deck name |
| 0.12 | Sora's status strip and status card |
| 0.13 | Start no longer opens the conversation log; BACK opens the settings menu |
| 0.14 | Riku's battle cards; map door markers, room names and tap-a-room; sleight names in the status card |
| 0.15 | New cards no longer counts cards returning after a battle; compact status card so sleight names fit |
| 0.16 | **In-game saves work**: save type SRAM 32 KB (was EEPROM, so nothing was ever written) |
| 0.17 | New cards ignores the collection swap when a save loads |
| 0.18 | Title logo on the bottom screen until play starts; touch pad / PAD view / MENU removed; R3 switches DECK/MAP |
| 0.19 | Castle Oblivion floor view in the halls between floors (worlds, cleared floors, current hall) |

Source: <https://github.com/thorisawoman/KingdomHeartsChainOfMemoriesRecomp> (branch
`android-thor-port`, a fork of rafadono/KingdomHeartsChainOfMemoriesRecomp). The changed
submodules are forks too: `gbarecomp` → thorisawoman/gbarecomp (`android-companion`) and
`external/gba-improvements` → thorisawoman/gba-improvements (`android-macos-host`); `.gitmodules`
points at them, so a recursive clone builds:

```bash
git clone --recursive -b android-thor-port https://github.com/thorisawoman/KingdomHeartsChainOfMemoriesRecomp.git
```

No APKs are published: every APK contains the recompiled game (`libmain.so`). Build your own and
use your own ROM and BIOS.

---

## 7. Next

- Riku's battle list (`0x02034AAC`) is found from code only; check it in Riku's story with a live
  dump, as Sora's was.
- Upstream the generic engine changes (companion hook, stick, link fixes).
