/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

import static com.android.wm.shell.shared.split.SplitScreenConstants.SNAP_TO_2_50_50;
import static com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

import com.android.systemui.LauncherProxyService;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.rr.RecentsStyle;
import com.android.systemui.shared.recents.ILauncherProxy;
import com.android.systemui.slimrecent.RecentController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.wm.shell.splitscreen.SplitScreen;

import dagger.Lazy;

import java.util.Optional;

import javax.inject.Inject;

/**
 * An implementation of the Recents interface which proxies to the LauncherProxyService.
 */
@SysUISingleton
public class OverviewProxyRecentsImpl implements RecentsImplementation {

    private final static String TAG = "OverviewProxyRecentsImpl";
    private Handler mHandler;
    private Context mContext;
    private final LauncherProxyService mLauncherProxyService;
    private final ActivityStarter mActivityStarter;
    private final KeyguardStateController mKeyguardStateController;
    private final Lazy<ScreenPinningRequest> mScreenPinningRequest;
    private final Optional<SplitScreen> mSplitScreen;
    private RecentController mSlimRecents;

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @Inject
    public OverviewProxyRecentsImpl(
            LauncherProxyService launcherProxyService,
            ActivityStarter activityStarter,
            KeyguardStateController keyguardStateController,
            Lazy<ScreenPinningRequest> screenPinningRequest,
            Optional<SplitScreen> splitScreen) {
        mLauncherProxyService = launcherProxyService;
        mActivityStarter = activityStarter;
        mKeyguardStateController = keyguardStateController;
        mScreenPinningRequest = screenPinningRequest;
        mSplitScreen = splitScreen;
    }

    @Override
    public void onStart(Context context) {
        mHandler = new Handler();
        mContext = context;
        RecentsStyle.setOnChange(() -> {
            int style = style();
            if (style != RecentsStyle.SLIM && mSlimRecents != null) {
                mSlimRecents.hideRecents(false);
            }
        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (mSlimRecents != null) {
            mSlimRecents.onConfigurationChanged(newConfig);
        }
    }

    @Override
    public void preloadRecentApps() {
        if (style() == RecentsStyle.SLIM && !mKeyguardStateController.isShowing()) {
            slimRecents().preloadRecentApps();
        }
    }

    @Override
    public void cancelPreloadRecentApps() {
        if (mSlimRecents != null) {
            mSlimRecents.cancelPreloadRecentApps();
        }
    }

    @Override
    public void showRecentApps(boolean triggeredFromAltTab) {
        if (!mKeyguardStateController.isShowing()) {
            switch (style()) {
                case RecentsStyle.SLIM:
                    slimRecents().showRecentApps();
                    return;
                default:
                    break;
            }
        }
        ILauncherProxy launcherProxy = mLauncherProxyService.getProxy();
        if (launcherProxy != null) {
            try {
                launcherProxy.onOverviewShown(triggeredFromAltTab);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send overview show event to launcher.", e);
            }
        }
    }

    @Override
    public void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        if (mSlimRecents != null) {
            mSlimRecents.hideRecentApps(triggeredFromAltTab, triggeredFromHomeKey);
        }
        // Home still has to close a launcher overview opened with a gesture.
        ILauncherProxy launcherProxy = mLauncherProxyService.getProxy();
        if (launcherProxy != null) {
            try {
                launcherProxy.onOverviewHidden(triggeredFromAltTab, triggeredFromHomeKey);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send overview hide event to launcher.", e);
            }
        }
    }

    @Override
    public void toggleRecentApps() {
        switch (style()) {
            case RecentsStyle.SLIM:
                afterKeyguard(() -> slimRecents().toggleRecentApps());
                return;
            default:
                toggleLauncher();
        }
    }

    private int style() {
        return RecentsStyle.get(mContext);
    }

    private RecentController slimRecents() {
        if (mSlimRecents == null) {
            mSlimRecents = new RecentController(mContext, new RecentController.Host() {
                @Override
                public void pinTask(int taskId) {
                    mScreenPinningRequest.get().showPrompt(taskId, false /* allowCancel */);
                }

                @Override
                public boolean splitTasks(int topTaskId, int bottomTaskId) {
                    if (mSplitScreen.isEmpty()) {
                        return false;
                    }
                    mSplitScreen.get().startTasks(topTaskId, null, bottomTaskId, null,
                            SPLIT_POSITION_TOP_OR_LEFT, SNAP_TO_2_50_50, null, null);
                    return true;
                }
            });
        }
        return mSlimRecents;
    }

    /** Recents button. Wait until the keyguard is gone so recents are not shown over the lock. */
    private void afterKeyguard(Runnable runnable) {
        if (mKeyguardStateController.isShowing()) {
            mActivityStarter.executeRunnableDismissingKeyguard(
                    () -> mHandler.post(runnable), null, true /* dismissShade */,
                    true /* afterKeyguardGone */,
                    true /* deferred */);
        } else {
            runnable.run();
        }
    }

    private void toggleLauncher() {
        // If connected to launcher service, let it handle the toggle logic
        ILauncherProxy launcherProxy = mLauncherProxyService.getProxy();
        if (launcherProxy != null) {
            final Runnable toggleRecents = () -> {
                try {
                    if (mLauncherProxyService.getProxy() != null) {
                        mLauncherProxyService.getProxy().onOverviewToggle();
                        mLauncherProxyService.notifyToggleRecentApps();
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Cannot send toggle recents through proxy service.", e);
                }
            };
            // Preload only if device for current user is unlocked
            if (mKeyguardStateController.isShowing()) {
                mActivityStarter.executeRunnableDismissingKeyguard(
                        () -> mHandler.post(toggleRecents), null, true /* dismissShade */,
                        false /* afterKeyguardGone */,
                        true /* deferred */);
            } else {
                toggleRecents.run();
            }
        }
    }
}
