package com.example.fm1burger

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh

private class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun lowpass(fc: Float, q: Float) {
        val w = 2f * PI.toFloat() * fc / Cfg.SR
        val cw = cos(w)
        val al = sin(w) / (2f * q)
        val a0 = 1f + al
        b0 = ((1f - cw) / 2f) / a0
        b1 = (1f - cw) / a0
        b2 = b0
        a1 = (-2f * cw) / a0
        a2 = (1f - al) / a0
    }

    fun highpass(fc: Float, q: Float) {
        val w = 2f * PI.toFloat() * fc / Cfg.SR
        val cw = cos(w)
        val al = sin(w) / (2f * q)
        val a0 = 1f + al
        b0 = ((1f + cw) / 2f) / a0
        b1 = -(1f + cw) / a0
        b2 = b0
        a1 = (-2f * cw) / a0
        a2 = (1f - al) / a0
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }
}

private class Comb(len: Int) {
    private val buf = FloatArray(len)
    private var i = 0
    private var st = 0f
    fun p(x: Float, fb: Float, damp: Float): Float {
        val o = buf[i]
        st = o * (1f - damp) + st * damp
        buf[i] = x + st * fb
        i++
        if (i >= buf.size) i = 0
        return o
    }
}

private class AllP(len: Int) {
    private val buf = FloatArray(len)
    private var i = 0
    fun p(x: Float): Float {
        val bo = buf[i]
        val o = -x + bo
        buf[i] = x + bo * 0.5f
        i++
        if (i >= buf.size) i = 0
        return o
    }
}

/** Effect chain of one track. The order of the 10 effects can be changed. */
class FxChain {

    companion object {
        const val N = 10
        val NAMES = arrayOf(
            "Low-pass", "High-pass", "Reverb", "Delay", "Distortion",
            "Chorus", "Phaser", "Lo-fi", "Wobble", "Chop"
        )
        val LABEL_A = arrayOf(
            "Cutoff", "Cutoff", "Mix", "Mix", "Drive",
            "Depth", "Mix", "Bits", "Depth", "Depth"
        )
        val LABEL_B = arrayOf(
            "Resonance", "Resonance", "Size", "Time", "Mix",
            "Rate", "Rate", "Rate reduce", "Rate", "Rate"
        )
        val DEF_A = floatArrayOf(1f, 0f, 0.3f, 0.3f, 0.3f, 0.5f, 0.5f, 0.3f, 0.5f, 0.5f)
        val DEF_B = floatArrayOf(0.1f, 0.1f, 0.5f, 0.5f, 0.5f, 0.3f, 0.3f, 0.3f, 0.3f, 0.5f)
        private val DELAY_BEATS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f)
        private val CHOP_BEATS = doubleArrayOf(1.0, 0.5, 0.25, 0.125)

