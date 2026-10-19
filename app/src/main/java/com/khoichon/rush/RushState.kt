package com.khoichon.rush

import android.os.SystemClock

enum class Phase { NONE, WARNING, APPROACH, PASS, CLEAR, JUMPSCARE, DEATH }

/**
 * Everything the renderer needs, written by [RushEngine] and read by [SceneView] on the main
 * thread. The engine only sets the phase (and when it began); the view works out the pixels from
 * how long ago that was.
 */
class RushState {
    var phase = Phase.NONE
    var phaseStart = 0L
    var phaseDur = 1L

    /** Which entity this encounter is. */
    var entity = Entity.RUSH

    /** 0..1, how hard the screen is shaking. */
    var shake = 0f

    /** In the closet? The bars animate from [hideChangedAt]. */
    var hidden = false
    var hideChangedAt = 0L

    /** +1 = crosses left to right, -1 = right to left. Flips on every rebound. */
    var passDir = 1

    /** Which pass this is (0-based); Ambush and Blitz rebound, so it can exceed 0. */
    var passIndex = 0

    /** Blitz: pink (rebounding) form instead of the green one. */
    var pinkForm = false

    /** Fake-out: the lights flicker and the sound starts, but nothing comes. */
    var fakeout = false

    /** Hide: how long the current hiding stint has lasted (uptimeMillis, 0 = not hidden). */
    var hideSince = 0L

    /** Hide is attacking (red pulsating vignette, GET OUT flashing, heartbeat). */
    var hideAttack = false

    /** Hide kicked you out; you cannot hide again until this time (uptimeMillis). */
    var hideLockUntil = 0L

    /** When Hide last threw you out; the red lingers briefly (uptimeMillis). */
    var hideKickAt = 0L

    // ---- Scribbles ----

    /** Player health, 100 at the start; every paper that hits you takes 10. */
    var health = 100

    /** Random seed of this encounter (lays out the papers on the walls). */
    var seed = 0L

    /** A paper in flight towards you. [cell] = which paper of the sheet (0-6). */
    class Throw(val at: Long, val cell: Int)

    /** A paper cut on your screen; position and angle come from [seed]; it fades over [fadeMs]. */
    class Cut(val at: Long, val fadeMs: Long, val seed: Long)

    val throws = ArrayList<Throw>()
    val cuts = ArrayList<Cut>()

    // ---- Surge ----

    /** 0 = not started, 1-3 = the three sky flashes, 4 = descending / attacking, 5 = flying back up. */
    var surgeStage = 0
    var surgeStageAt = 0L

    /** When the idle part of the attack began (flying around you / at you); 0 = not yet. */
    var surgeIdleAt = 0L

    /** The Surge vignette fades from [surgeVigFrom] to [surgeVigTarget] starting at [surgeVigAt]. */
    var surgeVigFrom = 0f
    var surgeVigTarget = 0f
    var surgeVigAt = 0L

    fun surgeVigLevel(now: Long): Float {
        val t = ((now - surgeVigAt).toFloat() / SURGE_VIG_FADE_MS).coerceIn(0f, 1f)
        return surgeVigFrom + (surgeVigTarget - surgeVigFrom) * t
    }

    fun reset() {
        phase = Phase.NONE
        phaseStart = 0L
        phaseDur = 1L
        entity = Entity.RUSH
        shake = 0f
        hidden = false
        hideChangedAt = 0L
        passDir = 1
        passIndex = 0
        pinkForm = false
        fakeout = false
        hideSince = 0L
        hideAttack = false
        hideLockUntil = 0L
        hideKickAt = 0L
        health = 100
        seed = 0L
        throws.clear()
        cuts.clear()
        surgeStage = 0
        surgeStageAt = 0L
        surgeIdleAt = 0L
        surgeVigFrom = 0f
        surgeVigTarget = 0f
        surgeVigAt = 0L
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
        const val SURGE_VIG_FADE_MS = 1500L
    }
}
