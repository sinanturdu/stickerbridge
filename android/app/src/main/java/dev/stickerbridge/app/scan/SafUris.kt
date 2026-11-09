package dev.stickerbridge.app.scan

/**
 * Pure (JVM-testable) version of DocumentsContract.buildDocumentUriUsingTree for the
 * stored (treeUri, documentId) pairs. Thumbnails, header checks, inserts and conversion all
 * open stickers through this one function, so they always use the same document URI.
 *
 * Matches the platform: content://<authority>/tree/<enc(treeDocId)>/document/<enc(documentId)>,
 * where enc is android.net.Uri.encode (everything except A-Z a-z 0-9 _ - ! . ~ ' ( ) * is
 * percent-encoded as UTF-8; a space becomes %20, ':' %3A, '/' %2F).
 */
object SafUris {
    private const val ALLOWED = "_-!.~'()*"

    fun documentUri(treeUri: String, documentId: String): String {
        val authority = authority(treeUri) ?: throw IllegalArgumentException("not a content tree URI")
        val treeDocId = treeDocumentId(treeUri) ?: throw IllegalArgumentException("no tree document id")
        return "content://$authority/tree/${encode(treeDocId)}/document/${encode(documentId)}"
    }

    fun authority(uri: String): String? {
        if (!uri.startsWith("content://")) return null
        val rest = uri.removePrefix("content://")
        val end = rest.indexOf('/')
        return if (end <= 0) null else rest.substring(0, end)
    }

    /** Decoded document id of the tree (path segment after "tree"). */
    fun treeDocumentId(treeUri: String): String? {
        val authority = authority(treeUri) ?: return null
        val path = treeUri.removePrefix("content://$authority").substringBefore('?').substringBefore('#')
        val segments = path.split('/').filter { it.isNotEmpty() }
        val i = segments.indexOf("tree")
        if (i < 0 || i + 1 >= segments.size) return null
        return decode(segments[i + 1])
    }

    fun encode(s: String): String {
        val out = StringBuilder(s.length + 16)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in ALLOWED)) out.append(ch)
            else out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
        return out.toString()
    }

    fun decode(s: String): String {
        val bytes = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '%' && i + 2 < s.length) {
                bytes.write(s.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                bytes.write(ch.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return bytes.toString("UTF-8")
    }

    /** Last [n] characters of a document id, for logs (file name pattern only). */
    fun suffix(documentId: String, n: Int = 24): String = if (documentId.length <= n) documentId else documentId.takeLast(n)
}
