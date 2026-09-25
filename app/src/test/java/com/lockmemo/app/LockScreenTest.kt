package com.lockmemo.app

import android.app.Application
import android.app.NotificationManager
import android.graphics.Bitmap
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LockScreenTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        MemoStore.add(context, "우유 사기")
        MemoStore.add(context, "치과 예약", System.currentTimeMillis() + 86_400_000L)
        MemoStore.add(context, "팀 회의 자료 챙기기", System.currentTimeMillis() + 3_600_000L)
    }

    @Test
    fun oneNotificationWithOneLinePerMemoInCustomOrder() {
        // 저장 순서(새 메모가 맨 앞): 팀 회의, 치과, 우유 → 맨 아래 우유를 맨 위로
        MemoStore.move(context, 2, 0)
        MemoNotifier.refresh(context)
        val posted = shadowOf(manager).allNotifications
        assertEquals(1, posted.size)
        val notification = posted.single()
        @Suppress("DEPRECATION")
        val collapsed = notification.contentView.apply(context, FrameLayout(context))
        val lines = (collapsed.findViewById<LinearLayout>(R.id.lines)).let { box ->
            (0 until box.childCount).map { (box.getChildAt(it) as TextView).text.toString() }
        }
        lines.forEach { println("알림 줄: $it") }
        assertEquals(3, lines.size)
        assertEquals("우유 사기", lines[0])
    }

    @Test
    fun wallpaperModeLeavesOnlyTheInputNotification() {
        MemoStore.setWallpaperMode(context, true)
        MemoNotifier.refresh(context)
        val posted = shadowOf(manager).allNotifications
        assertEquals(1, posted.size)
        assertNotEquals(0, posted.single().actions.size)
    }

    @Test
    fun renderWallpaperPreview() {
        val now = System.currentTimeMillis()
        val lines = MemoStore.all(context).map { DueFormat.line(context, it, now) } +
            "아주 긴 메모는 화면 끝에서 말줄임표로 잘려서 항상 한 줄로만 보여야 합니다 정말 길게 길게"
        val bitmap = LockWallpaper.render(context, lines, 1080, 2340)
        val out = File("build/wallpaper-preview.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("미리보기: ${out.absolutePath}")
    }
}
