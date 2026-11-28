/*
 * Copyright (C) 2023-2024 the risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.statusbar.policy

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AnalogClock
import android.widget.FrameLayout
import android.widget.TextClock

import com.android.systemui.res.R

class CustomClockViewStub @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val settingsObserver: ContentObserver
    private var currentClockView: View? = null
    private var clockStyle = 0
    private val userReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateLayout()
        }
    }

    init {
        settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                updateLayout()
            }
        }
        updateLayout()
    }

    private fun updateLayout() {
        clockStyle = Settings.System.getIntForUser(
            context.contentResolver, "qs_header_clock_style", 0, UserHandle.USER_CURRENT
        )
        currentClockView?.let {
            removeView(it)
            currentClockView = null
        }
        val layoutResId = when (clockStyle) {
            1 -> R.layout.qs_header_clock_chip
            2 -> R.layout.qs_header_clock_oos
            3 -> R.layout.qs_header_clock_analog
            else -> R.layout.qs_header_clock_simple
        }
        if (clockStyle != 0) {
            currentClockView = LayoutInflater.from(context).inflate(layoutResId, this, false)
            addView(currentClockView)
            applyClockColor(currentClockView!!)
            applyDateColor(currentClockView!!)
        }
        visibility = if (clockStyle == 0) View.GONE else View.VISIBLE
        currentClockView?.visibility = if (clockStyle == 0) View.GONE else View.VISIBLE
    }

    private fun applyClockColor(view: View) {
        val resolver = context.contentResolver
        if (Settings.System.getIntForUser(
                resolver, "qs_header_clock_color_enabled", 0, UserHandle.USER_CURRENT
            ) == 0) return

        val tint = ColorStateList.valueOf(Settings.System.getIntForUser(
            resolver, "qs_header_clock_color", android.graphics.Color.WHITE, UserHandle.USER_CURRENT
        ))

        fun colorClockViews(root: View) {
            when (root) {
                is TextClock -> root.setTextColor(tint)
                is AnalogClock -> {
                    root.dialTintList = tint
                    root.hourHandTintList = tint
                    root.minuteHandTintList = tint
                    root.secondHandTintList = tint
                }
                is ViewGroup -> for (index in 0 until root.childCount) {
                    colorClockViews(root.getChildAt(index))
                }
            }
        }

        when (clockStyle) {
            // The chip's second TextClock is a date.
            1 -> {
                fun firstClock(root: View): TextClock? {
                    if (root is TextClock) return root
                    if (root is ViewGroup) {
                        for (index in 0 until root.childCount) {
                            firstClock(root.getChildAt(index))?.let { return it }
                        }
                    }
                    return null
                }
                firstClock(view)?.setTextColor(tint)
            }
            // Both overlaid TextClocks form the time; the date is a separate sibling.
            2 -> view.findViewById<View>(R.id.oos_clock)?.let { colorClockViews(it) }
            // The analog face is the first child; the remaining TextClocks form the date.
            3 -> if (view is ViewGroup && view.childCount > 0) {
                colorClockViews(view.getChildAt(0))
            }
            // Simple uses separate TextClocks for hours/minutes and seconds.
            4 -> colorClockViews(view)
        }
    }

    private fun applyDateColor(view: View) {
        val resolver = context.contentResolver
        if (Settings.System.getIntForUser(
                resolver, "qs_header_date_color_enabled", 0, UserHandle.USER_CURRENT
            ) == 0) return

        val tint = ColorStateList.valueOf(Settings.System.getIntForUser(
            resolver, "qs_header_date_color", android.graphics.Color.WHITE, UserHandle.USER_CURRENT
        ))

        fun clocks(root: View): List<TextClock> {
            if (root is TextClock) return listOf(root)
            if (root !is ViewGroup) return emptyList()
            return (0 until root.childCount).flatMap { clocks(root.getChildAt(it)) }
        }

        when (clockStyle) {
            // Chip layout: the second text clock is the date.
            1 -> clocks(view).getOrNull(1)?.setTextColor(tint)
            // OOS layout: the date is separate from the two time layers.
            2 -> view.findViewById<TextClock>(R.id.oos_date)?.setTextColor(tint)
            // Analog layout: both text clocks display parts of the date.
            3 -> clocks(view).forEach { it.setTextColor(tint) }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        listOf(
            "qs_header_clock_style", "qs_header_clock_color_enabled", "qs_header_clock_color",
            "qs_header_date_color_enabled", "qs_header_date_color"
        ).forEach { key ->
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(key), false, settingsObserver, UserHandle.USER_ALL
            )
        }
        context.registerReceiver(userReceiver, IntentFilter(Intent.ACTION_USER_SWITCHED))
        updateLayout()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        context.contentResolver.unregisterContentObserver(settingsObserver)
        context.unregisterReceiver(userReceiver)
    }
}
