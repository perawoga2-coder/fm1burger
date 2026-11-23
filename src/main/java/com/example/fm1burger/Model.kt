package com.example.fm1burger

object Cfg {
    const val SR = 44100
    const val TRACKS = 5      // 3.19: five sampler tracks (SAMPLING page)
    /** Room for the longest pattern (4 bars of 16th notes). The pattern plays [Pat.len] of them. */
    const val STEPS = 64
    /** Drum pads: 2 banks (A, B) of 16. */
    const val PADS = 32
    const val PAD_BANK = 16

    /** Pad name: A1 .. A16, B1 .. B16. */
    fun padName(i: Int): String = "${'A' + i / PAD_BANK}${i % PAD_BANK + 1}"
    const val FM1_BUDGET = 496 * 1024
}

/**
 * Pattern length and the bar the step editors show. One length for every sequencer, so all
 * parts stay in step: 1, 2 or 4 bars (16 / 32 / 64 steps). Editors show one bar (16 steps) at a time.
 */
object Pat {
    const val BAR = 16
    val LENGTHS = intArrayOf(16, 32, 64)
    @Volatile var len = 16
    /** The bar shown in the step editors (0 based). */
    @Volatile var view = 0
    /** The editors jump to the bar that is playing. */
    @Volatile var follow = true

    // 3.28 METER: the time signature. A bar is spb() steps (16ths) long; it is stored in a slot of 16 steps (32 for
    // 5/4 and 12/8), so the steps past a shorter bar are kept, not played, and changing the meter moves no step.
    val METERS = arrayOf("4/4", "3/4", "2/4", "6/8", "12/8", "5/4", "7/8")
    private val SPB = intArrayOf(16, 12, 8, 12, 24, 20, 14)
    private val ACC = arrayOf(intArrayOf(0, 4, 8, 12), intArrayOf(0, 4, 8), intArrayOf(0, 4), intArrayOf(0, 6),
        intArrayOf(0, 6, 12, 18), intArrayOf(0, 4, 8, 12, 16), intArrayOf(0, 4, 8))
    @Volatile var meter = 0
    fun spb(): Int = SPB[meter.coerceIn(0, SPB.size - 1)]
    fun spbOf(m: Int): Int = SPB[m.coerceIn(0, SPB.size - 1)]
    fun slot(): Int = if (spb() > BAR) 2 * BAR else BAR
    /** the slot of meter m (see slot()) */
    fun slotOf(m: Int): Int = if (spbOf(m) > BAR) 2 * BAR else BAR
    /** audio frames (as a real number) in one bar of the meter at this tempo: a 16th is SR * 15 / bpm frames */
    fun barFrames(bpm: Double): Double = Cfg.SR * 15.0 / bpm * spb()
    /** seconds in one bar of the meter at this tempo */
    fun barSeconds(bpm: Double): Double = 15.0 / bpm * spb()
    /** a beat of the meter starts at step i of the bar (the keys show a mark) */
    fun accent(i: Int): Boolean = i in ACC[meter.coerceIn(0, ACC.size - 1)]
    /** which beat step i of the bar is in (0, 1, 2 ...: the editors shade every other beat) */
    fun beatOf(i: Int): Int = ACC[meter.coerceIn(0, ACC.size - 1)].count { it <= i } - 1
    /** the pattern lengths this meter allows (in stored steps): 1, 2, 4 bars that fit the 64 steps */
    fun lengths(): IntArray = intArrayOf(1, 2, 4).map { it * slot() }.filter { it <= Cfg.STEPS }.toIntArray()
    /** the steps the pattern plays */
    fun playLen(): Int {
        // (meter and len are read once: another thread may change them meanwhile)
        val sp = spb()
        val sl = if (sp > BAR) 2 * BAR else BAR
        return maxOf(1, len / sl) * sp
    }
    /** pattern position p (0 .. playLen) -> the stored step (always inside 0 .. Cfg.STEPS - 1) */
    fun stepAt(p: Int): Int {
        val sp = spb()
        val sl = if (sp > BAR) 2 * BAR else BAR
        val q = Math.floorMod(p, maxOf(1, len / sl) * sp)
        return (q / sp * sl + q % sp).coerceIn(0, Cfg.STEPS - 1)
    }
    /** the step played after stored step s: the next one of its bar, or the first step of the next bar (wrapping) */
    fun nextStep(s: Int): Int {
        val sp = spb()
        val sl = if (sp > BAR) 2 * BAR else BAR
        val nb = maxOf(1, len / sl)
        val i = s % sl
        val r = if (i + 1 < sp) s + 1 else ((s / sl + 1) % nb) * sl
        return r.coerceIn(0, Cfg.STEPS - 1)
    }

    /**
     * The bar slot changes from [oldSlot] to [newSlot] steps: calls [mv] (from, to) for every step of bars 2.. that
     * moves (bar b: b * oldSlot -> b * newSlot, the steps both slots hold), in an order that overwrites nothing still
     * to be moved (growing: the last bar first; shrinking: the first). Only bars that fit the 64 steps both ways.
     */
    fun moveBars(oldSlot: Int, newSlot: Int, bars: Int, mv: (Int, Int) -> Unit) {
        if (oldSlot == newSlot || oldSlot <= 0 || newSlot <= 0) return
        val n = minOf(oldSlot, newSlot)
        val nb = minOf(bars, Cfg.STEPS / oldSlot, Cfg.STEPS / newSlot)
        if (newSlot > oldSlot) {
            for (b in nb - 1 downTo 1) for (i in n - 1 downTo 0) mv(b * oldSlot + i, b * newSlot + i)
        } else {
            for (b in 1 until nb) for (i in 0 until n) mv(b * oldSlot + i, b * newSlot + i)
        }
    }

