package com.hhy.rearqr;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses `dumpsys window | grep -E 'mDisplayId=|mCurrentFocus='`. Pure Java for unit tests. */
final class Focus {
    static final String CMD = "dumpsys window | grep -E 'mDisplayId=|mCurrentFocus='";
    private static final Pattern DISPLAY = Pattern.compile("mDisplayId=(\\d+)");
    private static final Pattern FOCUS = Pattern.compile("mCurrentFocus=(.*)");
    private static final Pattern SHADE = Pattern.compile(
            "(?i)NotificationShade|StatusBar|ControlCenter|ControlPanel|NotificationPanel|QuickSettings|ExpandedNotification");
    private static final Pattern PKG = Pattern.compile("\\bu\\d+ ([A-Za-z0-9_.]+)[/} ]");

    private Focus() {
    }

    /** Focused window of display 0 (or the first mCurrentFocus if displays aren't labelled); "" if none. */
    static String mainFocus(String dump) {
        int display = -1;
        String first = null;
        for (String line : dump.split("\n")) {
            Matcher d = DISPLAY.matcher(line);
            if (d.find()) display = Integer.parseInt(d.group(1));
            Matcher f = FOCUS.matcher(line);
            if (f.find()) {
                String v = f.group(1).trim();
                if (display == 0) return v;
                if (first == null) first = v;
            }
        }
        return first == null ? "" : first;
    }

    /** For WIN_CMD output: display of the window titled exactly title ("Window{.. u0 <title>}"), or -1. */
    static final String WIN_CMD = "dumpsys window windows | grep -E 'Window #|mDisplayId='";
    private static final Pattern WIN = Pattern.compile("Window #\\d+ Window\\{\\S+ u\\d+ (.+?)\\}:?\\s*$");

    static int windowDisplay(String dump, String title) {
        String cur = null;
        for (String line : dump.split("\n")) {
            Matcher w = WIN.matcher(line);
            if (w.find()) {
                cur = w.group(1).trim();
                continue;
            }
            Matcher d = DISPLAY.matcher(line);
            if (d.find() && title.equals(cur)) return Integer.parseInt(d.group(1));
        }
        return -1;
    }

    static boolean isShade(String focus) {
        return SHADE.matcher(focus).find();
    }

    /** "Window{8a1 u0 com.alibaba.android.rimet/...}" -> "com.alibaba.android.rimet"; window title otherwise. */
    static String pkg(String focus) {
        Matcher m = PKG.matcher(focus + " ");
        return m.find() ? m.group(1) : focus;
    }
}