        private fun cut(a: Float): Float = 40f * 450f.pow(a)
    }

    val on = BooleanArray(N)

    /** How hard every effect goes: 1 = normal, up to 2 = extreme (longer, wetter, deeper, dirtier). */
    @Volatile var intensity = 1f

    val a = FloatArray(N) { DEF_A[it] }
    val b = FloatArray(N) { DEF_B[it] }

    @Volatile
    var order: IntArray = IntArray(N) { it }

    // low-pass / high-pass
    private val lp = Biquad()
    private val hp = Biquad()
    private var lpA = -1f
    private var lpB = -1f
    private var hpA = -1f
    private var hpB = -1f
    private var lastK0 = -1f
    private var lastK1 = -1f

    // reverb
    private val combs = arrayOf(Comb(1116), Comb(1188), Comb(1277), Comb(1356))
    private val aps = arrayOf(AllP(556), AllP(441))

    // delay
    private val dbuf = FloatArray(Cfg.SR * 2)
    private var dw = 0

    // chorus
    private val cbuf = FloatArray(4096)
    private var cw = 0
    private var cph = 0f

    // phaser
    private val apz = FloatArray(4)
    private var pph = 0f
    private var pfb = 0f
    private var pcoef = 0f
    private var pcnt = 0

    // lo-fi
    private var lfHold = 0f
    private var lfCnt = 0

    // wobble
    private var wph = 0f
    private var wy = 0f

    // chop
    private var cgs = 1f

    fun reset() {
        dbuf.fill(0f)
        cbuf.fill(0f)
        apz.fill(0f)
        pfb = 0f
        wy = 0f
    }

    fun process(x0: Float, bpm: Double, beat: Double): Float {
        var x = x0
        val ord = order
        for (k in 0 until N) {
            val i = ord[k]
            if (!on[i]) continue
            x = when (i) {
                0 -> lpf(x)
                1 -> hpf(x)
                2 -> reverb(x)
                3 -> delay(x, bpm)
                4 -> dist(x)
                5 -> chorus(x)
                6 -> phaser(x)
                7 -> lofi(x)
                8 -> wobble(x)
                else -> chop(x, beat)
            }
        }
        // extreme settings can get loud: a soft safety limit
        if (intensity > 1.01f && abs(x) > 1.2f) x = tanh(x / 1.2f) * 1.2f
        return x
    }

    private val k: Float get() = (intensity - 1f).coerceIn(0f, 1f)   // 0 at normal, 1 at extreme

    private fun lpf(x: Float): Float {
        if (a[0] >= 0.99f) return x
        if (a[0] != lpA || b[0] != lpB || k != lastK0) {
            lastK0 = k
            lpA = a[0]
            lpB = b[0]
            lp.lowpass(cut(lpA), 0.7f + lpB * (8f + 10f * k))
        }
        return lp.process(x)
    }

    private fun hpf(x: Float): Float {
        if (a[1] <= 0.01f) return x
        if (a[1] != hpA || b[1] != hpB || k != lastK1) {
            lastK1 = k
            hpA = a[1]
            hpB = b[1]
            hp.highpass(cut(hpA), 0.7f + hpB * (8f + 10f * k))
        }
        return hp.process(x)
    }

    private fun reverb(x: Float): Float {
        val fb = (0.7f + b[2] * (0.25f + 0.035f * k)).coerceAtMost(0.985f)
        val inp = x * 0.06f
        var s = 0f
        for (c in combs) s += c.p(inp, fb, 0.3f - 0.15f * k)
        var w = s
        for (ap in aps) w = ap.p(w)
        val m = a[2]
        // extreme: up to fully wet, and louder tails
        return x * (1f - (m * (0.5f + 0.5f * k)).coerceAtMost(1f)) + w * m * (3f + 2f * k)
    }

    private fun delay(x: Float, bpm: Double): Float {
        val beats = DELAY_BEATS[(b[3] * 3.99f).toInt().coerceIn(0, 3)]
        var n = (beats * 60.0 / bpm * Cfg.SR).toInt()
        n = n.coerceIn(1, dbuf.size - 1)
        var r = dw - n
        if (r < 0) r += dbuf.size
        val d = dbuf[r]
        dbuf[dw] = x + d * (0.45f + 0.45f * k)      // more repeats, up to a long dub tail
        dw++
        if (dw >= dbuf.size) dw = 0
        return x + d * a[3] * (1f + 0.6f * k)
    }

    private fun dist(x: Float): Float {
        val drive = 1f + a[4] * (25f + 75f * k)
        val y = tanh(x * drive) * 0.8f
        return x * (1f - b[4]) + y * b[4]
    }

    private fun chorus(x: Float): Float {
        cbuf[cw] = x
        cph += (0.2f + b[5] * 3f) / Cfg.SR
        if (cph >= 1f) cph -= 1f
        val depth = (3f + a[5] * (7f + 9f * k)) * Cfg.SR / 1000f
        val d = 12f * Cfg.SR / 1000f + sin(2f * PI.toFloat() * cph) * depth * 0.5f
        var pos = cw - d
        while (pos < 0f) pos += cbuf.size
        val i0 = pos.toInt().coerceIn(0, cbuf.size - 1)
        val fr = pos - i0
        val i1 = if (i0 + 1 >= cbuf.size) 0 else i0 + 1
        val s = cbuf[i0] * (1f - fr) + cbuf[i1] * fr
        cw++
        if (cw >= cbuf.size) cw = 0
        return x * (1f - a[5] * (0.4f + 0.3f * k)) + s * a[5] * (0.7f + 0.3f * k)
    }

    private fun phaser(x: Float): Float {
        if (pcnt <= 0) {
            pcnt = 16
            pph += 16f * (0.1f + b[6] * 2f) / Cfg.SR
            if (pph >= 1f) pph -= 1f
            val fc = 300f * 6f.pow(0.5f + 0.5f * sin(2f * PI.toFloat() * pph))
            val t = tan(PI.toFloat() * fc / Cfg.SR)
            pcoef = (t - 1f) / (t + 1f)
        }
        pcnt--
        var s = x + pfb * (0.5f + 0.4f * k)
        for (k in 0 until 4) {
            val y = pcoef * s + apz[k]
            apz[k] = s - pcoef * y
            s = y
        }
        pfb = s
        return x * (1f - a[6] * (0.5f + 0.2f * k)) + s * a[6] * (0.5f + 0.4f * k)
    }

    private fun lofi(x: Float): Float {
        if (lfCnt <= 0) {
            val bits = 16f - a[7] * (12f + 2f * k)          // down to 2 bits at extreme
            val levels = 2f.pow(bits - 1f)
            lfHold = round(x * levels) / levels
            lfCnt = 1 + (b[7] * b[7] * (15f + 33f * k)).toInt()
        }
        lfCnt--
        return lfHold
    }

    private fun wobble(x: Float): Float {
        wph += (0.3f + b[8] * 8f) / Cfg.SR
        if (wph >= 1f) wph -= 1f
        val l = sin(2f * PI.toFloat() * wph)
        val fc = 18000f * (0.01f - 0.007f * k).pow(a[8] * (0.5f + 0.5f * l))
        val g = (1f - exp(-2f * PI.toFloat() * fc / Cfg.SR)).coerceIn(0f, 1f)
        wy += g * (x - wy)
        return wy
    }

    private fun chop(x: Float, beat: Double): Float {
        val per = CHOP_BEATS[(b[9] * 3.99f).toInt().coerceIn(0, 3)]
        val ph = (beat / per) % 1.0
        val g = if (ph < 0.5) 1f else 1f - a[9]
        cgs += (g - cgs) * (0.02f + 0.18f * k)     // extreme: hard, gated edges
        return x * cgs
    }
}
