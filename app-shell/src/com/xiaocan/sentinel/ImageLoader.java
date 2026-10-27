package com.xiaocan.sentinel;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 图片加载器: 内存缓存 + 磁盘缓存 + 圆角预烘焙 (滚动时零额外开销)
 */
public class ImageLoader {
    static ImageLoader inst;
    final OkHttpClient client = new OkHttpClient();
    final ExecutorService pool = Executors.newFixedThreadPool(3);
    final Handler main = new Handler(Looper.getMainLooper());
    final LruCache mem;
    final File dir;
    final int radiusPx;

    static class LruCache {
        final LinkedHashMap<String, Bitmap> m = new LinkedHashMap<>(16, 0.75f, true);
        final int max;
        LruCache(int maxBytes) { max = maxBytes; }
        synchronized Bitmap get(String k) { return m.get(k); }
        synchronized void put(String k, Bitmap v) {
            if (v == null) return;
            m.put(k, v);
            int size = 0;
            java.util.Iterator<Bitmap> it = m.values().iterator();
            while (it.hasNext()) { size += it.next().getByteCount() * 2; if (size > max) it.remove(); }
        }
    }

    public static synchronized ImageLoader get(Context ctx) {
        if (inst == null) {
            inst = new ImageLoader(ctx, (int) (Runtime.getRuntime().maxMemory() / 8));
        }
        return inst;
    }

    ImageLoader(Context ctx, int memBytes) {
        float density = ctx.getResources().getDisplayMetrics().density;
        radiusPx = (int) (14 * density);
        mem = new LruCache(memBytes);
        dir = new File(ctx.getFilesDir(), "imgcache");
        if (!dir.exists()) dir.mkdirs();
    }

    public void load(String url, ImageView iv) {
        if (url == null || url.isEmpty()) { iv.setImageBitmap(null); return; }
        String key = url + "#" + radiusPx;
        iv.setTag(key);
        Bitmap hit = mem.get(key);
        if (hit != null) { iv.setImageBitmap(hit); return; }
        iv.setImageBitmap(null);
        pool.execute(() -> {
            Bitmap bmp = fromDisk(key);
            if (bmp == null) bmp = fromNet(url, key);
            if (bmp != null) mem.put(key, bmp);
            Bitmap f = bmp;
            main.post(() -> { if (key.equals(iv.getTag())) iv.setImageBitmap(f); });
        });
    }

    Bitmap fromDisk(String key) {
        try {
            File f = new File(dir, md5(key));
            if (!f.exists() || f.length() == 0) return null;
            FileInputStream in = new FileInputStream(f);
            byte[] data = readAll(in);
            in.close();
            return round(decode(data), radiusPx);
        } catch (Exception e) { return null; }
    }

    Bitmap fromNet(String url, String key) {
        try {
            Request rb = new Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build();
            Response r = client.newCall(rb).execute();
            if (!r.isSuccessful() || r.body() == null) return null;
            byte[] data = r.body().bytes();
            Bitmap raw = decode(data);
            if (raw == null) return null;
            Bitmap rounded = round(raw, radiusPx);
            FileOutputStream fo = new FileOutputStream(new File(dir, md5(key)));
            rounded.compress(Bitmap.CompressFormat.PNG, 90, fo);
            fo.close();
            return rounded;
        } catch (Exception e) { return null; }
    }

    Bitmap decode(byte[] data) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            int maxSide = Math.max(o.outWidth, o.outHeight);
            while (maxSide / sample > 300) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Exception e) { return null; }
    }

    /** 位图圆角预烘焙: 列表滚动时不再做实时裁切 */
    static Bitmap round(Bitmap src, float radius) {
        if (src == null) return null;
        try {
            Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            c.drawRoundRect(0, 0, src.getWidth(), src.getHeight(), radius, radius, p);
            return out;
        } catch (Exception e) { return src; }
    }

    byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return String.valueOf(s.hashCode()); }
    }
}
