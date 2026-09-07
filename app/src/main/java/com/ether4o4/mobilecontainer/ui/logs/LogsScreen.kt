package com.ether4o4.mobilecontainer.ui.logs

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ether4o4.mobilecontainer.MCApp
import com.ether4o4.mobilecontainer.data.ModelStore
import com.ether4o4.mobilecontainer.ui.theme.Bg
import com.ether4o4.mobilecontainer.ui.theme.Surface
import com.ether4o4.mobilecontainer.util.FileUtils
import com.ether4o4.mobilecontainer.util.LogStore

@Composable
fun LogsScreen() {
    val ctx = LocalContext.current
    val store = remember { ModelStore(ctx) }
    var tab by remember { mutableIntStateOf(0) }
    var logFiles by remember { mutableStateOf(store.logsDir.listFiles { f -> f.isFile && f.extension == "log" }?.sortedByDescending { it.lastModified() } ?: emptyList()) }
    var selectedLog by remember { mutableStateOf<String?>(null) }
    var appLog by remember { mutableStateOf(LogStore.lines()) }
    val listState = rememberLazyListState()

    LaunchedEffect(tab) {
        if (tab == 1) appLog = LogStore.lines()
        if (tab == 0) logFiles = store.logsDir.listFiles { f -> f.isFile && f.extension == "log" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Model logs") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("App log") })
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (tab == 0) "${logFiles.size} log files" else "${appLog.size} lines",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                val text = if (tab == 0) {
                    selectedLog?.let { FileUtils.readTail(store.logsDir.resolve(it), 500) } ?: ""
                } else appLog.joinToString("\n")
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("logs", text))
                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy") }
            if (tab == 1) {
                IconButton(onClick = { LogStore.clear(); appLog = emptyList() }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Clear")
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Bg)
        ) {
            if (tab == 0) {
                if (logFiles.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No model logs yet", color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                        if (selectedLog == null) {
                            items(logFiles, key = { it.name }) { f ->
                                LogFileRow(f.name, f.length(), f.lastModified(), onClick = { selectedLog = f.name })
                            }
                        } else {
                            val content = FileUtils.readTail(store.logsDir.resolve(selectedLog!!), 500)
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(selectedLog!!, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { selectedLog = null }) { Text("← back") }
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                            item {
                                Text(
                                    text = content,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    items(appLog) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                LaunchedEffect(appLog.size) {
                    if (appLog.isNotEmpty()) listState.animateScrollToItem(appLog.lastIndex.coerceAtLeast(0))
                }
            }
        }
    }
}

@Composable
private fun LogFileRow(name: String, size: Long, modified: Long, onClick: () -> Unit) {
    val df = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        Text("${FileUtils.humanReadableSize(size)}  ${df.format(java.util.Date(modified))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onClick) { Text("view") }
    }
}
