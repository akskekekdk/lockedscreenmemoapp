package com.lockmemo.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 앱 안 업데이트 확인.
 * GitHub 저장소 memo-releases 브랜치의 update.json 을 읽어 지금보다 높은 버전이면
 * APK 를 내려받아 설치 화면을 띄운다. (같은 키로 서명된 APK 라야 덮어 설치된다)
 *
 * update.json 예: {"versionCode": 40, "versionName": "1.0.40", "apk": "https://…/memo-1.0.40.apk", "notes": "…"}
 */
object UpdateChecker {
    data class Release(val versionCode: Int, val versionName: String, val apkUrl: String, val notes: String)

    private const val PREFS = "update"
    private const val KEY_LAST_CHECK = "last_check"
    private const val AUTO_INTERVAL_MS = 6 * 60 * 60_000L

    /** 설치 허용 설정에 다녀오는 동안 기다리는 업데이트 */
    private var pending: Release? = null

    fun parse(json: String): Release {
        val obj = JSONObject(json)
        return Release(
            versionCode = obj.getInt("versionCode"),
            versionName = obj.getString("versionName"),
            apkUrl = obj.getString("apk"),
            notes = obj.optString("notes"),
        )
    }

    fun isNewer(release: Release, currentVersionCode: Int = BuildConfig.VERSION_CODE) =
        release.versionCode > currentVersionCode

    private fun fetch(): Release {
        // raw.githubusercontent 는 몇 분간 캐시하므로 매번 다른 주소로 요청한다
        val connection = URL("${BuildConfig.UPDATE_URL}?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            return parse(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }

    /**
     * [manual] 이면 결과를 항상 알려주고, 아니면(앱 켤 때 자동) 6시간에 한 번만 조용히 확인해서
     * 새 버전이 있을 때만 묻는다.
     */
    fun check(activity: Activity, manual: Boolean) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong(KEY_LAST_CHECK, 0) < AUTO_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        if (manual) Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show()

        Thread {
            val result = runCatching { fetch() }
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                val release = result.getOrNull()
                when {
                    release != null && isNewer(release) -> offer(activity, release)
                    !manual -> Unit
                    release != null -> Toast.makeText(activity, R.string.update_latest, Toast.LENGTH_SHORT).show()
                    else -> Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun offer(activity: Activity, release: Release) {
        val message = activity.getString(R.string.update_message, BuildConfig.VERSION_NAME, release.versionName) +
            if (release.notes.isBlank()) "" else "\n\n${release.notes}"
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_now) { _, _ -> start(activity, release) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun start(activity: Activity, release: Release) {
        // 이 앱이 APK 를 설치할 수 있도록 한 번 허용받아야 한다
        if (!activity.packageManager.canRequestPackageInstalls()) {
            pending = release
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_message)
                .setPositiveButton(R.string.update_permission_go) { _, _ ->
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
                    )
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> pending = null }
                .show()
            return
        }
        download(activity, release)
    }

    /** 설치 허용 설정에서 돌아왔을 때 이어서 진행한다. MainActivity.onResume 에서 부른다. */
    fun resumePending(activity: Activity) {
        val release = pending ?: return
        if (!activity.packageManager.canRequestPackageInstalls()) return
        pending = null
        download(activity, release)
    }

    private fun download(activity: Activity, release: Release) {
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            val pad = (24 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_downloading, release.versionName))
            .setView(progress)
            .setCancelable(false)
            .show()

        Thread {
            val file = File(activity.cacheDir, "updates/memo-${release.versionName}.apk")
            val result = runCatching {
                file.parentFile?.mkdirs()
                val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                try {
                    if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
                    val total = connection.contentLengthLong
                    connection.inputStream.use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                done += n
                                if (total > 0) activity.runOnUiThread {
                                    progress.isIndeterminate = false
                                    progress.progress = (done * 100 / total).toInt()
                                }
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }
            activity.runOnUiThread {
                dialog.dismiss()
                if (result.isSuccess) install(activity, file) else {
                    file.delete()
                    Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
