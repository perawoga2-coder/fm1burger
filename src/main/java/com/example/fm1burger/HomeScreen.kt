package com.example.fm1burger

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The instruments a lane on the Home page can show. */
object Inst {
    const val EMPTY = 0
    const val SAMPLE = 1
    const val DRUM = 2
    const val BASS = 3
    const val SYNTH = 4
    const val VOICE = 5
    const val SONG = 6
    const val AMBIENT = 7
    const val RHYTHM = 8
    const val RIFF = 9
    const val COUNT = 10

    val NAMES = arrayOf("Empty", "Sampler", "Drum", "Bassline", "Analog synth", "Voice", "Song rec", "New Ambient", "Rhythm gtr", "Riff gtr")

    fun color(t: Int): Int = when (t) {
        SAMPLE -> Theme.ACCENT
        DRUM -> Theme.RED
        BASS -> Theme.BLUE
        SYNTH -> Theme.PURPLE
        VOICE -> Theme.PINK
        SONG -> Theme.GREEN
        AMBIENT -> Theme.TEAL
        RHYTHM -> Theme.STEEL
        RIFF -> Theme.LIME
        else -> Theme.FAINT
    }

    /** How many sources of this kind the engine has (4 sample tracks, 8 drum pads, one of the rest). */
    fun slots(t: Int): Int = when (t) {
        SAMPLE -> Cfg.TRACKS
        DRUM -> Cfg.PADS
        EMPTY -> 0
        else -> 1
    }
}

/** What each of the 17 lanes on the Home page shows. Saved with the project. */
class LaneSetup {
    companion object {
        const val N = 19
    }

    val type = IntArray(N)
    val idx = IntArray(N)

    init {
        reset()
    }

    /** Default: sample 1-4, drum pads 1-8, bass, synth, voice, song recording, New Ambient = 17 lanes. */
    fun reset() {
        var k = 0
        for (i in 0 until Cfg.TRACKS) { type[k] = Inst.SAMPLE; idx[k] = i; k++ }
        for (i in 0 until 8) { type[k] = Inst.DRUM; idx[k] = i; k++ }
        for (t in intArrayOf(Inst.BASS, Inst.SYNTH, Inst.SONG, Inst.AMBIENT, Inst.RHYTHM, Inst.RIFF)) {   // (3.19: no Voice; sampler 5)
            if (k < N) { type[k] = t; idx[k] = 0; k++ }
        }
        while (k < N) { type[k] = Inst.EMPTY; idx[k] = 0; k++ }
    }

    /** Tap on a lane number: the next instrument. A sample / drum lane takes a free slot when there is one. */
    fun cycle(lane: Int) {
        var t = (type[lane] + 1) % Inst.COUNT
        if (t == Inst.VOICE) t = (t + 1) % Inst.COUNT     // (3.19: the Voice page is gone: sampler track 5 records voices)
        type[lane] = t
        val n = Inst.slots(t)
        idx[lane] = 0
        if (n > 1) {
            for (s in 0 until n) {
                val used = (0 until N).any { it != lane && type[it] == t && idx[it] == s }
                if (!used) {
                    idx[lane] = s
                    break
                }
            }
        }
    }

    fun label(lane: Int): String {
        val t = type[lane]
        return if (t == Inst.DRUM) "Drum ${Cfg.padName(idx[lane])}"
        else if (Inst.slots(t) > 1) "${Inst.NAMES[t]} ${idx[lane] + 1}" else Inst.NAMES[t]
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        val ta = JSONArray()
        val ia = JSONArray()
        for (i in 0 until N) {
            ta.put(type[i])
            ia.put(idx[i])
        }
        o.put("type", ta)
        o.put("idx", ia)
        return o
    }

