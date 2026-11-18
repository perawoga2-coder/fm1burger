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
        const val GRAIN = 2048          // time stretch grain length (samples)

        // PREPARE mode: what plays alone (0-3 = sample track 1-4)
        const val PREP_NONE = -1
        const val PREP_PADS = 10
        const val PREP_BASS = 11
        const val PREP_SYNTH = 12
        const val PREP_RHYTHM = 13
        const val PREP_RIFF = 14
        const val PREP_AMBIENT = 15

        /** The stem files (in this order) when STEMS is on. */
        val STEM_NAMES = arrayOf(
            "Sampler1", "Sampler2", "Sampler3", "Sampler4", "Drums", "Bassline", "AnalogSynth",
            "RhythmGuitar", "RiffGuitar", "NewAmbient", "Voice", "SongRecordings"
        )

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
    /** STEMS: with the mix recording, every part into its own file (same format, same length). */
    @Volatile var stemsOn = false
    /** Which recorders the audio thread feeds: bit 0 = the mix, bit 1 = the stems (one read per block keeps them aligned). */
    @Volatile var recGate = 1
    val stemRecs = Array(STEM_NAMES.size) { MixRecorder() }
    val stemPeak = FloatArray(STEM_NAMES.size)
    private val stemBuf = Array(STEM_NAMES.size) { FloatArray(BLOCK * 2) }
    val bank = PartBank(this)
    val auto = Automation(this)
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
    /** 16th notes since the pattern started (the pattern step is this modulo Pat.len). */
    private var patCount = 0L
    private var patVersion = 0

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

    // ---- audio output (low latency) ----
    /** 0 = low latency, 1 = normal, 2 = safe (big buffer, like before). Takes effect at once. */
    @Volatile var latencyMode = 1
        set(v) {
            field = v.coerceIn(0, 2)
            restartOut = true
        }
    /** The device's own output rate and burst size (set by the activity from AudioManager). */
    @Volatile var nativeRate = Cfg.SR
    @Volatile var burst = 192
    /** What the output really got (for the Setup page). */
    @Volatile var outRate = Cfg.SR
        private set
    @Volatile var outFrames = 0
        private set
    @Volatile var outFast = false
        private set
    @Volatile var underruns = 0
        private set
    private var outCapacity = 0
    private var lastUnderrun = 0
    private var outStartNs = 0L
    private var lastGrowNs = 0L
    private val rs = OutResampler()

    /** Output latency of the buffer in milliseconds. */
    fun latencyMs(): Double = if (outRate > 0) outFrames * 1000.0 / outRate else 0.0

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
        val mode = latencyMode
        // low / normal: the device's own rate, so Android can use its fast (low latency) mixer path;
        // the engine still runs at 44.1 kHz and a small resampler sits at the very end
        val rate = if (mode < 2 && nativeRate in 8000..192000) nativeRate else Cfg.SR
        return try {
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val bytes = if (mode == 2) max(minBuf, BLOCK * 4 * 6) else max(minBuf, burst.coerceIn(64, 2048) * 4 * 8)
            val b = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= 26 && mode < 2) {
                b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            val t = b.build()
            val d = outDevice
            if (d != null) t.setPreferredDevice(d)
            outCapacity = if (Build.VERSION.SDK_INT >= 24) t.bufferCapacityInFrames else t.bufferSizeInFrames
            if (Build.VERSION.SDK_INT >= 24 && mode < 2) {
                // start small (2 bursts low, 4 normal; at least one engine block) and grow on glitches
                val bu = burst.coerceIn(64, 2048)
                val low = max(bu * 2, BLOCK * rate / Cfg.SR + bu)
                val want = if (mode == 0) low else low + bu * 2
                t.setBufferSizeInFrames(want.coerceAtMost(outCapacity))
                lastUnderrun = t.underrunCount
            }
            outFast = Build.VERSION.SDK_INT >= 26 && t.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
            outRate = rate
            outFrames = t.bufferSizeInFrames
            rs.reset(Cfg.SR.toDouble() / rate)
            // fill the buffer with silence before it starts, so the start itself is no underrun
            try {
                val z = ShortArray(outFrames * 2)
                t.write(z, 0, z.size, AudioTrack.WRITE_NON_BLOCKING)
            } catch (e: Exception) {
            }
            t.play()
            outStartNs = System.nanoTime()
            underruns = 0
            lastGrowNs = 0L
            if (Build.VERSION.SDK_INT >= 24) lastUnderrun = t.underrunCount
            latencyNs = outFrames.toLong() * 1_000_000_000L / rate
            t
        } catch (e: Exception) {
            null
        }
    }

    /** Low / normal latency: a glitch (underrun) makes the buffer one burst bigger, like Oboe's latency tuner. */
    private fun tune(t: AudioTrack) {
        if (Build.VERSION.SDK_INT < 24 || latencyMode == 2) return
        val u = t.underrunCount
        if (u > lastUnderrun) {
            val now = System.nanoTime()
            // the first second after a start (the device settling) is not counted
            if (now - outStartNs < 1_000_000_000L) {
                lastUnderrun = u
                return
            }
            underruns += u - lastUnderrun
            lastUnderrun = u
            // one glitch often comes as several underruns: grow at most once in 300 ms, and to 12 bursts at most
            if (now - lastGrowNs < 300_000_000L) return
            lastGrowNs = now
            val cur = t.bufferSizeInFrames
            if (cur >= burst.coerceIn(64, 2048) * 12) return
            if (cur < outCapacity) {
                val got = t.setBufferSizeInFrames((cur + burst.coerceIn(64, 2048)).coerceAtMost(outCapacity))
                if (got > 0) {
                    outFrames = got
                    latencyNs = got.toLong() * 1_000_000_000L / outRate
                }
            }
        }
    }

    private fun loop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Exception) {
        }
        val out = ShortArray(BLOCK * 2)
        val rsOut = ShortArray(BLOCK * 2 * 8 + 16)
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
                java.util.Arrays.fill(fout, 0f)
                for (t in tracks) t.playing = false
            }

            val w = try {
                if (outRate == Cfg.SR) {
                    a.write(out, 0, out.size)
                } else {
                    val n = rs.process(fout, BLOCK, rsOut)
                    a.write(rsOut, 0, n * 2)
                }
            } catch (ex: Exception) {
                -1
            }
            tune(a)
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
            auto.block()

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
            val gate = recGate
            val stemOn = gate and 2 != 0

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
                val f2 = f * 2
                for (i in 0 until Cfg.TRACKS) {
                    val t = tracks[i]
                    var s = renderTrack(t) * tb[i]
                    s = t.fx.process(s, bpmNow, beatPos)
                    if (stemOn) {
                        val on = !t.mute && (pr < 0 || pr == i)
                        stemBuf[i][f2] = if (on) s * tl[i] * mv else 0f
                        stemBuf[i][f2 + 1] = if (on) s * tr[i] * mv else 0f
                    }
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
                if (stemOn) {
                    stemBuf[4][f2] = pm * mv; stemBuf[4][f2 + 1] = pm * mv
                    stemBuf[5][f2] = bz * mv; stemBuf[5][f2 + 1] = bz * mv
                    stemBuf[6][f2] = sy * mv; stemBuf[6][f2 + 1] = sy * mv
                    stemBuf[7][f2] = gR * 0.9f * mv; stemBuf[7][f2 + 1] = gR * 0.55f * mv
                    stemBuf[8][f2] = gF * 0.55f * mv; stemBuf[8][f2 + 1] = gF * 0.9f * mv
                    stemBuf[9][f2] = ambient.outL * gAmb * mv; stemBuf[9][f2 + 1] = ambient.outR * gAmb * mv
                    stemBuf[10][f2] = vocal.outL * gRest * mv; stemBuf[10][f2 + 1] = vocal.outR * gRest * mv
                    stemBuf[11][f2] = bank.outL * gRest * mv; stemBuf[11][f2 + 1] = bank.outR * gRest * mv
                }
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
                if (gate and 1 != 0) mixRec.feed(fout)
                if (stemOn) {
                    for (k in stemBuf.indices) {
                        val b = stemBuf[k]
                        var pk = stemPeak[k]
                        for (x in b) {
                            val ax = abs(x)
                            if (ax > pk) pk = ax
                        }
                        stemPeak[k] = pk
                        stemRecs[k].feed(b)
                    }
                }
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
            auto.stopped()
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
                    patCount = spp.toLong()
                    patVersion = bank.version
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
            // b = step inside the bar (parts, cues and songs work in bars),
            // s = step inside the pattern, which can be 1, 2 or 4 bars long
            val b = ((tickCount / 6L) % Pat.BAR).toInt()
            if (tickCount == 0L) patCount = 0L
            if (prep) {
                // PREPARE: the pattern just loops, song parts and cues wait
            } else if (b == 0) {
                val first = tickCount == 0L
                if (!first && !bank.onBar()) {
                    stopHere(t0)
                    return
                }
                bank.barStart(frame, first)
            } else {
                bank.onStep(b, bpmF)
            }
            // a new part starts its pattern from the top (at the same place in the bar)
            val v = bank.version
            if (v != patVersion) {
                patVersion = v
                patCount = b.toLong()
            }
            val len = Pat.len.coerceIn(Pat.BAR, Cfg.STEPS)
            val s = (patCount % len).toInt()
            patCount++
            curStep = s
            auto.step(s, true)
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
        for (t in tracks) stepTrack(t, s)
    }

    /** One step of a sample track: a loop restarts on its bar, a step plays the sample (or its slice). */
    private fun stepTrack(t: Track, s: Int) {
        if (t.data == null) return
        if (t.loop) {
            if (s == 0) {
                val bar = (tickCount / 96L).toInt()
                if (t.fitBars <= 0 || bar % t.fitBars == 0 || !t.playing) trigger(t, 0, 1f)
            }
        } else {
            val st = t.steps[s]
            if (st.on) trigger(t, st.pitch, st.level, s)
        }
    }

    /** PREPARE: step only the part being prepared. */
    private fun prepareStep(pr: Int, s: Int) {
        when (pr) {
            in 0 until Cfg.TRACKS -> stepTrack(tracks[pr], s)
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

    private fun trigger(t: Track, semis: Int, level: Float, step: Int = -1) {
        val d = t.data ?: return
        val n = d.size
        if (n < 2) return
        var a = (t.start * n).toInt().coerceIn(0, n - 1)
        var e = (t.end * n).toInt().coerceIn(a + 1, n)
        var rev = t.reverse
        // slices: this step plays one equal part of start..end
        if (!t.loop && t.slices > 0 && step >= 0) {
            val k = (if (t.sliceOf[step % Pat.BAR] >= 0) t.sliceOf[step % Pat.BAR] else step) % t.slices
            val len = (e - a).toDouble() / t.slices
            val sa = a + (k * len).toInt()
            val se = (a + ((k + 1) * len).toInt()).coerceAtMost(e)
            a = sa
            e = se.coerceAtLeast(a + 1)
            if (t.sliceRev[step % Pat.BAR]) rev = !rev
        }
        t.vStart = a.toDouble()
        t.vEnd = e.toDouble()
        t.rate = 2.0.pow((t.pitch + semis) / 12.0)
        t.gain = level
        // fit the loop to N bars of the tempo
        t.stretch = false
        t.tlRate = 1.0
        if (t.loop && t.fitBars > 0) {
            val want = t.fitBars * Cfg.SR * 240.0 / bpmF          // samples in N bars
            val fit = (e - a) / want
            if (t.keepPitch) {
                t.stretch = true
                t.tlRate = fit
            } else {
                t.rate = fit * 2.0.pow(t.pitch / 12.0)
            }
        }
        if (rev) {
            t.dir = -1
            t.pos = (e - 1).toDouble()
        } else {
            t.dir = 1
            t.pos = a.toDouble()
        }
        t.tl = t.pos
        t.gAge[0] = 0
        t.gAge[1] = GRAIN / 2
        t.gPos[0] = t.pos
        t.gPos[1] = t.pos
        t.playing = true
    }

    private fun sampleAt(d: FloatArray, p: Double): Float {
        val i = p.toInt()
        if (i < 0 || i >= d.size) return 0f
        val fr = (p - i).toFloat()
        val s0 = d[i]
        val s1 = if (i + 1 < d.size) d[i + 1] else 0f
        return s0 + (s1 - s0) * fr
    }

    /** Moves a position by one step inside the loop (forward wraps, ping-pong turns around). Returns false at the end of a one-shot. */
    private fun advance(t: Track, rate: Double): Boolean {
        var p = t.tl + t.dir * rate
        val la = t.vStart + t.loopA.coerceIn(0f, 1f) * (t.vEnd - t.vStart)
        var lb = t.vStart + t.loopB.coerceIn(0f, 1f) * (t.vEnd - t.vStart)
        if (lb - la < 8.0) lb = (la + 8.0).coerceAtMost(t.vEnd)
        if (!t.loop) {
            if (p >= t.vEnd || p < t.vStart) return false
            t.tl = p
            return true
        }
        if (t.dir > 0 && p >= lb) {
            if (t.pingPong) {
                t.dir = -1
                p = lb - (p - lb)
            } else {
                p = la + (p - lb)
                if (p >= lb) p = la
            }
        } else if (t.dir < 0 && p < la) {
            if (t.pingPong) {
                t.dir = 1
                p = la + (la - p)
            } else {
                p = lb - (la - p)
                if (p < la) p = lb - 1.0
            }
        }
        t.tl = p.coerceIn(t.vStart, t.vEnd - 1.0)
        return true
    }

    private fun renderTrack(t: Track): Float {
        if (!t.playing) return 0f
        val d = t.data
        if (d == null) {
            t.playing = false
            return 0f
        }
        if (t.tl < 0.0 || t.tl.toInt() >= d.size) {
            t.playing = false
            return 0f
        }
        var s: Float
        if (t.stretch) {
            // time stretch: two overlapping grains read at the pitch rate, the loop moves at the fit rate
            s = 0f
            val pr = 2.0.pow(t.pitch / 12.0)
            for (k in 0 until 2) {
                if (t.gAge[k] == 0) t.gPos[k] = t.tl
                val w = 0.5f - 0.5f * cos(6.2831855f * t.gAge[k] / GRAIN)
                s += sampleAt(d, t.gPos[k]) * w
                t.gPos[k] = (t.gPos[k] + t.dir * pr).coerceIn(0.0, d.size - 1.0)
                t.gAge[k] = (t.gAge[k] + 1) % GRAIN
            }
            s *= t.gain
            if (!advance(t, t.tlRate)) t.playing = false
        } else {
            s = sampleAt(d, t.tl)
            // crossfade into the loop start just before the loop end (forward loops)
            if (t.loop && t.xfadeMs > 0.5f && t.dir > 0 && !t.pingPong) {
                val la = t.vStart + t.loopA * (t.vEnd - t.vStart)
                val lb = t.vStart + t.loopB * (t.vEnd - t.vStart)
                val xf = (t.xfadeMs / 1000.0 * Cfg.SR).coerceAtMost((lb - la) / 2.0)
                val into = t.tl - (lb - xf)
                if (xf > 1.0 && into >= 0.0) {
                    val k = (into / xf).toFloat().coerceIn(0f, 1f)
                    s = s * (1f - k) + sampleAt(d, t.tl - (lb - la)) * k
                }
            }
            s *= t.gain
            if (!advance(t, t.rate)) t.playing = false
        }
        t.pos = t.tl
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

/**
 * Streaming 4-point (Hermite) resampler for the output: 44.1 kHz engine blocks to the device rate.
 * Two frames of delay; the last three input frames are kept between blocks.
 */
class OutResampler {
    private var step = 1.0          // input frames per output frame
    private var pos = 0.0           // next output position, in input frames from the block start
    private val hist = FloatArray(6)

    fun reset(st: Double) {
        step = st.coerceIn(0.1, 8.0)
        pos = 0.0
        java.util.Arrays.fill(hist, 0f)
    }

    private fun x(inp: FloatArray, j: Int, ch: Int): Float =
        if (j >= 0) inp[j * 2 + ch] else hist[(j + 3) * 2 + ch]

    /** Resamples n stereo frames of [inp]; returns the frames written to [out]. */
    fun process(inp: FloatArray, n: Int, out: ShortArray): Int {
        var k = 0
        val maxK = out.size / 2
        while (pos < n - 2 && k < maxK) {
            val i = Math.floor(pos).toInt()
            val t = (pos - i).toFloat()
            for (ch in 0..1) {
                val xm1 = x(inp, i - 1, ch)
                val x0 = x(inp, i, ch)
                val x1 = x(inp, i + 1, ch)
                val x2 = x(inp, i + 2, ch)
                val c1 = 0.5f * (x1 - xm1)
                val c2 = xm1 - 2.5f * x0 + 2f * x1 - 0.5f * x2
                val c3 = 0.5f * (x2 - xm1) + 1.5f * (x0 - x1)
                val y = ((c3 * t + c2) * t + c1) * t + x0
                out[k * 2 + ch] = (y * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            k++
            pos += step
        }
        pos -= n
        for (q in 0 until 3) {
            hist[q * 2] = inp[(n - 3 + q) * 2]
            hist[q * 2 + 1] = inp[(n - 3 + q) * 2 + 1]
        }
        return k
    }
}
