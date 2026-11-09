package dev.stickerbridge.app.convert

import android.content.Context
import android.os.SystemClock
import dev.stickerbridge.app.DebugLog
import dev.stickerbridge.app.data.StickerDao
import dev.stickerbridge.app.data.StickerGridItem
import dev.stickerbridge.app.data.StickerLibrary
import dev.stickerbridge.app.ime.StickerAdapter
import dev.stickerbridge.app.ime.StickerInserter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Converts the stickers most likely to be sent next into the GIF cache in the background,
 * so most taps send instantly: the first 32 of Recent and of Favorites, plus the 16 newest
 * animated stickers in "In chats". One sticker at a time on a single background thread;
 * the cache is an LRU limited to 100 MB ([StickerInserter.MAX_GIF_CACHE_BYTES]).
 */
class GifPreconverter(
    private val context: Context,
    private val library: StickerLibrary,
    private val dao: StickerDao,
    private val scope: CoroutineScope,
) {
    private val running = AtomicBoolean(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)

    fun run() {
        if (!WebpAnimDecoder.available || !running.compareAndSet(false, true)) return
        scope.launch(worker) {
            try {
                val started = SystemClock.elapsedRealtime()
                val candidates = (library.recent().take(TOP_N) + library.favorites().take(TOP_N) + dao.newestAnimated(NEWEST_ANIMATED))
                    .distinctBy { it.id }
                    .filter { it.isAnimated != false }
                var converted = 0
                var cached = 0
                var skipped = 0
                for (item in candidates) {
                    if (StickerInserter.isGifCached(context, item, PROFILE)) {
                        cached++
                        continue
                    }
                    if (convertOne(item)) converted++ else skipped++
                }
                DebugLog.d(
                    context, DebugLog.GIF,
                    "preconvert: ${candidates.size} candidates, $converted converted, $cached already cached, " +
                        "$skipped skipped (static/unreadable), ${SystemClock.elapsedRealtime() - started} ms",
                )
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun convertOne(item: StickerGridItem): Boolean {
        val bytes = try {
            StickerInserter.readSource(context, StickerAdapter.documentUri(item))
        } catch (e: Exception) {
            return false
        }
        val animated = StickerInserter.isAnimatedWebp(bytes)
        if (item.isAnimated != animated) dao.setAnimated(item.id, animated)
        if (!animated) return false
        return try {
            StickerInserter.ensureCachedGif(context, item, bytes, PROFILE).first != null
        } catch (e: Throwable) {
            false
        }
    }

    companion object {
        /** GIFs are only sent where they animate (Stories, other apps), so pre-convert with that profile. */
        private val PROFILE = GifProfiles.STORY
        private const val TOP_N = 32
        private const val NEWEST_ANIMATED = 16
    }
}
