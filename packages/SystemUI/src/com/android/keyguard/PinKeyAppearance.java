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
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.UserHandle;
import android.provider.Settings;

/** Visual-only decoration for device PIN digit keys. No credential data is read. */
final class PinKeyAppearance {
    static final String[] KEYS = {"rising_pin_effect", "rising_pin_shape", "rising_pin_first_color",
            "rising_pin_second_color", "rising_pin_digit_color", "rising_pin_glow_intensity",
            "rising_pin_style", "rising_pin_neon_transparency"};
    private int effect, shape, first, second, digit, intensity, style, transparency;
    private Bitmap halo;
    private int width, height, padding;
    private float cachedDensity;

    void read(Context context) {
        var r = context.getContentResolver();
        effect = Math.max(0, Math.min(4, Settings.System.getIntForUser(r, KEYS[0], 0, UserHandle.USER_CURRENT)));
        shape = Math.max(0, Math.min(3, Settings.System.getIntForUser(r, KEYS[1], 0, UserHandle.USER_CURRENT)));
        first = Settings.System.getIntForUser(r, KEYS[2], 0xff6200ee, UserHandle.USER_CURRENT);
        second = Settings.System.getIntForUser(r, KEYS[3], 0xff0000ff, UserHandle.USER_CURRENT);
        digit = Settings.System.getIntForUser(r, KEYS[4], Color.WHITE, UserHandle.USER_CURRENT);
        intensity = Math.max(0, Math.min(100, Settings.System.getIntForUser(r, KEYS[5], 60, UserHandle.USER_CURRENT)));
        style = effect == 0 ? Math.max(0, Math.min(7, Settings.System.getIntForUser(r, KEYS[6], 0, UserHandle.USER_CURRENT))) : 0;
        transparency = Math.max(0, Math.min(100, Settings.System.getIntForUser(r, KEYS[7], 78, UserHandle.USER_CURRENT)));
        halo = null;
    }

    /** Read the active credential's appearance for its emergency button. */
    void readEmergency(Context context, String prefix, boolean hasStyle) {
        var r = context.getContentResolver();
        effect = Math.max(0, Math.min(4, Settings.System.getIntForUser(r,
                prefix + "_effect", 0, UserHandle.USER_CURRENT)));
        shape = 0;
        first = Settings.System.getIntForUser(r, prefix + "_first_color",
                0xff6200ee, UserHandle.USER_CURRENT);
        second = Settings.System.getIntForUser(r, prefix + "_second_color",
                0xff0000ff, UserHandle.USER_CURRENT);
        intensity = Math.max(0, Math.min(100, Settings.System.getIntForUser(r,
                prefix + "_glow_intensity", 60, UserHandle.USER_CURRENT)));
        transparency = Math.max(0, Math.min(100, Settings.System.getIntForUser(r,
                prefix + "_neon_transparency", 78, UserHandle.USER_CURRENT)));
        style = hasStyle && effect == 0 ? Math.max(0, Math.min(7, Settings.System.getIntForUser(r,
                prefix + "_style", 0, UserHandle.USER_CURRENT))) : 0;
        halo = null;
    }
    boolean enabled() { return effect != 0 || style != 0; }
    boolean customColor() { return effect != 0; }
    int digitColor() { return digit; }
    int firstColor() { return first; }

    void draw(Canvas canvas, int w, int h, float density, boolean enabled) {
        draw(canvas, w, h, density, enabled, -1);
    }

