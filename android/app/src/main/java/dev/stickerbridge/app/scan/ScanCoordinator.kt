package dev.stickerbridge.app.scan

import android.content.Context
import android.os.SystemClock
import dev.stickerbridge.app.data.StickerDao
import dev.stickerbridge.app.data.StickerScanRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Observable scan status for the UI. */
data class ScanState(
    val running: Boolean = false,
    val stickerCount: Int = 0,
    /** Wall-clock time of the last scan in which every folder was listed completely. */
    val lastSuccessAt: Long = 0L,
    val lastResult: String = "Not scanned yet",
    /** Increments after every scan that touched the database. */
    val generation: Int = 0,
)

enum class ScanTrigger { KEYBOARD_OPENED, REFRESH_BUTTON, ONBOARDING }

/**
 * Runs scans one at a time on a background dispatcher.
 *
 * - KEYBOARD_OPENED is throttled to at most one scan per 60 s.
 * - REFRESH_BUTTON and ONBOARDING always scan (unless one is already running).
 * Every scan lists all connected folders fully; the diff makes it incremental.
 */
class ScanCoordinator(
    private val context: Context,
    private val dao: StickerDao,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("scan_status", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var lastKeyboardScanStart = 0L // elapsedRealtime

    private val _state = MutableStateFlow(
        ScanState(
            lastSuccessAt = prefs.getLong(KEY_LAST_SUCCESS, 0L),
            lastResult = prefs.getString(KEY_LAST_RESULT, null) ?: "Not scanned yet",
        )
    )
    val state: StateFlow<ScanState> = _state.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { refreshCount() }
    }

    fun request(trigger: ScanTrigger) {
        if (trigger == ScanTrigger.KEYBOARD_OPENED) {
            val now = SystemClock.elapsedRealtime()
            if (lastKeyboardScanStart != 0L && now - lastKeyboardScanStart < KEYBOARD_THROTTLE_MS) return
            lastKeyboardScanStart = now
        }
        if (mutex.isLocked) return
        scope.launch(Dispatchers.IO) {
            if (!mutex.tryLock()) return@launch
            try {
                runScan()
            } finally {
                mutex.unlock()
            }
        }
    }

    /** Removes library rows of folders that are no longer connected. */
    fun onFoldersChanged() {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val uris = StickerFolders.connected(context).map { it.treeUri.toString() }
                if (uris.isEmpty()) dao.deleteAll() else dao.deleteSourcesExcept(uris)
                refreshCount(bumpGeneration = true)
            }
        }
    }

    private suspend fun runScan() {
        _state.value = _state.value.copy(running = true)
        val folders = StickerFolders.connected(context)
        if (folders.isEmpty()) {
            finish("No folder connected", success = false, changed = false)
            return
        }
        val scanner = FolderScanner(context.contentResolver)
        val started = SystemClock.elapsedRealtime()
        var allComplete = true
        var seenTotal = 0
        var newTotal = 0
        var archivedTotal = 0
        val problems = ArrayList<String>()
        var changed = false

        for (folder in folders) {
            val listing = scanner.list(folder.treeUri)
            if (!listing.complete) {
                allComplete = false
                problems += "${folder.label}: ${listing.error ?: "incomplete"}"
            }
            val source = folder.treeUri.toString()
            val existing = dao.scanRows(source).map { it.toExisting() }
            val diff = ScanDiff.diff(existing, listing.docs, listing.complete)
            if (!diff.isEmpty) {
                dao.applyDiff(source, diff, System.currentTimeMillis())
                changed = changed || diff.inserts.isNotEmpty() || diff.changed.isNotEmpty() || diff.toArchive.isNotEmpty()
            }
            seenTotal += listing.docs.size
            newTotal += diff.inserts.size
            archivedTotal += diff.toArchive.size
        }

        val ms = SystemClock.elapsedRealtime() - started
        val summary = buildString {
            append(if (allComplete) "OK" else "Partial")
            append(": $seenTotal files, $newTotal new")
            if (archivedTotal > 0) append(", $archivedTotal archived")
            append(" (${ms} ms)")
            if (problems.isNotEmpty()) append(" - ").append(problems.joinToString("; "))
        }
        finish(summary, success = allComplete, changed = changed)
    }

    private suspend fun finish(result: String, success: Boolean, changed: Boolean) {
        val now = System.currentTimeMillis()
        val editor = prefs.edit().putString(KEY_LAST_RESULT, result)
        if (success) editor.putLong(KEY_LAST_SUCCESS, now)
        editor.apply()
        val count = dao.activeCount()
        _state.value = _state.value.copy(
            running = false,
            stickerCount = count,
            lastResult = result,
            lastSuccessAt = if (success) now else _state.value.lastSuccessAt,
            generation = if (changed) _state.value.generation + 1 else _state.value.generation,
        )
    }

    private suspend fun refreshCount(bumpGeneration: Boolean = false) {
        val count = withContext(Dispatchers.IO) { dao.activeCount() }
        val s = _state.value
        _state.value = s.copy(stickerCount = count, generation = if (bumpGeneration) s.generation + 1 else s.generation)
    }

    private fun StickerScanRow.toExisting() =
        ExistingRecord(id, documentId, sizeBytes, lastModified, missingScanCount, archived)

    companion object {
        const val KEYBOARD_THROTTLE_MS = 60_000L
        private const val KEY_LAST_SUCCESS = "last_success_at"
        private const val KEY_LAST_RESULT = "last_result"
    }
}
