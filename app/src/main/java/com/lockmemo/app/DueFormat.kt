package com.lockmemo.app

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
