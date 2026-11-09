package dev.stickerbridge.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One sticker file seen in a connected source folder.
 *
 * Time fields are kept strictly apart:
 * - [lastModified]: reported by the source folder. Shown as "recently seen",
 *   NEVER as "added to WhatsApp".
 * - [firstSeenAt] / [lastSeenAt]: when StickerBridge itself observed the file.
 */
@Entity(
    tableName = "sticker_file",
    indices = [
        Index(value = ["sourceUri", "documentId"], unique = true),
        Index(value = ["archived", "lastModified"]),
    ],
)
data class StickerFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Tree URI of the connected folder (SAF). */
    val sourceUri: String,
    /** DocumentsContract document id inside that tree. */
    val documentId: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val mime: String,
    /** null until known. */
    val isAnimated: Boolean? = null,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    @ColumnInfo(defaultValue = "0") val missingScanCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
)

/** Light row for the keyboard grid (12k of these stay small in memory). */
data class StickerGridItem(
    val id: Long,
    val sourceUri: String,
    val documentId: String,
    val mime: String,
    val lastModified: Long,
)

/** Row used by the scan diff. */
data class StickerScanRow(
    val id: Long,
    val documentId: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val missingScanCount: Int,
    val archived: Boolean,
)
