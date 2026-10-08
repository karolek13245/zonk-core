package com.example.aiapp

import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** A real, public, 4-bit (Q4_K_M) GGUF model that llama.cpp can run on a phone. */
data class CatalogModel(
    val sizeKey: String,
    val name: String,
    val url: String,
    val sizeGb: Double,
    val license: String,
    /** What the model is for, shown as a filter chip in the Settings search. */
    val category: String = "General",
)

/**
 * Every entry here has been checked by hand: a real Hugging Face GGUF repo, a real
 * Q4_K_M file, and a license that is actually attached to that file (not a guess).
 * Add new models only after verifying the same three things.
 */
val CATALOG = listOf(
    CatalogModel(
        "1.5B", "Qwen2.5 1.5B Instruct",
        "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
        0.99, "Apache 2.0", "General",
    ),
    CatalogModel(
        "1.7B", "SmolLM2 1.7B Instruct",
        "https://huggingface.co/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
        1.06, "Apache 2.0", "General",
    ),
    CatalogModel(
        "3B", "Llama 3.2 3B Instruct",
        "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        2.02, "Llama 3.2 Community License", "General",
    ),
    CatalogModel(
        "3B-Code", "Qwen2.5 Coder 3B Instruct",
        "https://huggingface.co/bartowski/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf",
        2.01, "Apache 2.0", "Coding",
    ),
    CatalogModel(
        "7B", "Qwen2.5 7B Instruct",
        "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
        4.68, "Apache 2.0", "General",
    ),
)

fun catalogFor(sizeKey: String): CatalogModel = CATALOG.firstOrNull { it.sizeKey == sizeKey } ?: CATALOG[0]

/** Anything smaller than this is not a real model (e.g. an error page). */
private const val MIN_MODEL_BYTES = 100_000_000L

sealed interface DlState {
    data class Running(val percent: Int, val waiting: Boolean = false) : DlState
    data class Failed(val message: String) : DlState
}

/**
 * Downloads models with Android's built-in DownloadManager, so downloads keep going
 * in the background and survive leaving the app. Wi-Fi only (models are big).
 * The file is saved as *.part and renamed when complete, so a half-finished file is
 * never mistaken for a ready model.
 */
class ModelDownloader(private val context: Context, private val prefs: SharedPreferences) {
    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val states = mutableStateMapOf<String, DlState>()
    private val readyMap = mutableStateMapOf<String, Boolean>()

    private fun prefKey(m: CatalogModel) = "dl_${m.sizeKey}"
    private fun partFile(m: CatalogModel) = File(LlamaBackend.modelFile(context, m.sizeKey).path + ".part")

    fun isReady(sizeKey: String): Boolean = readyMap[sizeKey] == true

    fun start(m: CatalogModel) {
        if (states[m.sizeKey] is DlState.Running) return
        try {
            val dest = LlamaBackend.modelFile(context, m.sizeKey)
            val dir = dest.parentFile ?: return
            dir.mkdirs()
            val need = (m.sizeGb * 1_073_741_824.0).toLong() + 300_000_000L
            if (dir.usableSpace < need) {
                val gb = need / 1_073_741_824.0
                states[m.sizeKey] = DlState.Failed("Not enough free storage. Free up about ${"%.1f".format(gb)} GB and try again.")
                return
            }
            partFile(m).delete()
            val req = DownloadManager.Request(Uri.parse(m.url))
                .setTitle("Zonk-Core: ${m.name}")
                .setDescription("Downloading model")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
                .setDestinationUri(Uri.fromFile(partFile(m)))
            val id = dm.enqueue(req)
            prefs.edit().putLong(prefKey(m), id).apply()
            states[m.sizeKey] = DlState.Running(0)
        } catch (e: Exception) {
            states[m.sizeKey] = DlState.Failed("Could not start the download: ${e.message}")
        }
    }

    fun cancel(m: CatalogModel) {
        val id = prefs.getLong(prefKey(m), -1L)
        if (id >= 0) {
            try { dm.remove(id) } catch (e: Exception) { /* already gone */ }
        }
        partFile(m).delete()
        prefs.edit().remove(prefKey(m)).apply()
        states.remove(m.sizeKey)
    }

    fun delete(m: CatalogModel) {
        LlamaBackend.modelFile(context, m.sizeKey).delete()
        readyMap[m.sizeKey] = false
        states.remove(m.sizeKey)
    }

    /** Call about once a second: updates progress and finishes completed downloads. */
    fun refresh() {
        for (m in CATALOG) refreshOne(m)
    }

    private fun fail(m: CatalogModel, id: Long, message: String) {
        try { dm.remove(id) } catch (e: Exception) { /* ignore */ }
        partFile(m).delete()
        prefs.edit().remove(prefKey(m)).apply()
        states[m.sizeKey] = DlState.Failed(message)
    }

    private fun refreshOne(m: CatalogModel) {
        val dest = LlamaBackend.modelFile(context, m.sizeKey)
        readyMap[m.sizeKey] = dest.exists() && dest.length() >= MIN_MODEL_BYTES

        val id = prefs.getLong(prefKey(m), -1L)
        if (id < 0) return
        val cursor = dm.query(DownloadManager.Query().setFilterById(id)) ?: return
        cursor.use { c ->
            if (!c.moveToFirst()) {
                // The system no longer knows this download (cleared by the user).
                prefs.edit().remove(prefKey(m)).apply()
                states.remove(m.sizeKey)
                return
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val pct = if (total > 0) (got * 100 / total).toInt().coerceIn(0, 100) else 0
            val next: DlState? = when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val part = partFile(m)
                    dest.delete()
                    if (part.exists() && part.renameTo(dest) && dest.length() >= MIN_MODEL_BYTES) {
                        try { dm.remove(id) } catch (e: Exception) { /* ignore */ }
                        prefs.edit().remove(prefKey(m)).apply()
                        states.remove(m.sizeKey)
                        readyMap[m.sizeKey] = true
                    } else {
                        fail(m, id, "The download finished but the file looks wrong. Please try again.")
                    }
                    null
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    fail(m, id, "Download failed (code $reason). Check your Wi-Fi and storage, then try again.")
                    null
                }
                DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_PENDING -> DlState.Running(pct, waiting = true)
                else -> DlState.Running(pct)
            }
            if (next != null) states[m.sizeKey] = next
        }
    }
}

/** One model: name, size, license, and a Download / Cancel / Delete button with progress. */
@Composable
fun ModelRow(m: CatalogModel, downloader: ModelDownloader) {
    val state = downloader.states[m.sizeKey]
    val ready = downloader.isReady(m.sizeKey)
    val sub = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val sizeText = "%.2f".format(m.sizeGb)

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("${m.sizeKey} · $sizeText GB · ${m.license}", fontSize = 12.sp, color = sub)
            }
            when {
                state is DlState.Running -> TextButton(onClick = { downloader.cancel(m) }) { Text("Cancel") }
                ready -> TextButton(onClick = { downloader.delete(m) }) { Text("Delete") }
                else -> FilledTonalButton(onClick = { downloader.start(m) }) { Text("Download") }
            }
        }
        when {
            state is DlState.Running -> {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text(
                    if (state.waiting) "Waiting for Wi-Fi… ${state.percent}%" else "Downloading… ${state.percent}%",
                    fontSize = 12.sp, color = sub,
                )
            }
            state is DlState.Failed -> Text(state.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            ready -> Text("Ready on this phone", fontSize = 12.sp, color = Color(0xFF34C759))
        }
    }
}
