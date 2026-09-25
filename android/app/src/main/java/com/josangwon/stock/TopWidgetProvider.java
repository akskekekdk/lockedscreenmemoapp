package com.josangwon.stock;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.util.Locale;

/** 홈 화면(지원 기기는 잠금화면) 위젯: 상위 3종목. */
public class TopWidgetProvider extends AppWidgetProvider {

    private static final int[] NAME = {R.id.name1, R.id.name2, R.id.name3};
    private static final int[] INFO = {R.id.info1, R.id.info2, R.id.info3};

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        render(c, TopStocks.load(c));
        UpdateJobService.schedule(c);
        UpdateJobService.runNow(c);
    }

    static void render(Context c, TopStocks t) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, TopWidgetProvider.class));
        if (ids.length == 0) return;

        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_top3);
        for (int i = 0; i < TopStocks.COUNT; i++) {
            if (t != null && i < t.items.size()) {
                TopStocks.Item it = t.items.get(i);
                v.setTextViewText(NAME[i], (i + 1) + ". " + it.name + "  " + it.signal);
                v.setTextViewText(INFO[i], String.format(Locale.KOREA, "%,.0f  %+.2f%%", it.price, it.changePct));
                v.setTextColor(INFO[i], it.changePct > 0 ? 0xFFFF6B70 : it.changePct < 0 ? 0xFF6FA0FF : 0xFFCCCCCC);
            } else {
                v.setTextViewText(NAME[i], i == 0 ? "불러오는 중…" : "");
                v.setTextViewText(INFO[i], "");
            }
        }
        v.setTextViewText(R.id.subtitle, t == null ? "" : t.subtitle());
        v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(c, 0,
                new Intent(c, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        m.updateAppWidget(ids, v);
    }
}
