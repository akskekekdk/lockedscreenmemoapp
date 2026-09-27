package com.lockmemo.app

import android.animation.ArgbEvaluator
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** 메모 날짜·시간 표시와 선택. */
object DueFormat {
    /** "오늘 14:00", "내일 09:30", "9/28(일) 14:00", "2027.1.3(일) 14:00" 형태. */
    fun format(context: Context, due: Long, now: Long = System.currentTimeMillis()): String {
        val target = Calendar.getInstance().apply { timeInMillis = due }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(due))
        val dayDiff = daysBetween(today, target)
        val day = when {
            dayDiff == 0 -> context.getString(R.string.today)
            dayDiff == 1 -> context.getString(R.string.tomorrow)
            dayDiff == -1 -> context.getString(R.string.yesterday)
            target.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
                SimpleDateFormat("M/d(E)", Locale.getDefault()).format(Date(due))
            else -> SimpleDateFormat("yyyy.M.d(E)", Locale.getDefault()).format(Date(due))
        }
        val label = "$day $time"
        return if (due < now) context.getString(R.string.past_label, label) else label
    }

    const val DAY_MS = 86_400_000L

    /** 정해진 시각 앞뒤 2시간 동안 색이 점점 진해진다. */
    const val URGENT_WINDOW_MS = 2 * 60 * 60_000L
    private const val URGENT_START = 0xFFFFA23A.toInt() // 2시간 남았을 때: 주황
    private const val URGENT_END = 0xFFFF2D2D.toInt()   // 딱 그 시각: 빨강

    /** 0(2시간 이상 차이) ~ 1(바로 지금). 정해진 시각 전후 모두 같은 방식. */
    fun urgency(due: Long, now: Long = System.currentTimeMillis()): Float =
        (1f - abs(due - now).toFloat() / URGENT_WINDOW_MS).coerceAtLeast(0f)

    /** 가까울수록 주황 → 빨강. 2시간 밖이면 null(기본 색). */
    fun urgencyColor(due: Long, now: Long = System.currentTimeMillis()): Int? {
        val t = urgency(due, now)
        if (t <= 0f) return null
        return ArgbEvaluator().evaluate(t, URGENT_START, URGENT_END) as Int
    }

    /**
     * 남은 시간. 하루 이내면 "2시간 30분 남음" / "15분 남음", 그보다 멀면 "D-3". 지났으면 null.
     */
    fun remaining(context: Context, due: Long, now: Long = System.currentTimeMillis()): String? {
        val left = due - now
        if (left <= 0) return null
        if (left > DAY_MS) {
            val days = daysBetween(
                Calendar.getInstance().apply { timeInMillis = now },
                Calendar.getInstance().apply { timeInMillis = due },
            )
            return context.getString(R.string.d_day, days)
        }
        val minutes = (left + 59_999) / 60_000 // 올림: 29분 40초 남았으면 "30분"
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> context.getString(R.string.left_hours_minutes, h, m)
            h > 0 -> context.getString(R.string.left_hours, h)
            else -> context.getString(R.string.left_minutes, m)
        }
    }

    /** 정해진 시간 + 남은 시간: "오늘 14:00 · 2시간 30분 남음", 지났으면 "오늘 14:00 (지남)". */
    fun withRemaining(context: Context, due: Long, now: Long = System.currentTimeMillis()): String {
        val label = format(context, due, now)
        return remaining(context, due, now)?.let { "$label · $it" } ?: label
    }

    /** 메모 한 줄: 날짜가 있으면 "오늘 14:00 · 내용", 없으면 내용만. */
    fun line(context: Context, memo: Memo, now: Long = System.currentTimeMillis()): String =
        if (memo.due == null) memo.text else "${format(context, memo.due, now)} · ${memo.text}"

    private fun daysBetween(from: Calendar, to: Calendar): Int {
        fun startOfDay(c: Calendar) = (c.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 12) // 서머타임에도 하루 차이가 정확하도록 정오 기준
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return Math.round((startOfDay(to) - startOfDay(from)) / 86_400_000.0).toInt()
    }

    /** 다음 날 자정. 표시("오늘/내일")를 갱신할 시각. */
    fun nextMidnight(now: Long = System.currentTimeMillis()): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /**
     * 키보드로 "일 시간 분 초"를 직접 입력해 지금부터 그만큼 뒤로 정한다(초 단위까지).
     * 칸마다 범위 제한은 없어서 "90분"처럼 넣어도 그대로 더한다.
     */
    fun pickTyped(activity: Activity, onPicked: (Long) -> Unit) {
        val density = activity.resources.displayMetrics.density
        val units = listOf(
            R.string.unit_days_label to 86_400L,
            R.string.unit_hours_label to 3_600L,
            R.string.unit_minutes_label to 60L,
            R.string.unit_seconds_label to 1L,
        )
        lateinit var dialog: AlertDialog
        val fields = units.mapIndexed { index, _ ->
            EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(4))
                gravity = Gravity.CENTER
                hint = "0"
                textSize = 22f
                setSelectAllOnFocus(true)
                imeOptions = if (index == units.lastIndex) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
            }
        }
        fun total(): Long = fields.zip(units).sumOf { (field, unit) ->
            (field.text.toString().toLongOrNull() ?: 0L) * unit.second
        }
        fun confirm() {
            val seconds = total()
            if (seconds <= 0) {
                fields.first().error = activity.getString(R.string.typed_empty)
                return
            }
            onPicked(System.currentTimeMillis() + seconds * 1000)
            dialog.dismiss()
        }
        fields.last().setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                confirm()
                true
            } else {
                false
            }
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, 0)
            fields.zip(units).forEach { (field, unit) ->
                addView(
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                        addView(TextView(activity).apply {
                            setText(unit.first)
                            gravity = Gravity.CENTER
                        })
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (4 * density).toInt()
                        marginEnd = (4 * density).toInt()
                    },
                )
            }
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.typed_title)
            .setView(row)
            .setPositiveButton(android.R.string.ok, null) // 빈 입력이면 닫지 않도록 아래에서 직접 처리
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { confirm() }
            fields.first().requestFocus()
        }
        dialog.show()
    }

    /** 지금부터 얼마 뒤인지 고른다(10분 후, 2시간 후 …). 결과는 그 시각(분 단위로 맞춤). */
    fun pickDuration(activity: Activity, onPicked: (Long) -> Unit) {
        val presets = listOf(10L, 30L, 60L, 120L, 180L, 360L, 720L, 1440L, 4320L) // 분
        val labels = presets.map { durationLabel(activity, it) } + activity.getString(R.string.custom_duration)
        AlertDialog.Builder(activity)
            .setTitle(R.string.pick_remaining_title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < presets.size) onPicked(afterMinutes(presets[which])) else pickCustomDuration(activity, onPicked)
            }
            .show()
    }

    private fun afterMinutes(minutes: Long): Long {
        val due = System.currentTimeMillis() + minutes * 60_000
        return due / 60_000 * 60_000
    }

    /** 10 → "10분 후", 90 → "1시간 30분 후", 1440 → "1일 후" */
    private fun durationLabel(context: Context, minutes: Long): String {
        val d = minutes / 1440
        val h = minutes % 1440 / 60
        val m = minutes % 60
        val parts = buildList {
            if (d > 0) add(context.getString(R.string.unit_days, d))
            if (h > 0) add(context.getString(R.string.unit_hours, h))
            if (m > 0) add(context.getString(R.string.unit_minutes, m))
        }
        return context.getString(R.string.after, parts.joinToString(" "))
    }

    private fun pickCustomDuration(activity: Activity, onPicked: (Long) -> Unit) {
        fun picker(max: Int, unit: Int) = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val number = NumberPicker(activity).apply {
                minValue = 0
                maxValue = max
                // 0에서 위로 돌리면 최댓값(23시간, 59분)으로 넘어간다. 범위를 정한 뒤에 켜야 적용된다
                wrapSelectorWheel = true
                // 가운데 숫자를 누르면 키보드로 직접 입력할 수 있다
                descendantFocusability = NumberPicker.FOCUS_AFTER_DESCENDANTS
            }
            addView(number)
            addView(TextView(activity).apply {
                setText(unit)
                gravity = Gravity.CENTER
            })
            tag = number
        }
        val days = picker(30, R.string.unit_days_label)
        val hours = picker(23, R.string.unit_hours_label)
        val minutes = picker(59, R.string.unit_minutes_label)
        (hours.tag as NumberPicker).value = 1
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val pad = (16 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            listOf(days, hours, minutes).forEach {
                addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.pick_remaining_title)
            .setView(row)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // 키보드로 입력 중인 숫자도 반영되도록 먼저 포커스를 뺀다
                listOf(days, hours, minutes).forEach { (it.tag as NumberPicker).clearFocus() }
                val total = (days.tag as NumberPicker).value * 1440L +
                    (hours.tag as NumberPicker).value * 60L +
                    (minutes.tag as NumberPicker).value
                if (total > 0) onPicked(afterMinutes(total))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 날짜 → 시간 순으로 고른다. [initial]이 없으면 다음 정각으로 시작. */
    fun pick(activity: Activity, initial: Long?, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply {
            if (initial != null) {
                timeInMillis = initial
            } else {
                add(Calendar.HOUR_OF_DAY, 1)
                set(Calendar.MINUTE, 0)
            }
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        DatePickerDialog(activity, { _, year, month, day ->
            cal.set(year, month, day)
            TimePickerDialog(activity, { _, hour, minute ->
                cal.set(Calendar.HOUR_OF_DAY, hour)
                cal.set(Calendar.MINUTE, minute)
                onPicked(cal.timeInMillis)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), DateFormat.is24HourFormat(activity)).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }
}
