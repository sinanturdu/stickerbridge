package dev.stickerbridge.app.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class SafUrisTest {

    private val tree =
        "content://com.android.externalstorage.documents/tree/" +
            "primary%3AAndroid%2Fmedia%2Fcom.whatsapp%2FWhatsApp%2FMedia%2FWhatsApp%20Stickers"

    @Test
    fun treeDocumentIdIsDecodedIncludingSpaces() {
        assertEquals("com.android.externalstorage.documents", SafUris.authority(tree))
        assertEquals("primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers", SafUris.treeDocumentId(tree))
    }

    @Test
    fun documentUriForStickerFileNamePattern() {
        val docId = "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers/STK-20251103-WA0007.webp"
        assertEquals(
            "content://com.android.externalstorage.documents/tree/" +
                "primary%3AAndroid%2Fmedia%2Fcom.whatsapp%2FWhatsApp%2FMedia%2FWhatsApp%20Stickers" +
                "/document/" +
                "primary%3AAndroid%2Fmedia%2Fcom.whatsapp%2FWhatsApp%2FMedia%2FWhatsApp%20Stickers%2FSTK-20251103-WA0007.webp",
            SafUris.documentUri(tree, docId),
        )
    }

    @Test
    fun businessFolderWithSpacesInSeveralSegments() {
        val businessTree = "content://com.android.externalstorage.documents/tree/" +
            SafUris.encode("primary:Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Stickers")
        val docId = "primary:Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Stickers/STK-20240101-WA0123.webp"
        val uri = SafUris.documentUri(businessTree, docId)
        assertEquals(
            "primary:Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Stickers",
            SafUris.treeDocumentId(businessTree),
        )
        assertEquals(docId, SafUris.decode(uri.substringAfter("/document/")))
        assertEquals(false, uri.contains(' '))
    }

    @Test
    fun encodeMatchesAndroidUriEncodeRules() {
        assertEquals("a-b_c.d~e!f'g(h)i*j", SafUris.encode("a-b_c.d~e!f'g(h)i*j")) // unreserved kept
        assertEquals("a%20b%3Ac%2Fd%25e%2Bf", SafUris.encode("a b:c/d%e+f"))
        assertEquals("%C3%BC", SafUris.encode("ü")) // UTF-8
        assertEquals("a b:c/d%e+f ü", SafUris.decode(SafUris.encode("a b:c/d%e+f ü")))
    }

    @Test
    fun logSuffixKeepsOnlyTheLast24Characters() {
        val docId = "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers/STK-20251103-WA0007.webp"
        assertEquals("ers/STK-20251103-WA0007.webp".takeLast(24), SafUris.suffix(docId))
        assertEquals(24, SafUris.suffix(docId).length)
    }
}
