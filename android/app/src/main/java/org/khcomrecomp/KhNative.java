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
     * collection size, n, n card entries (ID | 0x8000 premium), 20 deck name bytes];
     * returns count.
     */
    static native int deckSnapshot(int[] out);

    /**
     * Battle card list (only while in battle): out = [valid, side (0 Sora, 1 Riku),
     * list (0 main, 1 magic, 2 item, 3 enemy), cursor, cards left, stock count, n,
     * n slots (card ID | used << 16 | stocked << 17 | removed << 18)]; returns count.
     * ID 0xFFFE is the Reload card.
     */
    static native int handSnapshot(int[] out);

    /**
     * Floor map: out = [world, floor (-1 none), riku (0/1), current room, 32 rooms
     * (flags | nameId << 16 | roomType << 24)]; returns count.
     */
    static native int mapSnapshot(int[] out);

    /**
     * Cards picked up in the current (or last) battle: out = [n, n entries
     * (card ID | 0x8000 premium)]; returns count.
     */
    static native int pickupSnapshot(int[] out);
}
