package com.example.fm1burger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** One knob that can be automated. Values are 0..1. */
class AutoTarget(val key: String, val name: String, val get: () -> Float, val set: (Float) -> Unit)

/**
 * Knob automation: every knob below has a lane of up to 64 steps (the pattern length).
 * A step with a value moves the knob there (with a short glide), a step without one keeps it.
 * A lane with no values at all leaves the knob alone, so the knob works by hand as before.
 */
class Automation(e: Engine) {

    val targets: Array<AutoTarget>

    init {
        val list = ArrayList<AutoTarget>()
        val an = e.analog
        val bs = e.bass
        list.add(AutoTarget("syn_cut", "Analog synth · cutoff", { an.cutoff }, { an.cutoff = it }))
        list.add(AutoTarget("syn_res", "Analog synth · peak", { an.res }, { an.res = it }))
        list.add(AutoTarget("syn_env", "Analog synth · EG int", { an.filtEnv }, { an.filtEnv = it }))
        list.add(AutoTarget("syn_drv", "Analog synth · drive", { an.drive }, { an.drive = it }))
        list.add(AutoTarget("syn_dfb", "Analog synth · delay feedback", { an.delayFb }, { an.delayFb = it.coerceAtMost(0.95f) }))
        list.add(AutoTarget("bass_cut", "Bassline · cutoff", { bs.cutoff }, { bs.cutoff = it }))
        list.add(AutoTarget("bass_res", "Bassline · resonance", { bs.res }, { bs.res = it }))
        list.add(AutoTarget("bass_env", "Bassline · env mod", { bs.envMod }, { bs.envMod = it }))
        list.add(AutoTarget("bass_dec", "Bassline · decay", { bs.decay }, { bs.decay = it }))
        for (i in 0 until Cfg.TRACKS) {
            val t = e.tracks[i]
            list.add(AutoTarget("t${i}_vol", "Sampler ${i + 1} · volume", { t.volume }, { t.volume = it }))
            list.add(AutoTarget("t${i}_lp", "Sampler ${i + 1} · low-pass", { if (t.fx.on[0]) t.fx.a[0] else 1f }, {
                t.fx.a[0] = it
                t.fx.on[0] = it < 0.99f
            }))
        }
        val dfx = e.drumFx
        list.add(AutoTarget("drum_vol", "Drums · volume", { e.padVol }, { e.padVol = it }))
        list.add(AutoTarget("drum_lp", "Drums · low-pass", { if (dfx.on[0]) dfx.a[0] else 1f }, {
            dfx.a[0] = it
            dfx.on[0] = it < 0.99f
        }))
        list.add(AutoTarget("drum_heavy", "Drums · HEAVY", { e.drumHeavy.amount }, { e.drumHeavy.amount = it }))
        list.add(AutoTarget("gtr_r_tone", "Rhythm guitar · tone", { e.rhythmGtr.tone }, { e.rhythmGtr.tone = it }))
        list.add(AutoTarget("gtr_f_tone", "Riff guitar · tone", { e.riffGtr.tone }, { e.riffGtr.tone = it }))
        list.add(AutoTarget("master", "Master volume", { e.masterVol }, { e.masterVol = it }))
        targets = list.toTypedArray()
    }

    /** lanes[target][step], NaN = no value on that step. */
    val lanes = Array(targets.size) { FloatArray(Cfg.STEPS) { Float.NaN } }
    @Volatile var enabled = true

    // recording: while the finger is on the slider, every step writes its value into the lane
    @Volatile var recLane = -1
    @Volatile var recValue = 0f
    @Volatile var recTouch = false

    private val target = FloatArray(targets.size) { Float.NaN }
    private val cur = FloatArray(targets.size) { Float.NaN }

    fun used(k: Int): Boolean = lanes[k].any { !it.isNaN() }

    fun clear(k: Int) {
        lanes[k].fill(Float.NaN)
        target[k] = Float.NaN
        cur[k] = Float.NaN
    }

    /** Audio thread, on every step of the pattern. */
    fun step(s: Int, playing: Boolean) {
        if (s !in 0 until Cfg.STEPS) return
        val rl = recLane
        if (playing && recTouch && rl in lanes.indices) lanes[rl][s] = recValue.coerceIn(0f, 1f)
        if (!enabled) return
        for (k in lanes.indices) {
            val v = lanes[k][s]
            if (!v.isNaN() && !(recTouch && k == rl)) {
                target[k] = v
                if (cur[k].isNaN()) cur[k] = targets[k].get()
            }
        }
    }

