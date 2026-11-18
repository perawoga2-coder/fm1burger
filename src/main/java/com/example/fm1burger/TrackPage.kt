package com.example.fm1burger

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The sub-page of one Sampler track, opened with its EF or LOOP button:
 *  EFFECTS: the 10 effects of that track (on / off, two settings each, order) and the effect intensity
 *  LOOP & SLICES: loop direction, loop points, crossfade, fit to bars, keep pitch; slices and their patterns
 * Everything changes the sound live.
 */
class TrackPage(private val a: MainActivity, private val ti: Int, startTab: Int) {

    companion object {
        const val TAB_FX = 0
        const val TAB_LOOP = 1
        private val FITS = intArrayOf(0, 1, 2, 4, 8)
        private val SLICES = intArrayOf(0, 4, 8, 16)
    }

    private val ui = a.ui
    private val t = a.engine.tracks[ti]
    private val col = Theme.TRACK_COLORS[ti]
    private val dlg = Dialog(a, android.R.style.Theme_Material_NoActionBar)
    private val body = ui.col()
    private val fxBox = ui.col()
    private val loopBox = ui.col()
    private lateinit var fxTab: Button
    private lateinit var loopTab: Button
    private var tab = startTab

    // loop controls
    private lateinit var fwdBtn: Button
    private lateinit var pingBtn: Button
    private lateinit var revBtn: Button
    private lateinit var modeBtn: Button
    private val fitBtns = ArrayList<Button>()
    private lateinit var keepBtn: Button
    private val sliceBtns = ArrayList<Button>()
    private lateinit var sliceMap: TextView
    private val sliders = ArrayList<Pair<SliderRow, () -> Int>>()

