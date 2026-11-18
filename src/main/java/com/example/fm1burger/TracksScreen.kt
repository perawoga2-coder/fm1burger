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
    private val fxBox = ui.panel()
    private var editScreen: EditScreen? = null
    private var fxScreen: FxScreen? = null

    // track 5: the voice
    private lateinit var voiceWave: WaveView
    private lateinit var voiceMicBtn: Button
    private lateinit var voiceMuteBtn: Button
    private lateinit var voiceVol: SliderRow

    // loops and slices of the selected track
    private lateinit var fwdBtn: Button
    private lateinit var pingBtn: Button
    private lateinit var loopAS: SliderRow
    private lateinit var loopBS: SliderRow
    private lateinit var xfadeS: SliderRow
    private val fitBtns = ArrayList<Button>()
    private lateinit var keepPitchBtn: Button
    private val sliceBtns = ArrayList<Button>()
    private lateinit var sliceMap: TextView
    private val FITS = intArrayOf(0, 1, 2, 4, 8)
    private val SLICES = intArrayOf(0, 4, 8, 16)

    init {
        content.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(88))
        root.addView(content)
        content.addView(ui.title("Sampler"))
        content.addView(
            ui.text(
                "1 · Sample and edit at the top, then send it to Sampler 1-4.  2 · The tracks: " +
                    "L = load · R = record the mic · S = sample the FM-1 · M = mute. Track 5 is your voice.  " +
                    "3 · Loops, slices and all effects of the selected track below.",
                11f, Theme.DIM
            ), ui.fullWidth(2)
        )
        editBox.addView(ui.text("1 · Sample · edit · send", 14f, Theme.ACCENT, true))
        content.addView(editBox, ui.fullWidth(8))
        for (i in 0 until Cfg.TRACKS) buildCard(i)
        buildVoiceCard()
        buildEffects()
        buildSettings()
        buildLoops()
        fxBox.addView(ui.text("All effects of the selected track", 14f, Theme.ACCENT, true))
        content.addView(fxBox, ui.fullWidth(10))
    }

    /** MainActivity puts the editor and the effects chain into this page once all pages exist. */
    fun attachEmbedded(edit: EditScreen, fx: FxScreen) {
        editScreen = edit
        fxScreen = fx
        editBox.addView(edit.detach(), ui.fullWidth(4))
        fxBox.addView(fx.detach(), ui.fullWidth(4))
    }

    fun scrollToEditor() {
        root.post { root.smoothScrollTo(0, editBox.top) }
    }

    fun scrollToEffects() {
        root.post { root.smoothScrollTo(0, fxBox.top) }
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

    // ---------------- loops and slices ----------------

    private fun buildLoops() {
        val p = ui.panel()
        p.addView(ui.text("Loop (LOOP mode)", 14f, Theme.ACCENT, true))
        val r1 = ui.row()
        fwdBtn = ui.button("Forward") {
            sel().pingPong = false
            refresh()
        }
        pingBtn = ui.button("Ping-pong") {
            sel().pingPong = true
            refresh()
        }
        r1.addView(fwdBtn, ui.w(1f, 3))
        r1.addView(pingBtn, ui.w(1f, 3))
        r1.addView(ui.text("Reverse (above) = loop backwards", 11f, Theme.DIM), ui.w(1.3f, 4))
        p.addView(r1)
        loopAS = ui.slider("Loop start", 100, 0, { "$it%" }) { v ->
            val t = sel()
            t.loopA = (v / 100f).coerceAtMost(t.loopB - 0.01f)
        }
        loopBS = ui.slider("Loop end", 100, 100, { "$it%" }) { v ->
            val t = sel()
            t.loopB = (v / 100f).coerceAtLeast(t.loopA + 0.01f)
        }
        xfadeS = ui.slider("Crossfade (no click at the loop point)", 100, 0, { if (it == 0) "off" else "$it ms" }) { v ->
            sel().xfadeMs = v.toFloat()
        }
        p.addView(loopAS.view)
        p.addView(loopBS.view)
        p.addView(xfadeS.view)
        p.addView(ui.text("Fit the loop to bars of the tempo", 12f, Theme.DIM), ui.fullWidth(6))
        val r2 = ui.row()
        for (k in FITS.indices) {
            val b = ui.button(if (FITS[k] == 0) "Off" else "${FITS[k]} bar" + (if (FITS[k] > 1) "s" else "")) {
                sel().fitBars = FITS[k]
                refresh()
            }
            fitBtns.add(b)
            r2.addView(b, ui.w(1f, 2))
        }
        p.addView(r2)
        keepPitchBtn = ui.button("Keep pitch (time stretch)") {
            val t = sel()
            t.keepPitch = !t.keepPitch
            refresh()
        }
        p.addView(keepPitchBtn, ui.fullWidth(4))
        content.addView(p, ui.fullWidth(10))

        val sp = ui.panel()
        sp.addView(ui.text("Slices (STEP mode): chop start..end into equal parts, one per step", 14f, Theme.ACCENT, true))
        val r3 = ui.row()
        for (k in SLICES.indices) {
            val b = ui.button(if (SLICES[k] == 0) "Off" else "${SLICES[k]}") {
                val t = sel()
                t.slices = SLICES[k]
                if (t.slices > 0) {
                    t.loop = false
                    slicePattern(0)
                }
                refresh()
            }
            sliceBtns.add(b)
            r3.addView(b, ui.w(1f, 2))
        }
        sp.addView(r3)
        val r4 = ui.row()
        r4.addView(ui.button("In order") { slicePattern(0) }, ui.w(1f, 2))
        r4.addView(ui.button("Shuffle") { slicePattern(1) }, ui.w(1f, 2))
        r4.addView(ui.button("Reverse some") { slicePattern(2) }, ui.w(1f, 2))
        r4.addView(ui.button("Stutter") { slicePattern(3) }, ui.w(1f, 2))
        sp.addView(r4, ui.fullWidth(4))
        sliceMap = ui.text("", 12f, Theme.DIM)
        sliceMap.typeface = android.graphics.Typeface.MONOSPACE
        sp.addView(sliceMap, ui.fullWidth(4))
        content.addView(sp, ui.fullWidth(10))
    }

    /** 0 in order, 1 shuffle, 2 reverse some, 3 stutter. All 16 steps play, so the slices make a beat. */
    private fun slicePattern(kind: Int) {
        val t = sel()
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
            when (kind) {
                1 -> {
                    t.sliceOf[s] = rnd.nextInt(n)
                    t.sliceRev[s] = false
                }
                2 -> {
                    t.sliceOf[s] = s % n
                    t.sliceRev[s] = rnd.nextInt(4) == 0
                }
                3 -> {
                    // the first half in order, then the same slice repeating faster and faster
                    t.sliceOf[s] = if (s < 8) s % n else if (s < 12) (8 % n) else (12 % n)
                    t.sliceRev[s] = false
                    if (s >= 12) t.steps[s].level = 0.7f + 0.1f * (s - 12)
                }
                else -> {
                    t.sliceOf[s] = s % n
                    t.sliceRev[s] = false
                }
            }
        }
        refresh()
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
        r2.addView(ui.button("All 10 effects ↓") { scrollToEffects() }, ui.w(1f, 3))
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
        fxScreen?.onShow()
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
        ui.styleButton(fwdBtn, !t.pingPong)
        ui.styleButton(pingBtn, t.pingPong)
        loopAS.set((t.loopA * 100).toInt())
        loopBS.set((t.loopB * 100).toInt())
        xfadeS.set(t.xfadeMs.toInt())
        for (k in fitBtns.indices) ui.styleButton(fitBtns[k], t.fitBars == FITS[k])
        ui.styleButton(keepPitchBtn, t.keepPitch, Theme.GREEN)
        for (k in sliceBtns.indices) ui.styleButton(sliceBtns[k], t.slices == SLICES[k])
        sliceMap.text = if (t.slices == 0) "Slices off" else
            "Step:  " + (1..16).joinToString(" ") { String.format("%2d", it) } + "\nSlice: " +
                (0 until Cfg.STEPS).joinToString(" ") { s ->
                    val k = (if (t.sliceOf[s] >= 0) t.sliceOf[s] else s) % t.slices
                    String.format("%2d", k + 1).let { if (t.sliceRev[s]) it.trim() + "r" else it }
                }
        fxScreen?.onShow()
        modeHint.text = if (t.loop) {
            "LOOP: the sample restarts at every bar and repeats. The steps are ignored."
        } else {
            "STEP: the sample is played by the steps on the Steps tab."
        }
    }

    override fun onTick() {
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
