/*
 * Copyright (C) 2026 The RisingOS Android Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.statusbar.notification.stack.rising

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.res.XmlResourceParser
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.util.Xml
import java.util.Locale
import org.xmlpull.v1.XmlPullParser

/** Resolves common launcher icon-pack appfilter entries; missing icons use the app icon. */
class RisingStacksIconPack(private val context: Context) {
    private var loadedPackage: String? = null
    private var loadedUserId: Int = UserHandle.USER_NULL
    private var packContext: Context? = null
    private val components = HashMap<String, String>()
    private val packages = HashMap<String, String>()

    fun icon(pack: String?, appContext: Context, appPackage: String): Drawable? {
        if (pack != loadedPackage || loadedUserId != ActivityManager.getCurrentUser()) load(pack)
        val owner = packContext ?: return null
        return runCatching {
            val component = appContext.packageManager.getLaunchIntentForPackage(appPackage)?.component
            val key = component?.flattenToString()?.lowercase(Locale.ROOT)
            val drawable = key?.let { components[it] } ?: packages[appPackage.lowercase(Locale.ROOT)]
                ?: return@runCatching null
            var id = owner.resources.getIdentifier(drawable, "drawable", owner.packageName)
            if (id == 0) id = owner.resources.getIdentifier(drawable, "mipmap", owner.packageName)
            if (id == 0) null else owner.getDrawable(id)
        }.getOrNull()
    }

    private fun load(pack: String?) {
        loadedPackage = pack
        loadedUserId = ActivityManager.getCurrentUser()
        packContext = null
        components.clear()
        packages.clear()
        if (pack.isNullOrBlank()) return
        runCatching {
            val owner = context.createPackageContextAsUser(
                pack, 0, UserHandle.of(loadedUserId)
            )
            val id = owner.resources.getIdentifier("appfilter", "xml", pack)
            if (id != 0) {
                val parser: XmlResourceParser = owner.resources.getXml(id)
                try { parse(parser) } finally { parser.close() }
            } else {
                owner.assets.open("appfilter.xml").use { stream ->
                    val parser = Xml.newPullParser()
                    parser.setInput(stream, "utf-8")
                    parse(parser)
                }
            }
            packContext = owner
        }.onFailure {
            components.clear()
            packages.clear()
        }
    }

    private fun parse(parser: XmlPullParser) {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("item", true)) {
                val raw = parser.getAttributeValue(null, "component")
                    ?.removePrefix("ComponentInfo{")?.removeSuffix("}")
                val drawable = parser.getAttributeValue(null, "drawable")
                val component = raw?.let(ComponentName::unflattenFromString)
                if (component != null && !drawable.isNullOrBlank()) {
                    components.putIfAbsent(component.flattenToString().lowercase(Locale.ROOT), drawable)
                    packages.putIfAbsent(component.packageName.lowercase(Locale.ROOT), drawable)
                }
            }
            event = parser.next()
        }
    }
}
