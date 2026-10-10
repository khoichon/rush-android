package com.khoichon.rush

import android.os.SystemClock

enum class Phase { NONE, WARNING, APPROACH, PASS, CLEAR, JUMPSCARE }

/**
 * Everything the renderer needs, written by [RushEngine] and read by [SceneView] on the main
 * thread. The engine only sets the phase (and when it began); the view works out the pixels from
 * how long ago that was.
 */
class RushState {
    var phase = Phase.NONE
    var phaseStart = 0L
    var phaseDur = 1L

    /** 0..1, how hard the screen is shaking. */
    var shake = 0f

    /** In the closet? The bars animate from [hideChangedAt]. */
    var hidden = false
    var hideChangedAt = 0L

    /** +1 = Rush crosses left to right, -1 = right to left. */
    var passDir = 1

    /** Fake-out: the lights flicker and the sound starts, but Rush never comes. */
    var fakeout = false

    fun reset() {
        phase = Phase.NONE
        phaseStart = 0L
        phaseDur = 1L
        shake = 0f
        hidden = false
        hideChangedAt = 0L
        passDir = 1
        fakeout = false
    }

    fun begin(p: Phase, durMs: Long) {
        phase = p
        phaseStart = SystemClock.uptimeMillis()
        phaseDur = durMs.coerceAtLeast(1L)
    }

    /** 0 = closet open, 1 = doors fully closed (thin line of light left in the middle). */
    fun closet(now: Long): Float {
        val t = ((now - hideChangedAt).toFloat() / CLOSET_MS).coerceIn(0f, 1f)
        return if (hidden) t else 1f - t
    }

    companion object {
        const val CLOSET_MS = 380L
    }
}
