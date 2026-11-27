/*
 * Copyright (C) 2024 The Android Open Source Project
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

@file:OptIn(ExperimentalFoundationApi::class)

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.content.Context
import android.view.View
import android.view.CrossWindowBlurListeners
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import java.util.function.Consumer
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.content.res.Resources
import android.os.Trace
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.trace
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.compose.animation.Expandable
import com.android.compose.animation.bounceable
import com.android.compose.animation.rememberExpandableController
import com.android.compose.animation.scene.ContentScope
import com.android.compose.modifiers.thenIf
import com.android.compose.modifiers.width
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.internal.graphics.ColorUtils
import com.android.mechanics.compose.modifier.verticalFadeContentReveal
import com.android.mechanics.compose.modifier.verticalTactileSurfaceReveal
import com.android.mechanics.effects.VerticalTactileSurfaceRevealEffect
import com.android.systemui.Flags
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.TransitionAnimator.Companion.dynamicTargetResolutionEnabled
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.flags.QsDetailedView
import com.android.systemui.qs.panels.ui.compose.BounceableInfo
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.ActiveCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.InactiveCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.tileHeight
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileArrangementPadding
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TilePaddingLarge
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileStartPadding
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.longPressLabelSettings
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.longPressLabelMoreDetails
import com.android.systemui.qs.panels.ui.viewmodel.AccessibilityUiState
import com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.IconProvider
import com.android.systemui.qs.panels.ui.viewmodel.TileUiState
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toIconProvider
import com.android.systemui.qs.panels.ui.viewmodel.toUiState
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.ui.composable.QuickSettingsShade
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import kotlinx.coroutines.CoroutineScope

private const val TEST_TAG_SMALL = "qs_tile_small"
private const val TEST_TAG_LARGE = "qs_tile_large"

@Composable
fun TileLazyGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        state = state,
        columns = columns,
        verticalArrangement = spacedBy(CommonTileDefaults.TileArrangementPadding),
        horizontalArrangement = spacedBy(CommonTileDefaults.TileArrangementPadding),
        contentPadding = contentPadding,
        modifier = modifier,
        content = content,
    )
}

private val TileViewModel.traceName
    get() = spec.toString().takeLast(Trace.MAX_SECTION_NAME_LEN)

/**
 * This composable function is responsible for rendering a tile based on the provided
 * [TileViewModel]. It handles different states of the tile (e.g., available, unavailable),
 * interactions (click, long click), and visual styles (icon only or large tile).
 *
 * @param tile The [TileViewModel] containing the data and logic for the tile.
 * @param iconOnly A boolean indicating whether to display only the icon of the tile or the full
 * tile content (false for large tiles).
 * @param squishiness The float value representing the current squishiness factor of the tile, used
 * for animations.
 * @param coroutineScope The [CoroutineScope] to launch coroutines for animations.
 * @param tileHapticsViewModelFactoryProvider A provider for creating a [TileHapticsViewModel]
 * instance, used for haptic feedback.
 * @param interactionSource An optional [MutableInteractionSource] to track user interactions with
 * the tile, used by the parent composable to animate a bounce effect. Tiles may or may not use
 * this interaction source to control whether they should bounce or not.
 * @param modifier An optional [Modifier] to be applied to the root composable of the tile.
 * @param isVisible Whether the tile is currently visible. Defaults to true.
 * @param requestToggleTextFeedback A lambda function that is invoked when a toggleable icon only
 * tile is clicked, used to request the feedback text.
 * @param detailsViewModel An optional [DetailsViewModel] used to handle navigation to a detailed
 * view when a tile is clicked, if applicable.
 * @param enableRevealEffect If `true`, the tiles will animate using the reveal animation.
 */
