package dev.stickerbridge.app.convert

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Converts an animated WEBP sticker into a GIF that Instagram animates (E1).
 *
 * - Decodes with libwebp (one composited frame at a time).
 * - Attempts [GifPlanning.STEPS] in order (320 px / 30 frames, 240 px / 30 frames,
 *   240 px / 20 frames) until the GIF is <= [GifPlanning.TARGET_BYTES]; frames are
 *   dropped evenly with merged delays.
 */
object AnimatedStickerConverter {

    /** Sizes and timings only (for the SB-Gif debug log). */
    class Result(
        val bytes: Int,
        val side: Int,
        val framesKept: Int,
        val sourceFrames: Int,
        val attempts: Int,
        val millis: Long,
    ) {
        val framesDropped: Int get() = sourceFrames - framesKept
    }

    /** Returns null if the WEBP cannot be decoded (caller then sends the WEBP). */
    fun convert(webp: ByteArray, out: File, steps: List<GifStep> = GifPlanning.STEPS, limit: Int = GifPlanning.TARGET_BYTES): Result? {
        val started = System.nanoTime()
        WebpAnimDecoder.open(webp)?.use { dec ->
            if (dec.width <= 0 || dec.height <= 0 || dec.frameCount <= 0) return null
            var index = 0
            while (true) {
                val step = steps[index]
                val plan = GifPlanning.planFrames(dec.durationsMs, step.maxFrames)
                val side = minOf(step.maxSide, maxOf(dec.width, dec.height))
                val bytes = encode(dec, plan, side)
                val next = GifPlanning.nextStepOrNull(index, bytes.size, limit, steps)
                if (next == null) {
                    writeAtomically(out, bytes)
                    val ms = (System.nanoTime() - started) / 1_000_000
                    return Result(bytes.size, side, plan.size, dec.frameCount, index + 1, ms)
                }
                index = next
                dec.reset()
            }
        }
        return null
    }

    /** One fixed size, no size target (debug "GIF size test"). */
    fun convertFixed(webp: ByteArray, out: File, side: Int, maxFrames: Int = GifPlanning.MAX_FRAMES): Result? =
        convert(webp, out, listOf(GifStep(side, maxFrames)), limit = Int.MAX_VALUE)

    private fun writeAtomically(out: File, bytes: ByteArray) {
        val tmp = File(out.parentFile, out.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(out)) {
            out.delete()
            tmp.renameTo(out)
        }
    }

    private fun encode(dec: WebpAnimDecoder, plan: List<PlannedFrame>, side: Int): ByteArray {
        val (w, h) = GifPlanning.scaledSize(dec.width, dec.height, side)
        val buffer = ByteArrayOutputStream(128 * 1024)
        val gif = GifEncoder(buffer, w, h, loopCount = 0)
        val frame = IntArray(dec.width * dec.height)
        var planIdx = 0
        var srcIdx = 0
        while (planIdx < plan.size && dec.next(frame)) {
            if (plan[planIdx].sourceIndex == srcIdx) {
                val scaled = ArgbScaler.scale(frame, dec.width, dec.height, w, h)
                gif.addFrame(scaled, plan[planIdx].delayCs)
                planIdx++
            }
            srcIdx++
        }
        gif.finish()
        return buffer.toByteArray()
    }
}
