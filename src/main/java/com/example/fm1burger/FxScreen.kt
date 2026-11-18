package com.example.fm1burger

import android.widget.Button
import android.widget.CheckBox
import android.widget.ScrollView

class FxScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    private val e = a.engine
    override val root = ScrollView(a)
    private val content = ui.col()
    private val listBox = ui.col()
    private val trackBtns = ArrayList<Button>()

    init {
        content.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(88))
        root.addView(content)
        content.addView(ui.title("Effects chain"))
        val tr = ui.row()
        for (i in 0 until Cfg.TRACKS) {
            val b = ui.button("Track ${i + 1}") { a.selectTrack(i) }
            trackBtns.add(b)
            tr.addView(b, ui.w(1f, 3))
        }
        content.addView(tr, ui.fullWidth(6))
        content.addView(
            ui.text(
                "The sound goes through the effects from top to bottom. " +
                    "Tick an effect to switch it on. Use the arrows to change the order.",
                12f, Theme.DIM
            ), ui.fullWidth(6)
        )
        content.addView(listBox, ui.fullWidth(6))
    }

    override fun onShow() {
        for (i in 0 until Cfg.TRACKS) ui.styleButton(trackBtns[i], i == a.sel, Theme.TRACK_COLORS[i])
        rebuild()
    }

    private fun rebuild() {
        listBox.removeAllViews()
        val t = e.tracks[a.sel]
        val order = t.fx.order
        for (k in 0 until FxChain.N) {
            val i = order[k]
            val p = ui.panel()
            val r = ui.row()

            val cb = CheckBox(a)
            cb.text = FxChain.NAMES[i]
            cb.setTextColor(Theme.TEXT)
            cb.isChecked = t.fx.on[i]
            cb.setOnCheckedChangeListener { _, c -> t.fx.on[i] = c }
            r.addView(cb, ui.w(1.2f, 2))

            val sa = ui.slider(FxChain.LABEL_A[i], 100, (t.fx.a[i] * 100).toInt(), { "$it%" }) { v ->
                t.fx.a[i] = v / 100f
            }
            val sb = ui.slider(FxChain.LABEL_B[i], 100, (t.fx.b[i] * 100).toInt(), { "$it%" }) { v ->
                t.fx.b[i] = v / 100f
            }
            r.addView(sa.view, ui.w(2f, 4))
            r.addView(sb.view, ui.w(2f, 4))
            r.addView(ui.button("▲") { move(k, -1) }, ui.w(0f, 2))
            r.addView(ui.button("▼") { move(k, 1) }, ui.w(0f, 2))
            p.addView(r)
            listBox.addView(p, ui.fullWidth(6))
        }
    }

    private fun move(k: Int, dir: Int) {
        val t = e.tracks[a.sel]
        val j = k + dir
        if (j < 0 || j >= FxChain.N) return
        val o = t.fx.order.copyOf()
        val tmp = o[k]
        o[k] = o[j]
        o[j] = tmp
        t.fx.order = o
        rebuild()
    }
}
