package com.example.fm1burger

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * NEW AMBIENT: a living soundscape of made-up creatures.
 *
 * Every creature is built the same way, from three layers:
 *  1. a SOURCE that vibrates or strikes (a sine, a pulse train, noise, a buzzing saw...)
 *  2. a BODY that the source rings (a resonant filter, a formant, a slow sweep)
 *  3. a slow BEHAVIOUR on top: calls made of syllables, grouped into phrases,
 *     how often they call (DENSITY) and how much every call differs (WANDER).
 * Then everything floats in its own stereo SPACE (a reverb).
 *
 * With FREE on the creatures call by themselves, even while the sequencer is stopped.
 * The 16 steps make a call on each lit step while the sequencer plays.
 * Runs on the audio thread: render() once per sample, step() from the sequencer.
 */
class NewAmbient {

    companion object {
        const val FIREFLY = 0
        const val FROG = 1
        const val WHALE = 2
        const val CICADA = 3
        const val OWL = 4
        const val JELLY = 5
        const val WIND = 6
        const val N_KIND = 7

        val NAMES = arrayOf("Firefly", "Rain frog", "Sky whale", "Night cicada", "Moon owl", "Jellyfish", "Wind spirit")
        val ABOUT = arrayOf(
            "Glassy pings that light up and fade, like tiny bells in the dark.",
            "Low wooden croaks from a pond after the rain, in short groups.",
            "Huge slow songs that glide from note to note across the sky.",
            "A shimmering high buzz that swells and fades in waves.",
            "Soft breathy hoots, two or three at a time.",
            "A floating pad that slowly breathes open and closed.",
            "Whistling air that wanders through hollow places."
        )
        private const val VOICES = 6
        /** Evens out the loudness of the creatures (measured: each one peaks near the same level). */
        private val KIND_GAIN = floatArrayOf(0.92f, 4.2f, 0.42f, 0.8f, 1.2f, 1.6f, 0.26f)
        const val WF = 240
    }

    // ---------------- controls (UI thread writes, audio thread reads) ----------------
    @Volatile var kind = FIREFLY
    @Volatile var density = 0.45f     // how often they call
    @Volatile var pitch = 0           // semitones, -12 .. +12
    @Volatile var tone = 0.5f         // brightness / character
    @Volatile var wander = 0.5f       // how different every call is
    @Volatile var space = 0.55f       // reverb amount
    @Volatile var volume = 0.7f
    @Volatile var mute = false
    @Volatile var free = false        // call by themselves
    @Volatile var key = 0             // the scale's root, 0 = C
    val steps = BooleanArray(Cfg.STEPS)
    @Volatile var callReq = false

    // output of the last sample
    var outL = 0f
    var outR = 0f

    // live picture for the screens: min / max of the output over short windows
    val wfMin = FloatArray(WF)
    val wfMax = FloatArray(WF)
    @Volatile var wfPos = 0
    private var wMin = 0f
    private var wMax = 0f
    private var wCount = 0

    // ---------------- voices ----------------
    private class Voice {
        var on = false
        var kind = 0
        var t = 0            // samples since the start
        var len = 1          // length in samples
        var f0 = 220.0       // main frequency
        var f1 = 220.0       // target frequency (glides)
        var p1 = 0.0
        var p2 = 0.0
        var p3 = 0.0
        var amp = 1f
        var pan = 0f
        var a = 0f           // per call random 0..1
        var b = 0f
        var lp = 0f          // one-pole state
        var s1 = 0f          // filter states
        var s2 = 0f
        var s3 = 0f
        var s4 = 0f
    }

    private val voices = Array(VOICES) { Voice() }
    private var rnd = 0x2545F491
    private var untilNext = 0

    private fun rand(): Float {
        var x = rnd
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rnd = x
        return (x ushr 8) / 16777216f
    }

    private fun noise(): Float = rand() * 2f - 1f

    private val sr = Cfg.SR.toFloat()
    private val pentatonic = intArrayOf(0, 2, 4, 7, 9)

    /** A note of the pentatonic scale on the key, around a base octave (MIDI), moved by PITCH and WANDER. */
    private fun scaleHz(baseMidi: Int, spread: Int): Double {
        val n = (rand() * (spread * 5 + 1)).toInt()
        val deg = pentatonic[n % 5] + 12 * (n / 5)
        val midi = baseMidi + key + pitch + deg
        return 440.0 * 2.0.pow((midi - 69) / 12.0)
    }

