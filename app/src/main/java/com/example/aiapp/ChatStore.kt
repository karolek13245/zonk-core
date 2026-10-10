package com.example.aiapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One saved chat. */
data class Conversation(
    val id: String,
    val title: String,
    val archived: Boolean,
    val updated: Long,
    val messages: List<Msg>,
)

/** Title = the first thing the user wrote, shortened. */
fun chatTitle(messages: List<Msg>): String {
    val first = messages.firstOrNull { it.role == "user" }?.content?.trim().orEmpty()
    val oneLine = first.replace(Regex("\\s+"), " ")
    return when {
        oneLine.isEmpty() -> "New chat"
        oneLine.length > 40 -> oneLine.take(40).trimEnd() + "…"
        else -> oneLine
    }
}

/**
 * Keeps every chat in one small JSON file inside the app's private storage.
 * (The old version kept the open chat in Android's saved-state Bundle, which can
 * crash with "TransactionTooLargeException" once a chat gets long.)
 */
class ChatStore(context: Context) {
    private val file = File(context.filesDir, "chats.json")
    private val tmp = File(context.filesDir, "chats.json.tmp")

    fun load(): List<Conversation> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val ms = o.getJSONArray("messages")
                Conversation(
                    id = o.getString("id"),
                    title = o.optString("title", "New chat"),
                    archived = o.optBoolean("archived", false),
                    updated = o.optLong("updated", 0L),
                    messages = (0 until ms.length()).map { j ->
                        val m = ms.getJSONObject(j)
                        Msg(m.getString("role"), m.getString("content"))
                    },
                )
            }
        } catch (e: Exception) {
            // Unreadable file: keep a copy instead of silently overwriting it.
            try { file.renameTo(File(file.path + ".bad")) } catch (ignored: Exception) { }
            emptyList()
        }
    }

    fun save(chats: List<Conversation>) {
        try {
            val arr = JSONArray()
            for (c in chats) {
                val ms = JSONArray()
                for (m in c.messages) ms.put(JSONObject().put("role", m.role).put("content", m.content))
                arr.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("title", c.title)
                        .put("archived", c.archived)
                        .put("updated", c.updated)
                        .put("messages", ms)
                )
            }
            // Write to a temp file first so a crash mid-write can't destroy the old data.
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            // Saving history is best-effort; never crash the app over it.
        }
    }
}
