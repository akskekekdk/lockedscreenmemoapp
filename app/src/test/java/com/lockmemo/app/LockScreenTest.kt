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

    private fun notificationLines(): List<TextView> {
        @Suppress("DEPRECATION")
        val view = shadowOf(manager).allNotifications.single().bigContentView.apply(context, FrameLayout(context))
        val box = view.findViewById<LinearLayout>(R.id.lines)
        return (0 until box.childCount).map { box.getChildAt(it).findViewById(R.id.line) }
    }

    @Test
    fun closestToNowComesFirstAndUndatedFollowInManualOrder() {
        val now = System.currentTimeMillis()
        MemoStore.all(context).forEach { MemoStore.remove(context, it) }
        MemoStore.add(context, "빨래")                                  // 날짜 없음
        MemoStore.add(context, "사흘 뒤", now + 3 * DueFormat.DAY_MS)
        MemoStore.add(context, "10분 전 지남", now - 10 * 60_000L)
        MemoStore.add(context, "청소")                                  // 날짜 없음
        MemoStore.add(context, "1시간 뒤", now + 60 * 60_000L)
        // 날짜 없는 메모 순서를 직접 바꿈: 빨래 → 청소
        val undated = MemoStore.all(context).filter { it.due == null }
        MemoStore.reorderUndated(context, undated.reversed())

        MemoNotifier.refresh(context)
        val texts = notificationLines().map { it.text.toString().substringAfter(" · ") }
        texts.forEach { println("정렬: $it") }
        assertEquals(listOf("10분 전 지남", "1시간 뒤", "사흘 뒤", "빨래", "청소"), texts)
    }

    @Test
    fun colorGetsRedderAsTheTimeApproaches() {
        val now = System.currentTimeMillis()
        assertEquals(null, DueFormat.urgencyColor(now + 3 * 60 * 60_000L, now))
        val far = DueFormat.urgencyColor(now + 110 * 60_000L, now)!!
        val near = DueFormat.urgencyColor(now + 5 * 60_000L, now)!!
        val past = DueFormat.urgencyColor(now - 5 * 60_000L, now)!!
        // 가까울수록 초록 성분이 줄어 빨강에 가까워진다
        assert(android.graphics.Color.green(near) < android.graphics.Color.green(far))
        assertEquals(near, past)
        println("색: 1시간50분 전 #%08X → 5분 전 #%08X".format(far, near))

        MemoStore.all(context).forEach { MemoStore.remove(context, it) }
        MemoStore.add(context, "곧", now + 5 * 60_000L)
        MemoStore.add(context, "멀리", now + 5 * 60 * 60_000L)
        MemoNotifier.refresh(context)
        val lines = notificationLines()
        assertNotEquals(lines[1].currentTextColor, lines[0].currentTextColor)
    }

    @Test
    fun remainingTimeText() {
        val now = System.currentTimeMillis()
        assertEquals("2시간 30분 남음", DueFormat.remaining(context, now + 150 * 60_000L, now))
        assertEquals("1시간 남음", DueFormat.remaining(context, now + 60 * 60_000L, now))
        assertEquals("30분 남음", DueFormat.remaining(context, now + 29 * 60_000L + 40_000L, now))
        assertEquals(null, DueFormat.remaining(context, now - 1, now))
        val threeDays = DueFormat.remaining(context, now + 3 * DueFormat.DAY_MS, now)
        assertEquals("D-3", threeDays)
        println("표시 예: " + DueFormat.withRemaining(context, now + 150 * 60_000L, now))
    }

    @Test
    fun notificationShowsLiveCountdownWithinADayAndDDayBeyond() {
        MemoStore.all(context).forEach { MemoStore.remove(context, it) }
        MemoStore.add(context, "먼 일정", System.currentTimeMillis() + 3 * DueFormat.DAY_MS)
        MemoStore.add(context, "곧 할 일", System.currentTimeMillis() + 90 * 60_000L)
        MemoNotifier.refresh(context)
        @Suppress("DEPRECATION")
        val view = shadowOf(manager).allNotifications.single().bigContentView.apply(context, FrameLayout(context))
        val box = view.findViewById<LinearLayout>(R.id.lines)
        val soon = box.getChildAt(0)
        val far = box.getChildAt(1)
        assertEquals(android.view.View.VISIBLE, soon.findViewById<android.widget.Chronometer>(R.id.countdown).visibility)
        assertEquals(true, soon.findViewById<android.widget.Chronometer>(R.id.countdown).isCountDown)
        assertEquals("D-3", far.findViewById<TextView>(R.id.remain_static).text.toString())
        println("알림 줄: " + soon.findViewById<TextView>(R.id.line).text + " | " +
            soon.findViewById<android.widget.Chronometer>(R.id.countdown).text)
    }

    @Test
    fun exactAlarmIsSetForTheNextDueTimeAndFiresAFullScreenSilentAlert() {
        val now = System.currentTimeMillis()
        MemoStore.all(context).forEach { MemoStore.remove(context, it) }
        MemoStore.add(context, "나중", now + 5 * 60 * 60_000L)
        MemoStore.add(context, "곧", now + 10 * 60_000L)
        MemoNotifier.refresh(context)
        val alarms = shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        val next = alarms.scheduledAlarms.single { it.operation != null && it.operation.let { op -> shadowOf(op).savedIntent.action == DueAlarm.ACTION_DUE } }
        val due = MemoStore.all(context).first { it.text == "곧" }.due!!
        assertEquals(due, next.triggerAtTime)

        // 알람이 울린 것처럼: 전체 화면 알림이 무음으로 뜬다
        DueAlarm.alert(context, due)
        val alert = shadowOf(manager).getNotification(DueAlarm.ALERT_NOTIFICATION_ID)
        assertNotEquals(null, alert.fullScreenIntent)
        val channel = manager.getNotificationChannel(alert.channelId)
        assertEquals(null, channel.sound)
        assertEquals(false, channel.shouldVibrate())
        assertEquals("곧", alert.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        // 끄기 전까지 계속: 밀어서 못 지우는 알림 + 지워지면 다시 띄우는 신호
        assertEquals(true, alert.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(DueAlarm.ACTION_REPOST, shadowOf(alert.deleteIntent).savedIntent.action)

        // 밀어서 지운 것처럼 → 다시 뜬다
        manager.cancel(DueAlarm.ALERT_NOTIFICATION_ID)
        DueAlarm.repost(context)
        assertNotEquals(null, shadowOf(manager).getNotification(DueAlarm.ALERT_NOTIFICATION_ID))

        // 전체 화면: 뒤로가기로는 안 닫히고 끄기로만 닫힌다
        val screen = org.robolectric.Robolectric.buildActivity(
            DueAlertActivity::class.java,
            android.content.Intent(context, DueAlertActivity::class.java).putExtra(DueAlertActivity.EXTRA_TEXT, "곧"),
        ).setup().get()
        @Suppress("DEPRECATION")
        screen.onBackPressed()
        assertEquals(false, screen.isFinishing)
        screen.findViewById<android.view.View>(R.id.alert_dismiss).performClick()
        assertEquals(true, screen.isFinishing)
        assertEquals(false, DueAlarm.isActive(context))
        assertEquals(null, shadowOf(manager).getNotification(DueAlarm.ALERT_NOTIFICATION_ID))

        // 끈 뒤에는 다시 띄우지 않는다
        DueAlarm.repost(context)
        assertEquals(null, shadowOf(manager).getNotification(DueAlarm.ALERT_NOTIFICATION_ID))
    }

    @Test
    fun tappingTheNotificationOpensTheApp() {
        MemoNotifier.refresh(context)
        val tap = shadowOf(manager).allNotifications.single().contentIntent
        assertEquals(MainActivity::class.java.name, shadowOf(tap).savedIntent.component?.className)
    }

    @Test
    fun withOverlayPermissionTheFullScreenOpensDirectlyEvenWhilePhoneIsInUse() {
        val due = System.currentTimeMillis()
        MemoStore.add(context, "버스", due)
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        DueAlarm.alert(context, due)
        val started = shadowOf(context).nextStartedActivity
        assertEquals(DueAlertActivity::class.java.name, started.component?.className)
        assertEquals("버스", started.getStringExtra(DueAlertActivity.EXTRA_TEXT))
        DueAlarm.dismiss(context)
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
