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
 * 7 s; a copy goes into the Maker Bank too.
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
    /** 0 SLICE (chop and trim), 1 SAMPLE (on the keys), 2 KIT (every take one more key: a kit in one slot) */
    private var mode = 0
    private val slice: Boolean get() = mode == 0
    private val kit = ArrayList<FloatArray>()
    private lateinit var kitInfo: TextView
    private var busy = false
    private var takes = 0
    private var loops = 0

    // the resampling loop: the device's sound (its effects on) back into the app, and back again
    private val resBtn: Button
    private val trigBox = android.widget.CheckBox(a)
    private val backBox = android.widget.CheckBox(a)
    private var resampling = false
    private var lastLoop: FloatArray? = null
    private val againBtn: Button

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
        for ((k, n) in listOf("SLICE: chop and trim", "SAMPLE: on the keys", "KIT: a hit per key").withIndex()) {
            val b = ui.button(n) { mode = k; paint() }
            b.textSize = 12f
            modeBtns.add(b)
            m.addView(b, ui.w(1f, 2))
        }
        view.addView(m, ui.fullWidth(4))
        val kr = ui.row()
        kitInfo = ui.text("", 12f, Theme.DIM)
        kr.addView(kitInfo, ui.w(1f, 3))
        kr.addView(ui.button("New kit") {
            kit.clear()
            paint()
            ui.toast("New kit: the next take is the first key (C4)")
        }, ui.w(0f, 3))
        view.addView(kr, ui.fullWidth(2))

        view.addView(ui.text("⟲ Resampling loop", 13f, Theme.ACCENT, true), ui.fullWidth(12))
        view.addView(ui.text("Shape the sound on the device (SLICER, delay, reverb, its keys), then record it back here over USB: " +
            "it comes back with the effects in it. Send it back again, shape it again, as many rounds as you like.",
            11f, Theme.DIM), ui.fullWidth(2))
        resBtn = ui.button("⟲ RESAMPLE from the device") { resample() }
        resBtn.textSize = 16f
        view.addView(resBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(60)).also {
            it.topMargin = ui.dp(6)
        })
        trigBox.text = "Play it on the device while resampling (a MIDI note to its track)"
        trigBox.setTextColor(Theme.TEXT)
        trigBox.isChecked = true
        view.addView(trigBox)
        backBox.text = "Send the result straight back (into the next slot, so the one before stays)"
        backBox.setTextColor(Theme.TEXT)
        backBox.isChecked = true
        view.addView(backBox)
        againBtn = ui.button("Send the last resample back") {
            val d = lastLoop
            if (d != null && !busy) send(d, "Loop_" + String.format(java.util.Locale.US, "%02d", loops), (slot + 1) % Fm1SampleLink.SLOTS, true)
        }
        againBtn.visibility = View.GONE
        view.addView(againBtn, ui.fullWidth(4))
        view.visibility = View.GONE
        paint()
        // the device's REC (on its SLICES page) records a take here: into the slot its track plays
        a.sampleLink().onEvent = { kind, s, t ->
            a.runOnUiThread {
                if (kind == 1 && Features.labs()) {
                    if (s in 0 until Fm1SampleLink.SLOTS) slot = s
                    if (t in 0 until 4) track = t
                    if (mode == 1) mode = 0
                    paint()
                    rec()
                }
            }
        }
    }

    private fun paint() {
        for (k in slotBtns.indices) ui.styleButton(slotBtns[k], k == slot)
        for (k in trackBtns.indices) ui.styleButton(trackBtns[k], k == track)
        for (k in modeBtns.indices) ui.styleButton(modeBtns[k], k == mode)
        if (::kitInfo.isInitialized) {
            kitInfo.visibility = if (mode == 2) View.VISIBLE else View.GONE
            kitInfo.text = if (kit.isEmpty()) "KIT: each take adds a key (C4, C#4, D4 …), up to 16 hits, 7 s in all"
            else "KIT: ${kit.size} hit${if (kit.size > 1) "s" else ""} on C4 … " + PianoRollView.noteName(59 + kit.size)
        }
    }

    private fun rec() {
        if (busy || resampling) return
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
        takes++
        if (mode == 2) {
            if (kit.size >= 16) {
                status.text = "The kit has 16 hits: press New kit for another one"
                return
            }
            kit.add(d)
            paint()
            sendKit()
            return
        }
        send(d, "Hit_" + String.format(java.util.Locale.US, "%02d", takes), slot, false)
    }

    /** The whole kit into the slot again (a slot is written in one go), SAMPLE on the track: a hit per key. */
    private fun sendKit() {
        busy = true
        recBtn.isEnabled = false
        resBtn.isEnabled = false
        val s = slot
        val t = track
        val hits = ArrayList(kit)
        val device = a.hub.slotDevice ?: "the device"
        status.text = "Sending the kit (${hits.size} hits) to $device USR${s + 1}…"
        Thread {
            val msg = try {
                try {
                    a.store.saveNew("Kit_hit_" + String.format(java.util.Locale.US, "%02d", hits.size), hits.last(), Cfg.SR)
                } catch (ex: Exception) {
                }
                val len = a.sampleLink().uploadKit(s, "KIT", hits) { f ->
                    a.runOnUiThread { status.text = "Sending the kit to $device USR${s + 1}: ${(f * 100).toInt()} %" }
                }
                if (!a.sampleLink().show(s, t, false)) a.sampleLink().prepareTrack(t, "SAMPLE", s)
                "Kit ready ($len): $device track ${t + 1}, keys C4 … " + PianoRollView.noteName(59 + hits.size) +
                    ". REC again: the next key."
            } catch (ex: Exception) {
                "Not sent: " + (ex.message ?: "?")
            }
            a.runOnUiThread {
                if (msg.startsWith("Not sent") && kit.isNotEmpty()) kit.removeAt(kit.size - 1)   // (not on the device)
                busy = false
                recBtn.isEnabled = true
                resBtn.isEnabled = true
                status.text = msg
                paint()
                a.libraryScreen.refresh()
            }
        }.start()
    }

    /** Into slot [s], on the chosen track; [next]: the slot becomes the current one (the loop moves on). */
    private fun send(d: FloatArray, name: String, s: Int, next: Boolean) {
        busy = true
        val device = a.hub.slotDevice ?: "the device"
        status.text = String.format(java.util.Locale.US, "Sending %.2f s to %s USR%d…", d.size / Cfg.SR.toDouble(), device, s + 1)
        recBtn.isEnabled = false
        resBtn.isEnabled = false
        val t = track
        if (next) {
            slot = s
            paint()
        }
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
                resBtn.isEnabled = true
                status.text = msg
                a.libraryScreen.refresh()
            }
        }.start()
    }

    /** ⟲: record the device's USB audio (its effects in it); stop: keep it, and (checked) send it back. */
    private fun resample() {
        if (busy) return
        val r = a.recorder
        if (r.running) {
            if (resampling) {
                r.stop()
                if (trigBox.isChecked) a.midi.noteOff(60)
            }
            return
        }
        val dev = r.fm1Input()
        if (dev == null) {
            status.text = "No sound from the device over USB. Its USB audio has to be on (plugged in by USB)."
            return
        }
        val err = r.start(dev, 30.0, null) { res -> a.runOnUiThread { resampled(res) } }
        if (err != null) {
            ui.toast(err)
            return
        }
        resampling = true
        if (trigBox.isChecked) a.midi.noteOn(60, 110)
        resBtn.text = "■ STOP resampling"
        ui.styleButton(resBtn, true, Theme.RED)
    }

    private fun resampled(res: RecResult?) {
        resampling = false
        resBtn.text = "⟲ RESAMPLE from the device"
        ui.styleButton(resBtn, false)
        if (res == null || res.data.isEmpty()) {
            status.text = "Nothing came back"
            return
        }
        val d0 = if (res.rate == Cfg.SR) res.data else Wav.resample(res.data, res.rate, Cfg.SR)
        val d = trimmed(d0)
        if (d == null) {
            status.text = "Only silence came back: play the device while resampling (or check its volume)"
            return
        }
        loops++
        lastLoop = d
        againBtn.visibility = View.VISIBLE
        val name = "Loop_" + String.format(java.util.Locale.US, "%02d", loops)
        if (backBox.isChecked) {
            send(d, name, (slot + 1) % Fm1SampleLink.SLOTS, true)
        } else {
            try {
                a.store.saveNew(name, d, Cfg.SR)
                a.libraryScreen.refresh()
            } catch (ex: Exception) {
            }
            status.text = String.format(java.util.Locale.US, "%s (%.1f s) is in the Maker Bank: edit it, or send it back.", name, d.size / Cfg.SR.toDouble())
        }
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
