package com.example.aiapp

import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val HISTORY_LIMIT = 40

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
            AIAppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var onboarded by remember { mutableStateOf(prefs.getBoolean("onboarded", false)) }
                    if (onboarded) MainScreen(prefs) else OnboardingScreen {
                        prefs.edit().putBoolean("onboarded", true).apply()
                        onboarded = true
                    }
                }
            }
        }
    }
}

@Composable
fun MainScreen(prefs: SharedPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stats = remember { HostStats(prefs) }
    val downloader = remember { ModelDownloader(context.applicationContext, prefs) }

    var tab by rememberSaveable { mutableStateOf(0) }
    var sharing by rememberSaveable { mutableStateOf(false) }

    // Chats are saved to a file (ChatStore), not to Android's saved-state Bundle:
    // a long chat in the Bundle can crash the app when it goes to the background.
    val store = remember { ChatStore(context.applicationContext) }
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

    var url by remember { mutableStateOf(prefs.getString("url", "") ?: "") }
    var key by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "") ?: "") }
    var onDevice by remember { mutableStateOf(prefs.getBoolean("onDevice", false)) }
    var sizeKey by remember { mutableStateOf(prefs.getString("size", "1.5B") ?: "1.5B") }

    LaunchedEffect(Unit) {
        while (true) {
            stats.tick()
            downloader.refresh()
            delay(1000)
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
            m.image?.let { deleteImage(context.applicationContext, it) }
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

    BackHandler(enabled = showHistory) { showHistory = false }

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
        val startedAt = stats.start()
        scope.launch {
            try {
                val backend: ModelBackend =
                    if (onDevice) LlamaBackend(context.applicationContext, sizeKey) else CloudBackend(url, key, model)
                var failed = false
                val reply = try {
                    backend.chat(history) { piece ->
                        if (piece == "Loading model…") {
                            thinkingText = piece
                        } else {
                            // First real piece of the answer: the "thinking" phase is over.
                            if (partial.isEmpty()) thinkingText = "Writing a reply…"
                            partial = piece
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                    "Error: ${e.message}"
                }
                messages.add(Msg(if (failed) "error" else "assistant", reply))
                val used = backend.usedTokens()
                if (used > 0) chatTokens = used
                persist()
            } finally {
                stats.finish(startedAt)
                busy = false
            }
        }
    }

    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!(imeVisible && tab == 1)) {
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
        Box(Modifier.padding(padding).fillMaxSize()) {
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
                        modelLabel = if (onDevice) "On this phone · $sizeKey" else "Cloud server",
                        aiName = if (onDevice) catalogFor(sizeKey).name else model.ifBlank { "The assistant" },
                        canSendPictures = onDevice && catalogFor(sizeKey).canSeePictures,
                        showTokens = showTokens,
                        tokens = chatTokens,
                        onSend = { text, image -> send(text, image) },
                        onNewChat = { newChat() },
                        onHistory = { showHistory = true },
                    )
                }
                else -> SettingsScreen(
                    url = url, onUrl = { url = it; prefs.edit().putString("url", it).apply() },
                    key = key, onKey = { key = it; prefs.edit().putString("key", it).apply() },
                    model = model, onModel = { model = it; prefs.edit().putString("model", it).apply() },
                    onDevice = onDevice, onOnDevice = { onDevice = it; prefs.edit().putBoolean("onDevice", it).apply() },
                    sizeKey = sizeKey,
                    downloader = downloader,
                    showTokens = showTokens,
                    onShowTokens = { showTokens = it; prefs.edit().putBoolean("showTokens", it).apply() },
                )
            }
        }
    }
}

@Composable
private fun ThinkingCard(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { expanded = !expanded }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("● ", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
            Text(
                if (expanded) "Thinking (tap to collapse)" else text.ifBlank { "Thinking…" },
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(if (expanded) "∧" else "∨", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
        if (expanded && text.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), lineHeight = 18.sp)
        }
    }
}

