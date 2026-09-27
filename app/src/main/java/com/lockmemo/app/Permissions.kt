package com.lockmemo.app

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * 잠금화면 메모와 정해진 시간 전체 화면 알림에 필요한 권한들의 현재 상태.
 * 설정 화면에 한눈에 보여주고, 꺼진 것은 바로 허용 화면으로 보낸다.
 */
object Permissions {
    class Item(val label: Int, val granted: Boolean, val fix: Intent)

    fun all(context: Context): List<Item> {
        val pkg = Uri.parse("package:${context.packageName}")
        val items = mutableListOf<Item>()
        items += Item(
            R.string.perm_notifications,
            MemoNotifier.canNotify(context) && NotificationManagerCompat.from(context).areNotificationsEnabled(),
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            items += Item(
                R.string.perm_full_screen,
                DueAlarm.canUseFullScreen(context),
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg),
            )
        }
        items += Item(
            R.string.perm_overlay,
            DueAlarm.canShowOverApps(context),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            items += Item(
                R.string.perm_exact_alarm,
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg),
            )
        }
        items += Item(
            R.string.perm_battery,
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            // 이 앱만 "배터리 사용량 최적화 안 함"으로 바꿀지 바로 묻는 창
            @Suppress("BatteryLife")
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
        )
        items += Item(
            R.string.perm_install,
            context.packageManager.canRequestPackageInstalls(),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg),
        )
        return items
    }

    /** 정해진 시간 전체 화면 알림에 필요한 것 중 꺼진 게 있는지(업데이트 설치 허용은 제외). */
    fun alertBlocked(context: Context) = all(context).any { !it.granted && it.label != R.string.perm_install }
}
