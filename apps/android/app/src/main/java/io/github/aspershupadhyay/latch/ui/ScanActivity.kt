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
import android.view.Surface
import androidx.activity.ComponentActivity
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.camera.CameraSettings
import com.journeyapps.barcodescanner.camera.CenterCropStrategy
import io.github.aspershupadhyay.latch.BuildConfig
import io.github.aspershupadhyay.latch.session.Pairing
import io.github.aspershupadhyay.latch.ui.theme.LatchTheme
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens the scanner; answers the Latch setup link it read, or null when the owner backed out. */
class ScanSetupQr : ActivityResultContract<Unit, String?>() {
    override fun createIntent(context: Context, input: Unit) = Intent(context, ScanActivity::class.java)

    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        if (resultCode == Activity.RESULT_OK) intent?.getStringExtra(ScanActivity.EXTRA_TEXT) else null
}

/**
 * The QR scanner for Wi-Fi setup (ADR-054): the camera full screen with a
 * framed window, like a payment app's. It reads codes anywhere in the frame,
 * dark on light or light on dark (terminals draw either), and only accepts a
 * Latch setup link; anything else is named on screen while it keeps scanning.
 * Zoom (pinch or slider), tap to focus, a flashlight, a focus mode for phones
 * whose camera hunts, brightness for bright monitors, and reading a photo
 * instead. Nothing it sees is stored or logged.
 */
class ScanActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent {
            LatchTheme {
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

        /** Whether [text] is a Latch Wi-Fi setup link: an address with its pin and a setup code. */
        fun isSetupLink(text: String): Boolean {
            val setup = Pairing.splitSetup(text)
            return setup.code != null && Pairing.parse(setup.address, BuildConfig.ALLOW_CLEARTEXT)?.pin != null
        }
    }
}

/** How the camera focuses. Some phones hunt in the default mode and never lock on a screen. */
enum class ScanFocus(val label: String, val detail: String) {
    AUTO("Auto", "Keeps focusing as you move"),
    STEADY("Refocus", "Focuses again every two seconds; try it if the picture stays blurry"),
    NEAR("Close-up", "For a code held very close to the camera"),
    ;

    fun settings(): CameraSettings = CameraSettings().apply {
        isAutoFocusEnabled = true
        isMeteringEnabled = true
        isExposureEnabled = false
        isBarcodeSceneModeEnabled = false
        when (this@ScanFocus) {
            AUTO -> { isContinuousFocusEnabled = true; focusMode = CameraSettings.FocusMode.CONTINUOUS }
            STEADY -> { isContinuousFocusEnabled = false; focusMode = CameraSettings.FocusMode.AUTO }
            NEAR -> { isContinuousFocusEnabled = false; focusMode = CameraSettings.FocusMode.MACRO }
        }
    }
}

private const val PREFS = "latch_scanner"

