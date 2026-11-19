package com.example.fm1burger

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Sends a sample from the app into a user sample slot (USR1..USR3) of an FM-1 running the Burger Studio
 * firmware (or Felucca): over USB MIDI SysEx, the same protocol as Felucca's tools/fm1_sample_upload.py
 * (web/EDITOR_PROTOCOL.md, commands 11..15). On the FM-1 the slot plays in the SAMPLE engine (SET USR1..3)
 * and in the SLICE engine (SRC USR1..3, sliced on its hits by AUTO).
 *
 * The slot: mono, 22050 Hz, IMA ADPCM (4 bit, low nibble first, from predictor 0 / step index 0), at most
 * 7.0 s (77,312 bytes: the slot's last sector stays free for the slices set by hand on the device).
 */
class Fm1SampleLink(private val midi: MidiLink) {

    companion object {
        private val HDR = byteArrayOf(0x7D, 0x46, 0x4C)
        const val SLOT_RATE = 22050
        const val SLOTS = 3
        private const val DATA_OFF = 512
        private const val ZONES = 16
        private const val HDR_LEN = 32 + ZONES * 28
        /** ADPCM bytes that leave the slot's last 4 KiB sector free (SLICE's own slices go there). */
        const val MAX_DATA = 0x13000 - DATA_OFF
        val MAX_SECONDS: Double get() = MAX_DATA * 2.0 / SLOT_RATE

        private val IMA_STEP = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88,
            97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
            724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660,
            4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818,
            18500, 20350, 22385, 24623, 27086, 29794, 32767
        )
        private val IMA_IDX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)

        /** 44.1 kHz floats -> 22050 Hz int16, peak at 30000 (as Felucca's uploader), cut to the slot with a short fade. */
        fun toSlot(src: FloatArray): ShortArray {
            val n = src.size / 2
            val maxN = MAX_DATA * 2
            val len = min(n, maxN)
            val f = FloatArray(len)
            var pk = 0f
            for (i in 0 until len) {
                // two to one, averaged (a gentle anti-alias)
                val a = src[2 * i]
                val b = if (2 * i + 1 < src.size) src[2 * i + 1] else a
                val v = (a + b) * 0.5f
                f[i] = v
                if (abs(v) > pk) pk = abs(v)
            }
            if (n > maxN) {
                val fade = min(len, SLOT_RATE / 50)
                for (k in 0 until fade) f[len - fade + k] *= 1f - k.toFloat() / fade
            }
            val g = if (pk > 1e-6f) 30000f / pk else 0f
            return ShortArray(len) { (f[it] * g).toInt().coerceIn(-32768, 32767).toShort() }
        }

        fun imaEncode(s: ShortArray): ByteArray {
            var pred = 0
            var idx = 0
            val out = ByteArray((s.size + 1) / 2)
            for (n in s.indices) {
                val step = IMA_STEP[idx]
                var diff = s[n] - pred
                var code = 0
                if (diff < 0) {
                    code = 8
                    diff = -diff
                }
                var vd = step shr 3
                if (diff >= step) {
                    code = code or 4
                    diff -= step
                    vd += step
                }
                if (diff >= step shr 1) {
                    code = code or 2
                    diff -= step shr 1
                    vd += step shr 1
                }
                if (diff >= step shr 2) {
                    code = code or 1
                    vd += step shr 2
                }
                pred = (if (code and 8 != 0) pred - vd else pred + vd).coerceIn(-32768, 32767)
                idx = (idx + IMA_IDX[code and 7]).coerceIn(0, 88)
                if (n and 1 == 0) out[n shr 1] = code.toByte()
                else out[n shr 1] = (out[n shr 1].toInt() or (code shl 4)).toByte()
            }
            return out
        }

        /** The 480-byte slot header: one zone over the whole keyboard, root C4 (Felucca's sampleio.user_slot). */
        fun header(name: String, samples: Int, data: ByteArray, root: Int = 60): ByteArray {
            val h = ByteArray(HDR_LEN)
            fun u16(o: Int, v: Int) { h[o] = v.toByte(); h[o + 1] = (v shr 8).toByte() }
            fun u32(o: Int, v: Long) { for (k in 0 until 4) h[o + k] = (v shr (8 * k)).toByte() }
            u32(0, 0x504D5346L)                      // "FSMP"
            u16(4, 1)
            h[6] = 1                                  // one zone
            val nm = name.uppercase().filter { it.code in 0x20..0x7E }.take(8)
            for ((k, c) in nm.withIndex()) h[8 + k] = c.code.toByte()
            val crc = CRC32()
            crc.update(data)
            u32(16, data.size.toLong())
            u32(20, crc.value)
            val z = 32
            u32(z, 0)                                 // off
            u32(z + 4, samples.toLong())              // n
            u32(z + 8, 0)                             // loop start
            u32(z + 12, (samples - 1).toLong())       // loop end
            u32(z + 16, Math.round(SLOT_RATE / 44100.0 * 65536).toLong())
            u16(z + 20, root * 16)
            u16(z + 22, 0)                            // predictor at the loop start
            h[z + 24] = 0                             // step index there
            h[z + 25] = 0                             // lo note
            h[z + 26] = 127                           // hi note
            h[z + 27] = 0                             // not looped
            return h
        }

        /** Felucca's pack7: groups of up to 7 bytes, each group led by their top bits. */
        fun pack7(b: ByteArray, from: Int = 0, to: Int = b.size): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var i = from
            while (i < to) {
                val e = min(i + 7, to)
                var hi = 0
                for (j in i until e) hi = hi or (((b[j].toInt() shr 7) and 1) shl (j - i))
                out.write(hi)
                for (j in i until e) out.write(b[j].toInt() and 0x7F)
                i = e
            }
            return out.toByteArray()
        }
    }

    private val replies = ArrayBlockingQueue<ByteArray>(64)

    /** The device asked for something (firmware bs5: REC on its SLICES page): kind, slot (127 none), track. */
    @Volatile var onEvent: ((Int, Int, Int) -> Unit)? = null

    init {
        midi.sysexListener = { data ->
            if (data.size >= 4 && data[0] == HDR[0] && data[1] == HDR[1] && data[2] == HDR[2]) {
                if (data[3].toInt() == 113 && data.size >= 7) onEvent?.invoke(data[4].toInt(), data[5].toInt(), data[6].toInt())
                else replies.offer(data)
            }
        }
    }

    /** Firmware bs5 and later: link the app (REC on the device's SLICES page then asks the app for a take). */
    fun hello(): Boolean = try {
        req(114, ByteArray(0), 1200).isNotEmpty()
    } catch (ex: IllegalStateException) {
        false
    }

    private fun req(cmd: Int, args: ByteArray, timeoutMs: Long = 3000): ByteArray {
        replies.clear()
        val body = ByteArray(4 + args.size)
        System.arraycopy(HDR, 0, body, 0, 3)
        body[3] = cmd.toByte()
        System.arraycopy(args, 0, body, 4, args.size)
        midi.sendSysex(body)
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) throw IllegalStateException("no answer from the FM-1 (command $cmd)")
            val r = replies.poll(left, TimeUnit.MILLISECONDS) ?: continue
            if (r[3].toInt() == cmd) return r.copyOfRange(4, r.size)
        }
    }

    /** The device answers SMP_INFO: it runs Burger Studio / Felucca firmware. Lines per slot. */
    fun info(): List<String> {
        var r: ByteArray? = null
        for (k in 0 until 3) {                       // the first frame after opening a port can be lost
            try {
                r = req(15, ByteArray(0), 1200)
                break
            } catch (ex: IllegalStateException) {
            }
        }
        val d = r ?: throw IllegalStateException(
            "The FM-1 does not answer. Is it running the Burger Studio firmware, and connected in MIDI sync?")
        val out = ArrayList<String>()
        try {
            val n = d[0].toInt()
            var i = 2
            for (k in 0 until n) {
                val nz = d[i].toInt()
                var j = i + 1
                while (d[j].toInt() != 0) j++
                val nm = String(d, i + 1, j - i - 1, Charsets.US_ASCII)
                out.add("USR${k + 1}: " + if (nz == 0) "empty" else "$nm (${d[j + 1]} KiB)")
                i = j + 2
            }
        } catch (ex: Exception) {
        }
        return out
    }

    /** Uploads [src] (44.1 kHz mono floats) into slot 0..2. [progress] gets 0..1. Runs on a worker thread. */
    fun upload(slot: Int, name: String, src: FloatArray, progress: (Float) -> Unit): String {
        val s = toSlot(src)
        if (s.isEmpty()) throw IllegalStateException("The sample is empty")
        val data = imaEncode(s)
        val hdr = header(name, s.size, data)
        info()
        val sl = slot.coerceIn(0, SLOTS - 1).toByte()
        val b = req(11, byteArrayOf(sl))
        if (b.size < 2 || b[1].toInt() != 0) throw IllegalStateException("The FM-1 could not start the slot (${b.getOrNull(1)})")
        var off = 0
        while (off < data.size) {
            val e = min(off + 256, data.size)
            val a = DATA_OFF + off
            val p = pack7(data, off, e)
            val args = ByteArray(4 + p.size)
            args[0] = sl
            args[1] = (a and 0x7F).toByte()
            args[2] = ((a shr 7) and 0x7F).toByte()
            args[3] = ((a shr 14) and 0x7F).toByte()
            System.arraycopy(p, 0, args, 4, p.size)
            val r = req(12, args)
            if (r.size < 5 || r[4].toInt() != 0) throw IllegalStateException("Writing failed at byte $a (${r.getOrNull(4)})")
            off = e
            progress(off.toFloat() / data.size)
        }
        val hp = pack7(hdr)
        val args = ByteArray(1 + hp.size)
        args[0] = sl
        System.arraycopy(hp, 0, args, 1, hp.size)
        val r = req(13, args, 6000)
        if (r.size < 2 || r[1].toInt() != 0) {
            val why = when (r.getOrNull(1)?.toInt()) { 1 -> "size"; 2 -> "header"; 3 -> "data check"; 4 -> "flash"; 5 -> "zones"; else -> "?" }
            throw IllegalStateException("The FM-1 did not accept the sample ($why)")
        }
        return String.format(java.util.Locale.US, "%.1f s", s.size / SLOT_RATE.toDouble())
    }

    // ---------------- setting up a track on the device (editor protocol: INFO, DESC, SET, TRACK) ----------------

    private var gEng = -1                 // the global id of "ENG" (the engine of the selected track)
    private var pE0 = -1                  // P_E0: SRC / SET of the sample engines
    private var engNames: List<String> = emptyList()

    private fun v14(v: Int): ByteArray {
        val x = (v + 8192).coerceIn(0, 16383)
        return byteArrayOf((x and 0x7F).toByte(), ((x shr 7) and 0x7F).toByte())
    }

    private fun readStr(d: ByteArray, from: Int): Pair<String, Int> {
        var j = from
        while (j < d.size && d[j].toInt() != 0) j++
        return Pair(String(d, from, j - from, Charsets.US_ASCII), j + 1)
    }

    /** INFO: the engine names, P_E0, and (once) the global id of ENG. */
    private fun learn() {
        if (gEng >= 0 && pE0 >= 0 && engNames.isNotEmpty()) return
        val d = req(1, ByteArray(0))
        var (_, i) = readStr(d, 0)
        val nEng = d[i].toInt(); val gCount = d[i + 2].toInt(); pE0 = d[i + 4].toInt()
        i += 5
        val names = ArrayList<String>()
        for (k in 0 until nEng) {
            val (n, j) = readStr(d, i)
            names.add(n)
            i = j
        }
        engNames = names
        for (id in 0 until gCount) {
            val r = req(5, byteArrayOf(1, id.toByte()))
            if (r.size > 9 && readStr(r, 9).first == "ENG") {
                gEng = id
                break
            }
        }
        if (gEng < 0) throw IllegalStateException("The device does not say how to change its engine")
    }

    /** The enum names of parameter [id] (scope 0: the selected track). */
    private fun names(id: Int): List<String> {
        val r = req(5, byteArrayOf(0, id.toByte()))
        var (_, i) = readStr(r, 9)                   // the label
        i = readStr(r, i).second                     // the unit
        val out = ArrayList<String>()
        while (i < r.size) {
            val (n, j) = readStr(r, i)
            out.add(n)
            i = j
        }
        return out
    }

    /**
     * Track [track] (0..3) of the device plays slot [slot] with [engine] ("SLICE": chopped, trimmed on its SLICES page;
     * "SAMPLE": on the keys). Its engine changes only when it is another one (a change loads that engine's first sound).
     */
    fun prepareTrack(track: Int, engine: String, slot: Int) {
        learn()
        req(27, byteArrayOf(track.coerceIn(0, 3).toByte()))
        val want = engNames.indexOf(engine)
        if (want < 0) throw IllegalStateException("The device has no $engine engine")
        val cur = req(2, byteArrayOf(1, gEng.toByte()))
        val now = if (cur.size >= 4) ((cur[2].toInt() and 0x7F) or ((cur[3].toInt() and 0x7F) shl 7)) - 8192 else -1
        if (now != want) req(3, byteArrayOf(1, gEng.toByte()) + v14(want))
        val idx = names(pE0).indexOf("USR${slot + 1}")
        if (idx < 0) throw IllegalStateException("The device's $engine has no USR${slot + 1}")
        req(3, byteArrayOf(0, pE0.toByte()) + v14(idx))
    }

    /**
     * Firmware 2 (Burger Studio FM-1 bs4 and later): one command puts the slot on a track, opens the trim page (SLICE),
     * plays it once and starts the music again if the upload stopped it. false: an older firmware (use prepareTrack).
     */
    fun show(slot: Int, track: Int, slice: Boolean, play: Boolean = true, resume: Boolean = true): Boolean {
        val r = try {
            req(112, byteArrayOf(slot.toByte(), track.coerceIn(0, 3).toByte(), (if (slice) 0 else 1).toByte(),
                ((if (play) 1 else 0) or (if (resume) 2 else 0)).toByte()), 1500)
        } catch (ex: IllegalStateException) {
            return false
        }
        if (r.size < 2) return false
        return when (r[1].toInt()) {
            0 -> true
            2 -> throw IllegalStateException("The slot on the device is empty")
            3 -> throw IllegalStateException("The device cannot play the slot with that engine")
            else -> false
        }
    }

    fun close() {
        midi.sysexListener = null
    }

    @Suppress("unused")
    private fun clampSec(x: Double) = max(0.0, x)
}
