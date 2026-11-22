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
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState
    private var enabled = false
    private var selected: RisingStacksModel.AppKey? = null
    private var iconPack: String? = null
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
                if (newState != StatusBarState.KEYGUARD) selected = null
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
            visibleGroups = emptyList()
            publish()
            filter.invalidateList("Rising Stacks setting changed")
        }, "system:rising_stacks_enabled")
        tunerService.addTunable({ _, value ->
            iconPack = value?.takeIf { it.isNotBlank() }
            publish()
        }, "system:rising_stacks_icon_pack")
    }

    fun select(app: RisingStacksModel.AppKey?) {
        if (!enabled || !onLockscreen()) return
        if (app != null && visibleGroups.none { it.app == app }) return
        if (selected == app) return
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
                sbn.notification.isGroupSummary, entry.ranking.rank
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
        selected = snapshot.selectedApp
        visibleGroups = snapshot.apps
        publish()
    }

    private fun publish() {
        val show = enabled && onLockscreen() &&
            visibleGroups.isNotEmpty()
        mutableState.value = UiState(show, if (show) visibleGroups else emptyList(), selected, iconPack)
    }

    private fun keyFor(entry: NotificationEntry): RisingStacksModel.AppKey =
        RisingStacksModel.AppKey(entry.sbn.packageName, entry.sbn.user.identifier)
}
