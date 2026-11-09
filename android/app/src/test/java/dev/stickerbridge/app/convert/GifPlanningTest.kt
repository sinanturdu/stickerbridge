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
    fun limitsAreConservativeForInstagram() {
        assertEquals(320, GifPlanning.MAX_SIDE)
        assertEquals(30, GifPlanning.MAX_FRAMES)
        assertEquals(500 * 1024, GifPlanning.TARGET_BYTES)
        assertEquals(listOf(GifStep(320, 30), GifStep(240, 30), GifStep(240, 20)), GifPlanning.STEPS)
    }

    @Test
    fun sizeFallbackDecision() {
        val limit = GifPlanning.TARGET_BYTES
        assertNull(GifPlanning.nextStepOrNull(0, limit, limit))          // fits exactly: keep 320 px / 30
        assertEquals(1, GifPlanning.nextStepOrNull(0, limit + 1, limit)) // -> 240 px / 30 frames
        assertEquals(2, GifPlanning.nextStepOrNull(1, limit + 1, limit)) // -> 240 px / 20 frames
        assertNull(GifPlanning.nextStepOrNull(2, limit * 3, limit))      // nothing smaller: accept
    }

    @Test
    fun predictionPicksOneAttemptForTypicalStickers() {
        // Device case: 512 px, 87 frames -> nothing is estimated to fit -> smallest attempt directly
        // (before: 3 attempts).
        assertEquals(2, GifPlanning.chooseStep(512, 512, 87))
        // Short animation: 3 frames fit at 320 px.
        assertEquals(0, GifPlanning.chooseStep(512, 512, 3))
        // 10 frames: 320 px estimated too big, 240 px fits.
        assertEquals(1, GifPlanning.chooseStep(512, 512, 10))
    }

    @Test
    fun estimateMatchesDeviceMeasurement() {
        // 20 frames at 240 px measured 481-574 KB on device.
        val kb = GifPlanning.estimateBytes(20, 240, 240) / 1024
        assertTrue("estimate $kb KB", kb in 480..600)
    }

    @Test
    fun defaultPlanKeepsAtMost30Frames() {
        val plan = GifPlanning.planFrames(IntArray(75) { 40 })
        assertEquals(30, plan.size)
        assertEquals(300, plan.sumOf { it.delayCs }) // 3 s kept
    }

    @Test
    fun scalerAveragesWithAlphaWeighting() {
        // 2x1 -> 1x1: opaque red + fully transparent blue must stay red, alpha halves.
        val out = ArgbScaler.scale(intArrayOf(0xFFFF0000.toInt(), 0x000000FF), 2, 1, 1, 1)
        assertEquals(0x7F, out[0] ushr 24)
        assertEquals(0xFF0000, out[0] and 0xFFFFFF)
    }
}