@Composable
fun ContentScope.Tile(
    tile: TileViewModel,
    iconOnly: Boolean,
    spanRows: Int = 1,
    squishiness: () -> Float,
    coroutineScope: CoroutineScope,
    bounceableInfo: BounceableInfo?,
    tileHapticsViewModelFactory: TileHapticsViewModel.Factory,
    interactionSource: MutableInteractionSource? = null,
    modifier: Modifier = Modifier,
    isVisible: () -> Boolean = { true },
    requestToggleTextFeedback: (TileSpec) -> Unit = {},
    detailsViewModel: DetailsViewModel?,
    enableRevealEffect: Boolean = false,
) {
    trace(tile.traceName) {
        val currentBounceableInfo by rememberUpdatedState(bounceableInfo)
        val resources = resources()

        val uiState by
            produceState(tile.currentState.toUiState(resources), tile, resources) {
                tile.state.collect { value = it.toUiState(resources) }
            }
        val isClickable = uiState.handlesMainClick

        val icon by
            produceState(tile.currentState.toIconProvider(), tile) {
                tile.state.collect { value = it.toIconProvider() }
            }

        val defaultColors = TileDefaults.getColorForState(uiState, iconOnly)
        val labelColors = rememberCustomLabelColors()
        val customLabel = when (uiState.visualState) {
            STATE_ACTIVE -> labelColors.active
            STATE_INACTIVE -> labelColors.inactive
            else -> null
        }
        val customSecondaryLabel = when (uiState.visualState) {
            STATE_ACTIVE -> labelColors.activeSecondary
            STATE_INACTIVE -> labelColors.inactiveSecondary
            else -> null
        }
        val customIcon = when (uiState.visualState) {
            STATE_ACTIVE -> labelColors.activeIcon
            STATE_INACTIVE -> labelColors.inactiveIcon
            else -> null
        }
        val colors = defaultColors.copy(
            icon = customIcon ?: defaultColors.icon,
            label = if (iconOnly) defaultColors.label else customLabel ?: defaultColors.label,
            secondaryLabel = if (iconOnly) defaultColors.secondaryLabel else customSecondaryLabel ?: defaultColors.secondaryLabel,
        )
        val hapticsViewModel: TileHapticsViewModel =
            rememberViewModel(traceName = "TileHapticsViewModel") {
                tileHapticsViewModelFactory.create(tile)
            }

        val density = LocalDensity.current
        val tileHeightDp = tileHeight()
        val height = remember(spanRows, density, tileHeightDp, iconOnly) {
            if (iconOnly) {
                tileHeightDp
            } else {
                with(density) {
                    (tileHeightDp.toPx() * spanRows + TileArrangementPadding.toPx() * (spanRows - 1)).toDp()
                }
            }
        }
        
        val stockTileShape by TileDefaults.animateTileShapeAsState(
            state = uiState.visualState, iconOnly = iconOnly
        )
        val isNeon = qsTileUsesNeon(labelColors, uiState.visualState)
        val tileShape = when (labelColors.tileShape) {
            1 -> RoundedCornerShape(0.dp)
            2 -> RoundedCornerShape(14.dp)
            3 -> RoundedCornerShape(
                topStart = 24.dp, topEnd = 2.dp, bottomEnd = 24.dp, bottomStart = 2.dp
            )
            4 -> RoundedCornerShape(50)
            else -> if (iconOnly && isNeon) RoundedCornerShape(50) else stockTileShape
        }
        val animatedColor by animateColorAsState(colors.background, label = "QSTileBackgroundColor")

        val surfaceRevealModifier: Modifier
        val contentRevealModifier: Modifier
        if (enableRevealEffect) {
            val marginBottom =
                with(LocalDensity.current) { QuickSettingsShade.Dimensions.VerticalPadding.toPx() }

            val animatedCornerRadius by animateDpAsState(TileDefaults.tileRadius(uiState))

            val inactiveCornerRadius = TileDefaults.InactiveIconCornerRadius
            surfaceRevealModifier =
                Modifier.verticalTactileSurfaceReveal(
                    deltaY = marginBottom,
                    effectSpec =
                        remember(inactiveCornerRadius) {
                            VerticalTactileSurfaceRevealEffect(
                                maxCornerSize = { animatedCornerRadius },
                                phase1MarginX = inactiveCornerRadius,
                            )
                        },
                    label = tile.traceName,
                )

            contentRevealModifier =
                Modifier.verticalFadeContentReveal(deltaY = marginBottom, label = tile.traceName)
        } else {
            surfaceRevealModifier = Modifier
            contentRevealModifier = Modifier
        }

        TileExpandable(
            color = {
                if (qsTileUsesBlur(labelColors, uiState.visualState) || isNeon) {
                    Color.Transparent
                } else {
                    animatedColor
                }
            },
            shape = tileShape,
            squishiness = if (iconOnly) { { 1f } } else squishiness,
            hapticsViewModel = hapticsViewModel,
            modifier =
                modifier
                    .then(surfaceRevealModifier)
                    .then(qsTileNeonHaloModifier(labelColors, uiState.visualState, tileShape))
                    .borderOnFocus(color = MaterialTheme.colorScheme.secondary, tileShape.topEnd)
                    .then(
                        if (iconOnly)
                            Modifier.width { tileHeightDp.roundToPx() }
                        else
                            Modifier.fillMaxWidth(0.9f)
                    )
                    .thenIf(currentBounceableInfo != null) {
                        Modifier.bounceable(
                            bounceable = currentBounceableInfo!!.bounceable,
                            previousBounceable = currentBounceableInfo!!.previousTile,
                            nextBounceable = currentBounceableInfo!!.nextTile,
                            orientation = Orientation.Horizontal,
                            bounceEnd = currentBounceableInfo!!.bounceEnd,
                        )
                    },
        ) { expandable ->
            val useLongClickToSettings = !(iconOnly && uiState.handlesToggleClick && isClickable)
            val longClick: (() -> Unit)? =
                {
                        hapticsViewModel?.setTileInteractionState(
                            TileHapticsViewModel.TileInteractionState.LONG_CLICKED
                        )

                        if (useLongClickToSettings) {
                            tile.settingsClick(expandable)
                        } else {
                            tile.mainClick(expandable)
                        }
                    }
                    .takeIf { !useLongClickToSettings }

            val bounceContainer = uiState.isToggleable && (iconOnly || !uiState.handlesToggleClick)
            TileContainer(
                interactionSource = interactionSource.takeIf { bounceContainer },
                height = height,
                onClick = onClick@{
                        if (!isClickable) return@onClick

                        val hasDetails =
                            QsDetailedView.isEnabled &&
                                detailsViewModel?.onTileClicked(tile.spec) == true
                        if (hasDetails) return@onClick

                        if (iconOnly && uiState.handlesToggleClick) {
                            tile.toggleClick()
                        } else {
                            tile.mainClick(expandable)
                        }

                        hapticsViewModel?.setTileInteractionState(
                            TileHapticsViewModel.TileInteractionState.CLICKED
                        )
                        coroutineScope.launch {
                            val detailsVisible =
                                QsDetailedView.isEnabled &&
                                    detailsViewModel?.onTileClicked(tile.spec) == true
                            if (!detailsVisible && !bounceContainer) {
                                tile.mainClick(expandable)
                            }
                        }
                        if (uiState.isToggleable && iconOnly) {
                            requestToggleTextFeedback(tile.spec)
                        }
                    },
                onLongClick = longClick,
                accessibilityUiState = uiState.accessibilityUiState,
                iconOnly = iconOnly,
                isDualTarget = uiState.handlesToggleClick,
                modifier = contentRevealModifier
                    .then(qsTileBackgroundModifier(labelColors, uiState.visualState, tileShape))
                    .then(qsTileStyleModifier(qsTileSelectedStyle(labelColors, uiState.visualState), uiState.visualState, tileShape))
                    .then(qsTileGlassModifier(labelColors, uiState.visualState, tileShape))
                    .then(qsTileNeonModifier(labelColors, uiState.visualState, tileShape)),
            ) {
                val iconProvider: Context.() -> Icon = { getTileIcon(icon = icon) }
                if (iconOnly) {
                    SmallTileContent(
                        iconProvider = iconProvider,
                        color = colors.icon,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    val iconShape by TileDefaults.animateIconShapeAsState(uiState.visualState)
                    val secondaryClick: (() -> Unit)? =
                        {
                            hapticsViewModel?.setTileInteractionState(
                                TileHapticsViewModel.TileInteractionState.CLICKED
                            )
                            tile.toggleClick()
                        }.takeIf { uiState.handlesToggleClick }
                        
                    LargeTileContent(
                        label = uiState.label,
                        secondaryLabel = uiState.secondaryLabel,
                        iconProvider = iconProvider,
                        sideDrawable = uiState.sideDrawable,
                        colors = colors,
                        iconShape = iconShape,
                        toggleClick = secondaryClick,
                        onLongClick = longClick,
                        accessibilityUiState = uiState.accessibilityUiState,
                        squishiness = squishiness,
                        isVisible = isVisible,
                        textScale = { 1f },
                        modifier =
                            Modifier.largeTilePadding(isDualTarget = uiState.handlesToggleClick),
                    )
                }
            }
        }
    }
}

@Composable
private fun TileExpandable(
    color: () -> Color,
    shape: Shape,
    squishiness: () -> Float,
    hapticsViewModel: TileHapticsViewModel?,
    modifier: Modifier = Modifier,
    content: @Composable (Expandable) -> Unit,
) {
    Expandable(
        controller = rememberExpandableController(color = color, shape = shape),
        modifier =
            modifier
                .clip(shape)
                .verticalSquish(squishiness),
        useModifierBasedImplementation = true,
    ) {
        content(hapticsViewModel?.createStateAwareExpandable(it) ?: it)
    }
}

@Composable
fun TileContainer(
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    accessibilityUiState: AccessibilityUiState,
    iconOnly: Boolean,
    isDualTarget: Boolean,
    interactionSource: MutableInteractionSource?,
    height: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier =
            modifier
                .height(height)
                .fillMaxWidth()
                .tileCombinedClickable(
                    onClick = onClick ?: {},
                    onLongClick = onLongClick,
                    accessibilityUiState = accessibilityUiState,
                    interactionSource = interactionSource,
                    iconOnly = iconOnly,
                    isDualTarget = isDualTarget,
                )
                .tileTestTag(iconOnly),
        content = content
    )
}

