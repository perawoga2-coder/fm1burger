package com.example.fm1burger

import java.io.ByteArrayOutputStream
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Writes the patterns as a Standard MIDI File (type 1, 96 ticks a beat), to open in any DAW.
 * One track per instrument: drums on channel 10 (General MIDI notes), sampler tracks 1-5 (channels 1-5),
 * bassline, analog synth (with its chord) and the two guitars. The sample sounds themselves are not in it.
 */
object MidiExport {

    private const val PPQ = 96
    private const val T16 = PPQ / 4          // ticks in a 16th note
    /** General MIDI drum notes for the 8 pad rows: kick, snare, closed hat, open hat, clap, low tom, high tom, crash. */
    private val GM_DRUMS = intArrayOf(
        36, 38, 42, 46, 39, 45, 48, 49, 35, 40, 44, 51, 37, 41, 47, 57,      // bank A
        50, 43, 52, 53, 54, 55, 56, 58, 59, 60, 61, 62, 63, 64, 65, 66       // bank B
    )
    private val CHORDS = arrayOf(intArrayOf(0), intArrayOf(0, 4, 7), intArrayOf(0, 3, 7), intArrayOf(0, 7), intArrayOf(0, 12))

    private class Ev(val tick: Int, val order: Int, val data: ByteArray)

    private class Trk(val name: String) {
        val ev = ArrayList<Ev>()
        fun note(ch: Int, n: Int, vel: Int, at: Int, len: Int) {
            val nn = n.coerceIn(0, 127)
            ev.add(Ev(at, 1, byteArrayOf((0x90 or ch).toByte(), nn.toByte(), vel.coerceIn(1, 127).toByte())))
            ev.add(Ev(at + len.coerceAtLeast(1), 0, byteArrayOf((0x80 or ch).toByte(), nn.toByte(), 0)))
        }
    }

    /** One stretch of the song: this part's patterns for this many bars. */
    private class Section(val snap: PartSnap, val bars: Int)

