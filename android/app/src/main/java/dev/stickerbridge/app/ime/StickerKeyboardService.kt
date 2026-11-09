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
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.stickerbridge.app.AppSettings
import dev.stickerbridge.app.DebugLog
import dev.stickerbridge.app.debug.GifSizeTest
import dev.stickerbridge.app.scan.SafUris
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

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

    private lateinit var title: TextView
    private lateinit var hint: TextView
    private lateinit var notice: TextView
    private lateinit var tabViews: Map<Tab, TextView>
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    /** Tab shown now; null until the first load decides the default. */
    private var currentTab: Tab? = null

    private var fieldAccepts = false
    private var hasDebugTab = false
    private var lastGeneration = -1
    private var loadJob: Job? = null
    private var stateJob: Job? = null
    private var insertJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // Outgoing sticker files stay readable for the whole session; old ones go at the next start.
        app.appScope.launch(Dispatchers.IO) { StickerInserter.cleanupOldOutgoing(this@StickerKeyboardService) }
    }

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
        title = view.findViewById(R.id.kbTitle)
        hint = view.findViewById(R.id.kbHint)
        notice = view.findViewById(R.id.kbNotice)
        notice.setOnClickListener { notice.visibility = View.GONE }
        tabViews = mapOf(
            Tab.RECENT to view.findViewById(R.id.kbTabRecent),
            Tab.FAVORITES to view.findViewById(R.id.kbTabFavorites),
            Tab.ALL to view.findViewById(R.id.kbTabAll),
            Tab.DEBUG to view.findViewById(R.id.kbTabDebug),
        )
        // The Debug tab is temporary and never remembered.
        tabViews.forEach { (tab, tv) -> tv.setOnClickListener { selectTab(tab, remember = tab != Tab.DEBUG) } }
        currentTab = savedTab()

        val cellPx = resources.displayMetrics.widthPixels / COLUMNS
        thumbnails = ThumbnailLoader(contentResolver, scope, cellPx)
        adapter = StickerAdapter(
            thumbnails,
            onClick = { insert(it) },
            onLongClick = { item, anchor -> showMenu(item, anchor) },
            onAnimatedLearned = { id, animated -> rememberAnimatedFlag(id, animated) },
        )
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
        adapter.animatePreviews = AppSettings.animatePreviews(this)
        fieldAccepts = StickerInserter.fieldAcceptsImages(info)
        if (info != null) {
            // Field signature for classification reports (no typed text; hint truncated).
            val kind = classifyField(info)
            val mimes = androidx.core.view.inputmethod.EditorInfoCompat.getContentMimeTypes(info).filterNotNull()
            DebugLog.d(this, DebugLog.FIELD, FieldClassifier.describe(signatureOf(info), kind, mimes))
        }
        when {
            !fieldAccepts -> showMessage(getString(R.string.kb_field_no_stickers), withButton = false)
            StickerFolders.connected(this).isEmpty() -> showMessage(getString(R.string.kb_not_connected), withButton = true)
            else -> {
                reloadGrid()
                app.scans.request(ScanTrigger.KEYBOARD_OPENED)
            }
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (root != null) adapter.resumeVisible(grid)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        insertJob?.cancel()
        if (root != null) adapter.stopAll() // keyboard hidden: no animations in the background
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
            s.lastSuccessAt > 0 && s.lastDurationMs >= 0 -> getString(
                R.string.kb_synced_in,
                String.format(java.util.Locale.US, "%.1f s", s.lastDurationMs / 1000.0),
                DateUtils.getRelativeTimeSpanString(s.lastSuccessAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
            s.lastSuccessAt > 0 -> getString(
                R.string.kb_synced,
                DateUtils.getRelativeTimeSpanString(s.lastSuccessAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
            else -> getString(R.string.kb_never_synced)
        }
        if (s.generation != lastGeneration) {
            lastGeneration = s.generation
            if (fieldAccepts && StickerFolders.connected(this).isNotEmpty()) reloadGrid()
        } else if (!s.running && fieldAccepts && currentTab == Tab.ALL && adapter.itemCount == 0 &&
            StickerFolders.connected(this).isNotEmpty()
        ) {
            // Only the All tab says "no stickers found"; Recent/Favorites keep their own empty text.
            showMessage(getString(R.string.kb_empty), withButton = false)
        }
    }

    private fun selectTab(tab: Tab, remember: Boolean) {
        if (remember) prefs.edit().putString(KEY_TAB, tab.name).apply()
        if (currentTab != tab) {
            currentTab = tab
            grid.scrollToPosition(0)
        }
        if (fieldAccepts && StickerFolders.connected(this).isNotEmpty()) reloadGrid()
    }

    private fun savedTab(): Tab? = prefs.getString(KEY_TAB, null)
        ?.let { runCatching { Tab.valueOf(it) }.getOrNull() }
        ?.takeIf { it != Tab.DEBUG }

    private fun renderTabs(tab: Tab) {
        tabViews.forEach { (t, tv) -> tv.isSelected = t == tab }
        hint.visibility = if (tab == Tab.ALL) View.VISIBLE else View.GONE
        title.setText(
            when (tab) {
                Tab.RECENT -> R.string.kb_title_recent
                Tab.FAVORITES -> R.string.kb_title_favorites
                Tab.ALL -> R.string.kb_title
                Tab.DEBUG -> R.string.kb_title_debug
            }
        )
    }

    private fun reloadGrid() {
        loadJob?.cancel()
        loadJob = scope.launch {
            val library = app.library
            val (tab, items, favorites) = withContext(Dispatchers.IO) {
                // No tab chosen manually: Recent if it is not empty, otherwise In chats.
                var tab = currentTab
                var recent: List<StickerGridItem>? = null
                if (tab == null) {
                    recent = library.recent()
                    tab = if (recent.isNotEmpty()) Tab.RECENT else Tab.ALL
                }
                val debugItems = GifSizeTest.gridItems(this@StickerKeyboardService)
                if (tab == Tab.DEBUG && debugItems.isEmpty()) tab = Tab.ALL
                val items = when (tab) {
                    Tab.RECENT -> recent ?: library.recent()
                    Tab.FAVORITES -> library.favorites()
                    Tab.ALL -> library.all()
                    Tab.DEBUG -> debugItems
                }
                hasDebugTab = debugItems.isNotEmpty()
                Triple(tab, items, library.favoriteIds())
            }
            currentTab = tab
            tabViews.getValue(Tab.DEBUG).visibility = if (hasDebugTab) View.VISIBLE else View.GONE
            renderTabs(tab)
            adapter.submit(items, favorites, showStars = tab != Tab.FAVORITES)
            if (!fieldAccepts) return@launch
            if (items.isEmpty()) {
                when (tab) {
                    Tab.RECENT -> showMessage(getString(R.string.kb_empty_recent), withButton = false)
                    Tab.FAVORITES -> showMessage(getString(R.string.kb_empty_favorites), withButton = false)
                    Tab.ALL -> if (!app.scans.state.value.running) showMessage(getString(R.string.kb_empty), withButton = false)
                    Tab.DEBUG -> Unit
                }
            } else {
                message.visibility = View.GONE
                grid.visibility = View.VISIBLE
            }
        }
    }

    /** Long-press menu: add/remove favorite, and "Remove from recent" in the Recent tab. */
    private fun showMenu(item: StickerGridItem, anchor: View) {
        val tab = currentTab ?: return
        if (GifSizeTest.isDebugItem(item)) return
        scope.launch {
            val isFavorite = withContext(Dispatchers.IO) { item.id in app.library.favoriteIds() }
            val popup = PopupMenu(anchor.context, anchor)
            popup.menu.add(0, MENU_FAVORITE, 0, if (isFavorite) R.string.kb_menu_remove_favorite else R.string.kb_menu_add_favorite)
            if (tab == Tab.RECENT) popup.menu.add(0, MENU_REMOVE_RECENT, 1, R.string.kb_menu_remove_recent)
            popup.setOnMenuItemClickListener { menuItem ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        when (menuItem.itemId) {
                            MENU_FAVORITE -> app.library.setFavorite(item.id, !isFavorite)
                            MENU_REMOVE_RECENT -> app.library.removeFromRecent(item.id)
                        }
                    }
                    reloadGrid()
                }
                true
            }
            popup.show()
        }
    }

    private fun showMessage(text: String, withButton: Boolean) {
        grid.visibility = View.GONE
        message.visibility = View.VISIBLE
        messageText.text = text
        messageButton.visibility = if (withButton) View.VISIBLE else View.GONE
    }

    /** Debug tab: sends one of the size-test GIFs as-is and logs its size. */
    private fun insertDebugGif(item: StickerGridItem) {
        insertJob?.cancel()
        insertJob = scope.launch {
            val source = java.io.File(item.documentId)
            val file = withContext(Dispatchers.IO) {
                runCatching { StickerInserter.prepareExistingGif(this@StickerKeyboardService, item.id, source) }.getOrNull()
            } ?: return@launch toast(R.string.kb_insert_error)
            val ctx = this@StickerKeyboardService
            val variant = GifSizeTest.variantOf(item)
            // Delivery variants (DM investigation): same 240 px file, different commitContent details.
            var label: String? = null
            var extraMimes = emptyList<String>()
            var linkUri: android.net.Uri? = null
            when (variant) {
                GifSizeTest.Variant.B -> linkUri = AppSettings.debugLinkUri(ctx).takeIf { it.isNotEmpty() }?.let { android.net.Uri.parse(it) }
                GifSizeTest.Variant.C -> label = "image"
                GifSizeTest.Variant.D -> extraMimes = listOf("image/*")
                else -> Unit
            }
            val ic = currentInputConnection
            val info = currentInputEditorInfo
            val ok = ic != null && info != null &&
                runCatching {
                    StickerInserter.commit(ctx, ic, info, file, StickerInserter.GIF_MIME, label, extraMimes, linkUri)
                }.getOrDefault(false)
            DebugLog.d(
                ctx, DebugLog.GIF,
                "size test send: ${GifSizeTest.sideOf(item)}px variant=${variant?.letter ?: "-"} " +
                    "label=${label ?: "GIF"} mimes=${listOf(StickerInserter.GIF_MIME) + extraMimes} linkUri=${linkUri ?: "none"} " +
                    "${file.length() / 1024} KB, commitContent=$ok (check the chat: did Instagram accept it?)",
            )
            if (variant == GifSizeTest.Variant.B && linkUri == null) toast(R.string.kb_debug_no_link_uri)
            if (!ok) toast(R.string.kb_insert_failed)
        }
    }

    private fun insert(item: StickerGridItem) {
        if (GifSizeTest.isDebugItem(item)) return insertDebugGif(item)
        insertJob?.cancel()
        insertJob = scope.launch {
            val ctx = this@StickerKeyboardService
            // Open exactly like the thumbnails: ContentResolver on the stored SAF document URI.
            val uri = StickerAdapter.documentUri(item)
            if (DebugLog.isDebuggable(ctx)) {
                val pureTwin = runCatching { SafUris.documentUri(item.sourceUri, item.documentId) }.getOrNull()
                DebugLog.d(
                    ctx, DebugLog.GIF,
                    "open: method=SAF openInputStream scheme=${uri.scheme} authority=${uri.authority} " +
                        "doc=…${SafUris.suffix(item.documentId)} pureUriMatch=${pureTwin == uri.toString()}",
                )
            }
            val bytes = try {
                withContext(Dispatchers.IO) { StickerInserter.readSource(ctx, uri) }
            } catch (e: FileNotFoundException) {
                onStickerMissing(item)
                return@launch
            } catch (e: Exception) {
                DebugLog.d(ctx, DebugLog.GIF, "open failed: ${e.javaClass.simpleName}")
                toast(R.string.kb_insert_error)
                return@launch
            }

            // Header check on the bytes just read; corrects the stored flag.
            val animated = StickerInserter.isAnimatedWebp(bytes)
            adapter.setAnimated(item.id, animated)
            if (item.isAnimated != animated) {
                app.appScope.launch(Dispatchers.IO) { app.database.stickerDao().setAnimated(item.id, animated) }
            }

            val sendFile: java.io.File
            val mime: String
            var log: String? = null
            if (!animated) {
                sendFile = withContext(Dispatchers.IO) { StickerInserter.writeOutgoing(ctx, item.id, bytes, "webp") }
                mime = StickerInserter.WEBP_MIME
            } else {
                // Animated: GIF if the field takes GIFs; otherwise, or if conversion fails, a still
                // PNG of the first frame. Animated WEBP is never sent (Instagram rejects it).
                var gif: java.io.File? = null
                // Instagram DMs silently drop GIFs from third-party keyboards (E7); Stories animate them.
                val kind = classifyField(currentInputEditorInfo)
                val stillByDesign = kind == FieldKind.DM || kind == FieldKind.UNKNOWN
                var failure: String? = when {
                    stillByDesign -> "Instagram $kind field: still image by design (E7)"
                    StickerInserter.fieldAcceptsGif(currentInputEditorInfo) -> null
                    else -> "field takes no GIF"
                }
                if (failure == null) {
                    adapter.setBusy(item.id, true)
                    val slowHint = launch {
                        delay(SLOW_CONVERSION_MS)
                        toast(R.string.kb_preparing_animated)
                    }
                    val outcome = try {
                        withContext(Dispatchers.Default) { StickerInserter.prepareGif(ctx, item, bytes) }
                    } finally {
                        slowHint.cancel()
                        adapter.setBusy(item.id, false)
                    }
                    gif = outcome.file
                    val r = outcome.result
                    log = when {
                        gif == null -> null.also { failure = outcome.error ?: "unknown" }
                        r == null -> "gif from cache: source=${bytes.size / 1024} KB, output=${gif.length() / 1024} KB"
                        else -> "gif converted: source=${bytes.size / 1024} KB, frames kept=${r.framesKept}/${r.sourceFrames}, " +
                            "${r.side}px, attempts=${r.attempts}, output=${r.bytes / 1024} KB, ${r.millis} ms (decode ${r.decodeMillis} ms, encode ${r.encodeMillis} ms)"
                    }
                }
                if (gif != null) {
                    sendFile = gif
                    mime = StickerInserter.GIF_MIME
                } else {
                    val still = withContext(Dispatchers.Default) { StickerInserter.prepareStill(ctx, item.id, bytes) }
                    if (still == null) {
                        DebugLog.d(ctx, DebugLog.GIF, "gif failed ($failure) and still image failed: nothing sent")
                        toast(R.string.kb_insert_error)
                        return@launch
                    }
                    sendFile = still
                    mime = StickerInserter.PNG_MIME
                    log = "gif not used ($failure): sending first frame as PNG, ${still.length() / 1024} KB"
                    if (stillByDesign) showDmStillNoticeOnce() else toast(R.string.kb_sent_as_still)
                }
            }

            val ic = currentInputConnection
            val info = currentInputEditorInfo
            val ok = ic != null && info != null &&
                runCatching { StickerInserter.commit(ctx, ic, info, sendFile, mime) }.getOrDefault(false)
            if (log != null) DebugLog.d(ctx, DebugLog.GIF, "$log, mime=$mime, commitContent=$ok")
            if (!ok) {
                toast(R.string.kb_insert_failed)
            } else {
                // Recent: only successful inserts count.
                app.appScope.launch(Dispatchers.IO) { app.library.recordUse(item.id) }
            }
        }
    }

    /**
     * The sticker file is gone from WhatsApp's folder: send nothing, tell the user, count the
     * row as missing (it is archived after the usual complete scans) and start a full scan.
     */
    private fun onStickerMissing(item: StickerGridItem) {
        DebugLog.d(this, DebugLog.GIF, "source missing (FileNotFoundException): nothing sent, full scan requested")
        toast(R.string.kb_sticker_missing)
        app.appScope.launch(Dispatchers.IO) { app.database.stickerDao().incrementMissing(listOf(item.id)) }
        app.scans.request(ScanTrigger.REFRESH_BUTTON)
    }

    private fun signatureOf(info: EditorInfo) = FieldSignature(
        packageName = info.packageName ?: "",
        fieldId = info.fieldId,
        inputType = info.inputType,
        imeOptions = info.imeOptions,
        hint = info.hintText?.toString(),
    )

    private fun classifyField(info: EditorInfo?): FieldKind =
        if (info == null) FieldKind.UNKNOWN else FieldClassifier.classify(signatureOf(info))

    /** One-time notice in the keyboard: DMs get still images, Stories animate. */
    private fun showDmStillNoticeOnce() {
        if (prefs.getBoolean(KEY_DM_NOTICE_SHOWN, false)) return
        prefs.edit().putBoolean(KEY_DM_NOTICE_SHOWN, true).apply()
        notice.visibility = View.VISIBLE
    }

    private val pendingFlags = HashMap<Long, Boolean>()
    private var flagFlushJob: Job? = null

    /** Lazy animation flags from visible tiles, written to the DB in one batch after a short pause. */
    private fun rememberAnimatedFlag(id: Long, animated: Boolean) {
        if (id < 0) return // debug tiles
        pendingFlags[id] = animated
        if (flagFlushJob?.isActive == true) return
        flagFlushJob = scope.launch {
            delay(FLAG_FLUSH_DELAY_MS)
            val batch = HashMap(pendingFlags)
            pendingFlags.clear()
            app.appScope.launch(Dispatchers.IO) { app.database.stickerDao().setAnimatedBatch(batch) }
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

    private enum class Tab { RECENT, FAVORITES, ALL, DEBUG }

    companion object {
        private const val COLUMNS = 4
        private const val SLOW_CONVERSION_MS = 3_000L
        private const val FLAG_FLUSH_DELAY_MS = 1_000L
        private const val KEY_DM_NOTICE_SHOWN = "dm_still_notice_shown"
        private const val PREFS = "keyboard"
        private const val KEY_TAB = "last_tab"
        private const val MENU_FAVORITE = 1
        private const val MENU_REMOVE_RECENT = 2
    }
}
