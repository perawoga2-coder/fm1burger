package com.example.fm1burger

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The audio engine: 4 tracks with a 16 step sequencer, 8 pads and a preview voice.
 * Everything is mixed on one audio thread.
 */
class Engine {

    companion object {
        const val BLOCK = 256
    }

    val tracks = Array(Cfg.TRACKS) { Track(it) }
    val pads = Array(Cfg.PADS) { PadVoice() }
    val drumFx = FxChain()
    private val preview = PadVoice()

    @Volatile var bpmF = 120.0
    var bpm: Int
        get() = Math.round(bpmF).toInt()
        set(v) {
            bpmF = v.coerceIn(40, 240).toDouble()
        }

    @Volatile var playing = false
    @Volatile var paused = false
    @Volatile var pauseReq = false
    @Volatile var follow = false
    @Volatile var sendClock = false
    @Volatile var offsetMs = 0
    @Volatile var masterVol = 0.9f
    @Volatile var padVol = 0.8f
    @Volatile var curStep = -1
    @Volatile var masterPeak = 0f
    @Volatile var startReq = false
    @Volatile var stopReq = false
    @Volatile var midiOut: ((Int, Long) -> Unit)? = null

    @Volatile private var outDevice: AudioDeviceInfo? = null
    @Volatile private var restartOut = false
    @Volatile private var running = false
    private var thread: Thread? = null
    val pendingTicks = AtomicInteger(0)

    private var tickCount = 0L
    private var tickAcc = 0.0
    private var beatPos = 0.0
    private var latencyNs = 0L

    // ---------------- control (called from the UI / MIDI threads) ----------------

    fun play() {
        startReq = true
    }

    fun stop() {
        stopReq = true
    }

    /** Pause (freeze the sequencer where it is) or resume. Only works while playing. */
    fun pause() {
        pauseReq = true
    }

    fun setOutput(d: AudioDeviceInfo?) {
        outDevice = d
        restartOut = true
    }

    fun padHit(i: Int, vel: Float) {
        if (i in pads.indices) pads[i].req = vel.coerceIn(0.05f, 1f)
    }

    fun previewPlay(d: FloatArray) {
        preview.data = d
        preview.req = 1f
    }

    fun previewStop() {
        preview.req = -1f
    }

    fun audition(i: Int) {
        tracks[i].auditionReq = true
    }

    fun setSample(i: Int, name: String, d: FloatArray?) {
        val t = tracks[i]
        t.playing = false
        t.sampleName = name
        t.data = d
    }

    fun setPad(i: Int, name: String, d: FloatArray?) {
        val p = pads[i]
        p.playing = false
        p.name = name
        p.data = d
    }

    fun start() {
        if (running) return
        running = true
        val th = Thread { loop() }
        th.isDaemon = true
        th.name = "burger-audio"
        th.start()
        thread = th
    }

    fun shutdown() {
        running = false
        try {
            thread?.join(600)
        } catch (e: Exception) {
        }
    }

    // ---------------- audio thread ----------------

