package com.example.aiapp

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

private val STAR = listOf(sparkleShape())

/** Adds each shape (corners as x,y pairs, size 1) to [path] at ([cx], [cy]) scaled by [scale], then fills the path once. */
private fun DrawScope.fillShapes(path: Path, shapes: List<FloatArray>, cx: Float, cy: Float, scale: Float, color: Color) {
    path.reset()
    for (pts in shapes) {
        var i = 0
        while (i < pts.size) {
            val x = cx + pts[i] * scale
            val y = cy + pts[i + 1] * scale
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            i += 2
        }
        path.close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawBats(things: List<Particle>, t: Float, darkPage: Boolean, path: Path) {
    // Dark bats on a light page, soft purple ones on a dark page. See-through, so text stays readable.
    val color = if (darkPage) Color(0xFFB79CFF).copy(alpha = 0.55f) else Color(0xFF2A1740).copy(alpha = 0.65f)
    things.forEachIndexed { i, p ->
        val s = batAt(p, i, t)
        val half = (16f + 20f * p.size) * density   // half the wingspan: 16 to 36 dp
        fillShapes(path, batShapes(s[2]), s[0] * size.width, s[1] * size.height, half, color)
    }
}

private fun DrawScope.drawSparkles(things: List<Particle>, t: Float, darkPage: Boolean, path: Path) {
    val tints = if (darkPage) {
        listOf(Color(0xFFFFFFFF), Color(0xFFFFD66B), Color(0xFFB8E1FF), Color(0xFFFFFFFF))
    } else {
        listOf(Color(0xFFE5A400), Color(0xFFD64550), Color(0xFF2E9B57), Color(0xFF4A90E2))
    }
    things.forEach { p ->
        val s = sparkleAt(p, t)
        val radius = (4f + 8f * p.size) * density * (0.75f + 0.45f * s[2])   // 4 to 12 dp, swells as it shines
        val color = tints[p.tint % tints.size].copy(alpha = 0.15f + 0.75f * s[2])
        fillShapes(path, STAR, s[0] * size.width, s[1] * size.height, radius, color)
    }
}

/**
 * The seasonal animation: bats at Halloween, sparkles at Christmas. It is drawn on top of the screen but never
 * takes touches. If the phone's "remove animations" setting is on, the picture stands still.
 */
@Composable
fun SeasonOverlay(season: Season) {
    if (season == Season.NONE) return
    val context = LocalContext.current
    val still = remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (e: Exception) {
            false
        }
    }
    val darkPage = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val things = remember(season) {
        if (season == Season.HALLOWEEN) makeParticles(7, 31L) else makeParticles(26, 12L)
    }
    var ms by remember { mutableLongStateOf(0L) }
    LaunchedEffect(season, still) {
        if (!still) {
            val start = withFrameNanos { it }
            while (true) {
                withFrameNanos { now -> ms = (now - start) / 1_000_000L }
            }
        }
    }
    val path = remember { Path() }
    Canvas(Modifier.fillMaxSize()) {
        val t = ms / 1000f
        if (season == Season.HALLOWEEN) drawBats(things, t, darkPage, path) else drawSparkles(things, t, darkPage, path)
    }
}
