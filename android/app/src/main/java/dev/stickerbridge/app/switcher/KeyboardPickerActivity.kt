package dev.stickerbridge.app.switcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager

/**
 * Invisible host for the system keyboard picker ("Choose input method").
 * Used by the Quick Settings tile and the launcher shortcut (same activity, same flow).
 *
 * The picker dialog belongs to the focused window's client, so this activity must stay
 * alive while the picker is open; finishing early closes the picker with it. The flow is
 * driven only by window focus, not by timers:
 *
 *  1. first focus gained  -> request the picker (once, after a short post so the input
 *                            method service has registered this window as focused)
 *  2. focus lost          -> the picker is showing: pickerShown = true
 *  3. focus regained      -> the user picked a keyboard or dismissed the picker: finish()
 *
 * Safety nets: finish in onPause/onStop once the picker has been shown (user left), and a
 * 30 s fallback timeout in case the picker never appears.
 */
class KeyboardPickerActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var pickerRequested = false
    private var pickerShown = false

    private val requestPicker = Runnable {
        if (isFinishing || pickerRequested) return@Runnable
        pickerRequested = true
        SwitchLog.d(this, "picker requested")
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    private val fallbackFinish = Runnable {
        SwitchLog.d(this, "fallback timeout (picker shown=$pickerShown)")
        finishHost("timeout")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SwitchLog.d(this, "activity created")
        handler.postDelayed(fallbackFinish, FALLBACK_TIMEOUT_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        when {
            hasFocus && !pickerRequested -> {
                SwitchLog.d(this, "focus gained")
                handler.removeCallbacks(requestPicker)
                handler.postDelayed(requestPicker, PICKER_DELAY_MS)
            }
            !hasFocus && pickerRequested -> {
                if (!pickerShown) SwitchLog.d(this, "focus lost -> picker shown")
                pickerShown = true
            }
            hasFocus && pickerShown -> {
                SwitchLog.d(this, "focus regained -> picker closed")
                finishHost("picker closed")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // The picker is a dialog window, not an activity, so it does not pause us.
        // Being paused after it was shown means the user left (home, recents, another app).
        if (pickerShown) finishHost("paused after picker")
    }

    override fun onStop() {
        super.onStop()
        if (pickerShown || pickerRequested) finishHost("stopped")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun finishHost(reason: String) {
        if (isFinishing) return
        SwitchLog.d(this, "finished ($reason)")
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val PICKER_DELAY_MS = 150L
        private const val FALLBACK_TIMEOUT_MS = 30_000L

        fun intent(context: Context): Intent =
            Intent(context, KeyboardPickerActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
    }
}
