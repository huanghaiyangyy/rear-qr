package com.hhy.rearqr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/** Plain-JVM tests (no JUnit needed): java -cp ... com.hhy.rearqr.Tests */
public final class Tests {
    static int failed, passed;

    static void check(boolean ok, String what) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL: " + what);
        }
    }

    public static void main(String[] a) throws Exception {
        rawHeader();
        rootShell();
        focus();
        stacks();
        windows();
        qrCases();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------ raw screencap header

    static byte[] raw(int w, int h, int fmt, int hdr, int bpp, int extra) {
        byte[] d = new byte[hdr + w * h * bpp + extra];
        int[] v = {w, h, fmt, 7 /* dataspace */};
        for (int i = 0; i < hdr / 4; i++) for (int k = 0; k < 4; k++) d[i * 4 + k] = (byte) (v[i] >> (8 * k));
        for (int i = 0; i < w * h; i++) {
            int o = hdr + i * bpp;
            if (bpp == 4) {
                d[o] = (byte) 0x11; d[o + 1] = (byte) 0x22; d[o + 2] = (byte) 0x33; d[o + 3] = 0;
            } else { // 565: r=31 g=0 b=31
                int px = 31 << 11 | 31;
                d[o] = (byte) px; d[o + 1] = (byte) (px >> 8);
            }
        }
        return d;
    }

    static void rawHeader() throws Exception {
        byte[] d = raw(1220, 2656, 1, 16, 4, 0);
        ScreenRaw r = ScreenRaw.parse(d, d.length, 37);
        check(r.width == 1220 && r.height == 2656 && r.format == 1 && r.offset == 16, "raw 16-byte header (Android 12+)");
        int[] px = r.toArgb(d);
        check(px[0] == 0xff112233 && px[px.length - 1] == 0xff112233, "RGBA->ARGB, alpha forced opaque");

        d = raw(64, 32, 2, 12, 4, 0);
        r = ScreenRaw.parse(d, d.length, 37);
        check(r.offset == 12, "12-byte header detected by length even on SDK 37");
        d = raw(64, 32, 1, 16, 4, 0);
        r = ScreenRaw.parse(d, d.length, 30);
        check(r.offset == 16, "16-byte header detected by length on SDK 30");
        d = raw(64, 32, 1, 16, 4, 16 + 64 * 32 * 4); // two frames concatenated
        r = ScreenRaw.parse(d, d.length, 37);
        check(r.offset == 16, "multi-frame output: first frame");
        d = raw(8, 8, 4, 16, 2, 0);
        r = ScreenRaw.parse(d, d.length, 37);
        check(r.toArgb(d)[5] == 0xffff00ff, "RGB_565 conversion");
        boolean threw = false;
        try {
            ScreenRaw.parse(d, 40, 37);
        } catch (java.io.IOException e) {
            threw = true;
        }
        check(threw, "truncated data rejected");
        threw = false;
        d = raw(8, 8, 99, 16, 4, 0);
        try {
            ScreenRaw.parse(d, d.length, 37);
        } catch (java.io.IOException e) {
            threw = true;
        }
        check(threw, "unknown pixel format rejected");
    }

    // ------------------------------------------------------------ binary root shell (with sh)

    static void rootShell() {
        RootShell sh = new RootShell(new String[] {"sh"}, false);
        RootShell.Result r = sh.exec("printf 'a\\nb\\000\\n\\n'", 5000);
        check(r.code == 0 && java.util.Arrays.equals(r.out, new byte[] {'a', '\n', 'b', 0, '\n', '\n'}), "binary stdout exact");
        r = sh.exec("head -c 13000016 /dev/urandom", 20000);
        check(r.code == 0 && r.out.length == 13000016, "13 MB binary stdout: " + r.out.length);
        r = sh.exec("echo oops >&2; exit 3", 5000);
        check(r.code == 3, "exit code propagated, shell survives");
        r = sh.exec("printf 'no newline'", 5000);
        check(r.text().equals("no newline"), "output without trailing newline");
        r = sh.exec("sleep 5", 300);
        check(r.code != 0, "timeout kills shell");
        r = sh.exec("echo again", 5000);
        check(r.code == 0 && r.text().equals("again"), "fresh shell after timeout");
        RootShell none = new RootShell(new String[] {"/nonexistent/su"}, true);
        r = none.exec("id", 5000);
        check(r.code == RootShell.EACCES && none.isDenied(), "missing su -> denied");
        RootShell notRoot = new RootShell(new String[] {"sh"}, true);
        check(notRoot.exec("id", 5000).code == RootShell.EACCES || isRoot(), "uid != 0 -> denied");
        byte[] m = "__RQR_END_x".getBytes(StandardCharsets.US_ASCII);
        byte[] b = "data\n__RQR_END_x 0\n".getBytes(StandardCharsets.US_ASCII);
        check(RootShell.findEnd(b, b.length, m) == 5, "findEnd locates marker");
        check(RootShell.findEnd(b, b.length - 1, m) == -1, "findEnd waits for full marker line");
    }

    static void focus() {
        String multi = "    mDisplayId=1 ...\n  mCurrentFocus=Window{1a u0 com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher}\n"
                + "Display: mDisplayId=0 rotation=0\n  mCurrentFocus=Window{2b u0 com.alibaba.android.rimet/com.alibaba.X}\n";
        check(Focus.mainFocus(multi).contains("rimet"), "focus: display 0 preferred");
        check(Focus.pkg(Focus.mainFocus(multi)).equals("com.alibaba.android.rimet"), "focus: package extracted");
        String shade = "  mCurrentFocus=Window{3c u0 NotificationShade}";
        check(Focus.isShade(Focus.mainFocus(shade)) && Focus.pkg(Focus.mainFocus(shade)).equals("NotificationShade"),
                "focus: NotificationShade detected");
        check(Focus.isShade("Window{4d u0 ControlCenterWindow}"), "focus: HyperOS control center detected");
        check(!Focus.isShade("Window{5e u0 com.miui.home/com.miui.home.launcher.Launcher}"), "focus: launcher is not shade");
        check(Focus.mainFocus("").isEmpty() && Focus.mainFocus("  mCurrentFocus=null").equals("null"), "focus: empty / null");
    }

    static void windows() {
        String d = "  Window #3 Window{1a2b u0 com.alibaba.android.rimet/com.x.Main}:\n    mDisplayId=0 rootTaskId=803 mSession=Session{}\n"
                + "  Window #4 Window{c9b1 u0 RearQR}:\n    mDisplayId=1 rootTaskId=1\n"
                + "  Window #5 Window{d0d0 u0 RearQR-capsule}:\n    mDisplayId=0 rootTaskId=1\n";
        check(Focus.windowDisplay(d, "RearQR") == 1, "windows: overlay found on display 1");
        check(Focus.windowDisplay(d, "RearQR-capsule") == 0, "windows: capsule on display 0 (exact title)");
        check(Focus.windowDisplay(d, "Nope") == -1 && Focus.windowDisplay("", "RearQR") == -1, "windows: missing -> -1");
        check(Focus.windowDisplay(d.replace("Window{c9b1 u0 RearQR}", "Window{c9b1 u0 RearQRx}"), "RearQR") == -1,
                "windows: no prefix match");
    }

    static void stacks() {
        String l = "RootTask id=1 bounds=[0,0][1220,2656] displayId=0 userId=0\n"
                + "  configuration={1.0 ?mcc0mnc [zh_CN] ldltr sw375dp}\n"
                + "  taskId=12: com.miui.home/com.miui.home.launcher.Launcher bounds=[0,0][1220,2656] userId=0 visible=false\n"
                + "RootTask id=3 bounds=[0,0][904,572] displayId=1 userId=0\n"
                + "  taskId=3: com.xiaomi.subscreencenter/.SubScreenLauncher bounds=[0,0][904,572] userId=0 visible=true\n"
                + "RootTask id=57 bounds=[0,0][1220,2656] displayId=0 userId=0\n"
                + "  taskId=57: com.hhy.rearqr/com.hhy.rearqr.RearActivity bounds=[0,0][1220,2656] userId=0 visible=true\n"
                + "RootTask id=40 bounds=[0,0][1220,2656] displayId=0 userId=0\n"
                + "  taskId=41: com.alibaba.android.rimet/com.alibaba.X bounds=[0,0][1220,2656] userId=0 visible=true\n";
        check(Stacks.rootTaskOf(l, 57) == 57, "stacks: leaf task is its own root");
        check(Stacks.rootTaskOf(l, 41) == 40, "stacks: nested task -> parent RootTask");
        check(Stacks.rootTaskOf(l, 12) == 1, "stacks: launcher task -> RootTask 1");
        check(Stacks.rootTaskOf(l, 99) == -1, "stacks: unknown task -> -1");
        check(Stacks.topApp(l, 0, "com.hhy.rearqr").equals("com.alibaba.android.rimet"), "stacks: top app skips our page + invisible");
        check(Stacks.topApp(l, 1, "com.hhy.rearqr").equals("com.xiaomi.subscreencenter"), "stacks: top app on rear");
        check(Stacks.topApp("", 0, "x").isEmpty(), "stacks: top app none");
        check(Stacks.displayOf(l, 57) == 0 && Stacks.displayOf(l, 3) == 1 && Stacks.displayOf(l, 5) == -1,
                "stacks: displayId of RootTask");
    }

    static boolean isRoot() {
        return "root".equals(System.getProperty("user.name"));
    }

    // ------------------------------------------------------------ synthetic screenshots

    static final int W = 1220, H = 2656;

    static BitMatrix qr(String text, ErrorCorrectionLevel ec) throws Exception {
        Map<EncodeHintType, Object> h = new EnumMap<>(EncodeHintType.class);
        h.put(EncodeHintType.MARGIN, 0);
        h.put(EncodeHintType.ERROR_CORRECTION, ec);
        return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, h);
    }

    /** Draws a QR with module size m at (x, y) (optionally rotated); returns its true outer box. */
    static QrLocator.Box drawQr(Graphics2D g, BitMatrix q, int x, int y, int m, double rotDeg) {
        int side = q.getWidth() * m;
        AffineTransform old = g.getTransform();
        g.rotate(Math.toRadians(rotDeg), x + side / 2.0, y + side / 2.0);
        g.setColor(Color.WHITE);
        g.fillRect(x - 4 * m, y - 4 * m, side + 8 * m, side + 8 * m);
        g.setColor(Color.BLACK);
        for (int j = 0; j < q.getHeight(); j++)
            for (int i = 0; i < q.getWidth(); i++) if (q.get(i, j)) g.fillRect(x + i * m, y + j * m, m, m);
        g.setTransform(old);
        double r = Math.toRadians(Math.abs(rotDeg)), half = side / 2.0 * (Math.cos(r) + Math.sin(r));
        double cx = x + side / 2.0, cy = y + side / 2.0;
        return new QrLocator.Box((int) Math.floor(cx - half), (int) Math.floor(cy - half),
                (int) Math.ceil(cx + half), (int) Math.ceil(cy + half));
    }

    static void watermark(Graphics2D g) {
        g.setColor(new Color(90, 90, 90, 60));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 46));
        AffineTransform old = g.getTransform();
        g.rotate(Math.toRadians(-30), W / 2.0, H / 2.0);
        for (int y = -H; y < 2 * H; y += 190)
            for (int x = -W; x < 2 * W; x += 520) g.drawString("ZHANG SAN 0931 内部", x + (y / 190 % 2) * 260, y);
        g.setTransform(old);
    }

    static void qrCases() throws Exception {
        BitMatrix big = qr("https://example.com/attendance?token=8f2c1d0a9b7e6f5a4c3b2a19&user=zhangsan", ErrorCorrectionLevel.M);
        BitMatrix bigH = qr("DINGTALK-PASS-0123456789-ABCDEFGHIJ", ErrorCorrectionLevel.H);
        BitMatrix small = qr("small", ErrorCorrectionLevel.L);
        // {module px, x, y, rotation, logo?, watermark?, second small QR?}
        int[][] cases = {
                {14, 180, 700, 0, 0, 1, 1},   // typical "my QR" page, ~800 px
                {8, 400, 1500, 0, 0, 1, 1},   // medium, lower half
                {5, 60, 200, 0, 0, 1, 0},     // small near top-left edge
                {18, 30, 900, 0, 0, 0, 1},    // nearly full width (crop box exceeds image -> white fill)
                {10, 300, 900, 8, 0, 1, 1},   // slightly rotated
                {12, 250, 800, 0, 1, 1, 1},   // H-level with a logo in the middle
                {6, 700, 2200, 0, 0, 1, 1},   // bottom-right
        };
        for (int[] c : cases) {
            int m = c[0];
            BitMatrix q = c[4] == 1 ? bigH : big;
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(0xF2F3F5));
            g.fillRect(0, 0, W, H);
            g.setColor(new Color(0x1677FF)); // app header bar
            g.fillRect(0, 0, W, 180);
            g.setColor(new Color(0xFF8A00)); // orange card behind the code
            int side = q.getWidth() * m;
            g.fillRoundRect(Math.max(0, c[1] - 6 * m), c[2] - 6 * m, Math.min(W, side + 12 * m), side + 30 * m, 40, 40);
            QrLocator.Box truth = drawQr(g, q, c[1], c[2], m, c[3]);
            if (c[4] == 1) { // logo
                g.setColor(new Color(0x2B6DE8));
                int ls = side / 5;
                g.fillRoundRect(c[1] + side / 2 - ls / 2, c[2] + side / 2 - ls / 2, ls, ls, 20, 20);
            }
            QrLocator.Box smallTruth = null;
            if (c[6] == 1) {
                int sx = c[1] > 600 ? 120 : 900, sy = c[2] > 1500 ? 300 : 2350;
                smallTruth = drawQr(g, small, sx, sy, 4, 0);
            }
            if (c[5] == 1) watermark(g);
            g.dispose();
            int[] px = img.getRGB(0, 0, W, H, null, 0, W);

            long t0 = System.nanoTime();
            QrLocator.Found f = QrLocator.locate(px, W, H);
            long ms = (System.nanoTime() - t0) / 1000000;
            String name = "case m=" + m + " at " + c[1] + "," + c[2] + " rot=" + c[3] + (c[4] == 1 ? " logo" : "")
                    + (c[6] == 1 ? " +smallQR" : "");
            if (f == null) {
                check(false, name + ": not found");
                continue;
            }
            int tol = (int) Math.ceil(1.5 * m) + 2;
            boolean nearTruth = Math.abs(f.qr.left - truth.left) <= tol && Math.abs(f.qr.top - truth.top) <= tol
                    && Math.abs(f.qr.right - truth.right) <= tol && Math.abs(f.qr.bottom - truth.bottom) <= tol;
            check(nearTruth, name + ": box " + f.qr + " vs truth " + truth);
            QrLocator.Box crop = QrLocator.cropBox(f, 0.12);
            int side2 = Math.max(truth.width(), truth.height());
            int need = (int) (side2 * 0.10);
            QrLocator.Box withMargin = new QrLocator.Box(truth.left - need, truth.top - need, truth.right + need, truth.bottom + need);
            check(crop.contains(withMargin) && crop.width() == crop.height(), name + ": crop " + crop + " contains truth+10% " + withMargin);
            if (smallTruth != null) {
                check(!crop.contains(smallTruth), name + ": picked the big QR, not the small one");
                check(QrLocator.locateAll(px, W, H).size() >= 2, name + ": both QR codes were seen");
            }
            int[] out = QrLocator.crop(px, W, H, f, crop);
            int cs = crop.width();
            // centre pixel of the crop equals the screenshot pixel; corners are white quiet zone
            int cx = (crop.left + crop.right) / 2, cy = (crop.top + crop.bottom) / 2;
            check(out[(cy - crop.top) * cs + (cx - crop.left)] == (px[cy * W + cx] | 0xff000000), name + ": original pixels copied");
            check(out[0] == 0xffffffff && out[cs * cs - 1] == 0xffffffff, name + ": white quiet zone corners");
            check(decodes(out, cs), name + ": crop still decodes");
            System.out.println("  ok " + name + "  found=" + f.qr + " decoded=" + f.decoded + " crop=" + cs + "px  " + ms + " ms");
        }
        // undecodable code (data area scribbled over): finder-pattern fallback still locates it
        {
            BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = im.createGraphics();
            g.setColor(new Color(0xF2F3F5));
            g.fillRect(0, 0, W, H);
            QrLocator.Box truth = drawQr(g, big, 200, 900, 12, 0);
            int side = big.getWidth() * 12;
            g.setColor(new Color(0x2B6DE8));
            g.fillRect(200 + 9 * 12, 900 + 9 * 12, side - 18 * 12, side - 18 * 12);
            watermark(g);
            g.dispose();
            int[] px = im.getRGB(0, 0, W, H, null, 0, W);
            QrLocator.Found f = QrLocator.locate(px, W, H);
            check(f != null && !f.decoded && QrLocator.cropBox(f, 0.12).contains(truth),
                    "undecodable QR located by finder patterns: " + (f == null ? null : f.qr) + " truth " + truth);
        }
        // nothing to find
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0xF2F3F5));
        g.fillRect(0, 0, W, H);
        watermark(g);
        g.dispose();
        check(QrLocator.locate(img.getRGB(0, 0, W, H, null, 0, W), W, H) == null, "no QR -> null");
    }

    static boolean decodes(int[] argb, int s) {
        try {
            com.google.zxing.LuminanceSource src = new com.google.zxing.RGBLuminanceSource(s, s, argb);
            new QRCodeWriter(); // keep imports simple
            new com.google.zxing.qrcode.QRCodeReader().decode(new com.google.zxing.BinaryBitmap(
                    new com.google.zxing.common.HybridBinarizer(src)));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