    // Other credential controls keep their native circular or pill geometry.
    void draw(Canvas canvas, int w, int h, float density, boolean enabled, int shapeOverride) {
        if (!enabled() || w <= 0 || h <= 0 || w > 1024 || h > 1024 || density <= 0) return;
        float inset = Math.min(Math.min(w, h) * .08f, 4f * density);
        RectF rect = new RectF(inset, inset, w - inset, h - inset);
        int displayShape = shapeOverride >= 0 ? shapeOverride : effect == 0 && style == 0 ? 0 : shape;
        float radius = displayShape == 2 ? 3f * density : displayShape == 1 ? 14f * density
                : displayShape == 3 ? Math.min(rect.width(), rect.height()) * .30f
                : Math.min(rect.width(), rect.height()) * .5f;
        radius = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
        Path path = new Path();
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);
        int end = effect == 2 || effect == 4 ? second : first;
        // Include the full blur in the offscreen layer. A null layer bound can be
        // clipped to the key rectangle, leaving a hard square edge on the halo.
        int haloPadding = (effect == 3 || effect == 4) && intensity > 0
                ? (int) Math.ceil((2f + 14f * intensity / 100f) * density * 2f) : 0;
        RectF layerBounds = new RectF(-haloPadding, -haloPadding,
                w + haloPadding, h + haloPadding);
        int save = canvas.saveLayerAlpha(layerBounds, enabled ? 255 : 100);
        try {
            if ((effect == 3 || effect == 4) && intensity > 0) {
                drawHalo(canvas, w, h, density, rect, radius, end, path);
            }
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (effect != 0) {
                p.setShader(new LinearGradient(rect.left, 0, rect.right, 0, first, end, Shader.TileMode.CLAMP));
                p.setAlpha(effect == 3 || effect == 4 ? (100 - transparency) * 255 / 100 : 255);
                canvas.drawPath(path, p);
                p.setShader(null);
            }
            canvas.save(); canvas.clipPath(path);
            if (style == 2 || style == 3 || style == 5 || style == 7) {
                int top = style == 3 ? 0x40000000 : 0x40ffffff;
                int bottom = style == 2 ? Color.TRANSPARENT : 0x33000000;
                boolean horizontal = style == 7;
                p.setShader(new LinearGradient(rect.left, rect.top,
                        horizontal ? rect.right : rect.left, horizontal ? rect.top : rect.bottom,
                        top, bottom, Shader.TileMode.CLAMP));
                p.setAlpha(255); canvas.drawRect(rect, p); p.setShader(null);
            }
            if (style == 6) {
                p.setColor(0x29ffffff); canvas.drawRect(rect.left, rect.top,
                        rect.left + rect.width() * .55f, rect.bottom, p);
                p.setColor(0x29000000); canvas.drawRect(rect.left + rect.width() * .58f,
                        rect.top, rect.right, rect.bottom, p);
            }
            if (style == 1 || style == 2 || style == 4 || style == 5 || style == 6) {
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(density * (style == 1 || style == 6 ? 2f : 1f));
                p.setColor(0x80ffffff); canvas.drawPath(path, p); p.setStyle(Paint.Style.FILL);
            }
            if ((effect == 3 || effect == 4) && intensity > 0) {
                p.setStyle(Paint.Style.STROKE);
                p.setShader(new LinearGradient(rect.left, 0, rect.right, 0, first, end, Shader.TileMode.CLAMP));
                for (int layer = 5; layer >= 1; layer--) {
                    p.setStrokeWidth(layer * 2f * density);
                    int alpha = 255 * intensity / 100;
                    p.setAlpha(layer == 1 ? alpha * 9 / 10 : alpha / 10);
                    canvas.drawPath(path, p);
                }
            }
            canvas.restore();
        } finally { canvas.restoreToCount(save); }
    }

    private void drawHalo(Canvas canvas, int w, int h, float density, RectF rect,
            float radius, int end, Path path) {
        if (halo == null || w != width || h != height || density != cachedDensity) {
            width = w; height = h; cachedDensity = density;
            float blur = (2f + 14f * intensity / 100f) * density;
            padding = (int) Math.ceil(blur * 2f);
            int bw = Math.max(1, (int) Math.ceil((w + 2f * padding) * .5f));
            int bh = Math.max(1, (int) Math.ceil((h + 2f * padding) * .5f));
            halo = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas soft = new Canvas(halo); soft.scale(.5f, .5f); soft.translate(padding, padding);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
            p.setShader(new LinearGradient(rect.left, 0, rect.right, 0, first, end, Shader.TileMode.CLAMP));
            p.setAlpha(intensity * 65 * 255 / 10000);
            soft.drawRoundRect(rect, radius, radius, p);
        }
        canvas.save(); canvas.clipOutPath(path);
        canvas.drawBitmap(halo, null, new RectF(-padding, -padding, w + padding, h + padding),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        canvas.restore();
    }
}
