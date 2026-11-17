package com.example.fm1burger

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Mono audio as floats between -1 and 1. */
class PcmData(val rate: Int, val data: FloatArray)

class WavInfo(val rate: Int, val channels: Int, val bits: Int, val bytes: Long) {
    val seconds: Double
        get() = if (rate <= 0 || channels <= 0 || bits <= 0) 0.0
        else bytes.toDouble() / (rate.toDouble() * channels * (bits / 8))
}

object Wav {

    private fun le16(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, p: Int): Int = le16(b, p) or (le16(b, p + 2) shl 16)

    private fun tag(b: ByteArray, p: Int): String = String(b, p, 4, Charsets.US_ASCII)

    /** Reads only the header (fast). Used for the library list. */
    fun info(f: File): WavInfo? {
        return try {
            val buf = ByteArray(65536)
            val n = f.inputStream().use { it.read(buf) }
            if (n < 44) return null
            val b = buf.copyOf(n)
            if (tag(b, 0) != "RIFF" || tag(b, 8) != "WAVE") return null
            var pos = 12
            var ch = 0
            var rate = 0
            var bits = 0
            while (pos + 8 <= b.size) {
                val id = tag(b, pos)
                val size = le32(b, pos + 4).toLong() and 0xFFFFFFFFL
                val body = pos + 8
                if (id == "fmt " && body + 16 <= b.size) {
                    ch = le16(b, body + 2)
                    rate = le32(b, body + 4)
                    bits = le16(b, body + 14)
                } else if (id == "data") {
                    val avail = (f.length() - body).coerceAtLeast(0L)
                    return WavInfo(rate, ch, bits, min(size, avail))
                }
                val next = body.toLong() + size + (size and 1L)
                if (next > b.size) return null
                pos = next.toInt()
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Decodes 16 bit, 24 bit and 32 bit float WAV into mono floats. */
    fun parse(b: ByteArray): PcmData? {
        if (b.size < 44) return null
        if (tag(b, 0) != "RIFF" || tag(b, 8) != "WAVE") return null
        var pos = 12
        var fmt = 0
        var ch = 0
        var rate = 0
        var bits = 0
        while (pos + 8 <= b.size) {
            val id = tag(b, pos)
            val size = le32(b, pos + 4).toLong() and 0xFFFFFFFFL
            val body = pos + 8
            if (id == "fmt " && body + 16 <= b.size) {
                fmt = le16(b, body)
                ch = le16(b, body + 2)
                rate = le32(b, body + 4)
                bits = le16(b, body + 14)
            } else if (id == "data") {
                val avail = (b.size - body).toLong().coerceAtLeast(0L)
                val len = min(size, avail).toInt()
                return decode(b, body, len, fmt, ch, rate, bits)
            }
            val next = body.toLong() + size + (size and 1L)
            if (next > b.size) return null
            pos = next.toInt()
        }
        return null
    }

    private fun decode(
        b: ByteArray, off: Int, len: Int,
        fmt: Int, ch: Int, rate: Int, bits: Int
    ): PcmData? {
        if (rate <= 0 || ch < 1 || ch > 8) return null
        val isInt = (fmt == 1 || fmt == 0xFFFE) && (bits == 16 || bits == 24)
        val isFloat = (fmt == 3 || fmt == 0xFFFE) && bits == 32
        if (!isInt && !isFloat) return null
        val bytesPer = bits / 8
        val frame = bytesPer * ch
        val frames = len / frame
        val out = FloatArray(frames)
        var p = off
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until ch) {
                val v: Float = when {
                    isFloat -> java.lang.Float.intBitsToFloat(le32(b, p))
                    bits == 16 -> ((b[p + 1].toInt() shl 8) or (b[p].toInt() and 0xFF)) / 32768f
                    else -> ((b[p + 2].toInt() shl 16) or
                        ((b[p + 1].toInt() and 0xFF) shl 8) or
                        (b[p].toInt() and 0xFF)) / 8388608f
                }
                sum += v
                p += bytesPer
            }
            out[i] = sum / ch
        }
        return PcmData(rate, out)
    }

    fun read(f: File): PcmData? {
        return try {
            parse(f.readBytes())
        } catch (e: Exception) {
            null
        }
    }

    /** 16 bit mono WAV bytes. */
    fun toBytes(d: FloatArray, rate: Int): ByteArray {
        val dataLen = d.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + dataLen)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1.toShort())
        bb.putShort(1.toShort())
        bb.putInt(rate)
        bb.putInt(rate * 2)
        bb.putShort(2.toShort())
        bb.putShort(16.toShort())
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataLen)
        for (x in d) {
            val v = (x * 32767f).toInt().coerceIn(-32768, 32767)
            bb.putShort(v.toShort())
        }
        return bb.array()
    }

    fun write(f: File, d: FloatArray, rate: Int) {
        f.writeBytes(toBytes(d, rate))
    }

    fun peak(d: FloatArray, from: Int = 0, to: Int = d.size): Float {
        var p = 0f
        for (i in max(0, from) until min(to, d.size)) {
            val a = abs(d[i])
            if (a > p) p = a
        }
        return p
    }

    /** Linear resampler. When going down a simple average acts as an anti-alias filter. */
    fun resample(d: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || d.isEmpty() || from <= 0 || to <= 0) return d
        var src = d
        if (to < from) {
            val w = Math.round(from.toDouble() / to).toInt()
            if (w > 1) {
                val pre = FloatArray(d.size + 1)
                for (i in d.indices) pre[i + 1] = pre[i] + d[i]
                val sm = FloatArray(d.size)
                val h = w / 2
                for (i in d.indices) {
                    val a = max(0, i - h)
                    val e = min(d.size, i - h + w)
                    sm[i] = if (e > a) (pre[e] - pre[a]) / (e - a) else d[i]
                }
                src = sm
            }
        }
        val n = max(1, (src.size.toLong() * to / from).toInt())
        val out = FloatArray(n)
        val step = from.toDouble() / to
        val last = src.size - 1
        for (i in 0 until n) {
            val p = i * step
            val i0 = min(p.toInt(), last)
            val i1 = min(i0 + 1, last)
            val fr = (p - i0).toFloat()
            out[i] = src[i0] + (src[i1] - src[i0]) * fr
        }
        return out
    }
}
