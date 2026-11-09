package dev.stickerbridge.app.ime

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Decodes small thumbnails off the main thread with downsampling and keeps
 * them in an LRU memory cache (1/8 of the app heap, by bytes).
 * Animated stickers show their first frame.
 */
class ThumbnailLoader(
    private val resolver: ContentResolver,
    private val scope: CoroutineScope,
    private val targetPx: Int,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(4)

    private val cache = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount
    }

    fun cached(id: Long): Bitmap? = cache.get(id)

    /** Starts loading; [onLoaded] runs on the main thread. Cancel the returned job when the view is recycled. */
    fun load(id: Long, uri: Uri, onLoaded: (Bitmap?) -> Unit): Job = scope.launch(Dispatchers.Main) {
        val bitmap = withContext(decodeDispatcher) {
            coroutineContext.ensureActive()
            cache.get(id) ?: decode(uri)?.also { cache.put(id, it) }
        }
        onLoaded(bitmap)
    }

    fun clear() = cache.evictAll()

    private fun decode(uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) decodeWithImageDecoder(uri) else decodeWithBitmapFactory(uri)
    } catch (e: Exception) {
        null // unreadable / deleted file: the tile stays empty
    } catch (e: OutOfMemoryError) {
        cache.evictAll()
        null
    }

    private fun decodeWithImageDecoder(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = targetPx.toFloat() / maxOf(w, h, 1)
            if (scale < 1f) decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        }
    }

    private fun decodeWithBitmapFactory(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        while (largest / (sample * 2) >= targetPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
