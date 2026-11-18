package com.hashira.launcher.icons

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream

data class IconPackInfo(val packageName: String, val label: String)

/** A parsed icon pack. Only supports the static mapping of appfilter.xml. */
class IconPack(
    val packageName: String,
    private val res: Resources,
    private val mapping: Map<String, String>, // "pkg/cls" -> drawable
    val pickerNames: List<String>             // drawables offered in the manual picker
) {
    fun drawableFor(component: ComponentName): Drawable? =
        mapping[component.flattenToString()]?.let { load(it) }

    fun load(name: String): Drawable? {
        var id = res.getIdentifier(name, "drawable", packageName)
        if (id == 0) id = res.getIdentifier(name, "mipmap", packageName)
        if (id == 0) return null
        return try {
            res.getDrawable(id, null)
        } catch (e: Exception) {
            null
        }
    }
}

class IconPackManager(private val context: Context) {
    private val pm = context.packageManager
    private val cache = HashMap<String, IconPack>()
    private val lock = Any()

    private val actions = listOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.anddoes.launcher.THEME"
    )

    fun listInstalled(): List<IconPackInfo> = actions
        .flatMap { pm.queryIntentActivities(Intent(it), PackageManager.ResolveInfoFlags.of(0)) }
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .map { IconPackInfo(it.packageName, it.loadLabel(pm).toString()) }
        .sortedBy { it.label.lowercase() }

    fun invalidate() = synchronized(lock) { cache.clear() }

    fun get(pkg: String): IconPack? = getResult(pkg).getOrNull()

    /** One read of appfilter.xml at a time: avoids parsing the same file N times in parallel. */
    fun getResult(pkg: String): Result<IconPack> = synchronized(lock) {
        val hit = cache[pkg]
        if (hit != null) Result.success(hit) else loadLocked(pkg)
    }

    /** Parses appfilter.xml (and drawable.xml if present). Fails with a clear message. */
    fun load(pkg: String): Result<IconPack> = synchronized(lock) { loadLocked(pkg) }

    private fun loadLocked(pkg: String): Result<IconPack> = runCatching {
        val res = try {
            pm.getResourcesForApplication(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            error("Package $pkg is no longer installed")
        }
        val mapping = LinkedHashMap<String, String>()
        val filter = openXml(res, pkg, "appfilter") ?: error("The pack has no appfilter.xml")
        try {
            var ev = filter.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && filter.name == "item") {
                    val comp = filter.getAttributeValue(null, "component")
                    val drawable = filter.getAttributeValue(null, "drawable")
                    if (comp != null && drawable != null &&
                        comp.startsWith("ComponentInfo{") && comp.endsWith("}")
                    ) {
                        ComponentName.unflattenFromString(comp.removePrefix("ComponentInfo{").removeSuffix("}"))
                            ?.let { mapping.putIfAbsent(it.flattenToString(), drawable.removePrefix("@drawable/")) }
                    }
                }
                ev = filter.next()
            }
        } finally {
            (filter as? XmlResourceParser)?.close()
        }
        if (mapping.isEmpty()) error("appfilter.xml has no icons")

        val names = LinkedHashSet<String>()
        runCatching {
            openXml(res, pkg, "drawable")?.let { p ->
                try {
                    var ev = p.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG && p.name == "item") {
                            p.getAttributeValue(null, "drawable")?.let { names.add(it) }
                        }
                        ev = p.next()
                    }
                } finally {
                    (p as? XmlResourceParser)?.close()
                }
            }
        }
        if (names.isEmpty()) names.addAll(mapping.values.toSortedSet())

        IconPack(pkg, res, mapping, names.toList()).also { cache[pkg] = it }
    }

    private fun openXml(res: Resources, pkg: String, name: String): XmlPullParser? {
        val xmlId = res.getIdentifier(name, "xml", pkg)
        if (xmlId != 0) return res.getXml(xmlId)
        val rawId = res.getIdentifier(name, "raw", pkg)
        if (rawId != 0) return newParser(res.openRawResource(rawId))
        return try {
            newParser(res.assets.open("$name.xml"))
        } catch (e: IOException) {
            null
        }
    }

    private fun newParser(stream: InputStream): XmlPullParser =
        XmlPullParserFactory.newInstance().newPullParser().apply { setInput(stream, null) }
}
