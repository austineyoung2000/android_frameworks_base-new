/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.pie

import android.app.StatusBarManager
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.hardware.input.InputManagerGlobal
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowManagerGlobal
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.shared.system.InputChannelCompat
import com.android.systemui.shared.system.InputMonitorCompat
import com.android.systemui.statusbar.notification.stack.rising.RisingStacksIconPack
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pie controls: hold still on the lower part of a screen edge and a pie of shortcuts opens
 * around the thumb. Slide to a slice and let go. A quick swipe from the edge still goes back,
 * because the pie only takes the touch after a short hold.
 */
@SysUISingleton
class PieController
@Inject
constructor(
    private val context: Context,
    private val keyguardStateController: KeyguardStateController,
    private val flashlightController: FlashlightController,
    private val activityStarter: ActivityStarter,
) : CoreStartable {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowContext: Context
    private lateinit var windowManager: WindowManager
    private var monitor: InputMonitorCompat? = null
    private var receiver: InputChannelCompat.InputEventReceiver? = null
    private var view: PieView? = null

    private var enabled = false
    private var edges = EDGES_BOTH
    private var zone = ZONE_LOWER
    private var items = DEFAULT_ITEMS
    private var apps = emptyList<String>()
    private var appsEnabled = true
    private var appIconPack: String? = null
    private val iconPack = RisingStacksIconPack(context)
    private var style = STYLE_CLASSIC
    private var holdDuration = 250L
    private var edgeWidth = 16f
    private var movementTolerance = 100
    private var showClock = true
    private var size = 100

    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val open = Runnable { openPie() }

    override fun start() {
        val display = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        windowContext = context.createWindowContext(
            display, WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL, null
        )
        windowManager = windowContext.getSystemService(WindowManager::class.java)
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = readSettings()
        }
        for (key in KEYS) {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL
            )
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                closePie(run = false)
                readSettings()
            }
        }, IntentFilter(Intent.ACTION_USER_SWITCHED), Context.RECEIVER_EXPORTED)
        readSettings()
    }

    private fun readSettings() {
        val resolver = context.contentResolver
        fun int(key: String, def: Int) =
            Settings.System.getIntForUser(resolver, key, def, UserHandle.USER_CURRENT)
        enabled = int(KEY_ENABLED, 0) == 1
        edges = int(KEY_EDGES, EDGES_BOTH)
        zone = int(KEY_ZONE, ZONE_LOWER)
        style = int(KEY_STYLE, STYLE_CLASSIC).coerceIn(STYLE_NEON, STYLE_CLASSIC)
        holdDuration = int(KEY_HOLD, 250).coerceIn(150, 1000).toLong()
        edgeWidth = int(KEY_EDGE_WIDTH, 16).coerceIn(8, 32).toFloat()
        movementTolerance = int(KEY_TOLERANCE, 100).coerceIn(50, 200)
        showClock = int(KEY_CLOCK, 1) == 1
        size = int(KEY_SIZE, 100).coerceIn(60, 150)
        items = Settings.System.getStringForUser(resolver, KEY_ITEMS, UserHandle.USER_CURRENT)
            ?: DEFAULT_ITEMS
        appsEnabled = int(KEY_APPS_ENABLED, 1) == 1
        apps = (Settings.System.getStringForUser(resolver, KEY_APPS, UserHandle.USER_CURRENT) ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_APPS)
        appIconPack = Settings.System.getStringForUser(resolver, KEY_APP_ICON_PACK, UserHandle.USER_CURRENT)
        if (enabled && monitor == null) {
            try {
                val inputMonitor = InputMonitorCompat(TAG, Display.DEFAULT_DISPLAY)
                monitor = inputMonitor
                receiver = inputMonitor.getInputReceiver(
                    Looper.getMainLooper(), Choreographer.getInstance()
                ) { event -> onInputEvent(event) }
            } catch (e: RuntimeException) {
                receiver?.dispose()
                monitor?.dispose()
                receiver = null
                monitor = null
                Log.e(TAG, "Unable to start Pie input monitoring", e)
            }
        } else if (!enabled && monitor != null) {
            receiver?.dispose()
            monitor?.dispose()
            receiver = null
            monitor = null
            closePie(run = false)
        }
    }

    private fun inZone(x: Float, y: Float): Boolean {
        val metrics = windowContext.resources.displayMetrics
        val edge = edgeWidth * metrics.density
        val left = x <= edge && edges != EDGES_RIGHT
        val right = x >= metrics.widthPixels - edge && edges != EDGES_LEFT
        if (!left && !right) return false
        val h = metrics.heightPixels.toFloat()
        return when (zone) {
            ZONE_MIDDLE -> y in h / 3f..h * 2f / 3f
            ZONE_WHOLE -> true
            else -> y >= h * 2f / 3f
        }
    }

    private fun onInputEvent(event: InputEvent) {
        if (event !is MotionEvent || !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (keyguardStateController.isShowing || !inZone(event.rawX, event.rawY)) return
                downX = event.rawX
                downY = event.rawY
                tracking = true
                handler.postDelayed(open, holdDuration)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return
                val fan = view
                if (fan == null) {
                    if (hypot(event.rawX - downX, event.rawY - downY) > slop * movementTolerance / 100f) {
                        tracking = false
                        handler.removeCallbacks(open)
                    }
                } else if (fan.select(event.rawX, event.rawY)) {
                    fan.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
                }
            }
            MotionEvent.ACTION_UP -> closePie(run = true)
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> closePie(run = false)
        }
    }

    private fun openPie() {
        if (!tracking || !enabled || keyguardStateController.isShowing) return
        val slices = items.split(',').map { it.trim() }.filter { it in ACTIONS }
        if (slices.isEmpty()) return
        val userContext = currentUserContext()
        // Keep each configured shortcut in the ring even if its icon cannot be loaded.
        // PackageManager lookups must use a concrete user, not USER_CURRENT (-2).
        val appIcons = (if (appsEnabled) apps else emptyList()).associateWith { pkg ->
            runCatching {
                if (userContext == null) context.packageManager.defaultActivityIcon else
                    iconPack.icon(appIconPack, userContext, pkg)
                        ?: userContext.packageManager.getApplicationIcon(pkg)
            }.getOrElse { error ->
                Log.w(TAG, "Unable to load Pie shortcut icon for $pkg", error)
                context.packageManager.defaultActivityIcon
            }
        }
        val pie = PieView(windowContext, slices, appIcons, downX, downY, style, showClock, size / 100f)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        lp.setFitInsetsTypes(0)
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.setTrustedOverlay()
        lp.title = "RRPie"
        try {
            windowManager.addView(pie, lp)
        } catch (e: RuntimeException) {
            return
        }
        view = pie
        monitor?.pilferPointers()
        pie.post { pie.performHapticFeedback(HapticFeedbackConstants.GESTURE_START) }
    }

    private fun closePie(run: Boolean) {
        tracking = false
        handler.removeCallbacks(open)
        val pie = view ?: return
        view = null
        val slice = pie.selectedSlice()
        try {
            windowManager.removeView(pie)
        } catch (e: RuntimeException) {
            // Already gone.
        }
        if (run && slice != null && enabled && !keyguardStateController.isShowing) run(slice)
    }

    private fun run(slice: String) {
        if (slice.startsWith(APP_PREFIX)) {
            val pkg = slice.removePrefix(APP_PREFIX)
            val userContext = currentUserContext() ?: return
            val intent = runCatching {
                userContext.packageManager.getLaunchIntentForPackage(pkg)
            }.getOrNull() ?: return
            activityStarter.postStartActivityDismissingKeyguard(
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 0
            )
            return
        }
        when (slice) {
            BACK -> key(KeyEvent.KEYCODE_BACK)
            HOME -> key(KeyEvent.KEYCODE_HOME)
            RECENTS -> key(KeyEvent.KEYCODE_APP_SWITCH)
            NOTIFICATIONS -> context.getSystemService(StatusBarManager::class.java)
                ?.expandNotificationsPanel()
            FLASHLIGHT -> flashlightController.setFlashlight(!flashlightController.isEnabled)
            SCREENSHOT -> key(KeyEvent.KEYCODE_SYSRQ)
            POWER -> try {
                WindowManagerGlobal.getWindowManagerService()?.showGlobalActions()
            } catch (e: Exception) {
                // Not now.
            }
            ASSISTANT -> key(KeyEvent.KEYCODE_ASSIST)
            CAMERA -> activityStarter.postStartActivityDismissingKeyguard(
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 0
            )
        }
    }

    private fun currentUserContext(): Context? = runCatching {
        context.createContextAsUser(UserHandle.of(ActivityManager.getCurrentUser()), 0)
    }.getOrElse { error ->
        Log.w(TAG, "Unable to create current-user Pie context", error)
        null
    }

    private fun key(code: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(now, now, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_VIRTUAL_HARD_KEY, InputDevice.SOURCE_KEYBOARD)
            InputManagerGlobal.getInstance().injectInputEvent(
                event, android.hardware.input.InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
            )
        }
    }

    /** The pie itself, drawn around the point where the thumb is held. */
    private class PieView(
        context: Context,
        private val slices: List<String>,
        appIcons: Map<String, Drawable>,
        private val cx: Float,
        private val cy: Float,
        private val style: Int,
        private val showClock: Boolean,
        scale: Float,
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val inner = slices.take(3)
        private val outer = slices.drop(3)
        private val appSlices = appIcons.keys.map { APP_PREFIX + it }
        private val r0 = 36 * density * scale
        private val r1 = 84 * density * scale
        private val r2 = 88 * density * scale
        private val r3 = 140 * density * scale
        private val r4 = 144 * density * scale
        private val r5 = 196 * density * scale
        private val onRight = cx > resources.displayMetrics.widthPixels / 2f
        // The half facing into the screen.
        private val start = if (onRight) 90f else -90f
        private val accent = context.getColor(android.R.color.system_accent1_400)
        private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            maskFilter = BlurMaskFilter(10 * density, BlurMaskFilter.Blur.NORMAL)
        }
        private var glowBitmap: Bitmap? = null
        private val glowBounds = RectF()
        private var glowSlice: String? = null
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val path = Path()
        private val icons = HashMap<String, Drawable>()
        private var selected: String? = null

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            for (slice in slices) {
                val icon = runCatching { context.getDrawable(ICONS.getValue(slice))?.mutate() }
                    .getOrNull()
                val fallback = NAV_FALLBACK_ICONS[slice]
                val resolved = if (fallback != null && (icon == null || !hasVisiblePixels(icon))) {
                    context.getDrawable(fallback)?.mutate()
                } else icon
                resolved?.let { icons[slice] = it }
            }
            appIcons.forEach { (pkg, icon) -> icons[APP_PREFIX + pkg] = icon.mutate() }
        }

        /** A theme can resolve a navigation vector with zero alpha; tint cannot make it visible. */
        private fun hasVisiblePixels(icon: Drawable): Boolean = runCatching {
            val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            icon.setTint(Color.WHITE)
            icon.setBounds(0, 0, bitmap.width, bitmap.height)
            icon.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            pixels.any { Color.alpha(it) != 0 }
        }.getOrDefault(false)

        fun selectedSlice() = selected

        fun select(x: Float, y: Float): Boolean {
            val d = hypot(x - cx, y - cy)
            val angle = norm(Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat())
            val ring = when {
                d in r0..r1 -> inner
                d in r2..(if (appSlices.isEmpty()) r3 * 1.4f else r3) -> outer.ifEmpty { inner }
                appSlices.isNotEmpty() && d in r4..r5 * 1.4f -> appSlices
                else -> null
            }
            val pick = ring?.let { list ->
                val sweep = 180f / list.size
                val rel = norm(angle - start)
                if (rel > 180f) null else list.getOrNull((rel / sweep).toInt().coerceAtMost(list.size - 1))
            }
            if (pick == selected) return false
            selected = pick
            invalidate()
            return pick != null
        }

        private fun norm(a: Float): Float = ((a % 360f) + 360f) % 360f

        override fun onDraw(canvas: Canvas) {
            val glass = style == STYLE_GLASS
            val classic = style == STYLE_CLASSIC
            drawRing(canvas, inner, r0, r1, glass, classic)
            if (outer.isNotEmpty()) drawRing(canvas, outer, r2, r3, glass, classic)
            if (appSlices.isNotEmpty()) drawRing(canvas, appSlices, r4, r5, glass, classic)
            if (showClock) {
                val radius = if (appSlices.isEmpty()) r3 else r5
                val labelX = cx + (if (onRight) -1 else 1) * (radius * 0.6f)
                val labelY = (cy - radius - 28 * density).coerceAtLeast(30 * density)
                textPaint.textSize = 30 * density
                textPaint.alpha = 255
                canvas.drawText(DateFormat.getTimeFormat(context).format(System.currentTimeMillis()),
                    labelX, labelY, textPaint)
                val level = context.getSystemService(BatteryManager::class.java)
                    ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
                textPaint.textSize = 13 * density
                textPaint.alpha = 190
                canvas.drawText("$level%", labelX, labelY + 20 * density, textPaint)
            }
        }

        // Cache the selected slice's soft halo on a software canvas. The normal
        // hardware canvas ignores mask filters; keep the blur outside the slice.
        private fun drawNeonHalo(canvas: Canvas, slice: String, shape: Path) {
            val bounds = RectF()
            shape.computeBounds(bounds, true)
            if (bounds.isEmpty) return
            val blur = 10.4f * density // Same blur as notification neon at 60%.
            val pad = kotlin.math.ceil(blur * 2f).toInt()
            if (glowBitmap == null || glowSlice != slice || glowBounds != bounds) {
                val w = kotlin.math.ceil((bounds.width() + pad * 2f) * .5f).toInt().coerceAtLeast(1)
                val h = kotlin.math.ceil((bounds.height() + pad * 2f) * .5f).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).apply {
                    scale(.5f, .5f)
                    translate(pad - bounds.left, pad - bounds.top)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = accent
                        alpha = 99 // 65% halo opacity at 60% intensity.
                        maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
                    }
                    drawPath(shape, paint)
                }
                glowBitmap = bitmap
                glowSlice = slice
                glowBounds.set(bounds)
            }
            canvas.save()
            canvas.clipOutPath(shape)
            glowBitmap?.let { bitmap ->
                canvas.drawBitmap(bitmap, null, RectF(bounds.left - pad, bounds.top - pad,
                    bounds.right + pad, bounds.bottom + pad), bitmapPaint)
            }
            canvas.restore()
        }

        private fun drawRing(canvas: Canvas, list: List<String>, ra: Float, rb: Float,
                glass: Boolean, classic: Boolean) {
            val sweep = 180f / list.size
            val gap = 1.5f
            list.forEachIndexed { i, slice ->
                val a0 = start + i * sweep + gap / 2
                path.reset()
                path.arcTo(RectF(cx - rb, cy - rb, cx + rb, cy + rb), a0, sweep - gap)
                path.arcTo(RectF(cx - ra, cy - ra, cx + ra, cy + ra), a0 + sweep - gap, -(sweep - gap))
                path.close()
                val on = slice == selected
                segPaint.color = when {
                    on && style == STYLE_NEON -> (accent and 0x00ffffff) or 0x38000000
                    on -> accent
                    glass -> 0x59FFFFFF
                    classic -> 0xE6202020.toInt()
                    else -> 0xE6120A0C.toInt()
                }
                if (on && style == STYLE_NEON) drawNeonHalo(canvas, slice, path)
                if (on && glass) canvas.drawPath(path, glowPaint)
                canvas.drawPath(path, segPaint)
                if (on && style == STYLE_NEON) {
                    canvas.save()
                    canvas.clipPath(path)
                    strokePaint.color = accent
                    for (layer in 5 downTo 1) {
                        strokePaint.strokeWidth = layer * 2f * density
                        strokePaint.alpha = if (layer == 1) 138 else 15
                        canvas.drawPath(path, strokePaint)
                    }
                    canvas.restore()
                    strokePaint.strokeWidth = density
                } else {
                    strokePaint.color = if (glass) 0x80FFFFFF.toInt() else 0x26FFFFFF
                    canvas.drawPath(path, strokePaint)
                }
                val mid = Math.toRadians((a0 + (sweep - gap) / 2).toDouble())
                val rm = (ra + rb) / 2
                val ix = cx + rm * cos(mid).toFloat()
                val iy = cy + rm * sin(mid).toFloat()
                val half = 12 * density * (if (on) 1.2f else 1f)
                icons[slice]?.let {
                    if (!slice.startsWith(APP_PREFIX)) {
                        it.setTint(if (glass && !on) 0xFF1B1517.toInt() else Color.WHITE)
                    }
                    it.setBounds((ix - half).toInt(), (iy - half).toInt(), (ix + half).toInt(), (iy + half).toInt())
                    it.draw(canvas)
                }
            }
        }
    }

    companion object {
        private const val TAG = "RRPie"
        const val KEY_ENABLED = "pie_controls"
        /** 0 left, 1 right, 2 both. */
        const val KEY_EDGES = "pie_edges"
        /** 0 lower third, 1 middle third, 2 whole edge. */
        const val KEY_ZONE = "pie_zone"
        /** Comma-separated slices, inner ring first. */
        const val KEY_ITEMS = "pie_items"
        const val KEY_APPS = "pie_apps"
        const val KEY_APPS_ENABLED = "pie_apps_enabled"
        const val KEY_APP_ICON_PACK = "pie_app_icon_pack"
        private const val APP_PREFIX = "app:"
        private const val MAX_APPS = 6
        /** 0 neon, 1 glass, 2 classic. */
        const val KEY_STYLE = "pie_style"
        const val KEY_CLOCK = "pie_clock"
        /** 60 to 150, percent. */
        const val KEY_SIZE = "pie_size"
        const val KEY_HOLD = "pie_hold_duration"
        const val KEY_EDGE_WIDTH = "pie_edge_width"
        const val KEY_TOLERANCE = "pie_movement_tolerance"
        private val KEYS = arrayOf(KEY_ENABLED, KEY_EDGES, KEY_ZONE, KEY_ITEMS, KEY_STYLE, KEY_CLOCK,
            KEY_SIZE, KEY_HOLD, KEY_EDGE_WIDTH, KEY_TOLERANCE, KEY_APPS, KEY_APP_ICON_PACK, KEY_APPS_ENABLED)

        private const val EDGES_LEFT = 0
        private const val EDGES_RIGHT = 1
        private const val EDGES_BOTH = 2
        private const val ZONE_LOWER = 0
        private const val ZONE_MIDDLE = 1
        private const val ZONE_WHOLE = 2
        private const val STYLE_NEON = 0
        private const val STYLE_GLASS = 1
        private const val STYLE_CLASSIC = 2

        private const val BACK = "back"
        private const val HOME = "home"
        private const val RECENTS = "recents"
        private const val NOTIFICATIONS = "notifications"
        private const val FLASHLIGHT = "flashlight"
        private const val SCREENSHOT = "screenshot"
        private const val POWER = "power"
        private const val ASSISTANT = "assistant"
        private const val CAMERA = "camera"
        private const val DEFAULT_ITEMS = "back,home,recents,notifications,flashlight,screenshot,power"
        private val ICONS = mapOf(
            BACK to R.drawable.ic_sysbar_back, HOME to R.drawable.ic_sysbar_home,
            RECENTS to R.drawable.ic_sysbar_recent, NOTIFICATIONS to R.drawable.ic_notification_bell,
            FLASHLIGHT to R.drawable.qs_flashlight_icon_on, SCREENSHOT to R.drawable.ic_screen_capture_camera_outline,
            POWER to R.drawable.ic_qs_footer_power, ASSISTANT to R.drawable.ic_assistant,
            CAMERA to R.drawable.ic_camera,
        )
        // Keep themed SystemUI navigation icons where they render; these bundled icons
        // provide a visible fallback for themes that resolve a transparent vector.
        private val NAV_FALLBACK_ICONS = mapOf(
            BACK to R.drawable.rr_pie_back,
            HOME to R.drawable.rr_pie_home,
            RECENTS to R.drawable.rr_pie_recents,
        )
        private val ACTIONS = ICONS.keys
    }
}
