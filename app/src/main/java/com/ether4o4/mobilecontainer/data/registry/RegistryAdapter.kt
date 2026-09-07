package com.ether4o4.mobilecontainer.data.registry

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Registry adapter layer. Resolves a pull spec into one or more concrete
 * download URLs + filenames, and (for HuggingFace) can list repo files so we
 * can auto-pick the .gguf when only a repo is given.
 *
 * Supported specs:
 *   hf://owner/repo[/branch]/path            (branch optional, defaults to main)
 *   hf://owner/repo                          (auto-list, pick .gguf)
 *   github://user/repo/branch/path           (raw)
 *   github://user/repo/releases              (latest release assets)
 *   https://.../file.gguf                    (arbitrary URL)
 *   /local/path                              (filesystem copy)
 */
class RegistryAdapter(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
) {
    data class ResolvedFile(val url: String, val filename: String, val sizeHint: Long = -1L)

    /** Resolve a pull spec into a list of files to download. */
    fun resolve(spec: String): List<ResolvedFile> {
        val t = spec.trim()
        return when {
            t.startsWith("hf://") -> resolveHf(t.removePrefix("hf://"))
            t.startsWith("github://") -> resolveGithub(t.removePrefix("github://"))
            t.startsWith("http://") || t.startsWith("https://") ->
                listOf(ResolvedFile(t, t.substringAfterLast('/').ifBlank { "download" }))
            t.startsWith("/") -> listOf(ResolvedFile("file://$t", File(t).name))
            else -> listOf(ResolvedFile(t, t.substringAfterLast('/').ifBlank { "download" }))
        }
    }

    private fun resolveHf(rest: String): List<ResolvedFile> {
        val parts = rest.split("/").filter { it.isNotBlank() }
        // owner/repo[/branch]/path...
        if (parts.size >= 4) {
            val (owner, repo, branch) = parts
            val path = parts.drop(3).joinToString("/")
            val url = "https://huggingface.co/$owner/$repo/resolve/$branch/$path"
            return listOf(ResolvedFile(url, path.substringAfterLast('/')))
        }
        // owner/repo[/branch]  -> list files, pick .gguf
        val owner = parts.getOrNull(0) ?: throw IllegalArgumentException("bad hf spec")
        val repo = parts.getOrNull(1) ?: throw IllegalArgumentException("bad hf spec")
        val branch = parts.getOrNull(2) ?: "main"
        val files = listHfFiles(owner, repo, branch)
        val gguf = files.filter { it.filename.endsWith(".gguf") }
            .sortedByDescending { it.sizeHint }
        if (gguf.isEmpty()) {
            throw RuntimeException("No .gguf files in $owner/$repo (branch $branch). Specify a path.")
        }
        return gguf
    }

    /** List files in a HF repo tree via the API. */
    private fun listHfFiles(owner: String, repo: String, branch: String): List<ResolvedFile> {
        val url = "https://huggingface.co/api/models/$owner/$repo/tree/$branch?recursive=true"
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HF tree HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            // parse JSON array of {path, size, type}
            val gson = Gson()
            val tree = gson.fromJson(body, Array<HfTreeItem>::class.java) ?: emptyArray()
            return tree.filter { it.type == "file" }.map {
                ResolvedFile(
                    "https://huggingface.co/$owner/$repo/resolve/$branch/${it.path}",
                    it.path.substringAfterLast('/'),
                    it.size ?: -1L
                )
            }
        }
    }

    private fun resolveGithub(rest: String): List<ResolvedFile> {
        val parts = rest.split("/").filter { it.isNotBlank() }
        // user/repo/branch/path
        if (parts.size >= 4) {
            val (user, repo, branch) = parts
            val path = parts.drop(3).joinToString("/")
            val url = "https://raw.githubusercontent.com/$user/$repo/$branch/$path"
            return listOf(ResolvedFile(url, path.substringAfterLast('/')))
        }
        // user/repo/releases -> latest release assets
        if (parts.size == 2 || (parts.size == 3 && parts[2] == "releases")) {
            val (user, repo) = parts
            return listGithubReleaseAssets(user, repo)
        }
        throw IllegalArgumentException("bad github spec: $rest")
    }

    private fun listGithubReleaseAssets(user: String, repo: String): List<ResolvedFile> {
        val url = "https://api.github.com/repos/$user/$repo/releases/latest"
        val req = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("GitHub releases HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val gson = Gson()
            val rel = gson.fromJson(body, GhRelease::class.java) ?: return emptyList()
            return rel.assets?.map {
                ResolvedFile(it.browser_download_url, it.name, (it.size ?: -1L).toLong())
            } ?: emptyList()
        }
    }

    private data class HfTreeItem(val path: String, val type: String?, val size: Long?)
    private data class GhRelease(val assets: List<GhAsset>?)
    private data class GhAsset(val name: String, val size: Int?, val browser_download_url: String)
}
