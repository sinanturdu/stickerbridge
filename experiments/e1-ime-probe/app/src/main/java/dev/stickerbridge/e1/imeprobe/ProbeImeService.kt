package dev.stickerbridge.e1.imeprobe

import android.content.ClipDescription
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File

/**
 * "IME Probe" keyboard for experiment E1.
 *
 * It reports which rich-content MIME types the focused field advertises and tries to
 * insert bundled synthetic images with commitContent.
 *
 * PRIVACY: this IME never reads or logs typed text. It does not call getTextBeforeCursor,
 * getTextAfterCursor, getExtractedText or read EditorInfo.initialSurroundingText. It only
 * looks at EditorInfo metadata (package, field id, input type, options, hint, MIME types).
 */
class ProbeImeService : InputMethodService() {

    private enum class TestAsset(val label: String, val rawRes: Int, val fileName: String, val mime: String) {
        PNG("PNG", R.raw.test_png, "test_png.png", "image/png"),
        WEBP("WEBP", R.raw.test_webp, "test_webp.webp", "image/webp"),
        ANIMATED_WEBP("Animated WEBP", R.raw.test_webp_animated, "test_webp_animated.webp", "image/webp"),
        GIF("GIF", R.raw.test_gif, "test_gif.gif", "image/gif"),
    }

    private var infoView: TextView? = null
    private var resultView: TextView? = null

    override fun onCreateInputView(): View {
        val ctx: Context = this
        val pad = dp(8)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF263238.toInt())
        }

        infoView = TextView(ctx).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
            maxLines = 9
            isVerticalScrollBarEnabled = true
            movementMethod = ScrollingMovementMethod()
            text = "IME Probe — focus a text field"
        }
        root.addView(infoView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        TestAsset.entries.forEachIndexed { i, asset ->
            val b = Button(ctx).apply {
                text = asset.label
                isAllCaps = false
                setOnClickListener { commitAsset(asset) }
            }
            (if (i < 2) row1 else row2).addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(row1)
        root.addView(row2)

        root.addView(Button(ctx).apply {
            text = "Next keyboard"
            isAllCaps = false
            setOnClickListener { switchKeyboard() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        resultView = TextView(ctx).apply {
            setTextColor(0xFFFFEB3B.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
            maxLines = 3
        }
        root.addView(resultView)

        // Keep buttons above the gesture/navigation bar on edge-to-edge devices.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(pad, pad, pad, pad + nav.bottom)
            insets
        }
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        resultView?.text = ""
        if (info == null) {
            infoView?.text = "EditorInfo: null"
            ProbeLog.append(this, "START_INPUT_VIEW editorInfo=null")
            return
        }
        val mimes = advertisedMimeTypes(info)
        val summary = buildString {
            appendLine("package     : ${info.packageName}")
            appendLine("fieldId     : ${info.fieldId}  fieldName: ${info.fieldName ?: "-"}")
            appendLine("inputType   : 0x%08x (%s)".format(info.inputType, describeInputType(info.inputType)))
            appendLine("imeOptions  : 0x%08x (action=%s)".format(info.imeOptions, describeAction(info.imeOptions)))
            appendLine("hintText    : ${info.hintText ?: "-"}")
            append("contentMime : ${if (mimes.isEmpty()) "null/empty" else mimes.joinToString(", ")}")
        }
        infoView?.text = summary
        infoView?.scrollTo(0, 0)
        ProbeLog.append(
            this,
            "START_INPUT_VIEW restarting=$restarting " +
                summary.lines().joinToString(" | ") { it.replace(Regex("\\s+:"), ":").trim() },
        )
    }

    private fun commitAsset(asset: TestAsset) {
        val editorInfo = currentInputEditorInfo
        val ic = currentInputConnection
        val mimes = if (editorInfo != null) advertisedMimeTypes(editorInfo) else emptyList()
        val match = mimes.firstOrNull { ClipDescription.compareMimeTypes(asset.mime, it) }
        val advertised = if (match != null) "yes (matches $match)" else "no"

        if (editorInfo == null || ic == null) {
            report("COMMIT ${asset.label} mime=${asset.mime} advertised=$advertised result=false (no input connection)")
            return
        }

        val result: String = try {
            val file = ensureAssetFile(asset)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val content = InputContentInfoCompat(uri, ClipDescription(asset.label, arrayOf(asset.mime)), null)
            val ok = InputConnectionCompat.commitContent(
                ic,
                editorInfo,
                content,
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null,
            )
            ok.toString()
        } catch (e: Exception) {
            "false (exception ${e.javaClass.simpleName}: ${e.message})"
        }
        report("COMMIT ${asset.label} mime=${asset.mime} advertised=$advertised target=${editorInfo.packageName} result=$result")
    }

    private fun report(line: String) {
        resultView?.text = line
        ProbeLog.append(this, line)
    }

    /** Copies the bundled raw resource to cacheDir/stickers/ (served by FileProvider). */
    private fun ensureAssetFile(asset: TestAsset): File {
        val dir = File(cacheDir, "stickers").apply { mkdirs() }
        val file = File(dir, asset.fileName)
        if (!file.exists() || file.length() == 0L) {
            resources.openRawResource(asset.rawRes).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file
    }

    private fun switchKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val switched = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToNextInputMethod(false)
            } else {
                val token = window?.window?.attributes?.token
                @Suppress("DEPRECATION")
                token != null && imm.switchToNextInputMethod(token, false)
            }
        } catch (e: Exception) {
            false
        }
        if (!switched) imm.showInputMethodPicker()
    }

    private fun advertisedMimeTypes(info: EditorInfo): List<String> =
        EditorInfoCompat.getContentMimeTypes(info).filterNotNull().toList()

    private fun describeInputType(type: Int): String {
        val cls = when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> "TEXT"
            InputType.TYPE_CLASS_NUMBER -> "NUMBER"
            InputType.TYPE_CLASS_PHONE -> "PHONE"
            InputType.TYPE_CLASS_DATETIME -> "DATETIME"
            InputType.TYPE_NULL -> "NULL"
            else -> "?"
        }
        val variation = type and InputType.TYPE_MASK_VARIATION
        val multi = if (type and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) " multiLine" else ""
        return "class=$cls variation=0x%x$multi".format(variation)
    }

    private fun describeAction(options: Int): String = when (options and EditorInfo.IME_MASK_ACTION) {
        EditorInfo.IME_ACTION_UNSPECIFIED -> "UNSPECIFIED"
        EditorInfo.IME_ACTION_NONE -> "NONE"
        EditorInfo.IME_ACTION_GO -> "GO"
        EditorInfo.IME_ACTION_SEARCH -> "SEARCH"
        EditorInfo.IME_ACTION_SEND -> "SEND"
        EditorInfo.IME_ACTION_NEXT -> "NEXT"
        EditorInfo.IME_ACTION_DONE -> "DONE"
        EditorInfo.IME_ACTION_PREVIOUS -> "PREVIOUS"
        else -> "?"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
