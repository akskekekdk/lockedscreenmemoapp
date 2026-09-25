package com.lockmemo.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** [time]: 작성 시각(식별자 겸용), [due]: 사용자가 지정한 날짜·시간(없으면 null) */
data class Memo(val time: Long, val text: String, val due: Long? = null)

/**
 * 한 줄 메모를 SharedPreferences에 JSON 배열로 저장한다.
 * 저장된 순서가 곧 보여줄 순서다(앱에서 꾹 눌러 끌어서 바꿀 수 있음). 새 메모는 맨 앞.
 */
object MemoStore {
    private const val PREFS = "memos"
    private const val KEY_MEMOS = "items"
    private const val KEY_LOCKSCREEN = "lockscreen_enabled"
    private const val KEY_WALLPAPER_MODE = "wallpaper_mode"
    private const val KEY_MANUAL_ORDER = "manual_order"
    private const val MAX_MEMOS = 200

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 보여줄 순서대로의 메모 목록. */
    @Synchronized
    fun all(context: Context): List<Memo> {
        val memos = read(context)
        if (prefs(context).getBoolean(KEY_MANUAL_ORDER, false)) return memos
        // 예전 버전은 날짜순으로 자동 정렬해서 보여줬다. 직접 순서를 정하는 방식으로 바뀌면서
        // 처음 한 번은 그때 보이던 순서를 그대로 저장해 둔다.
        val now = System.currentTimeMillis()
        val upcoming = memos.filter { it.due != null && it.due >= now }.sortedBy { it.due }
        val undated = memos.filter { it.due == null }
        val past = memos.filter { it.due != null && it.due < now }.sortedByDescending { it.due }
        val ordered = upcoming + undated + past
        save(context, ordered)
        prefs(context).edit().putBoolean(KEY_MANUAL_ORDER, true).apply()
        return ordered
    }

    private fun read(context: Context): List<Memo> {
        val raw = prefs(context).getString(KEY_MEMOS, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map {
            val obj = array.getJSONObject(it)
            Memo(obj.getLong("t"), obj.getString("m"), if (obj.has("d")) obj.getLong("d") else null)
        }
    }

    /** [from] 위치의 메모를 [to] 위치로 옮긴다. */
    @Synchronized
    fun move(context: Context, from: Int, to: Int) {
        val memos = all(context).toMutableList()
        if (from !in memos.indices || to !in memos.indices || from == to) return
        memos.add(to, memos.removeAt(from))
        save(context, memos)
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

    /** true 면 메모를 알림 대신 잠금화면 배경화면에 그린다(겹침 없음). */
    fun isWallpaperMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WALLPAPER_MODE, false)

    fun setWallpaperMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WALLPAPER_MODE, enabled).apply()
    }

    fun isLockScreenEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOCKSCREEN, true)

    fun setLockScreenEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCKSCREEN, enabled).apply()
    }
}