private fun Context.getTileIcon(icon: IconProvider): Icon {
    return icon.icon?.let {
        if (it is QSTileImpl.ResourceIcon) {
            Icon.Resource(it.resId, null)
        } else {
            Icon.Loaded(it.getDrawable(this), null)
        }
    } ?: Icon.Resource(R.drawable.ic_error_outline, null)
}

fun tileHorizontalArrangement(): Arrangement.Horizontal {
    return spacedBy(space = CommonTileDefaults.TileArrangementPadding, alignment = Alignment.Start)
}

@Composable
fun Modifier.tileCombinedClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    accessibilityUiState: AccessibilityUiState,
    interactionSource: MutableInteractionSource?,
    iconOnly: Boolean,
    isDualTarget: Boolean,
): Modifier {
    val longPressLabel =
        if (iconOnly && isDualTarget) longPressLabelMoreDetails() else longPressLabelSettings()
    return combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            onClickLabel = accessibilityUiState.clickLabel,
            onLongClickLabel = longPressLabel,
            hapticFeedbackEnabled = false, // Haptics handled separately
            interactionSource = interactionSource,
        )
        .semantics {
            val accessibilityRole =
                if (iconOnly && isDualTarget) {
                    Role.Switch
                } else {
                    accessibilityUiState.accessibilityRole
                }
            if (accessibilityRole == Role.Switch) {
                accessibilityUiState.toggleableState?.let { toggleableState = it }
            }
            role = accessibilityRole
            stateDescription = accessibilityUiState.stateDescription
        }
        .thenIf(iconOnly) {
            Modifier.semantics { contentDescription = accessibilityUiState.contentDescription }
        }
}

data class TileColors(
    val background: Color,
    val iconBackground: Color,
    val label: Color,
    val secondaryLabel: Color,
    val icon: Color,
)

internal data class CustomLabelColors(
    val active: Color?,
    val inactive: Color?,
    val activeSecondary: Color?,
    val inactiveSecondary: Color?,
    val activeIcon: Color?,
    val inactiveIcon: Color?,
    val tileShape: Int,
    val tileStyle: Int,
    val activeBackground: Color?,
    val activeGradientEnd: Color?,
    val inactiveBackground: Color?,
    val inactiveGradientEnd: Color?,
    val activeEffect: Int,
    val inactiveEffect: Int,
    val activeGlowIntensity: Int,
    val inactiveGlowIntensity: Int,
    val activeNeonTransparency: Int = 78,
    val inactiveNeonTransparency: Int = 78,
    val activeGlassTintIntensity: Int = 35,
    val inactiveGlassTintIntensity: Int = 20,
    val activeGlassSoftness: Int = 60,
    val inactiveGlassSoftness: Int = 60,
    val activeGlassTransparency: Int = 0,
    val inactiveGlassTransparency: Int = 0,
)

