package dev.stickerbridge.app.debug

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.ContentInfo
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import dev.stickerbridge.app.DebugLog

/**
 * DEBUG ONLY. "Receive test": a text field that accepts rich content from ANY keyboard and
 * reports exactly how it was delivered (URI, linkUri, description, extras, provider columns,
 * first bytes, source keyboard, call order). Used to compare Samsung Keyboard's GIF delivery
 * (accepted by Instagram DMs) with ours (rejected). Log tag: SB-Recv.
 *
 * Content stays on the phone: only metadata is shown/logged; the stream is opened just to read
 * its first 6 bytes.
 */
class ReceiveTestActivity : Activity() {

    private lateinit var report: TextView
    private lateinit var useReceiveListener: CheckBox
    private val log = StringBuilder()
    private var t0 = SystemClock.elapsedRealtime()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DebugLog.isDebuggable(this)) {
            finish()
            return
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        root.addView(TextView(this).apply {
            text = "Receive test (debug)\nSwitch to any keyboard and insert a GIF/sticker into the field below."
            textSize = 15f
        })

        // Advertising mode: Instagram DM composer (exact values seen on device) or wildcards.
        val modes = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        val instagramButton = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "Instagram-like" }
        val wildcardButton = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "Wildcard" }
        modes.addView(instagramButton)
        modes.addView(wildcardButton)
        modes.check(instagramButton.id)
        modes.setOnCheckedChangeListener { _, checkedId ->
            instagramLike = checkedId == instagramButton.id
            rebuildField(root)
        }
        root.addView(modes)

        useReceiveListener = CheckBox(this).apply {
            text = "Deliver keyboard content via OnReceiveContentListener (Android 12+) instead of the commitContent wrapper"
            isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            setOnCheckedChangeListener { _, _ -> rebuildField(root) }
        }
        root.addView(useReceiveListener)

        root.addView(RichEditText(this).also { field = it }, fieldParams())

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        buttons.addView(Button(this).apply {
            text = "Clear"
            setOnClickListener { log.setLength(0); report.text = ""; t0 = SystemClock.elapsedRealtime() }
        })
        buttons.addView(Button(this).apply {
            text = "Copy report"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("StickerBridge receive test", log.toString()))
                Toast.makeText(this@ReceiveTestActivity, "Report copied", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(buttons)

        report = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
        }
        root.addView(ScrollView(this).apply { addView(report) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        line("ready; current keyboard=${currentKeyboard()}")
    }

    private var field: EditText? = null

    /** true: mimic Instagram's DM composer exactly; false: the image and video wildcard types. */
    private var instagramLike = true

    private fun mimes(): Array<String> = if (instagramLike) INSTAGRAM_MIMES else WILDCARD_MIMES

    private fun modeName(): String {
        val adv = if (instagramLike) "Instagram-like [${INSTAGRAM_MIMES.joinToString()}]" else "wildcard [${WILDCARD_MIMES.joinToString()}]"
        val path = if (::useReceiveListener.isInitialized && useReceiveListener.isChecked) "OnReceiveContentListener" else "commitContent wrapper"
        return "advertising=$adv, path=$path"
    }

    private fun fieldParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun rebuildField(root: LinearLayout) {
        val index = root.indexOfChild(field)
        root.removeView(field)
        root.addView(RichEditText(this).also { field = it }, index, fieldParams())
        line("field rebuilt; ${modeName()}")
    }

    // ---------------------------------------------------------------------------------------

    /** Plain platform EditText (not AppCompat) so the delivery path is exactly the one chosen. */
    private inner class RichEditText(context: Context) : EditText(context) {
        init {
            hint = "Insert rich content here"
            minLines = 3
            if (instagramLike) {
                setRawInputType(INSTAGRAM_INPUT_TYPE)
                imeOptions = INSTAGRAM_IME_OPTIONS
            } else {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setOnReceiveContentListener(mimes()) { _, payload -> onReceive(payload) }
            }
        }

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            val ic = super.onCreateInputConnection(outAttrs) ?: return null
            if (instagramLike) {
                // Exactly what Instagram's DM composer reported on device.
                outAttrs.inputType = INSTAGRAM_INPUT_TYPE
                outAttrs.imeOptions = INSTAGRAM_IME_OPTIONS
            }
            EditorInfoCompat.setContentMimeTypes(outAttrs, mimes())
            line(
                "onCreateInputConnection: ${modeName()} inputType=0x%08x imeOptions=0x%08x keyboard=%s"
                    .format(outAttrs.inputType, outAttrs.imeOptions, currentKeyboard())
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && useReceiveListener.isChecked) {
                // Platform path: TextView turns commitContent into performReceiveContent.
                return ic
            }
            return InputConnectionCompat.createWrapper(ic, outAttrs) { info, flags, opts -> onCommit(info, flags, opts) }
        }
    }

    // ---- commitContent path -----------------------------------------------------------------

    private fun onCommit(info: InputContentInfoCompat, flags: Int, opts: Bundle?): Boolean {
        val start = SystemClock.elapsedRealtime()
        line("=== commitContent received; ${modeName()} ===")
        line("source keyboard=${currentKeyboard()}")
        line("flags=0x${Integer.toHexString(flags)} grantRead=${flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0}")
        line("opts keys=${opts?.keySet()?.joinToString() ?: "none"}")
        line("contentUri: ${describeUri(info.contentUri)}")
        line("linkUri (full): ${info.linkUri}")
        describeDescription(info.description)
        if (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0) {
            try {
                info.requestPermission()
                line("requestPermission: ok after ${SystemClock.elapsedRealtime() - start} ms")
            } catch (e: Exception) {
                line("requestPermission: FAILED ${e.javaClass.simpleName}")
            }
        }
        inspect(info.contentUri)
        line("=== done in ${SystemClock.elapsedRealtime() - start} ms (returning true) ===")
        return true
    }

    // ---- OnReceiveContentListener path (API 31+) --------------------------------------------

    @RequiresApi(Build.VERSION_CODES.S)
    private fun onReceive(payload: ContentInfo): ContentInfo? {
        val start = SystemClock.elapsedRealtime()
        line("=== onReceiveContent; ${modeName()} ===")
        line("source keyboard=${currentKeyboard()}")
        line("source=${sourceName(payload.source)} flags=0x${Integer.toHexString(payload.flags)}")
        line("linkUri (full): ${payload.linkUri}")
        line("extras keys=${payload.extras?.keySet()?.joinToString() ?: "none"}")
        val clip = payload.clip
        describeDescription(clip.description)
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            line("item[$i]: uri=${item.uri?.let { describeUri(it) } ?: "none"} text=${item.text?.toString()?.take(40) ?: "none"}")
            item.uri?.let { inspect(it) }
        }
        line("=== done in ${SystemClock.elapsedRealtime() - start} ms (consumed) ===")
        return null // consumed
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun sourceName(source: Int) = when (source) {
        ContentInfo.SOURCE_INPUT_METHOD -> "INPUT_METHOD"
        ContentInfo.SOURCE_CLIPBOARD -> "CLIPBOARD"
        ContentInfo.SOURCE_DRAG_AND_DROP -> "DRAG_AND_DROP"
        ContentInfo.SOURCE_AUTOFILL -> "AUTOFILL"
        ContentInfo.SOURCE_PROCESS_TEXT -> "PROCESS_TEXT"
        else -> "APP($source)"
    }

    // ---- shared inspection ------------------------------------------------------------------

    private fun describeUri(uri: Uri): String {
        val path = uri.encodedPath ?: ""
        return "scheme=${uri.scheme} authority=${uri.authority} path=…${path.takeLast(40)}"
    }

    private fun describeDescription(d: ClipDescription) {
        val mimes = (0 until d.mimeTypeCount).map { d.getMimeType(it) }
        line("description: label=\"${d.label}\" mimes=$mimes")
        line("description extras keys=${d.extras?.keySet()?.joinToString() ?: "none"}")
    }

    private fun inspect(uri: Uri) {
        val t = SystemClock.elapsedRealtime()
        line("getType=${runCatching { contentResolver.getType(uri) }.getOrElse { "error ${it.javaClass.simpleName}" }}")
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                line("query: ${c.count} row(s), columns=${c.columnNames.joinToString()}")
                if (c.moveToFirst()) {
                    for (i in 0 until c.columnCount) {
                        val v = runCatching {
                            when (c.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> "null"
                                android.database.Cursor.FIELD_TYPE_BLOB -> "blob(${c.getBlob(i).size} bytes)"
                                else -> c.getString(i)
                            }
                        }.getOrElse { "?" }
                        line("  ${c.getColumnName(i)} = ${v.take(80)}")
                    }
                }
            } ?: line("query: null cursor")
        } catch (e: Exception) {
            line("query: error ${e.javaClass.simpleName}")
        }
        try {
            contentResolver.openInputStream(uri)?.use { s ->
                val head = ByteArray(6)
                var n = 0
                while (n < 6) {
                    val r = s.read(head, n, 6 - n)
                    if (r < 0) break
                    n += r
                }
                line("stream: opened, first $n bytes=${head.take(n).joinToString(" ") { "%02X".format(it) }} (\"${String(head, 0, n, Charsets.US_ASCII).filter { it.code in 32..126 }}\")")
            } ?: line("stream: null")
        } catch (e: Exception) {
            line("stream: open FAILED ${e.javaClass.simpleName}")
        }
        line("inspect took ${SystemClock.elapsedRealtime() - t} ms")
    }

    private fun currentKeyboard(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "unknown"

    private fun line(text: String) {
        val stamped = "+${SystemClock.elapsedRealtime() - t0} ms  $text"
        Log.d(TAG, stamped)
        log.append(stamped).append('\n')
        if (::report.isInitialized) {
            report.text = log
            (report.parent as? View)?.post { (report.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    companion object {
        const val TAG = "SB-Recv"
        private val WILDCARD_MIMES = arrayOf("image/*", "video/*")
        /** Instagram DM composer on device: contentMimeTypes, inputType 0x000ac001, imeOptions 0x02000001. */
        private val INSTAGRAM_MIMES = arrayOf("image/png", "image/jpeg", "image/gif")
        // TEXT | CAP_SENTENCES | AUTO_CORRECT | MULTI_LINE | NO_SUGGESTIONS
        private const val INSTAGRAM_INPUT_TYPE = 0x000ac001
        // IME_FLAG_NO_FULLSCREEN | IME_ACTION_NONE
        private const val INSTAGRAM_IME_OPTIONS = 0x02000001
    }
}
