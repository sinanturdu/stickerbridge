package dev.stickerbridge.app.ime

/** Where the cursor is, for apps whose behavior differs per field (Instagram). */
enum class FieldKind { STORY, DM, UNKNOWN, OTHER_APP }

/**
 * The EditorInfo values that identify a field. [hint] is only a weak signal (localized,
 * may change); it is never typed text.
 */
data class FieldSignature(
    val packageName: String,
    val fieldId: Int,
    val inputType: Int,
    val imeOptions: Int,
    val hint: String? = null,
)

/**
 * Known Instagram field signatures, kept in one place so device reports can be added easily
 * (the keyboard logs every field's signature with tag SB-Field).
 *
 * Recorded on Samsung, Instagram 448.0.0.52.84 (E7):
 * - DM composer: fieldId=2131441646, inputType=0x000ac001, imeOptions=0x02000001.
 * - Story text tool: not recorded yet.
 */
object InstagramFields {
    const val PACKAGE = "com.instagram.android"

    data class Known(val kind: FieldKind, val fieldId: Int?, val inputType: Int, val imeOptions: Int)

    val SIGNATURES: List<Known> = listOf(
        Known(FieldKind.DM, fieldId = 2131441646, inputType = 0x000ac001, imeOptions = 0x02000001),
        // Add the Story text tool signature here once it is reported from a device.
    )

    /** Lower-case fragments of DM composer hints ("Message...", "Mesaj..."); weak signal only. */
    val DM_HINT_WORDS = listOf("message", "mesaj", "nachricht", "mensaje", "messaggio")
}

/** Pure classification (unit-tested). */
object FieldClassifier {

    fun classify(sig: FieldSignature, known: List<InstagramFields.Known> = InstagramFields.SIGNATURES): FieldKind {
        if (sig.packageName != InstagramFields.PACKAGE) return FieldKind.OTHER_APP

        // 1) Exact match on field id + input type + options (strongest).
        known.firstOrNull { it.fieldId != null && it.fieldId == sig.fieldId && it.inputType == sig.inputType && it.imeOptions == sig.imeOptions }
            ?.let { return it.kind }
        // 2) Same field id (resource ids are stable within an app version).
        known.firstOrNull { it.fieldId != null && it.fieldId == sig.fieldId }?.let { return it.kind }
        // 3) Same inputType + imeOptions, confirmed by the (weak) hint text.
        val hint = sig.hint?.lowercase().orEmpty()
        known.firstOrNull { it.inputType == sig.inputType && it.imeOptions == sig.imeOptions }?.let { k ->
            if (k.kind == FieldKind.DM && InstagramFields.DM_HINT_WORDS.any { hint.contains(it) }) return FieldKind.DM
        }
        return FieldKind.UNKNOWN
    }

    /** One log line (tag SB-Field). Hint truncated to 24 chars; no typed text exists here. */
    fun describe(sig: FieldSignature, kind: FieldKind, mimes: List<String>): String =
        "pkg=${sig.packageName} fieldId=${sig.fieldId} inputType=0x%08x imeOptions=0x%08x mimes=%s hint=\"%s\" -> %s".format(
            sig.inputType, sig.imeOptions, mimes, (sig.hint ?: "").take(24), kind,
        )
}
