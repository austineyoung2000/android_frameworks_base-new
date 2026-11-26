/*
 * Copyright (C) 2026 RisingOS (revived) Android Project
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

package com.android.systemui.media.ui.compose

import android.content.Context
import android.content.Intent
import android.app.ActivityManager
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.provider.Settings
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.media.ui.viewmodel.MiniPlayerViewModel
import com.android.systemui.statusbar.notification.row.NotificationBackgroundView
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberCustomLabelColors
import com.android.systemui.qs.panels.ui.compose.infinitegrid.qsTileUsesNeon
import com.android.systemui.qs.panels.ui.compose.infinitegrid.qsTileBackgroundModifier
import com.android.systemui.qs.panels.ui.compose.infinitegrid.qsTileStyleModifier
import com.android.systemui.qs.panels.ui.compose.infinitegrid.qsTileNeonModifier
import com.android.systemui.qs.panels.ui.compose.infinitegrid.qsTileNeonHaloModifier

@Composable
fun MiniPlayerCompact(
    viewModel: MiniPlayerViewModel,
    compact: Boolean = true,
    expansionProgress: Float = if (compact) 0f else 1f, // Kept to not break QSFragmentCompose but ignored for animations
    modifier: Modifier = Modifier,
    notificationAppearance: Boolean = false
) {
    val context = LocalContext.current
    val mediaState by viewModel.mediaState.collectAsStateWithLifecycle()

    val height = if (compact) 70.dp else 85.dp

    // background
    val bgColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f)

    // Border colors
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)

    // itens Colors
    val contentAlpha = if (mediaState.hasActiveMedia) 1f else 0.7f
    val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)

    val tileSettings = rememberCustomLabelColors()
    val shape = if (notificationAppearance) RoundedCornerShape(28.dp) else when (tileSettings.tileShape) {
        1 -> RoundedCornerShape(0.dp)
        2 -> RoundedCornerShape(14.dp)
        3 -> RoundedCornerShape(topStart = 24.dp, topEnd = 2.dp,
            bottomEnd = 24.dp, bottomStart = 2.dp)
        4 -> RoundedCornerShape(50)
        else -> RoundedCornerShape(28.dp)
    }
    val tileState = if (mediaState.isPlaying) STATE_ACTIVE else STATE_INACTIVE
    val isNeon = !notificationAppearance && qsTileUsesNeon(tileSettings, tileState)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .then(if (notificationAppearance) Modifier else
                qsTileNeonHaloModifier(tileSettings, tileState, shape))
            .clickable {
                if (mediaState.hasActiveMedia && mediaState.packageName != null) {
                    openMediaApp(context, mediaState.packageName!!)
                } else {
                    launchDefaultPlayer(context)
                }
            },
        color = Color.Transparent
    ) {
        // background with border
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (notificationAppearance) Modifier else Modifier.clip(shape))
                .background(color = if (isNeon || notificationAppearance) Color.Transparent else bgColor)
                .border(
                    width = 1.dp,
                    color = if (isNeon || notificationAppearance) Color.Transparent else borderColor,
                    shape = shape
                )
                .then(if (notificationAppearance) Modifier else
                    qsTileBackgroundModifier(tileSettings, tileState, shape))
                .then(if (notificationAppearance) Modifier else
                    qsTileStyleModifier(tileSettings.tileStyle, tileState, shape))
                .then(if (notificationAppearance) Modifier else
                    qsTileNeonModifier(tileSettings, tileState, shape))
        ) {
            if (notificationAppearance) {
                NotificationMediaBackground(Modifier.matchParentSize(), bgColor.toArgb())
            }
            Row(
                modifier = Modifier.padding(horizontal = 16.dp,
                    vertical = if (compact) 12.dp else 14.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Title and artist
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = mediaState.title,
                        color = textColor,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = if (compact) 14.sp else 16.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = mediaState.artist,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = contentAlpha * 0.8f
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 12.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Media Controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        enabled = mediaState.hasActiveMedia,
                        onClick = { viewModel.skipToPrevious() },
                        modifier = Modifier.size(if (compact) 44.dp else 48.dp)
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            tint = iconTint,
                            modifier = Modifier.size(if (compact) 24.dp else 28.dp)
                        )
                    }

                    FilledTonalIconButton(
                        onClick = {
                            if (mediaState.hasActiveMedia) {
                                viewModel.playPause()
                            } else {
                                launchDefaultPlayer(context)
                            }
                        },
                        modifier = Modifier.size(if (compact) 44.dp else 48.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(
                                alpha = 1f
                            )
                        )
                    ) {
                        Icon(
                            imageVector = if (mediaState.isPlaying) {
                                Icons.Default.Pause
                            } else {
                                Icons.Default.PlayArrow
                            },
                            contentDescription = "Play/Pause",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(if (compact) 24.dp else 28.dp)
                        )
                    }

                    IconButton(
                        enabled = mediaState.hasActiveMedia,
                        onClick = { viewModel.skipToNext() },
                        modifier = Modifier.size(if (compact) 44.dp else 48.dp)
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = iconTint,
                            modifier = Modifier.size(if (compact) 24.dp else 28.dp)
                        )
                    }
                }
            }
        }
    }
}


/** Uses the same notification background renderer and live settings as notification cards. */
@Composable
private fun NotificationMediaBackground(modifier: Modifier, nativeColor: Int) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            NotificationBackgroundView(context, null).apply {
                fun updateFill() {
                    val resolver = context.contentResolver
                    val user = ActivityManager.getCurrentUser()
                    val mode = Settings.System.getIntForUser(resolver,
                        "notification_card_color_mode", 0, user)
                    var first = Settings.System.getIntForUser(resolver,
                        "notification_card_color_first", android.graphics.Color.MAGENTA, user)
                    var second = if (mode == 2 || mode == 4 || mode == 6)
                        Settings.System.getIntForUser(resolver, "notification_card_color_second",
                            android.graphics.Color.BLUE, user) else first
                    if (mode == 0) { first = nativeColor; second = nativeColor }
                    if (mode == 5 || mode == 6) {
                        first = (first and 0x00ffffff) or
                            (minOf(android.graphics.Color.alpha(first), 56) shl 24)
                        second = (second and 0x00ffffff) or
                            (minOf(android.graphics.Color.alpha(second), 56) shl 24)
                    }
                    val fill = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(first, second))
                    val stateOverlay = GradientDrawable().apply {
                        setColor(android.graphics.Color.TRANSPARENT)
                    }
                    setCustomBackground(LayerDrawable(arrayOf(fill, stateOverlay)))
                    setTint(nativeColor)
                }
                setCardAppearanceChangedListener { updateFill() }
                updateFill()
                addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                    val radius = 28f * context.resources.displayMetrics.density
                    setRadius(radius, radius)
                    setActualWidth(right - left)
                    setActualHeight(bottom - top)
                }
            }
        },
    )
}

private fun openMediaApp(context: Context, pkg: String) {
    runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

private fun launchDefaultPlayer(context: Context) {
    runCatching {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_MUSIC)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
