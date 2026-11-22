package com.example.fm1burger

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Metal guitars: RHYTHM (power chord chugs) and RIFF (single-note lines), downtuned,
 * through a high gain amp. 16 steps each: tap a step to choose it, tap it again to change
 * what it does (· stop, M palm-muted chug, O open, — hold), set its fret with − and +.
 */
class GuitarScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    private val e = a.engine
    override val root = ScrollView(a)
    private val content = ui.col()
    private var mode = 0           // 0 rhythm, 1 riff
    private val prep = PrepareBar(a, true) { if (mode == 0) Engine.PREP_RHYTHM else Engine.PREP_RIFF }
    private var selStep = 0
    private var playStep = -2
    private lateinit var rhythmTab: Button
    private lateinit var riffTab: Button
    private lateinit var muteBtn: Button
    private lateinit var octBtn: Button
    private lateinit var fretText: TextView
    private lateinit var info: TextView
    private val cells = ArrayList<Button>()
    private val barCells = BarCells(ui, cells, 50, 2, 3, true)
    private val tuneBtns = ArrayList<Button>()
    private val artBtns = ArrayList<Button>()
    private val presetRow = ui.row()
    private val sliders = ArrayList<Pair<SliderRow, () -> Int>>()
    private val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    private fun g(): MetalGuitar = if (mode == 0) e.rhythmGtr else e.riffGtr

    init {
        content.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(88))
        root.addView(content)
        content.addView(ui.title("Metal guitars"))
        content.addView(
            ui.text(
                "Downtuned guitars through a high gain amp, for nu-metal, techno metal and psytrance metal. " +
                    "RHYTHM plays power chords, RIFF plays single notes.",
                12f, Theme.DIM
            ), ui.fullWidth(4)
        )
        val tabs = ui.row()
        rhythmTab = ui.button("Rhythm guitar") { showMode(0) }
        riffTab = ui.button("Riff guitar") { showMode(1) }
        tabs.addView(rhythmTab, ui.w(1f, 3))
        tabs.addView(riffTab, ui.w(1f, 3))
        content.addView(tabs, ui.fullWidth(8))
        content.addView(prep.view, ui.fullWidth(6))
        prep.onPage = { if (cells.size == BarCells.MAX) paintCells(true) }

        // presets (filled in by showMode)
        content.addView(ui.text("Ready-made patterns", 13f, Theme.DIM), ui.fullWidth(10))
        content.addView(presetRow, ui.fullWidth(2))

        // the 16 steps
        val sp = ui.panel()
        sp.addView(ui.text("Steps: tap on / off · hold to pick · the buttons below set open, hold and the fret", 14f, Theme.ACCENT, true))
        // (3.28: as many steps as the meter's bar; BarCells puts them in rows)
        for (s in 0 until BarCells.MAX) {
            val b = ui.button("") { tapStep(Pat.base() + s) }
            b.setOnLongClickListener { pickStep(Pat.base() + s); true }
            b.textSize = 11f
            b.setPadding(0, 0, 0, 0)
            b.maxLines = 2
            cells.add(b)
        }
        sp.addView(barCells.box)
        info = ui.text("", 12f, Theme.DIM)
        sp.addView(info, ui.fullWidth(6))

        val ar = ui.row()
        for ((k, label) in listOf("· Stop", "M Chug", "O Open", "— Hold").withIndex()) {
            val b = ui.button(label) { setArt(k) }
            artBtns.add(b)
            ar.addView(b, ui.w(1f, 3))
        }
        sp.addView(ar, ui.fullWidth(6))

        val fr = ui.row()
        fr.addView(ui.button("−") { moveFret(-1) }, ui.w(0f, 3))
        fretText = ui.text("", 15f, Theme.TEXT, true)
        fretText.gravity = android.view.Gravity.CENTER
        fr.addView(fretText, ui.w(1f, 3))
        fr.addView(ui.button("+") { moveFret(1) }, ui.w(0f, 3))
        fr.addView(ui.button("▶ hear") {
            val gg = g()
            gg.audition(if (gg.art[selStep] == MetalGuitar.OFF || gg.art[selStep] == MetalGuitar.HOLD) MetalGuitar.CHUG else gg.art[selStep], gg.fret[selStep])
        }, ui.w(0f, 3))
        sp.addView(fr, ui.fullWidth(4))
        val cr = ui.row()
        cr.addView(ui.button("Clear") {
            val gg = g()
            for (s in 0 until Cfg.STEPS) {
                gg.art[s] = MetalGuitar.OFF
                gg.fret[s] = 0
            }
            gg.audition(MetalGuitar.OFF, 0)                 // and what still rings stops now
            refresh()
            ui.toast("${if (mode == 0) "Rhythm" else "Riff"} guitar cleared")
        }, ui.w(1f, 3))
        cr.addView(ui.button("All chug") {
            val gg = g()
            for (s in 0 until Cfg.STEPS) gg.art[s] = MetalGuitar.CHUG
            refresh()
        }, ui.w(1f, 3))
        cr.addView(ui.button("◀ Shift") { shift(-1) }, ui.w(1f, 3))
        cr.addView(ui.button("Shift ▶") { shift(1) }, ui.w(1f, 3))
        sp.addView(cr, ui.fullWidth(4))
        content.addView(sp, ui.fullWidth(10))

        // tuning
        val tp = ui.panel()
        tp.addView(ui.text("Tuning (the lowest string)", 14f, Theme.ACCENT, true))
        val tr = ui.row()
        for (k in MetalGuitar.TUNINGS.indices) {
            val b = ui.button(MetalGuitar.TUNINGS[k]) {
                g().tuning = k
                refresh()
            }
            b.textSize = 12f
            tuneBtns.add(b)
            tr.addView(b, ui.w(1f, 2))
        }
        tp.addView(tr, ui.fullWidth(4))
        content.addView(tp, ui.fullWidth(10))

        // amp
        val ap = ui.panel()
        ap.addView(ui.text("Amp", 14f, Theme.ACCENT, true))
        addSlider(ap, "Gain", { (g().gain * 100).toInt() }, { if (it > 85) "brutal" else "$it%" }) { g().gain = it / 100f }
        addSlider(ap, "Tone", { (g().tone * 100).toInt() }, { if (it < 34) "dark" else if (it > 66) "bright" else "$it%" }) { g().tone = it / 100f }
        addSlider(ap, "Tight", { (g().tight * 100).toInt() }, { if (it > 75) "very short" else "$it%" }) { g().tight = it / 100f }
        addSlider(ap, "Volume", { (g().volume * 100).toInt() }, { "$it%" }) { g().volume = it / 100f }
        val mr = ui.row()
        octBtn = ui.button("Octave up: off") {
            val gg = g()
            gg.octave = 1 - gg.octave
            refresh()
        }
        mr.addView(octBtn, ui.w(1f, 3))
        muteBtn = ui.button("Mute") {
            val gg = g()
            gg.mute = !gg.mute
            refresh()
        }
        mr.addView(muteBtn, ui.w(1f, 3))
        ap.addView(mr, ui.fullWidth(6))
        content.addView(ap, ui.fullWidth(10))

        showMode(0)
    }

    private fun addSlider(parent: LinearLayout, name: String, get: () -> Int, fmt: (Int) -> String, set: (Int) -> Unit) {
        val s = ui.slider(name, 100, get(), fmt, set)
        sliders.add(Pair(s, get))
        parent.addView(s.view, ui.fullWidth(4))
    }

    fun showMode(m: Int) {
        mode = m
        presetRow.removeAllViews()
        val names = if (m == 0) MetalGuitar.RHYTHM_PRESETS else MetalGuitar.RIFF_PRESETS
        for (k in names.indices) {
            val b = ui.button(names[k]) {
                g().loadPreset(k)
                refresh()
                ui.toast("${if (mode == 0) "Rhythm" else "Riff"} guitar: ${names[k]}")
            }
            b.textSize = 12f
            presetRow.addView(b, ui.w(1f, 2))
        }
        refresh()
    }

    private fun noteName(f: Int): String {
        val gg = g()
        val rootMidi = when (gg.tuning) { 0 -> 33; 1 -> 35; 2 -> 36; 3 -> 38; else -> 40 }
        val midi = rootMidi + f + 12 * (if (gg.riff) gg.octave else 0)
        return noteNames[midi % 12] + (midi / 12 - 1)
    }

    /** A tap: on (a chug) / off (stop); the step is picked for the buttons below (open, hold, fret). */
    private fun tapStep(s: Int) {
        val gg = g()
        selStep = s
        if (gg.art[s] != MetalGuitar.OFF) {             // a chug, an open or a hold (—): one tap removes it
            gg.art[s] = MetalGuitar.OFF
            if (!e.playing) gg.audition(MetalGuitar.OFF, 0)
        } else {
            gg.art[s] = MetalGuitar.CHUG
            gg.audition(gg.art[s], gg.fret[s])
        }
        refresh()
    }

    /** A long press: pick the step without changing it. */
    private fun pickStep(s: Int) {
        selStep = s
        refresh()
    }

    private fun setArt(k: Int) {
        val gg = g()
        gg.art[selStep] = k
        if (k == MetalGuitar.CHUG || k == MetalGuitar.OPEN) gg.audition(k, gg.fret[selStep])
        refresh()
    }

    private fun moveFret(d: Int) {
        val gg = g()
        gg.fret[selStep] = (gg.fret[selStep] + d).coerceIn(0, 24)
        if (gg.art[selStep] == MetalGuitar.OFF || gg.art[selStep] == MetalGuitar.HOLD) gg.art[selStep] = MetalGuitar.OPEN
        gg.audition(gg.art[selStep], gg.fret[selStep])
        refresh()
    }

    private fun shift(d: Int) {
        val gg = g()
        val aa = gg.art.copyOf()
        val ff = gg.fret.copyOf()
        val n = Pat.len
        for (s in 0 until n) {
            val j = (s + d + n) % n
            gg.art[j] = aa[s]
            gg.fret[j] = ff[s]
        }
        refresh()
    }

    private fun paintCells(force: Boolean) {
        val ps = if (e.playing) e.curStep else -1
        if (!force && ps == playStep) return
        playStep = ps
        val gg = g()
        val col = Inst.color(if (mode == 0) Inst.RHYTHM else Inst.RIFF)
        val base = Pat.base()
        barCells.layout()
        for (c in 0 until Pat.spb()) {
            val s = base + c
            val b = cells[c]
            val art = gg.art[s]
            b.text = MetalGuitar.ART_NAMES[art] + (if (art == MetalGuitar.CHUG || art == MetalGuitar.OPEN) "\n${gg.fret[s]}" else "")
            when {
                s == ps -> ui.styleButton(b, true, Theme.TEXT, 8)
                s == selStep -> ui.styleButton(b, true, Theme.YELLOW, 8)
                art == MetalGuitar.CHUG -> ui.styleButton(b, true, Theme.alpha(col, 150), 8)
                art == MetalGuitar.OPEN -> ui.styleButton(b, true, col, 8)
                art == MetalGuitar.HOLD -> ui.styleButton(b, true, Theme.alpha(col, 70), 8)    // a hold shows, so it is clear it is there
                else -> ui.styleButton(b, false, col, 8)
            }
        }
    }

    fun refresh() {
        val gg = g()
        val col = Inst.color(if (mode == 0) Inst.RHYTHM else Inst.RIFF)
        ui.styleButton(rhythmTab, mode == 0, Inst.color(Inst.RHYTHM))
        ui.styleButton(riffTab, mode == 1, Inst.color(Inst.RIFF))
        for (k in tuneBtns.indices) ui.styleButton(tuneBtns[k], gg.tuning == k, col)
        for (k in artBtns.indices) ui.styleButton(artBtns[k], gg.art[selStep] == k, col)
        fretText.text = "Step ${selStep + 1}: fret ${gg.fret[selStep]} (${noteName(gg.fret[selStep])})"
        info.text = "M = palm-muted chug · O = open, rings · — = keep ringing · · = stop (tight silence)"
        octBtn.visibility = if (gg.riff) View.VISIBLE else View.GONE
        octBtn.text = if (gg.octave == 1) "Octave up: on" else "Octave up: off"
        ui.styleButton(octBtn, gg.octave == 1, col)
        muteBtn.text = if (gg.mute) "Muted" else "Mute"
        ui.styleButton(muteBtn, gg.mute, Theme.RED)
        for ((s, get) in sliders) s.set(get())
        paintCells(true)
    }

    override fun onShow() {
        refresh()
    }

    override fun onTick() {
        prep.refresh()
        paintCells(false)
    }
}
