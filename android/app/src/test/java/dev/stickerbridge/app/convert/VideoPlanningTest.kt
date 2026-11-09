package dev.stickerbridge.app.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlanningTest {

    // ---- loop count / duration ----

    @Test
    fun loopsUntilAtLeastThreeSeconds() {
        assertEquals(3, VideoPlanning.loopCount(1_000))   // 3.0 s
        assertEquals(8, VideoPlanning.loopCount(400))     // 3.2 s
        assertEquals(2, VideoPlanning.loopCount(1_600))   // 3.2 s
        assertEquals(1, VideoPlanning.loopCount(3_480))   // already long enough
    }

    @Test
    fun neverLongerThanTenSecondsWhenLooping() {
        assertEquals(1, VideoPlanning.loopCount(6_000))   // 2 loops would be 12 s
        assertEquals(2, VideoPlanning.loopCount(2_900))   // 5.8 s
    }

    @Test
    fun planDurationIsLoopsTimesAnimationAndCappedAtTenSeconds() {
        val short = VideoPlanning.plan(IntArray(10) { 100 })          // 1 s animation
        assertEquals(3, short.loops)
        assertEquals(3_000_000, short.durationUs)

        val long = VideoPlanning.plan(IntArray(120) { 100 })          // 12 s animation
        assertEquals(1, long.loops)
        assertEquals(10_000_000, long.durationUs)                     // cut at 10 s
        assertTrue(long.frames.all { it.ptsUs < 10_000_000 })
    }

    // ---- canvas ----

    @Test
    fun canvasIsSquareEvenAndAtMost480() {
        assertEquals(480, VideoPlanning.canvasSide(512, 512))
        assertEquals(480, VideoPlanning.canvasSide(512, 300))
        assertEquals(300, VideoPlanning.canvasSide(301, 200)) // odd -> even, never upscaled
        assertEquals(2, VideoPlanning.canvasSide(1, 1))
    }

    // ---- timestamps ----

    @Test
    fun timestampsAreStrictlyIncreasingAndFollowSourceTiming() {
        val plan = VideoPlanning.plan(intArrayOf(100, 50, 200, 40))   // 390 ms, irregular
        val pts = plan.frames.map { it.ptsUs }
        assertTrue(pts.zipWithNext().all { (a, b) -> b > a })
        // First loop starts exactly at the source start times.
        assertEquals(listOf(0L, 100_000L, 150_000L, 350_000L), pts.take(4))
        // Second loop starts exactly one animation length later.
        assertEquals(390_000L, pts[4])
        assertTrue(pts.last() < plan.durationUs)
    }

    @Test
    fun frameRateNeverExceeds30Fps() {
        val plan = VideoPlanning.plan(IntArray(100) { 10 })            // 100 fps source, 1 s
        val deltas = plan.frames.map { it.ptsUs }.zipWithNext { a, b -> b - a }
        assertTrue(deltas.all { it >= 33_333 || it == deltas.last() })
        assertTrue(plan.frames.size <= 3 * 30 + 2)                     // ~3 s at <= 30 fps
    }

    @Test
    fun zeroDurationFramesDoNotBreakTheTimeline() {
        val plan = VideoPlanning.plan(intArrayOf(0, 0, 100))
        assertTrue(plan.durationUs > 0)
        assertTrue(plan.frames.map { it.ptsUs }.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun emptyAnimationGivesEmptyPlan() {
        assertTrue(VideoPlanning.plan(IntArray(0)).frames.isEmpty())
    }
}
