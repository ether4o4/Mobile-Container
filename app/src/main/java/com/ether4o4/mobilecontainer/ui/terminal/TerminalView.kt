package com.ether4o4.mobilecontainer.ui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.ether4o4.mobilecontainer.ui.theme.Bg
import com.ether4o4.mobilecontainer.ui.theme.MonoFamily

/**
 * Renders a ScreenBuffer to a Compose Canvas using a monospace font.
 * Draws per-cell foreground/background colors and a block cursor.
 */
@Composable
fun TerminalView(
    buffer: ScreenBuffer,
    modifier: Modifier = Modifier,
    revision: Long = 0L,
    onTap: () -> Unit = {}
) {
    val density = LocalDensity.current
    val fontSize = 13.sp
    val charWidth = with(density) { fontSize.toPx() * 0.62f }
    val charHeight = with(density) { fontSize.toPx() * 1.18f }
    val textMeasurer = rememberTextMeasurer()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Bg)
            .pointerInput(buffer, revision) {
                detectTapGestures(onTap = { onTap() })
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val rows = buffer.rows
            val cols = buffer.cols
            val cells = buffer.cells()
            // background fill per cell where bg set
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val cell = cells[r * cols + c]
                    if (cell.bg >= 0) {
                        drawRect(
                            color = buffer.colorFor(cell.bg),
                            topLeft = Offset(c * charWidth, r * charHeight),
                            size = Size(charWidth, charHeight)
                        )
                    }
                }
            }
            // text per row, batched into segments by fg+style
            for (r in 0 until rows) {
                var segStart = 0
                var segFg = cells[r * cols].fg
                var segBold = cells[r * cols].bold
                var segItalic = cells[r * cols].italic
                val sb = StringBuilder()
                for (c in 0 until cols) {
                    val cell = cells[r * cols + c]
                    if (cell.fg != segFg || cell.bold != segBold || cell.italic != segItalic) {
                        flushSegment(textMeasurer, sb.toString(), r, segStart, segFg, segBold, segItalic, charWidth, charHeight, buffer)
                        segStart = c
                        segFg = cell.fg
                        segBold = cell.bold
                        segItalic = cell.italic
                        sb.clear()
                    }
                    sb.append(if (cell.ch == ' ') ' ' else cell.ch)
                }
                if (sb.isNotEmpty()) {
                    flushSegment(textMeasurer, sb.toString(), r, segStart, segFg, segBold, segItalic, charWidth, charHeight, buffer)
                }
            }
            // cursor block
            if (buffer.cursorVisible) {
                drawRect(
                    color = Color(0x66FFFFFF),
                    topLeft = Offset(buffer.cursorCol * charWidth, buffer.cursorRow * charHeight),
                    size = Size(charWidth, charHeight)
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.flushSegment(
    measurer: TextMeasurer,
    text: String,
    row: Int,
    startCol: Int,
    fg: Int,
    bold: Boolean,
    italic: Boolean,
    charWidth: Float,
    charHeight: Float,
    buffer: ScreenBuffer
) {
    if (text.isBlank()) return
    val color = buffer.colorFor(fg)
    val style = TextStyle(
        fontFamily = MonoFamily,
        fontSize = 13.sp,
        color = color,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal
    )
    val layout: TextLayoutResult = measurer.measure(
        text = text,
        style = style,
        constraints = Constraints(maxWidth = Int.MAX_VALUE, maxHeight = Int.MAX_VALUE)
    )
    drawText(
        textLayoutResult = layout,
        color = color,
        topLeft = Offset(startCol * charWidth, row * charHeight)
    )
}
