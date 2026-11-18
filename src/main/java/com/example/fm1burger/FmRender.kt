package com.example.fm1burger

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Renders DX7-format 6-operator FM voices (the format of M-VAVE FM-1 / DX7 .syx banks) to samples,
 * so a bank can be turned into sounds for the Sampler.
 *
 * Same method as tools/fm6.py. Its structure (32 algorithms, envelopes in log level units, level / rate /
 * velocity scaling, frequencies, feedback) follows the open DX7 engine in Google's "Music Synthesizer for
 * Android" (Apache License 2.0, see LICENSES/msfa-NOTICE.txt); this is a floating point re-implementation.
 * Not modelled: pitch envelope and LFO (rendered samples are one held note).
 */
object FmRender {

    private const val N = 64

    private val ALG = arrayOf(
        intArrayOf(0xc1, 0x11, 0x11, 0x14, 0x01, 0x14), intArrayOf(0x01, 0x11, 0x11, 0x14, 0xc1, 0x14),
        intArrayOf(0xc1, 0x11, 0x14, 0x01, 0x11, 0x14), intArrayOf(0x41, 0x11, 0x94, 0x01, 0x11, 0x14),
        intArrayOf(0xc1, 0x14, 0x01, 0x14, 0x01, 0x14), intArrayOf(0x41, 0x94, 0x01, 0x14, 0x01, 0x14),
        intArrayOf(0xc1, 0x11, 0x05, 0x14, 0x01, 0x14), intArrayOf(0x01, 0x11, 0xc5, 0x14, 0x01, 0x14),
        intArrayOf(0x01, 0x11, 0x05, 0x14, 0xc1, 0x14), intArrayOf(0x01, 0x05, 0x14, 0xc1, 0x11, 0x14),
        intArrayOf(0xc1, 0x05, 0x14, 0x01, 0x11, 0x14), intArrayOf(0x01, 0x05, 0x05, 0x14, 0xc1, 0x14),
        intArrayOf(0xc1, 0x05, 0x05, 0x14, 0x01, 0x14), intArrayOf(0xc1, 0x05, 0x11, 0x14, 0x01, 0x14),
        intArrayOf(0x01, 0x05, 0x11, 0x14, 0xc1, 0x14), intArrayOf(0xc1, 0x11, 0x02, 0x25, 0x05, 0x14),
        intArrayOf(0x01, 0x11, 0x02, 0x25, 0xc5, 0x14), intArrayOf(0x01, 0x11, 0x11, 0xc5, 0x05, 0x14),
        intArrayOf(0xc1, 0x14, 0x14, 0x01, 0x11, 0x14), intArrayOf(0x01, 0x05, 0x14, 0xc1, 0x14, 0x14),
        intArrayOf(0x01, 0x14, 0x14, 0xc1, 0x14, 0x14), intArrayOf(0xc1, 0x14, 0x14, 0x14, 0x01, 0x14),
        intArrayOf(0xc1, 0x14, 0x14, 0x01, 0x14, 0x04), intArrayOf(0xc1, 0x14, 0x14, 0x14, 0x04, 0x04),
        intArrayOf(0xc1, 0x14, 0x14, 0x04, 0x04, 0x04), intArrayOf(0xc1, 0x05, 0x14, 0x01, 0x14, 0x04),
        intArrayOf(0x01, 0x05, 0x14, 0xc1, 0x14, 0x04), intArrayOf(0x04, 0xc1, 0x11, 0x14, 0x01, 0x14),
        intArrayOf(0xc1, 0x14, 0x01, 0x14, 0x04, 0x04), intArrayOf(0x04, 0xc1, 0x11, 0x14, 0x04, 0x04),
        intArrayOf(0xc1, 0x14, 0x04, 0x04, 0x04, 0x04), intArrayOf(0xc4, 0x04, 0x04, 0x04, 0x04, 0x04)
    )
    private val LEVEL_LUT = intArrayOf(0, 5, 9, 13, 17, 20, 23, 25, 27, 29, 31, 33, 35, 37, 39, 41, 42, 43, 45, 46)
    private val VEL = intArrayOf(
        0, 70, 86, 97, 106, 114, 121, 126, 132, 138, 142, 148, 152, 156, 160, 163, 166, 170, 173, 174, 178,
        181, 184, 186, 189, 190, 194, 196, 198, 200, 202, 205, 206, 209, 211, 214, 216, 218, 220, 222, 224, 225,
        227, 229, 230, 232, 233, 235, 237, 238, 240, 241, 242, 243, 244, 246, 246, 248, 249, 250, 251, 252, 253, 254
    )
    private val EXP_SCALE = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 14, 16, 19, 23, 27, 33, 39, 47, 56, 66, 80, 94, 110, 126, 142, 158,
        174, 190, 206, 222, 238, 250
    )

    class Voice(val name: String, val p: IntArray)

    private fun scaleOut(l: Int): Int = if (l >= 20) l + 28 else LEVEL_LUT[l.coerceIn(0, 19)]
    private fun scaleVel(vel: Int, sens: Int): Int = ((sens * (VEL[vel.coerceIn(0, 127) shr 1] - 239) + 7) shr 3) shl 4
    private fun scaleRate(note: Int, sens: Int): Int = (sens * (note / 3 - 7).coerceIn(0, 31)) shr 3

    private fun scaleCurve(group: Int, depth: Int, curve: Int): Int {
        val s = if (curve == 0 || curve == 3) (group * depth * 329) shr 12
        else (EXP_SCALE[min(group, EXP_SCALE.size - 1)] * depth * 329) shr 15
        return if (curve < 2) -s else s
    }

    private fun scaleLevel(note: Int, bp: Int, ld: Int, rd: Int, lc: Int, rc: Int): Int {
        val off = note - bp - 17
        return if (off >= 0) scaleCurve(off / 3, rd, rc) else scaleCurve(-off / 3, ld, lc)
    }

    private class Env(val r: IntArray, val l: IntArray, val ol: Int, val rs: Int) {
        var level = 0.0
        var down = true
        var ix = 0
        var target = 0.0
        var rising = false
        var inc = 0.0

        init {
            advance(0)
        }

        fun advance(i: Int) {
            ix = i
            if (ix < 4) {
                val a = ((scaleOut(l[ix]) shr 1) shl 6) + ol - 4256
                target = max(16, a) * 65536.0
                rising = target > level
                val q = min(63, ((r[ix] * 41) shr 6) + rs)
                inc = ((4 + (q and 3)) shl (2 + 6 + (q shr 2))).toDouble()
            }
        }

        fun keyUp() {
            if (down) {
                down = false
                advance(3)
            }
        }

        fun step(): Double {
            if (ix < 3 || (ix < 4 && !down)) {
                if (rising) {
                    val jump = 1716 * 65536.0
                    if (level < jump) level = jump
                    level += ((17 * 16777216.0 - level) / 16777216.0) * inc
                    if (level >= target) {
                        level = target
                        advance(ix + 1)
                    }
                } else {
                    level -= inc
                    if (level <= target) {
                        level = target
                        advance(ix + 1)
                    }
                }
            }
            return level
        }
    }

    private fun opFreq(note: Int, mode: Int, coarse: Int, fine: Int, detune: Int): Double {
        return if (mode == 0) {
            val c = if ((coarse and 31) == 0) 0.5 else (coarse and 31).toDouble()
            440.0 * 2.0.pow((note - 69) / 12.0) * c * (1 + 0.01 * fine) * 2.0.pow(12606.0 * (detune - 7) / 16777216.0)
        } else {
            10.0.pow((coarse and 3) + fine / 100.0) * (if (detune > 7) 2.0.pow(13457.0 * (detune - 7) / 16777216.0) else 1.0)
        }
    }

    /** One note of a 155 value voice: [hold] seconds held, then [tail] seconds of release. Normalised, end trimmed. */
    fun render(v: IntArray, note: Int = 60, vel: Int = 100, hold: Double = 1.0, tail: Double = 1.5): FloatArray {
        val sr = Cfg.SR
        val envs = ArrayList<Env>()
        val inc = DoubleArray(6)
        for (op in 0 until 6) {
            val o = op * 21
            var ol = scaleOut(v[o + 16].coerceIn(0, 99))
            ol = min(127, ol + scaleLevel(note, v[o + 8], v[o + 9], v[o + 10], v[o + 11], v[o + 12]))
            ol = max(0, (ol shl 5) + scaleVel(vel, v[o + 15]))
            envs.add(Env(intArrayOf(v[o], v[o + 1], v[o + 2], v[o + 3]), intArrayOf(v[o + 4], v[o + 5], v[o + 6], v[o + 7]), ol,
                scaleRate(note, v[o + 13])))
            inc[op] = opFreq(note, v[o + 17], v[o + 18], v[o + 19], v[o + 20]) / sr
        }
        val alg = ALG[v[134] and 31]
        val fb = v[135] and 7
        val fbShift = if (fb != 0) 8 - fb else 16
        val blocks = ((hold + tail) * sr).toInt() / N
        val out = FloatArray(blocks * N)
        val phase = DoubleArray(6)
        val gPrev = DoubleArray(6)
        var fy0 = 0.0
        var fy1 = 0.0
        val holdBlocks = (hold * sr).toInt() / N
        val bus = Array(3) { DoubleArray(N) }
        val y = DoubleArray(N)
        val twoPi = 2.0 * PI
        for (b in 0 until blocks) {
            if (b == holdBlocks) for (e in envs) e.keyUp()
            val has = booleanArrayOf(true, false, false)
            for (op in 0 until 6) {
                val flags = alg[op]
                val g1 = gPrev[op]
                val g2 = 2.0.pow(envs[op].step() / 16777216.0 - 14.0)
                gPrev[op] = g2
                val add = (flags and 4) != 0
                val inb = (flags shr 4) and 3
                val outb = if (add) 0 else (flags and 3)
                if (g1 < 0.002 && g2 < 0.002) {
                    if (!add) has[outb] = false
                    phase[op] = (phase[op] + inc[op] * N) % 1.0
                    continue
                }
                val useFb = (flags and 0xc0) == 0xc0 && fbShift < 16 && (inb == 0 || !has[inb])
                val modIn = inb != 0 && has[inb]
                for (i in 0 until N) {
                    val gain = g1 + (g2 - g1) * (i + 1) / N
                    val ph = phase[op] + inc[op] * i
                    y[i] = when {
                        useFb -> {
                            val sfb = (fy0 + fy1) / 2.0 / (1 shl fbShift)
                            fy0 = fy1
                            fy1 = sin(twoPi * (ph + sfb)) * gain
                            fy1
                        }
                        modIn -> sin(twoPi * (ph + bus[inb][i])) * gain
                        else -> sin(twoPi * ph) * gain
                    }
                }
                val dst = bus[outb]
                if (add && has[outb]) for (i in 0 until N) dst[i] += y[i] else for (i in 0 until N) dst[i] = y[i]
                has[outb] = true
                phase[op] = (phase[op] + inc[op] * N) % 1.0
            }
            for (i in 0 until N) out[b * N + i] = bus[0][i].toFloat()
        }
        // normalise and trim the silent end
        var pk = 0f
        for (x in out) pk = max(pk, abs(x))
        if (pk <= 0f) return FloatArray(0)
        val g = 0.89f / pk
        var end = out.size
        while (end > 1 && abs(out[end - 1] * g) < 0.001f) end--
        end = min(out.size, end + sr / 20)
        val res = FloatArray(end)
        val fade = min(end, sr / 50)
        for (i in 0 until end) {
            var s = out[i] * g
            if (i >= end - fade) s *= (end - i).toFloat() / fade
            res[i] = s
        }
        return res
    }

    // ---------------- reading .syx files ----------------

    private fun unpack(b: IntArray): IntArray {
        val p = IntArray(155)
        for (op in 0 until 6) {
            val bo = op * 17
            val o = op * 21
            for (k in 0 until 11) p[o + k] = b[bo + k]
            p[o + 11] = b[bo + 11] and 3
            p[o + 12] = (b[bo + 11] shr 2) and 3
            p[o + 13] = b[bo + 12] and 7
            p[o + 20] = b[bo + 12] shr 3
            p[o + 14] = b[bo + 13] and 3
            p[o + 15] = b[bo + 13] shr 2
            p[o + 16] = b[bo + 14]
            p[o + 17] = b[bo + 15] and 1
            p[o + 18] = b[bo + 15] shr 1
            p[o + 19] = b[bo + 16]
        }
        for (k in 0 until 9) p[126 + k] = b[102 + k]
        p[135] = b[111] and 7
        p[136] = b[111] shr 3
        for (k in 0 until 4) p[137 + k] = b[112 + k]
        p[141] = b[116] and 1
        p[142] = (b[116] shr 1) and 7
        p[143] = b[116] shr 4
        for (k in 0 until 11) p[144 + k] = b[117 + k]
        // keep every value in range, so a damaged file cannot break the renderer
        for (op in 0 until 6) {
            val o = op * 21
            for (k in 0 until 8) p[o + k] = p[o + k].coerceIn(0, 99)
            p[o + 16] = p[o + 16].coerceIn(0, 99)
            p[o + 20] = p[o + 20].coerceIn(0, 14)
        }
        return p
    }

    private fun nameOf(p: IntArray, fallback: String): String {
        val sb = StringBuilder()
        for (k in 145 until 155) {
            val c = p[k]
            sb.append(if (c in 32..126) c.toChar() else ' ')
        }
        val s = sb.toString().trim()
        return s.ifEmpty { fallback }
    }

    /**
     * Reads a DX7 / FM-1 .syx file: a 32 voice bank (4104 bytes) or a single voice (163 bytes).
     * Returns the voices, or null when the file is not one of those.
     */
    fun readSyx(data: ByteArray): List<Voice>? {
        var i = 0
        while (i < data.size - 6) {
            if ((data[i].toInt() and 0xFF) == 0xF0 && (data[i + 1].toInt() and 0xFF) == 0x43) break
            i++
        }
        if (i >= data.size - 6) return null
        val fmt = data[i + 3].toInt() and 0xFF
        val body = i + 6
        if (fmt == 9 && data.size >= body + 4096) {
            val out = ArrayList<Voice>()
            for (k in 0 until 32) {
                val b = IntArray(128) { data[body + k * 128 + it].toInt() and 0x7F }
                val p = unpack(b)
                out.add(Voice(nameOf(p, "Voice ${k + 1}"), p))
            }
            return out
        }
        if (fmt == 0 && data.size >= body + 155) {
            val p = IntArray(155) { data[body + it].toInt() and 0x7F }
            return listOf(Voice(nameOf(p, "Voice"), p))
        }
        return null
    }
}
