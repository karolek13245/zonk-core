package com.example.aiapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// The network side of shared relays: reading the relay list, asking a relay how it is, and sending a chat through one.
// Relays belong to other people, so nothing they send is trusted: answers are size-limited, redirects are never
// followed (a relay can't send the phone to another address), and no key or login is ever sent to them.

private const val MAX_LIST_BYTES = 256 * 1024
private const val MAX_HEALTH_BYTES = 16 * 1024
private const val MAX_ANSWER_BYTES = 1024 * 1024

/** GET [url] and read at most [maxBytes] of the answer as text. Returns null on any problem (no connection, not 200, too long). Blocking. */
internal fun httpGetText(url: String, maxBytes: Int, connectMs: Int = 6000, readMs: Int = 8000): String? {
    val conn = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) { return null }
    return try {
        conn.instanceFollowRedirects = false
        conn.requestMethod = "GET"
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "Zonk-Core/$APP_VERSION")
        if (conn.responseCode != 200) null else conn.inputStream.use { readLimited(it, maxBytes) }
    } catch (e: Exception) {
        null
    } finally {
        conn.disconnect()
    }
}

/** The relays of the list at [url] (the full address of its /relays). Throws an IOException when the list doesn't answer. */
internal suspend fun fetchRelayListAt(url: String): List<ListedRelay> = withContext(Dispatchers.IO) {
    val text = httpGetText(url, MAX_LIST_BYTES) ?: throw IOException("The relay list did not answer.")
    parseRelayList(text)
}

/** The relays of the relay list at [listAddress] (see [cleanRelayAddress]). Throws an IOException when it can't be read. */
suspend fun fetchRelayList(listAddress: String): List<ListedRelay> {
    val base = cleanRelayAddress(listAddress) ?: throw IOException("The relay list address isn't valid.")
    return fetchRelayListAt("$base/relays")
}

/**
 * The relays to try ([withKnownRelays]): those [fetch] gives and, when it fails (the relay list is down or unreadable) or
 * lacks them, the relay this phone shares on ([ownRelay], may be empty) and the [builtIn] ones.
 */
internal suspend fun loadRelaysWith(
    fetch: suspend () -> List<ListedRelay>,
    ownRelay: String,
    builtIn: List<String> = BUILT_IN_RELAYS,
): List<ListedRelay> {
    val listed = try {
        fetch()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList() // the list is down: the relays we already know are still worth a try
    }
    return withKnownRelays(listed, listOf(ownRelay) + builtIn)
}

/** The relays to try for the relay list at [listAddress]: its relays, then [ownRelay] and the built-in ones (see [loadRelaysWith]). */
suspend fun loadRelays(listAddress: String, ownRelay: String): List<ListedRelay> =
    loadRelaysWith({ fetchRelayList(listAddress) }, ownRelay)

/** What the relay at [url] (the full address of its /health) says about itself, or null when it doesn't answer like a Zonk relay. */
internal suspend fun probeRelayAt(url: String): Health? = withContext(Dispatchers.IO) {
    httpGetText(url, MAX_HEALTH_BYTES)?.let { parseHealth(it) }
}

suspend fun probeRelay(relay: String): Health? = probeRelayAt("$relay/health")

/**
 * One chat through the relay at [relay] (blocking). [onConnection] hands over the open connection, so that Stop can cut it.
 * An error answer becomes an [HttpFailure]; an answer that isn't an AI answer becomes an [IOException].
 */
internal fun postChatBlocking(relay: String, history: List<Msg>, onConnection: (HttpURLConnection?) -> Unit = {}): Pair<String, Int> {
    val body = chatRequestBody("zonk", history).toString().toByteArray(Charsets.UTF_8)
    val conn = URL("${relay.trimEnd('/')}/v1/chat/completions").openConnection() as HttpURLConnection
    onConnection(conn)
    try {
        conn.instanceFollowRedirects = false
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout = 130_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "Zonk-Core/$APP_VERSION")
        conn.outputStream.use { it.write(body) }

        val code = conn.responseCode
        val ok = code in 200..299
        val text = (if (ok) conn.inputStream else conn.errorStream)?.use { readLimited(it, MAX_ANSWER_BYTES) }
        if (!ok) throw HttpFailure(code, friendlyHttpError(code, text.orEmpty(), ""))
        if (text == null) throw IOException("The relay's answer was empty or too long.")
        return try {
            parseChatAnswer(text)
        } catch (e: UserFacingError) {
            // The advice "check the server address in Settings" is for your own server; a relay was chosen for you.
            throw IOException("The relay did not send an AI answer.")
        }
    } finally {
        onConnection(null)
        conn.disconnect()
    }
}

/** Chats through shared relays: finds a working one, and switches to another when it stops answering. */
class SharedRelayBackend(
    private val picker: RelayPicker,
    private val ask: (relay: String, history: List<Msg>, onConnection: (HttpURLConnection?) -> Unit) -> Pair<String, Int> = ::postChatBlocking,
) : ModelBackend {
    private var used = 0

    @Volatile
    private var stopRequested = false

    @Volatile
    private var active: HttpURLConnection? = null

    override fun usedTokens(): Int = used

    override fun stop() {
        stopRequested = true
        try {
            active?.disconnect()
        } catch (ignored: Exception) {
            // already closed
        }
    }

    override suspend fun chat(history: List<Msg>, onPartial: (String) -> Unit): String {
        val answer = chatThroughRelays(
            picker,
            stopped = { stopRequested },
            onStatus = { onPartial("$STATUS_MARK$it") },
        ) { relay ->
            withContext(Dispatchers.IO) { ask(relay, history) { active = it } }
        }
        used = answer.second
        return answer.first
    }
}