    /** Audio thread, every block: glide the automated knobs to their values (no zipper noise). */
    fun block() {
        if (!enabled) return
        for (k in target.indices) {
            val t = target[k]
            if (t.isNaN()) continue
            val c = cur[k]
            val d = t - c
            if (kotlin.math.abs(d) < 0.0005f) {
                if (c != t) {
                    cur[k] = t
                    targets[k].set(t)
                }
                continue
            }
            val n = c + d * 0.3f
            cur[k] = n
            targets[k].set(n)
        }
    }

    /** Stop: forget the glides (the knobs stay where they are). */
    fun stopped() {
        target.fill(Float.NaN)
        cur.fill(Float.NaN)
    }

    /** The pattern got longer: copy the lanes like the notes. */
    fun extend(old: Int, n: Int) {
        if (old <= 0) return
        for (l in lanes) for (k in old until n) l[k] = l[k % old]
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        for ((k, t) in targets.withIndex()) {
            if (!used(k)) continue
            val a = JSONArray()
            for (s in 0 until Cfg.STEPS) {
                val v = lanes[k][s]
                if (!v.isNaN()) {
                    a.put(s)
                    a.put(Math.round(v * 1000.0) / 1000.0)
                }
            }
            o.put(t.key, a)
        }
        o.put("_enabled", enabled)
        return o
    }

    fun fromJson(o: JSONObject?) {
        for (l in lanes) l.fill(Float.NaN)
        stopped()
        if (o == null) return
        enabled = o.optBoolean("_enabled", true)
        for ((k, t) in targets.withIndex()) {
            val a = o.optJSONArray(t.key) ?: continue
            var i = 0
            while (i + 1 < a.length()) {
                val s = a.optInt(i, -1)
                val v = a.optDouble(i + 1, Double.NaN)
                if (s in 0 until Cfg.STEPS && !v.isNaN()) lanes[k][s] = v.toFloat().coerceIn(0f, 1f)
                i += 2
            }
        }
    }
}

/** One bar of an automation lane: drag to draw values; in erase mode drag to remove them. */
class AutoLaneView(ctx: Context, private val auto: Automation) : View(ctx) {
    var lane = 0
    var erase = false
    var playStep = -1
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    var onEdit: (() -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cw = w / Pat.BAR
        val base = Pat.base()
        val l = auto.lanes[lane]
        p.style = Paint.Style.FILL
        p.color = Theme.BG
        c.drawRect(0f, 0f, w, h, p)
        // the value held from before this bar
        var held = Float.NaN
        for (s in 0 until base) if (!l[s].isNaN()) held = l[s]
        if (held.isNaN()) for (s in Pat.len - 1 downTo base) if (!l[s].isNaN()) { held = l[s]; break }
        for (k in 0 until Pat.BAR) {
            val s = base + k
            p.color = if ((k / 4) % 2 == 0) Theme.PANEL2 else Theme.PANEL
            c.drawRect(k * cw + 1f, 0f, (k + 1) * cw - 1f, h, p)
            val v = l[s]
            if (!v.isNaN()) {
                held = v
                p.color = Theme.TEAL
                c.drawRect(k * cw + 3f, h - v * h, (k + 1) * cw - 3f, h, p)
            } else if (!held.isNaN()) {
                p.color = Theme.alpha(Theme.TEAL, 70)
                c.drawRect(k * cw + 3f, h - held * h - 2f, (k + 1) * cw - 3f, h - held * h + 2f, p)
            }
        }
        val ps = playStep - base
        if (playStep >= 0 && ps in 0 until Pat.BAR) {
            p.color = Color.argb(60, 255, 255, 255)
            c.drawRect(ps * cw, 0f, (ps + 1) * cw, h, p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val k = (e.x / (width.toFloat() / Pat.BAR)).toInt().coerceIn(0, Pat.BAR - 1)
                val s = Pat.base() + k
                auto.lanes[lane][s] = if (erase) Float.NaN else (1f - e.y / height).coerceIn(0f, 1f)
                invalidate()
                onEdit?.invoke()
            }
        }
        return true
    }
}

