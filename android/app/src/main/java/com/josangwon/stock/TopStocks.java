package com.josangwon.stock;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** 최신 분석 결과의 상위 3종목 + 네이버 실시간 시세. 마지막 값은 SharedPreferences에 보관한다. */
final class TopStocks {

    static final int COUNT = 3;
    private static final String LATEST_URL = BuildConfig.APP_URL + "data/latest.json";
    private static final String REALTIME_URL = "https://polling.finance.naver.com/api/realtime/domestic/stock/";
    private static final String PREFS = "top_stocks";
    private static final String KEY = "snapshot";

    static final class Item {
        String code, name, signal;
        double price, changePct;
    }

    final List<Item> items = new ArrayList<>();
    String analyzedAt = "";   // 분석 시각 (예: 09.29 오전)
    String pricedAt = "";     // 시세 시각 (예: 10:30)
    String regime = "";

    /** 네트워크에서 새로 받는다. 메인 스레드에서 부르면 안 된다. */
    static TopStocks fetch() throws Exception {
        JSONObject report = new JSONObject(get(LATEST_URL));
        TopStocks t = new TopStocks();
        JSONArray stocks = report.getJSONArray("stocks");
        StringBuilder codes = new StringBuilder();
        for (int i = 0; i < Math.min(COUNT, stocks.length()); i++) {
            JSONObject s = stocks.getJSONObject(i);
            Item it = new Item();
            it.code = s.getString("code");
            it.name = s.getString("name");
            it.signal = s.optString("signal", "");
            it.price = s.optDouble("price", 0);
            it.changePct = s.optDouble("change_pct", 0);
            t.items.add(it);
            if (codes.length() > 0) codes.append(',');
            codes.append(it.code);
        }
        String gen = report.optString("generated_at", "");  // 2026-09-29T09:50+09:00
        if (gen.length() >= 16) {
            int hour = Integer.parseInt(gen.substring(11, 13));
            t.analyzedAt = gen.substring(5, 7) + "." + gen.substring(8, 10) + (hour < 12 ? " 오전" : " 오후");
        }
        JSONObject regime = report.optJSONObject("regime");
        if (regime != null) {
            t.regime = regime.optString("regime", "") + " · 주식 " + Math.round(regime.optDouble("exposure", 0) * 100) + "%";
        }

        // 실시간 가격으로 덮어쓴다. 실패해도 분석 시점 가격은 남는다.
        try {
            JSONObject rt = new JSONObject(get(REALTIME_URL + codes));
            JSONArray datas = rt.getJSONArray("datas");
            for (int i = 0; i < datas.length(); i++) {
                JSONObject d = datas.getJSONObject(i);
                for (Item it : t.items) {
                    if (it.code.equals(d.optString("itemCode"))) {
                        it.price = Double.parseDouble(d.optString("closePrice", "0").replace(",", ""));
                        it.changePct = Double.parseDouble(d.optString("fluctuationsRatio", "0"));
                        String sign = d.optJSONObject("compareToPreviousPrice") == null ? ""
                                : d.getJSONObject("compareToPreviousPrice").optString("name");
                        if ("FALLING".equals(sign) || "LOWER_LIMIT".equals(sign)) {
                            it.changePct = -Math.abs(it.changePct);
                        }
                        String at = d.optString("localTradedAt", "");
                        if (at.length() >= 16) t.pricedAt = at.substring(5, 7) + "." + at.substring(8, 10) + " " + at.substring(11, 16);
                    }
                }
            }
        } catch (Exception ignored) {
            // 시세 API가 막혀도 위젯은 분석 결과로 보여준다.
        }
        return t;
    }

    void save(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("analyzedAt", analyzedAt).put("pricedAt", pricedAt).put("regime", regime);
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                arr.put(new JSONObject().put("code", it.code).put("name", it.name).put("signal", it.signal)
                        .put("price", it.price).put("changePct", it.changePct));
            }
            o.put("items", arr);
            prefs(c).edit().putString(KEY, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 마지막으로 받은 값. 없으면 null. */
    static TopStocks load(Context c) {
        String s = prefs(c).getString(KEY, null);
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            TopStocks t = new TopStocks();
            t.analyzedAt = o.optString("analyzedAt");
            t.pricedAt = o.optString("pricedAt");
            t.regime = o.optString("regime");
            JSONArray arr = o.getJSONArray("items");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject j = arr.getJSONObject(i);
                Item it = new Item();
                it.code = j.getString("code");
                it.name = j.getString("name");
                it.signal = j.optString("signal");
                it.price = j.optDouble("price");
                it.changePct = j.optDouble("changePct");
                t.items.add(it);
            }
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    static String line(int rank, Item it) {
        return String.format(java.util.Locale.KOREA, "%d. %s  %,.0f원 %+.2f%%  [%s]",
                rank, it.name, it.price, it.changePct, it.signal);
    }

    String subtitle() {
        StringBuilder b = new StringBuilder();
        if (!regime.isEmpty()) b.append(regime);
        if (!pricedAt.isEmpty()) b.append(b.length() > 0 ? " · " : "").append("시세 ").append(pricedAt);
        else if (!analyzedAt.isEmpty()) b.append(b.length() > 0 ? " · " : "").append(analyzedAt).append(" 분석");
        return b.toString();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setUseCaches(false);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) JosangwonStock");
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }
}
