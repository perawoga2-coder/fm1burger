package com.example.fm1burger

import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar

/**
 * The drum sequencer, the whole page (3.16: the pads moved to the MPC page).
 * Each pad is one row of 16 steps. Tap a cell to turn it on (full), tap again for medium,
 * again for soft, again to turn it off. The pattern plays together with the 4 tracks.
 * Every row has its own mute button and volume. Play / Pause / Stop are on this page too.
 */
class PadsScreen(private val a: MainActivity) : Screen {
    companion object { const val MAXB = 24 }

    private val ui = a.ui
    private val e = a.engine
    override val root = LockableScrollView(a)
    private val content = ui.col()
    private val labels = ArrayList<Button>()
    private val editBtns = ArrayList<Button>()
    private val kitBox = ui.col()
    private var editing = -1                      // the row whose "e" is open (red)
    private val muteBtns = ArrayList<Button>()
    private val volBars = ArrayList<SeekBar>()
    private val cells = Array(Cfg.PAD_BANK) { arrayOfNulls<View>(MAXB) }   // (3.28: up to a 12/8 bar; the meter shows spb of them)
    private var cellMeter = -1
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
        content.addView(ui.title("Drum sequencer"))
        content.addView(
            ui.text(
                "16 rows per bank, A and B (32 sounds). The sounds are the MPC page's pads: choose them there, " +
                    "or long press a row name here.",
                12f, Theme.DIM
            ), ui.fullWidth(2)
        )
        val br = ui.row()
        for (k in 0 until Cfg.PADS / Cfg.PAD_BANK) {
            val b = ui.button("Bank ${'A' + k}") { setBank(k) }
            bankBtns.add(b)
            br.addView(b, ui.w(1f, 3))
        }
        br.addView(ui.button("MPC pads →") { a.showTab(MainActivity.TAB_MPC) }, ui.w(1f, 3))
        content.addView(br, ui.fullWidth(6))

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
                "Tap a cell: on / off. Hold a cell: its level, full → medium → soft. Tap a row name to hear it, long press it to choose its sample. " +
                    "M = mute that row. The slider at the end of the row is its volume.",
                12f, Theme.DIM
            ), ui.fullWidth(4)
        )

        val r0 = ui.row()
        r0.addView(ui.button("Rock Heavy kit → pads") { rockKit() }, ui.w(1.3f, 3))
        r0.addView(ui.button("Electronic kit → pads") { drumKit() }, ui.w(1.3f, 3))
        r0.addView(ui.button("Clear bank") { clearAll() }, ui.w(1f, 3))
        content.addView(r0, ui.fullWidth(6))
        // 3.25: the Maker Bank's four drum kits (the same as the FM-1's DRUM kits)
        val rk = ui.row()
        for (kit in MakerBank.kits) rk.addView(ui.button("$kit → pads") { makerKit(kit) }, ui.w(1f, 3))
        content.addView(rk, ui.fullWidth(4))
        // 3.26: your own kits (16 slots: the 16 rows of a bank), and saving the rows as one
        content.addView(kitBox, ui.fullWidth(4))

        // 3.37: the rows in their dock (EDIT lock, PLAY, P); a row's name, e, M and volume are not steps
        val gridBox = ui.col()
        for (r in 0 until Cfg.PAD_BANK) gridBox.addView(buildRow(r), ui.fullWidth(2))
        val dock = StepDock(a, prep, gridBox)
        dock.passThrough(labels)
        dock.passThrough(editBtns)
        dock.passThrough(muteBtns)
        dock.passThrough(volBars)
        content.addView(dock.view, ui.fullWidth(4))

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
        lb.textSize = 13f
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
            a.pickSound("Sound for pad ${Cfg.padName(q)}") { f -> a.assignPad(q, f) }
            true
        }
        labels.add(lb)
        row.addView(lb, LinearLayout.LayoutParams(ui.dp(140), ui.dp(46)))
        // 3.24 EDIT DRUM: a small "e" right after the name; red while its edit window is open
        val eb = Button(a)
        eb.isAllCaps = false
        eb.text = "e"
        eb.textSize = 15f
        eb.typeface = Ui.BOLD
        eb.minHeight = 0
        eb.minWidth = 0
        eb.minimumHeight = 0
        eb.minimumWidth = 0
        eb.setPadding(0, 0, 0, 0)
        eb.stateListAnimator = null
        eb.setOnClickListener { editDrum(r) }
        editBtns.add(eb)
        row.addView(eb, LinearLayout.LayoutParams(ui.dp(30), ui.dp(46)).also { it.marginStart = ui.dp(2) })

        for (s in 0 until MAXB) {
            val v = View(a)
            v.setOnClickListener { cycle(r, Pat.base() + s) }
            v.setOnLongClickListener { level(r, Pat.base() + s); true }
            val lp = LinearLayout.LayoutParams(0, ui.dp(46), 1f)
            lp.setMargins(ui.dp(1), ui.dp(2), ui.dp(1), ui.dp(2))
            row.addView(v, lp)
            cells[r][s] = v
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
        val mlp = LinearLayout.LayoutParams(ui.dp(44), ui.dp(46))
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
        row.addView(vb, LinearLayout.LayoutParams(ui.dp(120), ui.dp(46)))
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
        if (c !in 0 until Pat.spb()) return
        val s = Pat.base() + c
        val p = e.pads[pi(r)]
        val base = if (p.on[s]) {
            alphaColor(Theme.ACCENT, (70 + 185 * p.lvl[s]).toInt())
        } else if (Pat.beatOf(c) % 2 == 0) {
            Theme.PANEL2
        } else {
            Theme.PANEL
        }
        val here = s == playStep
        cells[r][c]?.background = ui.bg(base, 6, if (here) Theme.TEXT else 0, if (here) 2 else 0)
    }

    /** A tap: on (full) / off. */
    private fun cycle(r: Int, s: Int) {
        val p = e.pads[pi(r)]
        p.on[s] = !p.on[s]
        p.lvl[s] = levels[0]
        paint(r, s - Pat.base())
        if (p.on[s]) e.padHit(pi(r), p.lvl[s])
    }

    /** A long press: the step's level, full → medium → soft → full (an off step comes on soft... full first). */
    private fun level(r: Int, s: Int) {
        val p = e.pads[pi(r)]
        if (!p.on[s]) {
            p.on[s] = true
            p.lvl[s] = levels[0]
        } else {
            val idx = levels.indexOfFirst { it == p.lvl[s] }
            p.lvl[s] = levels[(idx + 1).coerceAtLeast(0) % levels.size]
        }
        paint(r, s - Pat.base())
        e.padHit(pi(r), p.lvl[s])
        ui.toast(when (p.lvl[s]) { levels[0] -> "Full"; levels[1] -> "Medium"; else -> "Soft" })
    }

    private fun repaintAll() {
        layoutCells()
        for (r in 0 until Cfg.PAD_BANK) for (c in 0 until Pat.spb()) paint(r, c)
    }

    /** 3.28: the meter's bar: spb cells, a gap where each beat ends */
    private fun layoutCells() {
        if (cellMeter == Pat.meter) return
        cellMeter = Pat.meter
        val n = Pat.spb()
        for (r in 0 until Cfg.PAD_BANK) for (c in 0 until MAXB) {
            val v = cells[r][c] ?: continue
            v.visibility = if (c < n) View.VISIBLE else View.GONE
            val lp = v.layoutParams as LinearLayout.LayoutParams
            lp.rightMargin = if (c < n - 1 && Pat.accent(c + 1)) ui.dp(6) else ui.dp(1)
            v.layoutParams = lp
        }
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
    // ---------------- 3.26 your own kits ----------------

    private fun buildKits() {
        Boxes.load(a)
        kitBox.removeAllViews()
        var r: LinearLayout? = null
        val items = Boxes.kits.map { it.name } + listOf("")
        items.forEachIndexed { i, n ->
            if (i % 4 == 0) { r = ui.row(); kitBox.addView(r, ui.fullWidth(2)) }
            val b = if (n.isEmpty()) ui.button("+ Save these rows as a kit") { saveKitDialog() }
                else ui.button("★ $n → pads") { loadKit(n) }.also { it.setOnLongClickListener { kitMenu(n); true } }
            if (n.isNotEmpty()) ui.styleButton(b, true, Theme.YELLOW)
            r!!.addView(b, ui.w(1f, 3))
        }
        if (Boxes.kits.isNotEmpty()) kitBox.addView(ui.text("Long press one of your kits: save the rows into it, rename, delete. " +
            "Change any row with a long press on its name, → Kit in the Maker Bank, or its e.", 11f, Theme.FAINT), ui.fullWidth(2))
    }

    private fun rowsNow(): Array<String> = Array(Boxes.SLOTS) { e.pads[pi(it)].name }

    private fun saveKitDialog(name: String = "") {
        val edit = android.widget.EditText(a)
        edit.setText(name)
        edit.hint = "My Kit 1"
        android.app.AlertDialog.Builder(a).setTitle("Save the ${Cfg.PAD_BANK} rows of bank ${"AB"[bank]} as a kit").setView(edit)
            .setPositiveButton("Save") { _, _ ->
                val n = edit.text.toString().trim()
                if (n.isEmpty()) { ui.toast("Give the kit a name"); return@setPositiveButton }
                val over = Boxes.kit(n) != null
                if (!Boxes.saveKit(a, n, rowsNow())) { ui.toast("$n is the name of a ready-made kit or box: choose another"); return@setPositiveButton }
                buildKits()
                a.libraryScreen.refresh()
                ui.toast(if (over) "Kit $n saved again" else "Kit $n saved (also a box in the Maker Bank: Drum Kits)")
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun loadKit(n: String) {
        val k = Boxes.kit(n) ?: return
        // (one background thread reads the files, then the pads are set and the page is shown once)
        a.assignPads(IntArray(Boxes.SLOTS) { pi(it) }, List(Boxes.SLOTS) { i ->
            val f = k.slots[i]
            if (f.isEmpty() || !a.store.file(f).exists()) null else a.store.file(f)
        })
        ui.toast("$n on the pads (bank ${"AB"[bank]})")
    }

    private fun kitMenu(n: String) {
        android.app.AlertDialog.Builder(a).setTitle("Kit: $n")
            .setItems(arrayOf("Save the rows now into $n", "Rename", "Delete the kit (its sounds stay in the Maker Bank)")) { _, w ->
                when (w) {
                    0 -> { Boxes.saveKit(a, n, rowsNow()); ui.toast("Kit $n saved again") }
                    1 -> {
                        val edit = android.widget.EditText(a)
                        edit.setText(n)
                        android.app.AlertDialog.Builder(a).setTitle("Rename the kit").setView(edit)
                            .setPositiveButton("OK") { _, _ ->
                                val nn = edit.text.toString().trim()
                                if (nn.isEmpty() || (nn != n && (Boxes.kit(nn) != null || Boxes.reserved(a, nn)))) ui.toast("That name is empty, already a kit, or the name of a ready-made kit or box")
                                else { Boxes.kit(n)?.name = nn; Boxes.saveKits(a); buildKits(); a.libraryScreen.refresh() }
                            }.setNegativeButton("Cancel", null).show()
                    }
                    else -> { Boxes.deleteKit(a, n); buildKits(); a.libraryScreen.refresh() }
                }
            }.show()
    }

    private fun makerKit(kit: String) {
        val names = MakerBank.pieces.map { MakerBank.fileName(kit, it) }
        val files = names.map { a.store.file(it) }
        if (files.none { it.exists() }) {
            ui.toast("Getting the Maker Bank ready...")
            Thread {
                // only this kit's 8 sounds (not the whole bank: that is the start's job, and it waits for the music)
                try { MakerBank.install(a, a.store, true, busy = null, only = { it in names }) } catch (ex: Exception) {}
                a.runOnUiThread {
                    if (files.any { it.exists() }) { a.libraryScreen.refresh(); makerKit(kit) }
                    else ui.toast("Could not load $kit")
                }
            }.start()
            return
        }
        val there = files.indices.filter { files[it].exists() }
        a.assignPads(IntArray(there.size) { pi(there[it]) }, there.map { files[it] })
        ui.toast("$kit on the pads: " + MakerBank.pieces.joinToString(", ") + ". Edit any of them with e.")
    }

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
                    a.assignPads(IntArray(DrumKit.padKit.size) { pi(it) },
                        DrumKit.padKit.indices.map { a.store.file(DrumKit.fileName(DrumKit.padKit[it])) })
                    ui.toast(
                        "Drum kit ready: 8 sounds on the pads, " +
                            "all ${DrumKit.names.size} drum sounds are in the Maker Bank."
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
                    a.assignPads(IntArray(RockKit.pads.size) { pi(it) },
                        RockKit.pads.indices.map { a.store.file(RockKit.fileName(RockKit.pads[it])) })
                    a.libraryScreen.refresh()
                    ui.toast("Rock Heavy kit on the pads: kick, snare, hats, toms, crash, china. 4 more sounds are in the Maker Bank.")
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

    // ---------------- 3.24 EDIT DRUM ----------------

    /** "e": red while open, orange when the row's sound is an edited one, dim otherwise */
    private fun paintEdit(r: Int) {
        val b = editBtns[r]
        val n = e.pads[pi(r)].name
        val edited = n.isNotEmpty() && DrumEdit.hasNote(a.filesDir, n)
        b.background = ui.bg(if (r == editing) Theme.RED else Theme.PANEL2, 8)
        b.setTextColor(if (r == editing) Theme.BG else if (edited) Theme.ACCENT else Theme.DIM)
    }

    private fun editDrum(r: Int) {
        val q = pi(r)
        val pad = e.pads[q]
        val cur = pad.name
        if (cur.isEmpty() || pad.data == null) {
            ui.toast("This row has no sound yet: long press its name to choose one.")
            return
        }
        val note = DrumEdit.readNote(a.filesDir, cur)
        val src = note?.src ?: cur
        if (!a.store.file(src).exists() && DrumEdit.kitName(src) == null) {
            ui.toast("The original sound ($src) is no longer in the Maker Bank.")
            return
        }
        val knobs = DrumEdit.knobs(src)
        val k = DrumEdit.defaults(src)
        note?.knobs?.forEach { (id, v) -> if (k.containsKey(id)) k[id] = v }
        val start = HashMap(k)
        var audio: FloatArray? = null                 // the original's samples (sounds edited as audio)
        var last: FloatArray? = pad.data
        var gen = 0
        var changed = false

        editing = r
        paintEdit(r)
        val box = ui.col()
        box.setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6))
        val kindText = when (DrumEdit.kindOf(src)) {
            DrumEdit.Kind.AUDIO -> "Edited as audio. The original stays in the Maker Bank."
            else -> "Made again from the kit's recipe. The original stays in the Maker Bank."
        }
        box.addView(ui.text(kindText, 12f, Theme.DIM))

        // one render at a time, the latest knobs win: a move while a render runs is only noted, and when the render
        // ends the next one starts with the knobs as they are then (all of this on the UI thread, but the render)
        var rendering = false
        var want = false
        var wantPlay = false
        fun pump() {
            if (rendering || !want) return
            want = false
            val play = wantPlay
            wantPlay = false
            val my = gen
            val kk = HashMap(k)
            val aud = audio
            rendering = true
            Thread {
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) } catch (ex: Exception) { }
                val d = try { DrumEdit.render(src, kk, aud) } catch (ex: Throwable) { null }
                a.runOnUiThread {
                    rendering = false
                    if (my != gen) return@runOnUiThread          // (the window was closed)
                    if (d != null) {
                        last = d
                        e.setPad(q, pad.name, d)
                        if (play) e.padHit(q, 1f)
                    }
                    pump()
                }
            }.start()
        }
        fun rebuild(play: Boolean) {
            want = true
            if (play) wantPlay = true
            pump()
        }

        val rows = ArrayList<Pair<DrumEdit.Knob, SliderRow>>()
        for (kn in knobs) {
            val sr = ui.slider(kn.label, kn.max - kn.min, k[kn.id]!! - kn.min, { kn.fmt(it + kn.min) }) { v ->
                k[kn.id] = v + kn.min
                changed = true
                rebuild(false)
            }
            rows.add(Pair(kn, sr))
            box.addView(sr.view, ui.fullWidth(2))
        }
        val br = ui.row()
        br.addView(ui.button("▶ PREVIEW", true) { e.padHit(q, 1f) }, ui.w(1.3f))
        br.addView(ui.button("RESET") {
            for ((kn, sr) in rows) { k[kn.id] = kn.def; sr.set(kn.def - kn.min) }
            changed = true
            rebuild(true)
        }, ui.w(1f))
        box.addView(br, ui.fullWidth(10))
        box.addView(ui.text("Moves are heard at once (also while the pattern plays). DONE keeps the sound on this row and " +
            "saves it in the Maker Bank as \"" + src.removeSuffix(".wav").removePrefix("Drum_").replace('_', ' ') +
            " edit\" (send it to the FM-1 like any sample).", 11f, Theme.FAINT), ui.fullWidth(8))

        val sv = ScrollView(a)
        sv.addView(box)
        val title = Cfg.padName(q) + "  ·  EDIT  " + src.removeSuffix(".wav").removePrefix("Drum_").replace('_', ' ')
        val dlg = android.app.AlertDialog.Builder(a).setTitle(title).setView(sv)
            .setPositiveButton("DONE", null)
            .create()
        dlg.setOnDismissListener {
            gen++
            editing = -1
            finishEdit(q, src, k, start, changed, last, audio)
            paintEdit(r)
        }
        dlg.window?.setDimAmount(0.25f)              // (the rows stay visible behind it)
        dlg.show()

        if (DrumEdit.kindOf(src) == DrumEdit.Kind.AUDIO) {
            Thread {
                val d = a.store.loadEngine(src)
                a.runOnUiThread { audio = d; if (note != null && d != null) rebuild(false) }
            }.start()
        }
    }

    /** DONE: unchanged -> as it was; back at the original's knobs -> the original; else saved as "<name> edit" */
    private fun finishEdit(q: Int, src: String, k: Map<String, Int>, start: Map<String, Int>, changed: Boolean, data: FloatArray?,
                           audio: FloatArray?) {
        val pad = e.pads[q]
        val cur = pad.name
        if (!changed || k == start) {
            a.assignPad(q, a.store.file(cur))          // (what was heard while editing goes back to the file)
            return
        }
        if (k == DrumEdit.defaults(src)) {
            a.assignPad(q, a.store.file(src))
            onShow()
            return
        }
        // what is saved is made again from the knobs as they are now (the last render heard may be an older one)
        val kk = HashMap(k)
        Thread {
            val made = try {
                val aud = audio ?: if (DrumEdit.kindOf(src) == DrumEdit.Kind.AUDIO) a.store.loadEngine(src) else null
                DrumEdit.render(src, kk, aud)
            } catch (ex: Throwable) {
                null
            }
            val d = made ?: data ?: return@Thread
            val f = if (cur != src && DrumEdit.readNote(a.filesDir, cur)?.src == src) a.store.file(cur).also { a.store.overwrite(it, d, Cfg.SR) }
                else a.store.saveNew(src.removeSuffix(".wav") + " edit", d, Cfg.SR)
            DrumEdit.writeNote(a.filesDir, f.name, DrumEdit.Note(src, kk))
            a.runOnUiThread {
                e.setPad(q, f.name, d)
                onShow()
                ui.toast("Saved as ${f.name.removeSuffix(".wav").removePrefix("Drum_").replace('_', ' ')} (on ${Cfg.padName(q)}, and in the Maker Bank)")
            }
        }.start()
    }

    override fun onShow() {
        buildKits()
        for (i in 0 until Cfg.PAD_BANK) {
            val q = pi(i)
            val n = e.pads[q].name
            val shortName = n.removeSuffix(".wav").removePrefix("Drum_").replace('_', ' ')
            labels[i].text = Cfg.padName(q) + "  " + (if (n.isEmpty()) "(empty)" else shortName)
            paintEdit(i)
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
