package com.example.fm1burger

import android.app.AlertDialog
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File
import java.util.Locale

/**
 * 3.28: the sound picker of a Home lane (Sampler or Drum), a window over Home.
 * First the groups as icons (with how many sounds each holds), then the names of one group: tap a name to pick
 * and hear it, "✓ Put on lane N" puts it on the lane.
 */
class SoundPicker(private val a: MainActivity, private val lane: Int) {

    private class Group(val title: String, val icon: String, val match: (String) -> Boolean,
                        val children: List<Pair<String, (String) -> Boolean>> = emptyList())

    private val ui = a.ui
    private val store = a.store
    private val lanes = a.lanes
    private val drum = lanes.type[lane] == Inst.DRUM

    private var all: List<File> = emptyList()
    private val groups = ArrayList<Group>()
    private var group: Group? = null
    private var child = -1
    private var sel: File? = null
    private var put = false
    private val rowOf = HashMap<String, Button>()

    private val head = ui.text("", 13f, Theme.DIM)
    private val backRow = ui.row()
    private val groupText = ui.text("", 15f, Theme.TEXT, true)
    private val search = EditText(a)
    private val chipScroll = HorizontalScrollView(a)
    private val chipRow = ui.row()
    private val listScroll = ScrollView(a)
    private val listBox = ui.col()
    private val pickedText = ui.text("", 13f, Theme.TEXT, true)
    private lateinit var putBtn: Button
    private var dlg: AlertDialog? = null

    private fun nowName(): String {
        val k = lanes.idx[lane]
        return if (drum) a.engine.pads[k].name else a.engine.tracks[k].sampleName
    }

    private fun buildGroups() {
        all = store.list()
        val catOf = HashMap<String, String>()
        for (f in all) catOf[f.name] = store.categoryOf(f.name)
        val tree = Boxes.tree(a, store)
        fun cat(title: String, c: String = title) = groups.add(Group(title, title, { n -> catOf[n] == c }))
        fun box(title: String) {
            val b = tree.firstOrNull { it.title == title && !it.user } ?: return
            groups.add(Group(title, title, { n -> b.has(n) }, b.children.map { ch -> Pair(ch.title, { n: String -> ch.has(n) }) }))
        }
        groups.clear()
        cat(SoundIcons.KICK)
        cat(SoundIcons.SNARE)
        cat(SoundIcons.HATS)
        cat(SoundIcons.TOMS)
        box(SoundIcons.SYNTHS)
        box(SoundIcons.KITS)
        box(SoundIcons.PIANO)
        cat(SoundIcons.FM)
        cat(SoundIcons.LOOPS)
        cat(SoundIcons.VOICE)
        cat(SoundIcons.RECS, "Recorded")
        cat(SoundIcons.OTHER)
        for (b in tree) if (b.user) groups.add(Group(b.title, SoundIcons.BOX, { n -> b.has(n) }))
    }

    fun show() {
        if (lanes.type[lane] != Inst.SAMPLE && !drum) return
        buildGroups()

        val root = ui.col()
        root.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(4))
        root.minimumHeight = (a.resources.displayMetrics.heightPixels * 0.55f).toInt()
        root.addView(head)

        val back = ui.button("◀ Groups") { showGroups() }
        backRow.addView(back, ui.w(0f, 3))
        groupText.maxLines = 1
        groupText.ellipsize = TextUtils.TruncateAt.END
        groupText.setPadding(ui.dp(8), 0, 0, 0)
        backRow.addView(groupText, ui.w(1f, 3))
        root.addView(backRow, ui.fullWidth(4))

