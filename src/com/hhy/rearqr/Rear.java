package com.hhy.rearqr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Shows Capture.current on the rear display.
 *  1. move-stack (primary): root `am start --display 0` RearActivity (invisible, own task) ->
 *     root `am display move-stack <rootTask> <rear>`. The page only becomes visible after BOTH the window
 *     (client display id) and `am stack list` (server) report the rear display ("confirmed"). If that never
 *     happens the page is finished: it must never render the QR on the main display.
 *  2. overlay: a TYPE_APPLICATION_OVERLAY window on the rear, accepted only if it was really drawn on the rear
 *     display (client + `dumpsys window`). HyperOS normally refuses it ("Not allow non-system app add window
 *     type = 2038 on rear display"), so in practice this just fails cleanly.
 * (The 0.1.x Presentation fallback was removed: it needed the page to stay alive on the main display.)
 */
final class Rear {
    static final String TAG = Capture.TAG;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Runnable AUTO_CLOSE = () -> close("timeout");
    private static final String OVERLAY_TITLE = "RearQR";

    static volatile RearActivity activity;
    static volatile boolean activityResumed;
    /** Auto-close time (uptimeMillis), 0 = none (keep-open / nothing shown). Single source of truth for the
     *  rear timer and the capsule countdown; set in refresh(). */
    static volatile long deadline;
    /** show() is running: a page torn down meanwhile is our own stale one, not "the user closed it". */
    private static volatile boolean inShow;
    /** Stopped this long on the rear (swiped away / covered) -> close and clean up. */
    private static final long STOPPED_CLOSE_MS = 1200;
    private static volatile RearView overlay;
    private static WindowManager overlayWm;
    private static Context app;

    private Rear() {
    }

    static boolean inShow() {
        return inShow;
    }

    /** Called on the capture worker thread after Capture.current was set. @return true if the QR is on the rear */
    static boolean show(Context c, RootShell sh) {
        inShow = true;
        try {
            return doShow(c, sh);
        } finally {
            inShow = false;
        }
    }

    private static boolean doShow(Context c, RootShell sh) {
        app = c.getApplicationContext();
        int rear = App.rearDisplayId(c);
        Capture.su(sh, "input -d " + rear + " keyevent KEYCODE_WAKEUP", 5000);
        RearActivity a = activity;
        // reuse only a page that is confirmed on the rear and in front; after the rear screen slept or the
        // rear launcher came back on top, the old page is paused/stopped and refreshing it shows nothing
        boolean live = a != null && !a.isFinishing() && a.isOnRear() && activityResumed;
        if (a != null && !live && overlay == null) {
            Log.i(TAG, "rear: existing page not in front (resumed=" + activityResumed + ", onRear=" + a.isOnRear()
                    + ", display=" + a.displayId() + ") -> recreate");
        }
        if (live || overlay != null) {
            H.post(Rear::refresh);
            Log.i(TAG, "rear: refreshed existing " + (overlay != null ? "overlay" : "move-stack page"));
            return true;
        }
        if (a != null) finishPage(a, "stale page before new show");

        String how = viaMoveStack(sh, rear);
        if (how == null) { // launch swallowed (old task still tearing down) or move refused: clean up, retry once
            if (activity != null) finishPage(activity, "move-stack attempt 1 failed");
            Log.i(TAG, "rear: retry move-stack");
            SystemClock.sleep(400);
            how = viaMoveStack(sh, rear);
        }
        if (how == null && activity != null) finishPage(activity, "move-stack failed twice"); // never left on main
        if (how == null) how = viaOverlay(sh, rear);
        if (how == null) how = "NONE";
        Capture.su(sh, "input -d " + rear + " keyevent KEYCODE_WAKEUP", 5000);
        Log.i(TAG, "rear: shown via " + how + " on display " + rear);
        String f = Capture.focusNow(sh);
        Log.i(TAG, "main focus after show: " + f);
        App.rootWhitelist(c, sh);
        if (how.equals("NONE")) {
            close("all mechanisms failed");
            Capsule.fail(c, "背屏显示失败，请把 logcat 发给开发者");
            return false;
        }
        return true;
    }

    /** Worker thread: finishAndRemoveTask the page and wait (max 2 s) until it is destroyed. */
    private static void finishPage(RearActivity a, String why) {
        Log.i(TAG, "rear: finish page (" + why + "), display " + a.displayId() + ", task " + a.getTaskId());
        onUi(() -> {
            a.finishAndRemoveTask();
            return null;
        });
        // wait for onDestroy: a singleTask page that is still finishing swallows the next am start
        for (int k = 0; k < 40 && activity == a; k++) SystemClock.sleep(50);
        if (activity == a) Log.w(TAG, "rear: old page still not destroyed after 2 s");
        SystemClock.sleep(150);
    }

    // ------------------------------------------------------------ 1. move-stack

    private static String viaMoveStack(RootShell sh, int rear) {
        activityResumed = false;
        Capture.su(sh, "am start -W --display 0 --activity-no-animation --activity-exclude-from-recents -n "
                + App.PKG + "/.RearActivity", 8000);
        for (int k = 0; k < 20 && !activityResumed; k++) SystemClock.sleep(100);
        RearActivity a = activity;
        if (a == null || !activityResumed) {
            Log.w(TAG, "rear: RearActivity did not start on display 0 (created=" + (a != null) + ")");
            return null;
        }
        int taskId = a.getTaskId();
        String list = sh.exec("am stack list", 8000).text();
        int root = Stacks.rootTaskOf(list, taskId);
        Log.i(TAG, "rear: RearActivity task " + taskId + " -> RootTask " + root + " on display " + Stacks.displayOf(list, root)
                + ", window display " + a.displayId());
        if (root < 0) root = taskId;
        RootShell.Result mv = Capture.su(sh, "am display move-stack " + root + " " + rear, 8000);
        // confirm on both sides: our window reports the rear display AND the server lists the task there
        int client = -1, server = -1;
        for (int k = 0; k < 20; k++) {
            Integer id = onUi(() -> {
                RearActivity x = activity;
                return x == null || x.isFinishing() ? -2 : x.displayId();
            });
            client = id == null ? -1 : id;
            if (client == -2) break;
            if (client == rear) {
                server = Stacks.displayOf(sh.exec("am stack list", 8000).text(), root);
                if (server == rear) break;
            }
            SystemClock.sleep(100);
        }
        final boolean ok = client == rear && server == rear;
        Log.i(TAG, "rear: after move-stack (rc=" + mv.code + ") RootTask " + root + " server displayId=" + server
                + ", window display " + client + " -> " + (ok ? "CONFIRMED on rear" : "FAILED"));
        if (!ok) return null;
        Boolean shown = onUi(() -> {
            RearActivity x = activity;
            if (x == null || x.isFinishing()) return false;
            x.confirmRear(rear);
            refresh();
            return x.isOnRear();
        });
        if (!Boolean.TRUE.equals(shown)) {
            Log.w(TAG, "rear: page vanished / left the rear right after confirmation");
            return null;
        }
        return "move-stack";
    }

    // ------------------------------------------------------------ 2. overlay window (verified)

    private static String viaOverlay(RootShell sh, int rear) {
        if (!Settings.canDrawOverlays(app)) Capture.su(sh, "appops set " + App.PKG + " SYSTEM_ALERT_WINDOW allow", 5000);
        Boolean ok = onUi(() -> {
            Display d = display(app, rear);
            if (d == null) return false;
            Context wc = app.createDisplayContext(d)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            WindowManager wm = wc.getSystemService(WindowManager.class);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                    PixelFormat.OPAQUE);
            lp.screenBrightness = 1f;
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setFitInsetsTypes(0);
            lp.setTitle(OVERLAY_TITLE);
            RearView v = new RearView(wc);
            v.setBitmap(null); // no content until it is verified to be on the rear
            wm.addView(v, lp);
            overlay = v;
            overlayWm = wm;
            return true;
        });
        if (!Boolean.TRUE.equals(ok)) {
            Log.w(TAG, "rear: overlay failed (canDrawOverlays=" + Settings.canDrawOverlays(app) + ")");
            removeOverlay();
            return null;
        }
        SystemClock.sleep(400);
        Integer client = onUi(() -> {
            RearView v = overlay;
            if (v == null || !v.isAttachedToWindow() || v.getDisplay() == null || !v.drawn) return -1;
            return v.getDisplay().getDisplayId();
        });
        int server = Focus.windowDisplay(sh.exec(Focus.WIN_CMD, 8000).text(), OVERLAY_TITLE);
        boolean verified = client != null && client == rear && server == rear;
        Log.i(TAG, "rear: overlay check: window display " + client + ", server display " + server + " -> "
                + (verified ? "OK" : "REJECTED (removed)"));
        if (!verified) {
            onUi(() -> {
                removeOverlay();
                return null;
            });
            return null;
        }
        H.post(Rear::refresh);
        diagnostics(sh);
        return "overlay";
    }

    private static void removeOverlay() {
        if (overlay != null) {
            try {
                overlayWm.removeView(overlay);
            } catch (Exception ignored) {
            }
        }
        overlay = null;
        overlayWm = null;
    }

    private static void diagnostics(RootShell sh) {
        Capture.su(sh, "dumpsys window windows | grep -iE -A4 'rearqr'", 8000, 3000);
        Capture.su(sh, "dumpsys display | grep -iE 'mState|mScreenState|Display Power' | head", 8000, 1500);
    }

    private static Display display(Context c, int id) {
        Display d = ((DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE)).getDisplay(id);
        if (d == null) Log.w(TAG, "display " + id + " not found");
        return d;
    }

    /** Run on the main thread and wait (max 3 s); exceptions are logged and give null. */
    private static <T> T onUi(Callable<T> f) {
        final Object[] res = new Object[1];
        CountDownLatch done = new CountDownLatch(1);
        H.post(() -> {
            try {
                res[0] = f.call();
            } catch (Throwable t) {
                Log.e(TAG, "rear ui", t);
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        @SuppressWarnings("unchecked") T t = (T) res[0];
        return t;
    }

    // ------------------------------------------------------------ shared UI state (main thread)

    /** Show the current crop on whatever is (confirmed) on the rear and restart the auto-close timer. */
    static void refresh() {
        Bitmap bm = Capture.current;
        if (bm == null) {
            close("no bitmap");
            return;
        }
        RearActivity a = activity;
        boolean any = false;
        if (a != null && a.view != null && a.isOnRear()) { // never hand the crop to a page that isn't on the rear
            a.view.setBitmap(bm);
            any = true;
        }
        if (overlay != null && overlay.drawn) {
            overlay.setBitmap(bm);
            any = true;
        }
        if (!any) {
            Log.i(TAG, "rear: refresh skipped, nothing confirmed on the rear yet");
            return;
        }
        H.removeCallbacks(AUTO_CLOSE);
        Context c = app != null ? app : (a != null ? a.getApplicationContext() : null);
        if (c != null && !App.keepOpen(c)) {
            long ms = Math.max(5, App.timeoutSec(c)) * 1000L;
            deadline = SystemClock.uptimeMillis() + ms;
            H.postAtTime(AUTO_CLOSE, deadline);
        } else {
            deadline = 0;
        }
    }

    /** Main thread: is a QR page actually up on the rear (confirmed move-stack page or verified overlay)? */
    static boolean isShowing() {
        if (overlay != null) return true;
        RearActivity a = activity;
        return a != null && !a.isFinishing() && a.isOnRear();
    }

    /** Tap on the rear screen: take a new screenshot of the main screen (重新识别). */
    static void recapture(Context c) {
        recapture(c, "rear-tap");
    }

    /** rear-tap / capsule-refresh: new screenshot without collapsing anything; the old code stays on failure. */
    static void recapture(Context c, String trigger) {
        setBusy(true);
        boolean started = Capture.start(c, trigger, 100, false, ok -> {
            if (!ok) setBusy(false); // keep showing the old code
        });
        if (!started) setBusy(false);
    }

    private static void setBusy(boolean b) {
        RearActivity a = activity;
        if (a != null && a.view != null) a.view.setBusy(b);
        if (overlay != null) overlay.setBusy(b);
    }

    static void close(String why) {
        H.post(() -> {
            Log.i(TAG, "rear: close (" + why + ") -> cleanup: finish page, remove overlay, drop bitmap, remove capsule");
            H.removeCallbacks(AUTO_CLOSE);
            deadline = 0;
            Capsule.dismiss("rear closed: " + why);
            RearActivity a = activity;
            if (a != null) {
                if (a.view != null) a.view.setBitmap(null);
                a.finishAndRemoveTask(); // the rear launcher (subscreencenter) comes back; nothing left on display 0
            }
            removeOverlay();
            Capture.current = null;
        });
    }

    /** RearActivity destroyed (by us, by back, or by the system). */
    static void activityGone(RearActivity a) {
        if (activity != a) return;
        activity = null;
        activityResumed = false;
        if (inShow || Capture.busy()) { // our own stale page being replaced: keep the new crop
            Log.i(TAG, "rear: page destroyed during " + (inShow ? "show" : "capture") + " -> keep bitmap");
            return;
        }
        if (overlay == null) {
            Log.i(TAG, "rear: page destroyed -> cleanup (timer, bitmap, capsule)");
            H.removeCallbacks(AUTO_CLOSE);
            deadline = 0;
            Capture.current = null;
            Capsule.pageGone("rear page destroyed");
        }
    }

    /** RearActivity.onStop on the rear (user swiped it away with the rear gesture, rear launcher on top, rear
     *  screen off): if it is still not visible after ~1 s, close it for real so no stale hidden page remains. */
    static void pageStopped(RearActivity a) {
        H.postDelayed(() -> {
            if (a != activity || !a.stopped || a.isFinishing() || a.isDestroyed()) return;
            if (inShow || !a.isOnRear()) {
                Log.i(TAG, "rear: page stopped but " + (inShow ? "show in progress" : "not confirmed on rear") + " -> leave it");
                return;
            }
            close("rear page not visible for >" + STOPPED_CLOSE_MS + " ms (swiped away?)");
        }, STOPPED_CLOSE_MS);
    }

    /** The page turned up somewhere it must not be (main display outside of show()): get rid of it. */
    static void pageMisplaced(RearActivity a, String where) {
        H.post(() -> {
            if (a.isFinishing() || a.isDestroyed()) return;
            Log.w(TAG, "rear: page found " + where + " outside of show() -> close (never show the QR on the main screen)");
            if (a == activity) close("page " + where);
            else a.finishAndRemoveTask();
        });
    }
}
