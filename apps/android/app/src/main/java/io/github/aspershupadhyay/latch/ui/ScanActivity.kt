// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.HapticFeedbackConstants
import android.util.Size as CameraSize
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.aspershupadhyay.latch.BuildConfig
import io.github.aspershupadhyay.latch.session.ScanLink
import io.github.aspershupadhyay.latch.ui.theme.LatchTheme
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zxingcpp.BarcodeReader

/** Opens the scanner; answers the Latch code it read (ADR-054, ADR-069), or null when the owner backed out. */
class ScanSetupQr : ActivityResultContract<Unit, String?>() {
    override fun createIntent(context: Context, input: Unit) = Intent(context, ScanActivity::class.java)

    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        if (resultCode == Activity.RESULT_OK) intent?.getStringExtra(ScanActivity.EXTRA_TEXT) else null
}

/**
 * The QR scanner (ADR-054, ADR-069): the camera full screen with a
 * framed window, like a payment app's. It reads codes anywhere in the picture,
 * dark on light or light on dark (terminals draw either), zooms in by itself on
 * a code too small to read, and only accepts a Latch code; anything else
 * is named on screen while it keeps scanning. Zoom (pinch, slider, double tap),
 * tap to focus, a flashlight, brightness for bright monitors, and reading a
 * photo instead. Nothing it sees is stored or logged.
 *
 * CameraX gives full-resolution frames with continuous focus; zxing-cpp reads
 * them (it keeps reading the small, noisy code a terminal shows where ZXing's
 * Java reader, used before, gave up). Where the native reader does not load,
 * the Java reader takes over.
 */
class ScanActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent {
            LatchTheme(dark = true) {
                ScanScreen(
                    onResult = { text ->
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_TEXT = "text"

        /** Whether [text] is a Latch code: the Wi-Fi gateway's, the hosted relay's, or a mirror page's (ADR-069). */
        fun isLatchCode(text: String): Boolean = ScanLink.parse(text, BuildConfig.ALLOW_CLEARTEXT) != null
    }
}

private const val PREFS = "latch_scanner"

/** What the reader saw in one frame: a code's text, or a code it found but could not read yet. */
private sealed interface Seen {
    data class Text(val text: String) : Seen

    /** A code [side] pixels across in a frame whose short edge is [edge] pixels. */
    data class Unread(val side: Float, val edge: Int) : Seen
}

/**
 * Reads QR codes in camera frames on the analysis thread and hands what it saw
 * to [onSeen]. CameraX drops frames while one is being read, so the reader
 * always works on the newest picture.
 */
private class QrAnalyzer(private val onSeen: (Seen) -> Unit) : ImageAnalysis.Analyzer {
    private var native: BarcodeReader? = runCatching {
        BarcodeReader(
            BarcodeReader.Options(
                formats = setOf(BarcodeReader.Format.QR_CODE),
                tryHarder = true,
                tryRotate = false,
                tryInvert = true,
                tryDownscale = true,
                returnErrors = true,
                maxNumberOfSymbols = 3,
            ),
        )
    }.getOrNull()
    private var plane = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        try {
            val reader = native
            if (reader != null) {
                try {
                    readNative(reader, image)
                    return
                } catch (e: UnsatisfiedLinkError) {
                    native = null
                }
            }
            readJava(image)
        } finally {
            image.close()
        }
    }

    private fun readNative(reader: BarcodeReader, image: ImageProxy) {
        val results = reader.read(image)
        results.firstOrNull { it.error == null && it.text != null }?.let { onSeen(Seen.Text(it.text!!)); return }
        // Found but not read (too small or blurred): its size drives the auto-zoom.
        val unread = results.firstOrNull() ?: return
        val p = unread.position
        val side = maxOf(
            distance(p.topLeft, p.topRight),
            distance(p.topLeft, p.bottomLeft),
            distance(p.bottomRight, p.topRight),
            distance(p.bottomRight, p.bottomLeft),
        )
        onSeen(Seen.Unread(side, minOf(image.cropRect.width(), image.cropRect.height())))
    }

    private fun readJava(image: ImageProxy) {
        val y = image.planes[0]
        val crop = image.cropRect
        val stride = y.rowStride
        val buffer = y.buffer
        val size = stride * (crop.height() - 1) + crop.width()
        if (plane.size < size) plane = ByteArray(size)
        buffer.position(crop.top * stride + crop.left)
        buffer.get(plane, 0, minOf(size, buffer.remaining()))
        QrDecode.fromLuminance(plane, crop.width(), crop.height(), stride)?.let { onSeen(Seen.Text(it)) }
    }

    private fun distance(a: android.graphics.Point, b: android.graphics.Point): Float =
        kotlin.math.hypot((a.x - b.x).toFloat(), (a.y - b.y).toFloat())
}

@Composable
private fun ScanScreen(onResult: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val signal = LocalSignal.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var cameraAllowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var askedOnce by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoom by remember { mutableFloatStateOf(0f) }
    var exposure by remember { mutableFloatStateOf(prefs.getFloat("exposure", 0f)) }
    var torch by remember { mutableStateOf(false) }
    var showLight by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    var focusAt by remember { mutableStateOf<Offset?>(null) }
    var readingPhoto by remember { mutableStateOf(false) }
    // The owner's own zoom wins: auto-zoom waits a few seconds after they touch it.
    var ownerZoomAt by remember { mutableStateOf(0L) }
    var autoZoomAt by remember { mutableStateOf(0L) }

    fun accept(text: String): Boolean {
        if (done) return true
        if (!ScanActivity.isLatchCode(text)) {
            if (message == null || !message!!.startsWith("That QR code")) view.performHapticFeedback(HapticFeedbackConstants.REJECT)
            message = "That QR code isn't from Latch. Point at the code on your computer: its terminal, your relay's page, or the mirror page."
            return false
        }
        done = true
        message = null
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        scope.launch {
            // A moment to see the tick, as payment apps do.
            delay(350)
            onResult(text)
        }
        return true
    }

    fun setZoom(value: Float, byOwner: Boolean) {
        zoom = value.coerceIn(0f, 1f)
        if (byOwner) ownerZoomAt = System.currentTimeMillis()
        camera?.cameraControl?.setLinearZoom(zoom)
    }

    fun onSeen(seen: Seen) {
        when (seen) {
            is Seen.Text -> accept(seen.text)
            is Seen.Unread -> {
                val now = System.currentTimeMillis()
                val cam = camera ?: return
                if (done || now - ownerZoomAt < 4000 || now - autoZoomAt < 1200) return
                val state = cam.cameraInfo.zoomState.value ?: return
                val ratio = QrDecode.zoomFor(seen.side, seen.edge, state.zoomRatio, state.maxZoomRatio) ?: return
                autoZoomAt = now
                cam.cameraControl.setZoomRatio(ratio).addListener({
                    cam.cameraInfo.zoomState.value?.let { zoom = it.linearZoom }
                }, ContextCompat.getMainExecutor(context))
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraAllowed = granted
        askedOnce = true
    }
    LaunchedEffect(Unit) { if (!cameraAllowed) permission.launch(Manifest.permission.CAMERA) }

    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        readingPhoto = true
        message = null
        scope.launch {
            val text = withContext(Dispatchers.Default) { readPhoto(context, uri) }
            readingPhoto = false
            if (text == null) {
                message = "No QR code found in that picture. Try a sharper photo, closer to the code."
            } else {
                accept(text)
            }
        }
    }

    val preview = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // A TextureView: composes cleanly under the Compose overlay.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // CameraX follows the screen's lifecycle by itself; this binds once the camera is allowed.
    DisposableEffect(cameraAllowed, lifecycleOwner) {
        if (!cameraAllowed) return@DisposableEffect onDispose { }
        val main = ContextCompat.getMainExecutor(context)
        val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "latch-qr").apply { priority = Thread.NORM_PRIORITY - 1 } }
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            val p = runCatching { future.get() }.getOrNull()
            if (p == null) {
                message = "The camera could not start. Close other camera apps, or pick a photo of the code instead."
                return@addListener
            }
            provider = p
            val usePreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            // Full HD frames: a terminal's code is small, and every pixel counts for reading it.
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(CameraSize(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(worker, QrAnalyzer { seen -> main.execute { if (!disposed) onSeen(seen) } })
            try {
                p.unbindAll()
                camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, usePreview, analysis)
                camera?.cameraControl?.setLinearZoom(zoom)
            } catch (e: Exception) {
                message = "The camera could not start. Close other camera apps, or pick a photo of the code instead."
            }
        }, main)
        onDispose {
            disposed = true
            camera = null
            provider?.unbindAll()
            // A frame being read finishes on its own; its answer is ignored after this.
            worker.shutdown()
        }
    }
    // Brightness: the camera's own exposure compensation, -1…1 of its range.
    LaunchedEffect(camera, exposure) {
        val cam = camera ?: return@LaunchedEffect
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return@LaunchedEffect
        val range = state.exposureCompensationRange
        val index = if (exposure < 0) (range.lower * -exposure).toInt() else (range.upper * exposure).toInt()
        cam.cameraControl.setExposureCompensationIndex(index.coerceIn(range.lower, range.upper))
    }
    LaunchedEffect(camera, torch) {
        val cam = camera ?: return@LaunchedEffect
        if (cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(torch)
    }
    LaunchedEffect(done) { if (done) camera?.cameraControl?.enableTorch(false) }

    fun focusAtPoint(at: Offset) {
        val cam = camera ?: return
        val point = preview.meteringPointFactory.createPoint(at.x, at.y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val side = minOf(widthPx, heightPx) * 0.72f
        val frameTop = (heightPx - side) / 2f - with(density) { 40.dp.toPx() }
        val frame = androidx.compose.ui.geometry.Rect(Offset((widthPx - side) / 2f, frameTop), Size(side, side))

        if (cameraAllowed) {
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
        }

        // The dimmed surround, the frame's corners, and a moving scan line.
        val sweep = rememberInfiniteTransition(label = "scan")
        val line by sweep.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse), label = "line")
        val accent = if (done) signal.success else signal.accent
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .pointerInput(cameraAllowed) {
                    detectTransformGestures { _, _, gestureZoom, _ ->
                        if (gestureZoom != 1f) setZoom(zoom + (gestureZoom - 1f) * 0.6f, byOwner = true)
                    }
                }
                .pointerInput(cameraAllowed) {
                    detectTapGestures(
                        onDoubleTap = { setZoom(if (zoom < 0.3f) 0.5f else 0f, byOwner = true) },
                        onTap = { at ->
                            focusAt = at
                            focusAtPoint(at)
                        },
                    )
                }
                .semantics { contentDescription = "Camera. Point it at the QR code. Double-tap to zoom, tap to focus." },
        ) {
            drawRect(Color.Black.copy(alpha = 0.62f))
            drawRoundRect(Color.Transparent, frame.topLeft, frame.size, CornerRadius(28.dp.toPx()), blendMode = BlendMode.Clear)
            val arm = side * 0.16f
            val w = 4.dp.toPx()
            val r = 28.dp.toPx()
            val l = frame.left
            val t = frame.top
            val rr = frame.right
            val b = frame.bottom
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(accent, Offset(x + dx * r * 0.6f, y), Offset(x + dx * arm, y), w, StrokeCap.Round)
                drawLine(accent, Offset(x, y + dy * r * 0.6f), Offset(x, y + dy * arm), w, StrokeCap.Round)
                drawArc(
                    accent,
                    startAngle = when {
                        dx > 0 && dy > 0 -> 180f
                        dx < 0 && dy > 0 -> 270f
                        dx < 0 -> 0f
                        else -> 90f
                    },
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(if (dx > 0) x else x - 2 * r * 0.6f, if (dy > 0) y else y - 2 * r * 0.6f),
                    size = Size(2 * r * 0.6f, 2 * r * 0.6f),
                    style = Stroke(w, cap = StrokeCap.Round),
                )
            }
            corner(l, t, 1f, 1f)
            corner(rr, t, -1f, 1f)
            corner(l, b, 1f, -1f)
            corner(rr, b, -1f, -1f)
            if (cameraAllowed && !done) {
                val y = t + side * (0.08f + 0.84f * line)
                drawLine(accent.copy(alpha = 0.85f), Offset(l + side * 0.1f, y), Offset(rr - side * 0.1f, y), 2.dp.toPx(), StrokeCap.Round)
            }
            focusAt?.let { drawCircle(Color.White.copy(alpha = 0.9f), 30.dp.toPx(), it, style = Stroke(1.5.dp.toPx())) }
        }
        LaunchedEffect(focusAt) {
            if (focusAt != null) {
                delay(900)
                focusAt = null
            }
        }

        // Top bar: close, title, flashlight.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(LatchIcons.Close, "Close the scanner", onClick = onCancel)
            Text(
                "Scan QR code",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            RoundButton(LatchIcons.Flash, if (torch) "Turn the flashlight off" else "Turn the flashlight on", selected = torch, enabled = cameraAllowed) {
                torch = !torch
            }
        }

        // Under the frame: what to do, or what went wrong.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = with(density) { (frame.bottom + 20.dp.toPx()).toDp() })
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val text = when {
                done -> "Got it. Connecting…"
                readingPhoto -> "Reading the picture…"
                !cameraAllowed -> null
                message != null -> message
                else -> "Point at the QR code in your computer's terminal. It scans by itself."
            }
            if (text != null) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message != null && !done) signal.warning else Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        if (!cameraAllowed) {
            CameraBlocked(
                askedOnce = askedOnce,
                onAllow = {
                    val activity = context as? Activity
                    if (askedOnce && activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                        context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                    } else {
                        permission.launch(Manifest.permission.CAMERA)
                    }
                },
                onPhoto = { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Bottom: zoom, brightness, focus mode, a photo instead, or typing it.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (cameraAllowed) {
                LabeledSlider("Zoom", zoom, { setZoom(it, byOwner = true) }, "${(zoom * 100).toInt()} %")
                if (showLight) {
                    LabeledSlider("Brightness", (exposure + 1f) / 2f, { v ->
                        exposure = v * 2f - 1f
                        prefs.edit { putFloat("exposure", exposure) }
                    }, if (exposure < -0.05f) "darker" else if (exposure > 0.05f) "brighter" else "normal")
                }
            }
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                if (cameraAllowed) {
                    ToolButton(LatchIcons.Focus, "Focus", "on the code", "Focus on the middle of the frame. You can also tap the picture where the code is.") {
                        frame.center.let { focusAt = it; focusAtPoint(it) }
                    }
                    ToolButton(LatchIcons.Sun, "Light", if (showLight) "hide" else "adjust", "Brightness. A bright monitor scans better a little darker.", selected = showLight) {
                        showLight = !showLight
                        if (showLight && exposure == 0f) message = "Too bright? Slide toward darker: monitors often wash out the code."
                    }
                }
                ToolButton(LatchIcons.Photo, "Photo", "pick", "Read the code from a photo") {
                    photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                ToolButton(LatchIcons.Keyboard, "Type", "by hand", "Enter the address and code by hand", onClick = onCancel)
            }
        }
    }
}

