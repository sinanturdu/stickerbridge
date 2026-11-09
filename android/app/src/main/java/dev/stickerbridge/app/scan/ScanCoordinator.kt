package dev.stickerbridge.app.scan

import android.content.Context
import android.os.SystemClock
import dev.stickerbridge.app.DebugLog
import dev.stickerbridge.app.convert.WebpHeader
import dev.stickerbridge.app.data.StickerDao
import dev.stickerbridge.app.data.StickerScanRow
import dev.stickerbridge.app.ime.StickerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Observable scan status for the UI. */
data class ScanState(
    /** True only while a full folder listing runs (shown as "Syncing..."). */
    val running: Boolean = false,
    val stickerCount: Int = 0,
    /** Wall-clock time of the last scan (full or quick check) that covered every folder. */
    val lastSuccessAt: Long = 0L,
    val lastResult: String = "Not scanned yet",
    /** Duration of that last successful scan or check, in ms (shown as "Synced in 1.4 s"). */
    val lastDurationMs: Long = -1L,
    /** Increments after every scan that touched the database. */
    val generation: Int = 0,
)

enum class ScanTrigger { KEYBOARD_OPENED, ONBOARDING, REFRESH_BUTTON, FOLDER_CONNECTED }

/**
 * Runs scans one at a time on a background dispatcher. What runs is decided by
 * [ScanPolicy]: ↻ and connecting a folder list everything; keyboard-open and onboarding
 * skip if the last complete scan is < 5 min old, otherwise do a quick check (folder
 * last-modified + child count) and only list files if something changed.
 *
 * The grid never waits for a scan: it always shows the cached library, and the UI shows
 * "Syncing..." only while a full listing actually runs. Timings go to the "SB-Scan" debug log.
 */