@Composable
internal fun rememberCustomLabelColors(): CustomLabelColors {
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun readColors(): CustomLabelColors {
        fun color(enabledKey: String, colorKey: String, fallback: Int): Color? {
            val enabled = Settings.System.getIntForUser(
                resolver, enabledKey, 0, UserHandle.USER_CURRENT
            ) != 0
            return if (enabled) Color(Settings.System.getIntForUser(
                resolver, colorKey, fallback, UserHandle.USER_CURRENT
            )) else null
        }
        fun rawColor(key: String, fallback: Int) = Color(Settings.System.getIntForUser(resolver, key, fallback, UserHandle.USER_CURRENT))
        fun effect(kind: String): Int {
            val configured = Settings.System.getIntForUser(resolver, "qs_" + kind + "_background_effect", -1, UserHandle.USER_CURRENT)
            if (configured in 9..15) return configured
            if (configured in 0..8) {
                if (configured != 0) return configured
                // Until Personalizations migrates the old global style, retain it for
                // states which previously used the native background.
                val oldStyle = Settings.System.getIntForUser(
                    resolver, "qs_compose_tile_style", 0, UserHandle.USER_CURRENT
                )
                return if (oldStyle in 1..7) oldStyle + 8 else 0
            }
            if (Settings.System.getIntForUser(resolver, "qs_" + kind + "_background_color_enabled", 0, UserHandle.USER_CURRENT) == 0) {
                val oldStyle = Settings.System.getIntForUser(resolver, "qs_compose_tile_style", 0, UserHandle.USER_CURRENT)
                return if (oldStyle in 1..7) oldStyle + 8 else 0
            }
            return if (Settings.System.getIntForUser(resolver, "qs_" + kind + "_gradient_enabled", 0, UserHandle.USER_CURRENT) != 0) 2 else 1
        }
        return CustomLabelColors(
            active = color("qs_active_label_color_enabled", "qs_active_label_color", 0xff000000.toInt()),
            inactive = color("qs_inactive_label_color_enabled", "qs_inactive_label_color", 0xffffffff.toInt()),
            activeSecondary = color(
                "qs_active_secondary_label_color_enabled", "qs_active_secondary_label_color",
                0xff000000.toInt()
            ),
            inactiveSecondary = color(
                "qs_inactive_secondary_label_color_enabled", "qs_inactive_secondary_label_color",
                0xffffffff.toInt()
            ),
            activeIcon = color("qs_active_icon_color_enabled", "qs_active_icon_color", 0xffffffff.toInt()),
            inactiveIcon = color("qs_inactive_icon_color_enabled", "qs_inactive_icon_color", 0xffffffff.toInt()),
            tileShape = Settings.System.getIntForUser(
                resolver, "qs_tile_shape", 0, UserHandle.USER_CURRENT
            ),
            tileStyle = Settings.System.getIntForUser(
                resolver, "qs_compose_tile_style", 0, UserHandle.USER_CURRENT
            ),
            activeBackground = if (effect("active") !in 1..8) null else rawColor("qs_active_background_color", 0xff6200ee.toInt()),
            activeGradientEnd = if (effect("active") in listOf(2, 4, 6)) rawColor("qs_active_gradient_end_color", 0xff0000ff.toInt()) else null,
            inactiveBackground = if (effect("inactive") !in 1..8) null else rawColor("qs_inactive_background_color", 0xff55555f.toInt()),
            inactiveGradientEnd = if (effect("inactive") in listOf(2, 4, 6)) rawColor("qs_inactive_gradient_end_color", 0xff0000ff.toInt()) else null,
            activeEffect = effect("active"),
            inactiveEffect = effect("inactive"),
            activeGlowIntensity = Settings.System.getIntForUser(resolver, "qs_active_glow_intensity", 60, UserHandle.USER_CURRENT).coerceIn(0, 100),
            inactiveGlowIntensity = Settings.System.getIntForUser(resolver, "qs_inactive_glow_intensity", 60, UserHandle.USER_CURRENT).coerceIn(0, 100),
            activeNeonTransparency = Settings.System.getIntForUser(resolver, "qs_active_neon_transparency", 78, UserHandle.USER_CURRENT).coerceIn(0, 100),
            inactiveNeonTransparency = Settings.System.getIntForUser(resolver, "qs_inactive_neon_transparency", 78, UserHandle.USER_CURRENT).coerceIn(0, 100),
            activeGlassTintIntensity = Settings.System.getIntForUser(resolver, "qs_active_" + (if (effect("active") == 8) "liquid" else "frosted") + "_glass_tint", 35, UserHandle.USER_CURRENT).coerceIn(0, 100),
            activeGlassSoftness = Settings.System.getIntForUser(resolver, "qs_active_" + (if (effect("active") == 8) "liquid" else "frosted") + "_glass_softness", if (effect("active") == 8) 20 else 60, UserHandle.USER_CURRENT).coerceIn(0, 100),
            activeGlassTransparency = Settings.System.getIntForUser(resolver, "qs_active_" + (if (effect("active") == 8) "liquid" else "frosted") + "_glass_transparency", 0, UserHandle.USER_CURRENT).coerceIn(0, 100),
            inactiveGlassTintIntensity = Settings.System.getIntForUser(resolver, "qs_inactive_" + (if (effect("inactive") == 8) "liquid" else "frosted") + "_glass_tint", 20, UserHandle.USER_CURRENT).coerceIn(0, 100),
            inactiveGlassSoftness = Settings.System.getIntForUser(resolver, "qs_inactive_" + (if (effect("inactive") == 8) "liquid" else "frosted") + "_glass_softness", if (effect("inactive") == 8) 20 else 60, UserHandle.USER_CURRENT).coerceIn(0, 100),
            inactiveGlassTransparency = Settings.System.getIntForUser(resolver, "qs_inactive_" + (if (effect("inactive") == 8) "liquid" else "frosted") + "_glass_transparency", 0, UserHandle.USER_CURRENT).coerceIn(0, 100),
        )
    }
    val colors = remember(context) { mutableStateOf(readColors()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                colors.value = readColors()
            }
        }
        listOf(
            "qs_active_label_color_enabled", "qs_active_label_color",
            "qs_inactive_label_color_enabled", "qs_inactive_label_color",
            "qs_active_secondary_label_color_enabled", "qs_active_secondary_label_color",
            "qs_inactive_secondary_label_color_enabled", "qs_inactive_secondary_label_color",
            "qs_active_icon_color_enabled", "qs_active_icon_color",
            "qs_inactive_icon_color_enabled", "qs_inactive_icon_color",
            "qs_tile_shape", "qs_compose_tile_style",
            "qs_active_background_effect", "qs_inactive_background_effect",
            "qs_active_glow_intensity", "qs_inactive_glow_intensity",
            "qs_active_neon_transparency", "qs_inactive_neon_transparency",
            "qs_active_frosted_glass_tint", "qs_inactive_frosted_glass_tint",
            "qs_active_liquid_glass_tint", "qs_inactive_liquid_glass_tint",
            "qs_active_frosted_glass_softness", "qs_inactive_frosted_glass_softness",
            "qs_active_liquid_glass_softness", "qs_inactive_liquid_glass_softness",
            "qs_active_frosted_glass_transparency", "qs_inactive_frosted_glass_transparency",
            "qs_active_liquid_glass_transparency", "qs_inactive_liquid_glass_transparency",
            "qs_active_background_color_enabled", "qs_active_background_color",
            "qs_active_gradient_enabled", "qs_active_gradient_end_color",
            "qs_inactive_background_color_enabled", "qs_inactive_background_color",
            "qs_inactive_gradient_enabled", "qs_inactive_gradient_end_color",
        ).forEach { key ->
            resolver.registerContentObserver(
                Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL
            )
        }
        val userReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                colors.value = readColors()
            }
        }
        context.registerReceiver(userReceiver, IntentFilter(Intent.ACTION_USER_SWITCHED))
        colors.value = readColors()
        onDispose {
            resolver.unregisterContentObserver(observer)
            context.unregisterReceiver(userReceiver)
        }
    }
    return colors.value
}

