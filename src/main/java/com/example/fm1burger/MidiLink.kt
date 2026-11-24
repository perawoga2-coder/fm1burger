package com.example.fm1burger

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.LockSupport

/**
 * MIDI to the FM-1 and other gear, over USB (and Bluetooth MIDI devices Android has connected).
 *
 * Standard MIDI sync:
 *  - sends: Clock (24 per quarter note, timestamped), Start, Stop, Continue and Song Position Pointer,
 *    to every connected device; the clock can keep running while stopped so followers lock the tempo first
 *  - follows: Clock, Start, Stop, Continue and Song Position Pointer from one device (the first that sends clock)
 *  - incoming notes play the pads; MIDI Thru can pass everything on to the other devices
 */
class MidiLink(ctx: Context, private val engine: Engine) {

    private val mm = ctx.getSystemService(Context.MIDI_SERVICE) as MidiManager

    private inner class Conn(val id: Int, val name: String, val dev: MidiDevice, val inPort: MidiInputPort?, val outPort: MidiOutputPort?) {
        val rx = Rx(this)
        /** 3.55: an FM-1 (STACK): its keys' notes do not play the app's drum pads (they sound on the FM-1 itself). */
        @Volatile var fm1 = isFm1Name(name)
    }

    /** 3.55: a device's name says it is an FM-1 (STACK, or the Burger Studio / Felucca firmware). */
    private fun isFm1Name(n: String): Boolean =
        n.contains("STACK", true) || n.contains("FM-1", true) || n.contains("FM1", true) ||
            n.contains("Felucca", true) || n.contains("Burger", true)

    /** 3.55: this connected device turned out to be an FM-1 (it answered the sample protocol, or came over the
     * Bluetooth button): its notes stay off the pads. */
    fun markFm1(name: String) {
        for (c in conns) if (c.name == name) c.fm1 = true
    }

    /** 3.55: how many notes from an FM-1 were kept off the pads (the audio box shows that they arrive). */
    @Volatile var fm1Notes = 0L

    /**
     * 3.55: takes a MIDI device that is already open (Bluetooth: MidiManager.openBluetoothDevice gives the device,
     * not its info first). Returns its id, or -1 when its ports could not be opened.
     */
    fun adopt(dev: MidiDevice, shownName: String, fm1: Boolean): Int {
        val info = dev.info
        val old = conns.firstOrNull { it.id == info.id }
        if (old != null) {
            old.fm1 = old.fm1 || fm1
            return info.id
        }
        return try {
            val ip = if (info.inputPortCount > 0) dev.openInputPort(0) else null
            val op = if (info.outputPortCount > 0) dev.openOutputPort(0) else null
            val n = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: shownName
            val c = Conn(info.id, n, dev, ip, op)
            c.fm1 = c.fm1 || fm1
            op?.connect(c.rx)
            conns.add(c)
            info.id
        } catch (ex: Exception) {
            try {
                dev.close()
            } catch (e2: Exception) {
            }
            -1
        }
    }

    private val conns = CopyOnWriteArrayList<Conn>()

    val connected: Boolean get() = conns.isNotEmpty()
    val deviceName: String get() = conns.joinToString(" + ") { it.name }
    val count: Int get() = conns.size

    /** A whole SysEx message arrived (without F0 / F7): the FM-1 firmware's replies (Fm1SampleLink). */
    @Volatile var sysexListener: ((ByteArray) -> Unit)? = null

    /** Sends one SysEx message (body without F0 / F7) to the FM-1 running Burger Studio / Felucca firmware
     * (its port is named "Felucca"), or to every device when none has that name. Returns false with no device. */
    fun sendSysex(body: ByteArray): Boolean {
        val msg = ByteArray(body.size + 2)
        msg[0] = 0xF0.toByte()
        System.arraycopy(body, 0, msg, 1, body.size)
        msg[msg.size - 1] = 0xF7.toByte()
        val fw = conns.filter { it.name.contains("Felucca", true) || it.name.contains("Burger", true) }
        val to = if (fw.isNotEmpty()) fw else conns.toList()
        for (c in to) {
            try {
                c.inPort?.send(msg, 0, msg.size)
            } catch (e: Exception) {
            }
        }
        return to.isNotEmpty()
    }

    /** Pass everything that comes in on to the other devices. */
    @Volatile var thru = false

