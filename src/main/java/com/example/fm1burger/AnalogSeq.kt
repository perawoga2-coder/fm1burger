package com.example.fm1burger

import android.graphics.Color
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 16 step sequencer for the analog synth. Each step has a note and a length (1 to 4 steps).
 * A chord type is applied to every step. It is triggered by the engine step clock, so it
 * stays in sync with the drums and the bassline.
 */
class AnalogSeq(private val syn: AnalogSynth) {

    companion object {
        val CHORD_NAMES = arrayOf("Single", "Major", "Minor", "Power", "Octave")
        private val CHORDS = arrayOf(
            intArrayOf(0),
            intArrayOf(0, 4, 7),
            intArrayOf(0, 3, 7),
            intArrayOf(0, 7),
            intArrayOf(0, 12)
        )
    }

    val on = BooleanArray(16)
    val note = IntArray(16) { 60 }
    val len = IntArray(16) { 1 }
    @Volatile var enabled = true
    @Volatile var chord = 0
    @Volatile var resetReq = false
    @Volatile var sounding = false

    private val heldN = IntArray(64)
    private val heldL = IntArray(64)
    private var heldCount = 0

    fun clear() {
        for (i in 0 until 16) {
            on[i] = false
            note[i] = 60
            len[i] = 1
        }
    }

    /** Called by the audio thread on every step. */
    fun step(s: Int) {
        if (resetReq) {
            heldCount = 0
            resetReq = false
        }
        var j = 0
        for (i in 0 until heldCount) {
            heldL[i]--
            if (heldL[i] <= 0) {
                syn.noteOff(heldN[i])
            } else {
                heldN[j] = heldN[i]
                heldL[j] = heldL[i]
                j++
            }
        }
        heldCount = j
        if (enabled && on[s]) {
            val iv = CHORDS[chord.coerceIn(0, CHORDS.size - 1)]
            for (x in iv) {
                val n = (note[s] + x).coerceIn(0, 127)
                syn.noteOn(n)
                if (heldCount < 64) {
                    heldN[heldCount] = n
                    heldL[heldCount] = len[s].coerceIn(1, 4)
                    heldCount++
                }
            }
        }
        sounding = heldCount > 0
    }

    fun toJson(): String {
        val o = JSONObject()
        val a1 = JSONArray()
        val a2 = JSONArray()
        val a3 = JSONArray()
        for (i in 0 until 16) {
            a1.put(on[i])
            a2.put(note[i])
            a3.put(len[i])
        }
        o.put("on", a1)
        o.put("note", a2)
        o.put("len", a3)
        o.put("enabled", enabled)
        o.put("chord", chord)
        return o.toString()
    }

    fun fromJson(t: String) {
        try {
            val o = JSONObject(t)
            val a1 = o.optJSONArray("on")
            val a2 = o.optJSONArray("note")
            val a3 = o.optJSONArray("len")
            for (i in 0 until 16) {
                if (a1 != null && i < a1.length()) on[i] = a1.optBoolean(i, false)
                if (a2 != null && i < a2.length()) note[i] = a2.optInt(i, 60).coerceIn(0, 127)
                if (a3 != null && i < a3.length()) len[i] = a3.optInt(i, 1).coerceIn(1, 4)
            }
            enabled = o.optBoolean("enabled", true)
            chord = o.optInt("chord", 0).coerceIn(0, CHORDS.size - 1)
        } catch (ex: Exception) {
        }
    }
}

/** The screen part of the analog synth sequencer. */
class AnalogSeqPanel(private val a: MainActivity, private val seq: AnalogSeq) {

    private val ui = a.ui
    private val e = a.engine
    private val syn = e.analog
    private val file = File(a.filesDir, "analogseq.json")
    private val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val cells = ArrayList<Button>()
    private val chordBtns = ArrayList<Button>()
    private lateinit var onBtn: Button
    private lateinit var lenBtn: Button
    private lateinit var info: TextView
    private var sel = 0
    private var playStep = -1
    val view: LinearLayout = ui.panel()

    init {
        try {
            if (file.exists()) seq.fromJson(file.readText())
        } catch (ex: Exception) {
        }
        view.addView(ui.text("Step sequencer (analog synth)", 14f, Theme.ACCENT, true))
        view.addView(
            ui.text(
                "Tap a step to turn it on and pick it. Tap it again to turn it off. " +
                    "Use the note buttons to change its pitch. It plays with the drums when you press Play.",
                12f, Theme.DIM
            )
        )
        onBtn = ui.button("Sequencer: ON") {
            seq.enabled = !seq.enabled
            changed()
        }
        view.addView(onBtn, ui.fullWidth(4))

        view.addView(ui.text("Chord (all steps)", 12f, Theme.DIM), ui.fullWidth(4))
        val cr = ui.row()
        for (i in AnalogSeq.CHORD_NAMES.indices) {
            val b = ui.button(AnalogSeq.CHORD_NAMES[i]) {
                seq.chord = i
                changed()
            }
            chordBtns.add(b)
            cr.addView(b, ui.w(1f, 2))
        }
        view.addView(cr, ui.fullWidth(2))

        for (rowNo in 0 until 2) {
            val r = ui.row()
            for (k in 0 until 8) {
                val i = rowNo * 8 + k
                val b = Button(a)
                b.isAllCaps = false
                b.textSize = 12f
                b.minHeight = 0
                b.minimumHeight = 0
                b.minWidth = 0
                b.minimumWidth = 0
                b.setPadding(0, 0, 0, 0)
                b.setOnClickListener { tapCell(i) }
                val lp = LinearLayout.LayoutParams(0, ui.dp(54), 1f)
                lp.setMargins(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2))
                r.addView(b, lp)
                cells.add(b)
            }
            view.addView(r, ui.fullWidth(if (rowNo == 0) 6 else 0))
        }
        info = ui.text("", 13f, Theme.TEXT, true)
        view.addView(info, ui.fullWidth(4))

