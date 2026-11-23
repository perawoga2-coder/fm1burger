package com.example.fm1burger

/**
 * 3.50: the Bassline's Bass Bank. Pick a sound, then shape it with the Bassline's sliders as before.
 *  - SYNTH: settings of the Bassline's own synth (Saw / Square, cutoff, resonance, env, decay, accent, drive)
 *  - FM-1: the FM-1 firmware's basses from the Maker Bank, played at the step's note (Saw / Square do not apply)
 */
object BassBank {
    val SYNTH = listOf("Acid", "Sub", "Fat", "Rubber", "Square", "Soft")
    val FM1 = listOf("Sqr Bass", "Sub Bass", "Wave Bass", "Wow Bass", "Fat Bass", "Fm Bass")

    /** the Maker Bank file of an FM-1 bass (its note is C3) */
    fun file(name: String) = "$name C3.wav"

    // saw, cutoff, res, envMod, decay, accent, drive
    private val P = mapOf(
        "Acid" to floatArrayOf(1f, 0.35f, 0.55f, 0.6f, 0.35f, 0.6f, 0.2f),
        "Sub" to floatArrayOf(0f, 0.15f, 0.1f, 0.1f, 0.6f, 0.2f, 0.1f),
        "Fat" to floatArrayOf(1f, 0.45f, 0.3f, 0.35f, 0.5f, 0.4f, 0.5f),
        "Rubber" to floatArrayOf(0f, 0.25f, 0.65f, 0.7f, 0.2f, 0.6f, 0.15f),
        "Square" to floatArrayOf(0f, 0.5f, 0.2f, 0.3f, 0.4f, 0.5f, 0.2f),
        "Soft" to floatArrayOf(1f, 0.2f, 0.15f, 0.2f, 0.5f, 0.3f, 0f)
    )

    /** Pick [name] for the Bassline; [done] runs on the UI thread once it is in place. */
    fun choose(a: MainActivity, name: String, done: () -> Unit) {
        val b = a.engine.bass
        val p = P[name]
        if (p != null) {
            b.smp = null
            b.saw = p[0] > 0.5f
            b.cutoff = p[1]; b.res = p[2]; b.envMod = p[3]; b.decay = p[4]; b.accentAmt = p[5]; b.drive = p[6]
            b.sound = name
            done()
            return
        }
        Thread {
            val d = try { a.store.loadEngine(file(name)) } catch (e: Exception) { null }
            a.runOnUiThread {
                if (d == null) a.ui.toast("$name is not in the Maker Bank yet (it installs in the background): try again in a moment")
                else {
                    b.smp = d
                    // the filter open, so it sounds as the FM-1 plays it; the sliders shape it from there
                    b.cutoff = 1f; b.res = 0.1f; b.envMod = 0.1f; b.decay = 0.6f; b.accentAmt = 0.4f; b.drive = 0.05f
                    b.sound = name
                }
                done()
            }
        }.start()
    }

    /** At start: an FM-1 bass that was picked is loaded again (its settings came back with the rest). */
    fun restore(a: MainActivity) {
        val b = a.engine.bass
        if (b.sound !in FM1) return
        Thread {
            val d = try { a.store.loadEngine(file(b.sound)) } catch (e: Exception) { null }
            if (d != null) b.smp = d
        }.start()
    }
}
