package com.example.fm1burger

import kotlin.math.PI
import kotlin.math.sin

/**
 * 3.44: ROBOT, the SAMPLING page's robot voice, on its own button (off unless you turn it on).
 * A ring modulator (the voice times a 50 Hz tone) and a short metallic comb (5 ms), mixed by [amount].
 * One per track (audio thread); a fresh one for an offline render.
 */
class RobotFx {
    private var ph = 0.0
    private val buf = FloatArray(Cfg.SR / 200 + 1)      // 5 ms
    private var pos = 0

    fun process(x: Float, amount: Float): Float {
        if (amount <= 0f) return x
        ph += 50.0 / Cfg.SR
        if (ph >= 1.0) ph -= 1.0
        val rm = x * sin(2.0 * PI * ph).toFloat() * 1.4f
        val wet = x * (1f - amount) + rm * amount
        val y = wet + buf[pos] * (0.55f * amount)
        buf[pos] = y
        pos = (pos + 1) % buf.size
        return y * 0.8f
    }
}
