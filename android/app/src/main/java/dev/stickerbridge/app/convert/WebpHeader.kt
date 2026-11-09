package dev.stickerbridge.app.convert

/** Reads only the first bytes of a WEBP file. */
object WebpHeader {
    /** How many bytes callers should read. */
    const val PROBE_BYTES = 64

    /**
     * True if the WEBP is animated: the VP8X "animation" flag is set or an ANIM chunk
     * appears in the probed header. Returns false for static or non-WEBP data.
     */
    fun isAnimated(head: ByteArray, length: Int = head.size): Boolean {
        val n = minOf(length, head.size)
        if (n < 16) return false
        if (!matches(head, 0, "RIFF") || !matches(head, 8, "WEBP")) return false
        if (n >= 21 && matches(head, 12, "VP8X") && (head[20].toInt() and 0x02) != 0) return true
        for (i in 12..n - 4) if (matches(head, i, "ANIM")) return true
        return false
    }

    private fun matches(b: ByteArray, at: Int, tag: String): Boolean {
        if (at + tag.length > b.size) return false
        for (i in tag.indices) if (b[at + i] != tag[i].code.toByte()) return false
        return true
    }
}
