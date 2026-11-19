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
    private lateinit var intensityS: SliderRow
    private lateinit var boostS: SliderRow
    private lateinit var startS: SliderRow
    private lateinit var endS: SliderRow
    private lateinit var stepBtn: Button
    private lateinit var loopBtn: Button
    private lateinit var revBtn: Button
    private lateinit var modeHint: TextView

    private fun sel(): Track = e.tracks[a.sel]

    // the editor and the full effects live inside this page (they are put in by attachEmbedded)
    private val editBox = ui.panel()
    private var editScreen: EditScreen? = null
    private var fxScreen: FxScreen? = null

    // track 5: the voice
    private lateinit var voiceWave: WaveView
    private lateinit var voiceMicBtn: Button
    private lateinit var voiceMuteBtn: Button
    private lateinit var voiceVol: SliderRow
    private val quick = QuickSamplePanel(a)      // (before init: it is added to the page there)

    init {
        content.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(88))
        root.addView(content)
        content.addView(ui.title("Sampler"))
        content.addView(
            ui.text(
                "1 · Sample and edit at the top, then send it to Sampler 1-4.  2 · The tracks: " +
                    "L = load · R = record the mic · S = sample an instrument (USB audio / line in) · M = mute. Track 5 is your voice.  " +
                    "3 · Loops, slices and all effects of the selected track below.",
                11f, Theme.DIM
            ), ui.fullWidth(2)
        )
        content.addView(quick.view, ui.fullWidth(8))   // only while a device that takes samples is connected
        editBox.addView(ui.text("1 · Sample · edit · send", 14f, Theme.ACCENT, true))
        content.addView(editBox, ui.fullWidth(8))
        for (i in 0 until Cfg.TRACKS) buildCard(i)
        buildVoiceCard()
        buildEffects()
        buildSettings()
    }

    /** The sub-page of a track: its effects (EF) or its loop and slices (LOOP). */
    fun openTrackPage(i: Int, tab: Int) {
        a.sel = i
        TrackPage(a, i, tab).open()
    }

    /** MainActivity puts the editor and the effects chain into this page once all pages exist. */
    fun attachEmbedded(edit: EditScreen, fx: FxScreen) {
        editScreen = edit
        fxScreen = fx
        editBox.addView(edit.detach(), ui.fullWidth(4))
    }

    fun scrollToEditor() {
        root.post { root.smoothScrollTo(0, editBox.top) }
    }


    // ---------------- track 5: voice ----------------

    private fun buildVoiceCard() {
        val v = e.vocal
        val card = ui.col()
        card.background = ui.bg(Theme.PANEL, 12, Theme.PINK, 2)
        card.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
        val top = ui.row()
        val badge = ui.text("5", 20f, Theme.PINK, true)
        badge.setPadding(0, 0, ui.dp(10), 0)
        top.addView(badge)
        top.addView(ui.text("Voice · live mic", 14f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        voiceMicBtn = ui.button("Mic") {
            a.voiceScreen.toggleMic()
            refreshVoice()
        }
        top.addView(voiceMicBtn, ui.w(0f, 2))
        voiceMuteBtn = ui.button("M") {
            v.mute = !v.mute
            refreshVoice()
        }
        top.addView(voiceMuteBtn, ui.w(0f, 2))
        top.addView(ui.button("Voice ›") { a.showTab(MainActivity.TAB_VOICE) }, ui.w(0f, 2))
        card.addView(top)
        voiceWave = WaveView(a)
        voiceWave.ch = v
        card.addView(voiceWave, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(64)))
        voiceVol = ui.slider("Volume", 100, (v.volume * 100).toInt(), { "$it%" }) { x -> v.volume = x / 100f }
        card.addView(voiceVol.view)
        content.addView(card, ui.fullWidth(8))
    }

    private fun refreshVoice() {
        val v = e.vocal
        voiceMicBtn.text = if (v.on) "Mic ON" else "Mic"
        ui.styleButton(voiceMicBtn, v.on, Theme.GREEN)
        ui.styleButton(voiceMuteBtn, v.mute, Theme.RED)
        voiceVol.set((v.volume * 100).toInt())
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
        val r2 = ui.row()
        intensityS = ui.slider("Effect intensity", 100, 0, { if (it >= 95) "EXTREME" else "${100 + it}%" }) { v ->
            sel().fx.intensity = 1f + v / 100f
        }
        r2.addView(intensityS.view, ui.w(2f, 4))
        r2.addView(ui.button("All 10 effects (EF)") { openTrackPage(a.sel, TrackPage.TAB_FX) }, ui.w(1f, 3))
        p.addView(r2)
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
        val ef = ui.button("EF") { openTrackPage(i, TrackPage.TAB_FX) }
        ui.styleButton(ef, true, Theme.TRACK_COLORS[i])
        top.addView(ef, ui.w(0f, 2))
        top.addView(ui.button("LOOP") { openTrackPage(i, TrackPage.TAB_LOOP) }, ui.w(0f, 2))
        // 7 buttons in one row: keep them compact so they fit a phone
        for (k in 0 until top.childCount) {
            val c = top.getChildAt(k)
            if (c is Button) {
                c.textSize = 12f
                c.setPadding(ui.dp(7), ui.dp(6), ui.dp(7), ui.dp(6))
                (c.layoutParams as? LinearLayout.LayoutParams)?.setMargins(ui.dp(1), ui.dp(1), ui.dp(1), ui.dp(1))
            }
        }
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
        boostS = ui.slider("Gain boost (before the effects)", 12, 0, { if (it == 0) "0 dB" else "+$it dB" }) { v ->
            sel().boostDb = v.toFloat()
        }
        p.addView(boostS.view)
        content.addView(p, ui.fullWidth(10))
    }

    private fun syncRegion() {
        val t = sel()
        startS.set((t.start * 100).toInt())
        endS.set((t.end * 100).toInt())
        waves[a.sel].setRegion(t.start, t.end)
    }

    override fun onShow() {
        editScreen?.onShow()
        refresh()
        refreshVoice()
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
        intensityS.set(((t.fx.intensity - 1f) * 100).toInt().coerceIn(0, 100))
        startS.set((t.start * 100).toInt())
        endS.set((t.end * 100).toInt())
        boostS.set(t.boostDb.toInt())
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
        quick.tick()
        voiceWave.invalidate()
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