    private fun makeOut(): AudioTrack? {
        return try {
            val minBuf = AudioTrack.getMinBufferSize(
                Cfg.SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bytes = max(minBuf, BLOCK * 4 * 6)
            val b = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(Cfg.SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= 26) {
                b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            val t = b.build()
            val d = outDevice
            if (d != null) t.setPreferredDevice(d)
            t.play()
            latencyNs = t.bufferSizeInFrames.toLong() * 1_000_000_000L / Cfg.SR
            t
        } catch (e: Exception) {
            null
        }
    }

    private fun loop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Exception) {
        }
        val out = ShortArray(BLOCK * 2)
        val tl = FloatArray(Cfg.TRACKS)
        val tr = FloatArray(Cfg.TRACKS)
        val tpk = FloatArray(Cfg.TRACKS)
        var at = makeOut()
        while (running) {
            if (restartOut) {
                restartOut = false
                try {
                    at?.stop()
                    at?.release()
                } catch (e: Exception) {
                }
                at = makeOut()
            }
            val a = at
            if (a == null) {
                try {
                    Thread.sleep(300)
                } catch (e: Exception) {
                }
                at = makeOut()
                continue
            }

            val t0 = System.nanoTime()
            handleRequests(t0)

            for (i in 0 until Cfg.TRACKS) {
                val t = tracks[i]
                val ang = (t.pan.coerceIn(-1f, 1f) + 1f) * 0.7853982f
                tl[i] = t.volume * cos(ang)
                tr[i] = t.volume * sin(ang)
                tpk[i] = 0f
            }

            if (follow && playing && !paused) {
                var n = pendingTicks.getAndSet(0)
                while (n > 0) {
                    onTick(0, t0)
                    n--
                }
            } else {
                pendingTicks.set(0)
            }

            val bpmNow = bpmF
            val spt = Cfg.SR * 60.0 / (bpmNow * 24.0)
            val beatInc = 1.0 / (Cfg.SR * 60.0 / bpmNow)
            val mv = masterVol
            val pv = padVol
            var mpk = 0f

            for (f in 0 until BLOCK) {
                if (playing && !paused) {
                    if (!follow) {
                        tickAcc += 1.0
                        if (tickAcc >= spt) {
                            tickAcc -= spt
                            onTick(f, t0)
                        }
                    }
                    beatPos += beatInc
                }
                var l = 0f
                var r = 0f
                for (i in 0 until Cfg.TRACKS) {
                    val t = tracks[i]
                    var s = renderTrack(t)
                    s = t.fx.process(s, bpmNow, beatPos)
                    if (!t.mute) {
                        l += s * tl[i]
                        r += s * tr[i]
                        val ab = abs(s * t.volume)
                        if (ab > tpk[i]) tpk[i] = ab
                    }
                }
                var pm = 0f
                for (p in pads) {
                    pm += renderPad(p) * (if (p.mute) 0f else p.volume)
                }
                pm = drumFx.process(pm * pv, bpmNow, beatPos)
                l += pm
                r += pm
                val pr = renderPad(preview) * 0.8f
                l += pr
                r += pr
                l = softClip(l * mv)
                r = softClip(r * mv)
                val al = abs(l)
                if (al > mpk) mpk = al
                val ar = abs(r)
                if (ar > mpk) mpk = ar
                out[f * 2] = (l * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                out[f * 2 + 1] = (r * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }

            for (i in 0 until Cfg.TRACKS) tracks[i].peak = tpk[i]
            masterPeak = mpk

            val w = a.write(out, 0, out.size)
            if (w < 0) {
                outDevice = null
                restartOut = true
                try {
                    Thread.sleep(100)
                } catch (e: Exception) {
                }
            }
        }
        try {
            at?.stop()
            at?.release()
        } catch (e: Exception) {
        }
    }

    private fun send(b: Int, target: Long) {
        val m = midiOut
        if (m != null) m(b, target)
    }

    private fun handleRequests(t0: Long) {
        val off = offsetMs * 1_000_000L
        if (stopReq) {
            stopReq = false
            if (playing && !follow && sendClock) send(0xFC, t0 + latencyNs + off)
            playing = false
            paused = false
            curStep = -1
            for (t in tracks) t.playing = false
        }
        if (startReq) {
            startReq = false
            playing = true
            paused = false
            tickCount = 0L
            tickAcc = Cfg.SR * 60.0 / (bpmF * 24.0)
            beatPos = 0.0
            curStep = -1
            for (t in tracks) t.playing = false
            if (!follow && sendClock) send(0xFA, t0 + latencyNs + off)
        }
        if (pauseReq) {
            pauseReq = false
            if (playing) {
                paused = !paused
                if (!follow && sendClock) send(if (paused) 0xFC else 0xFB, t0 + latencyNs + off)
            }
        }
        for (t in tracks) {
            if (t.auditionReq) {
                t.auditionReq = false
                if (t.playing) t.playing = false else trigger(t, 0, 1f)
            }
        }
        for (p in pads) padRequest(p)
        padRequest(preview)
    }

    private fun padRequest(p: PadVoice) {
        val q = p.req
        if (q == 0f) return
        p.req = 0f
        if (q < 0f) {
            p.playing = false
        } else {
            p.gain = q
            p.pos = 0
            p.playing = p.data != null
        }
    }

    private fun onTick(frame: Int, t0: Long) {
        beatPos = tickCount / 24.0
        if (!follow && sendClock) {
            send(
                0xF8,
                t0 + frame * 1_000_000_000L / Cfg.SR + latencyNs + offsetMs * 1_000_000L
            )
        }
        if (tickCount % 6L == 0L) {
            val s = ((tickCount / 6L) % Cfg.STEPS).toInt()
            curStep = s
            stepTrigger(s)
        }
        tickCount++
    }

    private fun stepTrigger(s: Int) {
        for (p in pads) {
            if (p.on[s] && p.data != null) {
                p.gain = p.lvl[s]
                p.pos = 0
                p.playing = true
            }
        }
        for (t in tracks) {
            if (t.data == null) continue
            if (t.loop) {
                if (s == 0) trigger(t, 0, 1f)
            } else {
                val st = t.steps[s]
                if (st.on) trigger(t, st.pitch, st.level)
            }
        }
    }

    private fun trigger(t: Track, semis: Int, level: Float) {
        val d = t.data ?: return
        val n = d.size
        if (n < 2) return
        val a = (t.start * n).toInt().coerceIn(0, n - 1)
        val e = (t.end * n).toInt().coerceIn(a + 1, n)
        t.vStart = a.toDouble()
        t.vEnd = e.toDouble()
        t.rate = 2.0.pow((t.pitch + semis) / 12.0)
        t.gain = level
        if (t.reverse) {
            t.dir = -1
            t.pos = (e - 1).toDouble()
        } else {
            t.dir = 1
            t.pos = a.toDouble()
        }
        t.playing = true
    }

    private fun renderTrack(t: Track): Float {
        if (!t.playing) return 0f
        val d = t.data
        if (d == null) {
            t.playing = false
            return 0f
        }
        var p = t.pos
        val i = p.toInt()
        if (p < 0.0 || i >= d.size) {
            t.playing = false
            return 0f
        }
        val fr = (p - i).toFloat()
        val s0 = d[i]
        val s1 = if (i + 1 < d.size) d[i + 1] else 0f
        val s = (s0 + (s1 - s0) * fr) * t.gain
        p += t.dir * t.rate
        if (t.dir > 0) {
            if (p >= t.vEnd) {
                if (t.loop) {
                    p = t.vStart + (p - t.vEnd)
                    if (p >= t.vEnd) p = t.vStart
                } else {
                    t.playing = false
                }
            }
        } else {
            if (p < t.vStart) {
                if (t.loop) {
                    p = t.vEnd - 1.0 - (t.vStart - p)
                    if (p < t.vStart) p = t.vEnd - 1.0
                } else {
                    t.playing = false
                }
            }
        }
        t.pos = p
        return s
    }

    private fun renderPad(p: PadVoice): Float {
        if (!p.playing) return 0f
        val d = p.data
        if (d == null || p.pos >= d.size) {
            p.playing = false
            return 0f
        }
        val s = d[p.pos] * p.gain
        p.pos++
        return s
    }

    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a < 0.8f) return x
        val y = 0.8f + 0.2f * tanh((a - 0.8f) / 0.2f)
        return if (x < 0f) -y else y
    }
}
