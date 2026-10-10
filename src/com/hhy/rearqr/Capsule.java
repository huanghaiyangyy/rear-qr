package com.hhy.rearqr;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Choreographer;
import android.view.ContextThemeWrapper;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Status "island" on the MAIN display (TYPE_APPLICATION_OVERLAY, display-0 window context) replacing the capture
 * toasts, modelled on the HyperOS dynamic island:
 *  (A) compact island — pure black pill ENCLOSING the front-camera hole (vertically centred on it, the hole sits in
 *      an empty middle gap): left = state icon (spinner / red !), right = short text (识别中 / 未找到 …).
 *      Used while working and as the first beat of an error.
 *  (B) expanded card — wide #E6111111 rounded card just below the status bar: green ✓ + 「背屏 87s」 (+ 「· 未解码」)
 *      and two round buttons ⟳ 刷新 / ✕ 关闭, shown directly while the rear page is up (one tap, no expand step).
 *      Errors briefly expand into the card with the full message, then collapse back into the island and vanish.
 * The black shape morphs between A and B (grows down/outward from the hole); content cross-fades.
 * One fixed window covers both shapes; it is NOT_TOUCHABLE except in the OK state, never focusable, and hidden
 * around every screencap. Anchored to the camera like the system island, so it is not draggable.
 * Public methods may be called from any thread; all view work happens on the main thread.
 */
final class Capsule {
    private static final String TAG = Capture.TAG;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final int NONE = 0, WORKING = 1, OK = 2, ERROR = 3;
    private static final String[] NAMES = {"none", "working", "ok", "error"};
    static final int C_OK = 0xFF34C759, C_ERR = 0xFFFF453A;
    private static final int ISLAND = 0xFF000000, CARD = 0xE6111111, BTN = 0xFF3A3A3A, BTN_PRESSED = 0xFF555555;
    private static final long ERROR_MS = 2500, ERROR_EXPAND_AFTER = 380, MORPH_MS = 340, IN_MS = 220, OUT_MS = 220;
    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private static Context app;
    private static WindowManager wm;
    private static WindowManager.LayoutParams lp;
    private static Island island; // window root
    private static Island exiting; // previous window still playing its exit animation
    private static WindowManager exitingWm;
    private static float density = 1f;

    private static int state = NONE;
    private static boolean decoded = true;
    private static String errorText = "";
    private static boolean hiddenForShot;
    private static String lastText = "";

    private static final Runnable TICK = Capsule::tick;
    private static final Runnable ERROR_END = Capsule::errorEnd;
    private static final Runnable ERROR_EXPAND = Capsule::errorExpand;

    private Capsule() {
    }

    // ------------------------------------------------------------ API (any thread)

    /** A capture started: compact island (spinner | 识别中). */
    static void working(Context c, String trigger) {
        final Context a = c.getApplicationContext();
        run(() -> {
            cancelTimers();
            if (!ensure(a)) return; // no toast while working (there never was one)
            setState(WORKING, "trigger=" + trigger);
        });
    }

    /** The crop is on the rear screen: expanded card with countdown and buttons. */
    static void success(Context c, boolean dec) {
        final Context a = c.getApplicationContext();
        run(() -> {
            decoded = dec;
            cancelTimers();
            if (!ensure(a)) {
                App.toastShort(a, dec ? "✓ 已贴到背屏" : "✓ 已贴到背屏（未能解码，请确认是二维码）");
                return;
            }
            setState(OK, "decoded=" + dec);
        });
    }

    /** Failure: island (! | 未找到), briefly expanded to the card with the full message, then back to the OK card
     *  if the rear page is still up, else collapse and vanish (~2.5 s). */
    static void fail(Context c, String msg) {
        final Context a = c.getApplicationContext();
        run(() -> {
            cancelTimers();
            if (!ensure(a)) {
                Log.w(TAG, "capsule: overlay unavailable -> toast fallback: " + msg);
                App.toast(a, msg);
                return;
            }
            errorText = msg;
            setState(ERROR, msg);
            H.postDelayed(ERROR_EXPAND, ERROR_EXPAND_AFTER);
            H.postDelayed(ERROR_END, ERROR_MS);
        });
    }

    /** Remove the capsule (rear page closed / gone). */
    static void dismiss(String why) {
        run(() -> remove(why, true));
    }

