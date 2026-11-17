package com.example.fm1burger

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.tanh
import org.json.JSONObject

/**
 * Records everything you hear (the final stereo mix) into a WAV file while you play.
 * The audio is written to the file all the time, so there is no length limit except free storage.
 */
class MixRecorder {

    @Volatile var recording = false
        private set
    @Volatile var autoStopped = false
    @Volatile var frames = 0L
        private set
    @Volatile var dropped = 0
        private set
    @Volatile var bytesWritten = 0L
        private set
    var file: File? = null
        private set

    private val q = ArrayBlockingQueue<ShortArray>(800)
    private var thread: Thread? = null
    @Volatile private var writing = false

    /** Starts a new recording in [dir]. Returns the file, or null if it could not start. */
    fun start(dir: File): File? {
        if (recording) return file
        try {
            dir.mkdirs()
            if (dir.usableSpace < 50L * 1024 * 1024) return null
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val f = File(dir, "Mix_$stamp.wav")
            q.clear()
            frames = 0L
            dropped = 0
            bytesWritten = 0L
            autoStopped = false
            file = f
            writing = true
            val th = Thread { writer(f, dir) }
            th.isDaemon = true
            th.name = "burger-mixrec"
            th.start()
            thread = th
            recording = true
            return f
        } catch (e: Exception) {
            recording = false
            writing = false
            return null
        }
    }

    /** Stops and finishes the file. Returns the file (or null if nothing was recorded). */
    fun stop(): File? {
        recording = false
        writing = false
        try {
            thread?.join(5000)
        } catch (e: Exception) {
        }
        thread = null
        val f = file
        return if (f != null && f.exists()) f else null
    }

    /** Called by the audio thread with every finished block (interleaved stereo 16 bit). */
    fun feed(out: ShortArray) {
        if (!recording) return
        if (!q.offer(out.copyOf())) dropped++
        frames += (out.size / 2).toLong()
    }

    private fun header(dataLen: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray())
        b.putInt((36L + dataLen).toInt())
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray())
        b.putInt(16)
        b.putShort(1.toShort())
        b.putShort(2.toShort())
        b.putInt(Cfg.SR)
        b.putInt(Cfg.SR * 4)
        b.putShort(4.toShort())
        b.putShort(16.toShort())
        b.put("data".toByteArray())
        b.putInt(dataLen.toInt())
        return b.array()
    }

    private fun writer(f: File, dir: File) {
        var raf: RandomAccessFile? = null
        try {
            raf = RandomAccessFile(f, "rw")
            raf.write(header(0L))
            var total = 0L
            var sinceCheck = 0
            var bb = ByteArray(Engine.BLOCK * 4)
            while (true) {
                val b = q.poll(200, TimeUnit.MILLISECONDS)
                if (b == null) {
                    if (!writing) break
                    continue
                }
                if (bb.size != b.size * 2) bb = ByteArray(b.size * 2)
                var j = 0
                for (s in b) {
                    val v = s.toInt()
                    bb[j++] = (v and 0xFF).toByte()
                    bb[j++] = (v shr 8).toByte()
                }
                raf.write(bb, 0, j)
                total += j.toLong()
                bytesWritten = total
                sinceCheck++
                if (sinceCheck > 1000) {
                    sinceCheck = 0
                    if (dir.usableSpace < 20L * 1024 * 1024) {
                        autoStopped = true
                        recording = false
                        writing = false
                    }
                }
            }
            raf.seek(0L)
            raf.write(header(total))
        } catch (e: Exception) {
            autoStopped = true
            recording = false
        } finally {
            try {
                raf?.close()
            } catch (e: Exception) {
            }
        }
    }
}

/**
 * The 5th channel: your voice from the microphone, live, through a vocal chain
 * (low cut, compressor, room reverb, echo) into the mix.
 * Use headphones, or the speaker sound will feed back into the microphone.
 */
class VocalChannel {

    companion object {
        const val RING = 8192
        const val MASK = 8191L
        val PRESET_NAMES = listOf("Normal", "Bathroom", "Hall", "Mountain top")
        private val COMB = intArrayOf(1116, 1188, 1277, 1356)
        private const val MAX_TAKE = 180 * Cfg.SR
    }

    // sound settings, all 0..1
    @Volatile var micGain = 0.35f
    @Volatile var volume = 0.8f
    @Volatile var lowCut = 0.25f
    @Volatile var comp = 0.35f
    @Volatile var wet = 0.15f
    @Volatile var decay = 0.3f
    @Volatile var bright = 0.6f
    @Volatile var echo = 0f
    @Volatile var echoTime = 0.4f
    @Volatile var echoFb = 0.3f
    @Volatile var mute = false
    @Volatile var preset = 0
    @Volatile var roomScale = 0.7f
    @Volatile var preMs = 8f

    @Volatile var on = false
        private set
    @Volatile var inLevel = 0f
    @Volatile var taking = false
        private set
    @Volatile var takeFull = false
        private set

