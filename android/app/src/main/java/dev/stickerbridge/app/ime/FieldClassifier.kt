package dev.stickerbridge.app.ime

/** Where the cursor is, for apps whose behavior differs per field (Instagram). */
enum class FieldKind { STORY, DM, UNKNOWN, OTHER_APP }

/**
 * The EditorInfo values that identify a field. [hint] is only a weak signal (localized,
 * may change); it is never typed text. [mimes] are the advertised content MIME types.
 */
data class FieldSignature(
    val packageName: String,
    val fieldId: Int,
    val inputType: Int,
    val imeOptions: Int,
    val hint: String? = null,
    val mimes: List<String> = emptyList(),
)

/**
 * Known Instagram field signatures, kept in one place so device reports can be added easily
 * (the keyboard logs every field's signature with tag SB-Field).
 *
 * Recorded on Samsung, Instagram 448.0.0.52.84 (E7):
 * - DM composer:     fieldId=2131441646 inputType=0x000ac001 imeOptions=0x02000001
 *                    mimes=[image/png, image/jpeg, image/gif]
 * - Story text tool: fieldId=2131443961 inputType=0x0002c001 imeOptions=0x40000006
 *                    mimes=[image wildcard] hint=""
 */
object InstagramFields {
    const val PACKAGE = "com.instagram.android"

    data class Known(val kind: FieldKind, val fieldId: Int?, val inputType: Int, val imeOptions: Int)

    val SIGNATURES: List<Known> = listOf(
        Known(FieldKind.DM, fieldId = 2131441646, inputType = 0x000ac001, imeOptions = 0x02000001),
        Known(FieldKind.STORY, fieldId = 2131443961, inputType = 0x0002c001, imeOptions = 0x40000006),
    )

    /** DM composer advertises exactly these types (order-independent). */
    val DM_MIMES: Set<String> = setOf("image/png", "image/jpeg", "image/gif")

    /** Story text tool advertises the image wildcard. */
    const val STORY_MIME = "image/*"

    /** EditorInfo.IME_MASK_ACTION / IME_ACTION_DONE (plain ints: this file stays JVM-testable). */
    const val IME_MASK_ACTION = 0x000000ff
    const val IME_ACTION_DONE = 0x00000006
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

        // 3) Fallbacks for other Instagram versions, where resource ids change:
        //    exactly png + jpeg + gif -> DM;
        //    image wildcard + action DONE + empty hint -> STORY;
        //    anything else -> UNKNOWN (sent as a still image).
        val mimes = sig.mimes.map { it.lowercase() }
        if (mimes.toSet() == InstagramFields.DM_MIMES && mimes.size == InstagramFields.DM_MIMES.size) return FieldKind.DM
        val actionDone = (sig.imeOptions and InstagramFields.IME_MASK_ACTION) == InstagramFields.IME_ACTION_DONE
        if (InstagramFields.STORY_MIME in mimes && actionDone && sig.hint.isNullOrEmpty()) return FieldKind.STORY
        return FieldKind.UNKNOWN
    }

    /** One log line (tag SB-Field). Hint truncated to 24 chars; no typed text exists here. */
    fun describe(sig: FieldSignature, kind: FieldKind, mimes: List<String> = sig.mimes): String =
        "pkg=${sig.packageName} fieldId=${sig.fieldId} inputType=0x%08x imeOptions=0x%08x mimes=%s hint=\"%s\" -> %s".format(
            sig.inputType, sig.imeOptions, mimes, (sig.hint ?: "").take(24), kind,
        )
}
