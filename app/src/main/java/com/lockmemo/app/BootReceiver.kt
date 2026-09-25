package com.lockmemo.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 재부팅/업데이트/시계 변경 후 잠금화면 메모 알림을 다시 그린다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> MemoNotifier.refresh(context)
        }
    }
}
