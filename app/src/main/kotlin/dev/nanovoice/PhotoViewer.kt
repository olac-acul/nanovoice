package dev.nanovoice

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.nanovoice.agent.AgentImage
import dev.nanovoice.ui.Icons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A photo on the whole screen, to check it is the right one or read a detail: two fingers zoom and move it, a double
 * tap goes back to the whole photo. It shows the copy that was (or will be) sent, sharper than the chat's thumbnail,
 * once decoded. [onSearch], when given, is the reverse image search, a separate step the person chooses. Without
 * [full] (the app keeps the photos themselves for the latest few messages only, and not across a restart) it says the
 * thumbnail is all there is, and offers [onAttachAgain].
 */
@Composable
fun PhotoViewer(
    preview: ImageBitmap,
    full: AgentImage?,
    onSearch: (() -> Unit)?,
    onDismiss: () -> Unit,
    onAttachAgain: (() -> Unit)? = null,
) {
    var shown by remember { mutableStateOf(preview) }
    LaunchedEffect(full) {
        val bytes = full?.bytes ?: return@LaunchedEffect
        withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }?.let { shown = it }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val closeLabel = stringResource(R.string.assistant_close)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(
                shown,
                contentDescription = stringResource(R.string.photo_sent),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        })
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
            Box(
                modifier = Modifier
                    .safeDrawingPadding()
                    .padding(12.dp)
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    .semantics { contentDescription = closeLabel }
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .align(Alignment.TopStart),
                contentAlignment = Alignment.Center,
            ) { Icons.Close(Color.White) }
            // Zoom without two fingers (a screen reader, one hand): the photo grows around its centre.
            Row(
                modifier = Modifier.safeDrawingPadding().padding(12.dp).align(Alignment.TopEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZoomButton(stringResource(R.string.photo_zoom_out), "−") {
                    scale = (scale / ZOOM_STEP).coerceAtLeast(1f)
                    offset = if (scale == 1f) Offset.Zero else offset / ZOOM_STEP
                }
                ZoomButton(stringResource(R.string.photo_zoom_in), "+") {
                    scale = (scale * ZOOM_STEP).coerceAtMost(MAX_ZOOM)
                }
            }
            if (onSearch != null) {
                Button(
                    onClick = onSearch,
                    modifier = Modifier.safeDrawingPadding().padding(bottom = 24.dp).align(Alignment.BottomCenter),
                ) { Text(stringResource(R.string.search_tineye)) }
            } else if (full == null) {
                Column(
                    modifier = Modifier
                        .safeDrawingPadding()
                        .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                        .padding(12.dp)
                        .align(Alignment.BottomCenter),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.photo_preview_only), color = Color.White, textAlign = TextAlign.Center)
                    if (onAttachAgain != null) Button(onClick = onAttachAgain) { Text(stringResource(R.string.draft_attach_again)) }
                }
            }
        }
    }
}

@Composable
private fun ZoomButton(label: String, sign: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(sign, color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.titleLarge) }
}

private const val MAX_ZOOM = 6f
private const val ZOOM_STEP = 1.5f
