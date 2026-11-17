package com.example.fm1burger

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Drum sounds made in code (electronic 808 / 909 style). Nothing to download.
 * ensureAll() writes them into the sample library as Drum_*.wav files.
 */
object DrumKit {

    private const val SR = 44100
    private val TAU = (2.0 * PI).toFloat()

    val names = listOf(
        "Kick", "Kick Soft", "Snare", "Rim", "Clap",
        "Hat Closed", "Hat Open", "Hat Pedal",
        "Tom Low", "Tom Mid", "Tom High",
        "Crash", "Ride", "Cowbell", "Tambourine", "Shaker", "Conga"
    )

    /** The 8 sounds that go on the 8 pads, in the order of the 8 sequencer rows. */
    val padKit = listOf("Kick", "Snare", "Hat Closed", "Hat Open", "Clap", "Tom Low", "Tom High", "Crash")

    fun fileName(n: String): String = "Drum_" + n.replace(' ', '_') + ".wav"

    /** Writes every missing drum sound into the library. Returns how many were created. */
    fun ensureAll(store: Store): Int {
        var made = 0
        for (n in names) {
            val f = File(store.dir, fileName(n))
            if (!f.exists()) {
                Wav.write(f, make(n), SR)
                made++
            }
        }
        return made
    }

    // ---------------- helpers ----------------

    private class Noise(seed: Int) {
        private var s = seed
        fun next(): Float {
            s = s * 1664525 + 1013904223
            return ((s ushr 8) and 0xFFFF) / 32768f - 1f
        }
    }

    private fun noiseArr(n: Int, seed: Int): FloatArray {
        val g = Noise(seed)
        return FloatArray(n) { g.next() }
    }

    private fun hp(x: FloatArray, hz: Float): FloatArray {
        val rc = 1f / (TAU * hz)
        val dt = 1f / SR
        val a = rc / (rc + dt)
        val y = FloatArray(x.size)
        for (i in 1 until x.size) y[i] = a * (y[i - 1] + x[i] - x[i - 1])
        return y
    }

    private fun lp(x: FloatArray, hz: Float): FloatArray {
        val rc = 1f / (TAU * hz)
        val dt = 1f / SR
        val al = dt / (rc + dt)
        val y = FloatArray(x.size)
        for (i in 1 until x.size) y[i] = y[i - 1] + al * (x[i] - y[i - 1])
        return y
    }

    private fun square(t: Float, f: Float): Float = if ((t * f) % 1f < 0.5f) 1f else -1f

    private val hatF = floatArrayOf(205.3f, 304.4f, 369.6f, 522.7f, 540f, 800f)

