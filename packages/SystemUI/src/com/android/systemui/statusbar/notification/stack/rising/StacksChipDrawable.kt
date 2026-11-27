/* Copyright (C) 2026 The RisingOS Android Project
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.statusbar.notification.stack.rising

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.ceil
import kotlin.math.min

/** Selected Stacks item appearance. Neon halos may extend beyond the item's horizontal bounds. */
internal class StacksChipDrawable(
    private val density: Float,
    private val effect: Int,
    private val first: Int,
    private val second: Int,
    private val outline: Boolean,
    private val intensity: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var halo: Bitmap? = null
    private var cachedWidth = 0
    private var cachedHeight = 0
    private var alphaValue = 255
    private val pad get() = if (effect == 3 || effect == 4) 18f * density else 0f

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val b = Rect(bounds)
        // Keep the glow outside the hit target instead of widening every icon slot.
        if (effect == 3 || effect == 4) {
            val horizontalGlow = (18f * density + 0.5f).toInt()
            b.inset(-horizontalGlow, 0)
        }
        val rect = RectF(b.left + pad, b.top + pad, b.right - pad, b.bottom - pad)
        if (rect.isEmpty) return
        val radius = min(16f * density, min(rect.width(), rect.height()) / 2f)
        val path = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        val end = if (effect == 2 || effect == 4) second else first
        val neon = effect == 3 || effect == 4
        if (neon && intensity > 0) {
            if (halo == null || cachedWidth != b.width() || cachedHeight != b.height()) {
                cachedWidth = b.width(); cachedHeight = b.height()
                val scale = .5f
                halo = Bitmap.createBitmap(ceil(b.width() * scale).toInt().coerceAtLeast(1),
                    ceil(b.height() * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val soft = Canvas(halo!!)
                soft.scale(scale, scale)
                soft.translate(-b.left.toFloat(), -b.top.toFloat())
                val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    maskFilter = BlurMaskFilter((2f + 4f * intensity / 100f) * density,
                        BlurMaskFilter.Blur.NORMAL)
                    shader = LinearGradient(rect.left, 0f, rect.right, 0f, first, end,
                        Shader.TileMode.CLAMP)
                    alpha = intensity * 65 * 255 / 10000
                }
                soft.drawPath(path, glow)
            }
            canvas.save(); canvas.clipOutPath(path)
            paint.reset(); paint.isFilterBitmap = true; paint.alpha = alphaValue
            canvas.drawBitmap(halo!!, null, b, paint); canvas.restore()
        }
        paint.reset(); paint.isAntiAlias = true
        if (effect == 0) {
            paint.color = 0x665f6368
        } else {
            paint.shader = LinearGradient(rect.left, 0f, rect.right, 0f, first, end,
                Shader.TileMode.CLAMP)
            paint.alpha = if (neon) 56 else 255
        }
        val fillAlpha = paint.alpha
        paint.alpha = fillAlpha * alphaValue / 255
        canvas.drawPath(path, paint)
        paint.shader = null
        if (outline || neon && intensity > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            if (effect == 0) paint.color = 0xaaffffff.toInt()
            else paint.shader = LinearGradient(rect.left, 0f, rect.right, 0f, first, end,
                Shader.TileMode.CLAMP)
            paint.alpha = if (effect == 0) alphaValue * 170 / 255
                else if (neon && !outline) alphaValue * intensity / 100 else alphaValue
            canvas.drawPath(path, paint)
            paint.shader = null
        }
    }

    override fun setAlpha(alpha: Int) { alphaValue = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { }
    @Deprecated("Deprecated by Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
