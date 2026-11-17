package com.example.fm1burger

import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView

class PadsScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    private val e = a.engine
    override val root = ScrollView(a)
    private val content = ui.col()
    private val pads = ArrayList<Button>()

    init {
        content.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(24))
        root.addView(content)
        content.addView(ui.text("Pads", 18f, Theme.ACCENT, true))
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
                b.textSize = 14f
                b.setTextColor(Theme.TEXT)
                b.background = ui.bg(Theme.PANEL2, 14)
                b.setOnClickListener { e.padHit(i, 1f) }
                b.setOnLongClickListener {
                    a.pickSample("Sample for pad ${i + 1}") { f -> a.assignPad(i, f) }
                    true
                }
                pads.add(b)
                val lp = LinearLayout.LayoutParams(0, ui.dp(120), 1f)
                lp.setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
                row.addView(b, lp)
            }
            content.addView(row, ui.fullWidth(6))
        }
    }

    override fun onShow() {
        for (i in 0 until Cfg.PADS) {
            val n = e.pads[i].name
            pads[i].text = "${i + 1}\n" + (if (n.isEmpty()) "(empty)" else n.removeSuffix(".wav"))
        }
    }
}
