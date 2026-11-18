package com.example.fm1burger

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/** An RBJ biquad filter (one channel). */
class GtrFilter(kind: Int, hz: Float, q: Float = 0.707f, db: Float = 0f) {
    companion object {
        const val LP = 0
        const val HP = 1
        const val PEAK = 2
    }

    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    init {
        set(kind, hz, q, db)
    }

    fun set(kind: Int, hz: Float, q: Float = 0.707f, db: Float = 0f) {
        val w0 = 2.0 * PI * hz / Cfg.SR
        val al = sin(w0) / (2.0 * q)
        val cw = cos(w0)
        val a = 10.0.pow(db / 40.0)
        val bb: DoubleArray
        val aa: DoubleArray
        when (kind) {
            LP -> {
                bb = doubleArrayOf((1 - cw) / 2, 1 - cw, (1 - cw) / 2)
                aa = doubleArrayOf(1 + al, -2 * cw, 1 - al)
            }
            HP -> {
                bb = doubleArrayOf((1 + cw) / 2, -(1 + cw), (1 + cw) / 2)
                aa = doubleArrayOf(1 + al, -2 * cw, 1 - al)
            }
            else -> {
                bb = doubleArrayOf(1 + al * a, -2 * cw, 1 - al * a)
                aa = doubleArrayOf(1 + al / a, -2 * cw, 1 - al / a)
            }
        }
        b0 = (bb[0] / aa[0]).toFloat()
        b1 = (bb[1] / aa[0]).toFloat()
        b2 = (bb[2] / aa[0]).toFloat()
        a1 = (aa[1] / aa[0]).toFloat()
        a2 = (aa[2] / aa[0]).toFloat()
    }

    fun run(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = if (abs(y) < 1e-20f) 0f else y
        return y
    }
}

/** One plucked string (Karplus-Strong): a delay line one period long, softly filtered every pass. */
class GuitarString {
    private val buf = FloatArray(4096)
    private var n = 100
    private var pos = 0
    private var decay = 0.999f
    private var damp = 0.5f
    private var wait = 0
    var on = false
    private var rnd = 0x1234567

    private fun noise(): Float {
        var x = rnd
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rnd = x
        return (x ushr 8) / 8388608f - 1f
    }

    /** The loop runs once per period: fade per pass so the level drops 60 dB in t60 seconds. */
    private fun setT60(t60: Float) {
        decay = 10.0.pow(-3.0 * (n.toDouble() / Cfg.SR) / t60).toFloat()
    }

    fun pluck(hz: Float, bright: Float, mute: Boolean, muteT60: Float, delaySamples: Int) {
        n = (Cfg.SR / hz).roundToInt().coerceIn(2, buf.size - 1)
        pos = 0
        wait = delaySamples
        // the pick: noise, darker for a palm mute
        val lpc = 0.25f + 0.7f * bright * (if (mute) 0.45f else 1f)
        var z = 0f
        val lvl = if (mute) 0.8f else 1f
        for (i in 0 until n) {
            z += (noise() - z) * lpc
            buf[i] = z * lvl
        }
        setT60(if (mute) muteT60 else 3f)
        damp = if (mute) 0.62f else 0.5f
        on = true
    }

    /** Lifts the fretting hand: the string dies in a few hundredths of a second. */
    fun stop() {
        if (!on) return
        setT60(0.025f)
        damp = 0.7f
    }

    fun tick(): Float {
        if (!on) return 0f
        if (wait > 0) {
            wait--
            return 0f
        }
        val i = pos
        val j = if (i + 1 >= n) 0 else i + 1
        val cur = buf[i]
        buf[i] = decay * ((1f - damp) * cur + damp * buf[j])
        pos = j
        return cur
    }
}

/**
 * A downtuned metal guitar through a high gain amp and a speaker cabinet.
 *  RHYTHM: power chords (root, fifth, octave), palm-muted chugs and open hits.
 *  RIFF: single notes with an octave above, a little less gain and more mids.
 * The 16 steps hold an articulation (off / chug / open / hold) and a fret on the low string.
 * Audio thread: render() once per sample, step() from the sequencer.
 */
class MetalGuitar(val riff: Boolean) {

    companion object {
        const val OFF = 0       // stop the strings (tight silence)
        const val CHUG = 1      // palm mute
        const val OPEN = 2      // let it ring
        const val HOLD = 3      // keep what is ringing
        val ART_NAMES = arrayOf("·", "M", "O", "—")

        val TUNINGS = arrayOf("Drop A", "Drop B", "Drop C", "Drop D", "Drop E")
        private val TUNING_HZ = floatArrayOf(55.0f, 61.735f, 65.406f, 73.416f, 82.407f)

        val RHYTHM_PRESETS = arrayOf("Nu chug", "Gallop", "Techno 16ths", "Breakdown", "Psy roll")
        val RIFF_PRESETS = arrayOf("Nu riff", "Octave bounce", "Psy lead", "Chromatic")
        const val WF = 240
    }

