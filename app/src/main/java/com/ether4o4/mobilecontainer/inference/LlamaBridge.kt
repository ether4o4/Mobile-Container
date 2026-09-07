package com.ether4o4.mobilecontainer.inference

/**
 * JNI bridge to llama.cpp (llama_bridge.cpp -> libmcllama.so).
 * Loads a GGUF model and streams completions token-by-token.
 */
object LlamaBridge {
    init {
        try { System.loadLibrary("mcllama") } catch (e: Throwable) { /* lib may be absent on this abi */ }
    }

    interface Callback {
        fun onToken(piece: String)
        fun onDone()
        fun isCancelled(): Boolean = false
    }

    fun loadModel(path: String, gpuLayers: Int, ctx: Int, threads: Int, batch: Int): Long =
        nativeLoadModel(path, gpuLayers, ctx, threads, batch)

    fun modelInfo(handle: Long): String = nativeModelInfo(handle)

    fun generate(
        handle: Long, prompt: String, temp: Float, topK: Int, topP: Float,
        repeatPenalty: Float, maxTokens: Int, cb: Callback
    ) = nativeGenerate(handle, prompt, temp, topK, topP, repeatPenalty, maxTokens, cb)

    fun freeModel(handle: Long) = nativeFreeModel(handle)
    fun supportsGpu(): Boolean = nativeSupportsGpu()

    @JvmStatic external fun nativeLoadModel(path: String, gpu: Int, ctx: Int, threads: Int, batch: Int): Long
    @JvmStatic external fun nativeModelInfo(handle: Long): String
    @JvmStatic external fun nativeGenerate(handle: Long, prompt: String, temp: Float, topK: Int, topP: Float, repeatPenalty: Float, maxTokens: Int, cb: Callback)
    @JvmStatic external fun nativeFreeModel(handle: Long)
    @JvmStatic external fun nativeSupportsGpu(): Boolean
}
