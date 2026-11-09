package dev.stickerbridge.app.convert

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebpHeaderTest {

    private fun header(chunk: String, flags: Int = 0, extra: String = ""): ByteArray {
        val b = ByteArray(64)
        "RIFF".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        "WEBP".forEachIndexed { i, c -> b[8 + i] = c.code.toByte() }
        chunk.forEachIndexed { i, c -> b[12 + i] = c.code.toByte() }
        b[20] = flags.toByte()
        extra.forEachIndexed { i, c -> b[30 + i] = c.code.toByte() }
        return b
    }

    @Test
    fun vp8xAnimationFlagIsAnimated() = assertTrue(WebpHeader.isAnimated(header("VP8X", flags = 0x02)))

    @Test
    fun animChunkIsAnimated() = assertTrue(WebpHeader.isAnimated(header("VP8X", flags = 0x10, extra = "ANIM")))

    @Test
    fun vp8xWithAlphaOnlyIsStatic() = assertFalse(WebpHeader.isAnimated(header("VP8X", flags = 0x10)))

    @Test
    fun simpleLossyAndLosslessAreStatic() {
        assertFalse(WebpHeader.isAnimated(header("VP8 ")))
        assertFalse(WebpHeader.isAnimated(header("VP8L")))
    }

    @Test
    fun nonWebpAndShortDataAreStatic() {
        assertFalse(WebpHeader.isAnimated("GIF89a....".toByteArray()))
        assertFalse(WebpHeader.isAnimated(ByteArray(8)))
    }
}