    @Volatile var lastClockNs = 0L
    @Volatile var clockCount = 0L
    /** The device the clock is followed from (the first that sends one), or -1. */
    @Volatile private var clockSource = -1

    // messages to send at a time: [length, b0, b1, b2, time in ns]
    private val q = ConcurrentLinkedQueue<LongArray>()
    @Volatile private var alive = true

    // BPM estimate from incoming clock
    private val intervals = LongArray(48)
    private var ii = 0
    private var filled = 0
    private var lastTick = 0L

    init {
        engine.midiOut = { bytes, t ->
            if (conns.isNotEmpty()) {
                val e = LongArray(5)
                e[0] = bytes.size.toLong()
                for (k in 0 until minOf(3, bytes.size)) e[1 + k] = (bytes[k].toInt() and 0xFF).toLong()
                e[4] = t
                q.add(e)
            }
        }
        val th = Thread { senderLoop() }
        th.isDaemon = true
        th.name = "burger-midi-out"
        th.start()
    }

    @Suppress("DEPRECATION")
    fun devices(): List<MidiDeviceInfo> = try {
        mm.devices.toList()
    } catch (e: Exception) {
        emptyList()
    }

    /** Devices that are not connected yet. */
    fun freeDevices(): List<MidiDeviceInfo> = devices().filter { d -> conns.none { it.id == d.id } }

    fun nameOf(i: MidiDeviceInfo): String =
        i.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI device"

    /** Connects one more device (already connected: nothing to do). */
    fun open(info: MidiDeviceInfo, done: (Boolean) -> Unit) {
        if (conns.any { it.id == info.id }) {
            done(true)
            return
        }
        try {
            mm.openDevice(info, { dev ->
                if (dev == null) {
                    done(false)
                } else {
                    try {
                        val ip = if (info.inputPortCount > 0) dev.openInputPort(0) else null
                        val op = if (info.outputPortCount > 0) dev.openOutputPort(0) else null
                        val c = Conn(info.id, nameOf(info), dev, ip, op)
                        op?.connect(c.rx)
                        conns.add(c)
                        done(true)
                    } catch (ex: Exception) {
                        try {
                            dev.close()
                        } catch (e2: Exception) {
                        }
                        done(false)
                    }
                }
            }, Handler(Looper.getMainLooper()))
        } catch (ex: Exception) {
            done(false)
        }
    }

    fun isOpen(id: Int): Boolean = conns.any { it.id == id }

    /** The device went away (unplugged): drop it. */
    fun closeId(id: Int) {
        val c = conns.firstOrNull { it.id == id } ?: return
        conns.remove(c)
        if (clockSource == id) clockSource = -1
        closeConn(c)
    }

    private fun closeConn(c: Conn) {
        try {
            c.outPort?.disconnect(c.rx)
        } catch (e: Exception) {
        }
        try {
            c.outPort?.close()
        } catch (e: Exception) {
        }
        try {
            c.inPort?.close()
        } catch (e: Exception) {
        }
        try {
            c.dev.close()
        } catch (e: Exception) {
        }
    }

    /** Disconnects every device. */
    fun close() {
        q.clear()
        val all = conns.toList()
        conns.clear()
        clockSource = -1
        for (c in all) closeConn(c)
    }

    fun shutdown() {
        alive = false
        close()
    }

    /** True when MIDI clock ticks arrived during the last half second. */
    fun clockActive(): Boolean =
        lastClockNs != 0L && System.nanoTime() - lastClockNs < 500_000_000L

    /** Sends to every connected device (except one, for MIDI Thru). */
    private fun sendAll(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size, except: Conn? = null) {
        for (c in conns) {
            if (c === except) continue
            try {
                c.inPort?.send(bytes, offset, count)
            } catch (e: Exception) {
            }
        }
    }

    fun send(bytes: ByteArray) = sendAll(bytes)

    fun noteOn(note: Int, vel: Int) {
        send(byteArrayOf(0x90.toByte(), note.toByte(), vel.toByte()))
    }

    fun noteOff(note: Int) {
        send(byteArrayOf(0x80.toByte(), note.toByte(), 0))
    }

