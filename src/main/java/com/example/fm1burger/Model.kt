package com.example.fm1burger

object Cfg {
    const val SR = 44100
    const val TRACKS = 4
    const val STEPS = 16
    const val PADS = 8
    const val FM1_BUDGET = 496 * 1024
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
}
