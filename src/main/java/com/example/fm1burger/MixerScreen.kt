package com.example.fm1burger

import android.content.Context
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

class MixerScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    private val e = a.engine
    override val root = ScrollView(a)
    private val content = ui.col()
    private val am = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val faders = ArrayList<FaderView>()
    private val pans = ArrayList<SliderRow>()
    private val muteBtns = ArrayList<Button>()
    private lateinit var padFader: FaderView
    private lateinit var bassFader: FaderView
    private lateinit var synthFader: FaderView
    private lateinit var drumMuteBtn: Button
    private lateinit var bassMuteBtn: Button
    private lateinit var synthMuteBtn: Button
    private lateinit var voiceFader: FaderView
    private lateinit var voiceMuteBtn: Button
    private lateinit var songFader: FaderView
    private lateinit var songMuteBtn: Button
    private lateinit var mixRecBtn: Button
    private lateinit var mixInfo: TextView
    private lateinit var masterFader: FaderView
    private lateinit var outSpinner: Spinner
    private var outDevs: List<AudioDeviceInfo> = emptyList()
    private var lastOut = 0

    init {
        content.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(88))
        root.addView(content)

        val op = ui.panel()
        op.addView(ui.text("Output", 14f, Theme.ACCENT, true))
        outSpinner = ui.spinner(listOf("Default")) { idx ->
            if (idx != lastOut) {
                lastOut = idx
                e.setOutput(outDevs.getOrNull(idx - 1))
            }
        }
        op.addView(outSpinner)
        op.addView(
            ui.text(
                "Choose the tablet speaker, or the FM-1 USB audio if you want to hear everything through the FM-1.",
                12f, Theme.DIM
            )
        )
        op.addView(ui.button("Rescan outputs") { scanOutputs() }, ui.fullWidth(4))
        content.addView(op, ui.fullWidth(0))

        val mp = ui.panel()
        mp.addView(ui.text("Record the whole mix", 14f, Theme.ACCENT, true))
        mp.addView(
            ui.text(
                "Records everything you hear (tracks, drums, bass, synth, voice) live, with no length limit. " +
                    "The ● REC MIX button at the top does the same from any page.",
                12f, Theme.DIM
            )
        )
        val mr = ui.row()
        mixRecBtn = ui.button("● Record mix") { a.toggleMixRec() }
        mr.addView(mixRecBtn, ui.w(1f, 3))
        mr.addView(ui.button("Last mix...") { a.mixDialog() }, ui.w(1f, 3))
        mp.addView(mr, ui.fullWidth(4))
        mixInfo = ui.text("", 12f, Theme.DIM)
        mp.addView(mixInfo)
        content.addView(mp, ui.fullWidth(8))

        val row = ui.row()
        for (i in 0 until Cfg.TRACKS) {
            val col = ui.panel()
            col.addView(ui.text("Track ${i + 1}", 13f, Theme.TRACK_COLORS[i], true))
            val f = FaderView(a)
            f.color = Theme.TRACK_COLORS[i]
            f.onChange = { v -> e.tracks[i].volume = v }
            col.addView(f, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(240)))
            val pan = ui.slider("Pan", 200, 100, { panText(it) }) { v ->
                e.tracks[i].pan = (v - 100) / 100f
            }
            col.addView(pan.view)
            val m = ui.button("Mute") {
                val t = e.tracks[i]
                t.mute = !t.mute
                refreshMutes()
            }
            col.addView(m, ui.fullWidth(4))
            faders.add(f)
            pans.add(pan)
            muteBtns.add(m)
            row.addView(col, colLp())
        }

        val pcol = ui.panel()
        pcol.addView(ui.text("Drums", 13f, Theme.GREEN, true))
        padFader = FaderView(a)
        padFader.color = Theme.GREEN
        padFader.onChange = { v -> e.padVol = v }
        pcol.addView(padFader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(240)))
        pcol.addView(ui.text("All 8 drum pads together", 11f, Theme.DIM))
        drumMuteBtn = ui.button("Mute") {
            val anyOn = e.pads.any { !it.mute }
            for (p in e.pads) p.mute = anyOn
            refreshMutes()
        }
        pcol.addView(drumMuteBtn, ui.fullWidth(4))
        row.addView(pcol, colLp())

        val bcol = ui.panel()
        bcol.addView(ui.text("Bassline", 13f, Theme.ACCENT, true))
        bassFader = FaderView(a)
        bassFader.color = Theme.ACCENT
        bassFader.onChange = { v -> e.bass.volume = v }
        bcol.addView(bassFader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(240)))
        bcol.addView(ui.text("Bass synth (Synth page)", 11f, Theme.DIM))
        bassMuteBtn = ui.button("Mute") {
            e.bass.mute = !e.bass.mute
            refreshMutes()
        }
        bcol.addView(bassMuteBtn, ui.fullWidth(4))
        row.addView(bcol, colLp())

        val scol = ui.panel()
        scol.addView(ui.text("Analog synth", 13f, Theme.TEXT, true))
        synthFader = FaderView(a)
        synthFader.color = Theme.PURPLE
        synthFader.onChange = { v -> e.analog.volume = v }
        scol.addView(synthFader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(240)))
        scol.addView(ui.text("Keyboard synth (Synth page)", 11f, Theme.DIM))
        synthMuteBtn = ui.button("Mute") {
            e.analog.mute = !e.analog.mute
            refreshMutes()
        }
        scol.addView(synthMuteBtn, ui.fullWidth(4))
        row.addView(scol, colLp())

        val vcol = ui.panel()
        vcol.addView(ui.text("Voice", 13f, Theme.PINK, true))
        voiceFader = FaderView(a)
        voiceFader.color = Theme.PINK
        voiceFader.onChange = { v -> e.vocal.volume = v }
        vcol.addView(voiceFader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(240)))
        vcol.addView(ui.text("Live mic (Voice page)", 11f, Theme.DIM))
        voiceMuteBtn = ui.button("Mute") {
            e.vocal.mute = !e.vocal.mute
            refreshMutes()
        }
        vcol.addView(voiceMuteBtn, ui.fullWidth(4))
        row.addView(vcol, colLp())

        val gcol = ui.panel()
        gcol.addView(ui.text("Song rec", 13f, Theme.BLUE, true))
        songFader = FaderView(a)
        songFader.color = Theme.BLUE
        songFader.onChange = { v -> e.bank.clipVol = v }
        gcol.addView(songFader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(240)))
        gcol.addView(ui.text("Part recordings (Song page)", 11f, Theme.DIM))
        songMuteBtn = ui.button("Mute") {
            e.bank.clipMute = !e.bank.clipMute
            refreshMutes()
        }
        gcol.addView(songMuteBtn, ui.fullWidth(4))
        row.addView(gcol, colLp())

        val mcol = ui.panel()
        mcol.addView(ui.text("Master", 13f, Theme.TEXT, true))
        masterFader = FaderView(a)
        masterFader.color = Theme.TEXT
        masterFader.onChange = { v -> e.masterVol = v }
        mcol.addView(masterFader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(240)))
        row.addView(mcol, colLp())

        val hs = HorizontalScrollView(a)
        hs.isHorizontalScrollBarEnabled = true
        hs.addView(row)
        content.addView(hs, ui.fullWidth(10))
        scanOutputs()
    }

    /** Every mixer strip has the same width; the row scrolls sideways when there are more strips than fit. */
    private fun colLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ui.dp(118), LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2))
        return lp
    }

    private fun panText(v: Int): String = when {
        v == 100 -> "C"
        v < 100 -> "L${100 - v}"
        else -> "R${v - 100}"
    }

    private fun outLabel(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Tablet speaker"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB: ${d.productName}"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "3.5 mm headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth: ${d.productName}"
        else -> "Other: ${d.productName}"
    }

    private fun scanOutputs() {
        val allowed = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        )
        outDevs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in allowed }
        val items = listOf("Default (system choice)") + outDevs.map { outLabel(it) }
        lastOut = 0
        ui.fill(outSpinner, items)
        outSpinner.setSelection(0)
    }

    private fun refreshMutes() {
        for (i in 0 until Cfg.TRACKS) {
            ui.styleButton(muteBtns[i], e.tracks[i].mute, Theme.RED)
        }
        ui.styleButton(drumMuteBtn, e.pads.all { it.mute }, Theme.RED)
        ui.styleButton(bassMuteBtn, e.bass.mute, Theme.RED)
        ui.styleButton(synthMuteBtn, e.analog.mute, Theme.RED)
        ui.styleButton(voiceMuteBtn, e.vocal.mute, Theme.RED)
        ui.styleButton(songMuteBtn, e.bank.clipMute, Theme.RED)
    }

    override fun onShow() {
        for (i in 0 until Cfg.TRACKS) {
            faders[i].value = e.tracks[i].volume
            pans[i].set(((e.tracks[i].pan * 100).toInt() + 100).coerceIn(0, 200))
        }
        padFader.value = e.padVol
        bassFader.value = e.bass.volume
        synthFader.value = e.analog.volume
        voiceFader.value = e.vocal.volume
        songFader.value = e.bank.clipVol
        masterFader.value = e.masterVol
        refreshMutes()
    }

    override fun onTick() {
        for (i in 0 until Cfg.TRACKS) faders[i].meter = e.tracks[i].peak
        masterFader.meter = e.masterPeak
        val mr = e.mixRec
        if (mr.recording) {
            val s = mr.frames / Cfg.SR
            mixRecBtn.text = "■ Stop recording"
            ui.styleButton(mixRecBtn, true, Theme.RED)
            mixInfo.text = String.format(
                Locale.US, "Recording %d:%02d · %.1f MB", s / 60, s % 60, mr.bytesWritten / 1048576.0
            )
        } else {
            mixRecBtn.text = "● Record mix"
            ui.styleButton(mixRecBtn, false)
            mixInfo.text = "Not recording."
        }
    }
}
