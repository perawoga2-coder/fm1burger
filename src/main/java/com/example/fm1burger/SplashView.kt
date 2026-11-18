package com.example.fm1burger

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * The short intro animation shown when the app opens:
 * the burger drops in layer by layer, the name types itself in,
 * a small equalizer pulses, then everything fades away. Tap to skip.
 */
class SplashView(ctx: Context, private val onDone: () -> Unit) : View(ctx) {

    companion object {
        const val NAME_A = "FM1"
        const val NAME_B = "BURGER"
        const val TAGLINE = "groovebox for the FM-1"
        private const val TOTAL_MS = 2600L
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var t = 0f          // time in ms since the start
    private var finished = false
    private val anim = ValueAnimator.ofFloat(0f, TOTAL_MS.toFloat())

    // burger layers from the top: color, height (dp), width (fraction), corner (dp)
    private val layerColor = intArrayOf(
        Theme.ACCENT,                          // top bun
        Theme.GREEN,                           // lettuce
        Theme.YELLOW,                          // cheese
        android.graphics.Color.parseColor("#7A3E1D"), // patty
        Theme.ACCENT                           // bottom bun
    )
    private val layerH = floatArrayOf(30f, 8f, 8f, 18f, 16f)
    private val layerW = floatArrayOf(1.0f, 1.06f, 1.02f, 1.0f, 1.0f)

    init {
        setBackgroundColor(Theme.BG)
        isClickable = true
        textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textPaint.textAlign = Paint.Align.LEFT
        anim.duration = TOTAL_MS
        anim.interpolator = LinearInterpolator()
        anim.addUpdateListener {
            t = it.animatedValue as Float
            alpha = if (t > TOTAL_MS - 450) ((TOTAL_MS - t) / 450f).coerceIn(0f, 1f) else 1f
            invalidate()
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                finish()
            }
        })
    }

    fun start() {
        anim.start()
    }

    private fun finish() {
        if (finished) return
        finished = true
        anim.removeAllUpdateListeners()
        (parent as? ViewGroup)?.removeView(this)
        onDone()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            // tap: skip the rest with a quick fade
            anim.cancel()
            animate().alpha(0f).setDuration(180).withEndAction { finish() }.start()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    /** 0..1 progress of a part of the timeline that runs from start to start+len. */
    private fun part(start: Float, len: Float): Float = ((t - start) / len).coerceIn(0f, 1f)

    /** Ease out with a small bounce at the end. */
    private fun bounce(x: Float): Float {
        if (x >= 1f) return 1f
        val s = 1.70158f * 1.4f
        val y = x - 1f
        return 1f + y * y * ((s + 1f) * y + s)
    }

    private fun easeOut(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        // one design unit: the drawing is laid out for a 360 wide screen and scaled to fit
        val u = min(w, h) / 360f

        // soft glow behind the burger
        val glow = part(0f, 700f)
        paint.style = Paint.Style.FILL
        paint.color = Theme.alpha(Theme.ACCENT, (38 * glow).toInt())
        val gy = h * 0.40f
        c.drawCircle(cx, gy, 110f * u * (0.6f + 0.4f * easeOut(glow)), paint)

        // the burger: each layer drops in, bottom first
        val bw = 120f * u
        var total = 0f
        for (k in layerH.indices) total += layerH[k] * u + 3f * u
        var y = gy + total / 2f
        for (k in layerH.indices.reversed()) {
            val order = layerH.size - 1 - k
            val p = bounce(part(order * 110f, 420f))
            if (p <= 0f) {
                y -= layerH[k] * u + 3f * u
                continue
            }
            val lh = layerH[k] * u
            val lw = bw * layerW[k]
            val drop = (1f - p) * h * 0.35f
            val top = y - lh - drop
            paint.color = layerColor[k]
            when (k) {
                0 -> {
                    // top bun: a dome
                    rect.set(cx - lw / 2f, top, cx + lw / 2f, top + lh * 2f)
                    c.save()
                    c.clipRect(cx - lw / 2f, top, cx + lw / 2f, top + lh)
                    c.drawOval(rect, paint)
                    c.restore()
                    // sesame seeds
                    paint.color = Theme.alpha(android.graphics.Color.WHITE, 200)
                    for (s in 0 until 5) {
                        val sx = cx + (s - 2) * lw * 0.16f
                        val sy = top + lh * (if (s % 2 == 0) 0.42f else 0.62f)
                        rect.set(sx - 3f * u, sy - 1.5f * u, sx + 3f * u, sy + 1.5f * u)
                        c.drawOval(rect, paint)
                    }
                }
                1 -> {
                    // lettuce: a wavy strip
                    rect.set(cx - lw / 2f, top, cx + lw / 2f, top + lh)
                    c.drawRoundRect(rect, lh / 2f, lh / 2f, paint)
                    for (s in 0 until 9) {
                        val sx = cx - lw / 2f + (s + 0.5f) * lw / 9f
                        c.drawCircle(sx, top + lh, lh * 0.45f, paint)
                    }
                }
                2 -> {
                    // cheese with two drips
                    rect.set(cx - lw / 2f, top, cx + lw / 2f, top + lh)
                    c.drawRect(rect, paint)
                    val drip = lh * 1.2f * part(700f, 400f)
                    rect.set(cx - lw * 0.25f, top, cx - lw * 0.25f + 12f * u, top + lh + drip)
                    c.drawRoundRect(rect, 6f * u, 6f * u, paint)
                    rect.set(cx + lw * 0.18f, top, cx + lw * 0.18f + 10f * u, top + lh + drip * 0.7f)
                    c.drawRoundRect(rect, 5f * u, 5f * u, paint)
                }
                else -> {
                    rect.set(cx - lw / 2f, top, cx + lw / 2f, top + lh)
                    c.drawRoundRect(rect, lh / 2f, lh / 2f, paint)
                }
            }
            y -= lh + 3f * u
        }

        // the name types itself in, letter by letter
        val nameY = gy + total / 2f + 64f * u
        textPaint.textSize = 40f * u
        textPaint.letterSpacing = 0.08f
        val full = "$NAME_A $NAME_B"
        val fullW = textPaint.measureText(full)
        var x = cx - fullW / 2f
        for (i in full.indices) {
            val ch = full[i].toString()
            val cw = textPaint.measureText(ch)
            val p = easeOut(part(650f + i * 55f, 260f))
            if (p > 0f) {
                textPaint.color = if (i < NAME_A.length) Theme.ACCENT else Theme.TEXT
                textPaint.alpha = (255 * p).toInt()
                c.drawText(ch, x, nameY + (1f - p) * 18f * u, textPaint)
            }
            x += cw
        }

        // tagline
        val tp = part(1350f, 350f)
        if (tp > 0f) {
            textPaint.textSize = 13f * u
            textPaint.letterSpacing = 0.25f
            textPaint.color = Theme.DIM
            textPaint.alpha = (255 * tp).toInt()
            val tw = textPaint.measureText(TAGLINE.uppercase())
            c.drawText(TAGLINE.uppercase(), cx - tw / 2f, nameY + 30f * u, textPaint)
        }

        // a small equalizer that pulses to an imaginary beat
        val ep = part(1100f, 300f)
        if (ep > 0f) {
            val bars = 9
            val barW = 6f * u
            val gap = 5f * u
            val ew = bars * barW + (bars - 1) * gap
            val baseY = nameY + 70f * u
            for (b in 0 until bars) {
                val phase = t / 1000f * 2f * PI.toFloat() * 2f + b * 0.9f
                val lvl = 0.25f + 0.75f * abs(sin(phase)) * (1f - abs(b - 4) / 6f)
                val bh = 26f * u * lvl * ep
                val bx = cx - ew / 2f + b * (barW + gap)
                paint.color = if (b == 4) Theme.ACCENT else Theme.alpha(Theme.ACCENT, 150)
                rect.set(bx, baseY - bh, bx + barW, baseY)
                c.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
            }
        }
    }
}
