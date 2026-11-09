package dev.stickerbridge.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit schema migrations. The SQL must match Room's exported schema exactly
 * (app/schemas/dev.stickerbridge.app.data.AppDatabase/<version>.json);
 * MigrationSqlTest checks this on the JVM.
 */
object Migrations {

    /** v1 -> v2: adds the Recent (sticker_usage) and Favorites (sticker_favorite) tables. */
    val MIGRATION_1_2_SQL: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `sticker_usage` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `stickerId` INTEGER NOT NULL, `usedAt` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_sticker_usage_usedAt` ON `sticker_usage` (`usedAt`)",
        "CREATE INDEX IF NOT EXISTS `index_sticker_usage_stickerId` ON `sticker_usage` (`stickerId`)",
        "CREATE TABLE IF NOT EXISTS `sticker_favorite` (`stickerId` INTEGER NOT NULL, `source` TEXT NOT NULL, `favoritedAt` INTEGER NOT NULL, PRIMARY KEY(`stickerId`, `source`))",
        "CREATE INDEX IF NOT EXISTS `index_sticker_favorite_favoritedAt` ON `sticker_favorite` (`favoritedAt`)",
    )

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_1_2_SQL.forEach { db.execSQL(it) }
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
