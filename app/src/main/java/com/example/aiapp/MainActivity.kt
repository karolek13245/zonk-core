package com.example.aiapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val HISTORY_LIMIT = 40

/** The app looks for a new version when it opens, but at most this often (6 hours). */
private const val UPDATE_CHECK_EVERY_MS = 6L * 60L * 60L * 1000L

private val TABS = listOf(
    "Host" to Icons.Filled.FlashOn,
    "Chat" to Icons.Filled.ChatBubble,
    "Settings" to Icons.Filled.Settings,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        setContent {
            var themeId by remember { mutableStateOf(prefs.getString("theme", "classic") ?: "classic") }
            AIAppTheme(themeId) {
                // The page behind everything: flat for Classic, a soft gradient for the other themes.
                Box(Modifier.fillMaxSize().pageBackground()) {
                    Surface(
                        Modifier.fillMaxSize(),
                        color = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onBackground,
                    ) {
                        var onboarded by remember { mutableStateOf(prefs.getBoolean("onboarded", false)) }
                        if (onboarded) {
                            MainScreen(prefs, themeId) {
                                themeId = it
                                prefs.edit().putString("theme", it).apply()
                            }
                        } else OnboardingScreen {
                            prefs.edit().putBoolean("onboarded", true).apply()
                            onboarded = true
                        }
                    }
                }
            }
        }
    }
}

/** One reply being written. */
private class ChatRun(val backend: ModelBackend, val startedAt: Long) {
    /** True once the screen has been tidied up for this reply (it finished, or the person pressed Stop). */
    @Volatile
    var done = false
}

