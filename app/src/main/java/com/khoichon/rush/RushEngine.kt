package com.khoichon.rush

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import kotlin.random.Random

/**
 * One Rush encounter, start to finish:
 *
 *   WARNING   the lights flicker (bulb sound)                          ~1.7 s
 *   APPROACH  far rumble fades in, then the near one; screen dims and
 *             starts to shake. Tap the button to hide in the closet     4.5 - 9 s
 *   PASS      Rush crosses the screen; at the middle it checks if you are hiding
 *   CLEAR     he is gone, the lights come back, you step out
 *   JUMPSCARE if you were not hiding (see [Js])
 *
 * A fake-out is just WARNING plus a short burst of the fake-out sound, then nothing.
 * All timing runs on one main-thread [Handler]; [finish] cancels everything at once.
 */
class RushEngine(
    private val context: Context,
    private val cfg: Config,
    private val overlay: OverlayController,
    private val st: RushState
) {
    private val h = Handler(Looper.getMainLooper())
    private val haptics = Haptics(context) { cfg.haptics }

    /** Fired when Rush catches you (death consequences). */
    var onCaught: (() -> Unit)? = null

    val active get() = st.phase != Phase.NONE

    private var far: Sfx? = null
    private var near: Sfx? = null
    private var flicker: Sfx? = null
    private var bulbBreak: Sfx? = null
    private var fakeout: Sfx? = null
    private var scare: Sfx? = null
    private var kill: Sfx? = null
    private var staticSfx: Sfx? = null
    private var doorOpen: Sfx? = null
    private var hideIn: Sfx? = null
    private var hideOut: Sfx? = null

    private var approachStart = 0L
    private var approachMs = 1L
    private var closetBusyUntil = 0L
    private var lastToggle = 0L
    private var fading = false
    private var fadeStart = 0L

    // ---------------------------------------------------------------- start

    /** [allowFake]: automatic encounters may roll a fake-out; a manual call never does. */
    fun trigger(allowFake: Boolean) {
        if (active || !Settings.canDrawOverlays(context)) return
        val fake = allowFake && Random.nextInt(100) < cfg.fakeoutChance
        reset()
        loadSounds(fake)
        st.fakeout = fake
        st.begin(Phase.WARNING, FLICKER_MS)
        overlay.setOnHideTap { toggleHide() }
        overlay.showScene(SceneMode.PASS_THROUGH)
        overlay.showHideButton()

        flicker?.play()
        haptics.warning()
        if (fake) {
            fakeout?.play()
            h.postDelayed({ fadeFakeout(0) }, FAKE_FADE_AT)
        } else {
            h.postDelayed(::startApproach, FLICKER_MS)
        }
    }

    /** Test mode: jump straight to the jumpscare. */
    fun jumpscareNow() {
        if (active || !Settings.canDrawOverlays(context)) return
        reset()
        loadSounds(false)
        caught()
    }

    private fun reset() {
        h.removeCallbacksAndMessages(null)
        st.reset()
        fading = false
        closetBusyUntil = 0L
        lastToggle = 0L
    }

    private fun loadSounds(fake: Boolean) {
        releaseSounds()
        flicker = Sfx(context, "bulb_flicker")
        hideIn = Sfx(context, "hide_enter").also { it.preload() }
        hideOut = Sfx(context, "hide_exit").also { it.preload() }
        if (fake) {
            fakeout = Sfx(context, "fakeout")
        } else {
            far = Sfx(context, "rush_far").also { it.setVolume(0f) }
            near = Sfx(context, "rush_near").also { it.setVolume(0f) }
            bulbBreak = Sfx(context, "bulb_break")
            doorOpen = Sfx(context, "door_open")
            far?.preload(); near?.preload()
        }
        scare = Sfx(context, "rush_scare").also { it.preload() }
        kill = Sfx(context, "rush_kill").also { it.preload() }
        staticSfx = Sfx(context, "rush_static").also { it.preload() }
    }

    private fun releaseSounds() {
        listOf(far, near, flicker, bulbBreak, fakeout, scare, kill, staticSfx, doorOpen, hideIn, hideOut)
            .forEach { it?.release() }
        far = null; near = null; flicker = null; bulbBreak = null; fakeout = null
        scare = null; kill = null; staticSfx = null; doorOpen = null; hideIn = null; hideOut = null
    }

    // ---------------------------------------------------------------- fake-out

    private fun fadeFakeout(step: Int) {
        if (!active || !st.fakeout) return
        val steps = 16
        fakeout?.setVolume(1f - step / steps.toFloat())
        if (step >= steps) {
            fakeout?.pause()
            st.begin(Phase.CLEAR, 500)
            h.postDelayed(::finish, 500)
        } else {
            h.postDelayed({ fadeFakeout(step + 1) }, 50)
        }
    }

    // ---------------------------------------------------------------- approach

    private fun startApproach() {
        if (!active) return
        approachMs = 4500L + Random.nextInt(4500)
        approachStart = SystemClock.uptimeMillis()
        st.begin(Phase.APPROACH, approachMs)
        far?.playLooping()
        near?.playLooping()
        haptics.threat(true)
        h.post(ticker)
        h.postDelayed(::startPass, approachMs)
    }

    /** Volume / shake ramps while Rush is coming (every ~50 ms until the pass starts). */
    private val ticker = object : Runnable {
        override fun run() {
            if (st.phase != Phase.APPROACH) return
            val p = ((SystemClock.uptimeMillis() - approachStart).toFloat() / approachMs).coerceIn(0f, 1f)
            far?.setVolume(0.7f * p)
            near?.setVolume(((p - 0.5f) / 0.5f).coerceIn(0f, 1f))
            st.shake = (((p - 0.8f) / 0.2f).coerceIn(0f, 1f)) * 0.5f
            h.postDelayed(this, 50)
        }
    }

    // ---------------------------------------------------------------- the pass

    private fun startPass() {
        if (st.phase != Phase.APPROACH) return
        val passMs = (9000f / cfg.rushSpeed).toLong().coerceIn(180L, 3000L)
        st.passDir = if (Random.nextBoolean()) 1 else -1
        st.shake = 1f
        st.begin(Phase.PASS, passMs)
        applyMode()
        far?.setVolume(1f)
        near?.setVolume(1f)
        bulbBreak?.setVolume(0.8f)
        bulbBreak?.play()
        h.postDelayed(::decide, passMs / 2)
        h.postDelayed(::endPass, passMs)
    }

    /** Rush is at the middle of the screen: hiding or not? */
    private fun decide() {
        if (st.phase != Phase.PASS) return
        if (!st.hidden) caught()
    }

    private fun endPass() {
        if (st.phase != Phase.PASS) return
        haptics.threat(false)
        st.shake = 0f
        st.begin(Phase.CLEAR, 700)
        applyMode()
        doorOpen?.play()
        fading = true
        fadeStart = SystemClock.uptimeMillis()
        h.post(fadeOutRush)
        h.postDelayed(::stepOut, 1300)
    }

    private val fadeOutRush = object : Runnable {
        override fun run() {
            if (!fading) return
            val p = ((SystemClock.uptimeMillis() - fadeStart) / 1000f).coerceIn(0f, 1f)
            far?.setVolume(1f - p)
            near?.setVolume(1f - p)
            if (p >= 1f) { far?.pause(); near?.pause(); fading = false } else h.postDelayed(this, 50)
        }
    }

    /** Safe: leave the closet (if still in it), then wrap up. */
    private fun stepOut() {
        if (st.phase != Phase.CLEAR) return
        if (st.hidden) {
            setHidden(false)
            h.postDelayed(::finish, RushState.CLOSET_MS + 150)
        } else {
            finish()
        }
    }

    // ---------------------------------------------------------------- closet

    fun toggleHide() {
        val now = SystemClock.uptimeMillis()
        if (!active || st.phase == Phase.JUMPSCARE || now - lastToggle < 450L) return
        lastToggle = now
        setHidden(!st.hidden)
    }

    private fun setHidden(hide: Boolean) {
        val now = SystemClock.uptimeMillis()
        st.hidden = hide
        st.hideChangedAt = now
        closetBusyUntil = now + RushState.CLOSET_MS + 60
        if (hide) hideIn?.play() else hideOut?.play()
        haptics.tap()
        applyMode()
        overlay.refreshButton()
        // when the doors have finished opening, drop back to the pass-through window
        h.postDelayed(::applyMode, RushState.CLOSET_MS + 80)
    }

    /** Pick the scene window mode for the current state. */
    private fun applyMode() {
        val now = SystemClock.uptimeMillis()
        val m = when {
            st.phase == Phase.JUMPSCARE -> SceneMode.BLOCK
            st.hidden || now < closetBusyUntil -> SceneMode.BLOCK
            st.phase == Phase.PASS -> SceneMode.FX
            else -> SceneMode.PASS_THROUGH
        }
        overlay.setSceneMode(m)
    }

    // ---------------------------------------------------------------- caught

    private fun caught() {
        // stop the chase: everything but the jumpscare goes quiet
        h.removeCallbacksAndMessages(null)
        fading = false
        haptics.stopAll()
        listOf(far, near, flicker, bulbBreak, fakeout).forEach { it?.pause() }
        st.shake = 0f
        st.hidden = false
        st.begin(Phase.JUMPSCARE, Js.END_AT)
        overlay.hideHideButton()
        overlay.showScene(SceneMode.BLOCK)
        overlay.setSceneMode(SceneMode.BLOCK)

        staticSfx?.playLooping()
        h.postDelayed({
            staticSfx?.pause()
            scare?.play()
            haptics.jumpscare()
        }, Js.SCREAM_AT)
        h.postDelayed({ kill?.play() }, Js.HIT_AT)
        h.postDelayed({
            onCaught?.invoke()
            h.postDelayed(::finish, 700)
        }, Js.END_AT)
    }

    // ---------------------------------------------------------------- end

    fun finish() {
        h.removeCallbacksAndMessages(null)
        fading = false
        haptics.stopAll()
        overlay.hideHideButton()
        overlay.hideScene()
        st.reset()
        releaseSounds()
    }

    fun release() = finish()

    companion object {
        const val FLICKER_MS = 1700L
        const val FAKE_FADE_AT = 2600L
    }
}
