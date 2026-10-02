package io.github.aspershupadhyay.latch.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.scale
import io.github.aspershupadhyay.latch.LatchApp
import io.github.aspershupadhyay.latch.protocol.AppEntry
import io.github.aspershupadhyay.latch.protocol.ErrorCode
import io.github.aspershupadhyay.latch.protocol.GlobalAction
import io.github.aspershupadhyay.latch.protocol.Observation
import io.github.aspershupadhyay.latch.protocol.ProtocolException
import io.github.aspershupadhyay.latch.protocol.Rect
import io.github.aspershupadhyay.latch.protocol.ScreenInfo
import io.github.aspershupadhyay.latch.protocol.Screenshot
import io.github.aspershupadhyay.latch.protocol.Target
import io.github.aspershupadhyay.latch.protocol.UiNode
import io.github.aspershupadhyay.latch.session.PendingApproval
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The only component that touches other apps. It runs only while the owner
 * keeps it enabled in Android settings, and it acts only on commands the
 * session controller has already checked against the owner's choices.
 *
 * Device-side rules enforced here (the gateway cannot know these):
 *  - sensitive fields are redacted and never targeted;
 *  - actions must cite the latest observation, on the same app, with the
 *    target element still where it was;
 *  - Latch itself and system UI (notification shade, lock screen) are off limits;
 *  - gestures may not start in the status bar or on the Stop overlay.
 */
class LatchAccessibilityService : AccessibilityService() {

    private class Snapshot(
        val id: String,
        val packageName: String?,
        val nodes: Map<String, AccessibilityNodeInfo>,
        val ui: Map<String, UiNode>,
    )

    @Volatile private var latest: Snapshot? = null
    @Volatile private var foregroundPackage: String? = null
    private var overlay: TextView? = null
    private var overlayBounds: Rect? = null
    private var observationCounter = 0L
    private val random = SecureRandom()

