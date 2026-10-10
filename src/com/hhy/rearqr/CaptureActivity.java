package com.hhy.rearqr;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;

/**
 * Entry for the QS tile (Tile.setActivityLaunchForClick -> SystemUI starts us directly, which also thaws a
 * Greeze-frozen process; or onClick -> startActivityAndCollapse) and the exported action com.hhy.rearqr.CAPTURE.
 * Fully transparent, not touchable, not focusable; it stays alive (= app in the foreground, not frozen) until the
 * capture and rear show are done, at most MAX_MS, then finishes. The capture waits until the shade/control center
 * is gone, so neither it nor this activity ends up in the screenshot.
 */
public final class CaptureActivity extends Activity {
    public static final String EXTRA_FROM_TILE = "from_tile";
    /** How we were started: "tile-launch" (setActivityLaunchForClick), "tile-onclick", else "intent". */
    public static final String EXTRA_VIA = "via";
    private static final long MAX_MS = 6000;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> done("timeout " + MAX_MS + " ms");

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        overridePendingTransition(0, 0);
        handle(getIntent(), "onCreate");
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handle(i, "onNewIntent");
    }

    private void handle(Intent i, String how) {
        boolean tile = i != null && i.getBooleanExtra(EXTRA_FROM_TILE, false);
        String via = i != null && i.getStringExtra(EXTRA_VIA) != null ? i.getStringExtra(EXTRA_VIA) : tile ? "tile" : "intent";
        Log.i(Capture.TAG, "CaptureActivity via " + via + " (" + how + ")");
        KeepAliveService.sync(this);
        boolean started = Capture.start(this, via, tile ? 250 : 300, true, ok -> done("capture " + (ok ? "ok" : "failed")));
        h.removeCallbacks(timeout);
        if (!started) {
            done("capture already running");
            return;
        }
        h.postDelayed(timeout, MAX_MS);
    }

    @SuppressWarnings("deprecation")
    private void done(String why) {
        h.removeCallbacks(timeout);
        if (isFinishing() || isDestroyed()) return;
        Log.i(Capture.TAG, "CaptureActivity finish (" + why + ")");
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacks(timeout);
        super.onDestroy();
    }
}
