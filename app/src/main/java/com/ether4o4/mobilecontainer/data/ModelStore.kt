package com.ether4o4.mobilecontainer.data

import android.content.Context
import java.io.File

/**
 * Owns the on-device storage layout:
 *  filesDir/mobilecontainer/
 *    models/<dirName>/...        pulled artifacts
 *    manifests/<dirName>.json    one manifest per model
 *    runtimes/venvs/             isolated python envs (future)
 *    logs/<dirName>.log          per-model run logs
 *    bin/                        bundled busybox etc.
 */
class ModelStore(private val context: Context) {

    val root: File by lazy { File(context.filesDir, "mobilecontainer").apply { mkdirs() } }
    val modelsDir: File get() = File(root, "models").apply { mkdirs() }
    val manifestsDir: File get() = File(root, "manifests").apply { mkdirs() }
    val logsDir: File get() = File(root, "logs").apply { mkdirs() }
    val runtimesDir: File get() = File(root, "runtimes").apply { mkdirs() }
    val binDir: File get() = File(root, "bin").apply { mkdirs() }

    fun modelDir(name: String): File = File(modelsDir, ModelManifest.sanitizeName(name))
    fun manifestFile(name: String): File = File(manifestsDir, ModelManifest.sanitizeName(name) + ".json")
    fun logFile(name: String): File = File(logsDir, ModelManifest.sanitizeName(name) + ".log")

    fun list(): List<ModelManifest> = manifestsDir.listFiles { f -> f.isFile && f.extension == "json" }
        ?.mapNotNull { runCatching { readManifest(it) }.getOrNull() }
        ?.sortedByDescending { it.pulledAt } ?: emptyList()

    fun get(name: String): ModelManifest? = runCatching { readManifest(manifestFile(name)) }.getOrNull()

    fun save(m: ModelManifest) {
        manifestFile(m.name).writeText(gson.toJson(m))
    }

    fun delete(name: String): Boolean {
        val m = get(name) ?: return false
        modelDir(name).deleteRecursively()
        manifestFile(name).delete()
        logFile(name).delete()
        return true
    }

    fun diskUsage(): Long = modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun readManifest(f: File): ModelManifest? {
        val text = f.readText()
        if (text.isBlank()) return null
        return gson.fromJson(text, ModelManifest::class.java)
    }

    companion object {
        private val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
    }
}
