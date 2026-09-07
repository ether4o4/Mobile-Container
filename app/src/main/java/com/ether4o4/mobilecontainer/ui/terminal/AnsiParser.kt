package com.ether4o4.mobilecontainer.ui.terminal

import com.ether4o4.mobilecontainer.ui.theme.ansiColors
import androidx.compose.ui.graphics.Color

/**
 * A self-contained ANSI/VT100 terminal emulator. Maintains a screen grid of
 * rows x cols cells, each carrying a char plus foreground/background color and
 * style flags (bold, italic, underline). Handles CSI SGR (16/256/truecolor),
 * cursor movement, erase, scroll, and basic control chars.
 *
 * No external dependencies.
 */
class ScreenBuffer(
    initialRows: Int = 24,
    initialCols: Int = 80,
    private val maxScrollbackLines: Int = DEFAULT_SCROLLBACK_LINES
) {

    data class Cell(
        var ch: Char = ' ',
        var fg: Int = 7,        // index into ansiColors (0..15), -1 = default
        var bg: Int = -1,
        var bold: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false
    )

    /** Immutable copy of the portion of the terminal currently being viewed. */
    data class ViewportSnapshot(
        val rows: Int,
        val cols: Int,
        val cells: Array<Cell>,
        val cursorRow: Int,
        val cursorCol: Int,
        val cursorVisible: Boolean,
        val historySize: Int,
        val startLine: Int,
        val totalLines: Int
    )

    var rows: Int = initialRows
        private set
    var cols: Int = initialCols
        private set

    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set
    var cursorVisible: Boolean = true

    private var scrollTop: Int = 0
    private var scrollBottom: Int = initialRows - 1

    private var grid: Array<Cell> = Array(initialRows * initialCols) { Cell() }
    private val scrollback = ArrayDeque<Array<Cell>>()

    // current SGR state
    private var curFg: Int = 7
    private var curBg: Int = -1
    private var curBold = false
    private var curItalic = false
    private var curUnderline = false

    private val sb = StringBuilder()

    /** Snapshot of cells for rendering. */
    fun cells(): Array<Cell> = grid
    fun snapshot(): Array<Cell> = Array(grid.size) { grid[it].copy() }
    fun historySize(): Int = scrollback.size
    fun totalLineCount(): Int = scrollback.size + rows

    /**
     * Returns [rows] lines ending [linesFromBottom] lines above the live bottom.
     * This copy lets Compose render without racing the PTY reader thread.
     */
    fun viewport(linesFromBottom: Int = 0): ViewportSnapshot {
        val total = totalLineCount()
        val maxOffset = scrollback.size
        val offset = linesFromBottom.coerceIn(0, maxOffset)
        val start = (total - rows - offset).coerceAtLeast(0)
        val history = scrollback.toList()
        val out = Array(rows * cols) { Cell() }
        for (viewRow in 0 until rows) {
            val lineIndex = start + viewRow
            val source = when {
                lineIndex < history.size -> history[lineIndex]
                lineIndex - history.size in 0 until rows -> rowCopy(lineIndex - history.size)
                else -> null
            }
            if (source != null) {
                for (col in 0 until minOf(cols, source.size)) {
                    out[viewRow * cols + col] = source[col].copy()
                }
            }
        }
        val visibleCursorRow = history.size + cursorRow - start
        val atBottom = offset == 0
        return ViewportSnapshot(
            rows = rows,
            cols = cols,
            cells = out,
            cursorRow = visibleCursorRow.coerceIn(0, rows - 1),
            cursorCol = cursorCol.coerceIn(0, cols - 1),
            cursorVisible = cursorVisible && atBottom && visibleCursorRow in 0 until rows,
            historySize = history.size,
            startLine = start,
            totalLines = total
        )
    }

    fun resize(newRows: Int, newCols: Int) {
        val r = newRows.coerceAtLeast(2)
        val c = newCols.coerceAtLeast(8)
        if (r == rows && c == cols) return

        val oldRows = rows
        val oldCols = cols
        val oldGrid = grid
        val oldCursorRow = cursorRow
        val linesToKeep = minOf(oldRows, r)
        val oldStart = if (r < oldRows) {
            (oldCursorRow - linesToKeep + 1).coerceIn(0, oldRows - linesToKeep)
        } else {
            0
        }
        val newStart = 0

        if (r < oldRows && scrollTop == 0 && scrollBottom == oldRows - 1) {
            for (row in 0 until oldStart) appendScrollback(rowCopy(row))
        }

        val ng = Array(r * c) { Cell() }
        for (i in 0 until linesToKeep) {
            val oldRow = oldStart + i
            val newRow = newStart + i
            for (col in 0 until minOf(oldCols, c)) {
                ng[newRow * c + col] = oldGrid[oldRow * oldCols + col].copy()
            }
        }
        grid = ng
        rows = r
        cols = c
        scrollTop = 0
        scrollBottom = r - 1
        cursorRow = (newStart + (oldCursorRow - oldStart)).coerceIn(0, r - 1)
        cursorCol = cursorCol.coerceIn(0, c - 1)
    }

    fun write(bytes: ByteArray) {
        for (b in bytes) writeByte(b.toInt() and 0xFF)
    }

    fun writeText(text: String) {
        for (c in text) {
            if (c.code <= 0xFF) writeByte(c.code) else writeChar(c)
        }
    }

    private fun writeByte(b: Int) {
        when {
            b == 0x1B -> { sb.setLength(0); sb.append(0x1b.toChar()) } // ESC
            sb.isNotEmpty() && sb[0] == 0x1b.toChar() -> {
                sb.append(b.toChar())
                val s = sb.toString()
                val consumed = tryConsumeEscape(s)
                if (consumed) sb.setLength(0)
                else if (s.length > 64) sb.setLength(0) // give up on garbage
            }
            b == 0x0D -> { cursorCol = 0 }
            b == 0x0A -> { lineFeed() }
            b == 0x08 -> { if (cursorCol > 0) cursorCol-- }
            b == 0x09 -> {
                val next = ((cursorCol / 8) + 1) * 8
                cursorCol = minOf(next, cols - 1)
            }
            b == 0x07 -> { /* BEL */ }
            b >= 0x20 && b < 0x7F -> writeChar(b.toChar())
            b >= 0x80 -> writeChar(b.toChar()) // latin-1 fallback
            else -> {}
        }
    }

    private fun writeChar(c: Char) {
        if (cursorCol >= cols) { cursorCol = 0; lineFeed() }
        val idx = cursorRow * cols + cursorCol
        if (idx in grid.indices) {
            val cell = grid[idx]
            cell.ch = c
            cell.fg = curFg
            cell.bg = curBg
            cell.bold = curBold
            cell.italic = curItalic
            cell.underline = curUnderline
        }
        cursorCol++
    }

    private fun lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun scrollUp(n: Int) {
        val span = scrollBottom - scrollTop + 1
        val k = minOf(n, span)
        // Alternate-screen applications often install a partial scroll region;
        // only normal, whole-screen scrolling belongs in user-visible history.
        if (scrollTop == 0 && scrollBottom == rows - 1) {
            for (row in 0 until k) appendScrollback(rowCopy(row))
        }
        for (row in scrollTop until scrollBottom - k + 1) {
            val src = (row + k) * cols
            val dst = row * cols
            System.arraycopy(grid, src, grid, dst, cols)
        }
        for (row in (scrollBottom - k + 1)..scrollBottom) {
            for (col in 0 until cols) grid[row * cols + col] = Cell()
        }
    }

    private fun rowCopy(row: Int): Array<Cell> =
        Array(cols) { col -> grid[row * cols + col].copy() }

    private fun appendScrollback(line: Array<Cell>) {
        if (maxScrollbackLines <= 0) return
        scrollback.addLast(Array(line.size) { line[it].copy() })
        while (scrollback.size > maxScrollbackLines) scrollback.removeFirst()
    }

    private fun scrollDown(n: Int) {
        val span = scrollBottom - scrollTop + 1
        val k = minOf(n, span)
        for (row in scrollBottom downTo scrollTop + k) {
            val src = (row - k) * cols
            val dst = row * cols
            System.arraycopy(grid, src, grid, dst, cols)
        }
        for (row in scrollTop until scrollTop + k) {
            for (col in 0 until cols) grid[row * cols + col] = Cell()
        }
    }

    private fun tryConsumeEscape(s: String): Boolean {
        if (s.length < 2) return false
        if (s[1] != '[') return false // only CSI handled; consume single non-CSI
        // CSI: ESC [ params... final
        val last = s.last()
        if (last in 'A'..'Z' || last in 'a'..'z' || last == '@') {
            val params = s.substring(2, s.length - 1)
            handleCsi(last, params)
            return true
        }
        // private modes like ?25h
        if (s.endsWith("h") || s.endsWith("l")) {
            handlePrivateMode(s.last(), s.substring(2, s.length - 1))
            return true
        }
        return false
    }

    private fun handlePrivateMode(final: Char, params: String) {
        if (!params.startsWith("?")) return
        val code = params.removePrefix("?").toIntOrNull() ?: return
        when (code) {
            25 -> cursorVisible = (final == 'h')
        }
    }

    private fun handleCsi(final: Char, params: String) {
        val parts = if (params.isBlank()) emptyList()
        else params.split(";").map { it.toIntOrNull() ?: 0 }
        fun p(i: Int, default: Int): Int = if (i in parts.indices && parts[i] != 0) parts[i] else default
        when (final) {
            'm' -> handleSgr(parts)
            'H', 'f' -> {
                cursorRow = (p(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (p(1, 1) - 1).coerceIn(0, cols - 1)
            }
            'A' -> cursorRow = (cursorRow - p(0, 1)).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + p(0, 1)).coerceAtMost(rows - 1)
            'C' -> cursorCol = (cursorCol + p(0, 1)).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - p(0, 1)).coerceAtLeast(0)
            'J' -> eraseDisplay(p(0, 0))
            'K' -> eraseLine(p(0, 0))
            'S' -> scrollUp(p(0, 1))
            'T' -> scrollDown(p(0, 1))
            'r' -> {
                scrollTop = (p(0, 1) - 1).coerceIn(0, rows - 1)
                scrollBottom = (p(1, rows) - 1).coerceIn(scrollTop, rows - 1)
                cursorRow = 0; cursorCol = 0
            }
            'd' -> cursorRow = (p(0, 1) - 1).coerceIn(0, rows - 1)
            'G' -> cursorCol = (p(0, 1) - 1).coerceIn(0, cols - 1)
            else -> {}
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                // cursor to end
                for (c in cursorCol until cols) grid[cursorRow * cols + c] = Cell()
                for (r in (cursorRow + 1) until rows)
                    for (c in 0 until cols) grid[r * cols + c] = Cell()
            }
            1 -> {
                for (r in 0 until cursorRow)
                    for (c in 0 until cols) grid[r * cols + c] = Cell()
                for (c in 0..cursorCol) grid[cursorRow * cols + c] = Cell()
            }
            2, 3 -> {
                for (i in grid.indices) grid[i] = Cell()
            }
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> for (c in cursorCol until cols) grid[cursorRow * cols + c] = Cell()
            1 -> for (c in 0..cursorCol) grid[cursorRow * cols + c] = Cell()
            2 -> for (c in 0 until cols) grid[cursorRow * cols + c] = Cell()
        }
    }

    private fun handleSgr(parts: List<Int>) {
        if (parts.isEmpty()) { resetAttrs(); return }
        var i = 0
        while (i < parts.size) {
            val v = parts[i]
            when {
                v == 0 -> resetAttrs()
                v == 1 -> curBold = true
                v == 3 -> curItalic = true
                v == 4 -> curUnderline = true
                v == 22 -> { curBold = false; curItalic = false }
                v == 23 -> curItalic = false
                v == 24 -> curUnderline = false
                v == 38 -> {
                    if (i + 1 < parts.size && parts[i + 1] == 5) {
                        curFg = map256(parts.getOrNull(i + 2) ?: 7); i += 2
                    } else if (i + 1 < parts.size && parts[i + 1] == 2) {
                        curFg = mapTrue(parts.getOrNull(i + 2) ?: 7, parts.getOrNull(i + 3) ?: 7, parts.getOrNull(i + 4) ?: 7)
                        i += 4
                    }
                }
                v == 48 -> {
                    if (i + 1 < parts.size && parts[i + 1] == 5) {
                        curBg = map256(parts.getOrNull(i + 2) ?: 0); i += 2
                    } else if (i + 1 < parts.size && parts[i + 1] == 2) {
                        curBg = mapTrue(parts.getOrNull(i + 2) ?: 0, parts.getOrNull(i + 3) ?: 0, parts.getOrNull(i + 4) ?: 0)
                        i += 4
                    }
                }
                v == 39 -> curFg = 7
                v == 49 -> curBg = -1
                v in 30..37 -> curFg = v - 30
                v in 40..47 -> curBg = v - 40
                v in 90..97 -> curFg = v - 90 + 8
                v in 100..107 -> curBg = v - 100 + 8
                else -> {}
            }
            i++
        }
    }

    /** 256-color -> ansiColors index (best-effort mapping to 16). */
    private fun map256(n: Int): Int {
        if (n in 0..15) return n
        return 7 // default to white for extended colors
    }

    /** Truecolor -> nearest of the 16 palette entries. */
    private fun mapTrue(r: Int, g: Int, b: Int): Int {
        var best = 7
        var bestDist = Int.MAX_VALUE
        for (i in ansiColors.indices) {
            val c = ansiColors[i]
            val dr = ((c.red * 255).toInt() and 0xFF) - r
            val dg = ((c.green * 255).toInt() and 0xFF) - g
            val db = ((c.blue * 255).toInt() and 0xFF) - b
            val d = dr * dr + dg * dg + db * db
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }

    private fun resetAttrs() {
        curFg = 7; curBg = -1; curBold = false; curItalic = false; curUnderline = false
    }

    fun colorFor(idx: Int): Color =
        if (idx in ansiColors.indices) ansiColors[idx] else com.ether4o4.mobilecontainer.ui.theme.Text

    fun clear() {
        for (i in grid.indices) grid[i] = Cell()
        scrollback.clear()
        cursorRow = 0; cursorCol = 0
    }

    companion object {
        const val DEFAULT_SCROLLBACK_LINES = 2_000
    }
}