    @Volatile var outL = 0f
    @Volatile var outR = 0f

    private val ring = FloatArray(RING)
    @Volatile private var wr = 0L
    private var rd = 0L
    private var rec: AudioRecord? = null
    private var thread: Thread? = null

    private var take = FloatArray(Cfg.SR * 10)
    @Volatile private var takeLen = 0

    // audio thread state
    private var quiet = 0
    private var cnt = 0
    private var hpA = 0.05f
    private var hpLp = 0f
    private var env = 0f
    private val pd = FloatArray(8820)
    private var pi = 0
    private val cb = Array(8) { FloatArray(3300) }
    private val ci = IntArray(8)
    private val cs = FloatArray(8)
    private val ap = Array(4) { FloatArray(700) }
    private val api = IntArray(4)
    private val apLen = intArrayOf(556, 441, 579, 464)
    private val eb = FloatArray(66200)
    private var ei = 0
    private var efs = 0f

    fun applyPreset(i: Int) {
        preset = i
        when (i) {
            0 -> {
                roomScale = 0.7f; preMs = 8f; wet = 0.15f; decay = 0.3f; bright = 0.6f
                echo = 0f; echoTime = 0.4f; echoFb = 0.3f
            }
            1 -> {
                roomScale = 0.45f; preMs = 4f; wet = 0.38f; decay = 0.55f; bright = 0.85f
                echo = 0f; echoTime = 0.4f; echoFb = 0.3f
            }
            2 -> {
                roomScale = 1.8f; preMs = 28f; wet = 0.35f; decay = 0.85f; bright = 0.5f
                echo = 0f; echoTime = 0.4f; echoFb = 0.3f
            }
            else -> {
                roomScale = 2.0f; preMs = 40f; wet = 0.18f; decay = 0.6f; bright = 0.45f
                echo = 0.55f; echoTime = 0.45f; echoFb = 0.5f
            }
        }
    }

    // ---------------- microphone ----------------

    /** Starts the live microphone. Returns an error text, or null when it works. */
    fun start(): String? {
        if (on) return null
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                Cfg.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return "This device cannot record at 44100 Hz"
            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC, Cfg.SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, 4096) * 2
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                return "Could not open the microphone. Close other apps that use it."
            }
            rec = ar
            wr = 0L
            rd = 0L
            quiet = 0
            on = true
            ar.startRecording()
            val th = Thread { micLoop(ar) }
            th.isDaemon = true
            th.name = "burger-vocal"
            th.start()
            thread = th
        } catch (e: SecurityException) {
            on = false
            return "No microphone permission. Allow the microphone for this app."
        } catch (e: Exception) {
            on = false
            return "Could not open the microphone"
        }
        return null
    }

    fun stop() {
        on = false
        taking = false
        try {
            thread?.join(600)
        } catch (e: Exception) {
        }
        thread = null
    }

    private fun gainLin(): Float = 0.5f + micGain * 7f

    private fun micLoop(ar: AudioRecord) {
        val buf = ShortArray(128)
        while (on) {
            val n = ar.read(buf, 0, buf.size)
            if (n < 0) break
            if (n == 0) continue
            val w = wr
            for (i in 0 until n) ring[((w + i) and MASK).toInt()] = buf[i] / 32768f
            wr = w + n
            if (taking) appendTake(buf, n)
        }
        try {
            ar.stop()
        } catch (e: Exception) {
        }
        try {
            ar.release()
        } catch (e: Exception) {
        }
        on = false
    }

    private fun appendTake(buf: ShortArray, n: Int) {
        val len = takeLen
        if (len + n > MAX_TAKE) {
            takeFull = true
            taking = false
            return
        }
        if (len + n > take.size) take = take.copyOf(minOf(take.size * 2, MAX_TAKE))
        val g = gainLin()
        for (i in 0 until n) take[len + i] = (buf[i] / 32768f * g).coerceIn(-1f, 1f)
        takeLen = len + n
    }

    /** Starts saving the dry voice (no effects) as a take. The microphone must be on. */
    fun startTake(): Boolean {
        if (!on) return false
        takeLen = 0
        takeFull = false
        taking = true
        return true
    }

    fun takeSeconds(): Double = takeLen.toDouble() / Cfg.SR

    /** Ends the take and returns the recorded voice, or null if it was too short. */
    fun stopTake(): FloatArray? {
        taking = false
        try {
            Thread.sleep(40)
        } catch (e: Exception) {
        }
        val n = takeLen
        if (n < Cfg.SR / 4) return null
        return take.copyOf(n)
    }

    private fun pull(): Float {
        val w = wr
        var r = rd
        if (w - r > 3000L) r = w - 512L
        if (r < w) {
            val v = ring[(r and MASK).toInt()]
            rd = r + 1L
            return v
        }
        return 0f
    }

    // ---------------- audio thread: the vocal chain ----------------

    fun render() {
        val live = on
        if (!live) {
            quiet++
            if (quiet > Cfg.SR * 6) {
                outL = 0f
                outR = 0f
                return
            }
        } else {
            quiet = 0
        }
        var x = if (live) pull() * gainLin() else 0f
        val ax = abs(x)
        inLevel = max(ax, inLevel * 0.9995f)

        // low cut
        if ((cnt and 63) == 0) {
            val fc = 40.0 + lowCut * 360.0
            hpA = (1.0 - exp(-2.0 * PI * fc / Cfg.SR)).toFloat()
        }
        cnt++
        hpLp += hpA * (x - hpLp)
        x -= hpLp

        // compressor
        val a = abs(x)
        env += (a - env) * (if (a > env) 0.01f else 0.0008f)
        val thr = 0.25f
        val ratio = 1f + comp * 5f
        var g = 1f
        if (env > thr) g = (thr + (env - thr) / ratio) / env
        x *= g * (1f + comp * 0.8f)

        // room reverb
        pd[pi] = x
        val pre = (preMs * Cfg.SR / 1000f).toInt().coerceIn(0, pd.size - 1)
        val xd = pd[(pi - pre + pd.size) % pd.size]
        pi++
        if (pi >= pd.size) pi = 0
        val rin = xd * 0.12f
        val fb = 0.7f + decay * 0.27f
        val damp = (1f - bright) * 0.8f
        val scale = roomScale
        var sl = 0f
        var sr = 0f
        for (k in 0 until 8) {
            val base = COMB[k % 4] + (if (k >= 4) 23 else 0)
            val len = (base * scale).toInt().coerceIn(64, 3200)
            var idx = ci[k]
            if (idx >= len) idx = 0
            val buf = cb[k]
            val o = buf[idx]
            cs[k] = o * (1f - damp) + cs[k] * damp
            buf[idx] = rin + cs[k] * fb
            idx++
            if (idx >= len) idx = 0
            ci[k] = idx
            if (k < 4) sl += o else sr += o
        }
        for (k in 0 until 4) {
            val v = if (k < 2) sl else sr
            val len = apLen[k]
            var idx = api[k]
            if (idx >= len) idx = 0
            val b = ap[k][idx]
            val o = -v + b
            ap[k][idx] = v + b * 0.5f
            idx++
            if (idx >= len) idx = 0
            api[k] = idx
            if (k < 2) sl = o else sr = o
        }

        // echo
        val et = ((0.1f + echoTime * 0.9f) * Cfg.SR).toInt().coerceIn(2000, eb.size - 2)
        val ed = eb[(ei - et + eb.size) % eb.size]
        efs += (ed - efs) * 0.4f
        eb[ei] = x + efs * echoFb
        ei++
        if (ei >= eb.size) ei = 0
        val ec = ed * echo

        val wv = wet * 2f
        var l = x + sl * wv + ec
        var r = x + sr * wv + ec
        l = tanh(l * 0.9f)
        r = tanh(r * 0.9f)
        if (mute) {
            outL = 0f
            outR = 0f
        } else {
            outL = l * volume
            outR = r * volume
        }
    }
}