    override fun onServiceConnected() {
        super.onServiceConnected()
        LatchApp.get(this).bridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { foregroundPackage = it }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun detach() {
        hideOverlay()
        hideApproval()
        latest = null
        LatchApp.get(this).bridge.detach(this)
    }

    // ---- Overlay: always-visible session indicator and emergency stop ----

    fun showOverlay(onStop: () -> Unit) {
        if (overlay != null) return
        val wm = getSystemService(WindowManager::class.java)
        val density = resources.displayMetrics.density
        val view = TextView(this).apply {
            text = "● Latch · Stop"
            setTextColor(Color.WHITE)
            textSize = 13f
            val padH = (12 * density).roundToInt()
            val padV = (8 * density).roundToInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(Color.argb(230, 195, 54, 43))
            }
            contentDescription = "Latch remote control is active. Double-tap to stop all activity."
            setOnClickListener { onStop() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (8 * density).roundToInt()
            y = (64 * density).roundToInt()
        }
        wm.addView(view, params)
        overlay = view
        view.post {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            // Pad the no-touch zone so near misses cannot press Stop or slip past it.
            val pad = (16 * density).roundToInt()
            overlayBounds = Rect(loc[0] - pad, loc[1] - pad, loc[0] + view.width + pad, loc[1] + view.height + pad)
        }
    }

    fun hideOverlay() {
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
        overlayBounds = null
    }

    // ---- Approval card: drawn over the current app so the owner never has to switch ----

    private var approvalCard: android.view.View? = null
    private var approvalNonce: String? = null

    fun showApproval(pending: PendingApproval, onAnswer: (Boolean) -> Unit) {
        if (approvalNonce == pending.nonce) return
        hideApproval()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.rgb(23, 26, 32))
                setStroke(dp(2), Color.rgb(240, 168, 58))
            }
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        }
        card.addView(TextView(this).apply {
            text = "Approval needed · Latch"
            setTextColor(Color.rgb(240, 168, 58))
            textSize = 13f
        })
        card.addView(TextView(this).apply {
            text = pending.title
            setTextColor(Color.WHITE)
            textSize = 19f
            setPadding(0, dp(6), 0, dp(6))
        })
        card.addView(TextView(this).apply {
            text = pending.detail
            setTextColor(Color.rgb(200, 206, 214))
            textSize = 14f
        })
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16), 0, 0)
        }
        val deny = Button(this).apply {
            text = "Deny"
            setOnClickListener { onAnswer(false) }
        }
        val approve = Button(this).apply {
            text = "Approve"
            // A short delay so a tap meant for the app underneath cannot approve by accident.
            isEnabled = false
            postDelayed({ isEnabled = true }, APPROVE_ENABLE_DELAY_MS)
            setOnClickListener { onAnswer(true) }
        }
        buttons.addView(deny)
        buttons.addView(approve)
        card.addView(buttons)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            horizontalMargin = 0.03f
            verticalMargin = 0.03f
        }
        getSystemService(WindowManager::class.java).addView(card, params)
        approvalCard = card
        approvalNonce = pending.nonce
    }

    fun hideApproval() {
        approvalCard?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        approvalCard = null
        approvalNonce = null
    }

    // ---- Observation ----

    private fun screenInfo(): ScreenInfo {
        val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        @Suppress("DEPRECATION")
        val rotation = getSystemService(WindowManager::class.java).defaultDisplay.rotation * 90
        return ScreenInfo(bounds.width(), bounds.height(), rotation)
    }

    private fun refuseRestrictedPackage(packageName: String?) {
        when {
            packageName == null -> Unit
            packageName == this.packageName ->
                throw ProtocolException(ErrorCode.POLICY_REFUSED, "Latch itself is in the foreground; agents cannot see or operate Latch")
            packageName == SYSTEM_UI ->
                throw ProtocolException(ErrorCode.POLICY_REFUSED, "the notification shade, lock screen, and system UI are not available to agents")
        }
    }

    suspend fun observe(includeScreenshot: Boolean, maxNodes: Int): Observation {
        val root = rootInActiveWindow
        val packageName = root?.packageName?.toString() ?: foregroundPackage
        refuseRestrictedPackage(packageName)

        val ui = LinkedHashMap<String, UiNode>()
        val refs = HashMap<String, AccessibilityNodeInfo>()
        var redacted = 0
        var truncated = false
        if (root != null) {
            // Breadth-first so a truncated tree keeps the top of the hierarchy.
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, String?>>()
            queue.add(root to null)
            while (queue.isNotEmpty()) {
                val (node, parentId) = queue.removeFirst()
                if (!node.isVisibleToUser) continue
                if (ui.size >= maxNodes) {
                    truncated = true
                    break
                }
                val id = "n${ui.size}"
                val sensitive = Redaction.isSensitive(facts(node))
                if (sensitive) redacted++
                ui[id] = toUiNode(node, id, parentId, sensitive)
                refs[id] = node
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it to id) }
                }
            }
        }

        observationCounter++
        val observationId = "o_${observationCounter}_${random.nextInt(1 shl 20).toString(36)}"
        latest = Snapshot(observationId, packageName, refs, ui)
        val screen = screenInfo()
        return Observation(
            observationId = observationId,
            capturedAtMs = System.currentTimeMillis(),
            `package` = packageName,
            screen = screen,
            nodes = ui.values.toList(),
            screenshot = if (includeScreenshot) screenshot() else null,
            redactedCount = redacted,
            truncated = truncated,
        )
    }

    private fun facts(node: AccessibilityNodeInfo) = NodeFacts(
        className = node.className?.toString(),
        text = node.text?.toString(),
        description = node.contentDescription?.toString(),
        hint = node.hintText?.toString(),
        viewId = node.viewIdResourceName,
        isPassword = node.isPassword,
        isEditable = node.isEditable,
        inputType = node.inputType,
        dataSensitive = Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive,
    )

    private fun toUiNode(node: AccessibilityNodeInfo, id: String, parent: String?, sensitive: Boolean): UiNode {
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        fun clip(value: CharSequence?) = value?.toString()?.take(1_000)?.takeIf { it.isNotBlank() }
        return UiNode(
            id = id,
            parent = parent,
            role = node.className?.toString()?.substringAfterLast('.')?.take(64) ?: "View",
            text = if (sensitive) null else clip(node.text),
            description = if (sensitive) null else clip(node.contentDescription),
            resourceId = node.viewIdResourceName?.take(128),
            bounds = Rect(r.left, r.top, r.right, r.bottom),
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            checked = if (node.isCheckable) isChecked(node) else null,
            enabled = node.isEnabled,
            focused = node.isFocused,
            sensitive = sensitive,
        )
    }

    private fun isChecked(node: AccessibilityNodeInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 36) {
            node.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
        } else {
            @Suppress("DEPRECATION")
            node.isChecked
        }

    private suspend fun screenshot(): Screenshot {
        var attempt = 0
        while (true) {
            val result = suspendCancellableCoroutine { cont ->
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) = cont.resume(Result.success(screenshot))
                    override fun onFailure(errorCode: Int) = cont.resume(Result.failure(ScreenshotError(errorCode)))
                })
            }
            val shot = result.getOrElse { error ->
                val code = (error as ScreenshotError).code
                if (code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && attempt++ < 2) {
                    delay(1_100)
                    null
                } else if (code == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                    throw ProtocolException(ErrorCode.SCREEN_PROTECTED, "the app on screen blocks screenshots")
                } else {
                    throw ProtocolException(ErrorCode.INTERNAL, "screenshot failed with code $code")
                }
            } ?: continue
            return encode(shot)
        }
    }

    private class ScreenshotError(val code: Int) : Exception()

    private fun encode(shot: ScreenshotResult): Screenshot {
        val buffer = shot.hardwareBuffer
        try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, shot.colorSpace)
                ?: throw ProtocolException(ErrorCode.INTERNAL, "could not read the screenshot")
            val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
            hardware.recycle()
            val scale = MAX_SCREENSHOT_EDGE.toFloat() / max(software.width, software.height)
            val scaled = if (scale < 1f) {
                software.scale((software.width * scale).roundToInt(), (software.height * scale).roundToInt())
                    .also { software.recycle() }
            } else {
                software
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            val result = Screenshot("image/jpeg", scaled.width, scaled.height, Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            scaled.recycle()
            return result
        } finally {
            buffer.close()
        }
    }

    // ---- Freshness ----

    /** The snapshot an action cites, if it is still the screen being shown. */
    private fun requireFresh(observationId: String): Snapshot {
        val snap = latest
        if (snap == null || snap.id != observationId) {
            throw ProtocolException(ErrorCode.STALE_OBSERVATION, "that is not the latest observation on the phone")
        }
        val current = rootInActiveWindow?.packageName?.toString() ?: foregroundPackage
        refuseRestrictedPackage(current)
        if (current != snap.packageName) {
            throw ProtocolException(ErrorCode.STALE_OBSERVATION, "a different app is in the foreground now")
        }
        return snap
    }

    /** Re-reads an element and checks it is still visible, enabled, unmoved, and not sensitive. */
    private fun liveNode(snap: Snapshot, elementId: String): AccessibilityNodeInfo {
        val node = snap.nodes[elementId] ?: throw ProtocolException(ErrorCode.TARGET_NOT_FOUND, "no element with that id")
        val planned = snap.ui.getValue(elementId)
        if (planned.sensitive) throw ProtocolException(ErrorCode.SENSITIVE_TARGET, "that element is sensitive")
        if (!node.refresh() || !node.isVisibleToUser) {
            throw ProtocolException(ErrorCode.STALE_OBSERVATION, "the element is no longer on screen")
        }
        if (Redaction.isSensitive(facts(node))) throw ProtocolException(ErrorCode.SENSITIVE_TARGET, "that element is sensitive")
        if (!node.isEnabled) throw ProtocolException(ErrorCode.TARGET_NOT_FOUND, "that element is disabled")
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        val b = planned.bounds
        if (abs(r.left - b.left) > MOVE_TOLERANCE_PX || abs(r.top - b.top) > MOVE_TOLERANCE_PX ||
            abs(r.right - b.right) > MOVE_TOLERANCE_PX || abs(r.bottom - b.bottom) > MOVE_TOLERANCE_PX
        ) {
            throw ProtocolException(ErrorCode.STALE_OBSERVATION, "the element moved since the observation")
        }
        return node
    }

    private fun checkGesturePoint(x: Int, y: Int, snap: Snapshot) {
        val screen = screenInfo()
        if (x !in 0 until screen.width || y !in 0 until screen.height) {
            throw ProtocolException(ErrorCode.INVALID_REQUEST, "point is outside the screen")
        }
        if (overlayBounds?.contains(x, y) == true) {
            throw ProtocolException(ErrorCode.POLICY_REFUSED, "that point is on the Latch stop button")
        }
        if (y < statusBarHeight()) {
            throw ProtocolException(ErrorCode.POLICY_REFUSED, "gestures may not start in the status bar")
        }
        val hit = snap.ui.values.filter { it.bounds.contains(x, y) }.minByOrNull { (it.bounds.right - it.bounds.left).toLong() * (it.bounds.bottom - it.bounds.top) }
        if (hit?.sensitive == true) throw ProtocolException(ErrorCode.SENSITIVE_TARGET, "that point is on a sensitive element")
    }

    private fun statusBarHeight(): Int {
        val insets = getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
        val top = insets.getInsets(WindowInsets.Type.statusBars()).top
        return if (top > 0) top else (24 * resources.displayMetrics.density).roundToInt()
    }

    // ---- Actions ----

    private fun invalidate() {
        latest = null
    }

    suspend fun tap(observationId: String, target: Target, longPress: Boolean) {
        val snap = requireFresh(observationId)
        invalidate()
        when (target) {
            is Target.Element -> {
                val node = liveNode(snap, target.element)
                val action = if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
                val canAct = if (longPress) node.isLongClickable else node.isClickable
                if (canAct && node.performAction(action)) return
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                checkGesturePoint(r.centerX(), r.centerY(), snap)
                gesture(r.centerX(), r.centerY(), r.centerX(), r.centerY(), if (longPress) 650 else 60)
            }
            is Target.Point -> {
                checkGesturePoint(target.x, target.y, snap)
                gesture(target.x, target.y, target.x, target.y, if (longPress) 650 else 60)
            }
        }
    }

    suspend fun swipe(observationId: String, fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int) {
        val snap = requireFresh(observationId)
        invalidate()
        checkGesturePoint(fromX, fromY, snap)
        val screen = screenInfo()
        if (toX !in 0 until screen.width || toY !in 0 until screen.height) {
            throw ProtocolException(ErrorCode.INVALID_REQUEST, "swipe ends outside the screen")
        }
        gesture(fromX, fromY, toX, toY, durationMs.toLong())
    }

    fun typeText(observationId: String, elementId: String, text: String) {
        val snap = requireFresh(observationId)
        invalidate()
        val node = liveNode(snap, elementId)
        if (!node.isEditable || node.isPassword) {
            throw ProtocolException(ErrorCode.INVALID_REQUEST, "that element is not an editable text field")
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            throw ProtocolException(ErrorCode.TARGET_NOT_FOUND, "the field did not accept text")
        }
    }

    fun global(action: GlobalAction) {
        refuseRestrictedPackage(rootInActiveWindow?.packageName?.toString())
        invalidate()
        val ok = performGlobalAction(
            when (action) {
                GlobalAction.BACK -> GLOBAL_ACTION_BACK
                GlobalAction.HOME -> GLOBAL_ACTION_HOME
                GlobalAction.RECENTS -> GLOBAL_ACTION_RECENTS
            },
        )
        if (!ok) throw ProtocolException(ErrorCode.INTERNAL, "the system refused the button press")
    }

    fun listApps(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(packageManager).toString().take(80)) }
            .filter { it.`package` != packageName }
            .distinctBy { it.`package` }
            .sortedBy { it.label.lowercase() }
    }

    fun launch(target: String) {
        if (target == packageName || target == SYSTEM_UI) {
            throw ProtocolException(ErrorCode.POLICY_REFUSED, "agents cannot open Latch or system UI")
        }
        val intent = packageManager.getLaunchIntentForPackage(target)
            ?: throw ProtocolException(ErrorCode.TARGET_NOT_FOUND, "no launchable app with that package")
        invalidate()
        // Accessibility services are exempt from background activity start limits.
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString() ?: foregroundPackage

    fun screen(): ScreenInfo = screenInfo()

    private suspend fun gesture(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) {
        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        val completed = suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) = cont.resume(true)
                    override fun onCancelled(gestureDescription: GestureDescription?) = cont.resume(false)
                },
                null,
            )
            if (!dispatched) cont.resume(false)
        }
        if (!completed) throw ProtocolException(ErrorCode.CANCELLED, "the system cancelled the gesture")
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val MAX_SCREENSHOT_EDGE = 1280
        private const val MOVE_TOLERANCE_PX = 8
        private const val APPROVE_ENABLE_DELAY_MS = 1_000L
    }
}
