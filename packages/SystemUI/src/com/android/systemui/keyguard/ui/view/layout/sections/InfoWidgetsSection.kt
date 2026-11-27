/*
 * Copyright (C) 2025 the RisingOS Revived Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.app.ActivityManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.res.R
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.lockscreen.ExtraInfoWidgetsController
import com.android.systemui.plugins.statusbar.StatusBarStateController
import javax.inject.Inject
import org.lunaris.extrainfowidgets.ExtraInfoFreeformView

class InfoWidgetsSection
@Inject
constructor(
    private val context: Context,
    private val infoWidgetsController: ExtraInfoWidgetsController,
    private val statusBarStateController: StatusBarStateController,
) : KeyguardSection() {
    
    private var infoWidgetsView: View? = null
    private var freeformView: ExtraInfoFreeformView? = null
    private var positioningListener: ViewTreeObserver.OnPreDrawListener? = null
    private var positionAdjustmentApplied = false
    
    override fun addViews(constraintLayout: ConstraintLayout) {
        
        constraintLayout.findViewById<View?>(R.id.keyguard_info_widgets)?.let { existingView ->
            (existingView.parent as? ViewGroup)?.removeView(existingView)
        }
        
        constraintLayout.findViewById<View>(R.id.keyguard_independent_info_widgets)?.let {
            constraintLayout.removeView(it)
        }
        val overlay = ExtraInfoFreeformView(context).apply {
            id = R.id.keyguard_independent_info_widgets
            contentVisible = false
        }
        freeformView = overlay
        constraintLayout.addView(overlay)
        infoWidgetsView = infoWidgetsController.createView(context, overlay).apply {
            id = R.id.keyguard_info_widgets
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        constraintLayout.addView(infoWidgetsView)
    }
    
    override fun bindData(constraintLayout: ConstraintLayout) {
        val widget = infoWidgetsView ?: return
        positioningListener?.let {
            if (constraintLayout.viewTreeObserver.isAlive) {
                constraintLayout.viewTreeObserver.removeOnPreDrawListener(it)
            }
        }
        val widgetLocation = IntArray(2)
        val rowLocation = IntArray(2)
        val gap = (8f * context.resources.displayMetrics.density + 0.5f).toInt()
        // Restore Rising's original anchor when returning to doze or when the clock bounds vanish.
        var originalTopToTop: Int? = null
        var originalTopToBottom = ConstraintLayout.LayoutParams.UNSET
        var originalTopMargin = 0
        val listener = ViewTreeObserver.OnPreDrawListener {
            val root = constraintLayout.rootView
            val ambientPeek = statusBarStateController.isDozing &&
                Settings.System.getIntForUser(context.contentResolver, "ambient_peek_enabled", 0,
                    ActivityManager.getCurrentUser()) != 0
            val params = widget.layoutParams as? ConstraintLayout.LayoutParams
            var clockWindowBottom = (root.getTag(R.id.ambient_peek_clock_bottom) as? Float) ?: -1f
            if (clockWindowBottom <= 0f && !statusBarStateController.isDozing) {
                val clockLocation = IntArray(2)
                for (id in intArrayOf(R.id.clock_ls, ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                    ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE)) {
                    val clock = root.findViewById<View>(id) ?: continue
                    if (!clock.isShown || clock.alpha <= 0f || clock.height <= 0 ||
                        clock.height >= root.height * 0.6f) continue
                    clock.getLocationInWindow(clockLocation)
                    clockWindowBottom = maxOf(clockWindowBottom,
                        clockLocation[1] + clock.height * clock.scaleY)
                }
            }
            if (!statusBarStateController.isDozing && widget.isShown && params != null &&
                clockWindowBottom > 0f) {
                val parentLocation = IntArray(2)
                constraintLayout.getLocationInWindow(parentLocation)
                val mediaBottom = (root.getTag(R.id.rising_widgets_media_bottom) as? Float) ?: -1f
                val anchorBottom = maxOf(clockWindowBottom, mediaBottom)
                val targetMargin = (anchorBottom - parentLocation[1] + gap).toInt().coerceAtLeast(0)
                val currentLocation = IntArray(2)
                widget.getLocationInWindow(currentLocation)
                val needsAdjustment = originalTopToTop != null ||
                    currentLocation[1] < anchorBottom + gap
                if (needsAdjustment) {
                    if (originalTopToTop == null) {
                        originalTopToTop = params.topToTop
                        originalTopToBottom = params.topToBottom
                        originalTopMargin = params.topMargin
                    }
                    if (params.topToTop != ConstraintSet.PARENT_ID ||
                        params.topToBottom != ConstraintLayout.LayoutParams.UNSET ||
                        params.topMargin != targetMargin) {
                        params.topToTop = ConstraintSet.PARENT_ID
                        params.topToBottom = ConstraintLayout.LayoutParams.UNSET
                        params.topMargin = targetMargin
                        widget.layoutParams = params
                    }
                }
            } else if (params != null && originalTopToTop != null) {
                params.topToTop = originalTopToTop!!
                params.topToBottom = originalTopToBottom
                params.topMargin = originalTopMargin
                widget.layoutParams = params
                originalTopToTop = null
            }
            if (widget.isShown && ambientPeek) {
                val location = IntArray(2)
                val windowLocation = IntArray(2)
                root.getLocationOnScreen(location)
                root.getLocationInWindow(windowLocation)
                var clockBottom = if (clockWindowBottom > 0f)
                    (clockWindowBottom + location[1] - windowLocation[1]).toInt() else 0
                for (id in intArrayOf(R.id.clock_ls, R.id.aod_ls,
                    ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                    ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE)) {
                    val clock = root.findViewById<View>(id) ?: continue
                    if (!clock.isShown || clock.alpha <= 0f || clock.height <= 0 ||
                        clock.height >= root.height * 0.6f) continue
                    clock.getLocationOnScreen(rowLocation)
                    clockBottom = maxOf(clockBottom,
                        rowLocation[1] + (clock.height * clock.scaleY).toInt())
                }
                widget.getLocationOnScreen(widgetLocation)
                val baseTop = widgetLocation[1] - widget.translationY
                widget.translationY = if (clockBottom > 0)
                    (clockBottom + gap - baseTop).coerceAtLeast(0f) else 0f
                positionAdjustmentApplied = widget.translationY != 0f
            } else if (positionAdjustmentApplied) {
                widget.translationY = 0f
                positionAdjustmentApplied = false
            }
            true
        }
        positioningListener = listener
        constraintLayout.viewTreeObserver.addOnPreDrawListener(listener)
    }
    
    override fun applyConstraints(constraintSet: ConstraintSet) {
        
        constraintSet.apply {
            val overlayId = R.id.keyguard_independent_info_widgets
            connect(overlayId, ConstraintSet.LEFT, ConstraintSet.PARENT_ID, ConstraintSet.LEFT)
            connect(overlayId, ConstraintSet.RIGHT, ConstraintSet.PARENT_ID, ConstraintSet.RIGHT)
            connect(overlayId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            connect(overlayId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            constrainWidth(overlayId, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(overlayId, ConstraintSet.MATCH_CONSTRAINT)
            setVisibilityMode(overlayId, ConstraintSet.VISIBILITY_MODE_IGNORE)
            setElevation(overlayId, 2f)
            // Info widgets positioning - below WEATHER (3rd in hierarchy)
            connect(R.id.keyguard_info_widgets, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(R.id.keyguard_info_widgets, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            
            // Chain to weather (primary) or fallback hierarchy
            when {
                constraintSet.getConstraint(R.id.keyguard_weather) != null -> {
                    connect(R.id.keyguard_info_widgets, ConstraintSet.TOP, R.id.keyguard_weather, ConstraintSet.BOTTOM, 12)
                }
                constraintSet.getConstraint(R.id.default_weather_image) != null -> {
                    connect(R.id.keyguard_info_widgets, ConstraintSet.TOP, R.id.default_weather_image, ConstraintSet.BOTTOM, 12)
                }
                constraintSet.getConstraint(R.id.clock_ls) != null -> {
                    connect(R.id.keyguard_info_widgets, ConstraintSet.TOP, R.id.clock_ls, ConstraintSet.BOTTOM, 12)
                }
                constraintSet.getConstraint(R.id.keyguard_slice_view) != null -> {
                    connect(R.id.keyguard_info_widgets, ConstraintSet.TOP, R.id.keyguard_slice_view, ConstraintSet.BOTTOM, 12)
                }
                else -> {
                    connect(R.id.keyguard_info_widgets, ConstraintSet.TOP, R.id.lockscreen_clock_view, ConstraintSet.BOTTOM, 12)
                }
            }
            
            setVisibilityMode(R.id.keyguard_info_widgets, ConstraintSet.VISIBILITY_MODE_IGNORE)
            constrainHeight(R.id.keyguard_info_widgets, ConstraintSet.WRAP_CONTENT)
            constrainWidth(R.id.keyguard_info_widgets, ConstraintSet.MATCH_CONSTRAINT)
            setMargin(R.id.keyguard_info_widgets, ConstraintSet.START, 0)
            setMargin(R.id.keyguard_info_widgets, ConstraintSet.END, 0)
            // Add small bottom margin for AOD to prevent notification overlap
            setMargin(R.id.keyguard_info_widgets, ConstraintSet.BOTTOM, 6) // 6dp bottom margin for AOD
            setElevation(R.id.keyguard_info_widgets, 1f)
            
            // UNIFIED BARRIER - Create barrier in every section that could be last
            createUnifiedBarrierAndNotificationConstraints(constraintSet)
        }
    }
    
    private fun createUnifiedBarrierAndNotificationConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            // UNIFIED BARRIER - Include ALL status area elements
            createBarrier(
                R.id.smart_space_barrier_bottom,
                Barrier.BOTTOM,
                0,
                *intArrayOf(
                    R.id.keyguard_slice_view,
                    R.id.keyguard_weather,
                    R.id.default_weather_image,
                    R.id.default_weather_text,
                    R.id.clock_ls,
                    R.id.keyguard_info_widgets,
                    R.id.keyguard_widgets,
                    R.id.lockscreen_clock_view // Include fallback clock
                )
            )
            
            // Position notifications below ALL status area content
            if (constraintSet.getConstraint(R.id.left_aligned_notification_icon_container) != null) {
                connect(
                    R.id.left_aligned_notification_icon_container,
                    ConstraintSet.TOP,
                    R.id.smart_space_barrier_bottom,
                    ConstraintSet.BOTTOM,
                    context.resources.getDimensionPixelSize(R.dimen.below_clock_padding_start_icons)
                )
            }
        }
    }
    
    override fun removeViews(constraintLayout: ConstraintLayout) {
        positioningListener?.let {
            if (constraintLayout.viewTreeObserver.isAlive) {
                constraintLayout.viewTreeObserver.removeOnPreDrawListener(it)
            }
        }
        positioningListener = null
        positionAdjustmentApplied = false
        infoWidgetsView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        infoWidgetsView = null
        freeformView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        freeformView = null
    }
}
