package com.lockmemo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateCheckerTest {
    @Test
    fun parsesUpdateJsonAndComparesVersionCodes() {
        val release = UpdateChecker.parse(
            """{"versionCode": 40, "versionName": "1.0.40", "apk": "https://example.com/memo-1.0.40.apk", "notes": "새 기능"}""",
        )
        assertEquals(40, release.versionCode)
        assertEquals("1.0.40", release.versionName)
        assertEquals("새 기능", release.notes)
        assertTrue(UpdateChecker.isNewer(release, currentVersionCode = 39))
        assertFalse(UpdateChecker.isNewer(release, currentVersionCode = 40))
    }
}
