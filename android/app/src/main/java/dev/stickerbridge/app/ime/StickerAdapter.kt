package dev.stickerbridge.app.ime

import android.net.Uri
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import dev.stickerbridge.app.R
import dev.stickerbridge.app.data.StickerGridItem
import kotlinx.coroutines.Job

/**
 * Grid of stickers. The full list holds only light rows (ids and URIs); thumbnails
 * are decoded lazily per visible cell, so ~12,000 items stay smooth.
 */
class StickerAdapter(
    private val thumbnails: ThumbnailLoader,
    private val onClick: (StickerGridItem) -> Unit,
) : RecyclerView.Adapter<StickerAdapter.Holder>() {

    private var items: List<StickerGridItem> = emptyList()

    init {
        setHasStableIds(true)
    }

    fun submit(newItems: List<StickerGridItem>) {
        items = newItems
        notifyDataSetChanged() // full reload after a scan; stable ids keep scroll position
    }

    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = items[position].id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sticker, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.boundId = item.id
        holder.job?.cancel()
        holder.itemView.setOnClickListener { onClick(item) }
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

    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.job = null
        holder.boundId = -1
        holder.image.setImageDrawable(null)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.stickerImage)
        var job: Job? = null
        var boundId: Long = -1
    }

    companion object {
        fun documentUri(item: StickerGridItem): Uri =
            DocumentsContract.buildDocumentUriUsingTree(Uri.parse(item.sourceUri), item.documentId)
    }
}
