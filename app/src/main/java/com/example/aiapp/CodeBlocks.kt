package com.example.aiapp

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** A reply is split into normal text and code blocks (the parts between ``` fences). */
sealed interface Seg {
    data class Text(val text: String) : Seg
    data class Code(val lang: String, val code: String, val closed: Boolean) : Seg
}

fun parseSegments(src: String): List<Seg> {
    val out = ArrayList<Seg>()
    fun addText(raw: String) {
        val t = raw.trim('\n')
        if (t.isNotBlank()) out.add(Seg.Text(t))
    }
    var i = 0
    while (i < src.length) {
        val open = src.indexOf("```", i)
        if (open < 0) {
            addText(src.substring(i))
            break
        }
        addText(src.substring(i, open))
        val lineEnd = src.indexOf('\n', open + 3)
        if (lineEnd < 0) {
            // The reply is still being written and the fence line isn't finished yet.
            out.add(Seg.Code(src.substring(open + 3).trim(), "", false))
            break
        }
        val lang = src.substring(open + 3, lineEnd).trim()
        val close = src.indexOf("```", lineEnd + 1)
        if (close < 0) {
            out.add(Seg.Code(lang, src.substring(lineEnd + 1).trimEnd('\n'), false))
            break
        }
        out.add(Seg.Code(lang, src.substring(lineEnd + 1, close).trimEnd('\n'), true))
        i = close + 3
    }
    return out
}

private fun extensionFor(lang: String): String = when (lang.lowercase().trim()) {
    "python", "py" -> "py"
    "javascript", "js", "node" -> "js"
    "typescript", "ts" -> "ts"
    "kotlin", "kt" -> "kt"
    "java" -> "java"
    "c" -> "c"
    "cpp", "c++" -> "cpp"
    "csharp", "c#", "cs" -> "cs"
    "go", "golang" -> "go"
    "rust", "rs" -> "rs"
    "swift" -> "swift"
    "php" -> "php"
    "ruby", "rb" -> "rb"
    "bash", "sh", "shell", "zsh" -> "sh"
    "html" -> "html"
    "css" -> "css"
    "json" -> "json"
    "xml" -> "xml"
    "yaml", "yml" -> "yml"
    "sql" -> "sql"
    "markdown", "md" -> "md"
    else -> "txt"
}

private val CodeBg = Color(0xFF0D0D10)
private val CodeText = Color(0xFFE6E6EA)
private val CodeMuted = Color(0xFF8E8E98)

/** Black box for code, with the language on top and Copy / Download buttons. */
@Composable
fun CodeBox(lang: String, code: String, closed: Boolean) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var saveNote by remember { mutableStateOf<String?>(null) }
    val currentCode by rememberUpdatedState(code)

    val ext = extensionFor(lang)
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            saveNote = if (writeText(context, uri, currentCode)) "Saved" else "Couldn't save"
        }
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    LaunchedEffect(saveNote) {
        if (saveNote != null) {
            delay(2000)
            saveNote = null
        }
    }

    Column(
        Modifier.fillMaxWidth()
            .background(CodeBg, RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                lang.ifBlank { "code" },
                Modifier.weight(1f),
                color = CodeMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                },
                enabled = code.isNotEmpty(),
            ) { Text(if (copied) "Copied" else "Copy", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            TextButton(
                onClick = { saver.launch("code.$ext") },
                enabled = code.isNotEmpty(),
            ) { Text(saveNote ?: "Download", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
        Text(
            code.ifEmpty { " " },
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            color = CodeText,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
        )
    }
}

private fun writeText(context: Context, uri: Uri, text: String): Boolean = try {
    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
} catch (e: Exception) {
    false
}

/** An assistant reply: normal text in a bubble, code in black boxes. */
@Composable
fun AssistantContent(text: String, bubble: @Composable (String) -> Unit) {
    val segs = remember(text) { parseSegments(text) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segs.forEach { seg ->
            when (seg) {
                is Seg.Text -> bubble(seg.text)
                is Seg.Code -> CodeBox(seg.lang, seg.code, seg.closed)
            }
        }
    }
}
