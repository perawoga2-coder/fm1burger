package com.example.fm1burger

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** The colors of the app. Every screen takes its colors from here. */
object Theme {
    val BG = Color.parseColor("#0B0B0F")       // page background
    val PANEL = Color.parseColor("#15151B")    // cards, header
    val PANEL2 = Color.parseColor("#22222B")   // buttons, wells
    val PANEL3 = Color.parseColor("#2C2C37")   // raised / pressed
    val LINE = Color.parseColor("#2E2E3A")     // thin borders
    val ACCENT = Color.parseColor("#FF7A2F")
    val TEXT = Color.parseColor("#F2F2F5")
    val DIM = Color.parseColor("#8F8F9C")
    val FAINT = Color.parseColor("#5C5C69")
    val RED = Color.parseColor("#FF4D5E")
    val GREEN = Color.parseColor("#34D399")
    val BLUE = Color.parseColor("#4DA3FF")
    val YELLOW = Color.parseColor("#F5C542")
    val PURPLE = Color.parseColor("#B07CFF")
    val PINK = Color.parseColor("#FF5CA8")
    val TRACK_COLORS = intArrayOf(ACCENT, BLUE, GREEN, YELLOW)

    /** Text color that reads well on top of a filled color. */
    fun onColor(c: Int): Int {
        val l = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
        return if (l > 140) Color.parseColor("#111114") else TEXT
    }

    fun alpha(c: Int, a: Int): Int = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
}

/** A label and a slider in one block. */
class SliderRow(
    val view: LinearLayout,
    private val title: TextView,
    private val bar: SeekBar,
    private val name: String,
    private val fmt: (Int) -> String
) {
    var silent = false

    fun set(v: Int) {
        silent = true
        bar.progress = v
        title.text = Ui.label(name, fmt(v))
        silent = false
    }

    fun get(): Int = bar.progress

    fun setEnabled(e: Boolean) {
        bar.isEnabled = e
        view.alpha = if (e) 1f else 0.45f
    }
}

interface Screen {
    val root: View
    fun onShow() {}
    fun onTick() {}
}

class Ui(private val ctx: Context) {

