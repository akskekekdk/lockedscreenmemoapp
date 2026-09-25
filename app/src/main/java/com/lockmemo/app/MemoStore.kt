package com.lockmemo.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** [time]: 작성 시각(식별자 겸용), [due]: 사용자가 지정한 날짜·시간(없으면 null) */
data class Memo(val time: Long, val text: String, val due: Long? = null)

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
            Memo(obj.getLong("t"), obj.getString("m"), if (obj.has("d")) obj.getLong("d") else null)
        }
    }

    /**
     * 보여줄 순서: 다가오는 날짜 메모(가까운 순) → 날짜 없는 메모(최신 순) → 지난 날짜 메모(최근 지난 순).
     */
    fun sorted(context: Context, now: Long = System.currentTimeMillis()): List<Memo> {
        val memos = all(context)
        val upcoming = memos.filter { it.due != null && it.due >= now }.sortedBy { it.due }
        val undated = memos.filter { it.due == null }
        val past = memos.filter { it.due != null && it.due < now }.sortedByDescending { it.due }
        return upcoming + undated + past
    }

    /** 줄바꿈은 공백으로 합쳐 항상 한 줄로 저장한다. 빈 메모면 false. */
    @Synchronized
    fun add(context: Context, text: String, due: Long? = null): Boolean {
        val line = text.replace(Regex("\\s+"), " ").trim()
        if (line.isEmpty()) return false
        save(context, (listOf(Memo(System.currentTimeMillis(), line, due)) + all(context)).take(MAX_MEMOS))
        return true
    }

    @Synchronized
    fun setDue(context: Context, memo: Memo, due: Long?) {
        save(context, all(context).map { if (it.time == memo.time) it.copy(due = due) else it })
    }

    @Synchronized
    fun remove(context: Context, memo: Memo) {
        save(context, all(context).filterNot { it.time == memo.time })
    }

    private fun save(context: Context, memos: List<Memo>) {
        val array = JSONArray()
        memos.forEach {
            val obj = JSONObject().put("t", it.time).put("m", it.text)
            if (it.due != null) obj.put("d", it.due)
            array.put(obj)
        }
        prefs(context).edit().putString(KEY_MEMOS, array.toString()).apply()
    }

    fun isLockScreenEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOCKSCREEN, true)

    fun setLockScreenEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCKSCREEN, enabled).apply()
    }
}