    init {
        val root = ui.col()
        root.setBackgroundColor(Theme.BG)
        // header: back, the track, the two tabs
        val head = ui.row()
        head.setBackgroundColor(Theme.PANEL)
        head.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
        head.addView(ui.button("‹ Back") { dlg.dismiss() }, ui.w(0f, 3))
        val name = ui.text("Sampler ${ti + 1}" + (if (t.sampleName.isNotEmpty()) " · ${t.sampleName}" else ""), 15f, col, true)
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        head.addView(name, ui.w(1f, 6))
        root.addView(head)
        val tabs = ui.row()
        tabs.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
        fxTab = ui.button("EF · Effects") { show(TAB_FX) }
        loopTab = ui.button("LOOP · Loop & slices") { show(TAB_LOOP) }
        tabs.addView(fxTab, ui.w(1f, 3))
        tabs.addView(loopTab, ui.w(1f, 3))
        root.addView(tabs)

        val sv = ScrollView(a)
        body.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(48))
        sv.addView(body)
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(fxBox)
        body.addView(loopBox)
        buildLoop()
        dlg.setContentView(root)
        dlg.setOnDismissListener { a.refreshCurrent() }
        show(tab)
    }

    fun open() {
        dlg.show()
    }

    private fun show(k: Int) {
        tab = k
        ui.styleButton(fxTab, k == TAB_FX, col)
        ui.styleButton(loopTab, k == TAB_LOOP, col)
        fxBox.visibility = if (k == TAB_FX) View.VISIBLE else View.GONE
        loopBox.visibility = if (k == TAB_LOOP) View.VISIBLE else View.GONE
        if (k == TAB_FX) buildFx() else refreshLoop()
    }

    // ---------------- EFFECTS ----------------

    private fun buildFx() {
        fxBox.removeAllViews()
        val fx = t.fx
        val ip = ui.panel()
        val ins = ui.slider("Effect intensity", 100, ((fx.intensity - 1f) * 100).toInt().coerceIn(0, 100),
            { if (it >= 95) "EXTREME" else "${100 + it}%" }) { v -> fx.intensity = 1f + v / 100f }
        ip.addView(ins.view)
        ip.addView(ui.text("Tick an effect to switch it on. The sound goes through them from top to bottom; ▲ ▼ change the order.",
            11f, Theme.DIM))
        fxBox.addView(ip, ui.fullWidth(4))
        val order = fx.order
        for (k in 0 until FxChain.N) {
            val i = order[k]
            val p = ui.panel()
            val r = ui.row()
            val cb = CheckBox(a)
            cb.text = FxChain.NAMES[i]
            cb.setTextColor(Theme.TEXT)
            cb.isChecked = fx.on[i]
            cb.setOnCheckedChangeListener { _, c -> fx.on[i] = c }
            r.addView(cb, ui.w(1f, 2))
            r.addView(ui.button("▲") { move(k, -1) }, ui.w(0f, 2))
            r.addView(ui.button("▼") { move(k, 1) }, ui.w(0f, 2))
            p.addView(r)
            val sr = ui.row()
            val sa = ui.slider(FxChain.LABEL_A[i], 100, (fx.a[i] * 100).toInt(), { "$it%" }) { v -> fx.a[i] = v / 100f }
            val sb = ui.slider(FxChain.LABEL_B[i], 100, (fx.b[i] * 100).toInt(), { "$it%" }) { v -> fx.b[i] = v / 100f }
            sr.addView(sa.view, ui.w(1f, 4))
            sr.addView(sb.view, ui.w(1f, 4))
            p.addView(sr)
            fxBox.addView(p, ui.fullWidth(6))
        }
    }

    private fun move(k: Int, dir: Int) {
        val j = k + dir
        if (j < 0 || j >= FxChain.N) return
        val o = t.fx.order.copyOf()
        val tmp = o[k]
        o[k] = o[j]
        o[j] = tmp
        t.fx.order = o
        buildFx()
    }

    // ---------------- LOOP & SLICES ----------------

    private fun slider(parent: LinearLayout, name: String, max: Int, get: () -> Int, fmt: (Int) -> String, set: (Int) -> Unit) {
        val s = ui.slider(name, max, get(), fmt, set)
        sliders.add(Pair(s, get))
        parent.addView(s.view, ui.fullWidth(2))
    }

    private fun buildLoop() {
        val mp = ui.panel()
        mp.addView(ui.text("Play mode", 14f, Theme.ACCENT, true))
        modeBtn = ui.button("") {
            t.loop = !t.loop
            refreshLoop()
        }
        mp.addView(modeBtn, ui.fullWidth(4))
        loopBox.addView(mp, ui.fullWidth(4))

        val p = ui.panel()
        p.addView(ui.text("Loop", 14f, Theme.ACCENT, true))
        val r1 = ui.row()
        fwdBtn = ui.button("Forward") {
            t.pingPong = false
            refreshLoop()
        }
        pingBtn = ui.button("Ping-pong") {
            t.pingPong = true
            refreshLoop()
        }
        revBtn = ui.button("Reverse") {
            t.reverse = !t.reverse
            refreshLoop()
        }
        r1.addView(fwdBtn, ui.w(1f, 3))
        r1.addView(pingBtn, ui.w(1f, 3))
        r1.addView(revBtn, ui.w(1f, 3))
        p.addView(r1)
        slider(p, "Loop start", 100, { (t.loopA * 100).toInt() }, { "$it%" }) { v -> t.loopA = (v / 100f).coerceAtMost(t.loopB - 0.01f) }
        slider(p, "Loop end", 100, { (t.loopB * 100).toInt() }, { "$it%" }) { v -> t.loopB = (v / 100f).coerceAtLeast(t.loopA + 0.01f) }
        slider(p, "Crossfade (no click at the loop point)", 100, { t.xfadeMs.toInt() }, { if (it == 0) "off" else "$it ms" }) { v ->
            t.xfadeMs = v.toFloat()
        }
        p.addView(ui.text("Fit the loop to bars of the tempo", 12f, Theme.DIM), ui.fullWidth(6))
        val r2 = ui.row()
        for (k in FITS.indices) {
            val b = ui.button(if (FITS[k] == 0) "Off" else "${FITS[k]} bar" + (if (FITS[k] > 1) "s" else "")) {
                t.fitBars = FITS[k]
                refreshLoop()
            }
            fitBtns.add(b)
            r2.addView(b, ui.w(1f, 2))
        }
        p.addView(r2)
        keepBtn = ui.button("Keep pitch (time stretch)") {
            t.keepPitch = !t.keepPitch
            refreshLoop()
        }
        p.addView(keepBtn, ui.fullWidth(4))
        loopBox.addView(p, ui.fullWidth(8))

        val sp = ui.panel()
        sp.addView(ui.text("Slices (STEP mode): equal parts of the sample, one per step", 14f, Theme.ACCENT, true))
        val r3 = ui.row()
        for (k in SLICES.indices) {
            val b = ui.button(if (SLICES[k] == 0) "Off" else "${SLICES[k]}") {
                t.slices = SLICES[k]
                if (t.slices > 0) {
                    t.loop = false
                    pattern(0)
                }
                refreshLoop()
            }
            sliceBtns.add(b)
            r3.addView(b, ui.w(1f, 2))
        }
        sp.addView(r3)
        val r4 = ui.row()
        r4.addView(ui.button("In order") { pattern(0) }, ui.w(1f, 2))
        r4.addView(ui.button("Shuffle") { pattern(1) }, ui.w(1f, 2))
        r4.addView(ui.button("Reverse some") { pattern(2) }, ui.w(1f, 2))
        r4.addView(ui.button("Stutter") { pattern(3) }, ui.w(1f, 2))
        sp.addView(r4, ui.fullWidth(4))
        sliceMap = ui.text("", 12f, Theme.DIM)
        sliceMap.typeface = android.graphics.Typeface.MONOSPACE
        sp.addView(sliceMap, ui.fullWidth(4))
        loopBox.addView(sp, ui.fullWidth(8))
    }

    /** 0 in order, 1 shuffle, 2 reverse some, 3 stutter. All 16 steps play, so the slices make a beat. */
    private fun pattern(kind: Int) {
        if (t.slices == 0) {
            t.slices = 8
            t.loop = false
        }
        val n = t.slices
        val rnd = java.util.Random()
        for (s in 0 until Cfg.STEPS) {
            t.steps[s].on = true
            t.steps[s].pitch = 0
            t.steps[s].level = 1f
            t.sliceRev[s] = false
            t.sliceOf[s] = when (kind) {
                1 -> rnd.nextInt(n)
                3 -> if (s < 8) s % n else if (s < 12) 8 % n else 12 % n
                else -> s % n
            }
            if (kind == 2) t.sliceRev[s] = rnd.nextInt(4) == 0
            if (kind == 3 && s >= 12) t.steps[s].level = 0.7f + 0.1f * (s - 12)
        }
        refreshLoop()
    }

    private fun refreshLoop() {
        modeBtn.text = if (t.loop) "LOOP: the sample loops (steps ignored) · tap for STEP" else "STEP: the steps play the sample · tap for LOOP"
        ui.styleButton(modeBtn, t.loop, col)
        ui.styleButton(fwdBtn, !t.pingPong, col)
        ui.styleButton(pingBtn, t.pingPong, col)
        ui.styleButton(revBtn, t.reverse, col)
        for (k in fitBtns.indices) ui.styleButton(fitBtns[k], t.fitBars == FITS[k], col)
        ui.styleButton(keepBtn, t.keepPitch, Theme.GREEN)
        for (k in sliceBtns.indices) ui.styleButton(sliceBtns[k], t.slices == SLICES[k], col)
        for ((s, get) in sliders) s.set(get())
        sliceMap.text = if (t.slices == 0) "Slices off" else
            "Step:  " + (1..16).joinToString(" ") { String.format("%2d", it) } + "\nSlice: " +
                (0 until Cfg.STEPS).joinToString(" ") { s ->
                    val k = (if (t.sliceOf[s] >= 0) t.sliceOf[s] else s) % t.slices
                    String.format("%2d", k + 1).let { if (t.sliceRev[s]) it.trim() + "r" else it }
                }
    }
}
