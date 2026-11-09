package dev.stickerbridge.app.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Decodes the encoder's output with the JDK's own GIF reader (javax.imageio),
 * an implementation independent of ours, and checks structure and metadata.
 *
 * javax.imageio and java.awt are not on the Android compile classpath (android.jar), but they
 * are present in the host JDK that runs unit tests, so they are used through reflection on
 * their public API types.
 */
class GifImageIoTest {

    // ---- minimal reflective bridge to javax.imageio ----

    private object Io {
        private val imageIO = Class.forName("javax.imageio.ImageIO")
        private val readerCls = Class.forName("javax.imageio.ImageReader")
        private val metadataCls = Class.forName("javax.imageio.metadata.IIOMetadata")
        private val nodeCls = Class.forName("javax.imageio.metadata.IIOMetadataNode")
        private val bufferedImageCls = Class.forName("java.awt.image.BufferedImage")

        fun reader(bytes: ByteArray): Any {
            val readers = imageIO.getMethod("getImageReadersByFormatName", String::class.java).invoke(null, "gif") as Iterator<*>
            val reader = readers.next()!!
            val input = imageIO.getMethod("createImageInputStream", Any::class.java).invoke(null, ByteArrayInputStream(bytes))
            readerCls.getMethod("setInput", Any::class.java).invoke(reader, input)
            return reader
        }

        fun numImages(r: Any) = readerCls.getMethod("getNumImages", Boolean::class.javaPrimitiveType).invoke(r, true) as Int

        fun read(r: Any, i: Int): Any = readerCls.getMethod("read", Int::class.javaPrimitiveType).invoke(r, i)!!

        fun imageMeta(r: Any, i: Int): Node {
            val m = readerCls.getMethod("getImageMetadata", Int::class.javaPrimitiveType).invoke(r, i)
            return metadataCls.getMethod("getAsTree", String::class.java).invoke(m, "javax_imageio_gif_image_1.0") as Node
        }

        fun streamMeta(r: Any): Node {
            val m = readerCls.getMethod("getStreamMetadata").invoke(r)
            return metadataCls.getMethod("getAsTree", String::class.java).invoke(m, "javax_imageio_gif_stream_1.0") as Node
        }

        fun userObject(node: Node): Any? = nodeCls.getMethod("getUserObject").invoke(node)

        fun width(img: Any) = bufferedImageCls.getMethod("getWidth").invoke(img) as Int
        fun height(img: Any) = bufferedImageCls.getMethod("getHeight").invoke(img) as Int
        fun rgb(img: Any, x: Int, y: Int) =
            bufferedImageCls.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(img, x, y) as Int
    }

    private fun child(node: Node, name: String): Element? {
        var c = node.firstChild
        while (c != null) {
            if (c.nodeName == name) return c as Element
            c = c.nextSibling
        }
        return null
    }

    private fun encode(w: Int, h: Int, frames: List<IntArray>, delays: List<Int>, loop: Int = 0): ByteArray {
        val bos = ByteArrayOutputStream()
        val enc = GifEncoder(bos, w, h, loop)
        frames.forEachIndexed { i, f -> enc.addFrame(f, delays[i]) }
        enc.finish()
        return bos.toByteArray()
    }

