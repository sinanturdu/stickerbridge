package dev.stickerbridge.app.convert

/** One video frame: which source frame to draw and when (microseconds). */
data class VideoFrame(val sourceIndex: Int, val ptsUs: Long)

/** Full timeline of the video: frames in presentation order and the total length. */
data class VideoPlan(val frames: List<VideoFrame>, val durationUs: Long, val loops: Int)

/**
 * Pure planning for animated sticker -> MP4 (unit-tested, no Android).
 *
 * - Square canvas, longest side at most [MAX_SIDE] px, even dimensions (H.264 needs them).
 * - The animation loops until the video is at least [MIN_DURATION_MS] long, but never longer
 *   than [MAX_DURATION_MS] (a single loop longer than that is cut at the limit).
 * - Exact source timing: each frame starts at its source start time; frames that would start
 *   less than 1/[MAX_FPS] s after the previous emitted frame are skipped (the previous one
 *   simply stays on screen longer), so the frame rate never exceeds [MAX_FPS].
 * - presentationTimeUs is strictly increasing. A final copy of the last frame closes the
 *   timeline so the last frame keeps its duration in players that use sample deltas.
 */
object VideoPlanning {
    const val MAX_SIDE = 480
    const val MIN_DURATION_MS = 3_000L
    const val MAX_DURATION_MS = 10_000L
    const val MAX_FPS = 30
    const val BITRATE = 1_500_000

    private const val MIN_FRAME_US = 1_000_000L / MAX_FPS // 33,333 us

    /** Square canvas side: longest source side capped at [maxSide], rounded down to even, at least 2. */
    fun canvasSide(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        val side = minOf(maxOf(width, height), maxSide)
        return maxOf(2, side - side % 2)
    }

    /** How many times the animation is played: enough for [MIN_DURATION_MS], at most [MAX_DURATION_MS]. */
    fun loopCount(animationMs: Long): Int {
        if (animationMs <= 0) return 1
        var loops = ((MIN_DURATION_MS + animationMs - 1) / animationMs).toInt().coerceAtLeast(1)
        while (loops > 1 && loops * animationMs > MAX_DURATION_MS) loops--
        return loops
    }

    fun plan(durationsMs: IntArray): VideoPlan {
        val n = durationsMs.size
        if (n == 0) return VideoPlan(emptyList(), 0, 0)
        // A frame with a 0 ms duration is treated as 10 ms (browsers/players do something similar).
        val dur = LongArray(n) { (durationsMs[it].coerceAtLeast(0).takeIf { d -> d > 0 } ?: 10).toLong() }
        val animationMs = dur.sum()
        val loops = loopCount(animationMs)
        val totalUs = minOf(animationMs * loops, MAX_DURATION_MS) * 1_000

        val frames = ArrayList<VideoFrame>()
        var lastPts = Long.MIN_VALUE
        var startUs = 0L
        loop@ for (l in 0 until loops) {
            for (i in 0 until n) {
                if (startUs >= totalUs) break@loop
                if (lastPts == Long.MIN_VALUE || startUs - lastPts >= MIN_FRAME_US) {
                    frames += VideoFrame(i, startUs)
                    lastPts = startUs
                }
                startUs += dur[i] * 1_000
            }
        }
        // Closing sample: repeat the last frame shortly before the end so it keeps its duration.
        val last = frames.last()
        val closing = maxOf(last.ptsUs + 1, totalUs - MIN_FRAME_US)
        if (closing < totalUs && closing > last.ptsUs) frames += VideoFrame(last.sourceIndex, closing)
        return VideoPlan(frames, totalUs, loops)
    }
}
