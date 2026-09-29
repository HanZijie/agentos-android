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
}