    /** Rear page vanished on its own: remove unless a capture is running (that capture will report its result). */
    static void pageGone(String why) {
        run(() -> {
            if (state == WORKING) {
                Log.i(TAG, "capsule: page gone (" + why + ") while working -> keep");
                return;
            }
            remove(why, true);
        });
    }

    /** Capture worker: hide the capsule and wait until the frame without it is on screen. Never call on main. */
    static void hideForShot() {
        if (Looper.myLooper() == Looper.getMainLooper()) return;
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] had = {false};
        H.post(() -> {
            if (exiting != null) { // don't let a fading-out capsule end up in the screenshot
                had[0] = true;
                dropExiting();
            }
            if (island != null && island.getVisibility() == View.VISIBLE) {
                had[0] = true;
                hiddenForShot = true;
                island.setVisibility(View.INVISIBLE); // ViewRootImpl relayouts the window as hidden
            }
            if (had[0]) {
                final Choreographer ch = Choreographer.getInstance();
                ch.postFrameCallback(t -> ch.postFrameCallback(t2 -> done.countDown()));
            } else {
                done.countDown();
            }
        });
        try {
            done.await(600, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        if (had[0]) {
            SystemClock.sleep(60); // let SurfaceFlinger latch the frame without us
            Log.i(TAG, "capsule: hidden for screencap");
        }
    }

    /** Capture worker: show it again after the screencap. */
    static void restoreAfterShot() {
        H.post(() -> {
            if (!hiddenForShot) return;
            hiddenForShot = false;
            if (island != null) {
                island.setVisibility(View.VISIBLE);
                Log.i(TAG, "capsule: restored after screencap");
            }
        });
    }

    /** On the main thread: now (keeps ordering with the caller, e.g. Rear.close then Capsule.fail); else post. */
    private static void run(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else H.post(r);
    }

    private static void cancelTimers() {
        H.removeCallbacks(ERROR_END);
        H.removeCallbacks(ERROR_EXPAND);
        H.removeCallbacks(TICK);
    }

    // ------------------------------------------------------------ state (main thread)

    private static void setState(int s, String detail) {
        int old = state;
        state = s;
        lastText = "";
        Island v = island;
        if (v == null) return;
        v.layoutGeometry();
        switch (s) {
            case WORKING:
                v.showCompact(false, "识别中");
                break;
            case ERROR:
                v.showCompact(true, shortError(errorText));
                break;
            default:
                v.showCard();
                updateCountdown();
        }
        setTouchable(s == OK);
        Log.i(TAG, "capsule: " + NAMES[old] + " -> " + NAMES[s] + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (s == OK) tick();
    }

    private static void errorExpand() {
        if (state != ERROR || island == null) return;
        String[] td = splitError(errorText);
        island.showErrorCard(td[0], td[1]);
        Log.i(TAG, "capsule: error expanded to card");
    }

    private static void errorEnd() {
        if (state != ERROR) return;
        if (Rear.isShowing()) {
            setState(OK, "error shown, rear page still up");
        } else if (island != null) { // collapse back into the island, then vanish
            island.showCompact(true, shortError(errorText));
            H.postDelayed(() -> {
                if (state == ERROR) remove("error shown", true);
            }, MORPH_MS + 120);
        } else {
            remove("error shown", false);
        }
    }

    /** Live countdown (one source of truth: Rear.deadline). */
    private static void tick() {
        H.removeCallbacks(TICK);
        if (state != OK || island == null) return;
        if (!Rear.isShowing()) { // safety net: nothing is on the rear any more
            Rear.close("capsule: rear page no longer showing");
            return;
        }
        updateCountdown();
        long dl = Rear.deadline;
        if (dl > 0) {
            long left = dl - SystemClock.uptimeMillis();
            H.postDelayed(TICK, Math.max(50, left % 1000 + 20));
        } else {
            H.postDelayed(TICK, 1000); // keep-open: no number, just keep the safety net running
        }
    }

    private static void updateCountdown() {
        if (island == null) return;
        long dl = Rear.deadline;
        int sec = dl > 0 ? (int) Math.max(0, (dl - SystemClock.uptimeMillis() + 999) / 1000) : -1;
        String t = (sec >= 0 ? "背屏 " + sec + "s" : "背屏中") + (decoded ? "" : " · 未解码");
        if (t.equals(lastText)) return;
        lastText = t;
        island.okLabel.setText(t);
    }

    /** Short island text for an error message. */
    static String shortError(String msg) {
        if (msg.contains("没找到")) return "未找到";
        if (msg.equals(RootShell.DENIED_MSG) || msg.contains("root")) return "无 root";
        if (msg.contains("背屏")) return "背屏失败";
        return "失败";
    }

    /** "没找到二维码（当前屏幕：x）" -> {"没找到二维码", "当前屏幕：x"}; "A，B" -> {"A", "B"}. */
    static String[] splitError(String msg) {
        int i = -1;
        for (char ch : new char[] {'（', '，', '：', '('}) {
            int k = msg.indexOf(ch);
            if (k > 0 && (i < 0 || k < i)) i = k;
        }
        if (i < 0 || i > 16) return new String[] {msg.length() > 16 ? "出错了" : msg, msg.length() > 16 ? msg : ""};
        String detail = msg.substring(i + 1).trim();
        if (detail.endsWith("）") || detail.endsWith(")")) detail = detail.substring(0, detail.length() - 1);
        return new String[] {msg.substring(0, i), detail};
    }

    private static void setTouchable(boolean t) {
        if (lp == null || wm == null || island == null) return;
        int f = t ? lp.flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                : lp.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (f == lp.flags) return;
        lp.flags = f;
        try {
            wm.updateViewLayout(island, lp);
        } catch (Exception ignored) {
        }
    }

    private static void remove(String why, boolean animate) {
        cancelTimers();
        boolean had = island != null;
        if (island != null) {
            final Island v = island;
            final WindowManager w = wm;
            if (animate && v.getVisibility() == View.VISIBLE && v.isAttachedToWindow()) {
                dropExiting();
                exiting = v;
                exitingWm = w;
                v.collapseAway(() -> {
                    if (exiting == v) dropExiting();
                });
            } else {
                removeWindow(w, v);
            }
        }
        island = null;
        lp = null;
        wm = null;
        hiddenForShot = false;
        int old = state;
        state = NONE;
        if (had || old != NONE) Log.i(TAG, "capsule: hide/remove (" + why + ", was " + NAMES[old] + ")");
    }

    private static void dropExiting() {
        if (exiting != null) removeWindow(exitingWm, exiting);
        exiting = null;
        exitingWm = null;
    }

    private static void removeWindow(WindowManager w, View v) {
        try {
            if (w != null) w.removeViewImmediate(v);
        } catch (Exception e) {
            Log.w(TAG, "capsule: removeView " + e);
        }
    }

    // ------------------------------------------------------------ window (main thread)

    /** Create and add the overlay window on display 0 if needed; false if that isn't possible. */
    private static boolean ensure(Context a) {
        if (island != null) return true;
        app = a;
        if (!Settings.canDrawOverlays(a)) {
            Log.w(TAG, "capsule: no SYSTEM_ALERT_WINDOW permission");
            return false;
        }
        try {
            Context wc = App.mainCtx(a).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            Context tc = new ContextThemeWrapper(wc, android.R.style.Theme_DeviceDefault);
            density = tc.getResources().getDisplayMetrics().density;
            wm = wc.getSystemService(WindowManager.class);
            lp = new WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT; // x/y = absolute screen position (we cover the camera hole)
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setFitInsetsTypes(0);
            lp.setTitle("RearQR-capsule");
            lp.windowAnimations = 0;
            island = new Island(tc);
            island.layoutGeometry(); // sets lp x/y/w/h
            wm.addView(island, lp);
            final Island v = island;
            v.post(() -> v.checkPlacement());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "capsule: cannot add overlay window: " + t);
            island = null;
            wm = null;
            lp = null;
            return false;
        }
    }

    private static int dp(float v) {
        return Math.round(v * density);
    }

    // ------------------------------------------------------------ the island view (window root)

    /**
     * Draws the black shape (animated RectF, corner = half height, colour/shadow interpolated) and lays its content
     * out at absolute window positions. All geometry in window coordinates; the window covers island + card.
     */
    static final class Island extends FrameLayout {
        private final Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF cur = new RectF(), from = new RectF(), to = new RectF();
        private int colFrom = ISLAND, colTo = ISLAND, col = ISLAND;
        private float shFrom, shTo, sh;
        private ValueAnimator anim;
        private boolean shown;

        // geometry (window coords)
        final Rect hole = new Rect(); // camera hole (window coords); empty-ish if there's no cutout
        private boolean realHole;
        private final RectF card = new RectF();
        private int islandH, gap;

        // content
        private final FrameLayout cIcon; // compact: spinner or glyph
        private final ProgressBar spinner;
        private final Glyph cGlyph;
        private final TextView cText;
        private final LinearLayout okRow, errRow;
        final TextView okLabel;
        private final TextView errTitle, errDetail;

        Island(Context c) {
            super(c);
            setWillNotDraw(false);
            setClipChildren(false);
            cIcon = new FrameLayout(c);
            spinner = new ProgressBar(c, null, android.R.attr.progressBarStyleSmall);
            spinner.setIndeterminate(true);
            spinner.setIndeterminateTintList(ColorStateList.valueOf(Color.WHITE));
            cIcon.addView(spinner, new FrameLayout.LayoutParams(-1, -1));
            cGlyph = new Glyph(c, Glyph.ERROR, false);
            cIcon.addView(cGlyph, new FrameLayout.LayoutParams(-1, -1));
            addView(cIcon, new FrameLayout.LayoutParams(dp(16), dp(16)));
            cText = text(c, 13, Color.WHITE);
            cText.setGravity(Gravity.CENTER);
            addView(cText, new FrameLayout.LayoutParams(-2, -2));

            okRow = row(c);
            okRow.addView(new Glyph(c, Glyph.CHECK, false), new LinearLayout.LayoutParams(dp(28), dp(28)));
            okLabel = text(c, 17, Color.WHITE);
            okLabel.setFontFeatureSettings("tnum"); // tabular digits: the countdown never jitters
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, -2, 1);
            ll.leftMargin = dp(12);
            okRow.addView(okLabel, ll);
            Glyph refresh = new Glyph(c, Glyph.REFRESH, true);
            refresh.setContentDescription("刷新");
            refresh.setOnClickListener(x -> {
                Log.i(TAG, "capsule: 刷新 tapped");
                Rear.recapture(app, "capsule-refresh");
            });
            Glyph close = new Glyph(c, Glyph.CLOSE, true);
            close.setContentDescription("关闭");
            close.setOnClickListener(x -> {
                Log.i(TAG, "capsule: 关闭 tapped");
                Rear.close("capsule 关闭");
            });
            okRow.addView(refresh, new LinearLayout.LayoutParams(dp(46), dp(46))); // square -> perfect circle
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(46), dp(46));
            cl.leftMargin = dp(12);
            okRow.addView(close, cl);
            addView(okRow, new FrameLayout.LayoutParams(1, 1));

            errRow = row(c);
            errRow.addView(new Glyph(c, Glyph.ERROR, false), new LinearLayout.LayoutParams(dp(28), dp(28)));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            errTitle = text(c, 16, Color.WHITE);
            errDetail = text(c, 13, 0xB3FFFFFF);
            errDetail.setTypeface(Typeface.DEFAULT);
            errDetail.setMaxLines(2);
            col.addView(errTitle);
            col.addView(errDetail);
            LinearLayout.LayoutParams cll = new LinearLayout.LayoutParams(0, -2, 1);
            cll.leftMargin = dp(12);
            errRow.addView(col, cll);
            addView(errRow, new FrameLayout.LayoutParams(1, 1));

            for (View v : new View[] {cIcon, cText, okRow, errRow}) {
                v.setVisibility(GONE);
                v.setAlpha(0f);
            }
        }

