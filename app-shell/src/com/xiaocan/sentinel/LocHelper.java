package com.xiaocan.sentinel;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

/** 定位助手: GPS/网络双通道, 12 秒超时回退 lastKnown */
public class LocHelper {

    public interface Cb { void onFix(double lat, double lng, String src); }

    public static void fix(Context c, Cb cb) {
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        Handler h = new Handler(Looper.getMainLooper());
        final boolean[] done = {false};
        java.util.concurrent.Executor exec = command -> new Handler(Looper.getMainLooper()).post(command);

        Runnable timeout = () -> {
            if (done[0]) return;
            done[0] = true;
            Location l = newestLastKnown(lm);
            if (l != null) cb.onFix(l.getLatitude(), l.getLongitude(), "缓存定位");
            else cb.onFix(0, 0, "定位失败");
        };

        LocationListener[] listener = new LocationListener[1];
        try {
            List<String> providers = lm.getProviders(true);
            if (providers.isEmpty()) { timeout.run(); return; }
            listener[0] = new LocationListener() {
                @Override public void onLocationChanged(Location l) {
                    if (done[0] || l == null) return;
                    done[0] = true;
                    h.removeCallbacks(timeout);
                    cb.onFix(l.getLatitude(), l.getLongitude(), "手机定位");
                }
            };
            for (String p : providers) {
                if (!LocationManager.GPS_PROVIDER.equals(p) && !LocationManager.NETWORK_PROVIDER.equals(p)) continue;
                if (Build.VERSION.SDK_INT >= 30) {
                    lm.getCurrentLocation(p, null, exec, l -> {
                        if (!done[0] && l != null) {
                            done[0] = true;
                            h.removeCallbacks(timeout);
                            cb.onFix(l.getLatitude(), l.getLongitude(), "手机定位");
                        }
                    });
                } else {
                    lm.requestSingleUpdate(p, listener[0], Looper.getMainLooper());
                }
            }
            h.postDelayed(timeout, 12000);
        } catch (SecurityException se) {
            Location l = newestLastKnown(lm);
            if (l != null) cb.onFix(l.getLatitude(), l.getLongitude(), "缓存定位");
            else cb.onFix(0, 0, "无定位权限");
        } catch (Exception e) {
            timeout.run();
        }
    }

    static Location newestLastKnown(LocationManager lm) {
        Location best = null;
        try {
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignore) { }
        return best;
    }
}


interface CityCb { void onCity(String city); }

class CityResolver {
    private static final java.util.HashMap<String, String> cache = new java.util.HashMap<>();
    private static final okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build();

    /** 坐标 -> 市级名称 (系统 Geocoder 优先, OSM 兜底); 按 0.05 度网格缓存避免重复请求 */
    public static void city(Context c, double lat, double lng, CityCb cb) {
        String grid = String.format(java.util.Locale.ROOT, "%.1f,%.1f", lat, lng);
        synchronized (cache) {
            String hit = cache.get(grid);
            if (hit != null) { cb.onCity(hit); return; }
        }
        new Thread(() -> {
            String city = null;
            try {
                android.location.Geocoder g = new android.location.Geocoder(c, java.util.Locale.CHINA);
                java.util.List<android.location.Address> list = g.getFromLocation(lat, lng, 1);
                if (list != null && !list.isEmpty()) {
                    String locality = list.get(0).getLocality();
                    String admin = list.get(0).getAdminArea();
                    if (locality != null && !locality.isEmpty()) city = locality;
                    else if (admin != null && !admin.isEmpty()) city = admin;
                }
            } catch (Exception ignore) { }
            if (city == null) {
                try {
                    okhttp3.Request r = new okhttp3.Request.Builder()
                            .url("https://nominatim.openstreetmap.org/reverse?lat=" + lat + "&lon=" + lng
                                    + "&zoom=10&format=json&accept-language=zh-CN")
                            .header("User-Agent", "xiaocan-sentinel/1.0 (personal monitor)")
                            .build();
                    okhttp3.Response resp = client.newCall(r).execute();
                    if (resp.isSuccessful() && resp.body() != null) {
                        org.json.JSONObject o = new org.json.JSONObject(resp.body().string());
                        org.json.JSONObject ad = o.optJSONObject("address");
                        if (ad != null) {
                            city = ad.optString("city", null);
                            if (city == null || city.isEmpty()) city = ad.optString("county", null);
                        }
                    }
                } catch (Exception ignore) { }
            }
            synchronized (cache) {
                cache.put(grid, city == null ? "" : city);
                if (cache.size() > 50) cache.clear();
            }
            String result = city;
            new Handler(Looper.getMainLooper()).post(() -> cb.onCity(result));
        }).start();
    }
}
