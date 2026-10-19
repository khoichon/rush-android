package com.khoichon.rush

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import kotlin.random.Random

/**
 * One encounter, start to finish. The skeleton is shared by every entity:
 *
 *   WARNING   the entity announces itself (Rush/Blitz: flickering lights; Ambush: a distorted
 *             scream and the lights break; Bash: a distant roar; Scribbles: the screen shakes;
 *             Surge: lightning flashes)
 *   APPROACH  it comes closer; the screen dims and starts to shake. Tap the button to hide
 *   PASS      it crosses the screen (Ambush/Blitz rebound and cross again); at the middle of
 *             every pass it checks if you are hiding. Surge instead hovers and zaps anyone
 *             who is not sheltered for about ten seconds
 *   CLEAR     it is gone, the lights come back, you step out
 *   JUMPSCARE if you were not hiding (see [Js])
 *
 * While you are hidden in a Rush or Ambush encounter, [Hide] [hideTicker] starts its own timer:
 * hide too long and it throws you out and locks the closet for ten seconds.
 * A fake-out is WARNING plus a short burst of the fake-out sound, then nothing (Rush only).
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

    /** Fired when the entity catches you: (entity, escalating death number, kicked out by Hide). */
    var onCaught: ((Entity, Int, Boolean) -> Unit)? = null

    val active get() = st.phase != Phase.NONE

    private var far: Sfx? = null
    private var near: Sfx? = null
    private var pinkFar: Sfx? = null    // Blitz: the sound as it rebounds into its pink form
    private var pinkNear: Sfx? = null   // Blitz: the pink form crossing the screen
    private var warn: Sfx? = null
    private var bulbBreak: Sfx? = null
    private var fakeout: Sfx? = null
    private var scare: Sfx? = null
    private var kill: Sfx? = null
    private var staticSfx: Sfx? = null
    private var doorOpen: Sfx? = null
    private var hideIn: Sfx? = null
    private var hideOut: Sfx? = null
    private var heartbeat: Sfx? = null
    private var hideStatic: Sfx? = null
    private var hideScare: Sfx? = null
    private var surgeS2: Sfx? = null    // Surge: second sky flash
    private var surgeS3: Sfx? = null    // Surge: third flash, the screen turns yellow

    private var approachStart = 0L
    private var approachMs = 1L
    private var closetBusyUntil = 0L
    private var lastToggle = 0L
    private var fading = false
    private var fadeStart = 0L
    private var fadeMs = 1000f

    /** Passes still to run, counting the current one (Ambush/Blitz rebound). */
    private var passesLeft = 1

    /** Did Hide throw you out during this encounter? (picks the combo death message) */
    private var kickedByHide = false

    /** Surge: how many zaps you have taken this encounter. */
    private var surgeZaps = 0

    // ---------------------------------------------------------------- start

    /**
     * [allowFake]: automatic Rush encounters may roll a fake-out; a manual call never does.
     * [entity]: which one to send; [RushService] picks it by the configured weights.
     */
    fun trigger(entity: Entity, allowFake: Boolean) {
        if (active || !Settings.canDrawOverlays(context)) return
        val fake = allowFake && entity == Entity.RUSH && Random.nextInt(100) < cfg.fakeoutChance
        reset()
        st.entity = entity
        cfg.bumpEncounter(entity)
        st.seed = SystemClock.uptimeMillis()
        passesLeft = when (entity) {
            // Ambush: 2-3 round trips (forward + back each). Always an even number of passes so
            // it can only despawn on a backward pass.
            Entity.AMBUSH -> roll(listOf(60 to 4, 40 to 6))
            // Blitz: rebounds 2-3 times (green -> pink -> green [-> pink])
            Entity.BLITZ -> roll(listOf(50 to 3, 50 to 4))
            else -> 1
        }
        loadSounds(entity, fake)
        st.fakeout = fake
        overlay.setOnHideTap { toggleHide() }
        overlay.showScene(SceneMode.PASS_THROUGH)
        overlay.showHideButton()

        if (entity == Entity.SURGE) { startSurgeSequence(); return }

        val warnMs = when (entity) {
            Entity.RUSH, Entity.BLITZ -> FLICKER_MS
            Entity.AMBUSH -> SCREAM_MS
            Entity.BASH -> ROAR_MS
            Entity.SCRIBBLES -> SHAKE_MS
            Entity.SURGE -> BOLT_MS
        }
        st.begin(Phase.WARNING, warnMs)
        when (entity) {
            Entity.RUSH, Entity.BLITZ -> warn?.play()
            // the entity's own voice fades in from silence as it spawns (distance), not full at once
            Entity.AMBUSH -> { bulbBreak?.setVolume(0.8f); bulbBreak?.play(); warn?.setVolume(0f); warn?.play(); rampWarn(1f, warnMs) }
            Entity.BASH -> { warn?.setVolume(0f); warn?.play(); rampWarn(0.55f, warnMs) }
            Entity.SCRIBBLES -> { st.shake = 0.25f; warn?.setVolume(0f); warn?.play(); rampWarn(1f, warnMs) }
            Entity.SURGE -> {
                warn?.play()
                h.postDelayed({ if (Random.nextBoolean()) warn?.play() }, BOLT_MS / 2)
            }
        }
        haptics.warning()
        if (fake) {
            fakeout?.play()
            h.postDelayed({ fadeFakeout(0) }, FAKE_FADE_AT)
        } else {
            h.postDelayed(::startApproach, warnMs)
        }
    }

    /** Fades the warning sound from silence to [base] over the warning phase. */
    private fun rampWarn(base: Float, ms: Long) {
        val t0 = SystemClock.uptimeMillis()
        h.post(object : Runnable {
            override fun run() {
                if (st.phase == Phase.NONE) return
                val p = ((SystemClock.uptimeMillis() - t0).toFloat() / ms).coerceIn(0f, 1f)
                val inWarning = st.phase == Phase.WARNING
                warn?.setVolume(base * (if (inWarning) p else 1f))
                if (inWarning && p < 1f) h.postDelayed(this, 50)
            }
        })
    }

    /** Test mode: jump straight to Rush's jumpscare. */
    fun jumpscareNow() {
        if (active || !Settings.canDrawOverlays(context)) return
        reset()
        st.entity = Entity.RUSH
        loadSounds(Entity.RUSH, false)
        caught()
    }

    private fun reset() {
        h.removeCallbacksAndMessages(null)
        st.reset()
        fading = false
        closetBusyUntil = 0L
        lastToggle = 0L
        passesLeft = 1
        kickedByHide = false
        surgeZaps = 0
    }

    private fun loadSounds(entity: Entity, fake: Boolean) {
        releaseSounds()
        warn = Sfx(
            context,
            when (entity) {
                Entity.RUSH, Entity.BLITZ -> "bulb_flicker"
                Entity.AMBUSH -> "ambush_warning"
                Entity.BASH -> "bash_que"
                Entity.SCRIBBLES -> "scribbles_wind"
                Entity.SURGE -> "surge_s1"
            }
        )
        hideIn = Sfx(context, "hide_enter").also { it.preload() }
        hideOut = Sfx(context, "hide_exit").also { it.preload() }
        heartbeat = Sfx(context, "hide_heartbeat")
        hideStatic = Sfx(context, "hide_static")
        hideScare = Sfx(context, "hide_scare").also { it.preload() }
        if (fake) {
            fakeout = Sfx(context, "fakeout")
            scare = Sfx(context, "rush_scare").also { it.preload() }
            kill = Sfx(context, "rush_kill").also { it.preload() }
            staticSfx = Sfx(context, "rush_static").also { it.preload() }
            return
        }
        when (entity) {
            Entity.RUSH -> {
                far = Sfx(context, "rush_far").also { it.setVolume(0f) }
                near = Sfx(context, "rush_near").also { it.setVolume(0f) }
                bulbBreak = Sfx(context, "bulb_break")
                doorOpen = Sfx(context, "door_open")
                scare = Sfx(context, "rush_scare").also { it.preload() }
                kill = Sfx(context, "rush_kill").also { it.preload() }
                staticSfx = Sfx(context, "rush_static").also { it.preload() }
            }
            Entity.AMBUSH -> {
                far = Sfx(context, "ambush_far").also { it.setVolume(0f) }
                near = Sfx(context, "ambush_near").also { it.setVolume(0f) }
                scare = Sfx(context, "ambush_jumpscare").also { it.preload() }
                kill = Sfx(context, "ambush_kill").also { it.preload() }
                staticSfx = Sfx(context, "rush_static").also { it.preload() }
            }
            Entity.BLITZ -> {
                far = Sfx(context, "blitz_far").also { it.setVolume(0f) }
                near = Sfx(context, "blitz_near").also { it.setVolume(0f) }
                pinkFar = Sfx(context, "blitz_far_pink").also { it.preload() }
                pinkNear = Sfx(context, "blitz_near_pink").also { it.setVolume(0f); it.preload() }
                bulbBreak = Sfx(context, "bulb_break")
                scare = Sfx(context, "blitz_jumpscare").also { it.preload() }
                kill = Sfx(context, "rush_kill").also { it.preload() } // canon: Blitz shares Rush's kill
                staticSfx = Sfx(context, "rush_static").also { it.preload() }
            }
            Entity.BASH -> {
                far = Sfx(context, "bash_rumble").also { it.setVolume(0f) }  // the rumble loop
                near = Sfx(context, "bash_far").also { it.setVolume(0f) }     // the far roar under it
                scare = Sfx(context, "bash_close").also { it.preload() }
                kill = Sfx(context, "bash_que").also { it.preload() }
                staticSfx = Sfx(context, "rush_static").also { it.preload() }
            }
            Entity.SCRIBBLES -> {
                far = Sfx(context, "scribbles_ambience").also { it.setVolume(0f) }
                near = Sfx(context, "scribbles_close").also { it.setVolume(0f) }
                scare = Sfx(context, "scribbles_close").also { it.preload() }
                kill = Sfx(context, "scribbles_close").also { it.preload() }
                staticSfx = Sfx(context, "rush_static").also { it.preload() }
            }
            Entity.SURGE -> {
                // warn = Surge_1 (made above), then Surge_2, Surge_3, the attack, the idle loop
                surgeS2 = Sfx(context, "surge_s2").also { it.preload() }
                surgeS3 = Sfx(context, "surge_s3").also { it.preload() }
                kill = Sfx(context, "surge_attack").also { it.preload() }     // SurgeattackLOUD
                near = Sfx(context, "surge_idle").also { it.preload() }       // surgeidleLOUD
                doorOpen = Sfx(context, "surge_close")                        // the zap on each hit
                scare = Sfx(context, "surge_scare").also { it.preload() }
                staticSfx = Sfx(context, "rush_static").also { it.preload() }
            }
        }
        far?.preload(); near?.preload()
    }

    private fun releaseSounds() {
        listOf(
            far, near, pinkFar, pinkNear, warn, bulbBreak, fakeout, scare, kill, staticSfx, doorOpen,
            hideIn, hideOut, heartbeat, hideStatic, hideScare, surgeS2, surgeS3
        ).forEach { it?.release() }
        far = null; near = null; pinkFar = null; pinkNear = null; warn = null; bulbBreak = null; fakeout = null
        scare = null; kill = null; staticSfx = null; doorOpen = null
        hideIn = null; hideOut = null; heartbeat = null; hideStatic = null; hideScare = null
        surgeS2 = null; surgeS3 = null
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
        approachMs = when (st.entity) {
            Entity.RUSH -> 4500L + Random.nextInt(4500)
            Entity.AMBUSH -> 9000L + Random.nextInt(2000)   // ~10 s before the first rush
            Entity.BLITZ -> 2500L + Random.nextInt(2000)
            Entity.BASH -> 2000L + Random.nextInt(1500)   // nearly double Rush's speed
            Entity.SCRIBBLES -> 3500L + Random.nextInt(2000)
            Entity.SURGE -> 3000L
        }
        approachStart = SystemClock.uptimeMillis()
        st.begin(Phase.APPROACH, approachMs)
        when (st.entity) {
            Entity.BASH -> { far?.playLooping(); near?.playLooping() }
            Entity.SURGE -> { near?.playLooping(); bulbBreak?.setVolume(0.5f); bulbBreak?.play() }
            Entity.BLITZ -> far?.playLooping() // its near sound comes in 2 s before it crosses (ticker)
            Entity.AMBUSH -> { ambushNearOn = false; far?.playLooping() } // near starts 1 s before it crosses
            else -> { far?.playLooping(); near?.playLooping() }
        }
        haptics.threat(true)
        h.post(ticker)
        h.postDelayed(::startPass, approachMs)
    }

    /** Volume / shake ramps while it is coming (every ~50 ms until the pass starts). */
    private val ticker = object : Runnable {
        override fun run() {
            if (st.phase != Phase.APPROACH) return
            val elapsed = SystemClock.uptimeMillis() - approachStart
            val remaining = approachMs - elapsed
            val p = (elapsed.toFloat() / approachMs).coerceIn(0f, 1f)
            when (st.entity) {
                Entity.AMBUSH -> {
                    // silent at the spawn, 100% two seconds before it arrives, near sound 1 s before
                    far?.setVolume((elapsed.toFloat() / (approachMs - AMBUSH_FULL_LEAD_MS)).coerceIn(0f, 1f))
                    if (remaining <= AMBUSH_NEAR_LEAD_MS) startAmbushNear()
                }
                Entity.BLITZ -> {
                    far?.setVolume(p) // silent at the spawn, full when it is about to cross
                    if (remaining <= BLITZ_NEAR_LEAD_MS) {
                        near?.setVolume(1f - remaining.toFloat() / BLITZ_NEAR_LEAD_MS)
                        near?.playLooping()
                    }
                }
                else -> {
                    far?.setVolume(p)
                    near?.setVolume(((p - 0.5f) / 0.5f).coerceIn(0f, 1f))
                }
            }
            st.shake = (((p - 0.8f) / 0.2f).coerceIn(0f, 1f)) * (if (st.entity == Entity.BASH) 0.7f else 0.5f)
            h.postDelayed(this, 50)
        }
    }

    private var ambushNearOn = false

    /** Ambush's near sound, from its start, at full volume. */
    private fun startAmbushNear() {
        if (ambushNearOn) return
        ambushNearOn = true
        near?.setVolume(1f)
        near?.play()        // from the beginning
        near?.playLooping() // then keep going
    }

    // ---------------------------------------------------------------- the pass

    private fun startPass() {
        if (st.phase != Phase.APPROACH) return
        when (st.entity) {
            Entity.SURGE -> Unit // Surge has its own timeline (startSurgeSequence)
            else -> startCrossing()
        }
    }

    /** Rush / Ambush / Blitz / Bash / Scribbles cross the screen; the rebounders come back. */
    private fun startCrossing() {
        val speedMult = when (st.entity) {
            Entity.BASH -> 0.5f       // nearly double speed
            Entity.BLITZ -> 0.7f
            Entity.SCRIBBLES -> 0.8f
            else -> 1f
        }
        val passMs = if (st.entity == Entity.SCRIBBLES) SCRIBBLE_CROSS_MIN_MS + Random.nextLong(SCRIBBLE_CROSS_SPAN_MS)
        else (9000f / cfg.rushSpeed * speedMult).toLong().coerceIn(180L, 3000L)
        st.passDir = if (st.passIndex == 0) (if (Random.nextBoolean()) 1 else -1) else -st.passDir
        st.shake = 1f
        st.begin(Phase.PASS, passMs)
        applyMode()
        far?.setVolume(1f)
        near?.setVolume(1f)
        when (st.entity) {
            Entity.RUSH -> { bulbBreak?.setVolume(0.8f); bulbBreak?.play() }
            Entity.BLITZ -> {
                bulbBreak?.setVolume(0.8f); bulbBreak?.play()
                // green passes use the near sound, pink (rebounded) passes the pink one
                if (st.pinkForm) {
                    near?.pause()
                    pinkNear?.setVolume(1f); pinkNear?.playLooping()
                } else {
                    pinkNear?.pause()
                    near?.setVolume(1f); near?.playLooping()
                }
            }
            Entity.AMBUSH -> if (st.passIndex > 0) warn?.play() // the scream again: it is coming back
            Entity.BASH -> { warn?.setVolume(1f); warn?.play() } // the roar, now on top of you
            Entity.SCRIBBLES -> Unit                              // the close loop takes over via near volume
            Entity.SURGE -> Unit
        }
        // Scribbles never "catches" you at the middle: it throws papers for as long as it is in the room
        if (st.entity == Entity.SCRIBBLES) h.post(scribblesThrow) else h.postDelayed(::decide, passMs / 2)
        h.postDelayed(::endPass, passMs)
    }

    // ---------------------------------------------------------------- scribbles

    /** A paper every 200-400 ms while it crosses; each takes [SCRIBBLE_FLIGHT_MS] to reach you. */
    private val scribblesThrow = object : Runnable {
        override fun run() {
            if (st.phase != Phase.PASS || st.entity != Entity.SCRIBBLES) return
            val now = SystemClock.uptimeMillis()
            st.throws.removeAll { now - it.at > SCRIBBLE_FLIGHT_MS + 100 }
            st.throws.add(RushState.Throw(now, Random.nextInt(7)))
            h.postDelayed({ scribblesHit() }, SCRIBBLE_FLIGHT_MS)
            h.postDelayed(this, SCRIBBLE_THROW_MIN_MS + Random.nextLong(SCRIBBLE_THROW_SPAN_MS))
        }
    }

    /** The paper arrives: hiding protects you; otherwise 10 damage and a paper cut on the screen. */
    private fun scribblesHit() {
        if (st.phase != Phase.PASS || st.hidden) return
        val now = SystemClock.uptimeMillis()
        st.health -= SCRIBBLE_DAMAGE
        st.cuts.removeAll { now - it.at > it.fadeMs }
        st.cuts.add(RushState.Cut(now, SCRIBBLE_CUT_MIN_MS + Random.nextLong(SCRIBBLE_CUT_SPAN_MS), Random.nextLong()))
        haptics.tap()
        if (st.health <= 0) caught() // no jumpscare: straight to the death sequence
    }

    /** It is at the middle of the screen: hiding or not? Asked on every pass. */
    private fun decide() {
        if (st.phase != Phase.PASS) return
        if (!st.hidden) caught()
    }

    private fun endPass() {
        if (st.phase != Phase.PASS) return
        passesLeft--
        if (passesLeft > 0) {
            // rebound: a short off-screen pause, then it comes back the other way
            st.passIndex++
            // Ambush takes its time coming back from a rebound (5-12 s); the rest are quick
            // Blitz: near sound 2 s after it passed, far sound for a random 2-4 s, then the next
            // near sound 2 s before it crosses again
            val farMs = BLITZ_FAR_MIN_MS + Random.nextLong(BLITZ_FAR_SPAN_MS)
            val gapMs = when (st.entity) {
                // forward -> back is a quick turn (0.5 s); back -> forward is the long wait (5-10 s)
                Entity.AMBUSH -> if (st.passIndex % 2 == 1) AMBUSH_REBOUND_MS
                else AMBUSH_RETURN_MIN_MS + Random.nextLong(AMBUSH_RETURN_SPAN_MS)
                Entity.BLITZ -> BLITZ_NEAR_TAIL_MS + farMs + BLITZ_NEAR_LEAD_MS
                else -> REBOUND_MS
            }
            st.begin(Phase.APPROACH, gapMs)
            // Volume follows the distance: full at the crossings, quietest at the turnaround, but
            // not necessarily silent there.
            gapStart = SystemClock.uptimeMillis()
            gapLen = gapMs
            gapQuick = st.entity == Entity.AMBUSH && st.passIndex % 2 == 1
            when (st.entity) {
                Entity.BLITZ -> {
                    val nextPink = st.passIndex % 2 == 1   // green -> pink -> green -> ...
                    st.pinkForm = nextPink
                    far?.pause()
                    // the near sound keeps playing for 2 s after it passed, then the far sound
                    h.postDelayed({
                        near?.pause(); pinkNear?.pause()
                        if (nextPink) pinkFar?.play() else far?.play()   // rebound sound / back to green
                        applyGapVolume()
                    }, BLITZ_NEAR_TAIL_MS)
                    // and the next near sound starts 2 s before it crosses again
                    h.postDelayed({
                        if (nextPink) pinkNear?.playLooping() else near?.playLooping()
                        applyGapVolume()
                    }, BLITZ_NEAR_TAIL_MS + farMs)
                    h.post(gapTicker)
                }
                Entity.AMBUSH -> h.post(gapTicker)
                else -> Unit
            }
            h.postDelayed(::startCrossing, gapMs)
            return
        }
        haptics.threat(false)
        st.shake = 0f
        st.begin(Phase.CLEAR, 700)
        applyMode()
        if (st.entity != Entity.SURGE) doorOpen?.play()
        fading = true
        fadeStart = SystemClock.uptimeMillis()
        fadeMs = if (st.entity == Entity.BLITZ) BLITZ_NEAR_TAIL_MS.toFloat() else 1000f
        h.post(fadeOutRush)
        h.postDelayed(::stepOut, if (st.entity == Entity.BLITZ) BLITZ_NEAR_TAIL_MS + 100 else 1300L)
    }

    private var gapStart = 0L
    private var gapLen = 1L
    private var gapQuick = false

    /** 1 at the crossings, [REBOUND_FLOOR] at the turnaround halfway through the gap. */
    private fun applyGapVolume() {
        val u = ((SystemClock.uptimeMillis() - gapStart).toFloat() / gapLen).coerceIn(0f, 1f)
        val v = 1f - (1f - REBOUND_FLOOR) * kotlin.math.sin(Math.PI.toFloat() * u)
        if (st.entity == Entity.AMBUSH) {
            val el = SystemClock.uptimeMillis() - gapStart
            if (gapQuick) {
                // the 0.5 s turn: near dips to 50% in 0.25 s and is back at 100% in 0.25 s
                far?.setVolume(1f)
                near?.setVolume(if (el < 250L) 1f - 0.5f * el / 250f else 0.5f + 0.5f * ((el - 250L) / 250f).coerceIn(0f, 1f))
            } else {
                // the long wait: distance-based, back at 100% two seconds before it arrives
                val u = (el.toFloat() / (gapLen - AMBUSH_FULL_LEAD_MS).coerceAtLeast(1L)).coerceIn(0f, 1f)
                far?.setVolume(1f - (1f - REBOUND_FLOOR) * kotlin.math.sin(Math.PI.toFloat() * u))
                if (el >= gapLen - AMBUSH_NEAR_LEAD_MS) {
                    startAmbushNear() // 1 s before it arrives
                } else if (ambushNearOn) {
                    near?.setVolume((1f - el / 1000f).coerceIn(0f, 1f)) // it fades as it moves away
                    if (el >= 1000L) { near?.pause(); ambushNearOn = false }
                }
            }
        } else {
            far?.setVolume(v); near?.setVolume(v); pinkFar?.setVolume(v); pinkNear?.setVolume(v)
        }
    }

    private val gapTicker = object : Runnable {
        override fun run() {
            if (st.phase != Phase.APPROACH) return
            applyGapVolume()
            h.postDelayed(this, if (gapQuick) 50L else 100L)
        }
    }

    private val fadeOutRush = object : Runnable {
        override fun run() {
            if (!fading) return
            val p = ((SystemClock.uptimeMillis() - fadeStart) / fadeMs).coerceIn(0f, 1f)
            far?.setVolume(1f - p)
            near?.setVolume(1f - p)
            pinkNear?.setVolume(1f - p)
            if (p >= 1f) { far?.pause(); near?.pause(); pinkNear?.pause(); fading = false } else h.postDelayed(this, 50)
        }
    }

    /** Safe: leave the closet (if still in it), then wrap up. */
    private fun stepOut() {
        if (st.phase != Phase.CLEAR) return
        // let a GET OUT in progress (or Hide's ejection flash) play out before wrapping up
        val kickFresh = st.hideKickAt != 0L && SystemClock.uptimeMillis() - st.hideKickAt < 1300L
        if (st.hideAttack || kickFresh) {
            h.postDelayed(::stepOut, 100)
            return
        }
        if (st.hidden) {
            setHidden(false)
            h.postDelayed(::finish, RushState.CLOSET_MS + 150)
        } else {
            finish()
        }
    }

    // ---------------------------------------------------------------- surge

    /**
     * Surge's own timeline: three sky flashes (Surge_1/2/3), the screen turns yellow and the
     * vignette fades in unless you are hiding, then the attack sound plays while it drops from the
     * sky; shortly after, the idle sound starts and it flies around you (hidden) or at you
     * (exposed, damaging you). When the idle sound has finished it gives up and flies back up.
     */
    private fun startSurgeSequence() {
        st.begin(Phase.WARNING, SURGE_STAGE_MS)
        st.surgeStage = 1
        st.surgeStageAt = SystemClock.uptimeMillis()
        warn?.play() // Surge_1
        haptics.warning()
        h.postDelayed(::surgeStage2, SURGE_STAGE_MS)
    }

    private fun surgeStage2() {
        if (st.phase != Phase.WARNING) return
        st.begin(Phase.APPROACH, SURGE_STAGE_MS + SURGE_ATTACK_DELAY_MS)
        st.surgeStage = 2
        st.surgeStageAt = SystemClock.uptimeMillis()
        surgeS2?.play()
        haptics.threat(true)
        h.postDelayed(::surgeStage3, SURGE_STAGE_MS)
    }

    private fun surgeStage3() {
        if (st.phase != Phase.APPROACH) return
        st.surgeStage = 3
        st.surgeStageAt = SystemClock.uptimeMillis()
        surgeS3?.play()
        fadeSurgeVignette(1f)
        st.shake = 0.2f
        h.postDelayed(::surgeAttack, SURGE_ATTACK_DELAY_MS)
    }

    private fun surgeAttack() {
        if (st.phase != Phase.APPROACH) return
        st.begin(Phase.PASS, SURGE_IDLE_AFTER_MS + SURGE_IDLE_MS + SURGE_LEAVE_MS)
        st.surgeStage = 4
        st.surgeStageAt = SystemClock.uptimeMillis()
        applyMode()
        kill?.play() // SurgeattackLOUD while it drops from the sky
        h.postDelayed(::surgeIdle, SURGE_IDLE_AFTER_MS)
    }

    private fun surgeIdle() {
        if (st.phase != Phase.PASS) return
        st.surgeIdleAt = SystemClock.uptimeMillis()
        near?.play() // surgeidleLOUD, once
        st.shake = 0.15f
        h.post(surgeTick)
        h.postDelayed(::surgeLeave, SURGE_IDLE_MS)
    }

    private fun surgeLeave() {
        if (st.phase != Phase.PASS) return
        st.surgeStage = 5
        st.surgeStageAt = SystemClock.uptimeMillis()
        st.shake = 0f
        fadeSurgeVignette(0f)
        h.postDelayed(::endPass, SURGE_LEAVE_MS)
    }

    private fun fadeSurgeVignette(target: Float) {
        val now = SystemClock.uptimeMillis()
        st.surgeVigFrom = st.surgeVigLevel(now)
        st.surgeVigTarget = target
        st.surgeVigAt = now
    }

    /** 100 HP / 40 damage per zap: the third zap finishes you. Only while it is flying at/around you. */
    private val surgeTick = object : Runnable {
        override fun run() {
            if (st.phase != Phase.PASS || st.surgeStage != 4) return
            if (!st.hidden) {
                surgeZaps++
                doorOpen?.play()
                haptics.tap()
                st.shake = 0.6f
                if (surgeZaps >= 3) { caught(); return }
            } else {
                st.shake = 0.15f
            }
            h.postDelayed(this, SURGE_TICK_MS)
        }
    }

    // ---------------------------------------------------------------- Hide

    /**
     * Runs while you are hidden in a Rush or Ambush encounter: after ~12 s (a little longer vs
     * Ambush) the screen turns red and "GET OUT" flashes; stay put and Hide throws you out and
     * locks the closet for ten seconds. Never during Blitz / Bash / Scribbles / Surge.
     */
    private val hideTicker = object : Runnable {
        override fun run() {
            if (!st.hidden || st.phase == Phase.JUMPSCARE || st.phase == Phase.NONE) return
            val hideAllowed = st.entity == Entity.RUSH || st.entity == Entity.AMBUSH
            if (!hideAllowed || !cfg.hideEnabled) return
            val grace = if (st.entity == Entity.AMBUSH) HIDE_GRACE_AMBUSH_MS else HIDE_GRACE_MS
            val elapsed = SystemClock.uptimeMillis() - st.hideSince
            if (!st.hideAttack && elapsed >= grace) {
                st.hideAttack = true
                heartbeat?.playLooping()
                hideStatic?.playLooping()
                haptics.threat(true)
            }
            if (st.hideAttack && elapsed >= grace + HIDE_ATTACK_MS) {
                ejectFromCloset()
                return
            }
            h.postDelayed(this, 100)
        }
    }

    private fun ejectFromCloset() {
        kickedByHide = true
        st.hideAttack = false
        heartbeat?.pause()
        hideStatic?.pause()
        hideScare?.play()
        haptics.jumpscare()
        setHidden(false)
        st.hideKickAt = SystemClock.uptimeMillis()
        st.hideLockUntil = SystemClock.uptimeMillis() + HIDE_LOCK_MS
        h.postDelayed({ overlay.refreshButton() }, HIDE_LOCK_MS) // back to normal once the lock ends
    }

    // ---------------------------------------------------------------- closet

    fun toggleHide() {
        val now = SystemClock.uptimeMillis()
        if (!active || st.phase == Phase.JUMPSCARE || now - lastToggle < 450L) return
        if (!st.hidden && now < st.hideLockUntil) return // "Something is stopping you from hiding..."
        lastToggle = now
        setHidden(!st.hidden)
    }

    private fun setHidden(hide: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (hide) {
            st.hideSince = now
            h.removeCallbacks(hideTicker)
            h.post(hideTicker)
        } else {
            st.hideSince = 0L
            h.removeCallbacks(hideTicker)
            if (st.hideAttack) { // you got out in time; the red lingers a moment
                st.hideAttack = false
                heartbeat?.pause()
                hideStatic?.pause()
            }
        }
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
            st.phase == Phase.PASS && st.entity != Entity.SURGE -> SceneMode.FX
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
        listOf(far, near, pinkFar, pinkNear, warn, bulbBreak, fakeout, heartbeat, hideStatic, surgeS2, surgeS3)
            .forEach { it?.pause() }
        if (st.entity == Entity.SURGE) kill?.pause() // the long attack sound
        st.hideAttack = false
        st.shake = 0f
        st.hidden = false
        st.surgeStage = 0
        overlay.hideHideButton()
        overlay.showScene(SceneMode.BLOCK)
        overlay.setSceneMode(SceneMode.BLOCK)

        val deathNo = cfg.deaths(st.entity) + 1
        cfg.bumpDeath(st.entity)

        when (st.entity) {
            Entity.RUSH -> rushJumpscare(deathNo)
            Entity.AMBUSH -> ambushJumpscare(deathNo)
            Entity.BLITZ -> blitzJumpscare(deathNo)
            Entity.SURGE -> surgeJumpscare(deathNo)
            // no jumpscare: straight to the shared death sequence
            Entity.BASH -> { kill?.play(); startDeathSequence(deathNo) }
            Entity.SCRIBBLES -> startDeathSequence(deathNo)
        }
    }

    /** Rush: static, closed face, one step closer, then the screaming glitch rush (see [Js]). */
    private fun rushJumpscare(deathNo: Int) {
        st.begin(Phase.JUMPSCARE, Js.END_AT)
        staticSfx?.playLooping()
        h.postDelayed({
            staticSfx?.pause()
            scare?.play()
            haptics.jumpscare()
        }, Js.SCREAM_AT)
        h.postDelayed({ kill?.play() }, Js.HIT_AT)
        h.postDelayed({ finishKill(deathNo) }, Js.END_AT)
    }

    /** Ambush: no wait, no static: it rushes straight at you, zooming into the middle, then kills. */
    private fun ambushJumpscare(deathNo: Int) {
        st.begin(Phase.JUMPSCARE, Js.AMBUSH_MS)
        scare?.play()
        haptics.jumpscare()
        h.postDelayed({ kill?.play(); finishKill(deathNo) }, Js.AMBUSH_MS)
    }

    /** Blitz: face on dark, zoom out, shifting, then the flashing rush; then the death sequence. */
    private fun blitzJumpscare(deathNo: Int) {
        st.begin(Phase.JUMPSCARE, Js.BLITZ_FRAMES * Js.FRAME_MS)
        h.postDelayed({ scare?.play(); haptics.jumpscare() }, Js.BLITZ_SOUND_FRAME * Js.FRAME_MS)
        h.postDelayed({ startDeathSequence(deathNo) }, Js.BLITZ_FRAMES * Js.FRAME_MS)
    }

    /** Surge: its three faces flash in front of you on the jumpscare background; then the death sequence. */
    private fun surgeJumpscare(deathNo: Int) {
        st.begin(Phase.JUMPSCARE, Js.SURGE_FRAMES * Js.FRAME_MS)
        scare?.play()
        haptics.jumpscare()
        h.postDelayed({ startDeathSequence(deathNo) }, Js.SURGE_FRAMES * Js.FRAME_MS)
    }

    /**
     * Shared death sequence (everything but Rush and Ambush): the screen is visible again for
     * [Js.DEATH_FRAMES] frames while a red outline closes in until it has consumed the screen, and
     * only then do the death consequences fire.
     */
    private fun startDeathSequence(deathNo: Int) {
        st.begin(Phase.DEATH, Js.DEATH_FRAMES * Js.FRAME_MS)
        h.postDelayed({ finishKill(deathNo) }, Js.DEATH_FRAMES * Js.FRAME_MS)
    }

    private fun finishKill(deathNo: Int) {
        onCaught?.invoke(st.entity, deathNo, kickedByHide)
        h.postDelayed(::finish, 700)
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

    /** Weighted pick from (weight -> value) pairs, e.g. listOf(40 to 1, 40 to 2, 20 to 3). */
    private fun roll(options: List<Pair<Int, Int>>): Int {
        val total = options.sumOf { it.first }
        var r = Random.nextInt(total)
        for ((w, v) in options) {
            if (r < w) return v
            r -= w
        }
        return options.last().second
    }

    companion object {
        const val FLICKER_MS = 1700L      // Rush / Blitz warning
        const val SCREAM_MS = 1200L       // Ambush arrival scream
        const val ROAR_MS = 2000L         // Bash distant roar
        const val SHAKE_MS = 1600L        // Scribbles pre-shake
        const val BOLT_MS = 1500L         // Surge warning lightning
        const val FAKE_FADE_AT = 2600L
        const val BLITZ_NEAR_LEAD_MS = 2_000L     // near sound starts this long before it crosses
        const val BLITZ_NEAR_TAIL_MS = 2_000L     // ...and keeps going this long after it passed
        const val BLITZ_FAR_MIN_MS = 2_000L       // far sound between passes: random 2-4 s
        const val BLITZ_FAR_SPAN_MS = 2_001L
        const val REBOUND_FLOOR = 0.3f            // quietest a rebounder gets at the turnaround
        const val SCRIBBLE_CROSS_MIN_MS = 10_000L // crossing an average room: 10-20 s
        const val SCRIBBLE_CROSS_SPAN_MS = 10_001L
        const val SCRIBBLE_THROW_MIN_MS = 200L    // a paper every 200-400 ms
        const val SCRIBBLE_THROW_SPAN_MS = 201L
        const val SCRIBBLE_FLIGHT_MS = 250L       // how long a paper takes to reach you (my choice)
        const val SCRIBBLE_DAMAGE = 10
        const val SCRIBBLE_CUT_MIN_MS = 2_000L    // a paper cut fades after 2-5 s
        const val SCRIBBLE_CUT_SPAN_MS = 3_001L
        const val REBOUND_MS = 700L       // off-screen pause between passes
        const val AMBUSH_RETURN_MIN_MS = 5_000L   // Ambush's wait after a backward pass: 5-10 s
        const val AMBUSH_RETURN_SPAN_MS = 5_001L
        const val AMBUSH_REBOUND_MS = 500L        // forward -> backward turn
        const val AMBUSH_FULL_LEAD_MS = 2_000L    // 100% volume this long before it arrives
        const val AMBUSH_NEAR_LEAD_MS = 1_000L    // near sound starts this long before it arrives

        // Surge (timings given for the real game; "a few seconds" gaps are my guess, see the notes)
        const val SURGE_STAGE_MS = 3_000L         // gap between the three flashes
        const val SURGE_ATTACK_DELAY_MS = 3_000L  // from the third flash to the attack sound
        const val SURGE_DESCENT_MS = 1_250L       // it drops from the sky
        const val SURGE_IDLE_AFTER_MS = 1_455L    // attack sound -> idle sound
        const val SURGE_IDLE_MS = 7_473L          // length of surgeidleLOUD
        const val SURGE_LEAVE_MS = 1_250L         // flying back up
        const val SURGE_TICK_MS = 700L            // one zap per tick when uncovered

        const val HIDE_GRACE_MS = 12_000L        // ~12 s hidden before Hide wakes up
        const val HIDE_GRACE_AMBUSH_MS = 14_000L // Hide waits a bit longer vs Ambush
        const val HIDE_ATTACK_MS = 3000L         // the GET OUT window
        const val HIDE_LOCK_MS = 10_000L         // cannot re-hide after the ejection
    }
}
