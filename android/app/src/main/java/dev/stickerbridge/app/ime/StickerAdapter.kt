package dev.stickerbridge.app.ime

import android.net.Uri
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
 */
class StickerAdapter(
    private val thumbnails: ThumbnailLoader,
    private val onClick: (StickerGridItem) -> Unit,
    private val onLongClick: (StickerGridItem, View) -> Unit,
) : RecyclerView.Adapter<StickerAdapter.Holder>() {

    private var items: List<StickerGridItem> = emptyList()
    private var favoriteIds: Set<Long> = emptySet()
    private var showStars = true
    private val busyIds = HashSet<Long>()
    /** Animation flags learned after the list was loaded (e.g. on tap). */
    private val learnedAnimated = HashMap<Long, Boolean>()

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
        val cached = thumbnails.cached(item.id)
        if (cached != null) {
            holder.image.setImageBitmap(cached)
            return
        }
        holder.image.setImageDrawable(null)
        holder.job = thumbnails.load(item.id, documentUri(item)) { bitmap ->
            if (holder.boundId == item.id) holder.image.setImageBitmap(bitmap)
        }
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
        holder.boundId = -1
        holder.image.setImageDrawable(null)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.stickerImage)
        val badge: TextView = view.findViewById(R.id.stickerBadge)
        val busy: ProgressBar = view.findViewById(R.id.stickerBusy)
        val star: ImageView = view.findViewById(R.id.stickerStar)
        var job: Job? = null
        var boundId: Long = -1
    }

    companion object {
        private const val PAYLOAD_STATE = "state"

        fun documentUri(item: StickerGridItem): Uri =
            DocumentsContract.buildDocumentUriUsingTree(Uri.parse(item.sourceUri), item.documentId)
    }
}
