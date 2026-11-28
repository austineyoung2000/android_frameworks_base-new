/*
 * Copyright (C) 2026 The RisingOS Android Project
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.doze.ambientpeek

import android.app.AxSandboxManager
import android.app.ActivityManager
import android.app.Notification
import android.content.Context
import android.content.pm.UserInfo
import android.graphics.Point
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.AmbientDisplayConfiguration
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import android.util.SparseArray
import android.view.View
import android.view.Display
import com.android.systemui.biometrics.AuthController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProvider
import com.android.systemui.statusbar.policy.SensitiveNotificationProtectionController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.tuner.TunerService
import dagger.Lazy
import java.lang.ref.WeakReference
import java.util.function.Consumer
import javax.inject.Inject

/** Independent AOD notification preview. Does not modify or filter the notification pipeline. */
@SysUISingleton
class AmbientPeekController @Inject constructor(
    private val context: Context,
    private val status: StatusBarStateController,
    private val tuner: TunerService,
    private val pipeline: Lazy<NotifPipeline>,
    private val visibility: KeyguardNotificationVisibilityProvider,
    private val lockscreen: NotificationLockscreenUserManager,
    private val sensitiveProtection: SensitiveNotificationProtectionController,
    private val authController: AuthController,
    private val deviceProvisioned: DeviceProvisionedController,
    private val ambientDisplayConfiguration: AmbientDisplayConfiguration,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val previewWakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SystemUI:AmbientPeekPreview")
        .apply { setReferenceCounted(false) }
    private var view = WeakReference<AmbientPeekTapView>(null)
    private var enabled = false
    private var durationMs = 8000L
    private var maxApps = 6
    private var initialized = false
    // Do not expose notification content until both bouncer state flows have reported.
    private var bouncerShowing = true
    private var selected: AppKey? = null
    private val hidePreview = Runnable { clearPreview(); render() }
    private val privacyChanged = Consumer<String> { resetAndRender() }
    private val protectionChanged = Runnable { resetAndRender() }
    private val userChanged = object : NotificationLockscreenUserManager.UserChangedListener {
        override fun onUserChanged(userId: Int) { resetAndRender() }
        override fun onCurrentProfilesChanged(currentProfiles: SparseArray<UserInfo>) {
            resetAndRender()
        }
        override fun onUserRemoved(userId: Int) { resetAndRender() }
    }
    private val statusCallback = object : StatusBarStateController.StateListener {
        override fun onDozingChanged(isDozing: Boolean) { resetAndRender() }
        override fun onStateChanged(newState: Int) { resetAndRender() }
    }
    private val collectionCallback = object : NotifCollectionListener {
        override fun onEntryAdded(entry: NotificationEntry) { resetAndRender() }
        override fun onEntryUpdated(entry: NotificationEntry) { resetAndRender() }
        override fun onEntryRemoved(entry: NotificationEntry, reason: Int) { resetAndRender() }
        override fun onRankingApplied() { resetAndRender() }
    }

    data class AppKey(val packageName: String, val userId: Int)
    data class Preview(val title: String, val body: String, val redacted: Boolean)
    data class Group(val app: AppKey, val count: Int, val previews: List<Preview>)

    fun attach(target: AmbientPeekTapView) {
        view = WeakReference(target)
        if (!initialized) {
            initialized = true
            pipeline.get().addCollectionListener(collectionCallback)
            visibility.addOnStateChangedListener(privacyChanged)
            sensitiveProtection.registerSensitiveStateListener(protectionChanged)
            lockscreen.addUserChangedListener(userChanged)
            status.addCallback(statusCallback)
            tuner.addTunable({ _, value ->
                enabled = TunerService.parseIntegerSwitch(value, false)
                resetAndRender()
            }, "system:ambient_peek_enabled")
            tuner.addTunable({ _, value ->
                durationMs = (value?.toIntOrNull() ?: 8).coerceIn(3, 30) * 1000L
                resetAndRender()
            }, "system:ambient_peek_duration")
            tuner.addTunable({ _, value ->
                maxApps = (value?.toIntOrNull() ?: 6).coerceIn(2, 6)
                render()
            }, "system:ambient_peek_max_apps")
            tuner.addTunable({ _, _ -> render() }, "system:ambient_peek_icon_size")
        }
        render()
    }

    fun detach(target: AmbientPeekTapView) {
        if (view.get() === target) {
            view.clear()
            clearPreview()
            bouncerShowing = true
        }
    }

    private fun isActive(): Boolean {
        if (!enabled || !deviceProvisioned.isDeviceProvisioned || !status.isDozing ||
            status.state != StatusBarState.KEYGUARD || bouncerShowing) return false
        val user = ActivityManager.getCurrentUser()
        if (!ambientDisplayConfiguration.enabled(user) ||
            !ambientDisplayConfiguration.tapGestureEnabled(user)) return false
        val tapTypes = ambientDisplayConfiguration.tapSensorTypeMapping().filter { it.isNotEmpty() }.toSet()
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        return sensors.getSensorList(Sensor.TYPE_ALL).any { it.stringType in tapTypes }
    }

    fun setBouncerShowing(showing: Boolean) {
        if (bouncerShowing != showing) {
            bouncerShowing = showing
            resetAndRender()
        }
    }

    private fun groups(): List<Group> {
        if (!isActive()) return emptyList()
        val entries = pipeline.get().allNotifs.filter { entry ->
            val user = entry.sbn.user.identifier
            (user == UserHandle.USER_ALL ||
                (lockscreen.isCurrentProfile(user) && lockscreen.isProfileAvailable(user))) &&
                entry.dismissState == NotificationEntry.DismissState.NOT_DISMISSED &&
                !entry.isRowDismissed && !entry.ranking.isSuspended &&
                !entry.shouldSuppressAmbient() &&
                !visibility.shouldHideNotificationOnKeyguard(entry)
        }.sortedWith(compareByDescending<NotificationEntry> { it.sbn.postTime }
            .thenBy { it.ranking.rank })
        val children = entries.filter { !it.sbn.notification.isGroupSummary }
            .map { it.sbn.groupKey }.toSet()
        val counts = LinkedHashMap<AppKey, MutableList<Preview>>()
        for (entry in entries) {
            val sbn = entry.sbn
            val n = sbn.notification
            if (n.isGroupSummary && sbn.groupKey in children) continue
            val key = AppKey(sbn.packageName, sbn.user.identifier)
            val isLocked = n.extras.getBoolean(AxSandboxManager.EXTRA_NOTIFICATION_APP_LOCKED, false)
            val isRedacted = isLocked || lockscreen.getRedactionType(entry) != 0 ||
                (sensitiveProtection.isSensitiveStateActive &&
                    sensitiveProtection.shouldProtectNotification(entry))
            val title = if (isRedacted) "" else
                n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(160)
            val text = if (isRedacted) "" else
                n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().take(8192)
            counts.getOrPut(key) { ArrayList() }.add(Preview(title, text, isRedacted))
        }
        return counts.entries.take(maxApps).map { (key, entries) ->
            Group(key, entries.size, entries.take(50))
        }
    }

    private fun resetAndRender() {
        clearPreview()
        render()
    }

    private fun clearPreview() {
        handler.removeCallbacks(hidePreview)
        selected = null
        if (previewWakeLock.isHeld) previewWakeLock.release()
    }

    private fun render() {
        val target = view.get() ?: return
        val sensorLocation = authController.fingerprintSensorLocation
        val sensorRadius = authController.udfpsRadius
        val sensorUnknown = authController.isUdfpsEnrolled(ActivityManager.getCurrentUser()) &&
            (sensorLocation == null || sensorRadius <= 0f)
        if (!isActive() || sensorUnknown) {
            target.visibility = View.GONE
            target.setItems(emptyList(), null, null, 0f)
            return
        }
        val groups = groups()
        if (selected != null && groups.none { it.app == selected }) clearPreview()
        target.setItems(groups, selected, sensorLocation, sensorRadius)
        target.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Accepts physical display coordinates already scaled by the doze host. */
    fun onSensorTap(screenX: Float, screenY: Float): Boolean {
        val target = view.get() ?: return false
        val display = target.display ?: return false
        if (!isActive() || !target.isShown || !target.isLaidOut ||
            display.state == Display.STATE_OFF || display.state == Display.STATE_UNKNOWN) return false
        var ancestor: View? = target
        while (ancestor != null) {
            if (ancestor.visibility != View.VISIBLE || ancestor.alpha < 0.05f) return false
            ancestor = ancestor.parent as? View
        }
        // Re-evaluate visibility, redaction and fingerprint location before acting on the tap.
        render()
        if (target.handlePageTap(screenX, screenY)) {
            clearPreview()
            render()
            return true
        }
        val app = target.appAt(screenX, screenY) ?: return false
        val newSelection = if (selected == app) null else app
        clearPreview()
        selected = newSelection
        if (selected != null) {
            // Keep only the CPU awake while the preview is visible, with a bounded failsafe.
            // The display remains in its existing AOD state.
            previewWakeLock.acquire(durationMs + 1000L)
            handler.postDelayed(hidePreview, durationMs)
        }
        render()
        return true
    }
}
