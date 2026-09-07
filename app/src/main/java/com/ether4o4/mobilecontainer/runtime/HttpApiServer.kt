package com.ether4o4.mobilecontainer.runtime

import android.util.Log
import com.google.gson.Gson
import com.ether4o4.mobilecontainer.data.ModelManifest
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * OpenAI-compatible local HTTP server. Exposes:
 *   GET  /v1/models
 *   POST /v1/chat/completions   (streaming + non-streaming)
 *   POST /v1/completions
 *   GET  /health
 * Delegates generation to the InferenceEngine.
 */
class HttpApiServer(
    port: Int,
    private val engine: InferenceEngine,
    private val store: () -> ModelManifest?
) : NanoHTTPD(port) {

    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        return try {
            when {
                uri == "/health" && method == Method.GET -> ok(mapOf("status" to "ok"))
                uri == "/v1/models" && method == Method.GET -> ok(modelsList())
                uri == "/v1/chat/completions" && method == Method.POST -> chatCompletions(session)
                uri == "/v1/completions" && method == Method.POST -> completions(session)
                else -> NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"not found\"}")
            }
        } catch (e: Exception) {
            Log.e("MCHTTP", "serve error", e)
            json(Response.Status.INTERNAL_ERROR, mapOf("error" to (e.message ?: "error")))
        }
    }

    private fun modelsList(): Map<String, Any> {
        val m = store()
        val data = if (m != null) listOf(mapOf("id" to m.name, "object" to "model")) else emptyList<Any>()
        return mapOf("object" to "list", "data" to data)
    }

    private fun chatCompletions(session: IHTTPSession): Response {
        val body = readBody(session)
        val req = gson.fromJson(body, ChatRequest::class.java)
        val m = store() ?: throw RuntimeException("no model loaded")
        val prompt = buildChatPrompt(req)
        val stream = req.stream ?: false
        val temp = (req.temperature ?: 0.8f).toFloat()
        val topP = (req.top_p ?: 0.95f).toFloat()
        val maxTok = req.max_tokens ?: 512

        if (stream) {
            val pipeIn = PipedInputStream(64 * 1024)
            val pipeOut = PipedOutputStream(pipeIn)
            Thread {
                try {
                    pipeOut.write(sseChunk(chatChunk(m.name, "", "start")).toByteArray())
                    engine.complete(prompt, temp, 40, topP, 1.1f, maxTok,
                        { piece -> pipeOut.write(sseChunk(chatChunk(m.name, piece, "delta")).toByteArray()) },
                        { false })
                    pipeOut.write(sseChunk(chatChunk(m.name, "", "stop")).toByteArray())
                    pipeOut.write("data: [DONE]\n\n".toByteArray())
                } catch (e: Exception) {
                    pipeOut.write(sseChunk(mapOf("error" to (e.message ?: "error"))).toByteArray())
                } finally {
                    pipeOut.close()
                }
            }.start()
            return NanoHTTPD.newChunkedResponse(Response.Status.OK, "text/event-stream", pipeIn)
        } else {
            val sb = StringBuilder()
            engine.complete(prompt, temp, 40, topP, 1.1f, maxTok, { sb.append(it) }, { false })
            val text = sb.toString()
            return ok(mapOf(
                "id" to "chatcmpl-${System.currentTimeMillis()}",
                "object" to "chat.completion",
                "model" to m.name,
                "choices" to listOf(mapOf(
                    "index" to 0,
                    "message" to mapOf("role" to "assistant", "content" to text),
                    "finish_reason" to "stop"
                ))
            ))
        }
    }

    private fun completions(session: IHTTPSession): Response {
        val body = readBody(session)
        val req = gson.fromJson(body, ChatRequest::class.java)
        val m = store() ?: throw RuntimeException("no model loaded")
        val prompt = req.prompt ?: req.messages?.joinToString("\n") { it.content ?: "" } ?: ""
        val sb = StringBuilder()
        engine.complete(prompt, (req.temperature ?: 0.8f).toFloat(), 40,
            (req.top_p ?: 0.95f).toFloat(), 1.1f, req.max_tokens ?: 512, { sb.append(it) }, { false })
        return ok(mapOf(
            "id" to "cmpl-${System.currentTimeMillis()}",
            "object" to "text_completion",
            "model" to m.name,
            "choices" to listOf(mapOf("text" to sb.toString(), "finish_reason" to "stop"))
        ))
    }

    private fun buildChatPrompt(req: ChatRequest): String {
        val msgs = req.messages ?: return req.prompt ?: ""
        val sb = StringBuilder()
        for (m in msgs) {
            val role = m.role ?: "user"
            sb.append("<").append(role).append(">").append(m.content ?: "").append("</").append(role).append(">\n")
        }
        sb.append("<assistant>")
        return sb.toString()
    }

    private fun chatChunk(model: String, content: String, type: String): Map<String, Any> = mapOf(
        "id" to "chatcmpl-${System.currentTimeMillis()}",
        "object" to "chat.completion.chunk",
        "model" to model,
        "choices" to listOf(mapOf("index" to 0, "delta" to mapOf("content" to content), "finish_reason" to if (type == "stop") "stop" else null))
    )

    private fun sseChunk(obj: Map<String, Any>): String = "data: ${gson.toJson(obj)}\n\n"

    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        session.parseBody(map)
        return map["postData"] ?: ""
    }

    private fun ok(obj: Map<String, Any>): Response = json(Response.Status.OK, obj)
    private fun json(status: Response.Status, obj: Map<String, Any>): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", gson.toJson(obj))

    // ---- request models ----
    data class ChatRequest(
        val model: String? = null,
        val messages: List<Msg>? = null,
        val prompt: String? = null,
        val temperature: Number? = null,
        val top_p: Number? = null,
        val max_tokens: Int? = null,
        val stream: Boolean? = null
    )
    data class Msg(val role: String? = null, val content: String? = null)
}
