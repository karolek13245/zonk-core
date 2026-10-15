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

        /** The picture reader that goes with a vision model, e.g. models/mmproj-vision.gguf */
        fun projectorFile(context: Context, sizeKey: String): File =
            File(modelDir(context), "mmproj-${sizeKey.lowercase()}.gguf")

        /** Pictures attached to chat messages are kept here (small JPEGs). */
        fun imageDir(context: Context): File = File(context.filesDir, "images")
    }

    private var used = 0

    override fun usedTokens(): Int = used

    override suspend fun chat(history: List<Msg>, onPartial: (String) -> Unit): String =
        withContext(Dispatchers.Default) {
            used = 0
            val file = modelFile(context, sizeKey)
            val projector = if (catalogFor(sizeKey).projectorUrl != null) projectorFile(context, sizeKey) else null
            if (!file.exists() || (projector != null && !projector.exists())) {
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
            val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}" +
                (if (projector != null) ":${projector.length()}:${projector.lastModified()}" else "")
            if (loadedKey != key) {
                onPartial("Loading model…")
                if (handle != 0L) {
                    nativeClose(handle)
                    handle = 0
                    loadedKey = null
                }
                // Picture models need a bigger memory window: a picture takes a few hundred tokens.
                handle = nativeInit(file.absolutePath, if (projector != null) 4096 else 2048)
                if (handle == 0L) {
                    return@withContext "Couldn't load the model. The file may be damaged: delete it in Settings > Models and download it again."
                }
                if (projector != null && !nativeLoadVision(handle, projector.absolutePath)) {
                    nativeClose(handle)
                    handle = 0
                    return@withContext "Couldn't load the picture reader. The file may be damaged: delete this model in Settings > Models and download it again."
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

            // Only a picture model can look at pictures: use the newest picture in this chat.
            var imageBytes: ByteArray? = null
            var imageIndex = -1
            if (projector != null) {
                imageIndex = history.indexOfLast { it.role == "user" && it.image != null }
                if (imageIndex >= 0) {
                    val f = File(imageDir(context), File(history[imageIndex].image!!).name)
                    imageBytes = try { f.readBytes() } catch (e: Exception) { null }
                    if (imageBytes == null) {
                        return@withContext "The picture file for this message is missing, so the model can't look at it. Send the picture again."
                    }
                }
            }

            val out = ByteArrayOutputStream()
            val scope = this
            val sink = TokenSink { piece ->
                out.write(piece, 0, piece.size)
                // A multi-byte character can arrive in pieces; hide a half-written one.
                onPartial(String(out.toByteArray(), Charsets.UTF_8).trimEnd('\uFFFD'))
                scope.isActive
            }

            val stats = LongArray(2)
            val error = try {
                nativeChat(handle, chat.toByteArray(), imageBytes, imageIndex, stats, sink)
            } catch (e: Exception) {
                return@withContext "On-device error: ${e.message}"
            }
            used = (stats[0] + stats[1]).toInt()
            if (error != null) return@withContext error

            String(out.toByteArray(), Charsets.UTF_8).trim()
                .ifBlank { "(The model returned an empty reply.)" }
        }

    private external fun nativeInit(modelPath: String, contextSize: Int): Long
    private external fun nativeLoadVision(handle: Long, projectorPath: String): Boolean
    private external fun nativeChat(
        handle: Long, chat: ByteArray, image: ByteArray?, imageMessage: Int, stats: LongArray, sink: TokenSink,
    ): String?
    private external fun nativeClose(handle: Long)
}
