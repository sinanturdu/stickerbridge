package dev.stickerbridge.app.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class PaletteTest {

    private fun frames(n: Int, w: Int, h: Int, seed: Int = 1): List<IntArray> {
        val rnd = Random(seed)
        return (0 until n).map { f ->
            IntArray(w * h) { i ->
                val x = i % w
                val y = i / w
                val dx = x - w / 2 - f
                val dy = y - h / 2
                if (dx * dx + dy * dy > (w / 2 - 4) * (w / 2 - 4)) 0
                else (0xFF shl 24) or (((x * 255 / w) + rnd.nextInt(8)) and 0xFF shl 16) or
                    (((y * 255 / h) + rnd.nextInt(8)) and 0xFF shl 8) or ((f * 9) and 0xFF)
            }
        }
    }

    @Test
    fun globalPaletteHasAtMost255ColorsAndMapsTransparency() {
        val fs = frames(20, 240, 240)
        val h = Histogram()
        fs.forEach { h.add(it, it.size) }
        val p = Palette.from(h)
        assertTrue(p.colors.size in 1..255)
        val idx = ByteArray(240 * 240)
        p.map(fs[0], fs[0].size, idx)
        assertEquals(255, idx[0].toInt() and 0xFF) // corner transparent
        assertTrue((idx[240 * 120 + 120].toInt() and 0xFF) < p.colors.size) // center opaque
    }

    @Test
    fun fewColorsAreKeptExactly() {
        val colors = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        val argb = IntArray(30) { colors[it % 3] }
        val h = Histogram().apply { add(argb, argb.size) }
        val p = Palette.from(h)
        assertEquals(3, p.colors.size)
        val idx = ByteArray(30)
        p.map(argb, 30, idx)
        for (i in 0 until 30) assertEquals(colors[i % 3] and 0xFFFFFF, p.colors[idx[i].toInt() and 0xFF])
    }

    @Test
    fun medianCutSplitsIntoNonEmptyBoxes() {
        val counts = IntArray(32768) { if (it % 7 == 0) 1 + it % 5 else 0 }
        val keys = (0 until 32768).filter { counts[it] > 0 }.toIntArray()
        val boxes = MedianCut.split(keys, counts, 255)
        assertEquals(255, boxes.size)
        assertTrue(boxes.all { it.isNotEmpty() })
        assertEquals(keys.size, boxes.sumOf { it.size })
    }

    /** Prints the JVM cost of the encode work for a typical case (20 frames, 240 px). */
    @Test
    fun encodeWorkFor20FramesAt240pxIsFast() {
        val fs = frames(20, 240, 240)
        val t0 = System.nanoTime()
        val h = Histogram()
        fs.forEach { h.add(it, it.size) }
        val p = Palette.from(h)
        val bos = ByteArrayOutputStream()
        val gif = GifEncoder(bos, 240, 240, 0, p.colors)
        val idx = ByteArray(240 * 240)
        fs.forEach { p.map(it, it.size, idx); gif.addIndexedFrame(idx, 8) }
        gif.finish()
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("palette+map+LZW for 20 frames at 240 px: $ms ms, ${bos.size() / 1024} KB")
        assertTrue("too slow: $ms ms", ms < 3_000) // generous bound for slow CI machines
    }
}