/**
 * Draw custom fills inside the tile's existing shape and beneath its style and content.
 * Disabled and unavailable tiles retain the colors selected by SystemUI.
 */

// The QS host can remain composed after the shade closes. Do not retain blur regions then.
internal val LocalQsTileBlurVisible = staticCompositionLocalOf { false }

/** Neon uses a translucent tint; an opaque stock surface underneath defeats it. */
/** Each active/inactive appearance selects either a style or a background effect. */
internal fun qsTileSelectedStyle(settings: CustomLabelColors, state: Int): Int {
    val selected = when (state) {
        STATE_ACTIVE -> settings.activeEffect
        STATE_INACTIVE -> settings.inactiveEffect
        else -> 0
    }
    return if (selected in 9..15) selected - 8 else 0
}

internal fun qsTileUsesNeon(settings: CustomLabelColors, state: Int): Boolean =
    when (state) {
        STATE_ACTIVE -> settings.activeEffect in 5..6 && settings.activeBackground != null
        STATE_INACTIVE -> settings.inactiveEffect in 5..6 && settings.inactiveBackground != null
        else -> false
    }

internal fun qsTileUsesBlur(settings: CustomLabelColors, state: Int): Boolean =
    when (state) {
        STATE_ACTIVE -> settings.activeEffect in 3..4 || settings.activeEffect in 7..8
        STATE_INACTIVE -> settings.inactiveEffect in 3..4 || settings.inactiveEffect in 7..8
        else -> false
    }

@Composable
internal fun qsTileBackgroundModifier(
    settings: CustomLabelColors,
    state: Int,
    shape: RoundedCornerShape,
): Modifier {
    val start = when (state) {
        STATE_ACTIVE -> settings.activeBackground
        STATE_INACTIVE -> settings.inactiveBackground
        else -> null
    }
    val end = when (state) {
        STATE_ACTIVE -> settings.activeGradientEnd
        STATE_INACTIVE -> settings.inactiveGradientEnd
        else -> null
    }
    val selectedEffect = if (state == STATE_ACTIVE) settings.activeEffect else settings.inactiveEffect
    val wantsBlur = start != null && qsTileUsesBlur(settings, state) &&
        LocalQsTileBlurVisible.current
    val view = LocalView.current
    val glassSoftness = if (state == STATE_ACTIVE) settings.activeGlassSoftness else settings.inactiveGlassSoftness
    val glassRadius = with(LocalDensity.current) { (40f * glassSoftness / 100f).dp.roundToPx() }
    var blur by remember(view) { mutableStateOf<BackgroundBlurDrawable?>(null) }
    var blurSupported by remember(view) { mutableStateOf(false) }
    DisposableEffect(view, wantsBlur, selectedEffect, glassRadius) {
        val listeners = CrossWindowBlurListeners.getInstance()
        var disposed = false
        var ownedBlur: BackgroundBlurDrawable? = null

        fun releaseBlur() {
            val old = ownedBlur ?: return
            ownedBlur = null
            // Remove the region from the window aggregator before the composable disappears.
            old.setVisible(false, false)
            old.setBlurRadius(0)
            old.setBounds(0, 0, 0, 0)
            if (blur === old) blur = null
            // The scene can remain composed while hidden; force the host to redraw without
            // the released cross-window blur region.
            view.invalidate()
        }

        fun updateSupport(enabled: Boolean) {
            if (disposed) return
            blurSupported = enabled
            ownedBlur?.setVisible(enabled && wantsBlur && view.isAttachedToWindow, false)
        }

        val callback = Consumer<Boolean> { updateSupport(it) }
        val attachment = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (disposed || !wantsBlur || ownedBlur != null) return
                val created = v.viewRootImpl?.createBackgroundBlurDrawable() ?: return
                // Start hidden: a tile may be detached or the shade closed before its first draw.
                created.setVisible(false, false)
                created.setBlurRadius(if (selectedEffect in 7..8) glassRadius else
                    v.resources.getDimensionPixelSize(R.dimen.notification_background_blur_radius))
                created.setColor(android.graphics.Color.TRANSPARENT)
                ownedBlur = created
                blur = created
                updateSupport(listeners.isCrossWindowBlurEnabled)
            }

            override fun onViewDetachedFromWindow(v: View) {
                releaseBlur()
            }
        }
        if (wantsBlur) {
            listeners.addListener(view.context.mainExecutor, callback)
            view.addOnAttachStateChangeListener(attachment)
            if (view.isAttachedToWindow) attachment.onViewAttachedToWindow(view)
            updateSupport(listeners.isCrossWindowBlurEnabled)
        }
        onDispose {
            disposed = true
            if (wantsBlur) {
                listeners.removeListener(callback)
                view.removeOnAttachStateChangeListener(attachment)
            }
            releaseBlur()
        }
    }
    if (start == null) return Modifier
    val currentBlur = blur
    val activeBlur = wantsBlur && blurSupported && currentBlur != null
    return Modifier.drawBehind {
        var drewBlur = false
        if (activeBlur) {
            drawIntoCanvas { canvas ->
                val drawable = currentBlur ?: return@drawIntoCanvas
                if (canvas.nativeCanvas.isHardwareAccelerated && drawable.isVisible) {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    if (outline is Outline.Rectangle) {
                        drawable.setCornerRadius(0f)
                        drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                        drawable.draw(canvas.nativeCanvas)
                        drewBlur = true
                    } else if (outline is Outline.Rounded) {
                        val rr = outline.roundRect
                        drawable.setCornerRadius(
                            rr.topLeftCornerRadius.x, rr.topLeftCornerRadius.y,
                            rr.topRightCornerRadius.x, rr.topRightCornerRadius.y,
                            rr.bottomLeftCornerRadius.x, rr.bottomLeftCornerRadius.y,
                            rr.bottomRightCornerRadius.x, rr.bottomRightCornerRadius.y,
                        )
                        drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                        drawable.draw(canvas.nativeCanvas)
                        drewBlur = true
                    }
                }
            }
        }
        val effect = if (state == STATE_ACTIVE) settings.activeEffect else settings.inactiveEffect
        val glassTint = if (state == STATE_ACTIVE) settings.activeGlassTintIntensity
            else settings.inactiveGlassTintIntensity
        val glassAlpha = (glassTint.coerceIn(0, 100) / 100f) *
            (if (effect == 7) 0.45f else 0.50f)
        val glassTransparency = if (state == STATE_ACTIVE) settings.activeGlassTransparency
            else settings.inactiveGlassTransparency
        val glassOpacity = (100 - glassTransparency.coerceIn(0, 100)) / 100f
        val neonTransparency = if (state == STATE_ACTIVE) settings.activeNeonTransparency
            else settings.inactiveNeonTransparency
        val neonOpacity = (100 - neonTransparency.coerceIn(0, 100)) / 100f
        val first = when {
            effect in 5..6 -> start.copy(alpha = minOf(start.alpha, neonOpacity))
            // Keep the same translucent tint during transitions and when blur is
            // unavailable; never replace glass with an opaque fallback surface.
            effect in 7..8 -> start.copy(alpha = minOf(start.alpha, glassAlpha) * glassOpacity)
            drewBlur -> start.copy(alpha = minOf(start.alpha, 0.63f))
            else -> start
        }
        val last = if (effect in 5..6) end?.copy(alpha = minOf(end.alpha, neonOpacity))
            else if (drewBlur) end?.copy(alpha = minOf(end.alpha, 0.63f)) else end
        if (last != null) drawRect(brush = Brush.horizontalGradient(listOf(first, last)))
        else drawRect(color = first)
    }
}