    /** [song] = the whole song list; otherwise the pattern that is on now, once. Returns null if there is nothing to write. */
    fun build(e: Engine, song: Boolean): ByteArray? {
        val bank = e.bank
        val live = PartSnap()
        live.capture(e)
        val secs = ArrayList<Section>()
        if (song && bank.songLen > 0) {
            for (i in 0 until bank.songLen) {
                val p = bank.songPart[i].coerceIn(0, PartBank.N - 1)
                val real = bank.parts[p]
                val snap = if (p == bank.cur || !real.used) live else real
                // a part that plays only its recording has no notes
                if (real.mode == 1 && real.hasClip()) {
                    secs.add(Section(PartSnap(), bank.songBars[i].coerceAtLeast(1)))
                } else {
                    secs.add(Section(snap, bank.songBars[i].coerceAtLeast(1)))
                }
            }
        } else {
            secs.add(Section(live, Pat.bars()))
        }
        val len = Pat.len.coerceIn(Pat.BAR, Cfg.STEPS)

        val drums = Trk("Drums")
        val samp = Array(Cfg.TRACKS) { Trk("Sampler ${it + 1}" + (e.tracks[it].sampleName.let { n -> if (n.isEmpty()) "" else " ($n)" })) }
        val bass = Trk("Bassline")
        val synth = Trk("Analog synth")
        val gtr = arrayOf(Trk("Rhythm guitar"), Trk("Riff guitar"))
        val chord = CHORDS[e.analogSeq.chord.coerceIn(0, CHORDS.size - 1)]
        val bassRoot = e.bass.rootMidi()

        var at = 0                                   // tick of the section start
        for (sec in secs) {
            val sn = sec.snap
            val steps = sec.bars * Pat.BAR
            for (k in 0 until steps) {
                val s = k % len
                val t = at + k * T16
                // drums
                for (r in 0 until Cfg.PADS) {
                    if (sn.pMute[r] || !sn.pOn[r][s]) continue
                    drums.note(9, GM_DRUMS[r], (sn.pLvl[r][s] * 120).roundToInt(), t, T16)
                }
                // sampler tracks: a note on C4 + the step pitch; a loop = one long note per bar
                for (i in 0 until Cfg.TRACKS) {
                    val tr = e.tracks[i]
                    if (sn.tMute[i] || tr.sampleName.isEmpty()) continue
                    if (tr.loop) {
                        if (k % Pat.BAR == 0) samp[i].note(i, 60 + tr.pitch, 100, t, Pat.BAR * T16)
                    } else if (sn.tOn[i][s]) {
                        samp[i].note(i, 60 + tr.pitch + sn.tPitch[i][s], (sn.tLvl[i][s] * 120).roundToInt(), t, T16)
                    }
                }
                // bassline: slide = the note holds into the next one
                if (!sn.bMute && sn.bOn[s]) {
                    val nx = (s + 1) % len
                    val held = sn.bOn[nx] && sn.bSlide[nx]
                    bass.note(5, bassRoot + sn.bNote[s], if (sn.bAcc[s]) 120 else 90, t, if (held) T16 + T16 / 4 else T16 * 3 / 5)
                }
                // analog synth with its chord
                if (!sn.aMute && sn.aEnabled) {
                    val b = s * AnalogSeq.NOTES
                    for (n in 0 until AnalogSeq.NOTES) {
                        val l = sn.aGrid[b + n]
                        if (l > 0) for (x in chord) synth.note(6, n + x, 100, t, l.coerceIn(1, AnalogSeq.MAX_LEN) * T16)
                    }
                }
            }
            at += steps * T16
        }
        // guitars: from the live pattern through the whole length (they are not stored in the parts)
        val total = at
        for ((gi, g) in arrayOf(e.rhythmGtr, e.riffGtr).withIndex()) {
            if (g.mute) continue
            val root = 69.0 + 12.0 * ln(g.rootHz() / 440.0) / ln(2.0)
            val oct = if (gi == 1) 12 * g.octave else 0
            var k = 0
            val steps = total / T16
            while (k < steps) {
                val s = k % len
                val art = g.art[s]
                if (art == MetalGuitar.CHUG || art == MetalGuitar.OPEN) {
                    var l = 1
                    if (art == MetalGuitar.OPEN) {
                        while (k + l < steps && g.art[(k + l) % len] == MetalGuitar.HOLD) l++
                    }
                    gtr[gi].note(7 + gi, (root.roundToInt() + g.fret[s] + oct), if (art == MetalGuitar.CHUG) 100 else 115, k * T16,
                        if (art == MetalGuitar.CHUG) T16 / 2 else l * T16)
                }
                k++
            }
        }

        val tracks = ArrayList<Trk>()
        for (tk in listOf(drums) + samp.toList() + listOf(bass, synth) + gtr.toList()) if (tk.ev.isNotEmpty()) tracks.add(tk)
        if (tracks.isEmpty()) return null

        val out = ByteArrayOutputStream()
        val nTracks = tracks.size + 1
        out.write("MThd".toByteArray())
        be32(out, 6)
        be16(out, 1)
        be16(out, nTracks)
        be16(out, PPQ)
        // tempo track
        val tempo = ByteArrayOutputStream()
        metaText(tempo, 0x03, "Burger Studio")
        val us = (60_000_000.0 / e.bpmF).roundToInt()
        tempo.write(0); tempo.write(0xFF); tempo.write(0x51); tempo.write(3)
        tempo.write((us shr 16) and 0xFF); tempo.write((us shr 8) and 0xFF); tempo.write(us and 0xFF)
        tempo.write(0); tempo.write(0xFF); tempo.write(0x58); tempo.write(4)
        tempo.write(4); tempo.write(2); tempo.write(24); tempo.write(8)
        vlq(tempo, total); tempo.write(0xFF); tempo.write(0x2F); tempo.write(0)
        chunk(out, tempo.toByteArray())
        for (tk in tracks) {
            val b = ByteArrayOutputStream()
            metaText(b, 0x03, tk.name)
            val sorted = tk.ev.sortedWith(compareBy<Ev>({ it.tick }, { it.order }))
            var last = 0
            for (ev in sorted) {
                vlq(b, ev.tick - last)
                b.write(ev.data)
                last = ev.tick
            }
            vlq(b, 0); b.write(0xFF); b.write(0x2F); b.write(0)
            chunk(out, b.toByteArray())
        }
        return out.toByteArray()
    }

    private fun metaText(o: ByteArrayOutputStream, type: Int, s: String) {
        val t = s.toByteArray(Charsets.UTF_8)
        o.write(0); o.write(0xFF); o.write(type)
        vlq(o, t.size)
        o.write(t)
    }

    private fun chunk(o: ByteArrayOutputStream, data: ByteArray) {
        o.write("MTrk".toByteArray())
        be32(o, data.size)
        o.write(data)
    }

    private fun be32(o: ByteArrayOutputStream, v: Int) {
        o.write((v ushr 24) and 0xFF); o.write((v ushr 16) and 0xFF); o.write((v ushr 8) and 0xFF); o.write(v and 0xFF)
    }

    private fun be16(o: ByteArrayOutputStream, v: Int) {
        o.write((v ushr 8) and 0xFF); o.write(v and 0xFF)
    }

    private fun vlq(o: ByteArrayOutputStream, v0: Int) {
        var v = v0.coerceAtLeast(0)
        var buf = v and 0x7F
        v = v ushr 7
        while (v > 0) {
            buf = (buf shl 8) or 0x80 or (v and 0x7F)
            v = v ushr 7
        }
        while (true) {
            o.write(buf and 0xFF)
            if (buf and 0x80 != 0) buf = buf ushr 8 else break
        }
    }
}
