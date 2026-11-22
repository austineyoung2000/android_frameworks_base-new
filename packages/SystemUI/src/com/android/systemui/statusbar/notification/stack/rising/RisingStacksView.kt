/*
 * Copyright (C) 2026 The RisingOS Android Project
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.statusbar.notification.stack.rising

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
    }
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density + 0.5f).toInt()

    init {
        id = View.generateViewId()
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        visibility = View.GONE
    }

    fun render(state: RisingStacksController.UiState) {
        visibility = if (state.visible) View.VISIBLE else View.GONE
        row.removeAllViews()
        if (!state.visible) return
        row.addView(TextView(context).apply {
            text = context.getString(R.string.rising_stacks_all)
            contentDescription = context.getString(R.string.rising_stacks_show_all)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = chipBackground(state.selected == null)
            setOnClickListener { onSelect(null) }
        }, LinearLayout.LayoutParams(dp(50), dp(44)).apply {
            marginStart = dp(8)
            marginEnd = dp(6)
        })
        state.groups.forEach { group ->
            val key = group.app
            var label = key.packageName
            val icon = try {
                val user = if (key.userId == UserHandle.USER_ALL) UserHandle.SYSTEM
                    else UserHandle.of(key.userId)
                val appContext = context.createPackageContextAsUser(key.packageName, 0, user)
                val info = appContext.applicationInfo
                label = info.loadLabel(appContext.packageManager).toString()
                appContext.packageManager.getUserBadgedIcon(
                    info.loadIcon(appContext.packageManager), user
                )
            } catch (e: Exception) {
                context.getDrawable(android.R.drawable.sym_def_app_icon)
            }
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = chipBackground(state.selected == key)
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
            }, LinearLayout.LayoutParams(dp(30), dp(30)))
            item.addView(TextView(context).apply {
                text = if (group.count > 99) "99+" else group.count.toString()
                textSize = 10f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(14)))
            row.addView(item, LinearLayout.LayoutParams(dp(48), dp(50)).apply {
                marginEnd = dp(4)
            })
        }
    }

    private fun chipBackground(selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setColor(if (selected) 0x665f6368 else Color.TRANSPARENT)
        if (selected) setStroke(dp(1), 0xaaffffff.toInt())
    }
}
