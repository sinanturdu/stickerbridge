package dev.stickerbridge.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * JVM check of the v1 -> v2 migration against Room's exported v2 schema
 * (app/schemas/dev.stickerbridge.app.data.AppDatabase/2.json).
 *
 * Room's MigrationTestHelper needs instrumented tests and androidx.test, which are not
 * approved dependencies, so this test compares SQL instead: every table and index that
 * v2 adds must be created by MIGRATION_1_2 with exactly Room's statement, and the
 * migration must not touch the v1 table. The on-device upgrade is a manual check
 * (see android/README.md).
 */
class MigrationSqlTest {

    /** (tableName, createSql) for every table and index statement in the exported schema. */
    private fun schemaStatements(version: Int): List<Pair<String, String>> {
        val stream = javaClass.classLoader!!.getResourceAsStream("dev.stickerbridge.app.data.AppDatabase/$version.json")
        assertNotNull("exported schema $version.json not on the test classpath", stream)
        val json = stream!!.bufferedReader().use { it.readText() }
        val token = Regex("\"(tableName|createSql)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        var table = ""
        val out = ArrayList<Pair<String, String>>()
        for (m in token.findAll(json)) {
            val value = m.groupValues[2].replace("\\\"", "\"")
            if (m.groupValues[1] == "tableName") table = value
            else out += table to value.replace("\${TABLE_NAME}", table)
        }
        return out
    }

    @Test
    fun migration1to2CreatesExactlyTheNewTablesAndIndicesOfSchema2() {
        val v2 = schemaStatements(2)
        val expected = v2.filter { it.first in setOf("sticker_usage", "sticker_favorite") }.map { it.second }.toSet()
        assertEquals(5, expected.size) // 2 tables + 3 indices
        assertEquals(expected, Migrations.MIGRATION_1_2_SQL.toSet())
    }

    @Test
    fun migration1to2DoesNotTouchTheV1Table() {
        assertFalse(Migrations.MIGRATION_1_2_SQL.any { it.contains("sticker_file") })
        // The v1 table is unchanged in v2 (same statement as shipped in A1).
        val stickerFile = schemaStatements(2).first { it.first == "sticker_file" && it.second.startsWith("CREATE TABLE") }.second
        assertEquals(
            "CREATE TABLE IF NOT EXISTS `sticker_file` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceUri` TEXT NOT NULL, " +
                "`documentId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `lastModified` INTEGER NOT NULL, " +
                "`mime` TEXT NOT NULL, `isAnimated` INTEGER, `firstSeenAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, " +
                "`missingScanCount` INTEGER NOT NULL DEFAULT 0, `archived` INTEGER NOT NULL DEFAULT 0)",
            stickerFile,
        )
    }

    @Test
    fun everyVersionStepHasAMigration() {
        val covered = Migrations.ALL.map { it.startVersion to it.endVersion }
        assertEquals(listOf(1 to 2), covered)
    }
}
