package dev.stickerbridge.app.switcher

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.inputmethod.InputMethodManager

/**
 * Tiny transparent activity that opens the system keyboard picker and finishes.
 * Used by the Quick Settings tile and the launcher shortcut.
 *
 * The picker needs a focused window, so it is shown once this activity gains focus;
 * when the picker takes focus (or is dismissed) the activity finishes.
 */
class KeyboardPickerActivity : Activity() {

    private var pickerShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.postDelayed({ if (!isFinishing) finish() }, TIMEOUT_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !pickerShown) {
            pickerShown = true
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        } else if (pickerShown) {
            // Lost focus to the picker, or regained it after the picker closed.
            finish()
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TIMEOUT_MS = 10_000L
    }
}
