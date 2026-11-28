/* Copyright (C) 2026 The RisingOS Android Project
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.keyguard;

import android.database.ContentObserver;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewParent;

/** Watches the existing PIN visual settings only while a device-PIN child is attached. */
final class PinViewDecoration {
    private final View view;
    private final Runnable onChange;
    private final PinKeyAppearance appearance = new PinKeyAppearance();
    private boolean attached;
    private final ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) { refresh(); }
    };
    PinViewDecoration(View view, Runnable onChange) { this.view = view; this.onChange = onChange; }
    void attach() {
        ViewParent p = view.getParent();
        while (p instanceof View) {
            if (p instanceof KeyguardPINView) { attached = true; break; }
            p = p.getParent();
        }
        if (!attached) return;
        for (String key : PinKeyAppearance.KEYS) view.getContext().getContentResolver()
                .registerContentObserver(Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL);
        refresh();
    }
    void detach() {
        if (attached) view.getContext().getContentResolver().unregisterContentObserver(observer);
        attached = false;
    }
    void refresh() {
        if (!attached) return;
        appearance.read(view.getContext());
        onChange.run();
        view.invalidate();
    }
    boolean isDevicePin() { return attached; }
    boolean active() { return attached && appearance.enabled(); }
    boolean customColor() { return attached && appearance.customColor(); }
    int firstColor() { return appearance.firstColor(); }
    void draw(Canvas canvas, int width, int height) {
        if (active()) appearance.draw(canvas, width, height,
                view.getResources().getDisplayMetrics().density, view.isEnabled(), 0);
    }
}
