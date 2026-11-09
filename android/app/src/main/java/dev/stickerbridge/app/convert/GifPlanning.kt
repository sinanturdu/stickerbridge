package dev.stickerbridge.app.convert

import dev.stickerbridge.app.ime.FieldKind
import kotlin.math.roundToInt

/** One output GIF frame: which source frame to use and how long to show it. */
data class PlannedFrame(val sourceIndex: Int, val delayCs: Int)

/** One encoding attempt: longest side and frame cap. */
data class GifStep(val maxSide: Int, val maxFrames: Int)

/**
 * GIF quality profile for a target. [steps] are tried in order: the first always, the next
 * only if the output is larger than [targetBytes] (at most one retry). [cacheTag] is part
 * of the GIF cache key, so GIFs made with different profiles never mix.
 */
data class GifProfile(val name: String, val steps: List<GifStep>, val targetBytes: Int, val cacheTag: String)

object GifProfiles {
    /**
     * Instagram Stories (and other apps that accept GIFs): smooth motion first.
     * 480 px, up to 60 frames (dropped evenly only above that), <= 4 MB, one retry at 360 px.
     */
    val STORY = GifProfile(
        name = "story",
        steps = listOf(GifStep(480, 60), GifStep(360, 60)),
        targetBytes = 4 * 1024 * 1024,
        cacheTag = "story480",
    )

    /**
     * Profile for a field, or null when the sticker must be sent as a still image
     * (Instagram DMs drop keyboard GIFs, E7; unidentified Instagram fields play safe).
     * Other apps get a GIF only if their field advertises image/gif (checked by the caller).
     */
    fun forField(kind: FieldKind): GifProfile? = when (kind) {
        FieldKind.STORY, FieldKind.OTHER_APP -> STORY
        FieldKind.DM, FieldKind.UNKNOWN -> null
    }
}

/** Pure planning helpers for the WEBP -> GIF conversion (unit-tested). */
object GifPlanning {

    /**
     * Chooses at most [maxFrames] source frames (all of them if there are not more), evenly
     * spaced, and gives each kept frame the total duration of the source frames it replaces.
     * Delays: see [delaysCs].
     */
    fun planFrames(durationsMs: IntArray, maxFrames: Int): List<PlannedFrame> {
        val n = durationsMs.size
        if (n == 0) return emptyList()
        val kept: IntArray = if (n <= maxFrames) {
            IntArray(n) { it }
        } else {
            IntArray(maxFrames) { i -> (i.toLong() * n / maxFrames).toInt() }
        }
        // Duration of each kept frame in ms = sum of the source frames it stands for.
        val keptMs = LongArray(kept.size) { i ->
            val end = if (i + 1 < kept.size) kept[i + 1] else n
            var sum = 0L
            for (j in kept[i] until end) sum += durationsMs[j].coerceAtLeast(0)
            sum
        }
        val cs = delaysCs(keptMs)
        return kept.mapIndexed { i, src -> PlannedFrame(src, cs[i]) }
    }

    /**
     * Converts frame durations (ms) to GIF delays (centiseconds) without drift: each delay is
     * the rounded cumulative end time minus what was already emitted, so rounding remainders
     * (and any time added by the minimum) carry over to the next frames and the total length
     * stays exact whenever possible. Every delay is at least [GifEncoder.MIN_DELAY_CS] (2 cs);
     * 0/1 cs is raised to 2 cs, never to the 10 cs browsers use for "too small" delays.
     * With no dropped frames this keeps the source's own per-frame durations.
     */
    fun delaysCs(durationsMs: LongArray): IntArray {
        val out = IntArray(durationsMs.size)
        var endMs = 0L
        var emittedCs = 0L
        for (i in durationsMs.indices) {
            endMs += durationsMs[i].coerceAtLeast(0)
            val targetCs = (endMs / 10.0).roundToInt().toLong()
            val cs = (targetCs - emittedCs).coerceAtLeast(GifEncoder.MIN_DELAY_CS.toLong())
            out[i] = cs.coerceAtMost(65_535).toInt()
            emittedCs += out[i]
        }
        return out
    }

    /** Output size for a canvas so the longest side is at most [maxSide] (never upscales). */
    fun scaledSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= maxSide) return width to height
        val scale = maxSide.toDouble() / longest
        return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
    }

    /**
     * Size fallback: after attempt [stepIndex] of [steps] produced [bytes], returns the index
     * of the next attempt, or null to accept the current result (it fits, or no attempt is left).
     */
    fun nextStepOrNull(stepIndex: Int, bytes: Int, limit: Int, steps: List<GifStep>): Int? {
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
