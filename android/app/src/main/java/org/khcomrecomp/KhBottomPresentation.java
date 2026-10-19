package org.khcomrecomp;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import org.gbarecomp.GbaGameConfig;
import org.libsdl.app.SDLActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Bottom-screen panel with three views, cycled from the header button:
 *
 * DECK (default) shows the equipped deck read live from guest RAM (see
 * src/android/dual_screen.cpp), drawn with the game's own card art (KhRomArt). During a battle it
 * switches to the battle card list: selected card outlined, used cards dimmed,
 * stocked cards marked, with the cards picked up during the battle along the bottom.
 *
 * MAP shows the current floor the way the game's map screen draws it.
 *
 * PAD shows GBA buttons as touch targets. They inject the keyboard keys that
 * keybinds.ini maps to each button, so input reaches the game through its normal
 * path (SDL -> keybinds -> keypad), plus any guest RAM words listed in
 * bottom_screen_watches.txt (app external files dir, editable without a rebuild).
 */
final class KhBottomPresentation extends Presentation {
    private static final String WATCH_FILE = "bottom_screen_watches.txt";

    private KhRomArt art;

    KhBottomPresentation(Context outer, Display display) {
        super(outer, display);
    }

    @Override
    protected void onStop() {
        if (art != null) art.close();
        art = null;
        super.onStop();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        List<Watch> watches = loadWatches(getContext().getExternalFilesDir(null));
        int[] addrs = new int[watches.size()];
        for (int i = 0; i < addrs.length; i++) addrs[i] = watches.get(i).address;
        KhNative.setWatches(addrs);
        art = KhRomArt.open(getContext(), GbaGameConfig.load(getContext()).romFile);
        setContentView(new PanelView(getContext(), watches, art));
        if (getWindow() != null) {
            // Touches still arrive, but focus stays with the game window on the top screen:
            // a focus change there would make SDL drop held keys and pause.
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    static final class Watch {
        final int address;
        final String label;
        Watch(int address, String label) { this.address = address; this.label = label; }
    }

    /** Lines: `0x0200ABCD  label` (# comments). A commented template is written on first run. */
    static List<Watch> loadWatches(File dir) {
        List<Watch> out = new ArrayList<>();
        if (dir == null) return out;
        File f = new File(dir, WATCH_FILE);
        if (!f.isFile()) {
            try (FileWriter w = new FileWriter(f)) {
                w.write("# Guest RAM words shown on the bottom screen (EWRAM 0x02xxxxxx / IWRAM 0x03xxxxxx).\n"
                      + "# One per line: <hex address> <label>. Up to 64. Relaunch the game to reload.\n"
                      + "# 0x03007488 game mode\n");
            } catch (IOException ignored) {
            }
            return out;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null && out.size() < 64) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+", 2);
                try {
                    String hex = parts[0].toLowerCase().startsWith("0x") ? parts[0].substring(2) : parts[0];
                    int addr = (int) Long.parseLong(hex, 16);
                    out.add(new Watch(addr, parts.length > 1 ? parts[1] : parts[0]));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    /** A touch target; `keys` are Android keycodes pressed together (empty = action). */
    private static final class Button {
        String label;
        final int[] keys;
        final Runnable action;
        final RectF rect = new RectF();
        int holders;
        Button(String label, Runnable action, int... keys) {
            this.label = label;
            this.action = action;
            this.keys = keys;
        }
    }

    private static final class PanelView extends View {
        // keybinds.ini: A=X B=Z Select=Right Shift Start=Return L=C R=V, d-pad = arrows.
        private final Button bL = new Button("L", null, KeyEvent.KEYCODE_C);
        private final Button bR = new Button("R", null, KeyEvent.KEYCODE_V);
        private final Button bA = new Button("A", null, KeyEvent.KEYCODE_X);
        private final Button bB = new Button("B", null, KeyEvent.KEYCODE_Z);
        private final Button bSelect = new Button("SELECT", null, KeyEvent.KEYCODE_SHIFT_RIGHT);
        private final Button bStart = new Button("START", null, KeyEvent.KEYCODE_ENTER);
        private final Button bUp = new Button("▲", null, KeyEvent.KEYCODE_DPAD_UP);
        private final Button bDown = new Button("▼", null, KeyEvent.KEYCODE_DPAD_DOWN);
        private final Button bLeft = new Button("◀", null, KeyEvent.KEYCODE_DPAD_LEFT);
        private final Button bRight = new Button("▶", null, KeyEvent.KEYCODE_DPAD_RIGHT);
        private final Button bMenu = new Button("MENU", KhNative::toggleMenu);
        private final Button bView = new Button("MAP", this::toggleView);
        private final Button[] padButtons = {bL, bR, bA, bB, bSelect, bStart, bUp, bDown, bLeft, bRight, bMenu, bView};
        private final Button[] deckButtons = {bMenu, bView};
        private Button[] buttons = deckButtons;   // deck view first: the Thor has physical buttons
        // DECK (battle cards during a battle), MAP (floor map), PAD (touch controls + RAM watches).
        private static final int VIEW_DECK = 0, VIEW_MAP = 1, VIEW_PAD = 2;
        private static final String[] VIEW_NAMES = {"DECK", "MAP", "PAD"};
        private int view = VIEW_DECK;
        private final int[] map = new int[4 + 32];
        private int mapCount;
        private final KhHandAnimator handMotion = new KhHandAnimator();
        private final RectF cursorGlow = new RectF();
        private boolean animating;
        private final int[] pickups = new int[1 + 16];
        private final int[] status = new int[9];
        private int statusCount;
        private float hpShown = -1, hpTrail = -1;   // eased gauge value and damage trail
        private boolean statusOpen;
        private int pickupCount;

        private final SparseArray<Button> pointerOwner = new SparseArray<>();
        private final List<Watch> watches;
        private final int[] snap = new int[66];
        private int snapCount;
        private final int[] deck = new int[5 + 99 + 20];
        private int deckCount;
        private final int[] hand = new int[7 + 99 + 15];
        private int handCount;
        private static final int RELOAD_ID = 0xFFFE;
        private static final String[] LIST_NAMES = {"Cards", "Magic", "Items", "Enemy"};
        private final RectF tile = new RectF();

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pixel = new Paint();
        private final KhRomArt art;   // null when the ROM is not readable: flat tiles instead
        private final RectF card = new RectF();
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mono = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Runnable poll = new Runnable() {
            @Override
            public void run() {
                try {
                    snapCount = KhNative.snapshot(snap);
                    deckCount = KhNative.deckSnapshot(deck);
                    handCount = KhNative.handSnapshot(hand);
                    mapCount = KhNative.mapSnapshot(map);
                    pickupCount = KhNative.pickupSnapshot(pickups);
                    statusCount = KhNative.statusSnapshot(status);
                } catch (UnsatisfiedLinkError notYet) {
                    snapCount = 0;
                    deckCount = 0;
                    handCount = 0;
                    mapCount = 0;
                    pickupCount = 0;
                }
                stepSelection();
                invalidate();
                postDelayed(this, handLive() || tapTarget >= 0 ? 33 : 100);   // follow battles closely
            }
        };

        PanelView(Context c, List<Watch> watches, KhRomArt art) {
            super(c);
            this.watches = watches;
            this.art = art;
            pixel.setFilterBitmap(false);   // keep the game's pixel art crisp when scaled
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            mono.setColor(0xFFCFD3E6);
            mono.setTypeface(android.graphics.Typeface.MONOSPACE);
            setBackgroundColor(art != null ? KhRomArt.BACKDROP : 0xFF0E1124);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            post(poll);
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(poll);
            releaseAll();
            super.onDetachedFromWindow();
        }

        private void toggleView() {
            releaseAll();
            view = (view + 1) % VIEW_NAMES.length;
            buttons = view == VIEW_PAD ? padButtons : deckButtons;
            bView.label = VIEW_NAMES[(view + 1) % VIEW_NAMES.length];
            invalidate();
        }

        private float unit() { return Math.min(getWidth(), getHeight()) / 12f; }

        /** Integer scale for the game's pixel art (GBA pixels -> screen pixels). */
        private int gbaScale() { return Math.max(2, getWidth() / 300); }

        /** Height of the header shared by both views: the deck panel when art is available. */
        /** Header (deck panel + MENU / view buttons) only. */
        private float headerHeight() {
            if (art == null) return unit() * 1.4f;
            return KhRomArt.PANEL_HEIGHT * gbaScale() + unit() * 0.5f;
        }

        /** Height of Sora's status strip under the header. */
        private float statusHeight() { return unit() * 1.0f; }

        /** Everything above the view content: header plus status strip. */
        private float barHeight() { return headerHeight() + statusHeight(); }

        // Game-font colours: dialogue white with its dark shadow (gDialogBoxPalette 1 / 2).
        private static final int TEXT_BODY = 0xFFF8F8F8, TEXT_SHADOW = 0xFF404040;

        /**
         * Draws s in the game font at integer scale, top-left at (x, y) for align -1, centred
         * on x for 0, right-aligned at x for 1, shortened with ".." to fit maxWidth. Falls back
         * to the system font without art. Returns the drawn width.
         */
        private float gameText(Canvas canvas, String s, float x, float y, int scale, int align,
                               float maxWidth, int body, int shadow, int alpha) {
            if (art == null) {
                text.setTextSize(scale * 11f);
                text.setTextAlign(align < 0 ? Paint.Align.LEFT : align == 0 ? Paint.Align.CENTER : Paint.Align.RIGHT);
                text.setAlpha(alpha);
                canvas.drawText(s, x, y + scale * 10f, text);
                text.setAlpha(255);
                text.setTextAlign(Paint.Align.CENTER);
                return text.measureText(s);
            }
            if (art.textWidth(s) * scale > maxWidth) {
                while (s.length() > 1 && art.textWidth(s + "..") * scale > maxWidth) s = s.substring(0, s.length() - 1);
                s = s.trim() + "..";
            }
            Bitmap b = art.text(s, body, shadow);
            if (b == null) return 0;
            float bw = b.getWidth() * scale;
            float left = align < 0 ? x : align == 0 ? x - bw / 2 : x - bw;
            pixel.setAlpha(alpha);
            canvas.drawBitmap(b, null, new RectF(left, y, left + bw, y + b.getHeight() * scale), pixel);
            pixel.setAlpha(255);
            return bw;
        }

        private float gameText(Canvas canvas, String s, float x, float y, int scale, int align, float maxWidth) {
            return gameText(canvas, s, x, y, scale, align, maxWidth, TEXT_BODY, TEXT_SHADOW, 255);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float u = unit();
            float pad = u * 0.25f, bar = headerHeight();
            float content = barHeight();
            if (art != null) {
                // Header: deck panel on the left, MENU over the view toggle on the right.
                float left = Math.max(pad + KhRomArt.PANEL_WIDTH * gbaScale() + pad, w - u * 3.4f);
                float mid = bar / 2;
                bMenu.rect.set(left, pad, w - pad, mid - pad / 2);
                bView.rect.set(left, mid + pad / 2, w - pad, bar - pad);
            } else {
                // Status bar: text on the left, VIEW toggle and MENU on the right.
                bView.rect.set(w - u * 2.6f, pad, w - pad, bar - pad);
                bMenu.rect.set(w - u * 5.2f, pad, w - u * 2.85f, bar - pad);
            }
            // Pad view: shoulders under the bar, d-pad bottom left, A/B bottom right.
            bL.rect.set(pad, content + pad, w * 0.3f, content + pad + u * 1.5f);
            bR.rect.set(w * 0.7f, content + pad, w - pad, content + pad + u * 1.5f);
            float cy = h - u * 3.4f, cx = u * 3.6f, s = u * 1.45f;
            bUp.rect.set(cx - s / 2, cy - s * 1.5f, cx + s / 2, cy - s / 2);
            bDown.rect.set(cx - s / 2, cy + s / 2, cx + s / 2, cy + s * 1.5f);
            bLeft.rect.set(cx - s * 1.5f, cy - s / 2, cx - s / 2, cy + s / 2);
            bRight.rect.set(cx + s / 2, cy - s / 2, cx + s * 1.5f, cy + s / 2);
            float ax = w - u * 2.2f, bx = w - u * 4.6f, r = u * 1.0f;
            bA.rect.set(ax - r, cy - r * 1.6f, ax + r, cy + r * 0.4f);
            bB.rect.set(bx - r, cy - r * 0.4f, bx + r, cy + r * 1.6f);
            bSelect.rect.set(w * 0.36f, h - u * 1.4f, w * 0.49f, h - pad * 2);
            bStart.rect.set(w * 0.51f, h - u * 1.4f, w * 0.64f, h - pad * 2);
            mono.setTextSize(u * 0.42f);
        }

        private String statusLine() {
            String mode = "Booting";
            if (snapCount >= 2) mode = snap[1] == 1 ? "Battle" : snap[1] == 0 ? "Field" : "Booting";
            if (handLive()) {
                String list = hand[2] >= 0 && hand[2] < LIST_NAMES.length ? LIST_NAMES[hand[2]] : "?";
                return String.format("Battle  \u00B7  %s  \u00B7  %s  \u00B7  %d left  \u00B7  Stock %d",
                    hand[1] == 1 ? "Riku" : "Sora", list, hand[4], hand[5]);
            }
            if (deckCount >= 5 && deck[0] >= 0 && deck[0] <= 2) {
                return String.format("%s  ·  Deck %d  ·  %d cards  ·  %d CP",
                    mode, deck[0] + 1, deck[4], deck[1]);
            }
            return mode;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), u = unit(), bar = barHeight();
            drawStatusStrip(canvas, w, u);
            if (!drawHeaderPanel(canvas, u)) {
                text.setTextAlign(Paint.Align.LEFT);
                text.setTextSize(u * 0.55f);
                // Ellipsize so the status never runs under the MENU / view buttons.
                String status = statusLine();
                float room = bMenu.rect.left - u * 0.7f;
                int fit = text.breakText(status, true, room, null);
                if (fit < status.length()) status = status.substring(0, Math.max(0, fit - 1)) + "\u2026";
                canvas.drawText(status, u * 0.4f, bar / 2 - (text.descent() + text.ascent()) / 2, text);
                text.setTextAlign(Paint.Align.CENTER);
            }
            animating = false;
            if (!handLive()) {
                handMotion.reset();
                cursorGlow.setEmpty();
            }
            if (handLive() && view != VIEW_PAD) drawHand(canvas, w, h, u, bar);   // battles always show cards
            else if (view == VIEW_DECK) {
                drawDeck(canvas, w, h, u, bar);
                drawCloseup(canvas, w, h, u, bar);
            }
            else if (view == VIEW_MAP) drawMap(canvas, w, h, u, bar);
            else drawWatches(canvas, w, u, bar);
            text.setTextSize(u * 0.55f);
            drawStatusCard(canvas, w, h, u);
            for (Button b : buttons) drawButton(canvas, b);
            if (animating) postInvalidateOnAnimation();
        }

        /** A button in the deck panel's colours (fill 0xd, outline 0xa, pressed 0xb). */
        private void drawButton(Canvas canvas, Button b) {
            if (art == null) {
                fill.setColor(b.holders > 0 ? 0xFF5C6BC0 : 0xFF262B4A);
                float rr = b.rect.height() * 0.3f;
                canvas.drawRoundRect(b.rect, rr, rr, fill);
                float ty = b.rect.centerY() - (text.descent() + text.ascent()) / 2;
                canvas.drawText(b.label, b.rect.centerX(), ty, text);
                return;
            }
            int scale = gbaScale();
            float edge = scale;
            float rr = scale * 3f;
            fill.setColor(art.panelColour(0xA));
            canvas.drawRoundRect(b.rect, rr, rr, fill);
            fill.setColor(art.panelColour(b.holders > 0 ? 0xB : 0xD));
            canvas.drawRoundRect(b.rect.left + edge, b.rect.top + edge, b.rect.right - edge, b.rect.bottom - edge,
                rr, rr, fill);
            int ts = Math.max(2, Math.min(scale, (int) (b.rect.height() / 14)));
            gameText(canvas, b.label, b.rect.centerX(), b.rect.centerY() - ts * 6.5f, ts, 0, b.rect.width() - 2 * edge);
        }

        /** Deck name from the snapshot (10 UTF-16LE characters in Deck.name), or null. */
        private String deckName() {
            if (deckCount < 5) return null;
            int base = 5 + Math.min(deck[4], deckCount - 5);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i + 1 < 20 && base + i + 1 < deckCount; i += 2) {
                int c = (deck[base + i] & 0xFF) | (deck[base + i + 1] & 0xFF) << 8;
                if (c == 0) break;
                if (c < 32 || c > 255) return null;
                sb.append((char) c);
            }
            String name = sb.toString().trim();
            return name.isEmpty() ? null : name;
        }

        /**
         * Header from the deck menu's own panel: DECK n tab, name bar (deck name, or the battle
         * state), and the live CARDS / CP values over the panel's placeholder fields.
         */
        private boolean drawHeaderPanel(Canvas canvas, float u) {
            if (art == null) return false;
            int active = deckCount >= 5 && deck[0] >= 0 && deck[0] <= 2 ? deck[0] : 0;
            Bitmap panel = art.deckPanel(active);
            if (panel == null) return false;
            int s = gbaScale();
            float ox = u * 0.25f, oy = u * 0.25f;
            canvas.drawBitmap(panel, null, new RectF(ox, oy, ox + panel.getWidth() * s, oy + panel.getHeight() * s), pixel);
            int[] nb = KhRomArt.PANEL_NAME;
            String line;
            if (handLive()) {
                String list = hand[2] > 0 && hand[2] < LIST_NAMES.length ? "  \u00B7  " + LIST_NAMES[hand[2]] : "";
                line = String.format("%s%d left  \u00B7  Stock %d%s", hand[1] == 1 ? "Riku  \u00B7  " : "",
                    hand[4], hand[5], list);
            } else {
                String name = deckName();
                if (snapCount < 2 || snap[1] < 0) line = name != null ? name : "Waiting for the game";
                else line = name != null ? name : snap[1] == 1 ? "Battle" : "Field";
            }
            gameText(canvas, line, ox + (nb[0] + 3) * s, oy + (nb[1] - 1) * s, s, -1, (nb[2] - nb[0] - 4) * s);
            if (deckCount >= 5) {
                int digitBody = art.panelColour(0xF), digitShadow = art.panelColour(0xE);
                int[] cr = KhRomArt.PANEL_CARDS, cp = KhRomArt.PANEL_CP;
                gameText(canvas, Integer.toString(deck[4]), ox + (cr[0] + 2) * s, oy + (cr[1] - 5) * s, s, -1,
                    (cr[2] - cr[0]) * s, digitBody, digitShadow, 255);
                gameText(canvas, Integer.toString(deck[1]), ox + (cp[0] + 2) * s, oy + (cp[1] - 5) * s, s, -1,
                    (cp[2] - cp[0]) * s, digitBody, digitShadow, 255);
            }
            return true;
        }

        private static int categoryColor(KhCards.Category c) {
            switch (c) {
                case ATTACK: return 0xFF8A2E2E;
                case MAGIC: return 0xFF2E4E8A;
                case ITEM: return 0xFF2E7A45;
                case ENEMY: return 0xFF5A3378;
                default: return 0xFF4A4A55;
            }
        }

        /** True while the game is in battle and a card list is readable. */
        private boolean handLive() { return handCount >= 7 && hand[0] == 1; }

        /** Fallback card without art: category colour, value, name; gold edge for premium. */
        private void drawTile(Canvas canvas, int id, boolean premium, boolean selected, boolean dim,
                              String badge, float u) {
            float rr = tile.height() * 0.18f;
            boolean reload = id == RELOAD_ID;
            fill.setColor(reload ? 0xFF2E6E73 : categoryColor(KhCards.category(id)));
            if (dim) fill.setAlpha(80);
            canvas.drawRoundRect(tile, rr, rr, fill);
            fill.setAlpha(255);
            if (premium || selected) {
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(Math.max(2f, tile.height() * (selected ? 0.12f : 0.06f)));
                fill.setColor(selected ? Color.WHITE : 0xFFE8C45A);
                canvas.drawRoundRect(tile, rr, rr, fill);
                fill.setStyle(Paint.Style.FILL);
            }
            float ts = Math.min(tile.height() * 0.5f, u * 0.5f);
            text.setTextSize(ts);
            text.setAlpha(dim ? 110 : 255);
            float ty = tile.centerY() - (text.descent() + text.ascent()) / 2;
            int value = reload ? -1 : KhCards.value(id);
            float nameX = tile.left + ts * 0.5f;
            text.setTextAlign(Paint.Align.LEFT);
            if (value >= 0) {
                text.setFakeBoldText(true);
                canvas.drawText(Integer.toString(value), tile.left + ts * 0.45f, ty, text);
                text.setFakeBoldText(false);
                nameX = tile.left + ts * 1.5f;
            }
            String name = reload ? "Reload" : KhCards.name(id);
            float room = tile.right - nameX - ts * (badge != null ? 1.4f : 0.3f);
            int fit = text.breakText(name, true, room, null);
            canvas.drawText(fit < name.length() ? name.substring(0, Math.max(0, fit - 1)) + "\u2026" : name,
                nameX, ty, text);
            if (badge != null) {
                text.setTextAlign(Paint.Align.RIGHT);
                text.setColor(0xFFE8C45A);
                canvas.drawText(badge, tile.right - ts * 0.35f, ty, text);
                text.setColor(Color.WHITE);
            }
            text.setAlpha(255);
            text.setTextAlign(Paint.Align.CENTER);
        }

        // Card grid geometry, filled by layoutCards().
        private int gridCols;
        private float cellW, cellH, cardH, labelH;

        /**
         * Picks the column count that makes n cards largest (card shape 28:39), with names
         * under the cards when that still leaves them a useful size.
         */
        private void layoutCards(int n, float w, float top, float bottom, float u) {
            float gap = u * 0.15f;
            for (int pass = 0; pass < 2; pass++) {
                labelH = pass == 0 ? u * 0.42f : 0f;
                cardH = 0;
                for (int c = 1; c <= Math.max(1, n); c++) {
                    int rows = (n + c - 1) / c;
                    float cw = (w - gap) / c, ch = (bottom - top) / rows;
                    float h = Math.min(ch - labelH - gap, (cw - gap) * KhRomArt.HEIGHT / KhRomArt.WIDTH);
                    if (h > cardH) {
                        cardH = h;
                        gridCols = c;
                        cellW = cw;
                        cellH = ch;
                    }
                }
                if (cardH >= u * 1.6f) return;
            }
        }

        /** One card in grid cell i: the decoded card image, or a flat tile if art is unavailable. */
        /** Card rectangle of grid cell i (after layoutCards). */
        private void cellRect(int i, float top, float u, RectF out) {
            float gap = u * 0.15f;
            float cx = gap / 2 + (i % gridCols) * cellW + cellW / 2;
            float y = top + (i / gridCols) * cellH + gap / 2;
            float cw = cardH * KhRomArt.WIDTH / KhRomArt.HEIGHT;
            out.set(cx - cw / 2, y, cx + cw / 2, y + cardH);
        }

        /** One card in grid cell i: the decoded card image, or a flat tile if art is unavailable. */
        private void drawCard(Canvas canvas, int i, float top, float u, int id, boolean premium,
                              boolean selected, boolean dim, String badge) {
            cellRect(i, top, u, card);
            if (selected) drawGlow(canvas, card, 0x55FFFFFF);
            drawCardRect(canvas, card, u, id, premium, dim ? 70 : 255, badge, labelH > 0);
        }

        private void drawGlow(Canvas canvas, RectF r, int colour) {
            fill.setColor(colour);
            float pad = r.height() * 0.06f;
            canvas.drawRoundRect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad, pad * 2, pad * 2, fill);
        }

        /** The card image (or fallback tile) in r with alpha 0-255, optional badge and name label. */
        private void drawCardRect(Canvas canvas, RectF r, float u, int id, boolean premium, int alpha,
                                  String badge, boolean label) {
            boolean reload = id == RELOAD_ID;
            Bitmap bmp = art == null ? null : reload ? art.back(KhRomArt.BACK_RED) : art.card(id);
            float h = r.height();
            if (bmp != null) {
                pixel.setAlpha(alpha);
                canvas.drawBitmap(bmp, null, r, pixel);
                pixel.setAlpha(255);
            } else {
                tile.set(r);
                drawTile(canvas, id, premium, false, alpha < 128, null, u);
            }
            float edge = Math.max(2f, h * 0.03f);
            if (premium && bmp != null) {
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(edge);
                fill.setColor(0xFFE8C45A);
                fill.setAlpha(alpha);
                canvas.drawRoundRect(r, edge * 2, edge * 2, fill);
                fill.setStyle(Paint.Style.FILL);
                fill.setAlpha(255);
            }
            if (badge != null) {
                float rad = h * 0.13f;
                fill.setColor(0xFFE8C45A);
                fill.setAlpha(alpha);
                canvas.drawCircle(r.right - rad * 0.6f, r.top + rad * 0.6f, rad, fill);
                fill.setAlpha(255);
                text.setTextSize(rad * 1.3f);
                text.setColor(0xFF1B1F3B);
                text.setAlpha(alpha);
                text.setFakeBoldText(true);
                canvas.drawText(badge, r.right - rad * 0.6f, r.top + rad * 0.6f - (text.descent() + text.ascent()) / 2, text);
                text.setFakeBoldText(false);
                text.setColor(Color.WHITE);
            }
            if (label) {
                String name = reload ? "Reload" : KhCards.name(id);
                int ls = Math.max(1, (int) (labelH / 13));
                gameText(canvas, name, r.centerX(), r.bottom + (labelH - ls * 12) / 2, ls, 0,
                    Math.max(r.width() * 1.6f, cellW - u * 0.15f), TEXT_BODY, TEXT_SHADOW, alpha);
            }
        }

        /**
         * Recently picked-up cards (battle drops and rewards collected in the field), newest
         * last, along the bottom edge. Returns the height used.
         */
        private float drawPickups(Canvas canvas, float w, float h, float u) {
            int n = pickupCount >= 1 ? Math.min(pickups[0], pickupCount - 1) : 0;
            if (n == 0) return 0;
            int s = gbaScale();
            float cardHgt = u * 1.5f, cardW = cardHgt * KhRomArt.WIDTH / KhRomArt.HEIGHT;
            float stripH = cardHgt + u * 0.5f, top = h - stripH;
            fill.setColor(art != null ? art.panelColour(0xA) : 0xFF262B4A);
            canvas.drawRect(0, top, w, top + Math.max(2f, s), fill);
            float labelW = gameText(canvas, "New cards", u * 0.3f, top + (stripH - 12 * s) / 2, s, -1, w / 3);
            float x = u * 0.6f + labelW, gap = u * 0.12f;
            int fit = Math.max(1, (int) ((w - x) / (cardW + gap)));
            for (int i = Math.max(0, n - fit); i < n; i++) {   // newest stay visible
                int entry = pickups[1 + i];
                float y = top + (stripH - cardHgt) / 2 + u * 0.05f;
                card.set(x, y, x + cardW, y + cardHgt);
                Bitmap bmp = art == null ? null : art.card(entry & KhCards.ID_MASK);
                if (bmp != null) canvas.drawBitmap(bmp, null, card, pixel);
                else {
                    tile.set(card);
                    drawTile(canvas, entry & KhCards.ID_MASK, (entry & KhCards.PREMIUM) != 0, false, false, null, u);
                }
                x += cardW + gap;
            }
            return stripH;
        }

        /** The equipped deck in deck order. */
        private void drawDeck(Canvas canvas, float w, float h, float u, float bar) {
            deckShown = 0;
            int n = deckCount >= 5 ? Math.min(deck[4], deckCount - 5) : 0;
            if (n == 0) {
                gameText(canvas, deckCount >= 5 ? "No deck equipped yet" : "Waiting for the game...",
                    w / 2, (bar + h) / 2 - gbaScale() * 6, gbaScale(), 0, w);
                return;
            }
            float top = bar + u * 0.1f;
            float strip = drawPickups(canvas, w, h, u);   // recent pickups
            layoutCards(n, w, top, h - strip, u);
            deckTop = top;
            deckShown = n;
            for (int i = 0; i < n; i++) {
                int entry = deck[5 + i];
                drawCard(canvas, i, top, u, entry & KhCards.ID_MASK, (entry & KhCards.PREMIUM) != 0,
                    false, false, null);
            }
        }

        /**
         * The battle card list in game order: the selected card highlighted, used cards
         * dimmed, stocked cards badged S. The list re-reads several times a second.
         */
        private void drawHand(Canvas canvas, float w, float h, float u, float bar) {
            int n = Math.min(hand[6], handCount - 7);
            if (n <= 0) {
                gameText(canvas, "No cards in this list", w / 2, (bar + h) / 2 - gbaScale() * 6, gbaScale(), 0, w);
                return;
            }
            long now = android.os.SystemClock.uptimeMillis();
            float top = bar + u * 0.1f;
            float strip = drawPickups(canvas, w, h, u);
            handMotion.sync(hand, 7, n, RELOAD_ID, now);
            java.util.List<KhHandAnimator.Sprite> cells = handMotion.laidOut();
            layoutCards(Math.max(1, cells.size()), w, top, h - strip, u);
            for (int k = 0; k < cells.size(); k++) cellRect(k, top, u, cells.get(k).target);
            handMotion.step(now);

            int cursor = hand[3];
            KhHandAnimator.Sprite selected = handMotion.get(cursor);
            if (selected != null && selected.visible) {
                // The highlight glides after the cursor instead of jumping.
                if (cursorGlow.isEmpty()) cursorGlow.set(selected.rect);
                float k = 0.3f;
                cursorGlow.left += (selected.rect.left - cursorGlow.left) * k;
                cursorGlow.top += (selected.rect.top - cursorGlow.top) * k;
                cursorGlow.right += (selected.rect.right - cursorGlow.right) * k;
                cursorGlow.bottom += (selected.rect.bottom - cursorGlow.bottom) * k;
                float lift = cardH * 0.06f + (float) Math.sin(now / 260.0) * cardH * 0.02f;
                card.set(cursorGlow);
                card.offset(0, -lift);
                drawGlow(canvas, card, 0x66FFFFFF);
            }

            for (KhHandAnimator.Sprite sp : handMotion.drawn(now)) {
                card.set(sp.rect);
                float ch = card.height();
                int alpha = 255;
                float scaleX = 1, scale = 1, dy = 0;
                if (sp.phase == KhHandAnimator.PHASE_ENTER) {
                    float t = sp.progress(now);
                    if (now < sp.phaseStart) continue;            // still waiting for its turn in the deal
                    float e = KhHandAnimator.easeOut(t);
                    alpha = (int) (255 * Math.min(1, t * 1.6f));
                    if (sp.fromAbove) {                           // friend card: flips in from above
                        dy = -(1 - e) * ch * 1.4f;
                        scaleX = Math.max(0.05f, Math.abs((float) Math.cos((1 - e) * Math.PI)));
                    } else {                                       // dealt in from below
                        dy = (1 - e) * ch * 0.9f;
                        scale = 0.7f + 0.3f * e;
                    }
                } else if (sp.phase == KhHandAnimator.PHASE_EXIT) {
                    float t = sp.progress(now);                    // used: flies up and fades
                    float e = KhHandAnimator.easeOut(t);
                    alpha = (int) (255 * (1 - t));
                    dy = -e * ch * 0.7f;
                    scale = 1 + 0.18f * e;
                }
                if (sp.slot == cursor && sp.visible) dy -= cardH * 0.06f + (float) Math.sin(now / 260.0) * cardH * 0.02f;
                if (sp.stocked) dy -= ch * 0.14f;                  // stocked cards ride high
                float cx = card.centerX(), cy = card.centerY() + dy;
                float hw = card.width() / 2 * scale * scaleX, hh = ch / 2 * scale;
                card.set(cx - hw, cy - hh, cx + hw, cy + hh);
                if (sp.stocked) drawGlow(canvas, card, 0x66E8C45A);
                if (sp.friend && sp.phase == KhHandAnimator.PHASE_ENTER) drawGlow(canvas, card, 0x8848D0FF);
                int id = sp.id == RELOAD_ID ? sp.id : sp.id & KhCards.ID_MASK;
                drawCardRect(canvas, card, u, id, sp.premium, alpha, sp.stocked ? "S" : null,
                    labelH > 0 && sp.phase != KhHandAnimator.PHASE_EXIT);
            }
            animating = true;   // battle view redraws every frame (cursor bob, motion)
        }

        // ---- Sora's status ---------------------------------------------------------------------

        private int hpColour(float frac) {
            return frac > 0.5f ? 0xFF58D068 : frac > 0.25f ? 0xFFE8C45A : 0xFFE05050;
        }

        /** HP gauge in r: eased fill, a fading trail after damage, pulsing when low. */
        private void drawHpGauge(Canvas canvas, RectF r, int hp, int maxHp, long now) {
            float frac = maxHp > 0 ? Math.max(0, Math.min(1, hp / (float) maxHp)) : 0;
            if (hpShown < 0) hpShown = hpTrail = frac;
            hpShown += (frac - hpShown) * 0.25f;            // fill catches up quickly
            if (frac < hpTrail) hpTrail += (frac - hpTrail) * 0.04f; else hpTrail = frac;   // trail lingers
            if (Math.abs(hpShown - frac) > 0.002f || Math.abs(hpTrail - frac) > 0.002f) animating = true;
            float rr = r.height() / 2;
            fill.setColor(art != null ? art.panelColour(0xA) : 0xFF101425);
            canvas.drawRoundRect(r, rr, rr, fill);
            float inset = Math.max(2f, r.height() * 0.14f);
            RectF in = new RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset);
            float wv = in.width();
            fill.setColor(0xFFF0F0F0);
            canvas.drawRoundRect(in.left, in.top, in.left + wv * hpTrail, in.bottom, rr, rr, fill);
            int col = hpColour(frac);
            if (frac <= 0.25f) {                              // low HP: pulse
                float k = 0.5f + 0.5f * (float) Math.sin(now / 160.0);
                col = blend(col, 0xFFFFFFFF, k * 0.45f);
                animating = true;
            }
            fill.setColor(col);
            canvas.drawRoundRect(in.left, in.top, in.left + wv * hpShown, in.bottom, rr, rr, fill);
        }

        private static int blend(int a, int b, float t) {
            int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return 0xFF000000 | (int) (ar + (br - ar) * t) << 16 | (int) (ag + (bg - ag) * t) << 8 | (int) (ab + (bb - ab) * t);
        }

        /** Strip under the header: Lv, HP gauge with numbers, CP and EXP. Tap for details. */
        private void drawStatusStrip(Canvas canvas, float w, float u) {
            if (statusCount < 9 || status[2] <= 0) return;
            long now = android.os.SystemClock.uptimeMillis();
            float top = headerHeight(), hgt = statusHeight();
            int s = Math.max(2, gbaScale() - 1);
            float ty = top + (hgt - 12 * s) / 2;
            float x = u * 0.3f;
            x += gameText(canvas, "Lv " + status[3], x, ty, s, -1, w) + u * 0.5f;
            x += gameText(canvas, "HP", x, ty, s, -1, w) + u * 0.2f;
            float gaugeW = w * 0.32f;
            RectF g = new RectF(x, top + hgt * 0.28f, x + gaugeW, top + hgt * 0.72f);
            drawHpGauge(canvas, g, status[1], status[2], now);
            x += gaugeW + u * 0.25f;
            x += gameText(canvas, status[1] + "/" + status[2], x, ty, s, -1, w) + u * 0.6f;
            x += gameText(canvas, "CP " + status[4], x, ty, s, -1, w) + u * 0.6f;
            gameText(canvas, "EXP " + status[5] + "/" + status[6], x, ty, s, -1, w - x - u * 0.3f);
        }

        /** Tapped-open status card: level, HP, CP, EXP progress, moogle points, sleights. */
        private void drawStatusCard(Canvas canvas, float w, float h, float u) {
            if (!statusOpen) return;
            if (statusCount < 9) {
                statusOpen = false;
                return;
            }
            long now = android.os.SystemClock.uptimeMillis();
            fill.setColor(0xC0000000);
            canvas.drawRect(0, barHeight(), w, h, fill);
            float pw = w * 0.78f, ph = (h - barHeight()) * 0.82f;
            RectF p = new RectF((w - pw) / 2, barHeight() + (h - barHeight() - ph) / 2, (w + pw) / 2, 0);
            p.bottom = p.top + ph;
            int edge = gbaScale();
            fill.setColor(art != null ? art.panelColour(0xA) : 0xFF101425);
            canvas.drawRoundRect(p, edge * 4, edge * 4, fill);
            fill.setColor(art != null ? art.panelColour(0xD) : 0xFF262B4A);
            canvas.drawRoundRect(p.left + edge, p.top + edge, p.right - edge, p.bottom - edge, edge * 4, edge * 4, fill);
            int s = gbaScale();
            float x = p.left + u * 0.6f, y = p.top + u * 0.5f, line = 15 * s;
            gameText(canvas, "Sora  \u00B7  Level " + status[3] + (status[0] == 1 ? "  \u00B7  In battle" : ""), x, y, s, -1, pw);
            y += line * 1.3f;
            RectF g = new RectF(x, y, p.right - u * 0.6f, y + line * 0.8f);
            drawHpGauge(canvas, g, status[1], status[2], now);
            y += line;
            gameText(canvas, "HP  " + status[1] + " / " + status[2], x, y, s, -1, pw);
            y += line * 1.3f;
            gameText(canvas, "CP  " + status[4], x, y, s, -1, pw);
            y += line * 1.3f;
            int exp = status[5], next = Math.max(1, status[6]);
            gameText(canvas, "EXP  " + exp + "  \u00B7  next level at " + next, x, y, s, -1, pw);
            y += line;
            RectF e = new RectF(x, y, p.right - u * 0.6f, y + line * 0.5f);
            fill.setColor(art != null ? art.panelColour(0xA) : 0xFF101425);
            canvas.drawRoundRect(e, e.height() / 2, e.height() / 2, fill);
            fill.setColor(0xFF5C9CF0);
            canvas.drawRoundRect(e.left, e.top, e.left + e.width() * Math.min(1f, exp / (float) next), e.bottom,
                e.height() / 2, e.height() / 2, fill);
            y += line * 1.1f;
            gameText(canvas, "Moogle points  " + status[7], x, y, s, -1, pw);
            y += line * 1.3f;
            gameText(canvas, "Sleights learned  " + status[8], x, y, s, -1, pw);
        }

        private static final String[] WORLD_NAMES = {
            "Castle Oblivion", "Agrabah", "Atlantica", "Olympus Coliseum", "Wonderland", "Monstro",
            "Halloween Town", "Neverland", "Hollow Bastion", "Destiny Islands", "Traverse Town",
            "Twilight Town", "Castle Oblivion", "100 Acre Wood",
        };
        private final int[] roomFlags = new int[32];
        private Bitmap mapBitmap;
        private String mapKey;

        /**
         * The current floor drawn like the game's map screen: visited rooms with their doors,
         * unvisited rooms dimmed, event rooms marked, the current room pulsing. Title shows the
         * world and floor.
         */
        private void drawMap(Canvas canvas, float w, float h, float u, float bar) {
            int s = gbaScale();
            boolean valid = mapCount >= 4 + 32 && map[1] >= 0;
            KhRomArt.FloorLayout floor = valid && art != null ? art.floorLayout(map[1], map[2] != 0) : null;
            if (floor == null || floor.x[0] < 0) {
                gameText(canvas, art == null ? "Map needs the ROM" : "No floor map yet", w / 2,
                    (bar + h) / 2 - s * 6, s, 0, w);
                return;
            }
            String world = map[0] >= 0 && map[0] < WORLD_NAMES.length ? WORLD_NAMES[map[0]] : WORLD_NAMES[0];
            String title = String.format("%s  \u00B7  Floor %d%s", world, map[1] + 1, map[2] != 0 ? "  \u00B7  Riku" : "");
            float top = bar + u * 0.15f;
            gameText(canvas, title, w / 2, top, s, 0, w - u);
            top += 14 * s + u * 0.2f;
            for (int i = 0; i < 32; i++) roomFlags[i] = map[4 + i] & 0xFFFF;
            int phase = (int) ((android.os.SystemClock.uptimeMillis() / 200) % 4);
            String key = map[1] + "/" + map[2] + "/" + map[3] + "/" + phase + "/" + java.util.Arrays.hashCode(roomFlags);
            if (!key.equals(mapKey)) {
                mapBitmap = art.floorMap(floor, roomFlags, map[3], phase);
                mapKey = key;
            }
            if (mapBitmap == null) return;
            float availW = w - u * 0.5f, availH = h - top - u * 0.25f;
            int scale = (int) Math.max(1, Math.min(availW / mapBitmap.getWidth(), availH / mapBitmap.getHeight()));
            float mw = mapBitmap.getWidth() * scale, mh = mapBitmap.getHeight() * scale;
            float left = (w - mw) / 2, mtop = top + (availH - mh) / 2;
            canvas.drawBitmap(mapBitmap, null, new RectF(left, mtop, left + mw, mtop + mh), pixel);
        }

        /** Pad view middle: guest RAM words from bottom_screen_watches.txt. */
        private void drawWatches(Canvas canvas, float w, float u, float bar) {
            float y = bar + u * 2.6f;
            for (int i = 0; i < watches.size() && 2 + i < snapCount && y < getHeight() - u * 5.5f; i++) {
                String line = String.format("%-18s 0x%08X  %d", watches.get(i).label, snap[2 + i], snap[2 + i]);
                canvas.drawText(line, u * 0.8f, y, mono);
                y += u * 0.55f;
            }
        }

        private Button hit(float x, float y) {
            for (Button b : buttons) {
                RectF r = b.rect;
                float slop = r.height() * 0.15f;   // forgiving edges for thumbs
                if (x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop)
                    return b;
            }
            return null;
        }

        private void press(Button b) {
            if (b == null) return;
            if (b.holders++ == 0) {
                if (b.action != null) b.action.run();
                for (int k : b.keys) SDLActivity.onNativeKeyDown(k);
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            }
        }

        private void release(Button b) {
            if (b == null || b.holders == 0) return;
            if (--b.holders == 0) for (int k : b.keys) SDLActivity.onNativeKeyUp(k);
        }

        private void releaseAll() {
            for (int i = 0; i < pointerOwner.size(); i++) release(pointerOwner.valueAt(i));
            pointerOwner.clear();
        }

        // ---- Card taps ---------------------------------------------------------------------
        // Battle: tapping a card selects it; the panel presses R / L (keybinds V / C) one step at a
        // time until the game's own selection (sSoraSelectedCard) lands on it, so the game stays in
        // charge. Tapping the selected card presses A to use it. Deck view: a tap shows a close-up.
        private int tapTarget = -1, tapSteps;
        private boolean tapRightIsNext = true, keyDown;
        private int lastDistance = Integer.MAX_VALUE, heldKey;
        private long keyUpAt, nextStepAt;
        private int closeupId = -1;
        private long closeupUntil;
        private float deckTop;
        private int deckShown;

        private void tapCard(float x, float y) {
            long now = android.os.SystemClock.uptimeMillis();
            if (statusOpen) {                             // any tap closes the status card
                statusOpen = false;
                return;
            }
            if (y >= headerHeight() && y < barHeight() && statusCount >= 9) {
                statusOpen = true;
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                return;
            }
            if (closeupId >= 0) {                         // any tap dismisses the close-up
                closeupId = -1;
                return;
            }
            if (handLive() && view != VIEW_PAD) {
                int slot = handMotion.hit(x, y);
                if (slot < 0) return;
                if (slot == hand[3]) {
                    tapTarget = -1;
                    tapKey(KeyEvent.KEYCODE_X, now);          // A: use the selected card
                } else {
                    tapTarget = slot;
                    tapSteps = 0;
                    lastDistance = Integer.MAX_VALUE;
                    nextStepAt = now;
                }
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            } else if (view == VIEW_DECK && deckShown > 0) {
                for (int i = 0; i < deckShown; i++) {
                    cellRect(i, deckTop, unit(), card);
                    if (card.contains(x, y)) {
                        closeupId = deck[5 + i];
                        closeupUntil = now + 4000;
                        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                        return;
                    }
                }
            }
        }

        private void tapKey(int key, long now) {
            if (keyDown) SDLActivity.onNativeKeyUp(heldKey);
            SDLActivity.onNativeKeyDown(key);
            heldKey = key;
            keyDown = true;
            keyUpAt = now + 70;
        }

        /** Cards the L / R cycle visits, in slot order: not used or removed, plus Reload. */
        private int ringPosition(int slot, int[] countOut) {
            int n = Math.min(hand[6], handCount - 7), pos = -1, count = 0;
            for (int i = 0; i < n; i++) {
                int v = hand[7 + i];
                boolean out = (v & ((1 << 16) | (1 << 17) | (1 << 18))) != 0;
                if (out) continue;
                if (i == slot) pos = count;
                count++;
            }
            countOut[0] = count;
            return pos;
        }

        private final int[] ringCount = new int[1];

        private void stepSelection() {
            long now = android.os.SystemClock.uptimeMillis();
            if (keyDown && now >= keyUpAt) {
                SDLActivity.onNativeKeyUp(heldKey);
                keyDown = false;
            }
            if (tapTarget < 0 || keyDown || now < nextStepAt) return;
            if (!handLive() || tapSteps > 40) {
                tapTarget = -1;
                return;
            }
            int sel = hand[3];
            if (sel == tapTarget) {
                tapTarget = -1;
                return;
            }
            int t = ringPosition(tapTarget, ringCount), p = ringPosition(sel, ringCount), n = ringCount[0];
            if (t < 0 || p < 0 || n == 0) {
                tapTarget = -1;
                return;
            }
            int forward = ((t - p) % n + n) % n;
            int distance = Math.min(forward, n - forward);
            if (distance > lastDistance) tapRightIsNext = !tapRightIsNext;   // learned the cycle direction
            lastDistance = distance;
            boolean goForward = forward <= n - forward;
            int key = goForward == tapRightIsNext ? KeyEvent.KEYCODE_V : KeyEvent.KEYCODE_C;   // R : L
            tapKey(key, now);
            tapSteps++;
            nextStepAt = now + 150;                         // let the game turn the ring
        }

        /** Deck-view close-up of a tapped card: big card art, name and value. */
        private void drawCloseup(Canvas canvas, float w, float h, float u, float bar) {
            long now = android.os.SystemClock.uptimeMillis();
            if (closeupId < 0) return;
            if (now > closeupUntil || view != VIEW_DECK || handLive()) {
                closeupId = -1;
                return;
            }
            fill.setColor(0xC0000000);
            canvas.drawRect(0, bar, w, h, fill);
            int id = closeupId & KhCards.ID_MASK;
            float ch = (h - bar) * 0.62f, cw = ch * KhRomArt.WIDTH / KhRomArt.HEIGHT;
            float top = bar + (h - bar - ch) * 0.3f;
            card.set(w / 2 - cw / 2, top, w / 2 + cw / 2, top + ch);
            drawGlow(canvas, card, 0x55FFFFFF);
            drawCardRect(canvas, card, u, id, (closeupId & KhCards.PREMIUM) != 0, 255, null, false);
            int s = gbaScale();
            int value = KhCards.value(id);
            String line = KhCards.name(id) + (value >= 0 ? "  " + value : "")
                + ((closeupId & KhCards.PREMIUM) != 0 ? "  \u2605" : "");
            gameText(canvas, line, w / 2, card.bottom + u * 0.3f, s, 0, w - u);
            animating = true;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    int i = e.getActionIndex();
                    Button b = hit(e.getX(i), e.getY(i));
                    pointerOwner.put(e.getPointerId(i), b);
                    press(b);
                    break;
                }
                case MotionEvent.ACTION_MOVE:
                    // Sliding a thumb between buttons (e.g. around the d-pad) moves the press.
                    for (int i = 0; i < e.getPointerCount(); i++) {
                        int id = e.getPointerId(i);
                        Button now = hit(e.getX(i), e.getY(i));
                        Button was = pointerOwner.get(id);
                        if (now != was && (now == null || now.action == null)) {
                            release(was);
                            pointerOwner.put(id, now);
                            press(now);
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP: {
                    int i = e.getActionIndex();
                    int id = e.getPointerId(i);
                    Button was = pointerOwner.get(id);
                    if (was == null && pointerOwner.indexOfKey(id) >= 0) tapCard(e.getX(i), e.getY(i));
                    release(was);
                    pointerOwner.remove(id);
                    break;
                }
                case MotionEvent.ACTION_CANCEL:
                    releaseAll();
                    break;
            }
            invalidate();
            return true;
        }
    }
}
