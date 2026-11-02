package io.github.lettuceketchup.tvqs

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// The corner pill: icon, title, detail and a thin level bar on about 75% black. Fades in and out.
class PillView(ctx: Context) : LinearLayout(ctx) {
    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private val title = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD }
    private val detail = TextView(ctx).apply { setTextColor(0xD9FFFFFF.toInt()); textSize = 14f }
    private val level = View(ctx).apply {
        background = LayerDrawable(arrayOf(ColorDrawable(0x40FFFFFF), ClipDrawable(ColorDrawable(Color.WHITE), Gravity.START, ClipDrawable.HORIZONTAL)))
    }
    private val icon = ImageView(ctx)
    private val caps = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
    private val hideLater = Runnable { hide() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(0xBF000000.toInt()); cornerRadius = px(14).toFloat() }
        setPadding(px(16), px(12), px(20), px(12))
        addView(icon, LayoutParams(px(28), px(28)).apply { marginEnd = px(14) })
        // Fixed width: a MATCH_PARENT level bar in a wrap-content column stretched the pill to half the screen.
        addView(LinearLayout(ctx).apply {
            orientation = VERTICAL
            addView(title)
            addView(detail)
            addView(level, LayoutParams(LayoutParams.MATCH_PARENT, px(4)).apply { topMargin = px(8) })
            addView(caps, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) })
        }, LayoutParams(px(220), LayoutParams.WRAP_CONTENT))
        alpha = 0f
    }

    // keys: (key, what it does) pairs, drawn as key caps under the detail line.
    fun show(p: Effect.Pill, iconRes: Int = R.drawable.ic_backlight, keys: List<Pair<String, String>> = emptyList()) {
        icon.setImageResource(iconRes)
        caps.removeAllViews()
        caps.visibility = if (keys.isEmpty()) GONE else VISIBLE
        for ((key, action) in keys) {
            caps.addView(TextView(context).apply {
                text = key
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF1A1A1A.toInt())
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = px(4).toFloat() }
                setPadding(px(6), px(1), px(6), px(1))
            })
            caps.addView(TextView(context).apply { text = action; textSize = 14f; setTextColor(Color.WHITE) },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = px(6); marginEnd = px(16) })
        }
        title.text = p.title
        detail.text = p.detail
        level.visibility = if (p.levelPercent == null) GONE else VISIBLE
        level.background.level = (p.levelPercent ?: 0).coerceIn(0, 100) * 100 // ClipDrawable's level is 0–10000
        removeCallbacks(hideLater)
        animate().alpha(1f).setDuration(150)
        if (p.millis > 0) postDelayed(hideLater, p.millis)
    }

    fun hide() {
        removeCallbacks(hideLater)
        animate().alpha(0f).setDuration(300)
    }
}
