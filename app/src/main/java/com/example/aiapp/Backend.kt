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
data class Msg(
    val role: String,
    val content: String,
    /** File name of a picture attached to this message (stored in the app's private "images" folder). */
    val image: String? = null,
) : Parcelable

/**
 * A piece of text handed to `onPartial` that starts with this character is a status line
 * ("Loading model…"), not part of the reply itself.
 */
const val STATUS_MARK = '\u0001'

/** A problem whose message is written for the person and is shown as it is (in red, not sent back to the model). */
class UserFacingError(message: String) : Exception(message)

/** What a model that can't look at pictures should read for this message. */
fun Msg.textWithoutPicture(): String =
    if (image == null) content else "[A picture was attached here, but this model can't see pictures.] $content"

interface ModelBackend {
    /**
     * [onPartial] receives the reply as it is being written (used for streaming on-device).
     * Problems are thrown: a [UserFacingError] for things the person can fix, other exceptions otherwise.
     */
    suspend fun chat(history: List<Msg>, onPartial: (String) -> Unit = {}): String

    /** Tokens used by the last chat() call (prompt + reply), or 0 if the backend doesn't know. */
    fun usedTokens(): Int = 0

    /** Asks the backend to stop writing the reply as soon as it can. */
    fun stop() {}
}

/** Calls any OpenAI-compatible server. */
class CloudBackend(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : ModelBackend {
    private var used = 0

    @Volatile
    private var active: HttpURLConnection? = null

    override fun usedTokens(): Int = used

    override fun stop() {
        try {
            active?.disconnect()
        } catch (ignored: Exception) {
            // already closed
        }
    }

    override suspend fun chat(history: List<Msg>, onPartial: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val base = baseUrl.trim()
            if (base.isEmpty()) throw UserFacingError("Add your server URL in Settings first.")
            if (!base.startsWith("https://")) throw UserFacingError("The server URL must start with https://")

            val body = JSONObject()
                .put("model", model)
                .put(
                    "messages",
                    JSONArray(history.map {
                        JSONObject()
                            .put("role", it.role)
                            .put("content", it.textWithoutPicture())
                    })
                )

            val conn = (URL(base.trimEnd('/') + "/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 130000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            }
            active = conn

            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }

                val ok = conn.responseCode in 200..299
                val text = (if (ok) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: "Empty response from server"

                if (!ok) throw IOException("HTTP ${conn.responseCode}: ${text.take(300)}")

                val json = JSONObject(text)
                val reply = json
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                used = json.optJSONObject("usage")?.optInt("total_tokens", 0) ?: 0

                return@withContext reply
            } finally {
                active = null
                conn.disconnect()
            }
        }
}
