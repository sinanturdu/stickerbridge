package dev.stickerbridge.app.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GifPlanningTest {

    @Test
    fun fewFramesAreKeptAsIs() {
        val plan = GifPlanning.planFrames(intArrayOf(100, 50, 30))
        assertEquals(listOf(0, 1, 2), plan.map { it.sourceIndex })
        assertEquals(listOf(10, 5, 3), plan.map { it.delayCs })
    }

    @Test
    fun manyFramesAreDroppedEvenlyAndKeepTotalDuration() {
        val durations = IntArray(120) { 40 } // 4.8 s
        val plan = GifPlanning.planFrames(durations, maxFrames = 60)
        assertEquals(60, plan.size)
        assertEquals((0 until 120 step 2).toList(), plan.map { it.sourceIndex })
        assertTrue(plan.all { it.delayCs == 8 })
        assertEquals(480, plan.sumOf { it.delayCs })
    }

    @Test
    fun unevenDropKeepsTotalWithoutDrift() {
        val durations = IntArray(97) { 33 } // 3.201 s
        val plan = GifPlanning.planFrames(durations, maxFrames = 60)
        assertEquals(60, plan.size)
        assertEquals(0, plan.first().sourceIndex)
        assertTrue(plan.zipWithNext().all { (a, b) -> b.sourceIndex > a.sourceIndex })
        assertEquals(320, plan.sumOf { it.delayCs }) // round(3201 ms / 10)
    }

    @Test
    fun tinyDelaysAreRaisedToTwoCentiseconds() {
        val plan = GifPlanning.planFrames(intArrayOf(0, 5, 10))
        assertTrue(plan.all { it.delayCs >= 2 })
    }

    @Test
    fun emptyAnimationGivesEmptyPlan() {
        assertTrue(GifPlanning.planFrames(IntArray(0)).isEmpty())
    }

    @Test
    fun scaledSizeLimitsLongestSideAndNeverUpscales() {
        assertEquals(512 to 512, GifPlanning.scaledSize(512, 512, 512))
        assertEquals(256 to 128, GifPlanning.scaledSize(1024, 512, 256))
        assertEquals(300 to 200, GifPlanning.scaledSize(300, 200, 512))
    }

    @Test
    fun sizeFallbackDecision() {
        val limit = 2 * 1024 * 1024
        assertNull(GifPlanning.nextSideOrNull(512, limit, limit))        // fits exactly
        assertEquals(384, GifPlanning.nextSideOrNull(512, limit + 1, limit))
        assertEquals(256, GifPlanning.nextSideOrNull(384, limit + 1, limit))
        assertNull(GifPlanning.nextSideOrNull(256, limit * 3, limit))    // nothing smaller: accept
        assertEquals(256, GifPlanning.nextSideOrNull(300, limit + 1, limit)) // small canvas
    }

    @Test
    fun scalerAveragesWithAlphaWeighting() {
        // 2x1 -> 1x1: opaque red + fully transparent blue must stay red, alpha halves.
        val out = ArgbScaler.scale(intArrayOf(0xFFFF0000.toInt(), 0x000000FF), 2, 1, 1, 1)
        assertEquals(0x7F, out[0] ushr 24)
        assertEquals(0xFF0000, out[0] and 0xFFFFFF)
    }
}
