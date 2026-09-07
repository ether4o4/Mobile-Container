package com.ether4o4.mobilecontainer.ui.persona

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ether4o4.mobilecontainer.util.LogStore
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Editable persona configuration that turns raw LLM settings into
 * color-highlighted markdown sections. Persisted to SharedPreferences as JSON.
 */
data class PersonaConfig(
    val name: String = "default",
    val systemPrompt: String = "You are a helpful, concise assistant.",
    val temperature: Float = 0.8f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 512,
    val ctx: Int = 4096,
    val gpuLayers: Int = 0,
    val threads: Int = 4,
    val seed: Long = -1L,
    val chatTemplate: String = "llama-3"
)

class PersonaViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("persona", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _config = MutableStateFlow(load())
    val config: StateFlow<PersonaConfig> = _config.asStateFlow()

    private val _markdown = MutableStateFlow("")
    val markdown: StateFlow<String> = _markdown.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init { regenerate() }

    fun update(block: (PersonaConfig) -> PersonaConfig) {
        _config.value = block(_config.value)
        persist()
        regenerate()
    }

    fun setName(v: String) = update { it.copy(name = v) }
    fun setSystemPrompt(v: String) = update { it.copy(systemPrompt = v) }
    fun setTemperature(v: Float) = update { it.copy(temperature = v) }
    fun setTopK(v: Int) = update { it.copy(topK = v) }
    fun setTopP(v: Float) = update { it.copy(topP = v) }
    fun setRepeatPenalty(v: Float) = update { it.copy(repeatPenalty = v) }
    fun setMaxTokens(v: Int) = update { it.copy(maxTokens = v) }
    fun setCtx(v: Int) = update { it.copy(ctx = v) }
    fun setGpuLayers(v: Int) = update { it.copy(gpuLayers = v) }
    fun setThreads(v: Int) = update { it.copy(threads = v) }
    fun setSeed(v: Long) = update { it.copy(seed = v) }
    fun setChatTemplate(v: String) = update { it.copy(chatTemplate = v) }

    private fun regenerate() {
        _markdown.value = _config.value.toMarkdown()
    }

    fun copyMarkdown(): String = _config.value.toMarkdown()

    fun applyToRunning() {
        val c = _config.value
        // engine params are set at load time; we persist and notify.
        LogStore.add("[persona] applied: ${c.name} temp=${c.temperature} ctx=${c.ctx} threads=${c.threads}")
        _toast.value = "Persona '${c.name}' saved. Reload model to apply params."
    }

    fun consumeToast() { _toast.value = null }

    private fun persist() {
        prefs.edit().putString("config", gson.toJson(_config.value)).apply()
    }

    private fun load(): PersonaConfig {
        val s = prefs.getString("config", null) ?: return PersonaConfig()
        return runCatching { gson.fromJson(s, PersonaConfig::class.java) }.getOrNull() ?: PersonaConfig()
    }

    companion object {
        val templates = listOf("llama-3", "llama-2", "chatml", "mistral", "phi-3", "qwen2", "none")
    }
}

/**
 * Turns the config into structured markdown with headers, tables, and fenced
 * code blocks — the visual "Persona" page output.
 */
fun PersonaConfig.toMarkdown(): String {
    val sb = StringBuilder()
    sb.append("## Persona: ").append(name).append("\n\n")
    sb.append("> ").append(systemPrompt.replace("\n", "\n> ")).append("\n\n")
    sb.append("### Sampling\n\n")
    sb.append("| Parameter | Value | Note |\n")
    sb.append("|---|---|---|\n")
    sb.append("| temperature | ").append(temperature).append(" | creativity (0=deterministic) |\n")
    sb.append("| top_k | ").append(topK).append(" | token cutoff |\n")
    sb.append("| top_p | ").append(topP).append(" | nucleus sampling |\n")
    sb.append("| repeat_penalty | ").append(repeatPenalty).append(" | repetition penalty |\n")
    sb.append("| max_tokens | ").append(maxTokens).append(" | generation cap |\n")
    sb.append("| seed | ").append(if (seed < 0) "random" else seed).append(" | reproducibility |\n\n")
    sb.append("### Runtime\n\n")
    sb.append("| Parameter | Value | Note |\n")
    sb.append("|---|---|---|\n")
    sb.append("| ctx | ").append(ctx).append(" | context window |\n")
    sb.append("| gpu_layers | ").append(gpuLayers).append(" | offload to GPU |\n")
    sb.append("| threads | ").append(threads).append(" | CPU threads |\n")
    sb.append("| chat_template | `").append(chatTemplate).append("` | prompt format |\n\n")
    sb.append("### System Prompt\n\n")
    sb.append("```\n").append(systemPrompt).append("\n```\n")
    return sb.toString()
}

/** Best-effort parse of markdown back into a PersonaConfig. */
fun fromRawMarkdown(md: String): PersonaConfig {
    var name = "default"
    var systemPrompt = ""
    val params = mutableMapOf<String, String>()
    var inFence = false
    var fenceBuf = StringBuilder()
    for (line in md.lines()) {
        val t = line.trim()
        if (t.startsWith("```")) {
            if (inFence) { if (systemPrompt.isBlank()) systemPrompt = fenceBuf.toString().trim(); fenceBuf.clear(); inFence = false }
            else { inFence = true }
            continue
        }
        if (inFence) { fenceBuf.append(line).append('\n'); continue }
        if (t.startsWith("## Persona:")) { name = t.removePrefix("## Persona:").trim() }
        else if (t.startsWith("|") && t.contains("|")) {
            val cells = t.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            if (cells.size >= 2 && cells[0] != "Parameter" && cells[0] != "---" && cells[0].contains("---").not()) {
                params[cells[0]] = cells[1]
            }
        }
    }
    return PersonaConfig(
        name = name,
        systemPrompt = systemPrompt.ifBlank { params["system_prompt"] ?: "You are a helpful assistant." },
        temperature = params["temperature"]?.toFloatOrNull() ?: 0.8f,
        topK = params["top_k"]?.toIntOrNull() ?: 40,
        topP = params["top_p"]?.toFloatOrNull() ?: 0.95f,
        repeatPenalty = params["repeat_penalty"]?.toFloatOrNull() ?: 1.1f,
        maxTokens = params["max_tokens"]?.toIntOrNull() ?: 512,
        ctx = params["ctx"]?.toIntOrNull() ?: 4096,
        gpuLayers = params["gpu_layers"]?.toIntOrNull() ?: 0,
        threads = params["threads"]?.toIntOrNull() ?: 4,
        seed = params["seed"]?.toLongOrNull() ?: -1L,
        chatTemplate = params["chat_template"]?.removeSurrounding("`") ?: "llama-3"
    )
}