    // ---------------- reverb: 4 combs + 2 allpasses per side (a small Schroeder room) ----------------
    private val combLen = intArrayOf(1557, 1617, 1491, 1422)
    private val apLen = intArrayOf(225, 556)
    private val spread = 23
    private val combL = Array(4) { FloatArray(combLen[it]) }
    private val combR = Array(4) { FloatArray(combLen[it] + spread) }
    private val combPos = IntArray(4)
    private val combPosR = IntArray(4)
    private val combLp = FloatArray(8)
    private val apL = Array(2) { FloatArray(apLen[it]) }
    private val apR = Array(2) { FloatArray(apLen[it] + spread) }
    private val apPos = IntArray(2)
    private val apPosR = IntArray(2)

    private fun reverb(input: Float, right: Boolean): Float {
        var out = 0f
        val fb = 0.80f + 0.16f * space
        val damp = 0.35f
        for (i in 0 until 4) {
            val buf = if (right) combR[i] else combL[i]
            val pos = if (right) combPosR[i] else combPos[i]
            val y = buf[pos]
            val li = if (right) i + 4 else i
            combLp[li] = y * (1f - damp) + combLp[li] * damp
            buf[pos] = input + combLp[li] * fb
            out += y
            val np = if (pos + 1 >= buf.size) 0 else pos + 1
            if (right) combPosR[i] = np else combPos[i] = np
        }
        for (i in 0 until 2) {
            val buf = if (right) apR[i] else apL[i]
            val pos = if (right) apPosR[i] else apPos[i]
            val bo = buf[pos]
            val y = -out + bo
            buf[pos] = out + bo * 0.5f
            out = y
            val np = if (pos + 1 >= buf.size) 0 else pos + 1
            if (right) apPosR[i] = np else apPos[i] = np
        }
        return out * 0.25f
    }

    // ---------------- calls ----------------

    /** Sequencer step (audio thread). */
    fun step(s: Int) {
        if (s in 0 until Cfg.STEPS && steps[s]) call()
    }

    /** Starts one call of the chosen creature on a free voice (or the oldest one). */
    fun call() {
        var v = voices.firstOrNull { !it.on }
        if (v == null) v = voices.maxByOrNull { it.t.toFloat() / max(1, it.len) } ?: return
        val k = kind
        val w = wander
        v.kind = k
        v.t = 0
        v.p1 = 0.0
        v.p2 = 0.0
        v.p3 = 0.0
        v.lp = 0f
        v.s1 = 0f; v.s2 = 0f; v.s3 = 0f; v.s4 = 0f
        v.a = rand()
        v.b = rand()
        v.pan = (rand() * 2f - 1f) * (0.25f + 0.55f * w)
        v.amp = 1f - w * 0.4f * rand()
        val secs: Float
        when (k) {
            FIREFLY -> {
                v.f0 = scaleHz(76, 2 + (w * 2).toInt())
                secs = 1.2f + rand() * 1.4f
            }
            FROG -> {
                v.f0 = scaleHz(50, 1)
                secs = 0.5f + rand() * 0.6f * (0.5f + w)
            }
            WHALE -> {
                v.f0 = scaleHz(45, 2)
                v.f1 = scaleHz(45, 2 + (w * 2).toInt())
                secs = 3.5f + rand() * 4f
            }
            CICADA -> {
                v.f0 = 3500.0 + 3500.0 * tone + rand() * 800.0 * w
                v.f1 = 70.0 + rand() * 90.0
                secs = 2f + rand() * 2.5f
            }
            OWL -> {
                v.f0 = scaleHz(57, 1)
                secs = 1.1f + rand() * 0.8f
            }
            JELLY -> {
                v.f0 = scaleHz(40, 1)
                secs = 6f + rand() * 5f
            }
            else -> {
                v.f0 = 500.0 + rand() * 1200.0
                v.f1 = 0.2 + rand() * 0.5
                secs = 4f + rand() * 5f
            }
        }
        v.len = (secs * sr).toInt()
        v.on = true
    }

    /** How long until the next free call: from about 8 s (density 0) to a quarter second (density 1). */
    private fun nextGap(): Int {
        val mean = 8f * (1f - density).pow(2) + 0.25f
        val jitter = 0.4f + rand() * (0.6f + 1.4f * wander)
        return (mean * jitter * sr).toInt()
    }

