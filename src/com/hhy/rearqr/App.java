package com.hhy.rearqr;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Display;
import android.widget.Toast;

public final class App extends Application {
    public static final String PKG = "com.hhy.rearqr";
    public static final int REAR_FALLBACK_ID = 1;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static RootShell shell;

    public static synchronized RootShell shell() {
        if (shell == null) shell = new RootShell(new String[] {"su"}, true);
        return shell;
    }

    public static void ui(Runnable r) {
        MAIN.post(r);
    }

    /** Context bound to the main display, so toasts never land on the rear screen. */
    static Context mainCtx(Context c) {
        Context app = c.getApplicationContext();
        try {
            DisplayManager dm = app.getSystemService(DisplayManager.class);
            Display d = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (d != null) return app.createDisplayContext(d);
        } catch (Throwable ignored) {
        }
        return app;
    }

    public static void toastShort(Context c, String msg) {
        ui(() -> Toast.makeText(mainCtx(c), msg, Toast.LENGTH_SHORT).show());
    }

    public static void toast(Context c, String msg) {
        ui(() -> Toast.makeText(mainCtx(c), msg, Toast.LENGTH_LONG).show());
    }

    private static volatile Boolean whitelisted; // last applied state in this process

    /** Root: add to / remove from the doze whitelist to match the 「后台常驻」 switch (once per change). */
    static void rootWhitelist(Context c, RootShell sh) {
        boolean want = resident(c);
        if (whitelisted != null && whitelisted == want) return;
        whitelisted = want;
        Capture.su(sh, "dumpsys deviceidle whitelist " + (want ? "+" : "-") + PKG, 5000);
    }

    @SuppressWarnings("deprecation")
    public static void vibrate(Context c) {
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {
        }
    }

    /** 「5 秒后截取」: one toast now, capture after n seconds (Handler on the main looper survives backgrounding). */
    public static void countdownCapture(Context c, int seconds) {
        final Context app = c.getApplicationContext();
        Toast.makeText(app, seconds + " 秒后截取主屏，请现在切换到二维码页面", Toast.LENGTH_LONG).show();
        MAIN.postDelayed(() -> {
            vibrate(app);
            Capture.start(app, "test", 0, true, null);
        }, seconds * 1000L);
    }

    // ------------------------------------------------------------ settings

    public static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("settings", MODE_PRIVATE);
    }

    public static int timeoutSec(Context c) {
        return prefs(c).getInt("timeout", 90);
    }

    public static int marginPct(Context c) {
        return prefs(c).getInt("margin", 12);
    }

    /** 「后台常驻」: foreground service + doze whitelist so HyperOS never freezes the app. Default off. */
    public static boolean resident(Context c) {
        return prefs(c).getBoolean("resident", false);
    }

    public static boolean keepOpen(Context c) {
        return prefs(c).getBoolean("keep", false);
    }

    // ------------------------------------------------------------ rear display

    /** The rear display: a non-default display that is ~904x572 or FLAG_PRESENTATION; null if none. */
    public static Display rearDisplay(Context c) {
        DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        Display pres = null;
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY || !d.isValid()) continue;
            Display.Mode m = d.getMode();
            int a = Math.max(m.getPhysicalWidth(), m.getPhysicalHeight()), b = Math.min(m.getPhysicalWidth(), m.getPhysicalHeight());
            if (Math.abs(a - 904) <= 8 && Math.abs(b - 572) <= 8) return d;
            if (pres == null && (d.getFlags() & Display.FLAG_PRESENTATION) != 0) pres = d;
        }
        return pres;
    }

    public static int rearDisplayId(Context c) {
        try {
            Display d = rearDisplay(c);
            if (d != null) return d.getDisplayId();
        } catch (Exception ignored) {
        }
        return REAR_FALLBACK_ID;
    }
}
