package dev.stickerbridge.app.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDiffTest {

    private fun doc(id: String, modified: Long = 1000L, size: Long = 10L) =
        ScannedDoc(documentId = id, displayName = "$id.webp", mime = "image/webp", sizeBytes = size, lastModified = modified)

    private fun rec(id: Long, docId: String, modified: Long = 1000L, size: Long = 10L, missing: Int = 0, archived: Boolean = false) =
        ExistingRecord(id = id, documentId = docId, sizeBytes = size, lastModified = modified, missingScanCount = missing, archived = archived)

    @Test
    fun newDocumentsAreInserted() {
        val result = ScanDiff.diff(existing = emptyList(), scanned = listOf(doc("a"), doc("b")), complete = true)
        assertEquals(listOf("a", "b"), result.inserts.map { it.documentId })
        assertTrue(result.changed.isEmpty())
        assertTrue(result.missing.isEmpty())
    }

    @Test
    fun unchangedDocumentIsSeen() {
        val result = ScanDiff.diff(listOf(rec(1, "a")), listOf(doc("a")), complete = true)
        assertEquals(listOf(1L), result.seen)
        assertTrue(result.inserts.isEmpty())
        assertTrue(result.changed.isEmpty())
    }

    @Test
    fun changedLastModifiedOrSizeIsUpdated() {
        val result = ScanDiff.diff(
            existing = listOf(rec(1, "a", modified = 1000), rec(2, "b", size = 10)),
            scanned = listOf(doc("a", modified = 2000), doc("b", size = 99)),
            complete = true,
        )
        assertEquals(listOf(1L, 2L), result.changed.map { it.first })
        assertEquals(2000L, result.changed.first().second.lastModified)
        assertTrue(result.seen.isEmpty())
    }

    @Test
    fun missingInCompleteScanIncrementsButDoesNotArchiveEarly() {
        val result = ScanDiff.diff(listOf(rec(1, "a", missing = 0), rec(2, "b", missing = 1)), emptyList(), complete = true)
        assertEquals(listOf(1L, 2L), result.missing)
        assertTrue(result.toArchive.isEmpty())
    }

    @Test
    fun archivedAfterThirdConsecutiveCompleteMiss() {
        val result = ScanDiff.diff(listOf(rec(1, "a", missing = 2)), emptyList(), complete = true)
        assertEquals(listOf(1L), result.missing)
        assertEquals(listOf(1L), result.toArchive)
    }

    @Test
    fun partialScanNeverCountsMissing() {
        val result = ScanDiff.diff(
            existing = listOf(rec(1, "a"), rec(2, "b", missing = 2)),
            scanned = listOf(doc("a"), doc("c")),
            complete = false,
        )
        assertTrue(result.missing.isEmpty())
        assertTrue(result.toArchive.isEmpty())
        assertEquals(listOf(1L), result.seen)
        assertEquals(listOf("c"), result.inserts.map { it.documentId })
    }

    @Test
    fun failedScanChangesNothing() {
        val result = ScanDiff.diff(listOf(rec(1, "a", missing = 2)), emptyList(), complete = false)
        assertTrue(result.isEmpty)
    }

    @Test
    fun reappearingArchivedFileIsSeenAgain() {
        val result = ScanDiff.diff(listOf(rec(1, "a", missing = 3, archived = true)), listOf(doc("a")), complete = true)
        assertEquals(listOf(1L), result.seen)
        assertTrue(result.inserts.isEmpty())
        assertTrue(result.missing.isEmpty())
    }

    @Test
    fun reappearingFileWithNewTimestampIsChanged() {
        val result = ScanDiff.diff(listOf(rec(1, "a", missing = 1)), listOf(doc("a", modified = 5000)), complete = true)
        assertEquals(listOf(1L), result.changed.map { it.first })
        assertTrue(result.missing.isEmpty())
    }

    @Test
    fun archivedFileStillMissingIsNotCountedAgain() {
        val result = ScanDiff.diff(listOf(rec(1, "a", missing = 3, archived = true)), emptyList(), complete = true)
        assertTrue(result.missing.isEmpty())
        assertTrue(result.toArchive.isEmpty())
    }

    @Test
    fun duplicateDocumentIdsInScanAreIgnored() {
        val result = ScanDiff.diff(emptyList(), listOf(doc("a"), doc("a")), complete = true)
        assertEquals(1, result.inserts.size)
    }
}
