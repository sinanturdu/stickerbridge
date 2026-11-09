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
        "SELECT id, sourceUri, documentId, mime, lastModified FROM sticker_file " +
            "WHERE archived = 0 ORDER BY lastModified DESC, id DESC"
    )
    suspend fun gridItems(): List<StickerGridItem>

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

    @Query("DELETE FROM sticker_file WHERE sourceUri = :sourceUri")
    suspend fun deleteSource(sourceUri: String)

    @Query("DELETE FROM sticker_file WHERE sourceUri NOT IN (:sourceUris)")
    suspend fun deleteSourcesExcept(sourceUris: List<String>)

    @Query("DELETE FROM sticker_file")
    suspend fun deleteAll()

    /** Applies one folder's scan diff atomically. */
    @Transaction
    suspend fun applyDiff(sourceUri: String, diff: DiffResult, now: Long) {
        diff.inserts.chunked(CHUNK).forEach { chunk ->
            insertAll(chunk.map {
                StickerFile(
                    sourceUri = sourceUri,
                    documentId = it.documentId,
                    displayName = it.displayName,
                    sizeBytes = it.sizeBytes,
                    lastModified = it.lastModified,
                    mime = it.mime,
                    firstSeenAt = now,
                    lastSeenAt = now,
                )
            })
        }
        diff.changed.forEach { (id, doc) ->
            updateChanged(id, doc.displayName, doc.sizeBytes, doc.lastModified, doc.mime, now)
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