    companion object {
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

        /** "Name  value" with the name dim and the value bright. */
        fun label(name: String, value: String): CharSequence {
            val sb = SpannableStringBuilder()
            sb.append(name)
            sb.setSpan(ForegroundColorSpan(Theme.DIM), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (value.isNotEmpty()) {
                sb.append("  ")
                val st = sb.length
                sb.append(value)
                sb.setSpan(ForegroundColorSpan(Theme.TEXT), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return sb
        }
    }

    private val density = ctx.resources.displayMetrics.density
    private var lastToast: Toast? = null

    fun dp(n: Int): Int = (n * density + 0.5f).toInt()

    /** Shows a message. A new message replaces the old one instead of queueing behind it. */
    fun toast(msg: String) {
        try {
            lastToast?.cancel()
            val t = Toast.makeText(ctx, msg, Toast.LENGTH_LONG)
            lastToast = t
            t.show()
        } catch (e: Exception) {
        }
    }

    fun bg(color: Int, radiusDp: Int = 10, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor)
        return g
    }

    /** A background that shows a soft ripple when it is touched. */
    fun touchBg(content: Drawable, radiusDp: Int): Drawable {
        val mask = bg(Color.WHITE, radiusDp)
        return RippleDrawable(ColorStateList.valueOf(Theme.alpha(Color.WHITE, 40)), content, mask)
    }

    fun text(s: String, size: Float = 14f, color: Int = Theme.TEXT, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = if (size >= 16f) BOLD else MEDIUM
        t.setLineSpacing(0f, 1.1f)
        return t
    }

    /** A page title with a small accent bar in front of it. */
    fun title(s: String, color: Int = Theme.TEXT): TextView {
        val t = text(s, 18f, color, true)
        t.letterSpacing = 0.01f
        val bar = bg(Theme.ACCENT, 2)
        bar.setSize(dp(4), dp(18))
        t.setCompoundDrawablesRelativeWithIntrinsicBounds(bar, null, null, null)
        t.compoundDrawablePadding = dp(10)
        t.gravity = Gravity.CENTER_VERTICAL
        return t
    }

    /**
     * Colors a button: active = filled with the color, inactive = dark with a thin border.
     * Called many times per second by the screens, so it only rebuilds the drawable when the look changes.
     */
    fun styleButton(b: Button, active: Boolean, activeColor: Int = Theme.ACCENT, radiusDp: Int = 10) {
        val key = if (active) (activeColor.toLong() shl 8) or radiusDp.toLong() or (1L shl 60) else radiusDp.toLong()
        val prev = b.getTag(R.id.burger_style) as? StyleTag
        if (prev != null && prev.key == key && b.background === prev.drawable) return
        val content = if (active) bg(activeColor, radiusDp) else bg(Theme.PANEL2, radiusDp, Theme.LINE, 1)
        val d = touchBg(content, radiusDp)
        b.background = d
        b.setTextColor(if (active) Theme.onColor(activeColor) else Theme.TEXT)
        b.setTag(R.id.burger_style, StyleTag(key, d))
    }

    private class StyleTag(val key: Long, val drawable: Drawable)

    fun button(s: String, active: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = s
        b.isAllCaps = false
        b.textSize = 13f
        b.typeface = MEDIUM
        b.minHeight = dp(40)
        b.minimumHeight = dp(40)
        b.minWidth = 0
        b.minimumWidth = 0
        b.stateListAnimator = null
        b.elevation = 0f
        b.setPadding(dp(12), dp(8), dp(12), dp(8))
        b.maxLines = 2
        styleButton(b, active)
        b.setOnClickListener { onClick() }
        return b
    }

    fun row(): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    fun col(): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    /** A card: slightly lighter than the page, rounded, with a thin border. */
    fun panel(): LinearLayout {
        val l = col()
        l.background = bg(Theme.PANEL, 16, Theme.LINE, 1)
        l.setPadding(dp(14), dp(12), dp(14), dp(12))
        return l
    }

    fun w(weight: Float = 1f, margin: Int = 3): LinearLayout.LayoutParams {
        val width = if (weight > 0f) 0 else LinearLayout.LayoutParams.WRAP_CONTENT
        val p = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        p.setMargins(dp(margin), dp(margin), dp(margin), dp(margin))
        return p
    }

    fun fullWidth(topDp: Int = 6): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        p.topMargin = dp(topDp)
        return p
    }

    /** Gives a SeekBar the app colors. */
    fun tint(bar: SeekBar, color: Int = Theme.ACCENT) {
        bar.progressTintList = ColorStateList.valueOf(color)
        bar.thumbTintList = ColorStateList.valueOf(color)
        bar.progressBackgroundTintList = ColorStateList.valueOf(Theme.FAINT)
        bar.splitTrack = false
    }

    fun slider(
        name: String,
        max: Int,
        value: Int,
        fmt: (Int) -> String,
        onChange: (Int) -> Unit
    ): SliderRow {
        val box = col()
        val title = text("", 12f, Theme.DIM)
        title.text = label(name, fmt(value))
        title.setPadding(dp(4), dp(2), 0, 0)
        val bar = SeekBar(ctx)
        bar.max = max
        bar.progress = value
        tint(bar)
        bar.setPadding(dp(10), dp(8), dp(10), dp(8))
        box.addView(title)
        box.addView(bar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val sr = SliderRow(box, title, bar, name, fmt)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                title.text = label(name, fmt(p))
                if (!sr.silent && fromUser) onChange(p)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        return sr
    }

    fun spinner(items: List<String>, onSel: (Int) -> Unit): Spinner {
        val sp = Spinner(ctx)
        sp.setPopupBackgroundDrawable(bg(Theme.PANEL3, 12))
        sp.setPadding(dp(6), dp(4), dp(6), dp(4))
        fill(sp, items)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                onSel(pos)
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        return sp
    }

    fun fill(sp: Spinner, items: List<String>) {
        val prev = sp.selectedItem as? String
        val ad = object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? TextView)?.setTextColor(Theme.TEXT)
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                (v as? TextView)?.setTextColor(Theme.TEXT)
                return v
            }
        }
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = ad
        val idx = items.indexOf(prev)
        if (idx >= 0) sp.setSelection(idx)
    }
}
