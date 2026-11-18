package com.example.fm1burger

object Cfg {
    const val SR = 44100
    const val TRACKS = 4
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

    fun bars(): Int = len / BAR
    fun viewBar(): Int = view.coerceIn(0, bars() - 1)
    /** The first step of the bar shown. */
    fun base(): Int = viewBar() * BAR
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
    @Volatile var playing = false
    var pos = 0.0
    var rate = 1.0
    var dir = 1
    var gain = 1f
    var vStart = 0.0
    var vEnd = 0.0
    @Volatile var peak = 0f

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
