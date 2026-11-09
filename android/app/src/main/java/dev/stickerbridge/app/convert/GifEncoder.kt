package dev.stickerbridge.app.convert

import java.io.OutputStream

/**
 * Minimal GIF89a encoder (no dependencies).
 *
 * - Every frame is a full-canvas image with up to 255 colors from median-cut
 *   quantization + 1 transparent index (255); alpha < 50 % becomes transparent.
 * - Per-frame mode ([addFrame]): the first frame's palette is the Global Color Table
 *   (like Pillow and gifsicle); later frames carry a Local Color Table.
 * - Global mode ([globalPalette] + [addIndexedFrame]): one shared Global Color Table and
 *   no local tables (smaller files, used by the converter).
 * - One Graphic Control Extension per frame: disposal 2 (restore to background),
 *   transparency flag, delay with a 2 cs minimum.
 * - NETSCAPE2.0 loop block, LZW with min code size 8, trailer 0x3B.
 *
 * Verified on the JVM with javax.imageio's GIF reader (GifImageIoTest).
 */
class GifEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    /** 0 = loop forever. */
    private val loopCount: Int = 0,
    /** If set, every frame uses this Global Color Table (call [addIndexedFrame]). */
    private val globalPalette: IntArray? = null,
) {
    private var started = false
    private var finished = false

    /** Adds one frame of [argb] (width * height, non-premultiplied ARGB). [delayCs] in 1/100 s. */
    fun addFrame(argb: IntArray, delayCs: Int) {
        require(argb.size >= width * height) { "frame too small" }
        require(globalPalette == null) { "use addIndexedFrame with a global palette" }
        check(!finished) { "already finished" }

        val quantized = ColorQuantizer.quantize(argb, width * height)
        val firstFrame = !started
        if (firstFrame) writeHeader(globalPalette = quantized.palette)
        writeFrame(quantized.indices, delayCs, if (firstFrame) null else quantized.palette)
    }

    /** Global mode: adds a frame already mapped to [globalPalette] indices (255 = transparent). */
    fun addIndexedFrame(indices: ByteArray, delayCs: Int) {
        val palette = requireNotNull(globalPalette) { "no global palette" }
        require(indices.size >= width * height) { "frame too small" }
        check(!finished) { "already finished" }
        if (!started) writeHeader(globalPalette = palette)
        writeFrame(indices, delayCs, localPalette = null)
    }

    private fun writeFrame(indices: ByteArray, delayCs: Int, localPalette: IntArray?) {
        // Graphic Control Extension
        out.write(0x21); out.write(0xF9); out.write(4)
        // packed: reserved(3)=0, disposal(3)=2, user input(1)=0, transparency flag(1)=1
        out.write((2 shl 2) or 1)
        writeShort(delayCs.coerceIn(MIN_DELAY_CS, 65535))
        out.write(TRANSPARENT_INDEX)
        out.write(0)

        // Image Descriptor: full canvas, not interlaced.
        out.write(0x2C)
        writeShort(0); writeShort(0)
        writeShort(width); writeShort(height)
        if (localPalette == null) {
            out.write(0x00) // uses the Global Color Table
        } else {
            out.write(0x80 or 7) // Local Color Table, 2^(7+1) = 256 entries
            writeColorTable(localPalette)
        }

        Lzw.encode(indices, MIN_CODE_SIZE, out)
    }

    /** Writes the trailer. */
    fun finish() {
        if (finished) return
        if (!started) writeHeader(globalPalette = globalPalette ?: IntArray(0))
        out.write(0x3B)
        out.flush()
        finished = true
    }

    private fun writeHeader(globalPalette: IntArray) {
        started = true
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        // Logical Screen Descriptor: Global Color Table of 256 entries, 8-bit color resolution.
        writeShort(width); writeShort(height)
        out.write(0x80 or 0x70 or 7)
        out.write(TRANSPARENT_INDEX) // background color index = the transparent entry
        out.write(0)                 // pixel aspect ratio
        writeColorTable(globalPalette)
        // NETSCAPE2.0 application extension (looping)
        out.write(0x21); out.write(0xFF); out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3); out.write(1)
        writeShort(loopCount.coerceIn(0, 65535))
        out.write(0)
    }

    /** 256 RGB entries: the palette, padded with black (the transparent entry is 255). */
    private fun writeColorTable(palette: IntArray) {
        for (i in 0 until 256) {
            val c = if (i < palette.size && i != TRANSPARENT_INDEX) palette[i] else 0
            out.write((c shr 16) and 0xFF)
            out.write((c shr 8) and 0xFF)
            out.write(c and 0xFF)
        }
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
 * Per-frame quantizer (median cut on a 5:5:5 histogram, see [Palette]).
 * The converter uses one global [Palette] for all frames instead.
 */
object ColorQuantizer {
    fun quantize(argb: IntArray, pixelCount: Int): QuantizedFrame {
        val h = Histogram()
        h.add(argb, pixelCount)
        val palette = Palette.from(h)
        val indices = ByteArray(pixelCount)
        palette.map(argb, pixelCount, indices)
        return QuantizedFrame(palette.colors, indices)
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
