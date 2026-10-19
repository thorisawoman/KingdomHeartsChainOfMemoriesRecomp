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
 * Card images decoded at runtime from the player's own ROM (USA B8CE), which the
 * setup screen stores in app-private storage. Nothing from the game ships in the APK.
 *
 * A card is built the way the game's battle renderer (card_2) draws it, all at one
 * origin: the picture (gCardDefs[id].gfx / tiles / palette), the category border
 * (gCardBacks[category].gfx, gCard00Palette) over it, and the value digit
 * (gCardValueDigitFrames[value]) over that; enemy cards have no digit. Summon and
 * friend cards use the black border, as the prize-card code does. Addresses come
 * from the pheenoh/khcom asset tables and were checked against the ROM.
 */
final class KhCardArt {
    private static final int ROM_BASE = 0x08000000;
    private static final int CARD_DEFS = 0x08F70AB0;      // CardDef gCardDefs[950], 0x34 bytes each
    private static final int CARD_DEF_SIZE = 0x34;
    private static final int CARD_DEF_COUNT = 950;
    private static final int CARD_BACKS = 0x08F709B0;     // CardBack gCardBacks[5], 6 pointers each
    private static final int CARD_PALETTE = 0x09611AB8;   // gCard00Palette (borders, digits, backs)
    private static final int DIGIT_FRAMES = 0x09EE981C;   // gCardValueDigitFrames[15]
    private static final int DIGIT_TILES = 0x0905EAE8;    // gCardValueDigitTiles tile data
    private static final int FLAG_SUMMON = 0x4, FLAG_FRIEND = 0x8;
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
    private final Map<Integer, byte[]> pages = new LinkedHashMap<Integer, byte[]>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) { return size() > 256; }
    };

    private KhCardArt(RandomAccessFile rom) throws IOException {
        this.rom = rom;
        this.romSize = rom.length();
    }

    /** Opens the installed ROM, or returns null when it is missing or not the 32 MiB USA dump. */
    static KhCardArt open(Context context, String romFile) {
        File f = new File(context.getFilesDir(), "roms/" + romFile);
        if (!f.isFile() || f.length() != 32L * 1024 * 1024) return null;
        try {
            return new KhCardArt(new RandomAccessFile(f, "r"));
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
