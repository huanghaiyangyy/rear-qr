package com.hhy.rearqr;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * Rear page. Started (via root am start) on the MAIN display in its own task, fully invisible
 * (alpha 0, not touchable, no bitmap at all — touches fall through to the app below), then its task is moved to
 * the rear display with root `am display move-stack`. It becomes visible only after Rear confirmed the move
 * (window display AND `am stack list` both say rear) — and only while the window really is on that display.
 * If it ever shows up elsewhere after that (system moved it back), it hides at once and is closed.
 */
public final class RearActivity extends Activity {
    private static final long MISPLACED_MS = 600;
    RearView view;
    private boolean onRear;
    /** Display id Rear confirmed after move-stack; -1 = not confirmed (never visible). */
    private int confirmedDisplay = -1;
    volatile boolean stopped;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        KeepAliveService.sync(this);
        if (Capture.current == null || !Rear.inShow()) { // nothing to show / started from outside show()
            Log.i(Capture.TAG, "RearActivity created outside show() (bitmap=" + (Capture.current != null) + ") -> finish");
            finishAndRemoveTask();
            return;
        }
        Rear.activity = this;
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        w.setDecorFitsSystemWindows(false);
        view = new RearView(this);
        setContentView(view);
        Log.i(Capture.TAG, "RearActivity created on display " + displayId() + ", task " + getTaskId());
        applyMode();
    }

    /** Current display of our window (follows move-stack). */
    int displayId() {
        View d = getWindow().getDecorView();
        Display disp = d.getDisplay() != null ? d.getDisplay() : getDisplay();
        return disp != null ? disp.getDisplayId() : -1;
    }

    private android.graphics.Rect windowBounds() {
        try {
            return getWindowManager().getCurrentWindowMetrics().getBounds();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Confirmed by Rear AND the window is on that display right now. */
    boolean isOnRear() {
        return onRear;
    }

    /** Called by Rear (main thread) once window and server both report the rear display. */
    void confirmRear(int displayId) {
        confirmedDisplay = displayId;
        Log.i(Capture.TAG, "RearActivity confirmed on rear display " + displayId);
        applyMode();
    }

    /** Invisible + click-through + no content unless confirmed on the rear; visible full-brightness page there. */
    void applyMode() {
        if (view == null) return;
        int id = displayId();
        android.graphics.Rect bounds = windowBounds();
        // 3rd, independent check: our window really has the small rear size (904x572), not the main 1220x2656
        boolean small = bounds != null && Math.max(bounds.width(), bounds.height()) < 2000;
        boolean rear = confirmedDisplay >= 0 && id == confirmedDisplay && id != Display.DEFAULT_DISPLAY && small;
        if (confirmedDisplay >= 0 && id == confirmedDisplay && !small) {
            Log.w(Capture.TAG, "RearActivity on display " + id + " but window bounds " + bounds + " look like the main screen -> invisible");
        }
        Window w = getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.alpha = rear ? 1f : 0f;
        lp.screenBrightness = rear ? 1f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        if (rear) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        w.setAttributes(lp);
        w.getDecorView().setBackgroundColor(rear ? Color.BLACK : Color.TRANSPARENT);
        view.setVisibility(rear ? View.VISIBLE : View.INVISIBLE);
        if (!rear) {
            view.setBitmap(null); // nothing QR-like may exist in a window that isn't on the rear
        } else {
            if (!view.hasBitmap() && Capture.current != null) view.setBitmap(Capture.current);
            try {
                WindowInsetsController ic = w.getDecorView().getWindowInsetsController();
                if (ic != null) {
                    ic.hide(WindowInsets.Type.systemBars());
                    ic.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } catch (Throwable ignored) {
            }
            view.requestLayout();
            view.invalidate();
        }
        if (rear != onRear) {
            Log.i(Capture.TAG, "RearActivity now on display " + id + " bounds " + bounds + (rear ? " (confirmed rear: visible)"
                    : " (" + (confirmedDisplay < 0 ? "not confirmed" : "left rear " + confirmedDisplay) + ": invisible, no content)"));
        }
        if (!rear && confirmedDisplay >= 0) { // was on the rear, now reported elsewhere: re-check, then close
            view.postDelayed(() -> {
                if (isFinishing() || isDestroyed() || onRear) return; // came back (transient) -> keep
                Rear.pageMisplaced(this, "on display " + displayId() + " after being on the rear");
            }, MISPLACED_MS);
        }
        onRear = rear;
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c); // also delivered when move-stack changes our display (configChanges)
        Log.i(Capture.TAG, "RearActivity config changed, display " + displayId());
        applyMode();
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        Rear.activityResumed = true;
        Log.i(Capture.TAG, "RearActivity resumed on display " + displayId() + (onRear ? " (rear)" : ""));
        applyMode();
        if (!Rear.inShow() && confirmedDisplay < 0) {
            Rear.pageMisplaced(this, "resumed on display " + displayId() + " without confirmation");
            return;
        }
        if (onRear) Rear.refresh();
    }

    @Override
    protected void onPause() {
        Rear.activityResumed = false;
        Log.i(Capture.TAG, "RearActivity paused on display " + displayId());
        super.onPause();
    }

    @Override
    protected void onStop() {
        stopped = true;
        Log.i(Capture.TAG, "RearActivity stopped on display " + displayId() + (isFinishing() ? " (finishing)" : ""));
        if (!isFinishing()) Rear.pageStopped(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (view != null) view.setBitmap(null);
        Rear.activityGone(this);
        super.onDestroy();
    }
}