/**
 * ColorOS-inspired glass highlights are drawn inside the current tile shape. The liquid
 * variant adds a clearer fill and a stronger curved rim; it does not sample or refract
 * the underlying window.
 */
internal fun qsTileGlassModifier(
    settings: CustomLabelColors,
    state: Int,
    shape: RoundedCornerShape,
): Modifier {
    val effect = when (state) {
        STATE_ACTIVE -> settings.activeEffect
        STATE_INACTIVE -> settings.inactiveEffect
        else -> 0
    }
    if (effect !in 7..8) return Modifier
    return Modifier.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path()
        when (outline) {
            is Outline.Rounded -> path.addRoundRect(outline.roundRect)
            is Outline.Rectangle -> path.addRect(outline.rect)
            is Outline.Generic -> path.addPath(outline.path)
        }
        val liquid = effect == 8
        val sheen = Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (liquid) 0.24f else 0.14f),
            0.24f to Color.White.copy(alpha = if (liquid) 0.06f else 0.04f),
            0.55f to Color.Transparent,
            0.85f to (if (liquid) Color.Black.copy(alpha = 0.09f) else Color.Transparent),
            1f to (if (liquid) Color.Black.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.02f)),
        )
        val rim = Brush.linearGradient(
            0f to Color.White.copy(alpha = if (liquid) 0.70f else 0.40f),
            0.35f to Color.White.copy(alpha = if (liquid) 0.14f else 0.08f),
            0.70f to Color.White.copy(alpha = if (liquid) 0.12f else 0.06f),
            1f to Color.White.copy(alpha = if (liquid) 0.55f else 0.28f),
        )
        // Soft, shape-clipped bands suggest the curved highlights of a water surface.
        // These are decorative paths, not live backdrop refraction.
        val waterTop = Path().apply {
            // On compact/square tiles move the shine into the curved shape instead of
            // clipping the large-tile highlight entirely outside a circular edge.
            val top = if (size.width <= size.height * 1.2f) 0.19f else 0.10f
            moveTo(0f, size.height * top)
            cubicTo(size.width * 0.22f, size.height * (top - 0.08f),
                size.width * 0.38f, size.height * (top + 0.08f),
                size.width * 0.61f, size.height * (top - 0.02f))
            cubicTo(size.width * 0.78f, size.height * (top - 0.08f),
                size.width * 0.91f, size.height * (top + 0.02f),
                size.width, size.height * (top - 0.02f))
            lineTo(size.width, 0f)
            lineTo(0f, 0f)
            close()
        }
        val waterBottom = Path().apply {
            val bottom = if (size.width <= size.height * 1.2f) 0.73f else 0.79f
            moveTo(0f, size.height * bottom)
            cubicTo(size.width * 0.23f, size.height * (bottom - 0.08f),
                size.width * 0.37f, size.height * (bottom + 0.15f),
                size.width * 0.60f, size.height * (bottom + 0.04f))
            cubicTo(size.width * 0.78f, size.height * (bottom - 0.03f),
                size.width * 0.90f, size.height * (bottom + 0.13f),
                size.width, size.height * (bottom + 0.05f))
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        val waterHighlight = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.30f),
            0.22f to Color.White.copy(alpha = 0.04f),
            1f to Color.Transparent,
        )
        val waterDepth = Brush.verticalGradient(
            0f to Color.Transparent,
            0.75f to Color.White.copy(alpha = 0.04f),
            1f to Color.White.copy(alpha = 0.18f),
        )
        onDrawBehind {
            clipPath(path) {
                drawRect(brush = sheen)
                if (liquid) {
                    drawPath(waterTop, brush = waterHighlight)
                    drawPath(waterBottom, brush = waterDepth)
                }
                drawPath(path, brush = rim, style = Stroke((if (liquid) 2f else 1f).dp.toPx()))
            }
        }
    }
}