        search.hint = "Search name or tag..."
        search.setSingleLine(true)
        search.setTextColor(Theme.TEXT)
        search.setHintTextColor(Theme.DIM)
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (group != null) fillNames()
            }

            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        root.addView(search, ui.fullWidth(2))

        chipScroll.isHorizontalScrollBarEnabled = false
        chipScroll.addView(chipRow)
        root.addView(chipScroll, ui.fullWidth(2))

        listScroll.addView(listBox)
        root.addView(listScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).also { it.topMargin = ui.dp(4) })

        val foot = ui.row()
        pickedText.maxLines = 1
        pickedText.ellipsize = TextUtils.TruncateAt.END
        foot.addView(pickedText, ui.w(1f, 3))
        putBtn = ui.button("✓ Put on lane ${lane + 1}") { putOnLane() }
        foot.addView(putBtn, ui.w(0f, 3))
        root.addView(foot, ui.fullWidth(6))

        val d = AlertDialog.Builder(a)
            .setTitle("LANE ${lane + 1}  ·  ${lanes.label(lane)}  ·  SOUND")
            .setView(root)
            .setNegativeButton("Close", null)
            .create()
        d.setOnDismissListener { if (!put) a.engine.previewStop() }
        dlg = d
        showGroups()
        paintPicked()
        d.show()
    }

    // ---------------- the groups ----------------

    private fun showGroups() {
        group = null
        child = -1
        backRow.visibility = View.GONE
        search.visibility = View.GONE
        chipScroll.visibility = View.GONE
        val now = nowName().removeSuffix(".wav")
        head.text = (if (now.isEmpty()) "This lane is empty." else "On this lane now: $now.") +
            " Tap a group, then a name to hear it."
        listBox.removeAllViews()
        rowOf.clear()
        val per = 3
        var r: LinearLayout? = null
        groups.forEachIndexed { i, g ->
            if (i % per == 0) {
                r = ui.row()
                listBox.addView(r, ui.fullWidth(0))
            }
            r!!.addView(groupCell(g), ui.w(1f, 3))
        }
        val rest = groups.size % per
        if (rest != 0) for (k in rest until per) r?.addView(View(a), ui.w(1f, 3))
        listScroll.scrollTo(0, 0)
    }

    private fun groupCell(g: Group): View {
        val cell = ui.col()
        cell.gravity = Gravity.CENTER_HORIZONTAL
        cell.background = ui.touchBg(ui.bg(Theme.PANEL2, 12, Theme.LINE, 1), 12)
        cell.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(6))
        val icon = SoundIconView(a)
        icon.group = g.icon
        cell.addView(icon, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)))
        val name = ui.text(g.title, 12f, Theme.TEXT, true)
        name.gravity = Gravity.CENTER
        name.minLines = 2
        name.maxLines = 2
        name.ellipsize = TextUtils.TruncateAt.END
        cell.addView(name, ui.fullWidth(4))
        val n = all.count { g.match(it.name) }
        val count = ui.text(if (n == 1) "1 sound" else "$n sounds", 11f, Theme.DIM)
        count.gravity = Gravity.CENTER
        cell.addView(count, ui.fullWidth(0))
        cell.isClickable = true
        cell.setOnClickListener { openGroup(g) }
        return cell
    }

    // ---------------- the names of one group ----------------

    private fun openGroup(g: Group) {
        child = -1
        group = null                      // (clearing the search must not fill the list twice)
        search.setText("")
        group = g
        groupText.text = g.title
        head.text = "Tap a name: it is picked and plays once."
        backRow.visibility = View.VISIBLE
        search.visibility = View.VISIBLE
        fillNames()
        listScroll.scrollTo(0, 0)
    }

    private fun chip(label: String, on: Boolean, act: () -> Unit) {
        val b = ui.button(label) { act() }
        b.minHeight = ui.dp(36)
        b.minimumHeight = ui.dp(36)
        b.setPadding(ui.dp(14), 0, ui.dp(14), 0)
        ui.styleButton(b, on, Theme.TEAL, 18)
        chipRow.addView(b, ui.w(0f, 3))
    }

    private fun fillNames() {
        val g = group ?: return
        // the sub-groups (a kit of Drum Kits, an engine of Synths) as chips above the names
        chipRow.removeAllViews()
        if (g.children.isEmpty()) {
            chipScroll.visibility = View.GONE
        } else {
            chipScroll.visibility = View.VISIBLE
            chip("All in ${g.title}", child < 0) { child = -1; fillNames(); listScroll.scrollTo(0, 0) }
            g.children.forEachIndexed { i, ch ->
                val n = all.count { ch.second(it.name) }
                chip("${ch.first}  $n", child == i) { child = i; fillNames(); listScroll.scrollTo(0, 0) }
            }
        }
        val sub = g.children.getOrNull(child)
        val q = search.text.toString().trim().lowercase(Locale.ROOT)
        val files = all.filter { f ->
            g.match(f.name) && (sub == null || sub.second(f.name)) &&
                (q.isEmpty() || f.name.lowercase(Locale.ROOT).contains(q) ||
                    store.getTags(f.name).lowercase(Locale.ROOT).contains(q))
        }
        listBox.removeAllViews()
        rowOf.clear()
        if (files.isEmpty()) {
            listBox.addView(ui.text(if (q.isEmpty()) "Nothing here yet." else "No name with \"$q\" here.", 14f, Theme.DIM), ui.fullWidth(8))
        }
        // the names, two in a row: big and easy to tap (as in the Maker Bank)
        var r: LinearLayout? = null
        files.forEachIndexed { i, f ->
            if (i % 2 == 0) {
                r = ui.row()
                listBox.addView(r, ui.fullWidth(2))
            }
            val b = ui.button(f.nameWithoutExtension) { pick(f) }
            b.textSize = 15f
            b.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            b.setPadding(ui.dp(14), 0, ui.dp(10), 0)
            b.maxLines = 1
            b.ellipsize = TextUtils.TruncateAt.END
            b.minHeight = ui.dp(52)
            b.minimumHeight = ui.dp(52)
            rowOf[f.name] = b
            r!!.addView(b, ui.w(1f, 3))
        }
        if (files.size % 2 == 1) r?.addView(View(a), ui.w(1f, 3))
        paintRows()
    }

    /** A tap on a name: it is picked, and plays once. */
    private fun pick(f: File) {
        sel = f
        paintRows()
        paintPicked()
        preview(f)
    }

    private fun paintRows() {
        for ((n, b) in rowOf) ui.styleButton(b, n == sel?.name, Theme.ACCENT, 12)
    }

    private fun paintPicked() {
        val f = sel
        pickedText.text = if (f == null) "No name picked yet" else f.nameWithoutExtension
        pickedText.setTextColor(if (f == null) Theme.FAINT else Theme.TEXT)
        ui.styleButton(putBtn, f != null, Theme.GREEN)
    }

    private fun preview(f: File) {
        Thread {
            val d = store.loadEngine(f.name)
            a.runOnUiThread {
                if (d == null) ui.toast("Cannot play this file") else a.engine.previewPlay(d)
            }
        }.start()
    }

    private fun putOnLane() {
        val f = sel
        if (f == null || !f.exists()) {
            ui.toast("Tap a group, then a name first")
            return
        }
        // the lane may have changed under the window: only a Sampler / Drum lane takes a sound
        val t = lanes.type[lane]
        val k = lanes.idx[lane]
        if (t == Inst.DRUM) a.assignPad(k, f)
        else if (t == Inst.SAMPLE) a.assignTrack(k, f)
        else return
        put = true
        ui.toast("Lane ${lane + 1} (${lanes.label(lane)}): ${f.nameWithoutExtension}")
        dlg?.dismiss()
        a.refreshCurrent()
    }
}
