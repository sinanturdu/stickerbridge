package dev.stickerbridge.app.data

/** Pure ordering rules for the Recent and Favorites tabs (unit-tested, no Android). */
object TabLogic {
    const val RECENT_LIMIT = 32
    const val MAX_USAGE_EVENTS = 200

    /** One usage event as the logic needs it. */
    data class Use(val eventId: Long, val stickerId: Long, val usedAt: Long)

    /** One favorite row as the logic needs it. */
    data class Fav(val stickerId: Long, val source: String, val favoritedAt: Long)

    /**
     * Distinct sticker ids, most recently used first. A re-sent sticker moves to the top;
     * each id appears once. Ties on usedAt are broken by the newer event id.
     */
    fun distinctRecent(events: List<Use>): List<Long> {
        val sorted = events.sortedWith(compareByDescending<Use> { it.usedAt }.thenByDescending { it.eventId })
        val seen = LinkedHashSet<Long>()
        for (e in sorted) seen.add(e.stickerId)
        return seen.toList()
    }

    /**
     * Recent tab content: distinct recent ids, keeping only stickers that are currently
     * visible ([isVisible]), at most [limit].
     */
    fun recentTab(events: List<Use>, isVisible: (Long) -> Boolean, limit: Int = RECENT_LIMIT): List<Long> =
        distinctRecent(events).asSequence().filter(isVisible).take(limit).toList()

    /** Event ids to delete so that only the newest [keep] events remain. */
    fun eventsToTrim(events: List<Use>, keep: Int = MAX_USAGE_EVENTS): List<Long> =
        events.sortedWith(compareByDescending<Use> { it.usedAt }.thenByDescending { it.eventId })
            .drop(keep)
            .map { it.eventId }

    /**
     * Favorites tab order: newest favoritedAt first. A sticker that is a favorite from
     * several sources (LOCAL and, from A3, WHATSAPP) appears once, at its newest time.
     */
    fun orderFavorites(rows: List<Fav>): List<Long> =
        rows.groupBy { it.stickerId }
            .map { (id, list) -> id to list.maxOf { it.favoritedAt } }
            .sortedWith(compareByDescending<Pair<Long, Long>> { it.second }.thenByDescending { it.first })
            .map { it.first }

    /**
     * Toggles a LOCAL favorite in an in-memory list (mirrors what the repository writes):
     * adding gives it [now] as favoritedAt (it moves to the top), removing drops it.
     */
    fun toggleLocal(rows: List<Fav>, stickerId: Long, now: Long): List<Fav> {
        val isLocalFav = rows.any { it.stickerId == stickerId && it.source == FavoriteSource.LOCAL }
        return if (isLocalFav) {
            rows.filterNot { it.stickerId == stickerId && it.source == FavoriteSource.LOCAL }
        } else {
            rows + Fav(stickerId, FavoriteSource.LOCAL, now)
        }
    }
}
