package com.example.aiapp

import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** A real, public GGUF model that llama.cpp can run on a phone (4-bit Q4_K_M, or 8-bit for the small picture model). */
data class CatalogModel(
    val sizeKey: String,
    val name: String,
    val url: String,
    val sizeGb: Double,
    val license: String,
    /** What the model is for, shown as a filter chip in the Settings search. */
    val category: String = "General",
    /** Picture models also need a second file, the "projector" that reads images. */
    val projectorUrl: String? = null,
    /** Size of the projector file; [sizeGb] already includes it. */
    val projectorGb: Double = 0.0,
) {
    val canSeePictures: Boolean get() = projectorUrl != null
}

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
    // Picture model: SmolVLM 500M from ggml-org (the llama.cpp team). Two files: the model (437 MB)
    // and the projector that reads images (109 MB). Both are 8-bit and Apache 2.0.
    CatalogModel(
        "Vision", "SmolVLM 500M (reads pictures)",
        "https://huggingface.co/ggml-org/SmolVLM-500M-Instruct-GGUF/resolve/main/SmolVLM-500M-Instruct-Q8_0.gguf",
        0.55, "Apache 2.0", "Vision",
        projectorUrl = "https://huggingface.co/ggml-org/SmolVLM-500M-Instruct-GGUF/resolve/main/mmproj-SmolVLM-500M-Instruct-Q8_0.gguf",
        projectorGb = 0.11,
    ),
    CatalogModel(
        "7B", "Qwen2.5 7B Instruct",
        "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
        4.68, "Apache 2.0", "General",
    ),
)

fun catalogFor(sizeKey: String): CatalogModel = CATALOG.firstOrNull { it.sizeKey == sizeKey } ?: CATALOG[0]

/** A short name for small spaces, e.g. "Qwen2.5 1.5B Instruct" becomes "Qwen2.5 1.5B". */
fun shortModelName(name: String): String = name.substringBefore(" (").removeSuffix(" Instruct").trim()

/** Anything smaller than this is not a real model (e.g. an error page). */
private const val MIN_MODEL_BYTES = 100_000_000L
private const val MIN_PROJECTOR_BYTES = 50_000_000L

