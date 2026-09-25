package com.lockmemo.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 재부팅/업데이트 후 잠금화면 메모 알림을 복구한다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            MemoNotifier.refresh(context)
        }
    }
}
