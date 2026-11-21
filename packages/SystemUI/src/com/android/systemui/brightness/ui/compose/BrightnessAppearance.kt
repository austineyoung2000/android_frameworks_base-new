/*
 * Copyright (C) 2026 The RisingOS Android Project
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.brightness.ui.compose

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal data class BrightnessAppearance(
    val style: Int = 0,
    val fill: Color? = null,
    val end: Color? = null,
    val track: Color? = null,
) {
    val customized: Boolean get() = style != 0 || fill != null || track != null
}

@Composable
internal fun rememberBrightnessAppearance(): BrightnessAppearance {
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun read(): BrightnessAppearance {
        fun enabled(key: String) =
            Settings.System.getIntForUser(resolver, key, 0, UserHandle.USER_CURRENT) != 0
        fun color(key: String, default: Int) =
            Color(Settings.System.getIntForUser(resolver, key, default, UserHandle.USER_CURRENT))
        val fillEnabled = enabled("qs_brightness_color_enabled")
        return BrightnessAppearance(
            style = Settings.System.getIntForUser(
                resolver, "qs_brightness_style", 0, UserHandle.USER_CURRENT
            ).coerceIn(0, 7),
            fill = if (fillEnabled) color("qs_brightness_color", 0xff6200ee.toInt()) else null,
            end = if (fillEnabled && enabled("qs_brightness_gradient_enabled"))
                color("qs_brightness_gradient_end_color", 0xff0000ff.toInt()) else null,
            track = if (enabled("qs_brightness_track_color_enabled"))
                color("qs_brightness_track_color", 0xff0000ff.toInt()) else null,
        )
    }
    val appearance = remember(context) { mutableStateOf(read()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { appearance.value = read() }
        }
        listOf(
            "qs_brightness_style", "qs_brightness_color_enabled", "qs_brightness_color",
            "qs_brightness_gradient_enabled", "qs_brightness_gradient_end_color",
            "qs_brightness_track_color_enabled", "qs_brightness_track_color",
        ).forEach {
            resolver.registerContentObserver(
                Settings.System.getUriFor(it), false, observer, UserHandle.USER_ALL
            )
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { appearance.value = read() }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_SWITCHED))
        appearance.value = read()
        onDispose {
            resolver.unregisterContentObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return appearance.value
}

/** Draw only the track; the surrounding composable retains its full gesture and icon area. */
internal fun Modifier.brightnessAppearance(
    appearance: BrightnessAppearance,
    fraction: Float,
    vertical: Boolean,
    defaultFill: Color,
    defaultTrack: Color,
): Modifier = drawBehind {
    val thin = appearance.style == 4
    val thickness = if (thin) (if (vertical) size.width else size.height) * 0.14f else
        (if (vertical) size.width else size.height)
    val left = if (vertical) (size.width - thickness) / 2f else 0f
    val top = if (vertical) 0f else (size.height - thickness) / 2f
    val width = if (vertical) thickness else size.width
    val height = if (vertical) size.height else thickness
    val radius = minOf(width, height) / 2f
    val rect = RoundRect(Rect(left, top, left + width, top + height), CornerRadius(radius))
    val path = Path().apply { addRoundRect(rect) }
    val fill = appearance.fill ?: defaultFill
    val track = appearance.track ?: defaultTrack
    val progress = fraction.coerceIn(0f, 1f)
    clipPath(path) {
        drawRect(track, topLeft = Offset(left, top), size = Size(width, height))
        clipRect(
            left = left,
            top = if (vertical) top + height * (1f - progress) else top,
            right = if (vertical) left + width else left + width * progress,
            bottom = top + height,
        ) {
            val end = appearance.end
            if (end == null) {
                drawRect(fill, topLeft = Offset(left, top), size = Size(width, height))
            } else {
                val brush = if (vertical) Brush.verticalGradient(
                    listOf(end, fill), startY = top, endY = top + height
                ) else Brush.horizontalGradient(
                    listOf(fill, end), startX = left, endX = left + width
                )
                drawRect(brush, topLeft = Offset(left, top), size = Size(width, height))
            }
        }
        when (appearance.style) {
            2 -> drawRect(Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                startY = top, endY = top + height * 0.55f
            ), topLeft = Offset(left, top), size = Size(width, height * 0.55f))
            3 -> drawRect(Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent),
                startY = top, endY = top + height
            ), topLeft = Offset(left, top), size = Size(width, height))
            5 -> {
                drawRect(Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.25f), Color.Transparent,
                        Color.Black.copy(alpha = 0.25f)),
                    startY = top, endY = top + height
                ), topLeft = Offset(left, top), size = Size(width, height))
            }
            6 -> drawRect(Brush.horizontalGradient(
                listOf(Color.Magenta.copy(alpha = 0.22f), Color.Transparent,
                    Color.Cyan.copy(alpha = 0.22f)), startX = left, endX = left + width
            ), topLeft = Offset(left, top), size = Size(width, height))
            7 -> drawRect(Brush.horizontalGradient(
                listOf(Color.White.copy(alpha = 0.18f), Color.Transparent,
                    Color.Black.copy(alpha = 0.18f)), startX = left, endX = left + width
            ), topLeft = Offset(left, top), size = Size(width, height))
        }
    }
    if (appearance.style == 1 || appearance.style == 5 || appearance.style == 6) {
        val stroke = if (appearance.style == 5) 2.dp.toPx() else 1.5.dp.toPx()
        drawRoundRect(
            color = if (appearance.style == 6) Color.Cyan.copy(alpha = 0.75f)
                else Color.White.copy(alpha = 0.55f),
            topLeft = Offset(left + stroke / 2f, top + stroke / 2f),
            size = Size((width - stroke).coerceAtLeast(0f), (height - stroke).coerceAtLeast(0f)),
            cornerRadius = CornerRadius((radius - stroke / 2f).coerceAtLeast(0f)),
            style = Stroke(stroke),
        )
    }
}
