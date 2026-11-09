package dev.stickerbridge.app.ime

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.format.DateUtils
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.stickerbridge.app.MainActivity
import dev.stickerbridge.app.R
import dev.stickerbridge.app.StickerBridgeApp
import dev.stickerbridge.app.data.StickerGridItem
import dev.stickerbridge.app.scan.ScanState
import dev.stickerbridge.app.scan.ScanTrigger
import dev.stickerbridge.app.scan.StickerFolders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "StickerBridge" keyboard: a sticker grid, no letter keys.
 *
 * PRIVACY: never reads or logs typed text. It does not call getTextBeforeCursor,
 * getTextAfterCursor, getExtractedText or read EditorInfo.initialSurroundingText.
 * It only looks at the field's advertised content MIME types.
 */
class StickerKeyboardService : InputMethodService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app by lazy { StickerBridgeApp.from(this) }

    private var root: View? = null
    private lateinit var grid: RecyclerView
    private lateinit var message: View
    private lateinit var messageText: TextView
    private lateinit var messageButton: Button
    private lateinit var syncText: TextView
    private lateinit var refresh: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var adapter: StickerAdapter
    private lateinit var thumbnails: ThumbnailLoader

    private var fieldAccepts = false
    private var lastGeneration = -1
    private var loadJob: Job? = null
    private var stateJob: Job? = null
    private var insertJob: Job? = null

    override fun onCreateInputView(): View {
        val themed = ContextThemeWrapper(this, R.style.Theme_StickerBridge_Keyboard)
        val view = LayoutInflater.from(themed).inflate(R.layout.keyboard_view, null)
        root = view

        grid = view.findViewById(R.id.kbGrid)
        message = view.findViewById(R.id.kbMessage)
        messageText = view.findViewById(R.id.kbMessageText)
        messageButton = view.findViewById(R.id.kbMessageButton)
        syncText = view.findViewById(R.id.kbSyncText)
        refresh = view.findViewById(R.id.kbRefresh)
        spinner = view.findViewById(R.id.kbSpinner)

        val cellPx = resources.displayMetrics.widthPixels / COLUMNS
        thumbnails = ThumbnailLoader(contentResolver, scope, cellPx)
        adapter = StickerAdapter(thumbnails) { insert(it) }
        grid.layoutManager = GridLayoutManager(themed, COLUMNS)
        grid.adapter = adapter
        grid.setHasFixedSize(true)
        grid.setItemViewCacheSize(COLUMNS * 4)

        view.findViewById<View>(R.id.kbBack).setOnClickListener { switchBack() }
        refresh.setOnClickListener { app.scans.request(ScanTrigger.REFRESH_BUTTON) }
        messageButton.setOnClickListener { openApp() }

        // Keep the grid above the gesture/navigation bar on edge-to-edge devices.
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(0, 0, 0, nav.bottom)
            insets
        }

        stateJob?.cancel()
        stateJob = scope.launch { app.scans.state.collect { renderScanState(it) } }
        lastGeneration = -1
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (root == null) return
        fieldAccepts = StickerInserter.fieldAcceptsImages(info)
        when {
            !fieldAccepts -> showMessage(getString(R.string.kb_field_no_stickers), withButton = false)
            StickerFolders.connected(this).isEmpty() -> showMessage(getString(R.string.kb_not_connected), withButton = true)
            else -> {
                reloadGrid()
                app.scans.request(ScanTrigger.KEYBOARD_OPENED)
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        insertJob?.cancel()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        scope.cancel()
        if (::thumbnails.isInitialized) thumbnails.clear()
        super.onDestroy()
    }

    private fun renderScanState(s: ScanState) {
        spinner.visibility = if (s.running) View.VISIBLE else View.GONE
        refresh.visibility = if (s.running) View.INVISIBLE else View.VISIBLE
        syncText.text = when {
            s.running -> getString(R.string.kb_syncing)
            s.lastSuccessAt > 0 -> getString(
                R.string.kb_synced,
                DateUtils.getRelativeTimeSpanString(s.lastSuccessAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
            else -> getString(R.string.kb_never_synced)
        }
        if (s.generation != lastGeneration) {
            lastGeneration = s.generation
            if (fieldAccepts && StickerFolders.connected(this).isNotEmpty()) reloadGrid()
        } else if (!s.running && fieldAccepts && adapter.itemCount == 0 && StickerFolders.connected(this).isNotEmpty()) {
            showMessage(getString(R.string.kb_empty), withButton = false)
        }
    }

    private fun reloadGrid() {
        loadJob?.cancel()
        loadJob = scope.launch {
            val items = withContext(Dispatchers.IO) { app.database.stickerDao().gridItems() }
            adapter.submit(items)
            if (!fieldAccepts) return@launch
            if (items.isEmpty()) {
                if (!app.scans.state.value.running) showMessage(getString(R.string.kb_empty), withButton = false)
            } else {
                message.visibility = View.GONE
                grid.visibility = View.VISIBLE
            }
        }
    }

    private fun showMessage(text: String, withButton: Boolean) {
        grid.visibility = View.GONE
        message.visibility = View.VISIBLE
        messageText.text = text
        messageButton.visibility = if (withButton) View.VISIBLE else View.GONE
    }

    private fun insert(item: StickerGridItem) {
        insertJob?.cancel()
        insertJob = scope.launch {
            val file = try {
                withContext(Dispatchers.IO) {
                    StickerInserter.copyToCache(this@StickerKeyboardService, StickerAdapter.documentUri(item), item.id)
                }
            } catch (e: Exception) {
                toast(R.string.kb_insert_error)
                return@launch
            }
            // Cheap header check (first 64 bytes) of the real file; corrects the stored flag.
            val animated = withContext(Dispatchers.IO) { StickerInserter.isAnimatedWebp(file) }
            adapter.setAnimated(item.id, animated)
            if (item.isAnimated != animated) {
                app.appScope.launch(Dispatchers.IO) { app.database.stickerDao().setAnimated(item.id, animated) }
            }

            var sendFile = file
            var mime = StickerInserter.WEBP_MIME
            if (animated && StickerInserter.fieldAcceptsGif(currentInputEditorInfo)) {
                adapter.setBusy(item.id, true)
                val gif = try {
                    withContext(Dispatchers.Default) { StickerInserter.gifFor(this@StickerKeyboardService, item, file) }
                } finally {
                    adapter.setBusy(item.id, false)
                }
                if (gif != null) {
                    sendFile = gif
                    mime = StickerInserter.GIF_MIME
                }
                // gif == null: conversion failed -> send the WEBP (Instagram shows the first frame).
            }

            val ic = currentInputConnection
            val info = currentInputEditorInfo
            val ok = ic != null && info != null &&
                runCatching { StickerInserter.commit(this@StickerKeyboardService, ic, info, sendFile, mime) }.getOrDefault(false)
            if (!ok) toast(R.string.kb_insert_failed)
        }
    }

    private fun switchBack() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val switched = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToPreviousInputMethod()
            } else {
                val token = window?.window?.attributes?.token
                @Suppress("DEPRECATION")
                token != null && imm.switchToLastInputMethod(token)
            }
        } catch (e: Exception) {
            false
        }
        if (!switched) imm.showInputMethodPicker()
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    companion object {
        private const val COLUMNS = 4
    }
}
