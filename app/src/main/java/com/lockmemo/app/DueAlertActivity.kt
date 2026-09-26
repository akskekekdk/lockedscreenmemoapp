package com.lockmemo.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 정해진 시간이 됐을 때 잠금화면 위에 화면 전체로 뜨는 알림(무음). */
class DueAlertActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_due_alert)

        val due = intent.getLongExtra(DueAlarm.EXTRA_DUE, System.currentTimeMillis())
        findViewById<TextView>(R.id.alert_time).text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(due))
        findViewById<TextView>(R.id.alert_text).text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        findViewById<TextView>(R.id.alert_dismiss).setOnClickListener { finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        NotificationManagerCompat.from(this).cancel(DueAlarm.ALERT_NOTIFICATION_ID)
    }

    companion object {
        const val EXTRA_TEXT = "text"
    }
}
