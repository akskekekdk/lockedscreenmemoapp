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

    private fun capture(name: String, openSettings: Boolean = false) {
        seed()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        if (openSettings) activity.findViewById<View>(R.id.settings_button).performClick()
        val root = activity.window.decorView
        val width = 1080
        val height = 2340
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
    @Config(qualifiers = "+night")
    fun mainDarkWithSettings() = capture("screen-dark", openSettings = true)

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
