package com.example.fm1burger

import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar

/**
 * Pads: 8 pads you can tap, and below them a drum sequencer.
 * Each pad is one row of 16 steps. Tap a cell to turn it on (full), tap again for medium,
 * again for soft, again to turn it off. The pattern plays together with the 4 tracks.
 * Every row has its own mute button and volume. Play / Pause / Stop are on this page too.
 */
class PadsScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    private val e = a.engine
    override val root = ScrollView(a)
    private val content = ui.col()
    private val pads = ArrayList<Button>()
    private val labels = ArrayList<Button>()
    private val muteBtns = ArrayList<Button>()
    private val volBars = ArrayList<SeekBar>()
    private val cells = Array(Cfg.PAD_BANK) { arrayOfNulls<View>(Pat.BAR) }
    /** The bank shown: 0 = A (pads 1-16), 1 = B. Rows and pads on screen are the 16 of this bank. */
    private var bank = 0
    private val bankBtns = ArrayList<Button>()
    private fun pi(r: Int): Int = bank * Cfg.PAD_BANK + r
    private var playStep = -1
    private var lastState = -1

    private lateinit var playBtn: Button
    private lateinit var pauseBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var muteAllBtn: Button
    private lateinit var volS: SliderRow
    private lateinit var heavyS: SliderRow
    private lateinit var reverbS: SliderRow
    private lateinit var delayS: SliderRow
    private lateinit var lpS: SliderRow
    private lateinit var hpS: SliderRow
    private lateinit var driveS: SliderRow

    // 0 = off, then full / medium / soft
    private val levels = floatArrayOf(1f, 0.65f, 0.35f)
    private val prep = PrepareBar(a, false) { Engine.PREP_PADS }

    init {
        content.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(88))
        root.addView(content)
        content.addView(ui.title("Pads and drum sequencer"))
        content.addView(
            ui.text(
                "16 pads in each of 2 banks (A and B, 32 sounds). Tap a pad to play it, long press it to choose its sample. " +
                    "Notes from a MIDI keyboard or controller also play the pads (note number modulo 32: notes 0-15 bank A, 16-31 bank B).",
                12f, Theme.DIM
            ), ui.fullWidth(4)
        )
        val br = ui.row()
        for (k in 0 until Cfg.PADS / Cfg.PAD_BANK) {
            val b = ui.button("Bank ${'A' + k}") { setBank(k) }
            bankBtns.add(b)
            br.addView(b, ui.w(1f, 3))
        }
        content.addView(br, ui.fullWidth(4))
        for (rowIdx in 0 until 4) {
            val row = ui.row()
            for (c in 0 until 4) {
                val i = rowIdx * 4 + c
                val b = Button(a)
                b.isAllCaps = false
                b.textSize = 13f
                b.setTextColor(Theme.TEXT)
                b.background = ui.bg(Theme.PANEL2, 14)
                b.setOnClickListener { e.padHit(pi(i), 1f) }
                b.setOnLongClickListener {
                    val q = pi(i)
                    a.pickSample("Sample for pad ${Cfg.padName(q)}") { f -> a.assignPad(q, f) }
                    true
                }
                pads.add(b)
                val lp = LinearLayout.LayoutParams(0, ui.dp(64), 1f)
                lp.setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
                row.addView(b, lp)
            }
            content.addView(row, ui.fullWidth(4))
        }

        content.addView(ui.title("Drum sequencer"), ui.fullWidth(10))

        val tr = ui.row()
        playBtn = ui.button("▶ Play") { doPlay() }
        pauseBtn = ui.button("|| Pause") { doPause() }
        stopBtn = ui.button("■ Stop") { e.stop() }
        muteAllBtn = ui.button("Mute all drums") { muteAll() }
        tr.addView(playBtn, ui.w(1f, 3))
        tr.addView(pauseBtn, ui.w(1f, 3))
        tr.addView(stopBtn, ui.w(1f, 3))
        tr.addView(muteAllBtn, ui.w(1.3f, 3))
        tr.addView(prep.pButton, 0, ui.w(0f, 3))
        prep.onPage = { repaintAll() }
        content.addView(tr, ui.fullWidth(6))
        content.addView(prep.view, ui.fullWidth(0))

        content.addView(
            ui.text(
                "Tap a cell: full, medium, soft, off. Tap a row name to hear it, long press it to choose its sample. " +
                    "M = mute that row. The slider at the end of the row is its volume.",
                12f, Theme.DIM
            ), ui.fullWidth(4)
        )

        val r0 = ui.row()
        r0.addView(ui.button("Rock Heavy kit → pads") { rockKit() }, ui.w(1.3f, 3))
        r0.addView(ui.button("Electronic kit → pads") { drumKit() }, ui.w(1.3f, 3))
        r0.addView(ui.button("Clear bank") { clearAll() }, ui.w(1f, 3))
        content.addView(r0, ui.fullWidth(6))

        for (r in 0 until Cfg.PAD_BANK) content.addView(buildRow(r), ui.fullWidth(2))

        buildEffects()

        content.addView(ui.text("Ready-made beats (they use the drum kit on the pads)", 13f, Theme.DIM), ui.fullWidth(10))
        val r1 = ui.row()
        r1.addView(ui.button("Rock") { beat(0) }, ui.w(1f, 3))
        r1.addView(ui.button("4 on the floor") { beat(1) }, ui.w(1f, 3))
        r1.addView(ui.button("Hip-hop") { beat(2) }, ui.w(1f, 3))
        content.addView(r1, ui.fullWidth(6))
        content.addView(ui.text("Metal beats (made for the Rock Heavy kit)", 13f, Theme.DIM), ui.fullWidth(8))
        val r2 = ui.row()
        r2.addView(ui.button("Nu-metal") { beat(3) }, ui.w(1f, 3))
        r2.addView(ui.button("Double kick") { beat(4) }, ui.w(1f, 3))
        r2.addView(ui.button("Techno metal") { beat(5) }, ui.w(1f, 3))
        r2.addView(ui.button("Psy metal") { beat(6) }, ui.w(1f, 3))
        content.addView(r2, ui.fullWidth(4))
    }

    // ---------------- drum effects (the whole drum kit together) ----------------

    private fun buildEffects() {
        val p = ui.panel()
        p.addView(ui.text("Drum volume and effects (all drums together)", 14f, Theme.ACCENT, true))
        heavyS = ui.slider("HEAVY: weight, punch and density", 100, (e.drumHeavy.amount * 100f).toInt(),
            { if (it == 0) "off" else if (it > 80) "crushing" else "$it%" }) { v ->
            e.drumHeavy.amount = v / 100f
        }
        p.addView(heavyS.view, ui.fullWidth(4))
        val fx = e.drumFx
        val row = ui.row()
        volS = ui.slider("Volume", 100, (e.padVol * 100f).toInt(), { "$it%" }) { v ->
            e.padVol = v / 100f
        }
        reverbS = ui.slider("Reverb", 100, 0, { "$it%" }) { v ->
            fx.a[2] = v / 100f
            fx.on[2] = v > 0
        }
        delayS = ui.slider("Delay", 100, 0, { "$it%" }) { v ->
            fx.a[3] = v / 100f
            fx.on[3] = v > 0
        }
        lpS = ui.slider("Low-pass", 100, 100, { if (it >= 99) "open" else "$it%" }) { v ->
            fx.a[0] = v / 100f
            fx.on[0] = v < 99
        }
        hpS = ui.slider("High-pass", 100, 0, { if (it <= 1) "open" else "$it%" }) { v ->
            fx.a[1] = v / 100f
            fx.on[1] = v > 1
        }
        driveS = ui.slider("Drive", 100, 0, { "$it%" }) { v ->
            fx.a[4] = v / 100f
            fx.on[4] = v > 0
        }
        for (s in listOf(volS, reverbS, delayS, lpS, hpS, driveS)) row.addView(s.view, ui.w(1f, 4))
        p.addView(row)
        content.addView(p, ui.fullWidth(8))
    }

    private fun refreshEffects() {
        val fx = e.drumFx
        volS.set((e.padVol * 100f).toInt())
        heavyS.set((e.drumHeavy.amount * 100f).toInt())
        reverbS.set(if (fx.on[2]) (fx.a[2] * 100).toInt() else 0)
        delayS.set(if (fx.on[3]) (fx.a[3] * 100).toInt() else 0)
        lpS.set(if (fx.on[0]) (fx.a[0] * 100).toInt() else 100)
        hpS.set(if (fx.on[1]) (fx.a[1] * 100).toInt() else 0)
        driveS.set(if (fx.on[4]) (fx.a[4] * 100).toInt() else 0)
    }

    // ---------------- transport ----------------

    private fun doPlay() {
        prep.play()
    }

    private fun doPause() {
        if (e.playing && !e.paused) e.pause()
    }

    private fun refreshTransport() {
        val st = if (!e.playing) 0 else if (e.paused) 2 else 1
        if (st == lastState) return
        lastState = st
        ui.styleButton(playBtn, st == 1)
        ui.styleButton(pauseBtn, st == 2)
        ui.styleButton(stopBtn, false)
    }

    // ---------------- grid ----------------

    private fun buildRow(r: Int): LinearLayout {
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL

        val lb = Button(a)
        lb.isAllCaps = false
        lb.textSize = 11f
        lb.minHeight = 0
        lb.minWidth = 0
        lb.maxLines = 1
        lb.ellipsize = TextUtils.TruncateAt.END
        lb.setPadding(ui.dp(4), 0, ui.dp(4), 0)
        lb.setTextColor(Theme.TEXT)
        lb.background = ui.bg(Theme.PANEL2, 8)
        lb.setOnClickListener { e.padHit(pi(r), 1f) }
        lb.setOnLongClickListener {
            val q = pi(r)
            a.pickSample("Sample for pad ${Cfg.padName(q)}") { f -> a.assignPad(q, f) }
            true
        }
        labels.add(lb)
        row.addView(lb, LinearLayout.LayoutParams(ui.dp(96), ui.dp(34)))

        for (s in 0 until Pat.BAR) {
            val v = View(a)
            v.setOnClickListener { cycle(r, Pat.base() + s) }
            val lp = LinearLayout.LayoutParams(0, ui.dp(34), 1f)
            val gap = if (s % 4 == 3 && s < Pat.BAR - 1) ui.dp(6) else ui.dp(1)
            lp.setMargins(ui.dp(1), ui.dp(2), gap, ui.dp(2))
            row.addView(v, lp)
            cells[r][s] = v
            paint(r, s)
        }

        val mb = Button(a)
        mb.isAllCaps = false
        mb.text = "M"
        mb.textSize = 12f
        mb.minHeight = 0
        mb.minWidth = 0
        mb.setPadding(0, 0, 0, 0)
        mb.setTextColor(Theme.TEXT)
        mb.background = ui.bg(Theme.PANEL2, 8)
        mb.setOnClickListener {
            val pp = e.pads[pi(r)]
            pp.mute = !pp.mute
            refreshMutes()
        }
        muteBtns.add(mb)
        val mlp = LinearLayout.LayoutParams(ui.dp(36), ui.dp(34))
        mlp.setMargins(ui.dp(4), 0, ui.dp(4), 0)
        row.addView(mb, mlp)

        val vb = SeekBar(a)
        vb.max = 100
        vb.progress = (e.pads[pi(r)].volume * 100f).toInt().coerceIn(0, 100)
        vb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) e.pads[pi(r)].volume = p / 100f
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        volBars.add(vb)
        row.addView(vb, LinearLayout.LayoutParams(ui.dp(110), ui.dp(34)))
        return row
    }

    private fun refreshMutes() {
        for (r in 0 until Cfg.PAD_BANK) ui.styleButton(muteBtns[r], e.pads[pi(r)].mute, Theme.RED)
        val all = e.pads.all { it.mute }
        muteAllBtn.text = if (all) "Unmute all drums" else "Mute all drums"
        ui.styleButton(muteAllBtn, all, Theme.RED)
    }

    private fun muteAll() {
        val anyOn = e.pads.any { !it.mute }
        for (p in e.pads) p.mute = anyOn
        refreshMutes()
    }

    private fun alphaColor(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    /** [c] = column 0..15 of the bar shown. */
    private fun paint(r: Int, c: Int) {
        if (c !in 0 until Pat.BAR) return
        val s = Pat.base() + c
        val p = e.pads[pi(r)]
        val base = if (p.on[s]) {
            alphaColor(Theme.ACCENT, (70 + 185 * p.lvl[s]).toInt())
        } else if ((s / 4) % 2 == 0) {
            Theme.PANEL2
        } else {
            Theme.PANEL
        }
        val here = s == playStep
        cells[r][c]?.background = ui.bg(base, 6, if (here) Theme.TEXT else 0, if (here) 2 else 0)
    }

    private fun cycle(r: Int, s: Int) {
        val p = e.pads[pi(r)]
        if (!p.on[s]) {
            p.on[s] = true
            p.lvl[s] = levels[0]
        } else {
            val idx = levels.indexOfFirst { it == p.lvl[s] }
            if (idx < 0 || idx >= levels.size - 1) {
                p.on[s] = false
                p.lvl[s] = 1f
            } else {
                p.lvl[s] = levels[idx + 1]
            }
        }
        paint(r, s - Pat.base())
        if (p.on[s]) e.padHit(pi(r), p.lvl[s])
    }

    private fun repaintAll() {
        for (r in 0 until Cfg.PAD_BANK) for (c in 0 until Pat.BAR) paint(r, c)
    }

    private fun bankPads(): List<PadVoice> = (0 until Cfg.PAD_BANK).map { e.pads[pi(it)] }

    private fun setBank(k: Int) {
        if (k > 0 && !Features.gate(a, Features.PAD_BANK_B)) return
        bank = k
        onShow()
    }

    private fun clearAll() {
        for (p in bankPads()) {
            for (s in 0 until Cfg.STEPS) {
                p.on[s] = false
                p.lvl[s] = 1f
            }
        }
        repaintAll()
    }

    /** Makes the drum sounds (if they are not in the library yet) and puts 8 of them on the pads. */
    private fun drumKit() {
        ui.toast("Making the drum kit...")
        Thread {
            val made = try {
                DrumKit.ensureAll(a.store)
            } catch (ex: Exception) {
                -1
            }
            a.runOnUiThread {
                if (made < 0) {
                    ui.toast("Could not make the drum kit")
                } else {
                    for (i in DrumKit.padKit.indices) {
                        a.assignPad(pi(i), a.store.file(DrumKit.fileName(DrumKit.padKit[i])))
                    }
                    ui.toast(
                        "Drum kit ready: 8 sounds on the pads, " +
                            "all ${DrumKit.names.size} drum sounds are in the Library."
                    )
                }
            }
        }.start()
    }

    private fun rockKit() {
        ui.toast("Loading the Rock Heavy kit...")
        Thread {
            val ok = try {
                RockKit.install(a, a.store)
                true
            } catch (ex: Exception) {
                false
            }
            a.runOnUiThread {
                if (!ok) {
                    ui.toast("Could not load the Rock Heavy kit")
                } else {
                    for (i in RockKit.pads.indices) a.assignPad(pi(i), a.store.file(RockKit.fileName(RockKit.pads[i])))
                    a.libraryScreen.refresh()
                    ui.toast("Rock Heavy kit on the pads: kick, snare, hats, toms, crash, china. 4 more sounds are in the Library.")
                }
            }
        }.start()
    }

    private fun setRow(r: Int, steps: IntArray, level: Float = 1f) {
        for (s in steps) {
            val p = e.pads[pi(r)]
            p.on[s] = true
            p.lvl[s] = level
        }
    }

    // rows: 0 kick, 1 snare, 2 closed hat, 3 open hat, 4 clap, 5 tom low, 6 tom high, 7 crash
    private fun beat(kind: Int) {
        for (p in bankPads()) {
            for (s in 0 until Cfg.STEPS) {
                p.on[s] = false
                p.lvl[s] = 1f
            }
        }
        when (kind) {
            0 -> {
                setRow(0, intArrayOf(0, 8, 10))
                setRow(1, intArrayOf(4, 12))
                setRow(2, intArrayOf(0, 2, 4, 6, 8, 10, 12, 14), 0.65f)
            }
            1 -> {
                setRow(0, intArrayOf(0, 4, 8, 12))
                setRow(4, intArrayOf(4, 12))
                setRow(2, intArrayOf(0, 4, 8, 12), 0.65f)
                setRow(3, intArrayOf(2, 6, 10, 14), 0.65f)
            }
            3 -> {
                // nu-metal: syncopated kick, big snare on 2 and 4, open hat lift, crash on 1
                setRow(0, intArrayOf(0, 3, 6, 10, 11))
                setRow(1, intArrayOf(4, 12))
                setRow(2, intArrayOf(0, 2, 4, 6, 8, 10, 12), 0.6f)
                setRow(3, intArrayOf(14), 0.6f)
                setRow(6, intArrayOf(0), 0.8f)
            }
            4 -> {
                // double kick 16ths, snare 2 and 4, china on the beats
                setRow(0, IntArray(16) { it })
                for (s in 0 until 16) e.pads[0].lvl[s] = if (s % 2 == 0) 1f else 0.75f
                setRow(1, intArrayOf(4, 12))
                setRow(7, intArrayOf(0, 8), 0.7f)
                setRow(2, intArrayOf(2, 6, 10, 14), 0.5f)
            }
            5 -> {
                // techno metal: four on the floor, offbeat open hats, 16th closed hats, snare on 2 and 4
                setRow(0, intArrayOf(0, 4, 8, 12))
                setRow(1, intArrayOf(4, 12))
                setRow(3, intArrayOf(2, 6, 10, 14), 0.6f)
                setRow(2, intArrayOf(1, 3, 5, 7, 9, 11, 13, 15), 0.35f)
                setRow(6, intArrayOf(0), 0.7f)
            }
            6 -> {
                // psytrance metal: kick on the beat, the rolling bass lives in the gaps (rhythm guitar "Psy roll")
                setRow(0, intArrayOf(0, 4, 8, 12))
                setRow(1, intArrayOf(4, 12), 0.9f)
                setRow(3, intArrayOf(2, 6, 10, 14), 0.55f)
                setRow(2, intArrayOf(0, 1, 3, 4, 5, 7, 8, 9, 11, 12, 13, 15), 0.3f)
                setRow(4, intArrayOf(15), 0.7f)
                setRow(5, intArrayOf(14), 0.8f)
            }
            else -> {
                setRow(0, intArrayOf(0, 7, 10))
                setRow(1, intArrayOf(4, 12))
                setRow(2, intArrayOf(0, 2, 4, 6, 8, 10, 12, 14), 0.65f)
                setRow(2, intArrayOf(1, 5, 9, 13), 0.35f)
                setRow(4, intArrayOf(12), 0.65f)
            }
        }
        // the same beat in every bar of a longer pattern
        for (p in bankPads()) for (s in Pat.BAR until Cfg.STEPS) {
            p.on[s] = p.on[s % Pat.BAR]
            p.lvl[s] = p.lvl[s % Pat.BAR]
        }
        repaintAll()
        val missing = bankPads().take(5).any { it.data == null }
        if (missing) ui.toast("Tip: press \"Drum kit → pads\" first so the rows have drum sounds.")
    }

    override fun onShow() {
        for (i in 0 until Cfg.PAD_BANK) {
            val q = pi(i)
            val n = e.pads[q].name
            val shortName = n.removeSuffix(".wav").removePrefix("Drum_").replace('_', ' ')
            pads[i].text = Cfg.padName(q) + "\n" + (if (n.isEmpty()) "(empty)" else shortName)
            labels[i].text = Cfg.padName(q) + "  " + (if (n.isEmpty()) "(empty)" else shortName)
            volBars[i].progress = (e.pads[q].volume * 100f).toInt().coerceIn(0, 100)
        }
        for (k in bankBtns.indices) ui.styleButton(bankBtns[k], k == bank, Theme.RED)
        refreshEffects()
        lastState = -1
        refreshTransport()
        refreshMutes()
        repaintAll()
    }

    override fun onTick() {
        prep.refresh()
        refreshTransport()
        val s = if (e.playing) e.curStep else -1
        if (s != playStep) {
            val old = playStep
            playStep = s
            for (r in 0 until Cfg.PAD_BANK) {
                if (old >= 0) paint(r, old - Pat.base())
                if (s >= 0) paint(r, s - Pat.base())
            }
        }
    }
}
