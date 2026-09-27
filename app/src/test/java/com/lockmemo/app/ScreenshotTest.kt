package com.lockmemo.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 화면/아이콘 모양 확인용 미리보기 이미지를 build/ 에 만든다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    private fun seed() {
        val context = RuntimeEnvironment.getApplication()
        MemoStore.add(context, "우유 사기")
        MemoStore.add(context, "치과 예약", System.currentTimeMillis() + 86_400_000L)
        MemoStore.add(context, "팀 회의 자료 챙기기", System.currentTimeMillis() + 3_600_000L)
    }

    private fun capture(name: String, openSettings: Boolean = false, width: Int = 1080, height: Int = 2340) {
        seed()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        if (openSettings) activity.findViewById<View>(R.id.settings_button).performClick()
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun mainLight() = capture("screen-light")

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun mainNarrow() = capture("screen-narrow", width = 1080, height = 2340)

    @Test
    @Config(qualifiers = "+night")
    fun mainDarkWithSettings() = capture("screen-dark", openSettings = true)

    @Test
    @Config(qualifiers = "+night")
    fun notificationPreview() {
        val context = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val now = System.currentTimeMillis()
        MemoStore.add(context, "빨래 걷기")
        MemoStore.add(context, "치과 예약", now + 3 * DueFormat.DAY_MS)
        MemoStore.add(context, "택배 받기", now + 105 * 60_000L)
        MemoStore.add(context, "약 먹기", now - 20 * 60_000L)
        MemoStore.add(context, "팀 회의", now + 50 * 60_000L)
        MemoStore.add(context, "버스 타기", now + 5 * 60_000L)
        MemoNotifier.refresh(context)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        @Suppress("DEPRECATION")
        val remote = org.robolectric.Shadows.shadowOf(manager).allNotifications.single().bigContentView
        val frame = android.widget.FrameLayout(context).apply {
            setBackgroundColor(0xFF2A2830.toInt())
            setPadding(48, 36, 48, 36)
        }
        frame.addView(remote.apply(context, frame))
        val width = 1000
        frame.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        frame.layout(0, 0, width, frame.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, frame.measuredHeight, Bitmap.Config.ARGB_8888)
        frame.draw(Canvas(bitmap))
        File("build/notification-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun dueAlertScreen() {
        val intent = android.content.Intent(RuntimeEnvironment.getApplication(), DueAlertActivity::class.java)
            .putExtra(DueAlertActivity.EXTRA_TEXT, "버스 타기")
            .putExtra(DueAlarm.EXTRA_DUE, System.currentTimeMillis())
        val activity = Robolectric.buildActivity(DueAlertActivity::class.java, intent).setup().get()
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1080, 2340)
        val bitmap = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/due-alert.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun launcherIcon() {
        val context = RuntimeEnvironment.getApplication()
        val size = 432
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val drawable = context.getDrawable(R.mipmap.ic_launcher)!!
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        File("build/icon.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
