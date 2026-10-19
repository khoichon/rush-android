package com.khoichon.rush

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.AnimatedImageDrawable
import android.os.SystemClock
import android.view.View
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** Jumpscare timeline, in ms from the moment the entity catches you. */
object Js {
    const val FACE_MS = 1000L      // closed face, still, on black with static
    const val WAIT_MS = 700L       // ...it steps in one small notch, then waits
    const val SCREAM_AT = FACE_MS + WAIT_MS   // open mouth + scream + glitch rush
    const val STEPS = 9            // the rush at you is in jerky steps, not a smooth zoom
    const val STEP_MS = 42L
    const val HIT_AT = SCREAM_AT + STEPS * STEP_MS       // it reaches you
    const val END_AT = HIT_AT + 300L                     // death consequences fire here
    const val CREEP_SCALE = 1.2f   // the one small step closer
    const val FINAL_SCALE = 7f

    // The other entities, timed in frames of the 25 fps reference recordings
    const val FRAME_MS = 40L
    const val AMBUSH_MS = 862L          // length of Ambushjumpscare.mp3: the straight rush at you
    const val BLITZ_FRAMES = 13         // face on dark, zoom out, shifting, then the flashing zoom in
    const val BLITZ_SOUND_FRAME = 9     // BlitzJumpscareNew.mp3 starts with the flashing zoom in
    const val SURGE_FRAMES = 17         // its three faces flashing in front of you
    const val DEATH_FRAMES = 7          // the shared red outline closing in on the screen
}

/**
 * The full-screen overlay. Draws, depending on [RushState.phase] and [RushState.entity]:
 *  - the world: the entity's own warning (flickering or broken lights, a distant roar, a
 *    pre-shake, lightning), the dimming / shaking as it comes, its crossing (or hovering, for
 *    Surge), the flying paper for Scribbles, the fire glow for Bash, and the closet doors
 *    (two black bars closing in from the sides, a thin line of light left in the middle);
 *  - Hide's attack: a pulsating red vignette and flashing "GET OUT" while you overstay;
 *  - the jumpscare: black, static, the face creeping closer, then the glitching rush at you.
 * Everything is a pure function of the state and the clock, so the engine never has to animate.
 */
class SceneView(context: Context, private val st: RushState) : View(context) {
    private val density = resources.displayMetrics.density

    private val solid = Paint().apply { color = Color.BLACK }
    private val dim = Paint().apply { color = Color.BLACK }
    private val staticP = Paint()
    private val bmp = Paint().apply { isFilterBitmap = false }
    private val smoothP = Paint().apply { isFilterBitmap = true }
    private val redP = Paint().apply {
        isFilterBitmap = false
        colorFilter = LightingColorFilter(0xFFFF0000.toInt(), 0)
        alpha = 215
    }
    private val cyanP = Paint().apply {
        isFilterBitmap = false
        colorFilter = LightingColorFilter(0xFF00FFFF.toInt(), 0)
        alpha = 215
    }
    private val tintP = Paint().apply { isFilterBitmap = true }
    private val redFill = Paint().apply { color = Color.RED }
    private var fireP: Paint? = null   // radial orange glow, built once the size is known

    private val rnd = Random(System.nanoTime())
    private val rect = RectF()
    private val srcR = android.graphics.Rect()

    // ---------------------------------------------------------------- sprites (lazy, cached)

    private val bitmaps = HashMap<String, Bitmap?>()
    private val anims = HashMap<String, AnimatedImageDrawable?>()
    private var movingOn: AnimatedImageDrawable? = null

