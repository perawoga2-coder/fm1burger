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
    }

    private val conns = CopyOnWriteArrayList<Conn>()

    val connected: Boolean get() = conns.isNotEmpty()
    val deviceName: String get() = conns.joinToString(" + ") { it.name }
    val count: Int get() = conns.size

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
                            channelMsg(kind, d1, b)
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

    private fun channelMsg(kind: Int, note: Int, vel: Int) {
        if (kind == 0x90 && vel > 0) {
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
