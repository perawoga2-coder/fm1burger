package com.example.fm1burger

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class TracksScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    private val e = a.engine
    override val root = ScrollView(a)
    private val content = ui.col()

    private val cards = ArrayList<LinearLayout>()
    private val names = ArrayList<TextView>()
    private val waves = ArrayList<WaveformView>()
    private val muteBtns = ArrayList<Button>()
    private val recBtns = ArrayList<Button>()
    private val sampBtns = ArrayList<Button>()
    private val lastData = arrayOfNulls<FloatArray>(Cfg.TRACKS)

    private lateinit var fxTitle: TextView
    private lateinit var delayS: SliderRow
    private lateinit var reverbS: SliderRow
    private lateinit var pitchS: SliderRow
    private lateinit var lpS: SliderRow
    private lateinit var hpS: SliderRow
    private lateinit var startS: SliderRow
    private lateinit var endS: SliderRow
    private lateinit var stepBtn: Button
    private lateinit var loopBtn: Button
    private lateinit var revBtn: Button
    private lateinit var modeHint: TextView

    private fun sel(): Track = e.tracks[a.sel]

    init {
        content.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(24))
        root.addView(content)
        content.addView(
            ui.text(
                "L = load a sample (library or this device) · R = record with the mic (internal or external) · " +
                    "S = sample the FM-1 (up to 60 s, then opens in Edit) · M = mute. " +
                    "R and S: tap to start, tap ■ to stop. Long press R or S for options.",
                11f, Theme.DIM
            ), ui.fullWidth(0)
        )
        buildEffects()
        for (i in 0 until Cfg.TRACKS) buildCard(i)
        buildSettings()
    }

    private fun buildEffects() {
        val p = ui.panel()
        fxTitle = ui.text("Track 1 effects", 14f, Theme.ACCENT, true)
        p.addView(fxTitle)
        val row = ui.row()
        delayS = ui.slider("Delay", 100, 0, { "$it%" }) { v ->
            val t = sel()
            t.fx.a[3] = v / 100f
            t.fx.on[3] = v > 0
        }
        reverbS = ui.slider("Reverb", 100, 0, { "$it%" }) { v ->
            val t = sel()
            t.fx.a[2] = v / 100f
            t.fx.on[2] = v > 0
        }
        pitchS = ui.slider("Pitch", 48, 24, { (it - 24).toString() + " st" }) { v ->
            sel().pitch = v - 24
        }
        lpS = ui.slider("Low-pass", 100, 100, { if (it >= 99) "open" else "$it%" }) { v ->
            val t = sel()
            t.fx.a[0] = v / 100f
            t.fx.on[0] = v < 99
        }
        hpS = ui.slider("High-pass", 100, 0, { if (it <= 1) "open" else "$it%" }) { v ->
            val t = sel()
            t.fx.a[1] = v / 100f
            t.fx.on[1] = v > 1
        }
        for (s in listOf(delayS, reverbS, pitchS, lpS, hpS)) row.addView(s.view, ui.w(1f, 4))
        p.addView(row)
        content.addView(p, ui.fullWidth(0))
    }

    private fun buildCard(i: Int) {
        val card = ui.col()
        card.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
        card.setOnClickListener { a.selectTrack(i) }

        val top = ui.row()
        val badge = ui.text("${i + 1}", 20f, Theme.TRACK_COLORS[i], true)
        badge.setPadding(0, 0, ui.dp(10), 0)
        val name = ui.text("", 14f)
        name.setOnClickListener { a.selectTrack(i) }
        top.addView(badge)
        top.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(ui.button("▶") { e.audition(i) }, ui.w(0f, 2))
        top.addView(ui.button("L") { a.loadDialog(i) }, ui.w(0f, 2))
        val r = ui.button("R") { a.flow.onRecordButton(i) }
        r.setOnLongClickListener {
            a.flow.onRecordOptions(i)
            true
        }
        top.addView(r, ui.w(0f, 2))
        val s = ui.button("S") { a.flow.onSampleButton(i) }
        s.setOnLongClickListener {
            a.flow.onSampleOptions(i)
            true
        }
        top.addView(s, ui.w(0f, 2))
        val m = ui.button("M") {
            val t = e.tracks[i]
            t.mute = !t.mute
            refresh()
        }
        top.addView(m, ui.w(0f, 2))
        card.addView(top)

        val wv = WaveformView(a)
        wv.color = Theme.TRACK_COLORS[i]
        wv.setOnClickListener { a.selectTrack(i) }
        card.addView(wv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(64)))

        cards.add(card)
        names.add(name)
        waves.add(wv)
        muteBtns.add(m)
        recBtns.add(r)
        sampBtns.add(s)
        content.addView(card, ui.fullWidth(8))
    }

    private fun buildSettings() {
        val p = ui.panel()
        p.addView(ui.text("Selected track settings", 14f, Theme.ACCENT, true))
        val row = ui.row()
        stepBtn = ui.button("STEP") {
            sel().loop = false
            refresh()
        }
        loopBtn = ui.button("LOOP") {
            sel().loop = true
            refresh()
        }
        revBtn = ui.button("Reverse") {
            val t = sel()
            t.reverse = !t.reverse
            refresh()
        }
        row.addView(stepBtn, ui.w(1f, 3))
        row.addView(loopBtn, ui.w(1f, 3))
        row.addView(revBtn, ui.w(1f, 3))
        p.addView(row)
        modeHint = ui.text("", 12f, Theme.DIM)
        p.addView(modeHint)
        startS = ui.slider("Start", 100, 0, { "$it%" }) { v ->
            val t = sel()
            t.start = (v / 100f).coerceAtMost(t.end - 0.01f)
            syncRegion()
        }
        endS = ui.slider("End", 100, 100, { "$it%" }) { v ->
            val t = sel()
            t.end = (v / 100f).coerceAtLeast(t.start + 0.01f)
            syncRegion()
        }
        p.addView(startS.view)
        p.addView(endS.view)
        content.addView(p, ui.fullWidth(10))
    }

    private fun syncRegion() {
        val t = sel()
        startS.set((t.start * 100).toInt())
        endS.set((t.end * 100).toInt())
        waves[a.sel].setRegion(t.start, t.end)
    }

    override fun onShow() {
        refresh()
    }

    fun refresh() {
        for (i in 0 until Cfg.TRACKS) {
            val t = e.tracks[i]
            names[i].text = if (t.sampleName.isEmpty()) "(empty: press L, R or S)" else t.sampleName
            if (lastData[i] !== t.data) {
                lastData[i] = t.data
                waves[i].setData(t.data)
            }
            waves[i].setRegion(t.start, t.end)
            cards[i].background = ui.bg(
                Theme.PANEL, 12,
                if (a.sel == i) Theme.TRACK_COLORS[i] else Theme.PANEL, 2
            )
            ui.styleButton(muteBtns[i], t.mute, Theme.RED)
            val mic = a.flow.isMicTrack(i)
            recBtns[i].text = if (mic) "■" else "R"
            ui.styleButton(recBtns[i], mic, Theme.RED)
            val samp = a.flow.isSamplingTrack(i)
            sampBtns[i].text = if (samp) "■" else "S"
            ui.styleButton(sampBtns[i], samp, Theme.RED)
        }
        val t = sel()
        fxTitle.text = "Track ${a.sel + 1} effects"
        fxTitle.setTextColor(Theme.TRACK_COLORS[a.sel])
        delayS.set(if (t.fx.on[3]) (t.fx.a[3] * 100).toInt() else 0)
        reverbS.set(if (t.fx.on[2]) (t.fx.a[2] * 100).toInt() else 0)
        pitchS.set(t.pitch + 24)
        lpS.set(if (t.fx.on[0]) (t.fx.a[0] * 100).toInt() else 100)
        hpS.set(if (t.fx.on[1]) (t.fx.a[1] * 100).toInt() else 0)
        startS.set((t.start * 100).toInt())
        endS.set((t.end * 100).toInt())
        ui.styleButton(stepBtn, !t.loop)
        ui.styleButton(loopBtn, t.loop)
        ui.styleButton(revBtn, t.reverse)
        modeHint.text = if (t.loop) {
            "LOOP: the sample restarts at every bar and repeats. The steps are ignored."
        } else {
            "STEP: the sample is played by the steps on the Steps tab."
        }
    }

    override fun onTick() {
        for (i in 0 until Cfg.TRACKS) {
            waves[i].playhead = e.tracks[i].playFraction()
        }
        for (i in 0 until Cfg.TRACKS) {
            val wantR = if (a.flow.isMicTrack(i)) "■" else "R"
            val wantS = if (a.flow.isSamplingTrack(i)) "■" else "S"
            if (recBtns[i].text.toString() != wantR || sampBtns[i].text.toString() != wantS) refresh()
        }
    }
}