    private fun bitmap(path: String): Bitmap? = bitmaps.getOrPut(path) {
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.assets, path)) { dec, _, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    }

    private fun anim(path: String): AnimatedImageDrawable? = anims.getOrPut(path) {
        runCatching {
            (ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.assets, path))
                as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE }
        }.getOrNull()
    }

    private fun movingName(entity: Entity, pink: Boolean): String = when (entity) {
        Entity.RUSH -> "sprites/rush_moving.webp"
        Entity.AMBUSH -> "sprites/ambush_moving.webp"
        Entity.BLITZ -> if (pink) "sprites/blitz_moving_pink.webp" else "sprites/blitz_moving.webp"
        Entity.BASH -> "sprites/bash_moving.webp"
        Entity.SCRIBBLES -> "sprites/scribbles_moving.webp"
        Entity.SURGE -> "sprites/surge_f1.webp" // never used as a crossing sprite
    }

    private fun faceName(entity: Entity, pink: Boolean): String = when (entity) {
        Entity.RUSH -> "sprites/rush_face.webp"
        Entity.AMBUSH -> "sprites/ambush_face.webp"
        Entity.BLITZ -> if (pink) "sprites/blitz_face_pink.webp" else "sprites/blitz_face.webp"
        Entity.BASH -> "sprites/bash_face.webp"
        Entity.SCRIBBLES -> "sprites/scribbles_face.webp"
        Entity.SURGE -> "sprites/surge_face.webp"
    }

    private fun screamName(entity: Entity): String = when (entity) {
        Entity.RUSH -> "sprites/rush_scream.webp"
        Entity.AMBUSH -> "sprites/ambush_scream.webp"
        Entity.BLITZ -> "sprites/blitz_scream.webp"
        Entity.BASH -> "sprites/bash_scream.webp"
        Entity.SCRIBBLES -> "sprites/scribbles_scream.webp"
        Entity.SURGE -> "sprites/surge_f2.webp"
    }

    private fun stopMoving() {
        movingOn?.let { runCatching { it.stop() } }
        movingOn = null
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val w = width.toFloat()
        val h = height.toFloat()
        when (st.phase) {
            Phase.NONE -> { stopMoving(); return }
            Phase.JUMPSCARE -> { stopMoving(); drawJumpscare(c, w, h, now - st.phaseStart) }
            Phase.DEATH -> { stopMoving(); drawDeath(c, w, h, now - st.phaseStart) }
            else -> drawWorld(c, w, h, now)
        }
        // Battery: the long, slow parts (waiting, Scribbles' crossing, Surge's flight) do not need
        // 60+ fps; keep the full frame rate for fast movement, the closet doors and the jumpscares.
        val cl = st.closet(now)
        val slow = (st.phase == Phase.WARNING || st.phase == Phase.APPROACH ||
            (st.phase == Phase.PASS && (st.entity == Entity.SCRIBBLES || st.entity == Entity.SURGE))) &&
            !st.hideAttack && (cl <= 0f || cl >= 1f)
        if (slow) postInvalidateDelayed(24L) else postInvalidateOnAnimation()
    }

    private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    private fun drawWorld(c: Canvas, w: Float, h: Float, now: Long) {
        val t = now - st.phaseStart
        val p = (t.toFloat() / st.phaseDur).coerceIn(0f, 1f)
        val closet = st.closet(now)
        val e = st.entity

        c.save()
        if (st.shake > 0f) {
            val amp = st.shake * 7f * density
            c.translate((rnd.nextFloat() - 0.5f) * 2f * amp, (rnd.nextFloat() - 0.5f) * 2f * amp)
        }

        val gapHalf = lerp(w / 2f, 2.5f * density, smooth(closet))

        if (e == Entity.SCRIBBLES) drawWallPapers(c, w, h, p)
        drawLighting(c, w, h, t, p, closet, gapHalf, e)
        if (e == Entity.SURGE) drawSurgeEntity(c, w, h, now)
        else if (st.phase == Phase.PASS) drawPassing(c, w, h, p, e)

        // closet doors
        if (closet > 0f) {
            c.drawRect(-400f, -400f, w / 2f - gapHalf, h + 400f, solid)
            c.drawRect(w / 2f + gapHalf, -400f, w + 400f, h + 400f, solid)
        }
        drawCuts(c, w, h, now)
        // Hide goes on top of the doors, otherwise the closet hides it completely
        drawHideAttack(c, w, h, now)
        c.restore()
    }

    /** Lights per entity: flicker (Rush/Blitz), broken (Ambush), red dim (Bash), none (Scribbles), lightning (Surge). */
    private fun drawLighting(c: Canvas, w: Float, h: Float, t: Long, p: Float, closet: Float, gapHalf: Float, e: Entity) {
        var a = when (st.phase) {
            Phase.WARNING -> when (e) {
                Entity.RUSH, Entity.BLITZ -> if (flickerDark(t)) 0.78f else 0f
                Entity.AMBUSH -> 0.82f                       // the lights broke, period
                Entity.BASH -> 0.18f                          // only a hot dusk while the roar grows
                Entity.SCRIBBLES -> 0f                        // the shake is the warning, not the lights
                Entity.SURGE -> 0f                            // the yellow flashes are drawn by drawSurgeEffects
            }
            Phase.APPROACH -> when (e) {
                Entity.BASH -> 0.30f + 0.30f * p
                Entity.SURGE -> 0.0f
                else -> 0.12f + 0.30f * p
            }
            Phase.PASS -> when (e) {
                Entity.SURGE -> 0f
                else -> 0.55f
            }
            Phase.CLEAR -> 0.45f * (1f - p)
            else -> 0f
        }
        if (e == Entity.SURGE) drawSurgeEffects(c, w, h)
        if (a > 0f) {
            dim.alpha = (a * 255f).toInt()
            c.save()
            // the closet's gap stays un-dimmed, so the line of light reads as light
            if (closet > 0f) c.clipOutRect(w / 2f - gapHalf, -400f, w / 2f + gapHalf, h + 400f)
            c.drawRect(-400f, -400f, w + 400f, h + 400f, dim)
            c.restore()
        }
        if (e == Entity.BASH && (st.phase == Phase.APPROACH || st.phase == Phase.PASS)) {
            drawFireGlow(c, w, h, if (st.phase == Phase.PASS) 130 else (90 * p).toInt())
        }
    }

    /** Irregular on/off, changing every ~70 ms like a dying bulb. */
    private fun flickerDark(t: Long): Boolean {
        if (t > st.phaseDur - 180) return false
        return Random((t / 70).toInt() * 31 + 7).nextInt(100) < 55
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    // ---------------------------------------------------------------- passing

    private fun drawPassing(c: Canvas, w: Float, h: Float, p: Float, e: Entity) {

        val d = anim(movingName(e, st.pinkForm))
        val size = when (e) {
            Entity.RUSH -> h * 1.6f
            Entity.SCRIBBLES -> min(w, h) * 0.8f // its whole body (about 1100 texture px) across
            else -> h * 1.25f
        }
        val cx = if (st.passDir > 0) -size * 0.5f + (w + size) * p
        else w + size * 0.5f - (w + size) * p
        val cy = h / 2f
        if (e == Entity.SCRIBBLES) {
            drawScribbles(c, cx, cy, size / 1100f, SystemClock.uptimeMillis())
            drawThrows(c, w, h, size)
            return
        }
        if (d != null) {
            if (movingOn !== d) { stopMoving(); runCatching { d.start() }; movingOn = d }
            d.setBounds(
                (cx - size / 2f).toInt(), (cy - size / 2f).toInt(),
                (cx + size / 2f).toInt(), (cy + size / 2f).toInt()
            )
            d.draw(c)
        } else {
            c.drawRect(cx - size / 2f, 0f, cx + size / 2f, h, solid) // sprite failed to load
        }
    }

    /** Papers stuck all along the walls of the room (cells of the 4 x 2 sheet; the last cell is empty). */
    private fun drawWallPapers(c: Canvas, w: Float, h: Float, p: Float) {
        val sheet = bitmap("sprites/scribbles_papers.webp") ?: return
        val cw = sheet.width / 4
        val ch = sheet.height / 2
        val r = Random(st.seed)
        smoothP.alpha = if (st.phase == Phase.CLEAR) ((1f - p) * 255f).toInt().coerceIn(0, 255) else 255
        for (i in 0 until 28) {
            val cell = r.nextInt(7) // 0..6: the bottom-right cell (7) holds nothing
            srcR.set((cell % 4) * cw, (cell / 4) * ch, (cell % 4 + 1) * cw, (cell / 4 + 1) * ch)
            val pw = (0.10f + r.nextFloat() * 0.08f) * w
            val ph = pw * ch / cw
            val cx: Float
            val cy: Float
            when {
                i < 10 -> { cx = r.nextFloat() * pw * 0.35f; cy = (i + 0.5f) / 10f * h + (r.nextFloat() - 0.5f) * h * 0.04f }      // left wall
                i < 20 -> { cx = w - r.nextFloat() * pw * 0.35f; cy = (i - 10 + 0.5f) / 10f * h + (r.nextFloat() - 0.5f) * h * 0.04f } // right wall
                else -> { cx = (i - 20 + 0.5f) / 8f * w + (r.nextFloat() - 0.5f) * w * 0.04f; cy = r.nextFloat() * ph * 0.25f }   // top
            }
            rect.set(cx - pw / 2f, cy - ph / 2f, cx + pw / 2f, cy + ph / 2f)
            c.save()
            c.rotate((r.nextFloat() - 0.5f) * 50f, cx, cy)
            c.drawBitmap(sheet, srcR, rect, smoothP)
            c.restore()
        }
        smoothP.alpha = 255
    }

    /** Papers flying from Scribbles to the middle of the screen, growing as they come. */
    private fun drawThrows(c: Canvas, w: Float, h: Float, size: Float) {
        if (st.throws.isEmpty()) return
        val sheet = bitmap("sprites/scribbles_papers.webp") ?: return
        val cw = sheet.width / 4
        val ch = sheet.height / 2
        val now = SystemClock.uptimeMillis()
        for (t in st.throws) {
            val f = (now - t.at).toFloat() / RushEngine.SCRIBBLE_FLIGHT_MS
            if (f < 0f || f > 1f) continue
            val p0 = ((t.at - st.phaseStart).toFloat() / st.phaseDur).coerceIn(0f, 1f)
            val x0 = if (st.passDir > 0) -size * 0.5f + (w + size) * p0 else w + size * 0.5f - (w + size) * p0
            val x = lerp(x0, w / 2f, f)
            val y = h / 2f
            val pw = lerp(0.04f, 0.6f, f * f) * w
            srcR.set((t.cell % 4) * cw, (t.cell / 4) * ch, (t.cell % 4 + 1) * cw, (t.cell / 4 + 1) * ch)
            rect.set(x - pw / 2f, y - pw * ch / cw / 2f, x + pw / 2f, y + pw * ch / cw / 2f)
            c.save()
            c.rotate(f * 540f, x, y)
            smoothP.alpha = 255
            c.drawBitmap(sheet, srcR, rect, smoothP)
            c.restore()
        }
    }

    private val cutP = Paint().apply {
        color = Color.rgb(205, 16, 16)
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    /** Paper cuts on your screen: a red line about 3 cm long that fades away after 2-5 s. */
    private fun drawCuts(c: Canvas, w: Float, h: Float, now: Long) {
        if (st.cuts.isEmpty()) return
        val dm = resources.displayMetrics
        val dpi = if (dm.xdpi > 0f) dm.xdpi else density * 160f
        val len = 3f * dpi / 2.54f
        cutP.strokeWidth = 3f * density
        for (cut in st.cuts) {
            val age = now - cut.at
            if (age < 0L || age >= cut.fadeMs) continue
            val r = Random(cut.seed)
            val cx = w * (0.1f + 0.8f * r.nextFloat())
            val cy = h * (0.1f + 0.8f * r.nextFloat())
            val a = r.nextFloat() * Math.PI.toFloat()
            val dx = kotlin.math.cos(a) * len / 2f
            val dy = kotlin.math.sin(a) * len / 2f
            cutP.alpha = ((1f - age.toFloat() / cut.fadeMs) * 255f).toInt().coerceIn(0, 255)
            c.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, cutP)
        }
    }

    private val scribbleAngles = FloatArray(9) { i -> i * 40f + (Random(4 + i).nextFloat() - 0.5f) * 20f }

    /**
     * Scribbles as the real game builds it: the ink at the bottom, the duplicated hands above the
     * ink (rotated to point outwards, pushed out a bit, wiggling a few degrees around the centre),
     * then firstscribble .. fifthscribble on top as the face. [u] = screen pixels per texture pixel.
     */
    private fun drawScribbles(c: Canvas, cx: Float, cy: Float, u: Float, now: Long) {
        val ink = bitmap("sprites/scribbles_ink.webp")
        val arm = bitmap("sprites/scribbles_arm.webp")
        smoothP.alpha = 255
        c.save()
        c.translate(cx, cy)
        c.scale(u, u)
        if (ink != null) {
            rect.set(-ink.width / 2f, -ink.height / 2f, ink.width / 2f, ink.height / 2f)
            c.drawBitmap(ink, null, rect, smoothP)
        }
        if (arm != null) {
            for (i in scribbleAngles.indices) {
                val ang = scribbleAngles[i] + 6f * kotlin.math.sin(now / 230f + i * 1.7f)
                c.save()
                c.rotate(ang)
                rect.set(-arm.width / 2f, -ARM_OUT - arm.height / 2f, arm.width / 2f, -ARM_OUT + arm.height / 2f)
                c.drawBitmap(arm, null, rect, smoothP)
                c.restore()
            }
        }
        for (name in SCRIBBLE_LAYERS) {
            val l = bitmap("sprites/scribbles_$name.webp") ?: continue
            rect.set(-l.width / 2f, -l.height / 2f, l.width / 2f, l.height / 2f)
            c.drawBitmap(l, null, rect, smoothP)
        }
        c.restore()
    }

    /** Bash's speed against the carpet generates flames: a hot glow eating in from the edges. */
    private fun drawFireGlow(c: Canvas, w: Float, h: Float, alpha: Int) {
        val paint = fireP ?: Paint().apply {
            shader = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(0x00FF6A00.toInt(), 0x00FF6A00.toInt(), 0x66FF3D00.toInt(), 0xB3FF2A00.toInt()),
                floatArrayOf(0f, 0.45f, 0.8f, 1f),
                Shader.TileMode.CLAMP
            )
        }.also { fireP = it }
        c.save()
        c.translate(w / 2f, h / 2f)
        c.scale(w, h) // the gradient was built around a unit circle at the origin
        paint.alpha = alpha
        c.drawCircle(0f, 0f, 0.75f, paint)
        c.restore()
    }

    // ---------------------------------------------------------------- surge

    private val yellowP = Paint()
    private var lastSurgeDraw = 0L
    private var sgX = 0f
    private var sgY = 0f
    private var sgSize = 0f

    /** The sky flashes yellow in three steps, the screen turns yellow, and the vignette fades in unless hidden. */
    private fun drawSurgeEffects(c: Canvas, w: Float, h: Float) {
        val now = SystemClock.uptimeMillis()
        val stage = st.surgeStage
        if (stage in 1..5) {
            val ts = now - st.surgeStageAt
            var a = when (stage) {
                1 -> 0.10f
                2 -> 0.22f
                3 -> 0.50f
                4 -> 0.50f
                else -> 0.50f * (1f - ts.toFloat() / RushEngine.SURGE_LEAVE_MS).coerceIn(0f, 1f)
            }
            if (stage <= 2) a = if (ts < 700L) a * (if ((ts / 70L) % 2L == 0L) 1f else 0.35f) else a * 0.6f
            if (stage == 3) a *= (ts / 1000f).coerceIn(0f, 1f) // the screen turns yellow
            if (a > 0.005f) {
                yellowP.color = Color.argb((a * 255f).toInt().coerceIn(0, 255), 255, 214, 40)
                c.drawRect(-400f, -400f, w + 400f, h + 400f, yellowP)
            }
            if (stage <= 3 && ts < 420L && (ts / 90L) % 2L == 0L) drawBolt(c, w, h, stage)
        }
        val lvl = st.surgeVigLevel(now) * (1f - st.closet(now))
        if (lvl > 0.01f) {
            val v = bitmap("sprites/surge_vignette.webp")
            if (v != null) {
                tintP.alpha = (lvl * 200f).toInt().coerceIn(0, 255)
                rect.set(0f, 0f, w, h)
                c.drawBitmap(v, null, rect, tintP)
            }
        }
    }

    /** Lightning from the sky: flipped so the trunk is at the top, and starting past the top edge. */
    private fun drawBolt(c: Canvas, w: Float, h: Float, stage: Int) {
        val bolt = bitmap("sprites/surge_lightning.webp") ?: return
        val s = h * 0.95f
        val x = w * when (stage) { 1 -> 0.28f; 2 -> 0.70f; else -> 0.46f }
        rect.set(x - s / 2f, -s * 0.12f, x + s / 2f, -s * 0.12f + s)
        smoothP.alpha = 230
        c.save()
        c.scale(1f, -1f, rect.centerX(), rect.centerY())
        c.drawBitmap(bolt, null, rect, smoothP)
        c.restore()
    }

    /**
     * Surge itself: one sprite flipping between F1..F3 every frame. It drops from the sky, then
     * flies around you when you are hiding or at you when you are not; finally it flies back up.
     */
    private fun drawSurgeEntity(c: Canvas, w: Float, h: Float, now: Long) {
        if (st.surgeStage < 4) return
        val size0 = min(w, h) * 0.26f
        val dt = (now - lastSurgeDraw).coerceIn(1L, 60L).toFloat()
        lastSurgeDraw = now
        if (st.surgeStage == 4 && st.surgeIdleAt == 0L) {
            val d = ((now - st.surgeStageAt).toFloat() / RushEngine.SURGE_DESCENT_MS).coerceIn(0f, 1f)
            val e = 1f - (1f - d) * (1f - d)
            sgX = w / 2f
            sgY = lerp(-size0, h * 0.30f, e)
            sgSize = size0
        } else {
            val tx: Float
            val ty: Float
            val tsz: Float
            val tau: Float
            if (st.surgeStage == 5) {
                tx = sgX; ty = -size0 * 1.6f; tsz = size0; tau = 280f
            } else if (st.hidden) {
                val ang = now / 450f
                val r = min(w, h) * 0.32f
                tx = w / 2f + r * kotlin.math.cos(ang)
                ty = h * 0.45f + r * 0.8f * kotlin.math.sin(ang * 1.3f)
                tsz = size0; tau = 160f
            } else {
                tx = w / 2f + (rnd.nextFloat() - 0.5f) * 0.03f * w
                ty = h * 0.5f + (rnd.nextFloat() - 0.5f) * 0.03f * h
                tsz = size0 * 2.4f; tau = 140f
            }
            val k = 1f - kotlin.math.exp(-dt / tau)
            sgX += (tx - sgX) * k
            sgY += (ty - sgY) * k
            sgSize += (tsz - sgSize) * k
        }
        val f = bitmap("sprites/surge_f${((now / Js.FRAME_MS) % 3L).toInt() + 1}.webp") ?: return
        place(f, sgX, sgY, sgSize)
        c.drawBitmap(f, null, rect, bmp)
    }

    // ---------------------------------------------------------------- Hide

    /**
     * Hide's attack: its vignette (Hide_Frame_1, turned on its side in portrait) plus Hide_Frame_2,
     * and a flashing GET OUT that fills the screen (cropped, never stretched). After the kick the
     * red lingers a moment.
     */
    private fun drawHideAttack(c: Canvas, w: Float, h: Float, now: Long) {
        if (!st.hideAttack && now - st.hideKickAt > 1200L) return
        if (st.hideAttack) {
            smoothP.alpha = 255
            bitmap("sprites/hide_vig1.webp")?.let { v ->
                c.save()
                if (h > w) { // portrait: rotate the landscape frame to match
                    c.translate(w / 2f, h / 2f)
                    c.rotate(90f)
                    rect.set(-h / 2f, -w / 2f, h / 2f, w / 2f)
                } else {
                    rect.set(0f, 0f, w, h)
                }
                c.drawBitmap(v, null, rect, smoothP)
                c.restore()
            }
            bitmap("sprites/hide_vig2.webp")?.let { v ->
                rect.set(0f, 0f, w, h)
                c.drawBitmap(v, null, rect, smoothP)
            }
            if ((now / 150L) % 2L == 0L) {
                val g = bitmap("sprites/hide_getout.webp")
                if (g != null) {
                    val s = kotlin.math.max(w / g.width, h / g.height) // cover: fill, crop the excess
                    val bw = g.width * s
                    val bh = g.height * s
                    rect.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f)
                    c.drawBitmap(g, null, rect, smoothP)
                }
            }
        } else {
            // just kicked out: the red lingers a moment, then fades
            val k = 1f - (now - st.hideKickAt) / 1200f
            redFill.alpha = (120 * k).toInt().coerceIn(0, 120)
            c.drawRect(-400f, -400f, w + 400f, h + 400f, redFill)
        }
    }

    // ---------------------------------------------------------------- shared death sequence

    /**
     * Everything but Rush and Ambush: after the jumpscare (if it has one) the screen is visible
     * again for 7 frames while a red outline (Hide_Frame_2) closes in until it fills the screen.
     */
    private fun drawDeath(c: Canvas, w: Float, h: Float, t: Long) {
        val red = Color.rgb(102, 23, 22) // the colour at the edge of Hide_Frame_2
        val f = (t / Js.FRAME_MS).toInt().coerceIn(0, Js.DEATH_FRAMES - 1)
        if (f >= Js.DEATH_FRAMES - 1) { c.drawColor(red); return }
        val k = 1f - (f + 1f) / Js.DEATH_FRAMES * 0.93f
        rect.set(w / 2f * (1f - k), h / 2f * (1f - k), w / 2f * (1f + k), h / 2f * (1f + k))
        c.save()
        c.clipOutRect(rect)
        c.drawColor(red)
        c.restore()
        bitmap("sprites/hide_vig2.webp")?.let {
            smoothP.alpha = 255
            c.drawBitmap(it, null, rect, smoothP)
        }
    }

    // ---------------------------------------------------------------- jumpscare

    private fun drawJumpscare(c: Canvas, w: Float, h: Float, t: Long) {
        when (st.entity) {
            Entity.AMBUSH -> { drawAmbushJs(c, w, h, t); return }
            Entity.BLITZ -> { drawBlitzJs(c, w, h, t); return }
            Entity.SURGE -> { drawSurgeJs(c, w, h, t); return }
            else -> Unit
        }
        c.drawColor(Color.BLACK)
        val base = min(w, h) * 0.85f
        val e = st.entity

        if (t < Js.SCREAM_AT) {
            drawStatic(c, w, h)
            val f = bitmap(faceName(e, st.pinkForm)) ?: return
            // one small step closer in a single tick (not a smooth zoom), then it waits
            val s = if (t < Js.FACE_MS) 1f else Js.CREEP_SCALE
            val jx = (rnd.nextFloat() - 0.5f) * 3f * density
            val jy = (rnd.nextFloat() - 0.5f) * 3f * density
            place(f, w / 2f + jx, h / 2f + jy, base * s)
            if (e == Entity.SURGE) { // the whole screen snaps yellow with each face flash
                tintP.color = Color.argb(70, 240, 255, 120)
                c.drawRect(-400f, -400f, w + 400f, h + 400f, tintP)
            }
            c.drawBitmap(f, null, rect, bmp)
            return
        }

        // Open mouth. It comes at you in jerky steps (not a smooth zoom), glitching with colour bleed.
        val sc = bitmap(screamName(e)) ?: return
        val idx = ((t - Js.SCREAM_AT) / Js.STEP_MS).toInt()
        val r = Random(idx * 7919 + 13)
        val s = if (idx >= Js.STEPS) Js.FINAL_SCALE
        else Js.CREEP_SCALE * (Js.FINAL_SCALE / Js.CREEP_SCALE).pow((idx + 1f) / Js.STEPS)
        val size = base * s
        val cx = w / 2f + (r.nextFloat() - 0.5f) * 0.05f * size
        val cy = h / 2f + (r.nextFloat() - 0.5f) * 0.05f * size
        place(sc, cx, cy, size)

        val bleed = min(size * (0.012f + r.nextFloat() * 0.03f), 36f * density)
        c.save(); c.translate(-bleed, bleed * 0.35f); c.drawBitmap(sc, null, rect, redP); c.restore()
        c.save(); c.translate(bleed, -bleed * 0.35f); c.drawBitmap(sc, null, rect, cyanP); c.restore()
        c.drawBitmap(sc, null, rect, bmp)

        // a couple of torn horizontal slices
        repeat(2) {
            val y0 = rect.top + r.nextFloat() * rect.height()
            val bh = rect.height() * (0.03f + r.nextFloat() * 0.08f)
            val shift = (r.nextFloat() - 0.5f) * size * 0.25f
            c.save()
            c.clipRect(0f, y0, w, y0 + bh)
            c.drawRect(0f, y0, w, y0 + bh, solid)
            c.translate(shift, 0f)
            c.drawBitmap(sc, null, rect, bmp)
            c.restore()
        }
    }

    /** Ambush: first frame of its animation rushing straight at you, zooming into the middle. */
    private fun drawAmbushJs(c: Canvas, w: Float, h: Float, t: Long) {
        c.drawColor(Color.BLACK)
        val f = bitmap("sprites/ambush_js.webp") ?: return
        val p = (t.toFloat() / Js.AMBUSH_MS).coerceIn(0f, 1f)
        val s = 0.35f + (9f - 0.35f) * p.pow(2.2f)
        place(f, w / 2f, h / 2f, min(w, h) * 0.9f * s)
        smoothP.alpha = 255
        c.drawBitmap(f, null, rect, smoothP)
    }

    private class BlitzFrame(val bg: Int, val scale: Float, val ox: Float, val oy: Float)

    /** Frame by frame from the 25 fps reference (offsets in face heights from the centre). */
    private val blitzFrames = listOf(
        BlitzFrame(0xFF000F21.toInt(), 1.00f, 0.000f, 0.000f),  // centred
        BlitzFrame(0xFF000601.toInt(), 1.00f, 0.045f, 0.045f),  // shifted a few pixels
        BlitzFrame(0xFF000601.toInt(), 1.00f, 0.036f, 0.018f),  // still
        BlitzFrame(0xFF000203.toInt(), 0.54f, -0.126f, 0.054f), // zoomed out
        BlitzFrame(0xFF000203.toInt(), 0.54f, -0.126f, 0.054f),
        BlitzFrame(0xFF000601.toInt(), 0.54f, -0.108f, -0.054f),
        BlitzFrame(0xFF000601.toInt(), 0.54f, -0.108f, -0.054f),
        BlitzFrame(0xFF000100.toInt(), 0.54f, -0.108f, 0.000f),
        BlitzFrame(0xFF000601.toInt(), 0.54f, -0.072f, -0.036f),
        BlitzFrame(0xFF33D6C1.toInt(), 0.82f, 0.036f, -0.063f), // the flashing rush at you
        BlitzFrame(0xFF007DFE.toInt(), 2.80f, 0f, 0f),
        BlitzFrame(0xFF33DAF4.toInt(), 6.50f, 0f, 0f),
        BlitzFrame(0xFF105235.toInt(), 14.0f, 0f, 0f)
    )

    private fun drawBlitzJs(c: Canvas, w: Float, h: Float, t: Long) {
        val fr = blitzFrames[(t / Js.FRAME_MS).toInt().coerceIn(0, blitzFrames.size - 1)]
        c.drawColor(fr.bg)
        val face = bitmap(if (st.pinkForm) "sprites/blitz_js_pink.webp" else "sprites/blitz_js_green.webp") ?: return
        val s0 = min(w, h) * 0.8f
        val unit = s0 * 0.75f
        place(face, w / 2f + fr.ox * unit, h / 2f + fr.oy * unit, s0 * fr.scale)
        smoothP.alpha = 255
        c.drawBitmap(face, null, rect, if (fr.scale > 6f) bmp else smoothP)
    }

    /** Surge: its three faces flash in front of you on the lightning background. */
    private fun drawSurgeJs(c: Canvas, w: Float, h: Float, t: Long) {
        val i = (t / Js.FRAME_MS).toInt().coerceIn(0, Js.SURGE_FRAMES - 1)
        c.drawColor(0xFF463404.toInt())
        bitmap("sprites/surge_js_bg.webp")?.let {
            place(it, w / 2f, h / 2f, kotlin.math.max(w, h))
            smoothP.alpha = 255
            c.drawBitmap(it, null, rect, smoothP)
        }
        val f = bitmap("sprites/surge_f${i % 3 + 1}.webp") ?: return
        val jx = (rnd.nextFloat() - 0.5f) * 6f * density
        val jy = (rnd.nextFloat() - 0.5f) * 6f * density
        place(f, w / 2f + jx, h / 2f + jy, min(w, h) * 0.55f)
        c.drawBitmap(f, null, rect, bmp)
    }


    /** Dest rect for a face bitmap centred on (cx, cy), [size] tall, keeping its aspect ratio. */
    private fun place(b: Bitmap, cx: Float, cy: Float, size: Float) {
        val ww = size * b.width / b.height
        rect.set(cx - ww / 2f, cy - size / 2f, cx + ww / 2f, cy + size / 2f)
    }

    private fun drawStatic(c: Canvas, w: Float, h: Float) {
        repeat(90) {
            staticP.color = Color.argb(40 + rnd.nextInt(70), 210, 210, 210)
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            val s = (1 + rnd.nextInt(3)) * density
            c.drawRect(x, y, x + s * (1 + rnd.nextInt(4)), y + s, staticP)
        }
    }

    private companion object {
        const val ARM_OUT = 200f // how far the hands sit out from the centre, in texture pixels
        val SCRIBBLE_LAYERS = listOf("firstscribble", "secondscribble", "thirdscribble", "fourthscribble", "fifthscribble")
    }

    fun release() {
        stopMoving()
    }
}
