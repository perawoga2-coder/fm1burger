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
import java.util.concurrent.locks.LockSupport

/**
 * USB MIDI to the FM-1: sends clock (master), follows clock (follower),
 * and turns incoming notes into pad hits.
 */
class MidiLink(ctx: Context, private val engine: Engine) {

    private val mm = ctx.getSystemService(Context.MIDI_SERVICE) as MidiManager
    private var device: MidiDevice? = null
    private var inPort: MidiInputPort? = null
    private var outPort: MidiOutputPort? = null

    @Volatile var connected = false
    @Volatile var deviceName = ""
    @Volatile var lastClockNs = 0L
    @Volatile var clockCount = 0L

    private val q = ConcurrentLinkedQueue<LongArray>()
    @Volatile private var alive = true

    // BPM estimate from incoming clock
    private val intervals = LongArray(48)
    private var ii = 0
    private var filled = 0
    private var lastTick = 0L

    init {
        engine.midiOut = { b, t -> if (connected) q.add(longArrayOf(b.toLong(), t)) }
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

    fun nameOf(i: MidiDeviceInfo): String =
        i.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI device"

    fun open(info: MidiDeviceInfo, done: (Boolean) -> Unit) {
        close()
        mm.openDevice(info, { dev ->
            if (dev == null) {
                done(false)
            } else {
                device = dev
                if (info.inputPortCount > 0) inPort = dev.openInputPort(0)
                if (info.outputPortCount > 0) {
                    val o = dev.openOutputPort(0)
                    outPort = o
                    o?.connect(receiver)
                }
                deviceName = nameOf(info)
                connected = true
                done(true)
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun close() {
        connected = false
        q.clear()
        try {
            outPort?.disconnect(receiver)
        } catch (e: Exception) {
        }
        try {
            outPort?.close()
        } catch (e: Exception) {
        }
        try {
            inPort?.close()
        } catch (e: Exception) {
        }
        try {
            device?.close()
        } catch (e: Exception) {
        }
        outPort = null
        inPort = null
        device = null
    }

    fun shutdown() {
        alive = false
        close()
    }

    /** True when MIDI clock ticks arrived during the last half second. */
    fun clockActive(): Boolean =
        lastClockNs != 0L && System.nanoTime() - lastClockNs < 500_000_000L

    fun send(bytes: ByteArray) {
        try {
            inPort?.send(bytes, 0, bytes.size)
        } catch (e: Exception) {
        }
    }

    fun noteOn(note: Int, vel: Int) {
        send(byteArrayOf(0x90.toByte(), note.toByte(), vel.toByte()))
    }

    fun noteOff(note: Int) {
        send(byteArrayOf(0x80.toByte(), note.toByte(), 0))
    }

    private fun senderLoop() {
        while (alive) {
            val it = q.peek()
            if (it == null) {
                LockSupport.parkNanos(500_000L)
                continue
            }
            val d = it[1] - System.nanoTime()
            if (d > 1_500_000L) {
                LockSupport.parkNanos(d - 1_000_000L)
                continue
            }
            if (d > 0L) {
                while (System.nanoTime() < it[1]) {
                    Thread.yield()
                }
            }
            q.poll()
            send(byteArrayOf(it[0].toInt().toByte()))
        }
    }

    // ---------------- receiving ----------------

    private var status = 0
    private var d1 = -1

    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            val now = System.nanoTime()
            var i = offset
            val end = offset + count
            while (i < end) {
                val b = msg[i].toInt() and 0xFF
                i++
                if (b >= 0xF8) {
                    realtime(b, now)
                    continue
                }
                if (b >= 0x80) {
                    status = b
                    d1 = -1
                    continue
                }
                if (status in 0x80..0xEF) {
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
            }
        }
    }

    private fun channelMsg(kind: Int, note: Int, vel: Int) {
        if (kind == 0x90 && vel > 0) {
            engine.padHit(note % Cfg.PADS, vel / 127f)
        }
    }

    private fun realtime(b: Int, now: Long) {
        when (b) {
            0xF8 -> {
                lastClockNs = now
                clockCount++
                if (lastTick != 0L) {
                    intervals[ii] = now - lastTick
                    ii = (ii + 1) % intervals.size
                    if (filled < intervals.size) filled++
                    if (filled >= 12 && engine.follow) {
                        var sum = 0L
                        for (j in 0 until filled) sum += intervals[j]
                        val avg = sum.toDouble() / filled
                        engine.bpmF = (60_000_000_000.0 / (avg * 24.0)).coerceIn(30.0, 300.0)
                    }
                }
                lastTick = now
                if (engine.follow) engine.pendingTicks.incrementAndGet()
            }
            0xFA -> if (engine.follow) engine.startReq = true
            0xFB -> if (engine.follow) engine.playing = true
            0xFC -> if (engine.follow) engine.stopReq = true
        }
    }
}
