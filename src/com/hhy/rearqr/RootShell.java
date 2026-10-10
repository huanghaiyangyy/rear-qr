package com.hhy.rearqr;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

/**
 * One persistent root shell ("su") whose stdout is read as raw BYTES, so binary output such as
 * `screencap` lands directly in memory (no temp files). Pure Java: unit-testable with "sh".
 *
 * Each command is followed by a random marker line `\n<MARKER> <exit code>\n`; output ends where the
 * marker begins. stderr is drained in the background (last few KB kept for diagnostics).
 */
public final class RootShell {
    public static final int EACCES = 13;
    public static final String DENIED_MSG =
            "未获得 root 权限：请打开 KernelSU 管理器 →「超级用户」，找到「二维码贴背屏」并允许 root，然后重试。";

    public static final class Result {
        public final int code;
        public final byte[] out;
        public final String err;

        Result(int code, byte[] out, String err) {
            this.code = code;
            this.out = out;
            this.err = err;
        }

        public String text() {
            return new String(out, StandardCharsets.UTF_8).trim();
        }
    }

    private static final Timer WATCHDOG = new Timer("rootshell-watchdog", true);
    private final String[] suCmd;
    private final boolean requireUid0;
    private Process proc;
    private OutputStream stdin;
    private InputStream stdout;
    private final StringBuilder errTail = new StringBuilder();
    private volatile boolean denied;

    public RootShell(String[] suCmd, boolean requireUid0) {
        this.suCmd = suCmd.clone();
        this.requireUid0 = requireUid0;
    }

    public boolean isDenied() {
        return denied;
    }

    /** Run one command; never throws. code == EACCES means root was denied. */
    public synchronized Result exec(String cmd, long timeoutMs) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (!alive()) start();
            } catch (IOException e) {
                close();
                denied = true;
                return new Result(EACCES, new byte[0], DENIED_MSG + "\n(" + e.getMessage() + ")");
            }
            denied = false;
            try {
                return run(cmd, timeoutMs);
            } catch (IOException e) {
                close(); // shell died or timed out: next call starts a fresh one
                if (attempt == 1 || !e.getMessage().startsWith("write")) {
                    return new Result(1, new byte[0], "root shell 异常: " + e.getMessage());
                }
            }
        }
        return new Result(1, new byte[0], "unreachable");
    }

    public synchronized void close() {
        if (proc != null) proc.destroy();
        proc = null;
    }

    private boolean alive() {
        if (proc == null) return false;
        try {
            proc.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private void start() throws IOException {
        try {
            proc = new ProcessBuilder(suCmd).start();
        } catch (IOException e) {
            // KernelSU: an app without root grant cannot even see/exec "su"
            throw new IOException("su: " + e.getMessage());
        }
        stdin = proc.getOutputStream();
        stdout = proc.getInputStream();
        final InputStream err = proc.getErrorStream();
        Thread t = new Thread(() -> {
            byte[] b = new byte[4096];
            try {
                int n;
                while ((n = err.read(b)) > 0) {
                    synchronized (errTail) {
                        errTail.append(new String(b, 0, n, StandardCharsets.UTF_8));
                        if (errTail.length() > 4096) errTail.delete(0, errTail.length() - 4096);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "su-err");
        t.setDaemon(true);
        t.start();
        Result r;
        try {
            r = run("id -u", 60000); // generous: root-manager prompts
        } catch (IOException e) {
            throw new IOException("su 已退出 (" + e.getMessage() + ")");
        }
        if (requireUid0 && !"0".equals(r.text())) throw new IOException("uid=" + r.text());
    }

    /** Shell text for one command. Package-private for tests. */
    static String frame(String cmd, String marker) {
        return "( " + cmd + "\n) </dev/null\nprintf '\\n%s %d\\n' " + marker + " $?\n";
    }

    /**
     * If buf[0..len) ends with "\n" + marker + " " + digits + "\n", return the marker's index; else -1.
     * Package-private for tests.
     */
    static int findEnd(byte[] buf, int len, byte[] marker) {
        if (len < marker.length + 3 || buf[len - 1] != '\n') return -1;
        int from = Math.max(0, len - marker.length - 16);
        outer:
        for (int i = len - marker.length - 2; i >= from; i--) {
            for (int j = 0; j < marker.length; j++) if (buf[i + j] != marker[j]) continue outer;
            return (i > 0 && buf[i - 1] == '\n' && buf[i + marker.length] == ' ') ? i : -1;
        }
        return -1;
    }

    private Result run(String cmd, long timeoutMs) throws IOException {
        String m = "__RQR_END_" + UUID.randomUUID().toString().replace("-", "");
        byte[] marker = m.getBytes(StandardCharsets.US_ASCII);
        synchronized (errTail) {
            errTail.setLength(0);
        }
        try {
            stdin.write(frame(cmd, m).getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            throw new IOException("write: " + e.getMessage());
        }
        final Process p = proc;
        TimerTask kill = new TimerTask() {
            @Override
            public void run() {
                p.destroy();
            }
        };
        if (timeoutMs > 0) WATCHDOG.schedule(kill, timeoutMs);
        try {
            byte[] buf = new byte[1 << 16];
            int len = 0;
            while (true) {
                if (len == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
                int n = stdout.read(buf, len, buf.length - len);
                if (n < 0) throw new IOException("shell exited");
                len += n;
                int i = findEnd(buf, len, marker);
                if (i >= 0) {
                    int code;
                    try {
                        code = Integer.parseInt(new String(buf, i + marker.length + 1, len - i - marker.length - 2,
                                StandardCharsets.US_ASCII).trim());
                    } catch (NumberFormatException e) {
                        code = 1;
                    }
                    String err;
                    synchronized (errTail) {
                        err = errTail.toString();
                    }
                    return new Result(code, Arrays.copyOf(buf, i - 1), err);
                }
            }
        } finally {
            kill.cancel();
        }
    }
}
