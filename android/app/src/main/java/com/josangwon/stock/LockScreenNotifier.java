package com.josangwon.stock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 잠금화면에 보이는 고정 알림: 상위 3종목. 소리·진동 없이 조용히 갱신된다. */
final class LockScreenNotifier {

    private static final String CHANNEL = "top3";
    private static final int ID = 3;

    static void show(Context c, TopStocks t) {
        if (t == null || t.items.isEmpty()) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 33 && !nm.areNotificationsEnabled()) return;

        NotificationChannel ch = new NotificationChannel(CHANNEL, "상위 3종목 (잠금화면)", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("분석 상위 3종목을 잠금화면과 알림창에 계속 보여줍니다.");
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        Notification.InboxStyle style = new Notification.InboxStyle();
        for (int i = 0; i < t.items.size(); i++) {
            style.addLine(TopStocks.line(i + 1, t.items.get(i)));
        }
        style.setSummaryText(t.subtitle());

        TopStocks.Item first = t.items.get(0);
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_chart)
                .setContentTitle("조상원 주식 TOP3")
                .setContentText(String.format(java.util.Locale.KOREA, "1. %s %+.2f%% · 2. %s · 3. %s",
                        first.name, first.changePct,
                        t.items.size() > 1 ? t.items.get(1).name : "-",
                        t.items.size() > 2 ? t.items.get(2).name : "-"))
                .setStyle(style)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .build();
        nm.notify(ID, n);
    }
}