    private fun senderLoop() {
        val one = ByteArray(1)
        val three = ByteArray(3)
        while (alive) {
            val it = q.peek()
            if (it == null) {
                LockSupport.parkNanos(500_000L)
                continue
            }
            val d = it[4] - System.nanoTime()
            if (d > 1_500_000L) {
                LockSupport.parkNanos(d - 1_000_000L)
                continue
            }
            if (d > 0L) {
                while (System.nanoTime() < it[4]) {
                    Thread.yield()
                }
            }
            q.poll()
            val n = it[0].toInt()
            if (n == 1) {
                one[0] = it[1].toInt().toByte()
                sendAll(one)
            } else {
                for (k in 0 until 3) three[k] = it[1 + k].toInt().toByte()
                sendAll(three, 0, minOf(3, n))
            }
        }
    }

    // ---------------- receiving ----------------

    /** One receiver per device, with its own running status. */
    private inner class Rx(private val c: Conn) : MidiReceiver() {
        private var status = 0
        private var d1 = -1
        private val sx = java.io.ByteArrayOutputStream()
        private var inSx = false

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            val now = System.nanoTime()
            if (thru) sendAll(msg, offset, count, c)
            var i = offset
            val end = offset + count
            while (i < end) {
                val b = msg[i].toInt() and 0xFF
                i++
                if (b >= 0xF8) {
                    realtime(c, b, now)
                    continue
                }
                if (b == 0xF0) {
                    inSx = true
                    sx.reset()
                    status = 0
                    continue
                }
                if (inSx) {
                    if (b == 0xF7 || b >= 0x80) {
                        inSx = false
                        if (b == 0xF7) {
                            val l = sysexListener
                            if (l != null) try { l(sx.toByteArray()) } catch (e: Exception) {}
                            continue
                        }
                    } else {
                        if (sx.size() < 65536) sx.write(b)
                        continue
                    }
                }
                if (b >= 0x80) {
                    status = b
                    d1 = -1
                    continue
                }
                when {
                    status in 0x80..0xEF -> {
                        val kind = status and 0xF0
                        val need = if (kind == 0xC0 || kind == 0xD0) 1 else 2
                        if (need == 1) {
                            d1 = -1
                        } else if (d1 < 0) {
                            d1 = b
                        } else {
                            channelMsg(c, kind, d1, b)
                            d1 = -1
                        }
                    }
                    status == 0xF2 -> {
                        // Song Position Pointer: 14 bits, in 16th notes
                        if (d1 < 0) {
                            d1 = b
                        } else {
                            if (isClockSource(c)) engine.songPosition(d1 or (b shl 7))
                            d1 = -1
                            status = 0
                        }
                    }
                }
            }
        }
    }

    private fun isClockSource(c: Conn): Boolean {
        if (clockSource < 0 || conns.none { it.id == clockSource }) clockSource = c.id
        return clockSource == c.id
    }

    private fun channelMsg(c: Conn, kind: Int, note: Int, vel: Int) {
        if (kind == 0x90 && vel > 0) {
            if (c.fm1) {
                // 3.55: the FM-1's own keys (STACK sends them over Bluetooth): they play on the FM-1. Hitting the
                // app's pads with them too was the echo of old: drums on some of its keys. Counted, not played
                fm1Notes++
                return
            }
            engine.padHit(note % Cfg.PADS, vel / 127f)
        }
    }

    private fun realtime(c: Conn, b: Int, now: Long) {
        if (!isClockSource(c)) return
        when (b) {
            0xF8 -> {
                lastClockNs = now
                clockCount++
                if (lastTick != 0L) {
                    val iv = now - lastTick
                    // ignore a gap (the other side was stopped): start a fresh estimate
                    if (iv > 250_000_000L) {
                        filled = 0
                        ii = 0
                    } else if (iv < 4_000_000L) {
                        // 3.54: no tempo has ticks 4 ms apart: the short gap a device leaves at its Start (STACK
                        // starts the bar at once). Left out of the average
                    } else {
                        intervals[ii] = iv
                        ii = (ii + 1) % intervals.size
                        if (filled < intervals.size) filled++
                    }
                    if (filled >= 12 && engine.follow) {
                        var sum = 0L
                        for (j in 0 until filled) sum += intervals[j]
                        val avg = sum.toDouble() / filled
                        engine.bpmF = (60_000_000_000.0 / (avg * 24.0)).coerceIn(30.0, 300.0)
                    }
                }
                lastTick = now
                if (engine.follow) engine.followTick(now)
            }
            0xFA -> if (engine.follow) engine.startReq = true
            0xFB -> if (engine.follow) engine.continueReq = true
            0xFC -> if (engine.follow) engine.stopReq = true
        }
    }
}
