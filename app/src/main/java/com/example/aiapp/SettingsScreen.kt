package com.example.aiapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Settings is a list of groups, like iOS Settings: a small heading, a rounded box of rows, and a note underneath.
// Rows have 16 dp of space on the left, and headings and notes line up with the text inside the rows.

/** Colour for small grey text (captions, notes). Bright enough to read in both light and dark mode. */
@Composable
private fun captionColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)

/** A heading above a group, e.g. "MODELS". It sits close to its own group and far from the group above it. */
@Composable
private fun SectionHeader(text: String, top: Dp = 28.dp) {
    Text(
        text.uppercase(),
        Modifier.padding(start = 32.dp, end = 32.dp, top = top, bottom = 8.dp),
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        color = captionColor(),
    )
}

/** A small explanation under a group. */
@Composable
internal fun SettingsFootnote(text: String) {
    Text(
        text,
        Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = captionColor(),
    )
}

/** A rounded box that holds a few rows. */
@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        content = content,
    )
}

/** A thin line between two rows of a group. It starts at the text, not at the edge of the box. */
@Composable
internal fun SettingsDivider() {
    Box(
        Modifier.padding(start = 16.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
    )
}

@Composable
private fun switchColors(): SwitchColors = SwitchDefaults.colors(
    checkedTrackColor = Color(0xFF34C759),
    checkedThumbColor = Color.White,
    checkedBorderColor = Color.Transparent,
    uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
    uncheckedThumbColor = Color.White,
    uncheckedBorderColor = Color.Transparent,
)

/** A row with a title (and an optional line under it) and a switch on the right. Tap anywhere on the row to flip it. */
@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            if (subtitle != null) {
                Text(subtitle, fontSize = 13.sp, lineHeight = 18.sp, color = captionColor())
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange, colors = switchColors())
    }
}

/**
 * A text field drawn as a list row: a small label on top, the text below it. The whole row is the field,
 * so tapping anywhere on it starts typing. [secret] hides the text and adds a Show / Hide button.
 */
@Composable
private fun FieldRow(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    var shown by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier.fillMaxWidth(),
        textStyle = LocalTextStyle.current.copy(color = colors.onSurface, fontSize = 16.sp, lineHeight = 22.sp),
        singleLine = true,
        cursorBrush = SolidColor(colors.primary),
        visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, fontSize = 12.sp, lineHeight = 16.sp, color = captionColor())
                    Box(Modifier.fillMaxWidth().heightIn(min = 26.dp), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                hint,
                                fontSize = 16.sp,
                                lineHeight = 22.sp,
                                color = colors.onSurface.copy(alpha = 0.6f),
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                }
                if (secret) {
                    Text(
                        if (shown) "Hide" else "Show",
                        Modifier.clickable { shown = !shown }.padding(horizontal = 8.dp, vertical = 10.dp),
                        fontSize = 15.sp,
                        color = colors.primary,
                    )
                }
            }
        },
    )
}

/** The search box above the model list: a rounded field with a magnifier and a clear button. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        textStyle = LocalTextStyle.current.copy(color = colors.onSurface, fontSize = 16.sp, lineHeight = 22.sp),
        singleLine = true,
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceVariant)
                    .padding(start = 12.dp, end = 4.dp)
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp), tint = captionColor())
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            "Search models",
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            color = colors.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                        )
                    }
                    inner()
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search", modifier = Modifier.size(20.dp))
                    }
                }
            }
        },
    )
}

/** The row of filter chips under the search box (All, General, Coding, Vision). */
@Composable
private fun CategoryChips(categories: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        categories.forEach { c ->
            val on = c == selected
            Box(
                Modifier.clip(RoundedCornerShape(20.dp))
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelect(c) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    c,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    url: String, onUrl: (String) -> Unit,
    key: String, onKey: (String) -> Unit,
    model: String, onModel: (String) -> Unit,
    onDevice: Boolean, onOnDevice: (Boolean) -> Unit,
    sizeKey: String,
    downloader: ModelDownloader,
    showTokens: Boolean, onShowTokens: (Boolean) -> Unit,
) {
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
    val current = shortModelName(catalogFor(sizeKey).name)

    Column(
        // imePadding: lets the page scroll up above the keyboard, so the field you are typing in stays visible.
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
    ) {
        LargeTitle("Settings")

        SectionHeader("On this phone", top = 4.dp)
        SettingsGroup {
            SwitchRow(
                title = "Run on this phone",
                subtitle = when {
                    !onDevice -> "Off: chats go to your server (set it up below)."
                    downloader.isReady(sizeKey) -> "Using $current."
                    else -> "$current isn't downloaded yet. Download it below."
                },
                checked = onDevice,
                onChange = onOnDevice,
            )
        }

        SectionHeader("Models")
        SearchField(query) { query = it }
        Spacer(Modifier.height(10.dp))
        CategoryChips(categories, category) { category = it }
        Spacer(Modifier.height(12.dp))
        SettingsGroup {
            if (filtered.isEmpty()) {
                Text("No models match \"$query\".", Modifier.padding(16.dp), color = captionColor())
            } else {
                filtered.forEachIndexed { i, m ->
                    if (i > 0) SettingsDivider()
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        ModelRow(m, downloader, inUse = onDevice && sizeKey == m.sizeKey)
                    }
                }
            }
        }
        SettingsFootnote(
            "Public models that run on phones (mostly 4-bit). Downloads use Wi-Fi only and keep going in the background. " +
                "To send pictures, download the Vision model, then pick it with the model button under the message box in Chat.",
        )

        SectionHeader("Chat")
        SettingsGroup {
            SwitchRow("Show token total", null, showTokens, onShowTokens)
        }
        SettingsFootnote(
            "Shows how many tokens the chat uses so far, above the message box. A token is a small piece of a word.",
        )

        SectionHeader("Your server (optional)")
        SettingsGroup {
            FieldRow("Server URL", url, onUrl, "https://your-server.example", KeyboardType.Uri)
            SettingsDivider()
            FieldRow("API key", key, onKey, "Paste your API key", KeyboardType.Password, secret = true)
            SettingsDivider()
            FieldRow("Model name", model, onModel, "Model name on your server")
        }
        SettingsFootnote(
            "Only needed to chat with a server instead of this phone. Once an address is set, pick it with the model " +
                "button in Chat. The address must start with https://.",
        )

        SectionHeader("About")
        AboutSection()
        Spacer(Modifier.height(32.dp))
    }
}
