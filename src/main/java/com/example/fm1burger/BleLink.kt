package com.example.fm1burger

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid

/**
 * 3.55: the FM-1 (STACK firmware) over Bluetooth MIDI, from one button.
 *
 * CONNECT scans for Bluetooth LE devices that offer the MIDI service, takes the FM-1 (a name with STACK or FM-1 in
 * it first; else the only MIDI device found), and opens it through Android's own Bluetooth MIDI
 * (MidiManager.openBluetoothDevice). From there it is a MIDI device like any other in MidiLink; the audio box's MASTER
 * CLOCK switch says who leads. When the link goes by itself (the FM-1 off, out of reach) it is looked for again, every
 * few seconds for two minutes; DISCONNECT ends it and stops that.
 *
 * Android 12 and later ask for "Nearby devices"; before that a Bluetooth scan needs the location permission (the scan
 * is not used to find out where anyone is).
 */
class BleLink(private val a: MainActivity) {

    companion object {
        val MIDI_SERVICE: ParcelUuid = ParcelUuid.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
        const val PERM_REQ = 55
        const val OFF = 0
        const val SCANNING = 1
        const val CONNECTING = 2
        const val CONNECTED = 3
        private const val SCAN_MS = 8000L
        private const val RETRY_MS = 6000L
        private const val RETRY_FOR_MS = 120_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = a.getSharedPreferences("ble", Context.MODE_PRIVATE)

    @Volatile var state = OFF
        private set
    /** The last thing that happened, for the status lines. */
    @Volatile var note = ""
        private set
    /** The MIDI device id of the Bluetooth link in MidiLink, or -1. */
    @Volatile var midiId = -1
        private set
    var name = ""
        private set

    private var want = false                    // the user asked for the link: it is looked for again when it goes
    private var retryUntil = 0L
    private var scanning = false
    private var found: BluetoothDevice? = null
    private var foundNamed = false
    private var pendingAfterPermission = false

    private fun adapter(): BluetoothAdapter? = try {
        (a.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    } catch (ex: Exception) {
        null
    }

    private fun perms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun allowed(): Boolean = perms().all { a.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun statusLine(): String = when (state) {
        SCANNING -> "Bluetooth: looking for the FM-1…"
        CONNECTING -> "Bluetooth: connecting to $name…"
        CONNECTED -> "Bluetooth: connected to $name"
        else -> if (note.isNotEmpty()) "Bluetooth: $note" else "Bluetooth: not connected"
    }

    val busy: Boolean get() = state != OFF

    /** The button: connect (asking for the permission first when it is not there yet). */
    fun connect() {
        if (state != OFF) return
        val ad = adapter()
        if (ad == null || !a.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            say("this tablet has no Bluetooth LE")
            return
        }
        if (!allowed()) {
            pendingAfterPermission = true
            try {
                a.requestPermissions(perms(), PERM_REQ)
            } catch (ex: Exception) {
                say("could not ask for the Bluetooth permission")
            }
            return
        }
        if (!ad.isEnabled) {
            say("Bluetooth is off on the tablet. Turn it on, then CONNECT again")
            return
        }
        want = true
        retryUntil = 0L
        startScan()
    }

    /** MainActivity hands the answer to the permission question on. */
    fun onPermission(granted: Boolean) {
        if (!pendingAfterPermission) return
        pendingAfterPermission = false
        if (granted) connect()
        else say(if (Build.VERSION.SDK_INT >= 31) "needs the \"Nearby devices\" permission (Android settings, this app)"
                 else "needs the location permission for a Bluetooth scan (Android settings, this app)")
    }

    /** The button: disconnect, and stay disconnected. */
    fun disconnect() {
        want = false
        retryUntil = 0L
        main.removeCallbacks(retry)
        stopScan()
        val id = midiId
        midiId = -1
        state = OFF
        if (id >= 0) {
            a.midi.closeId(id)
            if (!a.midi.connected) a.setSyncMode(DeviceHub.SYNC_OFF)
        }
        note = "disconnected"
        a.refreshCurrent()
    }

    /** DeviceHub: a MIDI device went. Ours: look for the FM-1 again for a while (unless DISCONNECT was pressed). */
    fun onMidiGone(id: Int) {
        if (id != midiId) return
        midiId = -1
        state = OFF
        if (want) {
            note = "the FM-1 went: looking for it again"
            retryUntil = System.currentTimeMillis() + RETRY_FOR_MS
            main.removeCallbacks(retry)
            main.postDelayed(retry, 1500)
        } else {
            note = "disconnected"
        }
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        stopScan()
    }

    private val retry = Runnable {
        if (want && state == OFF && System.currentTimeMillis() < retryUntil) startScan()
        else if (want && state == OFF) {
            want = false
            note = "the FM-1 was not found again. Press CONNECT when it is on"
        }
    }

    private fun say(s: String) {
        note = s
        a.ui.toast("Bluetooth: $s")
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device ?: return
            val n = try {
                result.scanRecord?.deviceName ?: d.name ?: ""
            } catch (ex: SecurityException) {
                ""
            }
            val named = n.contains("STACK", true) || n.contains("FM-1", true) || n.contains("FM1", true)
            if (found == null || (named && !foundNamed)) {
                found = d
                foundNamed = named
                name = if (n.isNotEmpty()) n else "Bluetooth MIDI device"
            }
            if (named) {                         // the FM-1 itself: no need to wait for the scan's end
                main.removeCallbacks(scanEnd)
                main.post(scanEnd)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            state = OFF
            say("the scan failed (code $errorCode)")
        }
    }

    private val scanEnd = Runnable {
        if (state != SCANNING) return@Runnable
        stopScan()
        val d = found
        if (d == null) {
            state = OFF
            if (want && System.currentTimeMillis() < retryUntil) {
                main.postDelayed(retry, RETRY_MS)
            } else {
                want = want && retryUntil != 0L && System.currentTimeMillis() < retryUntil
                say("no FM-1 found. Is it on, with Bluetooth ON (its GLO page), and not connected to something else?")
            }
        } else {
            open(d)
        }
    }

    private fun startScan() {
        val sc = try {
            adapter()?.bluetoothLeScanner
        } catch (ex: Exception) {
            null
        }
        if (sc == null) {
            state = OFF
            say("Bluetooth is off on the tablet")
            return
        }
        found = null
        foundNamed = false
        try {
            val f = ScanFilter.Builder().setServiceUuid(MIDI_SERVICE).build()
            val st = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            sc.startScan(listOf(f), st, scanCb)
            scanning = true
            state = SCANNING
            note = ""
            main.removeCallbacks(scanEnd)
            main.postDelayed(scanEnd, SCAN_MS)
        } catch (ex: SecurityException) {
            state = OFF
            say("the Bluetooth permission is missing")
        } catch (ex: Exception) {
            state = OFF
            say("could not start the scan")
        }
    }

    private fun stopScan() {
        main.removeCallbacks(scanEnd)
        if (!scanning) return
        scanning = false
        try {
            adapter()?.bluetoothLeScanner?.stopScan(scanCb)
        } catch (ex: Exception) {
        }
    }

    private fun open(d: BluetoothDevice) {
        state = CONNECTING
        val mm = a.getSystemService(Context.MIDI_SERVICE) as MidiManager
        // Android gives no answer when the device does not respond: 15 s, then it counts as not there
        val giveUp = Runnable {
            if (state == CONNECTING) {
                state = OFF
                if (want && System.currentTimeMillis() < retryUntil) main.postDelayed(retry, RETRY_MS)
                else say("$name did not answer. Press CONNECT to try again")
            }
        }
        main.postDelayed(giveUp, 15000)
        try {
            mm.openBluetoothDevice(d, { dev ->
                main.removeCallbacks(giveUp)
                if (state != CONNECTING) {       // (DISCONNECT, or the 15 s, came first)
                    try {
                        dev?.close()
                    } catch (ex: Exception) {
                    }
                } else if (dev == null) {
                    state = OFF
                    if (want && System.currentTimeMillis() < retryUntil) main.postDelayed(retry, RETRY_MS)
                    else say("could not connect to $name")
                } else {
                    val id = a.midi.adopt(dev, name, true)
                    if (id < 0) {
                        state = OFF
                        say("could not open $name's MIDI")
                    } else {
                        midiId = id
                        state = CONNECTED
                        retryUntil = 0L
                        note = ""
                        prefs.edit().putString("last", name).apply()
                        a.audioBox.applyClock()          // MASTER CLOCK decides who leads, as over USB
                        a.ui.toast("Bluetooth: connected to $name")
                        a.refreshCurrent()
                    }
                }
            }, main)
        } catch (ex: SecurityException) {
            main.removeCallbacks(giveUp)
            state = OFF
            say("the Bluetooth permission is missing")
        } catch (ex: Exception) {
            main.removeCallbacks(giveUp)
            state = OFF
            say("could not connect to $name")
        }
    }
}
