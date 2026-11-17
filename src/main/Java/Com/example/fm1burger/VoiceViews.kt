package com.example.fm1burger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Live waveform of the voice: the last 240 slices, with the noise-gate level as green lines. */
class WaveView(ctx: Context) : View(ctx) {
    var ch: VocalChannel? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = Color.rgb(20, 20, 24)
        c.drawRect(0f, 0f, w, h, p)
        val v = ch ?: return
        val mid = h / 2f
        val n = VocalChannel.WF
        val cw = w / n
        val pos = v.wfPos
        for (i in 0 until n) {
            val idx = (pos + i) % n
            val mx = v.wfMax[idx]
            val mn = v.wfMin[idx]
            val peak = if (mx > -mn) mx else -mn
            p.color = if (peak > 0.95f) Color.rgb(230, 60, 60) else Color.rgb(90, 200, 120)
            val top = mid - mx.coerceIn(-1f, 1f) * mid
            val bot = mid - mn.coerceIn(-1f, 1f) * mid
            val x = i * cw
            c.drawRect(x, top, x + cw + 0.5f, if (bot - top < 1f) top + 1f else bot, p)
        }
        p.color = Color.rgb(70, 70, 80)
        c.drawRect(0f, mid - 0.5f, w, mid + 0.5f, p)
        val g = v.gateThreshold()
        if (g > 0f) {
            p.color = Color.rgb(255, 200, 60)
            val d = (g * 5f).coerceIn(0f, 1f) * mid
            c.drawRect(0f, mid - d - 1f, w, mid - d, p)
            c.drawRect(0f, mid + d, w, mid + d + 1f, p)
        }
    }
}

/** Frequency graph: live spectrum behind the equalizer curve. Drag the 5 dots. */
class EqView(ctx: Context) : View(ctx) {
    var ch: VocalChannel? = null
    var onEdit: (() -> Unit)? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val cols = 160
    private val smooth = FloatArray(cols)
    private val re = DoubleArray(1024)
    private val im = DoubleArray(1024)
    private val win = DoubleArray(1024) { 0.5 - 0.5 * cos(2.0 * PI * it / 1023.0) }
    private val fMin = 20.0
    private val fMax = 20000.0
    private val dbRange = 15f
    private val lo = floatArrayOf(40f, 100f, 400f, 1000f, 3000f)
    private val hi = floatArrayOf(300f, 1000f, 4000f, 10000f, 16000f)
    private var drag = -1

    private fun fx(f: Double): Float = (width * (ln(f / fMin) / ln(fMax / fMin))).toFloat()
    private fun xf(x: Float): Double = fMin * (fMax / fMin).pow((x / width).toDouble())
    private fun gy(g: Float): Float = height / 2f - g / dbRange * (height / 2f)
    private fun yg(y: Float): Float = (height / 2f - y) / (height / 2f) * dbRange

    private fun fft() {
        var j = 0
        val n = 1024
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val xr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val xi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + xr
                    im[i + k] = ui + xi
                    re[i + k + len / 2] = ur - xr
                    im[i + k + len / 2] = ui - xi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = Color.rgb(20, 20, 24)
        c.drawRect(0f, 0f, w, h, p)
        val v = ch ?: return

        // spectrum
        val end = v.specPos
        for (i in 0 until 1024) {
            re[i] = v.spec[(end - 1024 + i) and (VocalChannel.SPEC - 1)] * win[i]
            im[i] = 0.0
        }
        fft()
        val fs = Cfg.SR.toDouble()
        p.color = Color.argb(150, 70, 140, 200)
        for (k in 0 until cols) {
            val f1 = fMin * (fMax / fMin).pow(k.toDouble() / cols)
            val f2 = fMin * (fMax / fMin).pow((k + 1.0) / cols)
            var b1 = (f1 / fs * 1024).toInt().coerceIn(1, 511)
            var b2 = (f2 / fs * 1024).toInt().coerceIn(1, 511)
            if (b2 < b1) b2 = b1
            var m = 0.0
            for (b in b1..b2) {
                val a = sqrt(re[b] * re[b] + im[b] * im[b]) / 256.0
                if (a > m) m = a
            }
            val db = (20.0 * log10(m + 1e-9)).toFloat().coerceIn(-90f, -20f)
            val t = (db + 90f) / 70f
            smooth[k] = if (t > smooth[k]) t else smooth[k] * 0.85f + t * 0.15f
            val x1 = w * k / cols
            val x2 = w * (k + 1) / cols
            val top = h - smooth[k] * h
            c.drawRect(x1, top, x2 - 1f, h, p)
        }

        // grid
        p.color = Color.rgb(50, 50, 60)
        for (f in doubleArrayOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)) {
            val x = fx(f)
            c.drawRect(x, 0f, x + 1f, h, p)
        }
        for (g in intArrayOf(-10, 0, 10)) {
            val y = gy(g.toFloat())
            c.drawRect(0f, y, w, y + 1f, p)
        }
        p.textSize = 11f * resources.displayMetrics.density
        p.color = Color.rgb(140, 140, 150)
        c.drawText("100", fx(100.0) + 3f, h - 4f, p)
        c.drawText("1k", fx(1000.0) + 3f, h - 4f, p)
        c.drawText("10k", fx(10000.0) + 3f, h - 4f, p)

        // response curve
        val coefs = v.responseCoefs()
        path.reset()
        val steps = 200
        for (i in 0..steps) {
            val f = fMin * (fMax / fMin).pow(i.toDouble() / steps)
            var m = 1.0
            for (cf in coefs) m *= Bq.mag(cf, fs, f)
            val db = (20.0 * log10(m + 1e-9)).toFloat().coerceIn(-dbRange, dbRange)
            val x = w * i / steps
            val y = gy(db)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = Color.rgb(255, 170, 60)
        c.drawPath(path, p)

        // dots
        p.style = Paint.Style.FILL
        val r = 11f * resources.displayMetrics.density
        for (i in 0 until 5) {
            val x = fx(v.eqF[i].toDouble())
            val y = gy(v.eqG[i])
            p.color = if (i == drag) Color.WHITE else Color.rgb(255, 170, 60)
            c.drawCircle(x, y, r, p)
            p.color = Color.BLACK
            p.textAlign = Paint.Align.CENTER
            p.textSize = 12f * resources.displayMetrics.density
            c.drawText("${i + 1}", x, y + 4f * resources.displayMetrics.density, p)
            p.textAlign = Paint.Align.LEFT
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val v = ch ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                var best = -1
                var bd = 64f * resources.displayMetrics.density
                for (i in 0 until 5) {
                    val dx = fx(v.eqF[i].toDouble()) - e.x
                    val dy = gy(v.eqG[i]) - e.y
                    val d = sqrt(dx * dx + dy * dy)
                    if (d < bd) {
                        bd = d
                        best = i
                    }
                }
                drag = best
                if (drag < 0) return false
                move(v, e)
            }
            MotionEvent.ACTION_MOVE -> if (drag >= 0) move(v, e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = -1
                invalidate()
            }
        }
        return true
    }

    private fun move(v: VocalChannel, e: MotionEvent) {
        val x = e.x.coerceIn(0f, width.toFloat())
        var g = yg(e.y).coerceIn(-dbRange, dbRange)
        if (abs(g) < 0.4f) g = 0f
        v.eqF[drag] = xf(x).toFloat().coerceIn(lo[drag], hi[drag])
        v.eqG[drag] = g
        onEdit?.invoke()
        invalidate()
    }
}
