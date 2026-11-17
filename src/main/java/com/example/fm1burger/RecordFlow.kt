package com.example.fm1burger

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.view.View
import android.widget.CheckBox
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The R button (record from any input) and the S button (sample the FM-1 over USB). */
class RecordFlow(private val a: MainActivity) {

    private var recTrack = -1
    private var recLabel = ""
    private var lastNote = -1

    fun active(): Boolean = a.recorder.running

    /** One line for the header while recording. */
    fun statusLine(): String {
        val r = a.recorder
        if (!r.running) return ""
        if (r.waitingGate) return "● REC track ${recTrack + 1}: waiting for the next bar..."
        var s = String.format(
            Locale.US, "● REC track %d · %.1f s · level %d%%",
            recTrack + 1, r.seconds, (r.peak * 100).toInt()
        )
        if (r.clipped) s += " · CLIPPING, lower the volume on your instrument"
        return s
    }

    fun isRecordingTrack(ti: Int): Boolean = a.recorder.running && recTrack == ti

    fun onRecordButton(ti: Int) {
        if (a.recorder.running) {
            if (recTrack == ti) a.recorder.stop() else a.ui.toast("Another track is recording")
            return
        }
        recordDialog(ti)
    }

    fun onSampleButton(ti: Int) {
        if (a.recorder.running) {
            if (recTrack == ti) a.recorder.stop() else a.ui.toast("Another track is recording")
            return
        }
        sampleDialog(ti)
    }

    private fun check(text: String, on: Boolean): CheckBox {
        val c = CheckBox(a)
        c.text = text
        c.isChecked = on
        return c
    }

    private fun tip(text: String): View {
        val t = a.ui.text(text, 12f, Theme.DIM)
        t.setPadding(0, a.ui.dp(6), 0, a.ui.dp(2))
        return t
    }

    private fun recordDialog(ti: Int) {
        val ui = a.ui
        val devs = a.recorder.inputs()
        val box = ui.col()
        box.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(4))
        box.addView(ui.text("Input", 13f, Theme.DIM))
        val src = ui.spinner(devs.map { a.recorder.label(it) }.ifEmpty { listOf("No input found") }) {}
        box.addView(src)
        box.addView(ui.text("Length", 13f, Theme.DIM))
        val len = ui.spinner(listOf("Manual stop (max 30 s)", "1 bar", "2 bars", "4 bars")) {}
        box.addView(len)
        val wait = check("Start at the next bar (only while playing)", true)
        val auto = check("Auto level to 70% after recording", true)
        box.addView(wait)
        box.addView(auto)
        box.addView(tip("3.5 mm or line input: turn the volume down a little on your instrument first so it does not clip."))

        AlertDialog.Builder(a)
            .setTitle("Record into track ${ti + 1}")
            .setView(ScrollView(a).also { it.addView(box) })
            .setPositiveButton("Record") { _, _ ->
                val dev = devs.getOrNull(src.selectedItemPosition)
                val bars = intArrayOf(0, 1, 2, 4)[len.selectedItemPosition]
                val maxSec = if (bars == 0) 30.0 else bars * 4 * 60.0 / a.engine.bpmF
                val gate: (() -> Boolean)? =
                    if (wait.isChecked && a.engine.playing) makeBarGate() else null
                begin(ti, dev, maxSec, gate, auto.isChecked, "Rec", -1)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun noteName(n: Int): String {
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        return "$n  (${names[n % 12]}${n / 12 - 1})"
    }

    private fun sampleDialog(ti: Int) {
        val ui = a.ui
        val devs = a.recorder.inputs()
        val box = ui.col()
        box.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(4))
        box.addView(ui.text("Audio input (FM-1 over USB or a line in)", 13f, Theme.DIM))
        val src = ui.spinner(devs.map { a.recorder.label(it) }.ifEmpty { listOf("No input found") }) {}
        val usbIdx = devs.indexOfFirst { a.recorder.isUsbLike(it) }
        if (usbIdx >= 0) src.setSelection(usbIdx)
        box.addView(src)
        val canMidi = a.midi.connected
        val sendNote = check("Play a note on the FM-1 while sampling", canMidi)
        sendNote.isEnabled = canMidi
        box.addView(sendNote)
        val notes = (36..84).toList()
        box.addView(ui.text("Note", 13f, Theme.DIM))
        val noteSp = ui.spinner(notes.map { noteName(it) }) {}
        noteSp.setSelection(notes.indexOf(60))
        box.addView(noteSp)
        box.addView(ui.text("Length", 13f, Theme.DIM))
        val secs = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 6.0, 8.0)
        val lenSp = ui.spinner(secs.map { "${it.toInt()} s" }) {}
        lenSp.setSelection(1)
        box.addView(lenSp)
        val auto = check("Auto level to 70% after sampling", true)
        box.addView(auto)
        if (!canMidi) box.addView(tip("Tip: press SYNC first to connect the FM-1 over MIDI, then the app can play the note for you."))
        box.addView(tip("The line / 3.5 mm input can clip. If you see CLIPPING, lower the volume on the FM-1 and sample again."))

        AlertDialog.Builder(a)
            .setTitle("Sample the FM-1 into track ${ti + 1}")
            .setView(ScrollView(a).also { it.addView(box) })
            .setPositiveButton("Sample") { _, _ ->
                val dev = devs.getOrNull(src.selectedItemPosition)
                val note = if (sendNote.isChecked && canMidi) notes[noteSp.selectedItemPosition] else -1
                begin(ti, dev, secs[lenSp.selectedItemPosition], null, auto.isChecked, "FM1", note)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun makeBarGate(): () -> Boolean {
        var last = a.engine.curStep
        return {
            val c = a.engine.curStep
            val hit = c == 0 && last != 0
            last = c
            hit
        }
    }

    private fun begin(
        ti: Int,
        dev: AudioDeviceInfo?,
        maxSec: Double,
        gate: (() -> Boolean)?,
        auto: Boolean,
        base: String,
        note: Int
    ) {
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            a.ui.toast("Allow the microphone, then press the button again")
            return
        }
        recTrack = ti
        recLabel = base
        val err = a.recorder.start(dev, maxSec, gate) { res ->
            a.runOnUiThread { finish(res, ti, auto, base, note) }
        }
        if (err != null) {
            recTrack = -1
            a.ui.toast(err)
            return
        }
        if (note >= 0) {
            lastNote = note
            a.midi.noteOn(note, 100)
        }
        a.refreshCurrent()
    }

    private fun finish(res: RecResult?, ti: Int, auto: Boolean, base: String, note: Int) {
        recTrack = -1
        if (note >= 0) a.midi.noteOff(note)
        if (res == null) {
            a.ui.toast("Nothing was recorded")
            a.refreshCurrent()
            return
        }
        val d = res.data
        if (auto) {
            val p = Wav.peak(d)
            if (p > 0.001f) {
                val g = 0.7f / p
                for (i in d.indices) d[i] = d[i] * g
            }
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = a.store.saveNew("${base}_$stamp", d, res.rate)
        a.assignTrack(ti, f)
        var msg = "Saved ${f.name} to the library and loaded it into track ${ti + 1}"
        if (res.rawPeak >= 0.98f) {
            msg += "\nThe input was clipping. Lower the volume on your instrument and record again for a clean sample."
        }
        a.ui.toast(msg)
        a.refreshCurrent()
    }
}
