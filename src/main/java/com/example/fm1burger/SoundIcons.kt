package com.example.fm1burger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The drawn icons of the sound groups (Kick, Snare & Clap, Hats ... and one box for the user's own boxes).
 * Same look as the instrument icons of the Home lanes (InstIconView): line drawings in one colour on a dark tile.
 */
object SoundIcons {
    const val KICK = "Kick"
    const val SNARE = "Snare & Clap"
    const val HATS = "Hats"
    const val TOMS = "Toms & Cymbals"
    const val SYNTHS = "Synths"
    const val KITS = "Drum Kits"
    const val PIANO = "Piano · Flute · Sax"
    const val FM = "FM & Synth"
    const val LOOPS = "Loops"
    const val VOICE = "Voice"
    const val RECS = "My Recordings"
    const val OTHER = "Other"
    /** the icon of a box the user made */
    const val BOX = "Box"

    /** The group a sound sits in, from its file name. */
    fun groupOf(ctx: Context, store: Store, name: String): String {
        if (name.startsWith("Grand Piano ") || name.startsWith("Flute ") || name.startsWith("Tenor Sax ")) return PIANO
        if (Boxes.synthEngine(ctx, name) != null) return SYNTHS
        return when (val c = store.categoryOf(name)) {
            "Recorded" -> RECS
            KICK, SNARE, HATS, TOMS, FM, LOOPS, VOICE -> c
            else -> OTHER
        }
    }

    fun color(group: String): Int = when (group) {
        KICK -> Theme.RED
        SNARE -> Theme.ACCENT
        HATS -> Theme.YELLOW
        TOMS -> Theme.STEEL
        SYNTHS -> Theme.PURPLE
        KITS -> Theme.PINK
        PIANO -> Theme.BLUE
        FM -> Theme.TEAL
        LOOPS -> Theme.GREEN
        VOICE -> Theme.PINK
        RECS -> Theme.RED
        BOX -> Theme.YELLOW
        else -> Theme.DIM
    }

    /**
     * Draws the icon of a group around (cx, cy); s is the half size of the drawing, d the screen density,
     * col the colour of the lines.
     */
    fun draw(c: Canvas, group: String, cx: Float, cy: Float, s: Float, d: Float, col: Int,
             p: Paint, r: RectF, path: Path) {
        p.color = col
        p.strokeWidth = 2.2f * d
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.pathEffect = null
        p.style = Paint.Style.STROKE
        when (group) {
            KICK -> {
                // a bass drum from the front: the head, the beater mark, two legs
                c.drawCircle(cx, cy - s * 0.1f, s * 0.8f, p)
                c.drawLine(cx - s * 0.55f, cy + s * 0.5f, cx - s * 0.85f, cy + s * 0.95f, p)
                c.drawLine(cx + s * 0.55f, cy + s * 0.5f, cx + s * 0.85f, cy + s * 0.95f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy - s * 0.1f, s * 0.24f, p)
            }
            SNARE -> {
                // a snare drum from the side: flat shell with lugs, one stick
                r.set(cx - s, cy - s * 0.5f, cx + s, cy)
                c.drawOval(r, p)
                c.drawLine(cx - s, cy - s * 0.25f, cx - s, cy + s * 0.5f, p)
                c.drawLine(cx + s, cy - s * 0.25f, cx + s, cy + s * 0.5f, p)
                r.set(cx - s, cy + s * 0.25f, cx + s, cy + s * 0.75f)
                c.drawArc(r, 0f, 180f, false, p)
                p.strokeWidth = 1.5f * d
                c.drawLine(cx - s * 0.45f, cy + s * 0.05f, cx - s * 0.45f, cy + s * 0.68f, p)
                c.drawLine(cx + s * 0.45f, cy + s * 0.05f, cx + s * 0.45f, cy + s * 0.68f, p)
                p.strokeWidth = 2.2f * d
                c.drawLine(cx + s * 0.1f, cy - s * 0.3f, cx + s * 0.9f, cy - s, p)
            }
            HATS -> {
                // a hi-hat: two cymbals facing each other on a rod
                path.reset()
                path.moveTo(cx - s, cy - s * 0.12f)
                path.quadTo(cx, cy - s * 0.8f, cx + s, cy - s * 0.12f)
                path.close()
                c.drawPath(path, p)
                path.reset()
                path.moveTo(cx - s, cy + s * 0.1f)
                path.quadTo(cx, cy + s * 0.78f, cx + s, cy + s * 0.1f)
                path.close()
                c.drawPath(path, p)
                c.drawLine(cx, cy - s, cx, cy - s * 0.5f, p)
                c.drawLine(cx, cy + s * 0.48f, cx, cy + s, p)
            }
            TOMS -> {
                // a tom and a cymbal on its stand
                r.set(cx - s, cy, cx + s * 0.1f, cy + s * 0.35f)
                c.drawOval(r, p)
                c.drawLine(cx - s, cy + s * 0.175f, cx - s, cy + s * 0.75f, p)
                c.drawLine(cx + s * 0.1f, cy + s * 0.175f, cx + s * 0.1f, cy + s * 0.75f, p)
                r.set(cx - s, cy + s * 0.575f, cx + s * 0.1f, cy + s * 0.925f)
                c.drawArc(r, 0f, 180f, false, p)
                c.drawLine(cx + s * 0.55f, cy - s * 0.7f, cx + s * 0.55f, cy + s * 0.95f, p)
                p.strokeWidth = 3.4f * d
                c.drawLine(cx + s * 0.05f, cy - s * 0.52f, cx + s, cy - s * 0.88f, p)
            }
            SYNTHS -> {
                // a wave over a keyboard
                path.reset()
                path.moveTo(cx - s, cy - s * 0.55f)
                path.quadTo(cx - s * 0.5f, cy - s * 1.1f, cx, cy - s * 0.55f)
                path.quadTo(cx + s * 0.5f, cy, cx + s, cy - s * 0.55f)
                c.drawPath(path, p)
                keys(c, cx, cy + s * 0.05f, cy + s * 0.9f, s, d, p, r)
            }
            KITS -> {
                // a drum kit: the bass drum with two toms on top
                c.drawCircle(cx, cy + s * 0.3f, s * 0.6f, p)
                c.drawLine(cx - s * 0.45f, cy + s * 0.75f, cx - s * 0.7f, cy + s, p)
                c.drawLine(cx + s * 0.45f, cy + s * 0.75f, cx + s * 0.7f, cy + s, p)
                r.set(cx - s * 0.8f, cy - s * 0.85f, cx - s * 0.06f, cy - s * 0.5f)
                c.drawOval(r, p)
                r.set(cx + s * 0.06f, cy - s * 0.85f, cx + s * 0.8f, cy - s * 0.5f)
                c.drawOval(r, p)
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy + s * 0.3f, s * 0.16f, p)
            }
            PIANO -> {
                // a note over piano keys
                keys(c, cx, cy + s * 0.05f, cy + s * 0.9f, s, d, p, r)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.2f * d
                c.drawLine(cx - s * 0.05f, cy - s * 0.42f, cx - s * 0.05f, cy - s, p)
                c.drawLine(cx - s * 0.05f, cy - s, cx + s * 0.35f, cy - s * 0.75f, p)
                p.style = Paint.Style.FILL
                r.set(cx - s * 0.45f, cy - s * 0.58f, cx - s * 0.02f, cy - s * 0.26f)
                c.drawOval(r, p)
            }
            FM -> {
                // a wave that gets faster: frequency modulation
                path.reset()
                val n = 40
                for (k in 0..n) {
                    val t = k / n.toFloat()
                    val x = cx - s + 2f * s * t
                    val y = cy - s * 0.75f * sin(2.0 * PI * (1.0 * t + 2.6 * t * t)).toFloat()
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                c.drawPath(path, p)
            }
            LOOPS -> {
                // an arrow going round
                r.set(cx - s * 0.8f, cy - s * 0.8f, cx + s * 0.8f, cy + s * 0.8f)
                c.drawArc(r, 40f, 290f, false, p)
                val ex = cx + s * 0.69f
                val ey = cy - s * 0.4f
                c.drawLine(ex, ey, ex + s * 0.07f, ey - s * 0.41f, p)
                c.drawLine(ex, ey, ex - s * 0.39f, ey - s * 0.14f, p)
            }
            VOICE -> {
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
            RECS -> {
                // the record sign: a dot in a ring
                c.drawCircle(cx, cy, s * 0.85f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy, s * 0.42f, p)
            }
            BOX -> {
                // a box with its lid
                r.set(cx - s * 0.82f, cy - s * 0.2f, cx + s * 0.82f, cy + s * 0.85f)
                c.drawRect(r, p)
                r.set(cx - s, cy - s * 0.7f, cx + s, cy - s * 0.2f)
                c.drawRect(r, p)
                c.drawLine(cx - s * 0.28f, cy + s * 0.22f, cx + s * 0.28f, cy + s * 0.22f, p)
            }
            else -> {
                // other: two notes on a beam
                c.drawLine(cx - s * 0.3f, cy + s * 0.6f, cx - s * 0.3f, cy - s * 0.65f, p)
                c.drawLine(cx + s * 0.7f, cy + s * 0.35f, cx + s * 0.7f, cy - s * 0.9f, p)
                p.strokeWidth = 3.6f * d
                c.drawLine(cx - s * 0.3f, cy - s * 0.65f, cx + s * 0.7f, cy - s * 0.9f, p)
                p.style = Paint.Style.FILL
                r.set(cx - s * 0.8f, cy + s * 0.42f, cx - s * 0.27f, cy + s * 0.82f)
                c.drawOval(r, p)
                r.set(cx + s * 0.2f, cy + s * 0.17f, cx + s * 0.73f, cy + s * 0.57f)
                c.drawOval(r, p)
            }
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.2f * d
    }

    /** a small keyboard between top and bottom: four white keys, three black ones (leaves the paint on FILL) */
    private fun keys(c: Canvas, cx: Float, top: Float, bottom: Float, s: Float, d: Float, p: Paint, r: RectF) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.2f * d
        r.set(cx - s, top, cx + s, bottom)
        c.drawRoundRect(r, 3 * d, 3 * d, p)
        val kw = 2 * s / 4
        p.strokeWidth = 1.4f * d
        for (k in 1 until 4) c.drawLine(cx - s + k * kw, top, cx - s + k * kw, bottom, p)
        p.style = Paint.Style.FILL
        for (k in 1 until 4) {
            r.set(cx - s + k * kw - kw * 0.28f, top, cx - s + k * kw + kw * 0.28f, top + (bottom - top) * 0.58f)
            c.drawRect(r, p)
        }
    }
}

/** The icon of one sound group on a dark rounded tile (the picker's grid). */
class SoundIconView(ctx: Context) : View(ctx) {

    var group = SoundIcons.OTHER
        set(v) {
            field = v
            invalidate()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val d = resources.displayMetrics.density
        val col = SoundIcons.color(group)
        p.pathEffect = null
        p.style = Paint.Style.FILL
        p.color = Theme.alpha(col, 40)
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, 12 * d, 12 * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1 * d
        p.color = Theme.alpha(col, 150)
        c.drawRoundRect(r.apply { inset(0.5f * d, 0.5f * d) }, 12 * d, 12 * d, p)
        SoundIcons.draw(c, group, w / 2f, h / 2f, min(w, h) * 0.30f, d, col, p, r, path)
    }
}
