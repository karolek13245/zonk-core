package com.example.aiapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import kotlin.random.Random

// Finding a shared relay and staying connected to one that works. No Android classes in here, so it can be tested
// on a plain computer.

/** The exact sentence shown when no relay can be found or reached. */
const val RELAYS_NONE = "Couldnt find the Relays try next time"

/** The relay list the app asks unless the person changes it in Settings. */
const val DEFAULT_RELAY_LIST = "https://zonk-relays.karol-zeczek2914.workers.dev"

private val HOST_NAME = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
private val LISTED_NAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.workers\\.dev$")

/**
 * Turns what a person typed or pasted into the plain address of a relay or a list: `https://name.account.workers.dev`.
 * Adds https:// when it is missing and drops any path. Returns null when it can't be an address
 * (not https, a login in the address, a port, an IP number, a name without a dot).
 */
fun cleanRelayAddress(input: String): String? {
    var text = input.filter { !it.isWhitespace() }
    if (text.isEmpty() || text.length > 200) return null
    if (!text.contains("://")) text = "https://$text"
    val uri = try { URI(text) } catch (e: Exception) { return null }
    if (!"https".equals(uri.scheme, ignoreCase = true) || uri.userInfo != null) return null
    if (uri.port != -1 && uri.port != 443) return null
    val host = uri.host?.lowercase() ?: return null
    if (!HOST_NAME.matches(host) || host.all { it.isDigit() || it == '.' }) return null
    return "https://$host"
}

/** [url] as the address of a relay that may appear in the relay list (only name.account.workers.dev), or null. */
fun listedRelayAddress(url: String): String? = cleanRelayAddress(url)?.takeIf { LISTED_NAME.matches(it.removePrefix("https://")) }

/** A short name for showing: `zonk-relay.account` for `https://zonk-relay.account.workers.dev`. */
fun relayLabel(url: String): String = url.removePrefix("https://").removeSuffix(".workers.dev")

/** The address a phone connects to when it shares its model. */
fun hostSocketUrl(relay: String): String = relay.trimEnd('/') + "/host"

/** What a relay says about itself at /health. */
data class Health(val isPublic: Boolean, val ready: Boolean, val hosts: Int, val models: List<String>)

/** Reads the answer of a relay's /health, or null when it isn't a Zonk relay's answer. */
fun parseHealth(text: String): Health? {
    val json = try { JSONObject(text) } catch (e: Exception) { return null }
    if (json.optString("service", "") != "zonk-relay") return null
    val list = json.optJSONArray("models")
    val models = ArrayList<String>()
    if (list != null) for (i in 0 until list.length()) {
        val name = list.optString(i, "").trim()
        if (name.isNotEmpty() && models.size < 5) models.add(name.take(60))
    }
    return Health(
        isPublic = json.optBoolean("public", false),
        ready = json.optBoolean("ready", true),
        hosts = json.optInt("hosts", 0).coerceIn(0, 99),
        models = models,
    )
}

/** One entry of the relay list: the relay's address, and how many phones the list saw ready when it last looked. */
data class ListedRelay(val url: String, val hosts: Int)

/**
 * The relays in an answer of the relay list (GET /relays). Offline ones and anything that isn't a
 * name.account.workers.dev address are dropped, and an address appears once.
 */
fun parseRelayList(text: String): List<ListedRelay> {
    val array = try { JSONObject(text).optJSONArray("relays") } catch (e: Exception) { null } ?: return emptyList()
    val out = LinkedHashMap<String, ListedRelay>()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        if (item.has("online") && !item.optBoolean("online", true)) continue
        val url = listedRelayAddress(item.optString("url", "")) ?: continue
        if (url !in out) out[url] = ListedRelay(url, item.optInt("hosts", 0).coerceIn(0, 99))
        if (out.size >= 50) break
    }
    return out.values.toList()
}

/**
 * Chooses the relay to use, and remembers which ones stopped working.
 *  - [loadList] gives the relays of the relay list, [probe] asks one relay how it is (null = no answer).
 *  - At most [maxProbes] relays are asked at once, those the list saw with a phone ready first (in random order, so
 *    the load spreads). Of the ones that have a phone, the one with most phones is chosen, then the quickest to answer.
 *  - Once chosen, a relay stays chosen until [fail] says it stopped working. It then rests for [cooldownMs].
 * One chat at a time uses it; the functions are not meant to be called from several places at once.
 */
