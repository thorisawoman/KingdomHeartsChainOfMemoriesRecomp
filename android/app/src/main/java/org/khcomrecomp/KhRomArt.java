package org.khcomrecomp;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.SparseArray;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Game art decoded at runtime from the player's own ROM (USA B8CE), which the setup
 * screen stores in app-private storage: card images, the dialogue font and the deck
 * menu panels. Nothing from the game ships in the APK.
 *
 * A card is built the way the game's battle renderer (card_2) draws it, all at one
 * origin: the picture (gCardDefs[id].gfx / tiles / palette), the category border
 * (gCardBacks[category].gfx, gCard00Palette) over it, and the value digit
 * (gCardValueDigitFrames[value]) over that; enemy cards have no digit. Summon and
 * friend cards use the black border, as the prize-card code does. Addresses come
 * from the pheenoh/khcom asset tables and were checked against the ROM.
 */
final class KhRomArt {
    private static final int ROM_BASE = 0x08000000;
    private static final int CARD_DEFS = 0x08F70AB0;      // CardDef gCardDefs[950], 0x34 bytes each
    private static final int CARD_DEF_SIZE = 0x34;
    private static final int CARD_DEF_COUNT = 950;
    private static final int CARD_BACKS = 0x08F709B0;     // CardBack gCardBacks[5], 6 pointers each
    private static final int CARD_PALETTE = 0x09611AB8;   // gCard00Palette (borders, digits, backs)
    private static final int DIGIT_FRAMES = 0x09EE981C;   // gCardValueDigitFrames[15]
    private static final int DIGIT_TILES = 0x0905EAE8;    // gCardValueDigitTiles tile data
    private static final int FLAG_SUMMON = 0x4, FLAG_FRIEND = 0x8;

    // Dialogue font (LayoutMsgGlyphs): glyph = character code 32-255, advance from
    // sLatinGlyphWidths, 16x16 tiles from the glyph's frame (gMsgLatinFontFrames[g][3]).
    private static final int GLYPH_WIDTHS = 0x08F7D438;   // u16 sLatinGlyphWidths[256]
    private static final int FONT_FRAMES = 0x09EEC134;    // gMsgLatinFontFrames[256]
    private static final int FONT_TILES = 0x090CBFB2;     // gMsgLatinFontTiles tile data
    /** Font cell height; glyphs use colour 1 (body) and 2 (shadow). */
    static final int FONT_HEIGHT = 16;

    // Deck menu (card_deckmenu2.c): panel tiles + tilemap per deck, gDeckMenuPalettes.
    private static final int[][] DECK_PANELS = {           // {tiles, map}
        {0x09406F78, 0x09519AB8}, {0x09407B78, 0x0951A2B8}, {0x0940B998, 0x0951AAB8},
    };
    private static final int DECK_MENU_PALETTES = 0x09614118;   // 15 BG palettes
    private static final int PANEL_PALETTE = 8;
    /** Deck panel size in GBA pixels (tilemap columns 0-18, rows 0-5). */
    static final int PANEL_WIDTH = 152, PANEL_HEIGHT = 48;
    /** Panel regions in GBA pixels: name bar, CARDS value, CP value (placeholders erased). */
    static final int[] PANEL_NAME = {6, 10, 113, 24}, PANEL_CARDS = {12, 33, 57, 41}, PANEL_CP = {68, 33, 148, 41};
    /** Deck menu backdrop colour (gDeckMenuMap). */
    static final int BACKDROP = 0xFF000858;

