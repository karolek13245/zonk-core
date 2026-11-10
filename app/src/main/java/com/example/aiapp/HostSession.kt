package com.example.aiapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// Sharing this phone's model through a relay. The phone dials out to the relay, says which model it has, and then
// answers the chat requests the relay passes on. No Android classes in here, so it can be tested on a plain computer:
// the real connection (OkHttp) is plugged in through [HostSocketFactory].

/** A message from the relay to the phone. */
sealed interface RelayMessage {
    data class Request(val id: String, val history: List<Msg>) : RelayMessage
    data class Cancel(val id: String) : RelayMessage
}

private val ROLES = setOf("system", "user", "assistant")

/** Reads one message of the relay, or null when it is not one we understand (the phone just ignores those). */
fun parseRelayMessage(text: String): RelayMessage? {
    val json = try { JSONObject(text) } catch (e: Exception) { return null }
    val id = json.optString("id", "")
    if (id.isEmpty() || id.length > 100) return null
    when (json.optString("type", "")) {
        "cancel" -> return RelayMessage.Cancel(id)
        "request" -> {
            val array = json.optJSONArray("messages") ?: return null
            if (array.length() == 0 || array.length() > 200) return null
            val history = ArrayList<Msg>()
            for (i in 0 until array.length()) {
                val m = array.optJSONObject(i) ?: return null
                val role = m.optString("role", "")
                if (role !in ROLES) return null
                history.add(Msg(role, m.optString("content", "")))
            }
            return RelayMessage.Request(id, history)
        }
    }
    return null
}

/** The first thing a phone says: which model it shares. */
fun helloMessage(model: String, name: String): String =
    JSONObject().put("type", "hello").put("model", model.take(80)).put("name", name.take(40)).toString()

fun responseMessage(id: String, content: String, promptTokens: Int, completionTokens: Int): String =
    JSONObject().put("type", "response").put("id", id).put("content", content)
        .put("promptTokens", promptTokens).put("completionTokens", completionTokens).toString()

fun errorMessage(id: String, message: String): String =
    JSONObject().put("type", "error").put("id", id).put("message", message.take(300)).toString()

/** How long to wait before connecting again after the [attempt]th failure in a row (0 = the first one): about 2, 4, 8, 16, 32, then 60 seconds. */
fun reconnectDelayMs(attempt: Int, random: Double = Math.random()): Long {
    val base = minOf(60_000L, 2_000L shl minOf(attempt.coerceAtLeast(0), 5))
    return (base * (0.8 + 0.4 * random.coerceIn(0.0, 1.0))).toLong()
}

/** Why this phone can't start sharing yet, in words for the Host screen, or null when it can. */
fun hostSetupProblem(relayInput: String, token: String, modelReady: Boolean): String? = when {
    relayInput.isBlank() -> "Add your relay address in Settings > Share this phone."
    cleanRelayAddress(relayInput) == null -> "That relay address doesn't look right. Check it in Settings > Share this phone."
    token.isBlank() -> "Add the host token in Settings > Share this phone."
    !modelReady -> "Download this model first (Settings > Models)."
    else -> null
}

/**
 * The chat the model is given when a relay passes one on: the newest [limit] messages, starting with a user message
 * like the app's own chats do. A system message at the very start is kept.
 */
fun capSharedHistory(history: List<Msg>, limit: Int = 40): List<Msg> {
    val system = history.firstOrNull()?.takeIf { it.role == "system" }
    val rest = (if (system != null) history.drop(1) else history).takeLast(limit).dropWhile { it.role != "user" }
    return listOfNotNull(system) + rest
}

/** What the Host screen shows. */
sealed interface HostState {
    object Off : HostState
    data class Connecting(val relay: String) : HostState
    data class Online(val relay: String) : HostState
    data class Retrying(val relay: String, val inSeconds: Int, val why: String) : HostState
    /** Stopped for good: something has to be fixed first. [why] says what. */
    data class Failed(val why: String) : HostState
}

