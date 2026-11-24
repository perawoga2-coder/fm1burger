package com.example.fm1burger

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlin.math.abs
import kotlin.math.max

/**
 * 3.54 MERGED IN BURGER: the FM-1's sound, taken from its USB audio, played in the app's own mix.
 *
 * A thread reads the USB input into a ring; the audio thread takes it out one frame at a time (render), at the
 * input's rate against the engine's 44.1 kHz. The FM-1 and the tablet each have their own clock, so the ring would
 * slowly fill or run dry: the reading speed follows how full it is (half a percent at most, not heard), and the ring
 * is kept about 20 ms full. A gap in the input (the cable out) fades to silence and starts again by itself.
 */
class Fm1Monitor {

    companion object {
        private const val RING = 16384          // frames
        private const val MASK = RING - 1
    }

    @Volatile var on = false
        private set
    /** The input's level now, 0..1 (the louder side), for the meter. */
    @Volatile var level = 0f
        private set
    /** System.nanoTime() of the last frames that came in (0: none yet). */
    @Volatile var lastDataNs = 0L
        private set
    /** Why it is not running, for the box's status line ("" when fine). */
    @Volatile var error = ""
        private set
    @Volatile var outL = 0f
    @Volatile var outR = 0f

    private val ringL = FloatArray(RING)
    private val ringR = FloatArray(RING)
    @Volatile private var wr = 0L
    @Volatile private var inRate = 44100
    @Volatile private var resetReq = false
    private var thread: Thread? = null

    // audio thread only
    private var rd = 0L
    private var frac = 0.0
    private var primed = false
    private var fillAvg = 0.0
    private var gain = 0f

    /** True while frames came in during the last 300 ms: only then is the FM-1's own output muted. */
    fun alive(): Boolean = on && lastDataNs != 0L && System.nanoTime() - lastDataNs < 300_000_000L

    /** Starts reading [dev] (the FM-1's USB audio input). Returns an error text, or null when it runs. */
    fun start(dev: AudioDeviceInfo): String? {
        if (on) return null
        val rates = dev.sampleRates
        val rate = when {
            rates.isEmpty() || 44100 in rates -> 44100
            48000 in rates -> 48000
            else -> rates.maxOrNull() ?: 44100
        }
        val chs = dev.channelCounts
        val stereo = chs.isEmpty() || chs.any { it >= 2 }
        val mask = if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            error = "USB input: $rate Hz not supported"
            return error
        }
        var ar: AudioRecord? = null
        // the raw input first (no voice processing on it); the plain microphone source where that is not there
        val sources = if (Build.VERSION.SDK_INT >= 24)
            intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)
        else intArrayOf(MediaRecorder.AudioSource.MIC)
        for (src in sources) {
            try {
                val r = AudioRecord.Builder()
                    .setAudioSource(src)
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                    )
                    .setBufferSizeInBytes(max(minBuf, 256 * (if (stereo) 4 else 2)) * 2)
                    .build()
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    ar = r
                    break
                }
                r.release()
            } catch (e: SecurityException) {
                error = "No microphone permission (Android asks it for USB audio too)"
                return error
            } catch (e: Exception) {
            }
        }
        if (ar == null) {
            error = "Could not open the USB audio input"
            return error
        }
        try {
            ar.setPreferredDevice(dev)
            ar.startRecording()
        } catch (e: Exception) {
            try {
                ar.release()
            } catch (e2: Exception) {
            }
            error = "Could not start the USB audio input"
            return error
        }
        inRate = rate
        lastDataNs = 0L
        level = 0f
        error = ""
        resetReq = true
        on = true
        val th = Thread { loop(ar, stereo) }
        th.isDaemon = true
        th.name = "burger-fm1-in"
        th.start()
        thread = th
        return null
    }

    fun stop() {
        if (!on && thread == null) return
        on = false
        try {
            thread?.join(600)
        } catch (e: Exception) {
        }
        thread = null
        level = 0f
    }

    private fun loop(ar: AudioRecord, stereo: Boolean) {
        val nch = if (stereo) 2 else 1
        val buf = ShortArray(128 * nch)
        var bad = 0
        while (on) {
            val n = try {
                ar.read(buf, 0, buf.size)
            } catch (e: Exception) {
                -1
            }
            if (n < 0) {
                error = "The USB audio input stopped"
                break
            }
            if (n == 0) {
                if (++bad > 200) {
                    error = "The USB audio input gives nothing"
                    break
                }
                try {
                    Thread.sleep(5)
                } catch (e: Exception) {
                }
                continue
            }
            bad = 0
            val fr = n / nch
            val w = wr
            var pk = 0f
            for (i in 0 until fr) {
                val l = buf[i * nch] / 32768f
                val r = if (stereo) buf[i * nch + 1] / 32768f else l
                val k = ((w + i) and MASK.toLong()).toInt()
                ringL[k] = l
                ringR[k] = r
                val a = max(abs(l), abs(r))
                if (a > pk) pk = a
            }
            wr = w + fr
            lastDataNs = System.nanoTime()
            val lv = level * 0.93f
            level = if (pk > lv) pk else lv
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
        level = 0f
    }

    /** Audio thread: one frame of the engine's output (44.1 kHz) into outL / outR. */
    fun render() {
        if (!on) {
            if (gain > 0f || outL != 0f || outR != 0f) {
                gain = 0f
                outL = 0f
                outR = 0f
            }
            primed = false
            return
        }
        if (resetReq) {
            resetReq = false
            primed = false
        }
        val target = inRate / 50                        // about 20 ms in the ring
        val w = wr
        if (!primed) {
            if (w < target) {
                outL = 0f
                outR = 0f
                return
            }
            rd = w - target
            frac = 0.0
            fillAvg = target.toDouble()
            gain = 0f
            primed = true
        }
        val fill = (w - rd).toInt()
        if (fill < 2 || fill > RING - 64) {             // ran dry (the cable out), or far behind: start again
            primed = false
            outL *= 0.5f
            outR *= 0.5f
            return
        }
        fillAvg += (fill - fillAvg) * 0.0005
        val steer = ((fillAvg - target) / target * 0.02).coerceIn(-0.005, 0.005)
        val k0 = (rd and MASK.toLong()).toInt()
        val k1 = ((rd + 1) and MASK.toLong()).toInt()
        val f = frac.toFloat()
        if (gain < 1f) gain = (gain + 0.002f).coerceAtMost(1f)
        outL = (ringL[k0] + (ringL[k1] - ringL[k0]) * f) * gain
        outR = (ringR[k0] + (ringR[k1] - ringR[k0]) * f) * gain
        frac += inRate / Cfg.SR.toDouble() * (1.0 + steer)
        while (frac >= 1.0) {
            frac -= 1.0
            rd++
        }
    }
}
