package com.lockmemo.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 정해진 시간이 딱 되면(남은 시간 0:00:00) 소리·진동 없이 화면 전체에 알림을 띄운다.
 * 가장 가까운 다음 시각 하나에만 정확한 알람을 걸고, 울리면 그다음 시각으로 다시 건다.
 */
object DueAlarm {
    const val ACTION_DUE = "com.lockmemo.app.DUE"
    const val ACTION_REPOST = "com.lockmemo.app.DUE_REPOST"
    const val ACTION_DISMISS = "com.lockmemo.app.DUE_DISMISS"
    private const val KEY_ACTIVE_TEXT = "active_text"
    private const val KEY_ACTIVE_DUE = "active_due"
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

    /** [due] 시각이 된 메모들을 전체 화면으로 보여준다. 끄기 전까지 계속 떠 있다. */
    fun alert(context: Context, due: Long) {
        // 같은 분에 정해진 메모는 함께 보여준다
        val memos = MemoStore.all(context).filter { it.due != null && it.due / 60_000 == due / 60_000 }
        if (memos.isEmpty()) return
        val text = memos.joinToString("\n") { it.text }
        prefs(context).edit().putString(KEY_ACTIVE_TEXT, text).putLong(KEY_ACTIVE_DUE, due).apply()
        post(context, text, due)
    }

    /** 아직 끄지 않은 알림이 있으면 다시 띄운다(홈으로 나갔거나 알림을 밀어서 지웠을 때). */
    fun repost(context: Context) {
        val text = prefs(context).getString(KEY_ACTIVE_TEXT, null) ?: return
        val manager = NotificationManagerCompat.from(context)
        // 같은 알림을 고치기만 하면 전체 화면이 다시 뜨지 않아서, 지웠다가 새로 올린다
        manager.cancel(ALERT_NOTIFICATION_ID)
        post(context, text, prefs(context).getLong(KEY_ACTIVE_DUE, System.currentTimeMillis()))
    }

    /** 끄기: 알림을 없애고 더 이상 다시 띄우지 않는다. */
    fun dismiss(context: Context) {
        prefs(context).edit().remove(KEY_ACTIVE_TEXT).remove(KEY_ACTIVE_DUE).apply()
        NotificationManagerCompat.from(context).cancel(ALERT_NOTIFICATION_ID)
    }

    fun isActive(context: Context) = prefs(context).contains(KEY_ACTIVE_TEXT)

    private fun prefs(context: Context) = context.getSharedPreferences("due_alert", Context.MODE_PRIVATE)

    /** '다른 앱 위에 표시'가 허용돼 있으면 폰을 쓰는 중에도 바로 전체 화면을 띄울 수 있다. */
    fun canShowOverApps(context: Context) = Settings.canDrawOverlays(context)

    private fun post(context: Context, text: String, due: Long) {
        val screen = Intent(context, DueAlertActivity::class.java)
            .putExtra(DueAlertActivity.EXTRA_TEXT, text)
            .putExtra(EXTRA_DUE, due)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        if (canShowOverApps(context)) {
            // 알림은 폰을 쓰는 중이면 위쪽 팝업으로만 뜨므로, 허용돼 있으면 전체 화면을 직접 연다
            runCatching { context.startActivity(screen) }
        }
        if (!MemoNotifier.canNotify(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 21, screen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reposted = PendingIntent.getBroadcast(
            context, 22,
            Intent(context, Receiver::class.java).setAction(ACTION_REPOST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 소리·진동은 채널에서 끈다. (setSilent 는 팝업·전체 화면까지 막을 수 있어 쓰지 않는다)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_memo)
            .setContentTitle(text.lineSequence().first())
            .setContentText(context.getString(R.string.due_now))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            // 밀어서 지워도 끄기 전까지는 다시 띄운다
            .setDeleteIntent(reposted)
            .addAction(
                R.drawable.ic_memo,
                context.getString(R.string.alert_dismiss),
                PendingIntent.getBroadcast(
                    context, 23,
                    Intent(context, Receiver::class.java).setAction(ACTION_DISMISS),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(ALERT_NOTIFICATION_ID, notification)
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_DUE -> {
                    val due = intent.getLongExtra(EXTRA_DUE, 0L)
                    if (due > 0) alert(context, due)
                    // 알림 줄(지남 표시)과 다음 알람을 갱신
                    MemoNotifier.refresh(context)
                }
                ACTION_REPOST -> repost(context)
                ACTION_DISMISS -> dismiss(context)
            }
        }
    }
}
