package org.linox.mobile.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.max
import kotlin.math.min

class TerminalView(context: Context) : View(context) {

    private var cols = 80
    private var rows = 24

    private var cellW = 0f
    private var cellH = 0f
    private var fontSize = 15f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        isSubpixelText = true
    }

    private val bg = Color.rgb(13, 15, 18)
    private val fg = Color.rgb(232, 235, 238)

    private var cx = 0
    private var cy = 0

    private var savedX = 0
    private var savedY = 0

    private var cursorVisible = true

    private var fgColor = fg
    private var bgColor = bg

    private var bold = false
    private var inverse = false

    private var state = State.NORMAL
    private val csi = StringBuilder()

    private val grid = ArrayList<CharCell>()
    private val altGrid = ArrayList<CharCell>()

    private var usingAlt = false

    private val scrollback = ArrayDeque<Array<CharCell>>()
    private val maxScrollback = 1200

    data class CharCell(
        var ch: Char = ' ',
        var fg: Int = Color.rgb(232, 235, 238),
        var bg: Int = Color.rgb(13, 15, 18),
        var bold: Boolean = false
    )

    enum class State {
        NORMAL,
        ESC,
        CSI
    }

    init {
        isFocusable = true
        setBackgroundColor(bg)
        rebuildGrid(80, 24)
    }

    private fun blank(): CharCell {
        return CharCell()
    }

    private fun rebuildGrid(c: Int, r: Int) {
        cols = max(20, c)
        rows = max(5, r)

        grid.clear()
        altGrid.clear()

        repeat(cols * rows) {
            grid.add(blank())
            altGrid.add(blank())
        }

        cx = 0
        cy = 0

        invalidate()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int
    ) {
        paint.textSize = fontSize

        val fm = paint.fontMetrics

        cellW = max(1f, paint.measureText("M"))
        cellH = max(1f, fm.descent - fm.ascent + 2f)

        val newCols = max(20, (w / cellW).toInt())
        val newRows = max(5, (h / cellH).toInt())

        if (newCols != cols || newRows != rows) {
            rebuildGrid(newCols, newRows)
        }
    }

    fun dimensions(): Pair<Int, Int> {
        return Pair(cols, rows)
    }

    fun feed(text: String) {
        for (ch in text) {
            consume(ch)
        }

        postInvalidate()
    }

    private fun active(): ArrayList<CharCell> {
        return if (usingAlt) altGrid else grid
    }

    private fun idx(x: Int, y: Int): Int {
        return y * cols + x
    }

    private fun put(ch: Char) {
        if (cx >= cols) {
            cx = 0
            newline()
        }

        active()[idx(cx, cy)] = CharCell(
            ch = ch,
            fg = if (inverse) bgColor else fgColor,
            bg = if (inverse) fgColor else bgColor,
            bold = bold
        )

        cx++
    }

    private fun newline() {
        cy++

        if (cy < rows) {
            return
        }

        cy = rows - 1

        if (!usingAlt) {
            val line = Array(cols) { x ->
                active()[idx(x, cy)].copy()
            }

            scrollback.addLast(line)

            while (scrollback.size > maxScrollback) {
                scrollback.removeFirst()
            }
        }

        var y = 1

        while (y < rows) {
            var x = 0

            while (x < cols) {
                active()[idx(x, y - 1)] =
                    active()[idx(x, y)].copy()

                x++
            }

            y++
        }

        var x = 0

        while (x < cols) {
            active()[idx(x, rows - 1)] = blank()
            x++
        }
    }

    private fun consume(ch: Char) {
        when (state) {

            State.NORMAL -> {
                when (ch.code) {

                    0x1b -> {
                        state = State.ESC
                    }

                    10 -> {
                        newline()
                    }

                    13 -> {
                        cx = 0
                    }

                    8 -> {
                        cx = max(0, cx - 1)
                    }

                    9 -> {
                        val nextTab =
                            ((cx / 8) + 1) * 8

                        cx = min(cols - 1, nextTab)
                    }

                    7 -> {
                        // BEL
                    }

                    in 32..126 -> {
                        put(ch)
                    }

                    else -> {
                        if (ch.code >= 0xA0) {
                            put(ch)
                        }
                    }
                }
            }

            State.ESC -> {
                when (ch) {

                    '[' -> {
                        csi.clear()
                        state = State.CSI
                    }

                    '7' -> {
                        savedX = cx
                        savedY = cy
                        state = State.NORMAL
                    }

                    '8' -> {
                        cx = savedX.coerceIn(0, cols - 1)
                        cy = savedY.coerceIn(0, rows - 1)
                        state = State.NORMAL
                    }

                    'c' -> {
                        clear()
                        state = State.NORMAL
                    }

                    'D' -> {
                        newline()
                        state = State.NORMAL
                    }

                    'M' -> {
                        cy = max(0, cy - 1)
                        state = State.NORMAL
                    }

                    '=',
                    '>' -> {
                        state = State.NORMAL
                    }

                    else -> {
                        state = State.NORMAL
                    }
                }
            }

            State.CSI -> {
                if (isCsiFinal(ch)) {
                    applyCsi(csi.toString(), ch)
                    state = State.NORMAL
                } else {
                    csi.append(ch)
                }
            }
        }
    }

    private fun isCsiFinal(ch: Char): Boolean {
        return ch.code in 0x40..0x7E
    }

    private fun nums(value: String): List<Int> {
        val source = value.removePrefix("?")

        if (source.isEmpty()) {
            return listOf(0)
        }

        return source
            .split(';')
            .map { part ->
                part.toIntOrNull() ?: 0
            }
    }

    private fun applyCsi(
        raw: String,
        final: Char
    ) {
        val privateMode = raw.startsWith("?")
        val numbers = nums(raw)

        fun arg(index: Int, default: Int): Int {
            if (index >= numbers.size) {
                return default
            }

            val value = numbers[index]

            return if (value == 0) default else value
        }

        when (final) {

            'A' -> {
                cy = max(0, cy - arg(0, 1))
            }

            'B' -> {
                cy = min(rows - 1, cy + arg(0, 1))
            }

            'C' -> {
                cx = min(cols - 1, cx + arg(0, 1))
            }

            'D' -> {
                cx = max(0, cx - arg(0, 1))
            }

            'E' -> {
                cy = min(rows - 1, cy + arg(0, 1))
                cx = 0
            }

            'F' -> {
                cy = max(0, cy - arg(0, 1))
                cx = 0
            }

            'G',
            '`' -> {
                cx = (arg(0, 1) - 1)
                    .coerceIn(0, cols - 1)
            }

            'd' -> {
                cy = (arg(0, 1) - 1)
                    .coerceIn(0, rows - 1)
            }

            'H',
            'f' -> {
                cy = (arg(0, 1) - 1)
                    .coerceIn(0, rows - 1)

                cx = (arg(1, 1) - 1)
                    .coerceIn(0, cols - 1)
            }

            'J' -> {
                when (arg(0, 0)) {
                    0 -> eraseToEnd()
                    1 -> eraseToStart()
                    2, 3 -> clear()
                }
            }

            'K' -> {
                when (arg(0, 0)) {
                    0 -> eraseLineFromCursor()
                    1 -> eraseLineToCursor()
                    2 -> eraseLine()
                }
            }

            'm' -> {
                sgr(numbers)
            }

            's' -> {
                savedX = cx
                savedY = cy
            }

            'u' -> {
                cx = savedX.coerceIn(0, cols - 1)
                cy = savedY.coerceIn(0, rows - 1)
            }

            'h',
            'l' -> {
                if (privateMode && numbers.contains(1049)) {
                    switchAlt(final == 'h')
                }
            }
        }
    }

    private fun sgr(values: List<Int>) {
        if (values.isEmpty()) {
            resetStyle()
            return
        }

        for (value in values) {
            when (value) {

                0 -> {
                    resetStyle()
                }

                1 -> {
                    bold = true
                }

                7 -> {
                    inverse = true
                }

                22 -> {
                    bold = false
                }

                27 -> {
                    inverse = false
                }

                in 30..37 -> {
                    fgColor = ansi(value - 30, false)
                }

                in 40..47 -> {
                    bgColor = ansi(value - 40, false)
                }

                39 -> {
                    fgColor = fg
                }

                49 -> {
                    bgColor = bg
                }

                in 90..97 -> {
                    fgColor = ansi(value - 90, true)
                }

                in 100..107 -> {
                    bgColor = ansi(value - 100, true)
                }
            }
        }
    }

    private fun ansi(
        index: Int,
        bright: Boolean
    ): Int {
        val normal = intArrayOf(
            0xFF000000.toInt(),
            0xFFAA0000.toInt(),
            0xFF00AA00.toInt(),
            0xFFAA5500.toInt(),
            0xFF0000AA.toInt(),
            0xFFAA00AA.toInt(),
            0xFF00AAAA.toInt(),
            0xFFAAAAAA.toInt()
        )

        val brightColors = intArrayOf(
            0xFF777777.toInt(),
            0xFFFF5555.toInt(),
            0xFF55FF55.toInt(),
            0xFFFFFF55.toInt(),
            0xFF5555FF.toInt(),
            0xFFFF55FF.toInt(),
            0xFF55FFFF.toInt(),
            0xFFFFFFFF.toInt()
        )

        val palette = if (bright) brightColors else normal

        return palette[index.coerceIn(0, 7)]
    }

    private fun resetStyle() {
        fgColor = fg
        bgColor = bg
        bold = false
        inverse = false
    }

    private fun eraseLine() {
        var x = 0

        while (x < cols) {
            active()[idx(x, cy)] = blank()
            x++
        }
    }

    private fun eraseLineFromCursor() {
        var x = cx

        while (x < cols) {
            active()[idx(x, cy)] = blank()
            x++
        }
    }

    private fun eraseLineToCursor() {
        var x = 0

        while (x <= cx && x < cols) {
            active()[idx(x, cy)] = blank()
            x++
        }
    }

    private fun eraseToEnd() {
        var y = cy

        while (y < rows) {
            var x = 0

            while (x < cols) {
                if (y > cy || x >= cx) {
                    active()[idx(x, y)] = blank()
                }

                x++
            }

            y++
        }
    }

    private fun eraseToStart() {
        var y = 0

        while (y <= cy && y < rows) {
            var x = 0

            while (x < cols) {
                if (y < cy || x <= cx) {
                    active()[idx(x, y)] = blank()
                }

                x++
            }

            y++
        }
    }

    private fun clear() {
        val cells = active()

        var i = 0

        while (i < cells.size) {
            cells[i] = blank()
            i++
        }

        cx = 0
        cy = 0
    }

    private fun switchAlt(on: Boolean) {
        if (on == usingAlt) {
            return
        }

        if (on) {
            var i = 0

            while (i < altGrid.size) {
                altGrid[i] = blank()
                i++
            }

            usingAlt = true
        } else {
            usingAlt = false
        }

        cx = 0
        cy = 0
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawColor(bg)

        val cells = active()

        var y = 0

        while (y < rows) {
            var x = 0

            while (x < cols) {

                val cell = cells[idx(x, y)]

                val left = x * cellW
                val top = y * cellH

                if (cell.bg != bg) {
                    paint.color = cell.bg

                    canvas.drawRect(
                        left,
                        top,
                        left + cellW,
                        top + cellH,
                        paint
                    )
                }

                if (cell.ch != ' ') {
                    paint.color = cell.fg
                    paint.textSize = fontSize

                    paint.typeface = if (cell.bold) {
                        Typeface.create(
                            Typeface.MONOSPACE,
                            Typeface.BOLD
                        )
                    } else {
                        Typeface.MONOSPACE
                    }

                    canvas.drawText(
                        cell.ch.toString(),
                        left,
                        top - paint.fontMetrics.ascent,
                        paint
                    )
                }

                x++
            }

            y++
        }

        if (cursorVisible) {
            paint.color = fg

            canvas.drawRect(
                cx * cellW,
                cy * cellH,
                cx * cellW + 2f,
                cy * cellH + cellH,
                paint
            )
        }
    }

    fun setFontSize(sp: Float) {
        fontSize = sp
        requestLayout()
        invalidate()
    }

    fun setCursorVisible(visible: Boolean) {
        cursorVisible = visible
        invalidate()
    }
}
