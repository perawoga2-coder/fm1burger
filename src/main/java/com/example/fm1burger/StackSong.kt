package com.example.fm1burger

import java.util.zip.CRC32
import kotlin.math.ln

/**
 * 3.56 VACUUM: the whole song of an FM-1 running STACK, pulled over USB and laid onto Home's lanes 1 to 8 in STACK's
 * own order (T1 -> lane 1 ... T8 -> lane 8). This file is the part that needs no screen: what STACK sends
 * (stack/docs/PROTOCOL.md, VACUUM: command 30's record), which of the app's sounds each of STACK's is, and the plan:
 * what every lane becomes. Vacuum.kt asks the FM-1, shows the progress and puts the plan on the lanes.
 *
 * The lanes it makes are ordinary Sampler and Drum lanes (an engine track or pad with a sound of the library and
 * steps): TAKE ALL, the queue and 16 Track see nothing special in them.
 */

/** One of STACK's eight tracks, as its record has it. */
class StackTrack(
    val kind: Int,              // 0 EMPTY, 1 a built-in sound, 2 a sample of the app's (MY 1..3), 3 the looper
    val cat: Int,               // 0 DRUMS, 1 BASS, 2 KEYS, 3 PAD, 4 LEAD, 5 FX; 255 none
    val slot: Int,              // MY 1..3
    val hit: Boolean,           // plays through, its own pitch on every key
    val myCrc: Long,            // kind 2: the CRC the app gave the sample's data when it sent it
    val name: String,
    val catName: String,
    val set: IntArray,          // the 25 settings, signed where they are (PROTOCOL.md's order)
    val on: BooleanArray,
    val note: IntArray
) {
    companion object {
        const val PITCH = 0; const val FILTER = 1; const val DECAY = 2; const val LEVEL = 3
        const val ENV_A = 4; const val ENV_R = 5; const val ENV_S = 6; const val ENV_V = 7
        const val LFO_A = 8; const val ED_START = 12; const val ED_REV = 13; const val ED_LOOP = 14; const val ED_LEN = 15
        const val FIT = 16; const val ARP_R = 17; const val FX_A = 21; const val FX_B = 22; const val FX_AK = 23; const val FX_BK = 24
        private val SIGNED = setOf(PITCH, FILTER, ENV_R, FX_AK, FX_BK)
        fun signed(i: Int, b: Int): Int = if (i in SIGNED) b.toByte().toInt() else b
    }
    val isDrum: Boolean get() = kind == 1 && cat == 0
    val label: String get() = if (name.isNotEmpty()) name else "EMPTY"
}

class StackSong(
    val bpm: Int, val sig: Int, val steps: Int, val swing: Int, val layers: Int,
    val hasLoop: Boolean, val version: String, val tracks: List<StackTrack>
)

object StackDump {
    const val PIECE = 128
    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
    private fun text(b: ByteArray, o: Int, n: Int): String {
        val sb = StringBuilder()
        for (k in 0 until n) {
            val c = b[o + k].toInt() and 0xFF
            if (c == 0) break
            if (c in 0x20..0x7E) sb.append(c.toChar())
        }
        return sb.toString().trim()
    }

