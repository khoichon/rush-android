package com.khoichon.rush

import android.content.Context
import android.content.Intent

/**
 * The optional "Guiding Light" helper app (the DOORS death-message screen). Same as running:
 *   adb shell am start -a dev.khoichon.guidinglight.action.SHOW --es mode guiding --es message '...;...;...'
 * The message is three lines separated by ';'. Pass mode "curious" to get Curious Light instead
 * (the death screen for Blitz, Bash, Scribbles and Surge).
 */
object GuidingLight {
    const val ACTION = "dev.khoichon.guidinglight.action.SHOW"

    fun intent(entity: Entity, deathNo: Int, kickedByHide: Boolean): Intent = Intent(ACTION)
        .putExtra("mode", entity.light)
        .putExtra("message", Deaths.message(entity, deathNo, kickedByHide))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Is something installed that handles the SHOW action? (Needs the <queries> entry in the manifest.) */
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.queryIntentActivities(Intent(ACTION), 0).isNotEmpty()
    }.getOrDefault(false)
}
