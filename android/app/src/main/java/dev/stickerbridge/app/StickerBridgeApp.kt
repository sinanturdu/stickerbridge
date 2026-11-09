package dev.stickerbridge.app

import android.app.Application
import android.content.Context
import dev.stickerbridge.app.data.AppDatabase
import dev.stickerbridge.app.scan.ScanCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** Holds the process-wide library database and scan coordinator. */
class StickerBridgeApp : Application() {
    /** Lives as long as the process, so a scan finishes even if the keyboard closes. */
    val appScope = CoroutineScope(SupervisorJob())
    val database: AppDatabase by lazy { AppDatabase.create(this) }
    val scans: ScanCoordinator by lazy { ScanCoordinator(this, database.stickerDao(), appScope) }

    companion object {
        fun from(context: Context): StickerBridgeApp = context.applicationContext as StickerBridgeApp
    }
}
