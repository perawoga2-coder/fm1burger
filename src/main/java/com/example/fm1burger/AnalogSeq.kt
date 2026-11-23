package com.example.fm1burger

import android.graphics.Color
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Step sequencer for the analog synth (1, 2 or 4 bars). Polyphonic: any number of notes can start on a
 * step, each with its own length (1 to 16 steps). The chord type is added on top of every note.
 * It is triggered by the engine step clock, so it stays in sync with the drums and the bassline.
 */
class AnalogSeq(private val syn: AnalogSynth) {

    companion object {
        const val MAX_LEN = 16
        const val NOTES = 128
        val CHORD_NAMES = arrayOf("Single", "Major", "Minor", "Power", "Octave")
        val CHORDS = arrayOf(
            intArrayOf(0),
            intArrayOf(0, 4, 7),
            intArrayOf(0, 3, 7),
            intArrayOf(0, 7),
            intArrayOf(0, 12)
        )

        /** Legacy (one note per step) to the note grid. */
        fun fromMono(on: BooleanArray, note: IntArray, len: IntArray, grid: IntArray) {
            grid.fill(0)
            for (s in 0 until minOf(on.size, Cfg.STEPS)) {
                if (on[s]) grid[s * NOTES + note[s].coerceIn(0, 127)] = len[s].coerceIn(1, MAX_LEN)
            }
        }

        fun gridToJson(g: IntArray): JSONArray {
            val a = JSONArray()
            for (k in g.indices) if (g[k] > 0) {
                a.put(k / NOTES)
                a.put(k % NOTES)
                a.put(g[k])
            }
            return a
        }

        fun gridFromJson(a: JSONArray, g: IntArray) {
            g.fill(0)
            var k = 0
            while (k + 2 < a.length()) {
                val s = a.optInt(k, -1)
                val n = a.optInt(k + 1, -1)
                val l = a.optInt(k + 2, 0)
                if (s in 0 until Cfg.STEPS && n in 0 until NOTES && l > 0) g[s * NOTES + n] = l.coerceAtMost(MAX_LEN)
                k += 3
            }
        }
    }

    /** grid[step * 128 + note] = length in steps of the note that starts there, 0 = none. */
    val grid = IntArray(Cfg.STEPS * NOTES)
    @Volatile var enabled = true
    @Volatile var chord = 0
    @Volatile var resetReq = false
    @Volatile var sounding = false

    private val heldN = IntArray(128)
    private val heldL = IntArray(128)
    private var heldCount = 0

    fun lenAt(s: Int, n: Int): Int = if (s in 0 until Cfg.STEPS && n in 0 until NOTES) grid[s * NOTES + n] else 0
    fun set(s: Int, n: Int, l: Int) {
        if (s in 0 until Cfg.STEPS && n in 0 until NOTES) grid[s * NOTES + n] = l.coerceIn(0, MAX_LEN)
    }
    fun has(s: Int): Boolean {
        val b = s * NOTES
        for (n in 0 until NOTES) if (grid[b + n] > 0) return true
        return false
    }
    /** The notes that start on step s, low to high. */
    fun notesAt(s: Int): IntArray {
        val b = s * NOTES
        return (0 until NOTES).filter { grid[b + it] > 0 }.toIntArray()
    }
    fun clearStep(s: Int) {
        java.util.Arrays.fill(grid, s * NOTES, (s + 1) * NOTES, 0)
    }
    /** Move every note of step s by d semitones. */
    fun transposeStep(s: Int, d: Int) {
        val ns = notesAt(s)
        val ls = ns.map { grid[s * NOTES + it] }
        clearStep(s)
        for ((k, n) in ns.withIndex()) set(s, (n + d).coerceIn(24, 96), ls[k])
    }
    fun setStepLen(s: Int, l: Int) {
        for (n in notesAt(s)) set(s, n, l)
    }
    /** Copy step a to step b (all notes). */
    fun copyStep(a: Int, b: Int) {
        System.arraycopy(grid, a * NOTES, grid, b * NOTES, NOTES)
    }

    fun clear() {
        grid.fill(0)
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
        if (enabled && s in 0 until Cfg.STEPS) {
            val iv = CHORDS[chord.coerceIn(0, CHORDS.size - 1)]
            val b = s * NOTES
            for (root in 0 until NOTES) {
                val l = grid[b + root]
                if (l <= 0) continue
                for (x in iv) {
                    val n = (root + x).coerceIn(0, 127)
                    syn.noteOn(n)
                    if (heldCount < heldN.size) {
                        heldN[heldCount] = n
                        heldL[heldCount] = l.coerceIn(1, MAX_LEN)
                        heldCount++
                    }
                }
            }
        }
        sounding = heldCount > 0
    }

