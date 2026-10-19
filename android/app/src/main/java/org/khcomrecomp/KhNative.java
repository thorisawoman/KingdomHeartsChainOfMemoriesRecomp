package org.khcomrecomp;

/**
 * JNI entry points in libmain.so (src/android/dual_screen.cpp). SDLActivity
 * loads the library before the companion is created. Requests are queued and
 * applied on the game thread; snapshot() returns the latest per-frame copy.
 */
final class KhNative {
    private KhNative() {}

    /** Opens or closes the in-game settings menu (same as the controller Guide button). */
    static native void toggleMenu();

    /** Guest words to copy every frame (EWRAM / IWRAM only; others are ignored). */
    static native void setWatches(int[] addresses);

    /** out = [tick, battle (-1 unknown, 0 field, 1 battle), watch values...]; returns count. */
    static native int snapshot(int[] out);

    /**
     * Equipped deck: out = [active deck (0-2, -1 unknown), CP cost, card count,
     * collection size, n, n card entries (ID | 0x8000 premium)]; returns count.
     */
    static native int deckSnapshot(int[] out);
}
