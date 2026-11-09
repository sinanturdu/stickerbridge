package dev.stickerbridge.app.scan

/** One document as listed by the folder provider in a scan. */
data class ScannedDoc(
    val documentId: String,
    val displayName: String,
    val mime: String,
    val sizeBytes: Long,
    /** Last-modified time reported by the source (ms). "Recently seen", never "added to WhatsApp". */
    val lastModified: Long,
)

/** The part of a stored sticker_file row that the diff needs. */
data class ExistingRecord(
    val id: Long,
    val documentId: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val missingScanCount: Int,
    val archived: Boolean,
)

/** What has to change in the library after one scan of one folder. */
data class DiffResult(
    /** Documents not in the library yet. */
    val inserts: List<ScannedDoc>,
    /** Known documents whose size or lastModified changed (id to new values). */
    val changed: List<Pair<Long, ScannedDoc>>,
    /**
     * Known documents seen unchanged that need a write: they were missing in an earlier scan
     * or archived (reappearing). Reset missing count, un-archive, update lastSeenAt.
     * Healthy unchanged documents are NOT listed, so a typical rescan writes nothing.
     */
    val seen: List<Long>,
    /** Known documents not seen in a COMPLETE scan: missingScanCount + 1. */
    val missing: List<Long>,
    /** Subset of [missing] that reaches the archive threshold with this scan. */
    val toArchive: List<Long>,
) {
    val isEmpty: Boolean
        get() = inserts.isEmpty() && changed.isEmpty() && seen.isEmpty() && missing.isEmpty()

    /** Rows the database update will touch (archived rows are also in [missing]). */
    val rowsWritten: Int
        get() = inserts.size + changed.size + seen.size + missing.size
}

/**
 * Pure diff between the library and one scan of one folder.
 *
 * Rules:
 * - New documents are inserted; changed ones (size or lastModified) are updated.
 * - A document seen again after being missing or archived gets its missing count reset
 *   and is un-archived. Unchanged healthy documents produce no write at all.
 * - Documents not seen are counted as missing ONLY if the scan was complete.
 *   A failed or partial scan never increments missing counts.
 * - A document is archived after [archiveAfter] consecutive complete scans without it.
 */
object ScanDiff {
    const val DEFAULT_ARCHIVE_AFTER = 3

    fun diff(
        existing: List<ExistingRecord>,
        scanned: List<ScannedDoc>,
        complete: Boolean,
        archiveAfter: Int = DEFAULT_ARCHIVE_AFTER,
    ): DiffResult {
        val byDocId = existing.associateBy { it.documentId }
        val seenDocIds = HashSet<String>(scanned.size * 2)

        val inserts = ArrayList<ScannedDoc>()
        val changed = ArrayList<Pair<Long, ScannedDoc>>()
        val seen = ArrayList<Long>()

        for (doc in scanned) {
            if (!seenDocIds.add(doc.documentId)) continue // provider listed it twice
            val old = byDocId[doc.documentId]
            when {
                old == null -> inserts += doc
                old.lastModified != doc.lastModified || old.sizeBytes != doc.sizeBytes -> changed += old.id to doc
                old.missingScanCount > 0 || old.archived -> seen += old.id
                else -> Unit // unchanged and healthy: no write
            }
        }

        val missing = ArrayList<Long>()
        val toArchive = ArrayList<Long>()
        if (complete) {
            for (rec in existing) {
                if (rec.archived || rec.documentId in seenDocIds) continue
                missing += rec.id
                if (rec.missingScanCount + 1 >= archiveAfter) toArchive += rec.id
            }
        }
        return DiffResult(inserts, changed, seen, missing, toArchive)
    }
}
