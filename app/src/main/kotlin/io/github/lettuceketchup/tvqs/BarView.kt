package io.github.lettuceketchup.tvqs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// The bar: a scrolling row of tiles along the bottom, and the list pane above the focused tile.
// Nothing here takes focus: the Controller owns it, and render() draws its state from scratch.
class BarView(ctx: Context) : FrameLayout(ctx) {
    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()
    private val pad = px(10)
    private val gap = px(8)
    private val chev = px(24) // strip margin each side that holds a chevron

    private val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
    private val scroll = HorizontalScrollView(ctx).apply {
        isFocusable = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isHorizontalFadingEdgeEnabled = true // tiles fade out at the edges instead of being cut
        setFadingEdgeLength(px(24))
        addView(row)
        setOnScrollChangeListener { _, _, _, _, _ -> updateChevrons() }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateChevrons() }
    }
    private val chevLeft = image(R.drawable.ic_chevron, Color.WHITE).apply { rotation = 180f }
    private val chevRight = image(R.drawable.ic_chevron, Color.WHITE)
    private val paneHolder = FrameLayout(ctx)

    // Tile sizes come from the text they must hold (first render), so nothing clips at the TV's font scale.
    private var tileW = 0
    private var tileH = 0
    private val focusW get() = tileW * 13 / 10
    private val focusH get() = tileH * 115 / 100
    private var wasOpen = false

    init {
        val dm = resources.displayMetrics
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dm.widthPixels * 3 / 100, 0, dm.widthPixels * 3 / 100, dm.heightPixels * 5 / 100)
            addView(paneHolder, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = gap })
            addView(FrameLayout(ctx).apply {
                background = rounded(0x99000000.toInt(), 13)
                setPadding(0, pad, 0, pad)
                addView(scroll, LayoutParams(MATCH, WRAP).apply { leftMargin = chev; rightMargin = chev })
                addView(chevLeft, LayoutParams(chev, px(18), Gravity.START or Gravity.CENTER_VERTICAL))
                addView(chevRight, LayoutParams(chev, px(18), Gravity.END or Gravity.CENTER_VERTICAL))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }, LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        visibility = GONE
    }

    fun render(state: UiState, c: Controller, tiles: Map<TileId, Tile>) {
        visibility = if (state.barOpen) VISIBLE else GONE
        val opening = state.barOpen && !wasOpen
        wasOpen = state.barOpen
        if (!state.barOpen) return
        if (tileW == 0) measureTiles(tiles.values)

        row.removeAllViews()
        TileId.entries.forEachIndexed { i, id -> row.addView(tile(tiles.getValue(id), c, i == state.focus)) }

        // Centre the focused tile. All tiles before it are unfocused and the row's width never changes,
        // so the target is plain arithmetic, valid before this frame's layout.
        val focusLeft = state.focus * (tileW + gap)
        val x = (focusLeft + focusW / 2 - scroll.width / 2).coerceIn(0, maxOf(0, row.width - scroll.width))
        if (opening) scroll.scrollTo(x, 0) else scroll.smoothScrollTo(x, 0)

        paneHolder.removeAllViews()
        val tile = tiles.getValue(TileId.entries[state.focus])
        if (state.paneIndex != null && tile is ModeTile) {
            paneHolder.addView(pane(tile, c, state.paneIndex), LayoutParams(focusW, WRAP).apply { leftMargin = chev + focusLeft - x })
        }
    }

    private fun updateChevrons() {
        chevLeft.visibility = if (scroll.canScrollHorizontally(-1)) VISIBLE else INVISIBLE
        chevRight.visibility = if (scroll.canScrollHorizontally(1)) VISIBLE else INVISIBLE
    }

    private fun measureTiles(tiles: Collection<Tile>) {
        val head = px(ICON + 6)
        val names = tiles.maxOf { head + textW(it.label, NAME_SP) }
        // Focused: the name with its OK hint, or a bold value with room for the pane's tick.
        val hinted = tiles.filter { it is ModeTile && it.hasPane }.maxOf { head + textW(it.label, NAME_SP) + px(6) + textW(HINT, HINT_SP) }
        val values = (tiles.filterIsInstance<ModeTile>().flatMap { t -> t.options.map { it.label } } + listOf("Custom", "120 min left", "Press OK"))
            .maxOf { head + textW(it, VALUE_SP, bold = true) }
        tileW = maxOf(names + 2 * pad, (maxOf(hinted, values) + 2 * pad) / 1.3f).toInt() + 2
        // The focused mode tile is the tallest: name row, then ▲ line, value, ▼ line.
        val tall = 2 * pad + maxOf(px(ICON), lineH(NAME_SP)) + px(4) + 2 * lineH(ARROW_SP) + lineH(VALUE_SP, bold = true)
        tileH = tall * 100 / 115 + 1
    }

    private fun tile(t: Tile, c: Controller, focused: Boolean): View {
        val fg = if (focused) DARK else Color.WHITE
        val v = c.valueOf(t.id)
        val body = when (t) {
            is LevelTile -> level(v ?: 0, focused, fg)
            is ModeTile -> {
                val value = v ?: -1 // the Preset tile's value is already an index into its options
                val i = t.options.indexOfFirst { it.value == value }
                lines(modeLabel(t, value), stepMode(i, t.options.size, true)?.let { t.options[it].label },
                    stepMode(i, t.options.size, false)?.let { t.options[it].label }, focused, fg)
            }
            SleepTile -> {
                val left = v ?: 0
                lines(if (left == 0) "Off" else "$left min left", nextLonger(left)?.let { "$it min" },
                    nextShorter(left)?.let { if (it == 0) "Off" else "$it min" }, focused, fg)
            }
            is ActionTile -> lines(if (focused) "Press OK" else "", null, null, focused, fg)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = if (focused) rounded(Color.WHITE, 10) else rounded(0x1FFFFFFF, 10).apply { setStroke(px(1), 0x80FFFFFF.toInt()) }
            setPadding(pad, pad, pad, pad)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(image(ICONS.getValue(t.id), fg), LinearLayout.LayoutParams(px(ICON), px(ICON)).apply { marginEnd = px(6) })
                addView(text(t.label, NAME_SP, false, fg), LinearLayout.LayoutParams(0, WRAP, 1f))
                if (focused && t is ModeTile && t.hasPane) addView(text(HINT, HINT_SP, false, fg), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = px(6) })
            })
            addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
            layoutParams = LinearLayout.LayoutParams(if (focused) focusW else tileW, if (focused) focusH else tileH).apply { marginEnd = gap }
        }
    }

    // Value at the bottom; when focused, ▲ the option above and ▼ the one below, each blank at an end.
    private fun lines(value: String, above: String?, below: String?, focused: Boolean, fg: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM
        if (focused) addView(arrowLine(true, above, fg))
        addView(text(value, VALUE_SP, focused, fg))
        if (focused) addView(arrowLine(false, below, fg))
    }

    private fun arrowLine(up: Boolean, label: String?, fg: Int) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        visibility = if (label == null) INVISIBLE else VISIBLE
        addView(arrow(up, fg), LinearLayout.LayoutParams(px(10), px(8)).apply { marginEnd = px(5) })
        addView(text(label ?: "", ARROW_SP, false, fg).apply { alpha = 0.7f })
    }

    // Number, and beside it a segmented bar with ▲ above and ▼ below when focused.
    private fun level(v: Int, focused: Boolean, fg: Int) = LinearLayout(context).apply {
        gravity = Gravity.BOTTOM
        addView(text("$v", NUMBER_SP, true, fg), LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            if (focused) addView(arrow(true, fg).apply { visibility = if (v < 100) VISIBLE else INVISIBLE }, LinearLayout.LayoutParams(px(10), px(8)))
            addView(Segments(context, (v + BACKLIGHT_STEP / 2) / BACKLIGHT_STEP, fg), LinearLayout.LayoutParams(px(10), 0, 1f).apply { topMargin = px(2); bottomMargin = px(2) })
            if (focused) addView(arrow(false, fg).apply { visibility = if (v > 0) VISIBLE else INVISIBLE }, LinearLayout.LayoutParams(px(10), px(8)))
        }, LinearLayout.LayoutParams(WRAP, MATCH))
    }

    // Every option, a tick on the current one, the highlight inverted.
    private fun pane(t: ModeTile, c: Controller, highlight: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(0xD9000000.toInt(), 10)
        setPadding(px(4), px(4), px(4), px(4))
        t.options.forEachIndexed { i, o ->
            val fg = if (i == highlight) DARK else Color.WHITE
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad - px(4), px(6), pad - px(4), px(6))
                if (i == highlight) background = rounded(Color.WHITE, 7)
                addView(image(R.drawable.ic_tick, fg).apply { visibility = if (o.value == c.valueOf(t.id)) VISIBLE else INVISIBLE },
                    LinearLayout.LayoutParams(px(ICON), px(ICON)).apply { marginEnd = px(6) })
                addView(text(o.label, VALUE_SP, i == highlight, fg))
            })
        }
    }

    private fun text(s: String, sp: Float, bold: Boolean, color: Int) = TextView(context).apply {
        text = s
        textSize = sp
        setTextColor(color)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun image(res: Int, color: Int) = ImageView(context).apply { setImageResource(res); setColorFilter(color) }
    private fun arrow(up: Boolean, color: Int) = image(R.drawable.ic_arrow, color).apply { if (!up) rotation = 180f }
    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = px(radiusDp).toFloat() }

    private fun paint(sp: Float, bold: Boolean) = Paint().apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
    private fun textW(s: String, sp: Float, bold: Boolean = false) = paint(sp, bold).measureText(s)
    private fun lineH(sp: Float, bold: Boolean = false) = paint(sp, bold).fontMetricsInt.let { it.bottom - it.top }

    private companion object {
        const val MATCH = LayoutParams.MATCH_PARENT
        const val WRAP = LayoutParams.WRAP_CONTENT
        const val DARK = 0xFF1A1A1A.toInt()
        const val ICON = 18 // dp
        const val NAME_SP = 13f
        const val VALUE_SP = 16f
        const val ARROW_SP = 12f
        const val HINT_SP = 11f
        const val NUMBER_SP = 22f
        const val HINT = "OK …"
        val ICONS = mapOf(
            TileId.BACKLIGHT to R.drawable.ic_backlight, TileId.WARMTH to R.drawable.ic_warmth,
            TileId.PRESET to R.drawable.ic_preset, TileId.PICTURE to R.drawable.ic_picture,
            TileId.SOUND to R.drawable.ic_sound, TileId.OUTPUT to R.drawable.ic_output,
            TileId.SLEEP to R.drawable.ic_sleep, TileId.SCREEN_OFF to R.drawable.ic_screen_off,
        )
    }
}

// Backlight's segments, bottom up: filled ones solid, the rest faint.
private class Segments(ctx: Context, private val filled: Int, private val color: Int) : View(ctx) {
    private val paint = Paint()

    override fun onDraw(c: Canvas) {
        val n = 100 / BACKLIGHT_STEP
        val g = resources.displayMetrics.density
        val h = (height - (n - 1) * g) / n
        for (i in 0 until n) {
            paint.color = if (i < filled) color else color and 0x00FFFFFF or 0x40000000
            val top = height - (i + 1) * h - i * g
            c.drawRect(0f, top, width.toFloat(), top + h, paint)
        }
    }
}
