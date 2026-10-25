package com.xiaocan.sentinel;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 首次启动引导: 逐项授权 + 完成后自动启动监控 (仅出现一次) */
public class OnboardingActivity extends Activity {

    SharedPreferences p;
    TextView stNotif, stLoc, stBat;
    final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        p = getSharedPreferences(getPackageName(), MODE_PRIVATE);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    void buildUi() {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (pad * 1.6), pad, pad);

        TextView title = new TextView(this);
        title.setText("欢迎使用小蚕哨兵");
        title.setTextSize(24);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFF1A1A1A);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("它会在后台安静盯着你附近的返利活动，上新或补货时第一时间通知你。\n\n开始前请完成下面 3 项授权（都是必需的）：");
        sub.setTextSize(14);
        sub.setTextColor(0xFF666666);
        sub.setLineSpacing(0, 1.3f);
        sub.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, 0);
        root.addView(sub);

        // 步骤 1: 通知
        root.addView(step(1, "通知权限", "没有它收不到任何上新提醒", () -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
            }
            main.postDelayed(this::refreshStatus, 1200);
        }, out -> stNotif = out));
        // 步骤 2: 定位
        root.addView(step(2, "定位权限", "按手机所在位置巡检附近活动", () -> {
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 11);
            }
            main.postDelayed(this::refreshStatus, 1200);
        }, out -> stLoc = out));
        // 步骤 3: 电池白名单
        root.addView(step(3, "电池优化白名单", "防止系统省电策略杀掉后台巡检", () -> {
            try {
                startActivity(new android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception e) { openAppSettings(); }
            main.postDelayed(this::refreshStatus, 1200);
        }, out -> stBat = out));

        // MIUI 指引
        LinearLayout miui = new LinearLayout(this);
        miui.setOrientation(LinearLayout.VERTICAL);
        miui.setBackgroundResource(R.drawable.bg_card);
        int mpad = (int) (14 * getResources().getDisplayMetrics().density);
        miui.setPadding(mpad, mpad, mpad, mpad);
        TextView mt = new TextView(this);
        mt.setText("小米/MIUI 手机额外建议\n系统设置 → 应用设置 → 应用管理 → 小蚕哨兵 → 开启「自启动」「后台弹出界面」「锁屏显示」，省电策略设为无限制");
        mt.setTextSize(12);
        mt.setTextColor(0xFF808080);
        mt.setLineSpacing(0, 1.3f);
        miui.addView(mt);
        TextView mo = new TextView(this);
        mo.setText("打开应用设置");
        mo.setTextColor(0xFFFF5F2E);
        mo.setTextSize(13);
        mo.setTypeface(null, android.graphics.Typeface.BOLD);
        mo.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, 0);
        mo.setOnClickListener(v -> openAppSettings());
        miui.addView(mo);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mlp.setMargins(0, (int) (16 * getResources().getDisplayMetrics().density), 0, 0);
        root.addView(miui, mlp);

        // 完成按钮
        TextView done = new TextView(this);
        done.setText("完成并启动监控");
        done.setGravity(Gravity.CENTER);
        done.setTextSize(16);
        done.setTypeface(null, android.graphics.Typeface.BOLD);
        done.setTextColor(0xFFFFFFFF);
        done.setBackgroundResource(R.drawable.bg_btn_rebate);
        done.setPadding(0, (int) (16 * getResources().getDisplayMetrics().density), 0, (int) (16 * getResources().getDisplayMetrics().density));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(0, (int) (24 * getResources().getDisplayMetrics().density), 0, 0);
        root.addView(done, dlp);
        done.setOnClickListener(v -> {
            p.edit().putBoolean("onboarded", true).apply();
            Intent i = new Intent(this, PollService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            Toast.makeText(this, "监控已启动", Toast.LENGTH_SHORT).show();
            finish();
        });

        TextView skip = new TextView(this);
        skip.setText("暂不启动，稍后在设置里开启");
        skip.setGravity(Gravity.CENTER);
        skip.setTextSize(13);
        skip.setTextColor(0xFF999999);
        skip.setPadding(0, (int) (14 * getResources().getDisplayMetrics().density), 0, 0);
        root.addView(skip);
        skip.setOnClickListener(v -> {
            p.edit().putBoolean("onboarded", true).apply();
            finish();
        });

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    LinearLayout step(int n, String title, String sub, Runnable action, java.util.function.Consumer<TextView> out) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_card);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, (int) (12 * getResources().getDisplayMetrics().density), 0, 0);
        box.setLayoutParams(lp);

        TextView t1 = new TextView(this);
        t1.setText(n + ". " + title);
        t1.setTextSize(15);
        t1.setTypeface(null, android.graphics.Typeface.BOLD);
        t1.setTextColor(0xFF1A1A1A);
        box.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText(sub);
        t2.setTextSize(12);
        t2.setTextColor(0xFF9A9A9A);
        t2.setPadding(0, (int) (3 * getResources().getDisplayMetrics().density), 0, 0);
        box.addView(t2);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, 0);
        TextView st = new TextView(this);
        st.setTextSize(11);
        st.setPadding(10, 3, 10, 3);
        st.setBackgroundResource(R.drawable.bg_chip_rebate);
        st.setTextColor(0xFFFFFFFF);
        st.setText("未授权");
        row.addView(st);
        out.accept(st);
        TextView btn = new TextView(this);
        btn.setText("去授权");
        btn.setTextColor(0xFFFF5F2E);
        btn.setTextSize(13);
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        btn.setBackgroundResource(R.drawable.bg_search);
        btn.setPadding(16, 7, 16, 7);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(12, 0, 0, 0);
        btn.setLayoutParams(blp);
        btn.setOnClickListener(v -> action.run());
        row.addView(btn);
        box.addView(row);
        return box;
    }

    void refreshStatus() {
        boolean notif = true;
        if (Build.VERSION.SDK_INT >= 24) {
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            notif = nm == null || nm.areNotificationsEnabled();
        }
        boolean loc = Build.VERSION.SDK_INT < 23 ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        boolean bat = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        setSt(stNotif, notif);
        setSt(stLoc, loc);
        setSt(stBat, bat);
    }

    void setSt(TextView tv, boolean ok) {
        if (tv == null) return;
        tv.setText(ok ? "✓ 已授权" : "未授权");
        tv.setBackgroundResource(ok ? R.drawable.bg_chip_green : R.drawable.bg_chip_rebate);
        tv.setTextColor(ok ? 0xFF2BB673 : 0xFFFFFFFF);
    }

    void openAppSettings() {
        try {
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (Exception ignore) { }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        refreshStatus();
    }
}
