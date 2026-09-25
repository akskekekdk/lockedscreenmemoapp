package com.lockmemo.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat

/**
 * 잠금화면에 항상 떠 있는 메모 알림.
 * - 알림 하나에 메모를 한 줄에 하나씩 보여주고
 * - "한 줄 메모" 버튼으로 잠금을 풀지 않고 바로 입력(인라인 답장)할 수 있다.
 */
object MemoNotifier {
    // 중요도는 채널을 만든 뒤 바꿀 수 없어서, 바꿀 때마다 새 ID를 쓴다
    const val CHANNEL_ID = "lockscreen_memo_v2"
    private val OLD_CHANNEL_IDS = listOf("lockscreen_memo")
    const val KEY_TEXT = "memo_text"


    const val NOTIFICATION_ID = 1

    // 접힌 알림(잠금화면)은 높이가 정해져 있어 3줄까지, 펼치면 10줄까지
    private const val COLLAPSED_LINES = 3
    private const val EXPANDED_LINES = 10

    // 예전 버전이 메모마다 따로 쓰던 알림 ID(1~10). 1번은 지금도 쓴다.
    private val ROW_IDS = (1..10).toList()

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        OLD_CHANNEL_IDS.forEach { manager.deleteNotificationChannel(it) }
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // IMPORTANCE_LOW 이하는 "무음 알림"으로 분류되어 대부분의 기기에서 잠금화면에 표시되지 않는다.
        // 그래서 DEFAULT 로 두고 소리/진동만 끈다.
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_desc)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** 잠금화면에 알림이 보이지 않게 막고 있는 앱 쪽 설정. 문제없으면 null. */
    fun blockingReason(context: Context): Int? {
        if (!canNotify(context) || !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return R.string.problem_notifications_off
        }
        ensureChannel(context)
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(CHANNEL_ID)
        return when {
            channel.importance == NotificationManager.IMPORTANCE_NONE -> R.string.problem_channel_off
            channel.importance < NotificationManager.IMPORTANCE_DEFAULT -> R.string.problem_channel_silent
            channel.lockscreenVisibility == android.app.Notification.VISIBILITY_SECRET -> R.string.problem_channel_secret
            else -> null
        }
    }

    /** 설정에 따라 알림을 띄우거나 내린다. 메모가 바뀔 때마다 호출. */
    fun refresh(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val refreshIntent = actionIntent(context, MemoActionReceiver.ACTION_REPOST, 4)
        val manager = NotificationManagerCompat.from(context)
        if (!MemoStore.isLockScreenEnabled(context)) {
            alarms.cancel(refreshIntent)
            ROW_IDS.forEach { manager.cancel(it) }
            LockWallpaper.clear(context)
            return
        }

        val now = System.currentTimeMillis()
        val memos = MemoStore.all(context)
        val wallpaperMode = MemoStore.isWallpaperMode(context)
        if (wallpaperMode) {
            LockWallpaper.update(context, memos.map { DueFormat.line(context, it, now) })
        } else {
            LockWallpaper.clear(context)
        }

        // 예전 버전이 메모마다 따로 띄웠던 알림 정리
        ROW_IDS.drop(1).forEach { manager.cancel(it) }
        if (memos.isEmpty()) {
            // 메모가 하나도 없으면 잠금화면에 아무것도 띄우지 않는다
            manager.cancel(NOTIFICATION_ID)
        } else if (canNotify(context)) {
            ensureChannel(context)
            // 알림은 하나만. 배경화면 모드에서는 메모를 배경에 그리므로 입력 안내만 띄운다
            val rows = if (wallpaperMode) emptyList() else memos.map { Row(DueFormat.line(context, it, now), it.due) }
            @Suppress("MissingPermission")
            manager.notify(NOTIFICATION_ID, build(context, rows, now))
        }

        // 메모 시각이 지나거나 날짜가 바뀌면("내일" → "오늘") 표시를 다시 그린다.
        // 정확할 필요는 없어서 정확한 알람 권한 없이 쓸 수 있는 방식을 쓴다.
        // 하루 전 시점에는 "D-1" → 실시간 카운트다운으로 바뀌어야 하므로 그 시각도 후보에 넣는다
        val nextDue = memos.mapNotNull { it.due }
            .flatMap { listOf(it, it - DueFormat.DAY_MS) }
            .filter { it > now }
            .minOrNull()
        val next = minOf(nextDue ?: Long.MAX_VALUE, DueFormat.nextMidnight(now))
        alarms.setAndAllowWhileIdle(AlarmManager.RTC, next, refreshIntent)
    }

    /**
     * 알림 하나에 메모를 하나씩 줄바꿈해서 보여준다. 제목 없이 내용만.
     * 기본 알림 모양은 접힌 상태에서 한 줄만 보여서, 직접 만든 레이아웃에 줄을 채워 넣는다.
     * [lines]가 비면 입력 안내.
     */
    /** 알림의 한 줄: 보여줄 글(“오늘 14:00 · 내용”)과 남은 시간 계산용 시각. */
    private class Row(val text: String, val due: Long?)

    private fun build(context: Context, memoRows: List<Row>, now: Long): android.app.Notification {
        val rows = memoRows.ifEmpty { listOf(Row(context.getString(R.string.empty_hint), null)) }
        // 직접 만든 화면을 못 쓰는 곳(워치 등)에서는 남은 시간까지 글로 붙여 보여준다
        val lines = rows.map { row ->
            row.due?.let { DueFormat.remaining(context, it, now) }?.let { "${row.text} ($it)" } ?: row.text
        }
        val openQuickMemo = PendingIntent.getActivity(
            context, 2,
            Intent(context, QuickMemoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_memo)
            // 직접 만든 화면을 못 쓰는 곳(워치 등)에서 보일 내용
            .setContentTitle(lines.first())
            .setContentText(lines.drop(1).joinToString(" / ").ifEmpty { null })
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(linesView(context, rows.take(COLLAPSED_LINES), now))
            .setCustomBigContentView(linesView(context, rows.take(EXPANDED_LINES), now))
            .setContentIntent(openQuickMemo)
            .setOngoing(true)
            // Android 14+에서 사용자가 밀어서 지워도 다시 띄운다
            .setDeleteIntent(actionIntent(context, MemoActionReceiver.ACTION_REPOST, 3))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)

        val remoteInput = RemoteInput.Builder(KEY_TEXT)
            .setLabel(context.getString(R.string.input_hint))
            .build()
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_memo,
            context.getString(R.string.action_reply),
            actionIntent(context, MemoActionReceiver.ACTION_ADD, 1),
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .setAuthenticationRequired(false) // 잠금 해제 없이 입력 허용
            .build()
        return builder
            .addAction(replyAction)
            .addAction(R.drawable.ic_memo, context.getString(R.string.action_quick), openQuickMemo)
            .build()
    }

    private fun linesView(context: Context, rows: List<Row>, now: Long): RemoteViews {
        val view = RemoteViews(context.packageName, R.layout.notification_memos)
        rows.forEach { row ->
            val line = RemoteViews(context.packageName, R.layout.notification_line)
            line.setTextViewText(R.id.line, row.text)
            val left = row.due?.let { it - now } ?: 0
            when {
                left <= 0 -> Unit // 날짜 없음 또는 지남: 남은 시간 표시 없음
                left <= DueFormat.DAY_MS -> {
                    // 하루 이내: 잠금화면에서 초 단위로 줄어드는 카운트다운
                    line.setViewVisibility(R.id.countdown, View.VISIBLE)
                    line.setChronometer(
                        R.id.countdown,
                        SystemClock.elapsedRealtime() + left,
                        context.getString(R.string.countdown_format),
                        true,
                    )
                    line.setChronometerCountDown(R.id.countdown, true)
                }
                else -> {
                    line.setViewVisibility(R.id.remain_static, View.VISIBLE)
                    line.setTextViewText(R.id.remain_static, DueFormat.remaining(context, row.due!!, now))
                }
            }
            view.addView(R.id.lines, line)
        }
        return view
    }

    private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MemoActionReceiver::class.java).setAction(action)
        // 인라인 입력은 시스템이 결과를 채워 넣어야 하므로 MUTABLE 이어야 한다
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (action == MemoActionReceiver.ACTION_ADD) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }
}