/** A picture from a chat, shown small. */
@Composable
private fun PictureThumb(fileName: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bmp = remember(fileName) { loadThumbnail(context, fileName) }
    Box(
        modifier.size(size).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = "Attached picture",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text("Picture missing", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
    }
}

/** One plain text bubble. */
@Composable
private fun TextBubble(text: String, mine: Boolean, error: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(
                text,
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = when {
                    mine -> MaterialTheme.colorScheme.onPrimary
                    error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** iMessage-style bubbles: you on the right (blue), the AI on the left, code in black boxes. */
@Composable
private fun ChatBubble(msg: Msg) {
    when (msg.role) {
        "user" -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (msg.image != null) PictureThumb(msg.image, 160.dp)
            if (msg.image == null || msg.content != PICTURE_PROMPT) TextBubble(msg.content, mine = true)
        }
        "error" -> TextBubble(msg.content, mine = false, error = true)
        else -> AssistantContent(msg.content) { part -> TextBubble(part, mine = false) }
    }
}

private val SendBlue = Color(0xFF1B4FBF)

@Composable
fun ChatScreen(
    messages: List<Msg>,
    busy: Boolean,
    partial: String,
    thinkingText: String,
    modelLabel: String,
    aiName: String,
    canSendPictures: Boolean,
    showTokens: Boolean,
    tokens: Int,
    onSend: (String, String?) -> Unit,
    onNewChat: () -> Unit,
    onHistory: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var pendingImage by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val count = messages.size + if (busy) 1 else 0
    LaunchedEffect(count, partial.length) {
        if (count > 0) listState.scrollToItem(count - 1)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val name = withContext(Dispatchers.IO) { saveSelectedImage(context.applicationContext, uri) }
                if (name != null) {
                    pendingImage?.let { deleteImage(context.applicationContext, it) }
                    pendingImage = name
                } else {
                    android.widget.Toast.makeText(context, "Couldn't open that picture.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val canSend = !busy && (input.isNotBlank() || pendingImage != null)

    fun submit() {
        if (!canSend) return
        val text = input.trim()
        val image = pendingImage
        input = ""
        pendingImage = null
        onSend(text, image)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { LargeTitle("Chat") }
            IconButton(onClick = onHistory) { Icon(Icons.Filled.History, contentDescription = "Chat history") }
            IconButton(onClick = onNewChat, enabled = !busy && messages.isNotEmpty()) {
                Icon(Icons.Filled.Add, contentDescription = "New chat")
            }
        }
        Text(
            modelLabel,
            Modifier.padding(horizontal = 20.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages) { m -> ChatBubble(m) }
            if (busy) {
                item { ThinkingCard(thinkingText) }
                if (partial.isNotEmpty()) item { ChatBubble(Msg("assistant", partial)) }
            }
        }

        // Running token total for this chat (can be switched off in Settings).
        if (showTokens && messages.isNotEmpty()) {
            Text(
                if (tokens > 0) "Tokens in this chat: ${NumberFormat.getIntegerInstance().format(tokens)}"
                else "Tokens in this chat: counted after the next reply",
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }

        // The picture waiting to be sent.
        pendingImage?.let { name ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.Top) {
                PictureThumb(name, 72.dp)
                IconButton(
                    onClick = {
                        deleteImage(context.applicationContext, name)
                        pendingImage = null
                    },
                ) { Icon(Icons.Filled.Close, contentDescription = "Remove picture") }
            }
        }

        // iMessage-style composer.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canSendPictures) {
                IconButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !busy,
                ) { Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Add a picture") }
            }
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (pendingImage != null) "Ask about the picture" else "Message") },
                shape = RoundedCornerShape(21.dp),
                maxLines = 4,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(40.dp)
                    .clip(CircleShape)
                    .background(if (canSend) SendBlue else SendBlue.copy(alpha = 0.35f))
                    .clickable(enabled = canSend) { submit() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Send", tint = Color.White)
            }
        }
        Text(
            "$aiName is AI and can make mistakes.",
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}
