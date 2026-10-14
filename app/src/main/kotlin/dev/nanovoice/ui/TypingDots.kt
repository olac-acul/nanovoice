package dev.nanovoice.ui

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Three dots that swell in turn: the assistant is writing. They stay still if the system animations are off. */
@Composable
fun TypingDots(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animate = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
    val transition = rememberInfiniteTransition(label = "typing")
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..2) {
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    keyframes {
                        durationMillis = 1200
                        0f at 0 + i * 150
                        1f at 300 + i * 150
                        0f at 600 + i * 150
                    },
                    RepeatMode.Restart,
                ),
                label = "dot$i",
            )
            val p = if (animate) phase else 0.5f
            Surface(
                modifier = Modifier.size(8.dp).graphicsLayer { scaleX = 0.75f + 0.45f * p; scaleY = 0.75f + 0.45f * p; alpha = 0.45f + 0.55f * p },
                shape = CircleShape,
                color = color,
            ) {}
        }
    }
}

/** The assistant's bubble while its reply has not started: only the dots. */
@Composable
fun TypingBubble(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) { TypingDots(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) }
}
