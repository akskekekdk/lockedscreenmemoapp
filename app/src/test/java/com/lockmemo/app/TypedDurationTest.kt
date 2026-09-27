package com.lockmemo.app

import android.app.AlertDialog
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TypedDurationTest {
    private fun fields(dialog: AlertDialog): List<EditText> {
        val found = mutableListOf<EditText>()
        fun walk(v: android.view.View) {
            if (v is EditText) found += v
            if (v is android.view.ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) }
        }
        walk(dialog.window!!.decorView)
        return found
    }

    @Test
    fun typedDaysHoursMinutesSecondsBecomeTheDueTime() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        var picked: Long? = null
        val before = System.currentTimeMillis()
        DueFormat.pickTyped(activity) { picked = it }
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() // 다이얼로그가 다 뜰 때까지
        val (d, h, m, s) = fields(dialog)
        d.setText("1"); h.setText("2"); m.setText("3"); s.setText("4")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val expected = ((1 * 86_400L) + (2 * 3_600L) + (3 * 60L) + 4L) * 1000
        val offset = picked!! - before
        assert(offset in expected..expected + 2_000) { "offset=$offset" }
    }

    @Test
    fun emptyInputKeepsTheDialogOpen() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        var picked: Long? = null
        DueFormat.pickTyped(activity) { picked = it }
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() // 다이얼로그가 다 뜰 때까지
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertNull(picked)
        assertEquals(true, dialog.isShowing)
    }
}
