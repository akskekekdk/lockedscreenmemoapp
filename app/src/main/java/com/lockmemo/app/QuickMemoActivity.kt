package com.lockmemo.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * 잠금화면 위에 바로 뜨는 한 줄 메모 입력창.
 * 잠금을 풀지 않아도 되며, 저장하면 곧바로 잠금화면으로 돌아간다.
 * (보안상 기존 메모 목록 전체는 여기서 보여주지 않는다.)
 */
class QuickMemoActivity : Activity() {
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        setContentView(R.layout.activity_quick_memo)
        setFinishOnTouchOutside(true)

        input = findViewById(R.id.quick_input)
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                save()
                true
            } else {
                false
            }
        }
        findViewById<TextView>(R.id.quick_save).setOnClickListener { save() }
        findViewById<TextView>(R.id.quick_cancel).setOnClickListener { finish() }
        input.requestFocus()
    }

    private fun save() {
        if (MemoStore.add(this, input.text.toString())) {
            MemoNotifier.refresh(this)
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        } else {
            input.error = getString(R.string.empty_error)
        }
    }
}
