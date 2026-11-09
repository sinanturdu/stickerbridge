package dev.stickerbridge.app.convert

import java.io.Closeable

/**
 * Animated WEBP decoder backed by Google's libwebp (WebPAnimDecoder, vendored v1.5.0)
 * through a small JNI bridge (libstickerwebp.so).
 *
 * Frames come one at a time, composited on the full canvas, as non-premultiplied
 * ARGB ints, so only one frame is held in memory.
 */
class WebpAnimDecoder private constructor(private var handle: Long) : Closeable {

    val width: Int
    val height: Int
    val loopCount: Int
    val frameCount: Int

    /** Per-frame durations in ms, read without decoding. */
    val durationsMs: IntArray

    init {
        val info = nativeInfo(handle)
        width = info[0]
        height = info[1]
        loopCount = info[2]
        frameCount = info[3]
        durationsMs = nativeDurations(handle)
    }

    /** Decodes the next frame into [out] (width * height ARGB). Returns false when done. */
    fun next(out: IntArray): Boolean {
        check(handle != 0L) { "decoder closed" }
        return nativeNext(handle, out) >= 0
    }

    /** Decodes the next frame for compositing but does not copy it to Java. False when done. */
    fun skip(): Boolean {
        check(handle != 0L) { "decoder closed" }
        return nativeSkip(handle) >= 0
    }

    /** Rewinds to the first frame. */
    fun reset() {
        check(handle != 0L) { "decoder closed" }
        nativeReset(handle)
    }

    override fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    companion object {
        /** False if the native library could not be loaded (then callers send the WEBP as-is). */
        val available: Boolean = try {
            System.loadLibrary("stickerwebp")
            true
        } catch (e: Throwable) {
            false
        }

        /** Returns null if the data is not a decodable WEBP. */
        fun open(webp: ByteArray): WebpAnimDecoder? {
            if (!available) return null
            val h = nativeOpen(webp)
            return if (h == 0L) null else WebpAnimDecoder(h)
        }

        @JvmStatic private external fun nativeOpen(bytes: ByteArray): Long
        @JvmStatic private external fun nativeInfo(handle: Long): IntArray
        @JvmStatic private external fun nativeDurations(handle: Long): IntArray
        @JvmStatic private external fun nativeNext(handle: Long, argbOut: IntArray): Int
        @JvmStatic private external fun nativeSkip(handle: Long): Int
        @JvmStatic private external fun nativeReset(handle: Long)
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
