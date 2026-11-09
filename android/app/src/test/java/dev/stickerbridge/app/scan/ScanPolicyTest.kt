package dev.stickerbridge.app.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPolicyTest {

    private val min = 60_000L
    private val hour = 60 * min
    private val now = 1_000_000_000_000L

    private fun plan(trigger: ScanTrigger = ScanTrigger.KEYBOARD_OPENED, completeAgo: Long?, fullAgo: Long?, stamps: Boolean = true) =
        ScanPolicy.plan(trigger, now, completeAgo?.let { now - it } ?: 0L, fullAgo?.let { now - it } ?: 0L, stamps)

    @Test
    fun refreshAndConnectAlwaysScanFully() {
        assertEquals(ScanPlan.FULL, plan(ScanTrigger.REFRESH_BUTTON, completeAgo = 1_000, fullAgo = 1_000))
        assertEquals(ScanPlan.FULL, plan(ScanTrigger.FOLDER_CONNECTED, completeAgo = 1_000, fullAgo = 1_000))
    }

    @Test
    fun keyboardOpenSkipsWhenLastCompleteScanIsUnderFiveMinutes() {
        assertEquals(ScanPlan.SKIP_RECENT, plan(completeAgo = 4 * min, fullAgo = 2 * hour))
        assertEquals(ScanPlan.SKIP_RECENT, plan(ScanTrigger.ONBOARDING, completeAgo = 0, fullAgo = 0))
    }

    @Test
    fun keyboardOpenQuickChecksAfterFiveMinutes() {
        assertEquals(ScanPlan.QUICK_CHECK, plan(completeAgo = 5 * min, fullAgo = 5 * min))
        assertEquals(ScanPlan.QUICK_CHECK, plan(completeAgo = 3 * hour, fullAgo = 23 * hour))
    }

    @Test
    fun fullScanWhenNeverScannedNoStampsOrListingOlderThan24h() {
        assertEquals(ScanPlan.FULL, plan(completeAgo = null, fullAgo = null))
        assertEquals(ScanPlan.FULL, plan(completeAgo = 10 * min, fullAgo = 10 * min, stamps = false))
        assertEquals(ScanPlan.FULL, plan(completeAgo = 10 * min, fullAgo = 25 * hour))
    }

    @Test
    fun clockMovedBackwardsNeverSkips() {
        // Saved times in the future: do not trust them.
        assertEquals(ScanPlan.FULL, plan(completeAgo = -10 * min, fullAgo = -10 * min))
    }

    @Test
    fun quickCheckMatchesOnlyIdenticalKnownStamps() {
        val saved = FolderStamp(lastModified = 123L, childCount = 11_900)
        assertTrue(ScanPolicy.unchanged(saved, FolderStamp(123L, 11_900)))
        assertFalse(ScanPolicy.unchanged(saved, FolderStamp(124L, 11_900))) // folder modified
        assertFalse(ScanPolicy.unchanged(saved, FolderStamp(123L, 11_901))) // file added
        assertFalse(ScanPolicy.unchanged(null, FolderStamp(123L, 11_900)))  // never stamped
        assertFalse(ScanPolicy.unchanged(saved, null))                      // query failed
        assertFalse(ScanPolicy.unchanged(FolderStamp(0, 5), FolderStamp(0, 5))) // unknown time
    }

    @Test
    fun keyboardOpenCheckComparesFolderTimeOnly() {
        // The fast check does not count children (-1); only the folder's own time decides.
        val saved = FolderStamp(lastModified = 123L, childCount = 11_900)
        assertTrue(ScanPolicy.unchanged(saved, FolderStamp(123L, -1)))
        assertFalse(ScanPolicy.unchanged(saved, FolderStamp(124L, -1)))
        assertFalse(ScanPolicy.unchanged(saved, FolderStamp(0L, -1)))
    }

    @Test
    fun stampRoundTripsThroughPreferencesFormat() {
        val s = FolderStamp(1_731_000_000_123L, 11_904)
        assertEquals(s, FolderStamp.decode(s.encode()))
        assertNull(FolderStamp.decode(null))
        assertNull(FolderStamp.decode("garbage"))
    }
}