    // ---------------- filters ----------------

    /** One step of a TPT state variable filter; returns the band-pass, keeps the low-pass in s2. */
    private fun svfBand(v: Voice, x: Float, hz: Float, q: Float): Float {
        val g = tan(PI * min(hz, sr * 0.45f) / sr).toFloat()
        val k = 1f / q
        val a1 = 1f / (1f + g * (g + k))
        val v3 = x - v.s2
        val bp = (a1 * v.s1 + g * a1 * v3)
        val lpv = v.s2 + g * bp
        v.s1 = 2f * bp - v.s1
        v.s2 = 2f * lpv - v.s2
        return bp
    }

    private fun env(t: Int, len: Int, att: Float, rel: Float): Float {
        val ts = t / sr
        val left = (len - t) / sr
        val a = if (att > 0f) min(1f, ts / att) else 1f
        val r = if (rel > 0f) min(1f, left / rel) else 1f
        return max(0f, min(a, r))
    }

    // ---------------- the creatures ----------------

    private fun voiceSample(v: Voice): Float {
        val x = v.t.toFloat() / v.len
        val tSec = v.t / sr
        val twoPi = 2.0 * PI
        val out: Float
        when (v.kind) {
            FIREFLY -> {
                // source: a sine; body: a glassy partial at 3.5x that fades faster (a struck glass bead)
                val e = env(v.t, v.len, 0.003f, 0.05f) * exp(-tSec / (0.25f + 0.35f * v.a))
                val idx = (0.4f + 2.2f * tone) * exp(-tSec * 6f)
                v.p1 += v.f0 / sr
                v.p2 += v.f0 * 3.5 / sr
                val mod = sin(twoPi * v.p2).toFloat() * idx
                var s = sin(twoPi * v.p1 + mod).toFloat()
                // a second softer blink when the call is a double one
                if (v.b > 0.55f && tSec > 0.18f) {
                    val e2 = exp(-(tSec - 0.18f) / 0.3f)
                    v.p3 += v.f0 * 1.5 / sr
                    s += 0.5f * sin(twoPi * v.p3).toFloat() * e2
                }
                out = s * e * 0.55f
            }
            FROG -> {
                // source: a slow pulse train (vocal pulses); body: a resonant sac that sinks a little in pitch
                val rate = 38f + 30f * v.a
                v.p1 += rate / sr
                var pulse = 0f
                if (v.p1 >= 1.0) {
                    v.p1 -= 1.0
                    pulse = 1f
                }
                // two or three croaks inside one call
                val croaks = 2 + (v.b * 2).toInt()
                val cx = (x * croaks) % 1f
                val gate = if (cx < 0.62f) min(1f, cx / 0.05f) * min(1f, (0.62f - cx) / 0.08f) else 0f
                val fc = (v.f0 * (1.0 - 0.22 * cx)).toFloat() * (0.8f + 0.6f * tone)
                val bp = svfBand(v, pulse * 6f + noise() * 0.02f, fc, 7f + 10f * tone)
                out = bp * gate * 0.9f
            }
            WHALE -> {
                // source: a sine with soft harmonics; body: a slow low-pass sweep; behaviour: a long glide
                val g = 0.5f - 0.5f * kotlin.math.cos(PI * min(1f, x * 1.4f)).toFloat()
                val f = v.f0 + (v.f1 - v.f0) * g
                val vib = 1.0 + 0.004 * sin(twoPi * 4.3 * tSec)
                v.p1 += f * vib / sr
                val s = sin(twoPi * v.p1).toFloat() + 0.35f * sin(twoPi * 2 * v.p1).toFloat() +
                    0.18f * sin(twoPi * 3 * v.p1).toFloat()
                val cut = 300f + 2400f * tone * (0.4f + 0.6f * sin(PI * x).toFloat())
                val c = 1f - exp(-2f * PI.toFloat() * cut / sr)
                v.lp += (s - v.lp) * c
                out = v.lp * env(v.t, v.len, 0.9f, 1.6f) * 0.45f
            }
            CICADA -> {
                // source: noise; body: a high resonance; behaviour: a fast buzz that swells and fades
                val bp = svfBand(v, noise(), v.f0.toFloat(), 2.5f + 5f * tone)
                v.p1 += v.f1 / sr
                val am = 0.5f + 0.5f * sin(twoPi * v.p1).toFloat()
                val swell = sin(PI * x).toFloat().pow(1.5f)
                out = bp * am * am * swell * 0.8f
            }
            OWL -> {
                // source: a soft sine with breath; behaviour: two or three hoots that bend down
                val hoots = 2 + (v.b * 1.9f).toInt()
                val hx = (x * hoots) % 1f
                val gate = if (hx < 0.55f) sin(PI * (hx / 0.55f)).toFloat().pow(0.7f) else 0f
                val bend = 1.0 - 0.06 * hx
                val first = if (x < 1f / hoots) 1.06 else 1.0
                v.p1 += v.f0 * bend * first / sr
                val breath = noise() * (0.08f + 0.25f * (1f - tone))
                val c = 0.08f
                v.lp += (breath - v.lp) * c
                out = (sin(twoPi * v.p1).toFloat() + v.lp) * gate * 0.5f
            }
            JELLY -> {
                // source: three slightly detuned saws; body: a low-pass that breathes slowly
                v.p1 += v.f0 / sr
                v.p2 += v.f0 * 1.007 / sr
                v.p3 += v.f0 * 0.5 * 0.996 / sr
                val s = (2.0 * (v.p1 % 1.0) - 1.0 + 2.0 * (v.p2 % 1.0) - 1.0 + 0.7 * (2.0 * (v.p3 % 1.0) - 1.0)).toFloat() / 2.7f
                val breathe = 0.5f + 0.5f * sin(twoPi * (0.12 + 0.2 * v.a) * tSec).toFloat()
                val cut = 180f + (500f + 2200f * tone) * breathe
                val c = 1f - exp(-2f * PI.toFloat() * cut / sr)
                v.lp += (s - v.lp) * c
                v.s3 += (v.lp - v.s3) * c
                out = v.s3 * env(v.t, v.len, 2.2f, 3f) * 0.6f
            }
            else -> {
                // WIND: noise through a resonance that wanders like air in a hollow
                v.p1 += v.f1 / sr
                val wob = 0.5f + 0.35f * sin(twoPi * v.p1).toFloat() + 0.15f * sin(twoPi * v.p1 * 2.7 + v.a * 6).toFloat()
                val fc = (v.f0 * (0.6 + 0.9 * wob)).toFloat()
                val bp = svfBand(v, noise(), fc, 3f + 12f * tone)
                out = bp * env(v.t, v.len, 1.5f, 2.2f) * 0.9f
            }
        }
        return out * v.amp * KIND_GAIN[v.kind.coerceIn(0, N_KIND - 1)]
    }

