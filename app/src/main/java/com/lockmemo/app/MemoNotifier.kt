package com.lockmemo.app

import android.Manifest
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 잠금화면에 항상 떠 있는 메모 알림.
 * - 최근 메모를 한 줄씩 보여주고
 * - "한 줄 메모" 버튼으로 잠금을 풀지 않고 바로 입력(인라인 답장)할 수 있다.
 */
object MemoNotifier {
    const val CHANNEL_ID = "lockscreen_memo"
    const val NOTIFICATION_ID = 1
    const val KEY_TEXT = "memo_text"
    private const val PREVIEW_LINES = 5

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW, // 소리/진동 없이 조용히 표시
        ).apply {
            description = context.getString(R.string.channel_desc)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** 설정에 따라 알림을 띄우거나 내린다. 메모가 바뀔 때마다 호출. */
    fun refresh(context: Context) {
        if (!MemoStore.isLockScreenEnabled(context)) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            return
        }
        if (!canNotify(context)) return
        ensureChannel(context)
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(context))
    }

    private fun build(context: Context): android.app.Notification {
        val memos = MemoStore.all(context)
        val timeFormat = SimpleDateFormat("M/d HH:mm", Locale.getDefault())

        val style = NotificationCompat.InboxStyle()
        memos.take(PREVIEW_LINES).forEach { style.addLine("${timeFormat.format(Date(it.time))}  ${it.text}") }
        if (memos.size > PREVIEW_LINES) {
            style.setSummaryText(context.getString(R.string.more_memos, memos.size - PREVIEW_LINES))
        }

        val title = if (memos.isEmpty()) context.getString(R.string.empty_title)
        else context.getString(R.string.memo_count, memos.size)
        val summary = memos.firstOrNull()?.text ?: context.getString(R.string.empty_hint)

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

        val openQuickMemo = PendingIntent.getActivity(
            context, 2,
            Intent(context, QuickMemoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_memo)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(style)
            .setContentIntent(openQuickMemo)
            .addAction(replyAction)
            .addAction(R.drawable.ic_memo, context.getString(R.string.action_quick), openQuickMemo)
            .setOngoing(true)
            // Android 14+에서 사용자가 밀어서 지워도 다시 띄운다
            .setDeleteIntent(actionIntent(context, MemoActionReceiver.ACTION_REPOST, 3))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MemoActionReceiver::class.java).setAction(action)
        // 인라인 입력은 시스템이 결과를 채워 넣어야 하므로 MUTABLE 이어야 한다
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (action == MemoActionReceiver.ACTION_ADD) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }
}
