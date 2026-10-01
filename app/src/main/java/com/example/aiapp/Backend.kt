package com.example.aiapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Msg(val role: String, val content: String)

interface ModelBackend {
    suspend fun chat(history: List<Msg>): String
}

/** Calls any OpenAI-compatible server (your company's model API). */
class CloudBackend(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : ModelBackend {
    override suspend fun chat(history: List<Msg>): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray(history.map { JSONObject().put("role", it.role).put("content", it.content) }))
        val conn = (URL(baseUrl.trimEnd('/') + "/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val ok = conn.responseCode in 200..299
        val text = (if (ok) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
        if (!ok) error("HTTP ${conn.responseCode}: $text")
        JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }
}

/** Placeholder: plug in llama.cpp / MLC / ONNX Runtime here to run a model on the phone. */
class OnDeviceBackend : ModelBackend {
    override suspend fun chat(history: List<Msg>): String =
        "On-device model not installed yet. Add a runtime (e.g. llama.cpp) and load a .gguf model in OnDeviceBackend."
}
