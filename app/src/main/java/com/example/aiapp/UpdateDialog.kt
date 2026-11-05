package com.example.aiapp

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The version of the installed app, read from the system so it can never disagree with the APK itself. */
@Suppress("DEPRECATION")
fun installedVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: APP_VERSION
} catch (e: Exception) {
    APP_VERSION
}

/** One release in the list: its name, its day, and what changed. */
@Composable
private fun ReleaseBlock(r: ReleaseInfo) {
    val sub = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(r.tag, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (r.date.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(r.date, fontSize = 12.sp, color = sub)
        }
    }
    Spacer(Modifier.height(4.dp))
    if (r.notes.isNotEmpty()) {
        Text(r.notes, fontSize = 14.sp, lineHeight = 20.sp)
    } else {
        Text("No release notes.", fontSize = 13.sp, color = sub)
    }
}

/**
 * The update pop-up. "Get update" opens the release page in the browser (the app never installs anything by
 * itself). "Skip this version" hides the pop-up until an even newer version comes out.
 */
@Composable
fun UpdateDialog(
    state: UpdateUi,
    installed: String,
    onGet: (String) -> Unit,
    onSkip: (String) -> Unit,
    onClose: () -> Unit,
) {
    val sub = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    when (state) {
        is UpdateUi.Available -> {
            val latest = state.newer.first()
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("Update available") },
                text = {
                    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                        Text(
                            "Zonk-Core ${latest.tag} is out. You have $installed.",
                            fontSize = 14.sp, lineHeight = 20.sp, color = sub,
                        )
                        state.newer.take(3).forEach { ReleaseBlock(it) }
                    }
                },
                confirmButton = { TextButton(onClick = { onGet(latest.url) }) { Text("Get update") } },
                dismissButton = {
                    Row {
                        TextButton(onClick = { onSkip(latest.tag) }) { Text("Skip this version") }
                        TextButton(onClick = onClose) { Text("Later") }
                    }
                },
            )
        }
        is UpdateUi.UpToDate -> {
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("You're up to date") },
                text = {
                    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                        Text(
                            "Zonk-Core $installed is the newest version.",
                            fontSize = 14.sp, lineHeight = 20.sp, color = sub,
                        )
                        if (state.recent.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Text("Recent releases", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = sub)
                            state.recent.forEach { ReleaseBlock(it) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
            )
        }
        UpdateUi.Failed -> {
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("Couldn't check") },
                text = { Text("Check your internet connection and try again.", fontSize = 14.sp, lineHeight = 20.sp) },
                confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
            )
        }
    }
}
