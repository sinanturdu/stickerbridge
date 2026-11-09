package dev.stickerbridge.app.data

import dev.stickerbridge.app.data.TabLogic.Fav
import dev.stickerbridge.app.data.TabLogic.Use
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabLogicTest {

    private var nextEvent = 1L
    private fun use(sticker: Long, at: Long) = Use(nextEvent++, sticker, at)

    // ---- Recent ----

    @Test
    fun recentIsDistinctAndMostRecentFirst() {
        val events = listOf(use(1, 100), use(2, 200), use(1, 300), use(3, 250))
        assertEquals(listOf(1L, 3L, 2L), TabLogic.distinctRecent(events))
    }

    @Test
    fun resendingMovesStickerToTop() {
        val before = listOf(use(1, 100), use(2, 200))
        assertEquals(listOf(2L, 1L), TabLogic.distinctRecent(before))
        val after = before + use(1, 300)
        assertEquals(listOf(1L, 2L), TabLogic.distinctRecent(after))
    }

    @Test
    fun sameTimestampTieBrokenByNewerEvent() {
        val events = listOf(use(1, 100), use(2, 100))
        assertEquals(listOf(2L, 1L), TabLogic.distinctRecent(events))
    }

    @Test
    fun recentTabLimitsTo32AndSkipsHiddenStickers() {
        val events = (1L..50L).map { use(it, it * 10) } // sticker 50 is newest
        val hidden = setOf(50L, 49L)
        val tab = TabLogic.recentTab(events, { it !in hidden })
        assertEquals(32, tab.size)
        assertEquals(48L, tab.first())
        assertEquals(17L, tab.last())
    }

    @Test
    fun hiddenStickerComesBackWhenVisibleAgain() {
        val events = listOf(use(7, 100), use(8, 200))
        assertEquals(listOf(8L), TabLogic.recentTab(events, { it != 7L }))
        assertEquals(listOf(8L, 7L), TabLogic.recentTab(events, { true }))
    }

    @Test
    fun trimKeepsNewest200Events() {
        val events = (1L..250L).map { use(it % 40, it) }
        val trim = TabLogic.eventsToTrim(events, keep = 200)
        assertEquals(50, trim.size)
        val oldest50 = events.sortedBy { it.usedAt }.take(50).map { it.eventId }.toSet()
        assertEquals(oldest50, trim.toSet())
        assertTrue(TabLogic.eventsToTrim(events.take(200), keep = 200).isEmpty())
    }

    // ---- Favorites ----

    @Test
    fun favoritesNewestFirst() {
        val rows = listOf(Fav(1, FavoriteSource.LOCAL, 100), Fav(2, FavoriteSource.LOCAL, 300), Fav(3, FavoriteSource.LOCAL, 200))
        assertEquals(listOf(2L, 3L, 1L), TabLogic.orderFavorites(rows))
    }

    @Test
    fun toggleAddsToTopAndRemoves() {
        var rows = listOf(Fav(1, FavoriteSource.LOCAL, 100), Fav(2, FavoriteSource.LOCAL, 200))
        rows = TabLogic.toggleLocal(rows, 3, now = 300)
        assertEquals(listOf(3L, 2L, 1L), TabLogic.orderFavorites(rows))
        rows = TabLogic.toggleLocal(rows, 2, now = 400)
        assertEquals(listOf(3L, 1L), TabLogic.orderFavorites(rows))
    }

    @Test
    fun reAddingAFavoriteMovesItToTheTop() {
        var rows = listOf(Fav(1, FavoriteSource.LOCAL, 100), Fav(2, FavoriteSource.LOCAL, 200))
        rows = TabLogic.toggleLocal(rows, 1, now = 300) // remove
        rows = TabLogic.toggleLocal(rows, 1, now = 400) // add again
        assertEquals(listOf(1L, 2L), TabLogic.orderFavorites(rows))
    }

    @Test
    fun stickerFavoritedFromTwoSourcesAppearsOnceAtNewestTime() {
        val rows = listOf(
            Fav(1, FavoriteSource.WHATSAPP, 500),
            Fav(1, FavoriteSource.LOCAL, 100),
            Fav(2, FavoriteSource.LOCAL, 300),
        )
        assertEquals(listOf(1L, 2L), TabLogic.orderFavorites(rows))
        // Removing the LOCAL favorite keeps the WHATSAPP one.
        assertEquals(listOf(1L, 2L), TabLogic.orderFavorites(TabLogic.toggleLocal(rows, 1, now = 600)))
    }
}