    fun fromJson(o: JSONObject?) {
        if (o == null) return
        val ta = o.optJSONArray("type") ?: return
        val ia = o.optJSONArray("idx")
        for (i in 0 until min(N, ta.length())) {
            val t = ta.optInt(i, 0)
            type[i] = if (t in 0 until Inst.COUNT) t else Inst.EMPTY
            val n = Inst.slots(type[i])
            idx[i] = (ia?.optInt(i, 0) ?: 0).coerceIn(0, max(0, n - 1))
        }
        // 3.19: a Voice lane becomes sampler track 5 (when no lane shows it yet)
        for (i in 0 until N) if (type[i] == Inst.VOICE) {
            val used = (0 until N).any { type[it] == Inst.SAMPLE && idx[it] == Cfg.TRACKS - 1 }
            type[i] = if (used) Inst.EMPTY else Inst.SAMPLE
            idx[i] = Cfg.TRACKS - 1
        }
    }
}

/**
 * Home: one page with all 17 lanes.
 * Left: the lane number (tap again and again to choose the instrument; the color follows it),
 * then the instrument icon (tap it to open that instrument's own page),
 * then a picture of what the lane holds (waveform or pattern), and a mute button.
 */
class HomeScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    private val e = a.engine
    private val lanes = a.lanes
    override val root = ScrollView(a)
    private val content = ui.col()
    private val numBtns = ArrayList<Button>()
    private val icons = ArrayList<InstIconView>()
    private val views = ArrayList<LaneView>()
    private val muteBtns = ArrayList<Button>()
    private var lastStep = -2

    init {
        content.setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(32))
        root.addView(content)
        val head = ui.row()
        head.addView(ui.title("Home"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(ui.button("Reset lanes") {
            lanes.reset()
            refresh()
        }, ui.w(0f, 2))
        content.addView(head)
        val ver = try {
            a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: ""
        } catch (ex: Exception) {
            ""
        }
        content.addView(ui.text("Burger Studio Lab  v$ver", 11f, Theme.FAINT, true), ui.fullWidth(0))
        content.addView(
            ui.text(
                "Tap a number to change the instrument of that lane. " +
                    "Tap the icon to open the instrument's own page. Tap the picture to hear it. M = mute.",
                12f, Theme.DIM
            ), ui.fullWidth(4)
        )
        val list = ui.col()
        list.background = ui.bg(Theme.PANEL, 16, Theme.LINE, 1)
        list.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
        for (i in 0 until LaneSetup.N) list.addView(buildLane(i))
        content.addView(list, ui.fullWidth(10))
    }

    private fun buildLane(i: Int): View {
        val row = ui.row()
        row.setPadding(0, ui.dp(2), 0, ui.dp(2))

        val num = Button(a)
        num.text = "${i + 1}"
        num.textSize = 14f
        num.typeface = Ui.BOLD
        num.isAllCaps = false
        num.minWidth = 0
        num.minimumWidth = 0
        num.minHeight = 0
        num.minimumHeight = 0
        num.stateListAnimator = null
        num.setPadding(0, 0, 0, 0)
        num.gravity = Gravity.CENTER
        num.setOnClickListener {
            lanes.cycle(i)
            paintLane(i)
            ui.toast("Lane ${i + 1}: ${lanes.label(i)}")
        }
        row.addView(num, LinearLayout.LayoutParams(ui.dp(40), ui.dp(48)).also { it.marginEnd = ui.dp(5) })

        val icon = InstIconView(a)
        icon.setOnClickListener { openLane(i) }
        row.addView(icon, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).also { it.marginEnd = ui.dp(5) })

        val lv = LaneView(a, e, lanes, i)
        lv.setOnClickListener { hearLane(i) }
        row.addView(lv, LinearLayout.LayoutParams(0, ui.dp(48), 1f))

        val m = ui.button("M") { toggleMute(i) }
        m.minHeight = 0
        m.minimumHeight = 0
        m.setPadding(0, 0, 0, 0)
        row.addView(m, LinearLayout.LayoutParams(ui.dp(38), ui.dp(48)).also { it.marginStart = ui.dp(5) })

        numBtns.add(num)
        icons.add(icon)
        views.add(lv)
        muteBtns.add(m)
        return row
    }

    // ---------------- actions ----------------

    /** Tap on the icon: jump to the page where this instrument is made. */
    private fun openLane(i: Int) {
        val k = lanes.idx[i]
        when (lanes.type[i]) {
            Inst.SAMPLE -> {
                a.sel = k
                a.showTab(MainActivity.TAB_TRACKS)
            }
            Inst.DRUM -> a.showTab(MainActivity.TAB_PADS)
            Inst.BASS -> a.openSynth(0)
            Inst.SYNTH -> a.openSynth(1)
            Inst.VOICE -> a.showTab(MainActivity.TAB_TRACKS)
            Inst.SONG -> a.showTab(MainActivity.TAB_SONG)
            Inst.AMBIENT -> a.showTab(MainActivity.TAB_AMBIENT)
            Inst.RHYTHM -> a.openGuitar(0)
            Inst.RIFF -> a.openGuitar(1)
            else -> ui.toast("This lane is empty. Tap the number ${i + 1} to choose an instrument.")
        }
    }

    /** Tap on the picture: play the sound of this lane once (samples and drums). */
    private fun hearLane(i: Int) {
        val k = lanes.idx[i]
        when (lanes.type[i]) {
            Inst.SAMPLE -> {
                if (e.tracks[k].data == null) ui.toast("Sample ${k + 1} is empty. Tap the icon to load or record one.")
                else e.audition(k)
            }
            Inst.DRUM -> {
                if (e.pads[k].data == null) ui.toast("Drum pad ${Cfg.padName(k)} has no sound. Tap the icon to choose one.")
                else e.padHit(k, 1f)
            }
            Inst.AMBIENT -> e.ambient.callReq = true
            Inst.RHYTHM -> e.rhythmGtr.audition(MetalGuitar.CHUG, 0)
            Inst.RIFF -> e.riffGtr.audition(MetalGuitar.OPEN, 12)
            Inst.EMPTY -> ui.toast("Tap the number ${i + 1} to choose an instrument")
            else -> openLane(i)
        }
    }

    private fun isMuted(i: Int): Boolean {
        val k = lanes.idx[i]
        return when (lanes.type[i]) {
            Inst.SAMPLE -> e.tracks[k].mute
            Inst.DRUM -> e.pads[k].mute
            Inst.BASS -> e.bass.mute
            Inst.SYNTH -> e.analog.mute
            Inst.VOICE -> e.vocal.mute
            Inst.SONG -> e.bank.clipMute
            Inst.AMBIENT -> e.ambient.mute
            Inst.RHYTHM -> e.rhythmGtr.mute
            Inst.RIFF -> e.riffGtr.mute
            else -> false
        }
    }

    private fun toggleMute(i: Int) {
        val k = lanes.idx[i]
        val m = !isMuted(i)
        when (lanes.type[i]) {
            Inst.SAMPLE -> e.tracks[k].mute = m
            Inst.DRUM -> e.pads[k].mute = m
            Inst.BASS -> e.bass.mute = m
            Inst.SYNTH -> e.analog.mute = m
            Inst.VOICE -> e.vocal.mute = m
            Inst.SONG -> e.bank.clipMute = m
            Inst.AMBIENT -> e.ambient.mute = m
            Inst.RHYTHM -> e.rhythmGtr.mute = m
            Inst.RIFF -> e.riffGtr.mute = m
            else -> return
        }
        // several lanes can show the same instrument: repaint them all
        refresh()
    }

    // ---------------- drawing ----------------

    private fun paintLane(i: Int) {
        val t = lanes.type[i]
        val col = Inst.color(t)
        val num = numBtns[i]
        if (t == Inst.EMPTY) {
            num.background = ui.touchBg(ui.bg(Theme.PANEL2, 12, Theme.LINE, 1), 12)
            num.setTextColor(Theme.DIM)
        } else {
            num.background = ui.touchBg(ui.bg(col, 12), 12)
            num.setTextColor(Theme.onColor(col))
        }
        icons[i].kind = t
        val m = muteBtns[i]
        m.visibility = if (t == Inst.EMPTY) View.INVISIBLE else View.VISIBLE
        ui.styleButton(m, isMuted(i), Theme.RED)
        views[i].muted = isMuted(i)
        views[i].changed()
    }

    fun refresh() {
        for (i in 0 until LaneSetup.N) paintLane(i)
    }

    override fun onShow() {
        refresh()
    }

    private var muteKey = ""

    override fun onTick() {
        // a mute changed on another page (Mixer, Synth, Pads...): show it here too
        val mk = (0 until LaneSetup.N).joinToString("") { if (isMuted(it)) "1" else "0" }
        if (mk != muteKey) {
            muteKey = mk
            refresh()
        }
        val step = if (e.playing) e.curStep else -1
        val stepChanged = step != lastStep
        lastStep = step
        for (i in 0 until LaneSetup.N) {
            val v = views[i]
            // moving things: the play line, a playing sample, the live voice
            val live = when (lanes.type[i]) {
                Inst.SAMPLE -> e.tracks[lanes.idx[i]].playing
                Inst.VOICE -> e.vocal.on
                Inst.SONG -> e.playing
                Inst.AMBIENT -> e.ambient.active()
                Inst.RHYTHM -> e.rhythmGtr.ringing()
                Inst.RIFF -> e.riffGtr.ringing()
                else -> false
            }
            if (stepChanged || live) v.invalidate()
        }
    }
}

