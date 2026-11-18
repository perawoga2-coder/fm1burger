package com.example.fm1burger

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * Voice page: your voice from the microphone, live, with room effects.
 * Rooms: Normal, Bathroom, Hall, Mountain top. The voice goes into the mix like every other track.
 */
class VoiceScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    private val e = a.engine
    private val v = e.vocal
    override val root = ScrollView(a)
    private val content = ui.col()
    private lateinit var micBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var takeBtn: Button
    private lateinit var info: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var wave: WaveView
    private lateinit var eqView: EqView
    private lateinit var humBtn: Button
    private lateinit var playBtn: Button
    private lateinit var pauseBtn: Button
    private lateinit var stopBtn: Button
    private var lastState = -1
    private val roomBtns = ArrayList<Button>()
    private val refreshers = ArrayList<() -> Unit>()
    private var takeActive = false

    init {
        content.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(32))
        root.addView(content)
        content.addView(ui.title("Voice (5th track)"))
        content.addView(
            ui.text(
                "Sing or speak into the microphone and hear yourself with a room effect, mixed with everything else. " +
                    "USE HEADPHONES, otherwise the speaker sound goes back into the microphone and howls. " +
                    "Close other apps that use the microphone.",
                12f, Theme.DIM
            ), ui.fullWidth(4)
        )

        val r1 = ui.row()
        micBtn = ui.button("Mic: OFF") { toggleMic() }
        muteBtn = ui.button("Mute voice") {
            v.mute = !v.mute
            refreshButtons()
        }
        r1.addView(micBtn, ui.w(1f, 3))
        r1.addView(muteBtn, ui.w(1f, 3))
        content.addView(r1, ui.fullWidth(6))

        val tr = ui.row()
        playBtn = ui.button("▶ Play") {
            if (e.playing && e.paused) e.pause() else if (!e.playing) e.play()
        }
        pauseBtn = ui.button("|| Pause") {
            if (e.playing && !e.paused) e.pause()
        }
        stopBtn = ui.button("■ Stop") { e.stop() }
        tr.addView(playBtn, ui.w(1f, 3))
        tr.addView(pauseBtn, ui.w(1f, 3))
        tr.addView(stopBtn, ui.w(1f, 3))
        content.addView(tr, ui.fullWidth(4))

        content.addView(ui.text("Input level", 12f, Theme.DIM), ui.fullWidth(6))
        levelBar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal)
        levelBar.max = 100
        content.addView(levelBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(16)))

        wave = WaveView(a)
        wave.ch = v
        content.addView(wave, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(110)))

        content.addView(ui.text("Room", 14f, Theme.ACCENT, true), ui.fullWidth(10))
        val rr = ui.row()
        for (i in VocalChannel.PRESET_NAMES.indices) {
            val b = ui.button(VocalChannel.PRESET_NAMES[i]) {
                v.applyPreset(i)
                for (r in refreshers) r()
                refreshButtons()
            }
            roomBtns.add(b)
            rr.addView(b, ui.w(1f, 3))
        }
        content.addView(rr, ui.fullWidth(4))
        content.addView(
            ui.text(
                "Normal: a little room. Bathroom: short and bright. Hall: big and long. Mountain top: far away echoes. Dry: no effect.",
                12f, Theme.DIM
            ), ui.fullWidth(2)
        )

        val panel = ui.panel()
        panel.addView(ui.text("Voice sound", 14f, Theme.ACCENT, true))
        panel.addView(
            sliderLine(
                slider("Mic boost", { v.micGain }, { v.micGain = it }),
                slider("Volume", { v.volume }, { v.volume = it }),
                slider("Low cut", { v.lowCut }, { v.lowCut = it }),
                slider("Compress", { v.comp }, { v.comp = it })
            )
        )
        panel.addView(
            sliderLine(
                slider("Reverb", { v.wet }, { v.wet = it }),
                slider("Decay", { v.decay }, { v.decay = it }),
                slider("Brightness", { v.bright }, { v.bright = it })
            )
        )
        panel.addView(
            sliderLine(
                slider("Echo", { v.echo }, { v.echo = it }),
                slider("Echo time", { v.echoTime }, { v.echoTime = it }),
                slider("Echo repeats", { v.echoFb }, { v.echoFb = it })
            )
        )
        content.addView(panel, ui.fullWidth(8))

        val np = ui.panel()
        np.addView(ui.text("Noise cleaning", 14f, Theme.ACCENT, true))
        np.addView(
            ui.text(
                "Noise gate: raise it until the background noise disappears when you are quiet " +
                    "(yellow lines in the wave show the level). High cut removes hiss. Hum filter removes mains buzz.",
                12f, Theme.DIM
            )
        )
        val hr = ui.row()
        hr.addView(slider("Noise gate (0 = off)", { v.gate }, { v.gate = it }).view, ui.w(1f, 4))
        hr.addView(slider("High cut (0 = off)", { v.highCut }, { v.highCut = it }).view, ui.w(1f, 4))
        np.addView(hr)
        humBtn = ui.button("Hum filter: off") {
            v.hum = (v.hum + 1) % 3
            refreshButtons()
        }
        np.addView(humBtn, ui.fullWidth(4))
        content.addView(np, ui.fullWidth(8))

        val ep = ui.panel()
        ep.addView(ui.text("Equalizer (frequency graph)", 14f, Theme.ACCENT, true))
        ep.addView(
            ui.text(
                "Blue = your voice now. Orange = the equalizer. Drag a dot: left/right = frequency, up/down = louder/quieter. " +
                    "1 lows, 2 mud, 3 mid, 4 presence, 5 highs.",
                12f, Theme.DIM
            )
        )
        eqView = EqView(a)
        eqView.ch = v
        ep.addView(eqView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(200)))
        val pr = ui.row()
        pr.addView(ui.button("Flat") { eq(floatArrayOf(100f, 400f, 1500f, 4000f, 10000f), floatArrayOf(0f, 0f, 0f, 0f, 0f)) }, ui.w(1f, 3))
        pr.addView(ui.button("Clear voice") { eq(floatArrayOf(100f, 300f, 1500f, 4000f, 10000f), floatArrayOf(-3f, -3f, 0f, 3f, 2f)) }, ui.w(1f, 3))
        pr.addView(ui.button("Warm") { eq(floatArrayOf(100f, 300f, 1500f, 4000f, 10000f), floatArrayOf(3f, 2f, 0f, -1f, -2f)) }, ui.w(1f, 3))
        pr.addView(ui.button("Radio") { eq(floatArrayOf(200f, 500f, 1500f, 3000f, 6000f), floatArrayOf(-12f, -3f, 4f, 3f, -10f)) }, ui.w(1f, 3))
        ep.addView(pr, ui.fullWidth(4))
        content.addView(ep, ui.fullWidth(8))

        content.addView(ui.text("Record a voice take", 14f, Theme.ACCENT, true), ui.fullWidth(10))
        content.addView(
            ui.text(
                "Saves your dry voice (no room effect) as a sample in the Library. " +
                    "Then load it on a track with the L button. Turn the mic on first.",
                12f, Theme.DIM
            ), ui.fullWidth(2)
        )
        takeBtn = ui.button("● Record take") { toggleTake() }
        content.addView(takeBtn, ui.fullWidth(6))
        info = ui.text("", 13f, Theme.TEXT, true)
        content.addView(info, ui.fullWidth(4))
    }

    private fun slider(name: String, get: () -> Float, set: (Float) -> Unit): SliderRow {
        val row = ui.slider(name, 100, (get() * 100f).toInt(), { "$it%" }) { x -> set(x / 100f) }
        refreshers.add { row.set((get() * 100f).toInt()) }
        return row
    }

    private fun sliderLine(vararg rows: SliderRow): LinearLayout {
        val r = ui.row()
        for (x in rows) r.addView(x.view, ui.w(1f, 4))
        return r
    }

    private fun eq(f: FloatArray, g: FloatArray) {
        for (i in 0 until 5) {
            v.eqF[i] = f[i]
            v.eqG[i] = g[i]
        }
        eqView.invalidate()
    }

    private fun toggleMic() {
        if (v.on) {
            v.stop()
        } else {
            if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                ui.toast("Allow the microphone, then press Mic again.")
                return
            }
            if (a.recorder.running) {
                ui.toast("Finish the other recording first.")
                return
            }
            val err = v.start()
            if (err != null) ui.toast(err)
        }
        refreshButtons()
    }

    private fun toggleTake() {
        if (takeActive) {
            endTake()
        } else if (v.startTake()) {
            takeActive = true
        } else {
            ui.toast("Turn the microphone on first (Mic: ON).")
        }
        refreshButtons()
    }

    private fun endTake() {
        takeActive = false
        val d = v.stopTake()
        refreshButtons()
        if (d == null) {
            ui.toast("Too short, nothing saved.")
            return
        }
        var pk = 0f
        for (x in d) {
            val ax = abs(x)
            if (ax > pk) pk = ax
        }
        if (pk > 0.0001f) {
            val g = 0.9f / pk
            for (i in d.indices) d[i] = d[i] * g
        }
        Thread {
            val f = a.store.saveNew("Voice_take", d, Cfg.SR)
            a.runOnUiThread {
                a.libraryScreen.refresh()
                ui.toast("Saved ${f.name} in the Library. Load it on a track with the L button.")
            }
        }.start()
    }

    private fun refreshButtons() {
        micBtn.text = if (v.on) "Mic: ON" else "Mic: OFF"
        ui.styleButton(micBtn, v.on, Theme.GREEN)
        humBtn.text = when (v.hum) { 1 -> "Hum filter: 50 Hz"; 2 -> "Hum filter: 60 Hz"; else -> "Hum filter: off" }
        ui.styleButton(humBtn, v.hum > 0, Theme.GREEN)
        muteBtn.text = if (v.mute) "Unmute voice" else "Mute voice"
        ui.styleButton(muteBtn, v.mute, Theme.RED)
        for (i in roomBtns.indices) ui.styleButton(roomBtns[i], v.preset == i)
        takeBtn.text = if (takeActive) "■ Stop and save take" else "● Record take"
        ui.styleButton(takeBtn, takeActive, Theme.RED)
    }

    private fun refreshTransport() {
        val st = if (!e.playing) 0 else if (e.paused) 2 else 1
        if (st == lastState) return
        lastState = st
        ui.styleButton(playBtn, st == 1)
        ui.styleButton(pauseBtn, st == 2)
        ui.styleButton(stopBtn, false)
    }

    override fun onShow() {
        lastState = -1
        refreshTransport()
        for (r in refreshers) r()
        refreshButtons()
    }

    override fun onTick() {
        refreshTransport()
        levelBar.progress = (v.inLevel * 100f).toInt().coerceIn(0, 100)
        wave.invalidate()
        eqView.invalidate()
        if (takeActive && !v.taking) endTake()
        info.text = if (takeActive) {
            String.format(java.util.Locale.US, "Recording take: %.1f s", v.takeSeconds())
        } else if (v.on) {
            "Microphone is on. Speak or sing."
        } else {
            "Microphone is off."
        }
        info.visibility = View.VISIBLE
    }
}
