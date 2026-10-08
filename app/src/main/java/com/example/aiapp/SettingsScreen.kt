package com.example.aiapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        Modifier.padding(horizontal = 32.dp),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
}

@Composable
fun SettingsScreen(
    url: String, onUrl: (String) -> Unit,
    key: String, onKey: (String) -> Unit,
    model: String, onModel: (String) -> Unit,
    onDevice: Boolean, onOnDevice: (Boolean) -> Unit,
    sizeKey: String,
    downloader: ModelDownloader,
) {
    var keyVisible by remember { mutableStateOf(false) }
    val pad = Modifier.padding(horizontal = 16.dp)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LargeTitle("Settings")

        SectionLabel("Server")
        InfoCard(pad) {
            OutlinedTextField(
                url, onUrl, Modifier.fillMaxWidth(),
                label = { Text("Server URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                key, onKey, Modifier.fillMaxWidth(),
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (keyVisible) "Hide API key" else "Show API key",
                        )
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(model, onModel, Modifier.fillMaxWidth(), label = { Text("Model name (cloud)") }, singleLine = true)
        }

        SectionLabel("On-device")
        InfoCard(pad) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    onDevice, onOnDevice,
                    colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF34C759), checkedThumbColor = Color.White),
                )
                Spacer(Modifier.width(12.dp))
                Text("Run on device")
            }
            if (onDevice) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (downloader.isReady(sizeKey)) "Using the $sizeKey model."
                    else "The $sizeKey model isn't downloaded yet. Download it below.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }

        SectionLabel("Models")
        var query by remember { mutableStateOf("") }
        var category by remember { mutableStateOf("All") }
        val categories = remember { listOf("All") + CATALOG.map { it.category }.distinct() }
        val filtered = remember(query, category) {
            CATALOG.filter { m ->
                (category == "All" || m.category == category) &&
                    (query.isBlank() ||
                        m.name.contains(query, ignoreCase = true) ||
                        m.sizeKey.contains(query, ignoreCase = true) ||
                        m.category.contains(query, ignoreCase = true))
            }
        }

        InfoCard(pad) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search models") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                categories.forEach { c ->
                    val selected = c == category
                    Box(
                        Modifier.clip(RoundedCornerShape(16.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background)
                            .clickable { category = c }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(c, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }

        InfoCard(pad) {
            if (filtered.isEmpty()) {
                Text(
                    "No models match \"$query\".",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            } else {
                filtered.forEachIndexed { i, m ->
                    if (i > 0) Spacer(Modifier.height(14.dp))
                    ModelRow(m, downloader)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Public 4-bit models that run on phones. Downloads use Wi-Fi only and keep going in the background.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }

        SectionLabel("About")
        InfoCard(pad) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Version")
                Text("1.2", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
