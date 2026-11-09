package dev.stickerbridge.app.ime

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.stickerbridge.app.R
import dev.stickerbridge.app.data.StickerGridItem
import kotlinx.coroutines.Job

/**
 * Grid of stickers. The full list holds only light rows (ids and URIs); thumbnails
 * are decoded lazily per visible cell, so ~12,000 items stay smooth.
 * Animated stickers get a "GIF" badge once known; a tile being converted shows a spinner;
 * favorites get a star (except in the Favorites tab itself).
 *
 * Animated previews (API 28+, setting "Animate previews"): visible animated tiles play an
 * AnimatedImageDrawable decoded at tile size, at most [MAX_PLAYING] at once; a tile stops
 * when it is scrolled off (detached/recycled) or when the keyboard is hidden ([stopAll]).
 * Tiles over the limit keep their static first-frame thumbnail.
 */
class StickerAdapter(
    private val thumbnails: ThumbnailLoader,
    private val onClick: (StickerGridItem) -> Unit,
    private val onLongClick: (StickerGridItem, View) -> Unit,
    /** Called when a tile's thumbnail decode revealed the animation flag of a row stored as unknown. */
    private val onAnimatedLearned: (Long, Boolean) -> Unit = { _, _ -> },
) : RecyclerView.Adapter<StickerAdapter.Holder>() {

    private var items: List<StickerGridItem> = emptyList()
    private var favoriteIds: Set<Long> = emptySet()
    private var showStars = true
    private val busyIds = HashSet<Long>()
    /** Animation flags learned after the list was loaded (e.g. on tap). */
    private val learnedAnimated = HashMap<Long, Boolean>()
    private val playing = LinkedHashSet<Holder>()

    /** Set from the "Animate previews" setting. */
    var animatePreviews: Boolean = true
        set(value) {
            field = value
            if (!value) stopAll()
        }

    init {
        setHasStableIds(true)
    }

    fun submit(newItems: List<StickerGridItem>, favorites: Set<Long>, showStars: Boolean) {
        items = newItems
        favoriteIds = favorites
        this.showStars = showStars
        learnedAnimated.clear()
        notifyDataSetChanged() // full reload after a scan; stable ids keep scroll position
    }

    fun setBusy(id: Long, busy: Boolean) {
        val changed = if (busy) busyIds.add(id) else busyIds.remove(id)
        if (changed) notifyItemChangedById(id)
    }

    fun setAnimated(id: Long, animated: Boolean) {
        if (learnedAnimated.put(id, animated) != animated) notifyItemChangedById(id)
    }

    private fun notifyItemChangedById(id: Long) {
        val pos = items.indexOfFirst { it.id == id }
        if (pos >= 0) notifyItemChanged(pos, PAYLOAD_STATE)
    }

    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = items[position].id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sticker, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_STATE)) {
            bindState(holder, items[position])
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.boundId = item.id
        holder.job?.cancel()
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener { onLongClick(item, it); true }
        bindState(holder, item)
        stopAnimation(holder)
        holder.animJob?.cancel()
        val cached = thumbnails.cached(item.id)
        if (cached != null) {
            holder.image.setImageBitmap(cached)
        } else {
            holder.image.setImageDrawable(null)
            val unknown = learnedAnimated[item.id] == null && item.isAnimated == null
            holder.job = thumbnails.load(item.id, documentUri(item), needAnimatedFlag = unknown) { bitmap, animated ->
                if (holder.boundId == item.id && holder.animated == null) holder.image.setImageBitmap(bitmap)
                // Lazy animation flag: learned from the visible tile, stored once.
                if (unknown && animated != null && learnedAnimated[item.id] == null) {
                    onAnimatedLearned(item.id, animated)
                    learnedAnimated[item.id] = animated
                    if (holder.boundId == item.id) {
                        bindState(holder, item)
                        if (animated) maybeAnimate(holder, item)
                    }
                }
            }
        }
        maybeAnimate(holder, item)
    }

    private fun isAnimated(item: StickerGridItem) = (learnedAnimated[item.id] ?: item.isAnimated) == true

    private fun maybeAnimate(holder: Holder, item: StickerGridItem) {
        if (!animatePreviews || Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !isAnimated(item)) return
        if (playing.size >= MAX_PLAYING) return
        holder.animJob = thumbnails.loadAnimated(documentUri(item)) { drawable ->
            if (drawable == null || holder.boundId != item.id || !animatePreviews) return@loadAnimated
            if (playing.size >= MAX_PLAYING) return@loadAnimated
            holder.animated = drawable
            holder.image.setImageDrawable(drawable)
            (drawable as? Animatable)?.start()
            playing += holder
        }
    }

    private fun stopAnimation(holder: Holder) {
        (holder.animated as? Animatable)?.stop()
        holder.animated = null
        playing -= holder
    }

    /** Stops every playing tile (keyboard hidden, setting off). Static thumbnails stay. */
    fun stopAll() {
        for (h in playing.toList()) {
            h.animJob?.cancel()
            stopAnimation(h)
            val id = h.boundId
            thumbnails.cached(id)?.let { h.image.setImageBitmap(it) }
        }
        playing.clear()
    }

    /** Restarts animations for tiles that are on screen again (keyboard shown). */
    fun resumeVisible(recycler: RecyclerView) {
        if (!animatePreviews) return
        for (i in 0 until recycler.childCount) {
            val holder = recycler.getChildViewHolder(recycler.getChildAt(i)) as? Holder ?: continue
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION || pos >= items.size) continue
            if (holder.animated == null) maybeAnimate(holder, items[pos])
        }
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION && pos < items.size && holder.animated == null) maybeAnimate(holder, items[pos])
    }

    override fun onViewDetachedFromWindow(holder: Holder) {
        holder.animJob?.cancel()
        stopAnimation(holder)
    }

    private fun bindState(holder: Holder, item: StickerGridItem) {
        val animated = learnedAnimated[item.id] ?: item.isAnimated
        holder.badge.visibility = if (animated == true) View.VISIBLE else View.GONE
        holder.busy.visibility = if (item.id in busyIds) View.VISIBLE else View.GONE
        holder.star.visibility = if (showStars && item.id in favoriteIds) View.VISIBLE else View.GONE
    }

    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.job = null
        holder.animJob?.cancel()
        stopAnimation(holder)
        holder.boundId = -1
        holder.image.setImageDrawable(null)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.stickerImage)
        val badge: TextView = view.findViewById(R.id.stickerBadge)
        val busy: ProgressBar = view.findViewById(R.id.stickerBusy)
        val star: ImageView = view.findViewById(R.id.stickerStar)
        var job: Job? = null
        var animJob: Job? = null
        var animated: Drawable? = null
        var boundId: Long = -1
    }

    companion object {
        private const val PAYLOAD_STATE = "state"
        const val MAX_PLAYING = 12

        /**
         * The ONE way every path (thumbnails, header checks, inserts, GIF conversion) opens a
         * sticker: the SAF document URI built from the stored (treeUri, documentId).
         * dev.stickerbridge.app.scan.SafUris is its unit-tested pure twin.
         */
        fun documentUri(item: StickerGridItem): Uri = documentUri(item.sourceUri, item.documentId)

        fun documentUri(sourceUri: String, documentId: String): Uri =
            DocumentsContract.buildDocumentUriUsingTree(Uri.parse(sourceUri), documentId)
    }
}