class RelayPicker(
    private val loadList: suspend () -> List<ListedRelay>,
    private val probe: suspend (String) -> Health?,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: Random = Random.Default,
    private val listLifetimeMs: Long = 60_000L,
    private val emptyListLifetimeMs: Long = 10_000L,
    private val cooldownMs: Long = 2 * 60_000L,
    private val probeCooldownMs: Long = 10_000L,
    private val maxProbes: Int = 6,
) {
    private var list: List<ListedRelay> = emptyList()
    private var listAt = NEVER // when the list was last asked for
    private var current: String? = null
    private val resting = HashMap<String, Long>() // address -> when it may be tried again

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }

    private fun isResting(url: String): Boolean {
        val until = resting[url] ?: return false
        if (clock() >= until) {
            resting.remove(url)
            return false
        }
        return true
    }

    private suspend fun currentList(): List<ListedRelay> {
        val now = clock()
        val lifetime = if (list.isEmpty()) emptyListLifetimeMs else listLifetimeMs
        if (listAt == NEVER || now - listAt >= lifetime || now < listAt) {
            try {
                list = loadList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the old list: a hiccup of the list is no reason to forget the relays we know.
            }
            listAt = now // also after a failure, so a list that is down is not asked again at once
        }
        return list
    }

    /** The relay to use now, or null when none is available. */
    suspend fun pick(): String? {
        current?.let { if (!isResting(it)) return it }
        current = null

        val open = currentList().filter { !isResting(it.url) }
        val candidates = (open.filter { it.hosts > 0 }.shuffled(random) + open.filter { it.hosts <= 0 }.shuffled(random))
            .take(maxProbes)
            .map { it.url }
        if (candidates.isEmpty()) return null

        val answers = coroutineScope {
            candidates.map { url ->
                async {
                    val started = clock()
                    val health = try { probe(url) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                    Triple(url, health, clock() - started)
                }
            }.map { it.await() }
        }

        val usable = answers.filter { (_, h, _) -> h != null && h.isPublic && h.ready && h.hosts > 0 }
        for ((url, _, _) in answers) {
            if (usable.none { it.first == url }) resting[url] = clock() + probeCooldownMs
        }
        val best = usable.sortedWith(
            compareByDescending<Triple<String, Health?, Long>> { it.second?.hosts ?: 0 }.thenBy { it.third }
        ).firstOrNull() ?: return null
        current = best.first
        return best.first
    }

    /** [url] answered a chat: keep using it. */
    fun succeed(url: String) {
        resting.remove(url)
        current = url
    }

    /** [url] stopped working: leave it alone for a while and choose another next time. */
    fun fail(url: String) {
        resting[url] = clock() + cooldownMs
        if (current == url) current = null
    }

    /** The relay in use right now, if one has been chosen. */
    fun currentRelay(): String? = current

    /** Forget everything (used when the list address changes). */
    fun reset() {
        list = emptyList()
        listAt = NEVER
        current = null
        resting.clear()
    }
}

/** True when a failed try says something about the relay (down, busy, no phone, not a relay), not about our request. */
fun relayIsToBlame(e: Throwable): Boolean = when (e) {
    is HttpFailure -> e.code !in setOf(400, 413, 422)
    is IOException -> true
    is UserFacingError -> true // "the server answered, but not the way an AI server does"
    else -> false
}

/**
 * Sends one chat through a shared relay and, when that relay stops working, through another one.
 * [ask] does the real call for the relay it is given and returns the reply with the tokens used.
 * Ends with [RELAYS_NONE] when no relay can be found or all of them fail.
 */
suspend fun chatThroughRelays(
    picker: RelayPicker,
    attempts: Int = 3,
    stopped: () -> Boolean = { false },
    onStatus: (String) -> Unit = {},
    ask: suspend (relay: String) -> Pair<String, Int>,
): Pair<String, Int> {
    repeat(attempts) {
        if (stopped()) return "" to 0
        onStatus("Looking for a relay…")
        val relay = picker.pick()
        if (stopped()) return "" to 0 // Stop was pressed while we were looking: don't send the chat anywhere
        if (relay == null) throw UserFacingError(RELAYS_NONE)
        onStatus("Asking ${relayLabel(relay)}…")
        try {
            val answer = ask(relay)
            picker.succeed(relay)
            return answer
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (stopped()) return "" to 0
            if (!relayIsToBlame(e)) throw e
            picker.fail(relay)
            onStatus("That relay isn't answering. Trying another…")
        }
    }
    throw UserFacingError(RELAYS_NONE)
}
