/*
 * Copyright (C) 2026 The Resurrected Remix Project
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

package com.android.systemui.rr;

import android.app.ActivityManager;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

/**
 * Which recents the recents key and the Launcher3 overview gesture open. Launcher3's Recents
 * settings screen writes it. Adapted from Resurrected Remix for Rising.
 */
public final class RecentsStyle {
    private static final String TAG = "RrRecentsStyle";

    /** Settings.System int, one of the values below. */
    public static final String KEY = "rising_slim_recents_style";

    public static final int LAUNCHER = 0;
    public static final int SLIM = 1;

    private static int sUser = UserHandle.USER_NULL;
    private static int sStyle = -1;
    private static boolean sWatching;
    private static boolean sWatchFailed;
    private static Context sContext;
    private static Runnable sOnChange;

    private RecentsStyle() {}

    public static int get(Context context) {
        if (context == null) {
            return LAUNCHER;
        }
        try {
            watch(context);
            int user = ActivityManager.getCurrentUser();
            if (!sWatching || sStyle < 0 || user != sUser) {
                sUser = user;
                sStyle = read(context, user);
            }
            return sStyle;
        } catch (RuntimeException e) {
            Log.w(TAG, "Recents style is not readable yet", e);
            return LAUNCHER;
        }
    }

    /** Runs on the main thread after the style changes. */
    public static void setOnChange(Runnable onChange) {
        sOnChange = onChange;
    }

    private static int read(Context context, int user) {
        int style = Settings.System.getIntForUser(
                context.getContentResolver(), KEY, -1, user);
        if (style < LAUNCHER || style > SLIM) {
            style = LAUNCHER;
        }
        return style;
    }

    private static void watch(Context context) {
        if (sWatching || sWatchFailed) {
            return;
        }
        Context app = context.getApplicationContext();
        sContext = app != null ? app : context;
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                sStyle = -1;
                if (sOnChange != null) {
                    sOnChange.run();
                }
            }
        };
        try {
            sContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(KEY), false, observer, UserHandle.USER_ALL);
            sWatching = true;
        } catch (RuntimeException e) {
            sWatchFailed = true;
            Log.w(TAG, "Not watching the recents style", e);
        }
    }
}
