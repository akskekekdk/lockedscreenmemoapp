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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat

/**
 * 잠금화면에 항상 떠 있는 메모 알림.
 * - 메모 하나를 알림 하나로, 각각 한 줄씩 보여주고
 * - "한 줄 메모" 버튼으로 잠금을 풀지 않고 바로 입력(인라인 답장)할 수 있다.
 */
object MemoNotifier {
    // 중요도는 채널을 만든 뒤 바꿀 수 없어서, 바꿀 때마다 새 ID를 쓴다
    const val CHANNEL_ID = "lockscreen_memo_v2"
    private val OLD_CHANNEL_IDS = listOf("lockscreen_memo")
    const val KEY_TEXT = "memo_text"


    // 메모 하나 = 알림 하나. 알림 ID는 1부터 차례로 쓴다.
    private const val FIRST_ID = 1
    private const val MAX_ROWS = 10
    private val ROW_IDS = (FIRST_ID until FIRST_ID + MAX_ROWS).toList()

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
        val memos = MemoStore.sorted(context, now)
        val wallpaperMode = MemoStore.isWallpaperMode(context)
        if (wallpaperMode) {
            LockWallpaper.update(context, memos.map { DueFormat.line(context, it, now) })
        } else {
            LockWallpaper.clear(context)
        }

        if (canNotify(context)) {
            ensureChannel(context)
            // 배경화면 모드에서는 메모는 배경에 그리고, 알림은 입력용 하나만 둔다
            val rows: List<Memo?> = if (wallpaperMode) listOf(null) else memos.take(MAX_ROWS).ifEmpty { listOf(null) }
            rows.forEachIndexed { index, memo ->
                @Suppress("MissingPermission")
                manager.notify(ROW_IDS[index], buildRow(context, memo, index, now))
            }
            ROW_IDS.drop(rows.size).forEach { manager.cancel(it) }
        }

        // 메모 시각이 지나거나 날짜가 바뀌면("내일" → "오늘") 표시를 다시 그린다.
        // 정확할 필요는 없어서 정확한 알람 권한 없이 쓸 수 있는 방식을 쓴다.
        val nextDue = memos.mapNotNull { it.due }.filter { it > now }.minOrNull()
        val next = minOf(nextDue ?: Long.MAX_VALUE, DueFormat.nextMidnight(now))
        alarms.setAndAllowWhileIdle(AlarmManager.RTC, next, refreshIntent)
    }

    /**
     * 메모 하나 = 알림 하나 = 한 줄. 제목 자리에 메모 내용만 넣는다.
     *
     * 그룹을 지정하지 않은 알림이 여러 개면 시스템이 앱 단위로 자동으로 묶어 "3" 처럼 뭉쳐 보인다.
     * 알림마다 서로 다른 그룹 키를 주면 자동 묶음 대상에서 빠지고,
     * 요약 알림이 없는 그룹의 알림은 각자 따로 표시된다.
     * 입력 버튼은 맨 위(index 0) 알림에만 단다. [memo]가 null이면 빈 안내 알림.
     */
    private fun buildRow(context: Context, memo: Memo?, index: Int, now: Long): android.app.Notification {
        val openQuickMemo = PendingIntent.getActivity(
            context, 2,
            Intent(context, QuickMemoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_memo)
            .setContentTitle(memo?.let { DueFormat.line(context, it, now) } ?: context.getString(R.string.empty_hint))
            .setGroup("memo_row_$index")
            .setContentIntent(openQuickMemo)
            .setOngoing(true)
            // Android 14+에서 사용자가 밀어서 지워도 다시 띄운다
            .setDeleteIntent(actionIntent(context, MemoActionReceiver.ACTION_REPOST, 3))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            // 같은 중요도의 알림은 시각이 최근일수록 위에 오므로, 메모 순서대로 1ms씩 과거로 둔다
            .setWhen(now - index)
            .setShowWhen(false)

        if (index == 0) {
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
            builder.addAction(replyAction)
                .addAction(R.drawable.ic_memo, context.getString(R.string.action_quick), openQuickMemo)
        }
        return builder.build()
    }

    private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MemoActionReceiver::class.java).setAction(action)
        // 인라인 입력은 시스템이 결과를 채워 넣어야 하므로 MUTABLE 이어야 한다
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (action == MemoActionReceiver.ACTION_ADD) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }
}
