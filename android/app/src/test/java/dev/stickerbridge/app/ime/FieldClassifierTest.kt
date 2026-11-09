package dev.stickerbridge.app.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldClassifierTest {

    private val ig = InstagramFields.PACKAGE
    private val dmSig = FieldSignature(ig, fieldId = 2131441646, inputType = 0x000ac001, imeOptions = 0x02000001, hint = "Message…")

    @Test
    fun knownDmSignatureIsDm() = assertEquals(FieldKind.DM, FieldClassifier.classify(dmSig))

    @Test
    fun sameFieldIdIsEnoughEvenIfFlagsDiffer() =
        assertEquals(FieldKind.DM, FieldClassifier.classify(dmSig.copy(imeOptions = 0x00000006)))

    @Test
    fun matchingFlagsNeedTheWeakHintToCountAsDm() {
        val otherId = dmSig.copy(fieldId = 12345)
        assertEquals(FieldKind.DM, FieldClassifier.classify(otherId.copy(hint = "Mesaj gönder…")))
        assertEquals(FieldKind.UNKNOWN, FieldClassifier.classify(otherId.copy(hint = "Aa")))
        assertEquals(FieldKind.UNKNOWN, FieldClassifier.classify(otherId.copy(hint = null)))
    }

    @Test
    fun unrecordedInstagramFieldIsUnknown() {
        // Story text tool: signature not recorded yet -> UNKNOWN (sent as still image).
        val story = FieldSignature(ig, fieldId = 2131430000, inputType = 0x00020001, imeOptions = 0x00000006, hint = null)
        assertEquals(FieldKind.UNKNOWN, FieldClassifier.classify(story))
    }

    @Test
    fun recordedStorySignatureWins() {
        val story = FieldSignature(ig, fieldId = 777, inputType = 0x00020001, imeOptions = 0x00000006)
        val known = InstagramFields.SIGNATURES + InstagramFields.Known(FieldKind.STORY, 777, 0x00020001, 0x00000006)
        assertEquals(FieldKind.STORY, FieldClassifier.classify(story, known))
    }

    @Test
    fun otherAppsAreNotClassified() =
        assertEquals(FieldKind.OTHER_APP, FieldClassifier.classify(dmSig.copy(packageName = "com.whatsapp")))

    @Test
    fun logLineTruncatesHintAndShowsHexValues() {
        val line = FieldClassifier.describe(dmSig.copy(hint = "A very long localized hint text that goes on"), FieldKind.DM, listOf("image/gif"))
        assertTrue(line.contains("inputType=0x000ac001"))
        assertTrue(line.contains("imeOptions=0x02000001"))
        assertTrue(line.contains("hint=\"A very long localized hi\""))
        assertTrue(line.endsWith("-> DM"))
    }
}
