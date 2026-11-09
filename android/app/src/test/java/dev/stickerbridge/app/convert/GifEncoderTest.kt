package dev.stickerbridge.app.convert

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class GifEncoderTest {

    // ---- helpers: a tiny independent GIF parser + LZW decoder for verification ----

    private class ParsedFrame(val delayCs: Int, val disposal: Int, val transparentIndex: Int?, val palette: IntArray, val indices: IntArray)
    private class ParsedGif(val width: Int, val height: Int, val loop: Int?, val frames: List<ParsedFrame>, val endsWithTrailer: Boolean)

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun parse(b: ByteArray): ParsedGif {
        assertEquals("GIF89a", String(b, 0, 6, Charsets.US_ASCII))
        val w = u16(b, 6); val h = u16(b, 8)
        var p = 13 // no global color table
        var loop: Int? = null
        var delay = 0; var disposal = 0; var transparent: Int? = null
        val frames = ArrayList<ParsedFrame>()
        while (p < b.size) {
            when (b[p].toInt() and 0xFF) {
                0x21 -> {
                    val label = b[p + 1].toInt() and 0xFF
                    if (label == 0xF9) {
                        val packed = b[p + 3].toInt() and 0xFF
                        disposal = (packed shr 2) and 7
                        delay = u16(b, p + 4)
                        transparent = if (packed and 1 == 1) b[p + 6].toInt() and 0xFF else null
                        p += 8
                    } else {
                        if (label == 0xFF && String(b, p + 3, 11, Charsets.US_ASCII) == "NETSCAPE2.0") loop = u16(b, p + 16)
                        p += 2
                        while (b[p].toInt() != 0) p += (b[p].toInt() and 0xFF) + 1
                        p++
                    }
                }
                0x2C -> {
                    val fw = u16(b, p + 5); val fh = u16(b, p + 7)
                    val packed = b[p + 9].toInt() and 0xFF
                    p += 10
                    val palette = IntArray(0)
                    val pal = if (packed and 0x80 != 0) {
                        val n = 1 shl ((packed and 7) + 1)
                        IntArray(n) { i -> ((b[p + 3 * i].toInt() and 0xFF) shl 16) or ((b[p + 3 * i + 1].toInt() and 0xFF) shl 8) or (b[p + 3 * i + 2].toInt() and 0xFF) }
                            .also { p += 3 * n }
                    } else palette
                    val minCode = b[p].toInt() and 0xFF
                    p++
                    val data = ByteArrayOutputStream()
                    while (b[p].toInt() != 0) {
                        val len = b[p].toInt() and 0xFF
                        data.write(b, p + 1, len)
                        p += len + 1
                    }
                    p++
                    frames += ParsedFrame(delay, disposal, transparent, pal, lzwDecode(data.toByteArray(), minCode, fw * fh))
                }
                0x3B -> return ParsedGif(w, h, loop, frames, endsWithTrailer = p == b.size - 1)
                else -> error("unexpected block 0x%02x at %d".format(b[p], p))
            }
        }
        return ParsedGif(w, h, loop, frames, endsWithTrailer = false)
    }

    /** Standard GIF LZW decoder (independent of the encoder). */
    private fun lzwDecode(data: ByteArray, minCodeSize: Int, pixelCount: Int): IntArray {
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        var codeSize = minCodeSize + 1
        val prefix = IntArray(4096); val suffix = IntArray(4096); val first = IntArray(4096)
        for (i in 0 until clear) { prefix[i] = -1; suffix[i] = i; first[i] = i }
        var avail = eoi + 1
        var old = -1
        val out = ArrayList<Int>(pixelCount)
        var bitPos = 0
        val stack = IntArray(4097)
        fun readCode(): Int {
            var code = 0
            for (i in 0 until codeSize) {
                val byte = data[(bitPos + i) / 8].toInt() and 0xFF
                if ((byte shr ((bitPos + i) % 8)) and 1 == 1) code = code or (1 shl i)
            }
            bitPos += codeSize
            return code
        }
        while (bitPos + codeSize <= data.size * 8) {
            val code = readCode()
            if (code == clear) { codeSize = minCodeSize + 1; avail = eoi + 1; old = -1; continue }
            if (code == eoi) break
            var c = code
            var sp = 0
            if (old == -1) { out += suffix[c]; old = c; continue }
            if (c >= avail) { stack[sp++] = first[old]; c = old } // KwKwK case
            while (c >= clear) { stack[sp++] = suffix[c]; c = prefix[c] }
            stack[sp++] = suffix[c]
            val firstChar = suffix[c]
            while (sp > 0) out += stack[--sp]
            if (avail < 4096) {
                prefix[avail] = old; suffix[avail] = firstChar; first[avail] = first[old]
                avail++
                if (avail >= (1 shl codeSize) && codeSize < 12) codeSize++
            }
            old = code
        }
        return out.toIntArray()
    }

    private fun encode(w: Int, h: Int, frames: List<IntArray>, delay: Int = 10): ByteArray {
        val bos = ByteArrayOutputStream()
        val enc = GifEncoder(bos, w, h)
        frames.forEach { enc.addFrame(it, delay) }
        enc.finish()
        return bos.toByteArray()
    }

    // ---- tests ----

    @Test
    fun headerTrailerAndLoopBlock() {
        val bytes = encode(4, 3, listOf(IntArray(12) { 0xFFFF0000.toInt() }))
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        assertEquals(0x3B, bytes.last().toInt() and 0xFF)
        val gif = parse(bytes)
        assertEquals(4, gif.width)
        assertEquals(3, gif.height)
        assertEquals(0, gif.loop) // loop forever
        assertTrue(gif.endsWithTrailer)
        assertEquals(1, gif.frames.size)
        assertEquals(2, gif.frames[0].disposal) // restore to background
    }

    @Test
    fun lzwRoundTripSmallImage() {
        val colors = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt())
        val w = 7; val h = 5
        val argb = IntArray(w * h) { colors[(it * 7 / 3) % 4] }
        val frame = parse(encode(w, h, listOf(argb))).frames.single()
        val decoded = IntArray(w * h) { frame.palette[frame.indices[it]] or (0xFF shl 24) }
        assertArrayEquals(argb, decoded)
    }

    @Test
    fun lzwRoundTripRawIndicesIncludingTableReset() {
        // > 4096 distinct sequences forces a clear code mid-stream.
        val rnd = Random(42)
        val indices = ByteArray(40_000) { rnd.nextInt(256).toByte() }
        val bos = ByteArrayOutputStream()
        Lzw.encode(indices, 8, bos)
        val raw = bos.toByteArray()
        val data = ByteArrayOutputStream()
        var p = 1
        while (raw[p].toInt() != 0) { val len = raw[p].toInt() and 0xFF; data.write(raw, p + 1, len); p += len + 1 }
        val decoded = lzwDecode(data.toByteArray(), 8, indices.size)
        assertArrayEquals(indices.map { it.toInt() and 0xFF }.toIntArray(), decoded)
    }

    @Test
    fun lzwRoundTripLongRuns() {
        val indices = ByteArray(10_000) { (if (it < 6000) 3 else it % 5).toByte() }
        val bos = ByteArrayOutputStream()
        Lzw.encode(indices, 8, bos)
        val raw = bos.toByteArray()
        val data = ByteArrayOutputStream()
        var p = 1
        while (raw[p].toInt() != 0) { val len = raw[p].toInt() and 0xFF; data.write(raw, p + 1, len); p += len + 1 }
        assertArrayEquals(indices.map { it.toInt() and 0xFF }.toIntArray(), lzwDecode(data.toByteArray(), 8, indices.size))
    }

    @Test
    fun transparencyUsesIndex255WithFiftyPercentThreshold() {
        val argb = intArrayOf(
            0x00000000,               // fully transparent
            0x7FFF0000,               // alpha 127 < 128 -> transparent
            0x80FF0000.toInt(),       // alpha 128 -> opaque red
            0xFF00FF00.toInt(),       // opaque green
        )
        val frame = parse(encode(2, 2, listOf(argb))).frames.single()
        assertEquals(255, frame.transparentIndex)
        assertEquals(255, frame.indices[0])
        assertEquals(255, frame.indices[1])
        assertTrue(frame.indices[2] < 255)
        assertEquals(0xFF0000, frame.palette[frame.indices[2]])
        assertEquals(0x00FF00, frame.palette[frame.indices[3]])
    }

    @Test
    fun manyColorsAreQuantizedToAtMost255PlusTransparent() {
        val w = 64; val h = 64
        val argb = IntArray(w * h) { i -> (0xFF shl 24) or ((i * 37) and 0xFF shl 16) or ((i * 11) and 0xFF shl 8) or (i and 0xFF) }
        val frame = parse(encode(w, h, listOf(argb))).frames.single()
        assertEquals(w * h, frame.indices.size)
        assertTrue(frame.indices.all { it in 0..254 })
    }

    @Test
    fun delaysArePreservedWithMinimumTwoCentiseconds() {
        val bos = ByteArrayOutputStream()
        val enc = GifEncoder(bos, 1, 1)
        enc.addFrame(intArrayOf(0xFF000000.toInt()), 7)
        enc.addFrame(intArrayOf(0xFFFFFFFF.toInt()), 1)
        enc.finish()
        val gif = parse(bos.toByteArray())
        assertEquals(listOf(7, 2), gif.frames.map { it.delayCs })
    }
}
