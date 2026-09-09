package com.ether4o4.mobilecontainer.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun TerminalScreen() {
    val vm: TerminalViewModel = viewModel()
    val buffer by vm.buffer.collectAsStateWithLifecycle()
    val scrollbackOffset by vm.scrollbackOffset.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val cwd by vm.cwd.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val themeIdx by TerminalThemeStore.themeIndex.collectAsStateWithLifecycle()
    val theme = TerminalThemeStore.presets[themeIdx]
    var showThemes by remember { mutableStateOf(false) }

    var input by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        TerminalThemeStore.init(context)
        vm.start()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // top status bar
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Neversoft 3.11~",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = cwd.substringAfterLast('/'),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = theme.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = { showThemes = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Palette, contentDescription = "Shell colors", tint = theme.foreground)
                }
                DropdownMenu(expanded = showThemes, onDismissRequest = { showThemes = false }) {
                    TerminalThemeStore.presets.forEachIndexed { i, t ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .background(t.background)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(t.name, color = t.foreground)
                                }
                            },
                            onClick = {
                                TerminalThemeStore.select(context, i)
                                showThemes = false
                            }
                        )
                    }
                }
                Text(
                    text = if (running) "● live" else "○ idle",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }
        }

        // terminal canvas
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            TerminalView(
                buffer = buffer,
                pendingInput = input,
                modifier = Modifier.fillMaxSize(),
                bgColor = theme.background,
                fgColor = theme.foreground,
                onTap = { runCatching { focus.requestFocus() } },
                onResize = vm::resize,
                onScrollLines = vm::scrollBy,
                onScrollToBottom = vm::scrollToBottom
            )
            if (scrollbackOffset > 0) {
                TextButton(
                    onClick = vm::scrollToBottom,
                    modifier = Modifier.align(Alignment.BottomEnd)
                ) {
                    Text("↓ Live ($scrollbackOffset)")
                }
            }
        }

        // control keys row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            ControlKey("ESC") { vm.sendKey(byteArrayOf(0x1b.toByte())) }
            ControlKey("TAB") { vm.sendKey(byteArrayOf(0x09.toByte())) }
            ControlKey("CTRL") { /* modifier toggle not implemented; send Ctrl-C instead */ }
            ControlKey("↑") { vm.sendKey(byteArrayOf(0x1b.toByte(), '['.code.toByte(), 'A'.code.toByte())) }
            ControlKey("↓") { vm.sendKey(byteArrayOf(0x1b.toByte(), '['.code.toByte(), 'B'.code.toByte())) }
            ControlKey("C-c") { vm.sendCtrl('c') }
        }

        // input bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text("command or shell input", style = MaterialTheme.typography.labelMedium) },
                singleLine = true,
                textStyle = MaterialTheme.typography.labelMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (input.isNotBlank()) {
                        val line = input.trim()
                        input = ""
                        // app CLI verbs go through runCommand; otherwise raw shell input
                        val isAppVerb = line.startsWith("mc ") || isCliVerb(line)
                        if (isAppVerb) {
                            val cmd = if (line.startsWith("mc ")) line.removePrefix("mc ") else line
                            vm.runCommand(cmd)
                        } else {
                            vm.sendInput(line)
                        }
                    }
                })
            )
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = {
                if (input.isNotBlank()) {
                    val line = input.trim()
                    input = ""
                    val isAppVerb = line.startsWith("mc ") || isCliVerb(line)
                    if (isAppVerb) {
                        val cmd = if (line.startsWith("mc ")) line.removePrefix("mc ") else line
                        vm.runCommand(cmd)
                    } else {
                        vm.sendInput(line)
                    }
                }
            }) {
                Icon(Icons.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

private fun isCliVerb(line: String): Boolean {
    val v = line.trim().split(Regex("\\s+")).firstOrNull()?.lowercase() ?: return false
    return v in setOf("pull", "run", "list", "ls", "rm", "logs", "ps", "stop", "help", "h", "?", "clear")
}

@Composable
private fun ControlKey(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
    }
}