    // Floor map, as the allmap screen draws it (allmap.c / mode_allmap.c).
    private static final int FLOOR_DEFS = 0x0984C868, RIKU_FLOOR_DEFS = 0x0984CBD0;  // MapFloorDef, 12 bytes
    private static final int FLOOR_DEF_COUNT = 14;
    private static final int ROOM_TILES = 0x0976B340;    // gAllmapRoomTiles: 18 shapes x 16 tiles (32x32)
    private static final int ROOM_PALETTES = 0x0984A0F8; // gAllmapRoomPalettes[7]
    private static final int MAP_BG_PALETTES = 0x09849F78; // gAllmapBgPalettes[8] (BG banks 8-15)
    private static final int[] ROOM_SHAPE_ORDER = {0, 4, 2, 5, 3, 10, 9, 13, 1, 7, 8, 11, 6, 14, 12, 15};
    private static final int[][] ROOM_STEP = {{1, -1}, {-1, 1}, {1, 1}, {-1, -1}};   // UR, DL, DR, UL
    private static final int ROOM_NONE = 0xFF, ROOM_EXIT_HALL = 0xFD, ROOM_ENTRANCE_HALL = 0xFE;
    static final int FLOOR_ROOM_VISITED = 0x2, FLOOR_ROOM_LOCKED = 0x4, FLOOR_ROOM_EVENT_DONE = 0x8;
    private static final int EVENT_ROOM = 1, EVENT_HIDDEN_CHAMBER = 2, EVENT_BOSS_ROOM = 4, EVENT_END = 5;
    /** Room cell spacing and size in GBA pixels. */
    static final int ROOM_STEP_PX = 24, ROOM_SIZE_PX = 32;

    /** One floor's rooms: grid position (-1 when unreachable), links and event-door data. */
    static final class FloorLayout {
        final int[] x = new int[32], y = new int[32];
        final int[] links = new int[32 * 4];
        final int[] eventKind = new int[32], eventSide = new int[32];
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        int width() { return (maxX - minX) * ROOM_STEP_PX + ROOM_SIZE_PX; }
        int height() { return (maxY - minY) * ROOM_STEP_PX + ROOM_SIZE_PX; }
    }
    private static final int CATEGORY_ENEMY = 3;

    /** The card's opaque area inside the 64x64 sprite canvas (same for every card). */
    static final int WIDTH = 28, HEIGHT = 39;
    private static final int CROP_X = 18, CROP_Y = 12, CANVAS = 64;

    /** Card back colours (gCardBacks order). */
    static final int BACK_RED = 0, BACK_BLUE = 1, BACK_GREEN = 2, BACK_BLACK = 3, BACK_WHITE = 4;

    private static final int[][] SHAPES = {   // [shape][size] -> {w, h}
        {8, 8, 16, 16, 32, 32, 64, 64},
        {16, 8, 32, 8, 32, 16, 64, 32},
        {8, 16, 8, 32, 16, 32, 32, 64},
    };

