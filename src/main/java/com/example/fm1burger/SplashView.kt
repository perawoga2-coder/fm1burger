package com.example.fm1burger

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The retro 8-bit intro: first the company logo "MAKER HIP" (3 s), then "BURGER STUDIO" (5 s).
 *
 * Everything is drawn on a tiny low-resolution picture (about 128 pixels wide) and
 * blown up without smoothing, so every pixel shows as a crisp block like an old console.
 * It also runs at a choppy 15 frames per second on purpose, and plays a short chiptune.
 *
 * MAKER HIP: a hammer winds up and hits a gold block (flash, sparks, clunk),
 * the letters pop up, PRESENTS blinks in, then a 4 step fade to dark.
 *
 * BURGER STUDIO (times from the start of this scene):
 * 0.0 - 0.7 s  stars appear, a cursor blinks
 * 0.7 - 1.8 s  the burger drops in, layer by layer, with a blip for each one
 * 1.8 - 3.0 s  BURGER STUDIO types itself in
 * 3.0 - 4.3 s  fanfare: the title cycles through colors, GROOVEBOX blinks, the equalizer jumps
 * 4.3 - 5.0 s  the picture breaks into blocks and dissolves into the app
 *
 * Tap to skip.
 */
class SplashView(ctx: Context, private val onDone: () -> Unit) : View(ctx) {