@Composable
fun MainScreen(prefs: SharedPreferences, themeId: String, onTheme: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val stats = remember { HostStats(prefs) }
    val downloader = remember { ModelDownloader(app, prefs) }

    var tab by rememberSaveable { mutableStateOf(0) }
    var sharing by rememberSaveable { mutableStateOf(false) }

    // Chats are saved to a file (ChatStore), not to Android's saved-state Bundle:
    // a long chat in the Bundle can crash the app when it goes to the background.
    val store = remember { ChatStore(app) }
    val chats = remember { mutableStateListOf<Conversation>().also { it.addAll(store.load()) } }
    var currentId by remember { mutableStateOf(prefs.getString("currentChat", "") ?: "") }
    val messages = remember {
        mutableStateListOf<Msg>().also { list ->
            chats.firstOrNull { it.id == currentId }?.let { list.addAll(it.messages) }
        }
    }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var chatTokens by remember { mutableStateOf(chats.firstOrNull { it.id == currentId }?.tokens ?: 0) }
    var showTokens by remember { mutableStateOf(prefs.getBoolean("showTokens", true)) }
    var busy by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var thinkingText by remember { mutableStateOf("") }
    var currentRun by remember { mutableStateOf<ChatRun?>(null) }

    // What is being typed, and the picture waiting to be sent. Kept here (not inside the chat
    // screen) so switching tabs or opening the chat list doesn't throw them away.
    var draft by remember { mutableStateOf("") }
    var draftImage by remember { mutableStateOf<String?>(null) }

    var url by remember { mutableStateOf(prefs.getString("url", "") ?: "") }
    // The API key is kept encrypted (SecureStore). A plain-text key saved by an older version is locked up on first read.
    val secure = remember { SecureStore(prefs) }
    var key by remember { mutableStateOf(secure.loadApiKey()) }
    var savedKey by remember { mutableStateOf(key) }
    var keySecure by remember { mutableStateOf(secure.storageOk()) }
    var model by remember { mutableStateOf(prefs.getString("model", "") ?: "") }
    var onDevice by remember { mutableStateOf(prefs.getBoolean("onDevice", false)) }
    var sizeKey by remember { mutableStateOf(prefs.getString("size", "1.5B") ?: "1.5B") }

    // Seasonal effects (bats at Halloween, sparkles at Christmas). A preview from Settings overrides the date for a few seconds.
    var seasonFx by remember { mutableStateOf(prefs.getBoolean("seasonFx", true)) }
    var previewSeason by remember { mutableStateOf(Season.NONE) }
    val autoSeason = remember { currentSeason() }
    val season = when {
        previewSeason != Season.NONE -> previewSeason
        seasonFx -> autoSeason
        else -> Season.NONE
    }

    // Update check: a pop-up with the change log when a newer version is out.
    var checkUpdates by remember { mutableStateOf(prefs.getBoolean("checkUpdates", true)) }
    var updateUi by remember { mutableStateOf<UpdateUi?>(null) }
    var checking by remember { mutableStateOf(false) }
    val installed = remember { installedVersion(app) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) {
        while (true) {
            stats.tick()
            downloader.refresh()
            delay(1000)
        }
    }

    // Once at start: remove stored pictures that no chat uses any more.
    LaunchedEffect(Unit) {
        val keep = HashSet<String>()
        chats.forEach { c -> c.messages.forEach { m -> m.image?.let { keep.add(File(it).name) } } }
        withContext(Dispatchers.IO) { cleanUpImages(app, keep) }
    }

    // Locks the API key away a moment after typing stops (so not every key press calls the Keystore).
    LaunchedEffect(key) {
        if (key != savedKey) {
            delay(500)
            withContext(Dispatchers.IO) { secure.saveApiKey(key) }   // the Keystore can be slow: not on the screen's thread
            savedKey = key
            keySecure = secure.storageOk()
        }
    }

    // A season preview from Settings lasts a few seconds.
    LaunchedEffect(previewSeason) {
        if (previewSeason != Season.NONE) {
            delay(8000)
            previewSeason = Season.NONE
        }
    }

    // When the app opens (at most every 6 hours): is there a newer version on GitHub?
    LaunchedEffect(checkUpdates) {
        if (!checkUpdates) return@LaunchedEffect
        val now = System.currentTimeMillis()
        val last = prefs.getLong("updCheckedAt", 0L)
        if ((now - last) in 0L until UPDATE_CHECK_EVERY_MS) return@LaunchedEffect
        val releases = withContext(Dispatchers.IO) { fetchReleases() } ?: return@LaunchedEffect
        prefs.edit().putLong("updCheckedAt", now).apply()
        val newer = releases.filter { isNewerVersion(it.tag, installed) }
        if (newer.isNotEmpty() && prefs.getString("updSkip", "") != newer.first().tag) {
            updateUi = UpdateUi.Available(newer)
        }
    }

    fun setCurrent(id: String) {
        currentId = id
        prefs.edit().putString("currentChat", id).apply()
    }

    /** Saves the open chat (creating its history entry the first time). */
    fun persist() {
        if (messages.isEmpty()) return
        var id = currentId
        if (id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString()
            setCurrent(id)
        }
        val at = chats.indexOfFirst { it.id == id }
        val conv = Conversation(
            id = id,
            title = if (at >= 0) chats[at].title else chatTitle(messages),
            archived = if (at >= 0) chats[at].archived else false,
            updated = System.currentTimeMillis(),
            messages = messages.toList(),
            tokens = chatTokens,
        )
        if (at >= 0) chats[at] = conv else chats.add(0, conv)
        store.save(chats.toList())
    }

    fun newChat() {
        if (busy) return
        persist()
        messages.clear()
        partial = ""
        chatTokens = 0
        setCurrent("")
        showHistory = false
    }

    fun openChat(id: String) {
        if (busy) return
        val c = chats.firstOrNull { it.id == id } ?: return
        persist()
        messages.clear()
        messages.addAll(c.messages)
        partial = ""
        chatTokens = c.tokens
        setCurrent(id)
        showHistory = false
    }

    fun deleteChat(id: String) {
        if (busy && id == currentId) return
        // Delete the pictures that belonged to this chat.
        chats.firstOrNull { it.id == id }?.messages?.forEach { m ->
            m.image?.let { deleteImage(app, it) }
        }
        chats.removeAll { it.id == id }
        store.save(chats.toList())
        if (id == currentId) {
            messages.clear()
            chatTokens = 0
            setCurrent("")
        }
    }

    fun archiveChat(id: String, archived: Boolean) {
        val at = chats.indexOfFirst { it.id == id }
        if (at < 0) return
        chats[at] = chats[at].copy(archived = archived)
        store.save(chats.toList())
    }

    // The chat list is a screen inside the Chat tab: Back closes it (only while that tab is showing).
    BackHandler(enabled = showHistory && tab == 1) { showHistory = false }

    /** Tidies the screen up after a reply ended. Safe to call twice. */
    fun endRun(run: ChatRun) {
        if (run.done) return
        run.done = true
        stats.finish(run.startedAt)
        if (currentRun === run) currentRun = null
        partial = ""
        busy = false
    }

    fun send(text: String, image: String? = null) {
        if (busy) return
        messages.add(Msg("user", if (text.isBlank() && image != null) PICTURE_PROMPT else text, image))
        persist()
        busy = true
        partial = ""
        // Cap history so long chats don't blow past the model's token limit.
        // Error notices are for the person, not the model; and a chat must start with the user.
        val history = messages.filter { it.role != "error" }.takeLast(HISTORY_LIMIT)
            .dropWhile { it.role != "user" }
        val seesPicture = onDevice && catalogFor(sizeKey).canSeePictures && history.any { it.image != null }
        thinkingText = when {
            seesPicture -> "Looking at the picture…"
            onDevice -> "Reading your message…"
            else -> "Sending to the server…"
        }
        val backend: ModelBackend =
            if (onDevice) LlamaBackend(app, sizeKey) else CloudBackend(url, key, model)
        val run = ChatRun(backend, stats.start())
        currentRun = run
        scope.launch {
            try {
                var failed = false
                var reply = try {
                    backend.chat(history) { piece ->
                        if (!run.done) {
                            if (piece.isNotEmpty() && piece[0] == STATUS_MARK) {
                                // A status line ("Loading model…"), not part of the answer.
                                thinkingText = piece.substring(1)
                            } else {
                                partial = piece
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: UserFacingError) {
                    failed = true
                    e.message ?: "Something went wrong."
                } catch (e: Exception) {
                    failed = true
                    // The API key must never show up in an error message (it would be saved in the chat history).
                    "Error: ${redactSecret(e.message ?: "something went wrong", key)}"
                }
                if (!failed && reply.isBlank()) {
                    failed = true
                    reply = "The model sent an empty reply. Try asking again."
                }
                // If the person pressed Stop, what was written so far is already saved.
                if (!run.done) {
                    messages.add(Msg(if (failed) "error" else "assistant", reply))
                    val used = backend.usedTokens()
                    if (used > 0) chatTokens = used
                    persist()
                }
            } finally {
                endRun(run)
            }
        }
    }

    /** Stop button: keep what the AI has written so far and free the chat for the next message. */
    fun stopReply() {
        val run = currentRun ?: return
        run.backend.stop()
        val text = partial.trim()
        if (text.isNotEmpty() && !run.done) {
            messages.add(Msg("assistant", text))
            persist()
        }
        endRun(run)
    }

    /** "Check now" in Settings: always gives an answer, even when this already is the newest version. */
    fun checkNow() {
        if (checking) return
        checking = true
        scope.launch {
            val releases = withContext(Dispatchers.IO) { fetchReleases() }
            checking = false
            if (releases == null) {
                updateUi = UpdateUi.Failed
            } else {
                prefs.edit().putLong("updCheckedAt", System.currentTimeMillis()).apply()
                val newer = releases.filter { isNewerVersion(it.tag, installed) }
                updateUi = if (newer.isNotEmpty()) UpdateUi.Available(newer) else UpdateUi.UpToDate(releases.take(3))
            }
        }
    }

    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    // What the model button under the message box shows and offers.
    val modelMenu = ModelMenu(
        label = if (onDevice) shortModelName(catalogFor(sizeKey).name) else model.ifBlank { "Cloud server" },
        ready = if (onDevice) downloader.isReady(sizeKey) else url.isNotBlank(),
        onDevice = onDevice,
        sizeKey = sizeKey,
        readyModels = CATALOG.filter { downloader.isReady(it.sizeKey) },
        cloudLabel = if (url.isNotBlank()) model.ifBlank { "Cloud server" } else null,
        onSelectModel = { chosen ->
            sizeKey = chosen
            onDevice = true
            prefs.edit().putString("size", chosen).putBoolean("onDevice", true).apply()
        },
        onSelectCloud = {
            onDevice = false
            prefs.edit().putBoolean("onDevice", false).apply()
        },
        onMoreModels = { tab = 2 },
    )

    Scaffold(
        containerColor = Color.Transparent,   // the page colour or gradient is painted behind everything (pageBackground)
        contentColor = MaterialTheme.colorScheme.onBackground,
        bottomBar = {
            // The tab bar makes room for the keyboard instead of floating above it.
            if (!imeVisible) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                    TABS.forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        // consumeWindowInsets: the screens below add the keyboard's height themselves (imePadding),
        // so the space the Scaffold already reserved must not be counted twice.
        Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            when (tab) {
                0 -> HostScreen(
                    stats = stats,
                    sharing = sharing,
                    onToggleSharing = { sharing = !sharing },
                    serverSet = url.isNotBlank(),
                    sizeKey = sizeKey,
                    onSizeChange = {
                        sizeKey = it
                        prefs.edit().putString("size", it).apply()
                    },
                    downloader = downloader,
                )
                1 -> if (showHistory) {
                    ChatHistoryScreen(
                        chats = chats,
                        currentId = currentId,
                        busy = busy,
                        onOpen = { openChat(it) },
                        onDelete = { deleteChat(it) },
                        onArchive = { id, a -> archiveChat(id, a) },
                        onNew = { newChat() },
                        onBack = { showHistory = false },
                    )
                } else {
                    ChatScreen(
                        messages = messages,
                        busy = busy,
                        partial = partial,
                        thinkingText = thinkingText,
                        aiName = if (onDevice) catalogFor(sizeKey).name else model.ifBlank { "The assistant" },
                        canSendPictures = onDevice && catalogFor(sizeKey).canSeePictures,
                        showTokens = showTokens,
                        tokens = chatTokens,
                        input = draft,
                        onInput = { draft = it },
                        pendingImage = draftImage,
                        onPendingImage = { draftImage = it },
                        modelMenu = modelMenu,
                        onSend = { text, image -> send(text, image) },
                        onStop = { stopReply() },
                        onNewChat = { newChat() },
                        onHistory = { showHistory = true },
                    )
                }
                else -> SettingsScreen(
                    url = url, onUrl = { url = it; prefs.edit().putString("url", it).apply() },
                    key = key, onKey = { key = cleanApiKey(it) },
                    model = model, onModel = { model = it; prefs.edit().putString("model", it).apply() },
                    onDevice = onDevice, onOnDevice = { onDevice = it; prefs.edit().putBoolean("onDevice", it).apply() },
                    sizeKey = sizeKey,
                    downloader = downloader,
                    showTokens = showTokens,
                    onShowTokens = { showTokens = it; prefs.edit().putBoolean("showTokens", it).apply() },
                    keySecure = keySecure,
                    themeId = themeId,
                    onTheme = onTheme,
                    seasonFx = seasonFx,
                    onSeasonFx = { seasonFx = it; prefs.edit().putBoolean("seasonFx", it).apply() },
                    onPreviewSeason = { previewSeason = it },
                    checkUpdates = checkUpdates,
                    onCheckUpdates = { checkUpdates = it; prefs.edit().putBoolean("checkUpdates", it).apply() },
                    version = installed,
                    checking = checking,
                    onCheckNow = { checkNow() },
                )
            }
            // Bats at Halloween, sparkles at Christmas: drawn on top, never takes touches.
            SeasonOverlay(season)
        }
    }

    updateUi?.let { ui ->
        UpdateDialog(
            state = ui,
            installed = installed,
            onGet = { link ->
                // Only ever open this project's own release pages.
                if (link.startsWith("$REPO_URL/")) runCatching { uriHandler.openUri(link) }
                updateUi = null
            },
            onSkip = { tag ->
                prefs.edit().putString("updSkip", tag).apply()
                updateUi = null
            },
            onClose = { updateUi = null },
        )
    }
}