        val nr = ui.row()
        nr.addView(ui.button("Note −12") { shift(-12) }, ui.w(1f, 2))
        nr.addView(ui.button("−1") { shift(-1) }, ui.w(1f, 2))
        nr.addView(ui.button("+1") { shift(1) }, ui.w(1f, 2))
        nr.addView(ui.button("+12") { shift(12) }, ui.w(1f, 2))
        view.addView(nr, ui.fullWidth(2))

        val lr = ui.row()
        lenBtn = ui.button("Length: 1") {
            seq.len[sel] = seq.len[sel] % 4 + 1
            changed()
        }
        lr.addView(lenBtn, ui.w(1f, 3))
        lr.addView(ui.button("Clear step") {
            seq.on[sel] = false
            changed()
        }, ui.w(1f, 3))
        view.addView(lr, ui.fullWidth(2))

        view.addView(ui.text("Patterns", 12f, Theme.DIM), ui.fullWidth(6))
        val pr = ui.row()
        pr.addView(ui.button("Chords") { preset(0) }, ui.w(1f, 2))
        pr.addView(ui.button("Arp up") { preset(1) }, ui.w(1f, 2))
        pr.addView(ui.button("Stabs") { preset(2) }, ui.w(1f, 2))
        pr.addView(ui.button("Clear all") { preset(3) }, ui.w(1f, 2))
        view.addView(pr, ui.fullWidth(2))
        refresh()
    }

    private fun noteName(n: Int): String = names[n % 12] + (n / 12 - 1)

    private fun tapCell(i: Int) {
        if (i == sel && seq.on[i]) {
            seq.on[i] = false
        } else {
            sel = i
            if (!seq.on[i]) seq.on[i] = true
            val n = seq.note[i]
            syn.noteOn(n)
            view.postDelayed({ syn.noteOff(n) }, 250)
        }
        changed()
    }

    private fun shift(d: Int) {
        seq.note[sel] = (seq.note[sel] + d).coerceIn(24, 96)
        seq.on[sel] = true
        val n = seq.note[sel]
        syn.noteOn(n)
        view.postDelayed({ syn.noteOff(n) }, 250)
        changed()
    }

    private fun preset(k: Int) {
        seq.clear()
        when (k) {
            0 -> {
                seq.chord = 1
                val ns = intArrayOf(60, 65, 67, 65)
                for (j in 0 until 4) {
                    seq.on[j * 4] = true
                    seq.note[j * 4] = ns[j]
                    seq.len[j * 4] = 4
                }
            }
            1 -> {
                seq.chord = 0
                val ns = intArrayOf(60, 64, 67, 72)
                for (i in 0 until 16) {
                    seq.on[i] = true
                    seq.note[i] = ns[i % 4]
                }
            }
            2 -> {
                seq.chord = 2
                for (i in intArrayOf(2, 6, 10, 14)) {
                    seq.on[i] = true
                    seq.note[i] = 60
                }
            }
        }
        changed()
    }

    private fun changed() {
        refresh()
        try {
            SafeIo.writeText(file, seq.toJson())
        } catch (ex: Exception) {
        }
    }

    private fun paint(i: Int) {
        val b = cells[i]
        val base = if (seq.on[i]) {
            Theme.ACCENT
        } else if ((i / 4) % 2 == 0) {
            Theme.PANEL2
        } else {
            Theme.PANEL
        }
        val stroke = if (i == playStep) Theme.TEXT else if (i == sel) Theme.GREEN else 0
        b.background = ui.bg(base, 8, stroke, if (stroke != 0) 3 else 0)
        b.setTextColor(if (seq.on[i]) Color.BLACK else Theme.DIM)
        b.text = if (seq.on[i]) {
            noteName(seq.note[i]) + (if (seq.len[i] > 1) "\n×" + seq.len[i] else "")
        } else {
            "${i + 1}"
        }
    }

    fun refresh() {
        onBtn.text = if (seq.enabled) "Sequencer: ON" else "Sequencer: OFF"
        ui.styleButton(onBtn, seq.enabled, Theme.GREEN)
        for (i in chordBtns.indices) ui.styleButton(chordBtns[i], seq.chord == i)
        for (i in 0 until 16) paint(i)
        info.text = if (seq.on[sel]) {
            "Step ${sel + 1}: ${noteName(seq.note[sel])}, length ${seq.len[sel]}"
        } else {
            "Step ${sel + 1}: off. Tap it to turn it on."
        }
        lenBtn.text = "Length: ${seq.len[sel]}"
    }

    /** Called every screen tick: moves the white frame and releases hanging notes after Stop. */
    fun tick() {
        val s = if (e.playing && !e.paused) e.curStep else -1
        if (s != playStep) {
            val old = playStep
            playStep = s
            if (old >= 0) paint(old)
            if (s >= 0) paint(s)
        }
        if ((!e.playing || e.paused) && seq.sounding) {
            seq.sounding = false
            seq.resetReq = true
            syn.allOff()
        }
    }
}