    private fun metal(n: Int, cutoff: Float): FloatArray {
        val raw = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            var s = 0f
            for (f in hatF) s += square(t, f)
            raw[i] = s / hatF.size
        }
        return hp(raw, cutoff)
    }

    /** Scales to a good level and fades the last few milliseconds so nothing ends with a click. */
    private fun finish(y: FloatArray): FloatArray {
        var pk = 0f
        for (v in y) {
            val a = abs(v)
            if (a > pk) pk = a
        }
        if (pk > 0.0001f) {
            val g = 0.9f / pk
            for (i in y.indices) y[i] = y[i] * g
        }
        val n = minOf(220, y.size)
        for (i in 0 until n) {
            val g = i.toFloat() / n
            y[y.size - 1 - i] = y[y.size - 1 - i] * g
        }
        return y
    }

    // ---------------- the sounds ----------------

    fun make(name: String): FloatArray = finish(
        when (name) {
            "Kick" -> kick(false)
            "Kick Soft" -> kick(true)
            "Snare" -> snare()
            "Rim" -> rim()
            "Clap" -> clap()
            "Hat Closed" -> hat(0.15f, 0.02f, 11)
            "Hat Open" -> hat(0.9f, 0.22f, 12)
            "Hat Pedal" -> hat(0.25f, 0.045f, 13)
            "Tom Low" -> tom(85f)
            "Tom Mid" -> tom(125f)
            "Tom High" -> tom(175f)
            "Crash" -> crash()
            "Ride" -> ride()
            "Cowbell" -> cowbell()
            "Tambourine" -> tambourine()
            "Shaker" -> shaker()
            else -> conga()
        }
    )

    private fun kick(soft: Boolean): FloatArray {
        val n = (SR * (if (soft) 0.6f else 0.5f)).toInt()
        val y = FloatArray(n)
        val nz = Noise(1)
        val f0 = if (soft) 38f else 48f
        val fs = if (soft) 70f else 130f
        val tau = if (soft) 0.28f else 0.2f
        var ph = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            val f = f0 + fs * exp(-t / 0.03f)
            ph += TAU * f / SR
            var v = sin(ph) * exp(-t / tau)
            if (!soft) v += nz.next() * 0.25f * exp(-t / 0.004f)
            y[i] = tanh(v * 1.6f)
        }
        return y
    }

    private fun snare(): FloatArray {
        val n = (SR * 0.35f).toInt()
        val hn = hp(noiseArr(n, 2), 1500f)
        val y = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            val tone = sin(TAU * 180f * t) * 0.6f + sin(TAU * 330f * t) * 0.3f
            y[i] = tone * exp(-t / 0.05f) + hn[i] * 0.8f * exp(-t / 0.09f)
        }
        return y
    }

    private fun rim(): FloatArray {
        val n = (SR * 0.12f).toInt()
        val nz = noiseArr(n, 3)
        val y = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            val tone = sin(TAU * 1700f * t) * 0.5f + sin(TAU * 480f * t) * 0.5f
            y[i] = tone * exp(-t / 0.012f) + nz[i] * 0.2f * exp(-t / 0.003f)
        }
        return y
    }

    private fun clap(): FloatArray {
        val n = (SR * 0.4f).toInt()
        val nz = lp(hp(noiseArr(n, 4), 900f), 4500f)
        val y = FloatArray(n)
        val bursts = floatArrayOf(0f, 0.011f, 0.022f)
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            var e = 0f
            for (tb in bursts) if (t >= tb) e = max(e, exp(-(t - tb) / 0.007f))
            if (t >= 0.033f) e = max(e, 0.8f * exp(-(t - 0.033f) / 0.09f))
            y[i] = nz[i] * e
        }
        return y
    }

    private fun hat(secs: Float, tau: Float, seed: Int): FloatArray {
        val n = (SR * secs).toInt()
        val m = metal(n, 6500f)
        val nz = hp(noiseArr(n, seed), 7000f)
        val y = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            y[i] = (m[i] * 0.7f + nz[i] * 0.5f) * exp(-t / tau)
        }
        return y
    }

    private fun tom(f0: Float): FloatArray {
        val n = (SR * 0.55f).toInt()
        val nz = noiseArr(n, 5)
        val y = FloatArray(n)
        var ph = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            val f = f0 * (1f + 0.5f * exp(-t / 0.04f))
            ph += TAU * f / SR
            val v = sin(ph) * exp(-t / 0.22f) + nz[i] * 0.15f * exp(-t / 0.003f)
            y[i] = tanh(v * 1.3f)
        }
        return y
    }

    private fun crash(): FloatArray {
        val n = (SR * 2.0f).toInt()
        val m = metal(n, 4000f)
        val nz = hp(noiseArr(n, 6), 5000f)
        val y = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            val e = exp(-t / 0.55f) * (1f - exp(-t / 0.002f))
            y[i] = (nz[i] * 0.6f + m[i] * 0.5f) * e
        }
        return y
    }

    private fun ride(): FloatArray {
        val n = (SR * 1.4f).toInt()
        val m = metal(n, 5000f)
        val nz = hp(noiseArr(n, 7), 8000f)
        val y = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            val ping = sin(TAU * 2800f * t) * 0.4f * exp(-t / 0.35f)
            y[i] = ping + m[i] * 0.35f * exp(-t / 0.5f) + nz[i] * 0.1f * exp(-t / 0.3f)
        }
        return y
    }

    private fun cowbell(): FloatArray {
        val n = (SR * 0.4f).toInt()
        val raw = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            raw[i] = (square(t, 560f) + square(t, 845f)) * 0.5f
        }
        val b = lp(hp(raw, 400f), 3500f)
        val y = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            y[i] = b[i] * exp(-t / 0.09f)
        }
        return y
    }

    private fun tambourine(): FloatArray {
        val n = (SR * 0.35f).toInt()
        val nz = hp(noiseArr(n, 8), 6000f)
        val y = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            var e = exp(-t / 0.05f)
            if (t >= 0.018f) e = max(e, 0.8f * exp(-(t - 0.018f) / 0.06f))
            y[i] = nz[i] * e
        }
        return y
    }

    private fun shaker(): FloatArray {
        val n = (SR * 0.22f).toInt()
        val nz = lp(hp(noiseArr(n, 9), 5000f), 11000f)
        val y = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            val e = if (t < 0.035f) t / 0.035f else exp(-(t - 0.035f) / 0.05f)
            y[i] = nz[i] * e
        }
        return y
    }

    private fun conga(): FloatArray {
        val n = (SR * 0.32f).toInt()
        val nz = hp(noiseArr(n, 10), 1000f)
        val y = FloatArray(n)
        var ph = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            val f = 190f + 40f * exp(-t / 0.02f)
            ph += TAU * f / SR
            y[i] = sin(ph) * exp(-t / 0.1f) + nz[i] * 0.15f * exp(-t / 0.006f)
        }
        return y
    }
}
