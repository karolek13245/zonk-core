package com.example.aiapp

import android.os.Parcelable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import org.json.JSONArray
import org.json.JSONException
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

/** The server answered, but with an error status. [code] is the HTTP status (for example 401 or 503). */
class HttpFailure(val code: Int, message: String) : IOException(message)

/**
 * The text of an error answer, short and safe to show: AI servers send `{"error":{"message":"..."}}`, so that sentence is
 * used when it is there. Every copy of [secret] is replaced first, so a key can't travel on into the chat.
 */
fun friendlyHttpError(code: Int, body: String, secret: String): String {
    val sentence = try {
        val err = JSONObject(body).opt("error")
        when (err) {
            is JSONObject -> err.optString("message", "")
            is String -> err
            else -> ""
        }
    } catch (e: JSONException) {
        ""
    }
    val shown = redactSecret(sentence.ifBlank { body }.trim().take(300), secret)
    return if (shown.isEmpty()) "HTTP $code" else "HTTP $code: $shown"
}

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

/**
 * Reads the answer of an OpenAI-style server: the reply, and the tokens used (0 when the server doesn't say).
 * Throws a [UserFacingError] when the answer isn't what an AI server sends (a wrong address often gives a web page).
 */
fun parseChatAnswer(text: String): Pair<String, Int> {
    val json = try {
        JSONObject(text)
    } catch (e: JSONException) {
        throw UserFacingError("The server answered, but not the way an AI server does. Check the server address in Settings.")
    }
    val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        ?: throw UserFacingError("The server's answer has no reply in it. Check the server address and the model name in Settings.")
    val reply = if (message.isNull("content")) "" else message.optString("content", "")
    return reply to (json.optJSONObject("usage")?.optInt("total_tokens", 0) ?: 0)
}

/** The request of an OpenAI-style server: the model name and the chat. Pictures are never sent (only a note that there was one). */
internal fun chatRequestBody(model: String, history: List<Msg>): JSONObject =
    JSONObject()
        .put("model", model)
        .put("messages", JSONArray(history.map { JSONObject().put("role", it.role).put("content", it.textWithoutPicture()) }))

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

            val body = chatRequestBody(model, history)

            // Only visible characters go into the header: a pasted key with a hidden line break or space
            // could otherwise break the request (or add a header of its own).
            val token = cleanApiKey(apiKey)
            val conn = (URL(base.trimEnd('/') + "/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 130000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
            }
            active = conn

            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }

                val ok = conn.responseCode in 200..299
                val text = (if (ok) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: "Empty response from server"

                // A server can echo the request back in its error page: never let the key travel on into the chat.
                if (!ok) throw HttpFailure(conn.responseCode, friendlyHttpError(conn.responseCode, text, token))

                val answer = parseChatAnswer(text)
                used = answer.second
                return@withContext answer.first
            } finally {
                active = null
                conn.disconnect()
            }
        }
}