/** One line for the Host screen. */
fun hostStateText(state: HostState): String = when (state) {
    is HostState.Off -> "Not sharing"
    is HostState.Connecting -> "Connecting to ${relayLabel(state.relay)}…"
    is HostState.Online -> "Sharing through ${relayLabel(state.relay)}"
    is HostState.Retrying -> "${state.why} Trying again in ${state.inSeconds} s."
    is HostState.Failed -> state.why
}

/** The words that were written for one request, and how many tokens it took. */
data class HostAnswer(val content: String, val promptTokens: Int, val completionTokens: Int)

/** One open connection to a relay. */
interface HostSocket {
    /** False when the connection is already closed. */
    fun send(text: String): Boolean
    fun close(code: Int, reason: String)
}

/** What happens on the connection. Calls may come from any thread. */
interface HostSocketListener {
    fun onOpen(socket: HostSocket)
    fun onMessage(text: String)
    fun onClosed(code: Int, reason: String)
    /** The connection could not be opened or broke. [httpCode] is the status of a refused handshake, if there was one. */
    fun onFailure(httpCode: Int?, error: Throwable)
}

fun interface HostSocketFactory {
    /** Starts connecting to [url] (the token goes in the Authorization header) and returns at once. */
    fun open(url: String, token: String, listener: HostSocketListener): HostSocket
}

/**
 * Keeps a phone connected to one relay and answers its requests, until [stop] is called or something has to be fixed.
 * A dropped connection is retried with growing pauses. [answer] writes the reply for a chat: it throws a
 * [UserFacingError] for things worth telling the relay, and is cancelled when the relay says the request is no longer wanted.
 */