    companion object {
        const val LINE1 = "BURGER"
        const val LINE2 = "STUDIO"
        const val TAGLINE = "GROOVEBOX"
        const val COMPANY = "MAKER HIP"
        const val PRESENTS = "PRESENTS"
        const val CO_MS = 3000L            // length of the company logo scene
        const val TOTAL_MS = CO_MS + 5000L
        private const val FRAME_MS = 66   // 15 frames per second, on purpose
        private const val PI_F = 3.1415927f

        // an NES-like palette
        private val C_BG = Color.parseColor("#0B0B1A")
        private val C_BUN = Color.parseColor("#E07818")
        private val C_BUN_HI = Color.parseColor("#F8B868")
        private val C_BUN_LO = Color.parseColor("#A85008")
        private val C_SEED = Color.parseColor("#FCFCFC")
        private val C_LETTUCE = Color.parseColor("#58D854")
        private val C_LETTUCE_LO = Color.parseColor("#00A800")
        private val C_CHEESE = Color.parseColor("#F8D830")
        private val C_PATTY = Color.parseColor("#883000")
        private val C_PATTY_HI = Color.parseColor("#B05020")
        private val C_CYAN = Color.parseColor("#3CBCFC")
        private val C_PINK = Color.parseColor("#F878F8")
        private val C_WHITE = Color.parseColor("#FCFCFC")
        private val C_GREY = Color.parseColor("#7C7C9C")
        private val C_GOLD = Color.parseColor("#F8B800")
        private val C_GOLD_HI = Color.parseColor("#FCE0A8")
        private val C_GOLD_LO = Color.parseColor("#AC7C00")
        private val C_STEEL = Color.parseColor("#BCBCBC")
        private val C_STEEL_HI = Color.parseColor("#FCFCFC")
        private val C_STEEL_LO = Color.parseColor("#7C7C7C")
        private val C_HIT = Color.parseColor("#F83800")
        private val CYCLE = intArrayOf(
            Color.parseColor("#F83800"), Color.parseColor("#F87858"), Color.parseColor("#F8B800"),
            Color.parseColor("#B8F818"), Color.parseColor("#58F898"), Color.parseColor("#3CBCFC"),
            Color.parseColor("#6888FC"), Color.parseColor("#D800CC")
        )

        // 5 x 7 pixel font, one string of 35 bits per letter
        private val FONT: Map<Char, String> = mapOf(
            'B' to "11110100011000111110100011000111110",
            'U' to "10001100011000110001100011000101110",
            'R' to "11110100011000111110101001001010001",
            'G' to "01110100011000010111100011000101111",
            'E' to "11111100001000011110100001000011111",
            'S' to "01111100001000001110000010000111110",
            'T' to "11111001000010000100001000010000100",
            'D' to "11110100011000110001100011000111110",
            'I' to "01110001000010000100001000010001110",
            'O' to "01110100011000110001100011000101110",
            'F' to "11111100001000011110100001000010000",
            'M' to "10001110111010110101100011000110001",
            'V' to "10001100011000110001100010101000100",
            'X' to "10001100010101000100010101000110001",
            '1' to "00100011000010000100001000010001110",
            '-' to "00000000000000011111000000000000000",
            'A' to "01110100011000111111100011000110001",
            'K' to "10001100101010011000101001001010001",
            'H' to "10001100011000111111100011000110001",
            'P' to "11110100011000111110100001000010000",
            'N' to "10001110011010110011100011000110001"
        )

        /** One 8 x 8 block order for the dissolve (a Bayer pattern), values 0..63. */
        private val BAYER = intArrayOf(
            0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26,
            12, 44, 4, 36, 14, 46, 6, 38, 60, 28, 52, 20, 62, 30, 54, 22,
            3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25,
            15, 47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21
        )

        /** The chiptune that goes with the animation, as mono samples at the engine rate. */
        fun jingle(): FloatArray {
            val sr = Cfg.SR
            val out = FloatArray((sr * (CO_MS / 1000.0 + 4.6)).toInt())
            var shift = 0
            fun noise(ms: Int, durMs: Int, vol: Float) {
                val start = (ms + shift) * sr / 1000
                val len = durMs * sr / 1000
                var seed = 0x1234
                var v = 0f
                for (i in 0 until len) {
                    val k = start + i
                    if (k >= out.size) break
                    if (i % 6 == 0) {
                        seed = seed * 1103515245 + 12345
                        v = ((seed ushr 16) and 0x7fff) / 16384f - 1f
                    }
                    val x = i.toFloat() / len
                    out[k] += v * (1f - x) * (1f - x) * vol
                }
            }
            fun note(ms: Int, midi: Int, durMs: Int, vol: Float, duty: Float, slide: Float = 0f, tri: Boolean = false) {
                val start = (ms + shift) * sr / 1000
                val len = durMs * sr / 1000
                var ph = 0.0
                for (i in 0 until len) {
                    val k = start + i
                    if (k >= out.size) break
                    val x = i.toFloat() / len
                    val f = 440.0 * 2.0.pow((midi - 69 + slide * x) / 12.0)
                    ph += f / sr
                    ph -= ph.toInt()
                    val wave = if (tri) {
                        (if (ph < 0.5) ph * 4 - 1 else 3 - ph * 4).toFloat()
                    } else {
                        if (ph < duty) 1f else -1f
                    }
                    // quick attack, then decay; stepped in 16 levels like an old sound chip
                    val env = (min(1f, i / (sr * 0.004f)) * (1f - x * 0.7f) * 16f).toInt() / 16f
                    out[k] += wave * env * vol
                }
            }
            // ---- MAKER HIP: wind-up, the hammer hit, the letters, PRESENTS ----
            note(300, 45, 450, 0.05f, 0.5f, 14f)
            noise(900, 140, 0.35f)
            note(900, 40, 160, 0.40f, 0f, -12f, true)
            note(905, 88, 280, 0.13f, 0.25f)
            note(965, 93, 380, 0.11f, 0.25f)
            val pop = intArrayOf(67, 69, 71, 72, 74, 0, 76, 79, 84)
            for (i in pop.indices) if (pop[i] > 0) note(1000 + i * 70, pop[i], 60, 0.10f, 0.125f)
            note(1800, 72, 420, 0.09f, 0.5f)
            note(1950, 79, 520, 0.09f, 0.5f)
            note(1800, 48, 600, 0.25f, 0f, 0f, true)
            shift = CO_MS.toInt()

            // ---- BURGER STUDIO ----
            // a blip for each burger layer (falling pitch)
            val layerNotes = intArrayOf(72, 74, 76, 77, 79)
            for (i in layerNotes.indices) note(760 + i * 210, layerNotes[i], 90, 0.22f, 0.25f, -5f)
            // typing the title: a fast arpeggio
            val arp = intArrayOf(60, 64, 67, 72, 67, 64, 62, 65, 69, 74, 69, 65)
            for (i in arp.indices) note(1850 + i * 95, arp[i] + 12, 70, 0.12f, 0.125f)
            // fanfare
            note(3000, 72, 140, 0.22f, 0.5f)
            note(3160, 76, 140, 0.22f, 0.5f)
            note(3320, 79, 140, 0.22f, 0.5f)
            note(3480, 84, 760, 0.24f, 0.5f)
            note(3480, 79, 760, 0.12f, 0.25f)
            note(3000, 48, 300, 0.35f, 0f, 0f, true)
            note(3320, 43, 160, 0.35f, 0f, 0f, true)
            note(3480, 48, 760, 0.35f, 0f, 0f, true)
            // a little downward sweep for the dissolve
            note(4300, 84, 260, 0.08f, 0.5f, -24f)
            for (i in out.indices) out[i] = out[i].coerceIn(-0.9f, 0.9f)
            return out
        }
    }