/**
 * Draw the neon halo behind the clipped tile, following Resurrected Remix's blurred-paint
 * approach. A gradient shader extends it to the dual-color effect.
 */
internal fun neonHaloModifier(
    effect: Int,
    first: Color?,
    second: Color?,
    intensity: Int,
    shape: RoundedCornerShape,
): Modifier {
    if (effect !in 5..6 || first == null || intensity <= 0) return Modifier
    val strength = intensity.coerceIn(0, 100) / 100f
    val end = if (effect == 6) second ?: first else first
    return Modifier.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path()
        when (outline) {
            is Outline.Rounded -> path.addRoundRect(outline.roundRect)
            is Outline.Rectangle -> path.addRect(outline.rect)
            is Outline.Generic -> path.addPath(outline.path)
        }
        // Mask filters are not supported on hardware canvases. Cache a half-resolution
        // software-rendered halo and composite it onto SystemUI's hardware canvas.
        val blurPx = (2f + strength * 14f).dp.toPx()
        val padding = kotlin.math.ceil(blurPx * 2f).toInt()
        val bitmapScale = 0.5f
        val bitmapWidth = kotlin.math.ceil((size.width + padding * 2) * bitmapScale).toInt().coerceAtLeast(1)
        val bitmapHeight = kotlin.math.ceil((size.height + padding * 2) * bitmapScale).toInt().coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(bitmapWidth, bitmapHeight,
            android.graphics.Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = android.graphics.BlurMaskFilter(blurPx, android.graphics.BlurMaskFilter.Blur.NORMAL)
            shader = android.graphics.LinearGradient(0f, 0f, size.width.coerceAtLeast(1f), 0f,
                first.copy(alpha = 0.65f * strength).toArgb(),
                end.copy(alpha = 0.65f * strength).toArgb(),
                android.graphics.Shader.TileMode.CLAMP)
        }
        android.graphics.Canvas(bitmap).apply {
            scale(bitmapScale, bitmapScale)
            translate(padding.toFloat(), padding.toFloat())
            drawPath(path.asAndroidPath(), paint)
        }
        val destination = android.graphics.RectF(-padding.toFloat(), -padding.toFloat(),
            size.width + padding, size.height + padding)
        val bitmapPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        onDrawBehind {
            // Notification cards keep the soft halo outside their translucent interior.
            clipPath(path, clipOp = ClipOp.Difference) {
                drawIntoCanvas { canvas -> canvas.nativeCanvas.drawBitmap(bitmap, null, destination, bitmapPaint) }
            }
        }
    }
}

internal fun qsTileNeonHaloModifier(settings: CustomLabelColors, state: Int, shape: RoundedCornerShape): Modifier =
    when (state) {
        STATE_ACTIVE -> neonHaloModifier(settings.activeEffect, settings.activeBackground,
            settings.activeGradientEnd, settings.activeGlowIntensity, shape)
        STATE_INACTIVE -> neonHaloModifier(settings.inactiveEffect, settings.inactiveBackground,
            settings.inactiveGradientEnd, settings.inactiveGlowIntensity, shape)
        else -> Modifier
    }

/** Inset layered neon edges remain inside the tile clip and follow its current shape. */
internal fun neonGlowModifier(
    effect: Int,
    first: Color?,
    second: Color?,
    intensity: Int,
    shape: RoundedCornerShape,
): Modifier {
    if (effect !in 5..6 || first == null || intensity <= 0) return Modifier
    val strength = intensity.coerceIn(0, 100) / 100f
    val end = if (effect == 6) second ?: first else first
    return Modifier.drawWithContent {
        drawContent()
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path()
        when (outline) {
            is Outline.Rounded -> path.addRoundRect(outline.roundRect)
            is Outline.Rectangle -> path.addRect(outline.rect)
            is Outline.Generic -> path.addPath(outline.path)
        }
        clipPath(path) {
            for (layer in 5 downTo 1) {
                val width = (layer * 2f).dp.toPx()
                val alpha = strength * if (layer == 1) 0.9f else 0.10f
                val brush = Brush.horizontalGradient(listOf(first.copy(alpha = alpha), end.copy(alpha = alpha)))
                drawPath(path = path, brush = brush, style = Stroke(width))
            }
        }
    }
}

internal fun qsTileNeonModifier(settings: CustomLabelColors, state: Int, shape: RoundedCornerShape): Modifier =
    when (state) {
        STATE_ACTIVE -> neonGlowModifier(settings.activeEffect, settings.activeBackground,
            settings.activeGradientEnd, settings.activeGlowIntensity, shape)
        STATE_INACTIVE -> neonGlowModifier(settings.inactiveEffect, settings.inactiveBackground,
            settings.inactiveGradientEnd, settings.inactiveGlowIntensity, shape)
        else -> Modifier
    }

internal fun qsTileStyleModifier(
    style: Int,
    state: Int,
    shape: RoundedCornerShape,
): Modifier {
    if (style !in 1..7) return Modifier
    val active = state == STATE_ACTIVE
    val edge = if (active) Color.White.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.3f)
    val styled = Modifier.drawWithContent {
        when (style) {
            // Reflected: soft shine across the upper part of the tile.
            2 -> drawRect(Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.32f),
                0.48f to Color.White.copy(alpha = 0.03f),
                1f to Color.Transparent
            ))
            // Shaded: darken the upper edge.
            3 -> drawRect(Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent)
            ))
            // Neumorph: opposing highlights and shadows.
            5 -> drawRect(Brush.linearGradient(
                listOf(Color.White.copy(alpha = 0.26f), Color.Transparent,
                    Color.Black.copy(alpha = 0.22f))
            ))
            // CyberPunk: contrasting bands on opposite sides.
            6 -> {
                drawRect(Color.White.copy(alpha = 0.16f), size = Size(size.width * 0.55f, size.height))
                drawRect(Color.Black.copy(alpha = 0.16f),
                    topLeft = Offset(size.width * 0.58f, 0f),
                    size = Size(size.width * 0.42f, size.height))
            }
            // Two-tone accent: a distinct second tone within the tile.
            7 -> drawRect(Brush.horizontalGradient(
                listOf(Color.White.copy(alpha = 0.16f), Color.Transparent,
                    Color.Black.copy(alpha = 0.14f))
            ))
        }
        drawContent()
    }
    return when (style) {
        1 -> styled.border(2.dp, edge, shape)
        2 -> styled.border(1.dp, edge.copy(alpha = 0.18f), shape)
        3 -> styled
        4 -> styled.border(1.dp, edge, shape)
        5 -> styled.border(1.dp, edge.copy(alpha = 0.28f), shape)
        6 -> styled.border(2.dp, edge, shape)
        else -> styled
    }
}