class HostSession(
    private val scope: CoroutineScope,
    private val factory: HostSocketFactory,
    private val relay: String,
    private val token: String,
    private val modelName: String,
    private val deviceName: String,
    private val answer: suspend (List<Msg>) -> HostAnswer,
    private val onState: (HostState) -> Unit,
    /** Called with true when a request starts and false when it ends (for the statistics). */
    private val onRequest: (started: Boolean) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val random: () -> Double = { Math.random() },
) {
    private companion object {
        const val PING_EVERY_MS = 30_000L
        const val DEAD_AFTER_MS = 75_000L
        /** A connection that lasted this long counts as a good one: the pauses start from the beginning again. */
        const val STABLE_MS = 20_000L
        /** The relay sends one request at a time; more than this at once means something is wrong. */
        const val MAX_BUSY = 3
    }

    /** Why a connection ended. [permanent] is set when trying again can't help until the person fixes something. */
    private class Ended(val why: String, val permanent: String? = null)

    /** Everything about one connection, so a late call from an old one can't disturb a newer one. */
    private class Connection {
        val done = CompletableDeferred<Ended>()
        val lastHeard = AtomicLong(0)
        val live = AtomicReference<HostSocket?>(null)
        val pings = AtomicReference<Job?>(null)
        val finished = AtomicBoolean(false)
    }

    @Volatile
    private var main: Job? = null

    @Volatile
    private var socket: HostSocket? = null
    private val jobs = ConcurrentHashMap<String, Job>()

    fun start() {
        if (main?.isActive == true) return
        main = scope.launch { run() }
    }

    fun stop() {
        main?.cancel()
        main = null
        try { socket?.close(1000, "stopped") } catch (e: Exception) { /* already closed */ }
        socket = null
        cancelJobs()
        onState(HostState.Off)
    }

    private fun cancelJobs() {
        for (job in jobs.values) job.cancel()
        jobs.clear()
    }

    private fun safeSend(sock: HostSocket, text: String): Boolean =
        try { sock.send(text) } catch (e: Exception) { false }

    private suspend fun run() {
        var failures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            onState(HostState.Connecting(relay))
            val startedAt = clock()
            val ended = connectOnce()
            cancelJobs()
            if (ended.permanent != null) {
                onState(HostState.Failed(ended.permanent))
                return
            }
            if (clock() - startedAt >= STABLE_MS) failures = 0
            val wait = reconnectDelayMs(failures, random())
            failures++
            onState(HostState.Retrying(relay, ((wait + 999) / 1000).toInt(), ended.why))
            sleep(wait)
        }
    }

    private fun refusal(httpCode: Int?): Ended = when (httpCode) {
        401 -> Ended(
            "The relay didn't accept the host token.",
            permanent = "The relay didn't accept the host token. Check it in Settings > Share this phone.",
        )
        403, 404, 405 -> Ended(
            "That address isn't a Zonk relay.",
            permanent = "That address doesn't look like a Zonk relay. Check it in Settings > Share this phone.",
        )
        null -> Ended("Can't reach the relay. Check the internet.")
        else -> Ended("The relay answered with HTTP $httpCode.")
    }

    /** Connects once and waits until that connection ends. */
    private suspend fun connectOnce(): Ended {
        val c = Connection()
        c.lastHeard.set(clock())

        val listener = object : HostSocketListener {
            override fun onOpen(socket: HostSocket) {
                if (c.finished.get()) {
                    // We gave up on this connection while it was still opening.
                    try { socket.close(1000, "late") } catch (e: Exception) { /* already closed */ }
                    return
                }
                c.lastHeard.set(clock())
                if (!safeSend(socket, helloMessage(modelName, deviceName))) {
                    c.done.complete(Ended("The connection closed right away."))
                    return
                }
                c.live.set(socket)
                onState(HostState.Online(relay))
                c.pings.set(scope.launch { pingLoop(socket, c) })
            }

            override fun onMessage(text: String) {
                if (c.finished.get()) return
                c.lastHeard.set(clock())
                if (text == "pong") return
                val sock = c.live.get() ?: return
                when (val m = parseRelayMessage(text)) {
                    is RelayMessage.Request -> handle(sock, m)
                    is RelayMessage.Cancel -> jobs.remove(m.id)?.cancel()
                    null -> { /* not for us */ }
                }
            }

            override fun onClosed(code: Int, reason: String) {
                c.done.complete(Ended("The relay closed the connection."))
            }

            override fun onFailure(httpCode: Int?, error: Throwable) {
                c.done.complete(refusal(httpCode))
            }
        }

        val opening = try {
            factory.open(hostSocketUrl(relay), token, listener)
        } catch (e: Exception) {
            return Ended(
                "The relay address isn't valid.",
                permanent = "The relay address isn't valid. Check it in Settings > Share this phone.",
            )
        }
        socket = opening
        try {
            return c.done.await()
        } finally {
            c.finished.set(true)
            c.pings.get()?.cancel()
            try { opening.close(1000, "bye") } catch (e: Exception) { /* already closed */ }
            socket = null
        }
    }

    private suspend fun pingLoop(sock: HostSocket, c: Connection) {
        while (true) {
            sleep(PING_EVERY_MS)
            if (c.finished.get()) return
            if (clock() - c.lastHeard.get() > DEAD_AFTER_MS) {
                c.done.complete(Ended("The relay stopped answering."))
                return
            }
            if (!safeSend(sock, "ping")) {
                c.done.complete(Ended("The connection closed."))
                return
            }
        }
    }

    private fun handle(sock: HostSocket, request: RelayMessage.Request) {
        if (jobs.size >= MAX_BUSY) {
            safeSend(sock, errorMessage(request.id, "The phone is busy right now."))
            return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            onRequest(true)
            try {
                val a = answer(request.history)
                safeSend(sock, responseMessage(request.id, a.content, a.promptTokens, a.completionTokens))
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserFacingError) {
                safeSend(sock, errorMessage(request.id, e.message ?: "The phone could not answer."))
            } catch (e: OutOfMemoryError) {
                safeSend(sock, errorMessage(request.id, "The phone ran out of memory."))
            } catch (e: Exception) {
                safeSend(sock, errorMessage(request.id, "The phone could not write an answer."))
            } finally {
                jobs.remove(request.id, coroutineContext[Job])
                onRequest(false)
            }
        }
        if (jobs.putIfAbsent(request.id, job) != null) {
            job.cancel() // the same request again: the first one is still being answered
            return
        }
        job.start()
    }
}