/** The Automation page: pick a knob, draw its lane per bar, or record it by moving the slider while playing. */
class AutomationScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    private val e = a.engine
    private val auto = e.auto
    override val root = ScrollView(a)
    private val content = ui.col()
    private val prep = PrepareBar(a, true) { Engine.PREP_NONE }
    private val laneView = AutoLaneView(a, auto)
    private val pickBtn: Button
    private val recBtn: Button
    private val eraseBtn: Button
    private val onBtn: Button
    private val info: TextView
    private val usedText: TextView
    private val bar: SeekBar
    private var rec = false

    init {
        content.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(88))
        root.addView(content)
        content.addView(ui.title("Automation"))
        content.addView(ui.text(
            "Make a knob move by itself with the pattern. Pick a knob, then draw its values on the bar below " +
                "(pick the bar with the bar buttons), or press REC and move the slider while it plays.",
            12f, Theme.DIM), ui.fullWidth(4))
        prep.onPage = { laneView.invalidate() }
        prep.pButton.visibility = View.GONE
        content.addView(prep.view, ui.fullWidth(6))

        pickBtn = ui.button("") { pick() }
        content.addView(pickBtn, ui.fullWidth(6))
        info = ui.text("", 12f, Theme.TEXT)
        content.addView(info, ui.fullWidth(4))

        laneView.onEdit = { refreshInfo() }
        content.addView(laneView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(200)).also {
            it.topMargin = ui.dp(6)
        })

        val r = ui.row()
        recBtn = ui.button("● REC") {
            rec = !rec
            auto.recLane = if (rec) laneView.lane else -1
            if (!rec) auto.recTouch = false
            refreshButtons()
            ui.toast(if (rec) "REC: press Play, then hold and move the slider" else "REC off")
        }
        eraseBtn = ui.button("Erase") {
            laneView.erase = !laneView.erase
            refreshButtons()
        }
        r.addView(recBtn, ui.w(1f, 3))
        r.addView(eraseBtn, ui.w(1f, 3))
        r.addView(ui.button("Clear lane") {
            auto.clear(laneView.lane)
            laneView.invalidate()
            refreshInfo()
        }, ui.w(1f, 3))
        content.addView(r, ui.fullWidth(8))

        content.addView(ui.text("Knob (moves it now; with REC on it is recorded while playing)", 12f, Theme.DIM), ui.fullWidth(8))
        bar = SeekBar(a)
        bar.max = 1000
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = p / 1000f
                auto.recValue = v
                auto.targets[laneView.lane].set(v)
                refreshInfo()
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                auto.recValue = (s?.progress ?: 0) / 1000f
                if (rec) auto.recTouch = true
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                auto.recTouch = false
            }
        })
        content.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(48)))

        onBtn = ui.button("") {
            auto.enabled = !auto.enabled
            if (!auto.enabled) auto.stopped()
            refreshButtons()
        }
        content.addView(onBtn, ui.fullWidth(10))
        usedText = ui.text("", 12f, Theme.DIM)
        content.addView(usedText, ui.fullWidth(6))
        content.addView(ui.text(
            "The lanes belong to the project (they play in every part). A step with no value keeps the knob where it was; " +
                "a lane with nothing in it leaves the knob to you.", 11f, Theme.DIM), ui.fullWidth(6))
    }

    private fun pick() {
        val names = Array(auto.targets.size) { k -> (if (auto.used(k)) "● " else "") + auto.targets[k].name }
        android.app.AlertDialog.Builder(a)
            .setTitle("Automate which knob?")
            .setItems(names) { _, w ->
                laneView.lane = w
                if (rec) auto.recLane = w
                onShow()
            }
            .show()
    }

    private fun refreshButtons() {
        ui.styleButton(recBtn, rec, Theme.RED)
        ui.styleButton(eraseBtn, laneView.erase, Theme.YELLOW)
        onBtn.text = if (auto.enabled) "Automation: ON (all lanes)" else "Automation: OFF (knobs by hand only)"
        ui.styleButton(onBtn, auto.enabled, Theme.GREEN)
    }

    private fun refreshInfo() {
        val t = auto.targets[laneView.lane]
        val n = auto.lanes[laneView.lane].take(Pat.len).count { !it.isNaN() }
        info.text = "Now ${(t.get() * 100).toInt()}% · $n of ${Pat.len} steps have a value"
        val used = auto.targets.indices.filter { auto.used(it) }.map { auto.targets[it].name }
        usedText.text = if (used.isEmpty()) "No knob is automated yet." else "Automated: " + used.joinToString(", ")
    }

    override fun onShow() {
        pickBtn.text = "Knob: ${auto.targets[laneView.lane].name}  ▾"
        bar.progress = (auto.targets[laneView.lane].get() * 1000).toInt().coerceIn(0, 1000)
        refreshButtons()
        refreshInfo()
        laneView.invalidate()
    }

    override fun onTick() {
        prep.refresh()
        laneView.playStep = if (e.playing) e.curStep else -1
        if (e.playing && auto.used(laneView.lane) && !auto.recTouch) {
            val v = (auto.targets[laneView.lane].get() * 1000).toInt().coerceIn(0, 1000)
            if (v != bar.progress) bar.progress = v
        }
        if (e.playing) refreshInfo()
    }
}