    private final RandomAccessFile rom;
    private final long romSize;
    private final SparseArray<Bitmap> cards = new SparseArray<>();
    private final SparseArray<Bitmap> backs = new SparseArray<>();
    private final SparseArray<Bitmap> panels = new SparseArray<>();
    private int[] panelColours;
    private final SparseArray<FloorLayout> floors = new SparseArray<>();
    private final SparseArray<int[]> mapPalettes = new SparseArray<>();
    private final Map<String, Bitmap> texts = new LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) { return size() > 160; }
    };
    private final Map<Integer, byte[]> pages = new LinkedHashMap<Integer, byte[]>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) { return size() > 256; }
    };

    private KhRomArt(RandomAccessFile rom) throws IOException {
        this.rom = rom;
        this.romSize = rom.length();
    }

    /** Opens the installed ROM, or returns null when it is missing or not the 32 MiB USA dump. */
    static KhRomArt open(Context context, String romFile) {
        File f = new File(context.getFilesDir(), "roms/" + romFile);
        if (!f.isFile() || f.length() != 32L * 1024 * 1024) return null;
        try {
            return new KhRomArt(new RandomAccessFile(f, "r"));
        } catch (IOException e) {
            return null;
        }
    }

    void close() {
        try {
            rom.close();
        } catch (IOException ignored) {
        }
    }

    /** The full card for a card ID (flags ignored), or null if it cannot be decoded. */
    synchronized Bitmap card(int id) {
        id &= KhCards.ID_MASK;
        if (id >= CARD_DEF_COUNT) return null;
        Bitmap b = cards.get(id);
        if (b != null) return b;
        try {
            int def = CARD_DEFS + id * CARD_DEF_SIZE;
            int gfx = u32(def), tiles = u32(def + 4), palette = u32(def + 8);
            int flags = u16(def + 30), value = u8(def + 32), category = u8(def + 42);
            int back = (flags & (FLAG_SUMMON | FLAG_FRIEND)) != 0 ? CATEGORY_ENEMY : Math.min(category, 4);
            int[] canvas = new int[CANVAS * CANVAS];
            int[] cardColours = palette(CARD_PALETTE);
            draw(canvas, gfx, tiles, palette(palette));
            draw(canvas, u32(CARD_BACKS + back * 24), u32(CARD_BACKS + back * 24 + 12), cardColours);
            if (category != CATEGORY_ENEMY && value < 15)
                draw(canvas, u32(DIGIT_FRAMES + 4 * value), DIGIT_TILES, cardColours);
            b = crop(canvas);
        } catch (IOException | RuntimeException bad) {
            return null;
        }
        cards.put(id, b);
        return b;
    }

    /** The reverse side of a card in one of the five back colours, or null. */
    synchronized Bitmap back(int colour) {
        Bitmap b = backs.get(colour);
        if (b != null) return b;
        try {
            int[] canvas = new int[CANVAS * CANVAS];
            draw(canvas, u32(CARD_BACKS + colour * 24 + 8), u32(CARD_BACKS + colour * 24 + 20), palette(CARD_PALETTE));
            b = crop(canvas);
        } catch (IOException | RuntimeException bad) {
            return null;
        }
        backs.put(colour, b);
        return b;
    }

    /** Colour i (0-15) of the deck panel palette: 0xd panel fill, 0xf / 0xe digits, 0xa outline. */
    synchronized int panelColour(int i) {
        try {
            if (panelColours == null) panelColours = palette(DECK_MENU_PALETTES + 32 * PANEL_PALETTE);
            return panelColours[i & 15];
        } catch (IOException | RuntimeException bad) {
            return 0xFF406060;
        }
    }

    /** The deck menu panel for deck 0-2 (DECK n tab, name bar, CARDS / CP fields), or null. */
    synchronized Bitmap deckPanel(int deck) {
        if (deck < 0 || deck > 2) return null;
        Bitmap b = panels.get(deck);
        if (b != null) return b;
        try {
            int[][] pal = new int[15][];
            for (int k = 0; k < 15; k++) pal[k] = palette(DECK_MENU_PALETTES + 32 * k);
            int tiles = DECK_PANELS[deck][0], map = DECK_PANELS[deck][1];
            int[] px = new int[PANEL_WIDTH * PANEL_HEIGHT];
            for (int r = 0; r < PANEL_HEIGHT / 8; r++) {
                for (int c = 0; c < PANEL_WIDTH / 8; c++) {
                    int e = u16(map + 2 * (r * 32 + c));
                    int t = e & 0x3FF, bank = (e >> 12) % 15;
                    boolean hf = (e & 0x400) != 0, vf = (e & 0x800) != 0;
                    for (int py = 0; py < 8; py++) {
                        for (int x = 0; x < 8; x++) {
                            int b8 = u8(tiles + 32 * t + py * 4 + x / 2);
                            int v = (x & 1) != 0 ? b8 >> 4 : b8 & 15;
                            if (v == 0) continue;
                            int sx = hf ? 7 - x : x, sy = vf ? 7 - py : py;
                            px[(r * 8 + sy) * PANEL_WIDTH + c * 8 + sx] = pal[bank][v];
                        }
                    }
                }
            }
            // The game paints live numbers over these placeholders ("01/23", "4444/5555").
            int fill = pal[PANEL_PALETTE][13];
            for (int[] rect : new int[][] {PANEL_CARDS, PANEL_CP})
                for (int y = rect[1]; y < rect[3]; y++)
                    for (int x = rect[0]; x < rect[2]; x++) px[y * PANEL_WIDTH + x] = fill;
            b = Bitmap.createBitmap(px, PANEL_WIDTH, PANEL_HEIGHT, Bitmap.Config.ARGB_8888);
        } catch (IOException | RuntimeException bad) {
            return null;
        }
        panels.put(deck, b);
        return b;
    }

    /** The floor's room layout (AllmapAddRoom's walk from room 0), or null. */
    synchronized FloorLayout floorLayout(int floor, boolean riku) {
        if (floor < 0 || floor >= FLOOR_DEF_COUNT) return null;
        int key = floor | (riku ? 0x100 : 0);
        FloorLayout f = floors.get(key);
        if (f != null) return f;
        try {
            int def = (riku ? RIKU_FLOOR_DEFS : FLOOR_DEFS) + 12 * floor;
            int links = u32(def + 4), doors = u32(def + 8);
            f = new FloorLayout();
            for (int i = 0; i < 32 * 4; i++) f.links[i] = u8(links + i);
            java.util.Arrays.fill(f.x, -1);
            java.util.Arrays.fill(f.eventSide, -1);
            for (int d = 0; d < 64; d++) {           // MapEventDoor list, 8 bytes each
                int kind = u8(doors + 8 * d);
                if (kind == EVENT_END) break;
                int room = u8(doors + 8 * d + 2);
                if (room < 32) {
                    f.eventKind[room] = kind;
                    f.eventSide[room] = u8(doors + 8 * d + 3);
                }
            }
            place(f, 0, 32, 32);
        } catch (IOException | RuntimeException bad) {
            return null;
        }
        floors.put(key, f);
        return f;
    }

    private static void place(FloorLayout f, int room, int x, int y) {
        if (room >= 32 || f.x[room] >= 0) return;
        f.x[room] = x;
        f.y[room] = y;
        f.minX = Math.min(f.minX, x);
        f.maxX = Math.max(f.maxX, x);
        f.minY = Math.min(f.minY, y);
        f.maxY = Math.max(f.maxY, y);
        for (int side = 0; side < 4; side++) {
            int n = f.links[room * 4 + side];
            if (((n + 3) & 0xFF) > 2) place(f, n, x + ROOM_STEP[side][0], y + ROOM_STEP[side][1]);
        }
    }

    /** GetMapDoorFlags reduced to "is there a usable door" (present and not sealed). */
    private static boolean doorExists(FloorLayout f, int[] roomFlags, int room, int side) {
        int n = f.links[room * 4 + side];
        if (n == ROOM_NONE) return false;
        if (n == ROOM_EXIT_HALL || n == ROOM_ENTRANCE_HALL) return true;
        if (n >= 32) return false;
        int flags = roomFlags[n] & 0xFFFF;
        if ((flags & (FLOOR_ROOM_EVENT_DONE | FLOOR_ROOM_LOCKED)) != 0) return false;
        int kind = f.eventKind[n];
        if (kind == EVENT_ROOM || kind == EVENT_BOSS_ROOM || kind == EVENT_HIDDEN_CHAMBER)
            return f.eventSide[n] == side;
        return true;
    }

    /**
     * The floor map as the allmap screen draws it, in GBA pixels: room shape from its doors
     * (SetupAllmapRoomDoors), palette from its state (AllmapDrawRoomTiles); the current room
     * cycles through room palettes 2-4 with phase. roomFlags are the 32 MapFloorRoom flags.
     */
    synchronized Bitmap floorMap(FloorLayout f, int[] roomFlags, int current, int phase) {
        try {
            int w = f.width(), h = f.height();
            int[] px = new int[w * h];
            for (int parity = 0; parity < 2; parity++) {
                for (int room = 0; room < 32; room++) {
                    if (f.x[room] < 0 || (f.x[room] & 1) != parity) continue;
                    int flags = roomFlags[room] & 0xFFFF;
                    boolean visited = (flags & FLOOR_ROOM_VISITED) != 0, done = (flags & FLOOR_ROOM_EVENT_DONE) != 0;
                    boolean event = f.eventKind[room] == EVENT_ROOM || f.eventKind[room] == EVENT_BOSS_ROOM;
                    int shape;
                    if (event && done) shape = 17;
                    else if (!visited) shape = event ? 1 : 0;
                    else {
                        int mask = 0;
                        for (int side = 0; side < 4; side++) if (doorExists(f, roomFlags, room, side)) mask |= 1 << side;
                        shape = ROOM_SHAPE_ORDER[mask] + 1;
                    }
                    int bank;
                    if (visited || done) {
                        if (room == current) bank = 2 + new int[] {0, 1, 2, 1}[phase & 3];
                        else bank = shape == 1 ? 1 : shape == 17 ? 15 : 0;
                    } else {
                        bank = shape == 1 || shape == 17 ? 15 : 14;
                    }
                    int[] colours = mapPalette(bank);
                    int ox = (f.x[room] - f.minX) * ROOM_STEP_PX, oy = (f.y[room] - f.minY) * ROOM_STEP_PX;
                    for (int t = 0; t < 16; t++) {
                        int tile = ROOM_TILES + 32 * (shape * 16 + t);
                        for (int py = 0; py < 8; py++) {
                            for (int qx = 0; qx < 8; qx++) {
                                int b8 = u8(tile + py * 4 + qx / 2);
                                int v = (qx & 1) != 0 ? b8 >> 4 : b8 & 15;
                                if (v != 0) px[(oy + (t / 4) * 8 + py) * w + ox + (t % 4) * 8 + qx] = colours[v];
                            }
                        }
                    }
                }
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (IOException | RuntimeException bad) {
            return null;
        }
    }

    private int[] mapPalette(int bank) throws IOException {
        int[] p = mapPalettes.get(bank);
        if (p == null) {
            p = palette(bank < 7 ? ROOM_PALETTES + 32 * bank : MAP_BG_PALETTES + 32 * (bank - 8));
            mapPalettes.put(bank, p);
        }
        return p;
    }

    /** Pixel width of s in the game font (characters outside 32-255 count as '?'). */
    synchronized int textWidth(String s) {
        int w = 0;
        try {
            for (int i = 0; i < s.length(); i++) w += u16(GLYPH_WIDTHS + 2 * glyph(s.charAt(i)));
        } catch (IOException e) {
            return 0;
        }
        return w;
    }

    /** Character -> glyph, as LayoutMsgGlyphs maps it (arrows also accept the triangle forms). */
    private static int glyph(char ch) {
        if (ch >= 32 && ch <= 255) return ch;
        switch (ch) {
            case '\u2191': case '\u25B2': return 10;
            case '\u2193': case '\u25BC': return 11;
            case '\u2190': case '\u25C0': return 12;
            case '\u2192': case '\u25B6': return 13;
            case '\u2605': return 21;
            case '\u266A': return 18;
            default: return '?';
        }
    }

    /** s in the game's dialogue font, FONT_HEIGHT tall, or null; colours are ARGB. */
    synchronized Bitmap text(String s, int body, int shadow) {
        String key = s + '\u0000' + body + ',' + shadow;
        Bitmap b = texts.get(key);
        if (b != null) return b;
        try {
            int width = Math.max(1, textWidth(s) + 2);
            int[] px = new int[width * FONT_HEIGHT];
            int x = 0;
            for (int i = 0; i < s.length(); i++) {
                int g = glyph(s.charAt(i));
                if (g != 32) {
                    int tile = u16(u32(FONT_FRAMES + 4 * g) + 6) & 0x3FF;
                    for (int ty = 0; ty < 2; ty++)
                        for (int tx = 0; tx < 2; tx++)
                            for (int py = 0; py < 8; py++)
                                for (int qx = 0; qx < 8; qx++) {
                                    int b8 = u8(FONT_TILES + 32 * (tile + ty * 2 + tx) + py * 4 + qx / 2);
                                    int v = (qx & 1) != 0 ? b8 >> 4 : b8 & 15;
                                    int X = x + tx * 8 + qx, Y = ty * 8 + py;
                                    if (v == 0 || X >= width) continue;
                                    px[Y * width + X] = v == 1 ? body : shadow;
                                }
                }
                x += u16(GLYPH_WIDTHS + 2 * g);
            }
            b = Bitmap.createBitmap(px, width, FONT_HEIGHT, Bitmap.Config.ARGB_8888);
        } catch (IOException | RuntimeException bad) {
            return null;
        }
        texts.put(key, b);
        return b;
    }

    private static Bitmap crop(int[] canvas) {
        int[] out = new int[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++)
            System.arraycopy(canvas, (CROP_Y + y) * CANVAS + CROP_X, out, y * WIDTH, WIDTH);
        return Bitmap.createBitmap(out, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
    }

    /** Draws one sprite frame (OAM piece list) with 4bpp tiles; colour 0 is transparent. */
    private void draw(int[] canvas, int frame, int tiles, int[] colours) throws IOException {
        int pieces = u16(frame);
        if (pieces > 32) throw new IOException("bad frame");
        for (int p = 0; p < pieces; p++) {
            int a0 = u16(frame + 2 + 6 * p), a1 = u16(frame + 4 + 6 * p), a2 = u16(frame + 6 + 6 * p);
            int shape = a0 >>> 14, size = a1 >>> 14;
            if (shape > 2) continue;
            int w = SHAPES[shape][size * 2], h = SHAPES[shape][size * 2 + 1];
            int x = a1 & 0x1FF, y = a0 & 0xFF;
            if ((x & 0x100) != 0) x -= 512;
            if ((y & 0x80) != 0) y -= 256;
            boolean hflip = ((a1 >> 12) & 1) != 0, vflip = ((a1 >> 13) & 1) != 0;
            int firstTile = a2 & 0x3FF, tw = w / 8;
            for (int ty = 0; ty < h / 8; ty++) {
                for (int tx = 0; tx < tw; tx++) {
                    int tile = tiles + 32 * (firstTile + ty * tw + tx);
                    for (int py = 0; py < 8; py++) {
                        for (int px = 0; px < 8; px++) {
                            int b = u8(tile + py * 4 + px / 2);
                            int v = (px & 1) != 0 ? b >> 4 : b & 15;
                            if (v == 0) continue;
                            int sx = tx * 8 + px, sy = ty * 8 + py;
                            if (hflip) sx = w - 1 - sx;
                            if (vflip) sy = h - 1 - sy;
                            int cx = x + sx + CANVAS / 2, cy = y + sy + CANVAS / 2;
                            if (cx >= 0 && cx < CANVAS && cy >= 0 && cy < CANVAS) canvas[cy * CANVAS + cx] = colours[v];
                        }
                    }
                }
            }
        }
    }

    /** 16 BGR555 colours as opaque ARGB. */
    private int[] palette(int addr) throws IOException {
        int[] out = new int[16];
        for (int i = 0; i < 16; i++) {
            int c = u16(addr + 2 * i);
            int r = (c & 31) << 3, g = ((c >> 5) & 31) << 3, b = ((c >> 10) & 31) << 3;
            out[i] = 0xFF000000 | (r | r >> 5) << 16 | (g | g >> 5) << 8 | (b | b >> 5);
        }
        return out;
    }

    private int u8(int addr) throws IOException {
        long off = (addr & 0xFFFFFFFFL) - ROM_BASE;
        if (off < 0 || off >= romSize) throw new IOException("outside ROM: " + Integer.toHexString(addr));
        int page = (int) (off >>> 12);
        byte[] data = pages.get(page);
        if (data == null) {
            data = new byte[4096];
            rom.seek((long) page << 12);
            rom.readFully(data, 0, (int) Math.min(4096, romSize - ((long) page << 12)));
            pages.put(page, data);
        }
        return data[(int) (off & 4095)] & 0xFF;
    }

    private int u16(int addr) throws IOException { return u8(addr) | u8(addr + 1) << 8; }

    private int u32(int addr) throws IOException { return u16(addr) | u16(addr + 2) << 16; }
}
