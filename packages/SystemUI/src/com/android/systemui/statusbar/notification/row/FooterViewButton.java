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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.ViewGroup;

import com.android.systemui.statusbar.AlphaOptimizedButton;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;

public class FooterViewButton extends AlphaOptimizedButton {
    private int mCardStyle;
    private int mNeonMode, mNeonFirst, mNeonSecond, mNeonIntensity;
    private Bitmap mNeonBitmap;

    public void setNeon(int mode, int first, int second, int intensity) {
        if (mNeonMode == mode && mNeonFirst == first && mNeonSecond == second
                && mNeonIntensity == intensity) return;
        mNeonMode = mode; mNeonFirst = first; mNeonSecond = second;
        mNeonIntensity = intensity; mNeonBitmap = null; invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mNeonBitmap = null;
    }

    private void drawNeon(Canvas canvas) {
        if ((mNeonMode != 5 && mNeonMode != 6) || mNeonIntensity <= 0
                || getWidth() <= 0 || getHeight() <= 0) return;
        final float density = getResources().getDisplayMetrics().density;
        final int pad = (int) Math.ceil(24f * density);
        final int w = getWidth(), h = getHeight();
        if (mNeonBitmap == null) {
            mNeonBitmap = Bitmap.createBitmap((w + 2 * pad + 1) / 2,
                    (h + 2 * pad + 1) / 2, Bitmap.Config.ARGB_8888);
            Canvas soft = new Canvas(mNeonBitmap);
            soft.scale(.5f, .5f); soft.translate(pad, pad);
            Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
            halo.setMaskFilter(new BlurMaskFilter((2f + 10f * mNeonIntensity / 100f)
                    * density, BlurMaskFilter.Blur.NORMAL));
            halo.setShader(new LinearGradient(0, 0, w, 0, mNeonFirst,
                    mNeonMode == 6 ? mNeonSecond : mNeonFirst, Shader.TileMode.CLAMP));
            halo.setAlpha(130 * mNeonIntensity / 100);
            soft.drawRoundRect(2f * density, 4f * density, w - 2f * density,
                    h - 4f * density, 20f * density, 20f * density, halo);
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(mNeonBitmap, null, new RectF(-pad, -pad, w + pad, h + pad), paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2f * density);
        paint.setShader(new LinearGradient(0, 0, w, 0, mNeonFirst,
                mNeonMode == 6 ? mNeonSecond : mNeonFirst, Shader.TileMode.CLAMP));
        paint.setAlpha(225 * mNeonIntensity / 100);
        canvas.drawRoundRect(2f * density, 5f * density, w - 2f * density,
                h - 5f * density, 20f * density, 20f * density, paint);
    }

    private final Paint mCardStylePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public void setCardStyle(int style) {
        if (mCardStyle != style) {
            mCardStyle = style;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawNeon(canvas);
        if (mCardStyle >= 1 && mCardStyle <= 7) {
            final float density = getResources().getDisplayMetrics().density;
            final RectF rect = new RectF(0f, 4f * density, getWidth(),
                    getHeight() - 4f * density);
            if (!rect.isEmpty()) {
                final float radius = Math.min(20f * density, rect.height() / 2f);
                final Path shape = new Path();
                shape.addRoundRect(rect, radius, radius, Path.Direction.CW);
                canvas.save();
                canvas.clipPath(shape);
                final Paint paint = mCardStylePaint;
                paint.reset();
                paint.setAntiAlias(true);
                if (mCardStyle == 2 || mCardStyle == 3 || mCardStyle == 5
                        || mCardStyle == 7) {
                    int first = mCardStyle == 3 ? 0x40000000 : 0x40ffffff;
                    int last = mCardStyle == 2 ? Color.TRANSPARENT : 0x33000000;
                    boolean horizontal = mCardStyle == 7;
                    paint.setShader(new LinearGradient(rect.left, rect.top,
                            horizontal ? rect.right : rect.left,
                            horizontal ? rect.top : rect.bottom,
                            first, last, Shader.TileMode.CLAMP));
                    canvas.drawRect(rect, paint);
                    paint.setShader(null);
                }
                if (mCardStyle == 6) {
                    paint.setColor(0x29ffffff);
                    canvas.drawRect(rect.left, rect.top, rect.left + rect.width() * 0.55f,
                            rect.bottom, paint);
                    paint.setColor(0x29000000);
                    canvas.drawRect(rect.left + rect.width() * 0.58f, rect.top, rect.right,
                            rect.bottom, paint);
                }
                if (mCardStyle == 1 || mCardStyle == 2 || mCardStyle == 4
                        || mCardStyle == 5 || mCardStyle == 6) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(density * ((mCardStyle == 1 || mCardStyle == 6) ? 2f : 1f));
                    paint.setColor(0x80ffffff);
                    canvas.drawPath(shape, paint);
                }
                canvas.restore();
            }
        }
        super.onDraw(canvas);
    }


    public FooterViewButton(Context context) {
        this(context, null);
    }

    public FooterViewButton(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FooterViewButton(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public FooterViewButton(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    /**
     * This method returns the drawing rect for the view which is different from the regular
     * drawing rect, since we layout all children in the {@link NotificationStackScrollLayout} at
     * position 0 and usually the translation is neglected. The standard implementation doesn't
     * account for translation.
     *
     * @param outRect The (scrolled) drawing bounds of the view.
     */
    @Override
    public void getDrawingRect(Rect outRect) {
        super.getDrawingRect(outRect);
        float translationX = ((ViewGroup) mParent).getTranslationX();
        float translationY = ((ViewGroup) mParent).getTranslationY();
        outRect.left += translationX;
        outRect.right += translationX;
        outRect.top += translationY;
        outRect.bottom += translationY;
    }
}
