/*
 * Copyright (C) 2026 The RisingOS Android Project
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.statusbar.notification.stack.rising

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.UserHandle
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.res.R

/** A lock-screen-only app selector. It does not own or dismiss notifications. */
class RisingStacksView(
    context: Context,
    private val onSelect: (RisingStacksModel.AppKey?) -> Unit,
) : HorizontalScrollView(context) {
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    private val iconPack = RisingStacksIconPack(context)
    private var lastSelected: RisingStacksModel.AppKey? = null
    private var hasRendered = false
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density + 0.5f).toInt()
    var preferredHeightPx: Int = dp(56)
        private set

    fun headerHeightPx(state: RisingStacksController.UiState): Int =
        dp(state.iconSize.coerceIn(24, 60) + 26 + if (state.effect in 3..4) 36 else 0)

    init {
        id = View.generateViewId()
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        clipChildren = false
        clipToPadding = false
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        visibility = View.GONE
    }

    fun render(state: RisingStacksController.UiState) {
        preferredHeightPx = headerHeightPx(state)
        val iconSize = state.iconSize.coerceIn(24, 60)
        val glowSpace = if (state.effect in 3..4) 36 else 0
        val slotWidth = maxOf(48, iconSize + 8)
        visibility = if (state.visible) View.VISIBLE else View.GONE
        row.removeAllViews()
        if (!state.visible) return
        val selectionChanged = hasRendered && lastSelected != state.selected
        lastSelected = state.selected
        hasRendered = true
        state.groups.forEachIndexed { index, group ->
            val key = group.app
            var label = key.packageName
            val icon = try {
                val user = if (key.userId == UserHandle.USER_ALL) UserHandle.SYSTEM
                    else UserHandle.of(key.userId)
                val appContext = context.createPackageContextAsUser(key.packageName, 0, user)
                val info = appContext.applicationInfo
                label = info.loadLabel(appContext.packageManager).toString()
                val drawable = iconPack.icon(state.iconPack, appContext, key.packageName)
                    ?: info.loadIcon(appContext.packageManager)
                appContext.packageManager.getUserBadgedIcon(drawable, user)
            } catch (e: Exception) {
                context.getDrawable(android.R.drawable.sym_def_app_icon)
            }
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = chipBackground(state.selected == key, state)
                contentDescription = context.getString(
                    R.string.rising_stacks_app_description, label, group.count
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { onSelect(key) }
            }
            item.addView(ImageView(context).apply {
                setImageDrawable(icon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)))
            item.addView(TextView(context).apply {
                text = if (group.count > 99) "99+" else group.count.toString()
                textSize = 10f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(14)))
            if (selectionChanged && state.selected == key) {
                item.alpha = 0.7f
                item.animate().alpha(1f).setDuration(90).start()
            }
            row.addView(item, LinearLayout.LayoutParams(dp(slotWidth), dp(iconSize + 20 + glowSpace)).apply {
                marginStart = if (index == 0) dp(if (glowSpace > 0) 20 else 8) else 0
                marginEnd = dp(state.iconSpacing.coerceIn(0, 32))
            })
        }
        val all = TextView(context).apply {
            text = context.getString(R.string.rising_stacks_all)
            contentDescription = context.getString(R.string.rising_stacks_show_all)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = chipBackground(state.selected == null, state)
            setOnClickListener { onSelect(null) }
        }
        if (selectionChanged && state.selected == null) {
            all.alpha = 0.7f
            all.animate().alpha(1f).setDuration(90).start()
        }
        row.addView(all, LinearLayout.LayoutParams(dp(maxOf(50, slotWidth)), dp(iconSize + 14 + glowSpace)).apply {
            marginStart = 0
            marginEnd = dp(if (glowSpace > 0) 20 else 6)
        })
    }

    private fun chipBackground(selected: Boolean, state: RisingStacksController.UiState) =
        if (!selected) null else StacksChipDrawable(density, state.effect, state.firstColor,
            state.secondColor, state.outline, state.glowIntensity)
}
