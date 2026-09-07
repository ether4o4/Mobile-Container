package com.ether4o4.mobilecontainer.data.registry

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Universal pull engine: downloads a file from any supported source with
 * HTTP range/resume support and a progress callback. Writes into the target
 * file; safe to resume after interruption.
 */
class PullEngine(
    private val client: OkHttpClient = defaultClient()
) {
    /** Result of a pull. */
    data class Result(val file: File, val totalBytes: Long, val mimeType: String?)

    /** Progress callback: (bytesDownloaded, totalBytes or -1). */

    fun pull(url: String, target: File, progress: (Long, Long) -> Unit, cancel: () -> Boolean = { false }): Result {
        target.parentFile?.mkdirs()
        val existing = if (target.exists()) target.length() else 0L

        val reqBuilder = Request.Builder().url(url).header("Accept-Encoding", "identity")
        // HuggingFace needs no auth for public repos; GitHub raw/releases are public too.
        if (existing > 0) reqBuilder.header("Range", "bytes=$existing-")

        val resp = client.newCall(reqBuilder.build()).execute()
        if (!resp.isSuccessful && resp.code != 206) {
            resp.close()
            throw RuntimeException("HTTP ${resp.code} for $url")
        }
        val total = resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
            ?: resp.body?.contentLength()?.takeIf { it > 0 }?.let { it + existing }
            ?: -1L
        val mime = resp.header("Content-Type")
        val body = resp.body ?: throw RuntimeException("empty body for $url")

        val raf = RandomAccessFile(target, "rw")
        if (existing > 0) raf.seek(existing)
        try {
            body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var downloaded = existing
                while (true) {
                    if (cancel()) throw RuntimeException("cancelled")
                    val n = input.read(buf)
                    if (n <= 0) break
                    raf.write(buf, 0, n)
                    downloaded += n
                    progress(downloaded, total)
                }
                return Result(target, downloaded, mime)
            }
        } finally {
            raf.close()
            resp.close()
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        /** Resolve a HF "resolve" URL to a direct download link. */
        fun resolveUrl(raw: String): String {
            val t = raw.trim()
            if (t.startsWith("hf://")) {
                val rest = t.removePrefix("hf://")
                // hf://owner/repo/branch/path -> https://huggingface.co/owner/repo/resolve/branch/path
                val parts = rest.split("/", limit = 4)
                require(parts.size >= 4) { "hf:// URI needs owner/repo/branch/path" }
                return "https://huggingface.co/${parts[0]}/${parts[1]}/resolve/${parts[2]}/${parts[3]}"
            }
            if (t.startsWith("github://")) {
                val rest = t.removePrefix("github://")
                // github://user/repo/branch/path -> raw.githubusercontent.com
                val parts = rest.split("/", limit = 4)
                require(parts.size >= 4) { "github:// URI needs user/repo/branch/path" }
                return "https://raw.githubusercontent.com/${parts[0]}/${parts[1]}/${parts[2]}/${parts[3]}"
            }
            return t
        }
    }
}
