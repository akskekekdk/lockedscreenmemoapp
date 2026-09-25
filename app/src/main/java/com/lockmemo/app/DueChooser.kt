package com.lockmemo.app

import android.app.Activity
import android.view.View
import android.widget.TextView

/** 입력창 아래 "📅 날짜·시간 설정" 칩과 ✕ 버튼을 묶어 선택한 값을 들고 있는다. */
class DueChooser(private val activity: Activity) {
    private val button: TextView = activity.findViewById(R.id.due_button)
    private val clear: View = activity.findViewById(R.id.due_clear)

    var due: Long? = null
        set(value) {
            field = value
            button.text = value?.let { activity.getString(R.string.due_chip, DueFormat.format(activity, it)) }
                ?: activity.getString(R.string.add_due)
            clear.visibility = if (value == null) View.GONE else View.VISIBLE
        }

    init {
        button.setOnClickListener { DueFormat.pick(activity, due) { due = it } }
        clear.setOnClickListener { due = null }
    }
}
