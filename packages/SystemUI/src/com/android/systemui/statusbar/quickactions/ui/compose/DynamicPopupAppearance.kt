/* Copyright (C) 2026 The RisingOS Android Project
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.statusbar.quickactions.ui.compose

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.android.systemui.qs.panels.ui.compose.infinitegrid.neonGlowModifier
import com.android.systemui.qs.panels.ui.compose.infinitegrid.neonHaloModifier

internal data class DynamicPopupAppearance(
    val mode: Int, val first: Color, val second: Color, val dualColor: Boolean,
    val outline: Boolean, val intensity: Int,
)

@Composable
internal fun rememberDynamicPopupAppearance(prefix: String): DynamicPopupAppearance {
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun read(): DynamicPopupAppearance {
        fun get(suffix: String, fallback: Int) = Settings.System.getIntForUser(
            resolver, prefix + suffix, fallback, UserHandle.USER_CURRENT)
        return DynamicPopupAppearance(
            get("_popup_color_mode", 0).coerceIn(0, 4),
            Color(get("_popup_first_color", android.graphics.Color.BLACK)),
            Color(get("_popup_second_color", android.graphics.Color.BLUE)),
            get("_popup_dual_color", 0) != 0,
            get("_popup_outline", 0) != 0,
            get("_popup_glow_intensity", 60).coerceIn(0, 100),
        )
    }
    var appearance by remember(context, prefix) { mutableStateOf(read()) }
    DisposableEffect(context, prefix) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { appearance = read() }
        }
        listOf("_popup_color_mode", "_popup_first_color", "_popup_second_color",
            "_popup_dual_color", "_popup_outline", "_popup_glow_intensity").forEach {
            resolver.registerContentObserver(Settings.System.getUriFor(prefix + it), false,
                observer, UserHandle.USER_ALL)
        }
        appearance = read()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return appearance
}

/** Background and glow are separate, so neon does not paint an opaque interior. */
@Composable
internal fun DynamicPopupSurface(
    appearance: DynamicPopupAppearance,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    nativeBackground: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val custom = appearance.mode in 2..4
    val neon = appearance.mode in 3..4
    val second = if (appearance.mode == 4 || appearance.mode == 2 && appearance.dualColor)
        appearance.second else appearance.first
    val neonEffect = if (appearance.mode == 4) 6 else if (neon) 5 else 0
    val halo = neonHaloModifier(neonEffect, appearance.first, second, appearance.intensity, shape)
    val edge = neonGlowModifier(neonEffect, appearance.first, second, appearance.intensity, shape)
    val fill = if (neon) listOf(appearance.first.copy(alpha = .20f), second.copy(alpha = .20f))
        else listOf(appearance.first, second)
    Box(modifier.then(halo), propagateMinConstraints = true) {
        Box(Modifier.matchParentSize().clip(shape).then(
            if (custom) Modifier.background(Brush.horizontalGradient(fill)) else Modifier
        ).then(edge).then(
            if (appearance.outline) Modifier.border(1.dp,
                if (custom) appearance.first else Color.White.copy(alpha = .7f), shape)
            else Modifier
        )) {
            if (!custom) nativeBackground()
        }
        Box(Modifier.clip(shape), propagateMinConstraints = true) {
            if (custom) {
                val text = if (!neon && (appearance.first.luminance() + second.luminance()) / 2f > .5f)
                    Color.Black else Color.White
                MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
                    onSurface = text, onSurfaceVariant = text.copy(alpha = .78f))) {
                    content()
                }
            } else content()
        }
    }
}
