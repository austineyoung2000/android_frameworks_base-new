/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */
package com.android.systemui.statusbar.notification.stack.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import androidx.constraintlayout.core.widgets.Optimizer
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.constraintlayout.widget.ConstraintSet.VERTICAL
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.rising.RisingStacksController
import com.android.systemui.statusbar.notification.stack.rising.RisingStacksView
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel.HorizontalPosition
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel.HorizontalPosition.EdgeToMiddle
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel.HorizontalPosition.MiddleToEdge

/**
 * Container for the stack scroller, so that the bounds can be externally specified, such as from
 * the keyguard or shade scenes.
 */
class SharedNotificationContainer(context: Context, attrs: AttributeSet?) :
    ConstraintLayout(context, attrs) {

    private val baseConstraintSet = ConstraintSet()
    private var risingStacksView: RisingStacksView? = null
    private var lastLayout: LayoutValues? = null
    private val selectorLocation = IntArray(2)
    private val shelfLocation = IntArray(2)
    private val positionStacks = ViewTreeObserver.OnPreDrawListener {
        val selector = risingStacksView
        val stack = findViewById<NotificationStackScrollLayout>(R.id.notification_stack_scroller)
        val shelf = stack?.shelf
        if (selector != null && selector.visibility == View.VISIBLE &&
            shelf != null && shelf.isLaidOut && shelf.isShown && shelf.alpha > 0f
        ) {
            getLocationOnScreen(selectorLocation)
            shelf.getLocationOnScreen(shelfLocation)
            selector.translationY = (shelfLocation[1] - selectorLocation[1] - selector.top).toFloat()
            selector.alpha = shelf.alpha
        } else if (selector != null) {
            selector.alpha = 0f
        }
        true
    }

    private data class LayoutValues(
        val position: HorizontalPosition,
        val start: Int,
        val top: Int,
        val end: Int,
        val bottom: Int,
        val alpha: Float,
    )

    fun setRisingStacksView(view: RisingStacksView) {
        risingStacksView?.let { removeView(it) }
        risingStacksView = view
        addView(view)
        view.bringToFront()
        viewTreeObserver.addOnPreDrawListener(positionStacks)
    }

    fun removeRisingStacksView(view: RisingStacksView) {
        if (risingStacksView === view) {
            if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(positionStacks)
            removeView(view)
            risingStacksView = null
        }
    }

    fun renderRisingStacks(state: RisingStacksController.UiState) {
        val selector = risingStacksView ?: return
        val wasVisible = selector.visibility == View.VISIBLE
        selector.render(state)
        if (state.visible) selector.bringToFront()
        if (wasVisible != state.visible) {
            lastLayout?.let { values ->
                updateConstraints(values.position, values.start, values.top, values.end,
                    values.bottom, findViewById<View>(R.id.notification_stack_scroller)?.alpha
                        ?: values.alpha)
            }
        }
    }

    init {
        optimizationLevel = optimizationLevel or Optimizer.OPTIMIZATION_GRAPH
        baseConstraintSet.apply {
            create(R.id.nssl_guideline, VERTICAL)
            setGuidelinePercent(R.id.nssl_guideline, 0.5f)
        }
        baseConstraintSet.applyTo(this)
    }

    fun addNotificationStackScrollLayout(nssl: View) {
        addView(nssl)
    }

    fun updateConstraints(
        horizontalPosition: HorizontalPosition,
        marginStart: Int,
        marginTop: Int,
        marginEnd: Int,
        marginBottom: Int,
        nsslAlpha: Float,
    ) {
        lastLayout = LayoutValues(
            horizontalPosition, marginStart, marginTop, marginEnd, marginBottom, nsslAlpha
        )
        val constraintSet = ConstraintSet()
        constraintSet.clone(baseConstraintSet)

        val startConstraintId =
            if (horizontalPosition is MiddleToEdge) R.id.nssl_guideline else PARENT_ID

        val endConstraintId =
            if (SceneContainerFlag.isEnabled && horizontalPosition is EdgeToMiddle) {
                R.id.nssl_guideline
            } else {
                PARENT_ID
            }

        val nsslId = R.id.notification_stack_scroller
        constraintSet.apply {
            if (SceneContainerFlag.isEnabled) {
                when (horizontalPosition) {
                    is EdgeToMiddle -> {
                        setGuidelinePercent(R.id.nssl_guideline, /* ratio= */ 0.5f)
                        constrainMaxWidth(nsslId, horizontalPosition.maxWidth)
                        // Ensure START alignment in case the maxWidth is smaller than half the
                        // parent width.
                        constraintSet.setHorizontalBias(nsslId, /* bias= */ 0f)
                    }
                    is MiddleToEdge -> {
                        setGuidelinePercent(R.id.nssl_guideline, /* ratio= */ 0.5f)
                        constrainMaxWidth(nsslId, horizontalPosition.maxWidth)
                        // Ensure END alignment in case the maxWidth is smaller than half the
                        // parent width.
                        constraintSet.setHorizontalBias(nsslId, /* bias= */ 1f)
                    }
                    else -> Unit
                }
            }

            // Constraint layout sets the alpha to 1 if it's not set explicitly in the constraint
            // set. Let's keep the current nssl alpha instead, otherwise this might interfere with
            // animations.
            setAlpha(nsslId, nsslAlpha)
            connect(nsslId, START, startConstraintId, START, marginStart)
            connect(nsslId, END, endConstraintId, END, marginEnd)
            connect(nsslId, BOTTOM, PARENT_ID, BOTTOM, marginBottom)
            val selector = risingStacksView
            val selectorVisible = selector?.visibility == View.VISIBLE
            val selectorHeight = if (selectorVisible) {
                (56f * resources.displayMetrics.density + 0.5f).toInt()
            } else 0
            connect(nsslId, TOP, PARENT_ID, TOP, marginTop)
            if (selector != null) {
                constrainWidth(selector.id, ConstraintSet.MATCH_CONSTRAINT)
                constrainHeight(selector.id, selectorHeight)
                connect(selector.id, START, startConstraintId, START, marginStart)
                connect(selector.id, END, endConstraintId, END, marginEnd)
                connect(selector.id, TOP, PARENT_ID, TOP, 0)
                setVisibility(selector.id, selector.visibility)
            }
        }
        constraintSet.applyTo(this)
    }
}
