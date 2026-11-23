/*
 * Copyright (C) 2026 The RisingOS Android Project
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.doze.ambientpeek

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.os.UserHandle
import android.text.TextUtils
import android.view.View
import com.android.systemui.res.R
import kotlin.math.max
import kotlin.math.min

/** Draws app icons and an outlined AOD preview; input is routed by the doze sensor host. */
class AmbientPeekTapView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var groups: List<AmbientPeekController.Group> = emptyList()
    private var selected: AmbientPeekController.AppKey? = null
    private var fingerprint: Point? = null
    private var fingerprintRadius = 0f
    private var clockAnchor: View? = null
    private var page = 0
    private val hits = ArrayList<Pair<RectF, AmbientPeekController.AppKey>>()
    private var nextPage: RectF? = null
    private var ready = false
    private var readyListener: ((Boolean) -> Unit)? = null
    private val screenLocation = IntArray(2)
    private fun dp(v: Float) = v * resources.displayMetrics.density

    fun setClockAnchor(anchor: View) { clockAnchor = anchor }

    fun setReadyListener(listener: ((Boolean) -> Unit)?) {
        readyListener = listener
    }

    private fun setReady(value: Boolean) {
        if (ready == value) return
        ready = value
        post { if (ready == value) readyListener?.invoke(value) }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) setReady(false)
    }

    fun setItems(newGroups: List<AmbientPeekController.Group>, selection: AmbientPeekController.AppKey?,
        fingerprintLocation: Point?, radius: Float) {
        if (newGroups.map { it.app } != groups.map { it.app }) page = 0
        groups = newGroups
        selected = selection
        fingerprint = fingerprintLocation
        fingerprintRadius = radius
        invalidate()
    }

    private fun localCoordinates(screenX: Float, screenY: Float): Pair<Float, Float> {
        getLocationOnScreen(screenLocation)
        return (screenX - screenLocation[0]) to (screenY - screenLocation[1])
    }

    fun handlePageTap(screenX: Float, screenY: Float): Boolean {
        val (x, y) = localCoordinates(screenX, screenY)
        val hit = nextPage ?: return false
        if (!hit.contains(x, y) || isFingerprintArea(screenX, screenY)) return false
        page++
        invalidate()
        return true
    }

    fun appAt(screenX: Float, screenY: Float): AmbientPeekController.AppKey? {
        if (isFingerprintArea(screenX, screenY)) return null
        val (x, y) = localCoordinates(screenX, screenY)
        return hits.firstOrNull { it.first.contains(x, y) }?.second
    }

    private fun isFingerprintArea(x: Float, y: Float): Boolean {
        val p = fingerprint ?: return false
        if (fingerprintRadius <= 0f) return false
        val r = fingerprintRadius + dp(28f)
        return (x - p.x) * (x - p.x) + (y - p.y) * (y - p.y) < r * r
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hits.clear()
        nextPage = null
        if (groups.isEmpty() || width <= 0 || height <= 0) {
            setReady(false)
            return
        }
        getLocationOnScreen(screenLocation)
        val safeBottom = fingerprint?.takeIf { fingerprintRadius > 0f }?.let {
            it.y - screenLocation[1] - fingerprintRadius - dp(32f)
        } ?: height * 0.68f
        val anchorBottom = clockAnchor?.takeIf { it.isLaidOut }?.let {
            val loc = IntArray(2)
            it.getLocationOnScreen(loc)
            (loc[1] - screenLocation[1] + it.height).toFloat()
        } ?: 0f
        val minute = android.os.SystemClock.elapsedRealtime() / 60000L
        val jitter = ((minute % 3).toInt() - 1) * dp(2f)
        val wanted = max(height * 0.38f, anchorBottom + dp(12f)) + jitter
        val rowY = min(wanted, safeBottom - dp(152f))
        // If the sensor or available area leaves no safe space, hide rather than overlap.
        if (rowY < dp(96f) || rowY < anchorBottom || safeBottom > height
            || width < dp(100f)) {
            setReady(false)
            return
        }
        setReady(true)
        postInvalidateDelayed(60000L)
        val slots = max(2, (width / dp(48f)).toInt().coerceAtMost(8))
        val perPage = if (groups.size > slots) slots - 1 else slots
        val pages = (groups.size + perPage - 1) / perPage
        page %= pages
        val current = groups.drop(page * perPage).take(perPage)
        val needsPageButton = pages > 1
        val visibleCount = current.size + if (needsPageButton) 1 else 0
        val firstX = (width - visibleCount * dp(48f)) / 2f
        paint.color = Color.WHITE
        paint.alpha = 190
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = dp(10f)
        current.forEachIndexed { i, group ->
            val x = firstX + i * dp(48f)
            hits.add(RectF(x, rowY, x + dp(48f), rowY + dp(48f)) to group.app)
            try {
                val user = if (group.app.userId == UserHandle.USER_ALL) UserHandle.SYSTEM
                    else UserHandle.of(group.app.userId)
                val app = context.createPackageContextAsUser(group.app.packageName, 0, user)
                val original = app.applicationInfo.loadIcon(app.packageManager)
                val badged = app.packageManager.getUserBadgedIcon(original, user)
                val icon = badged.constantState?.newDrawable()?.mutate() ?: badged.mutate()
                icon.setBounds((x + dp(12f)).toInt(), (rowY + dp(3f)).toInt(),
                    (x + dp(36f)).toInt(), (rowY + dp(27f)).toInt())
                icon.draw(canvas)
            } catch (_: Exception) {
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(x + dp(24f), rowY + dp(15f), dp(9f), paint)
                paint.style = Paint.Style.FILL
            }
            canvas.drawText(if (group.count > 99) "99+" else group.count.toString(),
                x + dp(24f), rowY + dp(42f), paint)
        }
        if (needsPageButton) {
            val x = firstX + current.size * dp(48f)
            nextPage = RectF(x, rowY, x + dp(48f), rowY + dp(48f))
            canvas.drawText("•••", x + dp(24f), rowY + dp(24f), paint)
        }
        current.firstOrNull { it.app == selected }?.let {
            drawPreview(canvas, it, rowY + dp(56f), safeBottom)
        }
    }

    private fun drawPreview(canvas: Canvas, group: AmbientPeekController.Group, top: Float,
        safeBottom: Float) {
        if (top + dp(55f) > safeBottom) return
        val left = dp(24f)
        val right = width - dp(24f)
        val bottom = min(safeBottom, top + dp(130f))
        paint.color = Color.WHITE
        paint.alpha = 150
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        canvas.drawRoundRect(left, top, right, bottom, dp(12f), dp(12f), paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = dp(13f)
        var y = top + dp(22f)
        val availableWidth = right - left - dp(24f)
        for (preview in group.previews) {
            if (y > bottom - dp(8f)) break
            val title = if (preview.redacted) context.getString(R.string.ambient_peek_hidden)
                else preview.title
            if (title.isNotEmpty()) {
                canvas.drawText(TextUtils.ellipsize(title, android.text.TextPaint(paint),
                    availableWidth, TextUtils.TruncateAt.END).toString(), left + dp(12f), y, paint)
                y += dp(20f)
            }
            if (!preview.redacted && preview.body.isNotEmpty() && y <= bottom - dp(8f)) {
                canvas.drawText(TextUtils.ellipsize(preview.body, android.text.TextPaint(paint),
                    availableWidth, TextUtils.TruncateAt.END).toString(), left + dp(12f), y, paint)
                y += dp(20f)
            }
        }
    }
}
