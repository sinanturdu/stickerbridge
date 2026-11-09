package dev.stickerbridge.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.stickerbridge.app.debug.GifSizeTest
import dev.stickerbridge.app.ime.StickerKeyboardService
import dev.stickerbridge.app.scan.ScanState
import dev.stickerbridge.app.scan.ScanTrigger
import dev.stickerbridge.app.scan.StickerFolders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Onboarding: enable the keyboard, connect the stickers folder(s), show scan status. */
class MainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app by lazy { StickerBridgeApp.from(this) }

    private lateinit var keyboardStatus: TextView
    private lateinit var folderList: LinearLayout
    private lateinit var statusCount: TextView
    private lateinit var statusLastScan: TextView
    private lateinit var statusResult: TextView
    private lateinit var scanProgress: ProgressBar
    private lateinit var btnScanNow: Button

    private val pickFolder = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            try {
                StickerFolders.connect(this, uri)
            } catch (e: SecurityException) {
                Toast.makeText(this, R.string.folder_picker_failed, Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            if (!StickerFolders.describe(uri).looksLikeStickerFolder) {
                Toast.makeText(this, R.string.folder_unexpected, Toast.LENGTH_LONG).show()
            }
            renderFolders()
            app.scans.request(ScanTrigger.FOLDER_CONNECTED)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        keyboardStatus = findViewById(R.id.keyboardStatus)
        folderList = findViewById(R.id.folderList)
        statusCount = findViewById(R.id.statusCount)
        statusLastScan = findViewById(R.id.statusLastScan)
        statusResult = findViewById(R.id.statusResult)
        scanProgress = findViewById(R.id.scanProgress)
        btnScanNow = findViewById(R.id.btnScanNow)

        findViewById<Button>(R.id.btnEnableKeyboard).setOnClickListener {
            startActivity(android.content.Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btnPickKeyboard).setOnClickListener {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        findViewById<Button>(R.id.btnConnectWhatsApp).setOnClickListener {
            launchPicker(StickerFolders.WHATSAPP_DOC_ID)
        }
        findViewById<Button>(R.id.btnConnectBusiness).setOnClickListener {
            launchPicker(StickerFolders.WHATSAPP_BUSINESS_DOC_ID)
        }
        btnScanNow.setOnClickListener { app.scans.request(ScanTrigger.REFRESH_BUTTON) }

        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchAnimatePreviews).apply {
            isChecked = AppSettings.animatePreviews(this@MainActivity)
            setOnCheckedChangeListener { _, on -> AppSettings.setAnimatePreviews(this@MainActivity, on) }
        }

        // Hidden debug menu (debug builds only): long-press the title.
        if (DebugLog.isDebuggable(this)) {
            findViewById<View>(R.id.appTitle).setOnLongClickListener { anchor ->
                androidx.appcompat.widget.PopupMenu(this, anchor).apply {
                    menu.add(0, 1, 0, "GIF size test")
                    menu.add(0, 2, 1, "Receive test")
                    menu.add(0, 3, 2, "Debug linkUri…")
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> runGifSizeTest()
                            2 -> startActivity(android.content.Intent(this@MainActivity, dev.stickerbridge.app.debug.ReceiveTestActivity::class.java))
                            3 -> editDebugLinkUri()
                        }
                        true
                    }
                }.show()
                true
            }
        }

        uiScope.launch { app.scans.state.collect { renderStatus(it) } }
    }

    /** Debug: linkUri used by delivery variant B in the keyboard's Debug tab. */
    private fun editDebugLinkUri() {
        val input = android.widget.EditText(this).apply {
            setText(AppSettings.debugLinkUri(this@MainActivity))
            hint = "https://…  (empty = no linkUri)"
            isSingleLine = true
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Debug linkUri (variant B)")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> AppSettings.setDebugLinkUri(this, input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runGifSizeTest() {
        Toast.makeText(this, R.string.debug_gif_test_running, Toast.LENGTH_SHORT).show()
        uiScope.launch {
            val entries = withContext(Dispatchers.Default) { GifSizeTest.run(this@MainActivity) }
            if (entries == null) {
                Toast.makeText(this@MainActivity, R.string.debug_gif_test_no_source, Toast.LENGTH_LONG).show()
            } else {
                val summary = entries.joinToString(", ") { "${it.side}px ${it.bytes / 1024} KB" }
                Toast.makeText(this@MainActivity, getString(R.string.debug_gif_test_done, summary), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderKeyboardStatus()
        renderFolders()
        if (StickerFolders.connected(this).isNotEmpty()) app.scans.request(ScanTrigger.ONBOARDING)
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun launchPicker(initialDocumentId: String) {
        try {
            pickFolder.launch(StickerFolders.pickerIntent(initialDocumentId))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.folder_picker_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun renderKeyboardStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any {
            it.packageName == packageName && it.serviceName == StickerKeyboardService::class.java.name
        }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val selected = current.startsWith("$packageName/")
        keyboardStatus.setText(
            when {
                enabled && selected -> R.string.keyboard_enabled_default
                enabled -> R.string.keyboard_enabled
                else -> R.string.keyboard_disabled
            }
        )
        keyboardStatus.setTextColor(ContextCompat.getColor(this, if (enabled) R.color.sb_ok else R.color.sb_warn))
    }

    private fun renderFolders() {
        folderList.removeAllViews()
        val folders = StickerFolders.connected(this)
        if (folders.isEmpty()) {
            folderList.addView(TextView(this).apply {
                setText(R.string.no_folder_connected)
                setTextColor(ContextCompat.getColor(context, R.color.sb_warn))
            })
            return
        }
        for (folder in folders) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = buildString {
                    append("✓ ").append(folder.label)
                    if (!folder.looksLikeStickerFolder) append(" (unexpected folder)")
                }
                setTextColor(ContextCompat.getColor(context, if (folder.looksLikeStickerFolder) R.color.sb_ok else R.color.sb_warn))
                textSize = 15f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                setText(R.string.folder_disconnect)
                isAllCaps = false
                setOnClickListener {
                    StickerFolders.disconnect(this@MainActivity, folder.treeUri)
                    app.scans.onFoldersChanged()
                    renderFolders()
                }
            })
            folderList.addView(row)
        }
    }

    private fun renderStatus(s: ScanState) {
        statusCount.text = getString(R.string.status_count, s.stickerCount)
        statusLastScan.text = if (s.lastSuccessAt > 0) {
            getString(
                R.string.status_last_scan,
                DateUtils.getRelativeTimeSpanString(s.lastSuccessAt, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)
            )
        } else {
            getString(R.string.status_last_scan_never)
        }
        statusResult.text = getString(R.string.status_result, s.lastResult)
        scanProgress.visibility = if (s.running) android.view.View.VISIBLE else android.view.View.GONE
        btnScanNow.isEnabled = !s.running
    }
}
