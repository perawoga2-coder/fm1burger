package com.example.fm1burger

import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Pads: 8 pads you can tap, and below them a drum sequencer.
 * Each pad is one row of 16 steps. Tap a cell to turn it on (full), tap again for medium,
 * again for soft, again to turn it off. The pattern plays together with the 4 tracks.
 */
class PadsScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    private val e = a.engine
    override val root = ScrollView(a)
    private val content = ui.col()
    private val pads = ArrayList<Button>()
    private val labels = ArrayList<Button>()
    private val cells = Array(Cfg.PADS) { arrayOfNulls<View>(Cfg.STEPS) }
    private var playStep = -1

    // 0 = off, then full / medium / soft
    private val levels = floatArrayOf(1f, 0.65f, 0.35f)

    init {
        content.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(24))
        root.addView(content)
        content.addView(ui.text("Pads and drum sequencer", 18f, Theme.ACCENT, true))
        content.addView(
            ui.text(
                "Tap a pad to play it. Long press a pad to choose its sample. " +
                    "MIDI notes from the FM-1 also play the pads (note number modulo 8, so notes 0, 8, 16... play pad 1).",
                12f, Theme.DIM
            ), ui.fullWidth(4)
        )
        for (rowIdx in 0 until 2) {
            val row = ui.row()
            for (c in 0 until 4) {
                val i = rowIdx * 4 + c
                val b = Button(a)
                b.isAllCaps = false
                b.textSize = 13f
                b.setTextColor(Theme.TEXT)
                b.background = ui.bg(Theme.PANEL2, 14)
                b.setOnClickListener { e.padHit(i, 1f) }
                b.setOnLongClickListener {
                    a.pickSample("Sample for pad ${i + 1}") { f -> a.assignPad(i, f) }
                    true
                }
                pads.add(b)
                val lp = LinearLayout.LayoutParams(0, ui.dp(72), 1f)
                lp.setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
                row.addView(b, lp)
            }
            content.addView(row, ui.fullWidth(4))
        }

        content.addView(ui.text("Drum sequencer", 16f, Theme.ACCENT, true), ui.fullWidth(10))
        content.addView(
            ui.text(
                "Press ▶ at the top to play. Tap a cell: full, medium, soft, off. " +
                    "Tap a row name to hear it. Long press a row name to choose its sample.",
                12f, Theme.DIM
            ), ui.fullWidth(4)
        )

        val r0 = ui.row()
        r0.addView(ui.button("Drum kit → pads") { drumKit() }, ui.w(1f, 3))
        r0.addView(ui.button("Clear all") { clearAll() }, ui.w(1f, 3))
        content.addView(r0, ui.fullWidth(6))

        for (r in 0 until Cfg.PADS) content.addView(buildRow(r), ui.fullWidth(2))

        content.addView(ui.text("Ready-made beats (they use the drum kit on the pads)", 13f, Theme.DIM), ui.fullWidth(10))
        val r1 = ui.row()
        r1.addView(ui.button("Rock") { beat(0) }, ui.w(1f, 3))
        r1.addView(ui.button("4 on the floor") { beat(1) }, ui.w(1f, 3))
        r1.addView(ui.button("Hip-hop") { beat(2) }, ui.w(1f, 3))
        content.addView(r1, ui.fullWidth(6))
    }

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
        lb.setOnClickListener { e.padHit(r, 1f) }
        lb.setOnLongClickListener {
            a.pickSample("Sample for row ${r + 1}") { f -> a.assignPad(r, f) }
            true
        }
        labels.add(lb)
        row.addView(lb, LinearLayout.LayoutParams(ui.dp(96), ui.dp(34)))

        for (s in 0 until Cfg.STEPS) {
            val v = View(a)
            v.setOnClickListener { cycle(r, s) }
            val lp = LinearLayout.LayoutParams(0, ui.dp(34), 1f)
            val gap = if (s % 4 == 3 && s < Cfg.STEPS - 1) ui.dp(6) else ui.dp(1)
            lp.setMargins(ui.dp(1), ui.dp(2), gap, ui.dp(2))
            row.addView(v, lp)
            cells[r][s] = v
            paint(r, s)
        }
        return row
    }

    private fun alphaColor(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun paint(r: Int, s: Int) {
        val p = e.pads[r]
        val base = if (p.on[s]) {
            alphaColor(Theme.ACCENT, (70 + 185 * p.lvl[s]).toInt())
        } else if ((s / 4) % 2 == 0) {
            Theme.PANEL2
        } else {
            Theme.PANEL
        }
        val here = s == playStep
        cells[r][s]?.background = ui.bg(base, 6, if (here) Theme.TEXT else 0, if (here) 2 else 0)
    }

    private fun cycle(r: Int, s: Int) {
        val p = e.pads[r]
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
        paint(r, s)
        if (p.on[s]) e.padHit(r, p.lvl[s])
    }

    private fun repaintAll() {
        for (r in 0 until Cfg.PADS) for (s in 0 until Cfg.STEPS) paint(r, s)
    }

    private fun clearAll() {
        for (p in e.pads) {
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
                    for (i in 0 until Cfg.PADS) {
                        a.assignPad(i, a.store.file(DrumKit.fileName(DrumKit.padKit[i])))
                    }
                    ui.toast(
                        "Drum kit ready: 8 sounds on the pads, " +
                            "all ${DrumKit.names.size} drum sounds are in the Library."
                    )
                }
            }
        }.start()
    }

    private fun setRow(r: Int, steps: IntArray, level: Float = 1f) {
        for (s in steps) {
            val p = e.pads[r]
            p.on[s] = true
            p.lvl[s] = level
        }
    }

    // rows: 0 kick, 1 snare, 2 closed hat, 3 open hat, 4 clap, 5 tom low, 6 tom high, 7 crash
    private fun beat(kind: Int) {
        for (p in e.pads) {
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
            else -> {
                setRow(0, intArrayOf(0, 7, 10))
                setRow(1, intArrayOf(4, 12))
                setRow(2, intArrayOf(0, 2, 4, 6, 8, 10, 12, 14), 0.65f)
                setRow(2, intArrayOf(1, 5, 9, 13), 0.35f)
                setRow(4, intArrayOf(12), 0.65f)
            }
        }
        repaintAll()
        val missing = e.pads.take(5).any { it.data == null }
        if (missing) ui.toast("Tip: press \"Drum kit → pads\" first so the rows have drum sounds.")
    }

    override fun onShow() {
        for (i in 0 until Cfg.PADS) {
            val n = e.pads[i].name
            val shortName = n.removeSuffix(".wav").removePrefix("Drum_").replace('_', ' ')
            pads[i].text = "${i + 1}\n" + (if (n.isEmpty()) "(empty)" else shortName)
            labels[i].text = "${i + 1}  " + (if (n.isEmpty()) "(empty)" else shortName)
        }
        repaintAll()
    }

    override fun onTick() {
        val s = if (e.playing) e.curStep else -1
        if (s != playStep) {
            val old = playStep
            playStep = s
            for (r in 0 until Cfg.PADS) {
                if (old >= 0) paint(r, old)
                if (s >= 0) paint(r, s)
            }
        }
    }
}
