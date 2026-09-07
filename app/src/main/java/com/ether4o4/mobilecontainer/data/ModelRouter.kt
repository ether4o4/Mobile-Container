package com.ether4o4.mobilecontainer.data

import android.content.Context
import com.ether4o4.mobilecontainer.data.registry.PullEngine
import com.ether4o4.mobilecontainer.data.registry.RegistryAdapter
import java.io.File

/**
 * Orchestrates a pull: resolve spec -> download files -> detect format ->
 * write manifest. Returns the resulting ModelManifest.
 */
class ModelRouter(private val context: Context) {

    private val store = ModelStore(context)
    private val registry = RegistryAdapter()
    private val engine = PullEngine()

    data class PullEvent(val phase: String, val message: String, val downloaded: Long = 0, val total: Long = -1)

    fun pull(spec: String, nameOverride: String? = null, onEvent: (PullEvent) -> Unit, cancel: () -> Boolean = { false }): ModelManifest {
        onEvent(PullEvent("resolve", "Resolving $spec"))
        val files = registry.resolve(spec)
        if (files.isEmpty()) throw RuntimeException("Nothing to pull from $spec")

        val localName = nameOverride ?: inferName(spec, files)
        val modelDir = store.modelDir(localName)
        modelDir.mkdirs()

        val downloaded = mutableListOf<String>()
        var totalBytes = 0L
        for (rf in files) {
            if (cancel()) throw RuntimeException("cancelled")
            val target = File(modelDir, rf.filename)
            onEvent(PullEvent("download", "Fetching ${rf.filename}", 0, rf.sizeHint))
            val res = engine.pull(rf.url, target, { d, t -> onEvent(PullEvent("download", rf.filename, d, t)) }, cancel)
            downloaded.add(rf.filename)
            totalBytes += res.totalBytes
            // stop after the first big artifact if it's a gguf (avoid pulling whole repo)
            if (rf.filename.endsWith(".gguf") && files.size > 1) break
        }

        // detect format + run spec
        val spec2 = ManifestParser.parse(modelDir)
        val primary = spec2.file ?: downloaded.firstOrNull { it.endsWith(".gguf") } ?: downloaded.firstOrNull()
        val format = spec2.format?.let { runCatching { ModelFormat.valueOf(it.uppercase().replace("-", "_")) }.getOrNull() }
            ?: (primary?.let { ModelFormat.detect(it) } ?: ModelFormat.UNKNOWN)

        val manifest = ModelManifest(
            name = localName,
            source = spec,
            sourceType = sourceType(spec),
            format = format,
            files = downloaded,
            primaryFile = primary,
            chatTemplate = spec2.chatTemplate,
            params = spec2.extra + mapOf(
                "ctx" to (spec2.ctx?.toString() ?: "4096"),
                "gpu_layers" to (spec2.gpuLayers?.toString() ?: "0"),
                "threads" to (spec2.threads?.toString() ?: "4")
            ),
            sizeBytes = totalBytes
        )
        store.save(manifest)
        onEvent(PullEvent("done", "Saved ${manifest.name} (${format.label})"))
        return manifest
    }

    private fun inferName(spec: String, files: List<RegistryAdapter.ResolvedFile>): String {
        val base = files.firstOrNull()?.filename?.substringBeforeLast('.')?.lowercase()
            ?: spec.substringAfterLast('/').substringBeforeLast('.')
        return base.replace(Regex("[^a-z0-9._-]"), "-").trim('-').ifBlank { "model" }
    }

    private fun sourceType(spec: String): String = when {
        spec.startsWith("hf://") -> "hf"
        spec.startsWith("github://") -> "github"
        spec.startsWith("http") -> "url"
        else -> "local"
    }

    fun store(): ModelStore = store
}
