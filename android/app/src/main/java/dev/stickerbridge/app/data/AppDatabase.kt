package dev.stickerbridge.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** App-private library database. */
@Database(entities = [StickerFile::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stickerDao(): StickerDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "library.db")
                .fallbackToDestructiveMigration() // A1: no migrations yet; the library is rebuilt by a scan
                .build()
    }
}
