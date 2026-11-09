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
        adapter = StickerAdapter(thumbnails, onClick = { insert(it) }, onLongClick = { item, anchor -> showMenu(item, anchor) })
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
            val ic = currentInputConnection
            val info = currentInputEditorInfo
            val ok = ic != null && info != null &&
                runCatching { StickerInserter.commit(this@StickerKeyboardService, ic, info, file, StickerInserter.GIF_MIME) }.getOrDefault(false)
            DebugLog.d(
                this@StickerKeyboardService, DebugLog.GIF,
                "size test send: ${GifSizeTest.sideOf(item)}px, ${file.length() / 1024} KB, commitContent=$ok " +
                    "(check the chat: did Instagram accept it?)",
            )
            if (!ok) toast(R.string.kb_insert_failed)
        }
    }

    private fun insert(item: StickerGridItem) {
        if (GifSizeTest.isDebugItem(item)) return insertDebugGif(item)
        insertJob?.cancel()
        insertJob = scope.launch {
            val file = try {
                withContext(Dispatchers.IO) {
                    StickerInserter.copySource(this@StickerKeyboardService, StickerAdapter.documentUri(item), item.id)
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
            var gifLog: String? = null
            if (animated && StickerInserter.fieldAcceptsGif(currentInputEditorInfo)) {
                adapter.setBusy(item.id, true)
                // Long conversions get a hint after 3 s.
                val slowHint = launch {
                    delay(SLOW_CONVERSION_MS)
                    toast(R.string.kb_preparing_animated)
                }
                val outcome = try {
                    withContext(Dispatchers.Default) { StickerInserter.prepareGif(this@StickerKeyboardService, item, file) }
                } finally {
                    slowHint.cancel()
                    adapter.setBusy(item.id, false)
                }
                val gif = outcome.file
                if (gif != null) {
                    sendFile = gif
                    mime = StickerInserter.GIF_MIME
                    val r = outcome.result
                    gifLog = if (r == null) {
                        "cached gif, source=${file.length() / 1024} KB, output=${gif.length() / 1024} KB"
                    } else {
                        "converted source=${file.length() / 1024} KB, frames kept=${r.framesKept} dropped=${r.framesDropped}, " +
                            "side=${r.side}px, attempts=${r.attempts}, output=${r.bytes / 1024} KB, ${r.millis} ms"
                    }
                } else {
                    // Conversion failed: tell the user and send the WEBP (Instagram shows its first frame).
                    toast(R.string.kb_animated_failed)
                    gifLog = "conversion failed (${outcome.error}), source=${file.length() / 1024} KB, sending WEBP"
                }
            }

            val ic = currentInputConnection
            val info = currentInputEditorInfo
            val ok = ic != null && info != null &&
                runCatching { StickerInserter.commit(this@StickerKeyboardService, ic, info, sendFile, mime) }.getOrDefault(false)
            if (gifLog != null) DebugLog.d(this@StickerKeyboardService, DebugLog.GIF, "$gifLog, mime=$mime, commitContent=$ok")
            if (!ok) {
                toast(R.string.kb_insert_failed)
            } else {
                // Recent: only successful inserts count.
                app.appScope.launch(Dispatchers.IO) { app.library.recordUse(item.id) }
            }
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
        private const val PREFS = "keyboard"
        private const val KEY_TAB = "last_tab"
        private const val MENU_FAVORITE = 1
        private const val MENU_REMOVE_RECENT = 2
    }
}
