package com.example.fm1burger

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh
import org.json.JSONArray
import org.json.JSONObject

private const val TWO_PI = 2.0 * PI

/** Band-limited step correction, so saw and square waves do not alias (no harsh digital fizz). */
private fun polyBlep(t: Double, dt: Double): Double {
    if (t < dt) {
        val x = t / dt
        return x + x - x * x - 1.0
    }
    if (t > 1.0 - dt) {
        val x = (t - 1.0) / dt
        return x * x + x + x + 1.0
    }
    return 0.0
}

/** Two-pole resonant low-pass filter (state variable, stable at any setting). */
private class Svf {
    private var ic1 = 0.0
    private var ic2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var a3 = 0.0

    init {
        setup(2000.0, 0.0)
    }

    fun setup(fc: Double, res: Double) {
        val f = fc.coerceIn(20.0, Cfg.SR * 0.45)
        val g = tan(PI * f / Cfg.SR)
        val k = 2.0 - 1.95 * res.coerceIn(0.0, 1.0)
        a1 = 1.0 / (1.0 + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    fun process(x: Double): Double {
        val v3 = x - ic2
        val v1 = a1 * ic1 + a2 * v3
        val v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2.0 * v1 - ic1
        ic2 = 2.0 * v2 - ic2
        return v2
    }

    fun reset() {
        ic1 = 0.0
        ic2 = 0.0
    }
}

/**
 * Classic bassline: one note at a time, 16 steps, each step with a note, accent and slide.
 * Saw or square wave into a resonant low-pass filter that "plucks" on every note.
 */
class Bassline {
    val on = BooleanArray(Cfg.STEPS)
    val note = IntArray(Cfg.STEPS)
    val accent = BooleanArray(Cfg.STEPS)
    val slide = BooleanArray(Cfg.STEPS)

    @Volatile var saw = true
    @Volatile var cutoff = 0.35f
    @Volatile var res = 0.55f
    @Volatile var envMod = 0.6f
    @Volatile var decay = 0.35f
    @Volatile var accentAmt = 0.6f
    @Volatile var drive = 0.2f
    @Volatile var volume = 0.85f
    @Volatile var boostDb = 0f      // extra gain 0 .. +12 dB
    @Volatile var mute = false
    @Volatile var key = 0
    @Volatile var octave = 1
    @Volatile var previewReq = -1

    private var phase = 0.0
    private var freq = 55.0
    private var target = 55.0
    private var gateLeft = 0
    private var amp = 0.0
    private var fenv = 0.0
    private var fdec = 0.999
    private var acc = false
    private var sinceCoef = 0
    private val filt = Svf()

    fun rootMidi(): Int = 12 * (octave + 1) + key

    private fun freqOf(n: Int): Double = 440.0 * 2.0.pow((rootMidi() + n - 69) / 12.0)

    /** Called by the engine on every sequencer step. */
    fun step(s: Int, bpm: Double) {
        if (!on[s]) return
        val stepLen = Cfg.SR * 60.0 / (bpm * 4.0)
        val f = freqOf(note[s])
        val sliding = slide[s] && gateLeft > 0 && amp > 0.05
        target = f
        acc = accent[s]
        if (!sliding) {
            freq = f
            fenv = if (acc) 1.0 + accentAmt * 0.8 else 1.0
        }
        val next = (s + 1) % Cfg.STEPS
        val hold = on[next] && slide[next]
        gateLeft = (stepLen * (if (hold) 1.1 else 0.6)).toInt()
    }

    fun render(): Float {
        val p = previewReq
        if (p >= 0) {
            previewReq = -1
            val f = freqOf(p)
            freq = f
            target = f
            fenv = 1.0
            acc = false
            gateLeft = (Cfg.SR * 0.3).toInt()
        }
        if (gateLeft > 0) {
            gateLeft--
            amp += (1.0 - amp) * 0.03
        } else {
            amp -= amp * 0.004
        }
        if (gateLeft <= 0 && amp < 0.0002) {
            amp = 0.0
            return 0f
        }
        freq += (target - freq) * 0.0005
        if (sinceCoef <= 0) {
            sinceCoef = 32
            fdec = exp(-1.0 / ((0.06 + decay * 0.9) * Cfg.SR))
            val fc = 40.0 * 2.0.pow(cutoff * 7.5) * 2.0.pow(envMod * 4.0 * fenv)
            filt.setup(fc, res.toDouble())
        }
        sinceCoef--
        fenv *= fdec

        val dt = freq / Cfg.SR
        phase += dt
        if (phase >= 1.0) phase -= 1.0
        val o = if (saw) {
            2.0 * phase - 1.0 - polyBlep(phase, dt)
        } else {
            var q = if (phase < 0.5) 1.0 else -1.0
            q += polyBlep(phase, dt)
            q -= polyBlep((phase + 0.5) % 1.0, dt)
            q
        }
        var y = filt.process(o)
        y = tanh(y * (1.0 + drive * 4.0))
        val g = if (acc) 0.6 + accentAmt * 0.5 else 0.6
        if (mute) return 0f
        return (y * amp * g * volume * 1.2).toFloat()
    }

    fun clear() {
        for (i in 0 until Cfg.STEPS) {
            on[i] = false
            note[i] = 0
            accent[i] = false
            slide[i] = false
        }
    }
}

/**
 * Analog synth, built like a small 3-oscillator analog keyboard synth:
 *  - 3 VCOs and a VOICING mode: POLY (3 notes), UNISON (all 3 on one note, detuned), OCTAVE (+ an octave up),
 *    FIFTH (+ a fifth up), UNISON RING and POLY RING (ring modulation between the oscillators)
 *  - VCO octave, detune and portamento (glide)
 *  - one shared VCF (resonant low-pass with drive) and one shared EG for filter and amp (paraphonic, like the real ones)
 *  - LFO (saw / triangle / square), rate or tempo sync, to pitch and to cutoff
 *  - its own delay (time + feedback)
 *  - analog drift: every oscillator wanders a few cents, each note starts at a random phase
 * Played from the keyboard on its page and by its step sequencer.
 */
class AnalogSynth {
    // the original controls (kept: saved projects load as before)
    @Volatile var wave = 1              // 0 sine, 1 saw, 2 square, 3 triangle
    @Volatile var detune = 0.3f
    @Volatile var attack = 0.05f
    @Volatile var decay = 0.4f
    @Volatile var sustain = 0.6f
    @Volatile var release = 0.3f
    @Volatile var cutoff = 0.6f
    @Volatile var res = 0.2f            // the VCF "peak"
    @Volatile var filtEnv = 0.3f        // EG int to the cutoff
    @Volatile var volume = 0.85f
    @Volatile var mute = false
    val fx = FxChain()

    // the analog keyboard synth controls
    @Volatile var voicing = 0           // see VOICINGS
    @Volatile var octave = 0            // -2 .. +2
    @Volatile var portamento = 0f
    @Volatile var drive = 0.2f
    @Volatile var lfoWave = 1           // 0 saw, 1 triangle, 2 square
    @Volatile var lfoRate = 0.3f
    @Volatile var lfoSync = false       // rate follows the tempo
    @Volatile var lfoPitch = 0f
    @Volatile var lfoCutoff = 0f
    @Volatile var delayTime = 0.4f
    @Volatile var delayFb = 0f          // 0 = delay off
    @Volatile var bpm = 120.0
    @Volatile var boostDb = 0f      // extra gain 0 .. +12 dB

    companion object {
        val VOICINGS = arrayOf("Poly", "Unison", "Octave", "Fifth", "Uni Ring", "Poly Ring")
        val LFO_WAVES = arrayOf("Saw", "Tri", "Square")
        private val SYNC_BEATS = doubleArrayOf(4.0, 2.0, 1.0, 0.5, 0.25, 0.125)
    }

    private class Osc {
        var ph = 0.0
        var freq = 0.0          // where it is (glides toward target)
        var target = 0.0
        var drift = 0.0         // cents
        var driftTo = 0.0
        var note = -1
        var on = false
    }

    private val oscs = Array(3) { Osc() }
    private val held = ArrayList<Int>()
    private val events = ConcurrentLinkedQueue<Int>()
    private val f1 = Svf()
    private val f2 = Svf()
    private var eg = 0.0
    private var egStage = 0     // 0 idle, 1 attack, 2 decay / sustain, 3 release
    private var lfoPh = 0.0
    private var lfoVal = 0.0
    private var cc = 0
    private var rnd = 0x5EED1234
    private val dly = FloatArray(Cfg.SR)
    private var dw = 0
    private var dlp = 0f
    private var nextOsc = 0

    private fun rand(): Double {
        var x = rnd
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rnd = x
        return (x ushr 8) / 16777216.0
    }

    fun noteOn(n: Int) {
        events.add(n)
    }

    fun noteOff(n: Int) {
        events.add(n + 1000)
    }

    fun allOff() {
        events.add(-1)
    }

    private fun hz(n: Int): Double = 440.0 * 2.0.pow((n + 12 * octave - 69) / 12.0)

    private fun isUnison(): Boolean = voicing in intArrayOf(1, 2, 3, 4)

    /** Puts the held notes on the 3 oscillators the way the voicing wants. */
    private fun assign(newNote: Int) {
        val glide = portamento > 0.01f
        if (isUnison()) {
            // one note (the last one held) on all three oscillators
            val n = held.lastOrNull() ?: return
            val base = hz(n)
            val det = 2.0.pow(detune * 30.0 / 1200.0)
            val f = when (voicing) {
                2 -> doubleArrayOf(base, base * 2.0 * det, base / det)          // octave
                3 -> doubleArrayOf(base, base * 1.4983 * det, base / det)       // fifth
                else -> doubleArrayOf(base, base * det, base / det)             // unison (+ ring)
            }
            for (k in 0 until 3) {
                val o = oscs[k]
                o.target = f[k]
                if (!glide || o.freq <= 0.0) o.freq = f[k]
                if (!o.on) o.ph = rand()
                o.on = true
                o.note = n
            }
        } else {
            // poly: a note per oscillator (the oldest is taken when all three are busy)
            if (newNote < 0) return
            var o = oscs.firstOrNull { !it.on || it.note == newNote }
            if (o == null) {
                o = oscs[nextOsc % 3]
                nextOsc++
            }
            val f = hz(newNote) * (if (nextOsc % 2 == 0) 2.0.pow(detune * 8.0 / 1200.0) else 1.0)
            if (!glide || o.freq <= 0.0) o.freq = f
            o.target = f
            if (!o.on) o.ph = rand()
            o.on = true
            o.note = newNote
        }
    }

    private fun poll() {
        while (true) {
            val e = events.poll() ?: break
            if (e == -1) {
                held.clear()
                if (egStage != 0) egStage = 3
            } else if (e >= 1000) {
                val n = e - 1000
                held.remove(n)
                if (isUnison()) {
                    if (held.isEmpty()) egStage = 3 else assign(-1)
                } else {
                    for (o in oscs) if (o.note == n) o.on = false
                    if (held.isEmpty()) {
                        egStage = 3
                        // keep the oscillators sounding through the release
                        for (o in oscs) if (o.note >= 0 && o.freq > 0) o.on = true
                    }
                }
            } else {
                held.remove(e)
                held.add(e)
                val wasIdle = held.size == 1
                assign(e)
                // the EG starts again on a new note (from where it is, like the real thing)
                egStage = 1
                if (wasIdle && eg < 0.001) eg = 0.0
            }
        }
    }

    private fun osc(w: Int, ph: Double, dt: Double): Double = when (w) {
        0 -> sin(TWO_PI * ph)
        1 -> 2.0 * ph - 1.0 - polyBlep(ph, dt)
        2 -> {
            var q = if (ph < 0.5) 1.0 else -1.0
            q += polyBlep(ph, dt)
            q -= polyBlep((ph + 0.5) % 1.0, dt)
            q
        }
        else -> 4.0 * abs(ph - 0.5) - 1.0
    }

    fun render(): Float {
        if ((cc and 31) == 0) poll()
        val upd = (cc and 15) == 0
        cc++

        // the EG (shared by filter and amp)
        val atkT = 0.002 + attack.toDouble() * attack * 2.5
        val decT = 0.01 + decay.toDouble() * decay * 2.5
        val relT = 0.01 + release.toDouble() * release * 4.0
        val sus = sustain.toDouble()
        when (egStage) {
            1 -> {
                eg += (1.0 - eg) / (atkT * Cfg.SR) * 3.0 + 1.0 / (atkT * Cfg.SR) * 0.2
                if (eg >= 0.999) {
                    eg = 1.0
                    egStage = 2
                }
            }
            2 -> eg += (sus - eg) * (1.0 - exp(-1.0 / (decT * Cfg.SR)))
            3 -> {
                eg *= exp(-1.0 / (relT * Cfg.SR))
                if (eg < 0.0003) {
                    eg = 0.0
                    egStage = 0
                    for (o in oscs) o.on = false
                }
            }
        }

        // the LFO
        val lr = if (lfoSync) bpm / 60.0 / SYNC_BEATS[(lfoRate * 5.99f).toInt().coerceIn(0, 5)]
        else 0.05 * 2.0.pow(lfoRate * 9.2)                 // 0.05 .. ~30 Hz
        lfoPh += lr / Cfg.SR
        if (lfoPh >= 1.0) lfoPh -= 1.0
        lfoVal = when (lfoWave) {
            0 -> 1.0 - 2.0 * lfoPh
            2 -> if (lfoPh < 0.5) 1.0 else -1.0
            else -> 4.0 * abs(lfoPh - 0.5) - 1.0
        }

        // oscillators
        val glideC = if (portamento > 0.01f) 1.0 - exp(-1.0 / ((0.005 + portamento * portamento * 1.2) * Cfg.SR)) else 1.0
        val pitchMod = 2.0.pow(lfoVal * lfoPitch * lfoPitch * 1.0)   // up to ±1 octave
        val w = wave
        val ring = voicing == 4 || voicing == 5
        var sum = 0.0
        var ringOut = 1.0
        var active = 0
        for (k in 0 until 3) {
            val o = oscs[k]
            if (!o.on && egStage == 0) continue
            if (o.freq <= 0.0) continue
            o.freq += (o.target - o.freq) * glideC
            if (upd) {
                if (abs(o.drift - o.driftTo) < 0.05) o.driftTo = (rand() - 0.5) * 6.0
                o.drift += (o.driftTo - o.drift) * 0.002
            }
            val f = o.freq * pitchMod * 2.0.pow(o.drift / 1200.0)
            val dt = f / Cfg.SR
            o.ph += dt
            if (o.ph >= 1.0) o.ph -= 1.0
            val v = osc(w, o.ph, dt)
            if (o.on || egStage == 3) {
                sum += v
                ringOut *= v
                active++
            }
        }
        if (active == 0 && egStage == 0) return delayOnly()
        var x = if (ring && active >= 2) (sum / active) * 0.4 + ringOut * 0.8 else sum / 3.0 * (if (isUnison()) 1.0 else 1.4)

        // the VCF: drive into a 4-pole resonant low-pass, cutoff moved by the EG and the LFO
        if (upd) {
            val fc = 40.0 * 2.0.pow(cutoff * 9.0 + filtEnv * 5.0 * eg + lfoVal * lfoCutoff * 3.0)
            f1.setup(fc, res * 0.6)
            f2.setup(fc, res.toDouble())
        }
        val dr = 1.0 + drive * 6.0
        x = kotlin.math.tanh(x * dr) / kotlin.math.tanh(dr) * (1.0 + drive * 0.5)
        x = f2.process(f1.process(x))
        x *= eg
        var y = x.toFloat()

        // its own delay
        y = addDelay(y)
        if (mute) return 0f
        return y * volume * 1.4f
    }

    private fun addDelay(x: Float): Float {
        if (delayFb <= 0.01f) {
            dly[dw] = 0f
            dw = (dw + 1) % dly.size
            return x
        }
        val n = (Cfg.SR * (0.02f + delayTime * 0.95f)).toInt().coerceIn(1, dly.size - 1)
        var r = dw - n
        if (r < 0) r += dly.size
        val d = dly[r]
        dlp += (d - dlp) * 0.35f                       // the repeats get darker, like a tape / BBD delay
        dly[dw] = x + dlp * (delayFb * 0.9f)
        dw = (dw + 1) % dly.size
        return x + d * 0.6f
    }

    private fun delayOnly(): Float {
        if (mute) return 0f
        return addDelay(0f) * volume * 1.4f
    }
}

/** Saves and loads both synths (patterns and sound settings) in their own small file. */
object SynthSave {

    private fun arrB(a: BooleanArray): JSONArray {
        val j = JSONArray()
        for (x in a) j.put(x)
        return j
    }

    private fun arrI(a: IntArray): JSONArray {
        val j = JSONArray()
        for (x in a) j.put(x)
        return j
    }

    fun save(f: File, b: Bassline, s: AnalogSynth) {
        try {
            val o = JSONObject()
            val bo = JSONObject()
            bo.put("on", arrB(b.on))
            bo.put("note", arrI(b.note))
            bo.put("accent", arrB(b.accent))
            bo.put("slide", arrB(b.slide))
            bo.put("saw", b.saw)
            bo.put("cutoff", b.cutoff.toDouble())
            bo.put("res", b.res.toDouble())
            bo.put("envMod", b.envMod.toDouble())
            bo.put("decay", b.decay.toDouble())
            bo.put("accentAmt", b.accentAmt.toDouble())
            bo.put("drive", b.drive.toDouble())
            bo.put("volume", b.volume.toDouble())
            bo.put("mute", b.mute)
            bo.put("boostDb", b.boostDb.toDouble())
            bo.put("key", b.key)
            bo.put("octave", b.octave)
            o.put("bass", bo)
            val ao = JSONObject()
            ao.put("wave", s.wave)
            ao.put("detune", s.detune.toDouble())
            ao.put("attack", s.attack.toDouble())
            ao.put("decay", s.decay.toDouble())
            ao.put("sustain", s.sustain.toDouble())
            ao.put("release", s.release.toDouble())
            ao.put("cutoff", s.cutoff.toDouble())
            ao.put("res", s.res.toDouble())
            ao.put("filtEnv", s.filtEnv.toDouble())
            ao.put("volume", s.volume.toDouble())
            ao.put("mute", s.mute)
            ao.put("voicing", s.voicing)
            ao.put("boostDb", s.boostDb.toDouble())
            ao.put("octave", s.octave)
            ao.put("portamento", s.portamento.toDouble())
            ao.put("drive", s.drive.toDouble())
            ao.put("lfoWave", s.lfoWave)
            ao.put("lfoRate", s.lfoRate.toDouble())
            ao.put("lfoSync", s.lfoSync)
            ao.put("lfoPitch", s.lfoPitch.toDouble())
            ao.put("lfoCutoff", s.lfoCutoff.toDouble())
            ao.put("delayTime", s.delayTime.toDouble())
            ao.put("delayFb", s.delayFb.toDouble())
            val fo = JSONArray()
            val fa = JSONArray()
            val fb = JSONArray()
            for (i in 0 until FxChain.N) {
                fo.put(s.fx.on[i])
                fa.put(s.fx.a[i].toDouble())
                fb.put(s.fx.b[i].toDouble())
            }
            ao.put("fxOn", fo)
            ao.put("fxA", fa)
            ao.put("fxB", fb)
            o.put("analog", ao)
            SafeIo.writeText(f, o.toString())
        } catch (ex: Exception) {
        }
    }

    fun load(f: File, b: Bassline, s: AnalogSynth) {
        try {
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            val bo = o.optJSONObject("bass")
            if (bo != null) {
                val on = bo.optJSONArray("on")
                val nt = bo.optJSONArray("note")
                val ac = bo.optJSONArray("accent")
                val sl = bo.optJSONArray("slide")
                for (i in 0 until Cfg.STEPS) {
                    if (on != null && i < on.length()) b.on[i] = on.optBoolean(i, false)
                    if (nt != null && i < nt.length()) b.note[i] = nt.optInt(i, 0).coerceIn(0, 24)
                    if (ac != null && i < ac.length()) b.accent[i] = ac.optBoolean(i, false)
                    if (sl != null && i < sl.length()) b.slide[i] = sl.optBoolean(i, false)
                }
                b.saw = bo.optBoolean("saw", true)
                b.cutoff = bo.optDouble("cutoff", 0.35).toFloat()
                b.res = bo.optDouble("res", 0.55).toFloat()
                b.envMod = bo.optDouble("envMod", 0.6).toFloat()
                b.decay = bo.optDouble("decay", 0.35).toFloat()
                b.accentAmt = bo.optDouble("accentAmt", 0.6).toFloat()
                b.drive = bo.optDouble("drive", 0.2).toFloat()
                b.volume = bo.optDouble("volume", 0.7).toFloat()
                b.mute = bo.optBoolean("mute", false)
                b.boostDb = bo.optDouble("boostDb", 0.0).toFloat().coerceIn(0f, 12f)
                b.key = bo.optInt("key", 0).coerceIn(0, 11)
                b.octave = bo.optInt("octave", 1).coerceIn(1, 3)
            }
            val ao = o.optJSONObject("analog")
            if (ao != null) {
                s.wave = ao.optInt("wave", 1).coerceIn(0, 3)
                s.detune = ao.optDouble("detune", 0.3).toFloat()
                s.attack = ao.optDouble("attack", 0.05).toFloat()
                s.decay = ao.optDouble("decay", 0.4).toFloat()
                s.sustain = ao.optDouble("sustain", 0.6).toFloat()
                s.release = ao.optDouble("release", 0.3).toFloat()
                s.cutoff = ao.optDouble("cutoff", 0.6).toFloat()
                s.res = ao.optDouble("res", 0.2).toFloat()
                s.filtEnv = ao.optDouble("filtEnv", 0.3).toFloat()
                s.volume = ao.optDouble("volume", 0.6).toFloat()
                s.mute = ao.optBoolean("mute", false)
                s.voicing = ao.optInt("voicing", 0).coerceIn(0, AnalogSynth.VOICINGS.size - 1)
                s.boostDb = ao.optDouble("boostDb", 0.0).toFloat().coerceIn(0f, 12f)
                s.octave = ao.optInt("octave", 0).coerceIn(-2, 2)
                s.portamento = ao.optDouble("portamento", 0.0).toFloat().coerceIn(0f, 1f)
                s.drive = ao.optDouble("drive", 0.2).toFloat().coerceIn(0f, 1f)
                s.lfoWave = ao.optInt("lfoWave", 1).coerceIn(0, 2)
                s.lfoRate = ao.optDouble("lfoRate", 0.3).toFloat().coerceIn(0f, 1f)
                s.lfoSync = ao.optBoolean("lfoSync", false)
                s.lfoPitch = ao.optDouble("lfoPitch", 0.0).toFloat().coerceIn(0f, 1f)
                s.lfoCutoff = ao.optDouble("lfoCutoff", 0.0).toFloat().coerceIn(0f, 1f)
                s.delayTime = ao.optDouble("delayTime", 0.4).toFloat().coerceIn(0f, 1f)
                s.delayFb = ao.optDouble("delayFb", 0.0).toFloat().coerceIn(0f, 1f)
                val fo = ao.optJSONArray("fxOn")
                val fa = ao.optJSONArray("fxA")
                val fb = ao.optJSONArray("fxB")
                for (k in 0 until FxChain.N) {
                    if (fo != null && k < fo.length()) s.fx.on[k] = fo.optBoolean(k, false)
                    if (fa != null && k < fa.length()) s.fx.a[k] = fa.optDouble(k, FxChain.DEF_A[k].toDouble()).toFloat()
                    if (fb != null && k < fb.length()) s.fx.b[k] = fb.optDouble(k, FxChain.DEF_B[k].toDouble()).toFloat()
                }
            }
        } catch (ex: Exception) {
        }
    }
}
