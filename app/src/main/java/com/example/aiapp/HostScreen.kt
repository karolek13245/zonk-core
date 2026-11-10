package com.example.aiapp

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- Real device readings ----------

data class DeviceInfo(val batteryPercent: Int, val charging: Boolean, val tempC: Float, val onWifi: Boolean)

fun readDeviceInfo(context: Context): DeviceInfo {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val plugged = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
    return DeviceInfo(pct, plugged, temp, wifi)
}

fun totalRamGb(context: Context): Double {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    return info.totalMem / 1_073_741_824.0
}

/**
 * Rough RAM a model needs once loaded: the file itself, plus a working context cache
 * and the usual Android/app overhead. This scales automatically as models are added
 * to CATALOG, so the list in Models.kt is the only place sizes need to be entered.
 */
fun minRamGbFor(model: CatalogModel): Double = model.sizeGb * 1.5 + 1.0

// ---------- Shared UI pieces ----------

/** Every screen title uses the same serif style as "Zonk-Core". */
@Composable
fun LargeTitle(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        fontSize = 40.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Serif,
    )
}

@Composable
fun InfoCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) { Column(Modifier.padding(14.dp), content = content) }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CheckRow(ok: Boolean, text: String, hint: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (ok) Color(0xFF34C759) else Color(0xFFFF9500),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(text, fontSize = 15.sp)
            if (!ok) Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

// ---------- Host screen ----------

@Composable
fun HostScreen(
    stats: HostStats,
    hostState: HostState,
    /** Why sharing can't start yet (a setting is missing), or null when it can. */
    problem: String?,
    onToggleSharing: () -> Unit,
    sizeKey: String,
    onSizeChange: (String) -> Unit,
    downloader: ModelDownloader,
) {
    val context = LocalContext.current
    var dev by remember { mutableStateOf(readDeviceInfo(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            dev = readDeviceInfo(context)
            delay(3000)
        }
    }
    val ram = remember { totalRamGb(context) }
    val sub = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val green = Color(0xFF34C759)
    val pad = Modifier.padding(horizontal = 16.dp)
    val sharing = hostState is HostState.Connecting || hostState is HostState.Online || hostState is HostState.Retrying
    val online = hostState is HostState.Online

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LargeTitle("Zonk-Core")

        // Status (kept small)
        InfoCard(pad) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (online) green else if (sharing) Color(0xFFFF9500) else sub))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (online) "Sharing" else if (sharing) "Connecting…" else "Not sharing",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when {
                            sharing || hostState is HostState.Failed -> hostStateText(hostState)
                            problem != null -> problem
                            else -> "Ready. Tap Start to share this phone's model through your relay."
                        },
                        fontSize = 12.sp,
                        color = sub,
                    )
                }
                FilledTonalButton(onClick = onToggleSharing, enabled = sharing || problem == null) {
                    Text(if (sharing) "Stop" else "Start")
                }
            }
        }

        // Workload ring
        InfoCard(pad) {
            val w = stats.workload
            val ringColor = when {
                w < 70 -> green
                w < 90 -> Color(0xFFFF9500)
                else -> Color(0xFFFF3B30)
            }
            val track = MaterialTheme.colorScheme.outlineVariant
            val label = when {
                w < 10 -> "Idle"
                w < 40 -> "Light work"
                w < 70 -> "Working moderately"
                else -> "Working hard"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val strokeW = 9.dp.toPx()
                        val inset = strokeW / 2
                        val arc = Size(size.width - strokeW, size.height - strokeW)
                        drawArc(
                            color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                            topLeft = Offset(inset, inset), size = arc, style = Stroke(width = strokeW),
                        )
                        drawArc(
                            color = ringColor, startAngle = -90f, sweepAngle = w / 100f * 360f, useCenter = false,
                            topLeft = Offset(inset, inset), size = arc, style = Stroke(width = strokeW, cap = StrokeCap.Round),
                        )
                    }
                    Text("$w%", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Phone workload", fontSize = 12.sp, color = sub)
                    Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Share of the last minute spent answering", fontSize = 12.sp, color = sub)
                }
            }
        }

        // Model picker — a scrollable row of chips, one per CATALOG entry.
        InfoCard(pad) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Model", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(catalogFor(sizeKey).name, fontSize = 14.sp, color = sub, maxLines = 1)
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CATALOG.forEach { m ->
                    val ok = ram >= minRamGbFor(m)
                    val selected = m.sizeKey == sizeKey
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background)
                            .clickable(enabled = ok) { onSizeChange(m.sizeKey) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            m.sizeKey,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (ok) 1f else 0.3f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Showing models your phone can handle (${"%.1f".format(ram)} GB RAM). Greyed sizes need more memory. Find more in Settings.",
                fontSize = 12.sp, color = sub,
            )
            Spacer(Modifier.height(12.dp))
            ModelRow(catalogFor(sizeKey), downloader)
            Spacer(Modifier.height(6.dp))
            Text("Models download over Wi-Fi only.", fontSize = 12.sp, color = sub)
        }

        // Stats
        val reply = stats.lastReplySec?.let { "%.1f s".format(it) } ?: "—"
        val tempText = "%.0f°C".format(dev.tempC)
        val battText = if (dev.charging) "${dev.batteryPercent}% ⚡" else "${dev.batteryPercent}%"
        Row(pad, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Requests today", "${stats.requestsToday}", Modifier.weight(1f))
            Tile("Last reply", reply, Modifier.weight(1f))
        }
        Row(pad, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Battery", battText, Modifier.weight(1f))
            Tile("Temperature", tempText, Modifier.weight(1f))
        }

        // Checklist
        InfoCard(pad) {
            Text("Task", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            CheckRow(dev.charging, "Plugged in and charging", "Plug in your phone")
            CheckRow(dev.tempC < 38f, "Phone is cool", "Move it somewhere cooler (10–20°C is best)")
            CheckRow(dev.onWifi, "Connected to Wi-Fi", "Connect to Wi-Fi")
        }
        Spacer(Modifier.height(8.dp))
    }
}
