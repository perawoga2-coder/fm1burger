package com.example.fm1burger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 3.41: the SAMPLING page's frequency graph, the way DAWs draw a filter.
 * Left to right is 20 Hz .. 20 kHz; the curve is what the track's low cut (the high-pass, FxChain 1) and high cut
 * (the low-pass, FxChain 0) do. Drag a handle left / right for its frequency and up / down for its resonance;
 * all the way out (left for the low cut, right for the high cut) turns it off. It reads and writes the same values
 * as the Low-pass / High-pass sliders, so the two always agree.
 */
class FilterGraphView(ctx: Context) : View(ctx) {

    /** (which: 0 high cut / 1 low cut, cutoff 0..1, resonance 0..1, on) */
    var onChange: ((Int, Float, Float, Boolean) -> Unit)? = null
    var onRelease: (() -> Unit)? = null
    var color = Theme.ACCENT

    // the values shown: [0] = high cut (low-pass), [1] = low cut (high-pass)
    private val cutA = floatArrayOf(1f, 0f)
    private val resB = floatArrayOf(0.1f, 0.1f)
    private val on = booleanArrayOf(false, false)
    private var intensity = 0f
    private var drag = -1

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val dp = ctx.resources.displayMetrics.density

    companion object {
        private const val F0 = 20.0
        private const val F1 = 20000.0
        private const val DB_TOP = 18.0
        private const val DB_BOT = -30.0
        /** FxChain's cutoff curve: 40 Hz .. 18 kHz */
        fun cutHz(a: Float): Double = 40.0 * 450.0.pow(a.toDouble())
        fun cutA(hz: Double): Float = (ln(hz / 40.0) / ln(450.0)).toFloat().coerceIn(0f, 1f)
    }

    fun set(fx: FxChain) {
        cutA[0] = fx.a[0]; resB[0] = fx.b[0]; on[0] = fx.on[0] && fx.a[0] < 0.99f
        cutA[1] = fx.a[1]; resB[1] = fx.b[1]; on[1] = fx.on[1] && fx.a[1] > 0.01f
        intensity = fx.intensity
        invalidate()
    }

    private fun q(b: Float): Double = 0.7 + b * (8.0 + 10.0 * intensity)
    private fun xOf(hz: Double): Float = (pad() + (log10(hz / F0) / log10(F1 / F0)) * (width - 2 * pad())).toFloat()
    private fun hzOf(x: Float): Double = F0 * (F1 / F0).pow(((x - pad()) / (width - 2 * pad())).toDouble().coerceIn(0.0, 1.0))
    private fun yOf(db: Double): Float = (pad() + (DB_TOP - db) / (DB_TOP - DB_BOT) * (height - 2 * pad())).toFloat()
    private fun pad(): Float = 10 * dp

    /** the filters' gain in dB at [hz] (2nd order, as the biquads in FxChain) */
    private fun gainDb(hz: Double): Double {
        var g = 1.0
        if (on[0]) {
            val r = hz / cutHz(cutA[0]); val qq = q(resB[0])
            g *= 1.0 / sqrt((1 - r * r).pow(2) + (r / qq).pow(2))
        }
        if (on[1]) {
            val r = hz / cutHz(cutA[1]); val qq = q(resB[1])
            g *= (r * r) / sqrt((1 - r * r).pow(2) + (r / qq).pow(2))
        }
        return 20 * log10(max(g, 1e-6))
    }

    /** a handle sits at its cutoff (or the edge when off), at the height of its resonance */
    private fun handleX(k: Int): Float = when {
        on[k] -> xOf(cutHz(cutA[k]))
        k == 0 -> width - pad()
        else -> pad()
    }
    private fun handleY(k: Int): Float = yOf(-12.0 + resB[k] * 24.0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (130 * dp).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = Theme.alpha(Theme.BG, 255)
        c.drawRoundRect(0f, 0f, w, h, 10 * dp, 10 * dp, paint)
        // grid: 100 Hz, 1 kHz, 10 kHz and 0 dB
        paint.color = Theme.LINE
        paint.strokeWidth = 1 * dp
        paint.textSize = 10 * dp
        for ((hz, lab) in listOf(100.0 to "100", 1000.0 to "1k", 10000.0 to "10k")) {
            val x = xOf(hz)
            c.drawLine(x, pad(), x, h - pad(), paint)
            paint.color = Theme.FAINT
            c.drawText(lab, x + 3 * dp, h - pad() - 2 * dp, paint)
            paint.color = Theme.LINE
        }
        val y0 = yOf(0.0)
        c.drawLine(pad(), y0, w - pad(), y0, paint)
        // the curve, filled below
        path.reset()
        val n = max(2, (w / (3 * dp)).toInt())
        for (i in 0..n) {
            val x = pad() + i * (w - 2 * pad()) / n
            val y = yOf(gainDb(hzOf(x)).coerceIn(DB_BOT, DB_TOP)).coerceIn(pad(), h - pad())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * dp
        paint.color = color
        c.drawPath(path, paint)
        path.lineTo(w - pad(), h - pad())
        path.lineTo(pad(), h - pad())
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = Theme.alpha(color, 40)
        c.drawPath(path, paint)
        // the two handles with their names
        for (k in 0..1) {
            val x = handleX(k)
            val y = handleY(k)
            paint.style = Paint.Style.FILL
            paint.color = if (on[k]) color else Theme.FAINT
            c.drawCircle(x, y, (if (drag == k) 13 else 10) * dp, paint)
            paint.color = Theme.onColor(paint.color)
            paint.textSize = 10 * dp
            paint.textAlign = Paint.Align.CENTER
            c.drawText(if (k == 0) "H" else "L", x, y + 3.5f * dp, paint)
            paint.color = Theme.DIM
            paint.textSize = 10 * dp
            val label = (if (k == 0) "HIGH CUT " else "LOW CUT ") + if (on[k]) hzText(cutHz(cutA[k])) else "off"
            paint.textAlign = if (k == 0) Paint.Align.RIGHT else Paint.Align.LEFT
            c.drawText(label, if (k == 0) w - pad() else pad(), pad() + 8 * dp, paint)
            paint.textAlign = Paint.Align.LEFT
        }
    }

    private fun hzText(hz: Double): String = if (hz >= 1000) String.format(java.util.Locale.US, "%.1f kHz", hz / 1000) else "${hz.toInt()} Hz"

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                // the nearer handle; between them, the side of the touch
                val d0 = abs(ev.x - handleX(0)) + abs(ev.y - handleY(0)) * 0.3f
                val d1 = abs(ev.x - handleX(1)) + abs(ev.y - handleY(1)) * 0.3f
                drag = if (d0 <= d1) 0 else 1
                move(ev.x, ev.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag >= 0) move(ev.x, ev.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                onRelease?.invoke()
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun move(x: Float, y: Float) {
        val k = drag
        val a = cutA(hzOf(x))
        val b = ((DB_TOP - (DB_TOP - DB_BOT) * ((y - pad()) / (height - 2 * pad()))).let { (it + 12.0) / 24.0 }).toFloat().coerceIn(0f, 1f)
        // all the way out = off
        val off = if (k == 0) a >= 0.99f else a <= 0.01f
        cutA[k] = if (off) (if (k == 0) 1f else 0f) else a
        resB[k] = b
        on[k] = !off
        onChange?.invoke(k, cutA[k], resB[k], on[k])
        invalidate()
    }
}
