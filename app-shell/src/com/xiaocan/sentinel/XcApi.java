package com.xiaocan.sentinel;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 小蚕网关 RPC 客户端
 * 签名算法逆向自 com.kmp.realtech.core.net.RequestParamsIntercept (v3.21.6):
 *   X-Ashe  = MD5( MD5((servername + "." + methodname).toLowerCase()) + tsMs + nami )
 *   X-Nami  = 登录态: uuidHex前半+silkId+后半(16位) / 匿名: 17位纯随机
 *   X-Garen = System.currentTimeMillis()
 * 匿名模式(无token无silk_id)实测返回全量未个性化列表
 */
public final class XcApi {

    public static final String GATEWAY = "https://gw.xiaocantech.com/rpc";
    public static final String UA = "XC;Android;3.21.6;";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client;
    // 账号/环境参数 (匿名模式全部留空/0)
    public String token;
    public long uid;
    public long silkId;
    public int cityCode;
    public String sessionId;
    public String channel = "YYB";
    public String appId = "16";
    public String appVersion = "3.21.6.4";

    public XcApi() {
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 随机串: 登录态=silkId嵌入16位; 匿名=17位纯随机 (对应App匿名分支) */
    public String randomNami() {
        String hex = UUID.randomUUID().toString().replace("-", "");
        if (silkId == 0) return hex.substring(0, 17);
        String sid = String.valueOf(silkId);
        int len = 16 - sid.length();
        if (len < 2) len = 2;
        String seg = hex.substring(0, len);
        int half = len / 2;
        return seg.substring(0, half) + sid + seg.substring(half);
    }

    /** 发起一次 RPC 调用, 返回响应文本; 非 2xx 抛异常 */
    public String call(String servername, String methodname, String jsonBody) throws Exception {
        long now = System.currentTimeMillis();
        String nami = randomNami();
        String lower = (servername + "." + methodname).toLowerCase(java.util.Locale.ROOT);
        String ashe = md5(md5(lower) + now + nami);

        Map<String, String> h = new LinkedHashMap<>();
        h.put("servername", servername);
        h.put("methodname", methodname);
        h.put("x-ashe", ashe);
        h.put("x-nami", nami);
        h.put("x-garen", String.valueOf(now));
        h.put("x-platform", "Android");
        h.put("x-annie", "XC");
        if (sessionId != null && !sessionId.isEmpty()) h.put("x-session-id", sessionId);
        h.put("user-agent", UA);
        h.put("x-channel", channel);
        h.put("x-app-sr", appId);
        if (uid != 0) h.put("x-vayne", String.valueOf(uid));
        if (silkId != 0) h.put("x-teemo", String.valueOf(silkId));
        if (token != null && !token.isEmpty()) h.put("x-sivir", token);
        h.put("x-version", appVersion);
        h.put("x-citycode", String.valueOf(cityCode));
        h.put("x-city", String.valueOf(cityCode));
        h.put("content-type", "application/json; charset=utf-8");

        Request.Builder rb = new Request.Builder().url(GATEWAY).post(RequestBody.create(jsonBody, JSON));
        for (Map.Entry<String, String> e : h.entrySet()) rb.header(e.getKey(), e.getValue());

        try (Response resp = client.newCall(rb.build()).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) throw new ApiException(resp.code(), "HTTP " + resp.code() + " " + body.substring(0, Math.min(80, body.length())));
            return body;
        }
    }

    /** 解析 JWT 的 exp (秒), 失败返回 0 */
    public static long jwtExp(String jwt) {
        try {
            String[] p = jwt.split("\\.");
            if (p.length < 2) return 0;
            byte[] b = Base64.decode(p[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            JSONObject o = new JSONObject(new String(b, StandardCharsets.UTF_8));
            return o.optLong("exp", 0L);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 探索页活动列表单页 (匿名不带silk_id -> 全量未个性化列表) */
    public String listExplorePromotions(double lat, double lng, int pageSize, int pageNum) throws Exception {
        JSONObject body = new JSONObject();
        if (silkId != 0) body.put("silk_id", silkId);
        JSONObject loc = new JSONObject();
        loc.put("lat", lat);
        loc.put("lng", lng);
        body.put("loc", loc);
        body.put("dist_item", 30000);
        JSONObject page = new JSONObject();
        page.put("page_num", pageNum);
        page.put("page_size", pageSize);
        body.put("page", page);
        JSONObject filter = new JSONObject();
        filter.put("data_type", 1);
        filter.put("is_can_order", true);
        filter.put("ids", new JSONArray());
        body.put("filter", filter);
        return call("SilkwormExplore", "ExploreMobile.ListExplorePromotionByFilter", body.toString());
    }

    /** 拉取全部分页, 合并 item 数组 (最多 maxPages 页) */
    public JSONArray fetchExploreAll(double lat, double lng, int maxPages) throws Exception {
        JSONArray all = new JSONArray();
        int count = Integer.MAX_VALUE;
        for (int p = 1; p <= maxPages && (p - 1) * 50 < count; p++) {
            String resp = listExplorePromotions(lat, lng, 50, p);
            JSONObject root = new JSONObject(resp);
            int code = root.getJSONObject("status").optInt("code", -1);
            if (code != 0) throw new ApiException(200, "业务码 " + code);
            count = root.optInt("count", 0);
            JSONArray list = root.optJSONArray("list");
            if (list == null || list.length() == 0) break;
            for (int i = 0; i < list.length(); i++) all.put(list.getJSONObject(i));
        }
        return all;
    }

    /** 首页 Feed (按店铺活动流, 自带 distance/left_number, 与小蚕首页同源) */
    public String fetchFeed(double lat, double lng, int offset, int number) throws Exception {
        JSONObject body = new JSONObject();
        body.put("promotion_category", 0);
        body.put("is_show_mt", true);
        body.put("lat", lat);
        body.put("lon", lng);
        body.put("meal_period", 0);
        body.put("number", number);
        body.put("offset", offset);
        body.put("rebate_type", 0);
        body.put("scene", 1);
        if (silkId != 0) body.put("silk_id", silkId);
        body.put("sort", 0);
        return call("SilkwormFusion", "FusionService.GetFeedPromotions", body.toString());
    }

    /** 拉取全部 Feed 分页, 合并 feed_items 数组 (最多 maxPages 页) */
    public JSONArray fetchFeedAll(double lat, double lng, int maxPages) throws Exception {
        JSONArray all = new JSONArray();
        int number = 20;
        for (int p = 0; p < maxPages; p++) {
            String resp = fetchFeed(lat, lng, p * number, number);
            JSONObject root = new JSONObject(resp);
            int code = root.getJSONObject("status").optInt("code", -1);
            if (code != 0) throw new ApiException(200, "业务码 " + code);
            JSONArray items = root.optJSONArray("feed_items");
            if (items == null || items.length() == 0) break;
            for (int i = 0; i < items.length(); i++) all.put(items.getJSONObject(i));
            if (items.length() < number) break;
        }
        return all;
    }

    public static class ApiException extends RuntimeException {
        public final int code;
        public ApiException(int code, String msg) { super(msg); this.code = code; }
    }
}
