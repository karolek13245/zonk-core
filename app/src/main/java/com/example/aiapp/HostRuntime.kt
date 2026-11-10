package com.example.aiapp

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What sharing needs, read from the settings when it starts. */
class HostConfig(val relay: String, val token: String, val sizeKey: String, val deviceName: String)

/** Reads the settings for sharing. Returns null when something is missing (the Host screen says what). */
fun readHostConfig(prefs: SharedPreferences): HostConfig? {
    val relay = cleanRelayAddress(prefs.getString("hostRelay", "") ?: "") ?: return null
    val token = HostTokenStore(prefs).load()
    if (token.isEmpty()) return null
    return HostConfig(relay, token, prefs.getString("size", "1.5B") ?: "1.5B", "Phone")
}

/**
 * Sharing this phone's model through a relay, for as long as the sharing service ([ShareService]) runs.
 * It lives as long as the app's process; the Host screen watches [state].
 */
object HostRuntime {
    private val current = MutableStateFlow<HostState>(HostState.Off)
    val state: StateFlow<HostState> = current

    private var scope: CoroutineScope? = null
    private var session: HostSession? = null

    /** When each request that is being answered began (they are told to the statistics when they end). */
    private val running = ArrayDeque<Long>()

    @Synchronized
    fun start(context: Context, config: HostConfig, stats: HostStats) {
        if (session != null) return
        current.value = HostState.Off
        val app = context.applicationContext
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope
        val s = HostSession(
            scope = newScope,
            factory = OkHostSocketFactory(),
            relay = config.relay,
            token = config.token,
            modelName = shortModelName(catalogFor(config.sizeKey).name),
            deviceName = config.deviceName,
            answer = { history -> answerOnDevice(app, config.sizeKey, history) },
            onState = { next ->
                // "Failed" says what to fix: a later "Off" (the service closing down) must not wipe it out.
                if (!(next is HostState.Off && current.value is HostState.Failed)) current.value = next
            },
            onRequest = { started ->
                synchronized(running) {
                    if (started) running.addLast(stats.start()) else running.removeFirstOrNull()?.let { stats.finish(it) }
                }
            },
        )
        session = s
        s.start()
    }

    @Synchronized
    fun stop() {
        session?.stop()
        session = null
        scope?.cancel()
        scope = null
    }

    /** One chat from the relay, answered by the model on this phone. */
    private suspend fun answerOnDevice(context: Context, sizeKey: String, history: List<Msg>): HostAnswer {
        val chat = capSharedHistory(history)
        if (chat.none { it.role == "user" }) throw UserFacingError("There was no question in the chat.")
        val backend = LlamaBackend(context, sizeKey)
        val reply = try {
            backend.chat(chat)
        } catch (e: UserFacingError) {
            // Those texts are written for the phone's owner ("open Settings..."): the person at the relay gets the short first line.
            throw UserFacingError("The phone could not answer. " + (e.message ?: "").lineSequence().first().take(120))
        }
        return HostAnswer(reply, backend.lastPromptTokens(), backend.lastReplyTokens())
    }
}
