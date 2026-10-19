package org.khcomrecomp;

import android.app.Presentation;
import android.content.Context;
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

import org.libsdl.app.SDLActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Bottom-screen panel with two views, switched from the status bar:
 *
 * DECK (default) shows the equipped deck read live from guest RAM (see
 * src/android/dual_screen.cpp), coloured by card category.
 *
 * PAD shows GBA buttons as touch targets. They inject the keyboard keys that
 * keybinds.ini maps to each button, so input reaches the game through its normal
 * path (SDL -> keybinds -> keypad), plus any guest RAM words listed in
 * bottom_screen_watches.txt (app external files dir, editable without a rebuild).
 */
final class KhBottomPresentation extends Presentation {
    private static final String WATCH_FILE = "bottom_screen_watches.txt";

    KhBottomPresentation(Context outer, Display display) {
        super(outer, display);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        List<Watch> watches = loadWatches(getContext().getExternalFilesDir(null));
        int[] addrs = new int[watches.size()];
        for (int i = 0; i < addrs.length; i++) addrs[i] = watches.get(i).address;
        KhNative.setWatches(addrs);
        setContentView(new PanelView(getContext(), watches));
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
        private final Button bView = new Button("PAD", this::toggleView);
        private final Button[] padButtons = {bL, bR, bA, bB, bSelect, bStart, bUp, bDown, bLeft, bRight, bMenu, bView};
        private final Button[] deckButtons = {bMenu, bView};
        private Button[] buttons = deckButtons;   // deck view first: the Thor has physical buttons

        private final SparseArray<Button> pointerOwner = new SparseArray<>();
        private final List<Watch> watches;
        private final int[] snap = new int[66];
        private int snapCount;
        private final int[] deck = new int[5 + 99];
        private int deckCount;
        private final RectF tile = new RectF();

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mono = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Runnable poll = new Runnable() {
            @Override
            public void run() {
                try {
                    snapCount = KhNative.snapshot(snap);
                    deckCount = KhNative.deckSnapshot(deck);
                } catch (UnsatisfiedLinkError notYet) {
                    snapCount = 0;
                    deckCount = 0;
                }
                invalidate();
                postDelayed(this, 100);
            }
        };

        PanelView(Context c, List<Watch> watches) {
            super(c);
            this.watches = watches;
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            mono.setColor(0xFFCFD3E6);
            mono.setTypeface(android.graphics.Typeface.MONOSPACE);
            setBackgroundColor(0xFF0E1124);
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
            buttons = buttons == deckButtons ? padButtons : deckButtons;
            bView.label = buttons == deckButtons ? "PAD" : "DECK";
            invalidate();
        }

        private float unit() { return Math.min(getWidth(), getHeight()) / 12f; }

        /** Height of the status bar shared by both views. */
        private float barHeight() { return unit() * 1.4f; }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float u = unit();
            float pad = u * 0.25f, bar = barHeight();
            // Status bar: text on the left, VIEW toggle and MENU on the right.
            bView.rect.set(w - u * 2.6f, pad, w - pad, bar - pad);
            bMenu.rect.set(w - u * 5.2f, pad, w - u * 2.85f, bar - pad);
            // Pad view: shoulders under the bar, d-pad bottom left, A/B bottom right.
            bL.rect.set(pad, bar + pad, w * 0.3f, bar + pad + u * 1.5f);
            bR.rect.set(w * 0.7f, bar + pad, w - pad, bar + pad + u * 1.5f);
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
            if (deckCount >= 5 && deck[0] >= 0 && deck[0] <= 2) {
                return String.format("%s  ·  Deck %d  ·  %d cards  ·  %d CP",
                    mode, deck[0] + 1, deck[4], deck[1]);
            }
            return mode;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), u = unit(), bar = barHeight();
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(u * 0.55f);
            canvas.drawText(statusLine(), u * 0.4f, bar / 2 - (text.descent() + text.ascent()) / 2, text);
            text.setTextAlign(Paint.Align.CENTER);
            if (buttons == deckButtons) drawDeck(canvas, w, h, u, bar);
            else drawWatches(canvas, w, u, bar);
            text.setTextSize(u * 0.55f);
            for (Button b : buttons) {
                fill.setColor(b.holders > 0 ? 0xFF5C6BC0 : 0xFF262B4A);
                float rr = b.rect.height() * 0.3f;
                canvas.drawRoundRect(b.rect, rr, rr, fill);
                float ty = b.rect.centerY() - (text.descent() + text.ascent()) / 2;
                canvas.drawText(b.label, b.rect.centerX(), ty, text);
            }
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

        /** The equipped deck as a grid of tiles, in deck order, sized to fit without scrolling. */
        private void drawDeck(Canvas canvas, float w, float h, float u, float bar) {
            int n = deckCount >= 5 ? Math.min(deck[4], deckCount - 5) : 0;
            if (n == 0) {
                text.setTextSize(u * 0.5f);
                canvas.drawText(deckCount >= 5 ? "No deck equipped yet" : "Waiting for the game…",
                    w / 2, (bar + h) / 2, text);
                return;
            }
            float gap = u * 0.12f, top = bar + gap, avail = h - top - gap;
            int cols = 3;
            float rowH = Math.min(u * 1.1f, avail / (float) Math.ceil(n / (double) cols));
            while (rowH < u * 0.55f && cols < 8) {
                cols++;
                rowH = Math.min(u * 1.1f, avail / (float) Math.ceil(n / (double) cols));
            }
            float colW = (w - gap) / cols;
            for (int i = 0; i < n; i++) {
                int entry = deck[5 + i];
                int id = entry & KhCards.ID_MASK;
                float x = gap + (i % cols) * colW, y = top + (i / cols) * rowH;
                tile.set(x, y, x + colW - gap, y + rowH - gap);
                float rr = tile.height() * 0.18f;
                fill.setColor(categoryColor(KhCards.category(id)));
                canvas.drawRoundRect(tile, rr, rr, fill);
                if ((entry & KhCards.PREMIUM) != 0) {
                    fill.setStyle(Paint.Style.STROKE);
                    fill.setStrokeWidth(Math.max(2f, rowH * 0.06f));
                    fill.setColor(0xFFE8C45A);
                    canvas.drawRoundRect(tile, rr, rr, fill);
                    fill.setStyle(Paint.Style.FILL);
                }
                float ts = Math.min(tile.height() * 0.5f, u * 0.5f);
                text.setTextSize(ts);
                float ty = tile.centerY() - (text.descent() + text.ascent()) / 2;
                int value = KhCards.value(id);
                float nameX = tile.left + ts * 0.5f;
                if (value >= 0) {
                    text.setTextAlign(Paint.Align.LEFT);
                    text.setFakeBoldText(true);
                    canvas.drawText(Integer.toString(value), tile.left + ts * 0.45f, ty, text);
                    text.setFakeBoldText(false);
                    nameX = tile.left + ts * 1.5f;
                }
                text.setTextAlign(Paint.Align.LEFT);
                String name = KhCards.name(id);
                float room = tile.right - nameX - ts * 0.3f;
                int fit = text.breakText(name, true, room, null);
                canvas.drawText(fit < name.length() ? name.substring(0, Math.max(0, fit - 1)) + "…" : name,
                    nameX, ty, text);
            }
            text.setTextAlign(Paint.Align.CENTER);
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
                    int id = e.getPointerId(e.getActionIndex());
                    release(pointerOwner.get(id));
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
