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

        // PREPARE mode: what plays alone (0-3 = sample track 1-4)
        const val PREP_NONE = -1
        const val PREP_PADS = 10
        const val PREP_BASS = 11
        const val PREP_SYNTH = 12
        const val PREP_RHYTHM = 13
        const val PREP_RIFF = 14
        const val PREP_AMBIENT = 15

        fun prepName(p: Int): String = when (p) {
            in 0 until Cfg.TRACKS -> "Track ${p + 1}"
            PREP_PADS -> "Drums"
            PREP_BASS -> "Bassline"
            PREP_SYNTH -> "Analog synth"
            PREP_RHYTHM -> "Rhythm guitar"
            PREP_RIFF -> "Riff guitar"
            PREP_AMBIENT -> "New Ambient"
            else -> ""
        }
    }

    /**
     * PREPARE: while >= 0 only that part sounds and is stepped. Nothing else is triggered,
     * no MIDI Start / clock goes out, song parts do not change and nothing is recorded,
     * so a part can be prepared before it goes into the master. -1 = normal.
     */
    @Volatile var prepare = PREP_NONE

    val tracks = Array(Cfg.TRACKS) { Track(it) }
    val pads = Array(Cfg.PADS) { PadVoice() }
    val drumFx = FxChain()
    val bass = Bassline()
    val analog = AnalogSynth()
    val analogSeq = AnalogSeq(analog)
    val vocal = VocalChannel()
    val ambient = NewAmbient()
    val rhythmGtr = MetalGuitar(false)
    val riffGtr = MetalGuitar(true)
    val drumHeavy = DrumHeavy()
    val mixRec = MixRecorder()
    val bank = PartBank(this)
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
    @Volatile var midiOut: ((ByteArray, Long) -> Unit)? = null

    /** LEAD: keep sending MIDI clock while stopped, so followers lock the tempo before Start (standard). */
    @Volatile var clockIdle = true
    /** FOLLOW: Continue was received (play on from the Song Position Pointer). */
    @Volatile var continueReq = false
    /** FOLLOW: the last Song Position Pointer (16th notes), or -1. */
    @Volatile private var sppReq = -1

    // FOLLOW: arrival times of incoming clock ticks (one producer: the MIDI thread)
    private val tickTimes = LongArray(256)
    @Volatile private var tickHead = 0
    @Volatile private var tickTail = 0
    private val tickFrames = IntArray(64)
    private var idleAcc = 0.0

    /** The final mix of the last block as floats (before 16 bit), for the 24 bit / float recorder. */
    private val fout = FloatArray(BLOCK * 2)
    private val tb = FloatArray(Cfg.TRACKS)

    @Volatile private var outDevice: AudioDeviceInfo? = null
    @Volatile private var restartOut = false
    @Volatile private var running = false
    private var thread: Thread? = null
    val pendingTicks = AtomicInteger(0)

    /** MIDI thread: a clock tick arrived at time ns (FOLLOW). */
    fun followTick(ns: Long) {
        val h = tickHead
        val next = (h + 1) and 255
        if (next == tickTail) return      // full: drop (cannot happen at sane tempos)
        tickTimes[h] = ns
        tickHead = next
    }

    /** MIDI thread: Song Position Pointer received (FOLLOW), in 16th notes. */
    fun songPosition(sixteenths: Int) {
        sppReq = sixteenths.coerceAtLeast(0)
    }

    /** How many audio blocks failed and were replaced by silence (for the status line). */
    @Volatile var errors = 0

    private var tickCount = 0L
    private var tickAcc = 0.0
    private var beatPos = 0.0
    private var latencyNs = 0L

    // ---------------- control (called from the UI / MIDI threads) ----------------

    fun play() {
        prepare = PREP_NONE
        startReq = true
    }

    /** Plays only this part (PREPARE mode), from the start of the bar. */
    fun playPrepare(part: Int) {
        prepare = part
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
            try {
                renderBlock(t0, out, tl, tr, tpk)
            } catch (ex: Throwable) {
                // never let one bad block kill the audio thread: play silence and carry on
                errors++
                java.util.Arrays.fill(out, 0.toShort())
                for (t in tracks) t.playing = false
            }

            val w = try {
                a.write(out, 0, out.size)
            } catch (ex: Exception) {
                -1
            }
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

    /** Mixes one block of audio into out. Runs on the audio thread. */
    private fun renderBlock(t0: Long, out: ShortArray, tl: FloatArray, tr: FloatArray, tpk: FloatArray) {
            handleRequests(t0)

            for (i in 0 until Cfg.TRACKS) {
                val t = tracks[i]
                val ang = (t.pan.coerceIn(-1f, 1f) + 1f) * 0.7853982f
                tl[i] = t.volume * cos(ang)
                tr[i] = t.volume * sin(ang)
                tpk[i] = 0f
            }

            // FOLLOW: place every clock tick at its own sample, one block late, so the spacing stays exact
            var nTicks = 0
            if (follow && playing && !paused && prepare < 0) {
                val blockNs = BLOCK * 1_000_000_000L / Cfg.SR
                val base = t0 - blockNs
                while (tickTail != tickHead) {
                    val ts = tickTimes[tickTail]
                    if (ts > t0) break
                    tickTail = (tickTail + 1) and 255
                    val fr = ((ts - base) * Cfg.SR / 1_000_000_000L).toInt().coerceIn(0, BLOCK - 1)
                    if (nTicks < tickFrames.size) tickFrames[nTicks++] = fr
                }
            } else {
                tickTail = tickHead
            }
            pendingTicks.set(0)
            var nextTick = 0

            val bpmNow = bpmF
            val spt = Cfg.SR * 60.0 / (bpmNow * 24.0)

            // LEAD while stopped or paused: the clock keeps running (no Start), at the current tempo
            if (!follow && sendClock && clockIdle && (!playing || paused || prepare >= 0)) {
                idleAcc += BLOCK.toDouble()
                while (idleAcc >= spt) {
                    idleAcc -= spt
                    val fr = (BLOCK - idleAcc).toInt().coerceIn(0, BLOCK - 1)
                    send(0xF8, t0 + fr * 1_000_000_000L / Cfg.SR + latencyNs + offsetMs * 1_000_000L)
                }
            } else {
                idleAcc = 0.0
            }
            val beatInc = 1.0 / (Cfg.SR * 60.0 / bpmNow)
            val mv = masterVol
            val pv = padVol
            var mpk = 0f

            analog.bpm = bpmNow
            for (i in 0 until Cfg.TRACKS) tb[i] = 10f.pow(tracks[i].boostDb / 20f)
            val bassBoost = 10f.pow(bass.boostDb / 20f)
            val synthBoost = 10f.pow(analog.boostDb / 20f)
            val pr = prepare
            val gPads = if (pr < 0 || pr == PREP_PADS) 1f else 0f
            val gBass = if (pr < 0 || pr == PREP_BASS) 1f else 0f
            val gSynth = if (pr < 0 || pr == PREP_SYNTH) 1f else 0f
            val gRhythm = if (pr < 0 || pr == PREP_RHYTHM) 1f else 0f
            val gRiff = if (pr < 0 || pr == PREP_RIFF) 1f else 0f
            val gAmb = if (pr < 0 || pr == PREP_AMBIENT) 1f else 0f
            val gRest = if (pr < 0) 1f else 0f          // voice and song recordings: only in the full mix

            for (f in 0 until BLOCK) {
                while (nextTick < nTicks && tickFrames[nextTick] <= f) {
                    onTick(f, t0)
                    nextTick++
                }
                if (playing && !paused) {
                    if (!follow || prepare >= 0) {
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
                    var s = renderTrack(t) * tb[i]
                    s = t.fx.process(s, bpmNow, beatPos)
                    if (!t.mute && (pr < 0 || pr == i)) {
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
                pm = drumFx.process(drumHeavy.process(pm * pv), bpmNow, beatPos) * gPads
                l += pm
                r += pm
                val bz = bass.render() * gBass * bassBoost
                val sy = analog.fx.process(analog.render() * synthBoost, bpmNow, beatPos) * gSynth
                l += bz + sy
                r += bz + sy
                vocal.render()
                l += vocal.outL * gRest
                ambient.render()
                l += ambient.outL * gAmb
                r += ambient.outR * gAmb
                // the two guitars, a little apart: rhythm left of centre, riff right
                val gR = rhythmGtr.render() * gRhythm
                val gF = riffGtr.render() * gRiff
                l += gR * 0.9f + gF * 0.55f
                r += gR * 0.55f + gF * 0.9f
                r += vocal.outR * gRest
                bank.renderClip()
                bank.renderLayers()
                l += bank.outL * gRest
                r += bank.outR * gRest
                val pr = renderPad(preview) * 0.8f
                l += pr
                r += pr
                l = softClip(l * mv)
                r = softClip(r * mv)
                val al = abs(l)
                if (al > mpk) mpk = al
                val ar = abs(r)
                if (ar > mpk) mpk = ar
                fout[f * 2] = l
                fout[f * 2 + 1] = r
                out[f * 2] = (l * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                out[f * 2 + 1] = (r * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }

            // PREPARE playback stays out of the recordings
            if (prepare < 0 || !playing) {
                bank.feedRec(out)
                mixRec.feed(fout)
            }
            for (i in 0 until Cfg.TRACKS) tracks[i].peak = tpk[i]
            masterPeak = mpk
    }

    private fun send(b: Int, target: Long) {
        val m = midiOut
        if (m != null) m(byteArrayOf(b.toByte()), target)
    }

    /** Song Position Pointer (16th notes), then a realtime message (Start / Continue). */
    private fun sendPositionThen(sixteenths: Long, status: Int, target: Long) {
        val m = midiOut ?: return
        val v = sixteenths.coerceIn(0L, 16383L).toInt()
        m(byteArrayOf(0xF2.toByte(), (v and 0x7F).toByte(), ((v shr 7) and 0x7F).toByte()), target)
        m(byteArrayOf(status.toByte()), target)
    }

    private fun handleRequests(t0: Long) {
        val off = offsetMs * 1_000_000L
        if (stopReq) {
            stopReq = false
            if (playing && !follow && sendClock && prepare < 0) send(0xFC, t0 + latencyNs + off)
            playing = false
            prepare = PREP_NONE
            paused = false
            curStep = -1
            for (t in tracks) t.playing = false
            rhythmGtr.hit(MetalGuitar.OFF, 0)
            riffGtr.hit(MetalGuitar.OFF, 0)
            bank.onTransportStop(bpmF)
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
            if (prepare >= 0) {
                rhythmGtr.hit(MetalGuitar.OFF, 0)
                riffGtr.hit(MetalGuitar.OFF, 0)
            } else if (bank.songOn) bank.songStart() else bank.whileStopped()
            if (!follow && sendClock && prepare < 0) sendPositionThen(0L, 0xFA, t0 + latencyNs + off)
        }
        if (continueReq) {
            // FOLLOW: Continue, from the Song Position Pointer if one came (16th notes = 6 clocks)
            continueReq = false
            if (!playing || paused) {
                val spp = sppReq
                sppReq = -1
                if (spp >= 0) {
                    tickCount = spp * 6L
                    beatPos = tickCount / 24.0
                }
                playing = true
                paused = false
            }
        }
        if (pauseReq) {
            pauseReq = false
            if (playing) {
                paused = !paused
                if (!follow && sendClock && prepare < 0) {
                    if (paused) send(0xFC, t0 + latencyNs + off)
                    else sendPositionThen(tickCount / 6L, 0xFB, t0 + latencyNs + off)
                }
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
        if (!playing || paused) bank.whileStopped()
    }

    /** Stop from inside the audio thread (used when the song reaches its end). */
    private fun stopHere(t0: Long) {
        if (playing && !follow && sendClock) send(0xFC, t0 + latencyNs + offsetMs * 1_000_000L)
        playing = false
        paused = false
        curStep = -1
        for (t in tracks) t.playing = false
        bank.onTransportStop(bpmF)
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
        if (!playing) return
        beatPos = tickCount / 24.0
        val prep = prepare >= 0
        if (!follow && sendClock && !prep) {
            send(
                0xF8,
                t0 + frame * 1_000_000_000L / Cfg.SR + latencyNs + offsetMs * 1_000_000L
            )
        }
        if (tickCount % 6L == 0L) {
            val s = ((tickCount / 6L) % Cfg.STEPS).toInt()
            if (prep) {
                // PREPARE: the pattern just loops, song parts and cues wait
            } else if (s == 0) {
                val first = tickCount == 0L
                if (!first && !bank.onBar()) {
                    stopHere(t0)
                    return
                }
                bank.barStart(frame, first)
            } else {
                bank.onStep(s, bpmF)
            }
            curStep = s
            stepTrigger(s)
        }
        tickCount++
    }

    private fun stepTrigger(s: Int) {
        val pr = prepare
        if (pr >= 0) {
            prepareStep(pr, s)
            return
        }
        if (bank.silentNow()) {
            // this part plays its recording only: no new notes, but let held synth notes end
            val en = analogSeq.enabled
            analogSeq.enabled = false
            analogSeq.step(s)
            analogSeq.enabled = en
            return
        }
        bass.step(s, bpmF)
        ambient.step(s)
        rhythmGtr.step(s)
        riffGtr.step(s)
        analogSeq.step(s)
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

    /** PREPARE: step only the part being prepared. */
    private fun prepareStep(pr: Int, s: Int) {
        when (pr) {
            in 0 until Cfg.TRACKS -> {
                val t = tracks[pr]
                if (t.data != null) {
                    if (t.loop) {
                        if (s == 0) trigger(t, 0, 1f)
                    } else {
                        val st = t.steps[s]
                        if (st.on) trigger(t, st.pitch, st.level)
                    }
                }
            }
            PREP_PADS -> for (p in pads) {
                if (p.on[s] && p.data != null) {
                    p.gain = p.lvl[s]
                    p.pos = 0
                    p.playing = true
                }
            }
            PREP_BASS -> bass.step(s, bpmF)
            PREP_SYNTH -> analogSeq.step(s)
            PREP_RHYTHM -> rhythmGtr.step(s)
            PREP_RIFF -> riffGtr.step(s)
            PREP_AMBIENT -> ambient.step(s)
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