    @Volatile var gain = if (riff) 0.65f else 0.75f
    @Volatile var tone = if (riff) 0.7f else 0.55f
    @Volatile var tight = 0.5f             // palm mute length: 0 long .. 1 very short
    @Volatile var volume = if (riff) 0.6f else 0.75f
    @Volatile var mute = false
    @Volatile var tuning = 0
    @Volatile var octave = 0               // riff: 0 or +1 octave
    val art = IntArray(Cfg.STEPS)
    val fret = IntArray(Cfg.STEPS)

    var out = 0f
    val wfMin = FloatArray(WF)
    val wfMax = FloatArray(WF)
    @Volatile var wfPos = 0
    private var wMin = 0f
    private var wMax = 0f
    private var wCount = 0

    private val strings = Array(3) { GuitarString() }
    private val preHp = GtrFilter(GtrFilter.HP, if (riff) 90f else 110f)
    private val preMid = GtrFilter(GtrFilter.PEAK, 900f, 0.8f, 7f)
    private val cab = arrayOf(
        GtrFilter(GtrFilter.HP, 75f),
        GtrFilter(GtrFilter.PEAK, 115f, 1f, 3.5f),
        GtrFilter(GtrFilter.PEAK, 650f, 0.9f, if (riff) -3f else -6f),   // the nu-metal mid scoop
        GtrFilter(GtrFilter.PEAK, 2900f, 1f, 3.5f),
        GtrFilter(GtrFilter.LP, 5200f, 0.7f),
        GtrFilter(GtrFilter.LP, 5200f, 0.7f)
    )
    private var env = 0f
    private var dc = 0f
    private var lastTone = -1f

    init {
        loadPreset(0)
    }

    fun rootHz(): Float = TUNING_HZ[tuning.coerceIn(0, TUNING_HZ.size - 1)]

    fun loadPreset(k: Int) {
        val p: Array<IntArray> = if (riff) {
            when (k) {
                // fret, art pairs for 16 steps
                1 -> arrayOf(intArrayOf(0, 2), intArrayOf(12, 2), intArrayOf(0, 2), intArrayOf(12, 2), intArrayOf(3, 2), intArrayOf(15, 2), intArrayOf(3, 2), intArrayOf(15, 2),
                    intArrayOf(5, 2), intArrayOf(17, 2), intArrayOf(5, 2), intArrayOf(17, 2), intArrayOf(6, 2), intArrayOf(18, 2), intArrayOf(5, 2), intArrayOf(3, 2))
                2 -> arrayOf(intArrayOf(12, 2), intArrayOf(12, 1), intArrayOf(15, 2), intArrayOf(12, 1), intArrayOf(17, 2), intArrayOf(12, 1), intArrayOf(15, 2), intArrayOf(12, 1),
                    intArrayOf(19, 2), intArrayOf(12, 1), intArrayOf(17, 2), intArrayOf(12, 1), intArrayOf(15, 2), intArrayOf(13, 2), intArrayOf(12, 2), intArrayOf(10, 2))
                3 -> arrayOf(intArrayOf(0, 2), intArrayOf(1, 2), intArrayOf(2, 2), intArrayOf(3, 2), intArrayOf(0, 1), intArrayOf(0, 1), intArrayOf(6, 2), intArrayOf(5, 2),
                    intArrayOf(4, 2), intArrayOf(3, 2), intArrayOf(0, 1), intArrayOf(0, 1), intArrayOf(7, 2), intArrayOf(6, 2), intArrayOf(5, 2), intArrayOf(0, 0))
                else -> arrayOf(intArrayOf(12, 2), intArrayOf(0, 3), intArrayOf(10, 2), intArrayOf(12, 2), intArrayOf(0, 0), intArrayOf(15, 2), intArrayOf(13, 2), intArrayOf(0, 3),
                    intArrayOf(12, 2), intArrayOf(0, 3), intArrayOf(10, 2), intArrayOf(8, 2), intArrayOf(0, 0), intArrayOf(7, 2), intArrayOf(8, 2), intArrayOf(0, 3))
            }
        } else {
            when (k) {
                1 -> arrayOf(intArrayOf(0, 1), intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(0, 1), intArrayOf(0, 1), intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(0, 1),
                    intArrayOf(0, 1), intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(0, 1), intArrayOf(3, 2), intArrayOf(0, 3), intArrayOf(5, 2), intArrayOf(0, 3))
                2 -> Array(16) { intArrayOf(if (it == 14) 3 else if (it == 15) 5 else 0, if (it % 4 == 0) 2 else 1) }
                3 -> arrayOf(intArrayOf(0, 2), intArrayOf(0, 3), intArrayOf(0, 3), intArrayOf(0, 0), intArrayOf(0, 0), intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(0, 1),
                    intArrayOf(1, 2), intArrayOf(0, 3), intArrayOf(0, 3), intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(0, 0))
                4 -> Array(16) { intArrayOf(if (it >= 12) 3 else 0, if (it % 4 == 0) 0 else 1) }
                else -> arrayOf(intArrayOf(0, 1), intArrayOf(0, 1), intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(0, 1), intArrayOf(0, 0), intArrayOf(3, 2), intArrayOf(0, 3),
                    intArrayOf(0, 1), intArrayOf(0, 1), intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(5, 2), intArrayOf(0, 3), intArrayOf(6, 2), intArrayOf(5, 2))
            }
        }
        for (s in 0 until Cfg.STEPS) {
            fret[s] = p[s][0]
            art[s] = p[s][1]
        }
    }

