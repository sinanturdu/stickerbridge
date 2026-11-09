package dev.stickerbridge.app.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldClassifierTest {

    private val ig = InstagramFields.PACKAGE
    private val dmMimes = listOf("image/png", "image/jpeg", "image/gif")
    private val storyMimes = listOf("image/*")

    /** Recorded on device, Instagram 448.0.0.52.84. */
    private val dmSig = FieldSignature(ig, fieldId = 2131441646, inputType = 0x000ac001, imeOptions = 0x02000001, hint = "Message…", mimes = dmMimes)
    private val storySig = FieldSignature(ig, fieldId = 2131443961, inputType = 0x0002c001, imeOptions = 0x40000006, hint = "", mimes = storyMimes)

    // ---- real signatures ----

    @Test
    fun recordedDmSignatureIsDm() = assertEquals(FieldKind.DM, FieldClassifier.classify(dmSig))

    @Test
    fun recordedStorySignatureIsStory() = assertEquals(FieldKind.STORY, FieldClassifier.classify(storySig))

    @Test
    fun knownFieldIdWinsEvenIfFlagsChange() {
        assertEquals(FieldKind.DM, FieldClassifier.classify(dmSig.copy(imeOptions = 0x00000006, mimes = emptyList())))
        assertEquals(FieldKind.STORY, FieldClassifier.classify(storySig.copy(inputType = 0x00000001, mimes = emptyList())))
    }

    // ---- fallbacks for other Instagram versions (resource ids changed) ----

    @Test
    fun fallbackExactPngJpegGifIsDm() {
        val newVersionDm = dmSig.copy(fieldId = 999, hint = null)
        assertEquals(FieldKind.DM, FieldClassifier.classify(newVersionDm))
        assertEquals(FieldKind.DM, FieldClassifier.classify(newVersionDm.copy(mimes = listOf("image/gif", "image/png", "image/jpeg"))))
    }

    @Test
    fun fallbackDmNeedsExactlyThoseThreeTypes() {
        val base = dmSig.copy(fieldId = 999)
        assertEquals(FieldKind.UNKNOWN, FieldClassifier.classify(base.copy(mimes = listOf("image/png", "image/jpeg"))))
        assertEquals(FieldKind.UNKNOWN, FieldClassifier.classify(base.copy(mimes = dmMimes + "image/webp")))
    }

    @Test
    fun fallbackWildcardWithActionDoneAndEmptyHintIsStory() {
        val newVersionStory = storySig.copy(fieldId = 888)
        assertEquals(FieldKind.STORY, FieldClassifier.classify(newVersionStory))
        assertEquals(FieldKind.STORY, FieldClassifier.classify(newVersionStory.copy(hint = null)))
        assertEquals(FieldKind.STORY, FieldClassifier.classify(newVersionStory.copy(imeOptions = 0x00000006))) // other flags irrelevant
    }

    @Test
    fun fallbackStoryNeedsWildcardActionDoneAndEmptyHint() {
        val base = storySig.copy(fieldId = 888)
        assertEquals(FieldKind.UNKNOWN, FieldClassifier.classify(base.copy(hint = "Add a caption"))) // hint present
        assertEquals(FieldKind.UNKNOWN, FieldClassifier.classify(base.copy(imeOptions = 0x40000004))) // action SEND
        assertEquals(FieldKind.UNKNOWN, FieldClassifier.classify(base.copy(mimes = listOf("image/gif")))) // no wildcard
    }

    @Test
    fun everythingElseInInstagramIsUnknown() {
        val search = FieldSignature(ig, fieldId = 777, inputType = 0x00000001, imeOptions = 0x00000003, hint = "Search", mimes = emptyList())
        assertEquals(FieldKind.UNKNOWN, FieldClassifier.classify(search))
    }

    @Test
    fun otherAppsAreNotClassified() =
        assertEquals(FieldKind.OTHER_APP, FieldClassifier.classify(dmSig.copy(packageName = "com.whatsapp")))

    @Test
    fun logLineShowsSignatureAndTruncatedHint() {
        val line = FieldClassifier.describe(storySig.copy(hint = "A very long localized hint text that goes on"), FieldKind.STORY)
        assertTrue(line.contains("fieldId=2131443961"))
        assertTrue(line.contains("inputType=0x0002c001"))
        assertTrue(line.contains("imeOptions=0x40000006"))
        assertTrue(line.contains("mimes=[image/*]"))
        assertTrue(line.contains("hint=\"A very long localized hi\""))
        assertTrue(line.endsWith("-> STORY"))
    }
}
