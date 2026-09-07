package com.ether4o4.mobilecontainer.ui.terminal

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ether4o4.mobilecontainer.MCApp
import com.ether4o4.mobilecontainer.data.ModelRouter
import com.ether4o4.mobilecontainer.data.ModelStore
import com.ether4o4.mobilecontainer.runtime.InferenceService
import com.ether4o4.mobilecontainer.terminal.TerminalBridge
import com.ether4o4.mobilecontainer.util.FileUtils
import com.ether4o4.mobilecontainer.util.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the interactive pty shell + an in-app CLI command layer.
 * The shell is a real /system/bin/sh running under the app's filesDir,
 * with PATH including the app bin dir. A read loop pumps bytes into a
 * ScreenBuffer and emits snapshots via StateFlow.
 *
 * `runCommand(cmd)` executes app-level verbs (pull/run/list/rm/logs/ps/stop/help)
 * and streams their output into the same buffer as plain text.
 */
class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ModelStore(app)
    private val router = ModelRouter(app)
    private val binDir = store.binDir

    private val _buffer = MutableStateFlow(ScreenBuffer(24, 80))
    val buffer: StateFlow<ScreenBuffer> = _buffer.asStateFlow()

    // tick bumped on every buffer mutation so collectors recompose
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _cwd = MutableStateFlow(File(app.filesDir, "mobilecontainer").absolutePath)
    val cwd: StateFlow<String> = _cwd.asStateFlow()

    @Volatile private var handle: TerminalBridge.Handle? = null
    @Volatile private var readLoop: Thread? = null
    @Volatile private var alive = false

    fun start() {
        if (handle != null) return
        ensureBin()
        val ctx = getApplication<MCApp>()
        val home = File(ctx.filesDir, "mobilecontainer").apply { mkdirs() }.absolutePath
        val env = arrayOf(
            "TERM=xterm-256color",
            "HOME=$home",
            "PATH=${binDir.absolutePath}:/system/bin:/system/xbin:/vendor/bin",
            "PS1='mc# '",
            "LANG=en_US.UTF-8"
        )
        val cmd = "/system/bin/sh"
        try {
            val h = TerminalBridge.spawn(cmd, home, env, 24, 80)
            handle = h
            alive = true
            _running.value = true
            _cwd.value = home
            startReadLoop(h)
            printBanner()
        } catch (e: Exception) {
            LogStore.add("[terminal] spawn failed: ${e.message}")
            _buffer.value.writeText("Failed to spawn shell: ${e.message}\r\n")
            _buffer.value.writeText("Falling back to in-app CLI. Type 'help'.\r\n")
        }
    }

    private fun startReadLoop(h: TerminalBridge.Handle) {
        val t = Thread {
            val buf = ByteArray(8192)
            while (alive && handle != null) {
                val n = try { TerminalBridge.read(h, buf, 0, buf.size) } catch (e: Throwable) { -1 }
                if (n < 0) break
                if (n > 0) {
                    val slice = buf.copyOfRange(0, n)
                    synchronized(_buffer) {
                        _buffer.value.write(slice)
                    }
                    _tick.value++ // trigger snapshot emit
                }
                try { Thread.sleep(8) } catch (_: InterruptedException) { break }
            }
            _running.value = false
        }.apply { isDaemon = true; name = "mc-pty-read" }
        readLoop = t
        t.start()
    }

    private fun printBanner() {
        val b = StringBuilder()
        b.append("\r\n")
        b.append("MobileContainer v0.1.0 — on-device model runner\r\n")
        b.append("Shell: /system/bin/sh   Home: ").append(_cwd.value).append("\r\n")
        b.append("Type 'help' for app commands.\r\n\r\n")
        synchronized(_buffer) { _buffer.value.writeText(b.toString()) }
        _tick.value++
    }

    private fun ensureBin() {
        binDir.mkdirs()
    }

    fun write(data: ByteArray) {
        val h = handle ?: return
        try { TerminalBridge.write(h, data, 0, data.size) } catch (_: Throwable) {}
    }

    fun sendInput(text: String) {
        write((text + "\n").toByteArray())
    }

    fun resize(rows: Int, cols: Int) {
        val h = handle ?: return
        try {
            TerminalBridge.resize(h, rows, cols)
            synchronized(_buffer) { _buffer.value.resize(rows, cols) }
            _tick.value++
        } catch (_: Throwable) {}
    }

    fun sendCtrl(c: Char) {
        val code = c.code and 0x1F
        write(byteArrayOf(code.toByte()))
    }

    fun sendKey(bytes: ByteArray) {
        write(bytes)
    }

    fun stop() {
        alive = false
        val h = handle
        handle = null
        if (h != null) {
            runCatching { TerminalBridge.sendSignal(h, 15) }
            runCatching { TerminalBridge.close(h) }
        }
        readLoop?.interrupt()
        readLoop = null
        _running.value = false
    }

    // ---- in-app CLI command layer ----

    fun runCommand(cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return
        echoPrompt(trimmed)
        val parts = trimmed.split(Regex("\\s+"))
        val verb = parts.firstOrNull()?.lowercase() ?: return
        val args = parts.drop(1)
        viewModelScope.launch(Dispatchers.IO) {
            when (verb) {
                "help", "h", "?" -> doHelp()
                "pull" -> doPull(args)
                "run" -> doRun(args)
                "list", "ls" -> doList()
                "rm" -> doRm(args)
                "logs" -> doLogs(args)
                "ps" -> doPs()
                "stop" -> doStop()
                "clear" -> { synchronized(_buffer) { _buffer.value.clear() }; _tick.value++ }
                else -> out("unknown command: $verb  (try 'help')\r\n")
            }
        }
    }

    private fun echoPrompt(line: String) {
        out("mc# $line\r\n")
    }

    private fun out(text: String) {
        synchronized(_buffer) { _buffer.value.writeText(text) }
        _tick.value++
    }

    private fun doHelp() {
        out(
            """Commands:
  pull <spec> [name]   pull a model/agent from hf:// github:// or a URL
  run <name> [port]    load a model and start the OpenAI-compatible API
  list                 show pulled models
  rm <name>            delete a model
  logs <name>          show tail of a model's log
  ps                   show the running model
  stop                 unload the running model
  clear                clear the screen
  help                 this message
""".trimIndent() + "\r\n"
        )
    }

    private fun doPull(args: List<String>) {
        val spec = args.firstOrNull()
        if (spec == null) { out("usage: pull <spec> [name]\r\n"); return }
        val nameOverride = args.getOrNull(1)
        out("pulling $spec ...\r\n")
        LogStore.add("[pull] $spec")
        try {
            router.pull(spec, nameOverride, { ev ->
                val msg = when (ev.phase) {
                    "download" -> if (ev.total > 0)
                        "  ${ev.message}: ${FileUtils.humanReadableSize(ev.downloaded)} / ${FileUtils.humanReadableSize(ev.total)}"
                    else "  ${ev.message}: ${FileUtils.humanReadableSize(ev.downloaded)}"
                    "done" -> "  done: ${ev.message}"
                    else -> "  [${ev.phase}] ${ev.message}"
                }
                out(msg + "\r\n")
                LogStore.add("[pull] $msg")
            }, { false })
            out("pull complete.\r\n")
        } catch (e: Exception) {
            out("pull failed: ${e.message}\r\n")
            LogStore.add("[pull] failed: ${e.message}")
        }
    }

    private fun doRun(args: List<String>) {
        val name = args.firstOrNull()
        if (name == null) { out("usage: run <name> [port]\r\n"); return }
        val port = args.getOrNull(1)?.toIntOrNull() ?: InferenceService.DEFAULT_PORT
        val m = store.get(name)
        if (m == null) { out("no such model: $name (try 'list')\r\n"); return }
        out("loading $name on :$port ...\r\n")
        val ctx = getApplication<MCApp>()
        val intent = Intent(ctx, InferenceService::class.java).apply {
            action = InferenceService.ACTION_LOAD
            putExtra(InferenceService.EXTRA_MODEL, name)
            putExtra(InferenceService.EXTRA_PORT, port)
        }
        ContextCompat.startForegroundService(ctx, intent)
        out("service started. use 'ps' to check status.\r\n")
        LogStore.add("[run] $name :$port")
    }

    private fun doList() {
        val list = store.list()
        if (list.isEmpty()) { out("no models pulled. try 'pull hf://owner/repo'\r\n"); return }
        out(String.format("%-24s %-12s %-10s %12s\r\n", "NAME", "FORMAT", "SOURCE", "SIZE"))
        for (m in list) {
            out(String.format("%-24s %-12s %-10s %12s\r\n",
                m.name, m.format.label, m.sourceType, FileUtils.humanReadableSize(m.sizeBytes)))
        }
        out("disk: ${FileUtils.humanReadableSize(store.diskUsage())}\r\n")
    }

    private fun doRm(args: List<String>) {
        val name = args.firstOrNull()
        if (name == null) { out("usage: rm <name>\r\n"); return }
        if (store.delete(name)) out("removed $name\r\n")
        else out("no such model: $name\r\n")
    }

    private fun doLogs(args: List<String>) {
        val name = args.firstOrNull()
        if (name == null) { out("usage: logs <name>\r\n"); return }
        val f = store.logFile(name)
        if (!f.exists()) { out("no logs for $name\r\n"); return }
        out(FileUtils.readTail(f, 200) + "\r\n")
    }

    private fun doPs() {
        val svc = InferenceService.get()
        if (svc == null) { out("no inference service running\r\n"); return }
        try {
            val f = svc.javaClass.getDeclaredField("current")
            f.isAccessible = true
            val m = f.get(svc) as? com.ether4o4.mobilecontainer.data.ModelManifest
            if (m == null) out("service running but no model loaded\r\n")
            else out("running: ${m.name}  (${m.format.label})\r\n")
        } catch (e: Exception) {
            out("service running (status unavailable: ${e.message})\r\n")
        }
    }

    private fun doStop() {
        val ctx = getApplication<MCApp>()
        val intent = Intent(ctx, InferenceService::class.java).apply {
            action = InferenceService.ACTION_UNLOAD
        }
        ContextCompat.startForegroundService(ctx, intent)
        out("stop sent\r\n")
        LogStore.add("[stop] inference")
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
