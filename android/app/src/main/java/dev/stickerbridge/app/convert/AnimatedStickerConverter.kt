package dev.stickerbridge.app.convert

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Converts an animated WEBP sticker into a GIF that Instagram animates (E1).
 *
 * - Decodes with libwebp (one composited frame at a time).
 * - Longest side <= 512 px; at most 60 frames (evenly dropped, delays merged).
 * - Target < 2 MB; retries at 384 and 256 px if larger.
 */
object AnimatedStickerConverter {

    class Result(val bytes: Int, val side: Int, val frames: Int)

    /** Returns null if the WEBP cannot be decoded (caller then sends the WEBP). */
    fun convert(webp: ByteArray, out: File): Result? {
        WebpAnimDecoder.open(webp)?.use { dec ->
            if (dec.width <= 0 || dec.height <= 0 || dec.frameCount <= 0) return null
            val plan = GifPlanning.planFrames(dec.durationsMs)
            var side = minOf(GifPlanning.MAX_SIDE, maxOf(dec.width, dec.height))
            while (true) {
                val bytes = encode(dec, plan, side)
                val next = GifPlanning.nextSideOrNull(side, bytes.size)
                if (next == null) {
                    val tmp = File(out.parentFile, out.name + ".tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(out)) {
                        out.delete()
                        tmp.renameTo(out)
                    }
                    return Result(bytes.size, side, plan.size)
                }
                side = next
                dec.reset()
            }
        }
        return null
    }

    private fun encode(dec: WebpAnimDecoder, plan: List<PlannedFrame>, side: Int): ByteArray {
        val (w, h) = GifPlanning.scaledSize(dec.width, dec.height, side)
        val buffer = ByteArrayOutputStream(256 * 1024)
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
