package dev.stickerbridge.app.convert

import kotlin.math.roundToInt

/** One output GIF frame: which source frame to use and how long to show it. */
data class PlannedFrame(val sourceIndex: Int, val delayCs: Int)

/** One encoding attempt: longest side and frame cap. */
data class GifStep(val maxSide: Int, val maxFrames: Int)

/** Pure planning helpers for the WEBP -> GIF conversion (unit-tested). */
object GifPlanning {
    /**
     * Instagram rejected our 512 px / up-to-60-frame / up-to-2 MB GIFs from the keyboard
     * ("Tap the sticker icon to find and send GIFs") but accepted the E1 probe GIF
     * (52 KB, 4 frames). Limits are therefore conservative and tunable with the
     * debug "GIF size test".
     */
    const val MAX_FRAMES = 30
    const val MAX_SIDE = 320
    const val TARGET_BYTES = 500 * 1024

    /** Attempts in order while the GIF is larger than [TARGET_BYTES]. */
    val STEPS = listOf(GifStep(320, 30), GifStep(240, 30), GifStep(240, 20))

    /**
     * Chooses at most [maxFrames] source frames, evenly spaced, and gives each kept frame
     * the total duration of the source frames it replaces, so the animation keeps its speed.
     * Delays are computed from rounded cumulative times (no drift) and are at least
     * [GifEncoder.MIN_DELAY_CS] centiseconds (browsers treat smaller delays as ~10 cs).
     */
    fun planFrames(durationsMs: IntArray, maxFrames: Int = MAX_FRAMES): List<PlannedFrame> {
        val n = durationsMs.size
        if (n == 0) return emptyList()
        val kept: IntArray = if (n <= maxFrames) {
            IntArray(n) { it }
        } else {
            IntArray(maxFrames) { i -> (i.toLong() * n / maxFrames).toInt() }
        }
        // Cumulative start time (ms) of every source frame, plus the total.
        val start = LongArray(n + 1)
        for (i in 0 until n) start[i + 1] = start[i] + durationsMs[i].coerceAtLeast(0)
        return kept.mapIndexed { i, src ->
            val end = if (i + 1 < kept.size) kept[i + 1] else n
            val fromCs = (start[src] / 10.0).roundToInt()
            val toCs = (start[end] / 10.0).roundToInt()
            PlannedFrame(src, (toCs - fromCs).coerceAtLeast(GifEncoder.MIN_DELAY_CS))
        }
    }

    /** Output size for a canvas so the longest side is at most [maxSide] (never upscales). */
    fun scaledSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= maxSide) return width to height
        val scale = maxSide.toDouble() / longest
        return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
    }

    /**
     * Rough GIF size for a sticker: about 0.5 byte per output pixel per frame after LZW
     * (measured on device: 20 frames at 240 px gave 481-574 KB), plus header and tables.
     */
    const val BYTES_PER_PIXEL_ESTIMATE = 0.5

    fun estimateBytes(frames: Int, width: Int, height: Int): Long =
        (frames.toLong() * width * height * BYTES_PER_PIXEL_ESTIMATE).toLong() + 1_024 + frames * 16L

    /**
     * Picks the first attempt whose estimated size fits [limit], so a typical sticker needs a
     * single encode; if none fits, the last (smallest) attempt. The caller retries at most
     * once, with the next attempt, if the real output is still too large.
     */
    fun chooseStep(
        sourceWidth: Int,
        sourceHeight: Int,
        sourceFrames: Int,
        limit: Int = TARGET_BYTES,
        steps: List<GifStep> = STEPS,
    ): Int {
        for ((i, step) in steps.withIndex()) {
            val (w, h) = scaledSize(sourceWidth, sourceHeight, step.maxSide)
            val frames = minOf(sourceFrames, step.maxFrames)
            if (estimateBytes(frames, w, h) <= limit) return i
        }
        return steps.lastIndex
    }

    /**
     * Size fallback: after attempt [stepIndex] of [steps] produced [bytes], returns the index
     * of the next attempt, or null to accept the current result (it fits, or no attempt is left).
     */
    fun nextStepOrNull(stepIndex: Int, bytes: Int, limit: Int = TARGET_BYTES, steps: List<GifStep> = STEPS): Int? {
        if (bytes <= limit) return null
        val next = stepIndex + 1
        return if (next < steps.size) next else null
    }
}

/** Area-average downscaling of non-premultiplied ARGB with alpha-weighted color. Pure Kotlin. */
object ArgbScaler {
    fun scale(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        if (sw == dw && sh == dh) return src.copyOf(sw * sh)
        val dst = IntArray(dw * dh)
        for (dy in 0 until dh) {
            val y0 = dy * sh / dh
            val y1 = maxOf(y0 + 1, (dy + 1) * sh / dh)
            for (dx in 0 until dw) {
                val x0 = dx * sw / dw
                val x1 = maxOf(x0 + 1, (dx + 1) * sw / dw)
                var a = 0L; var r = 0L; var g = 0L; var b = 0L; var n = 0L
                for (y in y0 until minOf(y1, sh)) {
                    val row = y * sw
                    for (x in x0 until minOf(x1, sw)) {
                        val c = src[row + x]
                        val ca = (c ushr 24).toLong()
                        a += ca
                        r += ((c shr 16) and 0xFF) * ca
                        g += ((c shr 8) and 0xFF) * ca
                        b += (c and 0xFF) * ca
                        n++
                    }
                }
                dst[dy * dw + dx] = if (a == 0L || n == 0L) 0 else {
                    val oa = (a / n).toInt()
                    val or = (r / a).toInt()
                    val og = (g / a).toInt()
                    val ob = (b / a).toInt()
                    (oa shl 24) or (or shl 16) or (og shl 8) or ob
                }
            }
        }
        return dst
    }
}
