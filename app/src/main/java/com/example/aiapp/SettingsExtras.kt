package com.example.aiapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One round theme chip. It is split down the middle: the top-left half shows the theme in light mode,
 * the bottom-right half shows it in dark mode. The chosen chip gets a ring in the app's blue.
 */
@Composable
private fun ThemeSwatch(theme: AppTheme, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val split = Brush.linearGradient(
        0.0f to Color(theme.lightTop),
        0.5f to Color(theme.lightBottom),
        0.5f to Color(theme.darkTop),
        1.0f to Color(theme.darkBottom),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
    Column(
        Modifier
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(split)
                .border(if (selected) 3.dp else 1.dp, ring, CircleShape),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            theme.name,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Settings group "Appearance": the background theme, and the seasonal effects (with a way to try them out of season). */
@Composable
fun AppearanceSection(
    themeId: String, onTheme: (String) -> Unit,
    seasonFx: Boolean, onSeasonFx: (Boolean) -> Unit,
    onPreview: (Season) -> Unit,
) {
    SectionHeader("Appearance")
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)) {
            Text("Background", Modifier.padding(horizontal = 16.dp), fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                THEMES.forEach { t -> ThemeSwatch(t, t.id == themeId) { onTheme(t.id) } }
            }
        }
        SettingsDivider()
        SwitchRow("Seasonal effects", "Bats at Halloween, sparkles at Christmas.", seasonFx, onSeasonFx)
        SettingsDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            TextButton(onClick = { onPreview(Season.HALLOWEEN) }) { Text("Preview Halloween") }
            TextButton(onClick = { onPreview(Season.CHRISTMAS) }) { Text("Preview Christmas") }
        }
    }
    SettingsFootnote(
        "Backgrounds follow your phone's light or dark mode. Seasonal effects start by themselves from Oct 15 " +
            "to Nov 1 (Halloween) and Dec 1 to Jan 6 (Christmas). The previews last a few seconds. " +
            "You can turn the effects off here any time.",
    )
}

/** Settings group "Updates": the on/off switch for the update check, and a button to check right now. */
@Composable
fun UpdatesSection(
    checkUpdates: Boolean, onCheckUpdates: (Boolean) -> Unit,
    version: String, checking: Boolean, onCheckNow: () -> Unit,
) {
    val sub = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    SectionHeader("Updates")
    SettingsGroup {
        SwitchRow("Check for updates", "Looks for a new version when the app opens.", checkUpdates, onCheckUpdates)
        SettingsDivider()
        Row(
            Modifier.fillMaxWidth()
                .clickable(enabled = !checking, onClick = onCheckNow)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Check now", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
            Text(if (checking) "Checking…" else "Version $version", color = sub)
        }
    }
    SettingsFootnote(
        "The app asks GitHub for the newest release and shows what changed. GitHub can see your internet address; " +
            "nothing from your chats is sent. Updates are never installed by themselves: the button opens the download page.",
    )
}
