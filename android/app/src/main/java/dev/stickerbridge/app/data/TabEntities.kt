package dev.stickerbridge.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One successful insert of a sticker (commitContent returned true).
 * No foreign key on purpose: records survive when the sticker is archived or its
 * folder is disconnected, and show up again when the file reappears.
 */
@Entity(
    tableName = "sticker_usage",
    indices = [Index(value = ["usedAt"]), Index(value = ["stickerId"])],
)
data class StickerUsage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stickerId: Long,
    val usedAt: Long,
)

/**
 * A favorite. [source] is LOCAL (favorited in StickerBridge) now; WHATSAPP is
 * reserved for A3 (linked-device favorites), so both can be merged without another
 * migration. Like [StickerUsage], it has no foreign key on purpose.
 */
@Entity(
    tableName = "sticker_favorite",
    primaryKeys = ["stickerId", "source"],
    indices = [Index(value = ["favoritedAt"])],
)
data class StickerFavorite(
    val stickerId: Long,
    val source: String = FavoriteSource.LOCAL,
    val favoritedAt: Long,
)

object FavoriteSource {
    const val LOCAL = "LOCAL"
    const val WHATSAPP = "WHATSAPP"
}
