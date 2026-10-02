package com.example.aiapp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Receives each generated piece of text (UTF-8 bytes). Return false to stop generating. */
fun interface TokenSink {
    fun onToken(piece: ByteArray): Boolean
}

/** On-device backend powered by llama.cpp (see app/src/main/cpp). */
class LlamaBackend(private val context: Context, private val sizeKey: String) : ModelBackend {

    companion object {
        private var handle: Long = 0
        private var loadedKey: String? = null
        private var libLoaded = false

        /** Where downloaded models live (app-specific storage, no permission needed). */
        fun modelDir(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "models")

        /** One model file per size, e.g. models/model-1.5b.gguf */
        fun modelFile(context: Context, sizeKey: String): File =
            File(modelDir(context), "model-${sizeKey.lowercase()}.gguf")
    }

    override suspend fun chat(history: List<Msg>, onPartial: (String) -> Unit): String =
        withContext(Dispatchers.Default) {
            val file = modelFile(context, sizeKey)
            if (!file.exists()) {
                return@withContext "This model isn't downloaded yet.\n\nOpen the Host tab (Model card) or Settings > Models and tap Download."
            }
            if (!libLoaded) {
                try {
                    System.loadLibrary("llama-jni")
                    libLoaded = true
                } catch (e: UnsatisfiedLinkError) {
                    return@withContext "The on-device engine isn't available on this phone (it needs a 64-bit ARM phone)."
                }
            }

            // (Re)load the model if it changed or was re-downloaded.
            val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
            if (loadedKey != key) {
                onPartial("Loading model…")
                if (handle != 0L) {
                    nativeClose(handle)
                    handle = 0
                    loadedKey = null
                }
                handle = nativeInit(file.absolutePath)
                if (handle == 0L) {
                    return@withContext "Couldn't load the model. The file may be damaged: delete it in Settings > Models and download it again."
                }
                loadedKey = key
            }

            // role\0content\0role\0content\0 ...
            val chat = ByteArrayOutputStream()
            for (m in history) {
                chat.write(m.role.toByteArray(Charsets.UTF_8))
                chat.write(0)
                chat.write(m.content.replace("\u0000", "").toByteArray(Charsets.UTF_8))
                chat.write(0)
            }

            val out = ByteArrayOutputStream()
            val scope = this
            val sink = TokenSink { piece ->
                out.write(piece, 0, piece.size)
                // A multi-byte character can arrive in pieces; hide a half-written one.
                onPartial(String(out.toByteArray(), Charsets.UTF_8).trimEnd('\uFFFD'))
                scope.isActive
            }

            val error = try {
                nativeChat(handle, chat.toByteArray(), sink)
            } catch (e: Exception) {
                return@withContext "On-device error: ${e.message}"
            }
            if (error != null) return@withContext error

            String(out.toByteArray(), Charsets.UTF_8).trim()
                .ifBlank { "(The model returned an empty reply.)" }
        }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeChat(handle: Long, chat: ByteArray, sink: TokenSink): String?
    private external fun nativeClose(handle: Long)
}
