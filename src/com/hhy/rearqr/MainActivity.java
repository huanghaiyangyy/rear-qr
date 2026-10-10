package com.hhy.rearqr;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.os.Bundle;
import android.text.InputType;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Small settings screen: timeout, margin, keep-open, 「测试」 and a status line. */
public final class MainActivity extends Activity {
    private TextView status;
    private EditText timeout, margin;
    private Switch keep;
    private Switch resident;
    private int pad;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        KeepAliveService.sync(this);
        pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(22);
        col.addView(title);
        TextView help = text("把主屏上的二维码截下来，放大显示到背屏。在二维码页面下拉控制中心，点快捷开关「二维码贴背屏」；"
                + "或用悬浮球 / 自动化工具启动 action com.hhy.rearqr.CAPTURE。背屏上：单击 = 重新识别，长按或 ✕ = 关闭。\n"
                + "主屏会出现类似系统「灵动岛」的提示：识别时是包住前摄的小黑胶囊（转圈 | 识别中），成功后展开成状态栏下方的卡片："
                + "✓「背屏 87s」+ ⟳ 刷新 / ✕ 关闭两个圆形按钮；失败时红色 ! 显示原因。截图时自动隐藏，背屏页关闭后自动收回消失。");
        help.setAlpha(0.75f);
        col.addView(help);

        SharedPreferences p = App.prefs(this);
        timeout = number(col, "背屏自动关闭（秒）", p.getInt("timeout", 90));
        margin = number(col, "二维码四周留白（%）", p.getInt("margin", 12));
        keep = new Switch(this);
        keep.setText("背屏页面常驻（不自动关闭）");
        keep.setChecked(p.getBoolean("keep", false));
        keep.setPadding(0, pad, 0, pad / 2);
        col.addView(keep);

        resident = new Switch(this);
        resident.setText("后台常驻（备用）");
        resident.setChecked(p.getBoolean("resident", false));
        resident.setPadding(0, pad / 2, 0, 0);
        col.addView(resident);
        TextView rh = text("磁贴偶尔没反应时再开，开启后常驻待命、耗电极低。");
        rh.setAlpha(0.6f);
        rh.setTextSize(13);
        col.addView(rh);
        resident.setOnCheckedChangeListener((v, on) -> {
            App.prefs(this).edit().putBoolean("resident", on).apply();
            KeepAliveService.sync(this);
            new Thread(() -> App.rootWhitelist(this, App.shell())).start();
        });

        Button power = new Button(this);
        power.setText("打开省电策略设置");
        power.setOnClickListener(v -> openPowerPolicy());
        col.addView(power);

        Button test = new Button(this);
        test.setText("5 秒后截取");
        test.setOnClickListener(v -> {
            save();
            App.countdownCapture(this, 5);
        });
        col.addView(test);

        status = text("状态：检测中…");
        status.setPadding(0, pad, 0, 0);
        col.addView(status);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        sv.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                    | WindowInsets.Type.ime());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onPause() {
        save();
        super.onPause();
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setPadding(0, pad / 2, 0, 0);
        return t;
    }

    private EditText number(LinearLayout col, String label, int value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, pad / 2, 0, 0);
        TextView l = text(label);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        e.setMinEms(4);
        e.setGravity(Gravity.END);
        row.addView(e);
        col.addView(row);
        return e;
    }

    private static int parse(EditText e, int def, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(e.getText().toString().trim())));
        } catch (NumberFormatException x) {
            return def;
        }
    }

    private void save() {
        App.prefs(this).edit()
                .putInt("timeout", parse(timeout, 90, 5, 3600))
                .putInt("margin", parse(margin, 12, 0, 40))
                .putBoolean("keep", keep.isChecked())
                .apply();
    }

    /** HyperOS per-app power policy page (com.miui.powerkeeper); falls back to the app details page. */
    private void openPowerPolicy() {
        android.content.Intent i = new android.content.Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY")
                .setPackage("com.miui.powerkeeper")
                .putExtra("package_name", App.PKG)
                .putExtra("package_label", getString(R.string.app_name));
        try {
            startActivity(i);
            android.util.Log.i(Capture.TAG, "settings: opened powerkeeper policy page");
            return;
        } catch (Exception e) {
            android.util.Log.w(Capture.TAG, "settings: powerkeeper page not available: " + e);
        }
        try {
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + App.PKG)));
        } catch (Exception e) {
            android.util.Log.w(Capture.TAG, "settings: app details failed: " + e);
        }
    }

    private void refreshStatus() {
        Display d = null;
        try {
            d = App.rearDisplay(this);
        } catch (Exception ignored) {
        }
        final String rear = d != null
                ? "背屏：已找到 display " + d.getDisplayId() + "（" + d.getMode().getPhysicalWidth() + "x"
                        + d.getMode().getPhysicalHeight() + "）"
                : "背屏：未通过 DisplayManager 找到，将使用 display " + App.REAR_FALLBACK_ID;
        status.setText("状态：正在检查 root…\n" + rear);
        new Thread(() -> {
            RootShell.Result r = App.shell().exec("id -u", 70000);
            // overlay fallback for the rear screen needs SYSTEM_ALERT_WINDOW: grant it via root once
            if (r.code == 0 && !android.provider.Settings.canDrawOverlays(this)) {
                Capture.su(App.shell(), "appops set " + App.PKG + " SYSTEM_ALERT_WINDOW allow", 5000);
            }
            String root = r.code == 0 ? "root：正常" : r.code == RootShell.EACCES ? RootShell.DENIED_MSG : "root：异常 " + r.err;
            String power = "小米省电策略：未知（需要 root）";
            if (r.code == 0) {
                RootShell.Result m = Capture.su(App.shell(), "settings --user 0 get system MILLET_NO_RESTRICT_APP", 5000);
                power = m.code == 0 && m.text().contains(App.PKG) ? "小米省电策略：无限制 ✓" : "小米省电策略：未设置（建议设为「无限制」）";
            }
            final String pw = power;
            App.ui(() -> {
                if (!isDestroyed()) status.setText("状态：" + root + "\n" + rear + "\n" + pw);
            });
        }).start();
        status.setOnClickListener(v -> refreshStatus()); // tap to re-check
        status.setVisibility(View.VISIBLE);
    }
}
