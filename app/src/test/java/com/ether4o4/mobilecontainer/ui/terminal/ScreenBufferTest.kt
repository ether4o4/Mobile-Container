package com.ether4o4.mobilecontainer.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenBufferTest {

    @Test
    fun `full-screen scrolling retains lines and viewport can revisit them`() {
        val buffer = ScreenBuffer(initialRows = 3, initialCols = 8, maxScrollbackLines = 10)

        buffer.writeText("one\r\ntwo\r\nthree\r\nfour")

        // Three rows hold three lines; only the fourth line feed scrolls "one" out.
        assertEquals(1, buffer.historySize())
        assertEquals(listOf("two", "three", "four"), buffer.viewport().textRows())
        assertEquals(listOf("one", "two", "three"), buffer.viewport(linesFromBottom = 2).textRows())
        assertFalse(buffer.viewport(linesFromBottom = 1).cursorVisible)
    }

    @Test
    fun `scrollback is bounded to configured line count`() {
        val buffer = ScreenBuffer(initialRows = 2, initialCols = 8, maxScrollbackLines = 2)

        buffer.writeText("one\r\ntwo\r\nthree\r\nfour\r\nfive")

        assertEquals(2, buffer.historySize())
        assertEquals(listOf("two", "three"), buffer.viewport(linesFromBottom = 99).textRows())
    }

    @Test
    fun `shrinking rows keeps newest screen lines and moves clipped lines to history`() {
        val buffer = ScreenBuffer(initialRows = 4, initialCols = 8, maxScrollbackLines = 10)
        buffer.writeText("one\r\ntwo\r\nthree")

        buffer.resize(newRows = 2, newCols = 8)

        assertEquals(2, buffer.rows)
        assertEquals(1, buffer.historySize())
        assertEquals(listOf("two", "three"), buffer.viewport().textRows())
        assertEquals(listOf("one", "two"), buffer.viewport(linesFromBottom = 2).textRows())
    }

    @Test
    fun `gesture direction maps down to older history and up toward live`() {
        assertTrue(scrollbackDeltaForDragLines(3) > 0)
        assertTrue(scrollbackDeltaForDragLines(-3) < 0)
        assertEquals(0, scrollbackDeltaForDragLines(0))
    }

    @Test
    fun `column resize preserves overlapping cells and clamps cursor`() {
        val buffer = ScreenBuffer(initialRows = 2, initialCols = 12)
        buffer.writeText("abcdefghijk")

        buffer.resize(newRows = 2, newCols = 8)

        assertEquals(8, buffer.cols)
        assertEquals("abcdefgh", buffer.viewport().textRows().first())
        assertTrue(buffer.cursorCol in 0 until buffer.cols)
    }

    private fun ScreenBuffer.ViewportSnapshot.textRows(): List<String> =
        (0 until rows).map { row ->
            buildString {
                for (col in 0 until cols) append(cells[row * cols + col].ch)
            }.trimEnd()
        }
}
