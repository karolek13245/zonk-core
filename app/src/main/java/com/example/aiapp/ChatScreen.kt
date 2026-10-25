package com.example.aiapp

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

private val SendBlue = Color(0xFF1B4FBF)
private val WarnOrange = Color(0xFFFF9500)

/** What the model button under the message box shows, and what its menu offers. */
class ModelMenu(
    /** Short name shown on the button. */
    val label: String,
    /** False when the chosen model can't be used yet (not downloaded, or no server set): shows a warning mark. */
    val ready: Boolean,
    val onDevice: Boolean,
    val sizeKey: String,
    /** Models that are downloaded and can be picked. */
    val readyModels: List<CatalogModel>,
    /** Name of the cloud server entry, or null when no server address is set. */
    val cloudLabel: String?,
    val onSelectModel: (String) -> Unit,
    val onSelectCloud: () -> Unit,
    val onMoreModels: () -> Unit,
)

// ---------- Messages ----------

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

/** A picture from a chat, shown small. It loads in the background so scrolling stays smooth. */
@Composable
private fun PictureThumb(fileName: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val loaded by produceState<Pair<Boolean, ImageBitmap?>>(false to null, fileName) {
        value = true to withContext(Dispatchers.IO) { loadThumbnail(context, fileName) }
    }
    val bmp = loaded.second
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
        } else if (loaded.first) {
            Text("Picture missing", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
    }
}

