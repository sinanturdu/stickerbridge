package dev.stickerbridge.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(usage: StickerUsage)

    @Query("SELECT * FROM sticker_usage ORDER BY usedAt DESC, id DESC")
    suspend fun allNewestFirst(): List<StickerUsage>

    @Query("DELETE FROM sticker_usage WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("DELETE FROM sticker_usage WHERE stickerId = :stickerId")
    suspend fun deleteForSticker(stickerId: Long)
}

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: StickerFavorite)

    @Query("DELETE FROM sticker_favorite WHERE stickerId = :stickerId AND source = :source")
    suspend fun delete(stickerId: Long, source: String)

    @Query("SELECT * FROM sticker_favorite")
    suspend fun all(): List<StickerFavorite>
}
