package com.xiaocan.sentinel;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 店铺卡片适配器: ViewHolder 复用 + 预烘焙圆角图, 滚动零额外开销 */
public class CardAdapter extends BaseAdapter {
    static class Card {
        long key;
        String name, icon;
        int platform;
        String distText;
        boolean isNew;
        int acts;
        String rebateText, timeText, extraText;
        boolean noReview;
        int quota, quotaPct;
    }

    final Context ctx;
    final LayoutInflater inf;
    final List<Card> all = new ArrayList<>();
    final List<Card> shown = new ArrayList<>();
    String query = "";

    public CardAdapter(Context c) { ctx = c; inf = LayoutInflater.from(c); }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^\\u4e00-\\u9fa5a-z0-9]", "");
    }

    public void setData(List<Card> cards) {
        all.clear();
        all.addAll(cards);
        applyFilter();
    }

    public void setQuery(String q) {
        query = norm(q);
        applyFilter();
    }

    void applyFilter() {
        shown.clear();
        for (Card c : all) {
            if (query.isEmpty() || norm(c.name).contains(query)) shown.add(c);
        }
        notifyDataSetChanged();
    }

    @Override public int getCount() { return shown.size(); }
    @Override public Object getItem(int i) { return shown.get(i); }
    @Override public long getItemId(int i) { return shown.get(i).key; }

    static class VH {
        ImageView ivIcon;
        TextView tvNew, tvName, tvDist, tvPlatform, tvCount, tvCardNew, tvRebate, tvNoReview, tvExtra, tvTime, tvQuota, btnGo;
        ProgressBar pbQuota;
    }

    @Override
    public View getView(int i, View cv, ViewGroup parent) {
        View v = cv;
        VH h;
        if (v == null) {
            v = inf.inflate(R.layout.item_card, parent, false);
            h = new VH();
            h.ivIcon = v.findViewById(R.id.ivIcon);
            h.tvNew = v.findViewById(R.id.tvNew);
            h.tvName = v.findViewById(R.id.tvName);
            h.tvDist = v.findViewById(R.id.tvDist);
            h.tvPlatform = v.findViewById(R.id.tvPlatform);
            h.tvCount = v.findViewById(R.id.tvCount);
            h.tvCardNew = v.findViewById(R.id.tvCardNew);
            h.tvRebate = v.findViewById(R.id.tvRebate);
            h.tvNoReview = v.findViewById(R.id.tvNoReview);
            h.tvExtra = v.findViewById(R.id.tvExtra);
            h.tvTime = v.findViewById(R.id.tvTime);
            h.pbQuota = v.findViewById(R.id.pbQuota);
            h.tvQuota = v.findViewById(R.id.tvQuota);
            h.btnGo = v.findViewById(R.id.btnGo);
            v.setTag(h);
        } else {
            h = (VH) v.getTag();
        }

        Card card = shown.get(i);
        ImageLoader.get(ctx).load(card.icon, h.ivIcon);
        h.tvName.setText(card.name);
        h.tvDist.setText(card.distText);

        String[] names = {"", "美团", "淘宝闪购", "京东", "淘宝闪购"};
        h.tvPlatform.setText(card.platform >= 1 && card.platform < names.length ? names[card.platform] : "小蚕");
        boolean tb = card.platform == 2 || card.platform == 4;
        h.tvPlatform.setTextColor(tb ? 0xFFFF5000 : 0xFFFF9500);
        h.tvPlatform.setBackgroundResource(tb ? R.drawable.bg_chip_rebate : R.drawable.bg_chip_orange);
        if (tb) h.tvPlatform.setTextColor(0xFFFFFFFF);
        h.tvCount.setText(card.acts + " 个活动");

        boolean anyNew = card.isNew;
        h.tvNew.setVisibility(anyNew ? View.VISIBLE : View.GONE);
        h.tvCardNew.setVisibility(anyNew ? View.VISIBLE : View.GONE);

        h.tvRebate.setText(card.rebateText);
        h.tvNoReview.setVisibility(card.noReview ? View.VISIBLE : View.GONE);
        if (card.extraText != null && !card.extraText.isEmpty()) {
            h.tvExtra.setText(card.extraText);
            h.tvExtra.setVisibility(View.VISIBLE);
        } else {
            h.tvExtra.setVisibility(View.GONE);
        }
        h.tvTime.setText(card.timeText);

        h.pbQuota.setProgress(card.quotaPct);
        h.tvQuota.setText(card.quota > 0 ? "还剩 " + card.quota + " 份" : "已抢光");
        h.tvQuota.setTextColor(card.quota > 0 ? (card.quota <= 3 ? 0xFFFF3B30 : 0xFFFF6D00) : 0xFFBBBBBB);

        h.btnGo.setOnClickListener(v1 -> openXiaocan());
        v.setOnClickListener(v1 -> openXiaocan());
        return v;
    }

    void openXiaocan() {
        android.content.Intent li = ctx.getPackageManager().getLaunchIntentForPackage(PollService.TARGET_APP);
        if (li != null) ctx.startActivity(li);
    }
}
