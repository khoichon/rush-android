package com.khoichon.rush

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.os.SystemClock
import android.view.View
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** Jumpscare timeline, in ms from the moment Rush catches you. */
object Js {
    const val FACE_MS = 1000L      // closed-mouth face, still, on black with static
    const val CREEP_MS = 1400L     // it moves closer
    const val WAIT_MS = 700L       // ...and waits
    const val SCREAM_AT = FACE_MS + CREEP_MS + WAIT_MS   // open mouth + scream + glitch rush
    const val STEPS = 9            // the rush at you is in jerky steps, not a smooth zoom
    const val STEP_MS = 42L
    const val HIT_AT = SCREAM_AT + STEPS * STEP_MS       // it reaches you
    const val END_AT = HIT_AT + 300L                     // death consequences fire here
    const val CREEP_SCALE = 1.55f
    const val FINAL_SCALE = 7f
}

/**
 * The full-screen overlay. Draws, depending on [RushState.phase]:
 *  - the world: flickering / dimming lights, screen shake, Rush's smoke crossing the screen, and the
 *    closet doors (two black bars closing in from the sides, a thin line of light left in the middle);
 *  - the jumpscare: black, static, the face creeping closer, then the glitching rush at you.
 * Everything is a pure function of the state and the clock, so the engine never has to animate.
 */
class SceneView(context: Context, private val st: RushState) : View(context) {
    private val density = resources.displayMetrics.density

    private val solid = Paint().apply { color = Color.BLACK }
    private val dim = Paint().apply { color = Color.BLACK }
    private val staticP = Paint()
    private val bmp = Paint().apply { isFilterBitmap = false }
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

    private val face: Bitmap? = bitmap("sprites/rush_face.webp")
    private val scream: Bitmap? = bitmap("sprites/rush_scream.webp")
    private val moving: AnimatedImageDrawable? = runCatching {
        (ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.assets, "sprites/rush_moving.webp"))
            as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE }
    }.getOrNull()
    private var movingOn = false

    private val rnd = Random(System.nanoTime())
    private val rect = RectF()

    private fun bitmap(path: String): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.assets, path)) { dec, _, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val w = width.toFloat()
        val h = height.toFloat()
        when (st.phase) {
            Phase.NONE -> { stopMoving(); return }
            Phase.JUMPSCARE -> { stopMoving(); drawJumpscare(c, w, h, now - st.phaseStart) }
            else -> drawWorld(c, w, h, now)
        }
        postInvalidateOnAnimation()
    }

    private fun stopMoving() {
        if (movingOn) { runCatching { moving?.stop() }; movingOn = false }
    }

    private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    private fun drawWorld(c: Canvas, w: Float, h: Float, now: Long) {
        val t = now - st.phaseStart
        val p = (t.toFloat() / st.phaseDur).coerceIn(0f, 1f)
        val closet = st.closet(now)

        c.save()
        if (st.shake > 0f) {
            val amp = st.shake * 7f * density
            c.translate((rnd.nextFloat() - 0.5f) * 2f * amp, (rnd.nextFloat() - 0.5f) * 2f * amp)
        }

        val gapHalf = lerp(w / 2f, 2.5f * density, smooth(closet))

        // lights: flicker on the warning, dim as Rush gets close, restore afterwards
        val a = when (st.phase) {
            Phase.WARNING -> if (flickerDark(t)) 0.78f else 0f
            Phase.APPROACH -> 0.12f + 0.30f * p
            Phase.PASS -> 0.55f
            Phase.CLEAR -> 0.45f * (1f - p)
            else -> 0f
        }
        if (a > 0f) {
            dim.alpha = (a * 255f).toInt()
            c.save()
            // the closet's gap stays un-dimmed, so the line of light reads as light
            if (closet > 0f) c.clipOutRect(w / 2f - gapHalf, -400f, w / 2f + gapHalf, h + 400f)
            c.drawRect(-400f, -400f, w + 400f, h + 400f, dim)
            c.restore()
        }

        // Rush himself: a big dark smoke cloud with the face in it, crossing the screen
        if (st.phase == Phase.PASS) {
            val d = moving
            val size = h * 1.6f
            val cx = if (st.passDir > 0) -size * 0.5f + (w + size) * p
            else w + size * 0.5f - (w + size) * p
            val cy = h / 2f
            if (d != null) {
                if (!movingOn) { d.start(); movingOn = true }
                d.setBounds(
                    (cx - size / 2f).toInt(), (cy - size / 2f).toInt(),
                    (cx + size / 2f).toInt(), (cy + size / 2f).toInt()
                )
                d.draw(c)
            } else {
                c.drawRect(cx - size / 2f, 0f, cx + size / 2f, h, solid) // sprite failed to load
            }
        } else {
            stopMoving()
        }

        // closet doors
        if (closet > 0f) {
            c.drawRect(-400f, -400f, w / 2f - gapHalf, h + 400f, solid)
            c.drawRect(w / 2f + gapHalf, -400f, w + 400f, h + 400f, solid)
        }
        c.restore()
    }

    /** Irregular on/off, changing every ~70 ms like a dying bulb. */
    private fun flickerDark(t: Long): Boolean {
        if (t > st.phaseDur - 180) return false
        return Random((t / 70).toInt() * 31 + 7).nextInt(100) < 55
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    // ---------------------------------------------------------------- jumpscare

    private fun drawJumpscare(c: Canvas, w: Float, h: Float, t: Long) {
        c.drawColor(Color.BLACK)
        val base = min(w, h) * 0.85f

        if (t < Js.SCREAM_AT) {
            drawStatic(c, w, h)
            val f = face ?: return
            val s = when {
                t < Js.FACE_MS -> 1f
                t < Js.FACE_MS + Js.CREEP_MS ->
                    1f + (Js.CREEP_SCALE - 1f) * smooth((t - Js.FACE_MS).toFloat() / Js.CREEP_MS)
                else -> Js.CREEP_SCALE
            }
            val jx = (rnd.nextFloat() - 0.5f) * 3f * density
            val jy = (rnd.nextFloat() - 0.5f) * 3f * density
            place(f, w / 2f + jx, h / 2f + jy, base * s)
            c.drawBitmap(f, null, rect, bmp)
            return
        }

        // Open mouth. It comes at you in jerky steps (not a smooth zoom), glitching with colour bleed.
        val sc = scream ?: return
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

    fun release() {
        stopMoving()
    }
}
