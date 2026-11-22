package com.example.fm1burger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Waveform with an optional selection (drag) and a play position line. */
class WaveformView(ctx: Context) : View(ctx) {

    var editable = false
    var onSelect: ((Float, Float) -> Unit)? = null
    var color = Theme.ACCENT
    var playhead = -1f
        set(v) {
            field = v
            invalidate()
        }

    private var data: FloatArray? = null
    private var mn = FloatArray(0)
    private var mx = FloatArray(0)
    private var cachedW = -1
    private var selA = 0f
    private var selB = 0f
    private var regA = 0f
    private var regB = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setData(d: FloatArray?) {
        data = d
        cachedW = -1
        invalidate()
    }

    fun setRegion(a: Float, b: Float) {
        regA = a
        regB = b
        invalidate()
    }

    fun setSelection(a: Float, b: Float) {
        selA = a
        selB = b
        invalidate()
    }

    fun hasSelection(): Boolean = selB - selA > 0.002f
    fun selStart(): Float = selA
    fun selEnd(): Float = selB

    private fun buildCache(w: Int) {
        val d = data
        mn = FloatArray(w)
        mx = FloatArray(w)
        cachedW = w
        if (d == null || d.isEmpty()) return
        for (x in 0 until w) {
            val i0 = (x.toLong() * d.size / w).toInt()
            val i1 = max(i0 + 1, ((x + 1).toLong() * d.size / w).toInt())
            val stride = max(1, (i1 - i0) / 48)
            var lo = 0f
            var hi = 0f
            var i = i0
            while (i < i1 && i < d.size) {
                val v = d[i]
                if (v < lo) lo = v
                if (v > hi) hi = v
                i += stride
            }
            mn[x] = lo
            mx[x] = hi
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        paint.style = Paint.Style.FILL
        paint.color = Theme.PANEL2
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        c.drawRoundRect(rect, 10f, 10f, paint)
        if (cachedW != w) buildCache(w)
        val mid = h / 2f
        paint.color = Theme.DIM
        paint.strokeWidth = 1f
        c.drawLine(0f, mid, w.toFloat(), mid, paint)
        val ra = (regA * w).toInt()
        val rb = (regB * w).toInt()
        paint.strokeWidth = 1f
        for (x in 0 until w) {
            val inside = x in ra..rb
            paint.color = if (inside) color else Theme.DIM
            c.drawLine(x.toFloat(), mid - mx[x] * mid * 0.95f, x.toFloat(), mid - mn[x] * mid * 0.95f - 1f, paint)
        }
        if (hasSelection()) {
            paint.color = Color.argb(70, 255, 255, 255)
            c.drawRect(selA * w, 0f, selB * w, h.toFloat(), paint)
            paint.color = Color.WHITE
            c.drawRect(selA * w - 1f, 0f, selA * w + 1f, h.toFloat(), paint)
            c.drawRect(selB * w - 1f, 0f, selB * w + 1f, h.toFloat(), paint)
        }
        if (playhead in 0f..1f) {
            paint.color = Color.WHITE
            paint.strokeWidth = 3f
            c.drawLine(playhead * w, 0f, playhead * w, h.toFloat(), paint)
        }
    }

    private var anchor = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editable) return false
        val f = (e.x / max(1, width)).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                anchor = f
                selA = f
                selB = f
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                selA = min(anchor, f)
                selB = max(anchor, f)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onSelect?.invoke(selA, selB)
            }
        }
        return true
    }
}

/** 16 steps x 13 pitch rows (-6 .. +6 semitones) and a level strip under it. */
class StepGridView(ctx: Context) : View(ctx) {

    companion object {
        const val ROWS = 13
    }