@Composable
private fun ScanScreen(onResult: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val signal = LocalSignal.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var cameraAllowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var askedOnce by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf(ScanFocus.entries.firstOrNull { it.name == prefs.getString("focus", null) } ?: ScanFocus.AUTO) }
    var zoom by remember { mutableFloatStateOf(0f) }
    var exposure by remember { mutableFloatStateOf(prefs.getFloat("exposure", 0f)) }
    var torch by remember { mutableStateOf(false) }
    var showLight by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    var focusAt by remember { mutableStateOf<Offset?>(null) }
    var readingPhoto by remember { mutableStateOf(false) }

    fun accept(text: String): Boolean {
        if (done) return true
        if (!ScanActivity.isSetupLink(text)) {
            message = "That QR code isn't from Latch. Point at the one your computer's terminal shows."
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
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

    val barcode = remember {
        BarcodeView(context).apply {
            // Center-crop fills the screen; the decode window is set to the frame below.
            previewScalingStrategy = CenterCropStrategy()
            cameraSettings = focus.settings()
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE), QrDecode.hints, null, Intents.Scan.MIXED_SCAN)
        }
    }
    // Zoom and brightness are camera parameters: set again whenever the preview (re)starts.
    fun applyParameters() {
        if (barcode.isCameraClosed) return
        tune(barcode, zoom, exposure)
    }
    DisposableEffect(barcode) {
        val listener = object : CameraPreview.StateListener {
            override fun previewSized() = Unit
            override fun previewStarted() = applyParameters()
            override fun previewStopped() = Unit
            override fun cameraError(error: Exception?) {
                message = "The camera could not start. Close other camera apps, or pick a photo of the code instead."
            }
            override fun cameraClosed() = Unit
        }
        barcode.addStateListener(listener)
        onDispose { }
    }
    LaunchedEffect(zoom, exposure) { applyParameters() }
    LaunchedEffect(torch) { if (!barcode.isCameraClosed) barcode.setTorch(torch) }

    // Pause the camera with the screen, as Android expects.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, cameraAllowed) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (cameraAllowed && !done) {
                    barcode.resume()
                    barcode.decodeContinuous(BarcodeCallback { result -> result.text?.let { if (accept(it)) barcode.stopDecoding() } })
                }
                Lifecycle.Event.ON_PAUSE -> barcode.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (cameraAllowed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            barcode.resume()
            barcode.decodeContinuous(BarcodeCallback { result -> result.text?.let { if (accept(it)) barcode.stopDecoding() } })
        }
        onDispose {
            lifecycle.removeObserver(observer)
            barcode.pause()
        }
    }

    fun setFocus(next: ScanFocus) {
        focus = next
        prefs.edit { putString("focus", next.name) }
        // A new focus mode needs the camera opened again with it.
        barcode.pause()
        barcode.cameraSettings = next.settings()
        if (cameraAllowed) barcode.resume()
        torch = false
        message = "Focus: ${next.label}. ${next.detail}."
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val side = minOf(widthPx, heightPx) * 0.72f
        val frameTop = (heightPx - side) / 2f - with(density) { 40.dp.toPx() }
        val frame = androidx.compose.ui.geometry.Rect(Offset((widthPx - side) / 2f, frameTop), Size(side, side))

        if (cameraAllowed) {
            AndroidView(
                factory = { barcode },
                modifier = Modifier.fillMaxSize(),
                update = { v ->
                    // Decode a little more than the frame shows: a code half outside it still reads.
                    val decode = (side * 1.2f).toInt().coerceAtMost(minOf(widthPx, heightPx).toInt())
                    if (v.framingRectSize?.width != decode) v.framingRectSize = com.journeyapps.barcodescanner.Size(decode, decode)
                },
            )
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
                        if (gestureZoom != 1f) zoom = (zoom + (gestureZoom - 1f) * 0.6f).coerceIn(0f, 1f)
                    }
                }
                .pointerInput(cameraAllowed) {
                    detectTapGestures(
                        onDoubleTap = { zoom = if (zoom < 0.3f) 0.5f else 0f },
                        onTap = { at ->
                            focusAt = at
                            tapFocus(barcode, at, size.width, size.height, context)
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
                LabeledSlider("Zoom", zoom, { zoom = it }, "${(zoom * 100).toInt()} %")
                if (showLight) {
                    LabeledSlider("Brightness", (exposure + 1f) / 2f, { v ->
                        exposure = v * 2f - 1f
                        prefs.edit { putFloat("exposure", exposure) }
                    }, if (exposure < -0.05f) "darker" else if (exposure > 0.05f) "brighter" else "normal")
                }
            }
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                if (cameraAllowed) {
                    ToolButton(LatchIcons.Focus, "Focus", focus.label, "Focus mode: ${focus.label}. Tap to change.") {
                        setFocus(ScanFocus.entries[(focus.ordinal + 1) % ScanFocus.entries.size])
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

/** Zoom 0…1 of the camera's range; exposure -1…1 of its compensation range. */
@Suppress("DEPRECATION")
private fun tune(view: BarcodeView, zoom: Float, exposure: Float) {
    view.changeCameraParameters { p: android.hardware.Camera.Parameters ->
        if (p.isZoomSupported) p.zoom = (p.maxZoom * zoom).toInt().coerceIn(0, p.maxZoom)
        val step = if (exposure < 0) p.minExposureCompensation else p.maxExposureCompensation
        if (step != 0) p.exposureCompensation = (kotlin.math.abs(step) * exposure).toInt()
        p
    }
}

/** Points focus and metering at a tap, where the camera supports areas. Best effort. */
@Suppress("DEPRECATION")
private fun tapFocus(view: BarcodeView, at: Offset, width: Int, height: Int, context: Context) {
    if (view.isCameraClosed) return
    val sensor = runCatching {
        val info = android.hardware.Camera.CameraInfo()
        android.hardware.Camera.getCameraInfo(view.cameraSettings.requestedCameraId.coerceAtLeast(0), info)
        val turned = when (context.display.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        (info.orientation - turned + 360) % 360
    }.getOrDefault(90)
    val a = QrDecode.focusArea(at.x, at.y, width, height, sensor)
    val area = android.hardware.Camera.Area(android.graphics.Rect(a[0], a[1], a[2], a[3]), 1000)
    view.changeCameraParameters { p: android.hardware.Camera.Parameters ->
        if (p.maxNumFocusAreas > 0) p.focusAreas = listOf(area)
        if (p.maxNumMeteringAreas > 0) p.meteringAreas = listOf(area)
        p
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
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    QrDecode.fromPixels(pixels, bitmap.width, bitmap.height)
}.getOrNull()

private const val MAX_PHOTO_EDGE = 2000
