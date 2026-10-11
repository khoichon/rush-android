package com.khoichon.rush

import android.content.Context
import android.content.Intent

/**
 * The optional "Guiding Light" helper app (the DOORS death-message screen). Same as running:
 *   adb shell am start -a dev.khoichon.guidinglight.action.SHOW --es mode guiding --es message '...;...;...'
 * The message is three lines separated by ';'.
 */
object GuidingLight {
    const val ACTION = "dev.khoichon.guidinglight.action.SHOW"

    private const val MESSAGE =
        "You died to who you call Rush...;" +
            "Pay attention to any cues that may hint at its attack.;" +
            "You\u2019ve done great! Don\u2019t give up!"

    fun intent(): Intent = Intent(ACTION)
        .putExtra("mode", "guiding")
        .putExtra("message", MESSAGE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Is something installed that handles the SHOW action? (Needs the <queries> entry in the manifest.) */
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.queryIntentActivities(Intent(ACTION), 0).isNotEmpty()
    }.getOrDefault(false)
}
