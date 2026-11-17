แก้เสร็จแล้วครับ (ผมตรวจวงเล็บและโครงสร้างโค้ดแล้ว แต่ยังไม่เคยคอมไพล์ ถ้า build แดงส่ง e: มาได้เลย)
สิ่งที่เปลี่ยน
R = อัดจากไมค์ กดครั้งเดียวเริ่มอัดทันที (ใช้ไมค์นอกที่เสียบช่อง 3.5 mm ถ้ามี ไม่มีก็ใช้ไมค์ในตัว) กดอีกครั้งหยุด กดค้างเพื่อเลือกไมค์ USB หรือตั้งความยาว
S = สุ่มเสียง FM-1 กดครั้งเดียวเริ่มบันทึกทันทีจาก USB/line สูงสุด 60 วินาที กดอีกครั้งหยุด แล้วตัดช่วงเงียบหัวท้ายให้เอง และเปิดหน้า Edit ให้ตกแต่งต่อ
L = โหลดเสียง เลือกจากคลังในแอป หรือเลือก "Browse this device" เพื่อหยิบไฟล์ WAV จากเครื่อง
SYNC = กดแล้วเลือกเอง ไม่เดาอัตโนมัติอีก: FOLLOW (FM-1 เป็นหัวหน้า) / LEAD (แอปเป็นหัวหน้า ปุ่ม ▶ สั่ง FM-1 ด้วย) / ปิด มีข้อความบอกสถานะชัดเจนใต้แถบ BPM
ไอคอน เป็นรูปเบอร์เกอร์
ไฟล์ที่ต้องแก้/เพิ่ม (ทำตามลำดับ ทีละไฟล์)
Recorder.kt (แทนที่ทั้งไฟล์)
RecordFlow.kt (แทนที่)
TracksScreen.kt (แทนที่)
MainActivity.kt (แทนที่)
ไฟล์ไอคอนใหม่ 4 ไฟล์ (สร้างใหม่)
AndroidManifest.xml (แทนที่ ทำเป็นอันสุดท้าย)
ระหว่างทำ Actions อาจแดงชั่วคราวได้ ไม่ต้องสนใจ ให้ดูแค่รอบสุดท้ายหลังไฟล์ที่ 6
วิธีแทนที่ไฟล์: เปิดไฟล์ใน repo → กดดินสอ → กดค้างในช่องโค้ดแล้วเลือก Select all → ลบ → วางโค้ดใหม่ → Commit changes ก็อปปี้เฉพาะในกล่องโค้ด ระวังอย่าให้ภาษาไทยติดมา
ไฟล์ 1: Recorder.kt
(เปิด src/main/java/com/example/fm1burger/Recorder.kt แล้วแทนที่ทั้งไฟล์)
package com.example.fm1burger

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs

class RecResult(val data: FloatArray, val rate: Int, val rawPeak: Float)

/** Records from a chosen input (internal mic, external mic, USB, 3.5 mm) as mono floats. */
class Recorder(ctx: Context) {

    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile var running = false
        private set
    @Volatile var seconds = 0.0
        private set
    @Volatile var peak = 0f
        private set
    @Volatile var clipped = false
        private set
    @Volatile var waitingGate = false
        private set

    fun inputs(): List<AudioDeviceInfo> {
        val allowed = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG
        )
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type in allowed }
            .sortedBy { if (it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) 0 else 1 }
    }

    fun label(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Internal mic"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB: ${d.productName}"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "3.5 mm input / external mic"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line in"
        else -> "Other: ${d.productName}"
    }

    /** The mic for the R button: an external 3.5 mm mic when plugged in, otherwise the internal mic. */
    fun defaultMic(): AudioDeviceInfo? {
        val l = inputs()
        return l.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
            ?: l.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    /** The input for the S button: the FM-1 over USB, or a line / 3.5 mm cable. */
    fun fm1Input(): AudioDeviceInfo? {
        val l = inputs()
        return l.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
            ?: l.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            ?: l.firstOrNull { it.type == AudioDeviceInfo.TYPE_LINE_ANALOG }
            ?: l.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
    }

    fun isUsbLike(d: AudioDeviceInfo): Boolean =
        d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            d.type == AudioDeviceInfo.TYPE_LINE_ANALOG

    fun stop() {
        running = false
    }

    /**
     * Starts recording on its own thread.
     * gate: when not null the recording only begins when gate() returns true.
     * maxSeconds: stops by itself after this much recorded audio.
     * onDone is called from the recording thread (post to the UI thread yourself).
     * Returns an error text, or null when started.
     */
    fun start(
        dev: AudioDeviceInfo?,
        maxSeconds: Double,
        gate: (() -> Boolean)?,
        onDone: (RecResult?) -> Unit
    ): String? {
        if (running) return "Already recording"
        val usb = dev != null && isUsbLike(dev)
        val pref = if (usb) 48000 else 44100
        val rates = dev?.sampleRates ?: IntArray(0)
        val rate = when {
            rates.isEmpty() || pref in rates -> pref
            44100 in rates -> 44100
            48000 in rates -> 48000
            else -> rates.maxOrNull() ?: 44100
        }
        val chs = dev?.channelCounts ?: IntArray(0)
        val stereo = !(chs.isEmpty() || 1 in chs)
        val mask = if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val nch = if (stereo) 2 else 1

        val minBuf = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return "This input does not support recording at $rate Hz"
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 4)
                .build()
        } catch (e: Exception) {
            return "Cannot open input: ${e.message}"
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return "Input could not be opened"
        }
        if (dev != null) rec.setPreferredDevice(dev)

        val maxFrames = (maxSeconds * rate).toInt().coerceAtLeast(rate / 4)
        running = true
        seconds = 0.0
        peak = 0f
        clipped = false
        waitingGate = gate != null

        val th = Thread {
            val out = FloatArray(maxFrames)
            var n = 0
            var rawPeak = 0f
            val buf = ShortArray(minBuf / 2 + nch)
            var open = gate == null
            try {
                rec.startRecording()
                while (running && n < maxFrames) {
                    val got = rec.read(buf, 0, buf.size)
                    if (got < 0) break
                    if (got == 0) continue
                    if (!open) {
                        if (gate != null && gate()) {
                            open = true
                            waitingGate = false
                        } else {
                            continue
                        }
                    }
                    var i = 0
                    var pk = 0f
                    while (i + nch <= got && n < maxFrames) {
                        var s = 0f
                        for (c in 0 until nch) s += buf[i + c] / 32768f
                        s /= nch
                        out[n++] = s
                        val a = abs(s)
                        if (a > pk) pk = a
                        i += nch
                    }
                    if (pk > rawPeak) rawPeak = pk
                    peak = pk
                    if (pk >= 0.98f) clipped = true
                    seconds = n.toDouble() / rate
                }
            } catch (e: Exception) {
            }
            try {
                rec.stop()
            } catch (e: Exception) {
            }
            rec.release()
            running = false
            waitingGate = false
            onDone(if (n > 0) RecResult(out.copyOf(n), rate, rawPeak) else null)
        }
        th.isDaemon = true
        th.start()
        return null
    }
}
