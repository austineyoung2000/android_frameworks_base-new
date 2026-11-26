/*
 * Copyright (C) 2026 The RisingOS Android Project
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.keyguard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.UserHandle;
import android.provider.Settings;

/** Visual-only decoration for device PIN digit keys. No credential data is read. */
final class PinKeyAppearance {
    static final String[] KEYS = {"rising_pin_effect", "rising_pin_shape", "rising_pin_first_color",
            "rising_pin_second_color", "rising_pin_digit_color", "rising_pin_glow_intensity"};
    private int effect, shape, first, second, digit, intensity;
    private Bitmap bitmap;
    private int width, height;

    void read(Context context) {
        var r = context.getContentResolver();
        effect = Math.max(0, Math.min(4, Settings.System.getIntForUser(r, KEYS[0], 0, UserHandle.USER_CURRENT)));
        shape = Math.max(0, Math.min(3, Settings.System.getIntForUser(r, KEYS[1], 0, UserHandle.USER_CURRENT)));
        first = Settings.System.getIntForUser(r, KEYS[2], 0xff6200ee, UserHandle.USER_CURRENT);
        second = Settings.System.getIntForUser(r, KEYS[3], 0xff0000ff, UserHandle.USER_CURRENT);
        digit = Settings.System.getIntForUser(r, KEYS[4], Color.WHITE, UserHandle.USER_CURRENT);
        intensity = Math.max(0, Math.min(100, Settings.System.getIntForUser(r, KEYS[5], 60, UserHandle.USER_CURRENT)));
        bitmap = null;
    }
    boolean enabled() { return effect != 0; }
    int digitColor() { return digit; }
    void draw(Canvas canvas, int w, int h, float density, boolean enabled) {
        if (!enabled() || w <= 0 || h <= 0 || w > 1024 || h > 1024) return;
        if (bitmap == null || w != width || h != height) {
            width = w; height = h;
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas soft = new Canvas(bitmap);
            float inset = Math.min(Math.min(w, h) * .20f, 12f * density);
            RectF rect = new RectF(inset, inset, w - inset, h - inset);
            float radius = shape == 2 ? 3f * density : shape == 1 ? 14f * density
                    : shape == 3 ? Math.min(w, h) * .30f : Math.min(w, h) * .5f;
            int end = effect == 2 || effect == 4 ? second : first;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(rect.left, 0, rect.right, 0, first, end, Shader.TileMode.CLAMP));
            if ((effect == 3 || effect == 4) && intensity > 0) {
                p.setMaskFilter(new BlurMaskFilter((2f + 7f * intensity / 100f) * density,
                        BlurMaskFilter.Blur.NORMAL));
                p.setAlpha(150 * intensity / 100);
                soft.drawRoundRect(rect, radius, radius, p);
                p.setMaskFilter(null); p.setAlpha(100);
            } else { p.setAlpha(255); }
            soft.drawRoundRect(rect, radius, radius, p);
            if (effect == 3 || effect == 4) {
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f * density);
                p.setAlpha(255 * intensity / 100); soft.drawRoundRect(rect, radius, radius, p);
            }
        }
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        if (!enabled) paint.setAlpha(100);
        canvas.drawBitmap(bitmap, 0, 0, paint);
    }
}