    /** One stereo sample of the whole soundscape (audio thread). */
    // ---------------- 3.16: thicker and warmer ----------------
    // a slow stereo chorus doubles every creature (thickness), a low / low-mid lift and a soft top (warmth),
    // then a little tape-like saturation, which also makes it louder without hard clipping
    private val chBuf = Array(2) { FloatArray(4096) }
    private var chPos = 0
    private var chPh = 0.0
    private val lowL = GtrFilter(GtrFilter.PEAK, 150f, 0.7f, 5f)
    private val lowR = GtrFilter(GtrFilter.PEAK, 150f, 0.7f, 5f)
    private val midL = GtrFilter(GtrFilter.PEAK, 420f, 0.8f, 2.5f)
    private val midR = GtrFilter(GtrFilter.PEAK, 420f, 0.8f, 2.5f)
    private val topL = GtrFilter(GtrFilter.LP, 6000f, 0.6f)
    private val topR = GtrFilter(GtrFilter.LP, 6000f, 0.6f)

    private fun chorusTap(buf: FloatArray, delay: Float): Float {
        val d = (chPos - delay + buf.size) % buf.size
        val i = d.toInt()
        val fr = d - i
        val a = buf[i % buf.size]
        val b = buf[(i + 1) % buf.size]
        return a + (b - a) * fr
    }

    private var wOutL = 0f
    private var wOutR = 0f

