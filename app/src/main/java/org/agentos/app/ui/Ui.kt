package org.agentos.app.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small helpers shared by the App's screens (conversation, settings, onboarding). The UI is built
 * with platform Views in code: no androidx UI dependency (see the W8 report for why not Compose).
 */
object Ui {
    fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun color(ctx: Context, id: Int): Int = ctx.getColor(id)

    fun rounded(color: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
        }

    /**
     * targetSdk 36 is always edge-to-edge: the window draws behind the status and navigation bars and
     * the keyboard. Pads [root] by the system bars, the display cutout and the IME, so nothing is
     * hidden (the S2 stand-in App lost its top buttons under the bar without this).
     */
    fun applyInsets(root: View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
        root.requestApplyInsets()
    }

    fun text(ctx: Context, sizeSp: Float, colorRes: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(ctx.getColor(colorRes))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    /** A flat text button (no androidx / Material dependency). */
    fun textButton(ctx: Context, label: CharSequence, colorRes: Int, onClick: () -> Unit): TextView =
        text(ctx, 15f, colorRes, bold = true).apply {
            text = label
            gravity = Gravity.CENTER
            minHeight = dp(ctx, 40)
            minWidth = dp(ctx, 48)
            setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
            isClickable = true
            isFocusable = true
            val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = ta.getDrawable(0)
            ta.recycle()
            setOnClickListener { onClick() }
        }

    fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    fun wrapWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /**
     * A settings-style page: top bar with a back arrow and [title], a scrolling column below.
     * Returns the root (for setContentView) and the column to fill.
     */
    fun page(activity: android.app.Activity, title: CharSequence): Pair<View, LinearLayout> {
        val ctx: Context = activity
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ctx.getColor(org.agentos.app.R.color.ui_background))
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 4), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
        }
        bar.addView(textButton(ctx, "←", org.agentos.app.R.color.ui_text) { activity.finish() }.apply {
            contentDescription = "返回"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        })
        bar.addView(text(ctx, 20f, org.agentos.app.R.color.ui_text, bold = true).apply { this.text = title })
        root.addView(bar, matchWrap())
        root.addView(View(ctx).apply { setBackgroundColor(ctx.getColor(org.agentos.app.R.color.ui_divider)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        val scroll = android.widget.ScrollView(ctx).apply { isFillViewport = true }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 24))
        }
        scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        applyInsets(root)
        return root to column
    }

    fun sectionTitle(ctx: Context, title: CharSequence): TextView =
        text(ctx, 13f, org.agentos.app.R.color.ui_accent, bold = true).apply {
            this.text = title
            setPadding(dp(ctx, 4), dp(ctx, 20), dp(ctx, 4), dp(ctx, 8))
        }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx.getColor(org.agentos.app.R.color.ui_surface), dp(ctx, 14).toFloat())
        setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
    }

    /** A label / value pair inside a card; [warn] colors the value as a problem. */
    fun line(ctx: Context, label: CharSequence, value: CharSequence, warn: Boolean = false): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
            addView(text(ctx, 12f, org.agentos.app.R.color.ui_text_secondary).apply { this.text = label })
            addView(text(ctx, 15f, if (warn) org.agentos.app.R.color.ui_error else org.agentos.app.R.color.ui_text).apply {
                this.text = value
                setTextIsSelectable(true)
            })
        }

    fun paragraph(ctx: Context, value: CharSequence, colorRes: Int = org.agentos.app.R.color.ui_text_secondary): TextView =
        text(ctx, 14f, colorRes).apply {
            this.text = value
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
            setLineSpacing(0f, 1.15f)
        }

    /** A row of flat buttons, right-aligned. */
    fun buttons(ctx: Context, vararg items: Pair<CharSequence, () -> Unit>): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            items.forEach { (label, action) -> addView(textButton(ctx, label, org.agentos.app.R.color.ui_accent, action)) }
        }
}