private object TileDefaults {
    val ActiveIconCornerRadius = ActiveCornerRadius
    val ActiveTileCornerRadius = ActiveCornerRadius
    val InactiveIconCornerRadius = InactiveCornerRadius
    val InactiveTileCornerRadius = InactiveCornerRadius

    /** Half-alpha shade tile color, derived from the current context's themed color. */
    val shadeTileColor: Color
        @Composable
        @ReadOnlyComposable
        get() {
            val context = LocalContext.current
            val tileColor = context.getColor(R.color.shade_tile_color)
            val tileColorAlpha = ColorUtils.setAlphaComponent(tileColor, (0.5f * 255).toInt())
            return Color(tileColorAlpha)
        }

    /** An active icon tile uses the active color as background */
    @Composable
    @ReadOnlyComposable
    fun activeTileColors(): TileColors =
        TileColors(
            background = MaterialTheme.colorScheme.primary,
            iconBackground = Color.Transparent,
            label = MaterialTheme.colorScheme.onPrimary,
            secondaryLabel = MaterialTheme.colorScheme.onPrimary,
            icon = MaterialTheme.colorScheme.onPrimary,
        )

    @Composable
    @ReadOnlyComposable
    fun activeDualTargetTileColors(): TileColors =
        TileColors(
            background = MaterialTheme.colorScheme.primary,
            iconBackground = Color.Transparent,
            label = MaterialTheme.colorScheme.onPrimary,
            secondaryLabel = MaterialTheme.colorScheme.onPrimary,
            icon = MaterialTheme.colorScheme.onPrimary,
        )

    @Composable
    @ReadOnlyComposable
    fun inactiveDualTargetTileColors(): TileColors =
        TileColors(
            background = shadeTileColor,
            iconBackground = Color.Transparent,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onSurface,
        )

    @Composable
    @ReadOnlyComposable
    fun inactiveTileColors(): TileColors =
        TileColors(
            background = shadeTileColor,
            iconBackground = Color.Transparent,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onSurfaceVariant,
        )

    @Composable
    @ReadOnlyComposable
    fun unavailableTileColors(): TileColors {
        return TileColors(
            background = shadeTileColor,
            iconBackground = Color.Transparent,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onSurface,
        )
    }

    @Composable
    @ReadOnlyComposable
    fun getColorForState(uiState: TileUiState, iconOnly: Boolean): TileColors {
        return when (uiState.visualState) {
            STATE_ACTIVE -> {
                if (uiState.handlesToggleClick && !iconOnly) {
                    activeDualTargetTileColors()
                } else {
                    activeTileColors()
                }
            }
            STATE_INACTIVE -> {
                if (uiState.handlesToggleClick && !iconOnly) {
                    inactiveDualTargetTileColors()
                } else {
                    inactiveTileColors()
                }
            }
            else -> unavailableTileColors()
        }
    }

    @Composable
    fun iconRadius(uiState: TileUiState): Dp {
        return when (uiState.visualState) {
            STATE_ACTIVE -> ActiveIconCornerRadius
            STATE_INACTIVE -> InactiveIconCornerRadius
            else -> InactiveIconCornerRadius
        }
    }
    
    @Composable
    fun tileRadius(uiState: TileUiState): Dp {
        return when (uiState.visualState) {
            STATE_ACTIVE -> ActiveTileCornerRadius
            STATE_INACTIVE -> InactiveTileCornerRadius
            else -> InactiveTileCornerRadius
        }
    }

    @Composable
    fun animateTileShapeAsState(state: Int, iconOnly: Boolean = false): State<RoundedCornerShape> {
        val targetRadius = if (iconOnly) {
            InactiveTileCornerRadius
        } else {
            if (state == STATE_ACTIVE) ActiveTileCornerRadius else InactiveTileCornerRadius
        }
        
        return animateShapeAsState(
            targetValue = targetRadius,
            label = "QSTileBackgroundCornerRadius",
        )
    }

    @Composable
    fun animateTileShapeAsState(uiState: TileUiState): State<RoundedCornerShape> {
        return animateShapeAsState(targetValue = tileRadius(uiState), label = "QSTileCornerRadius")
    }

    @Composable
    fun animateIconShapeAsState(state: Int): State<RoundedCornerShape> {
        return animateShapeAsState(
            targetValue = if (state == STATE_ACTIVE) ActiveIconCornerRadius else InactiveIconCornerRadius,
            label = "QSTileIconCornerRadius"
        )
    }

    @Composable
    fun animateShapeAsState(targetValue: Dp, label: String): State<RoundedCornerShape> {
        val animatedCornerRadius by animateDpAsState(targetValue = targetValue, label = label)

        return remember {
            val corner =
                object : CornerSize {
                    override fun toPx(shapeSize: Size, density: Density): Float {
                        return with(density) { animatedCornerRadius.toPx() }
                    }
                }
            mutableStateOf(RoundedCornerShape(corner))
        }
    }
}

/**
 * A composable function that returns the [Resources]. It will be recomposed when [Configuration]
 * gets updated.
 */
@Composable
@ReadOnlyComposable
private fun resources(): Resources {
    LocalConfiguration.current
    return LocalResources.current
}
