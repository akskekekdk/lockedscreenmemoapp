package com.lockmemo.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 메모 목록 확인/추가/삭제와 잠금화면 표시 설정. */
class MainActivity : Activity() {
    companion object {
        private const val REQUEST_BACKGROUND = 1
    }

    private lateinit var input: EditText
    private lateinit var emptyView: View
    private lateinit var dueChooser: DueChooser
    private val adapter = MemoAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        input = findViewById(R.id.memo_input)
        emptyView = findViewById(R.id.empty_view)
        dueChooser = DueChooser(this)
        val list = findViewById<RecyclerView>(R.id.memo_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        reorder.attachToRecyclerView(list)

        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                addMemo()
                true
            } else {
                false
            }
        }
        findViewById<View>(R.id.add_button).setOnClickListener { addMemo() }

        val settingsCard = findViewById<View>(R.id.settings_card)
        findViewById<View>(R.id.settings_button).setOnClickListener {
            settingsCard.visibility = if (settingsCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            refreshPermissions()
        }
        findViewById<View>(R.id.test_alert_button).setOnClickListener {
            DueAlarm.scheduleTest(this)
            Toast.makeText(this, R.string.test_alert_scheduled, Toast.LENGTH_LONG).show()
        }

        val toggle = findViewById<Switch>(R.id.lockscreen_switch)
        toggle.isChecked = MemoStore.isLockScreenEnabled(this)
        toggle.setOnCheckedChangeListener { _, checked ->
            MemoStore.setLockScreenEnabled(this, checked)
            if (checked) requestNotificationPermissionIfNeeded()
            MemoNotifier.refresh(this)
            updateProblem()
        }

        findViewById<View>(R.id.open_settings).setOnClickListener { openNotificationSettings() }

        val wallpaperToggle = findViewById<Switch>(R.id.wallpaper_switch)
        wallpaperToggle.isChecked = MemoStore.isWallpaperMode(this)
        updateWallpaperOptions()
        wallpaperToggle.setOnCheckedChangeListener { button, checked ->
            if (!checked) {
                setWallpaperMode(false)
                return@setOnCheckedChangeListener
            }
            // 사용자의 잠금화면 배경을 바꾸므로 먼저 확인받는다
            AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_confirm_title)
                .setMessage(R.string.wallpaper_confirm)
                .setPositiveButton(R.string.turn_on) { _, _ -> setWallpaperMode(true) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> button.isChecked = false }
                .setOnCancelListener { button.isChecked = false }
                .show()
        }
        findViewById<View>(R.id.pick_background).setOnClickListener { chooseBackground() }

        findViewById<TextView>(R.id.version_text).text = getString(R.string.version_label, BuildConfig.VERSION_NAME)
        findViewById<View>(R.id.update_button).setOnClickListener { UpdateChecker.check(this, manual = true) }
        // 앱을 켤 때 가끔(6시간에 한 번) 새 버전이 있는지 조용히 확인한다
        UpdateChecker.check(this, manual = false)

        if (MemoStore.isLockScreenEnabled(this)) requestNotificationPermissionIfNeeded()
    }

    /** 남은 시간이 줄어드는 걸 보여주려고 화면에 있는 동안 30초마다 다시 그린다. */
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!dragging) reload() // 시간이 흐르면 가까운 순서·색이 바뀐다
            dueChooser.refreshLabel()
            ticker.postDelayed(this, 30_000)
        }
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    override fun onResume() {
        super.onResume()
        UpdateChecker.resumePending(this)
        refreshPermissions()
        ticker.postDelayed(tick, 30_000)
        reload()
        MemoNotifier.refresh(this)
        updateProblem()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        MemoNotifier.refresh(this)
        updateProblem()
    }

    /** 잠금화면 표시를 켰는데 알림 설정이 막고 있으면 원인과 설정 바로가기를 보여준다. */
    /** 잠금화면 표시나 정해진 시간 전체 화면 알림을 막고 있는 설정이 있으면 원인과 바로가기를 보여준다. */
    private fun updateProblem() {
        val lockReason = if (MemoStore.isLockScreenEnabled(this)) MemoNotifier.blockingReason(this) else null
        val hasUpcoming = MemoStore.all(this).any { it.due != null && it.due > System.currentTimeMillis() }
        permissionsNeeded = lockReason == null && hasUpcoming && Permissions.alertBlocked(this)
        val reason = lockReason ?: if (permissionsNeeded) R.string.problem_permissions else null
        findViewById<View>(R.id.problem_box).visibility = if (reason == null) View.GONE else View.VISIBLE
        if (reason == null) return
        findViewById<TextView>(R.id.problem_text).setText(reason)
        findViewById<View>(R.id.problem_tip).visibility = if (permissionsNeeded) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.open_settings).setText(
            if (permissionsNeeded) R.string.show_permissions else R.string.open_settings,
        )
    }

    private var permissionsNeeded = false

    private fun openNotificationSettings() {
        if (permissionsNeeded) {
            // 설정 카드를 펼쳐서 권한 상태를 보여준다
            findViewById<View>(R.id.settings_card).visibility = View.VISIBLE
            refreshPermissions()
            return
        }
        val notificationsOn = MemoNotifier.canNotify(this) &&
            NotificationManagerCompat.from(this).areNotificationsEnabled()
        val intent = if (notificationsOn) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_CHANNEL_ID, MemoNotifier.CHANNEL_ID)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        }.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        startActivity(intent)
    }

    /** 설정 카드의 권한 상태 목록: 허용된 건 ✓, 꺼진 건 [허용하기] 버튼. */
    private fun refreshPermissions() {
        val list = findViewById<android.widget.LinearLayout>(R.id.permission_list)
        list.removeAllViews()
        val pad = (8 * resources.displayMetrics.density).toInt()
        Permissions.all(this).forEach { item ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, pad)
            }
            row.addView(
                TextView(this).apply {
                    setText(item.label)
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 14f
                },
                android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(
                TextView(this).apply {
                    textSize = 13f
                    if (item.granted) {
                        setText(R.string.perm_ok)
                        setTextColor(getColor(R.color.text_secondary))
                    } else {
                        setText(R.string.perm_fix)
                        setTextColor(getColor(R.color.accent))
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setBackgroundResource(R.drawable.bg_chip)
                        setPadding(pad * 3 / 2, pad / 2, pad * 3 / 2, pad / 2)
                        setOnClickListener { runCatching { startActivity(item.fix) } }
                    }
                },
            )
            list.addView(row)
        }
    }


    private fun setWallpaperMode(enabled: Boolean) {
        MemoStore.setWallpaperMode(this, enabled)
        updateWallpaperOptions()
        MemoNotifier.refresh(this)
    }

    private fun updateWallpaperOptions() {
        findViewById<View>(R.id.wallpaper_options).visibility =
            if (MemoStore.isWallpaperMode(this)) View.VISIBLE else View.GONE
    }

    private fun chooseBackground() {
        AlertDialog.Builder(this)
            .setTitle(R.string.background_options)
            .setItems(arrayOf(getString(R.string.background_pick), getString(R.string.background_default))) { _, which ->
                if (which == 0) {
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Intent(MediaStore.ACTION_PICK_IMAGES)
                    } else {
                        Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                    }
                    startActivityForResult(intent, REQUEST_BACKGROUND)
                } else {
                    applyBackground(null)
                }
            }
            .show()
    }

    @Deprecated("Activity 기본 API 사용")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_BACKGROUND && resultCode == RESULT_OK) data?.data?.let { applyBackground(it) }
    }

    private fun applyBackground(uri: Uri?) {
        Thread {
            try {
                LockWallpaper.setBackground(this, uri)
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, R.string.background_failed, Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            runOnUiThread { MemoNotifier.refresh(this) }
        }.start()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    private fun addMemo() {
        if (MemoStore.add(this, input.text.toString(), dueChooser.due)) {
            input.text.clear()
            dueChooser.due = null
            reload()
            MemoNotifier.refresh(this)
            updateProblem()
        }
    }

    /** 메모를 누르면: 날짜·시간으로 정하기 / 남은 시간으로 정하기 / (있으면) 없애기 */
    private fun editDue(memo: Memo) {
        val apply = { due: Long? ->
            MemoStore.setDue(this, memo, due)
            reload()
            MemoNotifier.refresh(this)
            updateProblem()
        }
        val options = mutableListOf(
            getString(R.string.change_due),
            getString(R.string.set_remaining),
            getString(R.string.set_typed),
        )
        if (memo.due != null) options += getString(R.string.remove_due)
        AlertDialog.Builder(this)
            .setTitle(memo.text)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> DueFormat.pick(this, memo.due) { apply(it) }
                    1 -> DueFormat.pickDuration(this) { apply(it) }
                    2 -> DueFormat.pickTyped(this) { apply(it) }
                    else -> apply(null)
                }
            }
            .show()
    }

    private fun confirmDelete(memo: Memo) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, memo.text))
            .setPositiveButton(R.string.delete) { _, _ ->
                MemoStore.remove(this, memo)
                reload()
                MemoNotifier.refresh(this)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @SuppressLint("NotifyDataSetChanged") // 저장소에서 목록 전체를 다시 읽어 온다
    private fun reload() {
        adapter.memos = MemoStore.displayOrder(this).toMutableList()
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (adapter.memos.isEmpty()) View.VISIBLE else View.GONE
        updateHeader()
    }

    /** "9월 25일 목요일 · 메모 3개" */
    private fun updateHeader() {
        val today = SimpleDateFormat("M월 d일 EEEE", Locale.KOREAN).format(Date())
        findViewById<TextView>(R.id.header_date).text = getString(R.string.header_summary, today, adapter.memos.size)
    }

    /** 꾹 눌러서 위아래로 끌면 순서가 바뀐다. 손을 떼면 저장하고 잠금화면도 갱신. */
    private var dragging = false

    /**
     * 꾹 눌러서 위아래로 끌면 순서가 바뀐다. 날짜 있는 메모는 시간순 자동 정렬이라
     * 날짜 없는 메모끼리만 옮길 수 있다. 손을 떼면 저장하고 잠금화면도 갱신.
     */
    private val reorder = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        private fun isUndated(holder: RecyclerView.ViewHolder) =
            adapter.memos.getOrNull(holder.bindingAdapterPosition)?.due == null

        override fun getMovementFlags(rv: RecyclerView, holder: RecyclerView.ViewHolder): Int =
            if (isUndated(holder)) makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) else 0

        override fun canDropOver(rv: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) =
            isUndated(target)

        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            adapter.memos.add(b, adapter.memos.removeAt(a))
            adapter.notifyItemMoved(a, b)
            return true
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                dragging = true
                (viewHolder as? MemoHolder)?.card?.apply {
                    elevation = 10f * resources.displayMetrics.density
                    scaleX = 1.02f
                    scaleY = 1.02f
                }
            }
        }

        override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(rv, viewHolder)
            (viewHolder as? MemoHolder)?.card?.apply {
                elevation = 0f
                scaleX = 1f
                scaleY = 1f
            }
            dragging = false
            MemoStore.reorderUndated(this@MainActivity, adapter.memos.filter { it.due == null })
            MemoNotifier.refresh(this@MainActivity)
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
    })

    private class MemoHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: View = view.findViewById(R.id.memo_card)
        val text: TextView = view.findViewById(R.id.memo_text)
        val due: TextView = view.findViewById(R.id.memo_due)
        val time: TextView = view.findViewById(R.id.memo_time)
        val delete: View = view.findViewById(R.id.delete_button)
    }

    private inner class MemoAdapter : RecyclerView.Adapter<MemoHolder>() {
        var memos: MutableList<Memo> = mutableListOf()
        private val timeFormat = SimpleDateFormat("M/d HH:mm", Locale.getDefault())

        override fun getItemCount() = memos.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            MemoHolder(layoutInflater.inflate(R.layout.item_memo, parent, false))

        override fun onBindViewHolder(holder: MemoHolder, position: Int) {
            val memo = memos[position]
            holder.text.text = memo.text
            holder.due.visibility = if (memo.due == null) View.GONE else View.VISIBLE
            if (memo.due != null) {
                holder.due.text = getString(R.string.due_chip, DueFormat.withRemaining(this@MainActivity, memo.due))
                val now = System.currentTimeMillis()
                holder.due.setTextColor(
                    DueFormat.urgencyColor(memo.due, now)
                        ?: getColor(if (memo.due < now) R.color.text_secondary else R.color.accent),
                )
            }
            holder.time.text = getString(R.string.created_at, timeFormat.format(Date(memo.time)))
            holder.card.setOnClickListener {
                memos.getOrNull(holder.bindingAdapterPosition)?.let { editDue(it) }
            }
            holder.delete.setOnClickListener {
                memos.getOrNull(holder.bindingAdapterPosition)?.let { confirmDelete(it) }
            }
        }
    }
}
