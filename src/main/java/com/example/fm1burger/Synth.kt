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
    @Volatile var volume = 0.7f
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
 * Analog style synth: 6 voices, each with two slightly detuned oscillators
 * (sine / saw / square / triangle), a resonant low-pass filter and an ADSR envelope.
 * Played from the on-screen keyboard.
 */
class AnalogSynth {
    @Volatile var wave = 1
    @Volatile var detune = 0.3f
    @Volatile var attack = 0.05f
    @Volatile var decay = 0.4f
    @Volatile var sustain = 0.6f
    @Volatile var release = 0.3f
    @Volatile var cutoff = 0.6f
    @Volatile var res = 0.2f
    @Volatile var filtEnv = 0.3f
    @Volatile var volume = 0.6f
    @Volatile var mute = false
    val fx = FxChain()

    private class V {
        var note = -1
        var freq = 0.0
        var ph1 = 0.0
        var ph2 = 0.0
        var stage = 0   // 0 idle, 1 attack, 2 decay, 3 sustain, 4 release
        var level = 0.0
        var age = 0L
        val f = Svf()
    }

    private val voices = Array(6) { V() }
    private val events = ConcurrentLinkedQueue<Int>()
    private var counter = 0L
    private var cc = 0

    fun noteOn(n: Int) {
        events.add(n)
    }

    fun noteOff(n: Int) {
        events.add(n + 1000)
    }

    fun allOff() {
        events.add(-1)
    }

    private fun poll() {
        while (true) {
            val e = events.poll() ?: break
            if (e == -1) {
                for (v in voices) if (v.stage != 0) v.stage = 4
            } else if (e >= 1000) {
                val n = e - 1000
                for (v in voices) if (v.note == n && v.stage in 1..3) v.stage = 4
            } else {
                var pick: V? = null
                for (v in voices) if (v.stage == 0) {
                    pick = v
                    break
                }
                if (pick == null) {
                    var oldest = voices[0]
                    for (v in voices) if (v.age < oldest.age) oldest = v
                    pick = oldest
                }
                pick.note = e
                pick.freq = 440.0 * 2.0.pow((e - 69) / 12.0)
                pick.stage = 1
                counter++
                pick.age = counter
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
        if ((cc and 63) == 0) poll()
        val upd = (cc and 31) == 0
        cc++
        val w = wave
        val sus = sustain.toDouble()
        val atkT = 0.002 + attack.toDouble() * attack * 2.0
        val decT = 0.01 + decay.toDouble() * decay * 2.0
        val relT = 0.01 + release.toDouble() * release * 3.0
        val atkStep = 1.0 / (atkT * Cfg.SR)
        val decC = 1.0 - exp(-1.0 / (decT * Cfg.SR))
        val relC = exp(-1.0 / (relT * Cfg.SR))
        val ratio = 2.0.pow(detune * 25.0 / 1200.0)
        var sum = 0.0
        for (v in voices) {
            if (v.stage == 0) continue
            when (v.stage) {
                1 -> {
                    v.level += atkStep
                    if (v.level >= 1.0) {
                        v.level = 1.0
                        v.stage = 2
                    }
                }
                2 -> {
                    v.level += (sus - v.level) * decC
                    if (abs(v.level - sus) < 0.001) v.stage = 3
                }
                3 -> v.level = sus
                else -> {
                    v.level *= relC
                    if (v.level < 0.0005) {
                        v.level = 0.0
                        v.stage = 0
                        v.f.reset()
                        continue
                    }
                }
            }
            if (upd) {
                val fc = 60.0 * 2.0.pow(cutoff * 7.5) * 2.0.pow(filtEnv * 3.0 * v.level)
                v.f.setup(fc, res.toDouble())
            }
            val dt1 = v.freq * ratio / Cfg.SR
            val dt2 = v.freq / ratio / Cfg.SR
            v.ph1 += dt1
            if (v.ph1 >= 1.0) v.ph1 -= 1.0
            v.ph2 += dt2
            if (v.ph2 >= 1.0) v.ph2 -= 1.0
            val o = (osc(w, v.ph1, dt1) + osc(w, v.ph2, dt2)) * 0.5
            sum += v.f.process(o) * v.level * 0.3
        }
        if (mute) return 0f
        return (sum * volume * 1.5).toFloat()
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