/** Saves and loads the vocal channel settings. */
object VocalSave {
    fun save(f: File, v: VocalChannel) {
        try {
            val o = JSONObject()
            o.put("micGain", v.micGain.toDouble())
            o.put("volume", v.volume.toDouble())
            o.put("lowCut", v.lowCut.toDouble())
            o.put("comp", v.comp.toDouble())
            o.put("wet", v.wet.toDouble())
            o.put("decay", v.decay.toDouble())
            o.put("bright", v.bright.toDouble())
            o.put("echo", v.echo.toDouble())
            o.put("echoTime", v.echoTime.toDouble())
            o.put("echoFb", v.echoFb.toDouble())
            o.put("mute", v.mute)
            o.put("preset", v.preset)
            o.put("roomScale", v.roomScale.toDouble())
            o.put("preMs", v.preMs.toDouble())
            f.writeText(o.toString())
        } catch (ex: Exception) {
        }
    }

    fun load(f: File, v: VocalChannel) {
        try {
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            v.micGain = o.optDouble("micGain", 0.35).toFloat()
            v.volume = o.optDouble("volume", 0.8).toFloat()
            v.lowCut = o.optDouble("lowCut", 0.25).toFloat()
            v.comp = o.optDouble("comp", 0.35).toFloat()
            v.wet = o.optDouble("wet", 0.15).toFloat()
            v.decay = o.optDouble("decay", 0.3).toFloat()
            v.bright = o.optDouble("bright", 0.6).toFloat()
            v.echo = o.optDouble("echo", 0.0).toFloat()
            v.echoTime = o.optDouble("echoTime", 0.4).toFloat()
            v.echoFb = o.optDouble("echoFb", 0.3).toFloat()
            v.mute = o.optBoolean("mute", false)
            v.preset = o.optInt("preset", 0).coerceIn(0, 3)
            v.roomScale = o.optDouble("roomScale", 0.7).toFloat()
            v.preMs = o.optDouble("preMs", 8.0).toFloat()
        } catch (ex: Exception) {
        }
    }
}
