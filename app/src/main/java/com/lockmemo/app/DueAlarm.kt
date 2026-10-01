package com.lockmemo.app

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 정해진 시간이 딱 되면(남은 시간 0:00:00) 소리·진동 없이 화면 전체에 알림을 띄운다.
 * 가장 가까운 다음 시각 하나에만 정확한 알람을 걸고, 울리면 그다음 시각으로 다시 건다.
 */
@SuppressLint("StaticFieldLeak") // overlay: 앱 컨텍스트로 만든 1픽셀 창(아래 설명)
object DueAlarm {
    const val ACTION_DUE = "com.lockmemo.app.DUE"
    const val ACTION_REPOST = "com.lockmemo.app.DUE_REPOST"
    const val ACTION_DISMISS = "com.lockmemo.app.DUE_DISMISS"
    const val ACTION_TEST = "com.lockmemo.app.DUE_TEST"
    private const val KEY_ACTIVE_TEXT = "active_text"
    private const val KEY_ACTIVE_DUE = "active_due"
    private const val KEY_ACTIVE_STARTED = "active_started"
    const val ACTION_EXPIRE = "com.lockmemo.app.DUE_EXPIRE"

    /** 끄지 않아도 이 시간이 지나면 알림을 저절로 지운다. */
    const val MAX_ALERT_MS = 2 * 60 * 60_000L
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
            // "알람 시계" 방식: 절전·잠자기 중에도 가장 확실하게 제시간에 울린다(상태바에 알람 아이콘이 보일 수 있음)
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(next, openAppIntent(context)), intent)
        } else {
            // 정확한 알람 권한이 없으면 조금 늦을 수 있는 방식으로라도 건다
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
        }
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 25,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 설정 화면의 "10초 뒤 알림 시험": 메모 없이 시험용 전체 화면 알림만 띄운다. */
    fun scheduleTest(context: Context, delayMs: Long = 10_000) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + delayMs
        val intent = PendingIntent.getBroadcast(
            context, 24,
            Intent(context, Receiver::class.java).setAction(ACTION_TEST).putExtra(EXTRA_DUE, at),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, openAppIntent(context)), intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
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
        alertText(context, memos.joinToString("\n") { it.text }, due)
    }

    private fun alertText(context: Context, text: String, due: Long) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putString(KEY_ACTIVE_TEXT, text)
            .putLong(KEY_ACTIVE_DUE, due)
            .putLong(KEY_ACTIVE_STARTED, now)
            .apply()
        scheduleExpiry(context, now + MAX_ALERT_MS)
        post(context, text, due)
    }

    /** 알림이 저절로 지워지는 시각(뜬 지 2시간 뒤). 떠 있는 알림이 없으면 0. */
    fun expiresAt(context: Context): Long {
        val started = prefs(context).getLong(KEY_ACTIVE_STARTED, 0L)
        return if (started == 0L) 0L else started + MAX_ALERT_MS
    }

    private fun expiryIntent(context: Context) = PendingIntent.getBroadcast(
        context, 26,
        Intent(context, Receiver::class.java).setAction(ACTION_EXPIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun scheduleExpiry(context: Context, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, expiryIntent(context))
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, expiryIntent(context))
        }
    }

    /** 아직 끄지 않은 알림이 있으면 다시 띄운다(홈으로 나갔거나 알림을 밀어서 지웠을 때). */
    fun repost(context: Context) {
        if (!isActive(context)) return
        val text = prefs(context).getString(KEY_ACTIVE_TEXT, null) ?: return
        val manager = NotificationManagerCompat.from(context)
        // 같은 알림을 고치기만 하면 전체 화면이 다시 뜨지 않아서, 지웠다가 새로 올린다
        manager.cancel(ALERT_NOTIFICATION_ID)
        post(context, text, prefs(context).getLong(KEY_ACTIVE_DUE, System.currentTimeMillis()))
    }

    /** 끄기: 알림을 없애고 더 이상 다시 띄우지 않는다. */
    fun dismiss(context: Context) {
        prefs(context).edit().remove(KEY_ACTIVE_TEXT).remove(KEY_ACTIVE_DUE).remove(KEY_ACTIVE_STARTED).apply()
        context.getSystemService(AlarmManager::class.java).cancel(expiryIntent(context))
        NotificationManagerCompat.from(context).cancel(ALERT_NOTIFICATION_ID)
    }

    /** 끄지 않은 알림이 있는지. 뜬 지 2시간이 지났으면 지우고 false. */
    fun isActive(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (!prefs(context).contains(KEY_ACTIVE_TEXT)) return false
        val expires = expiresAt(context)
        if (expires != 0L && now >= expires) {
            dismiss(context)
            return false
        }
        return true
    }

    private fun prefs(context: Context) = context.getSharedPreferences("due_alert", Context.MODE_PRIVATE)

    /** '다른 앱 위에 표시'가 허용돼 있으면 폰을 쓰는 중에도 바로 전체 화면을 띄울 수 있다. */
    fun canShowOverApps(context: Context) = Settings.canDrawOverlays(context)

    // 앱 전체 컨텍스트로 만든 1픽셀 창이라 액티비티를 붙잡지 않는다
    @SuppressLint("StaticFieldLeak")
    private var overlay: View? = null

    /**
     * Android 15부터는 '다른 앱 위에 표시' 권한이 있어도 실제로 떠 있는 오버레이 창이 있어야
     * 뒤에서 화면을 열 수 있다. 그래서 1픽셀짜리 투명 창을 잠깐 띄운 뒤 연다.
     */
    private fun startOverApps(context: Context, screen: Intent) {
        val windows = context.getSystemService(WindowManager::class.java)
        if (overlay == null) {
            val view = View(context)
            val params = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            )
            if (runCatching { windows.addView(view, params) }.isSuccess) overlay = view
        }
        runCatching { context.startActivity(screen) }
        Handler(Looper.getMainLooper()).postDelayed({ removeOverlay(context) }, 5_000)
    }

    /** 전체 화면이 뜨면(또는 몇 초 뒤) 투명 창을 치운다. */
    fun removeOverlay(context: Context) {
        val view = overlay ?: return
        overlay = null
        runCatching { context.applicationContext.getSystemService(WindowManager::class.java).removeView(view) }
    }

    private fun post(context: Context, text: String, due: Long) {
        val screen = Intent(context, DueAlertActivity::class.java)
            .putExtra(DueAlertActivity.EXTRA_TEXT, text)
            .putExtra(EXTRA_DUE, due)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        if (canShowOverApps(context)) {
            // 알림은 폰을 쓰는 중이면 위쪽 팝업으로만 뜨므로, 허용돼 있으면 전체 화면을 직접 연다
            startOverApps(context.applicationContext, screen)
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
            // 끄지 않아도 처음 뜬 지 2시간이 지나면 저절로 사라진다
            .apply {
                val left = expiresAt(context) - System.currentTimeMillis()
                if (left > 0) setTimeoutAfter(left)
            }
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
                ACTION_TEST -> alertText(context, context.getString(R.string.test_alert_text), intent.getLongExtra(EXTRA_DUE, System.currentTimeMillis()))
                ACTION_DISMISS -> dismiss(context)
                ACTION_EXPIRE -> dismiss(context)
            }
        }
    }
}
