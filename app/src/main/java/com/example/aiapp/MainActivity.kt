package com.example.aiapp

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

    var tab by rememberSaveable { mutableStateOf(0) }
    var sharing by rememberSaveable { mutableStateOf(false) }

    // Chat state lives here so it survives switching tabs and rotating the phone.
    val messages = rememberSaveable(
        saver = listSaver<SnapshotStateList<Msg>, Msg>(
            save = { it.toList() },
            restore = { saved -> mutableStateListOf(*saved.toTypedArray()) },
        ),
    ) { mutableStateListOf<Msg>() }
    var busy by remember { mutableStateOf(false) }

    var url by remember { mutableStateOf(prefs.getString("url", "") ?: "") }
    var key by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "") ?: "") }
    var onDevice by remember { mutableStateOf(prefs.getBoolean("onDevice", false)) }
    var sizeKey by remember { mutableStateOf(prefs.getString("size", "1.5B") ?: "1.5B") }

    LaunchedEffect(Unit) {
        while (true) {
            stats.tick()
            delay(2000)
        }
    }

    fun send(text: String) {
        if (busy) return
        messages.add(Msg("user", text))
        busy = true
        // Cap history so long chats don't blow past the model's token limit.
        val history = messages.takeLast(HISTORY_LIMIT)
        val startedAt = stats.start()
        scope.launch {
            try {
                val backend: ModelBackend =
                    if (onDevice) LlamaBackend(context.applicationContext, sizeKey) else CloudBackend(url, key, model)
                val reply = try {
                    backend.chat(history)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
                messages.add(Msg("assistant", reply))
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
                )
                1 -> ChatScreen(
                    messages = messages,
                    busy = busy,
                    modelLabel = if (onDevice) "On this phone · $sizeKey" else "Cloud server",
                    onSend = { send(it) },
                )
                else -> SettingsScreen(
                    url = url, onUrl = { url = it; prefs.edit().putString("url", it).apply() },
                    key = key, onKey = { key = it; prefs.edit().putString("key", it).apply() },
                    model = model, onModel = { model = it; prefs.edit().putString("model", it).apply() },
                    onDevice = onDevice, onOnDevice = { onDevice = it; prefs.edit().putBoolean("onDevice", it).apply() },
                    sizeKey = sizeKey,
                )
            }
        }
    }
}

/** iMessage-style bubble: user on the right (blue), assistant on the left. */
@Composable
private fun ChatBubble(msg: Msg) {
    val mine = msg.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(
                msg.content,
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ChatScreen(
    messages: List<Msg>,
    busy: Boolean,
    modelLabel: String,
    onSend: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val count = messages.size + if (busy) 1 else 0
    LaunchedEffect(count) {
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""
        onSend(text)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LargeTitle("Chat")
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
            if (busy) item { ChatBubble(Msg("assistant", "Typing…")) }
        }

        // iMessage-style composer.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
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
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = { submit() }, enabled = !busy && input.isNotBlank()) {
                Text("Send", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
