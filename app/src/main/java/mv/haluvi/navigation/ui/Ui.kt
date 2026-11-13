package mv.haluvi.navigation.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

object UiColors {
    const val BG = 0xFF101820.toInt()
    const val INFO_BG = 0xFF1B2A38.toInt()
    const val BUTTON = 0xFF0B6E99.toInt()
    const val BUTTON_SELECTED = 0xFF1490C8.toInt()
    const val BUTTON_ON = 0xFF117A37.toInt()
    const val BUTTON_STOP = 0xFF9B1C1C.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val STROKE = 0xFF2A9DC2.toInt()
    const val STROKE_ON = 0xFF4CC96B.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun roundedBg(ctx: Context, color: Int, radiusDp: Int = 12, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = ctx.dp(radiusDp).toFloat()
    if (stroke != null) setStroke(ctx.dp(2), stroke)
}

/** A screen. Screens are rebuilt from factories, so they never hold stale state after Back. */
abstract class Screen {
    lateinit var host: MainActivity
    var titleView: TextView? = null

    abstract fun create(): View

    /** Called when navigation / explore / GPS state changes. Update text in place; do not rebuild. */
    open fun onLive() {}

    /** Called once per second while visible. */
    open fun onSecond() {}

    open fun onDispose() {}

    /** Rebuilds this screen in place (keeps the back stack). */
    fun rebuild() = host.rebuild()
}

/** Builds accessible, high contrast, large-target UI elements into a vertical container. */
class UiBuilder(val ctx: Context, val root: LinearLayout) {

    fun title(text: String): TextView {
        val t = TextView(ctx)
        t.text = text
        t.setTextColor(UiColors.TEXT)
        t.textSize = 24f
        t.setTypeface(null, Typeface.BOLD)
        t.gravity = Gravity.CENTER
        t.setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(8))
        t.isFocusable = true
        t.isFocusableInTouchMode = true
        ViewCompat.setAccessibilityHeading(t, true)
        root.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return t
    }

    fun heading(text: String): TextView {
        val t = TextView(ctx)
        t.text = text
        t.setTextColor(0xFFFFC94A.toInt())
        t.textSize = 19f
        t.setTypeface(null, Typeface.BOLD)
        t.setPadding(ctx.dp(8), ctx.dp(12), ctx.dp(8), ctx.dp(4))
        ViewCompat.setAccessibilityHeading(t, true)
        root.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return t
    }

    fun info(text: String): TextView {
        val t = TextView(ctx)
        t.text = text
        t.setTextColor(UiColors.TEXT)
        t.textSize = 18f
        t.setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
        t.background = roundedBg(ctx, UiColors.INFO_BG, 10)
        t.isFocusable = true
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, ctx.dp(3), 0, ctx.dp(3))
        root.addView(t, lp)
        return t
    }

    private fun styleButton(b: Button, text: String, color: Int, stroke: Int?) {
        b.text = text
        b.isAllCaps = false
        b.textSize = 18f
        b.setTextColor(UiColors.TEXT)
        b.minHeight = ctx.dp(56)
        b.setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
        b.background = roundedBg(ctx, color, 14, stroke)
        b.stateListAnimator = null
    }

    fun button(
        text: String,
        color: Int = UiColors.BUTTON,
        stroke: Int? = null,
        description: String? = null,
        onLong: (() -> Unit)? = null,
        longLabel: String? = null,
        onClick: () -> Unit
    ): Button {
        val b = Button(ctx)
        styleButton(b, text, color, stroke)
        if (description != null) b.contentDescription = description
        b.setOnClickListener { onClick() }
        if (onLong != null) {
            b.setOnLongClickListener { onLong(); true }
            if (longLabel != null) {
                ViewCompat.setAccessibilityDelegate(b, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK, longLabel))
                    }
                })
            }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, ctx.dp(3), 0, ctx.dp(3))
        root.addView(b, lp)
        return b
    }

    /** Side-by-side buttons. Each item: text to action. */
    fun row(vararg items: Pair<String, () -> Unit>): List<Button> {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        val out = ArrayList<Button>()
        for ((text, action) in items) {
            val b = Button(ctx)
            styleButton(b, text, if (text.startsWith("Stop")) UiColors.BUTTON_STOP else UiColors.BUTTON, null)
            b.textSize = 16f
            b.setOnClickListener { action() }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            lp.setMargins(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2))
            row.addView(b, lp)
            out.add(b)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, ctx.dp(1), 0, ctx.dp(1))
        root.addView(row, lp)
        return out
    }

    fun edit(label: String, initial: String = "", password: Boolean = false, onDone: ((String) -> Unit)? = null): EditText {
        val l = TextView(ctx)
        l.text = label
        l.setTextColor(UiColors.TEXT)
        l.textSize = 17f
        l.setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(2))
        root.addView(l)
        val e = EditText(ctx)
        e.setText(initial)
        e.hint = label
        e.textSize = 19f
        e.setTextColor(UiColors.TEXT)
        e.setHintTextColor(0xFFB0BEC5.toInt())
        e.setSingleLine(true)
        e.inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        e.imeOptions = EditorInfo.IME_ACTION_DONE
        e.background = roundedBg(ctx, 0xFF22313F.toInt(), 10, UiColors.STROKE)
        e.setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
        e.minHeight = ctx.dp(56)
        e.id = View.generateViewId()
        l.labelFor = e.id
        if (onDone != null) e.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEARCH) { onDone(v.text.toString()); true } else false
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, ctx.dp(2), 0, ctx.dp(6))
        root.addView(e, lp)
        return e
    }

    companion object {
        fun vertical(ctx: Context, padDp: Int = 10): LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(padDp), ctx.dp(6), ctx.dp(padDp), ctx.dp(6))
        }

        /** A simple scrolling screen: returns the scroll view and a builder for its content. */
        fun scrolling(ctx: Context): Pair<ScrollView, UiBuilder> {
            val scroll = ScrollView(ctx)
            scroll.setBackgroundColor(UiColors.BG)
            scroll.isFillViewport = true
            val content = vertical(ctx)
            scroll.addView(content)
            return scroll to UiBuilder(ctx, content)
        }
    }
}

/** A list of choices with a title (used for every picker). */
open class ChoiceScreen(
    private val title: String,
    private val intro: String?,
    private val choices: List<Pair<String, () -> Unit>>,
    private val backLabel: String = "Back"
) : Screen() {
    override fun create(): View {
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title(title)
        intro?.let { ui.info(it) }
        choices.forEach { (label, action) -> ui.button(label) { action() } }
        ui.button(backLabel) { host.back() }
        return scroll
    }
}

class ConfirmScreen(private val question: String, private val yesLabel: String, private val onYes: () -> Unit) : Screen() {
    override fun create(): View {
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Please confirm")
        ui.info(question)
        ui.button(yesLabel, color = UiColors.BUTTON_STOP) { onYes() }
        ui.button("Cancel") { host.back() }
        return scroll
    }
}

class TextScreen(private val title: String, private val paragraphs: List<String>) : Screen() {
    override fun create(): View {
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title(title)
        paragraphs.forEach { ui.info(it) }
        ui.button("Back") { host.back() }
        return scroll
    }
}