    private fun warm(inL: Float, inR: Float) {
        chBuf[0][chPos] = inL
        chBuf[1][chPos] = inR
        chPh += 0.27 / sr
        if (chPh >= 1.0) chPh -= 1.0
        val m = sin(2.0 * PI * chPh).toFloat()
        val base = 0.016f * sr
        val depth = 0.004f * sr
        // the doubled copy crosses over: left hears the right's copy and the other way round (wider)
        val cl = chorusTap(chBuf[1], base + depth * m)
        val cr = chorusTap(chBuf[0], base * 1.27f - depth * m)
        chPos = (chPos + 1) % chBuf[0].size
        var l = inL + cl * 0.55f
        var r = inR + cr * 0.55f
        l = topL.run(midL.run(lowL.run(l)))
        r = topR.run(midR.run(lowR.run(r)))
        // warm saturation with make-up: about +5 dB for quiet calls, softly held near 1.0 for loud ones
        l = kotlin.math.tanh(l * 1.8f)
        r = kotlin.math.tanh(r * 1.8f)
        wOutL = l
        wOutR = r
    }

    fun render() {
        if (callReq) {
            callReq = false
            call()
        }
        if (free) {
            untilNext--
            if (untilNext <= 0) {
                call()
                untilNext = nextGap()
            }
        }
        var dl = 0f
        var dr = 0f
        var any = false
        for (v in voices) {
            if (!v.on) continue
            any = true
            val s = voiceSample(v)
            val pr = (v.pan + 1f) * 0.5f
            dl += s * (1f - pr)
            dr += s * pr
            v.t++
            if (v.t >= v.len) v.on = false
        }
        val wet = space
        val send = (dl + dr) * 0.5f
        val rl = reverb(send, false)
        val rr = reverb(send, true)
        val g = if (mute) 0f else volume
        warm(dl * (1f - wet * 0.5f) + rl * wet * 1.6f, dr * (1f - wet * 0.5f) + rr * wet * 1.6f)
        outL = wOutL * g
        outR = wOutR * g
        if (!any && !free) untilNext = 0

        // the live picture
        val m = (outL + outR) * 0.5f
        if (m < wMin) wMin = m
        if (m > wMax) wMax = m
        wCount++
        if (wCount >= 512) {
            val i = wfPos % WF
            wfMin[i] = wMin
            wfMax[i] = wMax
            wfPos = wfPos + 1
            wMin = 0f
            wMax = 0f
            wCount = 0
        }
    }

    /** True when something is sounding or will sound by itself. */
    fun active(): Boolean = free || voices.any { it.on }

    fun level(): Float {
        val i = (wfPos - 1 + WF) % WF
        return max(abs(wfMin[i]), abs(wfMax[i]))
    }
}

/** Saves and loads the New Ambient settings. */
object AmbientSave {
    fun save(f: File, a: NewAmbient) {
        try {
            val o = JSONObject()
            o.put("kind", a.kind)
            o.put("density", a.density.toDouble())
            o.put("pitch", a.pitch)
            o.put("tone", a.tone.toDouble())
            o.put("wander", a.wander.toDouble())
            o.put("space", a.space.toDouble())
            o.put("volume", a.volume.toDouble())
            o.put("mute", a.mute)
            o.put("free", a.free)
            o.put("key", a.key)
            val st = JSONArray()
            for (s in a.steps) st.put(s)
            o.put("steps", st)
            SafeIo.writeText(f, o.toString())
        } catch (ex: Exception) {
        }
    }

    fun load(f: File, a: NewAmbient) {
        try {
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            a.kind = o.optInt("kind", 0).coerceIn(0, NewAmbient.N_KIND - 1)
            a.density = o.optDouble("density", 0.45).toFloat().coerceIn(0f, 1f)
            a.pitch = o.optInt("pitch", 0).coerceIn(-12, 12)
            a.tone = o.optDouble("tone", 0.5).toFloat().coerceIn(0f, 1f)
            a.wander = o.optDouble("wander", 0.5).toFloat().coerceIn(0f, 1f)
            a.space = o.optDouble("space", 0.55).toFloat().coerceIn(0f, 1f)
            a.volume = o.optDouble("volume", 0.7).toFloat().coerceIn(0f, 1f)
            a.mute = o.optBoolean("mute", false)
            a.free = o.optBoolean("free", false)
            a.key = o.optInt("key", 0).coerceIn(0, 11)
            val st = o.optJSONArray("steps")
            if (st != null) for (i in 0 until min(st.length(), Cfg.STEPS)) a.steps[i] = st.optBoolean(i, false)
        } catch (ex: Exception) {
        }
    }
}
