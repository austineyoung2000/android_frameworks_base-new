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

/** Visual-only emergency-button decoration for non-PIN device credentials. */
final class CredentialEmergencyDecoration {
    private final View view;
    private final Runnable onChange;
    private final PinKeyAppearance appearance = new PinKeyAppearance();
    private String prefix;
    private boolean hasStyle;
    private final ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) { refresh(); }
    };

    CredentialEmergencyDecoration(View view, Runnable onChange) {
        this.view = view;
        this.onChange = onChange;
    }

    void attach() {
        ViewParent parent = view.getParent();
        while (parent instanceof View) {
            if (parent instanceof KeyguardPatternView) {
                prefix = "rising_pattern";
                hasStyle = true;
                break;
            } else if (parent instanceof KeyguardKnockCodeView) {
                prefix = "rising_knock";
                hasStyle = false;
                break;
            } else if (parent instanceof KeyguardPasswordView) {
                prefix = "rising_password";
                hasStyle = true;
                break;
            }
            parent = parent.getParent();
        }
        if (prefix == null) return;
        for (String suffix : new String[]{"_effect", "_style", "_first_color",
                "_second_color", "_glow_intensity", "_neon_transparency"}) {
            view.getContext().getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(prefix + suffix), false, observer, UserHandle.USER_ALL);
        }
        refresh();
    }

    void detach() {
        if (prefix != null) view.getContext().getContentResolver().unregisterContentObserver(observer);
        prefix = null;
    }

    boolean supported() { return prefix != null; }
    boolean customColor() { return supported() && appearance.customColor(); }
    void refresh() {
        if (!supported()) return;
        appearance.readEmergency(view.getContext(), prefix, hasStyle);
        onChange.run();
        view.invalidate();
    }
    void draw(Canvas canvas, int width, int height) {
        if (supported() && appearance.enabled()) {
            appearance.draw(canvas, width, height,
                    view.getResources().getDisplayMetrics().density, view.isEnabled(), 0);
        }
    }
}
