package com.example.fm1burger

import android.app.AlertDialog
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper
import android.widget.CheckBox
import org.json.JSONObject

/**
 * Plug and play: when an instrument is plugged in (USB, or Bluetooth MIDI that Android connected) the app
 * connects it by itself. A device it has seen before gets what was chosen for it last time; a new one gets
 * one short question (follow its tempo, lead it, or notes only). Any instrument works the same way; what a
 * device can do more (a sample slot it takes samples into) is found out by asking it, quietly.
 */
class DeviceHub(private val a: MainActivity) {

    companion object {
        const val SYNC_OFF = 0
        const val SYNC_LEAD = 1
        const val SYNC_FOLLOW = 2
    }

    private val prefs = a.getSharedPreferences("devices", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val mm = a.getSystemService(Context.MIDI_SERVICE) as MidiManager
    private val am = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val asking = HashSet<Int>()

    /** The device the app set to follow its clock (CLK USB), or null. */
    @Volatile var synced: String? = null
        private set

    /** The connected device that takes samples into its own slots (answers the sample protocol), or null. */
    @Volatile var slotDevice: String? = null
        private set

    private val midiCb = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            main.postDelayed({ plugged(info) }, 400)      // let Android finish setting it up
        }

        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            main.post { unplugged(info) }
        }
    }

    private val audioCb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            for (d in added) {
                if (!usb(d)) continue
                val name = d.productName?.toString() ?: "USB audio"
                if (d.isSink && remembered(name)?.optBoolean("out", false) == true) {
                    a.engine.setOutput(d)
                    a.ui.toast("$name: the app plays through it")
                } else if (d.isSource) {
                    a.ui.toast("$name: ready to sample (S on a sampler track)")
                }
            }
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            for (d in removed) if (usb(d) && d.isSink) a.engine.setOutput(null)
        }
    }

    private fun usb(d: AudioDeviceInfo) =
        d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET

    fun start() {
        try {
            mm.registerDeviceCallback(midiCb, main)
        } catch (ex: Exception) {
        }
        try {
            am.registerAudioDeviceCallback(audioCb, main)
        } catch (ex: Exception) {
        }
        // what was plugged in before the app started
        main.postDelayed({ for (d in a.midi.devices()) plugged(d) }, 1500)
    }

    fun stop() {
        try {
            mm.unregisterDeviceCallback(midiCb)
        } catch (ex: Exception) {
        }
        try {
            am.unregisterAudioDeviceCallback(audioCb)
        } catch (ex: Exception) {
        }
    }

    private fun remembered(name: String): JSONObject? = try {
        prefs.getString(name, null)?.let { JSONObject(it) }
    } catch (ex: Exception) {
        null
    }

    private fun remember(name: String, sync: Int, out: Boolean) {
        val o = JSONObject()
        o.put("sync", sync)
        o.put("out", out)
        prefs.edit().putString(name, o.toString()).apply()
    }

    /** Forget every device (they ask again when plugged in). */
    fun forgetAll() {
        prefs.edit().clear().apply()
    }

    fun rememberedNames(): List<String> = prefs.all.keys.sorted()

    private fun plugged(info: MidiDeviceInfo) {
        if (info.type == MidiDeviceInfo.TYPE_VIRTUAL) return     // other apps' virtual ports: only by hand (MIDI sync)
        if (a.midi.isOpen(info.id) || !asking.add(info.id)) return
        val name = a.midi.nameOf(info)
        a.midi.open(info) { ok ->
            asking.remove(info.id)
            if (!ok) {
                a.ui.toast("$name: could not connect")
                return@open
            }
            val r = remembered(name)
            if (r != null) {
                val sync = r.optInt("sync", SYNC_OFF)
                a.setSyncMode(sync)
                a.ui.toast("$name connected · " + when (sync) {
                    SYNC_FOLLOW -> "follows its tempo"
                    SYNC_LEAD -> "the app leads the tempo"
                    else -> "notes and pads"
                })
            } else {
                ask(name)
            }
            probe(name)
        }
    }

    private fun unplugged(info: MidiDeviceInfo) {
        val name = a.midi.nameOf(info)
        if (!a.midi.isOpen(info.id)) return
        a.midi.closeId(info.id)
        if (slotDevice == name) slotDevice = null
        if (synced == name) synced = null
        if (!a.midi.connected) a.setSyncMode(SYNC_OFF)
        a.ui.toast("$name disconnected")
        a.refreshCurrent()
    }

    /** A new device: one question, remembered. */
    private fun ask(name: String) {
        val box = a.ui.col()
        box.setPadding(a.ui.dp(20), a.ui.dp(8), a.ui.dp(20), 0)
        val keep = CheckBox(a)
        keep.text = "Remember for $name"
        keep.isChecked = true
        val out = CheckBox(a)
        out.text = "Play the app through it (when it has USB audio)"
        out.isChecked = false
        box.addView(a.ui.text("Its notes play the pads either way.", 12f, Theme.DIM))
        box.addView(out)
        box.addView(keep)
        val items = arrayOf(
            "Follow its tempo (it is the boss: its Play starts the app)",
            "Lead the tempo (the app is the boss: its Play starts $name)",
            "No tempo sync (notes and pads only)"
        )
        val modes = intArrayOf(SYNC_FOLLOW, SYNC_LEAD, SYNC_OFF)
        AlertDialog.Builder(a)
            .setTitle("New device: $name")
            .setView(box)
            .setItems(items) { _, w ->
                var m = modes[w]
                if (slotDevice == name) {            // 3.54: an FM-1 with sample slots: the audio box's MASTER CLOCK switch decides
                    m = if (a.audioBox.clockDevice) SYNC_FOLLOW else SYNC_LEAD
                    a.ui.toast("$name: MASTER CLOCK is " + (if (a.audioBox.clockDevice) "DEVICE (the app follows it)" else "BURGER (the app leads)") + " · AUDIO BOX")
                }
                a.setSyncMode(m)
                if (out.isChecked) usbOutFor(name)?.let { a.engine.setOutput(it) }
                if (keep.isChecked) remember(name, m, out.isChecked)
                a.refreshCurrent()
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun usbOutFor(name: String): AudioDeviceInfo? {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { usb(it) }
        return outs.firstOrNull { it.productName?.toString()?.let { p -> name.contains(p, true) || p.contains(name, true) } == true }
            ?: outs.firstOrNull()
    }

    /** Ask the device quietly whether it takes samples into slots of its own (a firmware that speaks the sample protocol). */
    private fun probe(name: String) {
        Thread {
            val ok = try {
                a.sampleLink().info().isNotEmpty()
            } catch (ex: Exception) {
                false
            }
            if (ok) {
                slotDevice = name
                a.sampleLink().hello()                   // (newer firmware: its REC can ask the app for a take)
                // 3.54: the audio box's MASTER CLOCK switch decides who leads. BURGER (as before): the app leads and a
                // Felucca-based firmware is set to follow it (CLK USB; STACK follows any clock by itself). DEVICE: the
                // app follows the FM-1's clock and sends none
                val lead = !a.audioBox.clockDevice
                val clk = lead && a.sampleLink().followAppClock()
                main.post {
                    val mode = if (lead) SYNC_LEAD else SYNC_FOLLOW
                    a.audioBox.applyClock()
                    if (remembered(name)?.optInt("sync", SYNC_LEAD) != mode || remembered(name) == null)
                        remember(name, mode, remembered(name)?.optBoolean("out", false) ?: false)
                    synced = if (clk) name else null
                    a.ui.toast(if (!lead) "$name: the app follows its tempo (MASTER CLOCK: DEVICE) · takes samples"
                               else if (clk) "$name: in sync (it follows the app's tempo) · takes samples"
                               else "$name takes samples · the app leads the tempo (older firmware: set its CLK to USB)")
                    a.refreshCurrent()
                }
            }
        }.start()
    }
}
