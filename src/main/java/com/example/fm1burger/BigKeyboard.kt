package com.example.fm1burger

import android.app.Dialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 3.32: what the full-screen keyboard was opened from (its "Lane sound").
 * A sample lane gives its sample (C4 plays it as it is); an instrument lane is played with [on] / [off].
 */
abstract class KeyLane(val name: String) {
    /** true: [sample] is played by the keyboard's own voices, pitched; false: [on] / [off] play the instrument */
    open val isSample: Boolean = false
    open fun sample(): FloatArray? = null
    /** the lane's own pitch, in semitones (added to the key) */
    open fun semis(): Int = 0
    /** the part of the sample (of [n] samples) that plays */
    open fun from(n: Int): Int = 0
    open fun to(n: Int): Int = n
    open fun on(note: Int, vel: Int) {}
    open fun off(note: Int) {}
    /** a key went down (MIDI note, velocity 1..127): the STEPS window writes it in its steps */
    open fun keyDown(note: Int, vel: Int) {}
    /** a short line shown at the top (STEP INPUT / REC) */
    open fun info(): String = ""
}

/**
 * 3.32: a full-screen keyboard to play: 1, 2 or 3 octaves of big keys, many fingers at once, SUSTAIN, PIANO / ORGAN,
 * and touch velocity (how hard the screen is hit) from the motion sensor, the finger's contact size and where on the
 * key it lands. It plays the lane it was opened from, the Maker Bank's Grand Piano or its organ, through the engine's
 * keyboard voices.
 */
class BigKeyboard(private val a: MainActivity, private val lane: KeyLane, startOct: Int = 3) {
    private val ui = a.ui
    private val e = a.engine

    private var octs = sOcts
    private var oct = startOct.coerceIn(OCT_MIN, OCT_MAX)
    private var sound = 0                   // 0 the lane, 1 Grand Piano, 2 Organ
    private var organMode = false
    private var sustain = false
    private var touch = sTouch              // 0 soft, 1 normal, 2 hard
    private var useAcc = sAcc
    private var useSize = sSize
    private var usePos = sPos
    private var closed = false
    private var saidNoSound = false
    private var prevOri = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var oriSet = false

    private val keys = Keys(a)
    private val velText = ui.text("velocity: –", 14f, Theme.YELLOW, true)
    private val infoText = ui.text("", 12f, Theme.DIM)
    private val rangeText = ui.text("", 13f, Theme.TEXT, true)
    private val soundBtns = ArrayList<Button>()
    private val octBtns = ArrayList<Button>()
    private lateinit var modeBtn: Button
    private lateinit var touchBtn: Button
    private lateinit var accBtn: Button
    private lateinit var sizeBtn: Button
    private lateinit var posBtn: Button
    private lateinit var susBtn: Button

    // the notes that wait for SUSTAIN to be let go: the engine's voices by their id, the lane's instrument by its note
    private var nextId = 1
    private val sus = IntArray(64)
    private var susN = 0
    private val fingers = IntArray(128)
    private val susNote = BooleanArray(128)

    // the organ's loop (ORGAN mode holds it)
    private var oLa = 0
    private var oLb = 0
    private var oXf = 0

