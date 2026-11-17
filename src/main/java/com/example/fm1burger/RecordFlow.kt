package com.example.fm1burger

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * R = record with a microphone. Tap R: a big window shows the timer and the level.
 *     Press STOP & SAVE: the sound is saved, put in the track and played once.
 * S = sample the FM-1 (USB or line in), same window, then opens in the editor.
 * Long press R or S opens the options.
 */
class RecordFlow(private val a: MainActivity) {

    private var recTrack = -1
    private var recLabel = ""
    private var recMax = 60.0
    private var lastNote = -1
    private var discard = false
    private var devName = ""
    private var recDialog: AlertDialog? = null
    private val handler = Handler(Looper.getMainLooper())

    fun active(): Boolean = a.recorder.running

    /** One line for the header while recording. */
    fun statusLine(): String {
        val r = a.recorder
        if (!r.running) return ""
        val what = if (recLabel == "FM1") "SAMPLING FM-1" else "REC MIC"
        if (r.waitingGate) return "● $what track ${recTrack + 1}: waiting for the next bar..."
        var s = String.format(
            Locale.US, "● %s track %d · %.1f / %d s · level %d%%",
            what, recTrack + 1, r.seconds, recMax.toInt(), (r.peak * 100).toInt()
        )
        if (r.clipped) s += " · CLIPPING, lower the volume"
        return s
    }

    fun isRecordingTrack(ti: Int): Boolean = a.recorder.running && recTrack == ti
    fun isSamplingTrack(ti: Int): Boolean = isRecordingTrack(ti) && recLabel == "FM1"
    fun isMicTrack(ti: Int): Boolean = isRecordingTrack(ti) && recLabel != "FM1"

    private fun stopOrToast(ti: Int) {
        if (recTrack == ti) a.recorder.stop() else a.ui.toast("Another track is recording. Stop it first.")
    }

    // ---------------- R: microphone ----------------

    fun onRecordButton(ti: Int) {
        if (a.recorder.running) {
            stopOrToast(ti)
            return
        }
        // dev = null: Android picks the right microphone by itself
        // (internal mic, or the external mic when one is plugged in).
        begin(ti, null, 60.0, null, true, "Rec", -1, false)
    }

    fun onRecordOptions(ti: Int) {
        if (a.recorder.running) {
            stopOrToast(ti)
            return
        }
        recordDialog(ti)
    }

    // ---------------- S: sample the FM-1 ----------------

    fun onSampleButton(ti: Int) {
        if (a.recorder.running) {
            stopOrToast(ti)
            return
        }
        val dev = a.recorder.fm1Input()
        if (dev == null) {
            problem(
                "No FM-1 audio input found",
                "Connect the FM-1 with the USB cable (or a line cable) and try again.\n\n" +
                    "Long press S to choose another input."
            )
            return
        }
        begin(ti, dev, 60.0, null, true, "FM1", -1, true)
    }

    fun onSampleOptions(ti: Int) {
        if (a.recorder.running) {
            stopOrToast(ti)
            return
        }
        sampleDialog(ti)
    }

    // ---------------- dialogs ----------------