    fun toJson(): String {
        val o = JSONObject()
        o.put("notes", gridToJson(grid))
        o.put("enabled", enabled)
        o.put("chord", chord)
        return o.toString()
    }

    fun fromJson(t: String) {
        try {
            val o = JSONObject(t)
            val nt = o.optJSONArray("notes")
            if (nt != null) {
                gridFromJson(nt, grid)
            } else {
                // the old file: one note per step
                val a1 = o.optJSONArray("on")
                val a2 = o.optJSONArray("note")
                val a3 = o.optJSONArray("len")
                val on = BooleanArray(Cfg.STEPS)
                val note = IntArray(Cfg.STEPS) { 60 }
                val len = IntArray(Cfg.STEPS) { 1 }
                for (i in 0 until Cfg.STEPS) {
                    if (a1 != null && i < a1.length()) on[i] = a1.optBoolean(i, false)
                    if (a2 != null && i < a2.length()) note[i] = a2.optInt(i, 60).coerceIn(0, 127)
                    if (a3 != null && i < a3.length()) len[i] = a3.optInt(i, 1).coerceIn(1, MAX_LEN)
                }
                fromMono(on, note, len, grid)
            }
            enabled = o.optBoolean("enabled", true)
            chord = o.optInt("chord", 0).coerceIn(0, CHORDS.size - 1)
        } catch (ex: Exception) {
        }
    }
}

/** The screen part of the analog synth sequencer. */
class AnalogSeqPanel(private val a: MainActivity, private val seq: AnalogSeq, prep: PrepareBar? = null) {

    private val ui = a.ui
    private val e = a.engine
    private val syn = e.analog
    private val file = File(a.filesDir, "analogseq.json")
    private val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val cells = ArrayList<Button>()
    private val barCells = BarCells(ui, cells, 54, 2, 2, false)
    private val chordBtns = ArrayList<Button>()
    private lateinit var onBtn: Button
    private lateinit var lenBtn: Button
    private lateinit var info: TextView
    private var sel = 0
    private var playStep = -1
    /** 3.50: CLEAR emptied the part (the page redraws its piano roll) */
    var onCleared: (() -> Unit)? = null
    val view: LinearLayout = ui.panel()

