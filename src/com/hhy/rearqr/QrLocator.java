package com.hhy.rearqr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.Binarizer;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.DetectorResult;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import com.google.zxing.multi.qrcode.detector.MultiDetector;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.detector.FinderPattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the largest QR code in an ARGB screenshot and crops its original pixels onto a white square.
 * Pure Java + ZXing core (no android.*), so it is unit-tested on the desktop JVM.
 *
 * Search order: full resolution then 1/2 scale, each with HybridBinarizer then GlobalHistogramBinarizer,
 * using QRCodeMultiReader then GenericMultipleBarcodeReader(QRCodeReader). If nothing decodes (e.g. a
 * big logo in the middle), fall back to finder-pattern detection only (MultiDetector).
 */
public final class QrLocator {

    /** Axis-aligned rectangle [left, right) x [top, bottom) in screenshot pixels. */
    public static final class Box {
        public final int left, top, right, bottom;

        public Box(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public int width() {
            return right - left;
        }

        public int height() {
            return bottom - top;
        }

        public boolean contains(Box o) {
            return o.left >= left && o.top >= top && o.right <= right && o.bottom <= bottom;
        }

        @Override
        public String toString() {
            return "[" + left + "," + top + " - " + right + "," + bottom + "]";
        }
    }

    public static final class Found {
        public final Box qr;       // estimated outer edge of the QR symbol (finder pattern corners)
        public final float module; // module size in pixels
        public final boolean decoded;

        Found(Box qr, float module, boolean decoded) {
            this.qr = qr;
            this.module = module;
            this.decoded = decoded;
        }
    }

    private static final Map<DecodeHintType, Object> HINTS = new EnumMap<>(DecodeHintType.class);

    static {
        HINTS.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        HINTS.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
    }

    private QrLocator() {
    }

    /** Largest QR code in the image, or null. */
    public static Found locate(int[] argb, int w, int h) {
        return largest(locateAll(argb, w, h));
    }

    /** All QR codes from the first search attempt that finds any (possibly empty). */
    static List<Found> locateAll(int[] argb, int w, int h) {
        byte[] lum = luminance(argb);
        for (int pass = 0; pass < 2; pass++) { // pass 0: decode, pass 1: detect-only fallback
            for (int scale = 1; scale <= 2; scale++) {
                byte[] l = scale == 1 ? lum : halve(lum, w, h);
                int sw = w / scale, sh = h / scale;
                LuminanceSource src = new PlanarYUVLuminanceSource(l, sw, sh, 0, 0, sw, sh, false);
                for (int b = 0; b < 2; b++) {
                    Binarizer bin = b == 0 ? new HybridBinarizer(src) : new GlobalHistogramBinarizer(src);
                    List<Found> found = pass == 0 ? decode(new BinaryBitmap(bin), scale)
                            : detect(new BinaryBitmap(bin), scale);
                    if (!found.isEmpty()) return found;
                }
            }
        }
        return new ArrayList<>();
    }

    static Found largest(List<Found> list) {
        Found best = null;
        for (Found f : list) {
            if (best == null || (long) f.qr.width() * f.qr.height() > (long) best.qr.width() * best.qr.height()) best = f;
        }
        return best;
    }

    private static List<Found> decode(BinaryBitmap bb, int scale) {
        List<Found> out = new ArrayList<>();
        Result[] rs = null;
        try {
            rs = new QRCodeMultiReader().decodeMultiple(bb, HINTS);
        } catch (Exception ignored) {
        }
        if (rs == null || rs.length == 0) {
            try {
                rs = new GenericMultipleBarcodeReader(new QRCodeReader()).decodeMultiple(bb, HINTS);
            } catch (Exception ignored) {
            }
        }
        if (rs != null) {
            for (Result r : rs) {
                Found f = fromPoints(r.getResultPoints(), 0, scale, true);
                if (f != null) out.add(f);
            }
        }
        return out;
    }

    private static List<Found> detect(BinaryBitmap bb, int scale) {
        List<Found> out = new ArrayList<>();
        try {
            for (DetectorResult d : new MultiDetector(bb.getBlackMatrix()).detectMulti(HINTS)) {
                Found f = fromPoints(d.getPoints(), d.getBits().getHeight(), scale, false);
                if (f != null) out.add(f);
            }
        } catch (NotFoundException ignored) {
        }
        return out;
    }