/** A small drawn picture of an instrument, on a dark rounded tile. */
class InstIconView(ctx: Context) : View(ctx) {

    var kind = Inst.EMPTY
        set(v) {
            field = v
            invalidate()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val path = Path()
    private val dash = DashPathEffect(floatArrayOf(6f, 6f), 0f)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val d = resources.displayMetrics.density
        val col = Inst.color(kind)
        // tile
        p.style = Paint.Style.FILL
        p.color = if (kind == Inst.EMPTY) Theme.PANEL2 else Theme.alpha(col, 40)
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, 12 * d, 12 * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1 * d
        p.color = if (kind == Inst.EMPTY) Theme.LINE else Theme.alpha(col, 150)
        c.drawRoundRect(r.apply { inset(0.5f * d, 0.5f * d) }, 12 * d, 12 * d, p)

        val cx = w / 2f
        val cy = h / 2f
        val s = min(w, h) * 0.30f   // half size of the drawing
        p.color = col
        p.strokeWidth = 2.2f * d
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.pathEffect = null
        when (kind) {
            Inst.SAMPLE -> {
                // a waveform
                p.style = Paint.Style.STROKE
                val hs = floatArrayOf(0.3f, 0.7f, 1f, 0.5f, 0.85f, 0.4f, 0.2f)
                for (k in hs.indices) {
                    val x = cx - s + k * (2 * s / (hs.size - 1))
                    c.drawLine(x, cy - s * hs[k], x, cy + s * hs[k], p)
                }
            }
            Inst.DRUM -> {
                // a drum with two sticks
                p.style = Paint.Style.STROKE
                r.set(cx - s, cy - s * 0.25f, cx + s, cy + s * 0.25f)
                c.drawOval(r, p)
                c.drawLine(cx - s, cy, cx - s, cy + s * 0.7f, p)
                c.drawLine(cx + s, cy, cx + s, cy + s * 0.7f, p)
                r.set(cx - s, cy + s * 0.45f, cx + s, cy + s * 0.95f)
                c.drawArc(r, 0f, 180f, false, p)
                c.drawLine(cx - s * 0.2f, cy - s * 0.1f, cx - s * 0.9f, cy - s * 0.95f, p)
                c.drawLine(cx + s * 0.2f, cy - s * 0.1f, cx + s * 0.9f, cy - s * 0.95f, p)
            }
            Inst.BASS -> {
                // a bass guitar: body, neck, head
                p.style = Paint.Style.FILL
                c.drawCircle(cx - s * 0.35f, cy + s * 0.45f, s * 0.42f, p)
                c.drawCircle(cx - s * 0.05f, cy + s * 0.15f, s * 0.32f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3f * d
                c.drawLine(cx - s * 0.1f, cy + s * 0.1f, cx + s * 0.8f, cy - s * 0.8f, p)
                p.strokeWidth = 5f * d
                c.drawLine(cx + s * 0.75f, cy - s * 0.75f, cx + s * 0.95f, cy - s * 0.95f, p)
                p.color = Theme.PANEL
                p.style = Paint.Style.FILL
                c.drawCircle(cx - s * 0.3f, cy + s * 0.4f, s * 0.11f, p)
            }
            Inst.SYNTH -> {
                // a keyboard: white keys with black keys on top
                p.style = Paint.Style.STROKE
                r.set(cx - s, cy - s * 0.65f, cx + s, cy + s * 0.65f)
                c.drawRoundRect(r, 3 * d, 3 * d, p)
                val kw = 2 * s / 5
                p.strokeWidth = 1.4f * d
                for (k in 1 until 5) c.drawLine(cx - s + k * kw, cy - s * 0.65f, cx - s + k * kw, cy + s * 0.65f, p)
                p.style = Paint.Style.FILL
                for (k in intArrayOf(1, 2, 4)) {
                    r.set(cx - s + k * kw - kw * 0.3f, cy - s * 0.65f, cx - s + k * kw + kw * 0.3f, cy + s * 0.1f)
                    c.drawRect(r, p)
                }
            }
            Inst.VOICE -> {
                // a microphone
                p.style = Paint.Style.FILL
                r.set(cx - s * 0.38f, cy - s, cx + s * 0.38f, cy + s * 0.25f)
                c.drawRoundRect(r, s * 0.38f, s * 0.38f, p)
                p.style = Paint.Style.STROKE
                r.set(cx - s * 0.65f, cy - s * 0.55f, cx + s * 0.65f, cy + s * 0.5f)
                c.drawArc(r, 20f, 140f, false, p)
                c.drawLine(cx, cy + s * 0.5f, cx, cy + s * 0.9f, p)
                c.drawLine(cx - s * 0.4f, cy + s * 0.9f, cx + s * 0.4f, cy + s * 0.9f, p)
            }
            Inst.SONG -> {
                // a cassette tape
                p.style = Paint.Style.STROKE
                r.set(cx - s, cy - s * 0.65f, cx + s, cy + s * 0.65f)
                c.drawRoundRect(r, 4 * d, 4 * d, p)
                c.drawCircle(cx - s * 0.42f, cy - s * 0.05f, s * 0.2f, p)
                c.drawCircle(cx + s * 0.42f, cy - s * 0.05f, s * 0.2f, p)
                path.reset()
                path.moveTo(cx - s * 0.55f, cy + s * 0.65f)
                path.lineTo(cx - s * 0.4f, cy + s * 0.38f)
                path.lineTo(cx + s * 0.4f, cy + s * 0.38f)
                path.lineTo(cx + s * 0.55f, cy + s * 0.65f)
                c.drawPath(path, p)
            }
            Inst.RHYTHM, Inst.RIFF -> {
                // a pointy metal guitar: V body, long neck, headstock
                p.style = Paint.Style.FILL
                path.reset()
                path.moveTo(cx - s * 0.95f, cy + s * 0.15f)
                path.lineTo(cx - s * 0.1f, cy + s * 0.95f)
                path.lineTo(cx - s * 0.05f, cy + s * 0.35f)
                path.lineTo(cx + s * 0.15f, cy + s * 0.15f)
                path.lineTo(cx - s * 0.35f, cy + s * 0.05f)
                path.close()
                c.drawPath(path, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3f * d
                c.drawLine(cx - s * 0.2f, cy + s * 0.4f, cx + s * 0.75f, cy - s * 0.55f, p)
                p.strokeWidth = 5f * d
                c.drawLine(cx + s * 0.7f, cy - s * 0.5f, cx + s * 0.95f, cy - s * 0.85f, p)
                if (kind == Inst.RIFF) {
                    // a lightning bolt for the lead
                    p.strokeWidth = 1.8f * d
                    c.drawLine(cx + s * 0.35f, cy + s * 0.25f, cx + s * 0.6f, cy + s * 0.5f, p)
                    c.drawLine(cx + s * 0.6f, cy + s * 0.5f, cx + s * 0.45f, cy + s * 0.55f, p)
                    c.drawLine(cx + s * 0.45f, cy + s * 0.55f, cx + s * 0.75f, cy + s * 0.85f, p)
                }
            }
            Inst.AMBIENT -> {
                // a little sky whale: body, tail, eye, and a sparkle
                p.style = Paint.Style.FILL
                r.set(cx - s * 0.95f, cy - s * 0.45f, cx + s * 0.45f, cy + s * 0.5f)
                c.drawOval(r, p)
                path.reset()
                path.moveTo(cx + s * 0.3f, cy)
                path.lineTo(cx + s, cy - s * 0.45f)
                path.lineTo(cx + s * 0.85f, cy + s * 0.05f)
                path.lineTo(cx + s, cy + s * 0.45f)
                path.close()
                c.drawPath(path, p)
                p.color = Theme.PANEL
                c.drawCircle(cx - s * 0.5f, cy - s * 0.08f, s * 0.1f, p)
                p.color = col
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.6f * d
                c.drawLine(cx - s * 0.2f, cy - s * 0.95f, cx - s * 0.2f, cy - s * 0.6f, p)
                c.drawLine(cx - s * 0.37f, cy - s * 0.78f, cx - s * 0.03f, cy - s * 0.78f, p)
            }
            else -> {
                // empty: a dashed plus
                p.style = Paint.Style.STROKE
                p.color = Theme.DIM
                p.pathEffect = dash
                c.drawLine(cx - s * 0.6f, cy, cx + s * 0.6f, cy, p)
                c.drawLine(cx, cy - s * 0.6f, cx, cy + s * 0.6f, p)
                p.pathEffect = null
            }
        }
    }
}

/**
 * The picture of one lane: the waveform of a sample / drum sound / recording,
 * the note pattern of the bass and synth, the live mic for the voice.
 * A light column shows the step that is playing.
 */
class LaneView(
    ctx: Context,
    private val e: Engine,
    private val lanes: LaneSetup,
    private val lane: Int
) : View(ctx) {

    var muted = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val d = resources.displayMetrics.density

    // waveform cache: min / max per screen column, rebuilt when the sound or the width changes
    private var cacheSrc: Any? = null
    private var cacheW = -1
    private var mn = FloatArray(0)
    private var mx = FloatArray(0)

    init {
        tp.textSize = 11f * d
        tp.typeface = Ui.MEDIUM
    }

    fun changed() {
        cacheSrc = null
        invalidate()
    }

    private fun cacheFloats(src: FloatArray, w: Int) {
        if (cacheSrc === src && cacheW == w) return
        cacheSrc = src
        cacheW = w
        mn = FloatArray(w)
        mx = FloatArray(w)
        if (src.isEmpty()) return
        for (x in 0 until w) {
            val i0 = (x.toLong() * src.size / w).toInt()
            val i1 = max(i0 + 1, ((x + 1).toLong() * src.size / w).toInt())
            val stride = max(1, (i1 - i0) / 32)
            var lo = 0f
            var hi = 0f
            var i = i0
            while (i < i1 && i < src.size) {
                val v = src[i]
                if (v < lo) lo = v
                if (v > hi) hi = v
                i += stride
            }
            mn[x] = lo
            mx[x] = hi
        }
    }

    /** Interleaved stereo 16 bit (the song recordings), shown as one mono waveform. */
    private fun cacheShorts(src: ShortArray, w: Int) {
        if (cacheSrc === src && cacheW == w) return
        cacheSrc = src
        cacheW = w
        mn = FloatArray(w)
        mx = FloatArray(w)
        val frames = src.size / 2
        if (frames == 0) return
        for (x in 0 until w) {
            val f0 = (x.toLong() * frames / w).toInt()
            val f1 = max(f0 + 1, ((x + 1).toLong() * frames / w).toInt())
            val stride = max(1, (f1 - f0) / 32)
            var lo = 0f
            var hi = 0f
            var f = f0
            while (f < f1 && f < frames) {
                val v = (src[f * 2] + src[f * 2 + 1]) / 65536f
                if (v < lo) lo = v
                if (v > hi) hi = v
                f += stride
            }
            mn[x] = lo
            mx[x] = hi
        }
    }

    private fun drawWave(c: Canvas, w: Float, h: Float, col: Int) {
        val mid = h / 2f
        p.style = Paint.Style.FILL
        p.color = col
        val n = min(mn.size, w.toInt())
        for (x in 0 until n) {
            val top = mid - mx[x].coerceIn(-1f, 1f) * mid * 0.85f
            val bot = mid - mn[x].coerceIn(-1f, 1f) * mid * 0.85f
            c.drawRect(x.toFloat(), top, x + 1f, max(bot, top + 1f), p)
        }
    }

    /** 16 step blocks: height = note (or level), for bass, synth and drums. */
    private fun drawSteps(c: Canvas, w: Float, h: Float, col: Int, on: (Int) -> Boolean, height: (Int) -> Float) {
        val cw = w / Pat.len
        p.style = Paint.Style.FILL
        for (s in 0 until Pat.len) {
            if (!on(s)) continue
            val hh = (h - 10 * d) * height(s).coerceIn(0.15f, 1f)
            p.color = col
            r.set(s * cw + 1.5f * d, h - 4 * d - hh, (s + 1) * cw - 1.5f * d, h - 4 * d)
            c.drawRoundRect(r, 3 * d, 3 * d, p)
        }
    }

    private fun label(c: Canvas, text: String, color: Int) {
        tp.color = color
        c.drawText(text, 8 * d, 14 * d, tp)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val t = lanes.type[lane]
        val k = lanes.idx[lane]
        val col = if (muted) Theme.FAINT else Inst.color(t)

        p.style = Paint.Style.FILL
        p.color = Theme.BG
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, 10 * d, 10 * d, p)

        // beat grid: a faint line every 4 steps
        p.color = Theme.PANEL2
        val nb = 4 * Pat.bars()
        for (b in 1 until nb) {
            p.color = if (b % 4 == 0) Theme.PANEL3 else Theme.PANEL2
            c.drawRect(w * b / nb - 0.5f, 0f, w * b / nb + 0.5f, h, p)
        }

        // the step that is playing
        val step = if (e.playing) e.curStep else -1
        if (step >= 0 && t != Inst.EMPTY) {
            p.color = Theme.alpha(Theme.TEXT, 22)
            c.drawRect(step * w / Pat.len, 0f, (step + 1) * w / Pat.len, h, p)
        }

        val wi = w.toInt()
        var name = lanes.label(lane)
        when (t) {
            Inst.SAMPLE -> {
                val tr = e.tracks[k]
                val dat = tr.data
                if (dat != null) {
                    cacheFloats(dat, wi)
                    drawWave(c, w, h, Theme.alpha(col, 200))
                    // the steps that trigger it, as small marks along the bottom
                    if (!tr.loop) {
                        p.color = col
                        for (s in 0 until Pat.len) {
                            if (tr.steps[s].on) c.drawRect(s * w / Pat.len.toFloat() + 2 * d, h - 4 * d, (s + 1) * w / Pat.len.toFloat() - 2 * d, h - 1.5f * d, p)
                        }
                    }
                    val f = tr.playFraction()
                    if (f >= 0f) {
                        p.color = Theme.TEXT
                        c.drawRect(f * w - d, 0f, f * w + d, h, p)
                    }
                    name += " · " + tr.sampleName + (if (tr.loop) " · loop" else "")
                } else {
                    name += " · empty"
                }
            }
            Inst.DRUM -> {
                val pad = e.pads[k]
                val dat = pad.data
                if (dat != null) {
                    cacheFloats(dat, wi)
                    drawWave(c, w, h, Theme.alpha(col, 70))
                    name += " · " + pad.name
                } else {
                    name += " · no sound"
                }
                drawSteps(c, w, h, col, { pad.on[it] }, { pad.lvl[it] })
            }
            Inst.BASS -> {
                val b = e.bass
                drawSteps(c, w, h, col, { b.on[it] }, { (b.note[it] + 2) / 26f })
            }
            Inst.SYNTH -> {
                val sq = e.analogSeq
                var lo = 127
                var hi = 0
                val top = IntArray(Pat.len) { st -> sq.notesAt(st).lastOrNull() ?: -1 }
                for (v in top) if (v >= 0) { lo = min(lo, v); hi = max(hi, v) }
                val span = max(12, hi - lo)
                drawSteps(c, w, h, col, { top[it] >= 0 }, { 0.25f + 0.75f * (top[it] - lo) / span.toFloat() })
                if (!sq.enabled) name += " · keyboard only"
            }
            Inst.VOICE -> {
                val v = e.vocal
                val n = VocalChannel.WF
                val cw = w / n
                val mid = h / 2f
                p.color = col
                val pos = v.wfPos
                for (i in 0 until n) {
                    val idx = (pos + i) % n
                    val top = mid - v.wfMax[idx].coerceIn(-1f, 1f) * mid * 0.85f
                    val bot = mid - v.wfMin[idx].coerceIn(-1f, 1f) * mid * 0.85f
                    c.drawRect(i * cw, top, i * cw + cw + 0.5f, max(bot, top + 1f), p)
                }
                name += if (v.on) " · mic on" else " · mic off"
            }
            Inst.SONG -> {
                val part = e.bank.parts[e.bank.cur]
                val clip = part.clip
                if (part.hasClip() && clip != null) {
                    cacheShorts(clip, wi)
                    drawWave(c, w, h, Theme.alpha(col, 200))
                    name += " · " + PartNames.NAMES[e.bank.cur] + " · ${part.clipBars} bars"
                } else {
                    name += " · nothing recorded in " + PartNames.NAMES[e.bank.cur]
                }
            }
            Inst.RHYTHM, Inst.RIFF -> {
                val gt = if (t == Inst.RHYTHM) e.rhythmGtr else e.riffGtr
                val n = MetalGuitar.WF
                val cw = w / n
                val mid = h / 2f
                p.color = Theme.alpha(col, 120)
                val pos = gt.wfPos
                for (i in 0 until n) {
                    val idx = (pos + i) % n
                    val top = mid - (gt.wfMax[idx] * 2f).coerceIn(-1f, 1f) * mid * 0.85f
                    val bot = mid - (gt.wfMin[idx] * 2f).coerceIn(-1f, 1f) * mid * 0.85f
                    c.drawRect(i * cw, top, i * cw + cw + 0.5f, max(bot, top + 1f), p)
                }
                drawSteps(c, w, h, col, { gt.art[it] == MetalGuitar.CHUG || gt.art[it] == MetalGuitar.OPEN },
                    { if (gt.art[it] == MetalGuitar.OPEN) 0.4f + gt.fret[it] / 24f else 0.25f })
                name += " · " + MetalGuitar.TUNINGS[gt.tuning.coerceIn(0, MetalGuitar.TUNINGS.size - 1)]
            }
            Inst.AMBIENT -> {
                val am = e.ambient
                val n = NewAmbient.WF
                val cw = w / n
                val mid = h / 2f
                p.color = col
                val pos = am.wfPos
                for (i in 0 until n) {
                    val idx = (pos + i) % n
                    val top = mid - (am.wfMax[idx] * 1.6f).coerceIn(-1f, 1f) * mid * 0.85f
                    val bot = mid - (am.wfMin[idx] * 1.6f).coerceIn(-1f, 1f) * mid * 0.85f
                    c.drawRect(i * cw, top, i * cw + cw + 0.5f, max(bot, top + 1f), p)
                }
                // its steps along the bottom
                for (s in 0 until Pat.len) {
                    if (am.steps[s]) c.drawRect(s * w / Pat.len.toFloat() + 2 * d, h - 4 * d, (s + 1) * w / Pat.len.toFloat() - 2 * d, h - 1.5f * d, p)
                }
                name += " · " + NewAmbient.NAMES[am.kind.coerceIn(0, NewAmbient.N_KIND - 1)] + (if (am.free) " · free" else "")
            }
            else -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = d
                p.color = Theme.LINE
                r.set(d, d, w - d, h - d)
                c.drawRoundRect(r, 10 * d, 10 * d, p)
                p.style = Paint.Style.FILL
                name = "Empty · tap the number to choose an instrument"
            }
        }
        if (muted && t != Inst.EMPTY) name += " · muted"
        label(c, TextUtils.ellipsize(name, android.text.TextPaint(tp), w - 16 * d, TextUtils.TruncateAt.END).toString(),
            if (t == Inst.EMPTY) Theme.FAINT else Theme.TEXT)
    }
}
