package dev.stickerbridge.app.convert

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Animated WEBP sticker -> MP4 (H.264) for Instagram DMs (E8), with platform MediaCodec +
 * MediaMuxer only (no new dependencies).
 *
 * - Frames come from the existing libwebp decoder ([WebpAnimDecoder]), composited per frame.
 * - Square canvas (longest side <= 480 px, even), sticker scaled to fit and centered on a
 *   WHITE background (video has no alpha), converted to YUV (BT.601, limited range).
 * - Timing and looping come from [VideoPlanning]: exact source times, <= 30 fps, >= 3 s, <= 10 s.
 * - Input goes through the codec's YUV input image, so every frame gets its exact
 *   presentationTimeUs (no EGL needed).
 * - No audio by default; [Options.silentAudio] adds a silent AAC track (in case Instagram
 *   rejects video without audio).
 */
object StickerVideoEncoder {

    data class Options(val silentAudio: Boolean = false, val bitrate: Int = VideoPlanning.BITRATE)

    /** Sizes and timings only (for the SB-Video debug log). */
    class Result(
        val bytes: Long,
        val side: Int,
        val sourceFrames: Int,
        val videoFrames: Int,
        val loops: Int,
        val durationMs: Long,
        val millis: Long,
    )

    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val TIMEOUT_US = 10_000L

    /** Returns null if the WEBP cannot be decoded. Throws on codec errors (caller handles). */
    fun encode(webp: ByteArray, out: File, options: Options = Options()): Result? {
        val started = System.nanoTime()
        WebpAnimDecoder.open(webp)?.use { dec ->
            if (dec.width <= 0 || dec.height <= 0 || dec.frameCount <= 0) return null
            val plan = VideoPlanning.plan(dec.durationsMs)
            if (plan.frames.isEmpty()) return null
            val side = VideoPlanning.canvasSide(dec.width, dec.height)

            val tmp = File(out.parentFile, out.name + ".tmp")
            tmp.delete()
            val audio = if (options.silentAudio) SilentAac.encode(plan.durationUs) else null
            val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val codec = MediaCodec.createEncoderByType(MIME)
            try {
                val format = MediaFormat.createVideoFormat(MIME, side, side).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setInteger(MediaFormat.KEY_BIT_RATE, options.bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, VideoPlanning.MAX_FPS)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()

                val writer = MuxWriter(muxer, audio)
                val renderer = FrameRenderer(dec, side)
                for (frame in plan.frames) {
                    val pixels = renderer.render(frame.sourceIndex)
                    var index: Int
                    while (true) {
                        index = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) break
                        drain(codec, writer, endOfStream = false)
                    }
                    val image = codec.getInputImage(index) ?: throw IllegalStateException("no input image")
                    Yuv.write(pixels, side, image)
                    codec.queueInputBuffer(index, 0, side * side * 3 / 2, frame.ptsUs, 0)
                    drain(codec, writer, endOfStream = false)
                }
                var eosIndex: Int
                while (true) {
                    eosIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (eosIndex >= 0) break
                    drain(codec, writer, endOfStream = false)
                }
                codec.queueInputBuffer(eosIndex, 0, 0, plan.durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(codec, writer, endOfStream = true)
                writer.finish()
            } finally {
                runCatching { codec.stop() }
                codec.release()
                runCatching { muxer.release() }
            }
            if (!tmp.renameTo(out)) {
                out.delete()
                tmp.renameTo(out)
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            return Result(out.length(), side, dec.frameCount, plan.frames.size, plan.loops, plan.durationUs / 1_000, ms)
        }
        return null
    }

    private fun drain(codec: MediaCodec, writer: MuxWriter, endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> writer.start(codec.outputFormat)
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) writer.writeVideo(buffer, info)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Adds the video (and optional audio) track once the encoder's format is known. */
    private class MuxWriter(private val muxer: MediaMuxer, private val audio: SilentAac.Encoded?) {
        private var videoTrack = -1
        private var started = false

        fun start(videoFormat: MediaFormat) {
            check(!started) { "format changed twice" }
            videoTrack = muxer.addTrack(videoFormat)
            val audioTrack = audio?.let { muxer.addTrack(it.format) } ?: -1
            muxer.start()
            started = true
            audio?.samples?.forEach { s -> muxer.writeSampleData(audioTrack, ByteBuffer.wrap(s.data), s.info) }
        }

        fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            check(started) { "muxer not started" }
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            muxer.writeSampleData(videoTrack, buffer, info)
        }

        fun finish() {
            if (started) muxer.stop()
        }
    }

