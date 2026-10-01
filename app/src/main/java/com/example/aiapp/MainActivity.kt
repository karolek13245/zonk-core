package com.example.aiapp

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var onboarded by remember { mutableStateOf(prefs.getBoolean("onboarded", false)) }
                    if (onboarded) ChatScreen(prefs) else OnboardingScreen {
                        prefs.edit().putBoolean("onboarded", true).apply()
                        onboarded = true
                    }
                }
            }
        }
    }
}

@Composable
fun ChatScreen(prefs: android.content.SharedPreferences) {
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<Msg>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    var url by remember { mutableStateOf(prefs.getString("url", "https://api.example.com") ?: "") }
    var key by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "my-model") ?: "") }
    var onDevice by remember { mutableStateOf(prefs.getBoolean("onDevice", false)) }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""
        messages.add(Msg("user", text))
        busy = true
        scope.launch {
            val backend: ModelBackend = if (onDevice) OnDeviceBackend() else CloudBackend(url, key, model)
            val reply = try { backend.chat(messages.toList()) } catch (e: Exception) { "Error: ${e.message}" }
            messages.add(Msg("assistant", reply))
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("AI App", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { showSettings = true }) { Text("Settings") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { m ->
                val mine = m.role == "user"
                Card(
                    Modifier.fillMaxWidth().padding(start = if (mine) 40.dp else 0.dp, end = if (mine) 0.dp else 40.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) { Text(m.content, Modifier.padding(12.dp)) }
            }
            if (busy) item { Text("Thinking…") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Message") })
            Spacer(Modifier.width(8.dp))
            Button(onClick = { send() }, enabled = !busy) { Text("Send") }
        }
    }

    if (showSettings) AlertDialog(
        onDismissRequest = { showSettings = false },
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, singleLine = true)
                OutlinedTextField(key, { key = it }, label = { Text("API key") }, singleLine = true)
                OutlinedTextField(model, { model = it }, label = { Text("Model name") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(onDevice, { onDevice = it }); Spacer(Modifier.width(8.dp)); Text("Run on device")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.edit().putString("url", url).putString("key", key).putString("model", model).putBoolean("onDevice", onDevice).apply()
                showSettings = false
            }) { Text("Save") }
        }
    )
}
