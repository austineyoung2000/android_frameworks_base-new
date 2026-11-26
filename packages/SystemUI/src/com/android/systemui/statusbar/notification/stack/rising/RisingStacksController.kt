/*
 * Copyright (C) 2026 The RisingOS Android Project
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.statusbar.notification.stack.rising

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.collection.BundleEntry
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.PipelineEntry
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProvider
import com.android.systemui.tuner.TunerService
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Optional per-app filtering on keyguard. Selection never dismisses a notification. */
@SysUISingleton
class RisingStacksController @Inject constructor(
    private val tunerService: TunerService,
    private val statusBarStateController: StatusBarStateController,
    private val visibilityProvider: KeyguardNotificationVisibilityProvider,
) {
    data class UiState(
        val visible: Boolean = false,
        val groups: List<RisingStacksModel.AppGroup> = emptyList(),
        val selected: RisingStacksModel.AppKey? = null,
        val iconPack: String? = null,
        val iconSize: Int = 30,
        val effect: Int = 0,
        val firstColor: Int = 0xffff0055.toInt(),
        val secondColor: Int = 0xff315bff.toInt(),
        val outline: Boolean = true,
        val glowIntensity: Int = 60,
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState
    private var enabled = false
    private var selected: RisingStacksModel.AppKey? = null
    private var allSelectedByUser = false
    private var iconPack: String? = null
    private var iconSize = 30
    private var effect = 0
    private var firstColor = 0xffff0055.toInt()
    private var secondColor = 0xff315bff.toInt()
    private var outline = true
    private var glowIntensity = 60
    private var attached = false
    private var visibleGroups: List<RisingStacksModel.AppGroup> = emptyList()

    private fun onLockscreen(): Boolean =
        statusBarStateController.state == StatusBarState.KEYGUARD &&
            !statusBarStateController.isDozing

    private val filter = object : NotifFilter("RisingStacks") {
        override fun shouldFilterOut(entry: NotificationEntry, now: Long): Boolean {
            val chosen = selected ?: return false
            if (!enabled || !onLockscreen()) return false
            return keyFor(entry) != chosen
        }
    }

    fun attach(pipeline: NotifPipeline) {
        if (attached) return
        attached = true
        pipeline.addOnBeforeFinalizeFilterListener { entries ->
            updateGroups(entries)
        }
        pipeline.addFinalizeFilter(filter)
        statusBarStateController.addCallback(object : StatusBarStateController.StateListener {
            override fun onStateChanged(newState: Int) {
                if (newState != StatusBarState.KEYGUARD) {
                    selected = null
                    allSelectedByUser = false
                }
                publish()
                filter.invalidateList("Rising Stacks lock screen state changed")
            }

            override fun onDozingChanged(isDozing: Boolean) {
                publish()
                filter.invalidateList("Rising Stacks doze state changed")
            }
        })
        tunerService.addTunable({ _, value ->
            enabled = TunerService.parseIntegerSwitch(value, false)
            // Tuner replays settings on user changes. Never retain another user's icons or selection.
            selected = null
            allSelectedByUser = false
            visibleGroups = emptyList()
            publish()
            filter.invalidateList("Rising Stacks setting changed")
        }, "system:rising_stacks_enabled")
        tunerService.addTunable({ _, value ->
            iconPack = value?.takeIf { it.isNotBlank() }
            publish()
        }, "system:rising_stacks_icon_pack")
        tunerService.addTunable({ key, value ->
            when (key) {
                "system:rising_stacks_icon_size" -> iconSize = (value?.toIntOrNull() ?: 30).coerceIn(24, 60)
                "system:rising_stacks_effect" -> effect = (value?.toIntOrNull() ?: 0).coerceIn(0, 4)
                "system:rising_stacks_first_color" -> firstColor = value?.toIntOrNull() ?: 0xffff0055.toInt()
                "system:rising_stacks_second_color" -> secondColor = value?.toIntOrNull() ?: 0xff315bff.toInt()
                "system:rising_stacks_outline" -> outline = value?.toIntOrNull()?.let { it != 0 } ?: true
                "system:rising_stacks_glow_intensity" -> glowIntensity = (value?.toIntOrNull() ?: 60).coerceIn(0, 100)
            }
            publish()
        }, "system:rising_stacks_icon_size", "system:rising_stacks_effect",
            "system:rising_stacks_first_color", "system:rising_stacks_second_color",
            "system:rising_stacks_outline", "system:rising_stacks_glow_intensity")
    }

    fun select(app: RisingStacksModel.AppKey?) {
        if (!enabled || !onLockscreen()) return
        if (app != null && visibleGroups.none { it.app == app }) return
        if (selected == app && allSelectedByUser == (app == null)) return
        allSelectedByUser = app == null
        selected = app
        publish()
        filter.invalidateList("Rising Stacks selection changed")
    }

    private fun updateGroups(entries: List<PipelineEntry>) {
        if (!enabled || !onLockscreen()) {
            visibleGroups = emptyList()
            publish()
            return
        }
        val eligible = ArrayList<RisingStacksModel.Entry>()
        fun add(entry: NotificationEntry) {
            // Respect existing privacy and lock-screen visibility rules before exposing app icons.
            if (visibilityProvider.shouldHideNotificationOnKeyguard(entry)) return
            val sbn = entry.sbn
            eligible.add(RisingStacksModel.Entry(
                entry.key, keyFor(entry), sbn.groupKey,
                sbn.notification.isGroupSummary, entry.ranking.rank, sbn.postTime
            ))
        }
        fun visit(entry: PipelineEntry) {
            when (entry) {
                is NotificationEntry -> add(entry)
                is GroupEntry -> {
                    entry.summary?.let(::add)
                    entry.children.forEach(::add)
                }
                is BundleEntry -> entry.children.forEach(::visit)
                else -> Unit // Other pipeline entries have no supported notification children.
            }
        }
        entries.forEach(::visit)
        val snapshot = RisingStacksModel.build(eligible, selected)
        selected = if (allSelectedByUser) null else
            snapshot.selectedApp ?: snapshot.apps.firstOrNull()?.app
        visibleGroups = snapshot.apps
        publish()
    }

    private fun publish() {
        val show = enabled && onLockscreen() &&
            visibleGroups.isNotEmpty()
        mutableState.value = UiState(show, if (show) visibleGroups else emptyList(), selected, iconPack,
            iconSize, effect, firstColor, secondColor, outline, glowIntensity)
    }

    private fun keyFor(entry: NotificationEntry): RisingStacksModel.AppKey =
        RisingStacksModel.AppKey(entry.sbn.packageName, entry.sbn.user.identifier)
}
