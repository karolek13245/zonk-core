package com.example.aiapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatHistoryScreen(
    chats: List<Conversation>,
    currentId: String,
    busy: Boolean,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onArchive: (String, Boolean) -> Unit,
    onNew: () -> Unit,
    onBack: () -> Unit,
) {
    var showArchived by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Conversation?>(null) }
    val sub = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val shown = remember(chats, showArchived) {
        chats.filter { it.archived == showArchived }.sortedByDescending { it.updated }
    }
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to chat")
            }
            Box(Modifier.weight(1f)) { LargeTitle("Chats") }
            IconButton(onClick = onNew, enabled = !busy) {
                Icon(Icons.Filled.Add, contentDescription = "New chat")
            }
        }

        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false to "Active", true to "Archived").forEach { (flag, label) ->
                val selected = showArchived == flag
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { showArchived = flag }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        if (busy) {
            Text(
                "Wait for the current reply to finish before switching chats.",
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                fontSize = 12.sp, color = sub,
            )
        }
        Spacer(Modifier.height(8.dp))

        if (shown.isEmpty()) {
            Text(
                if (showArchived) "No archived chats." else "No saved chats yet. Start talking and they show up here.",
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                color = sub,
            )
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { it.id }) { c ->
                val current = c.id == currentId
                Card(
                    Modifier.fillMaxWidth().clickable(enabled = !busy) { onOpen(c.id) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${c.messages.size} messages · ${fmt.format(Date(c.updated))}" + if (current) " · open" else "",
                                fontSize = 12.sp, color = sub,
                            )
                        }
                        IconButton(onClick = { onArchive(c.id, !c.archived) }) {
                            Icon(
                                if (c.archived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                                contentDescription = if (c.archived) "Unarchive" else "Archive",
                            )
                        }
                        IconButton(onClick = { confirmDelete = c }, enabled = !(busy && current)) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this chat?") },
            text = { Text("\"${c.title}\" will be removed from this phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { onDelete(c.id); confirmDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