    /** STACK's record -> the song; null when it is not one (the magic, the version, the length or the CRC is wrong). */
    fun parse(b: ByteArray): StackSong? {
        if (b.size < 32 || b[0] != 'S'.code.toByte() || b[1] != 'T'.code.toByte() || b[2] != 'K'.code.toByte() || b[3] != 'D'.code.toByte()) return null
        if (b[4].toInt() != 1) return null
        val nt = b[5].toInt() and 0xFF
        val ts = b[6].toInt() and 0xFF
        val len = (b[8].toInt() and 0xFF) or ((b[9].toInt() and 0xFF) shl 8)
        if (nt != 8 || ts < 96 || len != 32 + nt * ts || b.size < len) return null
        val crc = CRC32()
        crc.update(b, 16, len - 16)
        if (crc.value != u32(b, 10)) return null
        val tr = ArrayList<StackTrack>()
        for (t in 0 until nt) {
            val o = 32 + t * ts
            val f = b[o + 3].toInt()
            tr.add(StackTrack(
                b[o].toInt() and 0xFF, b[o + 1].toInt() and 0xFF, b[o + 2].toInt() and 0xFF, f and 1 != 0, u32(b, o + 8),
                text(b, o + 12, 16), text(b, o + 88, 8),
                IntArray(25) { StackTrack.signed(it, b[o + 28 + it].toInt() and 0xFF) },
                BooleanArray(16) { b[o + 56 + it].toInt() != 0 }, IntArray(16) { b[o + 72 + it].toInt() and 0x7F }
            ))
        }
        return StackSong(
            b[16].toInt() and 0xFF, b[17].toInt() and 0xFF, (b[18].toInt() and 0xFF).coerceIn(1, 16), b[19].toInt() and 0xFF,
            b[21].toInt() and 0xFF, b[7].toInt() and 1 != 0, text(b, 22, 10), tr
        )
    }

    /** 7-to-8 packed bytes (a byte of top bits, then up to seven bytes) -> the bytes. */
    fun unpack7(s: ByteArray, from: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = from
        while (i < s.size) {
            val hi = s[i++].toInt()
            var j = 0
            while (j < 7 && i < s.size) {
                out.write((s[i].toInt() and 0x7F) or (((hi shr j) and 1) shl 7))
                i++
                j++
            }
        }
        return out.toByteArray()
    }
}

/** Which of the app's sounds each of STACK's built-in sounds is. */
object StackSounds {
    /** file: the sound in the app's library; semis: what plays it at STACK's pitch (STACK's middle C is the sound's own
     * note; the app plays a sample as it is); stand: not the same sound (STACK's came from a pack the app does not
     * have): the nearest one the app has. */
    class Snd(val file: String, val semis: Int, val stand: Boolean)

