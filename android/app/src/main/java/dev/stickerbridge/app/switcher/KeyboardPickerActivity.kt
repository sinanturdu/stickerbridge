package dev.stickerbridge.app.switcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager

/**
 * Tiny transparent activity that opens the system keyboard picker, then finishes.
 * Used by the Quick Settings tile and the launcher shortcut (same activity, same flow).
 *
 * The system shows the picker only for the app whose window currently has input focus,
 * and the input method service learns about that focus slightly after
 * onWindowFocusChanged(true). So the picker is requested [PICKER_DELAY_MS] after focus.
 * The activity stays alive until the picker takes focus away (or at least
 * [MIN_ALIVE_MS] after the request), and gives up after [TIMEOUT_MS].
 */
class KeyboardPickerActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var pickerRequestedAt = 0L

    private val requestPicker = Runnable {
        if (isFinishing) return@Runnable
        pickerRequestedAt = System.currentTimeMillis()
        SwitchLog.d(this, "picker requested")
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        // Fallback finish if focus never changes (e.g. the picker was not shown).
        handler.postDelayed(finishRunnable, FALLBACK_FINISH_MS)
    }

    private val finishRunnable = Runnable { if (!isFinishing) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SwitchLog.d(this, "activity created")
        handler.postDelayed(finishRunnable, TIMEOUT_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pickerRequestedAt == 0L) {
            SwitchLog.d(this, "focus gained")
            handler.removeCallbacks(requestPicker)
            handler.postDelayed(requestPicker, PICKER_DELAY_MS)
        } else if (!hasFocus && pickerRequestedAt != 0L) {
            // The picker dialog took focus: finish, but not before it had time to appear.
            SwitchLog.d(this, "focus lost to picker")
            val alive = System.currentTimeMillis() - pickerRequestedAt
            handler.removeCallbacks(finishRunnable)
            handler.postDelayed(finishRunnable, (MIN_ALIVE_MS - alive).coerceAtLeast(0L))
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val PICKER_DELAY_MS = 150L
        private const val MIN_ALIVE_MS = 500L
        private const val FALLBACK_FINISH_MS = 1_500L
        private const val TIMEOUT_MS = 10_000L

        fun intent(context: Context): Intent =
            Intent(context, KeyboardPickerActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
    }
}