    private var tAll = 0f       // time since the very start
    private var t = 0f          // time inside the BURGER STUDIO scene (negative during the logo)
    private var finished = false
    private val anim = ValueAnimator.ofFloat(0f, TOTAL_MS.toFloat())

    // the low-resolution picture
    private var px = 4            // screen pixels per picture pixel
    private var lw = 0
    private var lh = 0
    private var bmp: Bitmap? = null
    private var lc: Canvas? = null
    private val fill = Paint()
    private val clear = Paint().also { it.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val blit = Paint().also { it.isFilterBitmap = false; it.isAntiAlias = false }
    private val scan = Paint().also { it.color = Color.argb(70, 0, 0, 0) }
    private val src = Rect()
    private val dst = Rect()

    private val stars = Array(40) { intArrayOf(Random.nextInt(1000), Random.nextInt(1000), Random.nextInt(8)) }

    init {
        isClickable = true
        anim.duration = TOTAL_MS
        anim.interpolator = LinearInterpolator()
        anim.addUpdateListener {
            val v = it.animatedValue as Float
            val q = (v / FRAME_MS).toInt() * FRAME_MS.toFloat()
            if (q != tAll) {
                tAll = q
                t = q - CO_MS
                invalidate()
            }
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                finish()
            }
        })
    }

    fun start() {
        anim.start()
    }

    private fun finish() {
        if (finished) return
        finished = true
        anim.removeAllUpdateListeners()
        (parent as? ViewGroup)?.removeView(this)
        bmp?.recycle()
        bmp = null
        onDone()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && !finished) {
            anim.cancel()
            animate().alpha(0f).setDuration(160).withEndAction { finish() }.start()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0 || h <= 0) return
        // the content needs about 128 x 130 picture pixels; make the blocks as big as fits
        px = max(2, min(w / 128, h / 130))
        lw = (w + px - 1) / px
        lh = (h + px - 1) / px
        bmp?.recycle()
        val b = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
        bmp = b
        lc = Canvas(b)
    }

    // ---------------- drawing helpers (in picture pixels) ----------------

    private fun box(x: Int, y: Int, w: Int, h: Int, color: Int) {
        fill.color = color
        lc?.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), fill)
    }

    private fun dot(x: Int, y: Int, color: Int) = box(x, y, 1, 1, color)

    private fun glyph(ch: Char, x: Int, y: Int, scale: Int, color: Int) {
        val g = FONT[ch] ?: return
        for (r in 0 until 7) for (c in 0 until 5) {
            if (g[r * 5 + c] == '1') box(x + c * scale, y + r * scale, scale, scale, color)
        }
    }

    private fun textWidth(s: String, scale: Int): Int = s.length * 6 * scale - scale

    private fun part(start: Float, len: Float): Float = ((t - start) / len).coerceIn(0f, 1f)

    // ---------------- the burger ----------------

    private fun topBun(cx: Int, y: Int) {
        val rows = intArrayOf(20, 28, 34, 38, 40, 40, 40)
        for (r in rows.indices) {
            val w = rows[r]
            box(cx - w / 2, y + r, w, 1, C_BUN)
        }
        box(cx - 8, y + 1, 10, 1, C_BUN_HI)
        box(cx - 13, y + 2, 6, 1, C_BUN_HI)
        box(cx - 40 / 2, y + 6, 40, 1, C_BUN_LO)
        for ((sx, sy) in listOf(-10 to 3, -3 to 2, 4 to 3, 11 to 4, -15 to 5, 0 to 5)) {
            box(cx + sx, y + sy, 2, 1, C_SEED)
        }
    }

    private fun lettuce(cx: Int, y: Int) {
        box(cx - 22, y, 44, 2, C_LETTUCE)
        for (i in 0 until 11) box(cx - 22 + i * 4, y + 2, 2, 1, C_LETTUCE_LO)
    }

    private fun cheese(cx: Int, y: Int, drip: Int) {
        box(cx - 21, y, 42, 2, C_CHEESE)
        box(cx - 12, y + 2, 2, drip, C_CHEESE)
        box(cx + 9, y + 2, 2, (drip * 2) / 3, C_CHEESE)
    }

    private fun patty(cx: Int, y: Int) {
        box(cx - 20, y, 40, 4, C_PATTY)
        box(cx - 21, y + 1, 42, 2, C_PATTY)
        for ((sx, sy) in listOf(-15 to 1, -6 to 2, 3 to 1, 12 to 2, 17 to 1)) dot(cx + sx, y + sy, C_PATTY_HI)
    }

    private fun bottomBun(cx: Int, y: Int) {
        box(cx - 20, y, 40, 3, C_BUN)
        box(cx - 19, y + 3, 38, 1, C_BUN_LO)
        box(cx - 17, y + 4, 34, 1, C_BUN_LO)
    }

    // ---------------- MAKER HIP: the company logo scene ----------------

    private fun partAt(time: Float, start: Float, len: Float): Float = ((time - start) / len).coerceIn(0f, 1f)

    /** The gold block that gets hit, with a small M on it. */
    private fun block(x: Int, y: Int) {
        box(x, y, 16, 16, C_GOLD_LO)
        box(x + 1, y + 1, 14, 14, C_GOLD)
        box(x + 1, y + 1, 14, 1, C_GOLD_HI)
        box(x + 1, y + 1, 1, 14, C_GOLD_HI)
        box(x + 2, y + 14, 13, 1, C_GOLD_LO)
        box(x + 14, y + 2, 1, 13, C_GOLD_LO)
        glyph('M', x + 5, y + 4, 1, C_GOLD_LO)
    }

    /**
     * The hammer, drawn pixel by pixel around its pivot (the end of the handle).
     * deg = 0: lying flat with the head on the block; bigger = raised (turned clockwise).
     */
    private fun hammer(pvx: Int, pvy: Int, deg: Float) {
        val a = Math.toRadians(deg.toDouble())
        val cs = kotlin.math.cos(a)
        val sn = kotlin.math.sin(a)
        for (y in pvy - 30..pvy + 30) {
            for (x in pvx - 30..pvx + 30) {
                val dx = x + 0.5 - pvx
                val dy = y + 0.5 - pvy
                // turn back into the flat hammer
                val ux = dx * cs + dy * sn
                val uy = -dx * sn + dy * cs
                val col = when {
                    ux >= -28 && ux < -18 && uy >= -5 && uy < 5 -> when {
                        uy < -3 -> C_STEEL_HI
                        uy >= 3 -> C_STEEL_LO
                        else -> C_STEEL
                    }
                    ux >= -18 && ux < -4 && uy >= -1.5 && uy < 1.5 -> C_BUN_LO
                    ux >= -4 && ux < 0 && uy >= -2 && uy < 2 -> C_HIT
                    else -> 0
                }
                if (col != 0) dot(x, y, col)
            }
        }
    }

    private fun drawCompany(g: Canvas, T: Float) {
        if (T < 250f) return
        val cx = lw / 2
        val top = (lh - 128) / 2
        val frame = (T / FRAME_MS).toInt()
        val hit = 900f

        // one white frame at the moment of the hit
        if (T >= hit && T < hit + FRAME_MS) {
            g.drawColor(C_WHITE)
            return
        }

        val by = top + 34
        val sink = if (T >= hit && T < hit + 200f) 2 else 0
        block(cx - 8, by + sink)

        // sparks fly out from the hit
        if (T >= hit && T < hit + 450f) {
            val k = (T - hit) / 450f
            for (d in 0 until 8) {
                val an = d * PI_F / 4f
                val r = 6f + k * 24f
                val sx = cx - 1 + (kotlin.math.cos(an) * r).toInt()
                val sy = by + (kotlin.math.sin(an) * r * 0.7f).toInt()
                val size = if (k < 0.5f) 2 else 1
                box(sx, sy, size, size, if (d % 2 == 0) C_WHITE else C_GOLD)
            }
        }

        // the hammer: a slow wind-up, a fast swing down, a little bounce, then rest
        val ang = when {
            T < 300f -> 30f
            T < 750f -> 30f + 80f * (1f - (1f - (T - 300f) / 450f).let { it * it })
            T < hit -> 110f * (1f - ((T - 750f) / 150f).let { it * it })
            T < hit + 260f -> 14f * kotlin.math.sin(PI_F * (T - hit) / 260f)
            else -> 0f
        }
        hammer(cx + 22, by - 5 + sink, ang)

        // MAKER HIP: the letters pop up one after another
        val ty = by + 28
        val x0 = cx - textWidth(COMPANY, 2) / 2
        for (i in COMPANY.indices) {
            val ch = COMPANY[i]
            if (ch == ' ') continue
            val p = partAt(T, 1000f + i * 70f, 240f)
            if (p <= 0f) continue
            // rise with a small overshoot
            val s = 1.9f
            val q = p - 1f
            val back = 1f + q * q * ((s + 1f) * q + s)
            val y = ty + ((1f - back) * 14f).toInt()
            val col = if (i >= 6) C_HIT else C_WHITE
            glyph(ch, x0 + i * 12, y, 2, col)
        }

        // PRESENTS, blinking a few times before it stays
        if (T >= 1800f && (T > 2200f || frame % 4 < 2)) {
            val px0 = cx - textWidth(PRESENTS, 1) / 2
            for (i in PRESENTS.indices) glyph(PRESENTS[i], px0 + i * 6, ty + 22, 1, C_GREY)
        }

        // a little twinkle on the corner of the block
        if (T in 2000f..2500f) {
            val sx = cx - 10
            val sy = by + 1
            val big = frame % 4 < 2
            dot(sx, sy, C_WHITE)
            dot(sx - 1, sy, C_WHITE); dot(sx + 1, sy, C_WHITE)
            dot(sx, sy - 1, C_WHITE); dot(sx, sy + 1, C_WHITE)
            if (big) {
                dot(sx - 2, sy, C_GOLD_HI); dot(sx + 2, sy, C_GOLD_HI)
                dot(sx, sy - 2, C_GOLD_HI); dot(sx, sy + 2, C_GOLD_HI)
            }
        }

        // fade to dark in 4 steps, like an old console palette fade
        val f = ((T - 2550f) / 450f * 4f).toInt().coerceIn(0, 4)
        if (f > 0) {
            fill.color = Color.argb(minOf(255, f * 64), Color.red(C_BG), Color.green(C_BG), Color.blue(C_BG))
            g.drawRect(0f, 0f, lw.toFloat(), lh.toFloat(), fill)
        }
    }

    // ---------------- frame ----------------

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        val g = lc ?: return
        g.drawColor(C_BG)
        if (tAll < CO_MS) {
            drawCompany(g, tAll)
            present(c, b, true)
            return
        }
        val cx = lw / 2
        val top = (lh - 128) / 2
        val frame = (t / FRAME_MS).toInt()

        // twinkling stars
        val sp = part(0f, 500f)
        for (s in stars) {
            if (s[2] / 8f > sp) continue
            val sx = s[0] * lw / 1000
            val sy = s[1] * lh / 1000
            if (abs(sx - cx) < 68 && sy in (top + 10)..(top + 130)) continue   // keep the title area clean
            val on = (frame + s[2]) % 9 != 0
            if (on) dot(sx, sy, if (s[2] % 3 == 0) C_GREY else C_WHITE)
        }

        // the burger: 5 layers fall from the top and land one after another
        val ground = top + 46
        val layerTop = intArrayOf(ground - 22, ground - 15, ground - 12, ground - 9, ground - 4)
        for (k in 4 downTo 0) {
            val order = 4 - k
            val p = part(700f + order * 210f, 260f)
            if (p <= 0f) continue
            val fallY = ((1f - p * p) * (layerTop[k] + 30)).toInt()
            val y = layerTop[k] - fallY
            when (k) {
                0 -> topBun(cx, y)
                1 -> lettuce(cx, y)
                2 -> cheese(cx, y, (4 * part(1600f, 300f)).toInt())
                3 -> patty(cx, y)
                4 -> bottomBun(cx, y)
            }
            // a puff of dust the moment a layer lands
            val since = t - (700f + order * 210f + 260f)
            if (since in 0f..140f) {
                val d = 2 + (since / 50f).toInt() * 2
                dot(cx - 23 - d, layerTop[k] + 1, C_WHITE)
                dot(cx + 22 + d, layerTop[k] + 1, C_WHITE)
            }
        }

        // the title types itself in, with a blinking block cursor
        val titleY1 = top + 60
        val titleY2 = titleY1 + 18
        val typed = ((t - 1850f) / 95f).toInt().coerceIn(-1, LINE1.length + LINE2.length)
        val fanfare = t >= 3000f
        val x1 = cx - textWidth(LINE1, 2) / 2
        val x2 = cx - textWidth(LINE2, 2) / 2
        for (i in LINE1.indices) {
            if (i >= typed) break
            val col = if (fanfare) CYCLE[(i + frame) % CYCLE.size] else C_BUN_HI
            glyph(LINE1[i], x1 + i * 12, titleY1, 2, col)
        }
        for (i in LINE2.indices) {
            if (LINE1.length + i >= typed) break
            val col = if (fanfare) CYCLE[(i + 3 + frame) % CYCLE.size] else C_CYAN
            glyph(LINE2[i], x2 + i * 12, titleY2, 2, col)
        }
        if (!fanfare && frame % 4 < 2) {
            val n = typed.coerceAtLeast(0)
            val (cxx, cyy) = if (n < LINE1.length) Pair(x1 + n * 12, titleY1) else Pair(x2 + (n - LINE1.length).coerceAtMost(LINE2.length) * 12, titleY2)
            if (t < 1850f) box(cx - 5, titleY1, 10, 14, C_WHITE) else box(cxx, cyy, 10, 14, C_WHITE)
        }

        // tagline and the jumping equalizer during the fanfare
        if (fanfare) {
            if (frame % 6 < 4 || t > 3600f) {
                val tx = cx - textWidth(TAGLINE, 1) / 2
                for (i in TAGLINE.indices) glyph(TAGLINE[i], tx + i * 6, titleY2 + 22, 1, C_PINK)
            }
            val bars = 11
            val bx0 = cx - (bars * 4 - 1) / 2
            val base = titleY2 + 46
            for (i in 0 until bars) {
                val lvl = abs(sin(frame * 0.9 + i * 1.3 + sin(i * 0.7) * 2)) * (1.0 - abs(i - 5) / 7.0)
                val h = 2 + (lvl * 10).toInt() * 2
                for (y in 0 until h step 2) {
                    val col = when {
                        y >= 14 -> CYCLE[0]
                        y >= 8 -> CYCLE[2]
                        else -> CYCLE[4]
                    }
                    box(bx0 + i * 4, base - y - 1, 3, 1, col)
                }
            }
        }

        // the end: break into 8 x 8 blocks that vanish in a scattered order
        val dp = part(4300f, 650f)
        if (dp > 0f) {
            val limit = (dp * 64f).toInt()
            var by = 0
            while (by < lh) {
                var bx = 0
                while (bx < lw) {
                    val bi = BAYER[((by / 8) % 8) * 8 + (bx / 8) % 8]
                    if (bi < limit) g.drawRect(bx.toFloat(), by.toFloat(), (bx + 8).toFloat(), (by + 8).toFloat(), clear)
                    bx += 8
                }
                by += 8
            }
        }

        present(c, b, dp == 0f)
    }

    /** Blows the picture up with hard pixel edges, then adds CRT scanlines. */
    private fun present(c: Canvas, b: Bitmap, scanlines: Boolean) {
        src.set(0, 0, lw, lh)
        dst.set(0, 0, lw * px, lh * px)
        c.drawBitmap(b, src, dst, blit)
        if (px >= 3 && scanlines) {
            var y = px - 1
            val hh = height
            while (y < hh) {
                c.drawRect(0f, y.toFloat(), width.toFloat(), (y + 1).toFloat(), scan)
                y += px
            }
        }
    }
}
