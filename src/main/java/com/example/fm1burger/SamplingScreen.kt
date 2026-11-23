package com.example.fm1burger

import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * SAMPLING (3.19): the five sampler tracks on one page, everything for the selected one in one place.
 *  - tracks 1..5: big numbers (lit = it holds a sound), tap one to work on it
 *  - one waveform: drag across it to keep only that part (start / end), tap ▶ to hear it
 *  - one REC button: the source beside it (mic, a USB instrument, the app's own mix) and "send to the FM-1 after"
 *  - the sound: load (from anywhere), loop, reverse, louder, clear, → MPC pad, → FM-1
 *  - the effects: volume, pan, pitch, low-pass, high-pass, drive, delay, reverb, room, mute; more on its own page
 * It replaces the old Sampler, Edit and Voice pages (one way to record, one waveform, one effects panel).
 *
 * 3.41 (laid out for the order you work in): the track numbers sit in the page bar after Maker Bank (trackBar);
 * REC comes first, then the waveform with big ▶ / ■, a small LOOP in steps of the bar and the frequency graph;
 * the sound has Load, Clear and Send to… (MPC pad or FM-1, with or without the effects); Steps, Loop & slices
 * and Louder went to the effects.
 */
class SamplingScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    private val e = a.engine
    override val root = ScrollView(a)
    private val content = ui.col()

    private val trackBtns = ArrayList<Button>()
    /** 3.41: the track numbers 1..5, shown in the page bar after Maker Bank (MainActivity puts it there) */
    val trackBar: LinearLayout = ui.row()
    private val graph = FilterGraphView(a)
    private val microBtns = ArrayList<Pair<Int, Button>>()
    private lateinit var microBtn: Button
    private val wave = WaveformView(a)
    private val nameText = ui.text("", 16f, Theme.TEXT, true)
    private val infoText = ui.text("", 12f, Theme.DIM)
    private lateinit var recBtn: Button
    private lateinit var recInfo: TextView
    private val srcBtns = ArrayList<Button>()
    private lateinit var fm1Box: CheckBox
    private lateinit var loopBtn: Button
    private lateinit var revBtn: Button
    private lateinit var echoBtn: Button
    private lateinit var muteBtn: Button
    private val roomBtns = ArrayList<Button>()
    private val sliders = ArrayList<Pair<SliderRow, () -> Int>>()
    private var lastData: FloatArray? = null
    private var lastSel = -1

    /** REC's source: 0 the mic, 1 a USB instrument (or line in), 2 the app's own mix (resample). */
    private var src = 0
    private var resampling = false
    private var resampleTrack = 0
    /** "Send to the FM-1 after": the take is sent once it lands on its track. */
    private var sendAfter = -1
    private var sendRef: FloatArray? = null
    private val room = IntArray(Cfg.TRACKS)
    /** Send to… takes the effects with it (as you hear it): remembered */
    private var bake: Boolean
        get() = prefs.getBoolean("bake", true)
        set(v) { prefs.edit().putBoolean("bake", v).apply() }
    // 🔁 THE APP (resample): its length (0 free, else 1 / 2 / 4 bars from the next bar) and whether the track it goes into is
    // muted meanwhile (so its old sound stays out of the new one)
    private val prefs = a.getSharedPreferences("sampling", 0)
    private var resBars = prefs.getInt("resBars", 1)
    private val resBarBtns = ArrayList<Button>()
    private lateinit var resBox: LinearLayout
    private lateinit var muteWhileBox: CheckBox
    private var mutedPrev: Boolean? = null
    /** The FM-1 tools (KIT, resample from the device, the device's own REC button): only while such a device is plugged in. */
    private val quick = QuickSamplePanel(a)

    private fun t(): Track = e.tracks[a.sel.coerceIn(0, Cfg.TRACKS - 1)]
    private fun col(): Int = Theme.TRACK_COLORS[a.sel.coerceIn(0, Cfg.TRACKS - 1)]

    init {
        content.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(88))
        root.addView(content)

        // ---- the tracks: 1 .. 5 (in the page bar, a little smaller)
        trackBar.gravity = Gravity.CENTER_VERTICAL
        trackBar.setPadding(ui.dp(6), 0, 0, 0)
        for (i in 0 until Cfg.TRACKS) {
            val b = ui.button("${i + 1}") { select(i) }
            b.textSize = 17f
            b.setPadding(0, 0, 0, 0)
            b.minWidth = 0
            b.minimumWidth = 0
            b.minHeight = 0
            b.minimumHeight = 0
            b.typeface = Ui.BOLD
            b.setOnLongClickListener {
                e.tracks[i].mute = !e.tracks[i].mute
                ui.toast("Track ${i + 1}: " + if (e.tracks[i].mute) "muted" else "on")
                refresh()
                true
            }
            trackBtns.add(b)
            trackBar.addView(b, LinearLayout.LayoutParams(ui.dp(44), ui.dp(38)).also { it.setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        }

        // ---- the waveform
        val wp = ui.panel()
        val nr = ui.row()
        nr.gravity = Gravity.CENTER_VERTICAL
        nr.addView(nameText, ui.w(1f, 0))
        val playB = ui.button("▶") { play() }
        val stopB = ui.button("■") { stopAll() }
        for (b in listOf(playB, stopB)) {
            b.textSize = 26f
            nr.addView(b, LinearLayout.LayoutParams(ui.dp(76), ui.dp(60)).also { it.setMargins(ui.dp(6), 0, 0, 0) })
        }
        wp.addView(nr)
        wp.addView(infoText)
        wave.editable = true
        wave.onSelect = { s0, s1 ->
            val tt = t()
            if (s1 - s0 > 0.01f) {
                tt.start = s0
                tt.end = s1
                wave.setSelection(0f, 0f)
                refresh()
                play()
            }
        }
        wp.addView(wave, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(150)).also { it.topMargin = ui.dp(6) })
        val trimRow = ui.row()
        addSlider(trimRow, "Start", { (t().start * 1000).toInt() }, 1000, { "${it / 10}.${it % 10}%" }) { v ->
            val tt = t(); tt.start = min(v / 1000f, tt.end - 0.005f); wave.setRegion(tt.start, tt.end)
        }
        addSlider(trimRow, "End", { (t().end * 1000).toInt() }, 1000, { "${it / 10}.${it % 10}%" }) { v ->
            val tt = t(); tt.end = max(v / 1000f, tt.start + 0.005f); wave.setRegion(tt.start, tt.end)
        }
        wp.addView(trimRow, ui.fullWidth(2))
        // the small loop: LOOP on / off, and its length in parts of a bar (at the tempo)
        val ml = ui.row()
        ml.gravity = Gravity.CENTER_VERTICAL
        microBtn = ui.button("LOOP") {
            val tt = t()
            tt.micro = if (tt.micro > 0) 0 else tt.microLast
            refresh(); play()
        }
        microBtn.textSize = 14f
        ml.addView(microBtn, ui.w(1.3f, 3))
        for (dv in intArrayOf(32, 16, 8, 4, 2, 1)) {
            val b = ui.button(if (dv == 1) "1" else "1/$dv") {
                val tt = t()
                tt.micro = dv
                tt.microLast = dv
                refresh(); play()
            }
            b.textSize = 13f
            b.isAllCaps = false
            b.minWidth = 0
            b.minimumWidth = 0
            b.setPadding(0, 0, 0, 0)
            microBtns.add(Pair(dv, b))
            ml.addView(b, ui.w(1f, 2))
        }
        wp.addView(ml, ui.fullWidth(8))
        // the frequency graph: low cut and high cut, drag them
        graph.onChange = { k, av, bv, on ->
            val fx = t().fx
            fx.a[k] = av
            fx.b[k] = bv
            fx.on[k] = on
            for ((sl, get) in sliders) sl.set(get())
        }
        graph.onRelease = { play() }
        wp.addView(graph, ui.fullWidth(8))
        wp.addView(ui.text("Drag across the waveform to keep only that part. Long press a track number: mute.", 11f, Theme.DIM))

        // ---- REC (first: the waveform comes after it)
        val rp = ui.panel()
        val rr = ui.row()
        rr.gravity = Gravity.CENTER_VERTICAL
        recBtn = ui.button("●  REC") { rec() }
        recBtn.textSize = 24f
        recBtn.typeface = Ui.BOLD
        rr.addView(recBtn, LinearLayout.LayoutParams(0, ui.dp(84), 1.4f).also { it.setMargins(0, 0, ui.dp(10), 0) })
        val sc = ui.col()
        val sr = ui.row()
        for ((k, n) in listOf("🎤 Mic", "🎛 Instrument", "🔁 The app").withIndex()) {
            val b = ui.button(n) {
                src = k
                refresh()
            }
            b.textSize = 13f
            srcBtns.add(b)
            sr.addView(b, ui.w(1f, 2))
        }
        sc.addView(sr)
        fm1Box = CheckBox(a)
        fm1Box.text = "Send to the FM-1 when it is recorded"
        fm1Box.setTextColor(Theme.TEXT)
        sc.addView(fm1Box)
        rr.addView(sc, ui.w(2f, 0))
        rp.addView(rr)
        resBox = ui.col()                                   // (shown for 🔁 The app)
        val rb = ui.row()
        rb.gravity = Gravity.CENTER_VERTICAL
        rb.addView(ui.text("Length", 12f, Theme.DIM), ui.w(0f, 3))
        for ((k, n) in listOf(0 to "Free", 1 to "1 bar", 2 to "2 bars", 4 to "4 bars")) {
            val b = ui.button(n) {
                resBars = k
                prefs.edit().putInt("resBars", k).apply()
                refresh()
            }
            b.textSize = 13f
            b.tag = k
            resBarBtns.add(b)
            rb.addView(b, ui.w(1f, 2))
        }
        resBox.addView(rb, ui.fullWidth(6))
        muteWhileBox = CheckBox(a)
        muteWhileBox.setTextColor(Theme.TEXT)
        muteWhileBox.isChecked = prefs.getBoolean("muteWhile", true)
        muteWhileBox.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("muteWhile", on).apply() }
        resBox.addView(muteWhileBox)
        rp.addView(resBox)
        recInfo = ui.text("", 12f, Theme.DIM)
        rp.addView(recInfo, ui.fullWidth(4))
        content.addView(rp, ui.fullWidth(10))
        content.addView(wp, ui.fullWidth(10))

        // ---- the sound
        val mp = ui.panel()
        mp.addView(ui.text("The sound", 14f, Theme.ACCENT, true))
        val m1 = ui.row()
        m1.addView(colored("Load…", Theme.ACCENT) { load() }, ui.w(1f, 3))
        m1.addView(colored("Clear", Theme.RED) { clear() }, ui.w(1f, 3))
        m1.addView(colored("Send to…", Theme.PURPLE) { sendTo() }, ui.w(1f, 3))
        mp.addView(m1, ui.fullWidth(6))
        content.addView(mp, ui.fullWidth(10))

        // ---- the effects
        val fp = ui.panel()
        val fh = ui.row()
        fh.gravity = Gravity.CENTER_VERTICAL
        fh.addView(ui.text("Effects", 14f, Theme.ACCENT, true), ui.w(1f, 0))
        muteBtn = ui.button("Mute") { t().mute = !t().mute; refresh() }
        fh.addView(muteBtn, ui.w(0f, 3))
        fh.addView(ui.button("More effects…") { TrackPage(a, a.sel, TrackPage.TAB_FX).open() }, ui.w(0f, 3))
        fp.addView(fh)
        val fm = ui.row()
        fm.addView(colored("Steps…", Theme.GREEN) { a.showTab(MainActivity.TAB_STEPS) }, ui.w(1f, 3))
        fm.addView(colored("Loop & slices…", Theme.GREEN) { TrackPage(a, a.sel, TrackPage.TAB_LOOP).open() }, ui.w(1.2f, 3))
        fm.addView(colored("Louder", Theme.BLUE) { louder() }, ui.w(1f, 3))
        fp.addView(fm, ui.fullWidth(6))
        val f0 = ui.row()
        loopBtn = colored("Loop", Theme.BLUE) { t().loop = !t().loop; refresh() }
        f0.addView(loopBtn, ui.w(1f, 3))
        revBtn = colored("Reverse", Theme.BLUE) { t().reverse = !t().reverse; refresh(); play() }
        f0.addView(revBtn, ui.w(1f, 3))
        echoBtn = colored("Echo", Theme.BLUE) { toggleEcho() }
        f0.addView(echoBtn, ui.w(1f, 3))
        fp.addView(f0, ui.fullWidth(6))
        val f1 = ui.row()
        addSlider(f1, "Volume", { (t().volume * 100).toInt() }, 100, { "$it%" }) { t().volume = it / 100f }
        addSlider(f1, "Pan", { ((t().pan + 1f) * 50).toInt() }, 100, { if (it == 50) "centre" else if (it < 50) "L ${50 - it}" else "R ${it - 50}" }) { t().pan = it / 50f - 1f }
        addSlider(f1, "Pitch", { t().pitch + 12 }, 24, { val p = it - 12; if (p > 0) "+$p" else "$p" }) { t().pitch = it - 12 }
        fp.addView(f1, ui.fullWidth(4))
        val f2 = ui.row()
        addSlider(f2, "Low-pass", { fxv(0, 100) }, 100, { if (it >= 99) "open" else "$it%" }) { setFx(0, it / 100f, it < 99) }
        addSlider(f2, "High-pass", { fxv(1, 0) }, 100, { if (it <= 1) "open" else "$it%" }) { setFx(1, it / 100f, it > 1) }
        addSlider(f2, "Drive", { fxv(4, 0) }, 100, { if (it == 0) "off" else "$it%" }) { setFx(4, it / 100f, it > 0) }
        fp.addView(f2, ui.fullWidth(4))
        val f3 = ui.row()
        addSlider(f3, "Delay", { fxv(3, 0) }, 100, { if (it == 0) "off" else "$it%" }) { setFx(3, it / 100f, it > 0) }
        addSlider(f3, "Reverb", { fxv(2, 0) }, 100, { if (it == 0) "off" else "$it%" }) { setFx(2, it / 100f, it > 0) }
        fp.addView(f3, ui.fullWidth(4))
        val f4 = ui.row()
        addSlider(f4, "Echo", { fxv(FxChain.ECHO, 0) }, 100, { if (it == 0) "off" else "$it%" }) { setFx(FxChain.ECHO, it / 100f, it > 0); refreshEcho() }
        addSlider(f4, "Echo time", { (t().fx.b[FxChain.ECHO] * 100).toInt() }, 100, { "${FxChain.echoMs(it / 100f).toInt()} ms" }) { t().fx.b[FxChain.ECHO] = it / 100f }
        fp.addView(f4, ui.fullWidth(4))
        fp.addView(ui.text("Room", 12f, Theme.DIM), ui.fullWidth(6))
        val rmr = ui.row()
        for ((k, n) in ROOMS.withIndex()) {
            val b = ui.button(n) { setRoom(k) }
            b.textSize = 13f
            roomBtns.add(b)
            rmr.addView(b, ui.w(1f, 2))
        }
        fp.addView(rmr, ui.fullWidth(2))
        content.addView(fp, ui.fullWidth(10))
        content.addView(quick.view, ui.fullWidth(10))
        select(a.sel.coerceIn(0, Cfg.TRACKS - 1))
    }

    companion object {
        val ROOMS = arrayOf("Dry", "Room", "Bathroom", "Hall", "Mountain top")
    }

    // ---------------- helpers ----------------

    private fun colored(label: String, c: Int, act: () -> Unit): Button {
        val b = ui.button(label) { act() }
        b.background = ui.touchBg(ui.bg(Theme.alpha(c, 46), 10, c, 1), 10)
        b.setTextColor(c)
        return b
    }

    private fun addSlider(parent: LinearLayout, name: String, get: () -> Int, maxV: Int, fmt: (Int) -> String, set: (Int) -> Unit) {
        val s = ui.slider(name, maxV, get(), fmt) { v -> set(v); graph.set(t().fx) }
        sliders.add(Pair(s, get))
        parent.addView(s.view, ui.w(1f, 4))
    }

    private fun fxv(k: Int, off: Int): Int = if (t().fx.on[k]) (t().fx.a[k] * 100).toInt() else off

    private fun setFx(k: Int, v: Float, on: Boolean) {
        t().fx.a[k] = v
        t().fx.on[k] = on
    }

    /** The Echo button: on at a ready-to-use setting, or off. */
    private fun toggleEcho() {
        val fx = t().fx
        val k = FxChain.ECHO
        if (fx.on[k]) fx.on[k] = false
        else {
            if (fx.a[k] < 0.05f) fx.a[k] = FxChain.DEF_A[k]
            fx.on[k] = true
        }
        refresh()
        play()
    }

    private fun refreshEcho() { ui.styleButton(echoBtn, t().fx.on[FxChain.ECHO], Theme.BLUE) }

    private fun setRoom(k: Int) {
        val fx = t().fx
        val was = room[a.sel]
        room[a.sel] = k
        when (k) {
            0 -> { fx.on[2] = false }
            1 -> { fx.on[2] = true; fx.a[2] = 0.22f; fx.b[2] = 0.3f }
            2 -> { fx.on[2] = true; fx.a[2] = 0.38f; fx.b[2] = 0.45f }
            3 -> { fx.on[2] = true; fx.a[2] = 0.42f; fx.b[2] = 0.85f }
            else -> { fx.on[2] = true; fx.a[2] = 0.25f; fx.b[2] = 1f; fx.on[3] = true; fx.a[3] = 0.4f; fx.b[3] = 0.75f }
        }
        if (k != 4 && was == 4) fx.on[3] = false
        refresh()
        play()
    }

    private fun select(i: Int) {
        a.sel = i
        refresh()
    }

    private fun play() {
        if (t().data == null) ui.toast("Track ${a.sel + 1} is empty: record or load a sound") else e.audition(a.sel)
    }

    // ---------------- REC ----------------

    private fun rec() {
        val ti = a.sel
        if (resampling) {
            stopResample()
            return
        }
        if (a.recorder.running) {
            a.recorder.stop()
            return
        }
        sendAfter = if (fm1Box.isChecked) ti else -1
        sendRef = t().data
        when (src) {
            0 -> a.flow.onRecordButton(ti)
            1 -> a.flow.onSampleButton(ti)
            else -> {
                if (resBars > 0 && !e.playing) {
                    ui.toast("Press ▶ first: REC starts on the next bar and stops after $resBars bar" + if (resBars > 1) "s" else "")
                    return
                }
                if (!e.playing) ui.toast("Press ▶ to play something: REC catches the app's own sound")
                resampleTrack = ti
                if (muteWhileBox.isChecked) {
                    mutedPrev = e.tracks[ti].mute
                    e.tracks[ti].mute = true
                }
                if (resBars > 0) e.grabArmBars(resBars) else e.grabStart(30.0)
                resampling = true
            }
        }
        refresh()
    }

    private fun stopResample() {
        resampling = false
        val full = e.grabFull()                            // (bars: caught to the end = a loop that fits)
        val d = e.grabStop()
        mutedPrev?.let { e.tracks[resampleTrack].mute = it }
        mutedPrev = null
        val loopIt = resBars > 0 && d != null && full
        refresh()
        if (d == null) {
            ui.toast("Nothing was caught")
            return
        }
        val ti = resampleTrack
        Thread {
            val f = try {
                a.store.saveNew("Resample_${ti + 1}", d, Cfg.SR)
            } catch (ex: Exception) {
                null
            }
            a.runOnUiThread {
                if (f == null) ui.toast("Could not save it") else {
                    val tt = e.tracks[ti]
                    tt.start = 0f; tt.end = 1f; tt.reverse = false; tt.boostDb = 0f
                    if (loopIt) tt.loop = true                  // (bars: a loop that fits the pattern)
                    a.assignTrack(ti, f)
                    a.libraryScreen.refresh()
                    ui.toast("Track ${ti + 1}: ${f.nameWithoutExtension} (also in the Maker Bank)")
                }
            }
        }.start()
    }

    // ---------------- the sound ----------------

    private fun load() {
        val ti = a.sel
        a.pickSound("Load into track ${ti + 1}", "(empty this track)") { f -> a.assignTrack(ti, f) }
    }

    /** Louder: the part kept, up to full level (the track's boost, 0 .. +12 dB). */
    private fun louder() {
        val tt = t()
        val d = tt.data ?: return ui.toast("Track ${a.sel + 1} is empty")
        val i0 = (tt.start * d.size).toInt().coerceIn(0, d.size - 1)
        val i1 = (tt.end * d.size).toInt().coerceIn(i0 + 1, d.size)
        var pk = 0f
        for (i in i0 until i1) pk = max(pk, abs(d[i]))
        if (pk < 1e-5f) return ui.toast("It is silent")
        val db = (-20.0 * log10(pk.toDouble() / 0.98)).toFloat().coerceIn(0f, 12f)
        tt.boostDb = db
        ui.toast(if (db < 0.2f) "It is already at full level" else String.format(java.util.Locale.US, "+%.1f dB", db))
        play()
    }

    private fun clear() {
        val ti = a.sel
        if (t().data == null) return
        AlertDialog.Builder(a)
            .setTitle("Clear track ${ti + 1}?")
            .setMessage("Its sound leaves the track (it stays in the Maker Bank), the effects stay.")
            .setPositiveButton("Clear") { _, _ ->
                a.assignTrack(ti, null)
                val tt = e.tracks[ti]
                tt.start = 0f; tt.end = 1f; tt.reverse = false; tt.boostDb = 0f
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * The track's sound as you hear it from one note: the part kept, reverse, pitch, the boost, and (bake) the
     * effects with a tail for reverb / delay. Mono 44.1 kHz.
     */
    private fun render(bake: Boolean): FloatArray? {
        val tt = t()
        val d = tt.data ?: return null
        val i0 = (tt.start * d.size).toInt().coerceIn(0, d.size - 1)
        val i1 = (tt.end * d.size).toInt().coerceIn(i0 + 1, d.size)
        var x = d.copyOfRange(i0, i1)
        if (tt.reverse) x.reverse()
        if (tt.pitch != 0) {
            val r = 2.0.pow(tt.pitch / 12.0)
            val n = max(1, (x.size / r).toInt())
            val y = FloatArray(n)
            for (k in 0 until n) {
                val p = k * r
                val j = p.toInt()
                val fr = (p - j).toFloat()
                val v0 = x[min(j, x.size - 1)]
                val v1 = x[min(j + 1, x.size - 1)]
                y[k] = v0 + (v1 - v0) * fr
            }
            x = y
        }
        // 3.41: the small loop, as it plays: its first part repeated for as long as the part kept lasts
        if (tt.micro > 0) {
            val len = (Pat.barFrames(e.bpmF) / tt.micro).toInt().coerceAtLeast(64)
            if (len < x.size) {
                val y = FloatArray(x.size)
                val fade = min(48, len / 4)
                for (k in y.indices) {
                    val j = k % len
                    val w = when {
                        j < fade && k >= len -> j / fade.toFloat()          // (no fade-in on the very first one)
                        j >= len - fade -> (len - 1 - j) / fade.toFloat()
                        else -> 1f
                    }
                    y[k] = x[j] * w
                }
                x = y
            }
        }
        val g = 10f.pow(tt.boostDb / 20f)
        if (g != 1f) for (k in x.indices) x[k] *= g
        if (bake) {
            val src = tt.fx
            val fx = FxChain()
            for (k in 0 until FxChain.N) {
                fx.on[k] = src.on[k]
                fx.a[k] = src.a[k]
                fx.b[k] = src.b[k]
            }
            fx.order = src.order.copyOf()
            fx.intensity = src.intensity
            val tail = if (src.on[2] || src.on[3] || src.on[FxChain.ECHO]) Cfg.SR * 3 / 2 else 0
            val out = FloatArray(x.size + tail)
            val bpm = e.bpmF
            var beat = 0.0
            val inc = bpm / 60.0 / Cfg.SR
            for (k in out.indices) {
                out[k] = fx.process(if (k < x.size) x[k] else 0f, bpm, beat)
                beat += inc
            }
            x = out
        }
        var pk = 0f
        for (v in x) pk = max(pk, abs(v))
        if (pk > 0.98f) for (k in x.indices) x[k] *= 0.98f / pk
        return x
    }

    private fun baseName(): String {
        val n = t().sampleName.removeSuffix(".wav")
        return if (n.isEmpty()) "Track${a.sel + 1}" else n
    }

    private fun toMpc() {
        if (t().data == null) return ui.toast("Track ${a.sel + 1} is empty")
        val names = Array(Cfg.PADS) { q -> "Pad ${Cfg.padName(q)}   " + e.pads[q].name.removeSuffix(".wav").ifEmpty { "(empty)" } }
        val bake = this.bake
        AlertDialog.Builder(a)
            .setTitle("Send track ${a.sel + 1} to which MPC pad?")
            .setItems(names) { _, q ->
                Thread {
                    val f = try {
                        val d = render(bake) ?: throw IllegalStateException()
                        a.store.saveNew("MPC_" + baseName(), d, Cfg.SR)
                    } catch (ex: Exception) {
                        null
                    }
                    a.runOnUiThread {
                        if (f == null) ui.toast("Could not make the sound") else {
                            a.assignPad(q, f)
                            a.libraryScreen.refresh()
                            ui.toast("Pad ${Cfg.padName(q)}: ${f.nameWithoutExtension}")
                        }
                    }
                }.start()
            }
            .show()
    }

    /** 3.41: one Send to… for the MPC pads and the FM-1, with or without the effects (remembered) */
    private fun sendTo() {
        if (t().data == null) return ui.toast("Track ${a.sel + 1} is empty")
        val box = ui.col()
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(4))
        val cb = CheckBox(a)
        cb.text = "With the effects (as you hear it)"
        cb.setTextColor(Theme.TEXT)
        cb.isChecked = bake
        cb.setOnCheckedChangeListener { _, on -> bake = on }
        box.addView(cb)
        var dlg: AlertDialog? = null
        val row = ui.row()
        val mpcB = colored("MPC pad", Theme.ACCENT) { dlg?.dismiss(); toMpc() }
        val fmB = colored("FM-1", Theme.PURPLE) { dlg?.dismiss(); toFm1() }
        mpcB.textSize = 18f
        fmB.textSize = 18f
        row.addView(mpcB, LinearLayout.LayoutParams(0, ui.dp(64), 1f).also { it.setMargins(0, 0, ui.dp(6), 0) })
        row.addView(fmB, LinearLayout.LayoutParams(0, ui.dp(64), 1f).also { it.setMargins(ui.dp(6), 0, 0, 0) })
        box.addView(row, ui.fullWidth(10))
        dlg = AlertDialog.Builder(a)
            .setTitle("Send track ${a.sel + 1} to")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** ■: the preview and this track's sound (a LOOP keeps going until stopped) */
    private fun stopAll() {
        e.previewStop()
        t().playing = false
    }

    private fun toFm1() {
        val d = render(bake) ?: return ui.toast("Track ${a.sel + 1} is empty")
        a.sendToFm1(baseName(), d)
    }

    // ---------------- the page ----------------

    fun refresh() {
        val sel = a.sel.coerceIn(0, Cfg.TRACKS - 1)
        for ((i, b) in trackBtns.withIndex()) {
            val tt = e.tracks[i]
            val c = Theme.TRACK_COLORS[i]
            when {
                tt.mute -> {
                    b.background = ui.touchBg(ui.bg(if (i == sel) Theme.alpha(c, 120) else Theme.PANEL, 14, Theme.RED, 3), 14)
                    b.setTextColor(Theme.RED)
                }
                i == sel -> ui.styleButton(b, true, c, 14)
                tt.data != null -> {
                    b.background = ui.touchBg(ui.bg(Theme.alpha(c, 70), 14, c, 2), 14)
                    b.setTextColor(Theme.TEXT)
                }
                else -> {
                    b.background = ui.touchBg(ui.bg(Theme.PANEL, 14, Theme.LINE, 1), 14)
                    b.setTextColor(Theme.FAINT)
                }
            }
        }
        val tt = t()
        val c = col()
        nameText.text = "Track ${sel + 1} · " + tt.sampleName.removeSuffix(".wav").ifEmpty { "empty" }
        nameText.setTextColor(c)
        val d = tt.data
        infoText.text = if (d == null) "Press REC, or Load… a sound from anywhere." else String.format(
            java.util.Locale.US, "%.2f s, playing %.2f s%s%s", d.size / Cfg.SR.toDouble(), d.size * (tt.end - tt.start) / Cfg.SR.toDouble(),
            (if (tt.loop) " · loop" else "") + (if (tt.micro > 0) " · LOOP 1/${tt.micro}" else ""), if (tt.boostDb > 0.1f) String.format(java.util.Locale.US, " · +%.1f dB", tt.boostDb) else "")
        if (d !== lastData || sel != lastSel) {
            wave.setData(d)
            lastData = d
            lastSel = sel
        }
        wave.color = c
        wave.setRegion(tt.start, tt.end)
        for ((s, get) in sliders) s.set(get())
        ui.styleButton(microBtn, tt.micro > 0, c)
        for ((dv, b) in microBtns) ui.styleButton(b, tt.micro == dv, c)
        graph.color = c
        graph.set(tt.fx)
        ui.styleButton(loopBtn, tt.loop, Theme.BLUE)
        ui.styleButton(revBtn, tt.reverse, Theme.BLUE)
        refreshEcho()
        ui.styleButton(muteBtn, tt.mute, Theme.RED)
        for ((k, b) in roomBtns.withIndex()) ui.styleButton(b, room[sel] == k && (k > 0 || !tt.fx.on[2]), c)
        for ((k, b) in srcBtns.withIndex()) ui.styleButton(b, k == src, Theme.RED)
        resBox.visibility = if (src == 2) View.VISIBLE else View.GONE
        for (b in resBarBtns) ui.styleButton(b, b.tag == resBars, Theme.RED)
        muteWhileBox.text = "Mute track ${sel + 1} while it records (its old sound stays out)"
        paintRec()
    }

    private fun paintRec() {
        val running = a.recorder.running || resampling
        recBtn.text = if (running) "■  STOP" else "●  REC"
        recBtn.background = ui.touchBg(ui.bg(if (running) Theme.RED else Theme.alpha(Theme.RED, 60), 42, Theme.RED, 3), 42)
        recBtn.setTextColor(if (running) Theme.onColor(Theme.RED) else Theme.RED)
        recInfo.text = when {
            resampling && e.grabWaiting() -> "Waiting for the next bar… (track ${resampleTrack + 1}, $resBars bar${if (resBars > 1) "s" else ""})"
            resampling -> String.format(java.util.Locale.US, "Catching the app's sound into track %d: %.1f s%s",
                resampleTrack + 1, e.grabPos / Cfg.SR.toDouble(),
                if (resBars > 0) " (it stops by itself after $resBars bar${if (resBars > 1) "s" else ""})" else " (max 30 s). STOP to keep it.")
            a.recorder.running -> a.flow.statusLine()
            else -> "Records into track ${a.sel + 1} from " + arrayOf("the microphone", "the instrument on USB / line in",
                "the app itself (what plays now)")[src] + ". Every take is also kept in the Maker Bank."
        }
    }

    override fun onShow() {
        refresh()
    }

    override fun onTick() {
        quick.tick()
        if (resampling && e.grabFull()) stopResample()
        if (resampling || a.recorder.running) paintRec()
        val tt = t()
        if (tt.data !== lastData) refresh()
        // "send to the FM-1 after": once the take has landed on its track
        val sa = sendAfter
        if (sa >= 0 && !a.recorder.running && !resampling) {
            val d = e.tracks[sa].data
            if (d != null && d !== sendRef) {
                sendAfter = -1
                a.sel = sa
                refresh()
                toFm1()
            }
        }
        if (!a.recorder.running && !resampling && recBtn.text.startsWith("■")) paintRec()
    }
}
