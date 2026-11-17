package com.example.fm1burger

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

object Theme {
    val BG = Color.parseColor("#0E0E10")
    val PANEL = Color.parseColor("#1C1C20")
    val PANEL2 = Color.parseColor("#2A2A30")
    val ACCENT = Color.parseColor("#FF6A1A")
    val TEXT = Color.parseColor("#EDEDED")
    val DIM = Color.parseColor("#8E8E96")
    val RED = Color.parseColor("#E5484D")
    val GREEN = Color.parseColor("#3DD68C")
    val TRACK_COLORS = intArrayOf(
        Color.parseColor("#FF6A1A"),
        Color.parseColor("#3DA9FC"),
        Color.parseColor("#3DD68C"),
        Color.parseColor("#E5C03D")
    )
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
        title.text = "$name  ${fmt(v)}"
        silent = false
    }

    fun get(): Int = bar.progress

    fun setEnabled(e: Boolean) {
        bar.isEnabled = e
    }
}

interface Screen {
    val root: View
    fun onShow() {}
    fun onTick() {}
}

class Ui(private val ctx: Context) {

    private val density = ctx.resources.displayMetrics.density

    fun dp(n: Int): Int = (n * density).toInt()

    fun toast(msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }

    fun bg(color: Int, radiusDp: Int = 10, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor)
        return g
    }

    fun text(s: String, size: Float = 14f, color: Int = Theme.TEXT, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    fun styleButton(b: Button, active: Boolean, activeColor: Int = Theme.ACCENT) {
        b.background = bg(if (active) activeColor else Theme.PANEL2, 8)
        b.setTextColor(if (active) Color.BLACK else Theme.TEXT)
    }

    fun button(s: String, active: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = s
        b.isAllCaps = false
        b.textSize = 13f
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(dp(10), dp(8), dp(10), dp(8))
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

    fun panel(): LinearLayout {
        val l = col()
        l.background = bg(Theme.PANEL, 12)
        l.setPadding(dp(12), dp(10), dp(12), dp(10))
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

    fun slider(
        name: String,
        max: Int,
        value: Int,
        fmt: (Int) -> String,
        onChange: (Int) -> Unit
    ): SliderRow {
        val box = col()
        val title = text("$name  ${fmt(value)}", 12f, Theme.DIM)
        val bar = SeekBar(ctx)
        bar.max = max
        bar.progress = value
        box.addView(title)
        box.addView(bar)
        val sr = SliderRow(box, title, bar, name, fmt)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                title.text = "$name  ${fmt(p)}"
                if (!sr.silent && fromUser) onChange(p)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        return sr
    }

    fun spinner(items: List<String>, onSel: (Int) -> Unit): Spinner {
        val sp = Spinner(ctx)
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
        val ad = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, items)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = ad
        val idx = items.indexOf(prev)
        if (idx >= 0) sp.setSelection(idx)
    }
}
