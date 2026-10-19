package org.khcomrecomp;

import android.graphics.RectF;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Motion for the battle card list on the bottom screen. It follows the live snapshot
 * (KhNative.handSnapshot) slot by slot and turns state changes into animations:
 *
 *  - a card that gets used (or removed) flies up and fades out; the others slide to fill the gap;
 *  - cards that come back (Reload) deal in from below, one after another;
 *  - a friend card (Donald, Goofy, ... picked up in battle; the game adds it after the Reload
 *    slot) flips in from above;
 *  - a battle start deals the whole list in.
 *
 * Layout targets come from the panel; positions ease toward them every frame. Nothing here
 * talks to the game.
 */
final class KhHandAnimator {
    static final int ENTER_MS = 360, EXIT_MS = 320, DEAL_STAGGER_MS = 45;

    static final int PHASE_IDLE = 0, PHASE_ENTER = 1, PHASE_EXIT = 2, PHASE_GONE = 3;

    static final class Sprite {
        final int slot;
        int id;
        boolean premium, stocked, friend, visible;
        int phase = PHASE_GONE;
        long phaseStart;
        boolean fromAbove;          // friend cards arrive from the top
        final RectF rect = new RectF();     // current (eased) position
        final RectF target = new RectF();
        boolean placed;             // rect has been set once

        Sprite(int slot) { this.slot = slot; }

        /** 0..1 progress of the current enter / exit animation. */
        float progress(long now) {
            int len = phase == PHASE_ENTER ? ENTER_MS : EXIT_MS;
            float t = (now - phaseStart) / (float) len;
            return t < 0 ? 0 : t > 1 ? 1 : t;
        }
    }

    private final SparseArray<Sprite> sprites = new SparseArray<>();
    private final List<Sprite> order = new ArrayList<>();
    private long lastStep;
    private boolean fresh = true;

    /** Forget everything (battle over): the next battle deals in from scratch. */
    void reset() {
        sprites.clear();
        order.clear();
        fresh = true;
    }

    /**
     * Applies a snapshot: slots[i] = card ID | used << 16 | stocked << 17 | removed << 18,
     * reloadId marks the Reload card (friend cards follow it).
     */
    void sync(int[] slots, int offset, int n, int reloadId, long now) {
        int reloadAt = -1;
        int dealt = 0;
        for (int i = 0; i < n; i++) {
            int slot = slots[offset + i];
            int id = slot & 0xFFFF;
            if (id == reloadId && reloadAt < 0) reloadAt = i;
            boolean used = (slot & (1 << 16)) != 0, stocked = (slot & (1 << 17)) != 0;
            boolean removed = (slot & (1 << 18)) != 0;
            boolean visible = stocked || !(used || removed);
            Sprite s = sprites.get(i);
            if (s == null || s.id != id) {
                s = new Sprite(i);
                s.id = id;
                sprites.put(i, s);
            }
            s.premium = id != reloadId && (id & KhCards.PREMIUM) != 0;
            s.stocked = stocked;
            s.friend = reloadAt >= 0 && i > reloadAt;
            if (visible && (s.phase == PHASE_GONE || s.phase == PHASE_EXIT)) {
                s.phase = PHASE_ENTER;
                s.fromAbove = s.friend && !fresh;
                // Reload / battle start: stagger so the cards deal in one after another.
                s.phaseStart = now + (s.fromAbove ? 0 : dealt++ * DEAL_STAGGER_MS);
                s.placed = false;
            } else if (!visible && (s.phase == PHASE_IDLE || s.phase == PHASE_ENTER)) {
                s.phase = PHASE_EXIT;
                s.phaseStart = now;
            }
            s.visible = visible;
        }
        for (int i = sprites.size() - 1; i >= 0; i--) {
            if (sprites.keyAt(i) >= n) sprites.removeAt(i);
        }
        fresh = false;
    }

    /** Visible sprites in slot order: these get layout cells. */
    List<Sprite> laidOut() {
        order.clear();
        for (int i = 0; i < sprites.size(); i++) {
            Sprite s = sprites.valueAt(i);
            if (s.visible) order.add(s);
        }
        return order;
    }

    /** All sprites still drawn (visible, plus those finishing their exit), in slot order. */
    List<Sprite> drawn(long now) {
        order.clear();
        for (int i = 0; i < sprites.size(); i++) {
            Sprite s = sprites.valueAt(i);
            if (s.phase == PHASE_EXIT && s.progress(now) >= 1) s.phase = PHASE_GONE;
            if (s.phase == PHASE_ENTER && s.progress(now) >= 1) s.phase = PHASE_IDLE;
            if (s.phase != PHASE_GONE) order.add(s);
        }
        return order;
    }

    Sprite get(int slot) { return sprites.get(slot); }

    /** Slot of the visible card under (x, y), or -1. */
    int hit(float x, float y) {
        for (int i = sprites.size() - 1; i >= 0; i--) {
            Sprite s = sprites.valueAt(i);
            if (s.visible && s.rect.contains(x, y)) return s.slot;
        }
        return -1;
    }

    /** Eases every visible sprite toward its target; call once per frame after targets are set. */
    void step(long now) {
        float dt = lastStep == 0 ? 0.016f : Math.min(0.1f, (now - lastStep) / 1000f);
        lastStep = now;
        float k = (float) (1 - Math.exp(-dt * 14));
        for (int i = 0; i < sprites.size(); i++) {
            Sprite s = sprites.valueAt(i);
            if (!s.visible) continue;
            if (!s.placed) {
                s.rect.set(s.target);
                s.placed = true;
                continue;
            }
            s.rect.left += (s.target.left - s.rect.left) * k;
            s.rect.top += (s.target.top - s.rect.top) * k;
            s.rect.right += (s.target.right - s.rect.right) * k;
            s.rect.bottom += (s.target.bottom - s.rect.bottom) * k;
        }
    }

    /** True while anything is still moving (the panel keeps redrawing at frame rate). */
    boolean animating(long now) {
        for (int i = 0; i < sprites.size(); i++) {
            Sprite s = sprites.valueAt(i);
            if (s.phase == PHASE_ENTER || s.phase == PHASE_EXIT) return true;
            if (s.visible && Math.abs(s.rect.left - s.target.left) + Math.abs(s.rect.top - s.target.top) > 0.5f)
                return true;
        }
        return false;
    }

    static float easeOut(float t) { return 1 - (1 - t) * (1 - t) * (1 - t); }
}
