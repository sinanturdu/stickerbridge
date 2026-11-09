package dev.stickerbridge.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.stickerbridge.app.scan.DiffResult

@Dao
interface StickerDao {

    @Query(
        "SELECT id, sourceUri, documentId, mime, lastModified, isAnimated FROM sticker_file " +
            "WHERE archived = 0 ORDER BY lastModified DESC, id DESC"
    )
    suspend fun gridItems(): List<StickerGridItem>

    /** Newest stickers known to be animated (background GIF pre-conversion). */
    @Query(
        "SELECT id, sourceUri, documentId, mime, lastModified, isAnimated FROM sticker_file " +
            "WHERE archived = 0 AND isAnimated = 1 ORDER BY lastModified DESC, id DESC LIMIT :limit"
    )
    suspend fun newestAnimated(limit: Int): List<StickerGridItem>

    @Transaction
    suspend fun setAnimatedBatch(results: Map<Long, Boolean>) {
        results.forEach { (id, animated) -> setAnimated(id, animated) }
    }

    /** Visible (not archived) stickers among [ids]; order is not guaranteed. */
    @Query(
        "SELECT id, sourceUri, documentId, mime, lastModified, isAnimated FROM sticker_file " +
            "WHERE archived = 0 AND id IN (:ids)"
    )
    suspend fun gridItemsByIds(ids: List<Long>): List<StickerGridItem>

    @Query("SELECT COUNT(*) FROM sticker_file WHERE archived = 0")
    suspend fun activeCount(): Int

    @Query(
        "SELECT id, documentId, sizeBytes, lastModified, missingScanCount, archived " +
            "FROM sticker_file WHERE sourceUri = :sourceUri"
    )
    suspend fun scanRows(sourceUri: String): List<StickerScanRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(files: List<StickerFile>)

    @Query(
        "UPDATE sticker_file SET displayName = :displayName, sizeBytes = :sizeBytes, " +
            "lastModified = :lastModified, mime = :mime, isAnimated = NULL, lastSeenAt = :now, " +
            "missingScanCount = 0, archived = 0 WHERE id = :id"
    )
    suspend fun updateChanged(id: Long, displayName: String, sizeBytes: Long, lastModified: Long, mime: String, now: Long)

    @Query("UPDATE sticker_file SET lastSeenAt = :now, missingScanCount = 0, archived = 0 WHERE id IN (:ids)")
    suspend fun markSeen(ids: List<Long>, now: Long)

    @Query("UPDATE sticker_file SET missingScanCount = missingScanCount + 1 WHERE id IN (:ids)")
    suspend fun incrementMissing(ids: List<Long>)

    @Query("UPDATE sticker_file SET archived = 1 WHERE id IN (:ids)")
    suspend fun archive(ids: List<Long>)

    @Query("UPDATE sticker_file SET isAnimated = :animated WHERE id = :id")
    suspend fun setAnimated(id: Long, animated: Boolean)

    /**
     * Hides stickers of folders that are no longer connected. Rows are archived, not
     * deleted, so Recent/Favorites records keep pointing at the same ids and the
     * stickers come back (un-archived by the next scan) if the folder is reconnected.
     */
    @Query("UPDATE sticker_file SET archived = 1 WHERE archived = 0 AND sourceUri NOT IN (:sourceUris)")
    suspend fun archiveSourcesExcept(sourceUris: List<String>)

    @Query("UPDATE sticker_file SET archived = 1 WHERE archived = 0")
    suspend fun archiveAll()

    /**
     * Applies one folder's scan diff atomically. [animatedByDocId] holds header results for
     * new/changed files (read during the scan); missing entries stay unknown (null) and are
     * filled lazily when the tile becomes visible.
     */
    @Transaction
    suspend fun applyDiff(sourceUri: String, diff: DiffResult, now: Long, animatedByDocId: Map<String, Boolean> = emptyMap()) {
        diff.inserts.chunked(CHUNK).forEach { chunk ->
            insertAll(chunk.map {
                StickerFile(
                    sourceUri = sourceUri,
                    documentId = it.documentId,
                    displayName = it.displayName,
                    sizeBytes = it.sizeBytes,
                    lastModified = it.lastModified,
                    mime = it.mime,
                    isAnimated = animatedByDocId[it.documentId],
                    firstSeenAt = now,
                    lastSeenAt = now,
                )
            })
        }
        diff.changed.forEach { (id, doc) ->
            updateChanged(id, doc.displayName, doc.sizeBytes, doc.lastModified, doc.mime, now)
            animatedByDocId[doc.documentId]?.let { setAnimated(id, it) }
        }
        // SQLite on API 26 allows at most 999 bound variables per statement.
        diff.seen.chunked(CHUNK).forEach { markSeen(it, now) }
        diff.missing.chunked(CHUNK).forEach { incrementMissing(it) }
        diff.toArchive.chunked(CHUNK).forEach { archive(it) }
    }

    companion object {
        const val CHUNK = 500
    }
}
