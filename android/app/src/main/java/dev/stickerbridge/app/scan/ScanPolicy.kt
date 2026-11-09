package dev.stickerbridge.app.scan

/** Cheap fingerprint of a folder: its own last-modified time and its number of children. */
data class FolderStamp(val lastModified: Long, val childCount: Int) {
    fun encode(): String = "$lastModified:$childCount"

    companion object {
        fun decode(s: String?): FolderStamp? {
            val parts = s?.split(':') ?: return null
            if (parts.size != 2) return null
            val m = parts[0].toLongOrNull() ?: return null
            val c = parts[1].toIntOrNull() ?: return null
            return FolderStamp(m, c)
        }
    }
}

enum class ScanPlan {
    /** A complete scan finished moments ago: do nothing. */
    SKIP_RECENT,
    /** Compare each folder's stamp with the one saved after the last full scan. */
    QUICK_CHECK,
    /** List every file. */
    FULL,
}

/**
 * Pure decision rules for when to scan (unit-tested).
 *
 * - ↻ and connecting a folder always do a FULL scan.
 * - Keyboard-open / onboarding: SKIP if the last complete scan (full or quick) is < 5 min old;
 *   otherwise a QUICK_CHECK, unless there is no saved stamp for a folder or the last full
 *   listing is older than 24 h (then FULL).
 * - A quick check counts as "up to date" only if every folder's stamp equals the saved one.
 *   It cannot see a file whose own time changed without being added/removed (WhatsApp
 *   re-sending a cached sticker), so a full listing still happens at least every 24 h and on ↻.
 */
object ScanPolicy {
    const val RECENT_MS = 5L * 60 * 1000
    const val MAX_QUICK_AGE_MS = 24L * 60 * 60 * 1000

    fun plan(
        trigger: ScanTrigger,
        now: Long,
        lastCompleteAt: Long,
        lastFullAt: Long,
        haveStampsForAllFolders: Boolean,
    ): ScanPlan {
        if (trigger == ScanTrigger.REFRESH_BUTTON || trigger == ScanTrigger.FOLDER_CONNECTED) return ScanPlan.FULL
        if (lastCompleteAt > 0 && now >= lastCompleteAt && now - lastCompleteAt < RECENT_MS) return ScanPlan.SKIP_RECENT
        if (lastFullAt <= 0 || now < lastFullAt || now - lastFullAt > MAX_QUICK_AGE_MS) return ScanPlan.FULL
        if (!haveStampsForAllFolders) return ScanPlan.FULL
        return ScanPlan.QUICK_CHECK
    }

    /** True if the folder looks unchanged since the last full scan. Unknown values never match. */
    fun unchanged(saved: FolderStamp?, current: FolderStamp?): Boolean =
        saved != null && current != null && current.lastModified > 0 && saved == current
}
