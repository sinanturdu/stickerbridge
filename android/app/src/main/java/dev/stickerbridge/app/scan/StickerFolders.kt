package dev.stickerbridge.app.scan

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** A connected SAF folder (persisted read permission on a tree URI). */
data class StickerFolder(val treeUri: Uri, val label: String, val looksLikeStickerFolder: Boolean)

/**
 * Connected folders are exactly the persisted read grants this app holds.
 * Nothing else is stored, so revoking access in system settings disconnects too.
 */
object StickerFolders {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    const val WHATSAPP_DOC_ID = "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers"
    const val WHATSAPP_BUSINESS_DOC_ID = "primary:Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Stickers"

    fun initialUri(documentId: String): Uri =
        DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, documentId)

    fun pickerIntent(initialDocumentId: String): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri(initialDocumentId))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

    fun connected(context: Context): List<StickerFolder> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
            .map { describe(it.uri) }

    fun connect(context: Context, treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun disconnect(context: Context, treeUri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun describe(treeUri: Uri): StickerFolder {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrDefault("")
        val label = when {
            docId.contains("com.whatsapp.w4b") -> "WhatsApp Business"
            docId.contains("com.whatsapp") -> "WhatsApp"
            else -> docId.substringAfter(':').ifEmpty { treeUri.toString() }
        }
        val looksRight = docId.endsWith("Stickers") && docId.contains("com.whatsapp")
        return StickerFolder(treeUri, label, looksRight)
    }
}
