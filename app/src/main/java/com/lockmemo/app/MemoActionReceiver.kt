package com.lockmemo.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/** 알림에서 들어온 동작(인라인 메모 저장, 지워진 알림 다시 띄우기)을 처리한다. */
class MemoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ADD -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(MemoNotifier.KEY_TEXT)?.toString()
                if (text != null) MemoStore.add(context, text)
            }
            ACTION_REPOST -> Unit
        }
        // 알림을 다시 그려야 입력 중 표시(스피너)가 사라지고 새 메모가 보인다
        MemoNotifier.refresh(context)
    }

    companion object {
        const val ACTION_ADD = "com.lockmemo.app.ADD"
        const val ACTION_REPOST = "com.lockmemo.app.REPOST"
    }
}
