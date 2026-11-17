package com.example.fm1burger

import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Synth page with two instruments:
 *  - Bassline: classic one-note-at-a-time bass with a 16 step pattern (note, accent, slide).
 *  - Analog synth: 6-voice keyboard synth with sine / saw / square / triangle waves, filter and ADSR.
 * Play / Pause / Stop / Mute are at the top of the page.
 */
class SynthScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    private val e = a.engine
    private val bs = e.bass
    private val an = e.analog
    override val root = ScrollView(a)
    private val content = ui.col()
    private val bassBox = ui.col()
    private val anaBox = ui.col()
    private lateinit var bassTab: Button
    private lateinit var anaTab: Button
    private val cells = ArrayList<Button>()
    private val refreshers = ArrayList<() -> Unit>()
    private var mode = 0
    private var selStep = 0
    private var playStep = -1
    private lateinit var editInfo: TextView
    private lateinit var accBtn: Button
    private lateinit var slideBtn: Button
    private lateinit var sawBtn: Button
    private lateinit var sqBtn: Button
    private lateinit var bMuteBtn: Button
    private lateinit var keyText: TextView
    private val waveBtns = ArrayList<Button>()
    private lateinit var aMuteBtn: Button
    private lateinit var octText: TextView
    private var kbOct = 3
    private var lastState = -1
    private lateinit var playBtn: Button
    private lateinit var pauseBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var tMuteBtn: Button
    private val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    init {
        content.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(24))
        root.addView(content)
        content.addView(ui.text("Synth", 18f, Theme.ACCENT, true))
        val tabs = ui.row()
        bassTab = ui.button("Bassline") { showMode(0) }
        anaTab = ui.button("Analog synth") { showMode(1) }
        tabs.addView(bassTab, ui.w(1f, 3))
        tabs.addView(anaTab, ui.w(1f, 3))
        content.addView(tabs, ui.fullWidth(6))
        val tr = ui.row()
        playBtn = ui.button("▶ Play") { doPlay() }
        pauseBtn = ui.button("|| Pause") { doPause() }
        stopBtn = ui.button("■ Stop") { e.stop() }
        tMuteBtn = ui.button("Mute") { toggleMute() }
        tr.addView(playBtn, ui.w(1f, 3))
        tr.addView(pauseBtn, ui.w(1f, 3))
        tr.addView(stopBtn, ui.w(1f, 3))
        tr.addView(tMuteBtn, ui.w(1.3f, 3))
        content.addView(tr, ui.fullWidth(4))
        buildBass()
        buildAnalog()
        content.addView(bassBox)
        content.addView(anaBox)
        showMode(0)
    }

    private fun showMode(m: Int) {
        mode = m
        bassBox.visibility = if (m == 0) View.VISIBLE else View.GONE
        anaBox.visibility = if (m == 1) View.VISIBLE else View.GONE
        ui.styleButton(bassTab, m == 0)
        ui.styleButton(anaTab, m == 1)
        refreshMute()
    }

    // ---------------- transport (Play / Pause / Stop / Mute) ----------------

    private fun doPlay() {
        if (e.playing && e.paused) e.pause() else if (!e.playing) e.play()
    }

    private fun doPause() {
        if (e.playing && !e.paused) e.pause()
    }

    private fun toggleMute() {
        if (mode == 0) bs.mute = !bs.mute else an.mute = !an.mute
        refreshBass()
        refreshAnalog()
    }

    private fun refreshMute() {
        val m = if (mode == 0) bs.mute else an.mute
        tMuteBtn.text = if (mode == 0) (if (m) "Unmute bass" else "Mute bass") else (if (m) "Unmute synth" else "Mute synth")
        ui.styleButton(tMuteBtn, m, Theme.RED)
    }

    private fun refreshTransport() {
        val st = if (!e.playing) 0 else if (e.paused) 2 else 1
        if (st == lastState) return
        lastState = st
        ui.styleButton(playBtn, st == 1)
        ui.styleButton(pauseBtn, st == 2)
        ui.styleButton(stopBtn, false)
    }

    private fun slider(name: String, get: () -> Float, set: (Float) -> Unit): SliderRow {
        val row = ui.slider(name, 100, (get() * 100f).toInt(), { "$it%" }) { v -> set(v / 100f) }
        refreshers.add { row.set((get() * 100f).toInt()) }
        return row
    }

    private fun sliderLine(vararg rows: SliderRow): LinearLayout {
        val r = ui.row()
        for (x in rows) r.addView(x.view, ui.w(1f, 4))
        return r
    }

    // ================= Bassline =================

    private fun noteName(n: Int): String {
        val midi = bs.rootMidi() + n
        return noteNames[midi % 12] + (midi / 12 - 1)
    }

    private fun buildBass() {
        bassBox.addView(
            ui.text(
                "Classic bassline. Tap a step to select it and turn it on. Then change its note, " +
                    "Accent (louder, sharper) or Slide (glides into the note) below. It plays with the main ▶ button.",
                12f, Theme.DIM
            ), ui.fullWidth(4)
        )

        val kr = ui.row()
        kr.addView(ui.button("Key −") {
            bs.key = (bs.key + 11) % 12
            refreshBass()
        }, ui.w(1f, 3))
        keyText = ui.text("", 14f, Theme.TEXT, true)
        keyText.gravity = Gravity.CENTER
        kr.addView(keyText, LinearLayout.LayoutParams(ui.dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
        kr.addView(ui.button("Key +") {
            bs.key = (bs.key + 1) % 12
            refreshBass()
        }, ui.w(1f, 3))
        kr.addView(ui.button("Octave −") {
            bs.octave = (bs.octave - 1).coerceAtLeast(1)
            refreshBass()
        }, ui.w(1f, 3))
        kr.addView(ui.button("Octave +") {
            bs.octave = (bs.octave + 1).coerceAtMost(3)
            refreshBass()
        }, ui.w(1f, 3))
        bassBox.addView(kr, ui.fullWidth(6))

        for (rr in 0 until 2) {
            val row = ui.row()
            for (c in 0 until 8) {
                val i = rr * 8 + c
                val b = Button(a)
                b.isAllCaps = false
                b.textSize = 14f
                b.minHeight = 0
                b.minWidth = 0
                b.setPadding(0, 0, 0, 0)
                b.setOnClickListener { tapStep(i) }
                cells.add(b)
                val lp = LinearLayout.LayoutParams(0, ui.dp(56), 1f)
                lp.setMargins(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2))
                row.addView(b, lp)
            }
            bassBox.addView(row, ui.fullWidth(2))
        }

        editInfo = ui.text("", 13f, Theme.TEXT, true)
        bassBox.addView(editInfo, ui.fullWidth(8))
        val er = ui.row()
        er.addView(ui.button("−12") { nudge(-12) }, ui.w(1f, 3))
        er.addView(ui.button("−1") { nudge(-1) }, ui.w(1f, 3))
        er.addView(ui.button("+1") { nudge(1) }, ui.w(1f, 3))
        er.addView(ui.button("+12") { nudge(12) }, ui.w(1f, 3))
        accBtn = ui.button("Accent") {
            bs.accent[selStep] = !bs.accent[selStep]
            bs.on[selStep] = true
            bs.previewReq = bs.note[selStep]
            refreshBass()
        }
        slideBtn = ui.button("Slide") {
            bs.slide[selStep] = !bs.slide[selStep]
            bs.on[selStep] = true
            refreshBass()
        }
        er.addView(accBtn, ui.w(1.3f, 3))
        er.addView(slideBtn, ui.w(1.3f, 3))
        er.addView(ui.button("Clear step") {
            bs.on[selStep] = false
            bs.accent[selStep] = false
            bs.slide[selStep] = false
            bs.note[selStep] = 0
            refreshBass()
        }, ui.w(1.5f, 3))
        bassBox.addView(er, ui.fullWidth(4))

        bassBox.addView(ui.text("Ready-made lines", 13f, Theme.DIM), ui.fullWidth(10))
        val pr = ui.row()
        pr.addView(ui.button("Acid riff") { preset(0) }, ui.w(1f, 3))
        pr.addView(ui.button("Octave pump") { preset(1) }, ui.w(1f, 3))
        pr.addView(ui.button("Walking") { preset(2) }, ui.w(1f, 3))
        pr.addView(ui.button("Clear all") { preset(3) }, ui.w(1f, 3))
        bassBox.addView(pr, ui.fullWidth(4))

        val panel = ui.panel()
        panel.addView(ui.text("Bass sound", 14f, Theme.ACCENT, true))
        val wr = ui.row()
        sawBtn = ui.button("Saw wave") {
            bs.saw = true
            refreshBass()
        }
        sqBtn = ui.button("Square wave") {
            bs.saw = false
            refreshBass()
        }
        bMuteBtn = ui.button("Mute") {
            bs.mute = !bs.mute
            refreshBass()
        }
        wr.addView(sawBtn, ui.w(1f, 3))
        wr.addView(sqBtn, ui.w(1f, 3))
        wr.addView(bMuteBtn, ui.w(1f, 3))
        panel.addView(wr)
        panel.addView(
            sliderLine(
                slider("Cutoff", { bs.cutoff }, { bs.cutoff = it }),
                slider("Resonance", { bs.res }, { bs.res = it }),
                slider("Env mod", { bs.envMod }, { bs.envMod = it })
            )
        )
        panel.addView(
            sliderLine(
                slider("Decay", { bs.decay }, { bs.decay = it }),
                slider("Accent", { bs.accentAmt }, { bs.accentAmt = it }),
                slider("Drive", { bs.drive }, { bs.drive = it }),
                slider("Volume", { bs.volume }, { bs.volume = it })
            )
        )
        bassBox.addView(panel, ui.fullWidth(8))
    }

    private fun tapStep(i: Int) {
        if (selStep == i) {
            bs.on[i] = !bs.on[i]
        } else {
            selStep = i
            bs.on[i] = true
        }
        if (bs.on[i]) bs.previewReq = bs.note[i]
        refreshBass()
    }

    private fun nudge(d: Int) {
        bs.note[selStep] = (bs.note[selStep] + d).coerceIn(0, 24)
        bs.on[selStep] = true
        bs.previewReq = bs.note[selStep]
        refreshBass()
    }

    private fun st(s: Int, n: Int, ac: Boolean = false, sl: Boolean = false) {
        bs.on[s] = true
        bs.note[s] = n
        bs.accent[s] = ac
        bs.slide[s] = sl
    }

    private fun preset(k: Int) {
        bs.clear()
        when (k) {
            0 -> {
                st(0, 0)
                st(2, 0)
                st(3, 12, true)
                st(5, 0)
                st(6, 3, false, true)
                st(8, 0)
                st(10, 7, true)
                st(11, 5, false, true)
                st(13, 0)
                st(14, 12, true)
            }
            1 -> {
                for (s in 0 until 16 step 2) st(s, if (s % 4 == 0) 0 else 12, s % 8 == 2)
            }
            2 -> {
                st(0, 0)
                st(4, 3)
                st(6, 5)
                st(8, 7)
                st(10, 5, false, true)
                st(12, 3)
                st(14, 0, true)
            }
        }
        refreshBass()
    }

    private fun paint(i: Int) {
        val b = cells[i]
        val base = if (bs.on[i]) {
            Theme.ACCENT
        } else if ((i / 4) % 2 == 0) {
            Theme.PANEL2
        } else {
            Theme.PANEL
        }
        val stroke = if (i == playStep) Theme.TEXT else if (i == selStep) Theme.GREEN else 0
        b.background = ui.bg(base, 8, stroke, if (stroke != 0) 3 else 0)
        b.setTextColor(if (bs.on[i]) Color.BLACK else Theme.DIM)
        b.text = if (bs.on[i]) {
            noteName(bs.note[i]) + (if (bs.accent[i]) "▲" else "") + (if (bs.slide[i]) "~" else "")
        } else {
            "${i + 1}"
        }
    }

    private fun refreshBass() {
        keyText.text = "${noteNames[bs.key]}${bs.octave}"
        ui.styleButton(sawBtn, bs.saw)
        ui.styleButton(sqBtn, !bs.saw)
        ui.styleButton(bMuteBtn, bs.mute, Theme.RED)
        for (i in 0 until Cfg.STEPS) paint(i)
        val s = selStep
        editInfo.text = if (bs.on[s]) {
            "Step ${s + 1}: ${noteName(bs.note[s])} (${bs.note[s]} semitones above the key note)"
        } else {
            "Step ${s + 1}: off. Tap it to turn it on."
        }
        ui.styleButton(accBtn, bs.accent[s])
        ui.styleButton(slideBtn, bs.slide[s])
        refreshMute()
    }

    // ================= Analog synth =================

    private fun buildAnalog() {
        anaBox.addView(
            ui.text(
                "Analog synth. Play the keys (you can press several at once for chords). " +
                    "Choose the wave, then shape the sound with the sliders.",
                12f, Theme.DIM
            ), ui.fullWidth(4)
        )

        val octRow = ui.row()
        octRow.addView(ui.button("Octave −") {
            kbOct = (kbOct - 1).coerceAtLeast(1)
            refreshAnalog()
        }, ui.w(1f, 3))
        octText = ui.text("", 14f, Theme.TEXT, true)
        octText.gravity = Gravity.CENTER
        octRow.addView(octText, LinearLayout.LayoutParams(ui.dp(90), LinearLayout.LayoutParams.WRAP_CONTENT))
        octRow.addView(ui.button("Octave +") {
            kbOct = (kbOct + 1).coerceAtMost(6)
            refreshAnalog()
        }, ui.w(1f, 3))
        aMuteBtn = ui.button("Mute") {
            an.mute = !an.mute
            refreshAnalog()
        }
        octRow.addView(aMuteBtn, ui.w(1f, 3))
        anaBox.addView(octRow, ui.fullWidth(6))

        val kb = ui.row()
        val down = IntArray(13) { -1 }
        for (k in 0..12) {
            val name = noteNames[k % 12]
            val sharp = name.contains('#')
            val b = Button(a)
            b.isAllCaps = false
            b.text = name
            b.textSize = 12f
            b.minHeight = 0
            b.minWidth = 0
            b.setPadding(0, 0, 0, 0)
            b.background = ui.bg(if (sharp) Color.parseColor("#3A3A44") else Color.parseColor("#D8D8DC"), 6)
            b.setTextColor(if (sharp) Theme.TEXT else Color.BLACK)
            b.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.parent.requestDisallowInterceptTouchEvent(true)
                        val n = 12 * (kbOct + 1) + k
                        down[k] = n
                        an.noteOn(n)
                        v.alpha = 0.55f
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (down[k] >= 0) an.noteOff(down[k])
                        down[k] = -1
                        v.alpha = 1f
                    }
                }
                true
            }
            val lp = LinearLayout.LayoutParams(0, ui.dp(110), 1f)
            lp.setMargins(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2))
            kb.addView(b, lp)
        }
        anaBox.addView(kb, ui.fullWidth(4))

        val panel = ui.panel()
        panel.addView(ui.text("Synth sound", 14f, Theme.ACCENT, true))
        val wr = ui.row()
        val wn = listOf("Sine", "Saw", "Square", "Triangle")
        for (i in wn.indices) {
            val b = ui.button(wn[i]) {
                an.wave = i
                refreshAnalog()
            }
            waveBtns.add(b)
            wr.addView(b, ui.w(1f, 3))
        }
        panel.addView(wr)
        panel.addView(
            sliderLine(
                slider("Volume", { an.volume }, { an.volume = it }),
                slider("Thickness (detune)", { an.detune }, { an.detune = it })
            )
        )
        panel.addView(
            sliderLine(
                slider("Attack", { an.attack }, { an.attack = it }),
                slider("Decay", { an.decay }, { an.decay = it }),
                slider("Sustain", { an.sustain }, { an.sustain = it }),
                slider("Release", { an.release }, { an.release = it })
            )
        )
        panel.addView(
            sliderLine(
                slider("Cutoff", { an.cutoff }, { an.cutoff = it }),
                slider("Resonance", { an.res }, { an.res = it }),
                slider("Filter env", { an.filtEnv }, { an.filtEnv = it })
            )
        )
        val fx = an.fx
        val rev = ui.slider("Reverb", 100, 0, { "$it%" }) { v ->
            fx.a[2] = v / 100f
            fx.on[2] = v > 0
        }
        val dly = ui.slider("Delay", 100, 0, { "$it%" }) { v ->
            fx.a[3] = v / 100f
            fx.on[3] = v > 0
        }
        refreshers.add { rev.set(if (fx.on[2]) (fx.a[2] * 100).toInt() else 0) }
        refreshers.add { dly.set(if (fx.on[3]) (fx.a[3] * 100).toInt() else 0) }
        panel.addView(sliderLine(rev, dly))
        anaBox.addView(panel, ui.fullWidth(8))
    }

    private fun refreshAnalog() {
        octText.text = "Octave $kbOct"
        for (i in waveBtns.indices) ui.styleButton(waveBtns[i], an.wave == i)
        ui.styleButton(aMuteBtn, an.mute, Theme.RED)
        refreshMute()
    }

    // ================= Screen =================

    override fun onShow() {
        for (r in refreshers) r()
        refreshBass()
        refreshAnalog()
        lastState = -1
        refreshTransport()
        showMode(mode)
    }

    override fun onTick() {
        refreshTransport()
        val s = if (e.playing) e.curStep else -1
        if (s != playStep) {
            val old = playStep
            playStep = s
            if (old >= 0) paint(old)
            if (s >= 0) paint(s)
        }
    }
}
