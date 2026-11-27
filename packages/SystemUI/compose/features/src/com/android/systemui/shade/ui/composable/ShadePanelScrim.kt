/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.shade.ui.composable

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.shade.ui.ShadeColors

/** Scrim for QS/Shade, this will fill the max size, so it should be inside a Box like layout. */
@Composable
fun ContentScope.ShadePanelScrim(isTransparencyEnabled: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun readTransparency() = Settings.System.getIntForUser(
        resolver, "qs_background_transparency", 0, UserHandle.USER_CURRENT
    ).coerceIn(0, 100)
    var transparency by remember(context) { mutableIntStateOf(readTransparency()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { transparency = readTransparency() }
        }
        resolver.registerContentObserver(Settings.System.getUriFor("qs_background_transparency"),
            false, observer, UserHandle.USER_ALL)
        val userSwitch = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                transparency = readTransparency()
            }
        }
        context.registerReceiverForAllUsers(userSwitch, IntentFilter(Intent.ACTION_USER_SWITCHED),
            null, Handler(Looper.getMainLooper()), Context.RECEIVER_NOT_EXPORTED)
        transparency = readTransparency()
        onDispose {
            resolver.unregisterContentObserver(observer)
            context.unregisterReceiver(userSwitch)
        }
    }
    // This is the background for the whole scene, as the elements don't necessarily provide
    // a background that extends to the edges.
    Spacer(
        modifier =
            modifier
                .element(Shade.Elements.BackgroundScrim)
                .fillMaxSize()
                .background(color = scrimColor(isTransparencyEnabled).let {
                    it.copy(alpha = it.alpha * (100 - transparency) / 100f)
                })
    )
}

@Composable
private fun scrimColor(isTransparencyEnabled: Boolean): Color {
    // Read configuration to invalidate on changes
    LocalConfiguration.current
    return Color(
        ShadeColors.shadePanel(
            context = LocalContext.current,
            blurSupported = isTransparencyEnabled,
            withScrim = true,
        )
    )
}
