package com.lockmemo.app

import android.app.Activity
import android.view.View
import android.widget.TextView

/**
 * 입력창 아래 "📅 날짜·시간" / "⏱ 남은 시간" / "⌨ 직접 입력" 버튼과 ✕ 버튼을 묶어 선택한 값을 들고 있는다.
 * 어느 쪽으로 정해도 "📅 오늘 14:00 · 2시간 30분 남음" 처럼 정해진 시간과 남은 시간을 같이 보여준다.
 */
class DueChooser(private val activity: Activity) {
    private val dateButton: TextView = activity.findViewById(R.id.due_button)
    private val remainButton: View = activity.findViewById(R.id.remain_button)
    private val typeButton: View = activity.findViewById(R.id.type_button)
    private val clear: View = activity.findViewById(R.id.due_clear)

    var due: Long? = null
        set(value) {
            field = value
            refreshLabel()
            remainButton.visibility = if (value == null) View.VISIBLE else View.GONE
            typeButton.visibility = if (value == null) View.VISIBLE else View.GONE
            clear.visibility = if (value == null) View.GONE else View.VISIBLE
        }

    init {
        dateButton.setOnClickListener { DueFormat.pick(activity, due) { due = it } }
        remainButton.setOnClickListener { DueFormat.pickDuration(activity) { due = it } }
        typeButton.setOnClickListener { DueFormat.pickTyped(activity) { due = it } }
        clear.setOnClickListener { due = null }
    }

    /** 남은 시간이 줄어드니 화면에 있는 동안 주기적으로 다시 그린다. */
    fun refreshLabel() {
        dateButton.text = due?.let { activity.getString(R.string.due_chip, DueFormat.withRemaining(activity, it)) }
            ?: activity.getString(R.string.add_due)
    }
}
