package com.lockmemo.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Memo(val time: Long, val text: String)

/** 한 줄 메모를 SharedPreferences에 JSON 배열로 저장한다. 최신 메모가 맨 앞. */
object MemoStore {
    private const val PREFS = "memos"
    private const val KEY_MEMOS = "items"
    private const val KEY_LOCKSCREEN = "lockscreen_enabled"
    private const val MAX_MEMOS = 200

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(context: Context): List<Memo> {
        val raw = prefs(context).getString(KEY_MEMOS, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map {
            val obj = array.getJSONObject(it)
            Memo(obj.getLong("t"), obj.getString("m"))
        }
    }

    /** 줄바꿈은 공백으로 합쳐 항상 한 줄로 저장한다. 빈 메모면 false. */
    @Synchronized
    fun add(context: Context, text: String): Boolean {
        val line = text.replace(Regex("\\s+"), " ").trim()
        if (line.isEmpty()) return false
        save(context, (listOf(Memo(System.currentTimeMillis(), line)) + all(context)).take(MAX_MEMOS))
        return true
    }

    @Synchronized
    fun remove(context: Context, memo: Memo) {
        save(context, all(context) - memo)
    }

    private fun save(context: Context, memos: List<Memo>) {
        val array = JSONArray()
        memos.forEach { array.put(JSONObject().put("t", it.time).put("m", it.text)) }
        prefs(context).edit().putString(KEY_MEMOS, array.toString()).apply()
    }

    fun isLockScreenEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOCKSCREEN, true)

    fun setLockScreenEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCKSCREEN, enabled).apply()
    }
}
