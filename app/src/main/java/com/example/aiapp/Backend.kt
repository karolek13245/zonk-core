package com.example.aiapp

import android.os.Parcelable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@Parcelize
data class Msg(val role: String, val content: String) : Parcelable

interface ModelBackend {
    /** [onPartial] receives the reply as it is being written (used for streaming on-device). */
    suspend fun chat(history: List<Msg>, onPartial: (String) -> Unit = {}): String
}

/** Calls any OpenAI-compatible server. */
class CloudBackend(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : ModelBackend {
    override suspend fun chat(history: List<Msg>, onPartial: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val base = baseUrl.trim()
            if (base.isEmpty()) throw IOException("Add your server URL in Settings first.")
            if (!base.startsWith("https://")) throw IOException("Server URL must start with https://")

            val body = JSONObject()
                .put("model", model)
                .put(
                    "messages",
                    JSONArray(history.map {
                        JSONObject()
                            .put("role", it.role)
                            .put("content", it.content)
                    })
                )

            val conn = (URL(base.trimEnd('/') + "/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 60000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            }

            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }

                val ok = conn.responseCode in 200..299
                val text = (if (ok) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: "Empty response from server"

                if (!ok) throw IOException("HTTP ${conn.responseCode}: $text")

                val reply = JSONObject(text)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")

                return@withContext reply
            } finally {
                conn.disconnect()
            }
        }
}
