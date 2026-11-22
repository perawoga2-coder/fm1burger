package com.example.fm1burger

import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * EDIT DRUM (3.24): one drum sound edited from the drum sequencer's "e".
 *
 * The kit's own sounds (DrumKit, made in code) are made again from their recipe with the knobs: Kick (TUNE, PUNCH,
 * BOOM, SOFT, DRIVE, CLICK), the hi-hats (SIZE, DECAY, TONE, RESONANCE), Snare (TUNE, SNAP, BODY, DECAY), the toms
 * (TUNE, BODY, DECAY). Every other sound (the rest of the kit, Rock kit, recorded, imported) is edited as audio:
 * TUNE, DECAY, BODY, TONE, RESONANCE, DRIVE, ATTACK.
 *
 * The original file is never changed: an edit is saved as its own sound ("Kick edit.wav") with a note of where it
 * came from and the knobs (files/drumedit/<name>.json), so opening it again starts from the same knobs.
 */
object DrumEdit {
    private const val SR = Cfg.SR
    private val TAU = (2.0 * PI).toFloat()

    enum class Kind { KICK, HAT, SNARE, TOM, AUDIO }

    class Knob(val id: String, val label: String, val min: Int, val max: Int, val def: Int, val fmt: (Int) -> String)

    private fun pct(v: Int) = "$v %"
    private fun semi(v: Int) = if (v == 0) "0" else (if (v > 0) "+$v" else "$v") + " st"
    private fun db(v: Int) = if (v == 0) "0 dB" else (if (v > 0) "+$v" else "$v") + " dB"

    /** "Drum_Kick.wav" -> "Kick" when it is one of the kit's sounds made in code, else null */
    fun kitName(file: String): String? {
        if (!file.startsWith("Drum_") || !file.endsWith(".wav")) return null
        val n = file.removePrefix("Drum_").removeSuffix(".wav").replace('_', ' ')
        return if (n in DrumKit.names) n else null
    }

    fun kindOf(src: String): Kind = when (kitName(src)) {
        "Kick", "Kick Soft" -> Kind.KICK
        "Hat Closed", "Hat Open", "Hat Pedal" -> Kind.HAT
        "Snare" -> Kind.SNARE
        "Tom Low", "Tom Mid", "Tom High" -> Kind.TOM
        else -> Kind.AUDIO
    }

    fun knobs(src: String): List<Knob> {
        val soft = kitName(src) == "Kick Soft"
        return when (kindOf(src)) {
            Kind.KICK -> listOf(
                Knob("tune", "TUNE", -12, 12, 0, ::semi),
                Knob("punch", "PUNCH (the hit)", 0, 200, 100, ::pct),
                Knob("boom", "BOOM (the tail)", 25, 250, 100, ::pct),
                Knob("soft", "SOFT (round, warm)", 0, 100, 0, ::pct),
                Knob("drive", "DRIVE (thick)", 0, 100, 19, ::pct),
                Knob("click", "CLICK (the beater)", 0, 200, if (soft) 0 else 100, ::pct))
            Kind.HAT -> listOf(
                Knob("size", "SIZE (small - big)", -100, 100, 0) { if (it == 0) "0" else if (it > 0) "bigger $it" else "smaller ${-it}" },
                Knob("decay", "DECAY", 25, 400, 100, ::pct),
                Knob("tone", "TONE (dark - bright)", 0, 100, 39, ::pct),
                Knob("res", "RESONANCE (the ring)", 0, 100, 0, ::pct))
            Kind.SNARE -> listOf(
                Knob("tune", "TUNE", -12, 12, 0, ::semi),
                Knob("snap", "SNAP (the wires)", 0, 200, 100, ::pct),
                Knob("body", "BODY", 0, 200, 100, ::pct),
                Knob("decay", "DECAY", 25, 300, 100, ::pct))
            Kind.TOM -> listOf(
                Knob("tune", "TUNE", -12, 12, 0, ::semi),
                Knob("body", "BODY", 0, 200, 100, ::pct),
                Knob("decay", "DECAY", 25, 300, 100, ::pct))
            Kind.AUDIO -> listOf(
                Knob("tune", "TUNE", -12, 12, 0, ::semi),
                Knob("decay", "DECAY (length)", 10, 100, 100, ::pct),
                Knob("body", "BODY (low, thick)", -12, 12, 0, ::db),
                Knob("tone", "TONE (dark - open)", 0, 100, 100) { if (it >= 100) "open" else "$it %" },
                Knob("res", "RESONANCE", 0, 100, 0, ::pct),
                Knob("drive", "DRIVE", 0, 100, 0, ::pct),
                Knob("attack", "ATTACK (the hit)", 0, 100, 0, ::pct))
        }
    }

