package io.github.lettuceketchup.tvqs

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
// Nothing here takes focus: the Controller owns it, and render() draws its state.
// Views are built once and updated in place: rebuilding them per key took 65-190 ms on the TV.
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
        // No fading edges: they draw the row into an offscreen layer each frame, which held scrolling to 30 fps on the TV.
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
    private var tileViews = emptyList<TileView>()
    private var pane: Pane? = null
    private var last: Any? = null

    init {
        val dm = resources.displayMetrics
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dm.widthPixels * 3 / 100, 0, dm.widthPixels * 3 / 100, dm.heightPixels * 5 / 100)
            addView(paneHolder, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = gap })
            addView(FrameLayout(ctx).apply {
                background = rounded(0xBF000000.toInt(), 14) // the corner pill's 75% black
                setPadding(0, pad, 0, pad)
                addView(scroll, LayoutParams(MATCH, WRAP).apply { leftMargin = chev; rightMargin = chev })
                addView(chevLeft, LayoutParams(chev, px(18), Gravity.START or Gravity.CENTER_VERTICAL))
                addView(chevRight, LayoutParams(chev, px(18), Gravity.END or Gravity.CENTER_VERTICAL))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }, LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        visibility = GONE
    }

    fun render(state: UiState, c: Controller, tiles: Map<TileId, Tile>) {
        // Key UPs and repeated refreshes change nothing on screen; skip them.
        val snapshot = state to TileId.entries.map { c.valueOf(it) }
        if (snapshot == last) return
        val opening = state.barOpen && (last as? Pair<*, *>)?.first.let { it !is UiState || !it.barOpen }
        last = snapshot
        // Built on the first render (at service start, bar hidden), so the first MENU doesn't pay for it.
        if (tileViews.isEmpty()) {
            measureTiles(tiles.values)
            tileViews = TileId.entries.map { TileView(tiles.getValue(it)).also(row::addView) }
        }
        visibility = if (state.barOpen) VISIBLE else GONE
        if (!state.barOpen) return
        tileViews.forEachIndexed { i, v -> v.update(c, i == state.focus, animate = !opening) }

        // Centre the focused tile. All tiles before it are unfocused and the row's width never changes,
        // so the target is plain arithmetic, valid before this frame's layout.
        val focusLeft = state.focus * (tileW + gap)
        val x = (focusLeft + focusW / 2 - scroll.width / 2).coerceIn(0, maxOf(0, row.width - scroll.width))
        if (opening) scroll.scrollTo(x, 0) else scroll.smoothScrollTo(x, 0)

        val tile = tiles.getValue(TileId.entries[state.focus])
        if (state.paneIndex == null || tile !is ModeTile) {
            if (pane != null) { paneHolder.removeAllViews(); pane = null }
            return
        }
        val p = pane?.takeIf { it.tile == tile } ?: Pane(tile).also {
            paneHolder.removeAllViews()
            paneHolder.addView(it, LayoutParams(focusW, WRAP).apply { leftMargin = chev + focusLeft - x })
            pane = it
        }
        p.update(c, state.paneIndex)
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

    private inner class TileView(val t: Tile) : LinearLayout(context) {
        private val plain = rounded(0x14FFFFFF, 10)
        private val solid = rounded(Color.WHITE, 10)
        private val icon = image(ICONS.getValue(t.id), Color.WHITE)
        private val name = text(t.label, NAME_SP)
        private val under = text(t.label, NAME_SP).apply { alpha = 0.7f } // the name below the icon when unfocused
        private val hint = text(HINT, HINT_SP)
        // Level: a thin level bar as in the pill, beside the icon when unfocused and under the number when focused
        // (stacking it under the name made the whole bar taller). ▲▼ beside the name when focused.
        private val number = text("", NUMBER_SP)
        private val up = arrow(true)
        private val down = arrow(false)
        private val lightBar = levelBar(Color.WHITE)
        private val darkBar = levelBar(DARK)
        private val headBar = View(context).apply { background = lightBar }
        private val bar = View(context).apply { background = darkBar }
        // Other bodies: value at the bottom; when focused, ▲ the option above and ▼ the one below, each blank at an end.
        private val above = ArrowLine(true)
        private val value = text("", VALUE_SP)
        private val below = ArrowLine(false)
        private var focused: Boolean? = null

        init {
            orientation = VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(icon, LayoutParams(px(ICON), px(ICON)).apply { marginEnd = px(6) })
                addView(name, LayoutParams(0, WRAP, 1f))
                addView(hint, LayoutParams(WRAP, WRAP).apply { marginStart = px(6) })
                if (t is LevelTile) {
                    addView(headBar, LayoutParams(0, px(4), 1f).apply { marginStart = px(4) })
                    addView(up, LayoutParams(px(10), px(8)).apply { marginStart = px(6) })
                    addView(down, LayoutParams(px(10), px(8)).apply { marginStart = px(3) })
                }
            })
            addView(if (t is LevelTile) LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.BOTTOM
                addView(under)
                addView(number)
                addView(bar, LayoutParams(MATCH, px(4)).apply { topMargin = px(6); bottomMargin = px(4) })
            } else LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.BOTTOM
                addView(under)
                addView(above)
                addView(value)
                addView(below)
            }, LayoutParams(MATCH, 0, 1f))
            layoutParams = LayoutParams(tileW, tileH).apply { marginEnd = gap }
        }

        fun update(c: Controller, focused: Boolean, animate: Boolean) {
            val fg = if (focused) DARK else Color.WHITE
            if (focused != this.focused) {
                // Grow or shrink from the old size with scale: animating the size itself would re-layout every frame.
                if (animate && this.focused != null) {
                    scaleX = if (focused) tileW / focusW.toFloat() else focusW / tileW.toFloat()
                    scaleY = if (focused) tileH / focusH.toFloat() else focusH / tileH.toFloat()
                    animate().scaleX(1f).scaleY(1f).setDuration(FOCUS_MS)
                }
                this.focused = focused
                background = if (focused) solid else plain
                layoutParams = (layoutParams as LayoutParams).apply { width = if (focused) focusW else tileW; height = if (focused) focusH else tileH }
                icon.setColorFilter(fg)
                for (tv in listOf(name, hint, number, value)) tv.setTextColor(fg)
                name.visibility = if (focused) VISIBLE else GONE
                under.visibility = if (focused) GONE else VISIBLE
                headBar.visibility = if (focused) GONE else VISIBLE
                bar.visibility = if (focused) VISIBLE else GONE
                number.textSize = if (focused) NUMBER_SP else VALUE_SP
                number.typeface = if (focused) Typeface.DEFAULT_BOLD else MEDIUM
                up.setColorFilter(fg)
                down.setColorFilter(fg)
                value.typeface = if (focused) Typeface.DEFAULT_BOLD else MEDIUM
                hint.visibility = if (focused && t is ModeTile && t.hasPane) VISIBLE else GONE
            }
            val v = c.valueOf(t.id)
            when (t) {
                is LevelTile -> {
                    val level = v ?: 0
                    number.set("$level")
                    for (d in listOf(lightBar, darkBar)) d.level = level.coerceIn(0, 100) * 100 // ClipDrawable's level is 0–10000
                    up.visibility = if (!focused) GONE else if (level < 100) VISIBLE else INVISIBLE
                    down.visibility = if (!focused) GONE else if (level > 0) VISIBLE else INVISIBLE
                }
                is ModeTile -> {
                    val raw = v ?: -1 // the Preset tile's value is already an index into its options
                    val i = t.options.indexOfFirst { it.value == raw }
                    lines(modeLabel(t, raw), stepMode(i, t.options.size, true)?.let { t.options[it].label },
                        stepMode(i, t.options.size, false)?.let { t.options[it].label }, focused, fg)
                }
                SleepTile -> {
                    val left = v ?: 0
                    lines(if (left == 0) "Off" else "$left min left", nextLonger(left)?.let { "$it min" },
                        nextShorter(left)?.let { if (it == 0) "Off" else "$it min" }, focused, fg)
                }
                is ActionTile -> lines(if (focused) "Press OK" else "", null, null, focused, fg)
            }
        }

        private fun lines(text: String, aboveLabel: String?, belowLabel: String?, focused: Boolean, fg: Int) {
            value.set(text)
            above.set(aboveLabel, focused, fg)
            below.set(belowLabel, focused, fg)
        }
    }

    private inner class ArrowLine(up: Boolean) : LinearLayout(context) {
        private val arrow = arrow(up)
        private val label = text("", ARROW_SP).apply { alpha = 0.7f }

        init {
            gravity = Gravity.CENTER_VERTICAL
            addView(arrow, LayoutParams(px(10), px(8)).apply { marginEnd = px(5) })
            addView(label)
        }

        // Gone when unfocused, blank (but holding its line) at an end.
        fun set(text: String?, focused: Boolean, fg: Int) {
            visibility = if (!focused) GONE else if (text == null) INVISIBLE else VISIBLE
            label.set(text ?: "")
            label.setTextColor(fg)
            arrow.setColorFilter(fg)
        }
    }

    // Every option, a tick on the current one, the highlight inverted.
    private inner class Pane(val tile: ModeTile) : LinearLayout(context) {
        private val rows = tile.options.map { o ->
            val tick = image(R.drawable.ic_tick, Color.WHITE)
            val label = text(o.label, VALUE_SP)
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad - px(4), px(6), pad - px(4), px(6))
                addView(tick, LayoutParams(px(ICON), px(ICON)).apply { marginEnd = px(6) })
                addView(label)
            }
            Triple(row, tick, label)
        }
        private val highlightBg = rounded(Color.WHITE, 7)

        init {
            orientation = VERTICAL
            background = rounded(0xBF000000.toInt(), 14)
            setPadding(px(4), px(4), px(4), px(4))
            rows.forEach { addView(it.first) }
        }

        fun update(c: Controller, highlight: Int) {
            rows.forEachIndexed { i, (row, tick, label) ->
                val hi = i == highlight
                val fg = if (hi) DARK else Color.WHITE
                row.background = if (hi) highlightBg else null
                tick.setColorFilter(fg)
                tick.visibility = if (tile.options[i].value == c.valueOf(tile.id)) VISIBLE else INVISIBLE
                label.setTextColor(fg)
                label.typeface = if (hi) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
    }

    // setText relayouts even when the text is the same.
    private fun TextView.set(s: String) { if (text.toString() != s) text = s }

    private fun text(s: String, sp: Float) = TextView(context).apply {
        text = s
        textSize = sp
        setTextColor(Color.WHITE)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun image(res: Int, color: Int) = ImageView(context).apply { setImageResource(res); setColorFilter(color) }
    private fun arrow(up: Boolean) = image(R.drawable.ic_arrow, Color.WHITE).apply { if (!up) rotation = 180f }
    private fun levelBar(color: Int) =
        LayerDrawable(arrayOf(ColorDrawable(color and 0x00FFFFFF or 0x40000000), ClipDrawable(ColorDrawable(color), Gravity.START, ClipDrawable.HORIZONTAL)))
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
        const val FOCUS_MS = 150L
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val ICONS = mapOf(
            TileId.BACKLIGHT to R.drawable.ic_backlight, TileId.WARMTH to R.drawable.ic_warmth,
            TileId.PRESET to R.drawable.ic_preset, TileId.PICTURE to R.drawable.ic_picture,
            TileId.SOUND to R.drawable.ic_sound, TileId.OUTPUT to R.drawable.ic_output,
            TileId.SLEEP to R.drawable.ic_sleep, TileId.SCREEN_OFF to R.drawable.ic_screen_off,
        )
    }
}