    /** Sequencer step (audio thread). */
    fun step(s: Int) {
        if (s !in 0 until Cfg.STEPS) return
        hit(art[s], fret[s])
    }

    @Volatile private var reqArt = -1
    @Volatile private var reqFret = 0

    /** From the screen: play an articulation on the audio thread at the next sample. */
    fun audition(a: Int, f: Int) {
        reqFret = f
        reqArt = a
    }

    /** Plays an articulation now (audio thread). */
    fun hit(a: Int, f: Int) {
        when (a) {
            HOLD -> return
            OFF -> {
                for (st in strings) st.stop()
                return
            }
        }
        val muted = a == CHUG
        val hz = rootHz() * 2f.pow((f + 12 * (if (riff) octave else 0)) / 12f)
        val muteT60 = 0.32f - 0.26f * tight
        val ratios = if (riff) floatArrayOf(1f, 2f) else floatArrayOf(1f, 1.4983f, 2f)
        for (k in strings.indices) {
            if (k < ratios.size) {
                val strum = if (muted) (k * 0.0008f * Cfg.SR).toInt() else (k * 0.0025f * Cfg.SR).toInt()
                strings[k].pluck(hz * ratios[k], tone, muted, muteT60, strum)
            } else {
                strings[k].on = false
            }
        }
    }

    fun render(): Float {
        val ra = reqArt
        if (ra >= 0) {
            reqArt = -1
            hit(ra, reqFret)
        }
        if (tone != lastTone) {
            lastTone = tone
            // brighter tone opens the cabinet a little
            val lp = 3600f + 3400f * tone
            cab[4].set(GtrFilter.LP, lp, 0.7f)
            cab[5].set(GtrFilter.LP, lp, 0.7f)
        }
        var x = 0f
        for (k in strings.indices) x += strings[k].tick() * (if (riff && k == 1) 0.6f else 1f)
        x = preMid.run(preHp.run(x))
        // noise gate before the gain: tight silences between chugs
        val ax = abs(x)
        env += (ax - env) * (if (ax > env) 0.02f else 0.0015f)
        val gate = ((env - 0.004f) / 0.012f).coerceIn(0f, 1f)
        x *= gate
        val g = 4f + 60f * gain * gain
        var y = tanh(x * g + 0.15f) - 0.148885f   // asymmetric first stage (tanh(0.15))
        y = tanh(y * 2.2f)                         // second stage
        dc += (y - dc) * 0.001f
        y -= dc
        for (b in cab) y = b.run(y)
        out = if (mute) 0f else y * 0.5f * volume

        if (out < wMin) wMin = out
        if (out > wMax) wMax = out
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
        return out
    }

    fun ringing(): Boolean = strings.any { it.on } && max(abs(wfMin[(wfPos - 1 + WF) % WF]), abs(wfMax[(wfPos - 1 + WF) % WF])) > 0.002f
}

/** The drum bus "HEAVY": low-end weight, a fast compressor and saturation on all the drums together. */
class DrumHeavy {
    @Volatile var amount = 0.5f
    private val low = GtrFilter(GtrFilter.PEAK, 70f, 0.8f, 0f)
    private val snap = GtrFilter(GtrFilter.PEAK, 4200f, 0.9f, 0f)
    private var env = 0f
    private var lastAmount = -1f

