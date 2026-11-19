package com.example.fm1burger

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Quick sample into a device: hit something (spoons, a glass, your voice) in front of the tablet's mic, stop, and the
 * sound is already in the device's sample slot with one of its tracks set to play it: trim it there, on its own
 * screen, while you hit the next one. Shown only while a device that takes samples is connected (DeviceHub).
 *
 * After a take: the silence before and after is cut, a 3 ms fade at both ends, peak normalised, at most the slot's
 * 7 s; a copy goes into the Library too.
 */
class QuickSamplePanel(private val a: MainActivity) {

    private val ui = a.ui
    val view: LinearLayout = ui.panel()
    private val title: TextView
    private val status: TextView
    private val recBtn: Button
    private val slotBtns = ArrayList<Button>()
    private val trackBtns = ArrayList<Button>()
    private val modeBtns = ArrayList<Button>()
    private var slot = 0
    private var track = 0
    private var slice = true
    private var busy = false
    private var takes = 0

    init {
        title = ui.text("", 14f, Theme.ACCENT, true)
        view.addView(title)
        view.addView(ui.text("Hit something near the mic, stop, and it is on the device: trim it there on its own screen " +
            "(SLICE: EDIT → SLICES, KNOB 1 picks the start or END, KNOB 2 moves it) while you record the next one.",
            11f, Theme.DIM), ui.fullWidth(2))

        recBtn = ui.button("● REC") { rec() }
        recBtn.textSize = 20f
        view.addView(recBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(72)).also {
            it.topMargin = ui.dp(8)
        })
        status = ui.text("", 12f, Theme.TEXT)
        view.addView(status, ui.fullWidth(4))

        val r = ui.row()
        r.addView(ui.text("Slot", 12f, Theme.DIM), ui.w(0f, 3))
        for (k in 0 until Fm1SampleLink.SLOTS) {
            val b = ui.button("USR${k + 1}") { slot = k; paint() }
            b.textSize = 12f
            slotBtns.add(b)
            r.addView(b, ui.w(1f, 2))
        }
        r.addView(ui.text("  Track", 12f, Theme.DIM), ui.w(0f, 3))
        for (k in 0 until 4) {
            val b = ui.button("${k + 1}") { track = k; paint() }
            b.textSize = 12f
            trackBtns.add(b)
            r.addView(b, ui.w(0.7f, 2))
        }
        view.addView(r, ui.fullWidth(6))
        val m = ui.row()
        for ((k, n) in listOf("SLICE: chop and trim", "SAMPLE: play on the keys").withIndex()) {
            val b = ui.button(n) { slice = k == 0; paint() }
            b.textSize = 12f
            modeBtns.add(b)
            m.addView(b, ui.w(1f, 2))
        }
        view.addView(m, ui.fullWidth(4))
        view.visibility = View.GONE
        paint()
    }

    private fun paint() {
        for (k in slotBtns.indices) ui.styleButton(slotBtns[k], k == slot)
        for (k in trackBtns.indices) ui.styleButton(trackBtns[k], k == track)
        ui.styleButton(modeBtns[0], slice)
        ui.styleButton(modeBtns[1], !slice)
    }

    private fun rec() {
        if (busy) return
        val r = a.recorder
        if (r.running) {
            r.stop()
            return
        }
        val dev = r.defaultMic()
        val err = r.start(dev, Fm1SampleLink.MAX_SECONDS + 2.0, null) { res -> a.runOnUiThread { took(res) } }
        if (err != null) {
            ui.toast(err)
            return
        }
        recBtn.text = "■ STOP"
        ui.styleButton(recBtn, true, Theme.RED)
    }

    private fun took(res: RecResult?) {
        recBtn.text = "● REC"
        ui.styleButton(recBtn, false)
        if (res == null || res.data.isEmpty()) {
            status.text = "Nothing recorded"
            return
        }
        val d0 = if (res.rate == Cfg.SR) res.data else Wav.resample(res.data, res.rate, Cfg.SR)
        val d = trimmed(d0)
        if (d == null) {
            status.text = "Only silence: hit a little closer to the mic"
            return
        }
        busy = true
        takes++
        val name = "Hit_" + String.format(java.util.Locale.US, "%02d", takes)
        val device = a.hub.slotDevice ?: "the device"
        status.text = String.format(java.util.Locale.US, "Sending %.2f s to %s USR%d…", d.size / Cfg.SR.toDouble(), device, slot + 1)
        recBtn.isEnabled = false
        val s = slot
        val t = track
        val eng = if (slice) "SLICE" else "SAMPLE"
        Thread {
            val msg = try {
                try {
                    a.store.saveNew(name, d, Cfg.SR)
                } catch (ex: Exception) {
                }
                val len = a.sampleLink().upload(s, name, d) { f ->
                    a.runOnUiThread { status.text = "Sending to $device USR${s + 1}: ${(f * 100).toInt()} %" }
                }
                if (!a.sampleLink().show(s, t, eng == "SLICE")) a.sampleLink().prepareTrack(t, eng, s)
                "Ready ($len): $device track ${t + 1} plays USR${s + 1} with $eng." +
                    (if (eng == "SLICE") " Trim it there: EDIT → SLICES." else "") + " REC again for the next one."
            } catch (ex: Exception) {
                "Not sent: " + (ex.message ?: "?")
            }
            a.runOnUiThread {
                busy = false
                recBtn.isEnabled = true
                status.text = msg
                a.libraryScreen.refresh()
            }
        }.start()
    }

    /** Cut the quiet head and tail (keeping 10 ms before the first hit), fade 3 ms, normalise; null if all quiet. */
    private fun trimmed(x: FloatArray): FloatArray? {
        var pk = 0f
        for (v in x) if (abs(v) > pk) pk = abs(v)
        if (pk < 0.01f) return null
        val thr = pk * 0.06f
        var a0 = x.indexOfFirst { abs(it) > thr }
        var a1 = x.indexOfLast { abs(it) > thr * 0.5f }
        if (a0 < 0 || a1 <= a0) return null
        a0 = (a0 - Cfg.SR / 100).coerceAtLeast(0)
        a1 = (a1 + Cfg.SR / 20).coerceAtMost(x.size - 1)
        val maxN = (Fm1SampleLink.MAX_SECONDS * Cfg.SR).toInt()
        if (a1 - a0 + 1 > maxN) a1 = a0 + maxN - 1
        val out = x.copyOfRange(a0, a1 + 1)
        val f = minOf(out.size / 4, Cfg.SR * 3 / 1000)
        for (k in 0 until f) {
            val g = k.toFloat() / f
            out[k] *= g
            out[out.size - 1 - k] *= g
        }
        val gain = 0.95f / pk
        for (k in out.indices) out[k] *= gain
        return out
    }

    /** Every screen tick: shown only while a device that takes samples is connected; the timer while recording. */
    fun tick() {
        val dev = if (Features.labs()) a.hub.slotDevice else null     // LABS: the owner only, for now
        val vis = if (dev != null) View.VISIBLE else View.GONE
        if (view.visibility != vis) view.visibility = vis
        if (dev == null) return
        val t = "Quick sample → $dev"
        if (title.text.toString() != t) title.text = t
        val r = a.recorder
        if (r.running && !busy) status.text = String.format(java.util.Locale.US, "Recording %.1f s · level %d %%", r.seconds, (r.peak * 100).toInt())
    }
}
