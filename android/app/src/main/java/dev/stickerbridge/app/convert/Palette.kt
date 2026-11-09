package dev.stickerbridge.app.convert

/**
 * Fast color quantization on a 5:5:5 histogram (32,768 buckets).
 *
 * Median cut splits boxes with 32-bucket counting (no sorting, no boxing), so building a
 * 255-color palette costs O(255 x distinct colors) at most. Pixel mapping uses a shared
 * 32,768-entry lookup table filled lazily with the nearest palette color, so mapping is
 * one array read per pixel after warm-up.
 */
class Palette(val colors: IntArray) {
    private val lookup = IntArray(32768) { -1 }

    /** Maps ARGB pixels to palette indices; alpha < 50 % -> [GifEncoder.TRANSPARENT_INDEX]. */
    fun map(argb: IntArray, pixelCount: Int, out: ByteArray) {
        for (i in 0 until pixelCount) {
            val c = argb[i]
            if ((c ushr 24) < GifEncoder.ALPHA_THRESHOLD) {
                out[i] = GifEncoder.TRANSPARENT_INDEX.toByte()
                continue
            }
            val key = Histogram.key15(c)
            var idx = lookup[key]
            if (idx < 0) {
                idx = nearest(key)
                lookup[key] = idx
            }
            out[i] = idx.toByte()
        }
    }

    private fun nearest(key: Int): Int {
        if (colors.isEmpty()) return 0
        val r = ((key shr 10) and 31) * 8 + 4
        val g = ((key shr 5) and 31) * 8 + 4
        val b = (key and 31) * 8 + 4
        var best = 0
        var bestD = Int.MAX_VALUE
        for (i in colors.indices) {
            val p = colors[i]
            val dr = ((p shr 16) and 0xFF) - r
            val dg = ((p shr 8) and 0xFF) - g
            val db = (p and 0xFF) - b
            val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    companion object {
        const val MAX_COLORS = 255

        /** Builds one palette from a histogram (e.g. of all kept frames). */
        fun from(h: Histogram): Palette {
            val keys = h.keys()
            if (keys.size <= MAX_COLORS) {
                val p = Palette(IntArray(keys.size) { h.mean(intArrayOf(keys[it])) })
                keys.forEachIndexed { i, k -> p.lookup[k] = i }
                return p
            }
            val boxes = MedianCut.split(keys, h.counts, MAX_COLORS)
            return Palette(IntArray(boxes.size) { h.mean(boxes[it]) })
        }
    }
}

/** 5:5:5 histogram of opaque pixels with exact 8-bit sums per bucket. */
class Histogram {
    val counts = IntArray(32768)
    private val sumR = LongArray(32768)
    private val sumG = LongArray(32768)
    private val sumB = LongArray(32768)

    /** Adds opaque pixels (alpha >= 50 %). [step] > 1 samples every n-th pixel. */
    fun add(argb: IntArray, pixelCount: Int, step: Int = 1) {
        var i = 0
        while (i < pixelCount) {
            val c = argb[i]
            if ((c ushr 24) >= GifEncoder.ALPHA_THRESHOLD) {
                val k = key15(c)
                counts[k]++
                sumR[k] += ((c shr 16) and 0xFF).toLong()
                sumG[k] += ((c shr 8) and 0xFF).toLong()
                sumB[k] += (c and 0xFF).toLong()
            }
            i += step
        }
    }

    fun keys(): IntArray {
        var n = 0
        for (c in counts) if (c > 0) n++
        val out = IntArray(n)
        var j = 0
        for (k in counts.indices) if (counts[k] > 0) out[j++] = k
        return out
    }

    fun mean(keys: IntArray): Int {
        var n = 0L; var r = 0L; var g = 0L; var b = 0L
        for (k in keys) { n += counts[k]; r += sumR[k]; g += sumG[k]; b += sumB[k] }
        if (n == 0L) return 0
        return (((r / n).toInt()) shl 16) or (((g / n).toInt()) shl 8) or (b / n).toInt()
    }

    companion object {
        fun key15(c: Int): Int {
            val r = ((c shr 16) and 0xFF) shr 3
            val g = ((c shr 8) and 0xFF) shr 3
            val b = (c and 0xFF) shr 3
            return (r shl 10) or (g shl 5) or b
        }
    }
}

/** Median cut over 15-bit keys using 32-bucket counting per axis. */
object MedianCut {
    private fun channel(key: Int, axis: Int) = when (axis) {
        0 -> (key shr 10) and 31
        1 -> (key shr 5) and 31
        else -> key and 31
    }

    fun split(keys: IntArray, counts: IntArray, maxBoxes: Int): List<IntArray> {
        val boxes = ArrayList<IntArray>(maxBoxes)
        boxes += keys
        val bucket = LongArray(32)
        while (boxes.size < maxBoxes) {
            var bestBox = -1
            var bestAxis = 0
            var bestScore = 0L
            for ((bi, box) in boxes.withIndex()) {
                if (box.size < 2) continue
                var pop = 0L
                var loR = 31; var hiR = 0; var loG = 31; var hiG = 0; var loB = 31; var hiB = 0
                for (k in box) {
                    pop += counts[k]
                    val r = (k shr 10) and 31; val g = (k shr 5) and 31; val b = k and 31
                    if (r < loR) loR = r; if (r > hiR) hiR = r
                    if (g < loG) loG = g; if (g > hiG) hiG = g
                    if (b < loB) loB = b; if (b > hiB) hiB = b
                }
                val ranges = intArrayOf(hiR - loR, hiG - loG, hiB - loB)
                for (axis in 0..2) {
                    val score = ranges[axis].toLong() * pop
                    if (ranges[axis] > 0 && score > bestScore) { bestScore = score; bestBox = bi; bestAxis = axis }
                }
            }
            if (bestBox < 0) break
            val box = boxes[bestBox]
            // Population per channel value, then the value where half the population is reached.
            bucket.fill(0)
            var total = 0L
            for (k in box) { bucket[channel(k, bestAxis)] += counts[k].toLong(); total += counts[k] }
            var lo = 31; var hi = 0
            for (k in box) { val v = channel(k, bestAxis); if (v < lo) lo = v; if (v > hi) hi = v }
            var acc = 0L
            var cut = lo
            for (v in lo..hi) {
                acc += bucket[v]
                if (acc * 2 >= total) { cut = v; break }
            }
            if (cut >= hi) cut = hi - 1 // both halves non-empty
            var nLeft = 0
            for (k in box) if (channel(k, bestAxis) <= cut) nLeft++
            val left = IntArray(nLeft)
            val right = IntArray(box.size - nLeft)
            var li = 0; var ri = 0
            for (k in box) if (channel(k, bestAxis) <= cut) left[li++] = k else right[ri++] = k
            boxes[bestBox] = left
            boxes += right
        }
        return boxes
    }
}
