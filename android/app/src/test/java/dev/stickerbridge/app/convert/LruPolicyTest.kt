package dev.stickerbridge.app.convert

import dev.stickerbridge.app.convert.LruPolicy.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LruPolicyTest {
    private val mb = 1024L * 1024

    @Test
    fun underLimitEvictsNothing() {
        assertTrue(LruPolicy.evict(listOf(Entry("a", 40 * mb, 1), Entry("b", 50 * mb, 2)), 100 * mb).isEmpty())
    }

    @Test
    fun evictsLeastRecentlyUsedUntilUnderLimit() {
        val entries = listOf(Entry("old", 30 * mb, 1), Entry("mid", 40 * mb, 2), Entry("new", 50 * mb, 3))
        assertEquals(listOf("old"), LruPolicy.evict(entries, 100 * mb))
        assertEquals(listOf("old", "mid"), LruPolicy.evict(entries, 60 * mb))
    }

    @Test
    fun justWrittenFileIsNeverEvicted() {
        val entries = listOf(Entry("justWritten", 80 * mb, 1), Entry("other", 40 * mb, 2))
        assertEquals(listOf("other"), LruPolicy.evict(entries, 100 * mb, keep = "justWritten"))
    }
}
