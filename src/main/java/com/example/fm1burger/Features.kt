package com.example.fm1burger

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.util.zip.CRC32

/**
 * Ready before the launch, like the big apps, without opening it to everyone:
 *  - EARLY ACCESS: the app opens with an invite-code page until a code is entered (an invite: a tester;
 *    the owner's code: the owner, who also gets the Developer panel in Setup and can make invite codes)
 *  - FREE / PRO: the Pro features are marked and gated, but the split is off until the sale starts (SELLING);
 *    the owner can preview the free version (Developer > Store preview)
 *  - LABS: features that are done but not shown yet (Quick sample into a device), for the owner only
 * The codes are checked on the device (a gate for testers, not copy protection).
 */
object Features {

    /** Build switches: the early-access page; the free / Pro split (off: everything is open). */
    const val GATE = true
    const val SELLING = false

    // Pro features (gated only when SELLING, or in the owner's store preview)
    const val STEMS = "Stems export"
    const val HIRES = "24-bit and 32-bit float recording"
    const val MIDI_EXPORT = "MIDI export"
    const val AUTOMATION = "Knob automation"
    const val PAD_BANK_B = "Drum pad bank B (pads 17-32)"

    private const val INVITE_SALT = "burger-invite-1"
    private const val OWNER_SALT = "makerhip-owner-1"
    private const val ABC = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"     // no 0 O 1 I: easy to read out

    private lateinit var p: SharedPreferences

    fun init(ctx: Context) {
        p = ctx.getSharedPreferences("features", Context.MODE_PRIVATE)
    }

    val unlocked: Boolean get() = !GATE || p.getBoolean("unlocked", false)
    val owner: Boolean get() = p.getBoolean("owner", false)
    var storePreview: Boolean
        get() = owner && p.getBoolean("storePreview", false)
        set(v) = p.edit().putBoolean("storePreview", v).apply()
    var labsOn: Boolean
        get() = p.getBoolean("labs", true)
        set(v) = p.edit().putBoolean("labs", v).apply()
    /** Bought (later, from the store). */
    val pro: Boolean get() = p.getBoolean("pro", false)

    /** Hidden features are shown (the owner, with LABS on). */
    fun labs(): Boolean = owner && labsOn

    fun canUse(feature: String): Boolean = pro || !(SELLING || storePreview) || feature.isEmpty()

    /** true = go on; false = a Pro feature in the free version (it says so). */
    fun gate(a: MainActivity, feature: String): Boolean {
        if (canUse(feature)) return true
        AlertDialog.Builder(a)
            .setTitle("Burger Studio Pro")
            .setMessage("$feature is part of Burger Studio Pro (one purchase, at launch).\n\n" +
                "Pro: stems, 24 / 32-bit recording, MIDI export, knob automation and drum bank B." +
                (if (storePreview) "\n\n(Store preview: this is what the free version shows.)" else ""))
            .setPositiveButton("OK", null)
            .show()
        return false
    }

    // ---------------- codes ----------------

    private fun check(id: String, salt: String, n: Int): String {
        val c = CRC32()
        c.update((salt + id).toByteArray())
        var v = c.value
        val sb = StringBuilder()
        for (k in 0 until n) {
            sb.append(ABC[(v % ABC.length).toInt()])
            v /= ABC.length
        }
        return sb.toString()
    }

    private fun norm(code: String) = code.uppercase().filter { it.isLetterOrDigit() }

    /** "BS-XXXX-YY": a tester; "MH-XXXXXX-YYY": the owner. Returns "invite", "owner" or null. */
    fun kindOf(code: String): String? {
        val c = norm(code)
        if (c.length == 8 && c.startsWith("BS") && check(c.substring(2, 6), INVITE_SALT, 2) == c.substring(6)) return "invite"
        if (c.length == 11 && c.startsWith("MH") && check(c.substring(2, 8), OWNER_SALT, 3) == c.substring(8)) return "owner"
        return null
    }

    fun unlock(code: String): String? {
        val k = kindOf(code) ?: return null
        p.edit().putBoolean("unlocked", true).putBoolean("owner", k == "owner").apply()
        return k
    }

    fun lockAgain() {
        p.edit().putBoolean("unlocked", false).putBoolean("owner", false).apply()
    }

    /** A new tester code (the owner's Developer panel). */
    fun newInvite(): String {
        val r = java.security.SecureRandom()
        val id = String(CharArray(4) { ABC[r.nextInt(ABC.length)] })
        return "BS-$id-" + check(id, INVITE_SALT, 2)
    }

    /** The early-access page over the whole app; removes itself on a good code. */
    fun showGate(a: MainActivity, host: FrameLayout) {
        val ui = a.ui
        val page = LinearLayout(a)
        page.orientation = LinearLayout.VERTICAL
        page.gravity = Gravity.CENTER
        page.setBackgroundColor(Theme.BG)
        page.isClickable = true                      // nothing under it can be touched
        page.setPadding(ui.dp(32), ui.dp(32), ui.dp(32), ui.dp(32))
        val t = ui.text("BURGER STUDIO", 34f, Theme.ACCENT, true)
        t.gravity = Gravity.CENTER
        page.addView(t)
        val s = ui.text("Early access", 18f, Theme.TEXT, true)
        s.gravity = Gravity.CENTER
        page.addView(s, ui.fullWidth(6))
        val m = ui.text("Sample anything, chop it, play it, with any instrument you plug in.\n" +
            "This version is for invited testers. Enter your invite code.", 14f, Theme.DIM)
        m.gravity = Gravity.CENTER
        page.addView(m, ui.fullWidth(14))
        val et = EditText(a)
        et.hint = "BS-XXXX-XX"
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        et.setSingleLine(true)
        et.gravity = Gravity.CENTER
        et.textSize = 22f
        et.setTextColor(Theme.TEXT)
        et.setHintTextColor(Theme.DIM)
        page.addView(et, LinearLayout.LayoutParams(ui.dp(360), LinearLayout.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = ui.dp(18)
            it.gravity = Gravity.CENTER_HORIZONTAL
        })
        val b = ui.button("Unlock") {
            when (unlock(et.text.toString())) {
                null -> ui.toast("That code does not work. Check it and try again.")
                "owner" -> {
                    host.removeView(page)
                    ui.toast("Welcome back. Setup > Developer makes invite codes.")
                    a.refreshCurrent()
                }
                else -> {
                    host.removeView(page)
                    ui.toast("Welcome to the Burger Studio early access")
                    a.refreshCurrent()
                }
            }
        }
        ui.styleButton(b, true, Theme.ACCENT)
        page.addView(b, LinearLayout.LayoutParams(ui.dp(360), ui.dp(56)).also {
            it.topMargin = ui.dp(12)
            it.gravity = Gravity.CENTER_HORIZONTAL
        })
        val f = ui.text("MakerHip", 12f, Theme.DIM)
        f.gravity = Gravity.CENTER
        page.addView(f, ui.fullWidth(30))
        host.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
}
