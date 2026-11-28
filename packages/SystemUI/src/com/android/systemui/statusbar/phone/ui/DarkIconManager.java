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

package com.android.systemui.statusbar.phone.ui;

import android.widget.LinearLayout;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.UserHandle;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.tuner.TunerService;

import com.android.systemui.kairos.KairosNetwork;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.statusbar.StatusIconDisplayable;
import com.android.systemui.statusbar.connectivity.ui.MobileContextProvider;
import com.android.systemui.statusbar.phone.DemoStatusIcons;
import com.android.systemui.statusbar.phone.StatusBarIconHolder;
import com.android.systemui.statusbar.phone.StatusBarLocation;
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapter;
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapterKairos;
import com.android.systemui.statusbar.pipeline.wifi.ui.WifiUiAdapter;

import dagger.Lazy;
import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import kotlin.OptIn;

import kotlinx.coroutines.CoroutineScope;

/** Version of {@link IconManager} that observes state from the {@link DarkIconDispatcher}. */
public class DarkIconManager extends IconManager {
    private final DarkIconDispatcher mDarkIconDispatcher;
    private final int mIconHorizontalMargin;
    private final TunerService mTunerService;
    private final boolean mIsHome;
    private final Map<DarkIconDispatcher.DarkReceiver, DarkIconDispatcher.DarkReceiver> mReceivers =
            new HashMap<>();
    private boolean mCustomColorEnabled;
    private int mCustomColor = Color.WHITE;
    private static final String COLOR_ENABLED = "status_bar_system_icon_color_enabled";
    private static final String COLOR = "status_bar_system_icon_color";
    private final TunerService.Tunable mColorTunable = this::onColorSettingChanged;

    private void onColorSettingChanged(String key, String value) {
        mCustomColorEnabled = Settings.System.getIntForUser(
                mContext.getContentResolver(), COLOR_ENABLED, 0, UserHandle.USER_CURRENT) != 0;
        mCustomColor = Settings.System.getIntForUser(
                mContext.getContentResolver(), COLOR, Color.WHITE, UserHandle.USER_CURRENT);
        for (DarkIconDispatcher.DarkReceiver receiver : mReceivers.values()) {
            mDarkIconDispatcher.applyDark(receiver);
        }
    }

    private DarkIconDispatcher.DarkReceiver wrap(DarkIconDispatcher.DarkReceiver target) {
        return new DarkIconDispatcher.DarkReceiver() {
            @Override
            public void onDarkChanged(ArrayList<Rect> areas, float intensity, int tint) {
                target.onDarkChanged(mCustomColorEnabled ? new ArrayList<>() : areas,
                        intensity, mCustomColorEnabled ? mCustomColor : tint);
            }

            @Override
            public void onDarkChangedWithContrast(ArrayList<Rect> areas, int tint, int contrast) {
                int foreground = Color.luminance(mCustomColor) > 0.5f ? Color.BLACK : Color.WHITE;
                target.onDarkChangedWithContrast(mCustomColorEnabled ? new ArrayList<>() : areas,
                        mCustomColorEnabled ? mCustomColor : tint,
                        mCustomColorEnabled ? foreground : contrast);
            }
        };
    }

    @AssistedInject
    public DarkIconManager(
            @Assisted LinearLayout linearLayout,
            @Assisted StatusBarLocation location,
            WifiUiAdapter wifiUiAdapter,
            MobileUiAdapter mobileUiAdapter,
            Lazy<MobileUiAdapterKairos> mobileUiAdapterKairos,
            MobileContextProvider mobileContextProvider,
            KairosNetwork kairosNetwork,
            @Application CoroutineScope appScope,
            TunerService tunerService,
            @Assisted DarkIconDispatcher darkIconDispatcher) {
        super(linearLayout,
                location,
                wifiUiAdapter,
                mobileUiAdapter,
                mobileUiAdapterKairos,
                mobileContextProvider,
                kairosNetwork,
                appScope);
        mIconHorizontalMargin = mContext.getResources().getDimensionPixelSize(
                com.android.systemui.res.R.dimen.status_bar_icon_horizontal_margin);
        mDarkIconDispatcher = darkIconDispatcher;
        mTunerService = tunerService;
        mIsHome = location == StatusBarLocation.HOME;
        if (mIsHome) mTunerService.addTunable(mColorTunable,
                "system:" + COLOR_ENABLED, "system:" + COLOR);
    }

    @Override
    protected void onIconAdded(
            int index, String slot, boolean blocked, StatusBarIconHolder holder) {
        StatusIconDisplayable view = addHolder(index, slot, blocked, holder);
        DarkIconDispatcher.DarkReceiver receiver = mIsHome ? wrap(view) : view;
        mReceivers.put(view, receiver);
        mDarkIconDispatcher.addDarkReceiver(receiver);
    }

    @Override
    protected LinearLayout.LayoutParams onCreateLayoutParams(StatusBarIcon.Shape shape) {
        LinearLayout.LayoutParams lp = super.onCreateLayoutParams(shape);
        lp.setMargins(mIconHorizontalMargin, 0, mIconHorizontalMargin, 0);
        return lp;
    }

    @Override
    protected void destroy() {
        for (int i = 0; i < mGroup.getChildCount(); i++) {
            DarkIconDispatcher.DarkReceiver view =
                    (DarkIconDispatcher.DarkReceiver) mGroup.getChildAt(i);
            DarkIconDispatcher.DarkReceiver receiver = mReceivers.remove(view);
            if (receiver != null) mDarkIconDispatcher.removeDarkReceiver(receiver);
        }
        if (mIsHome) mTunerService.removeTunable(mColorTunable);
        mGroup.removeAllViews();
    }

    @Override
    protected void onRemoveIcon(int viewIndex) {
        DarkIconDispatcher.DarkReceiver receiver = mReceivers.remove(
                (DarkIconDispatcher.DarkReceiver) mGroup.getChildAt(viewIndex));
        if (receiver != null) mDarkIconDispatcher.removeDarkReceiver(receiver);
        super.onRemoveIcon(viewIndex);
    }

    @Override
    public void onSetIcon(int viewIndex, StatusBarIcon icon) {
        super.onSetIcon(viewIndex, icon);
        DarkIconDispatcher.DarkReceiver receiver = mReceivers.get(
                (DarkIconDispatcher.DarkReceiver) mGroup.getChildAt(viewIndex));
        if (receiver != null) mDarkIconDispatcher.applyDark(receiver);
    }

    @Override
    protected DemoStatusIcons createDemoStatusIcons() {
        DemoStatusIcons icons = super.createDemoStatusIcons();
        mDarkIconDispatcher.addDarkReceiver(icons);

        return icons;
    }

    @Override
    protected void exitDemoMode() {
        mDarkIconDispatcher.removeDarkReceiver(mDemoStatusIcons);
        super.exitDemoMode();
    }

    /**  */
    @AssistedFactory
    public interface Factory {

        /** Creates a new {@link DarkIconManager}. */
        DarkIconManager create(
                LinearLayout group,
                StatusBarLocation location,
                DarkIconDispatcher darkIconDispatcher);
    }
}
