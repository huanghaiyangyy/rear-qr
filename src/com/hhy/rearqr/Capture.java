package com.hhy.rearqr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** capture main screen (root screencap -> memory) -> locate QR -> crop -> Rear.show(). */
final class Capture {
    static final String TAG = "RearQR";
    /** The crop currently shown on the rear screen (in-process hand-over; never written to disk). */
    static volatile Bitmap current;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    /** uptime of the last start(); lets the QS tile detect a swallowed trampoline launch. */
    static volatile long lastTriggerAt;
    private static volatile String mainPhysId; // SurfaceFlinger id of the main display; "" = unknown

    private Capture() {
    }

    /**
     * @param trigger  tile / intent / test / rear-tap (logged)
     * @param delayMs  wait before checking the focused window / taking the screenshot
     * @param collapse collapse the notification shade / control center via root and wait until it is gone
     * @param done     optional, called on the main thread with true if a QR was shown
     * @return false if another capture was already running (ignored; done is not called)
     */
    static boolean start(Context ctx, String trigger, long delayMs, boolean collapse, Consumer<Boolean> done) {
        final Context c = ctx.getApplicationContext();
        lastTriggerAt = android.os.SystemClock.uptimeMillis();
        Log.i(TAG, "trigger=" + trigger + " delay=" + delayMs + " collapse=" + collapse);
        if (!BUSY.compareAndSet(false, true)) {
            Log.i(TAG, "busy, ignored");
            return false;
        }
        Capsule.working(c, trigger);
        EXEC.execute(() -> {
            boolean ok = false;
            try {
                ok = run(c, delayMs, collapse);
            } catch (Throwable t) {
                Log.e(TAG, "capture failed", t);
                Capsule.restoreAfterShot();
                Capsule.fail(c, "截屏/识别失败：" + t.getMessage());
            } finally {
                BUSY.set(false);
                final boolean r = ok;
                if (done != null) App.ui(() -> done.accept(r));
            }
        });
        return true;
    }

    static boolean busy() {
        return BUSY.get();
    }

    /** Root command with its exit code / output logged. */
    static RootShell.Result su(RootShell sh, String cmd, long timeoutMs) {
        return su(sh, cmd, timeoutMs, 400);
    }

    static RootShell.Result su(RootShell sh, String cmd, long timeoutMs, int maxLog) {
        long t = SystemClock.uptimeMillis();
        RootShell.Result r = sh.exec(cmd, timeoutMs);
        String out = r.text().replace('\n', '|'), err = r.err.trim().replace('\n', '|');
        if (out.length() > maxLog) out = out.substring(0, maxLog) + "…";
        Log.i(TAG, "su[" + cmd + "] rc=" + r.code + " " + (SystemClock.uptimeMillis() - t) + "ms out=" + out
                + (err.isEmpty() ? "" : " err=" + err));
        return r;
    }

    static String focusNow(RootShell sh) {
        return focus(sh);
    }

    private static String focus(RootShell sh) {
        return Focus.mainFocus(sh.exec(Focus.CMD, 5000).text());
    }

    private static boolean run(Context c, long delayMs, boolean collapse) throws IOException {
        RootShell sh = App.shell();
        RootShell.Result r = collapse ? su(sh, "cmd statusbar collapse", 70000) : sh.exec("true", 70000);
        if (r.code == RootShell.EACCES) {
            Capsule.fail(c, RootShell.DENIED_MSG);
            App.vibrate(c);
            return false;
        }
        if (delayMs > 0) SystemClock.sleep(delayMs);
        long t = SystemClock.uptimeMillis();
        String focus = focus(sh);
        if (collapse) { // wait until the shade / HyperOS control center no longer has focus
            boolean sawShade = false;
            while (Focus.isShade(focus) && SystemClock.uptimeMillis() - t < 1200) {
                sawShade = true;
                if (SystemClock.uptimeMillis() - t > 500) sh.exec("cmd statusbar collapse", 5000);
                SystemClock.sleep(100);
                focus = focus(sh);
            }
            if (sawShade) SystemClock.sleep(250); // let the collapse animation finish
        }
        Log.i(TAG, "main focus before capture: " + focus + " (waited " + (SystemClock.uptimeMillis() - t) + " ms)"
                + (Focus.pkg(focus).equals(App.PKG) ? " [own transparent CaptureActivity, ignored]" : ""));

        long t0 = SystemClock.uptimeMillis();
        int[] size = new int[2];
        int[] px;
        Capsule.hideForShot(); // the status capsule must never be in the screenshot
        try {
            px = grab(sh, size);
        } finally {
            Capsule.restoreAfterShot();
        }
        int w = size[0], h = size[1];
        long t1 = SystemClock.uptimeMillis();
        QrLocator.Found f = QrLocator.locate(px, w, h);
        Log.i(TAG, "screencap " + w + "x" + h + " " + (t1 - t0) + " ms, locate " + (SystemClock.uptimeMillis() - t1)
                + " ms -> " + (f == null ? "none" : f.qr + " decoded=" + f.decoded));
        if (f == null) {
            Capsule.fail(c, "没找到二维码（当前屏幕：" + screenPkg(sh, focus) + "）");
            App.vibrate(c);
            return false;
        }
        QrLocator.Box box = QrLocator.cropBox(f, App.marginPct(c) / 100.0);
        int[] crop = QrLocator.crop(px, w, h, f, box);
        px = null; // drop the full screenshot right away
        current = Bitmap.createBitmap(crop, box.width(), box.height(), Bitmap.Config.ARGB_8888);
        boolean ok = Rear.show(c, sh);
        if (ok) Capsule.success(c, f.decoded);
        return ok;
    }

