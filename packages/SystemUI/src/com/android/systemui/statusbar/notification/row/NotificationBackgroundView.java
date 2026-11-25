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

import static com.android.systemui.Flags.lockscreenBlurForNotifications;
import static com.android.systemui.util.ColorUtilKt.hexColorString;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.UserHandle;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.CrossWindowBlurListeners;
import java.util.function.Consumer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;

import com.android.axion.blur.AxBlurBackgroundRenderer;
import com.android.axion.blur.AxBlurColors;
import com.android.axion.blur.model.AxBackdropBlurSettingsSpec;
import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.Dumpable;
import com.android.systemui.Dependency;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.common.shared.colors.SurfaceEffectColors;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.shared.NotificationAddXOnHoverToDismiss;
import com.android.systemui.util.Assert;
import com.android.systemui.util.DrawableDumpKt;
import com.android.systemui.util.ScrimUtils;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * A view that can be used for both the dimmed and normal background of an notification.
 */
public class NotificationBackgroundView extends View implements Dumpable,
        ExpandableNotificationRow.DismissButtonTargetVisibilityListener {

    private final AxBlurBackgroundRenderer mAxBlurRenderer;
    private final Object mAxBlurKey = new Object();
    private final Rect mBackgroundBounds = new Rect();
    private final boolean mDontModifyCorners;
    private Drawable mBackground;
    private int mClipTopAmount;
    private int mTopOverlap;
    private int mBottomOverlap;
    private int mClipBottomAmount;
    private int mTintColor;
    @Nullable private Integer mRippleColor;
    private final float[] mCornerRadii = new float[8];
    private final float[] mFocusOverlayCornerRadii = new float[8];
    private float mFocusOverlayStroke = 0;
    private boolean mBottomIsRounded;
    private boolean mBottomAmountClips = true;
    private int mActualHeight = -1;
    private int mActualWidth = -1;
    private boolean mExpandAnimationRunning;
    private int mExpandAnimationWidth = -1;
    private int mExpandAnimationHeight = -1;
    private int mDrawableAlpha = 255;
    private final ColorStateList mLightColoredStatefulColors;
    private final ColorStateList mDarkColoredStatefulColors;
    private int mNormalColor;
    private final int convexR = 9;
    private final int concaveR = 22;
    private BackgroundBlurDrawable mBackgroundBlurDrawable;
    private BackgroundBlurDrawable mCardBlurDrawable;
    private final Consumer<Boolean> mCardBlurListener = enabled -> updateCardBlur();
    @VisibleForTesting
    protected View.OnAttachStateChangeListener mOnAttachStateChangeListener;
    private boolean mAxBlurEnabled;
    private int mAxBlurOverlayColor;
    private final GradientDrawable mMediaArtBackground = new GradientDrawable();
    private boolean mMediaArtVisible;
    private final ScrimUtils.ScrimEventListener mScrimListener =
            new ScrimUtils.ScrimEventListener() {
                @Override
                public void onMediaArtVisibilityChanged(boolean visible) {
                    setMediaArtVisible(visible);
                }
            };

    // True only if the dismiss button is visible.
    private boolean mDrawDismissButtonCutout = false;
    private boolean mOnKeyguard = true;

    private boolean mIsBlurSupported = false;
    private int mCardColorMode;
    private int mCardGlowIntensity;
    private int mCardColorFirst = Color.MAGENTA;
    private int mCardColorSecond = Color.BLUE;
    private int mCardStyle;
    private boolean mHadCustomCardColor;
    private final Paint mCardStylePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TunerService.Tunable mCardAppearanceTunable = (key, value) ->
            updateCardAppearance();

    private void updateCardAppearance() {
        final android.content.ContentResolver resolver = mContext.getContentResolver();
        mCardColorMode = Settings.System.getIntForUser(resolver,
                "notification_card_color_mode", 0, UserHandle.USER_CURRENT);
        mCardColorFirst = Settings.System.getIntForUser(resolver,
                "notification_card_color_first", Color.MAGENTA, UserHandle.USER_CURRENT);
        mCardColorSecond = Settings.System.getIntForUser(resolver,
                "notification_card_color_second", Color.BLUE, UserHandle.USER_CURRENT);
        mCardGlowIntensity = Math.max(0, Math.min(100, Settings.System.getIntForUser(resolver,
                "notification_card_glow_intensity", 60, UserHandle.USER_CURRENT)));
        mCardStyle = Settings.System.getIntForUser(resolver,
                "notification_card_style", 0, UserHandle.USER_CURRENT);
        updateCardBlur();
        invalidate();
    }

    private boolean usesCustomCardBlur() {
        return mCardColorMode == 3 || mCardColorMode == 4;
    }

    private boolean customCardBlurAvailable() {
        return usesCustomCardBlur() && mCardBlurDrawable != null
                && CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled();
    }

    private void updateCardBlur() {
        if (usesCustomCardBlur() && isAttachedToWindow() && mCardBlurDrawable == null
                && getViewRootImpl() != null) {
            mCardBlurDrawable = getViewRootImpl().createBackgroundBlurDrawable();
            if (mCardBlurDrawable != null) {
                mCardBlurDrawable.setBlurRadius(getResources().getDimensionPixelSize(
                        R.dimen.notification_background_blur_radius));
                mCardBlurDrawable.setXfermode(null);
                mCardBlurDrawable.setColor(Color.TRANSPARENT);
                mCardBlurDrawable.setCallback(this);
                mCardBlurDrawable.setCornerRadius(mCornerRadii[0], mCornerRadii[1],
                        mCornerRadii[2], mCornerRadii[3], mCornerRadii[4], mCornerRadii[5],
                        mCornerRadii[6], mCornerRadii[7]);
                mCardBlurDrawable.setAlpha(mDrawableAlpha);
            }
        }
        if (mCardBlurDrawable != null) {
            mCardBlurDrawable.setVisible(customCardBlurAvailable() && isShown(), false);
        }
        if (mBackground != null) setTint(mTintColor);
        invalidate();
    }

    private void drawCustomCardBlur(Canvas canvas, Rect bounds) {
        if (customCardBlurAvailable() && bounds != null && !bounds.isEmpty()
                && canvas.isHardwareAccelerated()) {
            mCardBlurDrawable.setBounds(bounds);
            mCardBlurDrawable.draw(canvas);
        }
    }

    public NotificationBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mAxBlurRenderer = new AxBlurBackgroundRenderer(this, AxBackdropBlurSettingsSpec.system(),
                false);
        mAxBlurOverlayColor = AxBlurColors.surfaceBrightTint(getContext());
        mDontModifyCorners = getResources().getBoolean(R.bool.config_clipNotificationsToOutline);
        mLightColoredStatefulColors = getResources().getColorStateList(
                R.color.notification_state_color_light);
        mDarkColoredStatefulColors = getResources().getColorStateList(
                R.color.notification_state_color_dark);

        boolean useAltColor = Settings.System.getInt(
                context.getContentResolver(),
                Settings.System.NOTIFICATION_BG_ALTERNATE_COLOR,
                0
        ) != 0;

        if (mIsBlurSupported) {
            mNormalColor = useAltColor
                    ? SurfaceEffectColors.surfaceEffect2(getContext())
                    : SurfaceEffectColors.surfaceEffect1(getContext());
        } else {
            mNormalColor = mContext.getColor(
                    com.android.internal.R.color.materialColorSurfaceContainerHigh);
        }

        mFocusOverlayStroke = getResources().getDimension(R.dimen.notification_focus_stroke_width);
    }

    /** Sets whether blur/translucency is supported for notification rows. */
    public void setIsBlurSupported(boolean isBlurSupported) {
        if (mIsBlurSupported != isBlurSupported) {
            mIsBlurSupported = isBlurSupported;
            setStatefulColors();
            invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Dependency.get(TunerService.class).addTunable(mCardAppearanceTunable,
                "system:notification_card_color_mode", "system:notification_card_color_first",
                "system:notification_card_color_second", "system:notification_card_style",
                "system:notification_card_glow_intensity");
        CrossWindowBlurListeners.getInstance().addListener(
                getContext().getMainExecutor(), mCardBlurListener);
        updateCardBlur();
        updateAxBlurOverlayColor();
        ScrimUtils scrimUtils = ScrimUtils.get(getContext());
        scrimUtils.addListener(mScrimListener);
        setMediaArtVisible(scrimUtils.isMediaArtVisible());
        mAxBlurRenderer.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        Dependency.get(TunerService.class).removeTunable(mCardAppearanceTunable);
        CrossWindowBlurListeners.getInstance().removeListener(mCardBlurListener);
        if (mCardBlurDrawable != null) {
            mCardBlurDrawable.setVisible(false, false);
            mCardBlurDrawable.setCallback(null);
            mCardBlurDrawable = null;
        }
        ScrimUtils.get().removeListener(mScrimListener);
        mAxBlurRenderer.onDetachedFromWindow();
        super.onDetachedFromWindow();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        mAxBlurRenderer.onVisibilityAggregated(isVisible);
        if (mCardBlurDrawable != null) {
            mCardBlurDrawable.setVisible(isVisible && customCardBlurAvailable(), false);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateAxBlurOverlayColor();
    }

    @Override
    public void onTargetVisibilityChanged(boolean targetVisible) {
        if (NotificationAddXOnHoverToDismiss.isUnexpectedlyInLegacyMode()) {
            return;
        }

        if (mDrawDismissButtonCutout != targetVisible) {
            mDrawDismissButtonCutout = targetVisible;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float clipTop = Math.max(mClipTopAmount, mTopOverlap);
        int clipBottomAmount = Math.max(mClipBottomAmount, mBottomOverlap);
        if (clipTop + clipBottomAmount < getActualHeight() || mExpandAnimationRunning) {
            canvas.save();
            if (!mExpandAnimationRunning) {
                canvas.clipRect(0, clipTop, getWidth(),
                        getActualHeight() - clipBottomAmount);
            }

            if (!NotificationAddXOnHoverToDismiss.isEnabled()) {
                if (mBackgroundBlurDrawable != null && mCardColorMode == 0) {
                    draw(canvas, mBackgroundBlurDrawable);
                } else {
                    draw(canvas, mBackground);
                }
                if (mBackground != null) {
                    drawCardStyle(canvas, mBackgroundBounds);
                    drawCardGlow(canvas, mBackgroundBounds);
                }
                canvas.restore();
                return;
            }

            Rect backgroundBounds = null;
            if (mBackground != null || mBackgroundBlurDrawable != null
                    || mDrawDismissButtonCutout) {
                backgroundBounds = calculateBackgroundBounds();
            }

            if (mDrawDismissButtonCutout) {
                canvas.clipPath(calculateDismissButtonCutoutPath(backgroundBounds));
            }
            if (mBackgroundBlurDrawable != null && mCardColorMode == 0) {
                mBackgroundBlurDrawable.setBounds(backgroundBounds);
                mBackgroundBlurDrawable.draw(canvas);
            } else if (mBackground != null) {
                drawCustomCardBlur(canvas, backgroundBounds);
                boolean drewAxBlur = drawAxBlurBackground(canvas, backgroundBounds);
                if (mCardColorMode != 0 || shouldDrawFallbackBackground(drewAxBlur)) {
                    mBackground.setBounds(backgroundBounds);
                    mBackground.draw(canvas);
                }
            }

            if (backgroundBounds != null) {
                drawCardStyle(canvas, backgroundBounds);
                drawCardGlow(canvas, backgroundBounds);
            }
            canvas.restore();
        }
    }

    private Path calculateDismissButtonCutoutPath(Rect backgroundBounds) {
        // TODO(b/365585705): Adapt to RTL after the UX design is finalized.

        NotificationAddXOnHoverToDismiss.isUnexpectedlyInLegacyMode();

        Path path = new Path();

        final int left = backgroundBounds.left;
        final int right = backgroundBounds.right;
        final int top = backgroundBounds.top;
        final int bottom = backgroundBounds.bottom;

        // Generate the path clockwise from the left-top corner.
        path.moveTo(left, top);
        path.lineTo(right - 2 * convexR - concaveR, top);
        path.quadTo(right - convexR - concaveR, top, right - convexR - concaveR,
                top + convexR);
        path.quadTo(right - convexR - concaveR, top + convexR + concaveR, right - convexR,
                top + convexR + concaveR);
        path.quadTo(right, top + convexR + concaveR, right, top + 2 * convexR + concaveR);
        path.lineTo(right, bottom);
        path.lineTo(left, bottom);
        path.lineTo(left, top);

        return path;
    }

    private void drawCardGlow(Canvas canvas, Rect bounds) {
        if ((mCardColorMode != 5 && mCardColorMode != 6) || mCardGlowIntensity <= 0
                || bounds == null || bounds.isEmpty()) return;
        RectF rect = new RectF(bounds);
        Path path = new Path();
        path.addRoundRect(rect, mCornerRadii, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(path);
        Paint paint = mCardStylePaint;
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        int second = mCardColorMode == 6 ? mCardColorSecond : mCardColorFirst;
        paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.top,
                mCardColorFirst, second, Shader.TileMode.CLAMP));
        float density = getResources().getDisplayMetrics().density;
        for (int layer = 5; layer >= 1; layer--) {
            paint.setStrokeWidth(layer * 2 * density);
            int alpha = mDrawableAlpha * mCardGlowIntensity / 100;
            paint.setAlpha(layer == 1 ? alpha * 9 / 10 : alpha / 10);
            canvas.drawPath(path, paint);
        }
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();
    }

    private void drawCardStyle(Canvas canvas, Rect bounds) {
        if (mCardStyle < 1 || mCardStyle > 7 || bounds.isEmpty()) return;
        final RectF rect = new RectF(bounds);
        final Path clip = new Path();
        clip.addRoundRect(rect, mCornerRadii, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        final Paint paint = mCardStylePaint;
        paint.reset();
        paint.setAntiAlias(true);
        paint.setAlpha(mDrawableAlpha);
        if (mCardStyle == 2 || mCardStyle == 3 || mCardStyle == 5
                || mCardStyle == 7) {
            int first = mCardStyle == 3 ? 0x40000000 : 0x40ffffff;
            int last = mCardStyle == 2 ? Color.TRANSPARENT : 0x33000000;
            boolean horizontal = mCardStyle == 6 || mCardStyle == 7;
            paint.setShader(new LinearGradient(rect.left, rect.top,
                    horizontal ? rect.right : rect.left,
                    horizontal ? rect.top : rect.bottom,
                    first, last, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, paint);
            paint.setShader(null);
        }
        if (mCardStyle == 6) {
            paint.setColor(0x29ffffff);
            paint.setAlpha(mDrawableAlpha * 41 / 255);
            canvas.drawRect(rect.left, rect.top, rect.left + rect.width() * 0.55f,
                    rect.bottom, paint);
            paint.setColor(0x29000000);
            paint.setAlpha(mDrawableAlpha * 41 / 255);
            canvas.drawRect(rect.left + rect.width() * 0.58f, rect.top, rect.right,
                    rect.bottom, paint);
        }
        if (mCardStyle == 1 || mCardStyle == 2 || mCardStyle == 4
                || mCardStyle == 5 || mCardStyle == 6) {
            float stroke = getResources().getDisplayMetrics().density
                    * ((mCardStyle == 1 || mCardStyle == 6) ? 2f : 1f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(0x80ffffff);
            paint.setAlpha(mDrawableAlpha * 128 / 255);
            canvas.drawPath(clip, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        canvas.restore();
    }

    private Rect calculateBackgroundBounds() {
        NotificationAddXOnHoverToDismiss.isUnexpectedlyInLegacyMode();

        int top = 0;
        int bottom = getActualHeight();
        if (mBottomIsRounded
                && mBottomAmountClips
                && !mExpandAnimationRunning) {
            bottom -= Math.max(mClipBottomAmount, mBottomOverlap);
        }
        final boolean alignedToRight = isAlignedToRight();
        final int width = getWidth();
        final int actualWidth = getActualWidth();

        int left = alignedToRight ? width - actualWidth : 0;
        int right = alignedToRight ? width : actualWidth;

        if (mExpandAnimationRunning) {
            // Horizontally center this background view inside of the container
            left = (int) ((width - actualWidth) / 2.0f);
            right = (int) (left + actualWidth);
        }

        return new Rect(left, top, right, bottom);
    }

    /**
     * @return Whether the background view should be right-aligned. This only matters if the
     * actualWidth is different than the full (measured) width. In other words, this is used to
     * define the short-shelf alignment.
     */
    protected boolean isAlignedToRight() {
        return isLayoutRtl();
    }

    private void draw(Canvas canvas, Drawable drawable) {
        NotificationAddXOnHoverToDismiss.assertInLegacyMode();

        if (drawable != null) {
            int top = 0;
            int bottom = getActualHeight();
            if (mBottomIsRounded
                    && mBottomAmountClips
                    && !mExpandAnimationRunning) {
                bottom -= Math.max(mClipBottomAmount, mBottomOverlap);
            }

            final boolean alignedToRight = isAlignedToRight();
            final int width = getWidth();
            final int actualWidth = getActualWidth();

            int left = alignedToRight ? width - actualWidth : 0;
            int right = alignedToRight ? width : actualWidth;

            if (mExpandAnimationRunning) {
                // Horizontally center this background view inside of the container
                left = (int) ((width - actualWidth) / 2.0f);
                right = (int) (left + actualWidth);
            }
            mBackgroundBounds.set(left, top, right, bottom);
            if (drawable == mBackground) drawCustomCardBlur(canvas, mBackgroundBounds);
            boolean drewAxBlur = drawAxBlurBackground(canvas, mBackgroundBounds);
            if (mCardColorMode != 0 || shouldDrawFallbackBackground(drewAxBlur)) {
                drawable.setBounds(left, top, right, bottom);
                drawable.draw(canvas);
            }
        }
    }

    protected boolean drawAxBlurBackground(Canvas canvas, Rect bounds) {
        if (mCardColorMode != 0) return false;
        return drawAxBlurBackground(canvas, bounds, mAxBlurKey, mCornerRadii);
    }

    /** Draw a separately bounded blur surface, such as an individual compact shelf icon. */
    protected boolean drawAxBlurBackground(Canvas canvas, Rect bounds, Object key,
            float[] cornerRadii) {
        if (!mAxBlurEnabled
                || !(mBackground instanceof LayerDrawable)
                || bounds == null
                || bounds.isEmpty()) {
            return false;
        }

        mBackground.setBounds(bounds);
        if (mMediaArtVisible) {
            // Keep the same translucent tint over media art without sampling the wallpaper.
            mMediaArtBackground.setBounds(bounds);
            mMediaArtBackground.setCornerRadii(cornerRadii);
            mMediaArtBackground.setColor(mAxBlurOverlayColor);
            mMediaArtBackground.setAlpha(mBackground.getAlpha());
            mMediaArtBackground.draw(canvas);
            return true;
        }
        return mAxBlurRenderer.draw(
                canvas,
                key,
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                cornerRadii,
                mAxBlurOverlayColor,
                mBackground.getAlpha());
    }

    private boolean shouldDrawFallbackBackground(boolean drewAxBlur) {
        return !drewAxBlur && (!mAxBlurEnabled || !mAxBlurRenderer.isCrossWindowBlurActive());
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who)
                || who == mBackground
                || who == mCardBlurDrawable
                || mAxBlurRenderer.verifyDrawable(who);
    }

    @Override
    protected void drawableStateChanged() {
        setState(getDrawableState());
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        if (mBackground != null) {
            mBackground.setHotspot(x, y);
        }
    }

    /**
     * Update the view on whether the device is currently on the keyguard.
     */
    public void setOnKeyguard(boolean onKeyguard) {
        if (onKeyguard == mOnKeyguard) {
            return;
        }

        // TODO: (b/445495701) - Determine why this value is opposite of expected, then use in #draw
        // to restrict the blurred background to the keyguard only.
        mOnKeyguard = onKeyguard;
    }

    /**
     * Stateful colors are colors that will overlay on the notification original color when one of
     * hover states, pressed states or other similar states is activated.
     */
    private void setStatefulColors() {
        Drawable statefulLayer = getStatefulBackgroundLayer();
        if (statefulLayer == null) {
            return;
        }
        if (mCardColorMode != 0 || mTintColor != mNormalColor) {
            ColorStateList newColor = ContrastColorUtil.isColorDark(
                    mCardColorMode != 0 ? mCardColorFirst : mTintColor)
                    ? mDarkColoredStatefulColors : mLightColoredStatefulColors;
            ((GradientDrawable) statefulLayer.mutate()).setColor(newColor);
        }
    }

    /**
     * Sets a background drawable. As we need to change our bounds independently of layout, we need
     * the notion of a background independently of the regular View background..
     */
    public void setCustomBackground(Drawable background) {
        if (mBackground != null) {
            mBackground.setCallback(null);
            unscheduleDrawable(mBackground);
        }
        mBackground = background;
        mRippleColor = null;
        mBackground.mutate();
        if (mBackground != null) {
            mBackground.setCallback(this);
            setTint(mTintColor);
        }
        if (mBackground instanceof RippleDrawable) {
            ((RippleDrawable) mBackground).setForceSoftware(true);
        }
        updateBackgroundRadii();
        invalidate();
    }

    public void setCustomBackground(int drawableResId) {
        final Drawable d = mContext.getDrawable(drawableResId);
        setCustomBackground(d);
    }

    /**
     * Update whether this view should allow a blurred background or not.
     *
     * @param enabled - If true, queues creation of a {BackgroundBlurDrawable}. If false, removes
     *     any reference to a blurred drawable.
     */
    @UiThread
    public void setBlurBackgroundEnabled(boolean enabled) {
        if (!lockscreenBlurForNotifications()) {
            return;
        }
        Assert.isMainThread();

        if (enabled && mBackgroundBlurDrawable == null) {
            if (mOnAttachStateChangeListener != null) {
                removeOnAttachStateChangeListener(mOnAttachStateChangeListener);
            }
            mOnAttachStateChangeListener =
                    new OnAttachStateChangeListener() {
                        @Override
                        public void onViewAttachedToWindow(View view) {
                            if (mBackgroundBlurDrawable == null) {
                                mBackgroundBlurDrawable =
                                        view.getViewRootImpl().createBackgroundBlurDrawable();
                                mBackgroundBlurDrawable.setBlurRadius(
                                        getResources().getDimensionPixelSize(
                                                R.dimen.notification_background_blur_radius));
                                mBackgroundBlurDrawable.setXfermode(null);
                                mBackgroundBlurDrawable.setCallback(
                                        NotificationBackgroundView.this);
                                mBackgroundBlurDrawable.setColor(mNormalColor);

                                updateBackgroundRadii();
                                invalidate();
                            }
                            NotificationBackgroundView.this.removeOnAttachStateChangeListener(this);
                            mOnAttachStateChangeListener = null;
                        }

                        @Override
                        public void onViewDetachedFromWindow(View view) {}
                    };
            addOnAttachStateChangeListener(mOnAttachStateChangeListener);

            if (isAttachedToWindow()) {
                mOnAttachStateChangeListener.onViewAttachedToWindow(this);
            }
        } else if (!enabled) {
            if (mOnAttachStateChangeListener != null) {
                removeOnAttachStateChangeListener(mOnAttachStateChangeListener);
                mOnAttachStateChangeListener = null;
            }
            if (mBackgroundBlurDrawable != null) {
                mBackgroundBlurDrawable.setCallback(null);
            }
            mBackgroundBlurDrawable = null;
        }
    }

    public void setAxBlurEnabled(boolean enabled) {
        if (mAxBlurEnabled == enabled) {
            return;
        }
        mAxBlurEnabled = enabled;
        mAxBlurRenderer.setEnabled(enabled && !mMediaArtVisible);
        invalidate();
    }

    private void setMediaArtVisible(boolean visible) {
        if (mMediaArtVisible == visible) {
            return;
        }
        mMediaArtVisible = visible;
        mAxBlurRenderer.setEnabled(mAxBlurEnabled && !visible);
        invalidate();
    }

    private void updateAxBlurOverlayColor() {
        mAxBlurOverlayColor = AxBlurColors.surfaceBrightTint(getContext());
        invalidate();
    }

    public Drawable getBaseBackgroundLayer() {
        return ((LayerDrawable) mBackground).getDrawable(0);
    }

    private Drawable getStatefulBackgroundLayer() {
        return ((LayerDrawable) mBackground).getDrawable(1);
    }

    public void setTint(int tintColor) {
        Drawable baseLayer = getBaseBackgroundLayer();
        if (mCardColorMode != 0 && baseLayer instanceof GradientDrawable) {
            GradientDrawable gradient = (GradientDrawable) baseLayer.mutate();
            gradient.setTintList(null);
            gradient.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
            int first = mCardColorFirst;
            int second = (mCardColorMode == 2 || mCardColorMode == 4 || mCardColorMode == 6)
                    ? mCardColorSecond : mCardColorFirst;
            if (customCardBlurAvailable()) {
                first = (first & 0x00ffffff) | (Math.min(Color.alpha(first), 160) << 24);
                second = (second & 0x00ffffff) | (Math.min(Color.alpha(second), 160) << 24);
            }
            gradient.setColors(new int[] {first, second});
            mHadCustomCardColor = true;
        } else {
            if (mHadCustomCardColor && baseLayer instanceof GradientDrawable) {
                ((GradientDrawable) baseLayer.mutate()).setColors((int[]) null);
                ((GradientDrawable) baseLayer).setColor(tintColor);
                mHadCustomCardColor = false;
            }
            if (mIsBlurSupported) {
                ((GradientDrawable) baseLayer.mutate()).setColor(tintColor);
            } else {
                baseLayer.mutate().setTintMode(PorterDuff.Mode.SRC_ATOP);
                baseLayer.setTint(tintColor);
            }
        }
        if (mBackgroundBlurDrawable != null) {
            mBackgroundBlurDrawable.setColor(tintColor);
        }
        mTintColor = tintColor;
        setStatefulColors();
        invalidate();
    }

    public void setActualHeight(int actualHeight) {
        if (mExpandAnimationRunning) {
            return;
        }
        mActualHeight = actualHeight;
        invalidate();
    }

    private int getActualHeight() {
        if (mExpandAnimationRunning && mExpandAnimationHeight > -1) {
            return mExpandAnimationHeight;
        } else if (mActualHeight > -1) {
            return mActualHeight;
        }
        return getHeight();
    }

    public void setActualWidth(int actualWidth) {
        mActualWidth = actualWidth;
    }

    private int getActualWidth() {
        if (mExpandAnimationRunning && mExpandAnimationWidth > -1) {
            return mExpandAnimationWidth;
        } else if (mActualWidth > -1) {
            return mActualWidth;
        }
        return getWidth();
    }

    public void setClipTopAmount(int clipTopAmount) {
        mClipTopAmount = clipTopAmount;
        invalidate();
    }

    /**
     * Sets the overlap on the top of the view with other views. As a result we should clip the
     * background and content such that no overlap is visible anymore.
     * This is related to setClipTopAmount, however it is a separate way to clip which is usually
     * then combined with the clipTopAmount to take the maximum.
     */
    public void setTopOverlap(int topOverlap) {
        mTopOverlap = topOverlap;
        invalidate();
    }

    /**
     * Sets the overlap on the bottom of the view with other views. As a result we should clip the
     * background and content such that no overlap is visible anymore.
     * This is related to setClipBottomAmount, however it is a separate way to clip which is usually
     * then combined with the clipBottomAmount to take the maximum.
     */
    public void setBottomOverlap(int bottomOverlap) {
        mBottomOverlap = bottomOverlap;
        invalidate();
    }

    public void setClipBottomAmount(int clipBottomAmount) {
        mClipBottomAmount = clipBottomAmount;
        invalidate();
    }

    @Override
    public boolean hasOverlappingRendering() {

        // Prevents this view from creating a layer when alpha is animating.
        return false;
    }

    public void setState(int[] drawableState) {
        if (mBackground != null && mBackground.isStateful()) {
            mBackground.setState(drawableState);
        }
    }

    public void setRippleColor(int color) {
        if (mBackground instanceof RippleDrawable) {
            RippleDrawable ripple = (RippleDrawable) mBackground;
            ripple.setColor(ColorStateList.valueOf(color));
            mRippleColor = color;
        } else {
            mRippleColor = null;
        }
    }

    public void setDrawableAlpha(int drawableAlpha) {
        mDrawableAlpha = drawableAlpha;
        if (mExpandAnimationRunning) {
            return;
        }
        mBackground.setAlpha(drawableAlpha);
        if (mCardBlurDrawable != null) mCardBlurDrawable.setAlpha(drawableAlpha);
        if (mBackgroundBlurDrawable != null) {
            mBackgroundBlurDrawable.setAlpha(drawableAlpha);
        }
    }

    /**
     * Sets the current top and bottom radius for this background.
     */
    public void setRadius(float topRoundness, float bottomRoundness) {
        if (topRoundness == mCornerRadii[0] && bottomRoundness == mCornerRadii[4]) {
            return;
        }
        mBottomIsRounded = bottomRoundness != 0.0f;
        mCornerRadii[0] = topRoundness;
        mCornerRadii[1] = topRoundness;
        mCornerRadii[2] = topRoundness;
        mCornerRadii[3] = topRoundness;
        mCornerRadii[4] = bottomRoundness;
        mCornerRadii[5] = bottomRoundness;
        mCornerRadii[6] = bottomRoundness;
        mCornerRadii[7] = bottomRoundness;
        updateBackgroundRadii();
    }

    public void setBottomAmountClips(boolean clips) {
        if (clips != mBottomAmountClips) {
            mBottomAmountClips = clips;
            invalidate();
        }
    }

    @VisibleForTesting
    protected boolean isBlurEnabled() {
        return mBackgroundBlurDrawable != null;
    }

    private void updateBackgroundRadii() {
        if (mDontModifyCorners) {
            return;
        }
        if (mBackground instanceof LayerDrawable layerDrawable) {
            int numberOfLayers = layerDrawable.getNumberOfLayers();
            for (int i = 0; i < numberOfLayers; i++) {
                GradientDrawable gradientDrawable = (GradientDrawable) layerDrawable.getDrawable(i);
                gradientDrawable.setCornerRadii(mCornerRadii);
            }
            updateFocusOverlayRadii(layerDrawable);
        }
        if (mCardBlurDrawable != null) {
            mCardBlurDrawable.setCornerRadius(mCornerRadii[0], mCornerRadii[1],
                    mCornerRadii[2], mCornerRadii[3], mCornerRadii[4], mCornerRadii[5],
                    mCornerRadii[6], mCornerRadii[7]);
        }
        if (mBackgroundBlurDrawable != null) {
            mBackgroundBlurDrawable.setCornerRadius(mCornerRadii[0], mCornerRadii[1],
                    mCornerRadii[2], mCornerRadii[3], mCornerRadii[4], mCornerRadii[5],
                    mCornerRadii[6], mCornerRadii[7]);
        }
    }

    private void updateFocusOverlayRadii(LayerDrawable background) {
        GradientDrawable overlay =
                (GradientDrawable) background.findDrawableByLayerId(
                        R.id.notification_focus_overlay);
        if (overlay == null) {
            return;
        }
        for (int i = 0; i < mCornerRadii.length; i++) {
            // in theory subtracting mFocusOverlayStroke/2 should be enough but notification
            // background is still peeking a bit from below - probably due to antialiasing or
            // overlay uneven scaling. So let's subtract full mFocusOverlayStroke to make sure the
            // radius is a bit smaller and covers background corners fully
            mFocusOverlayCornerRadii[i] = Math.max(0, mCornerRadii[i] - mFocusOverlayStroke);
        }
        overlay.setCornerRadii(mFocusOverlayCornerRadii);
    }

    /** Set the current expand animation size. */
    public void setExpandAnimationSize(int width, int height) {
        mExpandAnimationHeight = height;
        mExpandAnimationWidth = width;
        invalidate();
    }

    public void setExpandAnimationRunning(boolean running) {
        mExpandAnimationRunning = running;
        if (mBackground instanceof LayerDrawable) {
            GradientDrawable gradientDrawable =
                    (GradientDrawable) ((LayerDrawable) mBackground).getDrawable(0);
            // Speed optimization: disable AA if transfer mode is not SRC_OVER. AA is not easy to
            // spot during animation anyways.
            gradientDrawable.setAntiAlias(!running);
        }
        if (!mExpandAnimationRunning) {
            setDrawableAlpha(mDrawableAlpha);
        }
        invalidate();
    }

    @Override
    public void dump(PrintWriter pw, @NonNull String[] args) {
        pw.println("mDontModifyCorners: " + mDontModifyCorners);
        pw.println("mClipTopAmount: " + mClipTopAmount);
        pw.println("mClipBottomAmount: " + mClipBottomAmount);
        pw.println("mTopOverlap: " + mTopOverlap);
        pw.println("mBottomOverlap: " + mBottomOverlap);
        pw.println("mCornerRadii: " + Arrays.toString(mCornerRadii));
        pw.println("mBottomIsRounded: " + mBottomIsRounded);
        pw.println("mBottomAmountClips: " + mBottomAmountClips);
        pw.println("mActualWidth: " + mActualWidth);
        pw.println("mActualHeight: " + mActualHeight);
        pw.println("mTintColor: " + hexColorString(mTintColor));
        pw.println("mRippleColor: " + hexColorString(mRippleColor));
        pw.println("mBackground: " + DrawableDumpKt.dumpToString(mBackground));
        pw.println("mBackgroundBlurDrawable: "
                + DrawableDumpKt.dumpToString(mBackgroundBlurDrawable));
    }

    /** create a concise dump of this view's colors */
    public String toDumpString() {
        return "<NotificationBackgroundView"
                + " tintColor=" + hexColorString(mTintColor)
                + " rippleColor=" + hexColorString(mRippleColor)
                + " bgColor=" + DrawableDumpKt.getSolidColor(mBackground)
                + " blurEnabled=" + (mBackgroundBlurDrawable != null)
                + ">";

    }
}