    var track: Track? = null
    var playStep = -1
    var onEdit: (() -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var paintMode = 0   // 1 = paint, 2 = erase
    private var levelDrag = false

    private fun gridH(): Float = height * 0.74f
    private fun stripTop(): Float = gridH() + height * 0.04f

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val t = track
        val base = Pat.base()
        val nb = Pat.spb()                                   // (3.28: the meter's bar)
        val cw = w / nb
        val gh = gridH()
        val rh = gh / ROWS
        paint.style = Paint.Style.FILL
        paint.color = Theme.BG
        c.drawRect(0f, 0f, w, h, paint)
        for (r in 0 until ROWS) {
            for (s in 0 until nb) {
                paint.color = when {
                    r == ROWS / 2 -> Theme.PANEL3
                    Pat.beatOf(s) % 2 == 0 -> Theme.PANEL2
                    else -> Theme.PANEL
                }
                c.drawRect(s * cw + 1.5f, r * rh + 1.5f, (s + 1) * cw - 1.5f, (r + 1) * rh - 1.5f, paint)
            }
        }
        val strip = stripTop()
        val sh = h - strip
        if (t != null) {
            for (s in 0 until nb) {
                val st = t.steps[base + s]
                if (st.on) {
                    val row = (ROWS / 2 - st.pitch).coerceIn(0, ROWS - 1)
                    paint.color = Theme.ACCENT
                    c.drawRect(s * cw + 1.5f, row * rh + 1.5f, (s + 1) * cw - 1.5f, (row + 1) * rh - 1.5f, paint)
                }
                paint.color = if (st.on) Theme.ACCENT else Theme.PANEL3
                val bh = sh * st.level
                c.drawRect(s * cw + 3f, h - bh, (s + 1) * cw - 3f, h, paint)
            }
        }
        val ps = playStep - base
        if (playStep >= 0 && ps in 0 until nb) {
            paint.color = Color.argb(60, 255, 255, 255)
            c.drawRect(ps * cw, 0f, (ps + 1) * cw, h, paint)
        }
    }

    private fun apply(x: Float, y: Float, first: Boolean) {
        val t = track ?: return
        val cw = width.toFloat() / Pat.spb()
        val s = Pat.base() + (x / cw).toInt().coerceIn(0, Pat.spb() - 1)
        val gh = gridH()
        if (y < gh) {
            val row = (y / (gh / ROWS)).toInt().coerceIn(0, ROWS - 1)
            val pitch = ROWS / 2 - row
            val st = t.steps[s]
            if (first) {
                if (st.on && st.pitch == pitch) {
                    paintMode = 2
                } else {
                    paintMode = 1
                }
            }
            if (paintMode == 1) {
                st.on = true
                st.pitch = pitch
            } else if (paintMode == 2) {
                if (st.on && st.pitch == pitch) st.on = false
            }
        } else if (y >= stripTop() || levelDrag) {
            val sh = height - stripTop()
            val lv = (1f - (y - stripTop()) / sh).coerceIn(0.1f, 1f)
            t.steps[s].level = lv
        }
        invalidate()
        onEdit?.invoke()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                levelDrag = e.y >= stripTop()
                apply(e.x, e.y, true)
            }
            MotionEvent.ACTION_MOVE -> {
                val y = if (levelDrag) e.y.coerceAtLeast(stripTop()) else min(e.y, gridH() - 1f).coerceAtLeast(0f)
                apply(e.x.coerceIn(0f, width - 1f), y, false)
            }
        }
        return true
    }
}

/** Vertical fader (0..1) with a level meter drawn next to it. */
class FaderView(ctx: Context) : View(ctx) {

    var value = 0.8f
        set(v) {
            field = v
            invalidate()
        }
    var meter = 0f
        set(v) {
            field = v
            invalidate()
        }
    var color = Theme.ACCENT
    var onChange: ((Float) -> Unit)? = null
    /** 3.38: a finger is on the fader now (the Mixer does not move it under the finger) */
    var touching = false
        private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        paint.style = Paint.Style.FILL
        paint.color = Theme.PANEL2
        c.drawRoundRect(RectF(0f, 0f, w, h), 16f, 16f, paint)
        val pad = 16f
        val top = pad
        val bot = h - pad
        // meter
        val m = abs(meter).coerceIn(0f, 1f)
        paint.color = if (m > 0.9f) Theme.RED else Theme.GREEN
        c.drawRect(w * 0.10f, bot - (bot - top) * m, w * 0.22f, bot, paint)
        // groove
        paint.color = Theme.BG
        c.drawRoundRect(RectF(w * 0.5f - 4f, top, w * 0.5f + 4f, bot), 4f, 4f, paint)
        // fill
        val y = bot - (bot - top) * value
        paint.color = color
        c.drawRoundRect(RectF(w * 0.5f - 4f, y, w * 0.5f + 4f, bot), 4f, 4f, paint)
        // handle: a light cap with a colored line in the middle
        paint.color = Color.parseColor("#E8E8EE")
        c.drawRoundRect(RectF(w * 0.28f, y - 14f, w * 0.88f, y + 14f), 10f, 10f, paint)
        paint.color = color
        c.drawRect(w * 0.34f, y - 2f, w * 0.82f, y + 2f, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touching = true
                val pad = 16f
                val v = 1f - ((e.y - pad) / max(1f, height - 2 * pad))
                value = v.coerceIn(0f, 1f)
                onChange?.invoke(value)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
        return true
    }
}
