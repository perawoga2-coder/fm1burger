package com.example.fm1burger

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner

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
    private lateinit var masterFader: FaderView
    private lateinit var outSpinner: Spinner
    private var outDevs: List<AudioDeviceInfo> = emptyList()
    private var lastOut = 0

    init {
        content.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(24))
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

        val row = ui.row()
        row.weightSum = 6f
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
            row.addView(col, ui.w(1f, 2))
        }

        val pcol = ui.panel()
        pcol.addView(ui.text("Pads", 13f, Theme.TEXT, true))
        padFader = FaderView(a)
        padFader.color = Theme.GREEN
        padFader.onChange = { v -> e.padVol = v }
        pcol.addView(padFader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(240)))
        row.addView(pcol, ui.w(1f, 2))

        val mcol = ui.panel()
        mcol.addView(ui.text("Master", 13f, Theme.TEXT, true))
        masterFader = FaderView(a)
        masterFader.color = Theme.TEXT
        masterFader.onChange = { v -> e.masterVol = v }
        mcol.addView(masterFader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(240)))
        row.addView(mcol, ui.w(1f, 2))

        content.addView(row, ui.fullWidth(10))
        scanOutputs()
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
    }

    override fun onShow() {
        for (i in 0 until Cfg.TRACKS) {
            faders[i].value = e.tracks[i].volume
            pans[i].set(((e.tracks[i].pan * 100).toInt() + 100).coerceIn(0, 200))
        }
        padFader.value = e.padVol
        masterFader.value = e.masterVol
        refreshMutes()
    }

    override fun onTick() {
        for (i in 0 until Cfg.TRACKS) faders[i].meter = e.tracks[i].peak
        masterFader.meter = e.masterPeak
    }
}