class ScanCoordinator(
    private val context: Context,
    private val dao: StickerDao,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("scan_status", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(
        ScanState(
            lastSuccessAt = prefs.getLong(KEY_LAST_SUCCESS, 0L),
            lastResult = prefs.getString(KEY_LAST_RESULT, null) ?: "Not scanned yet",
            lastDurationMs = prefs.getLong(KEY_LAST_DURATION, -1L),
        )
    )
    val state: StateFlow<ScanState> = _state.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { refreshCount() }
    }

    fun request(trigger: ScanTrigger) {
        if (mutex.isLocked) return
        scope.launch(Dispatchers.IO) {
            if (!mutex.tryLock()) return@launch
            try {
                archiveDisconnected() // also covers access revoked in system settings (0 rows normally)
                val folders = StickerFolders.connected(context)
                if (folders.isEmpty()) {
                    finish("No folder connected", success = false, changed = false)
                    return@launch
                }
                val plan = ScanPolicy.plan(
                    trigger = trigger,
                    now = System.currentTimeMillis(),
                    lastCompleteAt = prefs.getLong(KEY_LAST_SUCCESS, 0L),
                    lastFullAt = prefs.getLong(KEY_LAST_FULL, 0L),
                    haveStampsForAllFolders = folders.all { savedStamp(it.treeUri.toString()) != null },
                )
                log("trigger=$trigger plan=$plan folders=${folders.size}")
                when (plan) {
                    ScanPlan.SKIP_RECENT -> Unit
                    ScanPlan.QUICK_CHECK -> if (!quickCheck(folders)) runFullScan(folders)
                    ScanPlan.FULL -> runFullScan(folders)
                }
            } finally {
                mutex.unlock()
            }
        }
    }

    /** Hides library rows of folders that are no longer connected (records are kept). */
    fun onFoldersChanged() {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                archiveDisconnected()
                refreshCount(bumpGeneration = true)
            }
        }
    }

    /** Hides (archives) stickers whose folder is no longer connected; records are kept. */
    private suspend fun archiveDisconnected() {
        val uris = StickerFolders.connected(context).map { it.treeUri.toString() }
        if (uris.isEmpty()) dao.archiveAll() else dao.archiveSourcesExcept(uris)
    }

    /** True if every folder is unchanged since its last full listing (then nothing is listed). */
    private suspend fun quickCheck(folders: List<StickerFolder>): Boolean {
        val scanner = FolderScanner(context.contentResolver)
        val started = SystemClock.elapsedRealtime()
        for (folder in folders) {
            val t0 = SystemClock.elapsedRealtime()
            val current = scanner.quickStamp(folder.treeUri) // one-row query, no children listing
            val same = ScanPolicy.unchanged(savedStamp(folder.treeUri.toString()), current)
            log(
                "quick check ${folder.label}: ${if (same) "unchanged" else "changed"}, " +
                    "folderTime=${if (current != null) "known" else "unknown"}, ${SystemClock.elapsedRealtime() - t0} ms"
            )
            if (!same) return false
        }
        val ms = SystemClock.elapsedRealtime() - started
        finish("Up to date (quick check, $ms ms)", success = true, changed = false, durationMs = ms)
        return true
    }

    private suspend fun runFullScan(folders: List<StickerFolder>) {
        _state.update { it.copy(running = true) }
        val scanner = FolderScanner(context.contentResolver)
        val started = SystemClock.elapsedRealtime()
        var allComplete = true
        var seenTotal = 0
        var newTotal = 0
        var writtenTotal = 0
        var archivedTotal = 0
        val problems = ArrayList<String>()
        var changed = false

        for (folder in folders) {
            val source = folder.treeUri.toString()
            // Folder time is read BEFORE listing: a change during the listing shows up next time.
            val folderModified = scanner.folderLastModified(folder.treeUri)

            val t0 = SystemClock.elapsedRealtime()
            val listing = scanner.list(folder.treeUri)
            val listMs = SystemClock.elapsedRealtime() - t0
            if (!listing.complete) {
                allComplete = false
                problems += "${folder.label}: ${listing.error ?: "incomplete"}"
            }

            val t1 = SystemClock.elapsedRealtime()
            val existing = dao.scanRows(source).map { it.toExisting() }
            val diff = ScanDiff.diff(existing, listing.docs, listing.complete)
            val diffMs = SystemClock.elapsedRealtime() - t1

            // Animated flag: header (64 bytes) read ONLY for new or changed files, newest first,
            // capped so a first import stays fast; the rest stays unknown and is filled lazily
            // when its tile becomes visible. Unchanged rows are never read.
            val t2 = SystemClock.elapsedRealtime()
            val toProbe = (diff.inserts + diff.changed.map { it.second })
                .sortedByDescending { it.lastModified }
                .take(HEADER_READ_CAP)
            val flags = HashMap<String, Boolean>(toProbe.size * 2)
            for (doc in toProbe) readAnimatedFlag(folder.treeUri.toString(), doc.documentId)?.let { flags[doc.documentId] = it }
            val headerMs = SystemClock.elapsedRealtime() - t2

            // Only rows that actually changed are written; a typical rescan writes 0 rows.
            val t3 = SystemClock.elapsedRealtime()
            if (!diff.isEmpty) {
                dao.applyDiff(source, diff, System.currentTimeMillis(), flags)
                changed = true
            }
            val dbMs = SystemClock.elapsedRealtime() - t3

            if (listing.complete && folderModified > 0) {
                prefs.edit().putString(stampKey(source), FolderStamp(folderModified, listing.childCount).encode()).apply()
            }
            log(
                "full ${folder.label}: files=${listing.docs.size} children=${listing.childCount} complete=${listing.complete} | " +
                    "listing=$listMs ms (${perFile(listMs, listing.childCount)}/file), " +
                    "headers=$headerMs ms (${toProbe.size} read, ${diff.inserts.size + diff.changed.size - toProbe.size} left lazy), " +
                    "diff=$diffMs ms, db=$dbMs ms, rowsWritten=${diff.rowsWritten}"
            )
            seenTotal += listing.docs.size
            newTotal += diff.inserts.size
            writtenTotal += diff.rowsWritten
            archivedTotal += diff.toArchive.size
        }

        val ms = SystemClock.elapsedRealtime() - started
        if (allComplete) prefs.edit().putLong(KEY_LAST_FULL, System.currentTimeMillis()).apply()
        val summary = buildString {
            append(if (allComplete) "OK" else "Partial")
            append(": $seenTotal files, $newTotal new")
            if (archivedTotal > 0) append(", $archivedTotal archived")
            append(" ($ms ms)")
            if (problems.isNotEmpty()) append(" - ").append(problems.joinToString("; "))
        }
        log("full scan done: $seenTotal files, $writtenTotal rows written, $ms ms total")
        finish(summary, success = allComplete, changed = changed, durationMs = ms)
    }

    private fun perFile(ms: Long, files: Int): String =
        if (files <= 0) "-" else String.format(java.util.Locale.US, "%.2f ms", ms.toDouble() / files)

    /** 64-byte header read through the same document URI as everything else; null if unreadable. */
    private fun readAnimatedFlag(sourceUri: String, documentId: String): Boolean? = try {
        val uri = StickerAdapter.documentUri(sourceUri, documentId)
        context.contentResolver.openInputStream(uri)?.use { s ->
            val buf = ByteArray(WebpHeader.PROBE_BYTES)
            val n = s.readNBytesCompat(buf)
            WebpHeader.isAnimated(buf, n)
        }
    } catch (e: Exception) {
        null
    }

    private fun savedStamp(source: String): FolderStamp? = FolderStamp.decode(prefs.getString(stampKey(source), null))

    private fun stampKey(source: String) = "stamp_" + Integer.toHexString(source.hashCode()) + "_" + source.length

    private fun log(message: String) = DebugLog.d(context, DebugLog.SCAN, message)

    private fun java.io.InputStream.readNBytesCompat(buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private suspend fun finish(result: String, success: Boolean, changed: Boolean, durationMs: Long = -1L) {
        val now = System.currentTimeMillis()
        val editor = prefs.edit().putString(KEY_LAST_RESULT, result)
        if (success) editor.putLong(KEY_LAST_SUCCESS, now).putLong(KEY_LAST_DURATION, durationMs)
        editor.apply()
        val count = dao.activeCount()
        _state.update {
            it.copy(
                running = false,
                stickerCount = count,
                lastResult = result,
                lastSuccessAt = if (success) now else it.lastSuccessAt,
                lastDurationMs = if (success) durationMs else it.lastDurationMs,
                generation = if (changed) it.generation + 1 else it.generation,
            )
        }
    }

    private suspend fun refreshCount(bumpGeneration: Boolean = false) {
        val count = withContext(Dispatchers.IO) { dao.activeCount() }
        _state.update { it.copy(stickerCount = count, generation = if (bumpGeneration) it.generation + 1 else it.generation) }
    }

    private fun StickerScanRow.toExisting() =
        ExistingRecord(id, documentId, sizeBytes, lastModified, missingScanCount, archived)

    companion object {
        /** New/changed files whose header is read during a scan; the rest is filled lazily. */
        private const val HEADER_READ_CAP = 300
        private const val KEY_LAST_SUCCESS = "last_success_at"
        private const val KEY_LAST_RESULT = "last_result"
        private const val KEY_LAST_FULL = "last_full_at"
        private const val KEY_LAST_DURATION = "last_duration_ms"
    }
}
