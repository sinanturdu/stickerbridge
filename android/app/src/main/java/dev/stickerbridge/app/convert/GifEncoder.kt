package dev.stickerbridge.app.convert

import java.io.OutputStream

/**
 * Minimal GIF89a encoder (no dependencies).
 *
 * - Every frame is a full-canvas image with its own local color table:
 *   up to 255 colors from median-cut quantization + 1 transparent index.
 * - Pixels with alpha < 50 % become transparent.
 * - Disposal method 2 (restore to background) and a NETSCAPE2.0 loop block.
 * - LZW compression with variable code size, clear code when the table is full.
 */
class GifEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    /** 0 = loop forever. */
    private val loopCount: Int = 0,
) {
    private var started = false
    private var finished = false

    /** Adds one frame of [argb] (width * height, non-premultiplied ARGB). [delayCs] in 1/100 s. */
    fun addFrame(argb: IntArray, delayCs: Int) {
        require(argb.size >= width * height) { "frame too small" }
        check(!finished) { "already finished" }
        if (!started) writeHeader()

        val quantized = ColorQuantizer.quantize(argb, width * height)
        val palette = quantized.palette // up to 255 colors
        val transparentIndex = TRANSPARENT_INDEX

        // Graphic Control Extension
        out.write(0x21); out.write(0xF9); out.write(4)
        // packed: reserved(3)=0, disposal(3)=2, user input(1)=0, transparency flag(1)=1
        out.write((2 shl 2) or 1)
        writeShort(delayCs.coerceIn(MIN_DELAY_CS, 65535))
        out.write(transparentIndex)
        out.write(0)

        // Image Descriptor with local color table of 256 entries (size field 7 -> 2^(7+1))
        out.write(0x2C)
        writeShort(0); writeShort(0)
        writeShort(width); writeShort(height)
        out.write(0x80 or 7)

        // Local color table: palette, padded to 256 entries; the transparent entry is black.
        for (i in 0 until 256) {
            val c = if (i < palette.size) palette[i] else 0
            out.write((c shr 16) and 0xFF)
            out.write((c shr 8) and 0xFF)
            out.write(c and 0xFF)
        }

        Lzw.encode(quantized.indices, MIN_CODE_SIZE, out)
    }

    /** Writes the trailer. */
    fun finish() {
        if (finished) return
        if (!started) writeHeader()
        out.write(0x3B)
        out.flush()
        finished = true
    }

    private fun writeHeader() {
        started = true
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        // Logical Screen Descriptor, no global color table
        writeShort(width); writeShort(height)
        out.write(0x70) // color resolution 8 bits, no GCT
        out.write(0)    // background color index
        out.write(0)    // pixel aspect ratio
        // NETSCAPE2.0 application extension (looping)
        out.write(0x21); out.write(0xFF); out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3); out.write(1)
        writeShort(loopCount.coerceIn(0, 65535))
        out.write(0)
    }

    private fun writeShort(v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    companion object {
        const val TRANSPARENT_INDEX = 255
        const val MIN_CODE_SIZE = 8
        const val MIN_DELAY_CS = 2
        const val ALPHA_THRESHOLD = 128 // alpha < 128 (50 %) -> transparent
    }
}

/** Palette (RGB ints, at most 255) + one index per pixel (255 = transparent). */
class QuantizedFrame(val palette: IntArray, val indices: ByteArray)

/**
 * Median-cut quantizer on a 15-bit (5:5:5) color histogram of opaque pixels.
 * Palette colors are the count-weighted means of each box in full 8-bit precision.
 */
object ColorQuantizer {
    private const val MAX_COLORS = 255

    fun quantize(argb: IntArray, pixelCount: Int): QuantizedFrame {
        val counts = IntArray(32768)
        val sumR = LongArray(32768)
        val sumG = LongArray(32768)
        val sumB = LongArray(32768)
        for (i in 0 until pixelCount) {
            val c = argb[i]
            if ((c ushr 24) < GifEncoder.ALPHA_THRESHOLD) continue
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val key = key15(c)
            counts[key]++
            sumR[key] += r.toLong(); sumG[key] += g.toLong(); sumB[key] += b.toLong()
        }
        val keys = (0 until 32768).filter { counts[it] > 0 }.toIntArray()
        val palette: IntArray
        val keyToIndex = IntArray(32768) { -1 }

        if (keys.size <= MAX_COLORS) {
            palette = IntArray(keys.size) { i -> meanColor(intArrayOf(keys[i]), counts, sumR, sumG, sumB) }
            keys.forEachIndexed { i, k -> keyToIndex[k] = i }
        } else {
            val boxes = medianCut(keys, counts)
            palette = IntArray(boxes.size) { i -> meanColor(boxes[i], counts, sumR, sumG, sumB) }
        }

        val indices = ByteArray(pixelCount)
        for (i in 0 until pixelCount) {
            val c = argb[i]
            if ((c ushr 24) < GifEncoder.ALPHA_THRESHOLD) {
                indices[i] = GifEncoder.TRANSPARENT_INDEX.toByte()
                continue
            }
            val key = key15(c)
            var idx = keyToIndex[key]
            if (idx < 0) {
                idx = nearest(palette, key)
                keyToIndex[key] = idx
            }
            indices[i] = idx.toByte()
        }
        return QuantizedFrame(palette, indices)
    }

    /** 5:5:5 histogram key of an ARGB color. */
    private fun key15(c: Int): Int {
        val r = ((c shr 16) and 0xFF) shr 3
        val g = ((c shr 8) and 0xFF) shr 3
        val b = (c and 0xFF) shr 3
        return (r shl 10) or (g shl 5) or b
    }

