package com.ether4o4.mobilecontainer.runtime

import android.util.Log
import com.ether4o4.mobilecontainer.data.ModelManifest
import com.ether4o4.mobilecontainer.inference.LlamaBridge
import java.io.File

/**
 * Wraps LlamaBridge into a managed, single-model inference runtime.
 * Loads a GGUF model on demand and streams completions.
 */
class InferenceEngine {

    @Volatile private var handle: Long = 0L
    @Volatile private var loadedName: String? = null
    @Volatile private var loadedInfo: String = ""

    val isLoaded: Boolean get() = handle != 0L
    val currentModel: String? get() = loadedName
    val info: String get() = loadedInfo

    /** Load (or replace) the active model. */
    fun load(manifest: ModelManifest, modelRoot: File): String {
        unload()
        val primary = manifest.primaryFile ?: throw RuntimeException("No primary file for ${manifest.name}")
        val path = File(modelRoot, primary).absolutePath
        if (!File(path).exists()) throw RuntimeException("Model file missing: $path")
        val ctx = (manifest.params["ctx"] ?: "4096").toIntOrNull() ?: 4096
        val gpu = (manifest.params["gpu_layers"] ?: "0").toIntOrNull() ?: 0
        val threads = (manifest.params["threads"] ?: "4").toIntOrNull() ?: 4
        val h = LlamaBridge.loadModel(path, gpu, ctx, threads, 512)
        if (h == 0L) throw RuntimeException("Failed to load model (bad GGUF or OOM)")
        handle = h
        loadedName = manifest.name
        loadedInfo = LlamaBridge.modelInfo(h)
        Log.i("MCENGINE", "loaded ${manifest.name}: $loadedInfo")
        return loadedInfo
    }

    fun unload() {
        if (handle != 0L) {
            runCatching { LlamaBridge.freeModel(handle) }
            handle = 0L
            loadedName = null
            loadedInfo = ""
        }
    }

    /** Stream a completion. Returns the full text via the callback. */
    fun complete(
        prompt: String, temp: Float, topK: Int, topP: Float,
        repeatPenalty: Float, maxTokens: Int, onToken: (String) -> Unit, isCancelled: () -> Boolean
    ) {
        if (handle == 0L) throw RuntimeException("No model loaded")
        val cb = object : LlamaBridge.Callback {
            override fun onToken(piece: String) = onToken(piece)
            override fun onDone() {}
            override fun isCancelled(): Boolean = isCancelled()
        }
        LlamaBridge.generate(handle, prompt, temp, topK, topP, repeatPenalty, maxTokens, cb)
    }
}
