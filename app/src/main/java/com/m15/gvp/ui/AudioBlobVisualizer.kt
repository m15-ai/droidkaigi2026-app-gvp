package com.m15.gvp.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.*
import androidx.compose.material3.MaterialTheme

@Composable
fun AudioBlobVisualizer(
    level: Float,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    accent2: Color = Color(0xFF666666),
) {
    var heldPeak by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(level) {
        heldPeak = max(level, heldPeak * 0.92f)
    }
    val smooth by animateFloatAsState(
        targetValue = (level * 0.75f + heldPeak * 0.25f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 35, easing = LinearEasing)
    )

    val t = rememberInfiniteTransition(label = "blobTime")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
    val shimmer by t.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer"
    )

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val center = Offset(cx, cy)

        val minDim = size.minDimension
        val baseR = minDim * 0.286f
        val punch = (smooth * smooth)
        val r = baseR * (1f + 1.15f * punch)

        // Subtle background mist
        drawMist(center, minDim, accent, accent2, shimmer)

        // Glow core
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accent.copy(alpha = 0.20f + 0.20f * punch),
                    accent2.copy(alpha = 0.08f + 0.12f * punch),
                    Color.Transparent
                ),
                center = center,
                radius = r * 2.1f
            ),
            radius = r * 2.1f,
            center = center
        )

        // 3-layer blob
        drawBlobLayer(
            center = center,
            radius = r * 1.00f,
            phase = phase,
            level = smooth,
            colorA = accent,
            colorB = accent2,
            strokeWidth = minDim * (0.010f + 0.008f * punch),
            alpha = 0.85f
        )

        drawBlobFill(
            center = center,
            radius = r * 0.86f,
            phase = phase + 0.9f,
            level = smooth,
            colorA = accent2,
            colorB = accent,
            alpha = 0.22f + 0.18f * punch
        )

        drawHighlightRing(
            center = center,
            radius = r * 1.06f,
            phase = phase + 1.7f,
            level = smooth,
            color = Color.White.copy(alpha = 0.10f + 0.12f * punch),
            strokeWidth = minDim * 0.0065f
        )

        // Sparks on louder moments
        if (smooth > 0.28f) {
            drawSparks(center, r, phase, smooth, accent2)
        }
    }
}

private fun DrawScope.drawMist(center: Offset, minDim: Float, a: Color, b: Color, shimmer: Float) {
    val fogR = minDim * 0.62f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                a.copy(alpha = 0.04f * shimmer),
                b.copy(alpha = 0.03f * shimmer),
                Color.Transparent
            ),
            center = center,
            radius = fogR
        ),
        radius = fogR,
        center = center
    )
}

private fun DrawScope.drawBlobLayer(
    center: Offset,
    radius: Float,
    phase: Float,
    level: Float,
    colorA: Color,
    colorB: Color,
    strokeWidth: Float,
    alpha: Float
) {
    val path = blobPath(center, radius, phase, level, points = 140, wobbleStrength = 0.10f + 0.22f * level)

    drawPath(
        path = path,
        brush = Brush.linearGradient(
            colors = listOf(
                colorA.copy(alpha = alpha),
                colorB.copy(alpha = alpha),
                colorA.copy(alpha = alpha)
            ),
            start = Offset(center.x - radius * 1.6f, center.y - radius * 1.2f),
            end = Offset(center.x + radius * 1.6f, center.y + radius * 1.2f)
        ),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    drawPath(
        path = path,
        color = colorA.copy(alpha = 0.12f + 0.10f * level),
        style = Stroke(width = strokeWidth * 2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

private fun DrawScope.drawBlobFill(
    center: Offset,
    radius: Float,
    phase: Float,
    level: Float,
    colorA: Color,
    colorB: Color,
    alpha: Float
) {
    val path = blobPath(center, radius, phase, level, points = 120, wobbleStrength = 0.06f + 0.18f * level)
    drawPath(
        path = path,
        brush = Brush.radialGradient(
            colors = listOf(
                colorA.copy(alpha = alpha),
                colorB.copy(alpha = alpha * 0.75f),
                Color.Transparent
            ),
            center = center,
            radius = radius * 1.8f
        )
    )
}

private fun DrawScope.drawHighlightRing(
    center: Offset,
    radius: Float,
    phase: Float,
    level: Float,
    color: Color,
    strokeWidth: Float
) {
    val path = blobPath(center, radius, phase, level, points = 110, wobbleStrength = 0.03f + 0.09f * level)
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

private fun blobPath(
    center: Offset,
    radius: Float,
    phase: Float,
    level: Float,
    points: Int,
    wobbleStrength: Float
): Path {
    val path = Path()
    val cx = center.x
    val cy = center.y

    val k1 = 3.0f
    val k2 = 5.0f
    val k3 = 7.0f

    for (i in 0..points) {
        val t = (i / points.toFloat()) * (2f * PI.toFloat())
        val wobble =
            sin(t * k1 + phase) * 0.55f +
                    sin(t * k2 - phase * 1.3f) * 0.30f +
                    sin(t * k3 + phase * 0.7f) * 0.15f

        val rr = radius * (1f + wobbleStrength * wobble * (0.55f + 0.45f * level))

        val x = cx + rr * cos(t)
        val y = cy + rr * sin(t)

        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

private fun DrawScope.drawSparks(center: Offset, r: Float, phase: Float, level: Float, color: Color) {
    val count = (6 + (level * 14)).toInt().coerceAtMost(20)
    val sparkR = r * (1.05f + 0.55f * level)

    for (i in 0 until count) {
        val a = phase * 1.8f + i * 0.55f
        val jitter = 0.12f * sin(phase * 6f + i)
        val p = Offset(
            center.x + sparkR * cos(a) * (1f + jitter),
            center.y + sparkR * sin(a) * (1f + jitter)
        )

        val len = (r * 0.06f) * (0.6f + level)
        val q = Offset(
            p.x + len * cos(a + 0.8f),
            p.y + len * sin(a + 0.8f)
        )

        drawLine(
            color = color.copy(alpha = (0.10f + 0.35f * level).coerceIn(0f, 1f)),
            start = p,
            end = q,
            strokeWidth = (1.2f + 2.8f * level)
        )
    }
}
