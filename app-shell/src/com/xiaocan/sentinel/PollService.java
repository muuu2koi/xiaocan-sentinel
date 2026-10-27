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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    final java.util.LinkedHashSet<Long> archive = new java.util.LinkedHashSet<>(); // 已见归档: 防止清理后再出现被重复推上新
    boolean stateLoaded = false;
    boolean hadPriorState = false;

    static class ItemState {
        String store;
        int rebate;      // 分
        int orderMoney;  // 分
        int quota;
        long fs;         // first seen (秒)
        long ls;         // last seen (秒): 活动短暂离开列表不立即删除, 防止重复推送
        boolean notifiedNew;
        boolean notifiedRestock;
    }

    static final String ACTION_POLL = "POLL";
    android.app.PendingIntent pollPi;

    /** 闹钟精准唤醒 (RTC_WAKEUP, 5 分钟窗口批量友好): Doze 下也不会漏巡检 */
    void scheduleNext(long delayMs) {
        android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(this, PollService.class).setAction(ACTION_POLL);
        // getForegroundService: 进程被划掉后, 闹钟也能从后台合法拉起前台服务 (getService 在 Android 8+ 后台会被拒)
        pollPi = android.app.PendingIntent.getForegroundService(this, 1, i,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        am.setWindow(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs,
                5 * 60_000L, pollPi);
    }

    void cancelAlarm() {
        if (pollPi != null) {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null) am.cancel(pollPi);
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopPolling();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running && intent != null && ACTION_POLL.equals(intent.getAction())) {
            // 闹钟唤醒: 拿短时 WakeLock 做完巡检立刻释放, CPU 其余时间休眠
            pool.execute(() -> {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                android.os.PowerManager.WakeLock wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "xc:poll");
                try {
                    wl.acquire(45_000);
                    doPoll();
                } finally {
                    try { wl.release(); } catch (Exception ignore) { }
                }
            });
            return START_STICKY;
        }
        startPolling();
        return START_STICKY;
    }

    void startPolling() {
        running = true;
        getSharedPreferences(getPackageName(), MODE_PRIVATE).edit().putBoolean(PREF_RUNNING, true).apply();
        createChannels();
        loadState();
        loadArchive();
        api = buildApi();
        startForeground(1, buildServiceNotification("初始化..."));
        scheduleNext(1500);
    }

    void stopPolling() {
        running = false;
        cancelAlarm();
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
        if (inQuietHours()) {
            // 静默时段: 不发请求也不唤醒, 直接把下一次闹钟排到静默结束后
            lastStatus = "静默时段";
            updateServiceNotification();
            scheduleNext(msUntilQuietEnd());
            appendLog("静默时段, 跳过本次巡检");
            return;
        }
        try {
            int pages = Math.max(1, Math.min(4, parseInt(p.getString("poll_pages", "2"), 2)));
            JSONArray list = api.fetchFeedAll(lat, lng, pages);
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
        scheduleNext(delay);
    }

    /** 距静默结束还有多少毫秒 (至少 60s, 避免边界抖动) */
    long msUntilQuietEnd() {
        try {
            SharedPreferences p = getSharedPreferences(getPackageName(), MODE_PRIVATE);
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.ROOT);
            Date now = sdf.parse(sdf.format(new Date()));
            Date end = sdf.parse(p.getString("quiet_end", "07:30"));
            long diff = end.getTime() - now.getTime();
            if (diff < 60_000L) diff += 24 * 3600_000L; // 跨午夜
            return Math.max(diff, 60_000L);
        } catch (Exception e) { return minutesMs(5); }
    }

    void handleSuccess(JSONArray list) {
        try {
            ensureStateLoaded();
            boolean baseline = !hadPriorState;
            SharedPreferences p = getSharedPreferences(getPackageName(), MODE_PRIVATE);
            boolean chkAll = p.getBoolean("switch_all", true);
            boolean chkKeyword = p.getBoolean("switch_keyword", true);
            String[] keywords = getKeywords();
            int minRebate = parseInt(p.getString("min_rebate_fen", "0"), 0);
            java.util.Set<String> plats = getPlatforms();
            long nowSec = System.currentTimeMillis() / 1000;

            HashMap<Long, Boolean> seen = new HashMap<>();
            List<String[]> batchNew = new ArrayList<>(); // {store, chip, quota}
            int newCount = 0;
            for (int i = 0; i < list.length(); i++) {
                JSONObject it = list.getJSONObject(i);
                long pid = it.optLong("promotion_id", 0);
                if (pid == 0) continue;
                seen.put(pid, true);
                ItemState st = items.get(pid);
                boolean archived = archive.contains(pid);   // 曾出现过且已过保留期
                boolean isNew = (st == null);
                if (isNew) {
                    st = new ItemState();
                    st.fs = nowSec;
                    st.ls = nowSec;
                    items.put(pid, st);
                    if (!archived) newCount++;
                    // 归档回归的活动不推「上新」, 但补货监控照常生效
                    if (archived || baseline) st.notifiedNew = true;
                }
                JSONObject store = it.optJSONObject("store") != null ? it.optJSONObject("store") : new JSONObject();
                String storeName = store.optString("name", "");
                int rebate = it.optInt("user_rebate", 0);
                int orderMoney = it.optInt("order_money", 0);
                int quota = it.optInt("left_number", 0);
                int platform = store.optInt("store_platform", it.optInt("store_platform", 0));
                st.ls = nowSec;

                if (baseline && isNew) st.notifiedNew = true;

                // 全部上新提醒: 尊重平台/最低返利筛选, 批量合并推送; 名额为 0 的新活动也如实计入
                if (!baseline && isNew && chkAll && !st.notifiedNew) {
                    boolean platOk = plats.isEmpty() || plats.contains(String.valueOf(platform));
                    boolean rebateOk = minRebate <= 0 || rebate >= minRebate;
                    if (platOk && rebateOk) {
                        st.notifiedNew = true;
                        batchNew.add(new String[]{storeName, chipText(orderMoney, rebate), String.valueOf(quota)});
                    }
                }
                // 关键词提醒: 精准命中, 不受筛选限制
                // 上新: 名额为 0 时只标记不推 (没有意义), 等补货 (0 -> 有) 时推「补货」
                if (!baseline && chkKeyword && keywords.length > 0 && hitKeyword(storeName, keywords)) {
                    if (isNew) {
                        if (!st.notifiedNew && quota > 0) {
                            st.notifiedNew = true;
                            notifyAlert("🎯 关键词上新 · " + chipText(orderMoney, rebate), storeName, quota);
                        } else if (!st.notifiedNew) {
                            st.notifiedNew = true;  // 秒没的新活动: 静默标记, 静候补货
                        }
                    } else if (st.quota == 0 && quota > 0 && !st.notifiedRestock) {
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

            // 批量通知: 3 条以内逐条, 超过合并成一条汇总 (解决「一到刷新时间推一堆」)
            if (!batchNew.isEmpty()) {
                if (batchNew.size() <= 3) {
                    for (String[] b : batchNew) {
                        notifyAlert("🆕 上新 · " + b[1], b[0], Integer.parseInt(b[2]));
                    }
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < batchNew.size() && i < 3; i++) {
                        sb.append(batchNew.get(i)[0]).append(" ").append(batchNew.get(i)[1]).append("\n");
                    }
                    sb.append("… 共 ").append(batchNew.size()).append(" 个新活动");
                    notifySystem("🆕 发现 " + batchNew.size() + " 个新活动", sb.toString().trim());
                }
            }

            // 保留期: 48 小时内出现过的活动不删除 (防止列表抖动导致重复推送), 过期才清理
            Iterator<Long> it = items.keySet().iterator();
            int pruned = 0;
            while (it.hasNext()) {
                Long k = it.next();
                ItemState st = items.get(k);
                if (!seen.containsKey(k) && nowSec - st.ls > 48 * 3600) {
                    it.remove();
                    pruned++;
                    archive.add(k);   // 进入已见归档, 再出现不重复推上新
                }
            }
            while (archive.size() > 8000) {
                Iterator<Long> ai = archive.iterator();
                if (ai.hasNext()) { ai.next(); ai.remove(); } else break;
            }
            saveArchive();

            hadPriorState = true;
            saveState();
            lastStatus = "在售 " + list.length() + " · 新增 " + newCount + (pruned > 0 ? " · 清理 " + pruned : "");
            appendLog("巡检 在售 " + list.length() + " · 新增 " + newCount + (baseline ? " (首巡基线, 不推送)" : ""));
        } catch (Exception e) {
            handleFailure("解析: " + e.getMessage());
        }
    }

    java.util.Set<String> getPlatforms() {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (String k : getSharedPreferences(getPackageName(), MODE_PRIVATE).getString("platforms", "").split(",")) {
            k = k.trim();
            if (!k.isEmpty()) s.add(k);
        }
        return s;
    }

    // 巡检日志: 最近 100 条, 设置页可视化
    File logFile() { return new File(getFilesDir(), "poll_log.txt"); }

    File archiveFile() { return new File(getFilesDir(), "archive.json"); }

    void loadArchive() {
        try {
            java.io.FileInputStream fi = new java.io.FileInputStream(archiveFile());
            byte[] all = new byte[(int) archiveFile().length()];
            int n = fi.read(all);
            fi.close();
            org.json.JSONArray a = new org.json.JSONArray(new String(all, 0, Math.max(n, 0), "UTF-8"));
            for (int i = 0; i < a.length(); i++) archive.add(a.optLong(i));
        } catch (Exception ignore) { }
    }

    void saveArchive() {
        try {
            org.json.JSONArray a = new org.json.JSONArray();
            for (Long id : archive) a.put(id);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(archiveFile());
            fo.write(a.toString().getBytes("UTF-8"));
            fo.close();
        } catch (Exception ignore) { }
    }

    void appendLog(String line) {
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.ROOT);
            String line2 = sdf.format(new Date()) + "  " + line + "\n";
            java.io.FileOutputStream fo = new java.io.FileOutputStream(logFile(), true);
            fo.write(line2.getBytes("UTF-8"));
            fo.close();
            // 裁剪到最近 100 行
            java.io.FileInputStream fi = new java.io.FileInputStream(logFile());
            byte[] all = new byte[(int) logFile().length()];
            int n = fi.read(all);
            fi.close();
            String[] lines = new String(all, 0, Math.max(n, 0), "UTF-8").split("\n");
            if (lines.length > 100) {
                java.io.FileOutputStream fo2 = new java.io.FileOutputStream(logFile());
                StringBuilder sb = new StringBuilder();
                for (int i = lines.length - 100; i < lines.length; i++) sb.append(lines[i]).append('\n');
                fo2.write(sb.toString().getBytes("UTF-8"));
                fo2.close();
            }
        } catch (Exception ignore) { }
    }

    public static String readLogStatic(java.io.File filesDir, int maxLines) {
        try {
            java.io.FileInputStream fi = new java.io.FileInputStream(new java.io.File(filesDir, "poll_log.txt"));
            byte[] all = new byte[(int) new java.io.File(filesDir, "poll_log.txt").length()];
            int n = fi.read(all);
            fi.close();
            String[] lines = new String(all, 0, Math.max(n, 0), "UTF-8").split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - 1; i >= 0 && i >= lines.length - maxLines; i--) sb.append(lines[i]).append('\n');
            return sb.toString();
        } catch (Exception e) { return "暂无巡检记录, 启动监控后这里会显示每次巡检结果"; }
    }

    String readLog(int maxLines) {
        try {
            java.io.FileInputStream fi = new java.io.FileInputStream(logFile());
            byte[] all = new byte[(int) logFile().length()];
            int n = fi.read(all);
            fi.close();
            String[] lines = new String(all, 0, Math.max(n, 0), "UTF-8").split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - 1; i >= 0 && i >= lines.length - maxLines; i--) sb.append(lines[i]).append('\n');
            return sb.toString();
        } catch (Exception e) { return "暂无巡检记录"; }
    }

    static String chipText(int orderMoney, int rebate) {
        String ry = fmtYuan(rebate);
        return orderMoney > 0 ? "满" + fmtYuan(orderMoney) + "返" + ry : "最高返" + ry;
    }

    static String fmtYuan(int fen) {
        return fen % 100 == 0 ? String.valueOf(fen / 100) : String.format("%.1f", fen / 100.0);
    }

    void handleFailure(String why) {
        consecutiveFails++;
        lastStatus = "失败x" + consecutiveFails + " (" + why + ")";
        appendLog("失败 " + why);
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
                st.ls = v.optLong("ls", v.optLong("fs", 0));
                if (st.ls == 0) st.ls = System.currentTimeMillis() / 1000;
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
                v.put("ls", st.ls);
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
