package dev.stickerbridge.app

import android.app.Application
import android.content.Context
import dev.stickerbridge.app.convert.GifPreconverter
import dev.stickerbridge.app.data.AppDatabase
import dev.stickerbridge.app.data.StickerLibrary
import dev.stickerbridge.app.scan.ScanCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** Holds the process-wide library database and scan coordinator. */
class StickerBridgeApp : Application() {
    /** Lives as long as the process, so a scan finishes even if the keyboard closes. */
    val appScope = CoroutineScope(SupervisorJob())
    val database: AppDatabase by lazy { AppDatabase.create(this) }
    val library: StickerLibrary by lazy { StickerLibrary(database) }
    val scans: ScanCoordinator by lazy {
        ScanCoordinator(this, database.stickerDao(), appScope).also { coordinator ->
            // After each successful scan/check, pre-convert likely GIFs so taps send instantly.
            coordinator.onScanFinished = { gifPreconverter.run() }
        }
    }
    val gifPreconverter: GifPreconverter by lazy { GifPreconverter(this, library, database.stickerDao(), appScope) }

    companion object {
        fun from(context: Context): StickerBridgeApp = context.applicationContext as StickerBridgeApp
    }
}