    /**
     * QR result points are [bottomLeft, topLeft, topRight, (alignment)] finder centres. The symbol corners
     * lie 3.5 modules outward from each finder centre along the code's own axes (handles rotation).
     */
    static Found fromPoints(ResultPoint[] p, int dimension, int scale, boolean decoded) {
        if (p == null || p.length < 3) return null;
        float blx = p[0].getX(), bly = p[0].getY(), tlx = p[1].getX(), tly = p[1].getY();
        float trx = p[2].getX(), try_ = p[2].getY();
        float ux = trx - tlx, uy = try_ - tly, vx = blx - tlx, vy = bly - tly;
        float lu = (float) Math.hypot(ux, uy), lv = (float) Math.hypot(vx, vy);
        if (lu < 1 || lv < 1) return null;
        float m = 0;
        int n = 0;
        for (int i = 0; i < 3; i++) {
            if (p[i] instanceof FinderPattern) {
                m += ((FinderPattern) p[i]).getEstimatedModuleSize();
                n++;
            }
        }
        if (n > 0) m /= n;
        else m = dimension > 7 ? (lu + lv) / 2 / (dimension - 7) : (lu + lv) / 2 / 14;
        ux /= lu;
        uy /= lu;
        vx /= lv;
        vy /= lv;
        float e = 3.5f * m;
        float[] xs = {tlx - e * (ux + vx), trx + e * (ux - vx), blx + e * (vx - ux), trx + blx - tlx + e * (ux + vx)};
        float[] ys = {tly - e * (uy + vy), try_ + e * (uy - vy), bly + e * (vy - uy), try_ + bly - tly + e * (uy + vy)};
        float x0 = xs[0], x1 = xs[0], y0 = ys[0], y1 = ys[0];
        for (int i = 1; i < 4; i++) {
            x0 = Math.min(x0, xs[i]);
            x1 = Math.max(x1, xs[i]);
            y0 = Math.min(y0, ys[i]);
            y1 = Math.max(y1, ys[i]);
        }
        Box b = new Box((int) Math.floor(x0 * scale), (int) Math.floor(y0 * scale),
                (int) Math.ceil(x1 * scale), (int) Math.ceil(y1 * scale));
        return new Found(b, m * scale, decoded);
    }

    /** Square crop box centred on the QR, side + 2 * marginFrac * side (may extend past the image). */
    public static Box cropBox(Found f, double marginFrac) {
        int side = Math.max(f.qr.width(), f.qr.height());
        int total = side + 2 * (int) Math.round(side * marginFrac);
        int cx = (f.qr.left + f.qr.right) / 2, cy = (f.qr.top + f.qr.bottom) / 2;
        return new Box(cx - total / 2, cy - total / 2, cx - total / 2 + total, cy - total / 2 + total);
    }

    /**
     * Copies the original pixels of the QR (its box grown by one module, clamped to the image) onto a
     * white square of {@link #cropBox}; everything else is white (clean quiet zone). Returns side*side ARGB.
     */
    public static int[] crop(int[] argb, int w, int h, Found f, Box c) {
        int side = c.width();
        int[] out = new int[side * side];
        java.util.Arrays.fill(out, 0xffffffff);
        int g = (int) Math.ceil(f.module);
        int x0 = Math.max(Math.max(0, c.left), f.qr.left - g), x1 = Math.min(Math.min(w, c.right), f.qr.right + g);
        int y0 = Math.max(Math.max(0, c.top), f.qr.top - g), y1 = Math.min(Math.min(h, c.bottom), f.qr.bottom + g);
        for (int y = y0; y < y1; y++) {
            int src = y * w + x0, dst = (y - c.top) * side + (x0 - c.left);
            for (int x = x0; x < x1; x++) out[dst++] = argb[src++] | 0xff000000;
        }
        return out;
    }

    static byte[] luminance(int[] argb) {
        byte[] l = new byte[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            l[i] = (byte) ((((c >> 16) & 0xff) * 306 + ((c >> 8) & 0xff) * 601 + (c & 0xff) * 117) >> 10);
        }
        return l;
    }

    static byte[] halve(byte[] l, int w, int h) {
        int sw = w / 2, sh = h / 2;
        byte[] o = new byte[sw * sh];
        for (int y = 0; y < sh; y++) {
            int r0 = 2 * y * w, r1 = r0 + w;
            for (int x = 0; x < sw; x++) {
                int s = (l[r0 + 2 * x] & 0xff) + (l[r0 + 2 * x + 1] & 0xff) + (l[r1 + 2 * x] & 0xff) + (l[r1 + 2 * x + 1] & 0xff);
                o[y * sw + x] = (byte) (s >> 2);
            }
        }
        return o;
    }
}
