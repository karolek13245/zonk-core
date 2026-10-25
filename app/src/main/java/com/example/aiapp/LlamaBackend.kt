package com.example.aiapp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

        /** The engine can only write one reply at a time; a second one waits here for the first to end. */
        private val engineLock = Mutex()

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

    @Volatile
    private var stopRequested = false

    override fun usedTokens(): Int = used

    override fun stop() {
        stopRequested = true
    }

    override suspend fun chat(history: List<Msg>, onPartial: (String) -> Unit): String =
        withContext(Dispatchers.Default) {
            val scope = this
            engineLock.withLock {
                // The person may have pressed Stop while this reply was still waiting for its turn.
                if (stopRequested) "" else generate(history, onPartial) { scope.isActive && !stopRequested }
            }
        }

    /** Writes one reply. Blocking: runs on a background thread. Problems are thrown as [UserFacingError]. */
    private fun generate(history: List<Msg>, onPartial: (String) -> Unit, keepGoing: () -> Boolean): String {
        used = 0
        val file = modelFile(context, sizeKey)
        val projector = if (catalogFor(sizeKey).projectorUrl != null) projectorFile(context, sizeKey) else null
        if (!file.exists() || (projector != null && !projector.exists())) {
            throw UserFacingError(
                "This model isn't downloaded yet.\n\n" +
                    "Open Settings > Models and tap Download, or pick another model with the model button under the message box."
            )
        }
        if (!libLoaded) {
            try {
                System.loadLibrary("llama-jni")
                libLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                throw UserFacingError("The on-device engine isn't available on this phone (it needs a 64-bit ARM phone).")
            }
        }

        // (Re)load the model if it changed or was re-downloaded.
        val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}" +
            (if (projector != null) ":${projector.length()}:${projector.lastModified()}" else "")
        if (loadedKey != key) {
            onPartial("${STATUS_MARK}Loading model…")
            if (handle != 0L) {
                nativeClose(handle)
                handle = 0
                loadedKey = null
            }
            // Picture models need a bigger memory window: a picture takes a few hundred tokens.
            handle = nativeInit(file.absolutePath, if (projector != null) 4096 else 2048)
            if (handle == 0L) {
                throw UserFacingError(
                    "Couldn't load the model. The phone may not have enough free memory for it (close other apps and try again), " +
                        "or the file is damaged: delete it in Settings > Models and download it again."
                )
            }
            if (projector != null && !nativeLoadVision(handle, projector.absolutePath)) {
                nativeClose(handle)
                handle = 0
                throw UserFacingError("Couldn't load the picture reader. The file may be damaged: delete this model in Settings > Models and download it again.")
            }
            loadedKey = key
        }

        // role\0content\0role\0content\0 ...
        val canSee = projector != null
        val chat = ByteArrayOutputStream()
        for (m in history) {
            val text = if (canSee) m.content else m.textWithoutPicture()
            chat.write(m.role.toByteArray(Charsets.UTF_8))
            chat.write(0)
            chat.write(text.replace("\u0000", "").toByteArray(Charsets.UTF_8))
            chat.write(0)
        }

        // Only a picture model can look at pictures: use the newest picture in this chat.
        var imageBytes: ByteArray? = null
        var imageIndex = -1
        if (canSee) {
            imageIndex = history.indexOfLast { it.role == "user" && it.image != null }
            if (imageIndex >= 0) {
                val name = history[imageIndex].image ?: ""
                val f = File(imageDir(context), File(name).name)
                imageBytes = try { f.readBytes() } catch (e: Exception) { null }
                if (imageBytes == null) {
                    throw UserFacingError("The picture file for this message is missing, so the model can't look at it. Send the picture again.")
                }
            }
        }
        onPartial("${STATUS_MARK}${if (imageBytes != null) "Looking at the picture…" else "Reading your message…"}")

        val out = ByteArrayOutputStream()
        val sink = TokenSink { piece ->
            out.write(piece, 0, piece.size)
            // A multi-byte character can arrive in pieces; hide a half-written one.
            onPartial(String(out.toByteArray(), Charsets.UTF_8).trimEnd('�'))
            keepGoing()
        }

        val stats = LongArray(2)
        val error = try {
            nativeChat(handle, chat.toByteArray(), imageBytes, imageIndex, stats, sink)
        } catch (e: Exception) {
            throw UserFacingError("On-device error: ${e.message}")
        }
        used = (stats[0] + stats[1]).toInt()
        if (error != null) throw UserFacingError(error.removePrefix("Error: "))

        val text = String(out.toByteArray(), Charsets.UTF_8).trim()
        if (text.isBlank() && !stopRequested) throw UserFacingError("The model sent an empty reply. Try asking again.")
        return text
    }

    private external fun nativeInit(modelPath: String, contextSize: Int): Long
    private external fun nativeLoadVision(handle: Long, projectorPath: String): Boolean
    private external fun nativeChat(
        handle: Long, chat: ByteArray, image: ByteArray?, imageMessage: Int, stats: LongArray, sink: TokenSink,
    ): String?
    private external fun nativeClose(handle: Long)
}