    /**
     * Turns source frames into white-background ARGB canvases. Frames are decoded in order;
     * a smaller source index than the previous one starts a new loop (decoder reset); the same
     * index again (closing sample) reuses the last canvas.
     */
    private class FrameRenderer(private val dec: WebpAnimDecoder, private val side: Int) {
        private val decoded = IntArray(dec.width * dec.height)
        private var cursor = 0 // index of the next source frame the decoder will produce
        private var lastIndex = -1
        private var last = IntArray(side * side)
        private val fit = GifPlanning.scaledSize(dec.width, dec.height, side)

        fun render(sourceIndex: Int): IntArray {
            if (sourceIndex == lastIndex) return last
            if (sourceIndex < cursor) {
                dec.reset()
                cursor = 0
            }
            while (cursor < sourceIndex) {
                dec.skip()
                cursor++
            }
            check(dec.next(decoded)) { "decoder ended early" }
            cursor++
            val (w, h) = fit
            val scaled = ArgbScaler.scale(decoded, dec.width, dec.height, w, h)
            val canvas = IntArray(side * side) { 0xFFFFFFFF.toInt() }
            val ox = (side - w) / 2
            val oy = (side - h) / 2
            for (y in 0 until h) {
                for (x in 0 until w) canvas[(oy + y) * side + ox + x] = onWhite(scaled[y * w + x])
            }
            last = canvas
            lastIndex = sourceIndex
            return canvas
        }

        private fun onWhite(c: Int): Int {
            val a = c ushr 24
            if (a == 255) return c
            val inv = 255 - a
            val r = (((c shr 16) and 0xFF) * a + 255 * inv) / 255
            val g = (((c shr 8) and 0xFF) * a + 255 * inv) / 255
            val b = ((c and 0xFF) * a + 255 * inv) / 255
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** ARGB -> YUV 4:2:0 (BT.601 limited range) into the codec's input image, any plane layout. */
    private object Yuv {
        fun write(argb: IntArray, side: Int, image: android.media.Image) {
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yBuf = yPlane.buffer
            val yRow = yPlane.rowStride
            val yPix = yPlane.pixelStride
            for (y in 0 until side) {
                val row = y * side
                for (x in 0 until side) {
                    val c = argb[row + x]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    yBuf.put(y * yRow + x * yPix, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                }
            }
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer
            val uRow = uPlane.rowStride
            val uPix = uPlane.pixelStride
            val vRow = vPlane.rowStride
            val vPix = vPlane.pixelStride
            for (y in 0 until side / 2) {
                for (x in 0 until side / 2) {
                    // Average of the 2x2 block.
                    var r = 0; var g = 0; var b = 0
                    for (dy in 0..1) for (dx in 0..1) {
                        val c = argb[(2 * y + dy) * side + 2 * x + dx]
                        r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                    }
                    r /= 4; g /= 4; b /= 4
                    uBuf.put(y * uRow + x * uPix, (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                    vBuf.put(y * vRow + x * vPix, (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
                }
            }
        }
    }

    /** Silent AAC-LC mono track, encoded up front (only used when [Options.silentAudio] is on). */
    private object SilentAac {
        class Sample(val data: ByteArray, val info: MediaCodec.BufferInfo)
        class Encoded(val format: MediaFormat, val samples: List<Sample>)

        private const val SAMPLE_RATE = 44_100
        private const val CHUNK_SAMPLES = 1024

        fun encode(durationUs: Long): Encoded {
            val mime = MediaFormat.MIMETYPE_AUDIO_AAC
            val codec = MediaCodec.createEncoderByType(mime)
            val format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val samples = ArrayList<Sample>()
            var outFormat: MediaFormat? = null
            val totalSamples = durationUs * SAMPLE_RATE / 1_000_000
            var fed = 0L
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            try {
                while (true) {
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val pts = fed * 1_000_000 / SAMPLE_RATE
                            if (fed >= totalSamples) {
                                codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val n = minOf(CHUNK_SAMPLES.toLong(), totalSamples - fed).toInt()
                                val buf = codec.getInputBuffer(index)!!
                                buf.clear()
                                buf.put(ByteArray(n * 2)) // 16-bit PCM silence
                                codec.queueInputBuffer(index, 0, n * 2, pts, 0)
                                fed += n
                            }
                        }
                    }
                    val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outFormat = codec.outputFormat
                    } else if (out >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buf = codec.getOutputBuffer(out)!!
                            val data = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.get(data)
                            val copy = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                            samples += Sample(data, copy)
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return Encoded(outFormat ?: throw IllegalStateException("no AAC format"), samples)
        }
    }
}
