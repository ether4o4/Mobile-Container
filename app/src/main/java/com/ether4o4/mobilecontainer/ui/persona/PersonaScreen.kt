package com.ether4o4.mobilecontainer.ui.persona

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ether4o4.mobilecontainer.ui.theme.Accent
import com.ether4o4.mobilecontainer.ui.theme.Bg
import com.ether4o4.mobilecontainer.ui.theme.Primary
import com.ether4o4.mobilecontainer.ui.theme.Surface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaScreen() {
    val vm: PersonaViewModel = viewModel()
    val config by vm.config.collectAsStateWithLifecycle()
    val markdown by vm.markdown.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scroll = rememberScrollState()

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // animated gradient header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(
                    Brush.horizontalGradient(listOf(Primary, Accent))
                )
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text("Persona", color = Bg, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Row(modifier = Modifier.fillMaxSize()) {
            // left: editable form
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(scroll)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = config.name, onValueChange = vm::setName,
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = config.systemPrompt, onValueChange = vm::setSystemPrompt,
                    label = { Text("System prompt") },
                    modifier = Modifier.fillMaxWidth().height(120.dp)
                )
                Row {
                    Text("${config.systemPrompt.length} chars", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }

                SliderRow("temperature", config.temperature, 0f, 2f) { vm.setTemperature(it) }
                SliderRow("top_p", config.topP, 0f, 1f) { vm.setTopP(it) }
                SliderRow("repeat_penalty", config.repeatPenalty, 0.8f, 2f) { vm.setRepeatPenalty(it) }

                IntField("top_k", config.topK, 1, 200) { vm.setTopK(it) }
                IntField("max_tokens", config.maxTokens, 1, 8192) { vm.setMaxTokens(it) }
                IntField("ctx", config.ctx, 512, 32768) { vm.setCtx(it) }
                IntField("gpu_layers", config.gpuLayers, 0, 200) { vm.setGpuLayers(it) }
                IntField("threads", config.threads, 1, 16) { vm.setThreads(it) }
                LongField("seed", config.seed) { vm.setSeed(it) }

                // chat template dropdown
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    TextField(
                        value = config.chatTemplate, onValueChange = {},
                        readOnly = true,
                        label = { Text("chat_template") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        PersonaViewModel.templates.forEach { t ->
                            DropdownMenuItem(text = { Text(t) }, onClick = { vm.setChatTemplate(t); expanded = false })
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("persona", vm.copyMarkdown()))
                        Toast.makeText(ctx, "Markdown copied", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Filled.ContentCopy, null); Spacer(Modifier.width(4.dp)); Text("Copy MD")
                    }
                    Button(onClick = { vm.applyToRunning() }) {
                        Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Apply")
                    }
                }
            }

            // right: live markdown preview
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Surface)
                    .padding(12.dp)
            ) {
                Text("Preview", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Bg)
                        .padding(12.dp)
                ) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        MarkdownText(md = markdown)
                    }
                }
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(String.format("%.2f", value), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}

@Composable
private fun IntField(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s.filter { it.isDigit() }
            text.toIntOrNull()?.let { if (it in min..max) onChange(it) }
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun LongField(label: String, value: Long, onChange: (Long) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s.filter { it.isDigit() || it == '-' }
            text.toLongOrNull()?.let { onChange(it) }
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}
