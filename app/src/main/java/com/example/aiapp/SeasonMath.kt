package com.example.aiapp

import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** A holiday that has its own little animation on top of the app. */
enum class Season { NONE, HALLOWEEN, CHRISTMAS }

/** Halloween: Oct 15 to Nov 1. Christmas: Dec 1 to Jan 6. Any other day: none. */
fun seasonOn(month: Int, day: Int): Season = when {
    month == 10 && day >= 15 -> Season.HALLOWEEN
    month == 11 && day == 1 -> Season.HALLOWEEN
    month == 12 -> Season.CHRISTMAS
    month == 1 && day <= 6 -> Season.CHRISTMAS
    else -> Season.NONE
}

/** The season of today's date on this phone. */
fun currentSeason(): Season {
    val today = LocalDate.now()
    return seasonOn(today.monthValue, today.dayOfMonth)
}

/** One floating thing (a bat or a sparkle). Its starting spot is a fraction of the screen (0 to 1). */
class Particle(
    val x0: Float,
    val y0: Float,
    /** 0 to 1: how big it is. */
    val size: Float,
    /** About 0.6 to 1.4: how fast it moves and flaps. */
    val speed: Float,
    /** Where in its wobble it starts, in radians. */
    val phase: Float,
    /** Picks its colour. */
    val tint: Int,
)

/** Always gives the same things for the same [seed], so the picture doesn't jump when the screen is redrawn. */
fun makeParticles(count: Int, seed: Long): List<Particle> {
    val r = java.util.Random(seed)
    return List(count) {
        Particle(
            x0 = r.nextFloat(),
            y0 = r.nextFloat(),
            size = r.nextFloat(),
            speed = 0.6f + r.nextFloat() * 0.8f,
            phase = r.nextFloat() * (2f * PI.toFloat()),
            tint = r.nextInt(4),
        )
    }
}

private fun frac(v: Float): Float = v - floor(v)

/**
 * Where bat number [i] is [t] seconds after the start: x and y as fractions of the screen, and the wing flap
 * from -1 to 1. Bats drift across the screen (odd ones the other way round) and come in again from the side.
 * x runs a little past both edges, so a bat is out of sight when it turns around.
 */
fun batAt(p: Particle, i: Int, t: Float): FloatArray {
    val dir = if (i % 2 == 0) 1f else -1f
    val u = frac(p.x0 + dir * p.speed * t * 0.09f)
    val x = -0.12f + u * 1.24f
    val y = 0.06f + p.y0 * 0.70f + 0.03f * sin(t * 1.3f * p.speed + p.phase)
    val flap = sin(t * (12f + 5f * p.speed) + p.phase)
    return floatArrayOf(x, y, flap)
}

/**
 * Where a sparkle is [t] seconds after the start: x and y as fractions of the screen, and how brightly it
 * shines right now (0 to 1). Sparkles sink slowly, twinkle, and fade out near the top and bottom edge.
 */
fun sparkleAt(p: Particle, t: Float): FloatArray {
    val x = (p.x0 + 0.02f * sin(t * 0.3f * p.speed + p.phase)).coerceIn(0f, 1f)
    val y = frac(p.y0 + t * 0.012f * p.speed)
    val twinkle = 0.5f + 0.5f * sin(t * (1.6f + p.speed) + p.phase)
    val edge = minOf(1f, minOf(y, 1f - y) * 8f)
    return floatArrayOf(x, y, twinkle * edge)
}

// ---------- Shapes (all as x,y pairs; half the wingspan / the radius is 1) ----------

private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, n: Int): FloatArray {
    val pts = FloatArray(n * 2)
    for (k in 0 until n) {
        val a = 2.0 * PI * k / n
        pts[2 * k] = cx + rx * cos(a).toFloat()
        pts[2 * k + 1] = cy + ry * sin(a).toFloat()
    }
    return pts
}

/**
 * The right wing of a bat. [flap] 1 lifts the wing tip, -1 lowers it. The left wing is this one mirrored.
 * The back edge has three points, like a real bat's wing.
 */
fun batWing(flap: Float): FloatArray {
    val base = floatArrayOf(
        0.06f, -0.06f, 0.50f, -0.38f, 1.00f, -0.20f, 0.82f, 0.02f, 0.78f, 0.22f,
        0.58f, 0.06f, 0.52f, 0.28f, 0.32f, 0.10f, 0.24f, 0.30f, 0.08f, 0.16f,
    )
    val out = FloatArray(base.size)
    var i = 0
    while (i < base.size) {
        out[i] = base[i]
        out[i + 1] = base[i + 1] - flap * 0.55f * base[i]   // the further from the body, the more it moves
        i += 2
    }
    return out
}

/** The body, the head and the two ears of a bat, as separate shapes. */
fun batBody(): List<FloatArray> = listOf(
    ellipse(0f, 0.06f, 0.12f, 0.26f, 14),
    ellipse(0f, -0.24f, 0.10f, 0.10f, 12),
    floatArrayOf(-0.09f, -0.27f, -0.085f, -0.45f, -0.02f, -0.31f),
    floatArrayOf(0.09f, -0.27f, 0.085f, -0.45f, 0.02f, -0.31f),
)

/** The area of a shape; the sign tells which way round its corners are listed. */
fun signedArea(p: FloatArray): Float {
    val n = p.size / 2
    var a = 0f
    for (k in 0 until n) {
        val j = (k + 1) % n
        a += p[2 * k] * p[2 * j + 1] - p[2 * j] * p[2 * k + 1]
    }
    return a / 2f
}

/** The same shape with its corners listed the other way round, if needed so that [signedArea] is positive. */
private fun cornersOneWay(p: FloatArray): FloatArray {
    if (signedArea(p) >= 0f) return p
    val n = p.size / 2
    val out = FloatArray(p.size)
    for (k in 0 until n) {
        out[2 * k] = p[2 * (n - 1 - k)]
        out[2 * k + 1] = p[2 * (n - 1 - k) + 1]
    }
    return out
}

/**
 * A whole bat as shapes to fill: both wings, body, head and ears. Every shape lists its corners the same way
 * round, so when they are all added to ONE path and filled once, overlaps merge instead of cancelling out
 * (and a see-through bat doesn't get darker patches where its parts overlap).
 */
fun batShapes(flap: Float): List<FloatArray> {
    val right = batWing(flap)
    val left = FloatArray(right.size) { i -> if (i % 2 == 0) -right[i] else right[i] }
    return (listOf(right, left) + batBody()).map { cornersOneWay(it) }
}

/** A four-pointed sparkle with a thin waist: points at the top, right, bottom and left. */
fun sparkleShape(dent: Float = 0.22f): FloatArray {
    val pts = FloatArray(16)
    for (k in 0 until 8) {
        val a = (k * 45.0 - 90.0) * PI / 180.0
        val r = if (k % 2 == 0) 1f else dent
        pts[2 * k] = (r * cos(a)).toFloat()
        pts[2 * k + 1] = (r * sin(a)).toFloat()
    }
    return pts
}