    val MAP: Map<String, Snd> = mapOf(
        "KICK ROCK" to Snd("Rock Kick.wav", 0, false),
        "KICK TIGHT" to Snd("Rock Kick Tight.wav", 0, false),
        "SNARE ROCK" to Snd("Rock Snare.wav", 0, false),
        "SNARE CRACK" to Snd("Rock Snare Crack.wav", 0, false),
        "HAT ROCK" to Snd("Rock Hat Closed.wav", 0, false),
        "OPEN ROCK" to Snd("Rock Hat Open.wav", 0, false),
        "TOM ROCK" to Snd("Rock Tom Mid.wav", 0, false),
        "FLOOR TOM" to Snd("Rock Tom Floor.wav", 0, false),
        "CRASH ROCK" to Snd("Rock Crash.wav", 0, false),
        "RIDE ROCK" to Snd("Rock Ride.wav", 0, false),
        "KICK HG" to Snd("Studio Kit Kick.wav", 0, true),
        "SNARE HG" to Snd("Studio Kit Snare.wav", 0, true),
        "CLAP HG" to Snd("Studio Kit Clap.wav", 0, true),
        "HAT HG" to Snd("Studio Kit Hat Closed.wav", 0, true),
        "COWBELL HG" to Snd("Perc Kit Bell.wav", 0, true),
        "RIM HG" to Snd("Studio Kit Rim.wav", 0, true),
        "KICK DEEP" to Snd("Deep Kit Kick.wav", 0, false),
        "SNARE DEEP" to Snd("Deep Kit Snare.wav", 0, false),
        "KICK STUDIO" to Snd("Studio Kit Kick.wav", 0, false),
        "CLAP STUDIO" to Snd("Studio Kit Clap.wav", 0, false),
        "BELL PERC" to Snd("Perc Kit Bell.wav", 0, false),
        "HAND DRUM" to Snd("Hand Drum C4.wav", 0, false),
        "SUB BASS" to Snd("Sub Bass C3.wav", 12, false),
        "SQR BASS" to Snd("Sqr Bass C3.wav", 12, false),
        "FAT BASS" to Snd("Fat Bass C3.wav", 12, false),
        "WAVE BASS" to Snd("Wave Bass C3.wav", 12, false),
        "WOW BASS" to Snd("Wow Bass C3.wav", 12, false),
        "FM BASS" to Snd("Fm Bass C3.wav", 12, false),
        "ACID" to Snd("Acid C4.wav", 0, false),
        "PUNCH BASS" to Snd("FM Bass Punch C2.wav", 24, false),
        "SLAP BASS" to Snd("FM Slap Bass C2.wav", 24, false),
        "GROWL BASS" to Snd("FM Growl Bass C2.wav", 24, false),
        "PIANO" to Snd("Grand Piano C4.wav", 0, false),
        "TINE EP" to Snd("Tine Ep C4.wav", 0, false),
        "SOFT EP" to Snd("FM E.Piano Soft C4.wav", 0, false),
        "CLAV" to Snd("FM Clav C4.wav", 0, false),
        "ORGAN" to Snd("Organ C4.wav", 0, false),
        "FULL ORGAN" to Snd("Full Organ C4.wav", 0, false),
        "MARIMBA" to Snd("Marimba C4.wav", 0, false),
        "KALIMBA" to Snd("Kalimba C4.wav", 0, false),
        "VIBES" to Snd("FM Vibes C5.wav", -12, false),
        "HARP" to Snd("Harp C4.wav", 0, false),
        "BELL" to Snd("Bell C4.wav", 0, false),
        "FM BELL" to Snd("FM6 Bell C4.wav", 0, false),
        "PLUCK" to Snd("Pluck C4.wav", 0, false),
        "PLUCK HG" to Snd("Pluck C4.wav", 0, true),
        "MUSIC BOX" to Snd("FM Music Box G5.wav", -19, false),
        "STRINGS" to Snd("Strings C4.wav", 0, false),
        "PWM STRINGS" to Snd("Pwm Str C4.wav", 0, false),
        "CHOIR" to Snd("Choir Aah C4.wav", 0, false),
        "SHIMMER" to Snd("Shimmer C4.wav", 0, false),
        "FM PAD" to Snd("Pad C4.wav", 0, false),
        "VOX HG" to Snd("Choir Aah C4.wav", 0, true),
        "GLASS HG" to Snd("Bell C4.wav", 0, true),
        "SAW LEAD" to Snd("Saw Lead C4.wav", 0, false),
        "PULSE LEAD" to Snd("Pulse Ld C4.wav", 0, false),
        "WAVE LEAD" to Snd("Wave Lead C4.wav", 0, false),
        "VOX LEAD" to Snd("Vox Lead C4.wav", 0, false),
        "RAVE" to Snd("Rave C4.wav", 0, false),
        "FLUTE" to Snd("Soft Flute C4.wav", 0, false),
        "ROCK ORGAN" to Snd("Rock Drive C4.wav", 0, false),
        "BRASS HG" to Snd("Brass C4.wav", 0, true),
        "REED HG" to Snd("FM6 Organ C4.wav", 0, true),
        "ORGAN HG" to Snd("Organ C4.wav", 0, true),
        "WIND" to Snd("Wind.wav", 0, false),
        "RAIN" to Snd("Rain.wav", 0, false),
        "ARCADE" to Snd("Arcade.wav", 0, false),
        "GLITCH" to Snd("Glitch C4.wav", 0, false),
        "SPACE FX" to Snd("FM Space FX C4.wav", 0, false),
    )

    /** STACK's time signatures (2/4, 3/4, 4/4, 6/8, 7/8) as the app's meters (Pat.METERS). */
    val METER = intArrayOf(2, 1, 0, 3, 6)
    val SIG_NAME = arrayOf("2/4", "3/4", "4/4", "6/8", "7/8")
    val FX_NAME = arrayOf("OFF", "DELAY", "ECHO", "REVERB", "TAPE SPEED", "ROBOT", "FLANGER", "OVERDRIVE", "CHORUS", "LO-FI")
}

