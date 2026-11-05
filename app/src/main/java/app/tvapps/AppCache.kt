package app.tvapps

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Кэш списка приложений и их значков на диске: при запуске список показывается сразу из кэша,
 * а настоящая загрузка идёт в фоне и потом обновляет его. Кэш готовится и при включении устройства.
 * Файлы значков называются по правилам IconCacheRules (пакет, время обновления, версия отрисовки, версия программы), поэтому устаревший
 * или чёрный значок не живёт в кэше вечно.
 */
object AppCache {
    private fun listFile(ctx: Context) = File(ctx.filesDir, "apps_cache.json")
    private fun iconDir(ctx: Context) = File(ctx.cacheDir, "icons_cache")

    /** Один раз при первом запуске версии с новым кэшем значков: прежний кэш (имена <пакет>.png, в том числе чёрные значки) удаляется целиком. */
    private fun migrate(ctx: Context) {
        val sp = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE)
        if (sp.getBoolean("icons_migrated_v3", false)) return
        iconDir(ctx).deleteRecursively()
        listFile(ctx).delete()
        sp.edit().putBoolean("icons_migrated_v3", true).apply()
    }

    private fun fileName(e: Entry) = IconCacheRules.name(e.pkg, e.updated, IconCacheRules.ICON_PIPELINE_VERSION, BuildConfig.VERSION_CODE)

    fun save(ctx: Context, list: List<Entry>, force: Boolean = false) {
        runCatching {
            migrate(ctx)
            val arr = JSONArray()
            val dir = iconDir(ctx).apply { mkdirs() }
            val keep = HashMap<String, String>()
            for (e in list) {
                val comp = e.launch.component ?: continue
                val name = fileName(e)
                val f = File(dir, name)
                if (force || !f.exists()) {
                    // в кэш идёт готовая картинка плитки (128 px); пустой или чёрный значок в кэш не записывается (перерисуется при следующей загрузке)
                    val b: Bitmap = e.bmp?.asAndroidBitmap() ?: runCatching { e.icon.toBitmap(128, 128) }.getOrNull() ?: continue
                    if (IconQuality.isBadBitmap(b)) continue
                    f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                keep[e.pkg] = name
                arr.put(
                    JSONObject().put("p", e.pkg).put("l", e.label).put("s", e.system).put("c", comp.className)
                        .put("t", if (e.launch.categories?.contains(Intent.CATEGORY_LEANBACK_LAUNCHER) == true) "tv" else "app")
                        .put("i", e.installed).put("u", e.updated).put("f", name),
                )
            }
            // старые файлы значков (прежний формат имени, прежнее время обновления, удалённые приложения) удаляются
            IconCacheRules.stale(dir.list()?.toList().orEmpty(), keep).forEach { File(dir, it).delete() }
            listFile(ctx).writeText(arr.toString())
        }
    }

    fun load(ctx: Context): List<Entry> {
        migrate(ctx)
        val f = listFile(ctx)
        if (!f.exists()) return emptyList()
        val pm = ctx.packageManager
        return runCatching {
            val arr = JSONArray(f.readText())
            val dir = iconDir(ctx)
            val out = ArrayList<Entry>()
            for (n in 0 until arr.length()) {
                val o = arr.getJSONObject(n)
                val pkg = o.getString("p")
                // актуальность по PackageManager: приложение обновилось или удалено, пока программа не работала, значит старый значок не показываем
                val pmUpdated = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrNull() ?: continue
                val name = o.optString("f")
                if (!IconCacheRules.isCurrent(name, pkg, pmUpdated, IconCacheRules.ICON_PIPELINE_VERSION, BuildConfig.VERSION_CODE)) continue
                val file = File(dir, name)
                val bitmap = BitmapFactory.decodeFile(file.path) ?: continue
                // пустой или чёрный значок из кэша не используется: файл удаляется, значок перерисуется в фоне
                if (!IconCacheRules.useCached(true, IconQuality.isBadBitmap(bitmap))) { file.delete(); continue }
                val cat = if (o.optString("t") == "tv") Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER
                val launch = Intent(Intent.ACTION_MAIN).addCategory(cat).setClassName(pkg, o.getString("c"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                out.add(
                    Entry(pkg, o.getString("l"), BitmapDrawable(ctx.resources, bitmap), o.getBoolean("s"), launch,
                        o.optLong("i"), o.optLong("u"), bitmap.asImageBitmap()),
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    fun clearIcons(ctx: Context) {
        iconDir(ctx).deleteRecursively()
        listFile(ctx).delete()
    }
}
