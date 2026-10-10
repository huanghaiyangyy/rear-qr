package com.hhy.rearqr;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

/**
 * Quick Settings tile 「二维码贴背屏」.
 * Primary (API 34+): Tile.setActivityLaunchForClick(CaptureActivity) — SystemUI then starts the activity itself on
 * click (and collapses the panel) instead of binding us and calling onClick, so a click works even while HyperOS
 * Greeze has frozen the process (the activity start thaws it). Re-set on every onStartListening because SystemUI
 * does not persist it (e.g. across SystemUI restarts). No ACTIVE_TILE meta-data: we rely on onStartListening.
 * Fallback: onClick (ROMs that ignore the launch intent) -> startActivityAndCollapse, plus a 1 s direct capture if
 * the trampoline launch was swallowed.
 */
public final class QrTileService extends TileService {
    private static final int RC_TILE_LAUNCH = 0x7117;
    private static final int RC_ONCLICK = 0x7118;

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        Log.i(Capture.TAG, "tile added");
        refreshTile("onTileAdded");
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        refreshTile("onStartListening");
    }

    private void refreshTile(String from) {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(Tile.STATE_INACTIVE);
        t.setLabel(getString(R.string.app_name));
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                Intent i = new Intent(this, CaptureActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        .putExtra(CaptureActivity.EXTRA_FROM_TILE, true)
                        .putExtra(CaptureActivity.EXTRA_VIA, "tile-launch");
                t.setActivityLaunchForClick(PendingIntent.getActivity(this, RC_TILE_LAUNCH, i,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
                Log.i(Capture.TAG, "tile: setActivityLaunchForClick set (" + from + ")");
            } catch (Throwable e) {
                Log.w(Capture.TAG, "tile: setActivityLaunchForClick failed (" + from + "): " + e);
            }
        }
        t.updateTile();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onClick() {
        Log.i(Capture.TAG, "tile onClick (SystemUI did not use the launch intent)");
        KeepAliveService.sync(this);
        final long clickAt = android.os.SystemClock.uptimeMillis();
        // HyperOS sometimes swallows the trampoline launch (seen after the rear page was swiped away on the
        // rear screen): if CaptureActivity hasn't started within ~1s, capture directly (root collapse + screencap)
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (Capture.lastTriggerAt < clickAt) {
                Log.w(Capture.TAG, "tile: trampoline not started -> direct capture");
                Capture.start(getApplicationContext(), "tile-direct", 100, true, null);
            }
        }, 1000);
        Intent i = new Intent(this, CaptureActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(CaptureActivity.EXTRA_FROM_TILE, true)
                .putExtra(CaptureActivity.EXTRA_VIA, "tile-onclick");
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, RC_ONCLICK, i,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            } else {
                startActivityAndCollapse(i);
            }
        } catch (Exception e) { // still collapse (via root) and capture
            Log.w(Capture.TAG, "startActivityAndCollapse failed", e);
            Capture.start(this, "tile-direct", 300, true, null);
        }
    }
}
