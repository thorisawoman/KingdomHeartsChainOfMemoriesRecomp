package org.khcomrecomp;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.KeyEvent;

import org.gbarecomp.GbaCompanion;

/**
 * Puts the bottom-screen panel on the secondary display of dual-screen
 * devices (AYN Thor and similar). With no second display it does nothing, so
 * the same APK still runs on ordinary phones. Also maps the BACK button to the
 * settings menu.
 */
public final class KhDualScreen implements GbaCompanion, DisplayManager.DisplayListener {
    private Activity activity;
    private DisplayManager displays;
    private KhBottomPresentation presentation;

    @Override
    public void onCreate(Activity activity) {
        this.activity = activity;
        displays = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        displays.registerDisplayListener(this, null);
    }

    @Override
    public void onResume() { show(); }

    @Override
    public void onPause() {
        // Presentation windows outlive a paused Activity badly; rebuild on resume.
        dismiss();
    }

    @Override
    public void onDestroy() {
        dismiss();
        if (displays != null) displays.unregisterDisplayListener(this);
    }

    /**
     * The Thor's BACK button (bottom right) opens / closes the settings menu instead of reaching
     * the game as Select (SDL maps a gamepad's BACK key to the controller Back button). The real
     * Select button sends BUTTON_SELECT and is untouched.
     */
    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            try {
                KhNative.toggleMenu();
            } catch (UnsatisfiedLinkError notLoaded) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onDisplayAdded(int displayId) { show(); }

    @Override
    public void onDisplayRemoved(int displayId) {
        if (presentation != null && presentation.getDisplay().getDisplayId() == displayId) dismiss();
    }

    @Override
    public void onDisplayChanged(int displayId) {}

    private Display secondaryDisplay() {
        Display[] candidates = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        int own = activity.getWindowManager().getDefaultDisplay().getDisplayId();
        for (Display d : candidates) {
            if (d.getDisplayId() != own && d.getDisplayId() != Display.DEFAULT_DISPLAY) return d;
        }
        return null;
    }

    private void show() {
        if (activity == null || activity.isFinishing() || presentation != null) return;
        Display target = secondaryDisplay();
        if (target == null) return;
        presentation = new KhBottomPresentation(activity, target);
        try {
            presentation.show();
        } catch (android.view.WindowManager.InvalidDisplayException gone) {
            presentation = null;
        }
    }

    private void dismiss() {
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
        }
    }
}
