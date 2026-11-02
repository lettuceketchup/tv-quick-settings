package io.github.lettuceketchup.tvqs

import android.animation.ValueAnimator
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
        if (!state.barOpen) { paneHolder.removeAllViews(); pane = null; return }
        tileViews.forEachIndexed { i, v -> v.update(c, i == state.focus, animate = !opening) }

        // Centre the focused tile. All tiles before it are unfocused and the row's width never changes,
        // so the target is plain arithmetic, valid before this frame's layout.
        val focusLeft = state.focus * (tileW + gap)
        val x = (focusLeft + focusW / 2 - scroll.width / 2).coerceIn(0, maxOf(0, row.width - scroll.width))
        if (opening) scroll.scrollTo(x, 0) else scroll.smoothScrollTo(x, 0)

        val tile = tiles.getValue(TileId.entries[state.focus])
        if (state.paneIndex == null || tile !is ModeTile) {
            pane?.let { old ->
                old.animate().alpha(0f).translationY(px(12).toFloat()).setDuration(ANIM_MS).withEndAction { paneHolder.removeView(old) }
                pane = null
            }
            return
        }
        val p = pane?.takeIf { it.tile == tile } ?: Pane(tile).also {
            paneHolder.removeAllViews()
            paneHolder.addView(it, LayoutParams(focusW, WRAP).apply { leftMargin = chev + focusLeft - x })
            it.alpha = 0f
            it.translationY = px(12).toFloat()
            it.animate().alpha(1f).translationY(0f).setDuration(ANIM_MS)
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
        // Level: the level in chunks, beside the icon when unfocused and under the number when focused
        // (stacking it under the name made the whole bar taller). ▲▼ beside the name when focused.
        private val number = text("", NUMBER_SP)
        private val up = arrow(true)
        private val down = arrow(false)
        private val headBar = Chunks(context, Color.WHITE)
        private val bar = Chunks(context, DARK)
        private val body = LinearLayout(context) // slides when Up/Down changes the value
        private var rank: Int? = null // the value's place, top to bottom, for the slide's direction
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
                    addView(headBar, LayoutParams(0, px(8), 1f).apply { marginStart = px(4) })
                    addView(up, LayoutParams(px(10), px(8)).apply { marginStart = px(6) })
                    addView(down, LayoutParams(px(10), px(8)).apply { marginStart = px(3) })
                }
            })
            addView(if (t is LevelTile) LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.BOTTOM
                addView(under)
                addView(number)
                addView(bar, LayoutParams(MATCH, px(8)).apply { topMargin = px(4); bottomMargin = px(2) })
            } else body.apply {
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
            val wasFocused = this.focused == true
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
                    val chunks = (level.coerceIn(0, 100) + BACKLIGHT_STEP / 2) / BACKLIGHT_STEP
                    headBar.set(chunks, animate)
                    bar.set(chunks, animate)
                    slide(number, -level, animate && wasFocused && focused) // a higher number rolls up from below
                    up.visibility = if (!focused) GONE else if (level < 100) VISIBLE else INVISIBLE
                    down.visibility = if (!focused) GONE else if (level > 0) VISIBLE else INVISIBLE
                }
                is ModeTile -> {
                    val raw = v ?: -1 // the Preset tile's value is already an index into its options
                    val i = t.options.indexOfFirst { it.value == raw }
                    slide(body, i, animate && wasFocused && focused)
                    lines(modeLabel(t, raw), stepMode(i, t.options.size, true)?.let { t.options[it].label },
                        stepMode(i, t.options.size, false)?.let { t.options[it].label }, focused, fg)
                }
                SleepTile -> {
                    val left = v ?: 0
                    slide(body, -left, animate && wasFocused && focused) // longer times sit above
                    lines(if (left == 0) "Off" else "$left min left", nextLonger(left)?.let { "$it min" },
                        nextShorter(left)?.let { if (it == 0) "Off" else "$it min" }, focused, fg)
                }
                is ActionTile -> lines(if (focused) "Press OK" else "", null, null, focused, fg)
            }
        }

        // The ▲ option sits above the value, so moving up the list brings the new value down from above.
        private fun slide(view: View, rank: Int, animate: Boolean) {
            val old = this.rank
            this.rank = rank
            if (!animate || old == null || old == rank) return
            view.translationY = px(if (rank < old) -10 else 10).toFloat()
            view.alpha = 0.3f
            view.animate().translationY(0f).alpha(1f).setDuration(ANIM_MS)
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

    // Every option, a tick on the current one, the highlight inverted. The highlight slides between rows,
    // and the tick swells for a moment when it moves to a new option.
    private inner class Pane(val tile: ModeTile) : FrameLayout(context) {
        private val rowH = maxOf(px(ICON), lineH(VALUE_SP, bold = true)) + px(12) // fixed, so the slide target needs no layout
        private val rows = tile.options.map { o ->
            val tick = image(R.drawable.ic_tick, Color.WHITE)
            val label = text(o.label, VALUE_SP)
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad - px(4), 0, pad - px(4), 0)
                addView(tick, LayoutParams(px(ICON), px(ICON)).apply { marginEnd = px(6) })
                addView(label)
            }
            Triple(row, tick, label)
        }
        private val highlightView = View(context).apply { background = rounded(Color.WHITE, 7) }
        private var shown = -1
        private var current: Int? = null
        private val recolor = Runnable { colour() }

        init {
            background = rounded(0xBF000000.toInt(), 14)
            setPadding(px(4), px(4), px(4), px(4))
            addView(highlightView, LayoutParams(MATCH, rowH))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                rows.forEach { addView(it.first, LinearLayout.LayoutParams(MATCH, rowH)) }
            })
        }

        fun update(c: Controller, highlight: Int) {
            val cur = c.valueOf(tile.id)
            rows.forEachIndexed { i, (_, tick, _) ->
                val ticked = tile.options[i].value == cur
                tick.visibility = if (ticked) VISIBLE else INVISIBLE
                if (ticked && current != null && cur != current) {
                    tick.scaleX = 1.6f
                    tick.scaleY = 1.6f
                    tick.animate().scaleX(1f).scaleY(1f).setDuration(200)
                }
            }
            current = cur
            if (highlight == shown) return
            val y = (highlight * rowH).toFloat()
            removeCallbacks(recolor)
            if (shown < 0) { highlightView.translationY = y; shown = highlight; colour(); return }
            shown = highlight
            highlightView.animate().translationY(y).setDuration(ANIM_MS)
            postDelayed(recolor, ANIM_MS / 2) // swap the text colours when the highlight is halfway across
        }

        private fun colour() = rows.forEachIndexed { i, (_, tick, label) ->
            val fg = if (i == shown) DARK else Color.WHITE
            tick.setColorFilter(fg)
            label.setTextColor(fg)
            label.typeface = if (i == shown) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
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
        const val ANIM_MS = 150L
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val ICONS = mapOf(
            TileId.BACKLIGHT to R.drawable.ic_backlight, TileId.WARMTH to R.drawable.ic_warmth,
            TileId.PRESET to R.drawable.ic_preset, TileId.PICTURE to R.drawable.ic_picture,
            TileId.SOUND to R.drawable.ic_sound, TileId.OUTPUT to R.drawable.ic_output,
            TileId.SLEEP to R.drawable.ic_sleep, TileId.SCREEN_OFF to R.drawable.ic_screen_off,
        )
    }
}

// Backlight's level as 10 chunks with small gaps; the chunk that fills or empties swells for a moment.
private class Chunks(ctx: Context, private val color: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var filled = -1
    private var pulse = -1
    private var swell = 0f // 1 at the start of a pulse, 0 at rest
    private val anim = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = 180
        addUpdateListener { swell = it.animatedValue as Float; invalidate() }
    }

    fun set(filled: Int, animate: Boolean) {
        if (filled == this.filled) return
        if (animate && this.filled >= 0) { pulse = maxOf(filled, this.filled) - 1; anim.start() }
        this.filled = filled
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val n = 100 / BACKLIGHT_STEP
        val d = resources.displayMetrics.density
        val gap = 2 * d
        val w = (width - (n - 1) * gap) / n
        for (i in 0 until n) {
            paint.color = if (i < filled) color else color and 0x00FFFFFF or 0x40000000
            val h = height / 2f * (1 + if (i == pulse) swell else 0f) // rests at half the view's height, swells to all of it
            val left = i * (w + gap)
            val top = (height - h) / 2
            c.drawRoundRect(left, top, left + w, top + h, d, d, paint)
        }
    }
}
