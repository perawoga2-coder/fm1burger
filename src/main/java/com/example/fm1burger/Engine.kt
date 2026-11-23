package com.example.fm1burger

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
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
        // 3.16: the synths sat well under the samples; a fixed lift (the master soft clip catches the peaks)
        const val SYNTH_LIFT_BASS = 1.6f      // +4 dB
        const val SYNTH_LIFT_ANALOG = 1.75f   // +5 dB
        const val SYNTH_LIFT_GTR = 1.4f       // +3 dB
        const val BLOCK = 256

        // 3.40 CUE: the sources that can go to the headphones (the index into Engine.cue)
        const val CUE_PAD = Cfg.TRACKS                 // sampler track i = i; drum pad k = CUE_PAD + k
        const val CUE_BASS = CUE_PAD + Cfg.PADS
        const val CUE_SYNTH = CUE_BASS + 1
        const val CUE_RHYTHM = CUE_BASS + 2
        const val CUE_RIFF = CUE_BASS + 3
        const val CUE_AMBIENT = CUE_BASS + 4
        const val CUE_SONG = CUE_BASS + 5
        const val CUE_VOICE = CUE_BASS + 6
        const val CUE_N = CUE_BASS + 7
        const val GRAIN = 2048          // time stretch grain length (samples)

        // 3.32: the full-screen keyboard's own voices
        const val KEY_VOICES = 8
        /** the keyboard's level in the mix (the preview voice sits at 0.8; chords add up, so a little under it) */
        const val KEYS_LEVEL = 0.6f
        private const val KQ = 64                       // the request queue (a power of two)
        private const val KEY_ATTACK = 1f / 88f         // 2 ms

        // PREPARE mode: what plays alone (0-3 = sample track 1-4)
        const val PREP_NONE = -1
        const val PREP_PADS = 10
        const val PREP_BASS = 11
        const val PREP_SYNTH = 12
        const val PREP_RHYTHM = 13
        const val PREP_RIFF = 14
        const val PREP_AMBIENT = 15
        /** 3.36 offline stems (offlineBlock): the part's recording */
        const val OFF_CLIP = 16
        /** 3.36: where renderBlock puts the two guitars (rhythm: near = left; riff: near = right) */
        const val GTR_NEAR = 0.9f
        const val GTR_FAR = 0.55f

        /** The first stem after the sampler tracks. */
        const val ST = Cfg.TRACKS

        /** The stem files (in this order) when STEMS is on. */
        val STEM_NAMES = arrayOf(
            "Sampler1", "Sampler2", "Sampler3", "Sampler4", "Sampler5", "Drums", "Bassline", "AnalogSynth",
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
    /** the step inside the bar (audio thread): counted, so that a meter change can restart the bar */
    private var barStep = 0
    /** the meter the audio thread last saw */
    private var seenMeter = 0
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
        cueTried = false          // 3.40: another main output: the headphones may be free now (one new try)
    }

    // ---------------- 3.40 CUE: a source in CUE is silent in the main output and heard in the headphones ----------------
    // Three ways out, chosen by the engine (see cueSync / makeOut / loop):
    //  - the Output is a USB interface with 4+ channels: one 4-channel AudioTrack, 1-2 main, 3-4 headphones
    //  - else a second AudioTrack on a headphone-type device that is not the main output's (opened off the audio
    //    thread when the first source goes into CUE, written non-blocking, closed when no source is in CUE)
    //  - "Split cable": no second track; the main output is left = main (mono), right = headphones (mono)
    // With no way out the CUE sources stay in the main output. The recordings always get the whole mix.

    /** The CUE flags (index: CUE_*). Per session, not saved. Change them with [setCue] only. */
    val cue = BooleanArray(CUE_N)
    @Volatile var cueAny = false
        private set
    @Volatile private var cuePads = false
    /** How much of the main mix the headphones also get, 0 .. 1. */
    @Volatile var cueMix = 0.5f
    /** The headphone level; below 0 = the same as MASTER (until its slider is moved). */
    @Volatile var cueVol = -1f
    /** Split cable (DJ): the main output is left = main mix (mono), right = headphone mix (mono). */
    @Volatile var cueSplit = false
    /** Split cable: the sides exchanged. */
    @Volatile var cueSwap = false
    /** The activity gives the output devices (AudioManager.getDevices); called off the audio thread. */
    @Volatile var cueDevices: (() -> Array<AudioDeviceInfo>)? = null

    /** The main AudioTrack has 4 channels (1-2 main, 3-4 headphones). Set by the audio thread. */
    @Volatile var cueQuad = false
        private set
    @Volatile private var mainTrack: AudioTrack? = null
    @Volatile private var hpTrack: AudioTrack? = null      // the second output; opened and released by cueWork
    @Volatile private var hpAck: AudioTrack? = null        // the one the audio thread uses now (for a safe release)
    @Volatile private var hpRate = Cfg.SR
    @Volatile private var hpDevId = -1
    @Volatile private var hpName = ""
    @Volatile private var hpBroken = false                 // a write failed or the routes met: back to the main output
    @Volatile private var cueTried = false                 // one try for each "first source into CUE": never a loop
    @Volatile private var cueNote = ""
    private val cueBusy = AtomicBoolean(false)
    private var cueCheckMs = 0L
    /** audio thread: this block is rendered with the headphone mix (mainF / hpF) */
    private var cueRender = false
    /** What the main output plays while CUE is live (the recordings keep fout), and the headphone mix. */
    private val mainF = FloatArray(BLOCK * 2)
    private val hpF = FloatArray(BLOCK * 2)
    // the drum bus without the pads in CUE (the bus effects are not linear: a second, identical chain); made by
    // setCue on the UI thread the first time a pad goes into CUE
    @Volatile private var padFx2: FxChain? = null
    private val drumHeavy2 = DrumHeavy()
    private val rs2 = OutResampler()

    /** UI thread: source [i] (CUE_*) into / out of CUE. */
    fun setCue(i: Int, on: Boolean) {
        if (i !in cue.indices) return
        cue[i] = on
        var any = false
        var pd = false
        for (k in cue.indices) if (cue[k]) {
            any = true
            if (k >= CUE_PAD && k < CUE_BASS) pd = true
        }
        if (pd && padFx2 == null) padFx2 = FxChain()
        cuePads = pd
        cueAny = any
        cueSync()
    }

    fun isCue(i: Int): Boolean = i in cue.indices && cue[i]

    /** The headphone level as its slider shows it. */
    fun cueLevel(): Float = cueVol.let { if (it < 0f) masterVol else it }

    /** One line for the Mixer: where the headphone mix goes now. */
    fun cueStatus(): String = when {
        cueSplit -> "Split cable: " + (if (cueSwap) "left = headphones, right = main" else "left = main, right = headphones") + " (both mono)."
        cueQuad -> "USB 4 channels: 1-2 main, 3-4 headphones."
        !cueAny -> "No source in CUE."
        hpTrack != null && !hpBroken -> "Headphones: $hpName."
        cueNote.isNotEmpty() -> cueNote
        else -> "Looking for the headphones..."
    }

    private fun cueRecording(): Boolean = mixRec.recording || bank.recState == 1 || bank.recState == 2

    /**
     * UI thread (after every CUE change, and on the UI tick): opens the second output when the first source goes
     * into CUE, closes it when none is left (after a recording, not during it). Cheap when there is nothing to do;
     * the work itself runs on a thread of its own, never on the audio thread.
     */
    fun cueSync() {
        if (!cueAny) {
            cueTried = false
            cueNote = ""
        }
        if (!running) return
        val cur = hpTrack
        if (cur != null && !hpBroken) {
            // about once a second: both outputs still on different devices? (headphones pulled out ...)
            val now = android.os.SystemClock.uptimeMillis()
            if (now - cueCheckMs > 1000L) {
                cueCheckMs = now
                val hr = routed(cur)
                val mr = routed(mainTrack)
                if (hr == null || hr.id != hpDevId || (mr != null && mr.id == hr.id)) {
                    hpBroken = true
                    cueNote = "The headphones are gone: CUE sources play in the main output."
                }
            }
        }
        val want = cueAny && !cueSplit && !cueQuad && !restartOut
        val close = cur != null && (hpBroken || (!want && !restartOut && !cueRecording()))
        val open = cur == null && want && !cueTried
        if (!close && !open) return
        if (!cueBusy.compareAndSet(false, true)) return
        try {
            val th = Thread {
                try {
                    cueWork()
                } catch (ex: Throwable) {
                } finally {
                    cueBusy.set(false)
                }
            }
            th.isDaemon = true
            th.name = "burger-cue"
            th.start()
        } catch (ex: Throwable) {
            cueBusy.set(false)
        }
    }

    private fun routed(t: AudioTrack?): AudioDeviceInfo? = try {
        t?.routedDevice
    } catch (ex: Throwable) {
        null
    }

    /** The cue thread: closes and / or opens the second output (one at a time: cueBusy). */
    private fun cueWork() {
        val cur = hpTrack
        val want = cueAny && !cueSplit && !cueQuad && !restartOut
        if (cur != null && (hpBroken || (!want && !cueRecording()))) {
            cueClose(cur)
            return                 // (a broken one is not opened again: cueTried stays set until CUE is empty)
        }
        if (cur == null && want && !cueTried) {
            cueTried = true
            cueOpen()
        }
    }

    private fun cueClose(t: AudioTrack) {
        hpTrack = null
        // the audio thread lets go of it at its next block
        var n = 0
        while (running && hpAck === t && n++ < 40) {
            try {
                Thread.sleep(10)
            } catch (ex: Exception) {
            }
        }
        try {
            t.pause()
            t.flush()
        } catch (ex: Throwable) {
        }
        try {
            t.release()
        } catch (ex: Throwable) {
        }
        hpBroken = false
    }

    private fun cueOpen() {
        val src = cueDevices
        val devs = try {
            src?.invoke()
        } catch (ex: Throwable) {
            null
        }
        if (devs == null) {
            cueNote = "No headphone output found: CUE sources play in the main output."
            return
        }
        // where the main output plays: the Output chosen in the Mixer, or what the system gave it
        val mainDev = outDevice ?: routed(mainTrack)
        if (mainDev == null) {
            cueNote = "The main output's device is not known: CUE sources play in the main output."
            return
        }
        // a headphone-type output that is not the main output's device (wired first, then USB, then Bluetooth)
        val order = intArrayOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        )
        var hp: AudioDeviceInfo? = null
        for (ty in order) {
            for (d in devs) if (d.isSink && d.type == ty && d.id != mainDev.id) {
                hp = d
                break
            }
            if (hp != null) break
        }
        if (hp == null) {
            cueNote = if (mainDev.type in order)
                "The main output is on the headphones: choose the tablet speaker as Output (or Split cable) to use CUE."
            else "No headphones found: CUE sources play in the main output."
            return
        }
        var t: AudioTrack? = null
        try {
            val rate = if (latencyMode < 2 && nativeRate in 8000..192000) nativeRate else Cfg.SR
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            val bu = burst.coerceIn(64, 2048)
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
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, bu * 8 * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= 26) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            val tr = b.build()
            t = tr
            if (!tr.setPreferredDevice(hp)) throw IllegalStateException("device")
            if (Build.VERSION.SDK_INT >= 24) {
                val cap = tr.bufferCapacityInFrames
                tr.setBufferSizeInFrames((max(bu * 2, BLOCK * rate / Cfg.SR + bu) + bu * 2).coerceAtMost(cap))
            }
            tr.play()
            // the system must really play it on the headphones, and not where the main output plays
            var rd: AudioDeviceInfo? = null
            var n = 0
            while (n++ < 10) {
                rd = routed(tr)
                if (rd != null) break
                try {
                    Thread.sleep(50)
                } catch (ex: Exception) {
                }
            }
            val mr = routed(mainTrack)
            if (rd == null || rd.id != hp.id || rd.id == mainDev.id || (mr != null && mr.id == rd.id)) {
                throw IllegalStateException("route")
            }
            hpRate = rate
            hpDevId = hp.id
            hpName = when (hp.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "3.5 mm headphones"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth ${hp.productName}"
                else -> "USB ${hp.productName}"
            }
            hpBroken = false
            cueCheckMs = android.os.SystemClock.uptimeMillis()
            cueNote = ""
            hpTrack = tr          // from the next block the audio thread feeds it
        } catch (ex: Throwable) {
            try {
                t?.release()
            } catch (e2: Throwable) {
            }
            cueNote = if (ex.message == "route")
                "The system plays the headphones and the main output on the same device: CUE sources play in the main output."
            else "The headphone output could not be opened: CUE sources play in the main output."
        }
    }

    fun padHit(i: Int, vel: Float) {
        if (i in pads.indices) { pads[i].req = vel.coerceIn(0.05f, 1f); Live.touch() }
    }

    // ---------------- 3.19 RESAMPLE: the app's own mix into memory (SAMPLING's REC, source "the app") ----------------
    @Volatile private var grabBuf: FloatArray? = null
    @Volatile var grabPos = 0
        private set

    @Volatile private var grabPending: FloatArray? = null

    /** Starts catching the mix (mono) for at most [seconds]. */
    fun grabStart(seconds: Double) {
        grabPending = null
        grabPos = 0
        grabBuf = FloatArray((seconds * Cfg.SR).toInt().coerceAtLeast(Cfg.SR))
    }

    /** Catches exactly [bars] bars from the start of the next bar (a loop that fits); the buffer is made here, not on the audio thread. */
    fun grabArmBars(bars: Int) {
        grabBuf = null
        grabPos = 0
        val n = (Pat.barFrames(bpmF) * bars).toInt().coerceIn(Cfg.SR / 4, Cfg.SR * 60)
        grabPending = FloatArray(n)
    }

    /** Armed, waiting for the next bar. */
    fun grabWaiting(): Boolean = grabPending != null

    /** Stops and returns what was caught (null if nothing). */
    fun grabStop(): FloatArray? {
        grabPending = null
        val b = grabBuf ?: return null
        grabBuf = null
        val n = grabPos.coerceAtMost(b.size)
        return if (n < 64) null else b.copyOf(n)
    }

    fun grabbing(): Boolean = grabBuf != null || grabPending != null
    fun grabFull(): Boolean = grabBuf?.let { grabPos >= it.size } ?: false

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

    /** Plays track [i]'s sample once at [semis] semitones from its pitch (the piano keys of the STEPS window). The audio thread starts it. */
    fun auditionNote(i: Int, semis: Int) {
        if (i in tracks.indices) { tracks[i].noteReq = semis.coerceIn(-24, 24); Live.touch() }
    }

    // ---------------- 3.32: the full-screen keyboard's voices (a small pool, mixed where the preview voice is) ----------------

    /** One voice of the keyboard. Only the audio thread touches it. */
    private class KeyVoice {
        var data: FloatArray? = null
        var id = 0
        var age = 0L
        var stage = 0           // 0 idle, 1 attack, 2 held, 3 release
        var pos = 0.0
        var rate = 1.0
        var end = 0             // the sample ends here
        var la = 0              // loop (lb > 0): la .. lb, the last xf samples before lb fade into la
        var lb = 0
        var xf = 0
        var gain = 0f
        var lp = 0f             // one-pole low-pass (brightness)
        var lpK = 1f
        var env = 0f
        var relInc = 0f
    }

    private val keyVoices = Array(KEY_VOICES) { KeyVoice() }
    private val keyBuf = FloatArray(BLOCK)
    private var keysOn = false          // (audio thread) keyBuf holds sound for this block
    private var keyAge = 0L

    // the requests: the UI thread writes (under keyLock), the audio thread reads at the start of a block. Nothing is
    // allocated: every slot is made here.
    private val keyLock = Any()
    private val kqType = IntArray(KQ)                   // 1 note on, 2 note off, 3 all off
    private val kqId = IntArray(KQ)
    private val kqData = arrayOfNulls<FloatArray>(KQ)
    private val kqRate = DoubleArray(KQ)
    private val kqGain = FloatArray(KQ)
    private val kqLp = FloatArray(KQ)
    private val kqRel = FloatArray(KQ)
    private val kqFrom = IntArray(KQ)
    private val kqTo = IntArray(KQ)
    private val kqLa = IntArray(KQ)
    private val kqLb = IntArray(KQ)
    private val kqXf = IntArray(KQ)
    @Volatile private var kqHead = 0
    @Volatile private var kqTail = 0

    /**
     * The keyboard: a note starts. [id] names it for [keyOff]; [d] is played from [from] to [to] at [rate] (1 = as it is),
     * at [gain] (0..1) through a low-pass of coefficient [lp] (0..1, 1 = open); it fades out in [releaseMs] at the note off.
     * With [loopB] > [loopA] it loops loopA..loopB while it sounds, the last [xfade] samples crossfaded into the loop start.
     */
    fun keyOn(id: Int, d: FloatArray, rate: Double, gain: Float, lp: Float, releaseMs: Float,
              from: Int, to: Int, loopA: Int, loopB: Int, xfade: Int) {
        Live.touch()
        synchronized(keyLock) {
            val h = kqHead
            val next = (h + 1) and (KQ - 1)
            if (next == kqTail) return          // full (the audio thread is not running): drop
            kqType[h] = 1
            kqId[h] = id
            kqData[h] = d
            kqRate[h] = rate
            kqGain[h] = gain
            kqLp[h] = lp
            kqRel[h] = releaseMs
            kqFrom[h] = from
            kqTo[h] = to
            kqLa[h] = loopA
            kqLb[h] = loopB
            kqXf[h] = xfade
            kqHead = next
        }
    }

    /** The keyboard: the note [id] is released. */
    fun keyOff(id: Int) {
        synchronized(keyLock) {
            val h = kqHead
            val next = (h + 1) and (KQ - 1)
            if (next == kqTail) return
            kqType[h] = 2
            kqId[h] = id
            kqData[h] = null
            kqHead = next
        }
    }

    /** The keyboard: every note is released. */
    fun keysAllOff() {
        synchronized(keyLock) {
            val h = kqHead
            val next = (h + 1) and (KQ - 1)
            if (next == kqTail) return
            kqType[h] = 3
            kqId[h] = 0
            kqData[h] = null
            kqHead = next
        }
    }

    /** Audio thread, at the start of a block: the keyboard's requests. */
    private fun drainKeys() {
        while (kqTail != kqHead) {
            val i = kqTail
            when (kqType[i]) {
                1 -> keyStart(i)
                2 -> {
                    val id = kqId[i]
                    for (v in keyVoices) if (v.stage != 0 && v.id == id) v.stage = 3
                }
                else -> for (v in keyVoices) if (v.stage != 0) v.stage = 3
            }
            kqData[i] = null
            kqTail = (i + 1) and (KQ - 1)
        }
    }

    private fun keyStart(i: Int) {
        val d = kqData[i] ?: return
        val n = d.size
        if (n < 2) return
        // a free voice, else the oldest
        var v = keyVoices[0]
        var found = false
        for (k in keyVoices) {
            if (k.stage == 0) {
                v = k
                found = true
                break
            }
        }
        if (!found) for (k in keyVoices) if (k.age < v.age) v = k
        val a = kqFrom[i].coerceIn(0, n - 2)
        val e = kqTo[i].coerceIn(a + 1, n)
        var la = kqLa[i]
        var lb = kqLb[i]
        var xf = kqXf[i]
        if (la < a || lb > e || lb - la < 64) {
            la = 0
            lb = 0
            xf = 0
        } else {
            xf = xf.coerceIn(0, minOf(la - a, (lb - la) / 2))
        }
        v.data = d
        v.id = kqId[i]
        v.age = ++keyAge
        v.pos = a.toDouble()
        v.rate = kqRate[i].coerceIn(0.03, 32.0)
        v.end = e
        v.la = la
        v.lb = lb
        v.xf = xf
        v.gain = kqGain[i].coerceIn(0f, 1f)
        v.lpK = kqLp[i].coerceIn(0.01f, 1f)
        v.lp = 0f
        v.env = 0f
        v.relInc = 1f / (kqRel[i].coerceIn(2f, 5000f) * 0.001f * Cfg.SR)
        v.stage = 1
    }

    /** Audio thread: the keyboard's voices of this block into keyBuf (keysOn says whether there is any). */
    private fun renderKeys() {
        var any = false
        for (v in keyVoices) if (v.stage != 0) any = true
        if (!any) {
            keysOn = false
            return
        }
        java.util.Arrays.fill(keyBuf, 0f)
        for (v in keyVoices) {
            if (v.stage == 0) continue
            val d = v.data
            if (d == null) {
                v.stage = 0
                continue
            }
            val n = d.size
            val looped = v.lb > 0
            val span = (v.lb - v.la).toDouble()
            for (f in 0 until BLOCK) {
                var p = v.pos
                if (looped && p >= v.lb) {
                    p -= span
                    if (p >= v.lb || p < v.la) p = v.la.toDouble()
                }
                val i = p.toInt()
                if (i < 0 || i >= v.end || i >= n) {
                    v.stage = 0
                    break
                }
                val fr = (p - i).toFloat()
                val s0 = d[i]
                val s1 = if (i + 1 < n) d[i + 1] else 0f
                var s = s0 + (s1 - s0) * fr
                if (looped) {
                    // the end of the loop fades into its start
                    if (v.xf > 0 && p >= v.lb - v.xf) {
                        val q = p - span
                        val j = q.toInt()
                        if (j >= 0 && j + 1 < n) {
                            val k = ((p - (v.lb - v.xf)) / v.xf).toFloat().coerceIn(0f, 1f)
                            val t0 = d[j]
                            s = s * (1f - k) + (t0 + (d[j + 1] - t0) * (q - j).toFloat()) * k
                        }
                    }
                } else if (p >= v.end - v.rate * 230.0) {
                    // the sample runs out: a 5 ms fade instead of a click
                    v.stage = 3
                    if (v.relInc < 0.005f) v.relInc = 0.005f
                }
                v.lp += v.lpK * (s - v.lp)
                if (v.stage == 1) {
                    v.env += KEY_ATTACK
                    if (v.env >= 1f) {
                        v.env = 1f
                        v.stage = 2
                    }
                } else if (v.stage == 3) {
                    v.env -= v.relInc
                    if (v.env <= 0f) {
                        v.env = 0f
                        v.stage = 0
                        break
                    }
                }
                keyBuf[f] += v.lp * v.gain * v.env
                v.pos = p + v.rate
            }
        }
        keysOn = true
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
        // 3.40: the second (headphone) output, when it is open
        val h = hpTrack
        hpTrack = null
        try {
            h?.release()
        } catch (e: Throwable) {
        }
    }

    // ---------------- audio thread ----------------

    private fun makeOut(): AudioTrack? {
        // 3.40 CUE: the Output chosen in the Mixer is a USB audio output with 4 or more channels: one 4-channel
        // track (1-2 main, 3-4 headphones). Anything else, or when that fails: the stereo track, as always.
        val d = outDevice
        if (d != null && (d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET) &&
            d.isSink && d.channelCounts.any { it >= 4 }
        ) {
            val t = buildOut(true)
            if (t != null) return t
        }
        return buildOut(false)
    }

    private fun buildOut(quad: Boolean): AudioTrack? {
        val mode = latencyMode
        val ch = if (quad) 4 else 2
        val mask = if (quad) AudioFormat.CHANNEL_OUT_QUAD else AudioFormat.CHANNEL_OUT_STEREO
        // low / normal: the device's own rate, so Android can use its fast (low latency) mixer path;
        // the engine still runs at 44.1 kHz and a small resampler sits at the very end
        val rate = if (mode < 2 && nativeRate in 8000..192000) nativeRate else Cfg.SR
        return try {
            val minBuf = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            val bytes = if (mode == 2) max(minBuf, BLOCK * 2 * ch * 6) else max(minBuf, burst.coerceIn(64, 2048) * 2 * ch * 8)
            val fmt = AudioFormat.Builder()
                .setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            // the interface's own channels 1-4 when it says so (index mask), else front left / right + back left / right
            if (quad && outDevice?.channelIndexMasks?.contains(0xF) == true) fmt.setChannelIndexMask(0xF)
            else fmt.setChannelMask(mask)
            val b = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(fmt.build())
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= 26 && mode < 2) {
                b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            val t = b.build()
            if (quad && t.channelCount != 4) {
                t.release()
                return null
            }
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
            if (quad) rs2.reset(Cfg.SR.toDouble() / rate)
            cueQuad = quad
            // fill the buffer with silence before it starts, so the start itself is no underrun
            try {
                val z = ShortArray(outFrames * ch)
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
        // 3.40 CUE: everything the headphone mix needs, made once here
        val spk = ShortArray(BLOCK * 2)                 // the main output while CUE is live
        val rsOut2 = ShortArray(rsOut.size)             // 4 channels: the headphone pair, resampled
        val quadOut = ShortArray(rsOut.size * 2)        // 4 channels, interleaved
        val hpRs = FloatArray(rsOut.size)               // the second output, resampled
        var hpCur: AudioTrack? = null
        var at = makeOut()
        mainTrack = at
        while (running) {
            if (restartOut) {
                restartOut = false
                try {
                    at?.stop()
                    at?.release()
                } catch (e: Exception) {
                }
                at = makeOut()
                mainTrack = at
            }
            val a = at
            if (a == null) {
                try {
                    Thread.sleep(300)
                } catch (e: Exception) {
                }
                at = makeOut()
                mainTrack = at
                continue
            }

            // 3.40 CUE: how this block goes out (nothing of it when no source is in CUE and there is no split cable)
            val hp = hpTrack
            if (hp !== hpCur) {
                hpCur = hp
                hpAck = hp
                if (hp != null) rs2.reset(Cfg.SR.toDouble() / hpRate)
            }
            val quad = cueQuad
            val split = cueSplit
            val hpLive = hp != null && !hpBroken && !split && !quad
            val live = split || (cueAny && (quad || hpLive))
            cueRender = live

            val t0 = System.nanoTime()
            try {
                renderBlock(t0, out, tl, tr, tpk)
            } catch (ex: Throwable) {
                // never let one bad block kill the audio thread: play silence and carry on
                errors++
                java.util.Arrays.fill(out, 0.toShort())
                java.util.Arrays.fill(fout, 0f)
                java.util.Arrays.fill(mainF, 0f)
                java.util.Arrays.fill(hpF, 0f)
                for (t in tracks) t.playing = false
            }

            // 3.40 CUE: the second output first, and never waiting for it: what does not fit is dropped
            if (hpLive && live && hp != null) {
                val hw = try {
                    if (hpRate == Cfg.SR) {
                        hp.write(hpF, 0, BLOCK * 2, AudioTrack.WRITE_NON_BLOCKING)
                    } else {
                        val n = rs2.processF(hpF, BLOCK, hpRs)
                        hp.write(hpRs, 0, n * 2, AudioTrack.WRITE_NON_BLOCKING)
                    }
                } catch (ex: Throwable) {
                    -1
                }
                if (hw < 0) hpBroken = true      // (cueSync closes it; the CUE sources are back in the main output)
            }

            val w = try {
                if (!live && !quad) {
                    if (outRate == Cfg.SR) {
                        a.write(out, 0, out.size)
                    } else {
                        val n = rs.process(fout, BLOCK, rsOut)
                        a.write(rsOut, 0, n * 2)
                    }
                } else {
                    // the main output without the CUE sources (the recordings got fout: the whole mix)
                    val src = if (live) mainF else fout
                    if (quad) {
                        if (!live) java.util.Arrays.fill(hpF, 0f)
                        if (outRate == Cfg.SR) {
                            for (i in 0 until BLOCK) {
                                quadOut[i * 4] = (src[i * 2] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                                quadOut[i * 4 + 1] = (src[i * 2 + 1] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                                quadOut[i * 4 + 2] = (hpF[i * 2] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                                quadOut[i * 4 + 3] = (hpF[i * 2 + 1] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            }
                            a.write(quadOut, 0, BLOCK * 4)
                        } else {
                            val n = rs.process(src, BLOCK, rsOut)
                            val n2 = rs2.process(hpF, BLOCK, rsOut2)
                            for (i in 0 until n) {
                                quadOut[i * 4] = rsOut[i * 2]
                                quadOut[i * 4 + 1] = rsOut[i * 2 + 1]
                                quadOut[i * 4 + 2] = if (i < n2) rsOut2[i * 2] else 0.toShort()
                                quadOut[i * 4 + 3] = if (i < n2) rsOut2[i * 2 + 1] else 0.toShort()
                            }
                            a.write(quadOut, 0, n * 4)
                        }
                    } else if (outRate == Cfg.SR) {
                        for (i in 0 until BLOCK * 2) spk[i] = (src[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        a.write(spk, 0, spk.size)
                    } else {
                        val n = rs.process(src, BLOCK, rsOut)
                        a.write(rsOut, 0, n * 2)
                    }
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
            drainKeys()
            renderKeys()
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
            // 3.40 CUE (only when the loop said this block has a headphone mix)
            val cq = cueRender
            val pfx = if (cq && cuePads) padFx2 else null
            if (pfx != null) {
                // the second drum bus is set like the first
                System.arraycopy(drumFx.on, 0, pfx.on, 0, FxChain.N)
                System.arraycopy(drumFx.a, 0, pfx.a, 0, FxChain.N)
                System.arraycopy(drumFx.b, 0, pfx.b, 0, FxChain.N)
                pfx.order = drumFx.order
                pfx.intensity = drumFx.intensity
                drumHeavy2.amount = drumHeavy.amount
            }
            val cmx = cueMix.coerceIn(0f, 1f)
            val hv = cueVol.let { if (it < 0f) mv else it }
            val split = cueSplit
            val swap = cueSwap

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
                var cl = 0f                        // (3.40: the sources in CUE, as they sit in the mix)
                var cr = 0f
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
                        if (cq && cue[i]) {
                            cl += s * tl[i]
                            cr += s * tr[i]
                        }
                        val ab = abs(s * t.volume)
                        if (ab > tpk[i]) tpk[i] = ab
                    }
                }
                var pm = 0f
                var pmMain = 0f                    // (3.40: the pads that are not in CUE)
                for (k in pads.indices) {
                    val p = pads[k]
                    val pvk = renderPad(p) * (if (p.mute) 0f else p.volume)
                    pm += pvk
                    if (pfx != null && !cue[CUE_PAD + k]) pmMain += pvk
                }
                pm = drumFx.process(drumHeavy.process(pm * pv), bpmNow, beatPos) * gPads
                l += pm
                r += pm
                if (pfx != null) {
                    // the pads in CUE = the whole drum bus less the bus of the others
                    val pc = pm - pfx.process(drumHeavy2.process(pmMain * pv), bpmNow, beatPos) * gPads
                    cl += pc
                    cr += pc
                }
                val bz = bass.render() * gBass * bassBoost * SYNTH_LIFT_BASS
                val sy = analog.fx.process(analog.render() * synthBoost * SYNTH_LIFT_ANALOG, bpmNow, beatPos) * gSynth
                l += bz + sy
                r += bz + sy
                vocal.render()
                l += vocal.outL * gRest
                ambient.render()
                l += ambient.outL * gAmb
                r += ambient.outR * gAmb
                // the two guitars, a little apart: rhythm left of centre, riff right
                val gR = rhythmGtr.render() * gRhythm * SYNTH_LIFT_GTR
                val gF = riffGtr.render() * gRiff * SYNTH_LIFT_GTR
                l += gR * 0.9f + gF * 0.55f
                r += gR * 0.55f + gF * 0.9f
                r += vocal.outR * gRest
                bank.renderClip()
                bank.renderLayers()
                l += bank.outL * gRest
                r += bank.outR * gRest
                if (stemOn) {
                    stemBuf[ST][f2] = pm * mv; stemBuf[ST][f2 + 1] = pm * mv
                    stemBuf[ST + 1][f2] = bz * mv; stemBuf[ST + 1][f2 + 1] = bz * mv
                    stemBuf[ST + 2][f2] = sy * mv; stemBuf[ST + 2][f2 + 1] = sy * mv
                    stemBuf[ST + 3][f2] = gR * 0.9f * mv; stemBuf[ST + 3][f2 + 1] = gR * 0.55f * mv
                    stemBuf[ST + 4][f2] = gF * 0.55f * mv; stemBuf[ST + 4][f2 + 1] = gF * 0.9f * mv
                    stemBuf[ST + 5][f2] = ambient.outL * gAmb * mv; stemBuf[ST + 5][f2 + 1] = ambient.outR * gAmb * mv
                    stemBuf[ST + 6][f2] = vocal.outL * gRest * mv; stemBuf[ST + 6][f2 + 1] = vocal.outR * gRest * mv
                    stemBuf[ST + 7][f2] = bank.outL * gRest * mv; stemBuf[ST + 7][f2 + 1] = bank.outR * gRest * mv
                }
                if (cq) {
                    if (cue[CUE_BASS]) { cl += bz; cr += bz }
                    if (cue[CUE_SYNTH]) { cl += sy; cr += sy }
                    if (cue[CUE_VOICE]) { cl += vocal.outL * gRest; cr += vocal.outR * gRest }
                    if (cue[CUE_AMBIENT]) { cl += ambient.outL * gAmb; cr += ambient.outR * gAmb }
                    if (cue[CUE_RHYTHM]) { cl += gR * 0.9f; cr += gR * 0.55f }
                    if (cue[CUE_RIFF]) { cl += gF * 0.55f; cr += gF * 0.9f }
                    if (cue[CUE_SONG]) { cl += bank.outL * gRest; cr += bank.outR * gRest }
                }
                val pr = renderPad(preview) * 0.8f
                l += pr
                r += pr
                if (keysOn) {                      // 3.32: the full-screen keyboard, where the preview voice is
                    val kv = keyBuf[f] * KEYS_LEVEL
                    l += kv
                    r += kv
                }
                if (cq) {
                    // 3.40: main = the mix less the CUE sources; headphones = the CUE sources + CUE MIX x main.
                    // (l / r stay the whole mix: that is what every recording below gets)
                    val ml = l - cl
                    val mr = r - cr
                    val hl = cl + cmx * ml
                    val hr = cr + cmx * mr
                    if (split) {
                        // split cable: one side the main mix, the other the headphone mix, both mono
                        val a = softClip((ml + mr) * 0.5f * mv)
                        val b = softClip((hl + hr) * 0.5f * hv)
                        mainF[f2] = if (swap) b else a
                        mainF[f2 + 1] = if (swap) a else b
                    } else {
                        mainF[f2] = softClip(ml * mv)
                        mainF[f2 + 1] = softClip(mr * mv)
                    }
                    hpF[f2] = softClip(hl * hv)
                    hpF[f2 + 1] = softClip(hr * hv)
                }
                val preMono = (l + r) * 0.5f       // (3.19 RESAMPLE: caught before MASTER, at full level)
                l = softClip(l * mv)
                r = softClip(r * mv)
                val al = abs(l)
                if (al > mpk) mpk = al
                val ar = abs(r)
                if (ar > mpk) mpk = ar
                fout[f * 2] = l
                fout[f * 2 + 1] = r
                val gb = grabBuf
                if (gb != null && grabPos < gb.size) gb[grabPos++] = softClip(preMono)
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

    // ---------------- 3.36 → MIX: offline stems (MixSend.kt) ----------------
    // Only for an Engine of its own that was never start()ed (no audio thread, no output, no MIDI): the same
    // sequencer (onTick: the parts, the song, the automation, the step triggers) and the same voice code as
    // playback, one source at a time. The engine that plays is never touched by it.

    /** Offline: the transport starts at bar 1, beat 1 (what Play does in handleRequests; nothing is sent). */
    fun offlineBegin() {
        if (running) return
        prepare = PREP_NONE
        follow = false
        sendClock = false
        playing = true
        paused = false
        tickCount = 0L
        tickAcc = Cfg.SR * 60.0 / (bpmF * 24.0)
        beatPos = 0.0
        curStep = -1
        for (t in tracks) t.playing = false
        if (bank.songOn) bank.songStart() else bank.whileStopped()
    }

    /**
     * Offline: the next [n] frames (at most BLOCK) of one source into [out] (stereo, interleaved), as renderBlock
     * mixes it: after its own effects, volume and mute, where it sits left / right, before the master.
     * [src]: 0 .. TRACKS-1 a sampler track, PREP_PADS (the drum pad [pad], alone through the drum bus), PREP_BASS,
     * PREP_SYNTH, PREP_RHYTHM, PREP_RIFF, PREP_AMBIENT or OFF_CLIP (the part's recording, as the song plays it).
     * A sampler track comes out without its pan (the same in both channels, at its volume).
     */
    fun offlineBlock(src: Int, pad: Int, out: FloatArray, n: Int) {
        if (running) return
        auto.block()
        val bpmNow = bpmF
        val spt = Cfg.SR * 60.0 / (bpmNow * 24.0)
        val beatInc = 1.0 / (Cfg.SR * 60.0 / bpmNow)
        val pv = padVol
        analog.bpm = bpmNow
        val trk = if (src in 0 until Cfg.TRACKS) tracks[src] else null
        val pd = if (src == PREP_PADS && pad in pads.indices) pads[pad] else null
        val boost = when {
            trk != null -> 10f.pow(trk.boostDb / 20f)
            src == PREP_BASS -> 10f.pow(bass.boostDb / 20f)
            src == PREP_SYNTH -> 10f.pow(analog.boostDb / 20f)
            else -> 1f
        }
        val tv = trk?.volume ?: 0f
        for (f in 0 until minOf(n, BLOCK, out.size / 2)) {
            if (playing) {
                tickAcc += 1.0
                if (tickAcc >= spt) {
                    tickAcc -= spt
                    onTick(f, 0L)
                }
                beatPos += beatInc
            }
            var l = 0f
            var r = 0f
            if (trk != null) {
                var s = renderTrack(trk) * boost
                s = trk.fx.process(s, bpmNow, beatPos)
                if (!trk.mute) {
                    l = s * tv
                    r = l
                }
            } else if (pd != null) {
                val pm = renderPad(pd) * (if (pd.mute) 0f else pd.volume)
                l = drumFx.process(drumHeavy.process(pm * pv), bpmNow, beatPos)
                r = l
            } else when (src) {
                PREP_BASS -> {
                    l = bass.render() * boost * SYNTH_LIFT_BASS
                    r = l
                }
                PREP_SYNTH -> {
                    l = analog.fx.process(analog.render() * boost * SYNTH_LIFT_ANALOG, bpmNow, beatPos)
                    r = l
                }
                PREP_RHYTHM -> {
                    val g = rhythmGtr.render() * SYNTH_LIFT_GTR
                    l = g * GTR_NEAR
                    r = g * GTR_FAR
                }
                PREP_RIFF -> {
                    val g = riffGtr.render() * SYNTH_LIFT_GTR
                    l = g * GTR_FAR
                    r = g * GTR_NEAR
                }
                PREP_AMBIENT -> {
                    ambient.render()
                    l = ambient.outL
                    r = ambient.outR
                }
                OFF_CLIP -> {
                    bank.renderClip()
                    l = bank.outL
                    r = bank.outR
                }
            }
            out[f * 2] = l
            out[f * 2 + 1] = r
        }
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
                    seenMeter = Pat.meter
                    barStep = (spp % Pat.spbOf(seenMeter)).coerceAtLeast(0)
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
            val nr = t.noteReq
            if (nr != Track.NOTE_NONE) {
                t.noteReq = Track.NOTE_NONE
                trigger(t, nr, 1f)
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
            // (3.28: the meter's bar. The meter changed while playing: bar and pattern start again together)
            val m = Pat.meter
            if (tickCount == 0L || m != seenMeter) {
                seenMeter = m
                patCount = 0L
                barStep = 0
            }
            val spbNow = Pat.spbOf(m)
            if (barStep >= spbNow) barStep = 0
            val b = barStep
            barStep = if (b + 1 >= spbNow) 0 else b + 1
            if (b == 0) {                                   // 3.19 RESAMPLE armed for bars: it starts on this frame
                val gp = grabPending
                if (gp != null) {
                    grabPos = 0
                    grabBuf = gp
                    grabPending = null
                }
            }
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
            val s = Pat.stepAt((patCount % Pat.playLen()).toInt())
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
                val bar = (tickCount / (6L * Pat.spb())).toInt()
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
            val want = t.fitBars * Pat.barFrames(bpmF)            // samples in N bars (of the meter)
            val fit = (e - a) / want
            if (t.keepPitch) {
                t.stretch = true
                t.tlRate = fit
            } else {
                t.rate = fit * 2.0.pow(t.pitch / 12.0)
            }
        }
        // 3.41: the small loop (in source samples, so it keeps its length in time at any pitch)
        if (t.micro > 0) {
            t.microLen = Pat.barFrames(bpmF) / t.micro * (if (t.stretch) t.tlRate else t.rate)
            t.microLeft = (e - a).toDouble()
        } else t.microLen = 0.0
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
        // 3.41: the small loop repeats the start of the part (its end when reversed); it lasts as long as the part
        // would (for ever with LOOP on)
        if (t.microLen > 1.0) {
            if (!t.loop) {
                t.microLeft -= rate
                if (t.microLeft <= 0.0) return false
            }
            val ma: Double
            val mb: Double
            if (t.dir > 0) { ma = t.vStart; mb = (t.vStart + t.microLen).coerceAtMost(t.vEnd) }
            else { mb = t.vEnd; ma = (t.vEnd - t.microLen).coerceAtLeast(t.vStart) }
            val len = (mb - ma).coerceAtLeast(2.0)
            if (t.dir > 0 && p >= mb) p = ma + (p - mb) % len
            else if (t.dir < 0 && p < ma) p = mb - (ma - p) % len - 1.0
            t.tl = p.coerceIn(t.vStart, t.vEnd - 1.0)
            return true
        }
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

    /** 3.40: the same, into floats (the headphone output of CUE). */
    fun processF(inp: FloatArray, n: Int, out: FloatArray): Int {
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
                out[k * 2 + ch] = ((c3 * t + c2) * t + c1) * t + x0
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
