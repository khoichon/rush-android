package com.khoichon.rush

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Phone-only extras: a buzz while Rush is coming, a tap on the warning, and a jumpscare shake. */
class Haptics(context: Context, private val enabled: () -> Boolean) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private var threatOn = false

    /** Steady heartbeat-ish buzz while Rush is coming. */
    fun threat(on: Boolean) {
        if (on == threatOn) return
        threatOn = on
        val v = vibrator ?: return
        if (on) {
            if (!enabled()) return
            runCatching { v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 35, 90, 35, 90), 0)) }
        } else {
            runCatching { v.cancel() }
        }
    }

    /** The lights flickering. */
    fun warning() = oneShot(60)

    /** Entering / leaving the closet. */
    fun tap() = oneShot(20)

    fun jumpscare() {
        if (!enabled()) return
        runCatching {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 450, 80, 300, 80, 700), -1))
        }
    }

    fun stopAll() {
        threatOn = false
        runCatching { vibrator?.cancel() }
    }

    private fun oneShot(ms: Long) {
        if (!enabled()) return
        runCatching {
            vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
