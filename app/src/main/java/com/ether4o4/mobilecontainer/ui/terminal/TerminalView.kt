package com.ether4o4.mobilecontainer.ui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.ether4o4.mobilecontainer.ui.theme.Bg
import com.ether4o4.mobilecontainer.ui.theme.MonoFamily
import com.ether4o4.mobilecontainer.ui.theme.Text
import com.ether4o4.mobilecontainer.ui.theme.ansiColors
import kotlin.math.floor

/**
 * Canvas terminal renderer. The PTY grid follows the unscaled viewport while
 * pinch zoom is a visual magnifier, so zooming never unexpectedly reflows a
 * running full-screen program. Drag vertically to browse scrollback and drag
 * horizontally when magnification makes the grid wider than the viewport.
 */
@Composable
fun TerminalView(
    buffer: ScreenBuffer.ViewportSnapshot,
    pendingInput: String,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
    onResize: (rows: Int, cols: Int) -> Unit = { _, _ -> },
    onScrollLines: (Int) -> Unit = {},
    onScrollToBottom: () -> Unit = {}
) {
    val density = LocalDensity.current
    val baseFontSize = 13.sp
    val baseCharWidth = with(density) { baseFontSize.toPx() * 0.62f }
    val baseCharHeight = with(density) { baseFontSize.toPx() * 1.18f }
    val textMeasurer = rememberTextMeasurer()

    var scale by remember { mutableFloatStateOf(1f) }
    var horizontalOffset by remember { mutableFloatStateOf(0f) }
    var verticalOffset by remember { mutableFloatStateOf(0f) }
    var verticalDragRemainder by remember { mutableFloatStateOf(0f) }
    var viewportWidth by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableFloatStateOf(0f) }

    fun clampHorizontal(offset: Float, atScale: Float): Float {
        val overflow = (buffer.cols * baseCharWidth * atScale - viewportWidth).coerceAtLeast(0f)
        return offset.coerceIn(-overflow, 0f)
    }

    fun clampVertical(offset: Float, atScale: Float): Float {
        val overflow = (buffer.rows * baseCharHeight * atScale - viewportHeight).coerceAtLeast(0f)
        return offset.coerceIn(-overflow, 0f)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Bg)
            .onSizeChanged { size ->
                viewportWidth = size.width.toFloat()
                viewportHeight = size.height.toFloat()
                horizontalOffset = clampHorizontal(horizontalOffset, scale)
                verticalOffset = clampVertical(verticalOffset, scale)
                val rows = floor(size.height / baseCharHeight).toInt().coerceAtLeast(2)
                val cols = floor(size.width / baseCharWidth).toInt().coerceAtLeast(8)
                onResize(rows, cols)
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { onScrollToBottom() }
                )
            }
            .pointerInput(buffer.cols, buffer.historySize) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldScale = scale
                    val newScale = (oldScale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    val scaleRatio = newScale / oldScale
                    val focalX = centroid.x - (centroid.x - horizontalOffset) * scaleRatio
                    val focalY = centroid.y - (centroid.y - verticalOffset) * scaleRatio
                    horizontalOffset = clampHorizontal(focalX + pan.x, newScale)
                    val oldVerticalOffset = verticalOffset
                    val desiredVerticalOffset = focalY + pan.y
                    verticalOffset = clampVertical(desiredVerticalOffset, newScale)
                    scale = newScale

                    // Consume drag by bounded canvas panning first, then route any remainder
                    // beyond the edge to scrollback so prior output is always reachable.
                    val consumedVerticalPan = verticalOffset - oldVerticalOffset
                    val scrollPan = pan.y - consumedVerticalPan
                    if (scrollPan != 0f) {
                        verticalDragRemainder += scrollPan
                        val scaledLineHeight = baseCharHeight * newScale
                        val lines = (verticalDragRemainder / scaledLineHeight).toInt()
                        if (lines != 0) {
                            // Compose pan.y is positive when dragging down. Positive buffer
                            // deltas move away from the live bottom into older scrollback.
                            onScrollLines(scrollbackDeltaForDragLines(lines))
                            verticalDragRemainder -= lines * scaledLineHeight
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val charWidth = baseCharWidth * scale
            val charHeight = baseCharHeight * scale
            val fontSize = baseFontSize * scale
            val cells = buffer.cells

            clipRect {
                // Per-cell backgrounds.
                for (row in 0 until buffer.rows) {
                    for (col in 0 until buffer.cols) {
                        val cell = cells[row * buffer.cols + col]
                        if (cell.bg >= 0) {
                            drawRect(
                                color = colorFor(cell.bg),
                                topLeft = Offset(horizontalOffset + col * charWidth, verticalOffset + row * charHeight),
                                size = Size(charWidth, charHeight)
                            )
                        }
                    }
                }

                // Text is batched into runs sharing foreground and style.
                for (row in 0 until buffer.rows) {
                    var segmentStart = 0
                    var segmentFg = cells[row * buffer.cols].fg
                    var segmentBold = cells[row * buffer.cols].bold
                    var segmentItalic = cells[row * buffer.cols].italic
                    val text = StringBuilder()
                    for (col in 0 until buffer.cols) {
                        val cell = cells[row * buffer.cols + col]
                        if (cell.fg != segmentFg || cell.bold != segmentBold || cell.italic != segmentItalic) {
                            flushSegment(
                                textMeasurer, text.toString(), row, segmentStart, segmentFg,
                                segmentBold, segmentItalic, charWidth, charHeight, fontSize,
                                horizontalOffset, verticalOffset
                            )
                            segmentStart = col
                            segmentFg = cell.fg
                            segmentBold = cell.bold
                            segmentItalic = cell.italic
                            text.clear()
                        }
                        text.append(cell.ch)
                    }
                    if (text.isNotEmpty()) {
                        flushSegment(
                            textMeasurer, text.toString(), row, segmentStart, segmentFg,
                            segmentBold, segmentItalic, charWidth, charHeight, fontSize,
                            horizontalOffset, verticalOffset
                        )
                    }
                }

                var cursorRow = buffer.cursorRow
                var cursorCol = buffer.cursorCol
                if (pendingInput.isNotEmpty() && buffer.cursorVisible) {
                    for (ch in pendingInput) {
                        if (ch == '\n') {
                            cursorRow++
                            cursorCol = 0
                            continue
                        }
                        if (cursorCol >= buffer.cols) {
                            cursorCol = 0
                            cursorRow++
                        }
                        if (cursorRow >= buffer.rows) break
                        flushSegment(
                            measurer = textMeasurer,
                            text = ch.toString(),
                            row = cursorRow,
                            startCol = cursorCol,
                            fg = PENDING_FG,
                            bold = false,
                            italic = false,
                            charWidth = charWidth,
                            charHeight = charHeight,
                            fontSize = fontSize,
                            horizontalOffset = horizontalOffset,
                            verticalOffset = verticalOffset
                        )
                        cursorCol++
                    }
                }

                if (buffer.cursorVisible && cursorRow in 0 until buffer.rows) {
                    if (cursorCol >= buffer.cols) {
                        cursorCol = 0
                        cursorRow++
                    }
                    if (cursorRow in 0 until buffer.rows) {
                        drawRect(
                            color = Color(0x66FFFFFF),
                            topLeft = Offset(
                                horizontalOffset + cursorCol * charWidth,
                                verticalOffset + cursorRow * charHeight
                            ),
                            size = Size(charWidth, charHeight)
                        )
                    }
                }
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
    fontSize: androidx.compose.ui.unit.TextUnit,
    horizontalOffset: Float,
    verticalOffset: Float
) {
    if (text.isBlank()) return
    val color = colorFor(fg)
    val layout: TextLayoutResult = measurer.measure(
        text = text,
        style = TextStyle(
            fontFamily = MonoFamily,
            fontSize = fontSize,
            color = color,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal
        ),
        constraints = Constraints(maxWidth = Int.MAX_VALUE, maxHeight = Int.MAX_VALUE)
    )
    drawText(
        textLayoutResult = layout,
        color = color,
        topLeft = Offset(horizontalOffset + startCol * charWidth, verticalOffset + row * charHeight)
    )
}

private fun colorFor(index: Int): Color =
    if (index in ansiColors.indices) ansiColors[index] else Text

internal fun scrollbackDeltaForDragLines(dragLines: Int): Int = dragLines

private const val MIN_SCALE = 0.75f
private const val MAX_SCALE = 2.5f
private const val PENDING_FG = 14
