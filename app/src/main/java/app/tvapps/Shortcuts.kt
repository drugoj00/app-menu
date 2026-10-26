package app.tvapps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import org.xmlpull.v1.XmlPullParser

/** Быстрая ссылка приложения (то, что у Samsung показывается над кнопками при долгом нажатии на значок). */
data class Shortcut(val id: String, val label: String, val icon: Drawable?, val intent: Intent)

/**
 * Читает «статические» быстрые ссылки из самого приложения (файл shortcuts.xml, указанный в манифесте).
 * Динамические ссылки, вроде недавних чатов, система отдаёт только основному лаунчеру, поэтому их здесь нет.
 */
object Shortcuts {
    private const val NS = "http://schemas.android.com/apk/res/android"

    fun load(ctx: Context, e: Entry): List<Shortcut> = runCatching {
        val pm = ctx.packageManager
        val comp = e.launch.component ?: return emptyList()
        val ai = pm.getActivityInfo(comp, PackageManager.GET_META_DATA)
        val resId = ai.metaData?.getInt("android.app.shortcuts") ?: 0
        if (resId == 0) return emptyList()
        val res = pm.getResourcesForApplication(e.pkg)
        val p = res.getXml(resId)

        fun text(attr: String): String {
            val r = p.getAttributeResourceValue(NS, attr, 0)
            return if (r != 0) runCatching { res.getString(r) }.getOrDefault("") else p.getAttributeValue(NS, attr).orEmpty()
        }

        val out = ArrayList<Shortcut>()
        var id = ""; var label = ""; var iconRes = 0; var enabled = true
        var intent: Intent? = null
        var inShortcut = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            val t = p.eventType
            if (t == XmlPullParser.START_TAG) {
                when (p.name) {
                    "shortcut" -> {
                        inShortcut = true
                        id = p.getAttributeValue(NS, "shortcutId").orEmpty()
                        enabled = p.getAttributeBooleanValue(NS, "enabled", true)
                        label = text("shortcutShortLabel").ifBlank { text("shortcutLongLabel") }
                        iconRes = p.getAttributeResourceValue(NS, "icon", 0)
                        intent = null
                    }
                    "intent" -> if (inShortcut && intent == null) {
                        val action = p.getAttributeValue(NS, "action")
                        val tPkg = p.getAttributeValue(NS, "targetPackage") ?: e.pkg
                        val tCls = p.getAttributeValue(NS, "targetClass")
                        val data = p.getAttributeValue(NS, "data")
                        val i = Intent(action ?: Intent.ACTION_MAIN)
                        if (tCls != null) i.setClassName(tPkg, tCls) else i.setPackage(tPkg)
                        if (data != null) i.data = Uri.parse(data)
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        intent = i
                    }
                    "extra" -> intent?.let { i ->
                        val name = p.getAttributeValue(NS, "name")
                        val value = p.getAttributeValue(NS, "value")
                        if (name != null && value != null) i.putExtra(name, value)
                    }
                }
            } else if (t == XmlPullParser.END_TAG && p.name == "shortcut") {
                val i = intent
                if (inShortcut && enabled && i != null && label.isNotBlank()) {
                    val icon = if (iconRes != 0) runCatching { res.getDrawable(iconRes, null) }.getOrNull() else null
                    out.add(Shortcut(id, label, icon, i))
                }
                inShortcut = false
            }
        }
        p.close()
        out.take(5)
    }.getOrDefault(emptyList())
}
