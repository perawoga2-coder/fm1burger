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

        /** 3.18: what goes into a slot: HI 22.05 kHz, LONG 11.025 kHz, LO-FI 5.5 kHz (the device plays each at its rate). */
        val QUALITY_NAMES = arrayOf("HI", "LONG", "LO-FI")
        private val QUALITY_DIV = intArrayOf(2, 4, 8)
        fun qualityRate(q: Int): Double = 44100.0 / QUALITY_DIV[q.coerceIn(0, 2)]
        /** ADPCM bytes for a sample over [span] slots in a row (firmware bs10 for 2 and 3), the last 4 KiB left for SLICE's slices. */
        fun capBytes(span: Int): Int = (span.coerceIn(1, 3) - 1) * 0x14000 + MAX_DATA
        fun capSeconds(q: Int, span: Int): Double = capBytes(span) * 2.0 / qualityRate(q)
        /** The shortest way to hold [secs] at quality [q] from slot [slot] on: span 1..3, or 0 when even all the slots are too short. */
        fun spanFor(secs: Double, q: Int, slot: Int): Int {
            for (sp in 1..(SLOTS - slot)) if (capSeconds(q, sp) >= secs) return sp
            return 0
        }

        /** 44.1 kHz floats -> int16 at quality [q] ([div] 2 / 4 / 8 to one, averaged), peak at 30000, cut to [span] slots with a short fade. */
        fun toSlot(src: FloatArray, q: Int = 0, span: Int = 1): ShortArray {
            val div = QUALITY_DIV[q.coerceIn(0, 2)]
            val rate = (44100 / div)
            val n = src.size / div
            val maxN = capBytes(span) * 2
            val len = min(n, maxN)
            val f = FloatArray(len)
            var pk = 0f
            for (i in 0 until len) {
                var v = 0f                                // div to one, averaged (a gentle anti-alias; LO-FI keeps some grit)
                for (j in 0 until div) v += src[min(src.size - 1, div * i + j)]
                v /= div
                f[i] = v
                if (abs(v) > pk) pk = abs(v)
            }
            if (n > maxN) {
                val fade = min(len, rate / 50)
                for (k in 0 until fade) f[len - fade + k] *= 1f - k.toFloat() / fade
            }
            if (pk > 1e-6f && loud > 0) {
                // 3.18 LOUDNESS, in two layers: the sound itself (its head, the hit, untouched) with a squeezed copy under it
                // (+12 dB into a fast limiter and a soft knee: the body). About +5 dB (LOUD) / +6 dB (LOUDER) on a spoon hit,
                // the hit keeps its snap (3.17 squeezed the sound itself, which flattened the head).
                val w = if (loud >= 2) 2.5f else 1.0f
                val drive = 4f
                val att = 1f - kotlin.math.exp(-1f / (0.001f * rate))
                val rel = 1f - kotlin.math.exp(-1f / (0.025f * rate))
                var env = 0f
                val inv = 1f / pk
                pk = 0f
                for (i in 0 until len) {
                    val o = f[i] * inv
                    val x = o * drive
                    val ax = abs(x)
                    env += (ax - env) * (if (ax > env) att else rel)
                    var c = if (env > 0.9f) x * 0.9f / env else x
                    val ac = abs(c)
                    if (ac > 0.8f) c = (if (c < 0f) -1f else 1f) * (0.8f + 0.2f * kotlin.math.tanh((ac - 0.8f) / 0.2f))
                    val y = o + w * c
                    f[i] = y
                    if (abs(y) > pk) pk = abs(y)
                }
            }
            val g = if (pk > 1e-6f) 30000f / pk else 0f
            return ShortArray(len) { (f[it] * g).toInt().coerceIn(-32768, 32767).toShort() }
        }

        /** Loudness of what goes into a slot: 0 NORMAL (peak only), 1 LOUD (default), 2 LOUDER. */
        @Volatile var loud = 1
        val LOUD_NAMES = arrayOf("Normal", "Loud", "Louder")

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
        fun header(name: String, samples: Int, data: ByteArray, root: Int = 60, q: Int = 0, span: Int = 1): ByteArray {
            val h = ByteArray(HDR_LEN)
            fun u16(o: Int, v: Int) { h[o] = v.toByte(); h[o + 1] = (v shr 8).toByte() }
            fun u32(o: Int, v: Long) { for (k in 0 until 4) h[o + k] = (v shr (8 * k)).toByte() }
            u32(0, 0x504D5346L)                      // "FSMP"
            u16(4, 1)
            h[6] = 1                                  // one zone
            h[7] = (if (span >= 2) span else 0).toByte()   // bs10: the slots it takes (joined)
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
            u32(z + 16, Math.round(qualityRate(q) / 44100.0 * 65536).toLong())
            u16(z + 20, root * 16)
            u16(z + 22, 0)                            // predictor at the loop start
            h[z + 24] = 0                             // step index there
            h[z + 25] = 0                             // lo note
            h[z + 26] = 127                           // hi note
            h[z + 27] = 0                             // not looped
            return h
        }

        /**
         * A kit: zone k (one hit) on key 60 + k only (C4, C#4, D4 ...), the keys between them silent; each zone is
         * encoded on its own from predictor 0 / index 0, as the device starts every note. [offs] byte offsets, [ns] samples.
         */
        fun kitHeader(name: String, offs: IntArray, ns: IntArray, data: ByteArray): ByteArray {
            val h = ByteArray(HDR_LEN)
            fun u16(o: Int, v: Int) { h[o] = v.toByte(); h[o + 1] = (v shr 8).toByte() }
            fun u32(o: Int, v: Long) { for (k in 0 until 4) h[o + k] = (v shr (8 * k)).toByte() }
            val n = min(offs.size, ZONES)
            u32(0, 0x504D5346L)
            u16(4, 1)
            h[6] = n.toByte()
            val nm = name.uppercase().filter { it.code in 0x20..0x7E }.take(8)
            for ((k, c) in nm.withIndex()) h[8 + k] = c.code.toByte()
            val crc = CRC32()
            crc.update(data)
            u32(16, data.size.toLong())
            u32(20, crc.value)
            for (k in 0 until n) {
                val z = 32 + k * 28
                val root = 60 + k
                u32(z, offs[k].toLong())
                u32(z + 4, ns[k].toLong())
                u32(z + 8, 0)
                u32(z + 12, (ns[k] - 1).toLong())
                u32(z + 16, Math.round(SLOT_RATE / 44100.0 * 65536).toLong())
                u16(z + 20, root * 16)
                u16(z + 22, 0)
                h[z + 24] = 0
                h[z + 25] = root.toByte()             // lo = hi = its own key
                h[z + 26] = root.toByte()
                h[z + 27] = 0
            }
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

    /** The firmware joins slots (bs10 and later): learnt from SMP_INFO. */
    @Volatile var joinOk = false

    /** The device asked for something (firmware bs5: REC on its SLICES page): kind, slot (127 none), track. */
    @Volatile var onEvent: ((Int, Int, Int) -> Unit)? = null

    init {
        midi.sysexListener = { data ->
            if (data.size >= 4 && data[0] == HDR[0] && data[1] == HDR[1] && data[2] == HDR[2]) {
                if (data[3].toInt() == 113 && data.size >= 7) onEvent?.invoke(data[4].toInt(), data[5].toInt(), data[6].toInt())
                else if (data[3].toInt() == 20) {     // 3.54: STACK's answer to MUTE (never waited for: not queued)
                    if (data.size >= 5) onMuteAck?.invoke(data[4].toInt() and 0x7F)
                } else replies.offer(data)
            }
        }
    }

    /** 3.56: the CRC of the data the last upload sent (what STACK's song names a sample of the app's by). */
    @Volatile var lastCrc = 0L

    // ---------------- 3.56 VACUUM: STACK's song and loop (stack/docs/PROTOCOL.md: commands 30, 31, 32) ----------------

    /** The FM-1 answered none of the tries: it is not there, or its firmware is older than STACK 4.0-beta. */
    class NoAnswer : Exception("no answer")
    class Loop(val data: ShortArray, val rate: Int)

    /** One piece asked for until its answer comes (the answer names the piece: an older one that comes late is passed over). */
    private fun piece(cmd: Int, k: Int, tries: Int): ByteArray {
        for (n in 0 until tries) {
            try {
                val end = System.currentTimeMillis() + 700
                while (System.currentTimeMillis() < end) {
                    val r = req(cmd, byteArrayOf((k and 0x7F).toByte(), (k shr 7).toByte()), 700)
                    if (r.size >= 4 && ((r[0].toInt() and 0x7F) or ((r[1].toInt() and 0x7F) shl 7)) == k) return r
                }
            } catch (ex: IllegalStateException) {
            }
        }
        throw NoAnswer()
    }

    /** STACK's song, whole (its record: StackDump.parse reads it). NoAnswer: no STACK 4.0-beta or newer there. */
    fun dumpSong(progress: (Float) -> Unit): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var pieces = 1
        var k = 0
        while (k < pieces) {
            val r = piece(30, k, if (k == 0) 2 else 5)
            pieces = ((r[2].toInt() and 0x7F) or ((r[3].toInt() and 0x7F) shl 7)).coerceIn(1, 64)
            out.write(StackDump.unpack7(r, 4))
            k++
            progress(k.toFloat() / pieces)
        }
        return out.toByteArray()
    }

    /** The loop on STACK's track 8 (or 7, since 4.04-beta), or null when there is none. Read again from its start if it
     * changed meanwhile. 3.59: since 4.03-beta a loop is up to 8 s (352 800 bytes, 2 757 pieces, four times before):
     * there is no time limit on the whole, only on each piece (asked again, five times), and the bytes are kept in
     * one buffer of the loop's own size. */
    fun dumpLoop(progress: (Float) -> Unit): Loop? {
        for (round in 0 until 3) {
            var info: ByteArray? = null
            for (n in 0 until 4) {
                try {
                    info = req(31, ByteArray(0), 900)
                    break
                } catch (ex: IllegalStateException) {
                }
            }
            val i = info ?: throw NoAnswer()
            if (i.size < 8 || i[0].toInt() == 0) return null
            val rate = if (i[1].toInt() == 1) 11025 else 22050
            val n = (i[2].toInt() and 0x7F) or ((i[3].toInt() and 0x7F) shl 7) or ((i[4].toInt() and 0x7F) shl 14)
            val ver = (i[6].toInt() and 0x7F) or ((i[7].toInt() and 0x7F) shl 7)
            val c4 = StackDump.unpack7(i, 8)
            if (n <= 0 || c4.size < 4) return null
            val want = (c4[0].toLong() and 0xFF) or ((c4[1].toLong() and 0xFF) shl 8) or ((c4[2].toLong() and 0xFF) shl 16) or ((c4[3].toLong() and 0xFF) shl 24)
            val bytes = java.io.ByteArrayOutputStream(2 * n + StackDump.PIECE)
            val pieces = (2 * n + StackDump.PIECE - 1) / StackDump.PIECE
            var same = true
            var k = 0
            while (k < pieces && same) {
                val r = piece(32, k, 5)
                same = ((r[2].toInt() and 0x7F) or ((r[3].toInt() and 0x7F) shl 7)) == ver
                bytes.write(StackDump.unpack7(r, 4))
                k++
                if (k % 8 == 0) progress(k.toFloat() / pieces)
            }
            val b = bytes.toByteArray()
            val crc = CRC32()
            crc.update(b, 0, minOf(b.size, 2 * n))
            if (same && b.size >= 2 * n && crc.value == want) {
                progress(1f)
                return Loop(ShortArray(n) { ((b[2 * it].toInt() and 0xFF) or (b[2 * it + 1].toInt() shl 8)).toShort() }, rate)
            }
            // (a layer was added or taken off while it was read, or a piece was damaged: once more, from the start)
        }
        throw IllegalStateException("The loop kept changing while it was read. Stop adding layers, then press VACUUM FM-1 again.")
    }

    /** 3.54: STACK answered MUTE: 1 its own output is silent now, 0 it sounds (stack/docs/PROTOCOL.md). */
    @Volatile var onMuteAck: ((Int) -> Unit)? = null

    /**
     * 3.54: STACK's MUTE (command 20): F0 7D 46 4C 14 01 F7 the FM-1's own speaker and 3.5 mm jack silent, 00 they
     * sound. A MUTE ON lasts 3 s on the FM-1: it is sent again every second while it is wanted. Nothing is waited for.
     */
    fun mute(on: Boolean): Boolean =
        midi.sendSysex(byteArrayOf(HDR[0], HDR[1], HDR[2], 20, if (on) 1 else 0))

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
            joinOk = i + n <= d.size                 // (an older firmware sends nothing after the slots)
            for (k in 0 until n) {                   // bs10: after the slots, what each one's sample takes / whose it is
                if (i + k >= d.size) break
                val v = d[i + k].toInt()
                if (v and 0x40 != 0) out[k] = "USR${k + 1}: part of USR${(v and 3) + 1}'s sample"
                else if (v >= 2 && k < out.size) out[k] = out[k] + " · USR${k + 1}-${k + v}"
            }
        } catch (ex: Exception) {
        }
        return out
    }

    /** Uploads [src] (44.1 kHz mono floats) into slot 0..2. [progress] gets 0..1. Runs on a worker thread. */
    fun upload(slot: Int, name: String, src: FloatArray, q: Int = 0, span: Int = 1, progress: (Float) -> Unit): String {
        val sp = span.coerceIn(1, SLOTS - slot.coerceIn(0, SLOTS - 1))
        val s = toSlot(src, q, sp)
        if (s.isEmpty()) throw IllegalStateException("The sample is empty")
        val data = imaEncode(s)
        val hdr = header(name, s.size, data, 60, q, sp)
        write(slot, hdr, data, progress)
        lastCrc = CRC32().also { it.update(data) }.value        // (3.56: VACUUM finds the sample again by it)
        return String.format(java.util.Locale.US, "%.1f s, %s%s", s.size / qualityRate(q), QUALITY_NAMES[q.coerceIn(0, 2)],
            if (sp > 1) ", USR${slot + 1}-${slot + sp}" else "")
    }

    /** Up to 16 hits as a kit in one slot (SAMPLE: one hit per key from C4). Returns "n hits, x s". */
    fun uploadKit(slot: Int, name: String, hits: List<FloatArray>, progress: (Float) -> Unit): String {
        val list = hits.take(ZONES)
        if (list.isEmpty()) throw IllegalStateException("The kit is empty")
        val out = java.io.ByteArrayOutputStream()
        val offs = IntArray(list.size)
        val ns = IntArray(list.size)
        var total = 0
        for ((k, h) in list.withIndex()) {
            val sm = toSlot(h)
            val enc = imaEncode(sm)
            offs[k] = out.size()
            ns[k] = sm.size
            out.write(enc)
            total += sm.size
        }
        val data = out.toByteArray()
        if (data.size > MAX_DATA) throw IllegalStateException(String.format(java.util.Locale.US,
            "The kit is %.1f s long; a slot holds %.1f s: start a new kit", total / SLOT_RATE.toDouble(), MAX_SECONDS))
        write(slot, kitHeader(name, offs, ns, data), data, progress)
        return String.format(java.util.Locale.US, "%d hits, %.1f s", list.size, total / SLOT_RATE.toDouble())
    }

    private fun write(slot: Int, hdr: ByteArray, data: ByteArray, progress: (Float) -> Unit) {
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
    }

    // ---------------- setting up a track on the device (editor protocol: INFO, DESC, SET, TRACK) ----------------

    private var gEng = -1                 // the global id of "ENG" (the engine of the selected track)
    private var gClk = -1                 // the global id of "CLK" (where its tempo comes from: INT, USB, TRS)
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
            if (r.size > 9) {
                when (readStr(r, 9).first) {
                    "ENG" -> gEng = id
                    "CLK" -> gClk = id
                }
            }
            if (gEng >= 0 && gClk >= 0) break
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

    /**
     * The device follows the app's MIDI clock over USB (its CLK: USB). Felucca-based firmware sends no clock of its
     * own, so the app leads; out of the box the device's CLK is INT and it ignores any clock. true: done.
     */
    fun followAppClock(): Boolean = try {
        learn()
        if (gClk < 0) false
        else {
            val r = req(3, byteArrayOf(1, gClk.toByte()) + v14(1))
            r.size >= 4 && ((r[2].toInt() and 0x7F) or ((r[3].toInt() and 0x7F) shl 7)) - 8192 == 1
        }
    } catch (ex: Exception) {
        false
    }

    fun close() {
        midi.sysexListener = null
    }

    @Suppress("unused")
    private fun clampSec(x: Double) = max(0.0, x)
}