    private fun meanColor(keys: IntArray, counts: IntArray, sr: LongArray, sg: LongArray, sb: LongArray): Int {
        var n = 0L; var r = 0L; var g = 0L; var b = 0L
        for (k in keys) { n += counts[k]; r += sr[k]; g += sg[k]; b += sb[k] }
        if (n == 0L) return 0
        return (((r / n).toInt()) shl 16) or (((g / n).toInt()) shl 8) or (b / n).toInt()
    }

    private fun nearest(palette: IntArray, key: Int): Int {
        val r = ((key shr 10) and 31) * 8 + 4
        val g = ((key shr 5) and 31) * 8 + 4
        val b = (key and 31) * 8 + 4
        var best = 0
        var bestD = Int.MAX_VALUE
        for (i in palette.indices) {
            val p = palette[i]
            val dr = ((p shr 16) and 0xFF) - r
            val dg = ((p shr 8) and 0xFF) - g
            val db = (p and 0xFF) - b
            val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun channel(key: Int, axis: Int) = when (axis) {
        0 -> (key shr 10) and 31
        1 -> (key shr 5) and 31
        else -> key and 31
    }

    /** Splits the histogram keys into at most 255 boxes. */
    private fun medianCut(keys: IntArray, counts: IntArray): List<IntArray> {
        val boxes = ArrayList<IntArray>()
        boxes += keys
        while (boxes.size < MAX_COLORS) {
            // Pick the splittable box with the largest (range * population).
            var bestBox = -1
            var bestScore = -1L
            var bestAxis = 0
            for ((bi, box) in boxes.withIndex()) {
                if (box.size < 2) continue
                var pop = 0L
                for (k in box) pop += counts[k]
                for (axis in 0..2) {
                    var lo = 31; var hi = 0
                    for (k in box) { val v = channel(k, axis); if (v < lo) lo = v; if (v > hi) hi = v }
                    val score = (hi - lo).toLong() * pop
                    if (hi > lo && score > bestScore) { bestScore = score; bestBox = bi; bestAxis = axis }
                }
            }
            if (bestBox < 0) break
            val box = boxes[bestBox]
            val axis = bestAxis
            val sorted = box.sortedBy { channel(it, axis) }.toIntArray()
            var total = 0L
            for (k in sorted) total += counts[k]
            var acc = 0L
            var cut = 1
            for (i in sorted.indices) {
                acc += counts[sorted[i]]
                if (acc * 2 >= total) { cut = (i + 1).coerceIn(1, sorted.size - 1); break }
            }
            boxes[bestBox] = sorted.copyOfRange(0, cut)
            boxes += sorted.copyOfRange(cut, sorted.size)
        }
        return boxes
    }
}

/** GIF-flavored LZW (LSB-first bit packing, 255-byte sub-blocks). */
object Lzw {
    private const val MAX_CODES = 4096

    /**
     * Follows the structure of the classic GIFENCOD/compress encoder: the code size
     * is checked after every emitted code, which keeps it in step with decoders
     * (they grow their code size one code later than the encoder adds entries).
     */
    fun encode(indices: ByteArray, minCodeSize: Int, out: OutputStream) {
        out.write(minCodeSize)
        val state = State(BlockBitWriter(out), minCodeSize)
        state.emit(state.clearCode)
        if (indices.isEmpty()) {
            state.emit(state.eoiCode)
            state.writer.finish()
            return
        }
        val table = HashMap<Int, Int>(MAX_CODES * 2)
        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val k = indices[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            val existing = table[key]
            if (existing != null) {
                prefix = existing
                continue
            }
            state.emit(prefix)
            prefix = k
            if (state.freeEnt < MAX_CODES) {
                table[key] = state.freeEnt++
            } else {
                table.clear()
                state.freeEnt = state.clearCode + 2
                state.clearPending = true
                state.emit(state.clearCode)
            }
        }
        state.emit(prefix)
        state.emit(state.eoiCode)
        state.writer.finish()
    }

    private class State(val writer: BlockBitWriter, private val minCodeSize: Int) {
        val clearCode = 1 shl minCodeSize
        val eoiCode = clearCode + 1
        var freeEnt = clearCode + 2
        var clearPending = false
        private var nBits = minCodeSize + 1
        private var maxCode = (1 shl nBits) - 1

        fun emit(code: Int) {
            writer.write(code, nBits)
            if (clearPending) {
                nBits = minCodeSize + 1
                maxCode = (1 shl nBits) - 1
                clearPending = false
            } else if (freeEnt > maxCode && nBits < 12) {
                nBits++
                maxCode = if (nBits == 12) MAX_CODES else (1 shl nBits) - 1
            }
        }
    }

    private class BlockBitWriter(private val out: OutputStream) {
        private val block = ByteArray(255)
        private var blockLen = 0
        private var bitBuf = 0L
        private var bitCount = 0

        fun write(code: Int, size: Int) {
            bitBuf = bitBuf or (code.toLong() shl bitCount)
            bitCount += size
            while (bitCount >= 8) {
                put((bitBuf and 0xFF).toInt())
                bitBuf = bitBuf ushr 8
                bitCount -= 8
            }
        }

        fun finish() {
            if (bitCount > 0) put((bitBuf and 0xFF).toInt())
            bitBuf = 0; bitCount = 0
            flushBlock()
            out.write(0) // block terminator
        }

        private fun put(b: Int) {
            block[blockLen++] = b.toByte()
            if (blockLen == 255) flushBlock()
        }

        private fun flushBlock() {
            if (blockLen == 0) return
            out.write(blockLen)
            out.write(block, 0, blockLen)
            blockLen = 0
        }
    }
}
