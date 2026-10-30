package dev.nanovoice.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Small line icons drawn by hand, so the app does not need the large material-icons dependency. */
object Icons {
    @Composable
    fun Back(color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        val p = Path().apply {
            moveTo(w * 0.62f, w * 0.2f); lineTo(w * 0.3f, w * 0.5f); lineTo(w * 0.62f, w * 0.8f)
        }
        drawPath(p, color, style = Stroke(w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    @Composable
    fun Chevron(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        val p = Path().apply {
            moveTo(w * 0.38f, w * 0.2f); lineTo(w * 0.7f, w * 0.5f); lineTo(w * 0.38f, w * 0.8f)
        }
        drawPath(p, color, style = Stroke(w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    @Composable
    fun Settings(color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.085f
        for (i in 0..2) {
            val y = w * (0.26f + 0.24f * i)
            val knob = w * (if (i == 1) 0.68f else 0.34f)
            drawLine(color, Offset(w * 0.15f, y), Offset(w * 0.85f, y), sw, StrokeCap.Round)
            drawCircle(color, radius = w * 0.095f, center = Offset(knob, y))
        }
    }

    /** A flame: "start fresh", for the new conversation button. */
    @Composable
    fun Flame(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        fun Path.c(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = cubicTo(x1 * w, y1 * w, x2 * w, y2 * w, x3 * w, y3 * w)
        val outer = Path().apply {
            moveTo(0.5f * w, 0.92f * w)
            c(0.27f, 0.92f, 0.18f, 0.74f, 0.25f, 0.58f)
            c(0.31f, 0.44f, 0.44f, 0.36f, 0.44f, 0.10f)
            c(0.64f, 0.24f, 0.83f, 0.44f, 0.80f, 0.66f)
            c(0.78f, 0.83f, 0.66f, 0.92f, 0.5f, 0.92f)
            close()
        }
        val inner = Path().apply {
            moveTo(0.5f * w, 0.84f * w)
            c(0.41f, 0.84f, 0.37f, 0.76f, 0.41f, 0.68f)
            c(0.45f, 0.61f, 0.51f, 0.58f, 0.52f, 0.49f)
            c(0.60f, 0.56f, 0.64f, 0.65f, 0.62f, 0.73f)
            c(0.60f, 0.80f, 0.56f, 0.84f, 0.5f, 0.84f)
            close()
        }
        val stroke = Stroke(w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(outer, color, style = stroke)
        drawPath(inner, color, style = stroke)
    }

    @Composable
    fun Stop(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        drawRoundRect(color, topLeft = Offset(w * 0.15f, w * 0.15f), size = androidx.compose.ui.geometry.Size(w * 0.7f, w * 0.7f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f))
    }

    /** A camera: attach a photo. */
    @Composable
    fun Camera(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        val stroke = Stroke(w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val body = Path().apply {
            moveTo(w * 0.14f, w * 0.34f)
            lineTo(w * 0.32f, w * 0.34f); lineTo(w * 0.39f, w * 0.22f); lineTo(w * 0.61f, w * 0.22f); lineTo(w * 0.68f, w * 0.34f)
            lineTo(w * 0.86f, w * 0.34f); lineTo(w * 0.86f, w * 0.80f); lineTo(w * 0.14f, w * 0.80f)
            close()
        }
        drawPath(body, color, style = stroke)
        drawCircle(color, radius = w * 0.14f, center = Offset(w * 0.5f, w * 0.56f), style = stroke)
    }

    /** A picture with a mountain and the sun: pick from the gallery. */
    @Composable
    fun Gallery(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        val stroke = Stroke(w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.14f, w * 0.18f),
            size = androidx.compose.ui.geometry.Size(w * 0.72f, w * 0.64f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.1f),
            style = stroke,
        )
        val hills = Path().apply {
            moveTo(w * 0.2f, w * 0.72f); lineTo(w * 0.42f, w * 0.48f); lineTo(w * 0.58f, w * 0.64f); lineTo(w * 0.66f, w * 0.56f); lineTo(w * 0.8f, w * 0.72f)
        }
        drawPath(hills, color, style = stroke)
        drawCircle(color, radius = w * 0.06f, center = Offset(w * 0.66f, w * 0.36f))
    }

    /** Barcode bars: scan a product code. */
    @Composable
    fun Barcode(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        // x position and width of each bar, as in a real code: thin and thick mixed.
        val bars = listOf(0.16f to 0.05f, 0.25f to 0.09f, 0.38f to 0.04f, 0.46f to 0.07f, 0.58f to 0.04f, 0.66f to 0.09f, 0.79f to 0.05f)
        for ((x, bw) in bars) {
            drawRect(color, topLeft = Offset(w * x, w * 0.24f), size = androidx.compose.ui.geometry.Size(w * bw, w * 0.52f))
        }
    }

    /** A magnifying glass: search. */
    @Composable
    fun Search(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        val stroke = Stroke(w * 0.09f, cap = StrokeCap.Round)
        drawCircle(color, radius = w * 0.24f, center = Offset(w * 0.44f, w * 0.44f), style = stroke)
        drawLine(color, Offset(w * 0.62f, w * 0.62f), Offset(w * 0.82f, w * 0.82f), w * 0.09f, StrokeCap.Round)
    }

    /** A keyboard: write instead of speaking. */
    @Composable
    fun Keyboard(color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.1f, w * 0.24f),
            size = androidx.compose.ui.geometry.Size(w * 0.8f, w * 0.52f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
            style = Stroke(w * 0.075f),
        )
        val key = w * 0.045f
        for (row in 0..1) for (col in 0..3) {
            drawCircle(color, radius = key, center = Offset(w * (0.29f + 0.14f * col), w * (0.39f + 0.11f * row)))
        }
        drawLine(color, Offset(w * 0.36f, w * 0.62f), Offset(w * 0.64f, w * 0.62f), w * 0.075f, StrokeCap.Round)
    }

    /** A cross: remove. */
    @Composable
    fun Close(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.12f
        drawLine(color, Offset(w * 0.22f, w * 0.22f), Offset(w * 0.78f, w * 0.78f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.78f, w * 0.22f), Offset(w * 0.22f, w * 0.78f), sw, StrokeCap.Round)
    }

    @Composable
    fun Send(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        val p = Path().apply {
            moveTo(w * 0.5f, w * 0.82f); lineTo(w * 0.5f, w * 0.2f)
            moveTo(w * 0.24f, w * 0.46f); lineTo(w * 0.5f, w * 0.2f); lineTo(w * 0.76f, w * 0.46f)
        }
        drawPath(p, color, style = Stroke(w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    private fun androidx.compose.ui.graphics.drawscope.DrawScope.line(color: Color, w: Float, vararg points: Float) {
        val p = Path().apply {
            moveTo(points[0] * w, points[1] * w)
            for (i in 2 until points.size step 2) lineTo(points[i] * w, points[i + 1] * w)
        }
        drawPath(p, color, style = Stroke(w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    private fun androidx.compose.ui.graphics.drawscope.DrawScope.box(color: Color, w: Float, x: Float, y: Float, bw: Float, bh: Float) =
        drawRoundRect(
            color,
            topLeft = Offset(w * x, w * y),
            size = androidx.compose.ui.geometry.Size(w * bw, w * bh),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.07f),
            style = Stroke(w * 0.085f),
        )

    /** Two sheets: copy. */
    @Composable
    fun Copy(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        box(color, w, 0.32f, 0.3f, 0.48f, 0.52f)
        line(color, w, 0.2f, 0.68f, 0.2f, 0.18f, 0.6f, 0.18f)
    }

    /** A curved arrow back: reply. */
    @Composable
    fun Reply(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        line(color, w, 0.4f, 0.24f, 0.16f, 0.46f, 0.4f, 0.68f)
        val p = Path().apply { moveTo(0.18f * w, 0.46f * w); cubicTo(0.6f * w, 0.46f * w, 0.84f * w, 0.52f * w, 0.84f * w, 0.8f * w) }
        drawPath(p, color, style = Stroke(w * 0.085f, cap = StrokeCap.Round))
    }

    /** Three connected dots: share. */
    @Composable
    fun Share(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        val r = w * 0.1f
        listOf(Offset(0.72f * w, 0.22f * w), Offset(0.26f * w, 0.5f * w), Offset(0.72f * w, 0.78f * w)).forEach { drawCircle(color, r, it, style = Stroke(w * 0.08f)) }
        drawLine(color, Offset(0.35f * w, 0.45f * w), Offset(0.63f * w, 0.28f * w), w * 0.08f, StrokeCap.Round)
        drawLine(color, Offset(0.35f * w, 0.55f * w), Offset(0.63f * w, 0.72f * w), w * 0.08f, StrokeCap.Round)
    }

    /** A loudspeaker: read aloud. */
    @Composable
    fun Speaker(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        line(color, w, 0.14f, 0.4f, 0.3f, 0.4f, 0.5f, 0.22f, 0.5f, 0.78f, 0.3f, 0.6f, 0.14f, 0.6f, 0.14f, 0.4f)
        drawArc(color, -45f, 90f, false, Offset(0.42f * w, 0.32f * w), androidx.compose.ui.geometry.Size(0.36f * w, 0.36f * w), style = Stroke(w * 0.08f, cap = StrokeCap.Round))
    }

    /** A microphone: say it instead of typing it. */
    @Composable
    fun Mic(color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        val stroke = Stroke(w * 0.085f, cap = StrokeCap.Round)
        drawRoundRect(color, Offset(0.38f * w, 0.12f * w), androidx.compose.ui.geometry.Size(0.24f * w, 0.42f * w), androidx.compose.ui.geometry.CornerRadius(0.12f * w), style = stroke)
        drawArc(color, 0f, 180f, false, Offset(0.26f * w, 0.28f * w), androidx.compose.ui.geometry.Size(0.48f * w, 0.42f * w), style = stroke)
        line(color, w, 0.5f, 0.7f, 0.5f, 0.86f)
    }

    /** A circular arrow: try again. */
    @Composable
    fun Retry(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        drawArc(color, -60f, 290f, false, Offset(0.2f * w, 0.2f * w), androidx.compose.ui.geometry.Size(0.6f * w, 0.6f * w), style = Stroke(w * 0.085f, cap = StrokeCap.Round))
        line(color, w, 0.6f, 0.12f, 0.68f, 0.26f, 0.52f, 0.32f)
    }

    /** Two chain links: a web address. */
    @Composable
    fun Link(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        rotate(-45f) {
            drawRoundRect(color, Offset(0.1f * w, 0.36f * w), androidx.compose.ui.geometry.Size(0.42f * w, 0.28f * w), androidx.compose.ui.geometry.CornerRadius(0.14f * w), style = Stroke(w * 0.085f))
            drawRoundRect(color, Offset(0.48f * w, 0.36f * w), androidx.compose.ui.geometry.Size(0.42f * w, 0.28f * w), androidx.compose.ui.geometry.CornerRadius(0.14f * w), style = Stroke(w * 0.085f))
        }
    }

    /** A text cursor with handles: select part of the text. */
    @Composable
    fun Select(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        line(color, w, 0.5f, 0.18f, 0.5f, 0.82f)
        line(color, w, 0.36f, 0.18f, 0.64f, 0.18f)
        line(color, w, 0.36f, 0.82f, 0.64f, 0.82f)
    }

    /** A question mark drawn with the same stroke as the other icons: what can be asked. */
    @Composable
    fun Help(color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        val p = Path().apply {
            arcTo(androidx.compose.ui.geometry.Rect(w * 0.35f, w * 0.18f, w * 0.65f, w * 0.48f), 180f, 225f, true)
            lineTo(w * 0.5f, w * 0.56f)
            lineTo(w * 0.5f, w * 0.63f)
        }
        drawPath(p, color, style = Stroke(w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, radius = w * 0.06f, center = Offset(w * 0.5f, w * 0.79f))
    }

    /** A phone: what the app does on it by itself. */
    @Composable
    fun Phone(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        val stroke = Stroke(w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.28f, w * 0.12f),
            size = androidx.compose.ui.geometry.Size(w * 0.44f, w * 0.76f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.1f),
            style = stroke,
        )
        line(color, w, 0.44f, 0.76f, 0.56f, 0.76f)
    }

    /** A cloud: what goes to the server. */
    @Composable
    fun Cloud(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        fun box(cx: Float, cy: Float, r: Float) = androidx.compose.ui.geometry.Rect(w * (cx - r), w * (cy - r), w * (cx + r), w * (cy + r))
        val p = Path().apply {
            moveTo(w * 0.28f, w * 0.72f)
            lineTo(w * 0.74f, w * 0.72f)
            arcTo(box(0.74f, 0.58f, 0.14f), 90f, -180f, false)
            arcTo(box(0.5f, 0.47f, 0.22f), -5f, -170f, false)
            arcTo(box(0.28f, 0.6f, 0.12f), -90f, -180f, false)
            close()
        }
        drawPath(p, color, style = Stroke(w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    /** An arrow down: to the latest message. */
    @Composable
    fun Down(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) = Canvas(modifier.size(size)) {
        val w = this.size.width
        line(color, w, 0.5f, 0.2f, 0.5f, 0.78f)
        line(color, w, 0.26f, 0.54f, 0.5f, 0.78f, 0.74f, 0.54f)
    }
}
