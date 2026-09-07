package com.ether4o4.mobilecontainer.ui.persona

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ether4o4.mobilecontainer.ui.theme.Accent
import com.ether4o4.mobilecontainer.ui.theme.Bg
import com.ether4o4.mobilecontainer.ui.theme.Primary
import com.ether4o4.mobilecontainer.ui.theme.Surface
import com.ether4o4.mobilecontainer.ui.theme.SurfaceTint
import com.ether4o4.mobilecontainer.ui.theme.Text
import com.ether4o4.mobilecontainer.ui.theme.TextDim

/**
 * A small, self-contained markdown renderer that turns raw LLM settings into
 * COLOR-HIGHLIGHTED markdown sections. Supports:
 *   # ## ### headers (teal)
 *   **bold** (purple)
 *   *italic*
 *   `inline code` (surface-tinted, monospace)
 *   ```fenced``` code blocks (darker bg, monospace, line numbers)
 *   - list items
 *   | table | rows | (header row highlighted)
 *   > blockquote (left accent bar)
 *
 * No external markdown library — parses line-by-line.
 */
@Composable
fun MarkdownText(
    md: String,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.secondary
    val codeBg = MaterialTheme.colorScheme.surfaceTint
    val fenceBg = MaterialTheme.colorScheme.surfaceVariant
    val text = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val lines = md.lines()
        var i = 0
        var inFence = false
        val fenceBuf = StringBuilder()
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                if (inFence) {
                    // flush fenced block
                    FencedCode(fenceBuf.toString(), fenceBg, text)
                    fenceBuf.clear()
                    inFence = false
                } else {
                    inFence = true
                }
                i++; continue
            }
            if (inFence) { fenceBuf.append(line).append('\n'); i++; continue }

            when {
                trimmed.startsWith("### ") -> {
                    Text(
                        text = inlineAnnotated(trimmed.removePrefix("### "), primary, accent, codeBg, text),
                        style = MaterialTheme.typography.titleSmall.copy(color = primary),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                trimmed.startsWith("## ") -> {
                    Text(
                        text = inlineAnnotated(trimmed.removePrefix("## "), primary, accent, codeBg, text),
                        style = MaterialTheme.typography.titleMedium.copy(color = primary),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                trimmed.startsWith("# ") -> {
                    Text(
                        text = inlineAnnotated(trimmed.removePrefix("# "), primary, accent, codeBg, text),
                        style = MaterialTheme.typography.titleLarge.copy(color = primary),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                trimmed.startsWith("> ") -> {
                    Blockquote(trimmed.removePrefix("> "), accent, text)
                }
                trimmed.startsWith("|") && trimmed.contains("|") -> {
                    // collect contiguous table rows
                    val tableLines = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        tableLines.add(lines[i].trim())
                        i++
                    }
                    Table(tableLines, primary, accent, codeBg, text, dim)
                    continue
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    Row(modifier = Modifier.padding(start = 8.dp)) {
                        Text("•", color = accent, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = inlineAnnotated(trimmed.drop(2), primary, accent, codeBg, text),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                trimmed.isBlank() -> Spacer(Modifier.height(2.dp))
                else -> {
                    Text(
                        text = inlineAnnotated(trimmed, primary, accent, codeBg, text),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            i++
        }
        if (inFence && fenceBuf.isNotEmpty()) {
            FencedCode(fenceBuf.toString(), fenceBg, text)
        }
    }
}

/** Parse inline **bold**, *italic*, `code` into an AnnotatedString. */
private fun inlineAnnotated(
    src: String,
    primary: Color,
    accent: Color,
    codeBg: Color,
    text: Color
): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < src.length) {
        val c = src[i]
        when {
            c == '*' && i + 1 < src.length && src[i + 1] == '*' -> {
                val end = src.indexOf("**", i + 2)
                if (end >= 0) {
                    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
                        append(src.substring(i + 2, end))
                    }
                    i = end + 2; continue
                }
                append(c); i++
            }
            c == '*' -> {
                val end = src.indexOf('*', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = text)) {
                        append(src.substring(i + 1, end))
                    }
                    i = end + 1; continue
                }
                append(c); i++
            }
            c == '`' -> {
                val end = src.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, color = primary)) {
                        append(' ')
                        append(src.substring(i + 1, end))
                        append(' ')
                    }
                    i = end + 1; continue
                }
                append(c); i++
            }
            else -> { append(c); i++ }
        }
    }
}

@Composable
private fun FencedCode(content: String, bg: Color, text: Color) {
    val lines = content.trimEnd('\n').split('\n')
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        lines.forEachIndexed { idx, ln ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "${idx + 1}",
                    color = text.copy(alpha = 0.35f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.width(24.dp)
                )
                Text(
                    text = ln,
                    color = text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun Blockquote(content: String, accent: Color, text: Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = inlineAnnotated(content, accent, accent, MaterialTheme.colorScheme.surfaceTint, text),
            style = MaterialTheme.typography.bodyMedium.copy(color = text.copy(alpha = 0.85f)),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun Table(rows: List<String>, primary: Color, accent: Color, codeBg: Color, text: Color, dim: Color) {
    val parsed = rows.map { row ->
        row.trim('|').split("|").map { it.trim() }
    }.filter { it.isNotEmpty() }
    if (parsed.isEmpty()) return
    val isSep = parsed.getOrNull(1)?.all { it.all { c -> c == '-' || c == ':' } } == true
    val header = parsed.first()
    val body = if (isSep) parsed.drop(2) else parsed.drop(1)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // header row
        Row(modifier = Modifier.fillMaxWidth()) {
            header.forEach { cell ->
                Text(
                    text = inlineAnnotated(cell, primary, accent, codeBg, text),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = primary),
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                )
            }
        }
        body.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { cell ->
                    Text(
                        text = inlineAnnotated(cell, primary, accent, codeBg, text),
                        style = MaterialTheme.typography.labelMedium.copy(color = text),
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}
