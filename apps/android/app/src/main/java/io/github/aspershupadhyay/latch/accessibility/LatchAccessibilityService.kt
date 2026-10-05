// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.scale
import io.github.aspershupadhyay.latch.LatchApp
import io.github.aspershupadhyay.latch.protocol.AppEntry
import io.github.aspershupadhyay.latch.protocol.Direction
import io.github.aspershupadhyay.latch.protocol.ErrorCode
import io.github.aspershupadhyay.latch.protocol.GlobalAction
import io.github.aspershupadhyay.latch.protocol.Observation
import io.github.aspershupadhyay.latch.protocol.ProtocolException
import io.github.aspershupadhyay.latch.protocol.Rect
import io.github.aspershupadhyay.latch.protocol.ScreenInfo
import io.github.aspershupadhyay.latch.protocol.Screenshot
import io.github.aspershupadhyay.latch.protocol.Target
import io.github.aspershupadhyay.latch.protocol.UiNode
import io.github.aspershupadhyay.latch.protocol.WaitResult
import io.github.aspershupadhyay.latch.session.ApprovalChoice
import io.github.aspershupadhyay.latch.session.ApprovalKind
import io.github.aspershupadhyay.latch.session.PendingApproval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    private var overlay: StopPill? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    @Volatile private var overlayBounds: Rect? = null
    private var observationCounter = 0L
    private val random = SecureRandom()

    override fun onServiceConnected() {
        super.onServiceConnected()
        LatchApp.get(this).bridge.attach(this)
    }

    /** When another app's screen last changed (uptime ms), for smart settle and waits. */
    @Volatile private var lastUiEventAtMs = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { foregroundPackage = it }
        }
        // A tap the AI did not make is the owner's: Activity says "You". Latch's own
        // screens and the system bars are not logged.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && pkg != packageName && pkg != SYSTEM_UI) LatchApp.get(this).bridge.touches.tapped(pkg)
        }
        // Latch's own overlays (Stop pill, approval card, cursor) are not the app changing.
        if (event.packageName?.toString() != packageName) lastUiEventAtMs = SystemClock.uptimeMillis()
    }

    /**
     * A cheap fingerprint of what the active window shows: text, positions,
     * and checked states of the first visible elements. Page transitions and
     * slide-ins move elements without sending accessibility events; positions
     * catch them.
     */
    fun screenSignature(): Int {
        val root = rootInActiveWindow ?: return 0
        var h = root.packageName?.hashCode() ?: 0
        val queue = ArrayDeque(listOf(root))
        val r = android.graphics.Rect()
        var seen = 0
        while (queue.isNotEmpty() && seen < SIGNATURE_NODES) {
            val node = queue.removeFirst()
            if (!node.isVisibleToUser) continue
            seen++
            node.getBoundsInScreen(r)
            h = 31 * h + (node.text?.hashCode() ?: 0)
            h = 31 * h + (node.contentDescription?.hashCode() ?: 0)
            h = 31 * h + r.left
            h = 31 * h + r.top
            h = 31 * h + r.right
            h = 31 * h + r.bottom
            h = 31 * h + if (node.isCheckable && isChecked(node)) 1 else 0
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        // The keyboard is its own window: count it, so its slide-in is not taken for a still screen.
        keyboardBounds()?.let { h = 31 * h + it.top }
        return if (h == 0) 1 else h
    }

    /**
     * What the screen says, without positions and without text fields: changes
     * when a search shows results or a message joins a chat, but not when the
     * field the agent typed into grows a line or the layout shifts.
     */
    private fun contentSignature(): Int {
        val root = rootInActiveWindow ?: return 0
        var h = root.packageName?.hashCode() ?: 0
        val queue = ArrayDeque(listOf(root))
        var seen = 0
        while (queue.isNotEmpty() && seen < SIGNATURE_NODES) {
            val node = queue.removeFirst()
            if (!node.isVisibleToUser) continue
            seen++
            if (!node.isEditable) {
                h = 31 * h + (node.text?.hashCode() ?: 0)
                h = 31 * h + (node.contentDescription?.hashCode() ?: 0)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return h
    }

    /**
     * Waits until the screen shows the result of an action: it has changed
     * from [before] (or [expectChangeMs] passed without a change, so nothing
     * will) and then holds still, meaning two fingerprints in a row are equal
     * and no event arrived for [quietMs]. Never longer than [maxMs].
     * With [expectKeyboard], a tap that focused a text field also waits for
     * the keyboard to open, so the agent sees the screen it will type into.
     */
    suspend fun awaitSettled(before: Int, quietMs: Long, maxMs: Long, minMs: Long, expectChangeMs: Long, expectKeyboard: Boolean = false) {
        val start = SystemClock.uptimeMillis()
        var previous = screenSignature()
        while (true) {
            delay(SETTLE_STEP_MS)
            val now = SystemClock.uptimeMillis()
            val elapsed = now - start
            if (elapsed >= maxMs) return
            val current = screenSignature()
            val changed = current != before
            val still = current == previous && current != 0 && now - max(lastUiEventAtMs, start) >= quietMs
            val keyboardPending = expectKeyboard && elapsed < KEYBOARD_WAIT_MS && keyboardBounds() == null && editableFocused()
            if (still && !keyboardPending && elapsed >= minMs && (changed || elapsed >= expectChangeMs)) return
            previous = current
        }
    }

    /** Waits until [target] is the app in front, at most [maxMs]. Returns the time spent. */
    suspend fun awaitForeground(target: String, maxMs: Long): Long {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < maxMs) {
            if ((rootInActiveWindow?.packageName?.toString() ?: foregroundPackage) == target) break
            delay(20)
        }
        return SystemClock.uptimeMillis() - start
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

    /** Shows where the AI acts; present while a session runs and the owner wants it. */
    private val cursor by lazy { CursorOverlay(this) }
    @Volatile private var cursorOn = false

    private fun preferences() = LatchApp.get(this).settings.preferences.value

    /**
     * The session indicator. With keepAwake, it also keeps the screen on (and so
     * unlocked) while the session runs, so a task is not cut off by the lock screen.
     */
    fun showOverlay(onStop: () -> Unit) {
        if (preferences().showCursor && !cursorOn) {
            cursor.attach()
            cursorOn = true
        }
        if (overlay != null) return
        val wm = getSystemService(WindowManager::class.java)
        val density = resources.displayMetrics.density
        val store = getSharedPreferences("latch_overlay", MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (preferences().keepAwake) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Where the owner last left it; first time, against the right edge below the middle,
            // away from the toolbars and Send/Next buttons that sit at the top of most apps.
            x = store.getInt("pill_x", -1).takeIf { it >= 0 } ?: resources.displayMetrics.widthPixels
            y = store.getInt("pill_y", -1).takeIf { it >= 0 } ?: (resources.displayMetrics.heightPixels * 0.58f).roundToInt()
        }
        val view = StopPill(
            this,
            onStop = onStop,
            onMove = { dx, dy ->
                params.x += dx
                params.y += dy
                clampOverlay(params)
                overlay?.let { pill ->
                    runCatching { wm.updateViewLayout(pill, params) }
                    updateOverlayBounds(pill)
                }
            },
            onRelease = {
                // Glide to the nearer side so it never sits over the middle of an app.
                val pill = overlay ?: return@StopPill
                val screen = resources.displayMetrics.widthPixels
                val margin = (8 * density).roundToInt()
                val target = if (params.x + pill.width / 2 < screen / 2) margin else screen - pill.width - margin
                android.animation.ValueAnimator.ofInt(params.x, target).apply {
                    duration = 260
                    interpolator = android.view.animation.OvershootInterpolator(1.1f)
                    addUpdateListener {
                        params.x = it.animatedValue as Int
                        runCatching { wm.updateViewLayout(pill, params) }
                    }
                    doOnEndCompat {
                        store.edit { putInt("pill_x", params.x); putInt("pill_y", params.y) }
                        updateOverlayBounds(pill)
                    }
                    start()
                }
            },
        )
        // The service may be going away (switched off, updating): no pill then, but no crash either.
        if (runCatching { wm.addView(view, params) }.isFailure) return
        overlay = view
        overlayParams = params
        view.post {
            clampOverlay(params)
            runCatching { wm.updateViewLayout(view, params) }
            view.post { updateOverlayBounds(view) }
        }
    }

    /** Keeps the pill on screen and below the status bar. */
    private fun clampOverlay(params: WindowManager.LayoutParams) {
        val pill = overlay ?: return
        val m = resources.displayMetrics
        val top = (28 * m.density).roundToInt()
        params.x = params.x.coerceIn(0, (m.widthPixels - pill.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(top, (m.heightPixels - pill.height - top).coerceAtLeast(top))
    }

    /** The AI may not touch the pill: its bounds, padded so near misses cannot press Stop or slip past it. */
    private fun updateOverlayBounds(view: android.view.View) {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val pad = (16 * resources.displayMetrics.density).roundToInt()
        overlayBounds = Rect(loc[0] - pad, loc[1] - pad, loc[0] + view.width + pad, loc[1] + view.height + pad)
    }

    /**
     * The AI may never press Stop, but the pill must not block its work either:
     * when a gesture would start on the pill, the pill steps to the other side
     * of the screen (or up or down) first. Returns once it has moved.
     */
    private suspend fun movePillAside(vararg points: Pair<Int, Int>) {
        val bounds = overlayBounds ?: return
        if (points.none { (x, y) -> bounds.contains(x, y) }) return
        withContext(Dispatchers.Main) {
            val pill = overlay ?: return@withContext
            val params = overlayParams ?: return@withContext
            val m = resources.displayMetrics
            val margin = (8 * m.density).roundToInt()
            val pad = (16 * m.density).roundToInt()
            fun hits(px: Int, py: Int) = points.any { (x, y) ->
                x >= px - pad && x < px + pill.width + pad && y >= py - pad && y < py + pill.height + pad
            }
            val onLeft = params.x + pill.width / 2 < m.widthPixels / 2
            params.x = if (onLeft) m.widthPixels - pill.width - margin else margin
            if (hits(params.x, params.y)) {
                val lowest = points.maxOf { it.second }
                params.y = if (lowest > m.heightPixels / 2) (m.heightPixels * 0.25f).roundToInt() else (m.heightPixels * 0.7f).roundToInt()
            }
            clampOverlay(params)
            runCatching { getSystemService(WindowManager::class.java).updateViewLayout(pill, params) }
            // Until the window has moved, judge by where it is going.
            overlayBounds = Rect(params.x - pad, params.y - pad, params.x + pill.width + pad, params.y + pill.height + pad)
        }
        delay(PILL_MOVE_MS)
        withContext(Dispatchers.Main) { overlay?.let(::updateOverlayBounds) }
    }

    /** Shows on the cursor what the AI is doing off screen (reading, saving, sharing). */
    fun cursorStatus(text: String) {
        if (cursorOn) cursor.status(text)
    }

    /** The current command finished; the cursor waits briefly for the next one, then fades. */
    fun cursorIdle() {
        if (cursorOn) cursor.idle()
    }

    /** The AI said its task is done: the cursor goes at once. */
    fun cursorFinish() {
        if (cursorOn) cursor.finish()
    }

    /** Shows a moving file on the cursor; null when nothing moves. */
    fun cursorTransfer(text: String?, fraction: Float?) {
        if (cursorOn) cursor.transfer(text, fraction)
    }

    /** Applies the owner's cursor and keep-awake switches to a running session at once. */
    fun applyOverlayPreferences(showCursor: Boolean, keepAwake: Boolean) {
        if (showCursor && !cursorOn) {
            cursor.attach()
            cursorOn = true
        } else if (!showCursor && cursorOn) {
            cursor.detach()
            cursorOn = false
        }
        val view = overlay ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val flags = if (keepAwake) {
            params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        if (flags != params.flags) {
            params.flags = flags
            runCatching { getSystemService(WindowManager::class.java).updateViewLayout(view, params) }
        }
    }

    fun hideOverlay() {
        cursor.detach()
        cursorOn = false
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
        overlayParams = null
        overlayBounds = null
    }

    // ---- Approval card: drawn over the current app so the owner never has to switch ----

    private var approvalCard: android.view.View? = null
    private var approvalNonce: String? = null
    /** Where the card is on screen, padded: only the owner's finger may answer it. */
    @Volatile private var approvalBounds: Rect? = null

    /** The AI can never answer its own question by tapping the card. */
    private fun refuseOnApprovalCard(x: Int, y: Int) {
        if (approvalBounds?.contains(x, y) == true) {
            throw ProtocolException(ErrorCode.POLICY_REFUSED, "that point is on Latch's approval card, which only the owner may answer")
        }
    }

    fun showApproval(pending: PendingApproval, onAnswer: (ApprovalChoice) -> Unit) {
        if (approvalNonce == pending.nonce) return
        hideApproval()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()
        // Same look and words as the in-app card (ui/Approval.kt), in light and dark.
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val surface = if (dark) Color.rgb(32, 26, 22) else Color.rgb(255, 251, 245)
        val text = if (dark) Color.rgb(245, 236, 225) else Color.rgb(31, 26, 22)
        val text2 = if (dark) Color.rgb(170, 156, 142) else Color.rgb(116, 104, 93)
        val accent = if (dark) Color.rgb(240, 138, 93) else Color.rgb(181, 74, 38)
        val warning = if (dark) Color.rgb(242, 177, 76) else Color.rgb(168, 92, 10)
        val tint = if (pending.risk == "high") warning else accent
        val appRequest = pending.kind == ApprovalKind.APP
        val ownerTask = pending.kind == ApprovalKind.OWNER_TASK
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(surface)
                if (dark) setStroke(dp(1), Color.rgb(58, 49, 42))
            }
            elevation = dp(12).toFloat()
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        }
        card.addView(TextView(this).apply {
            this.text = when {
                ownerTask -> "Your AI needs you"
                appRequest -> "The AI wants to use an app"
                pending.risk == "high" -> "Check this before it happens"
                else -> "The AI is asking you"
            }
            setTextColor(tint)
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            this.text = pending.title
            setTextColor(text)
            textSize = 20f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(0, dp(6), 0, dp(4))
        })
        card.addView(TextView(this).apply {
            this.text = pending.detail
            setTextColor(text2)
            textSize = 15f
        })
        // Filled pill buttons: terracotta for the main answer, tinted for the rest.
        fun pill(label: String, primary: Boolean, muted: Boolean = false) = TextView(this).apply {
            this.text = label
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(12), 0)
            minHeight = dp(48)
            isClickable = true
            isFocusable = true
            // A window from another app drawn over the card must not trick the owner into a tap.
            filterTouchesWhenObscured = true
            setTextColor(if (primary) (if (dark) Color.rgb(31, 26, 22) else Color.WHITE) else if (muted) text2 else accent)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(if (primary) accent else Color.argb(if (dark) 46 else 26, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }
        // A short delay so a tap meant for the app underneath cannot approve by accident.
        fun allow(label: String, choice: ApprovalChoice, primary: Boolean = false) = pill(label, primary).apply {
            isEnabled = false
            alpha = 0.5f
            postDelayed({ isEnabled = true; alpha = 1f }, APPROVE_ENABLE_DELAY_MS)
            setOnClickListener { onAnswer(choice) }
        }
        fun deny(label: String) = pill(label, primary = false, muted = true).apply { setOnClickListener { onAnswer(ApprovalChoice.DENY) } }
        fun row(vararg views: android.view.View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            views.forEachIndexed { i, v ->
                addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(10) })
            }
        }
        if (ownerTask) {
            card.addView(row(deny("I can't"), allow("Done", ApprovalChoice.ONCE, primary = true)))
        } else if (appRequest) {
            // An app is allowed for a while, never for one command: that would ask again at once.
            card.addView(row(allow("Allow always", ApprovalChoice.ALWAYS, primary = true)))
            card.addView(row(allow("Just this session", ApprovalChoice.SESSION), deny("Not now")))
        } else {
            card.addView(row(deny("Don't allow"), allow("Allow", ApprovalChoice.ONCE, primary = true)))
            if (pending.rememberable) {
                card.addView(row(allow("This session", ApprovalChoice.SESSION), allow("Always in ${pending.appName ?: "this app"}".take(40), ApprovalChoice.ALWAYS)))
            } else {
                card.addView(TextView(this).apply {
                    this.text = "Latch asks every time for this kind of action."
                    setTextColor(text2)
                    textSize = 13f
                    setPadding(0, dp(10), 0, 0)
                })
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // A step for the owner happens in the app underneath, often with the keyboard
            // up: the card waits at the top, out of the way.
            gravity = if (ownerTask) Gravity.TOP else Gravity.BOTTOM
            horizontalMargin = 0.03f
            verticalMargin = if (ownerTask) 0.06f else 0.03f
        }
        // If the card cannot be drawn (the service is going away), the in-app card still asks.
        if (runCatching { getSystemService(WindowManager::class.java).addView(card, params) }.isFailure) return
        approvalCard = card
        approvalNonce = pending.nonce
        // Until it is laid out, the whole width at the card's edge of the screen is the card's.
        val m = resources.displayMetrics
        approvalBounds = if (ownerTask) Rect(0, 0, m.widthPixels, m.heightPixels / 2) else Rect(0, m.heightPixels / 2, m.widthPixels, m.heightPixels)
        card.post {
            if (approvalCard !== card) return@post
            val loc = IntArray(2)
            card.getLocationOnScreen(loc)
            val pad = dp(16)
            approvalBounds = Rect(loc[0] - pad, loc[1] - pad, loc[0] + card.width + pad, loc[1] + card.height + pad)
        }
    }

    fun hideApproval() {
        approvalCard?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        approvalCard = null
        approvalNonce = null
        approvalBounds = null
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
                throw ProtocolException(
                    ErrorCode.POLICY_REFUSED,
                    "Latch itself is in the foreground; agents cannot see or operate Latch. Open the app you need with launch_app, or press home",
                )
            packageName == SYSTEM_UI ->
                throw ProtocolException(ErrorCode.POLICY_REFUSED, "the notification shade, lock screen, and system UI are not available to agents")
        }
    }

    suspend fun observe(includeScreenshot: Boolean, maxNodes: Int): Observation {
        val root = rootInActiveWindow ?: topAppWindowRoot()
        val packageName = root?.packageName?.toString() ?: foregroundPackage
        refuseRestrictedPackage(packageName)

        val ui = LinkedHashMap<String, UiNode>()
        val refs = HashMap<String, AccessibilityNodeInfo>()
        val controls = HashSet<Triple<Rect, String?, String?>>()
        val screen = screenInfo()
        val bounds = android.graphics.Rect()
        var redacted = 0
        var truncated = false
        if (root != null) {
            // Breadth-first so a truncated tree keeps the top of the hierarchy.
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, String?>>()
            queue.add(root to null)
            while (queue.isNotEmpty()) {
                val (node, parentId) = queue.removeFirst()
                // Hidden, zero-size, or fully off-screen elements (common in web pages) cannot be
                // seen or used; leave them out, and hang what they contain on the nearest kept
                // ancestor. Some apps report a container as hidden while what it holds is shown.
                // Empty layout containers (only listed because of flagIncludeNotImportantViews)
                // are skipped the same way.
                node.getBoundsInScreen(bounds)
                if (!node.isVisibleToUser || bounds.width() <= 0 || bounds.height() <= 0 ||
                    !bounds.intersect(0, 0, screen.width, screen.height) || emptyLayout(node)
                ) {
                    for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it to parentId) }
                    continue
                }
                if (ui.size >= maxNodes) {
                    truncated = true
                    break
                }
                val id = "n${ui.size}"
                val sensitive = Redaction.isSensitive(facts(node))
                val candidate = toUiNode(node, id, parentId, sensitive)
                // Some apps (Maps among them) wrap an element in a container that repeats it exactly:
                // same place, words, and behaviour. List it once and hang its contents on the first.
                // Others draw a plain label exactly over a button that already says the same
                // ("Voice search" in Maps): the label adds nothing the button doesn't.
                val parent = parentId?.let(ui::get)
                val labelKey = candidate.takeIf { it.text != null || it.description != null }?.let { Triple(it.bounds, it.text, it.description) }
                val repeatsControl = labelKey != null && !candidate.sensitive && !interactive(candidate) && labelKey in controls
                if (repeatsControl || (parent != null && sameElement(parent, candidate))) {
                    for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it to parentId) }
                    continue
                }
                if (sensitive) redacted++
                ui[id] = candidate
                refs[id] = node
                if (labelKey != null && interactive(candidate)) controls += labelKey
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it to id) }
                }
            }
        }

        observationCounter++
        val observationId = "o_${observationCounter}_${random.nextInt(1 shl 20).toString(36)}"
        latest = Snapshot(observationId, packageName, refs, ui)
        return Observation(
            observationId = observationId,
            capturedAtMs = System.currentTimeMillis(),
            `package` = packageName,
            screen = screen,
            nodes = ui.values.toList(),
            screenshot = if (includeScreenshot) withoutCursor { screenshot() } else null,
            redactedCount = redacted,
            truncated = truncated,
        )
    }

    /** A layout container that is not important for accessibility and says or does nothing. */
    private fun emptyLayout(node: AccessibilityNodeInfo): Boolean =
        !node.isImportantForAccessibility && node.text.isNullOrBlank() && node.contentDescription.isNullOrBlank() &&
            !node.isClickable && !node.isLongClickable && !node.isEditable && !node.isScrollable &&
            !node.isCheckable && !node.isFocusable

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

    /**
     * Some dialogs (Android's permission and install prompts among them) leave
     * no active window for a moment; read the topmost app window instead of
     * returning an empty screen. System UI windows are never read this way.
     */
    private fun topAppWindowRoot(): AccessibilityNodeInfo? =
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
            .firstNotNullOfOrNull { it.root }

    private fun interactive(n: UiNode) = n.clickable || n.longClickable || n.editable || n.scrollable || n.checked != null

    /** Whether [child] repeats [parent] exactly, so listing both would only confuse the agent. */
    private fun sameElement(parent: UiNode, child: UiNode): Boolean =
        child.copy(id = parent.id, parent = parent.parent, role = parent.role, resourceId = parent.resourceId, focused = parent.focused) == parent

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

    /** The AI must see the app, not Latch's pointer or Stop pill. Views change only on the main thread. */
    private suspend fun <T> withoutCursor(block: suspend () -> T): T {
        val restore = withContext(Dispatchers.Main) {
            val cursorBack = if (cursorOn) cursor.hideForCapture() else ({})
            val pill = overlay
            pill?.visibility = android.view.View.INVISIBLE
            ({ cursorBack(); pill?.visibility = android.view.View.VISIBLE })
        }
        try {
            delay(CURSOR_HIDE_MS)
            return block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { restore() }
        }
    }

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

    /** Like a computer: a hand over things that can be tapped, a text cursor over fields. */
    private fun pointerFor(node: UiNode?): CursorOverlay.Pointer = when {
        node == null -> CursorOverlay.Pointer.ARROW
        node.editable -> CursorOverlay.Pointer.TEXT
        node.clickable || node.longClickable -> CursorOverlay.Pointer.HAND
        else -> CursorOverlay.Pointer.ARROW
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
        // movePillAside() runs first, so this only holds if the pill could not move.
        if (overlayBounds?.contains(x, y) == true) {
            throw ProtocolException(ErrorCode.POLICY_REFUSED, "that point is on the Latch stop button")
        }
        refuseOnApprovalCard(x, y)
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

    // ---- Approval check: what the phone itself sees under a tap ----

    /** The phone's own view of a tap target, for the device-side approval check. */
    class TapContext(val observation: Observation, val node: UiNode?, val live: List<UiNode>)

    /**
     * Null when the cited observation is not the latest; the action is then
     * refused as stale anyway. [TapContext.live] adds what the element shows
     * right now (the gateway's copy may be truncated), redacted like observe.
     */
    fun tapContext(observationId: String, target: Target): TapContext? {
        val snap = latest?.takeIf { it.id == observationId } ?: return null
        val nodes = snap.ui.values.toList()
        val node = when (target) {
            is Target.Element -> snap.ui[target.element]
            is Target.Point -> io.github.aspershupadhyay.latch.policy.Consequences.nodeAt(nodes, target.x, target.y)
        }
        val live = node?.let { snap.nodes[it.id] }?.let(::liveLabels).orEmpty()
        val observation = Observation(snap.id, 0, snap.packageName, ScreenInfo(0, 0), nodes)
        return TapContext(observation, node, live)
    }

    private fun liveLabels(root: AccessibilityNodeInfo): List<UiNode> {
        if (!root.refresh()) return emptyList()
        val out = ArrayList<UiNode>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty() && out.size < LIVE_SCOPE_NODES) {
            val node = queue.removeFirst()
            if (!Redaction.isSensitive(facts(node))) {
                out += UiNode(
                    id = "live${out.size}",
                    role = "View",
                    text = node.text?.toString()?.take(1_000),
                    description = node.contentDescription?.toString()?.take(1_000),
                    resourceId = node.viewIdResourceName?.take(128),
                    bounds = Rect(0, 0, 0, 0),
                )
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return out
    }

    // ---- Actions ----

    private fun invalidate() {
        latest = null
    }

    suspend fun tap(observationId: String, target: Target, longPress: Boolean, double: Boolean = false) {
        val snap = requireFresh(observationId)
        invalidate()
        val press = when {
            longPress -> CursorOverlay.Press.LONG_PRESS
            double -> CursorOverlay.Press.DOUBLE
            else -> CursorOverlay.Press.TAP
        }
        val (x, y) = when (target) {
            is Target.Element -> {
                val node = liveNode(snap, target.element)
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                if (!double) {
                    val action = if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
                    val canAct = if (longPress) node.isLongClickable else node.isClickable
                    if (canAct) {
                        if (cursorOn) cursor.press(r.centerX(), r.centerY(), pointerFor(snap.ui[target.element]), press)
                        val before = screenSignature()
                        // Some apps (Instagram's caption "OK") accept the accessibility click and
                        // ignore it. If the screen shows no change, press with a real finger instead.
                        if (node.performAction(action) && (longPress || clickTookEffect(node, before))) return
                    }
                }
                r.centerX() to r.centerY()
            }
            is Target.Point -> target.x to target.y
        }
        movePillAside(x to y)
        checkGesturePoint(x, y, snap)
        if (cursorOn) {
            val under = (target as? Target.Element)?.let { snap.ui[it.element] }
                ?: io.github.aspershupadhyay.latch.policy.Consequences.nodeAt(snap.ui.values.toList(), x, y)
            cursor.press(x, y, pointerFor(under), press)
        }
        if (double) {
            // Two short presses 160 ms apart, inside the double-tap window of every Android version.
            val first = GestureDescription.StrokeDescription(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 0, 50)
            val second = GestureDescription.StrokeDescription(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 160, 50)
            dispatch(GestureDescription.Builder().addStroke(first).addStroke(second).build())
        } else {
            gesture(x, y, x, y, if (longPress) 650 else 60)
        }
    }

    /**
     * Whether an accessibility click visibly did something within
     * [CLICK_EFFECT_MS]: the screen changed, or the element went away.
     */
    private suspend fun clickTookEffect(node: AccessibilityNodeInfo, before: Int): Boolean {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < CLICK_EFFECT_MS) {
            delay(SETTLE_STEP_MS)
            if (screenSignature() != before) return true
            if (!node.refresh() || !node.isVisibleToUser) return true
        }
        return false
    }

    /** Two fingers moving apart or together, horizontally around a point. */
    suspend fun pinch(observationId: String, cx: Int, cy: Int, startSpan: Int, endSpan: Int, durationMs: Int) {
        val snap = requireFresh(observationId)
        invalidate()
        val screen = screenInfo()
        val sides = listOf(-1, 1).map { (cx + it * startSpan / 2).coerceIn(0, screen.width - 1) to cy }
        movePillAside(cx to cy, *sides.toTypedArray())
        checkGesturePoint(cx, cy, snap)
        sides.forEach { (x, y) ->
            if (overlayBounds?.contains(x, y) == true) throw ProtocolException(ErrorCode.POLICY_REFUSED, "a finger of this pinch would start on the Latch stop button")
            refuseOnApprovalCard(x, y)
        }
        fun clampX(v: Int) = v.coerceIn(0, screen.width - 1)
        val fingers = listOf(-1, 1).map { side ->
            CursorOverlay.Stroke(
                clampX(cx + side * startSpan / 2).toFloat(), cy.toFloat(),
                clampX(cx + side * endSpan / 2).toFloat(), cy.toFloat(),
            )
        }
        keyboardBounds()?.let { keyboard ->
            if (fingers.any { keyboard.contains(it.fromX.toInt(), cy) || keyboard.contains(it.toX.toInt(), cy) }) {
                throw ProtocolException(ErrorCode.POLICY_REFUSED, "the on-screen keyboard is where this pinch would run; press back to hide it")
            }
        }
        if (cursorOn) cursor.stroke(fingers, durationMs.toLong())
        val builder = GestureDescription.Builder()
        for (f in fingers) {
            val path = Path().apply {
                moveTo(f.fromX, f.fromY)
                lineTo(f.toX, f.toY)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong()))
        }
        dispatch(builder.build())
    }

    suspend fun swipe(observationId: String, fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int, holdMs: Int = 0) {
        val snap = requireFresh(observationId)
        invalidate()
        movePillAside(fromX to fromY)
        checkGesturePoint(fromX, fromY, snap)
        val screen = screenInfo()
        if (toX !in 0 until screen.width || toY !in 0 until screen.height) {
            throw ProtocolException(ErrorCode.INVALID_REQUEST, "swipe ends outside the screen")
        }
        // A swipe across the on-screen keyboard is glide typing: it would type words into
        // the focused field instead of scrolling. Text goes through type_text only.
        keyboardBounds()?.let { keyboard ->
            if (keyboard.contains(fromX, fromY) || keyboard.contains(toX, toY)) {
                throw ProtocolException(
                    ErrorCode.POLICY_REFUSED,
                    "the on-screen keyboard is open where this swipe would run and would type text; press back to hide it, or swipe above the keyboard",
                )
            }
        }
        if (cursorOn) {
            cursor.stroke(
                listOf(CursorOverlay.Stroke(fromX.toFloat(), fromY.toFloat(), toX.toFloat(), toY.toFloat())),
                durationMs.toLong(),
                holdMs.toLong(),
            )
        }
        if (holdMs > 0) {
            // A drag: hold still first (the app picks the item up), then move without lifting.
            val hold = GestureDescription.StrokeDescription(
                Path().apply { moveTo(fromX.toFloat(), fromY.toFloat()) }, 0, holdMs.toLong(), true,
            )
            dispatch(GestureDescription.Builder().addStroke(hold).build())
            val move = hold.continueStroke(
                Path().apply {
                    moveTo(fromX.toFloat(), fromY.toFloat())
                    lineTo(toX.toFloat(), toY.toFloat())
                },
                0, durationMs.toLong(), false,
            )
            dispatch(GestureDescription.Builder().addStroke(move).build())
        } else {
            gesture(fromX, fromY, toX, toY, durationMs.toLong())
        }
    }

    /** Whether a text field has input focus (a keyboard is on its way, unless a hardware one is used). */
    private fun editableFocused(): Boolean = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true

    /** Screen area of the on-screen keyboard while it is shown. */
    private fun keyboardBounds(): android.graphics.Rect? {
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
        val r = android.graphics.Rect()
        ime.getBoundsInScreen(r)
        return r.takeUnless { it.isEmpty }
    }

    /**
     * What typing did: the screen fingerprint right after the text went in
     * (before Enter), so settling waits for what the app does with it, and,
     * with submit, whether Enter visibly did anything.
     */
    class Typed(val signature: Int, val submitted: Boolean?)

    suspend fun typeText(observationId: String, elementId: String, text: String, submit: Boolean = false): Typed {
        val snap = requireFresh(observationId)
        invalidate()
        val node = liveNode(snap, elementId)
        if (!node.isEditable || node.isPassword) {
            throw ProtocolException(ErrorCode.INVALID_REQUEST, "that element is not an editable text field")
        }
        if (cursorOn) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            cursor.type(r.centerX(), r.centerY())
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            throw ProtocolException(ErrorCode.TARGET_NOT_FOUND, "the field did not accept text")
        }
        val typed = screenSignature()
        if (!submit) return Typed(typed, null)
        val content = contentSignature()
        // The keyboard's own action key for this field: Enter, Search, Send, Go, or Done.
        node.refresh()
        if (!node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
            throw ProtocolException(
                ErrorCode.TARGET_NOT_FOUND,
                "the text was typed, but this field does not take Enter; it may be a placeholder that opens the real search box. Observe and use the field that now has focus",
            )
        }
        return Typed(typed, awaitSubmitEffect(node, text, content))
    }

    /**
     * Some apps accept Enter and do nothing with it (WhatsApp sends only with
     * its Send button). Enter counts as done once the field lost the text or
     * the rest of the screen said something new; otherwise the agent is told.
     */
    private suspend fun awaitSubmitEffect(field: AccessibilityNodeInfo, text: String, before: Int): Boolean {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < SUBMIT_EFFECT_MS) {
            delay(SETTLE_STEP_MS)
            if (!field.refresh() || !field.isVisibleToUser) return true
            if (field.text?.toString()?.trimEnd('\n') != text) return true
            if (contentSignature() != before) return true
        }
        return false
    }

    // ---- Waiting and scrolling on the phone, without a round trip per step ----

    /** Waits until an element containing [text] is on screen (or none is, with [gone]). */
    suspend fun waitFor(text: String, gone: Boolean, timeoutMs: Int, maxNodes: Int): WaitResult {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val observation = observe(false, maxNodes)
            // Text inside input fields is usually what the agent typed itself, not the page answering.
            val present = observation.nodes.any { n ->
                !n.sensitive && !n.editable &&
                    (n.text?.contains(text, ignoreCase = true) == true || n.description?.contains(text, ignoreCase = true) == true)
            }
            if (present != gone) return WaitResult(true, observation)
            val now = SystemClock.uptimeMillis()
            if (now >= deadline) return WaitResult(false, observation)
            // Look again when the screen changes, and at least every 300 ms.
            val seen = lastUiEventAtMs
            val until = minOf(deadline, now + 300)
            while (SystemClock.uptimeMillis() < until && lastUiEventAtMs == seen) delay(25)
        }
    }

    /** Scrolls until an element containing [text] is visible. Returns whether it is. */
    suspend fun scrollTo(observationId: String, text: String, direction: Direction, containerId: String?, maxSwipes: Int): Boolean {
        val snap = requireFresh(observationId)
        invalidate()
        var container = if (containerId != null) liveNode(snap, containerId) else largestScrollable()
        for (attempt in 0..maxSwipes) {
            val current = rootInActiveWindow?.packageName?.toString() ?: foregroundPackage
            refuseRestrictedPackage(current)
            if (current != snap.packageName) throw ProtocolException(ErrorCode.STALE_OBSERVATION, "a different app is in the foreground now")
            if (showsText(text)) return true
            if (attempt == maxSwipes) return false
            val before = signature(container)
            val screenBefore = screenSignature()
            if (container != null && scrollOnce(container, direction)) {
                showScroll(container, direction)
            } else {
                scrollByGesture(container, direction, snap)
            }
            // Lists fling on after the gesture: wait until they stop before looking again.
            awaitSettled(screenBefore, SCROLL_QUIET_MS, SCROLL_MAX_SETTLE_MS, 0, SCROLL_EXPECT_CHANGE_MS)
            if (container?.refresh() == false) container = largestScrollable()
            // Nothing moved: the list is at its end.
            if (signature(container) == before) return showsText(text)
        }
        return false
    }

    private fun visibleNodes(): Sequence<AccessibilityNodeInfo> = sequence {
        val root = rootInActiveWindow ?: return@sequence
        val queue = ArrayDeque(listOf(root))
        var seen = 0
        while (queue.isNotEmpty() && seen < MAX_SEARCH_NODES) {
            val node = queue.removeFirst()
            seen++
            // A container reported as hidden may still hold shown elements (see observe).
            if (node.isVisibleToUser) yield(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
    }

    private fun showsText(text: String): Boolean = visibleNodes().any { node ->
        !node.isEditable && !Redaction.isSensitive(facts(node)) &&
            (node.text?.contains(text, ignoreCase = true) == true || node.contentDescription?.contains(text, ignoreCase = true) == true)
    }

    private fun largestScrollable(): AccessibilityNodeInfo? = visibleNodes().filter { it.isScrollable }.maxByOrNull { node ->
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        r.width().toLong() * r.height()
    }

    /** What the list shows, cheaply: changes when it scrolled. */
    private fun signature(container: AccessibilityNodeInfo?): Int {
        val root = container ?: rootInActiveWindow ?: return 0
        val parts = ArrayList<String>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty() && parts.size < 80) {
            val node = queue.removeFirst()
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            parts += "${node.text}|${node.contentDescription}|${r.top}|${r.left}"
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return parts.hashCode()
    }

    private fun scrollOnce(node: AccessibilityNodeInfo, direction: Direction): Boolean {
        val exact = when (direction) {
            Direction.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            Direction.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
            Direction.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
            Direction.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
        }
        if (node.actionList.any { it.id == exact.id } && node.performAction(exact.id)) return true
        val generic = if (direction == Direction.DOWN || direction == Direction.RIGHT) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return node.performAction(generic)
    }

    /** The list scrolled by itself; show the owner a grabbing hand moving the way the content went. */
    private fun showScroll(container: AccessibilityNodeInfo, direction: Direction) {
        if (!cursorOn) return
        val r = android.graphics.Rect()
        container.getBoundsInScreen(r)
        val (from, to) = when (direction) {
            Direction.DOWN -> (r.centerX() to r.top + r.height() * 2 / 3) to (r.centerX() to r.top + r.height() / 3)
            Direction.UP -> (r.centerX() to r.top + r.height() / 3) to (r.centerX() to r.top + r.height() * 2 / 3)
            Direction.RIGHT -> (r.left + r.width() * 2 / 3 to r.centerY()) to (r.left + r.width() / 3 to r.centerY())
            Direction.LEFT -> (r.left + r.width() / 3 to r.centerY()) to (r.left + r.width() * 2 / 3 to r.centerY())
        }
        cursor.stroke(listOf(CursorOverlay.Stroke(from.first.toFloat(), from.second.toFloat(), to.first.toFloat(), to.second.toFloat())), 250)
    }

    /** Fallback for lists that do not offer scroll actions: a swipe across the middle of the area, above the keyboard. */
    private suspend fun scrollByGesture(container: AccessibilityNodeInfo?, direction: Direction, snap: Snapshot) {
        val area = android.graphics.Rect()
        if (container != null) container.getBoundsInScreen(area) else area.set(0, statusBarHeight(), screenInfo().width, screenInfo().height)
        keyboardBounds()?.let { keyboard -> if (keyboard.top in (area.top + 1) until area.bottom) area.bottom = keyboard.top }
        if (area.width() < 10 || area.height() < 10) throw ProtocolException(ErrorCode.TARGET_NOT_FOUND, "nothing on screen can scroll")
        val cx = area.centerX()
        val cy = area.centerY()
        val (from, to) = when (direction) {
            Direction.DOWN -> (cx to area.top + area.height() * 4 / 5) to (cx to area.top + area.height() / 5)
            Direction.UP -> (cx to area.top + area.height() / 5) to (cx to area.top + area.height() * 4 / 5)
            Direction.RIGHT -> (area.left + area.width() * 4 / 5 to cy) to (area.left + area.width() / 5 to cy)
            Direction.LEFT -> (area.left + area.width() / 5 to cy) to (area.left + area.width() * 4 / 5 to cy)
        }
        movePillAside(from)
        checkGesturePoint(from.first, from.second, snap)
        if (cursorOn) {
            cursor.stroke(listOf(CursorOverlay.Stroke(from.first.toFloat(), from.second.toFloat(), to.first.toFloat(), to.second.toFloat())), 300)
        }
        gesture(from.first, from.second, to.first, to.second, 300)
    }

    fun global(action: GlobalAction) {
        val current = rootInActiveWindow?.packageName?.toString()
        // Home only leaves the current app, so it is how an agent gets out of Latch's own screen.
        if (!(action == GlobalAction.HOME && current == packageName)) refuseRestrictedPackage(current)
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

    /** Puts text on the clipboard (protocol 1.7). Latch never reads the clipboard. */
    suspend fun setClipboard(text: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            ?: throw ProtocolException(ErrorCode.UNSUPPORTED_CAPABILITY, "this phone has no clipboard")
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Latch", text))
    }

    fun currentPackage(): String? = (rootInActiveWindow ?: topAppWindowRoot())?.packageName?.toString() ?: foregroundPackage

    /** The launcher, which agents may always use to find apps; it shows only app names. */
    fun isHomeApp(target: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val home = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return home?.activityInfo?.packageName == target
    }

    /**
     * Latch's own screen is off limits to agents. When the owner leaves it in
     * front and an agent starts working, the phone goes to the home screen
     * instead of refusing: leaving Latch shows the agent nothing of Latch and
     * changes nothing, and refusing only made agents stop and ask. Only Latch's
     * app screen moves aside; the shade and lock screen are still refused.
     * Returns true when it pressed home.
     */
    suspend fun stepAsideFromLatch(): Boolean {
        if (!LatchApp.get(this).ownScreenShown || currentPackage() != packageName) return false
        invalidate()
        if (!performGlobalAction(GLOBAL_ACTION_HOME)) return false
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < LEAVE_LATCH_MS && currentPackage() == packageName) delay(20)
        // The home screen slides in; let it hold still before anyone reads it.
        awaitSettled(0, LEAVE_QUIET_MS, LEAVE_LATCH_MS, LEAVE_FLOOR_MS, 0)
        return true
    }

    fun screen(): ScreenInfo = screenInfo()

    private suspend fun gesture(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) {
        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    private suspend fun dispatch(description: GestureDescription) {
        val completed = suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                description,
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
        private const val LEAVE_LATCH_MS = 1_500L
        private const val LEAVE_QUIET_MS = 200L
        private const val LEAVE_FLOOR_MS = 300L
        private const val MAX_SCREENSHOT_EDGE = 1280
        private const val MOVE_TOLERANCE_PX = 8
        private const val APPROVE_ENABLE_DELAY_MS = 1_000L
        private const val LIVE_SCOPE_NODES = 64
        private const val CURSOR_HIDE_MS = 50L
        /** How long an accessibility click has to show an effect before a real tap follows. */
        private const val CLICK_EFFECT_MS = 450L
        /** Time for the Stop pill's window to move before a gesture starts where it was. */
        private const val PILL_MOVE_MS = 60L
        private const val SIGNATURE_NODES = 300
        private const val SETTLE_STEP_MS = 60L
        /** Longest wait for the keyboard after a tap on a text field. */
        private const val KEYBOARD_WAIT_MS = 1_000L
        /** How long Enter has to show an effect before the agent hears it did nothing. */
        private const val SUBMIT_EFFECT_MS = 1_000L
        private const val MAX_SEARCH_NODES = 2_000
        private const val SCROLL_QUIET_MS = 120L
        private const val SCROLL_MAX_SETTLE_MS = 800L
        private const val SCROLL_EXPECT_CHANGE_MS = 300L
    }
}

private inline fun android.animation.Animator.doOnEndCompat(crossinline action: () -> Unit) {
    addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = action()
    })
}
