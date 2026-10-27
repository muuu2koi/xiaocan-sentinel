package com.xiaocan.sentinel;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 设置页: 权限检查 + 监听配置 (分区卡片式) */
public class SettingsActivity extends Activity {

    SharedPreferences p;
    final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    LinearLayout chipsBox;
    EditText kwInput, etQs, etQe;
    SeekBar seek;
    TextView tvInterval, tvRunTitle, tvRunSub, tvRunBtn, tvNotif, tvLoc, tvBat;
    Switch swAll, swKw, swQuiet;
    LinearLayout quietBox;
    boolean running;
    final List<String> keywords = new ArrayList<>();
    EditText etMinRebate;
    CheckBox cbMt, cbTb, cbJd;
    TextView tvLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        p = getSharedPreferences(getPackageName(), MODE_PRIVATE);
        if (!p.contains("uid")) {
            p.edit()
             .putString("uid", "0")
             .putString("silk_id", "0")
             .putString("city_code", "441900")
             .putString("lat", "23.020500")
             .putString("lng", "113.751800")
             .putString("session_id", "")
             .putString("interval_min", "3")
             .putString("quiet_start", "02:00")
             .putString("quiet_end", "07:30")
             .putBoolean("switch_all", true)
             .putBoolean("switch_keyword", true)
             .apply();
        }
        buildUi();
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = p.getBoolean(PollService.PREF_RUNNING, false);
        renderRun();
        refreshPerms();
        if (tvLog != null) tvLog.setText(new PollService().readLogStatic(getFilesDir(), 20));
    }

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        root.addView(card(runCard()));
        root.addView(card(permCard()));
        root.addView(card(ruleCard()));
        root.addView(card(kwCard()));
        root.addView(card(filterCard()));
        root.addView(card(freqCard()));
        root.addView(card(quietCard()));
        root.addView(card(logCard()));

        TextView tips = new TextView(this);
        tips.setTextSize(12);
        tips.setTextColor(0xFFB0B0B0);
        tips.setLineSpacing(0, 1.3f);
        tips.setText("只读浏览, 不自动下单 · 通知点击直达小蚕 · 连续异常自动暂停");
        tips.setPadding(0, (int) (14 * getResources().getDisplayMetrics().density), 0, 0);
        root.addView(tips);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    LinearLayout card(View inner) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        box.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int m = (int) (10 * getResources().getDisplayMetrics().density);
        lp.setMargins(0, m, 0, 0);
        box.setLayoutParams(lp);
        box.addView(inner);
        return box;
    }

    // ---- 运行状态卡 ----
    View runCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvRunTitle = new TextView(this);
        tvRunTitle.setTextSize(17);
        tvRunTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvRunTitle.setTextColor(0xFF1A1A1A);
        info.addView(tvRunTitle);
        tvRunSub = new TextView(this);
        tvRunSub.setTextSize(12);
        tvRunSub.setTextColor(0xFF9A9A9A);
        tvRunSub.setText("启动后自动巡检上新并推送");
        info.addView(tvRunSub);
        box.addView(info);

        tvRunBtn = new TextView(this);
        tvRunBtn.setBackgroundResource(R.drawable.bg_btn_rebate);
        tvRunBtn.setTextColor(0xFFFFFFFF);
        tvRunBtn.setTextSize(14);
        tvRunBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        tvRunBtn.setPadding((int) (26 * getResources().getDisplayMetrics().density), 10,
                (int) (26 * getResources().getDisplayMetrics().density), 10);
        tvRunBtn.setOnClickListener(v -> toggleRun());
        box.addView(tvRunBtn);
        return box;
    }

    void toggleRun() {
        if (running) {
            Intent i = new Intent(this, PollService.class);
            i.setAction("STOP");
            startService(i);
            running = false;
            toast("监控已停止");
        } else {
            persistAll();
            Intent i = new Intent(this, PollService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            running = true;
            toast("监控已启动");
        }
        renderRun();
    }

    void renderRun() {
        tvRunTitle.setText(running ? "监控运行中" : "监控已停止");
        tvRunSub.setText(running ? "自动巡检上新并推送, 通知会响铃" : "启动后才会推送上新提醒");
        tvRunBtn.setText(running ? "停止" : "启动");
        if (running) {
            tvRunBtn.setBackgroundResource(R.drawable.bg_search);
            tvRunBtn.setTextColor(0xFF666666);
        } else {
            tvRunBtn.setBackgroundResource(R.drawable.bg_btn_rebate);
            tvRunBtn.setTextColor(0xFFFFFFFF);
        }
    }

    // ---- 权限检查卡 ----

    View permCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(secTitle("权限检查 (后台监听必读)"));

        TextView[] stN = new TextView[1];
        TextView[] stL = new TextView[1];
        TextView[] stB = new TextView[1];
        box.addView(permRow("通知权限", "不开启将收不到任何提醒", stN, () -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
            }
            main.postDelayed(this::refreshPerms, 1500);
        }));
        box.addView(permRow("定位权限", "用于按手机所在位置巡检附近活动", stL, () -> {
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 4);
            }
            main.postDelayed(this::refreshPerms, 1500);
        }));
        box.addView(permRow("电池优化白名单", "防止系统省电策略杀掉后台巡检", stB, () -> {
            try {
                android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) { openAppSettings(); }
            main.postDelayed(this::refreshPerms, 1500);
        }));

        TextView miui = new TextView(this);
        miui.setTextSize(11.5f);
        miui.setTextColor(0xFF808080);
        miui.setLineSpacing(0, 1.3f);
        miui.setText("小米/MIUI 手机建议手动开启: 系统设置 → 应用设置 → 应用管理 → 小蚕哨兵 → 打开「自启动」「后台弹出界面」「锁屏显示」, 省电策略设为无限制");
        miui.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, (int) (6 * getResources().getDisplayMetrics().density));
        box.addView(miui);

        TextView openBtn = new TextView(this);
        openBtn.setText("打开应用设置");
        openBtn.setTextColor(0xFFFF5F2E);
        openBtn.setTextSize(13);
        openBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        openBtn.setOnClickListener(v -> openAppSettings());
        box.addView(openBtn);

        tvNotif = stN[0];
        tvLoc = stL[0];
        tvBat = stB[0];
        return box;
    }

    LinearLayout permRow(String title, String sub, TextView[] statusOut, Runnable fix) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, 0);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView t1 = new TextView(this);
        t1.setTextSize(14);
        t1.setTextColor(0xFF333333);
        t1.setText(title);
        info.addView(t1);
        TextView t2 = new TextView(this);
        t2.setTextSize(11);
        t2.setTextColor(0xFFA0A0A0);
        t2.setText(sub);
        info.addView(t2);
        row.addView(info);

        TextView st = new TextView(this);
        st.setTextSize(11);
        st.setPadding(10, 3, 10, 3);
        statusOut[0] = st;
        row.addView(st);

        TextView btn = new TextView(this);
        btn.setText("去开启");
        btn.setTextColor(0xFFFF5F2E);
        btn.setTextSize(13);
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        btn.setBackgroundResource(R.drawable.bg_search);
        btn.setPadding(14, 6, 14, 6);
        btn.setOnClickListener(v -> fix.run());
        row.addView(btn);
        return row;
    }

    void refreshPerms() {
        boolean notif = true;
        if (Build.VERSION.SDK_INT >= 24) {
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            notif = nm == null || nm.areNotificationsEnabled();
        }
        boolean loc = Build.VERSION.SDK_INT < 23 ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        boolean bat = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        applyPerm(tvNotif, notif);
        applyPerm(tvLoc, loc);
        applyPerm(tvBat, bat);
    }

    void applyPerm(TextView ref, boolean ok) {
        if (ref == null) return;
        ref.setText(ok ? "已开启" : "未开启");
        ref.setBackgroundResource(ok ? R.drawable.bg_chip_green : R.drawable.bg_chip_rebate);
        ref.setTextColor(ok ? 0xFF2BB673 : 0xFFFFFFFF);
    }

    // ---- 提醒规则卡 ----
    View ruleCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(secTitle("提醒规则"));

        box.addView(switchRow("全部上新提醒", "巡检发现任何新活动都推送", p.getBoolean("switch_all", true), (btn, checked) -> {
            p.edit().putBoolean("switch_all", checked).apply();
            toast(checked ? "已开启全部上新提醒" : "已关闭全部上新提醒");
        }, out -> swAll = out));
        box.addView(switchRow("关键词提醒", "只推送命中关键词的店铺, 含补货提醒", p.getBoolean("switch_keyword", true), (btn, checked) -> {
            p.edit().putBoolean("switch_keyword", checked).apply();
            toast(checked ? "已开启关键词提醒" : "已关闭关键词提醒");
        }, out -> swKw = out));
        return box;
    }

    LinearLayout switchRow(String title, String sub, boolean checked, android.widget.CompoundButton.OnCheckedChangeListener l,
                           java.util.function.Consumer<Switch> out) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, 0);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView t1 = new TextView(this);
        t1.setTextSize(14);
        t1.setTextColor(0xFF333333);
        t1.setText(title);
        info.addView(t1);
        TextView t2 = new TextView(this);
        t2.setTextSize(11);
        t2.setTextColor(0xFFA0A0A0);
        t2.setText(sub);
        info.addView(t2);
        row.addView(info);
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);
        out.accept(sw);
        return row;
    }

    // ---- 关键词卡 ----
    View kwCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(secTitle("我的关键词 (可同时监听多家店)"));

        chipsBox = new LinearLayout(this);
        chipsBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(chipsBox);

        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        addRow.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, 0);
        kwInput = new EditText(this);
        kwInput.setHint("输入店铺名, 如: 茶百道");
        kwInput.setTextSize(13);
        kwInput.setSingleLine();
        kwInput.setBackgroundResource(R.drawable.bg_search);
        kwInput.setPadding(16, 10, 16, 10);
        kwInput.setTextColor(0xFF333333);
        kwInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        addRow.addView(kwInput, klp);
        TextView add = new TextView(this);
        add.setText(" 添加 ");
        add.setTextColor(0xFFFFFFFF);
        add.setTextSize(13);
        add.setTypeface(null, android.graphics.Typeface.BOLD);
        add.setBackgroundResource(R.drawable.bg_btn_rebate);
        add.setPadding(20, 10, 20, 10);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.setMargins(12, 0, 0, 0);
        add.setLayoutParams(alp);
        add.setOnClickListener(v -> addKeyword());
        addRow.addView(add);
        box.addView(addRow);

        TextView hint = new TextView(this);
        hint.setTextSize(11);
        hint.setTextColor(0xFFB0B0B0);
        hint.setText("模糊匹配: 忽略括号/中点/空格差异, 「茶百道」也能命中「茶百道(石排店)」");
        hint.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, 0);
        box.addView(hint);
        return box;
    }

    void addKeyword() {
        String k = kwInput.getText().toString().trim();
        if (k.isEmpty() || keywords.contains(k)) { kwInput.setText(""); return; }
        keywords.add(k);
        kwInput.setText("");
        persistKeywords();
        renderChips();
        toast("已添加 " + k);
    }

    void removeKeyword(int i) {
        keywords.remove(i);
        persistKeywords();
        renderChips();
    }

    void renderChips() {
        chipsBox.removeAllViews();
        if (keywords.isEmpty()) return;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int dpi = (int) (8 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < keywords.size(); i++) {
            String k = keywords.get(i);
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
            chip.setBackgroundResource(R.drawable.bg_chip_orange);
            chip.setPadding(dpi + 4, dpi, dpi + 4, dpi);
            TextView t = new TextView(this);
            t.setText(k);
            t.setTextColor(0xFFFF5F2E);
            t.setTextSize(13);
            chip.addView(t);
            TextView x = new TextView(this);
            x.setText(" ×");
            x.setTextColor(0xFFFF9A7E);
            x.setTextSize(15);
            x.setTypeface(null, android.graphics.Typeface.BOLD);
            int idx = i;
            x.setOnClickListener(v -> removeKeyword(idx));
            chip.addView(x);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 4, 10, 4);
            row.addView(chip, lp);
            if ((i + 1) % 3 == 0) { chipsBox.addView(row); row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); }
        }
        if (row.getChildCount() > 0) chipsBox.addView(row);
    }

    // ---- 筛选卡 ----
    View filterCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(secTitle("上新筛选 (只影响全部上新提醒)"));

        TextView l1 = new TextView(this);
        l1.setTextSize(13);
        l1.setTextColor(0xFF666666);
        l1.setText("最低返利 (元, 0 = 不限)");
        l1.setPadding(0, (int) (4 * getResources().getDisplayMetrics().density), 0, 0);
        box.addView(l1);
        etMinRebate = new EditText(this);
        etMinRebate.setTextSize(14);
        etMinRebate.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etMinRebate.setSingleLine();
        etMinRebate.setBackgroundResource(R.drawable.bg_search);
        etMinRebate.setPadding(16, 10, 16, 10);
        try {
            int fen = Integer.parseInt(p.getString("min_rebate_fen", "0"));
            etMinRebate.setText(fen > 0 ? String.valueOf(fen / 100.0) : "0");
        } catch (Exception e) { etMinRebate.setText("0"); }
        etMinRebate.setOnFocusChangeListener((v, f) -> { if (!f) saveFilter(); });
        box.addView(etMinRebate);

        TextView l2 = new TextView(this);
        l2.setTextSize(13);
        l2.setTextColor(0xFF666666);
        l2.setText("只监听这些平台 (全不选 = 不限)");
        l2.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, 0);
        box.addView(l2);
        java.util.Set<String> pf = platformSet();
        cbMt = platCheck("美团", pf.contains("1"));
        cbTb = platCheck("淘宝闪购", pf.contains("2") || pf.contains("4"));
        cbJd = platCheck("京东", pf.contains("3"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(wrapCheck(cbMt));
        row.addView(wrapCheck(cbTb));
        row.addView(wrapCheck(cbJd));
        box.addView(row);
        return box;
    }

    CheckBox platCheck(String label, boolean checked) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextSize(13);
        c.setChecked(checked);
        c.setOnCheckedChangeListener((b, w) -> saveFilter());
        return c;
    }

    LinearLayout wrapCheck(CheckBox c) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.addView(c);
        return b;
    }

    java.util.Set<String> platformSet() {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (String k : p.getString("platforms", "").split(",")) {
            k = k.trim();
            if (!k.isEmpty()) s.add(k);
        }
        return s;
    }

    void saveFilter() {
        double yuan = 0;
        try { yuan = Double.parseDouble(etMinRebate.getText().toString()); } catch (Exception ignore) { }
        int fen = (int) Math.round(yuan * 100);
        java.util.Set<String> s = new java.util.HashSet<>();
        if (cbMt.isChecked()) s.add("1");
        if (cbTb.isChecked()) { s.add("2"); s.add("4"); }
        if (cbJd.isChecked()) s.add("3");
        p.edit().putString("min_rebate_fen", String.valueOf(fen))
                .putString("platforms", String.join(",", s)).apply();
        toast("筛选已保存");
    }

    // ---- 巡检动态卡 ----
    View logCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(secTitle("巡检动态 (最近记录)"));
        tvLog = new TextView(this);
        tvLog.setTextSize(11);
        tvLog.setTextColor(0xFF555555);
        tvLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvLog.setLineSpacing(0, 1.2f);
        box.addView(tvLog);
        return box;
    }

    // ---- 频率卡 ----
    View freqCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(secTitle("巡检频率"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, 0);
        seek = new SeekBar(this);
        seek.setMax(59);
        seek.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean f) { tvInterval.setText((v + 1) + " 分钟"); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { p.edit().putString("interval_min", String.valueOf(s.getProgress() + 1)).apply(); }
        });
        row.addView(seek);
        tvInterval = new TextView(this);
        tvInterval.setTextColor(0xFFFF5F2E);
        tvInterval.setTextSize(14);
        tvInterval.setTypeface(null, android.graphics.Typeface.BOLD);
        tvInterval.setPadding(16, 0, 0, 0);
        row.addView(tvInterval);
        box.addView(row);
        TextView hint = new TextView(this);
        hint.setTextSize(11);
        hint.setTextColor(0xFFB0B0B0);
        hint.setText("巡检间隔自带随机抖动, 更像真人浏览 (上限 60 分钟)");
        hint.setPadding(0, (int) (6 * getResources().getDisplayMetrics().density), 0, 0);
        box.addView(hint);

        TextView l3 = new TextView(this);
        l3.setTextSize(13);
        l3.setTextColor(0xFF666666);
        int cur = Math.max(1, Math.min(4, parseInt2(p.getString("poll_pages", "2"), 2)));
        l3.setText("巡检深度: " + cur + " 页 (约 " + (cur * 20) + " 条)");
        l3.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, 0);
        box.addView(l3);
        SeekBar seekPages = new SeekBar(this);
        seekPages.setMax(3);
        seekPages.setProgress(cur - 1);
        seekPages.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean f) { l3.setText("巡检深度: " + (v + 1) + " 页 (约 " + ((v + 1) * 20) + " 条)"); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) {
                p.edit().putString("poll_pages", String.valueOf(s.getProgress() + 1)).apply();
                toast("巡检深度已保存, 越少越省电");
            }
        });
        box.addView(seekPages);
        TextView hint2 = new TextView(this);
        hint2.setTextSize(11);
        hint2.setTextColor(0xFFB0B0B0);
        hint2.setText("页数越少越省电; 新活动一般集中在前两页");
        hint2.setPadding(0, (int) (6 * getResources().getDisplayMetrics().density), 0, 0);
        box.addView(hint2);
        return box;
    }

    static int parseInt2(String s, int dft) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return dft; } }

    // ---- 免打扰卡 ----
    View quietCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(switchRow("夜间免打扰", "静默时段完全不发请求, 也不会响铃", true, (btn, checked) -> {
            quietBox.setVisibility(checked ? View.VISIBLE : View.GONE);
            saveQuiet();
        }, out -> swQuiet = out));
        quietBox = new LinearLayout(this);
        quietBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (6 * getResources().getDisplayMetrics().density), 0, 0);
        TextView l1 = new TextView(this);
        l1.setText("开始"); l1.setTextSize(13); l1.setTextColor(0xFF666666);
        etQs = timeInput();
        TextView l2 = new TextView(this);
        l2.setText("结束"); l2.setTextSize(13); l2.setTextColor(0xFF666666);
        etQe = timeInput();
        row.addView(l1);
        row.addView(etQs);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) etQs.getLayoutParams();
        lp.setMargins(10, 0, 24, 0);
        etQs.setLayoutParams(lp);
        row.addView(l2);
        row.addView(etQe);
        quietBox.addView(row);
        TextView hint = new TextView(this);
        hint.setTextSize(11);
        hint.setTextColor(0xFFB0B0B0);
        hint.setText("格式 HH:mm, 支持 02:00-07:30 这类跨午夜时段");
        hint.setPadding(0, (int) (6 * getResources().getDisplayMetrics().density), 0, 0);
        quietBox.addView(hint);
        box.addView(quietBox);
        return box;
    }

    EditText timeInput() {
        EditText et = new EditText(this);
        et.setTextSize(14);
        et.setTextColor(0xFF333333);
        et.setSingleLine();
        et.setBackgroundResource(R.drawable.bg_search);
        et.setPadding(14, 10, 14, 10);
        et.setWidth((int) (84 * getResources().getDisplayMetrics().density));
        et.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        return et;
    }

    void saveQuiet() {
        p.edit().putString("quiet_start", etQs.getText().toString().trim())
                .putString("quiet_end", etQe.getText().toString().trim()).apply();
        toast("免打扰时段已保存");
    }

    void persistKeywords() {
        StringBuilder sb = new StringBuilder();
        for (String k : keywords) { if (sb.length() > 0) sb.append(','); sb.append(k); }
        p.edit().putString("keywords", sb.toString()).apply();
    }

    void persistAll() { persistKeywords(); }

    void reload() {
        keywords.clear();
        for (String k : p.getString("keywords", "").split("[,，\\s]+")) {
            k = k.trim();
            if (!k.isEmpty()) keywords.add(k);
        }
        renderChips();
        seek.setProgress(Integer.parseInt(p.getString("interval_min", "3")) - 1);
        tvInterval.setText(p.getString("interval_min", "3") + " 分钟");
        etQs.setText(p.getString("quiet_start", "02:00"));
        etQe.setText(p.getString("quiet_end", "07:30"));
        swAll.setChecked(p.getBoolean("switch_all", true));
        swKw.setChecked(p.getBoolean("switch_keyword", true));
        boolean q = !p.getString("quiet_start", "").isEmpty() || !p.getString("quiet_end", "").isEmpty();
        swQuiet.setChecked(q);
        quietBox.setVisibility(q ? View.VISIBLE : View.GONE);
    }

    // ---- 公共 ----
    TextView secTitle(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(15);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(0xFF1A1A1A);
        tv.setPadding(0, 0, 0, (int) (6 * getResources().getDisplayMetrics().density));
        return tv;
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    void openAppSettings() {
        try {
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (Exception ignore) { }
    }
}
