package com.ether4o4.mobilecontainer.data

import com.google.gson.annotations.SerializedName

/** A pulled model/agent artifact and how to run it. */
data class ModelManifest(
    val name: String,                 // local name, e.g. "phi-3-mini"
    val source: String,               // original pull URI
    val sourceType: String,           // hf | github | url | local
    val format: ModelFormat,          // detected format
    val files: List<String>,          // relative paths under the model dir
    val primaryFile: String?,        // the file to load/run
    val chatTemplate: String? = null, // optional chat template name
    val params: Map<String, String> = emptyMap(), // run.yaml params
    val sizeBytes: Long = 0L,
    val pulledAt: Long = System.currentTimeMillis()
) {
    val dirName: String get() = sanitizeName(name)
    companion object {
        fun sanitizeName(n: String): String =
            n.lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-')
    }
}

enum class ModelFormat(val label: String, val ext: List<String>) {
    GGUF("gguf", listOf("gguf")),
    SAFETENSORS("safetensors", listOf("safetensors")),
    ONNX("onnx", listOf("onnx")),
    PYAGENT("python-agent", listOf("py")),
    DOCKER("docker", listOf("dockerfile")),
    UNKNOWN("unknown", emptyList());

    companion object {
        fun detect(filename: String): ModelFormat {
            val f = filename.lowercase()
            return when {
                f.endsWith(".gguf") -> GGUF
                f.endsWith(".safetensors") || f.contains("pytorch_model") || f.contains("model.safetensors") -> SAFETENSORS
                f.endsWith(".onnx") -> ONNX
                f == "main.py" || f.endsWith(".py") -> PYAGENT
                f == "dockerfile" || f == "docker-compose.yml" -> DOCKER
                else -> UNKNOWN
            }
        }
    }
}

/** run.yaml / manifest.json parsed fields. */
data class RunSpec(
    val format: String? = null,
    val file: String? = null,
    val chatTemplate: String? = null,
    val ctx: Int? = null,
    val gpuLayers: Int? = null,
    val threads: Int? = null,
    val cmd: String? = null,        // for agent formats
    val port: Int? = null,
    val extra: Map<String, String> = emptyMap()
)