    fun bars(): Int = maxOf(1, len / slot())
    fun viewBar(): Int = view.coerceIn(0, bars() - 1)
    /** The first step of the bar shown. */
    fun base(): Int = viewBar() * slot()
}

class Step {
    @Volatile var on = false
    @Volatile var pitch = 0
    @Volatile var level = 1f
}

class Track(val index: Int) {
    @Volatile var sampleName = ""
    @Volatile var data: FloatArray? = null
    @Volatile var volume = 0.8f
    @Volatile var pan = 0f
    @Volatile var mute = false
    @Volatile var loop = false
    @Volatile var pitch = 0
    @Volatile var start = 0f
    @Volatile var end = 1f
    @Volatile var reverse = false
    /** Extra gain before the effects, 0 .. +12 dB (for quiet samples, and to drive the effects harder). */
    @Volatile var boostDb = 0f

    // ---- loops (LOOP mode) ----
    @Volatile var pingPong = false        // forward / back / forward ... (with reverse on: starts backwards)
    @Volatile var loopA = 0f              // loop points inside start..end, 0..1
    @Volatile var loopB = 1f
    @Volatile var xfadeMs = 0f            // crossfade at the loop point, 0..100 ms
    @Volatile var fitBars = 0             // 0 off, or the loop is stretched to 1 / 2 / 4 / 8 bars of the tempo
    @Volatile var keepPitch = false       // fit without changing the pitch (time stretch) instead of like a tape

    // ---- 3.41: the SAMPLING page's small loop: the first 1/micro of a bar (at the tempo) repeats for as long as the part kept lasts ----
    @Volatile var micro = 0               // 0 off, or 32 / 16 / 8 / 4 / 2 / 1 (1/32 bar .. one bar)
    @Volatile var microLast = 8           // the length the LOOP button turns back on
    // ---- 3.44: ROBOT (off = 0, else how much, 0.1 .. 1) ----
    @Volatile var robot = 0f
    /** 3.46: start / end moved while it plays (the waveform slide): the audio thread follows without restarting */
    @Volatile var slideReq = false
    var xfPos = 0.0                       // (audio thread) the old position faded out after a jump (no click)
    var xfLeft = 0
    @Volatile var robotAmt = 0.6f         // what the ROBOT button turns back on
    val robotFx = RobotFx()               // (audio thread)

    /**
     * 3.45: a new sound (recorded, loaded, picked) starts clean, so it sounds as recorded: no pitch, no ROBOT,
     * no effects, no loop or slices, the whole sound. Volume and pan stay.
     */
    fun freshSound() {
        pitch = 0; robot = 0f; micro = 0; loop = false; reverse = false; boostDb = 0f
        start = 0f; end = 1f
        pingPong = false; loopA = 0f; loopB = 1f; xfadeMs = 0f; fitBars = 0; keepPitch = false; slices = 0
        for (k in 0 until FxChain.N) { fx.on[k] = false; fx.a[k] = FxChain.DEF_A[k]; fx.b[k] = FxChain.DEF_B[k] }
    }
    var microLen = 0.0                    // (audio thread) the loop's length in source samples
    var microLeft = 0.0                   // (audio thread) source samples left before the sound ends

    // ---- slices (STEP mode) ----
    @Volatile var slices = 0              // 0 off, 4 / 8 / 16 equal slices of start..end
    val sliceOf = IntArray(Pat.BAR) { -1 }        // which slice each step of a bar plays (-1 = slice number of the step)
    val sliceRev = BooleanArray(Pat.BAR)          // play that slice backwards

    // time stretch state (audio thread)
    var tl = 0.0
    val gPos = DoubleArray(2)
    val gAge = IntArray(2)
    var stretch = false
    var tlRate = 1.0
    val steps = Array(Cfg.STEPS) { Step() }
    val fx = FxChain()

    // state used by the audio thread
    @Volatile var auditionReq = false
    /** STEPS window piano keys: play the sample at this many semitones from its pitch (NOTE_NONE = nothing asked). */
    @Volatile var noteReq = NOTE_NONE
    @Volatile var playing = false
    var pos = 0.0
    var rate = 1.0
    var dir = 1
    var gain = 1f
    var vStart = 0.0
    var vEnd = 0.0
    @Volatile var peak = 0f

    companion object {
        const val NOTE_NONE = Int.MIN_VALUE
    }

    fun clearSteps() {
        for (s in steps) {
            s.on = false
            s.pitch = 0
            s.level = 1f
        }
    }

    fun playFraction(): Float {
        val d = data ?: return -1f
        if (!playing || d.isEmpty()) return -1f
        return (pos / d.size).toFloat().coerceIn(0f, 1f)
    }
}

class PadVoice {
    @Volatile var name = ""
    @Volatile var data: FloatArray? = null
    @Volatile var req = 0f
    @Volatile var playing = false
    var pos = 0
    var gain = 1f

    // drum sequencer pattern of this pad (one row on the Pads page)
    val on = BooleanArray(Cfg.STEPS)
    val lvl = FloatArray(Cfg.STEPS) { 1f }

    // volume and mute of this drum row
    @Volatile var volume = 1f
    @Volatile var mute = false
}

/** 3.33: when a note was last played by hand (pads, keys, keyboard): heavy background work waits a little after it. */
object Live {
    @Volatile var lastNoteMs = 0L
    fun touch() { lastNoteMs = android.os.SystemClock.uptimeMillis() }
    fun busy(): Boolean = android.os.SystemClock.uptimeMillis() - lastNoteMs < 3000L
}
