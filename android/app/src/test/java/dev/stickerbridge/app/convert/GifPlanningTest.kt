package dev.stickerbridge.app.convert

import dev.stickerbridge.app.ime.FieldKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GifPlanningTest {

    // ---- story profile ----

    @Test
    fun storyProfileValues() {
        val p = GifProfiles.STORY
        assertEquals(listOf(GifStep(480, 60), GifStep(360, 60)), p.steps)
        assertEquals(4 * 1024 * 1024, p.targetBytes)
    }

    @Test
    fun profileSelectionByFieldClass() {
        assertEquals(GifProfiles.STORY, GifProfiles.forField(FieldKind.STORY))
        assertEquals(GifProfiles.STORY, GifProfiles.forField(FieldKind.OTHER_APP)) // other apps that take image/gif
        assertNull(GifProfiles.forField(FieldKind.DM))      // still image (E7)
        assertNull(GifProfiles.forField(FieldKind.UNKNOWN)) // still image, play safe
    }

    @Test
    fun oneRetryAt360pxOnlyWhenTooLarge() {
        val p = GifProfiles.STORY
        assertNull(GifPlanning.nextStepOrNull(0, p.targetBytes, p.targetBytes, p.steps))        // fits: keep 480 px
        assertEquals(1, GifPlanning.nextStepOrNull(0, p.targetBytes + 1, p.targetBytes, p.steps)) // -> 360 px
        assertNull(GifPlanning.nextStepOrNull(1, p.targetBytes * 2, p.targetBytes, p.steps))    // no second retry
    }

    // ---- frame selection ----

    @Test
    fun upTo60FramesAreAllKeptWithTheirOwnDurations() {
        val durations = intArrayOf(100, 50, 30, 70, 40)
        val plan = GifPlanning.planFrames(durations, maxFrames = 60)
        assertEquals(listOf(0, 1, 2, 3, 4), plan.map { it.sourceIndex })
        assertEquals(listOf(10, 5, 3, 7, 4), plan.map { it.delayCs }) // source timing preserved
    }

    @Test
    fun eightySevenFramesKeep60AndExactTotalDuration() {
        // The device case that looked choppy: 87 frames of 40 ms (3.48 s).
        val plan = GifPlanning.planFrames(IntArray(87) { 40 }, maxFrames = 60)
        assertEquals(60, plan.size)
        assertEquals(348, plan.sumOf { it.delayCs })
        assertTrue(plan.zipWithNext().all { (a, b) -> b.sourceIndex > a.sourceIndex })
        assertTrue(plan.all { it.delayCs in 4..8 }) // each kept frame covers 1 or 2 source frames
    }

    @Test
    fun droppedFramesKeepTotalDurationWithIrregularTiming() {
        val durations = IntArray(130) { i -> if (i % 3 == 0) 20 else 45 } // irregular source
        val totalMs = durations.sum()
        val plan = GifPlanning.planFrames(durations, maxFrames = 60)
        assertEquals(60, plan.size)
        assertEquals(Math.round(totalMs / 10.0).toInt(), plan.sumOf { it.delayCs })
    }

    @Test
    fun emptyAnimationGivesEmptyPlan() {
        assertTrue(GifPlanning.planFrames(IntArray(0), maxFrames = 60).isEmpty())
    }

    // ---- delay rounding ----

    @Test
    fun roundingRemainderIsCarriedToTheNextFrame() {
        // 33 + 33 + 34 ms = 100 ms: naive per-frame rounding gives 3+3+3 = 9 cs; carry gives 10.
        assertArrayEquals(intArrayOf(3, 4, 3), GifPlanning.delaysCs(longArrayOf(33, 33, 34)))
        // 25 ms frames = 2.5 cs each: carry alternates 3/2 and keeps 100 ms exact (no drift).
        assertArrayEquals(intArrayOf(3, 2, 3, 2), GifPlanning.delaysCs(LongArray(4) { 25 }))
    }

    @Test
    fun minimumIsTwoCentisecondsAndTheExcessIsTakenFromLaterFrames() {
        // 5 + 5 + 100 ms = 110 ms: the first two are raised to 2 cs, the last gives the time back.
        assertArrayEquals(intArrayOf(2, 2, 7), GifPlanning.delaysCs(longArrayOf(5, 5, 100)))
        assertEquals(11, GifPlanning.delaysCs(longArrayOf(5, 5, 100)).sum())
    }

    @Test
    fun zeroAndOneCentisecondBecomeTwoNeverTen() {
        val cs = GifPlanning.delaysCs(longArrayOf(0, 10, 0, 300))
        assertTrue(cs.all { it >= 2 })
        assertTrue(cs.take(3).all { it == 2 }) // not the browser default of 10 cs
        assertEquals(31, cs.sum())              // 300 ms total kept: 2 + 2 + 2 + 25
    }

    @Test
    fun scaledSizeLimitsLongestSideAndNeverUpscales() {
        assertEquals(480 to 480, GifPlanning.scaledSize(512, 512, 480))
        assertEquals(360 to 180, GifPlanning.scaledSize(1024, 512, 360))
        assertEquals(300 to 200, GifPlanning.scaledSize(300, 200, 480))
    }

    @Test
    fun scalerAveragesWithAlphaWeighting() {
        // 2x1 -> 1x1: opaque red + fully transparent blue must stay red, alpha halves.
        val out = ArgbScaler.scale(intArrayOf(0xFFFF0000.toInt(), 0x000000FF), 2, 1, 1, 1)
        assertEquals(0x7F, out[0] ushr 24)
        assertEquals(0xFF0000, out[0] and 0xFFFFFF)
    }
}
