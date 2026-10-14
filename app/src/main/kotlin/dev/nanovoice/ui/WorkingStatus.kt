package dev.nanovoice.ui

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** What the agent is doing ("Cerco sul Web..."): a free line, not a message: a small orb breathes and the text colours flow. */
@Composable
fun WorkingStatus(text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animate = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
    val scheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "working")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = PERIOD,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "shift",
    )
    // Mirrored tiles repeat every 2 x WIDTH, so moving by exactly that distance loops without a jump.
    val brush = if (animate) {
        Brush.linearGradient(
            colors = listOf(scheme.primary, scheme.secondary, scheme.primary),
            start = Offset(shift, 0f),
            end = Offset(shift + WIDTH, 0f),
            tileMode = TileMode.Mirror,
        )
    } else {
        Brush.linearGradient(listOf(scheme.primary, scheme.primary))
    }
    val (light, mid, deep) = MaterialTheme.extra.orb
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val p = if (animate) pulse else 0f
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // A tiny orb, the same one as the microphone, breathing next to the text.
        Canvas(Modifier.size(24.dp)) {
            val c = center
            val core = size.minDimension * (0.28f + 0.07f * p)
            drawCircle(light.copy(alpha = 0.30f * (1f - p)), radius = core + size.minDimension * 0.2f * p + size.minDimension * 0.08f, center = c)
            drawCircle(
                Brush.radialGradient(0f to light, 0.55f to mid, 1f to deep, center = Offset(c.x - core * 0.35f, c.y - core * 0.4f), radius = core * 1.7f),
                radius = core,
                center = c,
            )
        }
        // The dots the agent typed are replaced by animated ones.
        val label = text.trimEnd('.', '\u2026', ' ')
        val style = MaterialTheme.typography.bodyLarge.merge(TextStyle(brush = brush, fontWeight = FontWeight.SemiBold))
        val dotStyle = MaterialTheme.typography.bodyLarge.merge(TextStyle(color = scheme.primary, fontWeight = FontWeight.SemiBold))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = style)
            for (i in 0..2) {
                val dot by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        keyframes {
                            durationMillis = 1200
                            0.2f at 0 + i * 180
                            1f at 300 + i * 180
                            0.2f at 600 + i * 180
                        },
                        RepeatMode.Restart,
                    ),
                    label = "dot$i",
                )
                Text(".", style = dotStyle, modifier = Modifier.graphicsLayer { alpha = if (animate) dot else 1f })
            }
        }
    }
}

private const val WIDTH = 300f
private const val PERIOD = WIDTH * 2
