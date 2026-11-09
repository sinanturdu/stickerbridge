package dev.stickerbridge.app.convert

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Converts an animated WEBP sticker into a GIF that Instagram animates (E1).
 *
 * Speed (target < 1.5 s for typical stickers):
 * - Frames to keep are chosen first; decoding stays sequential (WEBP frames are composited),
 *   but dropped frames are decoded natively without being copied or scaled ([WebpAnimDecoder.skip]).
 * - Each kept frame is downscaled once; ONE global palette is built from all kept frames
 *   (fast 5:5:5 median cut) and shared by every frame (one color table, smaller file).
 * - Size, frame cap and byte target come from a [GifProfile] (Stories: 480 px, up to 60
 *   frames, <= 4 MB); the first attempt always runs, at most one retry with the next one.
 * - Frame delays keep the source timing (rounding carried over, see [GifPlanning.delaysCs]).
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
        val decodeMillis: Long,
        val encodeMillis: Long,
    ) {
        val framesDropped: Int get() = sourceFrames - framesKept
    }

    private class Encoded(val bytes: ByteArray, val side: Int, val frames: Int, val decodeMs: Long, val encodeMs: Long)

    /** Returns null if the WEBP cannot be decoded (caller then sends a still image). */
    fun convert(webp: ByteArray, out: File, profile: GifProfile = GifProfiles.STORY): Result? {
        val started = System.nanoTime()
        val steps = profile.steps
        val limit = profile.targetBytes
        WebpAnimDecoder.open(webp)?.use { dec ->
            if (dec.width <= 0 || dec.height <= 0 || dec.frameCount <= 0) return null
            var encoded = encode(dec, steps[0])
            var attempts = 1
            val retry = GifPlanning.nextStepOrNull(0, encoded.bytes.size, limit, steps)
            if (retry != null) { // at most one retry
                dec.reset()
                encoded = encode(dec, steps[retry])
                attempts = 2
            }
            writeAtomically(out, encoded.bytes)
            val ms = (System.nanoTime() - started) / 1_000_000
            return Result(encoded.bytes.size, encoded.side, encoded.frames, dec.frameCount, attempts, ms, encoded.decodeMs, encoded.encodeMs)
        }
        return null
    }

    private fun writeAtomically(out: File, bytes: ByteArray) {
        val tmp = File(out.parentFile, out.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(out)) {
            out.delete()
            tmp.renameTo(out)
        }
    }

    private fun encode(dec: WebpAnimDecoder, step: GifStep): Encoded {
        val t0 = System.nanoTime()
        val plan = GifPlanning.planFrames(dec.durationsMs, step.maxFrames)
        val side = minOf(step.maxSide, maxOf(dec.width, dec.height))
        val (w, h) = GifPlanning.scaledSize(dec.width, dec.height, side)
        val pixels = w * h

        // 1) Sequential decode; only kept frames are copied, scaled once and counted.
        val canvas = IntArray(dec.width * dec.height)
        val kept = ArrayList<IntArray>(plan.size)
        val histogram = Histogram()
        var planIdx = 0
        var src = 0
        while (planIdx < plan.size) {
            if (plan[planIdx].sourceIndex == src) {
                if (!dec.next(canvas)) break
                val scaled = ArgbScaler.scale(canvas, dec.width, dec.height, w, h)
                histogram.add(scaled, pixels)
                kept += scaled
                planIdx++
            } else if (!dec.skip()) {
                break
            }
            src++
        }
        val t1 = System.nanoTime()

        // 2) One global palette for all kept frames, then map + LZW per frame.
        val palette = Palette.from(histogram)
        val buffer = ByteArrayOutputStream(128 * 1024)
        val gif = GifEncoder(buffer, w, h, loopCount = 0, globalPalette = palette.colors)
        val indices = ByteArray(pixels)
        for (i in kept.indices) {
            palette.map(kept[i], pixels, indices)
            gif.addIndexedFrame(indices, plan[i].delayCs)
        }
        gif.finish()
        val t2 = System.nanoTime()
        return Encoded(buffer.toByteArray(), side, kept.size, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000)
    }
}
