package dev.nanovoice.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class OrbState { Idle, Listening, Thinking, Speaking }

/** The microphone: a gradient orb taken from the app icon, with a different motion for each assistant state. */
@Composable
fun VoiceOrb(state: OrbState, modifier: Modifier = Modifier, size: Dp = 88.dp) {
    val (light, mid, deep) = MaterialTheme.extra.orb
    val transition = rememberInfiniteTransition(label = "orb")
    val pulseMs = if (state == OrbState.Speaking) 700 else 1100
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(pulseMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val turn by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "turn",
    )
    Canvas(modifier = modifier.size(size)) {
        val c = center
        val r = this.size.minDimension / 2f
        val core = when (state) {
            OrbState.Idle -> 0.62f + 0.02f * pulse
            OrbState.Listening -> 0.58f + 0.10f * pulse
            OrbState.Thinking -> 0.50f
            OrbState.Speaking -> 0.60f + 0.06f * pulse
        } * r
        val orb = Brush.radialGradient(
            0f to light, 0.55f to mid, 1f to deep,
            center = Offset(c.x - core * 0.35f, c.y - core * 0.4f), radius = core * 1.7f,
        )
        if (state == OrbState.Listening || state == OrbState.Speaking) {
            for (i in 1..2) {
                val ring = core + (r - core) * (i * 0.5f) * (0.6f + 0.4f * pulse)
                drawCircle(light.copy(alpha = (0.28f - 0.10f * i) * (1f - 0.4f * pulse)), radius = ring, center = c)
            }
        }
        if (state == OrbState.Thinking) {
            val stroke = r * 0.13f
            val arcR = r * 0.80f
            rotate(turn, pivot = c) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(Color.Transparent, light, mid, deep, Color.Transparent), center = c),
                    startAngle = 0f, sweepAngle = 300f, useCenter = false,
                    topLeft = Offset(c.x - arcR, c.y - arcR), size = Size(arcR * 2, arcR * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        drawCircle(orb, radius = core, center = c, alpha = if (state == OrbState.Idle) 0.9f else 1f)
        // Soft highlight, like light on glass: it fades out instead of sitting on the orb as a spot.
        val glow = Offset(c.x - core * 0.35f, c.y - core * 0.4f)
        val glowR = core * 0.45f
        drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = 0.32f), 1f to Color.Transparent, center = glow, radius = glowR), radius = glowR, center = glow)
    }
}