    // ---- the motion sensor: the last moments of it, written by its own thread ----
    private val sm = a.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accel: Sensor? = try { sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) } catch (ex: Exception) { null }
    private var accThread: HandlerThread? = null
    private var listening = false
    private val accLock = Any()
    private val accT = LongArray(RING)
    private val accX = FloatArray(RING)
    private val accY = FloatArray(RING)
    private val accZ = FloatArray(RING)
    private var accW = 0
    private var accCount = 0
    private var accPeak = 6f                // the hardest hit lately (m/s²): the top of the scale follows the player
    private var szMin = Float.MAX_VALUE
    private var szMax = 0f

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(ev: SensorEvent) {
            if (ev.values.size < 3) return
            val t = System.nanoTime()
            synchronized(accLock) {
                accT[accW] = t
                accX[accW] = ev.values[0]
                accY[accW] = ev.values[1]
                accZ[accW] = ev.values[2]
                accW = (accW + 1) % RING
                if (accCount < RING) accCount++
            }
        }

        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
    }

    private fun sensorOn() {
        if (closed || listening) return
        val m = sm ?: return
        val s = accel ?: return
        val th = HandlerThread("burger-keys-accel")
        th.start()
        val hd = Handler(th.looper)
        var ok = try {
            m.registerListener(listener, s, SensorManager.SENSOR_DELAY_FASTEST, hd)
        } catch (ex: Exception) {
            false
        }
        if (!ok) {
            // (Android 12+ may refuse the fastest rate): the game rate is fast enough to see a hit
            try {
                ok = m.registerListener(listener, s, SensorManager.SENSOR_DELAY_GAME, hd)
            } catch (ex: Exception) {
                ok = false
            }
        }
        if (ok) {
            listening = true
            accThread = th
        } else {
            th.quit()
        }
    }

    private fun sensorOff() {
        if (listening) {
            try { sm?.unregisterListener(listener) } catch (ex: Exception) {}
            listening = false
        }
        accThread?.quit()
        accThread = null
        synchronized(accLock) { accCount = 0 }
    }

    /** How hard the device was shaken around the touch at [t] (ns): the peak change of the acceleration, -1 = not known. */
    private fun jolt(t: Long): Float {
        val from = t - BEFORE_NS
        val to = t + AFTER_NS
        var n = 0
        var x0 = 0f; var x1 = 0f; var y0 = 0f; var y1 = 0f; var z0 = 0f; var z1 = 0f
        synchronized(accLock) {
            for (q in 1..accCount) {
                val i = ((accW - q) % RING + RING) % RING
                val ts = accT[i]
                if (ts < from) break
                if (ts > to) continue
                val x = accX[i]; val y = accY[i]; val z = accZ[i]
                if (n == 0) {
                    x0 = x; x1 = x; y0 = y; y1 = y; z0 = z; z1 = z
                } else {
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                    if (z < z0) z0 = z
                    if (z > z1) z1 = z
                }
                n++
            }
        }
        if (n < 2) return -1f
        val dx = x1 - x0; val dy = y1 - y0; val dz = z1 - z0
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** The velocity (1..127) of a hit: the shake [t], the contact [size], how low on the key [yf] (0 top .. 1 bottom). */
    private fun velocityOf(t: Long, size: Float, yf: Float): Int {
        var sum = 0f
        var w = 0f
        if (useAcc && listening) {
            val j = jolt(t)
            if (j >= 0f) {
                accPeak = maxOf(accPeak * 0.99f, j)
                val hi = accPeak.coerceIn(3f, 14f)
                val x = (ln((j.coerceAtLeast(ACC_LO)) / ACC_LO) / ln(hi / ACC_LO)).coerceIn(0f, 1f)
                sum += 3f * x
                w += 3f
            }
        }
        if (useSize && size > 0f) {
            if (size < szMin) szMin = size
            if (size > szMax) szMax = size
            val span = szMax - szMin
            // (a screen that always says the same size tells nothing: it stays out)
            if (span > 0.15f * szMax) {
                sum += 1.5f * ((size - szMin) / span).coerceIn(0f, 1f)
                w += 1.5f
                // the calibration follows the player slowly
                szMin += span * 0.003f
                szMax -= span * 0.003f
            }
        }
        if (usePos) {
            sum += yf.coerceIn(0f, 1f)
            w += 1f
        }
        if (w <= 0f) return 100
        var x = (sum / w).coerceIn(0f, 1f)
        x = when (touch) {
            0 -> x.pow(0.55f)       // soft: a light hit is already loud
            2 -> x.pow(1.7f)        // hard: it takes a real hit
            else -> x
        }
        return (1 + Math.round(x * 126f)).coerceIn(1, 127)
    }

    // ---------------- the notes ----------------

    /** A key goes down. Returns what [stop] needs: > 0 an engine voice, < 0 the lane's instrument, 0 nothing. */
    private fun start(note: Int, vel: Int, write: Boolean): Int {
        if (note < 0 || note > 127) return 0
        if (write) {
            velText.text = "velocity: $vel"
            try { lane.keyDown(note, vel) } catch (ex: Exception) {}
            infoText.text = lane.info()
        }
        val v = if (organMode) 127 else vel
        if (sound == 1) {
            val set = piano
            if (set != null && set.isNotEmpty()) {
                var best = set[0]
                for (s in set) if (Math.abs(s.note - note) < Math.abs(best.note - note)) best = s
                return voice(best.d, note - best.note, v, 0, best.d.size, false)
            }
        } else if (sound == 2) {
            val o = organ
            if (o != null) return voice(o.d, note - o.note, v, 0, o.d.size, organMode)
        }
        if (lane.isSample) {
            val d = lane.sample()
            if (d == null || d.size < 2) {
                if (!saidNoSound) {
                    saidNoSound = true
                    ui.toast("This lane has no sound yet. Pick Grand Piano or Organ.")
                }
                return 0
            }
            return voice(d, lane.semis() + note - 60, v, lane.from(d.size), lane.to(d.size), false)
        }
        lane.on(note, v)
        fingers[note]++
        susNote[note] = false
        return -(note + 1)
    }

    private fun voice(d: FloatArray, semis: Int, vel: Int, from: Int, to: Int, loop: Boolean): Int {
        val id = nextId
        nextId = if (nextId >= 1_000_000_000) 1 else nextId + 1
        val x = (vel / 127f).coerceIn(0f, 1f)
        // louder and brighter with the velocity: the low-pass opens from about 1.5 kHz to fully open
        val gain = if (organMode) 0.7f else 0.06f + 0.94f * x.pow(1.6f)
        val fc = 1500.0 * 2.0.pow(3.6 * x)
        val lp = if (vel >= 127) 1f else (1.0 - exp(-6.2831853 * fc / Cfg.SR)).toFloat()
        e.keyOn(id, d, 2.0.pow(semis / 12.0), gain, lp, if (organMode) 30f else 150f, from, to,
            if (loop) oLa else 0, if (loop) oLb else 0, if (loop) oXf else 0)
        return id
    }

    /** A key goes up (or the finger slides off it). */
    private fun stop(h: Int) {
        if (h > 0) {
            if (sustain) {
                if (susN >= sus.size) {
                    e.keyOff(sus[0])
                    System.arraycopy(sus, 1, sus, 0, sus.size - 1)
                    susN = sus.size - 1
                }
                sus[susN++] = h
            } else e.keyOff(h)
        } else if (h < 0) {
            val n = -h - 1
            if (n !in 0..127) return
            if (fingers[n] > 0) fingers[n]--
            if (fingers[n] == 0) {
                if (sustain) susNote[n] = true else lane.off(n)
            }
        }
    }

    /** SUSTAIN is let go: the notes that waited end. */
    private fun sustainOff() {
        for (i in 0 until susN) e.keyOff(sus[i])
        susN = 0
        for (n in 0..127) {
            if (susNote[n]) {
                susNote[n] = false
                if (fingers[n] == 0) lane.off(n)
            }
        }
    }

    // ---------------- the sounds ----------------

    private fun pickSound(w: Int) {
        if (w == 0) {
            sound = 0
            paint()
            return
        }
        if ((w == 1 && piano != null) || (w == 2 && organ != null)) {
            useSound(w)
            return
        }
        // the Maker Bank's files, read away from the UI thread
        val store = a.store
        Thread {
            var ok = false
            try {
                if (w == 1) {
                    val l = ArrayList<Smp>()
                    for (o in 1..5) {
                        val d = store.loadEngine("Grand Piano C$o.wav")
                        if (d != null && d.size > 64) l.add(Smp(d, 12 * (o + 1)))
                    }
                    if (l.isNotEmpty()) {
                        piano = l.toTypedArray()
                        ok = true
                    }
                } else {
                    for (n in ORGANS) {
                        val d = store.loadEngine(n)
                        if (d != null && d.size > 64) {
                            organ = Smp(d, 60)
                            ok = true
                            break
                        }
                    }
                }
            } catch (ex: Throwable) {
                ok = false
            }
            val got = ok
            a.runOnUiThread {
                if (!closed) {
                    if (got) useSound(w)
                    else {
                        ui.toast((if (w == 1) "Grand Piano" else "The organ") +
                            " is not in the Maker Bank on this device (its sounds install at the first start): Lane sound.")
                        sound = 0
                        paint()
                    }
                }
            }
        }.start()
    }

    private fun useSound(w: Int) {
        sound = w
        if (w == 2) {
            organLoop(organ?.d?.size ?: 0)
            organMode = true            // the organ holds; MODE puts it back
        } else if (w == 1) {
            organMode = false
        }
        paint()
    }

    /** A stable middle part of the organ sample (a whole number of C4's periods, so the loop keeps its pitch). */
    private fun organLoop(n: Int) {
        oLa = 0
        oLb = 0
        oXf = 0
        if (n < Cfg.SR / 2) return
        val period = Cfg.SR / 261.6256
        val start = (n * 0.35).toInt()
        val len = Math.round(Math.floor(n * 0.4 / period) * period).toInt()
        if (len < 2048 || start + len > n) return
        oLa = start
        oLb = start + len
        oXf = minOf((Cfg.SR * 0.04).toInt(), len / 4, start)
    }

    // ---------------- the window ----------------

    fun show(onClose: (() -> Unit)? = null) {
        val root = ui.col()
        root.setBackgroundColor(Theme.BG)
        root.setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4))
        val dlg = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        // the sound, the last hit
        val r1 = ui.row()
        r1.addView(ui.button("✕ CLOSE") { dlg.dismiss() }, ui.w(0f, 2))
        val names = arrayOf("Lane sound: " + lane.name, "Grand Piano", "Organ")
        for (i in names.indices) {
            val b = ui.button(names[i]) { pickSound(i) }
            b.maxLines = 1
            soundBtns.add(b)
            r1.addView(b, ui.w(0f, 2))
        }
        velText.setPadding(ui.dp(10), 0, ui.dp(10), 0)
        r1.addView(velText, ui.w(0f, 2))
        r1.addView(infoText, ui.w(0f, 2))
        val h1 = HorizontalScrollView(a)
        h1.isHorizontalScrollBarEnabled = false
        h1.addView(r1)
        root.addView(h1, ui.fullWidth(0))

        // the keys shown, how they answer
        val r2 = ui.row()
        for (n in 1..3) {
            val b = ui.button("$n OCT") {
                octs = n
                sOcts = n
                paint()
            }
            octBtns.add(b)
            r2.addView(b, ui.w(0f, 2))
        }
        r2.addView(ui.button("OCT −") { if (oct > OCT_MIN) { oct--; paint() } }, ui.w(0f, 2))
        rangeText.gravity = Gravity.CENTER
        rangeText.setPadding(ui.dp(6), 0, ui.dp(6), 0)
        r2.addView(rangeText, ui.w(0f, 2))
        r2.addView(ui.button("OCT +") { if (oct < OCT_MAX) { oct++; paint() } }, ui.w(0f, 2))
        modeBtn = ui.button("MODE: PIANO") {
            organMode = !organMode
            paint()
        }
        r2.addView(modeBtn, ui.w(0f, 2))
        touchBtn = ui.button("TOUCH: normal") {
            touch = (touch + 1) % 3
            sTouch = touch
            paint()
        }
        r2.addView(touchBtn, ui.w(0f, 2))
        accBtn = ui.button("ACC") {
            if (accel == null) ui.toast("This device has no motion sensor: SIZE and POS make the velocity.")
            else {
                useAcc = !useAcc
                sAcc = useAcc
                paint()
            }
        }
        r2.addView(accBtn, ui.w(0f, 2))
        sizeBtn = ui.button("SIZE") {
            useSize = !useSize
            sSize = useSize
            paint()
        }
        r2.addView(sizeBtn, ui.w(0f, 2))
        posBtn = ui.button("POS") {
            usePos = !usePos
            sPos = usePos
            paint()
        }
        r2.addView(posBtn, ui.w(0f, 2))
        val h2 = HorizontalScrollView(a)
        h2.isHorizontalScrollBarEnabled = false
        h2.addView(r2)
        root.addView(h2, ui.fullWidth(0))
        root.addView(ui.text("Hit harder: louder and brighter. The velocity comes from ACC (the device's motion sensor feels the hit), " +
            "SIZE (the finger's contact) and POS (low on a key is louder). TOUCH soft: a light hit is already loud; hard: it takes a real hit.",
            10f, Theme.FAINT), ui.fullWidth(0))

        // SUSTAIN under the left thumb, the keys
        val body = ui.row()
        susBtn = ui.button("SUSTAIN") {
            sustain = !sustain
            if (!sustain) sustainOff()
            paint()
        }
        susBtn.textSize = 12f
        susBtn.setPadding(0, 0, 0, 0)
        body.addView(susBtn, LinearLayout.LayoutParams(ui.dp(84), LinearLayout.LayoutParams.MATCH_PARENT).also {
            it.marginEnd = ui.dp(6)
        })
        body.addView(keys, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).also { it.topMargin = ui.dp(4) })

        dlg.setContentView(root)
        dlg.setOnDismissListener {
            closed = true
            keys.release()
            sustain = false
            sustainOff()
            for (n in 0..127) {
                if (fingers[n] > 0) {
                    fingers[n] = 0
                    lane.off(n)
                }
            }
            e.keysAllOff()
            sensorOff()
            if (oriSet) {
                try { a.requestedOrientation = prevOri } catch (ex: Exception) {}
            }
            onClose?.invoke()
        }
        // landscape: MainActivity handles the turn itself (configChanges), so nothing is made again
        try {
            prevOri = a.requestedOrientation
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            oriSet = true
        } catch (ex: Exception) {
        }
        infoText.text = lane.info()
        paint()
        dlg.show()
        sensorOn()
    }

    private fun paint() {
        for (i in soundBtns.indices) ui.styleButton(soundBtns[i], i == sound, Theme.ACCENT)
        for (i in octBtns.indices) ui.styleButton(octBtns[i], i + 1 == octs, Theme.TEAL)
        rangeText.text = "C$oct – C${oct + octs}"
        modeBtn.text = if (organMode) "MODE: ORGAN" else "MODE: PIANO"
        ui.styleButton(modeBtn, true, if (organMode) Theme.PURPLE else Theme.BLUE)
        touchBtn.text = "TOUCH: " + TOUCH_NAMES[touch.coerceIn(0, 2)]
        ui.styleButton(touchBtn, touch != 1, Theme.YELLOW)
        accBtn.text = if (accel == null) "ACC –" else "ACC"
        ui.styleButton(accBtn, useAcc && accel != null, Theme.GREEN)
        ui.styleButton(sizeBtn, useSize, Theme.GREEN)
        ui.styleButton(posBtn, usePos, Theme.GREEN)
        susBtn.text = if (sustain) "SUSTAIN\nON" else "SUSTAIN"
        ui.styleButton(susBtn, sustain, Theme.RED)
        keys.invalidate()
    }

    /** The keys, drawn: from the C of [oct], [octs] octaves and the C above them. Every finger plays its own note. */
    private inner class Keys(ctx: Context) : View(ctx) {
        private inner class Ptr {
            var id = -1             // the pointer on it (-1: free)
            var key = -1
            var note = 0
            var h = 0               // what start() gave
            var pending = false     // the note waits for the hit to show in the motion sensor
            var up = false          // ... and the finger already left
            var vel = 100
            var size = 0f
            var yf = 0.5f
            var t = 0L
        }

        private val ptrs = Array(MAX_PTR) { Ptr() }
        private val fire = Array(MAX_PTR) { i -> Runnable { firePending(i) } }
        private val lit = IntArray(12 * 3 + 1)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val d = resources.displayMetrics.density
        private var hitH = 1f

        private fun whites(): Int = 7 * octs + 1

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            // the motion sensor only while the keyboard is on the screen
            if (visibility == View.VISIBLE) sensorOn() else sensorOff()
        }

        /** The key under (x, y), 0 = the lower C; hitH = the height of that key. */
        private fun keyAt(x0: Float, y0: Float): Int {
            val w = width.toFloat()
            val hh = height.toFloat()
            if (w <= 0f || hh <= 0f) return -1
            val n = whites()
            val ww = w / n
            val x = x0.coerceIn(0f, w - 1f)
            val y = y0.coerceIn(0f, hh)
            if (y < hh * BLACK_H) {
                // a black key sits on the line after white key i
                val i = ((x + ww / 2f) / ww).toInt() - 1
                if (i in 0 until n - 1 && HAS_BLACK[i % 7] && Math.abs(x - (i + 1) * ww) <= ww * BLACK_W / 2f) {
                    hitH = hh * BLACK_H
                    return i / 7 * 12 + WHITE_SEMI[i % 7] + 1
                }
            }
            val i = (x / ww).toInt().coerceIn(0, n - 1)
            hitH = hh
            return i / 7 * 12 + WHITE_SEMI[i % 7]
        }

        private fun find(id: Int): Ptr? {
            for (q in ptrs) if (q.id == id) return q
            return null
        }

        private fun sizeOf(ev: MotionEvent, i: Int): Float {
            val s = ev.getTouchMajor(i)
            return if (s > 0f) s else ev.getSize(i) * 1000f
        }

        private fun light(key: Int, by: Int) {
            if (key in lit.indices) lit[key] = (lit[key] + by).coerceAtLeast(0)
        }

        private fun down(ev: MotionEvent, i: Int) {
            val id = ev.getPointerId(i)
            if (find(id) != null) return
            val y = ev.getY(i)
            val key = keyAt(ev.getX(i), y)
            if (key < 0) return
            val note = 12 * (oct + 1) + key
            if (note > 127) return
            var slot = -1
            for (k in ptrs.indices) if (ptrs[k].id == -1 && !ptrs[k].pending) { slot = k; break }
            if (slot < 0) return
            val q = ptrs[slot]
            q.id = id
            q.key = key
            q.note = note
            q.h = 0
            q.up = false
            q.size = sizeOf(ev, i)
            q.yf = (y / hitH).coerceIn(0f, 1f)
            // when the finger landed, on the motion sensor's clock
            q.t = System.nanoTime() - (SystemClock.uptimeMillis() - ev.eventTime).coerceIn(0L, 100L) * 1_000_000L
            light(key, 1)
            if (useAcc && listening) {
                // the hit shows in the motion sensor a little after the touch: the note waits for it
                q.pending = true
                postDelayed(fire[slot], WAIT_MS)
            } else {
                q.pending = false
                q.vel = velocityOf(q.t, q.size, q.yf)
                q.h = start(q.note, q.vel, true)
            }
        }

        private fun firePending(slot: Int) {
            val q = ptrs[slot]
            if (!q.pending) return
            q.pending = false
            if (closed) return
            q.vel = velocityOf(q.t, q.size, q.yf)
            q.h = start(q.note, q.vel, true)
            if (q.up) {
                // a tap shorter than the wait: the note, and its end at once
                stop(q.h)
                q.h = 0
                q.up = false
            }
        }

        private fun move(ev: MotionEvent, i: Int) {
            val q = find(ev.getPointerId(i)) ?: return
            if (q.pending) {
                // the contact grows while the finger lands
                val s = sizeOf(ev, i)
                if (s > q.size) q.size = s
            }
            val key = keyAt(ev.getX(i), ev.getY(i))
            if (key < 0 || key == q.key) return
            val note = 12 * (oct + 1) + key
            if (note > 127) return
            // the finger slid to another key: the old note ends, the new one starts (as hard as the hit was)
            light(q.key, -1)
            light(key, 1)
            q.key = key
            q.note = note
            if (!q.pending) {
                stop(q.h)
                q.h = start(note, q.vel, false)
            }
        }

        private fun up(id: Int) {
            val q = find(id) ?: return
            light(q.key, -1)
            if (q.pending) q.up = true
            else {
                stop(q.h)
                q.h = 0
            }
            q.id = -1
        }

        /** Every finger off (the window closes, the touch was taken away). */
        fun release() {
            for (k in ptrs.indices) {
                val q = ptrs[k]
                removeCallbacks(fire[k])
                if (q.id != -1 && !q.pending) stop(q.h)
                q.id = -1
                q.pending = false
                q.up = false
                q.h = 0
            }
            java.util.Arrays.fill(lit, 0)
            invalidate()
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    down(ev, ev.actionIndex)
                }
                MotionEvent.ACTION_MOVE -> for (i in 0 until ev.pointerCount) move(ev, i)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(ev.getPointerId(ev.actionIndex))
                MotionEvent.ACTION_CANCEL -> release()
            }
            invalidate()
            return true
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val hh = height.toFloat()
            if (w <= 0f || hh <= 0f) return
            val n = whites()
            val ww = w / n
            val rad = 5f * d
            // the white keys
            for (i in 0 until n) {
                val key = i / 7 * 12 + WHITE_SEMI[i % 7]
                r.set(i * ww + d, 0f, (i + 1) * ww - d, hh - d)
                p.style = Paint.Style.FILL
                p.color = if (lit[key] > 0) Theme.YELLOW else Theme.TEXT
                c.drawRoundRect(r, rad, rad, p)
                if (i % 7 == 0) {
                    p.color = Theme.BG
                    p.textSize = 13f * d
                    p.textAlign = Paint.Align.CENTER
                    p.typeface = Typeface.DEFAULT_BOLD
                    c.drawText("C${oct + i / 7}", (i + 0.5f) * ww, hh - 9f * d, p)
                }
            }
            // the black keys, over them
            val bw = ww * BLACK_W
            val bh = hh * BLACK_H
            for (i in 0 until n - 1) {
                if (!HAS_BLACK[i % 7]) continue
                val key = i / 7 * 12 + WHITE_SEMI[i % 7] + 1
                val cx = (i + 1) * ww
                r.set(cx - bw / 2f, 0f, cx + bw / 2f, bh)
                p.style = Paint.Style.FILL
                p.color = if (lit[key] > 0) Theme.ACCENT else Theme.BG
                c.drawRoundRect(r, rad, rad, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = d
                p.color = Theme.LINE
                c.drawRoundRect(r, rad, rad, p)
            }
            p.style = Paint.Style.FILL
        }
    }

    private class Smp(val d: FloatArray, val note: Int)

    private companion object {
        const val OCT_MIN = 0
        const val OCT_MAX = 6
        const val MAX_PTR = 10
        const val RING = 512
        const val WAIT_MS = 14L                     // the note waits this long for the hit to reach the motion sensor
        const val BEFORE_NS = 40_000_000L           // the shake is looked for from 40 ms before the touch ...
        const val AFTER_NS = 30_000_000L            // ... to what has come in since (the wait, and a little for late events)
        const val ACC_LO = 0.35f                    // (m/s²) under this a hit is the softest
        const val BLACK_W = 0.6f
        const val BLACK_H = 0.6f
        val WHITE_SEMI = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        val HAS_BLACK = booleanArrayOf(true, true, false, true, true, true, false)
        val TOUCH_NAMES = arrayOf("soft", "normal", "hard")
        val ORGANS = arrayOf("Full Organ C4.wav", "Gospel C4.wav", "Jazz Perc C4.wav", "Organ C4.wav")

        // the Maker Bank's sounds, read once
        @Volatile var piano: Array<Smp>? = null
        @Volatile var organ: Smp? = null

        // how the keyboard was left (until the app closes)
        var sOcts = 2
        var sTouch = 1
        var sAcc = true
        var sSize = true
        var sPos = true
    }
}
