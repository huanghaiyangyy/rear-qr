package com.hhy.rearqr;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses `am stack list` (RootTask id=.. displayId=.. / taskId=..: lines). Pure Java for unit tests. */
final class Stacks {
    private static final Pattern ROOT = Pattern.compile("(?:RootTask|Stack) id=(\\d+)");
    private static final Pattern DISPLAY = Pattern.compile("displayId=(\\d+)");
    private static final Pattern TASK = Pattern.compile("taskId=(\\d+):");

    private Stacks() {
    }

    /** RootTask id containing taskId (a leaf task is its own root), or -1 if not listed. */
    static int rootTaskOf(String list, int taskId) {
        int root = -1;
        for (String line : list.split("\n")) {
            Matcher r = ROOT.matcher(line);
            if (r.find()) {
                root = Integer.parseInt(r.group(1));
                if (root == taskId) return root;
                continue;
            }
            Matcher t = TASK.matcher(line);
            if (t.find() && Integer.parseInt(t.group(1)) == taskId) return root >= 0 ? root : taskId;
        }
        return -1;
    }

    private static final Pattern TASK_PKG = Pattern.compile("taskId=\\d+: ([A-Za-z0-9_.]+)/");

    /** Package of the top visible task on the display, skipping exclude (our own pages); "" if none. */
    static String topApp(String list, int displayId, String exclude) {
        int disp = -1;
        for (String line : list.split("\n")) {
            Matcher r = ROOT.matcher(line);
            if (r.find()) {
                Matcher d = DISPLAY.matcher(line);
                disp = d.find() ? Integer.parseInt(d.group(1)) : -1;
                continue;
            }
            Matcher t = TASK_PKG.matcher(line);
            if (disp == displayId && t.find() && line.contains("visible=true") && !t.group(1).equals(exclude)) {
                return t.group(1);
            }
        }
        return "";
    }

    /** displayId of the given RootTask, or -1. */
    static int displayOf(String list, int rootTaskId) {
        for (String line : list.split("\n")) {
            Matcher r = ROOT.matcher(line);
            if (r.find() && Integer.parseInt(r.group(1)) == rootTaskId) {
                Matcher d = DISPLAY.matcher(line);
                return d.find() ? Integer.parseInt(d.group(1)) : -1;
            }
        }
        return -1;
    }
}