    fun defaults(src: String): MutableMap<String, Int> = knobs(src).associate { it.id to it.def }.toMutableMap()

    /** the sound with these knobs; [audio]: the source's samples (for Kind.AUDIO) */
    fun render(src: String, k: Map<String, Int>, audio: FloatArray?): FloatArray? {
        val n = kitName(src)
        fun v(id: String) = (k[id] ?: 0).toFloat()
        return when (kindOf(src)) {
            Kind.KICK -> finish(kick(n == "Kick Soft", v("tune"), v("punch") / 100f, v("boom") / 100f, v("soft") / 100f,
                0.8f + v("drive") / 100f * 4.2f, v("click") / 100f))
            Kind.HAT -> {
                val (secs, tau, seed) = when (n) {
                    "Hat Open" -> Triple(0.9f, 0.22f, 12)
                    "Hat Pedal" -> Triple(0.25f, 0.045f, 13)
                    else -> Triple(0.15f, 0.02f, 11)
                }
                finish(hat(secs, tau, seed, v("size") / 100f, v("decay") / 100f, v("tone") / 100f, v("res") / 100f))
            }
            Kind.SNARE -> finish(snare(v("tune"), v("snap") / 100f, v("body") / 100f, v("decay") / 100f))
            Kind.TOM -> {
                val f0 = when (n) { "Tom Low" -> 85f; "Tom Mid" -> 125f; else -> 175f }
                finish(tom(f0, v("tune"), v("body") / 100f, v("decay") / 100f))
            }
            Kind.AUDIO -> audio?.let { audioEdit(it, k) }
        }
    }

    // ---------------- the recipes (DrumKit's, with knobs; at the defaults they make the kit's sounds) ----------------

    private class Noise(seed: Int) {
        private var s = seed
        fun next(): Float { s = s * 1664525 + 1013904223; return ((s ushr 8) and 0xFFFF) / 32768f - 1f }
    }

    private fun hp(x: FloatArray, hz: Float): FloatArray {
        val rc = 1f / (TAU * hz); val dt = 1f / SR; val a = rc / (rc + dt)
        val y = FloatArray(x.size)
        for (i in 1 until x.size) y[i] = a * (y[i - 1] + x[i] - x[i - 1])
        return y
    }

    private fun lp(x: FloatArray, hz: Float): FloatArray {
        val rc = 1f / (TAU * hz); val dt = 1f / SR; val al = dt / (rc + dt)
        val y = FloatArray(x.size)
        for (i in 1 until x.size) y[i] = y[i - 1] + al * (x[i] - y[i - 1])
        return y
    }

    private fun st(semis: Float) = 2f.pow(semis / 12f)

