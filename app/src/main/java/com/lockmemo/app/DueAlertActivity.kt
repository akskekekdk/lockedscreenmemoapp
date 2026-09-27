package com.lockmemo.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 정해진 시간이 됐을 때 잠금화면 위에 화면 전체로 뜨는 알림(무음).
 * "끄기"를 누르기 전까지는 닫히지 않는다: 뒤로가기는 막고, 홈 등으로 나가면 다시 띄운다.
 */
class DueAlertActivity : Activity() {
    private var dismissed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        // 끄기 전까지 화면이 꺼지지 않게
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_due_alert)
        findViewById<TextView>(R.id.alert_dismiss).setOnClickListener {
            dismissed = true
            DueAlarm.dismiss(this)
            finish()
        }
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    private fun show(intent: Intent) {
        // 이미 꺼진 알림을 누른 경우(예: 알림 줄의 끄기)면 바로 닫는다
        if (!DueAlarm.isActive(this)) {
            dismissed = true
            finish()
            return
        }
        val due = intent.getLongExtra(DueAlarm.EXTRA_DUE, System.currentTimeMillis())
        findViewById<TextView>(R.id.alert_time).text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(due))
        findViewById<TextView>(R.id.alert_text).text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
    }

    @Deprecated("끄기 버튼으로만 닫는다")
    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        // 뒤로가기로는 닫지 않는다
    }

    override fun onStop() {
        super.onStop()
        // 화면이 꺼져서 멈춘 거면 그대로 두고(다시 켜면 이 화면이 그대로 보인다),
        // 홈 버튼 등으로 다른 화면에 간 거면 알림을 다시 띄운다
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        if (!dismissed && !isChangingConfigurations && screenOn && DueAlarm.isActive(this)) {
            DueAlarm.repost(this)
        }
    }

    companion object {
        const val EXTRA_TEXT = "text"
    }
}
