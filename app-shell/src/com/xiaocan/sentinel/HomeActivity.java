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
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 主页: 附近活动浏览 (纯原生渲染, 滚动由系统接管) */
public class HomeActivity extends Activity {

    ListView lv;
    TextView tvLoc, tvStatus, tvEmpty, tvSort;
    EditText etSearch;
    CardAdapter adapter;
    List<CardAdapter.Card> lastCards = new ArrayList<>();
    String[] sortNames = {"默认顺序", "按距离", "按返利", "按名额"};
    SharedPreferences p;
    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService pool = Executors.newSingleThreadExecutor();
    volatile boolean fetching = false;
    int sortMode() { try { return Integer.parseInt(p.getString("sort_mode", "0")); } catch (Exception e) { return 0; } }

    void applySort() {
        int m = sortMode();
        if (m == 1) {
            lastCards.sort((a, b) -> Float.compare(parseFloat(a.distText), parseFloat(b.distText)));
        } else if (m == 2) {
            lastCards.sort((a, b) -> Integer.compare(b.rebateVal, a.rebateVal));
        } else if (m == 3) {
            lastCards.sort((a, b) -> Integer.compare(b.quota, a.quota));
        }
        // m == 0: 后端原始顺序, 不排序
        adapter.setData(lastCards);
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        p = getSharedPreferences(getPackageName(), MODE_PRIVATE);
        // 权限申请统一由首启引导页 / 设置页权限检查负责, 主页不再弹窗
        setContentView(R.layout.activity_home);
        lv = findViewById(R.id.listView);
        tvLoc = findViewById(R.id.tvLoc);
        tvStatus = findViewById(R.id.tvStatus);
        tvEmpty = findViewById(R.id.tvEmpty);
        etSearch = findViewById(R.id.etSearch);
        tvSort = findViewById(R.id.tvSort);
        tvSort.setText(sortNames[sortMode()] + " ▾");
        tvSort.setOnClickListener(v -> {
            int m = (sortMode() + 1) % 4;
            p.edit().putString("sort_mode", String.valueOf(m)).apply();
            tvSort.setText(sortNames[m] + " ▾");
            applySort();
            Toast.makeText(this, "已按" + sortNames[m], Toast.LENGTH_SHORT).show();
        });
        adapter = new CardAdapter(this);
        lv.setAdapter(adapter);
        lv.setEmptyView(tvEmpty);

        findViewById(R.id.btnRefresh).setOnClickListener(v -> locateAndFetch());
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        if (!p.getBoolean("onboarded", false)) {
            startActivity(new Intent(this, OnboardingActivity.class));
        }
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            etSearch.setText("");
            adapter.setQuery("");
        });
        etSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                adapter.setQuery(s.toString());
                findViewById(R.id.btnClear).setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
        });

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
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean running = p.getBoolean(PollService.PREF_RUNNING, false);
        tvStatus.setText(running ? "监听中" : "已停止");
        tvStatus.setBackgroundResource(running ? R.drawable.bg_chip_green : R.drawable.bg_search);
        tvStatus.setTextColor(running ? 0xFF2BB673 : 0xFF9A9A9A);
        tvSort.setText(sortNames[sortMode()] + " ▾");
        if (!fetching && adapter.getCount() == 0) locateAndFetch();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == 1) {
            boolean ok = false;
            for (int r : results) if (r == PackageManager.PERMISSION_GRANTED) ok = true;
            locateAndFetch();
        }
    }

    void locateAndFetch() {
        boolean granted = Build.VERSION.SDK_INT < 23 ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            tvLoc.setText("📍 无定位权限 (设置页可授权), 使用默认位置");
            fetchWith(prefsLat(), prefsLng());
            return;
        }
        tvLoc.setText("📍 定位中…");
        LocHelper.fix(this, (lat, lng, src) -> {
            if (lat != 0 && lng != 0) {
                p.edit().putString("lat", String.valueOf(lat)).putString("lng", String.valueOf(lng)).apply();
                main.post(() -> tvLoc.setText("📍 " + String.format("%.4f, %.4f · %s", lat, lng, src)));
                fetchWith(lat, lng);
            } else {
                main.post(() -> tvLoc.setText("📍 定位失败, 使用默认位置"));
                fetchWith(prefsLat(), prefsLng());
            }
        });
    }

    void fetchWith(double lat, double lng) {
        if (fetching) return;
        fetching = true;
        tvEmpty.setText("正在获取附近活动…");
        tvEmpty.setVisibility(View.VISIBLE);
        lv.setVisibility(View.GONE);
        pool.execute(() -> {
            try {
                XcApi api = buildApi();
                JSONArray items = api.fetchFeedAll(lat, lng, 4);
                List<CardAdapter.Card> cards = buildCards(items);
                Set<Long> fresh = loadFreshIds();
                lastCards = cards;
                main.post(() -> {
                    fetching = false;
                    applySort();
                    lv.setVisibility(View.VISIBLE);
                    tvEmpty.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                main.post(() -> {
                    fetching = false;
                    tvEmpty.setText("获取失败: " + e.getMessage() + "\n点⟳重试");
                    tvEmpty.setVisibility(View.VISIBLE);
                    lv.setVisibility(View.GONE);
                    Toast.makeText(this, "刷新失败", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    List<CardAdapter.Card> buildCards(JSONArray items) throws Exception {
        Set<Long> fresh = loadFreshIds();
        HashMap<String, List<JSONObject>> groups = new HashMap<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            String key = String.valueOf(it.optLong("same_group_id", 0));
            if ("0".equals(key)) key = "s" + it.optLong("store_id", i);
            List<JSONObject> l = groups.get(key);
            if (l == null) { l = new ArrayList<>(); groups.put(key, l); }
            l.add(it);
        }
        List<CardAdapter.Card> cards = new ArrayList<>();
        for (String key : groups.keySet()) {
            List<JSONObject> acts = groups.get(key);
            acts.sort((a, b) -> b.optInt("user_rebate", 0) - a.optInt("user_rebate", 0));
            JSONObject first = acts.get(0);
            JSONObject st = first.optJSONObject("store") != null ? first.optJSONObject("store") : new JSONObject();

            CardAdapter.Card c = new CardAdapter.Card();
            c.key = first.optLong("same_group_id", first.optLong("store_id"));
            c.name = st.optString("name", "未知店铺");
            c.icon = st.optString("icon", "");
            if (c.icon.isEmpty()) c.icon = first.optString("icon", "");
            c.platform = st.optInt("store_platform", first.optInt("store_platform", 0));
            int dist = first.optInt("distance", -1);
            c.distText = dist >= 0 ? (dist >= 1000 ? String.format("%.1fkm", dist / 1000.0) : dist + "m") : "";
            c.acts = acts.size();
            c.extraText = null;
            boolean anyNew = false;
            // 卡片行展示返利最高那个活动; 多活动以「N 个活动」体现
            JSONObject best = acts.get(0);
            int om = best.optInt("order_money", 0);
            int rb = best.optInt("user_rebate", 0);
            // 官方语义 (HAR 真值反推): rebate_condition==99 -> 无需评价; order_money==0 -> 无实付要求
            c.noReview = best.optInt("rebate_condition", 0) == 99;
            c.rebateVal = best.optInt("user_rebate", 0);
            String ry = rb % 100 == 0 ? String.valueOf(rb / 100) : String.format("%.1f", rb / 100.0);
            // 官方规则: 有实付门槛 -> 满X返Y; 无实付要求 -> 最高返Y (返利浮动)
            c.rebateText = om > 0
                    ? "满" + (om % 100 == 0 ? om / 100 : String.format("%.1f", om / 100.0)) + "返" + ry
                    : "最高返" + ry;
            if (om == 0) c.extraText = "无实付要求";
            else {
                JSONObject pc = best.optJSONObject("promotion_condition");
                if (pc != null && pc.length() > 0) c.extraText = "券专享";
            }
            c.timeText = (best.optString("start_time_hour", "00:00")) + "-" + (best.optString("end_time_hour", "23:59")) + " 可抢";
            c.quota = best.optInt("left_number", 0);
            c.quotaPct = Math.min(100, c.quota * 8);
            for (JSONObject a : acts) if (fresh.contains(a.optLong("promotion_id", 0))) anyNew = true;
            c.isNew = anyNew;
            cards.add(c);
        }
        cards.sort((a, b) -> {
            try {
                return Float.compare(parseFloat(a.distText), parseFloat(b.distText));
            } catch (Exception e) { return 0; }
        });
        return cards;
    }

    static float parseFloat(String s) {
        try { return Float.parseFloat(s.replace("km", "").replace("m", "")); } catch (Exception e) { return 99f; }
    }



    Set<Long> loadFreshIds() {
        Set<Long> fresh = new HashSet<>();
        try {
            File f = new File(getFilesDir(), "state.json");
            if (!f.exists()) return fresh;
            FileInputStream fin = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int n = fin.read(buf);
            fin.close();
            JSONObject o = new JSONObject(new String(buf, 0, Math.max(n, 0), "UTF-8"));
            long now = System.currentTimeMillis() / 1000;
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                long fs = o.getJSONObject(k).optLong("fs", 0);
                if (fs > 0 && now - fs < 1800) fresh.add(Long.parseLong(k));
            }
        } catch (Exception ignore) { }
        return fresh;
    }

    double prefsLat() { try { return Double.parseDouble(p.getString("lat", "23.020500")); } catch (Exception e) { return 23.020500; } }
    double prefsLng() { try { return Double.parseDouble(p.getString("lng", "113.751800")); } catch (Exception e) { return 113.751800; } }

    XcApi buildApi() {
        XcApi a = new XcApi();
        a.token = p.getString("token", "");
        a.uid = parseL(p.getString("uid", "0"));
        a.silkId = parseL(p.getString("silk_id", "0"));
        a.cityCode = (int) parseL(p.getString("city_code", "441900"));
        a.sessionId = p.getString("session_id", "");
        return a;
    }

    static long parseL(String s) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; } }
}
