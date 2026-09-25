package com.josangwon.stock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.RemoteViews;

import java.util.Locale;

/** 알림창(내려서 보는 패널)에 조용히 떠 있는 상위 3종목. 소리·진동·팝업 없이 갱신된다. */
final class LockScreenNotifier {

    // 채널 중요도는 만든 뒤에 못 바꾸므로 예전 채널(top3, top3_lock)은 지우고 새 채널을 쓴다.
    private static final String[] OLD_CHANNELS = {"top3", "top3_lock"};
    private static final String CHANNEL = "top3_panel";
    private static final int ID = 3;

    static boolean enabled(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (!nm.areNotificationsEnabled()) return false;
        NotificationChannel ch = nm.getNotificationChannel(CHANNEL);
        return ch == null || ch.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        for (String old : OLD_CHANNELS) nm.deleteNotificationChannel(old);
        // 중요도 '낮음': 알림창에만 표시, 소리·진동·팝업 없음
        NotificationChannel ch = new NotificationChannel(CHANNEL, "상위 3종목 (알림창)", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("분석 상위 3종목을 알림창에 조용히 계속 보여줍니다.");
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

        // 제목·요약 줄 없이 종목 줄만, 마지막 줄에 갱신 시각(맨 위 앱 이름·아이콘은 시스템이 붙인다).
        RemoteViews big = new RemoteViews(c.getPackageName(), R.layout.notif_top3);
        big.removeAllViews(R.id.lines);
        SpannableStringBuilder summary = new SpannableStringBuilder();
        if (t.buyList) summary.append("매수 관심 ").append(String.valueOf(t.items.size())).append("종목  ");
        for (int i = 0; i < t.items.size(); i++) {
            TopStocks.Item it = t.items.get(i);
            RemoteViews row = new RemoteViews(c.getPackageName(), R.layout.notif_line);
            row.setTextViewText(R.id.line, line(i + 1, it, !t.buyList));
            big.addView(R.id.lines, row);
            if (i > 0) summary.append("  ");
            summary.append(it.name).append(' ').append(change(it));
        }
        String time = t.updatedAt.isEmpty() ? "" : t.updatedAt + " 기준";
        if (!t.buyList) time = "매수 관심 종목 없음 · 종합 상위 " + t.items.size() + "종목" + (time.isEmpty() ? "" : " · " + time);
        big.setTextViewText(R.id.time, time);
        RemoteViews small = new RemoteViews(c.getPackageName(), R.layout.notif_top3_small);
        small.setTextViewText(R.id.summary, summary);

        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_chart)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomContentView(small)
                .setCustomBigContentView(big)
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

    /** "1. 슈프리마  55,400원 +6.95%" (+ 상위 종목 모드면 "[관망]" 같은 신호) — 등락률만 빨강/파랑 */
    private static CharSequence line(int rank, TopStocks.Item it, boolean withSignal) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        b.append(String.format(Locale.KOREA, "%d. %s  %,.0f원 ", rank, it.name, it.price));
        b.append(change(it));
        if (withSignal && !it.signal.isEmpty()) b.append("  [").append(it.signal).append(']');
        return b;
    }

    private static CharSequence change(TopStocks.Item it) {
        SpannableString s = new SpannableString(String.format(Locale.KOREA, "%+.2f%%", it.changePct));
        int color = it.changePct > 0 ? 0xFFE5484D : it.changePct < 0 ? 0xFF3E7BFA : 0xFF888888;
        s.setSpan(new ForegroundColorSpan(color), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }
}