/** One plain text bubble. Long-press the text to select and copy it. */
@Composable
private fun TextBubble(text: String, mine: Boolean, error: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            modifier = if (mine) Modifier.widthIn(max = 320.dp) else Modifier,
            color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(18.dp),
        ) {
            SelectionContainer {
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

/** Shown in place of the message list while the chat is empty. */
@Composable
private fun EmptyChat(name: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("How can I help?", fontSize = 28.sp, fontFamily = FontFamily.Serif, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "Chatting with $name",
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
    }
}

// ---------- Scrolling helpers ----------

/** True when the list was already showing its very end (judged from the last layout). */
private fun LazyListState.isNearEnd(): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    return last.index >= info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 48
}

/** Scrolls so the end of the last item is visible (a long reply shows its end, not its start). */
private suspend fun LazyListState.scrollToEnd(lastIndex: Int) {
    scrollToItem(lastIndex)
    scrollBy(100_000f)
}

// ---------- Chat screen ----------

@Composable
fun ChatScreen(
    messages: List<Msg>,
    busy: Boolean,
    partial: String,
    thinkingText: String,
    /** Full name of the AI, used in the "is AI" notice. */
    aiName: String,
    canSendPictures: Boolean,
    showTokens: Boolean,
    tokens: Int,
    /** The text being typed. It lives outside this screen so it survives switching tabs. */
    input: String,
    onInput: (String) -> Unit,
    /** File name of the picture waiting to be sent (also kept outside this screen). */
    pendingImage: String?,
    onPendingImage: (String?) -> Unit,
    modelMenu: ModelMenu,
    onSend: (String, String?) -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onHistory: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var menuOpen by remember { mutableStateOf(false) }

    // While busy there is one extra item: the "thinking" card, or the reply being written.
    val itemCount = messages.size + if (busy) 1 else 0

    // A new message (yours or the reply) always scrolls to the bottom.
    LaunchedEffect(messages.size) {
        if (itemCount > 0) listState.scrollToEnd(itemCount - 1)
    }
    // While a reply is being written, keep following it, unless you scrolled up to read.
    LaunchedEffect(partial.length, busy) {
        if (busy && itemCount > 0 && listState.isNearEnd()) listState.scrollToEnd(itemCount - 1)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val name = withContext(Dispatchers.IO) { saveSelectedImage(app, uri) }
                if (name != null) {
                    pendingImage?.let { deleteImage(app, it) }
                    onPendingImage(name)
                } else {
                    Toast.makeText(context, "Couldn't open that picture.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val canSend = !busy && (input.isNotBlank() || pendingImage != null)

    fun submit() {
        if (!canSend) return
        val text = input.trim()
        val image = pendingImage
        onInput("")
        onPendingImage(null)
        onSend(text, image)
    }

    fun plusClicked() {
        if (canSendPictures) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else {
            Toast.makeText(context, "Pick a model that can look at pictures (SmolVLM) first.", Toast.LENGTH_LONG).show()
            menuOpen = true
        }
    }

    val placeholder = when {
        pendingImage != null -> "Ask about the picture…"
        messages.isEmpty() -> "Message ${modelMenu.label}…"
        else -> "Reply to ${modelMenu.label}…"
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { LargeTitle("Chat") }
            IconButton(onClick = onHistory) { Icon(Icons.Filled.History, contentDescription = "Chat history") }
            IconButton(onClick = onNewChat, enabled = !busy && messages.isNotEmpty()) {
                Icon(Icons.Filled.Add, contentDescription = "New chat")
            }
        }

        if (messages.isEmpty() && !busy) {
            EmptyChat(aiName, Modifier.weight(1f))
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(messages) { m -> ChatBubble(m) }
                if (busy) {
                    if (partial.isEmpty()) {
                        item { ThinkingCard(thinkingText) }
                    } else {
                        item { ChatBubble(Msg("assistant", partial)) }
                    }
                }
            }
        }

        // Running token total for this chat (can be switched off in Settings).
        if (showTokens && messages.isNotEmpty()) {
            Text(
                if (tokens > 0) "Tokens in this chat: ${NumberFormat.getIntegerInstance().format(tokens)}"
                else "Tokens in this chat: counted after the next reply",
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
        }

        MessageBox(
            input = input,
            onInput = onInput,
            placeholder = placeholder,
            pendingImage = pendingImage,
            onRemoveImage = {
                pendingImage?.let { deleteImage(app, it) }
                onPendingImage(null)
            },
            busy = busy,
            canSend = canSend,
            modelMenu = modelMenu,
            menuOpen = menuOpen,
            onMenuOpen = { menuOpen = it },
            onPlus = { plusClicked() },
            onSend = { submit() },
            onStop = onStop,
        )

        Text(
            "$aiName is AI and can make mistakes.",
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
    }
}

// ---------- The message box ----------

/** A round button: the + button, and the send / stop button. */
@Composable
private fun RoundButton(
    onClick: () -> Unit,
    background: Color,
    description: String,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        Modifier.size(40.dp)
            .clip(CircleShape)
            .background(background)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * The message box: a rounded box with the text on top and, underneath, a + button (add a picture),
 * the model button, and a round button that sends, or stops the reply while the AI is writing.
 */
@Composable
private fun MessageBox(
    input: String,
    onInput: (String) -> Unit,
    placeholder: String,
    pendingImage: String?,
    onRemoveImage: () -> Unit,
    busy: Boolean,
    canSend: Boolean,
    modelMenu: ModelMenu,
    menuOpen: Boolean,
    onMenuOpen: (Boolean) -> Unit,
    onPlus: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val chipBg = colors.onSurface.copy(alpha = 0.08f)

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(28.dp),
        color = colors.surfaceVariant,
        border = BorderStroke(1.dp, colors.onSurface.copy(alpha = 0.12f)),
    ) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)) {
            // The picture waiting to be sent.
            if (pendingImage != null) {
                Box(Modifier.padding(start = 6.dp, top = 10.dp, bottom = 2.dp)) {
                    PictureThumb(pendingImage, 64.dp)
                    Box(
                        Modifier.align(Alignment.TopEnd)
                            .offset(x = 8.dp, y = (-8).dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF48484A))
                            .clickable(onClickLabel = "Remove picture", role = Role.Button) { onRemoveImage() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove picture",
                            modifier = Modifier.size(14.dp),
                            tint = Color.White,
                        )
                    }
                }
            }

            BasicTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = LocalTextStyle.current.copy(color = colors.onSurface, fontSize = 16.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(colors.primary),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().heightIn(min = 28.dp), contentAlignment = Alignment.CenterStart) {
                        if (input.isEmpty()) {
                            Text(
                                placeholder,
                                color = colors.onSurface.copy(alpha = 0.6f),
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(onClick = onPlus, background = chipBg, description = "Add a picture") {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = colors.onSurface)
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    ModelButton(
                        menu = modelMenu,
                        enabled = !busy,
                        expanded = menuOpen,
                        onExpanded = onMenuOpen,
                        chipBg = chipBg,
                    )
                }
                Spacer(Modifier.width(8.dp))
                val active = busy || canSend
                RoundButton(
                    onClick = { if (busy) onStop() else onSend() },
                    background = if (active) SendBlue else SendBlue.copy(alpha = 0.35f),
                    description = if (busy) "Stop" else "Send",
                    enabled = active,
                ) {
                    if (busy) {
                        Box(Modifier.size(14.dp).background(Color.White, RoundedCornerShape(3.dp)))
                    } else {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = null, tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(
        text.uppercase(),
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
    )
}

/** The model button: shows the model in use and opens a menu to switch. */
@Composable
private fun ModelButton(
    menu: ModelMenu,
    enabled: Boolean,
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    chipBg: Color,
) {
    val sub = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    Box {
        Row(
            Modifier.height(40.dp)
                .clip(CircleShape)
                .background(chipBg)
                .clickable(enabled = enabled, role = Role.Button) { onExpanded(true) }
                .padding(start = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                menu.label,
                Modifier.weight(1f, fill = false),
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!menu.ready) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = "This model isn't ready yet",
                    modifier = Modifier.size(18.dp),
                    tint = WarnOrange,
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "Choose a model",
                modifier = Modifier.size(20.dp),
                tint = sub,
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { onExpanded(false) }) {
            if (menu.readyModels.isNotEmpty()) {
                MenuLabel("On this phone")
                menu.readyModels.forEach { m ->
                    val selected = menu.onDevice && menu.sizeKey == m.sizeKey
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(shortModelName(m.name), fontSize = 15.sp)
                                if (m.canSeePictures) Text("Can look at pictures", fontSize = 12.sp, color = sub)
                            }
                        },
                        onClick = {
                            onExpanded(false)
                            menu.onSelectModel(m.sizeKey)
                        },
                        trailingIcon = {
                            if (selected) Icon(Icons.Filled.Check, contentDescription = "In use")
                        },
                    )
                }
            }
            menu.cloudLabel?.let { cloud ->
                MenuLabel("Your server")
                DropdownMenuItem(
                    text = { Text(cloud, fontSize = 15.sp) },
                    onClick = {
                        onExpanded(false)
                        menu.onSelectCloud()
                    },
                    trailingIcon = {
                        if (!menu.onDevice) Icon(Icons.Filled.Check, contentDescription = "In use")
                    },
                )
            }
            if (menu.readyModels.isEmpty() && menu.cloudLabel == null) {
                DropdownMenuItem(
                    text = { Text("No models downloaded yet", fontSize = 15.sp, color = sub) },
                    onClick = {},
                    enabled = false,
                )
            }
            Box(
                Modifier.fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            )
            DropdownMenuItem(
                text = { Text("Get more models…", fontSize = 15.sp) },
                onClick = {
                    onExpanded(false)
                    menu.onMoreModels()
                },
            )
        }
    }
}
