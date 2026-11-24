/*
 * Copyright (C) 2026 The RisingOS Android Project
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.doze.ambientpeek

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.os.UserHandle
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.stack.rising.RisingStacksIconPack
import kotlin.math.max
import kotlin.math.min

/** Draws app icons and an outlined AOD preview; input is routed by the doze sensor host. */
class AmbientPeekTapView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPack = RisingStacksIconPack(context)
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
        val maxApps = Settings.System.getIntForUser(
            context.contentResolver, "ambient_peek_max_apps", 6, ActivityManager.getCurrentUser()
        ).coerceIn(2, 6)
        val iconScale = Settings.System.getIntForUser(
            context.contentResolver, "ambient_peek_icon_size", 100, ActivityManager.getCurrentUser()
        ).coerceIn(100, 200) / 100f
        val availableWidth = (width - dp(32f)).coerceAtLeast(0f)
        // Size against the configured maximum, so incoming apps do not resize existing icons.
        val maxIcon = (availableWidth / maxApps - dp(12f)).coerceAtLeast(dp(24f))
        val iconSize = min(dp(24f) * iconScale, min(dp(64f), maxIcon))
        val cellWidth = max(dp(48f), iconSize + dp(12f))
        val rowHeight = max(dp(48f), iconSize + dp(24f))
        val wanted = max(height * 0.38f, anchorBottom + dp(12f)) + jitter
        val rowY = min(wanted, safeBottom - rowHeight - dp(104f))
        // If the sensor or available area leaves no safe space, hide rather than overlap.
        if (rowY < dp(96f) || rowY < anchorBottom || safeBottom > height
            || width < dp(100f)) {
            setReady(false)
            return
        }
        setReady(true)
        postInvalidateDelayed(60000L)
        val slots = max(2, (availableWidth / cellWidth).toInt().coerceAtMost(8))
        val perPage = if (groups.size > slots) slots - 1 else slots
        val pages = (groups.size + perPage - 1) / perPage
        page %= pages
        val current = groups.drop(page * perPage).take(perPage)
        val needsPageButton = pages > 1
        val visibleCount = current.size + if (needsPageButton) 1 else 0
        val firstX = (width - visibleCount * cellWidth) / 2f
        paint.color = Color.WHITE
        paint.alpha = 190
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = dp(10f)
        val selectedPack = Settings.System.getStringForUser(
            context.contentResolver, "ambient_peek_icon_pack", ActivityManager.getCurrentUser()
        )
        current.forEachIndexed { i, group ->
            val x = firstX + i * cellWidth
            hits.add(RectF(x, rowY, x + cellWidth, rowY + rowHeight) to group.app)
            try {
                val user = if (group.app.userId == UserHandle.USER_ALL) UserHandle.SYSTEM
                    else UserHandle.of(group.app.userId)
                val app = context.createPackageContextAsUser(group.app.packageName, 0, user)
                val original = iconPack.icon(selectedPack, app, group.app.packageName)
                    ?: app.applicationInfo.loadIcon(app.packageManager)
                val badged = app.packageManager.getUserBadgedIcon(original, user)
                val icon = badged.constantState?.newDrawable()?.mutate() ?: badged.mutate()
                val iconLeft = x + (cellWidth - iconSize) / 2f
                icon.setBounds(iconLeft.toInt(), (rowY + dp(3f)).toInt(),
                    (iconLeft + iconSize).toInt(), (rowY + dp(3f) + iconSize).toInt())
                icon.draw(canvas)
            } catch (_: Exception) {
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(x + cellWidth / 2f, rowY + dp(3f) + iconSize / 2f,
                    iconSize * 0.375f, paint)
                paint.style = Paint.Style.FILL
            }
            canvas.drawText(if (group.count > 99) "99+" else group.count.toString(),
                x + cellWidth / 2f, rowY + dp(18f) + iconSize, paint)
        }
        if (needsPageButton) {
            val x = firstX + current.size * cellWidth
            nextPage = RectF(x, rowY, x + cellWidth, rowY + rowHeight)
            canvas.drawText("•••", x + cellWidth / 2f, rowY + rowHeight / 2f, paint)
        }
        current.firstOrNull { it.app == selected }?.let {
            drawPreview(canvas, it, rowY + rowHeight + dp(8f), safeBottom)
        }
    }

    private fun drawPreview(canvas: Canvas, group: AmbientPeekController.Group, top: Float,
        safeBottom: Float) {
        val left = dp(24f)
        val right = width - dp(24f)
        val padding = dp(12f)
        val textWidth = (right - left - 2 * padding).toInt()
        val availableHeight = safeBottom - top - 2 * padding
        if (textWidth <= 0 || availableHeight <= 0f) return

        val content = group.previews.mapNotNull { preview ->
            val title = if (preview.redacted) context.getString(R.string.ambient_peek_hidden)
                else preview.title
            val body = if (preview.redacted) "" else preview.body
            listOf(title, body).filter { it.isNotBlank() }.joinToString("\n")
                .takeIf { it.isNotEmpty() }
        }.joinToString("\n\n").let {
            if (group.count > group.previews.size) "$it\n…" else it
        }
        if (content.isBlank()) return

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 190
            textSize = dp(13f)
        }
        val spacing = dp(2f)
        val metrics = textPaint.fontMetrics
        val lineHeight = kotlin.math.ceil(metrics.descent - metrics.ascent + spacing).toInt()
        val maxLines = (availableHeight / lineHeight).toInt()
        if (maxLines < 1) return
        val layout = StaticLayout.Builder.obtain(content, 0, content.length, textPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(spacing, 1f)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setEllipsizedWidth(textWidth)
            .build()
        val bottom = top + 2 * padding + layout.height
        if (bottom > safeBottom) return

        paint.color = Color.WHITE
        paint.alpha = 150
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        canvas.drawRoundRect(left, top, right, bottom, dp(12f), dp(12f), paint)
        paint.style = Paint.Style.FILL
        canvas.save()
        canvas.translate(left + padding, top + padding)
        layout.draw(canvas)
        canvas.restore()
    }
}