    /** A moving, many-colored, partly transparent test animation. */
    private fun animation(w: Int, h: Int, n: Int): List<IntArray> = (0 until n).map { f ->
        IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val dx = x - (w / 2 + f * 3 - n)
            val dy = y - h / 2
            if (dx * dx + dy * dy > (w / 3) * (w / 3)) 0x00000000
            else (0xFF shl 24) or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or ((f * 37) and 0xFF)
        }
    }

    // ---- tests ----

    @Test
    fun multiFrameGifDecodesWithMatchingFrameCountDelaysAndSize() {
        val w = 160
        val h = 120
        val delays = listOf(4, 7, 2, 10, 25, 3)
        val r = Io.reader(encode(w, h, animation(w, h, delays.size), delays))

        assertEquals(delays.size, Io.numImages(r))
        for (i in delays.indices) {
            val img = Io.read(r, i)
            assertEquals(w, Io.width(img))
            assertEquals(h, Io.height(img))
            val meta = Io.imageMeta(r, i)
            val desc = child(meta, "ImageDescriptor")!!
            assertEquals("0", desc.getAttribute("imageLeftPosition"))
            assertEquals("0", desc.getAttribute("imageTopPosition"))
            assertEquals(w.toString(), desc.getAttribute("imageWidth"))
            assertEquals(h.toString(), desc.getAttribute("imageHeight"))
            assertEquals("FALSE", desc.getAttribute("interlaceFlag"))
            val gce = child(meta, "GraphicControlExtension")
            assertNotNull("frame $i has no Graphic Control Extension", gce)
            assertEquals(delays[i].toString(), gce!!.getAttribute("delayTime"))
            assertEquals("restoreToBackgroundColor", gce.getAttribute("disposalMethod"))
        }
        val stream = Io.streamMeta(r)
        val lsd = child(stream, "LogicalScreenDescriptor")!!
        assertEquals(w.toString(), lsd.getAttribute("logicalScreenWidth"))
        assertEquals(h.toString(), lsd.getAttribute("logicalScreenHeight"))
        assertEquals("89a", child(stream, "Version")!!.getAttribute("value"))
    }

    @Test
    fun transparencyIndexIsValidAndTransparentPixelsDecodeTransparent() {
        val w = 64
        val h = 64
        val r = Io.reader(encode(w, h, animation(w, h, 3), listOf(5, 5, 5)))
        for (i in 0 until 3) {
            val meta = Io.imageMeta(r, i)
            val gce = child(meta, "GraphicControlExtension")!!
            assertEquals("TRUE", gce.getAttribute("transparentColorFlag"))
            val tIndex = gce.getAttribute("transparentColorIndex").toInt()
            val tableSize = child(meta, "LocalColorTable")?.getAttribute("sizeOfLocalColorTable")?.toInt()
                ?: child(Io.streamMeta(r), "GlobalColorTable")!!.getAttribute("sizeOfGlobalColorTable").toInt()
            assertTrue("transparent index $tIndex outside color table of $tableSize", tIndex in 0 until tableSize)
            val img = Io.read(r, i)
            assertEquals(0, Io.rgb(img, 0, 0) ushr 24) // corner is transparent
            assertEquals(0xFF, Io.rgb(img, w / 2 + i * 3 - 3, h / 2) ushr 24) // circle is opaque
        }
    }

    @Test
    fun globalPaletteModeDecodesWithOneColorTable() {
        val w = 120
        val h = 90
        val frames = animation(w, h, 5)
        val histogram = Histogram()
        frames.forEach { histogram.add(it, w * h) }
        val palette = Palette.from(histogram)
        val bos = ByteArrayOutputStream()
        val enc = GifEncoder(bos, w, h, 0, palette.colors)
        val idx = ByteArray(w * h)
        frames.forEach { palette.map(it, w * h, idx); enc.addIndexedFrame(idx, 6) }
        enc.finish()

        val r = Io.reader(bos.toByteArray())
        assertEquals(5, Io.numImages(r))
        assertNotNull(child(Io.streamMeta(r), "GlobalColorTable"))
        for (i in 0 until 5) {
            val meta = Io.imageMeta(r, i)
            assertEquals(null, child(meta, "LocalColorTable")) // shared table only
            assertEquals("6", child(meta, "GraphicControlExtension")!!.getAttribute("delayTime"))
            val img = Io.read(r, i)
            assertEquals(0, Io.rgb(img, 0, 0) ushr 24)
            assertEquals(0xFF, Io.rgb(img, w / 2 + i * 3 - 5, h / 2) ushr 24)
        }
    }

    @Test
    fun loopsForeverViaNetscapeBlock() {
        val r = Io.reader(encode(8, 8, animation(8, 8, 2), listOf(10, 10)))
        Io.numImages(r)
        val apps = child(Io.imageMeta(r, 0), "ApplicationExtensions")
        assertNotNull("no application extensions", apps)
        val app = child(apps!!, "ApplicationExtension")!!
        assertEquals("NETSCAPE", app.getAttribute("applicationID"))
        assertEquals("2.0", app.getAttribute("authenticationCode"))
        val data = Io.userObject(app) as ByteArray
        assertEquals(1, data[0].toInt())
        assertEquals(0, (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)) // 0 = forever
    }

    @Test
    fun largeNoisyFrameExercisesFullCodeWidthAndDecodesCorrectly() {
        // Noise forces > 4096 codes (code size up to 12 bits plus clear codes) in one frame.
        val w = 256
        val h = 256
        val rnd = java.util.Random(7)
        val palette = IntArray(200) { (0xFF shl 24) or (rnd.nextInt() and 0xFFFFFF) }
        val frame = IntArray(w * h) { palette[rnd.nextInt(palette.size)] }
        val img = Io.read(Io.reader(encode(w, h, listOf(frame), listOf(10))), 0)
        var mismatches = 0
        for (i in 0 until w * h) {
            val a = frame[i]
            val b = Io.rgb(img, i % w, i / w)
            val dr = Math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
            val dg = Math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
            val db = Math.abs((a and 0xFF) - (b and 0xFF))
            if (dr > 8 || dg > 8 || db > 8) mismatches++
        }
        assertEquals(0, mismatches)
    }

    @Test
    fun blockStructureIsValid() {
        val w = 200
        val h = 200
        val bytes = encode(w, h, animation(w, h, 2), listOf(10, 10))
        var p = 6 + 7
        val lsdPacked = bytes[10].toInt() and 0xFF
        if (lsdPacked and 0x80 != 0) p += 3 * (1 shl ((lsdPacked and 7) + 1))
        var images = 0
        var gces = 0
        while (true) {
            when (bytes[p].toInt() and 0xFF) {
                0x21 -> {
                    if ((bytes[p + 1].toInt() and 0xFF) == 0xF9) {
                        assertEquals(4, bytes[p + 2].toInt()) // GCE block size
                        gces++
                    }
                    p += 2
                    while (bytes[p].toInt() != 0) p += (bytes[p].toInt() and 0xFF) + 1 // length byte is 1..255
                    p++
                }
                0x2C -> {
                    val packed = bytes[p + 9].toInt() and 0xFF
                    p += 10
                    if (packed and 0x80 != 0) p += 3 * (1 shl ((packed and 7) + 1))
                    assertEquals(8, bytes[p].toInt()) // LZW minimum code size for a 256-entry table
                    p++
                    while (bytes[p].toInt() != 0) p += (bytes[p].toInt() and 0xFF) + 1
                    p++
                    images++
                }
                0x3B -> break
                else -> throw AssertionError("bad block 0x%02x at %d".format(bytes[p], p))
            }
        }
        assertEquals(2, images)
        assertEquals(2, gces) // one Graphic Control Extension per frame
        assertEquals(bytes.size - 1, p) // trailer 0x3B is the last byte
    }
}