    private fun problem(title: String, msg: String) {
        AlertDialog.Builder(a)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
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
        box.addView(ui.text("Microphone", 13f, Theme.DIM))
        val names = ArrayList<String>()
        names.add("Automatic (recommended)")
        for (d in devs) names.add(a.recorder.label(d))
        val src = ui.spinner(names) {}
        box.addView(src)
        box.addView(ui.text("Length", 13f, Theme.DIM))
        val len = ui.spinner(listOf("Manual stop (max 60 s)", "1 bar", "2 bars", "4 bars")) {}
        box.addView(len)
        val wait = check("Start at the next bar (only while playing)", false)
        val auto = check("Auto level to 70% after recording", true)
        box.addView(wait)
        box.addView(auto)
        box.addView(tip("An external mic on the 3.5 mm jack or a USB mic shows up in the list when it is plugged in."))

        AlertDialog.Builder(a)
            .setTitle("Record into track ${ti + 1}")
            .setView(ScrollView(a).also { it.addView(box) })
            .setPositiveButton("Record") { _, _ ->
                val dev = devs.getOrNull(src.selectedItemPosition - 1)
                val bars = intArrayOf(0, 1, 2, 4)[len.selectedItemPosition]
                val maxSec = if (bars == 0) 60.0 else bars * 4 * 60.0 / a.engine.bpmF
                val gate: (() -> Boolean)? =
                    if (wait.isChecked && a.engine.playing) makeBarGate() else null
                begin(ti, dev, maxSec, gate, auto.isChecked, "Rec", -1, false)
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
        box.addView(ui.text("Audio input (FM-1 over USB, or a line in)", 13f, Theme.DIM))
        val src = ui.spinner(devs.map { a.recorder.label(it) }.ifEmpty { listOf("No input found") }) {}
        val fm = a.recorder.fm1Input()
        val fmIdx = if (fm == null) -1 else devs.indexOfFirst { it.id == fm.id }
        if (fmIdx >= 0) src.setSelection(fmIdx)
        box.addView(src)
        val canMidi = a.midi.connected
        val sendNote = check("Play a note on the FM-1 while sampling (needs SYNC / MIDI)", canMidi)
        sendNote.isEnabled = canMidi
        box.addView(sendNote)
        val notes = (36..84).toList()
        box.addView(ui.text("Note", 13f, Theme.DIM))
        val noteSp = ui.spinner(notes.map { noteName(it) }) {}
        noteSp.setSelection(notes.indexOf(60))
        box.addView(noteSp)
        box.addView(ui.text("Length", 13f, Theme.DIM))
        val secs = doubleArrayOf(60.0, 2.0, 4.0, 8.0, 16.0, 30.0)
        val labels = listOf("Manual stop (max 60 s)", "2 s", "4 s", "8 s", "16 s", "30 s")
        val lenSp = ui.spinner(labels) {}
        box.addView(lenSp)
        val auto = check("Auto level to 70% after sampling", true)
        box.addView(auto)
        box.addView(tip("The line / 3.5 mm input can clip. If you see CLIPPING, lower the volume on the FM-1 and sample again."))

        AlertDialog.Builder(a)
            .setTitle("Sample the FM-1 into track ${ti + 1}")
            .setView(ScrollView(a).also { it.addView(box) })
            .setPositiveButton("Sample") { _, _ ->
                val dev = devs.getOrNull(src.selectedItemPosition)
                val note = if (sendNote.isChecked && canMidi) notes[noteSp.selectedItemPosition] else -1
                begin(ti, dev, secs[lenSp.selectedItemPosition], null, auto.isChecked, "FM1", note, true)
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

    // ---------------- the recording window ----------------

    private fun showRecDialog(ti: Int, sampling: Boolean) {
        val ui = a.ui
        val box = ui.col()
        box.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(8))
        val big = ui.text("", 22f, Theme.RED, true)
        val sub = ui.text("", 13f, Theme.DIM)
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        box.addView(big)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = ui.dp(10)
        box.addView(bar, lp)
        box.addView(sub)

        val title = if (sampling) "Sampling the FM-1 into track ${ti + 1}" else "Recording into track ${ti + 1}"
        val d = AlertDialog.Builder(a)
            .setTitle(title)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("STOP & SAVE") { _, _ -> a.recorder.stop() }
            .setNegativeButton("Cancel (throw away)") { _, _ ->
                discard = true
                a.recorder.stop()
            }
            .create()

        val tick = object : Runnable {
            override fun run() {
                val r = a.recorder
                if (!r.running) return
                if (r.waitingGate) {
                    big.text = "Waiting for the next bar..."
                } else {
                    big.text = String.format(Locale.US, "● %.1f s  of %d s", r.seconds, recMax.toInt())
                }
                bar.progress = (r.peak * 100).toInt().coerceIn(0, 100)
                var s = "Input: $devName\nSound level: ${(r.peak * 100).toInt()}%"
                if (r.seconds > 1.0 && r.peak < 0.01f) s += "\nNo sound is coming in!"
                if (r.clipped) s += "\nCLIPPING: too loud, lower the volume"
                sub.text = s
                handler.postDelayed(this, 100)
            }
        }
        d.setOnDismissListener { handler.removeCallbacks(tick) }
        d.show()
        recDialog = d
        handler.post(tick)
    }

    // ---------------- recording ----------------

    private fun askPermission() {
        a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        AlertDialog.Builder(a)
            .setTitle("Microphone permission needed")
            .setMessage(
                "1. When Android asks, tap ALLOW.\n" +
                    "2. Then tap R again.\n\n" +
                    "If Android does not ask: tap Open settings, then Permissions, then Microphone, then Allow."
            )
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    val i = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + a.packageName)
                    )
                    a.startActivity(i)
                } catch (e: Exception) {
                }
            }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun begin(
        ti: Int,
        dev: AudioDeviceInfo?,
        maxSec: Double,
        gate: (() -> Boolean)?,
        auto: Boolean,
        base: String,
        note: Int,
        edit: Boolean
    ) {
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermission()
            return
        }
        recTrack = ti
        recLabel = base
        recMax = maxSec
        discard = false
        val done: (RecResult?) -> Unit = { res ->
            a.runOnUiThread { finish(res, ti, auto, base, note, edit) }
        }
        devName = if (dev == null) "automatic microphone" else a.recorder.label(dev)
        var err = a.recorder.start(dev, maxSec, gate, done)
        if (err != null && dev != null) {
            // the chosen input failed: try again with the automatic input
            devName = "automatic microphone"
            err = a.recorder.start(null, maxSec, gate, done)
        }
        if (err != null) {
            recTrack = -1
            problem("Cannot start recording", err)
            return
        }
        if (note >= 0) {
            lastNote = note
            a.midi.noteOn(note, 100)
        }
        showRecDialog(ti, base == "FM1")
        a.refreshCurrent()
    }

    /** Cuts the silence before the sound starts and after it ends. */
    private fun trimSilence(d: FloatArray): FloatArray {
        val pk = Wav.peak(d)
        if (pk < 0.001f) return d
        val thr = max(0.004f, pk * 0.03f)
        var s = 0
        while (s < d.size && abs(d[s]) < thr) s++
        var e = d.size - 1
        while (e > s && abs(d[e]) < thr) e--
        if (e - s < 200) return d
        val from = max(0, s - 300)
        val to = min(d.size, e + 2000)
        return d.copyOfRange(from, to)
    }

    private fun finish(res: RecResult?, ti: Int, auto: Boolean, base: String, note: Int, edit: Boolean) {
        recDialog?.dismiss()
        recDialog = null
        recTrack = -1
        if (note >= 0) a.midi.noteOff(note)
        if (discard) {
            discard = false
            a.ui.toast("Thrown away")
            a.refreshCurrent()
            return
        }
        if (res == null) {
            a.refreshCurrent()
            problem(
                "Nothing was recorded",
                "Android gave no sound from: $devName.\n\n" +
                    "Check: the microphone permission is on, no other app is using the microphone, " +
                    "then tap R and try again."
            )
            return
        }
        if (res.rawPeak < 0.003f) {
            a.refreshCurrent()
            problem(
                "The recording is silent",
                "The app recorded from: $devName, but no sound came in " +
                    "(${String.format(Locale.US, "%.1f", res.data.size.toDouble() / res.rate)} s of silence). Nothing was saved.\n\n" +
                    "Try: make a loud sound close to the microphone while recording. " +
                    "If it is still silent, long press R and choose another microphone."
            )
            return
        }
        var d = res.data
        if (edit) d = trimSilence(d)
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
        val secs = d.size.toDouble() / res.rate
        var clipNote = ""
        if (res.rawPeak >= 0.98f) {
            clipNote = "\n\nThe input was clipping. Lower the volume and record again for a clean sound."
        }
        if (edit) {
            a.ui.toast("Sampled ${f.name} into track ${ti + 1}. It is open in Edit: trim, fade, then Save.$clipNote")
            a.openEdit(f)
        } else {
            val pv = Wav.resample(d, res.rate, Cfg.SR)
            a.engine.previewPlay(pv)
            a.refreshCurrent()
            AlertDialog.Builder(a)
                .setTitle("Recorded")
                .setMessage(
                    String.format(Locale.US, "Saved as %s (%.1f s).\n", f.name, secs) +
                        "It is now in track ${ti + 1}. Press ▶ on the track to hear it again." + clipNote
                )
                .setPositiveButton("Play again") { _, _ -> a.engine.previewPlay(pv) }
                .setNegativeButton("OK", null)
                .show()
        }
    }
}
