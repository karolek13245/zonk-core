package com.example.aiapp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device backend powered by llama.cpp through a JNI bridge.
 *
 * Until the native library is built (see README -> "On-device model"), this
 * class fails gracefully with instructions instead of crashing.
 */
class LlamaBackend(private val context: Context, private val sizeKey: String) : ModelBackend {

    companion object {
        private var handle: Long = 0
        private var loadedPath: String? = null

        /** One model file per size, e.g. models/model-1.5b.gguf */
        fun modelFile(context: Context, sizeKey: String): File =
            File(context.filesDir, "models/model-${sizeKey.lowercase()}.gguf")

        /** Human-readable status shown in Host and Settings. */
        fun modelStatus(context: Context, sizeKey: String): String {
            val f = modelFile(context, sizeKey)
            val gb = f.length() / 1_073_741_824.0
            return when {
                !f.exists() -> "No $sizeKey model found. Put a .gguf file at:\n${f.absolutePath}"
                f.length() < 100_000_000 -> "Model looks too small (${"%.0f".format(f.length() / 1_048_576.0)} MB) — download may be incomplete."
                else -> "Ready: ${f.name} (${"%.1f".format(gb)} GB)"
            }
        }
    }

    override suspend fun chat(history: List<Msg>): String = withContext(Dispatchers.Default) {
        val file = modelFile(context, sizeKey)
        if (!file.exists()) {
            return@withContext "On-device model not found.\n\nPut a .gguf model at:\n${file.absolutePath}\n\n(See README -> On-device model.)"
        }
        try {
            System.loadLibrary("llama-jni")
        } catch (e: UnsatisfiedLinkError) {
            return@withContext "Native engine not bundled yet.\n\nBuild the llama.cpp JNI library (README -> On-device model) and reinstall."
        }
        val prompt = buildString {
            append("A helpful assistant.\n\n")
            history.forEach { m ->
                val who = if (m.role == "user") "User" else "Assistant"
                append("$who: ${m.content}\n")
            }
            append("Assistant:")
        }
        if (loadedPath != file.absolutePath) {
            if (handle != 0L) nativeClose(handle)
            handle = nativeInit(file.absolutePath)
            loadedPath = if (handle != 0L) file.absolutePath else null
        }
        if (handle == 0L) return@withContext "Failed to load model. Check that the file is a valid .gguf model."
        val bytes = try {
            nativeComplete(handle, prompt.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            return@withContext "On-device error: ${e.message}"
        }
        String(bytes, Charsets.UTF_8)
            .substringBefore("\nUser:")
            .trim()
            .ifBlank { "(The model returned an empty reply.)" }
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeComplete(handle: Long, prompt: ByteArray): ByteArray
    private external fun nativeClose(handle: Long)
}
