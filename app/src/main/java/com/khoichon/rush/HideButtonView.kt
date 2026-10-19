package com.khoichon.rush

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/**
 * The little round "hide" button at the bottom of the screen. Tap to step into the closet, tap
 * again to step out. It lives in its own small window so the rest of the phone stays usable.
 */
class HideButtonView(context: Context, private val st: RushState) : View(context) {
    private val density = resources.displayMetrics.density
    private val icon: Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.assets, "sprites/hiding.webp")) { dec, _, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()

    private val fill = Paint().apply { isAntiAlias = true }
    private val ring = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val iconP = Paint().apply { isFilterBitmap = true }
    private val dst = RectF()

    var onTap: (() -> Unit)? = null

    init {
        setOnClickListener { onTap?.invoke() }
        contentDescription = "Hide"
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 3f * density
        val hiding = st.hidden
        val locked = android.os.SystemClock.uptimeMillis() < st.hideLockUntil
        fill.color = when {
            locked -> 0x66FF2020.toInt()        // Hide locked the closet
            hiding -> 0xE6000000.toInt()
            else -> 0xB0101018.toInt()
        }
        c.drawCircle(cx, cy, r, fill)
        ring.color = when {
            locked -> 0xFFFF4040.toInt()
            hiding -> 0xFFFFFFFF.toInt()
            else -> 0x88FFFFFF.toInt()
        }
        c.drawCircle(cx, cy, r, ring)
        icon?.let {
            iconP.alpha = when {
                locked -> 90
                hiding -> 255
                else -> 190
            }
            val s = r * 1.15f
            dst.set(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f)
            c.drawBitmap(it, null, dst, iconP)
        }
        if (locked) postInvalidateOnAnimation() // keep refreshing until the lock expires
    }
}
