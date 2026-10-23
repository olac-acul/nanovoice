package dev.nanovoice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.nanovoice.actions.photo.ProductKind
import dev.nanovoice.ui.Icons
import dev.nanovoice.ui.NanoTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live barcode scanner for products: the camera preview, read frame by frame on the phone (ZXing), closes on the
 * first product code and returns its digits. No picture is kept or sent. Needs the camera permission, asked before.
 */
class BarcodeScanActivity : ComponentActivity() {
    private lateinit var analysis: ExecutorService
    private val found = AtomicBoolean(false)
    private val frames = AtomicInteger(0)
    private var camera: Camera? = null
    private val torch = mutableStateOf(false)
    /** Reads a QR code (the pairing code of the panel) instead of a product barcode. */
    private val qr by lazy { intent.getBooleanExtra(EXTRA_QR, false) }
    /** The kind of product the person chose: the title and the database named in the hint. */
    private val kind by lazy { ProductKind.byId(intent.getStringExtra(EXTRA_KIND)) ?: ProductKind.Food }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        analysis = Executors.newSingleThreadExecutor()
        val container = (application as NanoVoiceApp).container
        setContent {
            val settings by container.settingsStore.settings.collectAsState()
            NanoTheme(settings.themeMode, settings.dynamicColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(factory = { context -> PreviewView(context).also { start(it) } }, modifier = Modifier.fillMaxSize())
                        // Where to hold the code: a wide frame for a barcode, a square one for a QR code.
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .then(if (qr) Modifier.size(260.dp) else Modifier.fillMaxWidth(0.8f).height(150.dp))
                                .border(BorderStroke(3.dp, Color.White.copy(alpha = 0.85f)), RoundedCornerShape(20.dp)),
                        )
                        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(
                                    modifier = Modifier.size(44.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).clickable { finish() },
                                    contentAlignment = Alignment.Center,
                                ) { Icons.Close(Color.White) }
                                Text(
                                    if (qr) stringResource(R.string.scan_qr_title) else stringResource(R.string.scan_title_kind, stringResource(kind.label)),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = ::toggleTorch) {
                                    Text(stringResource(if (torch.value) R.string.scan_torch_off else R.string.scan_torch_on), color = Color.White)
                                }
                            }
                            Box(modifier = Modifier.weight(1f))
                            // Over the camera image: light text on a dark veil, like the buttons above, in any theme.
                            Surface(
                                shape = MaterialTheme.shapes.extraLarge,
                                color = Color.Black.copy(alpha = 0.6f),
                                contentColor = Color.White,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (qr) stringResource(R.string.scan_qr_hint) else scanHint(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Where the number goes, and for the smaller databases that some products are missing. */
    @Composable
    private fun scanHint(): String {
        val hint = stringResource(R.string.scan_hint, kind.service)
        val small = kind != ProductKind.Food && kind != ProductKind.Book
        return if (small) hint + " " + stringResource(R.string.scan_hint_small, kind.service) else hint
    }

    private fun start(view: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrElse { finish(); return@addListener }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            // Thin bars need pixels: about 720p, the newest frame only.
            val selector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val reader = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            reader.setAnalyzer(analysis) { image ->
                image.use {
                    if (found.get()) return@use
                    val plane = it.planes[0]
                    val luma = ByteArray(plane.buffer.remaining()).also { bytes -> plane.buffer.get(bytes) }
                    // Every other frame turned: the code may be held level or upright.
                    val code = if (qr) Barcodes.readQr(luma, plane.rowStride, it.width, it.height)
                    else Barcodes.read(luma, plane.rowStride, it.width, it.height, sideways = frames.incrementAndGet() % 2 == 0)
                    if (code != null && found.compareAndSet(false, true)) runOnUiThread { deliver(code) }
                }
            }
            runCatching {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, reader)
            }.onFailure { finish() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        val c = camera ?: return
        if (!c.cameraInfo.hasFlashUnit()) return
        torch.value = !torch.value
        c.cameraControl.enableTorch(torch.value)
    }

    private fun deliver(code: String) {
        runCatching {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_CODE, code))
        finish()
    }

    override fun onDestroy() {
        analysis.shutdown()
        super.onDestroy()
    }

    /** Opens the scanner for a kind of product; the result is the code's digits, or null when it was closed. */
    class Scan : ActivityResultContract<ProductKind, String?>() {
        override fun createIntent(context: Context, input: ProductKind) =
            Intent(context, BarcodeScanActivity::class.java).putExtra(EXTRA_KIND, input.id)
        override fun parseResult(resultCode: Int, intent: Intent?): String? =
            intent?.getStringExtra(EXTRA_CODE)?.takeIf { resultCode == Activity.RESULT_OK }
    }

    /** Opens the scanner for a QR code; the result is its text, or null when it was closed. */
    class ScanQr : ActivityResultContract<Unit, String?>() {
        override fun createIntent(context: Context, input: Unit) =
            Intent(context, BarcodeScanActivity::class.java).putExtra(EXTRA_QR, true)
        override fun parseResult(resultCode: Int, intent: Intent?): String? =
            intent?.getStringExtra(EXTRA_CODE)?.takeIf { resultCode == Activity.RESULT_OK }
    }

    private companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_QR = "qr"
        const val EXTRA_KIND = "kind"
    }
}
