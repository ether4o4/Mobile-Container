package com.ether4o4.mobilecontainer.data

import java.io.File

/**
 * Parses a run.yaml / manifest.json from a pulled repo root to determine how
 * to run the artifact. Falls back to file-extension sniffing when absent.
 * Minimal YAML parser (key: value, nested via indentation) — enough for run.yaml.
 */
object ManifestParser {

    fun parse(dir: File): RunSpec {
        val yaml = File(dir, "run.yaml")
        val json = File(dir, "manifest.json")
        return when {
            yaml.exists() -> parseYaml(yaml.readText())
            json.exists() -> parseJson(json.readText())
            else -> sniff(dir)
        }
    }

    private fun parseYaml(text: String): RunSpec {
        val map = LinkedHashMap<String, String>()
        var lastKey: String? = null
        for (line in text.lines()) {
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val indent = line.takeWhile { it == ' ' }.length
            val trimmed = line.trim()
            if (indent == 0 && trimmed.contains(':')) {
                val (k, v) = trimmed.split(':', limit = 2).map { it.trim() }
                if (v.isEmpty()) { lastKey = k; map[k] = "" }
                else { map[k] = v; lastKey = null }
            } else if (lastKey != null) {
                // nested under lastKey
                map["$lastKey.$trimmed"] = ""
            }
        }
        return RunSpec(
            format = map["format"],
            file = map["file"] ?: map["model"],
            chatTemplate = map["chat_template"] ?: map["chatTemplate"],
            ctx = map["ctx"]?.toIntOrNull(),
            gpuLayers = map["gpu_layers"]?.toIntOrNull() ?: map["gpuLayers"]?.toIntOrNull(),
            threads = map["threads"]?.toIntOrNull(),
            cmd = map["cmd"] ?: map["command"],
            port = map["port"]?.toIntOrNull(),
            extra = map.filterKeys { it !in setOf("format","file","model","chat_template","chatTemplate","ctx","gpu_layers","gpuLayers","threads","cmd","command","port") }
        )
    }

    private fun parseJson(text: String): RunSpec {
        val gson = com.google.gson.Gson()
        @Suppress("UNCHECKED_CAST")
        val m = (gson.fromJson(text, Map::class.java) ?: emptyMap<String, Any?>()) as Map<String, Any?>
        return RunSpec(
            format = m["format"] as? String,
            file = (m["file"] as? String) ?: (m["model"] as? String),
            chatTemplate = m["chat_template"] as? String ?: m["chatTemplate"] as? String,
            ctx = (m["ctx"] as? Number)?.toInt(),
            gpuLayers = (m["gpu_layers"] as? Number)?.toInt() ?: (m["gpuLayers"] as? Number)?.toInt(),
            threads = (m["threads"] as? Number)?.toInt(),
            cmd = m["cmd"] as? String ?: m["command"] as? String,
            port = (m["port"] as? Number)?.toInt(),
            extra = m.filterKeys { it !in setOf("format","file","model","chat_template","chatTemplate","ctx","gpu_layers","gpuLayers","threads","cmd","command","port") }
                .mapValues { it.value?.toString().orEmpty() }
        )
    }

    /** Sniff the directory for a recognizable primary file. */
    fun sniff(dir: File): RunSpec {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val gguf = files.firstOrNull { it.name.endsWith(".gguf") }
        if (gguf != null) return RunSpec(format = "gguf", file = gguf.relativeTo(dir).path)
        val onnx = files.firstOrNull { it.name.endsWith(".onnx") }
        if (onnx != null) return RunSpec(format = "onnx", file = onnx.relativeTo(dir).path)
        val st = files.firstOrNull { it.name.endsWith(".safetensors") }
        if (st != null) return RunSpec(format = "safetensors", file = st.relativeTo(dir).path)
        val mainPy = files.firstOrNull { it.name == "main.py" || it.name == "app.py" || it.name == "run.py" }
        if (mainPy != null) return RunSpec(format = "python-agent", file = mainPy.relativeTo(dir).path, cmd = "python3 ${mainPy.name}")
        val docker = files.firstOrNull { it.name.equals("dockerfile", true) || it.name == "docker-compose.yml" }
        if (docker != null) return RunSpec(format = "docker", file = docker.relativeTo(dir).path)
        return RunSpec()
    }
}
