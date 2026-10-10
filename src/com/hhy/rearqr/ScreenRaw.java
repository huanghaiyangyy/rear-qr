package com.hhy.rearqr;

import java.io.IOException;

/**
 * Parser for `screencap` raw output: little-endian header w, h, format (+ dataspace on Android 12+,
 * i.e. 16 bytes; 12 bytes before), then tightly packed rows. Pure Java for unit tests.
 */
public final class ScreenRaw {
    public static final int RGBA_8888 = 1, RGBX_8888 = 2, RGB_888 = 3, RGB_565 = 4, BGRA_8888 = 5;

    public final int width, height, format, offset;

    ScreenRaw(int width, int height, int format, int offset) {
        this.width = width;
        this.height = height;
        this.format = format;
        this.offset = offset;
    }

    static int bpp(int format) {
        switch (format) {
            case RGBA_8888: case RGBX_8888: case BGRA_8888: return 4;
            case RGB_888: return 3;
            case RGB_565: return 2;
            default: return 0;
        }
    }

    static int le32(byte[] d, int o) {
        return (d[o] & 0xff) | (d[o + 1] & 0xff) << 8 | (d[o + 2] & 0xff) << 16 | (d[o + 3] & 0xff) << 24;
    }

    /** Detects the header size (16 or 12) from the data length; prefers 16 on SDK >= 31. */
    public static ScreenRaw parse(byte[] d, int len, int sdk) throws IOException {
        if (len < 16) throw new IOException("screencap 输出太短 (" + len + " 字节)");
        int w = le32(d, 0), h = le32(d, 4), f = le32(d, 8), bpp = bpp(f);
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384) throw new IOException("screencap 头部异常 " + w + "x" + h);
        if (bpp == 0) throw new IOException("不支持的像素格式 " + f);
        long px = (long) w * h * bpp;
        int first = sdk >= 31 ? 16 : 12, second = sdk >= 31 ? 12 : 16;
        int hdr;
        if (len - 16 == px) hdr = 16;          // exact matches win
        else if (len - 12 == px) hdr = 12;
        else if (len >= first + px) hdr = first; // e.g. trailing bytes / several frames: take the first
        else if (len >= second + px) hdr = second;
        else throw new IOException("screencap 数据不完整 (" + len + " < " + px + ")");
        return new ScreenRaw(w, h, f, hdr);
    }

    /** Converts pixels to opaque ARGB ints (alpha forced to 0xFF). */
    public int[] toArgb(byte[] d) {
        int n = width * height;
        int[] out = new int[n];
        int o = offset;
        switch (format) {
            case RGBA_8888: case RGBX_8888:
                for (int i = 0; i < n; i++, o += 4)
                    out[i] = 0xff000000 | (d[o] & 0xff) << 16 | (d[o + 1] & 0xff) << 8 | (d[o + 2] & 0xff);
                break;
            case BGRA_8888:
                for (int i = 0; i < n; i++, o += 4)
                    out[i] = 0xff000000 | (d[o + 2] & 0xff) << 16 | (d[o + 1] & 0xff) << 8 | (d[o] & 0xff);
                break;
            case RGB_888:
                for (int i = 0; i < n; i++, o += 3)
                    out[i] = 0xff000000 | (d[o] & 0xff) << 16 | (d[o + 1] & 0xff) << 8 | (d[o + 2] & 0xff);
                break;
            default: // RGB_565
                for (int i = 0; i < n; i++, o += 2) {
                    int v = (d[o] & 0xff) | (d[o + 1] & 0xff) << 8;
                    int r = (v >> 11) & 31, g = (v >> 5) & 63, b = v & 31;
                    out[i] = 0xff000000 | (r << 3 | r >> 2) << 16 | (g << 2 | g >> 4) << 8 | (b << 3 | b >> 2);
                }
        }
        return out;
    }
}
