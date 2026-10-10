package com.example.aiapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

const val APP_VERSION = "1.2.1"
const val REPO_URL = "https://github.com/karolek13245/zonk-core"
private const val ISSUES_URL = "$REPO_URL/issues"

/** Plain-language terms. Not legal advice: have a lawyer review them before a wide public release. */
private fun termsSections(): List<Pair<String, String>> = listOf(
    "What Zonk-Core is" to
        "Zonk-Core is an app that runs open AI models on your phone and can talk to an AI server you point it at. " +
        "It is free software published at $REPO_URL.",
    "AI can be wrong" to
        "Replies are written by an AI model. They can be wrong, out of date or made up. " +
        "Don't rely on them for medical, legal, financial or safety decisions, and check anything important yourself.",
    "Your messages and privacy" to
        "• On-device mode: your messages are processed on this phone and are not sent anywhere.\n" +
        "• Cloud mode: your messages go to the server address you typed in Settings. That server's owner decides how they are handled.\n" +
        "• Shared models: if you use a model that runs on someone else's phone through a relay, your messages are handled by that phone and by the relay. " +
        "Never send passwords, ID numbers or other sensitive information that way.\n" +
        "• Your chat history is stored only on this phone. You can delete or archive any chat in the Chats screen.\n" +
        "• The server address and API key you enter are stored on this phone in the app's private storage.",
    "If you share your phone" to
        "If you turn on sharing, other people's messages are processed by your phone and you are responsible for how you run it. " +
        "Keep your keys secret and don't use sharing to run anything illegal.",
    "Acceptable use" to
        "Don't use Zonk-Core to break the law, to harass or deceive people, or to create sexual content involving minors " +
        "or intimate images of real people without their consent.",
    "Models and licenses" to
        "The models are made by other people and have their own licenses, which you agree to when you download them:\n" +
        CATALOG.joinToString("\n") { "• ${it.name}: ${it.license}" },
    "No warranty" to
        "The app is provided \"as is\", without any promise that it will work or be free of errors. " +
        "To the extent the law allows, the makers are not responsible for losses from using it.",
    "Changes and contact" to
        "These terms may change when the app is updated. Questions or problems: $ISSUES_URL",
)

@Composable
fun AboutSection(cardModifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    var showTerms by remember { mutableStateOf(false) }
    val sub = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    InfoCard(cardModifier) {
        Text("Zonk-Core", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Run open AI models on your phone and share them through your own server. " +
                "Free and open source.",
            fontSize = 13.sp, color = sub,
        )
        Spacer(Modifier.height(12.dp))
        AboutRow("Version", APP_VERSION, null)
        AboutRow("Source code on GitHub", "Open", { runCatching { uri.openUri(REPO_URL) } })
        AboutRow("Report a problem", "Open", { runCatching { uri.openUri(ISSUES_URL) } })
        AboutRow("Terms of Service", "Read", { showTerms = true })
        Spacer(Modifier.height(4.dp))
        Text("AI can make mistakes. Check important information.", fontSize = 12.sp, color = sub)
    }

    if (showTerms) TermsDialog { showTerms = false }
}

@Composable
private fun AboutRow(label: String, value: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Text(
            value,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun TermsDialog(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { LargeTitle("Terms") }
                    TextButton(onClick = onClose) { Text("Close", fontSize = 16.sp) }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        "Version $APP_VERSION. Plain-language terms for using Zonk-Core.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    termsSections().forEachIndexed { i, (title, body) ->
                        Column {
                            Text("${i + 1}. $title", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(body, fontSize = 14.sp, lineHeight = 20.sp)
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