/** One downloadable file of a model (the model itself, or a picture model's projector). */
private class Part(
    val prefKey: String,
    val url: String,
    val dest: File,
    val partFile: File,
    val minBytes: Long,
    val estBytes: Long,
)

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

    private fun parts(m: CatalogModel): List<Part> {
        val main = LlamaBackend.modelFile(context, m.sizeKey)
        val list = ArrayList<Part>()
        list.add(
            Part(
                "dl_${m.sizeKey}", m.url, main, File(main.path + ".part"),
                MIN_MODEL_BYTES, ((m.sizeGb - m.projectorGb) * 1_000_000_000.0).toLong(),
            )
        )
        val projectorUrl = m.projectorUrl
        if (projectorUrl != null) {
            val proj = LlamaBackend.projectorFile(context, m.sizeKey)
            list.add(
                Part(
                    "dl_${m.sizeKey}_mm", projectorUrl, proj, File(proj.path + ".part"),
                    MIN_PROJECTOR_BYTES, (m.projectorGb * 1_000_000_000.0).toLong(),
                )
            )
        }
        return list
    }

    private fun isPartReady(p: Part): Boolean = p.dest.exists() && p.dest.length() >= p.minBytes

    fun isReady(sizeKey: String): Boolean = readyMap[sizeKey] == true

    fun start(m: CatalogModel) {
        if (states[m.sizeKey] is DlState.Running) return
        try {
            val ps = parts(m)
            val dir = ps[0].dest.parentFile ?: return
            dir.mkdirs()
            val need = (m.sizeGb * 1_073_741_824.0).toLong() + 300_000_000L
            if (dir.usableSpace < need) {
                val gb = need / 1_073_741_824.0
                states[m.sizeKey] = DlState.Failed("Not enough free storage. Free up about ${"%.1f".format(gb)} GB and try again.")
                return
            }
            for (p in ps) {
                if (isPartReady(p)) continue   // this file is already on the phone
                p.partFile.delete()
                val req = DownloadManager.Request(Uri.parse(p.url))
                    .setTitle("Zonk-Core: ${m.name}")
                    .setDescription("Downloading model")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
                    .setDestinationUri(Uri.fromFile(p.partFile))
                val id = dm.enqueue(req)
                prefs.edit().putLong(p.prefKey, id).apply()
            }
            states[m.sizeKey] = DlState.Running(0)
        } catch (e: Exception) {
            states[m.sizeKey] = DlState.Failed("Could not start the download: ${e.message}")
        }
    }

    fun cancel(m: CatalogModel) {
        for (p in parts(m)) {
            val id = prefs.getLong(p.prefKey, -1L)
            if (id >= 0) {
                try { dm.remove(id) } catch (e: Exception) { /* already gone */ }
            }
            p.partFile.delete()
            prefs.edit().remove(p.prefKey).apply()
        }
        states.remove(m.sizeKey)
    }

    fun delete(m: CatalogModel) {
        for (p in parts(m)) p.dest.delete()
        readyMap[m.sizeKey] = false
        states.remove(m.sizeKey)
    }

    /** Call about once a second: updates progress and finishes completed downloads. */
    fun refresh() {
        for (m in CATALOG) refreshOne(m)
    }

    private fun failAll(m: CatalogModel, message: String) {
        for (p in parts(m)) {
            val id = prefs.getLong(p.prefKey, -1L)
            if (id >= 0) {
                try { dm.remove(id) } catch (e: Exception) { /* ignore */ }
            }
            p.partFile.delete()
            prefs.edit().remove(p.prefKey).apply()
        }
        states[m.sizeKey] = DlState.Failed(message)
    }

    private fun refreshOne(m: CatalogModel) {
        val ps = parts(m)
        readyMap[m.sizeKey] = ps.all { isPartReady(it) }

        var anyActive = false
        var waiting = false
        var cleared = false
        var failure: String? = null
        var gotSum = 0L
        var estSum = 0L

        for (p in ps) {
            estSum += p.estBytes
            val id = prefs.getLong(p.prefKey, -1L)
            if (id < 0) {
                if (isPartReady(p)) gotSum += p.estBytes
                continue
            }
            val cursor = dm.query(DownloadManager.Query().setFilterById(id))
            if (cursor == null) continue
            cursor.use { c ->
                if (!c.moveToFirst()) {
                    // The system no longer knows this download (cleared by the user).
                    prefs.edit().remove(p.prefKey).apply()
                    cleared = true
                    return@use
                }
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        p.dest.delete()
                        if (p.partFile.exists() && p.partFile.renameTo(p.dest) && p.dest.length() >= p.minBytes) {
                            try { dm.remove(id) } catch (e: Exception) { /* ignore */ }
                            prefs.edit().remove(p.prefKey).apply()
                            gotSum += p.estBytes
                        } else {
                            failure = "The download finished but the file looks wrong. Please try again."
                        }
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        failure = "Download failed (code $reason). Check your Wi-Fi and storage, then try again."
                    }
                    DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_PENDING -> {
                        anyActive = true
                        waiting = true
                        gotSum += got
                    }
                    else -> {
                        anyActive = true
                        gotSum += got
                    }
                }
            }
        }

        val failureMessage = failure
        if (failureMessage != null) {
            failAll(m, failureMessage)
            return
        }
        val ready = ps.all { isPartReady(it) }
        readyMap[m.sizeKey] = ready
        if (cleared && !ready) {
            cancel(m)
            return
        }
        val running = states[m.sizeKey] is DlState.Running
        when {
            ready -> if (running) states.remove(m.sizeKey)
            anyActive -> {
                val pct = if (estSum > 0) (gotSum * 100 / estSum).toInt().coerceIn(0, 100) else 0
                states[m.sizeKey] = DlState.Running(pct, waiting)
            }
            running -> states.remove(m.sizeKey)
        }
    }
}

/**
 * One model: name, size, license, its state, and a Download / Cancel / Delete button with progress.
 * [inUse] marks the model that chats are currently using. Delete asks first, because a model is a big download.
 * The row adds no padding of its own, so each screen decides how to inset it.
 */
@Composable
fun ModelRow(m: CatalogModel, downloader: ModelDownloader, inUse: Boolean = false) {
    val state = downloader.states[m.sizeKey]
    val ready = downloader.isReady(m.sizeKey)
    val sub = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    val sizeText = "%.2f".format(m.sizeGb)
    val title = m.name.substringBefore(" (").trim()
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("${m.sizeKey} · $sizeText GB · ${m.license}", fontSize = 13.sp, lineHeight = 18.sp, color = sub)
                if (m.canSeePictures) {
                    Text("Can look at pictures you send", fontSize = 13.sp, lineHeight = 18.sp, color = sub)
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                state is DlState.Running -> TextButton(onClick = { downloader.cancel(m) }) { Text("Cancel") }
                ready -> TextButton(onClick = { confirmDelete = true }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
                else -> FilledTonalButton(onClick = { downloader.start(m) }) { Text("Download") }
            }
        }
        when {
            state is DlState.Running -> {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(
                    if (state.waiting) "Waiting for Wi-Fi… ${state.percent}%" else "Downloading… ${state.percent}%",
                    fontSize = 13.sp, lineHeight = 18.sp, color = sub,
                )
            }
            state is DlState.Failed -> {
                Spacer(Modifier.height(4.dp))
                Text(state.message, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.error)
            }
            ready -> {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF34C759)))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (inUse) "In use right now" else "Ready on this phone",
                        fontSize = 13.sp, lineHeight = 18.sp, color = sub,
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete $title?") },
            text = { Text("It will be removed from this phone ($sizeText GB). You can download it again later.") },
            confirmButton = {
                TextButton(onClick = { downloader.delete(m); confirmDelete = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
