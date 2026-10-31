package dev.finevolume.app.service

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uses Android's view-level haptic feedback instead of direct Vibrator pulses.
 *
 * This lets the platform honor the user's system touch-feedback setting and
 * the hosting view's hapticFeedbackEnabled property. No raw vibration is sent.
 */
@Singleton
class HapticFeedbackManager @Inject constructor() {

    /** level: 0=off, 1=light tick, 2=standard key tap, 3=confirmation */
    fun vibrate(view: View?, level: Int) {
        if (level <= 0 || view == null || !view.isAttachedToWindow) return

        val constant = when (level) {
            1 -> HapticFeedbackConstants.CLOCK_TICK
            2 -> HapticFeedbackConstants.KEYBOARD_TAP
            3 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
            else -> return
        }

        runCatching { view.performHapticFeedback(constant) }
    }
}
