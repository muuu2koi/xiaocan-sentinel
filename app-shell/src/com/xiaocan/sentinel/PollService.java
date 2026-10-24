package com.xiaocan.sentinel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 后台巡检服务: 周期性拉取首页 Feed -> 差分新活动/补货 -> 本地通知
 * 安全设计: 只读接口; 人性化间隔+抖动; 夜间静默; 连续失败退避熔断
 */
public class PollService extends Service {

    public static final String CH_ALERT = "xc_alert";
    public static final String CH_SERVICE = "xc_service";
    public static final String CH_SYS = "xc_sys";
    public static final String TARGET_APP = "com.realtech.xiaocan";
    public static final String PREF_RUNNING = "xc_running";

    final Handler handler = new Handler(Looper.getMainLooper());
    final ExecutorService pool = Executors.newSingleThreadExecutor();
    XcApi api;
    volatile boolean running = false;
    int consecutiveFails = 0;
    long lastPollTs = 0;
    String lastStatus = "";

    // 活动状态表 (持久化 files/state.json, H5 首页也读它标 NEW)
    final HashMap<Long, ItemState> items = new HashMap<>();
    boolean stateLoaded = false;
    boolean hadPriorState = false;

    static class ItemState {
        String store;
        int rebate;      // 分
        int orderMoney;  // 分
        int quota;
        long fs;         // first seen (秒)
        boolean notifiedNew;
        boolean notifiedRestock;
    }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (inQuietHours()) {
                lastStatus = "静默时段";
                updateServiceNotification();
                handler.postDelayed(this, minutesMs(1));
                return;
            }
            pool.execute(() -> doPoll());
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopPolling();
            stopSelf();
            return START_NOT_STICKY;
        }
        startPolling();
        return START_STICKY;
    }

    void startPolling() {
        running = true;
        getSharedPreferences(getPackageName(), MODE_PRIVATE).edit().putBoolean(PREF_RUNNING, true).apply();
        createChannels();
        loadState();
        api = buildApi();
        startForeground(1, buildServiceNotification("初始化..."));
        handler.post(tick);
    }

    void stopPolling() {
        running = false;
        handler.removeCallbacks(tick);
        getSharedPreferences(getPackageName(), MODE_PRIVATE).edit().putBoolean(PREF_RUNNING, false).apply();
    }

    XcApi buildApi() {
        SharedPreferences p = getSharedPreferences(getPackageName(), MODE_PRIVATE);
        XcApi a = new XcApi();
        a.token = p.getString("token", "");
        a.uid = parseLong(p.getString("uid", "0"));
        a.silkId = parseLong(p.getString("silk_id", "0"));
        a.cityCode = (int) parseLong(p.getString("city_code", "441900"));
        a.sessionId = p.getString("session_id", "");
        return a;
    }

    void doPoll() {
        if (!running) return;
        SharedPreferences p = getSharedPreferences(getPackageName(), MODE_PRIVATE);
        double lat = parseD(p.getString("lat", "23.020500"), 23.020500);
        double lng = parseD(p.getString("lng", "113.751800"), 113.751800);
        try {
            JSONArray list = api.fetchFeedAll(lat, lng, 4);
            handleSuccess(list);
            consecutiveFails = 0;
            lastPollTs = System.currentTimeMillis();
        } catch (XcApi.ApiException e) {
            handleFailure("HTTP " + e.code);
        } catch (Exception e) {
            handleFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        updateServiceNotification();
        int baseMin = parseInt(p.getString("interval_min", "3"), 3);
        long delay;
        if (consecutiveFails > 0) {
            long backoff = minutesMs(baseMin) * (1L << Math.min(consecutiveFails, 5));
            delay = Math.min(backoff, minutesMs(30));
        } else {
            delay = (long) (minutesMs(baseMin) * (0.75 + Math.random() * 0.5));
        }
        handler.postDelayed(tick, delay);
    }

    void handleSuccess(JSONArray list) {
        try {
            ensureStateLoaded();
            boolean baseline = !hadPriorState;
            SharedPreferences p = getSharedPreferences(getPackageName(), MODE_PRIVATE);
            boolean chkAll = p.getBoolean("switch_all", true);
            boolean chkKeyword = p.getBoolean("switch_keyword", true);
            String[] keywords = getKeywords();

            HashMap<Long, Boolean> seen = new HashMap<>();
            int newCount = 0;
            for (int i = 0; i < list.length(); i++) {
                JSONObject it = list.getJSONObject(i);
                long pid = it.optLong("promotion_id", 0);
                if (pid == 0) continue;
                seen.put(pid, true);
                ItemState st = items.get(pid);
                boolean isNew = (st == null);
                if (isNew) { st = new ItemState(); st.fs = System.currentTimeMillis() / 1000; items.put(pid, st); newCount++; }
                JSONObject store = it.optJSONObject("store") != null ? it.optJSONObject("store") : new JSONObject();
                String storeName = store.optString("name", "");
                int rebate = it.optInt("user_rebate", 0);
                int orderMoney = it.optInt("order_money", 0);
                int quota = it.optInt("left_number", 0);

                if (baseline && isNew) st.notifiedNew = true;

                if (!baseline && isNew && chkAll && !st.notifiedNew) {
                    st.notifiedNew = true;
                    notifyAlert("🆕 上新 · " + chipText(orderMoney, rebate), storeName, quota);
                }
                if (!baseline && chkKeyword && keywords.length > 0 && hitKeyword(storeName, keywords)) {
                    if (isNew && !st.notifiedNew) {
                        st.notifiedNew = true;
                        notifyAlert("🎯 关键词上新 · " + chipText(orderMoney, rebate), storeName, quota);
                    } else if (!isNew && st.quota == 0 && quota > 0 && !st.notifiedRestock) {
                        st.notifiedRestock = true;
                        notifyAlert("🎯 关键词补货 · " + chipText(orderMoney, rebate), storeName, quota);
                    }
                }
                st.store = storeName;
                st.rebate = rebate;
                st.orderMoney = orderMoney;
                st.quota = quota;
                if (quota == 0) st.notifiedRestock = false;
            }
            if (!seen.isEmpty()) {
                Iterator<Long> it = items.keySet().iterator();
                while (it.hasNext()) { Long k = it.next(); if (!seen.containsKey(k)) it.remove(); }
            }
            hadPriorState = true;
            saveState();
            lastStatus = "正常 · 在售 " + list.length() + " · 新增 " + newCount;
        } catch (Exception e) {
            handleFailure("解析: " + e.getMessage());
        }
    }

    static String chipText(int orderMoney, int rebate) {
        return "满" + (orderMoney / 100) + "返" + fmtYuan(rebate);
    }

    static String fmtYuan(int fen) {
        return fen % 100 == 0 ? String.valueOf(fen / 100) : String.format("%.1f", fen / 100.0);
    }

    void handleFailure(String why) {
        consecutiveFails++;
        lastStatus = "失败x" + consecutiveFails + " (" + why + ")";
        if (consecutiveFails == 10) {
            notifySystem("⏸ 监控已自动暂停", "连续失败: " + why + "\n请打开小蚕哨兵检查网络后重新启动");
            stopPolling();
            stopSelf();
        }
    }

    /** 模糊匹配: 去掉括号/中点/空格等符号后比较, 「茶百道(石排店)」也能命中关键词「茶百道」 */
    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\u4e00-\\u9fa5a-z0-9]", "");
    }

    boolean hitKeyword(String storeName, String[] keywords) {
        String n = norm(storeName);
        for (String k : keywords) if (!k.isEmpty() && n.contains(norm(k))) return true;
        return false;
    }

    String[] getKeywords() {
        String raw = getSharedPreferences(getPackageName(), MODE_PRIVATE).getString("keywords", "");
        String[] arr = raw.split("[,，\\s]+");
        for (int i = 0; i < arr.length; i++) arr[i] = arr[i].trim().toLowerCase(Locale.ROOT);
        return arr;
    }

    boolean inQuietHours() {
        SharedPreferences p = getSharedPreferences(getPackageName(), MODE_PRIVATE);
        String qs = p.getString("quiet_start", "02:00");
        String qe = p.getString("quiet_end", "07:30");
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.ROOT);
            Date now = sdf.parse(sdf.format(new Date()));
            Date s = sdf.parse(qs), e = sdf.parse(qe);
            if (s.before(e)) return now.after(s) && now.before(e);
            return now.after(s) || now.before(e);
        } catch (Exception ex) { return false; }
    }

    // ---------- 持久化 ----------
    File stateFile() { return new File(getFilesDir(), "state.json"); }

    void loadState() {
        try {
            hadPriorState = stateFile().exists();
            if (!hadPriorState) { stateLoaded = true; return; }
            FileInputStream fin = new FileInputStream(stateFile());
            byte[] buf = new byte[(int) stateFile().length()];
            int n = fin.read(buf);
            fin.close();
            JSONObject o = new JSONObject(new String(buf, 0, Math.max(n, 0), "UTF-8"));
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                JSONObject v = o.getJSONObject(k);
                ItemState st = new ItemState();
                st.store = v.optString("store", "");
                st.rebate = v.optInt("rebate", 0);
                st.orderMoney = v.optInt("om", 0);
                st.quota = v.optInt("quota", 0);
                st.fs = v.optLong("fs", 0);
                st.notifiedNew = v.optBoolean("nn", false);
                st.notifiedRestock = v.optBoolean("nr", false);
                items.put(Long.parseLong(k), st);
            }
        } catch (Exception ignore) { }
        stateLoaded = true;
    }

    void ensureStateLoaded() { if (!stateLoaded) loadState(); }

    void saveState() {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<Long, ItemState> e : items.entrySet()) {
                ItemState st = e.getValue();
                JSONObject v = new JSONObject();
                v.put("store", st.store);
                v.put("rebate", st.rebate);
                v.put("om", st.orderMoney);
                v.put("quota", st.quota);
                v.put("fs", st.fs);
                v.put("nn", st.notifiedNew);
                v.put("nr", st.notifiedRestock);
                o.put(String.valueOf(e.getKey()), v);
            }
            FileOutputStream fo = new FileOutputStream(stateFile());
            fo.write(o.toString().getBytes("UTF-8"));
            fo.close();
        } catch (Exception ignore) { }
    }

    // ---------- 通知 ----------
    void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "活动提醒", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CH_SERVICE, "监控服务", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_SYS, "系统提醒", NotificationManager.IMPORTANCE_DEFAULT));
    }

    void notifyAlert(String title, String storeName, int quota) {
        String text = storeName + (quota > 0 ? " · 还剩" + quota + "份" : " · 已抢光");
        Notification.Builder b = new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true);
        Intent launch = getPackageManager().getLaunchIntentForPackage(TARGET_APP);
        if (launch != null) b.setContentIntent(PendingIntent.getActivity(this, (int) System.currentTimeMillis(), launch, PendingIntent.FLAG_IMMUTABLE));
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), b.build());
    }

    void notifySystem(String title, String text) {
        Notification.Builder b = new Notification.Builder(this, CH_SYS)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(1001, b.build());
    }

    void updateServiceNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(1, buildServiceNotification(lastStatus));
    }

    Notification buildServiceNotification(String status) {
        Intent open = new Intent(this, HomeActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("小蚕哨兵监控中")
                .setContentText((lastPollTs == 0 ? "未巡检" : new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(lastPollTs))) + " · " + status)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    static long parseLong(String s) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; } }
    static int parseInt(String s, int d) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; } }
    static double parseD(String s, double d) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return d; } }
    static long minutesMs(int m) { return m * 60_000L; }
}