    /** Package shown on the main screen; if the focus is our own (transparent CaptureActivity) or null, the top
     *  visible non-own task from `am stack list`. */
    static String screenPkg(RootShell sh, String focus) {
        String p = Focus.pkg(focus);
        if (!p.equals(App.PKG) && !p.isEmpty() && !p.equals("null")) return p;
        String top = Stacks.topApp(sh.exec("am stack list", 8000).text(), 0, App.PKG);
        Log.i(TAG, "focus is " + (p.isEmpty() ? "empty" : p) + " -> top visible app on display 0: " + (top.isEmpty() ? "?" : top));
        return top.isEmpty() ? p : top;
    }

    /** Main-display screenshot as ARGB pixels; raw format first, PNG as fallback. size = {w, h}. */
    private static int[] grab(RootShell sh, int[] size) throws IOException {
        String id = mainPhysId(sh);
        String d = id.isEmpty() ? "" : " -d " + id;
        String[] cmds = d.isEmpty() ? new String[] {"screencap", "screencap -p"}
                : new String[] {"screencap" + d, "screencap" + d + " -p", "screencap", "screencap -p"};
        String err = "";
        for (String cmd : cmds) {
            RootShell.Result r = sh.exec(cmd, 20000);
            if (r.code == RootShell.EACCES) throw new IOException(RootShell.DENIED_MSG);
            if (r.code != 0 || r.out.length == 0) {
                err = r.err.trim();
                Log.w(TAG, cmd + " failed rc=" + r.code + " " + err);
                continue;
            }
            try {
                if (!cmd.endsWith("-p")) {
                    ScreenRaw raw = ScreenRaw.parse(r.out, r.out.length, Build.VERSION.SDK_INT);
                    size[0] = raw.width;
                    size[1] = raw.height;
                    return raw.toArgb(r.out);
                }
                Bitmap b = BitmapFactory.decodeByteArray(r.out, 0, r.out.length);
                if (b == null) throw new IOException("PNG 解码失败");
                size[0] = b.getWidth();
                size[1] = b.getHeight();
                int[] px = new int[size[0] * size[1]];
                b.getPixels(px, 0, size[0], 0, 0, size[0], size[1]);
                b.recycle();
                return px;
            } catch (IOException e) {
                err = e.getMessage();
                Log.w(TAG, cmd + ": " + err);
            }
        }
        throw new IOException("screencap 失败 " + err);
    }

    /** Main display's SurfaceFlinger id ("HWC display 0"), so screencap never grabs the rear screen. */
    private static String mainPhysId(RootShell sh) {
        String id = mainPhysId;
        if (id != null) return id;
        RootShell.Result r = sh.exec("dumpsys SurfaceFlinger --display-id", 10000);
        Matcher m = Pattern.compile("Display (\\d+) \\(HWC display 0\\)").matcher(r.text());
        id = m.find() ? m.group(1) : "";
        Log.i(TAG, "main display SurfaceFlinger id: " + (id.isEmpty() ? "(unknown, no -d)" : id));
        mainPhysId = id;
        return id;
    }
}
