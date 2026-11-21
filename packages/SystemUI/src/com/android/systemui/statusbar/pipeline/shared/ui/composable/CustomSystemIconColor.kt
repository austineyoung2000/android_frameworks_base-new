/*
 * Copyright (C) 2026 The RisingOS Android Project
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.statusbar.pipeline.shared.ui.composable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.android.systemui.Dependency
import com.android.systemui.tuner.TunerService

private const val ICON_COLOR_ENABLED = "system:status_bar_system_icon_color_enabled"
private const val ICON_COLOR = "system:status_bar_system_icon_color"

/** Per-user custom status icon tint, or null when the theme should supply the tint. */
@Composable
fun rememberSystemIconColor(): Color? {
    var enabled by remember { mutableStateOf(false) }
    var color by remember { mutableIntStateOf(android.graphics.Color.WHITE) }
    DisposableEffect(Unit) {
        val tuner = Dependency.get(TunerService::class.java)
        val listener = TunerService.Tunable { key, value ->
            when (key) {
                ICON_COLOR_ENABLED -> enabled = TunerService.parseIntegerSwitch(value, false)
                ICON_COLOR -> color = TunerService.parseInteger(value, android.graphics.Color.WHITE)
            }
        }
        tuner.addTunable(listener, ICON_COLOR_ENABLED, ICON_COLOR)
        onDispose { tuner.removeTunable(listener) }
    }
    return if (enabled) Color(color) else null
}
