package com.lockmemo.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 메모 목록 확인/추가/삭제와 잠금화면 표시 설정. */
class MainActivity : Activity() {
    private lateinit var input: EditText
    private lateinit var emptyView: TextView
    private val adapter = MemoAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        input = findViewById(R.id.memo_input)
        emptyView = findViewById(R.id.empty_view)
        val list = findViewById<ListView>(R.id.memo_list)
        list.adapter = adapter
        list.emptyView = emptyView
        list.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(adapter.getItem(position))
            true
        }

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

        val toggle = findViewById<Switch>(R.id.lockscreen_switch)
        toggle.isChecked = MemoStore.isLockScreenEnabled(this)
        toggle.setOnCheckedChangeListener { _, checked ->
            MemoStore.setLockScreenEnabled(this, checked)
            if (checked) requestNotificationPermissionIfNeeded()
            MemoNotifier.refresh(this)
            updateProblem()
        }

        findViewById<View>(R.id.open_settings).setOnClickListener { openNotificationSettings() }

        if (MemoStore.isLockScreenEnabled(this)) requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
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
    private fun updateProblem() {
        val reason = if (MemoStore.isLockScreenEnabled(this)) MemoNotifier.blockingReason(this) else null
        findViewById<View>(R.id.problem_box).visibility = if (reason == null) View.GONE else View.VISIBLE
        if (reason != null) findViewById<TextView>(R.id.problem_text).setText(reason)
    }

    private fun openNotificationSettings() {
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

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    private fun addMemo() {
        if (MemoStore.add(this, input.text.toString())) {
            input.text.clear()
            reload()
            MemoNotifier.refresh(this)
        }
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

    private fun reload() {
        adapter.memos = MemoStore.all(this)
        adapter.notifyDataSetChanged()
    }

    private inner class MemoAdapter : BaseAdapter() {
        var memos: List<Memo> = emptyList()
        private val timeFormat = SimpleDateFormat("yyyy.M.d HH:mm", Locale.getDefault())

        override fun getCount() = memos.size
        override fun getItem(position: Int) = memos[position]
        override fun getItemId(position: Int) = memos[position].time

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_memo, parent, false)
            val memo = memos[position]
            view.findViewById<TextView>(R.id.memo_text).text = memo.text
            view.findViewById<TextView>(R.id.memo_time).text = timeFormat.format(Date(memo.time))
            return view
        }
    }
}