    init {
        try {
            if (file.exists()) seq.fromJson(file.readText())
        } catch (ex: Exception) {
        }
        view.addView(ui.text("Step sequencer (analog synth)", 14f, Theme.ACCENT, true))
        view.addView(
            ui.text(
                "Tap a step: on / off. Hold a step to pick it without changing it. The note buttons move all the notes of " +
                    "the step. For chords and several notes on a step use the piano roll below.",
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

        // (3.28: as many steps as the meter's bar; BarCells puts them in rows)
        for (i in 0 until BarCells.MAX) {
            val b = Button(a)
            b.isAllCaps = false
            b.textSize = 12f
            b.minHeight = 0
            b.minimumHeight = 0
            b.minWidth = 0
            b.minimumWidth = 0
            b.setPadding(0, 0, 0, 0)
            b.setOnClickListener { tapCell(Pat.base() + i) }
            b.setOnLongClickListener { pickCell(Pat.base() + i); true }
            cells.add(b)
        }
        // 3.50: CLEAR in the dock (the whole part, steps and piano roll, at once); the grid keeps its size when
        // EDIT locks, so the rows under it (notes, Clear step, patterns) stay on the screen
        val clearBtn = ui.button("CLEAR") {
            preset(3)
            onCleared?.invoke()
        }
        clearBtn.textSize = 15f
        clearBtn.typeface = Ui.BOLD
        ui.styleButton(clearBtn, true, Theme.RED)
        view.addView(StepDock(a, prep, barCells.box, fill = false, extra = clearBtn).view, ui.fullWidth(6))      // 3.37
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
            val opts = intArrayOf(1, 2, 3, 4, 6, 8, 12, 16)
            val cur = seq.notesAt(sel).firstOrNull()?.let { seq.lenAt(sel, it) } ?: 1
            val k = opts.indexOfFirst { it > cur }
            seq.setStepLen(sel, if (k < 0) 1 else opts[k])
            changed()
        }
        lr.addView(lenBtn, ui.w(1f, 3))
        lr.addView(ui.button("Clear step") {
            seq.clearStep(sel)
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

    private var lastNote = 60

    private fun hear(ns: IntArray) {
        for (n in ns) syn.noteOn(n)
        view.postDelayed({ for (n in ns) syn.noteOff(n) }, 250)
    }

    /** A tap: on / off (and the step is picked for the note buttons). */
    private fun tapCell(i: Int) {
        sel = i
        if (seq.has(i)) {
            seq.clearStep(i)
        } else {
            seq.set(i, lastNote, 1)
            hear(seq.notesAt(i))
        }
        changed()
    }

    /** A long press: pick the step without turning it on or off. */
    private fun pickCell(i: Int) {
        sel = i
        if (seq.has(i)) hear(seq.notesAt(i))
        changed()
    }

    private fun shift(d: Int) {
        if (!seq.has(sel)) seq.set(sel, lastNote, 1) else seq.transposeStep(sel, d)
        val ns = seq.notesAt(sel)
        if (ns.isNotEmpty()) lastNote = ns[0]
        hear(ns)
        changed()
    }

    private fun preset(k: Int) {
        seq.clear()
        when (k) {
            0 -> {
                seq.chord = 1
                val ns = intArrayOf(60, 65, 67, 65)
                for (j in 0 until 4) seq.set(j * 4, ns[j], 4)
            }
            1 -> {
                seq.chord = 0
                val ns = intArrayOf(60, 64, 67, 72)
                for (i in 0 until Pat.BAR) seq.set(i, ns[i % 4], 1)
            }
            2 -> {
                seq.chord = 2
                for (i in intArrayOf(2, 6, 10, 14)) seq.set(i, 60, 1)
            }
        }
        // the same bar in every bar of a longer pattern
        for (i in Pat.BAR until Cfg.STEPS) seq.copyStep(i % Pat.BAR, i)
        changed()
    }

    /** Something else changed the pattern (the piano roll, the bar shown): redraw and save. */
    fun changedOutside() = changed()

    /** The step picked for the note buttons (absolute step). */
    var selected: Int
        get() = sel
        set(v) {
            sel = v.coerceIn(0, Cfg.STEPS - 1)
            refresh()
        }

    private fun changed() {
        refresh()
        try {
            SafeIo.writeText(file, seq.toJson())
        } catch (ex: Exception) {
        }
    }

    private fun paint(c: Int) {
        if (c !in 0 until Pat.spb()) return
        val b = cells[c]
        val i = Pat.base() + c
        val ns = seq.notesAt(i)
        val on = ns.isNotEmpty()
        val base = if (on) {
            Theme.ACCENT
        } else if (Pat.beatOf(c) % 2 == 0) {
            Theme.PANEL2
        } else {
            Theme.PANEL
        }
        val stroke = if (i == playStep) Theme.TEXT else if (i == sel) Theme.GREEN else 0
        b.background = ui.bg(base, 8, stroke, if (stroke != 0) 3 else 0)
        b.setTextColor(if (on) Color.BLACK else Theme.DIM)
        b.text = if (on) {
            val l = seq.lenAt(i, ns[0])
            noteName(ns[0]) + (if (ns.size > 1) " +${ns.size - 1}" else "") + (if (l > 1) "\n×$l" else "")
        } else {
            "${i + 1}"
        }
    }

    fun refresh() {
        onBtn.text = if (seq.enabled) "Sequencer: ON" else "Sequencer: OFF"
        ui.styleButton(onBtn, seq.enabled, Theme.GREEN)
        for (i in chordBtns.indices) ui.styleButton(chordBtns[i], seq.chord == i)
        barCells.layout()
        for (i in 0 until Pat.spb()) paint(i)
        val ns = seq.notesAt(sel)
        val l = ns.firstOrNull()?.let { seq.lenAt(sel, it) } ?: 1
        info.text = if (ns.isNotEmpty()) {
            "Step ${sel + 1}: " + ns.joinToString(" ") { noteName(it) } + ", length $l"
        } else {
            "Step ${sel + 1}: off. Tap it to turn it on."
        }
        lenBtn.text = "Length: $l"
    }

    /** Called every screen tick: moves the white frame and releases hanging notes after Stop. */
    fun tick() {
        val s = if (e.playing && !e.paused) e.curStep else -1
        if (s != playStep) {
            val old = playStep
            playStep = s
            if (old >= 0) paint(old - Pat.base())
            if (s >= 0) paint(s - Pat.base())
        }
        if ((!e.playing || e.paused) && seq.sounding) {
            seq.sounding = false
            seq.resetReq = true
            syn.allOff()
        }
    }
}