        private static TextView text(Context c, int sp, int color) {
            TextView t = new TextView(c);
            t.setTextColor(color);
            t.setTextSize(sp);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setIncludeFontPadding(false);
            t.setSingleLine(false);
            t.setMaxLines(1);
            t.setEllipsize(TextUtils.TruncateAt.END);
            return t;
        }

        private LinearLayout row(Context c) {
            LinearLayout r = new LinearLayout(c);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(22), 0, dp(17), 0);
            return r;
        }

        @Override
        protected void onConfigurationChanged(Configuration c) {
            super.onConfigurationChanged(c);
            post(() -> { // rotation etc.: re-anchor to the (moved) camera hole
                if (island != this) return;
                layoutGeometry();
                try {
                    wm.updateViewLayout(this, lp);
                } catch (Exception ignored) {
                }
                setState(state, "re-layout after configuration change");
            });
        }

        /** Compute hole / card geometry and the window rect covering both (+ shadow margin). */
        void layoutGeometry() {
            Rect scr = new Rect(0, 0, 1220, 2656);
            Rect h = null;
            int sb = dp(36);
            try {
                scr = wm.getCurrentWindowMetrics().getBounds();
                WindowInsets wi = wm.getCurrentWindowMetrics().getWindowInsets();
                sb = Math.max(wi.getInsets(WindowInsets.Type.statusBars()).top, dp(24));
                DisplayCutout dc = wi.getDisplayCutout();
                if (dc != null) {
                    Rect r = dc.getBoundingRectTop();
                    if (r != null && !r.isEmpty()) h = new Rect(r);
                }
            } catch (Throwable t) {
                Log.w(TAG, "capsule: window metrics " + t);
            }
            realHole = h != null;
            if (h == null) { // no cutout: island at the top centre, just below the status bar
                int cy = sb + dp(4) + dp(17);
                h = new Rect(scr.centerX() - dp(3), cy - dp(3), scr.centerX() + dp(3), cy + dp(3));
            }
            int d = Math.max(h.width(), h.height());
            islandH = Math.max(dp(32), Math.min(dp(40), d + dp(22)));
            gap = (realHole ? h.width() : 0) + dp(12); // empty middle: hole + ≥6dp each side
            float cw = Math.max(dp(300), Math.min(scr.width() - dp(24), scr.width() * 0.81f));
            float ch = dp(80);
            float islandBottom = h.centerY() + islandH / 2f;
            float top = Math.max(sb, islandBottom) + dp(8);
            RectF cardS = new RectF(h.centerX() - cw / 2f, top, h.centerX() + cw / 2f, top + ch);
            if (cardS.left < dp(12)) cardS.offset(dp(12) - cardS.left, 0);
            if (cardS.right > scr.width() - dp(12)) cardS.offset(scr.width() - dp(12) - cardS.right, 0);
            int pad = dp(14);
            int wl = (int) Math.floor(Math.min(cardS.left, h.centerX() - dp(110))) - pad;
            int wt = Math.max(0, (int) (h.centerY() - islandH / 2f) - pad);
            int wr = (int) Math.ceil(Math.max(cardS.right, h.centerX() + dp(110))) + pad;
            int wb = (int) Math.ceil(cardS.bottom) + pad + dp(6);
            lp.x = wl;
            lp.y = wt;
            lp.width = wr - wl;
            lp.height = wb - wt;
            hole.set(h.left - wl, h.top - wt, h.right - wl, h.bottom - wt);
            card.set(cardS.left - wl, cardS.top - wt, cardS.right - wl, cardS.bottom - wt);
            place(okRow, card);
            place(errRow, card);
            Log.i(TAG, "capsule: geometry hole=" + h + (realHole ? "" : " (no cutout: fallback)") + " island h=" + islandH
                    + " card=" + cardS + " window=" + wl + "," + wt + " " + lp.width + "x" + lp.height);
        }

        /** After the first layout: did the system really put us over the camera (above / into the status bar)? */
        void checkPlacement() {
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            if (lp != null && (Math.abs(loc[0] - lp.x) > 4 || Math.abs(loc[1] - lp.y) > 4)) {
                Log.w(TAG, "capsule: window placed at " + loc[0] + "," + loc[1] + " instead of " + lp.x + "," + lp.y
                        + " (ROM keeps overlays out of the status bar / cutout?)");
            } else {
                Log.i(TAG, "capsule: window placed at " + loc[0] + "," + loc[1] + " as requested (drawn under the status bar"
                        + " layer, which is transparent around the camera)");
            }
        }

        private static void place(View v, RectF r) {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
            p.width = Math.round(r.width());
            p.height = Math.round(r.height());
            p.leftMargin = Math.round(r.left);
            p.topMargin = Math.round(r.top);
            p.gravity = Gravity.TOP | Gravity.LEFT;
            v.setLayoutParams(p);
        }

        /** Island rect for the given short text: symmetric segments so the hole stays exactly in the middle. */
        private RectF islandRect(String t) {
            float tw = cText.getPaint().measureText(t);
            float seg = Math.max(dp(16), tw) + dp(11) * 2;
            float cx = hole.exactCenterX(), cy = hole.exactCenterY();
            return new RectF(cx - gap / 2f - seg, cy - islandH / 2f, cx + gap / 2f + seg, cy + islandH / 2f);
        }

        /** (A) compact island: icon | hole | text. */
        void showCompact(boolean error, String t) {
            RectF r = islandRect(t);
            float seg = (r.width() - gap) / 2f;
            int is = dp(16);
            FrameLayout.LayoutParams ip = (FrameLayout.LayoutParams) cIcon.getLayoutParams();
            ip.width = is;
            ip.height = is;
            ip.gravity = Gravity.TOP | Gravity.LEFT;
            ip.leftMargin = Math.round(r.left + (seg - is) / 2f);
            ip.topMargin = Math.round(r.centerY() - is / 2f);
            cIcon.setLayoutParams(ip);
            spinner.setVisibility(error ? GONE : VISIBLE);
            cGlyph.setVisibility(error ? VISIBLE : GONE);
            cText.setText(t);
            place(cText, new RectF(r.right - seg, r.top, r.right, r.bottom));
            morph(r, ISLAND, 0f, shown ? MORPH_MS : IN_MS, cIcon, cText);
        }

        /** (B) expanded card with countdown and the two buttons. */
        void showCard() {
            morph(card, CARD, 1f, MORPH_MS, okRow);
        }

        /** (B) for an error: full message. */
        void showErrorCard(String title, String detail) {
            errTitle.setText(title);
            errDetail.setText(detail);
            errDetail.setVisibility(detail.isEmpty() ? GONE : VISIBLE);
            morph(card, CARD, 1f, MORPH_MS, errRow);
        }

        /** Shrink back into the camera hole, then run end (window removal). */
        void collapseAway(Runnable end) {
            float cx = hole.exactCenterX(), cy = hole.exactCenterY(), r = Math.max(hole.width(), hole.height()) / 2f;
            morph(new RectF(cx - r, cy - r, cx + r, cy + r), ISLAND, 0f, OUT_MS, (View[]) null);
            postDelayed(end, OUT_MS + 30);
        }

        /** Animate the black shape to target (from the hole on first show) and cross-fade to the given content. */
        private void morph(RectF target, int color, float shadow, long dur, View... content) {
            if (anim != null) anim.cancel();
            if (!shown) { // first show: grow out of the camera hole
                float cx = hole.exactCenterX(), cy = hole.exactCenterY(), r = Math.max(hole.width(), hole.height()) / 2f;
                cur.set(cx - r, cy - r, cx + r, cy + r);
                col = ISLAND;
                sh = 0f;
                shown = true;
            }
            from.set(cur);
            to.set(target);
            colFrom = col;
            colTo = color;
            shFrom = sh;
            shTo = shadow;
            for (View v : new View[] {cIcon, cText, okRow, errRow}) {
                boolean keep = false;
                if (content != null) for (View x : content) keep |= x == v;
                if (keep) {
                    if (v.getVisibility() != VISIBLE) {
                        v.setAlpha(0f);
                        v.setVisibility(VISIBLE);
                    }
                    v.animate().alpha(1f).setStartDelay(dur * 45 / 100).setDuration(dur * 55 / 100).start();
                } else if (v.getVisibility() == VISIBLE) {
                    v.animate().alpha(0f).setStartDelay(0).setDuration(Math.min(120, dur / 2))
                            .withEndAction(() -> v.setVisibility(GONE)).start();
                }
            }
            final ArgbEvaluator ev = new ArgbEvaluator();
            ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(dur);
            va.setInterpolator(EASE);
            va.addUpdateListener(a -> {
                float f = (Float) a.getAnimatedValue();
                cur.set(from.left + (to.left - from.left) * f, from.top + (to.top - from.top) * f,
                        from.right + (to.right - from.right) * f, from.bottom + (to.bottom - from.bottom) * f);
                col = (Integer) ev.evaluate(f, colFrom, colTo);
                sh = shFrom + (shTo - shFrom) * f;
                invalidate();
            });
            va.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator a) {
                    if (anim == va) anim = null;
                }
            });
            anim = va;
            va.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (cur.isEmpty()) return;
            blob.setColor(col);
            if (sh > 0.01f) blob.setShadowLayer(dp(12), 0, dp(3), Color.argb(Math.round(90 * sh), 0, 0, 0));
            else blob.clearShadowLayer();
            float r = cur.height() / 2f; // corner = half height (pill / rounded card like the system)
            c.drawRoundRect(cur, r, r, blob);
        }
    }

    // ------------------------------------------------------------ vector-like glyphs

    /** Canvas-drawn icons: CHECK / ERROR (state dot) and REFRESH / CLOSE (round buttons, always perfect circles). */
    static final class Glyph extends View {
        static final int CHECK = 0, ERROR = 1, REFRESH = 2, CLOSE = 3;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF oval = new RectF();
        private final boolean button;
        private final int kind;

        Glyph(Context c, int kind, boolean button) {
            super(c);
            this.kind = kind;
            this.button = button;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(Color.WHITE);
            fill.setStyle(Paint.Style.FILL);
            if (button) {
                setClickable(true);
                setFocusable(false);
            }
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate(); // pressed highlight
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
            float R = Math.min(w, h) / 2f; // circle radius from the smaller side: never an ellipse
            float u = R / 10f; // glyph unit (20 units across the disc)
            fill.setColor(button ? (isPressed() ? BTN_PRESSED : BTN) : (kind == ERROR ? C_ERR : C_OK));
            c.drawCircle(cx, cy, R, fill);
            fill.setColor(Color.WHITE);
            path.reset();
            switch (kind) {
                case CHECK:
                    stroke.setStrokeWidth(1.9f * u);
                    path.moveTo(cx - 4.4f * u, cy + 0.2f * u);
                    path.lineTo(cx - 1.4f * u, cy + 3.2f * u);
                    path.lineTo(cx + 4.6f * u, cy - 3.0f * u);
                    c.drawPath(path, stroke);
                    break;
                case ERROR:
                    stroke.setStrokeWidth(2.0f * u);
                    c.drawLine(cx, cy - 4.8f * u, cx, cy + 1.0f * u, stroke);
                    c.drawCircle(cx, cy + 4.4f * u, 1.2f * u, fill);
                    break;
                case REFRESH:
                    drawRefresh(c, cx, cy, u);
                    break;
                default: { // CLOSE
                    stroke.setStrokeWidth(1.0f * u);
                    float r = 3.6f * u;
                    c.drawLine(cx - r, cy - r, cx + r, cy + r, stroke);
                    c.drawLine(cx - r, cy + r, cx + r, cy - r, stroke);
                }
            }
        }

        /** Material-style refresh: a perfectly circular 300° arc (uniform stroke, round caps) with a small filled
         *  arrowhead tangent at its end (top right, pointing clockwise into the gap). */
        private void drawRefresh(Canvas c, float cx, float cy, float u) {
            float r = 4.6f * u, sw = 1.0f * u;
            stroke.setStrokeWidth(sw);
            oval.set(cx - r, cy - r, cx + r, cy + r);
            float end = -45f, sweep = 300f; // clockwise from 15° round to -45°
            c.drawArc(oval, end - sweep, sweep, false, stroke);
            double a = Math.toRadians(end);
            float ex = cx + (float) (r * Math.cos(a)), ey = cy + (float) (r * Math.sin(a));
            float tx = (float) -Math.sin(a), ty = (float) Math.cos(a); // clockwise tangent
            float nx = (float) Math.cos(a), ny = (float) Math.sin(a); // radial normal
            float len = 3.0f * u, half = 2.1f * u;
            path.moveTo(ex + tx * len * 0.75f, ey + ty * len * 0.75f); // tip, ahead of the arc end
            path.lineTo(ex - tx * len * 0.25f + nx * half, ey - ty * len * 0.25f + ny * half);
            path.lineTo(ex - tx * len * 0.25f - nx * half, ey - ty * len * 0.25f - ny * half);
            path.close();
            c.drawPath(path, fill);
        }
    }
}