    private fun kick(soft: Boolean, tune: Float, punch: Float, boom: Float, softK: Float, drive: Float, click: Float): FloatArray {
        val tau = (if (soft) 0.28f else 0.2f) * boom
        val n = (SR * maxOf(if (soft) 0.6f else 0.5f, tau * 2.6f)).toInt()
        val y = FloatArray(n)
        val nz = Noise(1)
        val p = st(tune)
        val f0 = (if (soft) 38f else 48f) * p
        val fs = (if (soft) 70f else 130f) * p * punch
        val att = softK * 0.008f                          // SOFT: a rounder start
        var ph = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            val f = f0 + fs * exp(-t / 0.03f)
            ph += TAU * f / SR
            var v = sin(ph) * exp(-t / tau)
            if (att > 0f && t < att) v *= t / att
            v += nz.next() * 0.25f * click * exp(-t / 0.004f)
            y[i] = tanh(v * drive)
        }
        return if (softK > 0.01f) lp(lp(y, 9000f * (1f - softK) + 220f), 9000f * (1f - softK) + 220f) else y
    }

    private val hatF = floatArrayOf(205.3f, 304.4f, 369.6f, 522.7f, 540f, 800f)

    private fun hat(secs: Float, tau: Float, seed: Int, size: Float, decay: Float, tone: Float, res: Float): FloatArray {
        val n = (SR * secs * maxOf(1f, decay)).toInt()
        val sc = 2f.pow(-size)                             // bigger: lower
        val cut = 3000f + tone * 9000f
        val raw = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            var s = 0f
            for (f in hatF) s += if ((t * f * sc) % 1f < 0.5f) 1f else -1f
            raw[i] = s / hatF.size
        }
        val m = hp(raw, cut)
        val g = Noise(seed)
        val nz = hp(FloatArray(n) { g.next() }, cut + 500f)
        val y = FloatArray(n)
        val tt = tau * decay
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            y[i] = (m[i] * 0.7f + nz[i] * 0.5f) * exp(-t / tt)
        }
        if (res > 0.01f) {                                 // RESONANCE: a ringing band around the hat's pitch
            val bp = biquadBand(y, (7500f * sc).coerceIn(2000f, 16000f), 1f + res * 14f)
            for (i in 0 until n) y[i] += bp[i] * res * 2.5f
        }
        return y
    }

    private fun snare(tune: Float, snap: Float, body: Float, decay: Float): FloatArray {
        val n = (SR * 0.35f * maxOf(1f, decay)).toInt()
        val g = Noise(2)
        val hn = hp(FloatArray(n) { g.next() }, 1500f)
        val y = FloatArray(n)
        val p = st(tune)
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            val tone = sin(TAU * 180f * p * t) * 0.6f + sin(TAU * 330f * p * t) * 0.3f
            y[i] = tone * body * exp(-t / (0.05f * decay)) + hn[i] * 0.8f * snap * exp(-t / (0.09f * decay))
        }
        return y
    }

    private fun tom(base: Float, tune: Float, body: Float, decay: Float): FloatArray {
        val n = (SR * 0.55f * maxOf(1f, decay)).toInt()
        val nz = Noise(5)
        val y = FloatArray(n)
        val f0 = base * st(tune)
        var ph = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            val f = f0 * (1f + 0.5f * exp(-t / 0.04f))
            ph += TAU * f / SR
            val v = sin(ph) * body * exp(-t / (0.22f * decay)) + nz.next() * 0.15f * exp(-t / 0.003f)
            y[i] = tanh(v * 1.3f)
        }
        return y
    }

    /** as DrumKit: to 0.9 peak, the end faded */
    private fun finish(y: FloatArray): FloatArray {
        var pk = 0f
        for (v in y) if (abs(v) > pk) pk = abs(v)
        if (pk > 0.0001f) { val g = 0.9f / pk; for (i in y.indices) y[i] *= g }
        val n = minOf(220, y.size)
        for (i in 0 until n) y[y.size - 1 - i] *= i.toFloat() / n
        return y
    }

    // ---------------- any sound, as audio ----------------

    private fun audioEdit(src: FloatArray, k: Map<String, Int>): FloatArray {
        var x = src
        val tune = (k["tune"] ?: 0).toFloat()
        if (tune != 0f) {                                  // TUNE: played faster / slower (as a sampler does)
            val r = st(tune)
            val n = maxOf(1, (x.size / r).toInt())
            val o = FloatArray(n)
            for (i in 0 until n) {
                val p = i * r; val j = p.toInt(); val f = p - j
                val a = x[minOf(j, x.size - 1)]; val b = x[minOf(j + 1, x.size - 1)]
                o[i] = a + (b - a) * f
            }
            x = o
        } else x = x.copyOf()
        val decay = (k["decay"] ?: 100) / 100f
        if (decay < 0.995f) {                              // DECAY: shorter, the tail faded away
            val len = maxOf(64, (x.size * decay).toInt())
            val fade = maxOf(32, len / 3)
            x = x.copyOf(len)
            for (i in 0 until fade) x[len - 1 - i] *= (i.toFloat() / fade).let { it * it }
        }
        val attack = (k["attack"] ?: 0) / 100f
        if (attack > 0f) {                                 // ATTACK: the first 20 ms louder
            val n = minOf(x.size, SR / 50)
            for (i in 0 until n) x[i] *= 1f + attack * 1.5f * (1f - i.toFloat() / n)
        }
        val body = (k["body"] ?: 0).toFloat()
        if (body != 0f) x = biquadShelf(x, 120f, body)
        val tone = k["tone"] ?: 100
        val res = (k["res"] ?: 0) / 100f
        if (tone < 100 || res > 0.01f) {                   // TONE / RESONANCE: a resonant low-pass
            val f = 300f * 2f.pow(tone / 100f * 6f)        // 300 Hz .. 19 kHz
            x = biquadLow(x, f.coerceAtMost(19000f), 0.707f + res * 9f)
        }
        val drive = (k["drive"] ?: 0) / 100f
        if (drive > 0f) {
            val g = 1f + drive * 6f
            val norm = 1f / tanh(g)
            for (i in x.indices) x[i] = tanh(x[i] * g) * norm
        }
        var pk = 0f
        for (v in x) if (abs(v) > pk) pk = abs(v)
        if (pk > 0.98f) { val g = 0.98f / pk; for (i in x.indices) x[i] *= g }
        return x
    }

    // ---------------- biquads (RBJ's cookbook) ----------------

    private fun runBq(x: FloatArray, b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double): FloatArray {
        val c0 = (b0 / a0).toFloat(); val c1 = (b1 / a0).toFloat(); val c2 = (b2 / a0).toFloat()
        val d1 = (a1 / a0).toFloat(); val d2 = (a2 / a0).toFloat()
        val y = FloatArray(x.size)
        var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
        for (i in x.indices) {
            val v = c0 * x[i] + c1 * x1 + c2 * x2 - d1 * y1 - d2 * y2
            x2 = x1; x1 = x[i]; y2 = y1; y1 = v
            y[i] = v
        }
        return y
    }

    private fun biquadBand(x: FloatArray, f: Float, q: Float): FloatArray {
        val w = 2 * PI * f / SR; val al = sin(w) / (2 * q); val c = cos(w)
        return runBq(x, al, 0.0, -al, 1 + al, -2 * c, 1 - al)
    }

    private fun biquadLow(x: FloatArray, f: Float, q: Float): FloatArray {
        val w = 2 * PI * f / SR; val al = sin(w) / (2 * q); val c = cos(w)
        return runBq(x, (1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al)
    }

    private fun biquadShelf(x: FloatArray, f: Float, gainDb: Float): FloatArray {
        val a = 10.0.pow(gainDb / 40.0); val w = 2 * PI * f / SR; val c = cos(w)
        val sa = 2 * sqrt(a) * sin(w) / 2 * sqrt(2.0)
        return runBq(x, a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
            (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa)
    }

    // ---------------- the note kept beside an edited sound ----------------

    class Note(val src: String, val knobs: MutableMap<String, Int>)

    private fun noteFile(dir: File, name: String) = File(File(dir, "drumedit").also { it.mkdirs() }, "$name.json")

    fun readNote(dir: File, name: String): Note? = try {
        val f = noteFile(dir, name)
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            val k = o.getJSONObject("knobs")
            val m = HashMap<String, Int>()
            for (key in k.keys()) m[key] = k.getInt(key)
            Note(o.getString("src"), m)
        }
    } catch (e: Exception) { null }

    fun writeNote(dir: File, name: String, n: Note) {
        val o = JSONObject()
        o.put("src", n.src)
        val k = JSONObject()
        for ((key, v) in n.knobs) k.put(key, v)
        o.put("knobs", k)
        noteFile(dir, name).writeText(o.toString())
    }
}