/** What one Home lane becomes. type: Inst.EMPTY / SAMPLE / DRUM; idx: the engine's sampler track or pad. */
class LaneAct(val lane: Int, val type: Int, val idx: Int, val track: StackTrack, val file: String?, val semis: Int, val loop: Boolean)

class VacuumPlanResult(val acts: List<LaneAct>, val notes: List<String>)

object VacuumPlan {
    const val EMPTY = 0
    const val SAMPLE = 1
    const val DRUM = 2

    /**
     * The plan. laneType / laneIdx: Home's lanes as they are (only the lanes after the eighth matter: the sampler
     * tracks and pads they show are theirs and are not taken). hasFile: is this sound in the library. findMy: the
     * library's sound for a sample of the app's, or null. loopFile: the loop's audio, already in the library, or null.
     */
    fun plan(
        song: StackSong, laneType: IntArray, laneIdx: IntArray, tracks: Int, pads: Int,
        hasFile: (String) -> Boolean, findMy: (StackTrack) -> String?, loopFile: String?
    ): VacuumPlanResult {
        val notes = ArrayList<String>()
        val freeTr = ArrayList<Int>()
        for (k in 0 until tracks) if ((8 until laneType.size).none { laneType[it] == SAMPLE && laneIdx[it] == k }) freeTr.add(k)
        val freePad = ArrayList<Int>()
        // (bank B first: the pads Home's own drum lanes start on are A1 to A8)
        for (k in (16 until pads) + (0 until minOf(16, pads))) if ((8 until laneType.size).none { laneType[it] == DRUM && laneIdx[it] == k }) freePad.add(k)
        // what each track wants: a file, and a sampler track or a pad
        val file = arrayOfNulls<String>(8)
        val semis = IntArray(8)
        val want = IntArray(8)
        for (t in 0 until 8) {
            val s = song.tracks[t]
            when (s.kind) {
                1 -> {
                    val m = StackSounds.MAP[s.name]
                    if (m == null) {
                        notes.add("T${t + 1} ${s.label}: the app does not know this sound. Lane ${t + 1} is left empty.")
                    } else if (!hasFile(m.file)) {
                        notes.add("T${t + 1} ${s.label}: \"${m.file.removeSuffix(".wav")}\" is not in the library (yet). Lane ${t + 1} is left empty.")
                    } else {
                        file[t] = m.file
                        semis[t] = m.semis
                        want[t] = if (s.isDrum) DRUM else SAMPLE
                        if (m.stand) notes.add("T${t + 1} ${s.label}: the app has no such sound. \"${m.file.removeSuffix(".wav")}\" stands in for it.")
                    }
                }
                2 -> {
                    val f = findMy(s)
                    if (f == null) notes.add("T${t + 1} MY ${s.slot} \"${s.name}\": this sample was not found in the app's library. Lane ${t + 1} is left empty.")
                    else {
                        file[t] = f
                        want[t] = SAMPLE
                    }
                }
                3 -> {
                    if (loopFile != null) {
                        file[t] = loopFile
                        want[t] = SAMPLE
                    } else if (song.hasLoop) notes.add("T${t + 1}: the loop could not be read. Lane ${t + 1} is left empty.")
                }
            }
        }
        // the sampler tracks: the loop first (only a sampler track loops), then in STACK's order; who finds none
        // left plays from a drum pad, as a hit, and its steps' notes are lost: said by name, never silently
        val idx = IntArray(8) { -1 }
        for (t in 0 until 8) if (want[t] == SAMPLE && song.tracks[t].kind == 3 && freeTr.isNotEmpty()) idx[t] = freeTr.removeAt(0)
        val toPads = ArrayList<String>()
        for (t in 0 until 8) if (want[t] == SAMPLE && idx[t] < 0) {
            if (freeTr.isNotEmpty()) idx[t] = freeTr.removeAt(0)
            else {
                want[t] = DRUM
                toPads.add("T${t + 1} ${song.tracks[t].label}")
            }
        }
        if (toPads.isNotEmpty()) notes.add(
            "The app has $tracks Sampler tracks. These play from Drum pads instead, as one-shots, and their steps' notes are lost: " +
                toPads.joinToString(", ") + "."
        )
        for (t in 0 until 8) if (want[t] == DRUM) {
            if (freePad.isNotEmpty()) idx[t] = freePad.removeAt(0)
            else {
                want[t] = EMPTY
                notes.add("T${t + 1} ${song.tracks[t].label}: no Drum pad is free. Lane ${t + 1} is left empty.")
            }
        }
        val acts = ArrayList<LaneAct>()
        for (t in 0 until 8) {
            val s = song.tracks[t]
            val ty = if (idx[t] < 0) EMPTY else want[t]
            acts.add(LaneAct(t, ty, maxOf(0, idx[t]), s, if (ty == EMPTY) null else file[t], semis[t], s.kind == 3))
            if (ty == EMPTY) continue
            // what does not come across, said per track
            val lost = ArrayList<String>()
            val v = s.set
            if (ty == DRUM) {
                if (v[StackTrack.PITCH] != 0) lost.add("PITCH")
                if (v[StackTrack.FILTER] < -3 || v[StackTrack.FILTER] > 3) lost.add("FILTER")
                for (q in 0..1) if (v[StackTrack.FX_A + q] != 0 && (v[StackTrack.FX_AK + q] < -3 || v[StackTrack.FX_AK + q] > 3))
                    lost.add(StackSounds.FX_NAME[v[StackTrack.FX_A + q].coerceIn(0, 9)])
                if (v[StackTrack.ED_START] != 0 || v[StackTrack.ED_LEN] != 100 || v[StackTrack.ED_REV] != 0) lost.add("EDIT")
            } else {
                for (q in 0..1) if (v[StackTrack.FX_A + q] == 4 && (v[StackTrack.FX_AK + q] < -3 || v[StackTrack.FX_AK + q] > 3)) lost.add("TAPE SPEED")
                if (v[StackTrack.FIT] == 1 || v[StackTrack.FIT] == 2) lost.add("FIT under a bar")
            }
            if (v[StackTrack.ENV_A] != 0 || v[StackTrack.ENV_R] != -1 || v[StackTrack.ENV_S] != 100 || v[StackTrack.ENV_V] != 75) lost.add("ENV")
            if (v[StackTrack.LFO_A] != 0) lost.add("LFO")
            if (v[StackTrack.ARP_R] != 0) lost.add("ARP")
            if (v[StackTrack.ED_LOOP] != 0) lost.add("EDIT LOOP")
            if (lost.isNotEmpty()) notes.add("T${t + 1} ${s.label}: not carried over (the app has no such thing here): " + lost.distinct().joinToString(", ") + ".")
        }
        if (song.swing != 50) notes.add("SWING ${song.swing}%: the app has no swing. The steps are straight.")
        return VacuumPlanResult(acts, notes)
    }

    /** STACK's FILTER knob -> the app's Low-pass (0) or High-pass (1) and its Cutoff 0..1; null: off. */
    fun filter(f: Int): Pair<Int, Float>? {
        if (f in -3..3) return null
        val hz = if (f < 0) 16000.0 * Math.pow(2.0, -(-f - 3) * 0.14) else 30.0 * Math.pow(2.0, (f - 3) * 0.15)
        return Pair(if (f < 0) 0 else 1, (ln(hz / 40.0) / ln(450.0)).toFloat().coerceIn(0f, 1f))
    }

    /** STACK's LEVEL (80 where it starts; the gain is its square) -> a Sampler track's volume (0.8 where it starts). */
    fun trackVolume(level: Int): Float = (level * level / 8000f).coerceIn(0f, 1f)
    /** -> a Drum pad's volume (1 where it starts). */
    fun padVolume(level: Int): Float = (level * level / 6400f).coerceIn(0f, 1f)
}
