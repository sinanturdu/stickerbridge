package dev.stickerbridge.app.debug

import android.content.Context
import dev.stickerbridge.app.DebugLog
import dev.stickerbridge.app.convert.AnimatedStickerConverter
import dev.stickerbridge.app.convert.GifPlanning
import dev.stickerbridge.app.data.StickerGridItem
import dev.stickerbridge.app.ime.StickerAdapter
import dev.stickerbridge.app.ime.StickerInserter
import java.io.File

/**
 * Hidden debug tool (debug builds only) to find Instagram's GIF limit.
 *
 * Long-press the app title on the main screen: the last animated sticker that was sent
 * (debug/last_source.webp) is converted at 160, 240, 320 and 480 px (max 30 frames, no
 * size target) into debug/size_<px>.gif. The keyboard then shows a temporary "Debug" tab
 * with these four GIFs; sending each one logs its size and the commitContent result
 * (tag SB-Gif). Whether Instagram accepted it is visible in the chat (or its toast).
 */
object GifSizeTest {
    val SIDES = intArrayOf(160, 240, 320, 480)

    class Entry(val side: Int, val bytes: Long, val frames: Int)

    /** Runs the conversions (background thread). Returns null if there is no source yet. */
    fun run(context: Context): List<Entry>? {
        val dir = StickerInserter.debugDir(context) ?: return null
        val source = File(dir, StickerInserter.DEBUG_SOURCE_NAME)
        if (!source.isFile) return null
        val webp = source.readBytes()
        val entries = ArrayList<Entry>()
        for (side in SIDES) {
            val out = File(dir, "size_$side.gif")
            val r = AnimatedStickerConverter.convertFixed(webp, out, side, GifPlanning.MAX_FRAMES) ?: continue
            entries += Entry(side, out.length(), r.framesKept)
            DebugLog.d(context, DebugLog.GIF, "size test: ${side}px -> ${out.length() / 1024} KB, frames=${r.framesKept}/${r.sourceFrames}, ${r.millis} ms")
        }
        return entries
    }

    /** Grid items for the keyboard's Debug tab (empty if the test has not been run). */
    fun gridItems(context: Context): List<StickerGridItem> {
        val dir = StickerInserter.debugDir(context) ?: return emptyList()
        return SIDES.toList().mapNotNull { side ->
            val f = File(dir, "size_$side.gif")
            if (!f.isFile) null else StickerGridItem(
                id = -side.toLong(), // negative ids never collide with library rows
                sourceUri = StickerAdapter.DEBUG_SOURCE,
                documentId = f.absolutePath,
                mime = StickerInserter.GIF_MIME,
                lastModified = f.lastModified(),
                isAnimated = true,
            )
        }
    }

    fun isDebugItem(item: StickerGridItem) = item.sourceUri == StickerAdapter.DEBUG_SOURCE

    fun sideOf(item: StickerGridItem) = (-item.id).toInt()
}
