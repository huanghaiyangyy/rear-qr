package com.hhy.rearqr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Rear-screen content shared by RearActivity and the overlay fallback: the crop drawn as large as possible
 * beside the camera cutout (nearest neighbour when enlarging), tap = re-capture, long-press / ✕ = close.
 */
final class RearView extends FrameLayout {
    /** Rear screen cutout (camera) on the left, used if the window reports no cutout inset. */
    static final int FALLBACK_LEFT_INSET = 296;

    private final QrView qr;
    /** Set once the window actually drew a frame (used to verify the rear overlay really exists). */
    volatile boolean drawn;

    RearView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        qr = new QrView(c);
        addView(qr, new LayoutParams(-1, -1));
        TextView x = new TextView(c);
        x.setText("✕");
        x.setTextSize(14);
        x.setTextColor(0xFF9E9E9E);
        x.setPadding(20, 4, 16, 4);
        x.setOnClickListener(v -> Rear.close("✕"));
        addView(x, new LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        GestureDetector gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                Rear.recapture(c);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                Rear.close("long-press");
            }
        });
        qr.setOnTouchListener((v, e) -> gd.onTouchEvent(e));
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        drawn = true;
    }

    boolean hasBitmap() {
        return qr.bm != null;
    }

    void setBitmap(Bitmap b) {
        qr.bm = b;
        qr.setAlpha(1f);
        qr.invalidate();
    }

    void setBusy(boolean busy) {
        qr.setAlpha(busy ? 0.35f : 1f);
    }

    static final class QrView extends View {
        private final Paint paint = new Paint();
        private final Rect dst = new Rect();
        Bitmap bm;

        QrView(Context c) {
            super(c);
            paint.setAntiAlias(false);
            paint.setDither(false);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(Color.BLACK);
            if (bm == null) return;
            int l = 0, t = 0, r = 0, b = 0;
            WindowInsets wi = getRootWindowInsets();
            if (wi != null) {
                Insets i = wi.getInsets(WindowInsets.Type.displayCutout());
                l = i.left;
                t = i.top;
                r = i.right;
                b = i.bottom;
            }
            if (l == 0 && t == 0 && r == 0 && b == 0 && getWidth() > getHeight()) {
                l = FALLBACK_LEFT_INSET * getWidth() / 904; // rear screen: camera on the left
            }
            int aw = getWidth() - l - r, ah = getHeight() - t - b, side = Math.min(aw, ah);
            if (side <= 0) return;
            int x = l + (aw - side) / 2, y = t + (ah - side) / 2;
            dst.set(x, y, x + side, y + side);
            paint.setFilterBitmap(side < bm.getWidth()); // enlarging: nearest neighbour keeps modules crisp
            c.drawBitmap(bm, null, dst, paint);
        }
    }
}
