package dev.stickerbridge.app.convert

/** Pure LRU eviction decision for the GIF conversion cache (unit-tested). */
object LruPolicy {
    data class Entry(val name: String, val bytes: Long, val lastUsed: Long)

    /**
     * Names to delete, least recently used first, until the total is at most [maxBytes].
     * [keep] (the file just written) is never evicted.
     */
    fun evict(entries: List<Entry>, maxBytes: Long, keep: String? = null): List<String> {
        var total = entries.sumOf { it.bytes }
        if (total <= maxBytes) return emptyList()
        val out = ArrayList<String>()
        for (e in entries.sortedWith(compareBy<Entry> { it.lastUsed }.thenBy { it.name })) {
            if (total <= maxBytes) break
            if (e.name == keep) continue
            total -= e.bytes
            out += e.name
        }
        return out
    }
}