    fun process(x: Float): Float {
        val a = amount
        if (a <= 0.001f) return x
        if (a != lastAmount) {
            lastAmount = a
            low.set(GtrFilter.PEAK, 70f, 0.8f, 7f * a)
            snap.set(GtrFilter.PEAK, 4200f, 0.9f, 4f * a)
        }
        var y = snap.run(low.run(x))
        // compressor: threshold falls and ratio rises with the amount, then make-up gain
        val ay = abs(y)
        env += (ay - env) * (if (ay > env) 0.25f else 0.0006f)
        val thr = 0.5f - 0.38f * a
        val ratio = 1f + 5f * a
        val gr = if (env > thr) (thr + (env - thr) / ratio) / env else 1f
        y *= gr * (1f + 1.6f * a)
        // saturation for density
        val drive = 1f + 2.5f * a
        y = tanh(y * drive) / tanh(drive) * (0.9f + 0.4f * a)
        return y
    }
}

/** Saves and loads both guitars and the drum bus amount. */
object GuitarSave {
    private fun put(o: JSONObject, g: MetalGuitar) {
        o.put("gain", g.gain.toDouble())
        o.put("tone", g.tone.toDouble())
        o.put("tight", g.tight.toDouble())
        o.put("volume", g.volume.toDouble())
        o.put("mute", g.mute)
        o.put("tuning", g.tuning)
        o.put("octave", g.octave)
        val a = JSONArray()
        val f = JSONArray()
        for (s in 0 until Cfg.STEPS) {
            a.put(g.art[s])
            f.put(g.fret[s])
        }
        o.put("art", a)
        o.put("fret", f)
    }

    private fun get(o: JSONObject?, g: MetalGuitar) {
        if (o == null) return
        g.gain = o.optDouble("gain", g.gain.toDouble()).toFloat().coerceIn(0f, 1f)
        g.tone = o.optDouble("tone", g.tone.toDouble()).toFloat().coerceIn(0f, 1f)
        g.tight = o.optDouble("tight", g.tight.toDouble()).toFloat().coerceIn(0f, 1f)
        g.volume = o.optDouble("volume", g.volume.toDouble()).toFloat().coerceIn(0f, 1f)
        g.mute = o.optBoolean("mute", false)
        g.tuning = o.optInt("tuning", 0).coerceIn(0, MetalGuitar.TUNINGS.size - 1)
        g.octave = o.optInt("octave", 0).coerceIn(0, 1)
        val a = o.optJSONArray("art")
        val f = o.optJSONArray("fret")
        for (s in 0 until Cfg.STEPS) {
            if (a != null && s < a.length()) g.art[s] = a.optInt(s, 0).coerceIn(0, 3)
            if (f != null && s < f.length()) g.fret[s] = f.optInt(s, 0).coerceIn(0, 24)
        }
    }

    fun save(file: File, rhythm: MetalGuitar, riff: MetalGuitar, heavy: DrumHeavy) {
        try {
            val o = JSONObject()
            o.put("rhythm", JSONObject().also { put(it, rhythm) })
            o.put("riff", JSONObject().also { put(it, riff) })
            o.put("drumHeavy", heavy.amount.toDouble())
            SafeIo.writeText(file, o.toString())
        } catch (ex: Exception) {
        }
    }

    fun load(file: File, rhythm: MetalGuitar, riff: MetalGuitar, heavy: DrumHeavy) {
        try {
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            get(o.optJSONObject("rhythm"), rhythm)
            get(o.optJSONObject("riff"), riff)
            heavy.amount = o.optDouble("drumHeavy", 0.5).toFloat().coerceIn(0f, 1f)
        } catch (ex: Exception) {
        }
    }
}

/** The "Rock Heavy" drum kit: 12 WAV files made by tools/make_rock_kit.py, shipped inside the app. */
object RockKit {
    /** The 8 pad sounds in row order, then the extras. */
    val pads = listOf(
        "Rock Kick", "Rock Snare", "Rock Hat Closed", "Rock Hat Open",
        "Rock Tom High", "Rock Tom Floor", "Rock Crash", "Rock China"
    )
    val extras = listOf("Rock Kick Tight", "Rock Snare Crack", "Rock Tom Mid", "Rock Ride")

    fun fileName(n: String) = "$n.wav"

    /** Copies the kit from the app into the sample library (only the files that are missing). Background thread. */
    fun install(ctx: android.content.Context, store: Store): Int {
        var n = 0
        for (name in pads + extras) {
            val target = store.file(fileName(name))
            if (target.exists()) continue
            ctx.assets.open("kits/rock/" + fileName(name)).use { input ->
                SafeIo.writeBytes(target, input.readBytes())
            }
            n++
        }
        return n
    }
}