@Composable
private fun CameraBlocked(askedOnce: Boolean, onAllow: () -> Unit, onPhoto: () -> Unit, modifier: Modifier = Modifier) {
    val signal = LocalSignal.current
    Column(
        modifier.padding(32.dp).clip(RoundedCornerShape(20.dp)).background(signal.surface).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(LatchIcons.Camera, null, tint = signal.accent, modifier = Modifier.size(36.dp))
        Text("Latch needs the camera to scan", style = MaterialTheme.typography.titleMedium, color = signal.text, textAlign = TextAlign.Center)
        Text(
            "Only while this screen is open. Nothing it sees is saved." + if (askedOnce) " If Android doesn't ask again, allow Camera in Latch's settings." else "",
            style = MaterialTheme.typography.bodySmall,
            color = signal.text2,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onAllow) { Text(if (askedOnce) "Allow camera" else "Continue", color = signal.accent) }
        TextButton(onClick = onPhoto) { Text("Use a photo of the code instead", color = signal.text2) }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (selected) Color.White else Color.Black.copy(alpha = 0.45f))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (selected) Color.Black else Color.White.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ToolButton(icon: ImageVector, title: String, value: String, label: String, selected: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = value
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(if (selected) Color.White else Color.White.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (selected) Color.Black else Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, color = Color.White)
        Text(value, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit, shown: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.width(78.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.weight(1f).semantics { contentDescription = label; stateDescription = shown },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = LocalSignal.current.accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
            ),
        )
        Text(shown, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.width(64.dp), textAlign = TextAlign.End)
    }
}

/** The QR code in a picture the owner picked, or null. Scaled so huge photos stay quick. */
private fun readPhoto(context: Context, uri: Uri): String? = runCatching {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val edge = maxOf(info.size.width, info.size.height)
        if (edge > MAX_PHOTO_EDGE) {
            val scale = MAX_PHOTO_EDGE.toFloat() / edge
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
    }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
    // zxing-cpp first (it reads small and noisy codes), then ZXing's Java reader.
    val native = runCatching {
        BarcodeReader(BarcodeReader.Options(formats = setOf(BarcodeReader.Format.QR_CODE), tryHarder = true, tryInvert = true, tryDownscale = true))
            .read(bitmap, android.graphics.Rect(0, 0, bitmap.width, bitmap.height), 0)
            .firstOrNull { it.error == null }?.text
    }.getOrNull()
    native ?: run {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        QrDecode.fromPixels(pixels, bitmap.width, bitmap.height)
    }
}.getOrNull()

private const val MAX_PHOTO_EDGE = 2000
