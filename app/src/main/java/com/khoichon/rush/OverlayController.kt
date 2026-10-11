package com.khoichon.rush

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** How the full-screen scene window treats touches. */
enum class SceneMode {
    /** Lights flickering / dimming: touches fall through to the app below (window alpha <= 0.79,
     *  the most Android 12+ lets an overlay have and still be passed touches). */
    PASS_THROUGH,

    /** Rush on screen: fully opaque, so touches are dropped for the second or so it lasts. */
    FX,

    /** Closet doors / jumpscare: fully opaque and swallows touches. */
    BLOCK
}

/** Owns the two overlay windows: the full-screen scene, and the small hide button above it. */
class OverlayController(private val context: Context, val state: RushState) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density

    private val scene = SceneView(context, state)
    private val button = HideButtonView(context, state)

    private var sceneShown = false
    private var buttonShown = false
    private var mode = SceneMode.PASS_THROUGH

    fun setOnHideTap(l: (() -> Unit)?) { button.onTap = l }

    private fun sceneParams(m: SceneMode): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (m != SceneMode.BLOCK) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = if (m == SceneMode.PASS_THROUGH) 0.79f else 1f
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            title = "Rush scene"
        }
    }

    private fun buttonParams(): WindowManager.LayoutParams {
        val size = (68 * density).toInt()
        return WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (76 * density).toInt() // clear of the gesture / nav bar
            title = "Rush hide"
        }
    }

    fun showScene(m: SceneMode) {
        if (sceneShown) { setSceneMode(m); return } // must not touch `mode` first: setSceneMode compares it
        mode = m
        runCatching {
            wm.addView(scene, sceneParams(m))
            sceneShown = true
        }
    }

    fun setSceneMode(m: SceneMode) {
        if (m == mode && sceneShown) return
        mode = m
        if (!sceneShown) return
        runCatching { wm.updateViewLayout(scene, sceneParams(m)) }
    }

    fun showHideButton() {
        if (buttonShown) return
        runCatching {
            wm.addView(button, buttonParams())
            buttonShown = true
        }
    }

    fun hideHideButton() {
        if (!buttonShown) return
        runCatching { wm.removeViewImmediate(button) }
        buttonShown = false
    }

    fun refreshButton() = button.invalidate()

    fun hideScene() {
        if (!sceneShown) return
        runCatching { wm.removeViewImmediate(scene) }
        sceneShown = false
    }

    /** Screen rotated / resized: re-apply the window params. */
    fun relayout() {
        if (sceneShown) runCatching { wm.updateViewLayout(scene, sceneParams(mode)) }
        if (buttonShown) runCatching { wm.updateViewLayout(button, buttonParams()) }
    }

    fun release() {
        hideHideButton()
        hideScene()
        scene.release()
    }
}
