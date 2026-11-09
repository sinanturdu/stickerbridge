package dev.stickerbridge.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * App-private library database.
 *
 * Schema history (exported to app/schemas/, checked in):
 * - v1: sticker_file
 * - v2: + sticker_usage (Recent), + sticker_favorite (Favorites, with source column for A3)
 *
 * No destructive migration: every version step has an explicit [Migrations] entry.
 */
@Database(
    entities = [StickerFile::class, StickerUsage::class, StickerFavorite::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stickerDao(): StickerDao
    abstract fun usageDao(): UsageDao
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        const val NAME = "library.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}
