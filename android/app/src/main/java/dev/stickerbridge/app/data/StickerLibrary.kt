package dev.stickerbridge.app.data

import androidx.room.withTransaction

/**
 * Recent and Favorites on top of the sticker table.
 * Ordering rules live in [TabLogic]; this class only loads and stores.
 * Stickers that are archived (missing, or folder disconnected) are hidden, but their
 * usage/favorite records are kept so they come back when the file reappears.
 */
class StickerLibrary(private val db: AppDatabase) {
    private val stickers = db.stickerDao()
    private val usage = db.usageDao()
    private val favorites = db.favoriteDao()

    suspend fun all(): List<StickerGridItem> = stickers.gridItems()

    suspend fun recent(): List<StickerGridItem> {
        val events = usage.allNewestFirst().map { TabLogic.Use(it.id, it.stickerId, it.usedAt) }
        val visible = visibleById(TabLogic.distinctRecent(events))
        return TabLogic.recentTab(events, { it in visible }).mapNotNull { visible[it] }
    }

    suspend fun favorites(): List<StickerGridItem> {
        val ordered = TabLogic.orderFavorites(favoriteRows())
        val visible = visibleById(ordered)
        return ordered.mapNotNull { visible[it] }
    }

    suspend fun favoriteIds(): Set<Long> = favorites.all().mapTo(HashSet()) { it.stickerId }

    /** Records a successful insert and trims the history to the newest 200 events. */
    suspend fun recordUse(stickerId: Long, now: Long = System.currentTimeMillis()) {
        db.withTransaction {
            usage.insert(StickerUsage(stickerId = stickerId, usedAt = now))
            val events = usage.allNewestFirst().map { TabLogic.Use(it.id, it.stickerId, it.usedAt) }
            val trim = TabLogic.eventsToTrim(events)
            trim.chunked(StickerDao.CHUNK).forEach { usage.deleteIds(it) }
        }
    }

    suspend fun removeFromRecent(stickerId: Long) = usage.deleteForSticker(stickerId)

    /** Adds or removes a LOCAL favorite. Adding (again) moves it to the top. */
    suspend fun setFavorite(stickerId: Long, favorite: Boolean, now: Long = System.currentTimeMillis()) {
        if (favorite) {
            favorites.upsert(StickerFavorite(stickerId = stickerId, source = FavoriteSource.LOCAL, favoritedAt = now))
        } else {
            favorites.delete(stickerId, FavoriteSource.LOCAL)
        }
    }

    private suspend fun favoriteRows() =
        favorites.all().map { TabLogic.Fav(it.stickerId, it.source, it.favoritedAt) }

    private suspend fun visibleById(ids: List<Long>): Map<Long, StickerGridItem> {
        val out = HashMap<Long, StickerGridItem>(ids.size * 2)
        ids.chunked(StickerDao.CHUNK).forEach { chunk ->
            stickers.gridItemsByIds(chunk).forEach { out[it.id] = it }
        }
        return out
    }
}
