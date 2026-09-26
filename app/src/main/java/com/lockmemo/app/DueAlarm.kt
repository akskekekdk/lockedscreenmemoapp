package com.lockmemo.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 정해진 시간이 딱 되면(남은 시간 0:00:00) 소리·진동 없이 화면 전체에 알림을 띄운다.
 * 가장 가까운 다음 시각 하나에만 정확한 알람을 걸고, 울리면 그다음 시각으로 다시 건다.
 */
object DueAlarm {
    const val ACTION_DUE = "com.lockmemo.app.DUE"
    const val EXTRA_DUE = "due"
    const val ALERT_NOTIFICATION_ID = 100
    private const val CHANNEL_ID = "due_alert"

    /** 메모 목록이 바뀔 때마다 호출: 다음 시각에 알람을 다시 건다(없으면 취소). */
    fun schedule(context: Context, memos: List<Memo>, now: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val next = memos.mapNotNull { it.due }.filter { it > now }.minOrNull()
        val intent = pendingIntent(context, next ?: 0L)
        if (next == null) {
            alarms.cancel(intent)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
        } else {
            // 정확한 알람 권한이 없으면 조금 늦을 수 있는 방식으로라도 건다
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
        }
    }

    private fun pendingIntent(context: Context, due: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, 20,
            Intent(context, Receiver::class.java).setAction(ACTION_DUE).putExtra(EXTRA_DUE, due),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Android 14부터는 전체 화면 알림에 사용자 허용이 필요하다. */
    fun canUseFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // 전체 화면으로 띄우려면 중요도가 높아야 한다. 대신 소리·진동은 끈다
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.due_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.due_channel_desc)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
            },
        )
    }

    /** [due] 시각이 된 메모들을 전체 화면으로 보여준다. */
    fun alert(context: Context, due: Long) {
        // 같은 분에 정해진 메모는 함께 보여준다
        val memos = MemoStore.all(context).filter { it.due != null && it.due / 60_000 == due / 60_000 }
        if (memos.isEmpty() || !MemoNotifier.canNotify(context)) return
        ensureChannel(context)
        val text = memos.joinToString("\n") { it.text }
        val open = PendingIntent.getActivity(
            context, 21,
            Intent(context, DueAlertActivity::class.java)
                .putExtra(DueAlertActivity.EXTRA_TEXT, text)
                .putExtra(EXTRA_DUE, due)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_memo)
            .setContentTitle(text.lineSequence().first())
            .setContentText(context.getString(R.string.due_now))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(ALERT_NOTIFICATION_ID, notification)
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_DUE) return
            val due = intent.getLongExtra(EXTRA_DUE, 0L)
            if (due > 0) alert(context, due)
            // 알림 줄(지남 표시)과 다음 알람을 갱신
            MemoNotifier.refresh(context)
        }
    }
}
