package com.josangwon.stock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/** 잠금화면에 보이는 고정 알림: 상위 3종목. 소리·진동 없이 조용히 갱신된다. */
final class LockScreenNotifier {

    // 중요도 '낮음' 채널(top3)은 무음 알림이라 잠금화면에서 숨겨졌다. 채널 중요도는 나중에 못 바꾸므로 새 채널을 쓴다.
    private static final String OLD_CHANNEL = "top3";
    private static final String CHANNEL = "top3_lock";
    private static final int ID = 3;

    static boolean enabled(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (!nm.areNotificationsEnabled()) return false;
        NotificationChannel ch = nm.getNotificationChannel(CHANNEL);
        return ch == null || ch.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.deleteNotificationChannel(OLD_CHANNEL);
        // 기본 중요도(잠금화면에 표시) + 소리·진동 없음
        NotificationChannel ch = new NotificationChannel(CHANNEL, "상위 3종목 (잠금화면)", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("분석 상위 3종목을 잠금화면과 알림창에 계속 보여줍니다. 소리·진동 없음.");
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.enableLights(false);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    /** 알림을 띄웠으면 true. */
    static boolean show(Context c, TopStocks t) {
        if (t == null || t.items.isEmpty()) return false;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        ensureChannel(c);
        if (!nm.areNotificationsEnabled()) return false;

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
                .setCategory(Notification.CATEGORY_STATUS)
                .setShowWhen(false)
                .setContentIntent(open)
                .build();
        nm.notify(ID, n);
        return true;
    }
}
