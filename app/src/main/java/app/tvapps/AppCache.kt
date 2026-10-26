package app.tvapps

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Кэш списка приложений и их значков на диске: при запуске список показывается сразу из кэша,
 * а настоящая загрузка идёт в фоне и потом обновляет его. Кэш готовится и при включении устройства.
 */
object AppCache {
    private fun listFile(ctx: Context) = File(ctx.filesDir, "apps_cache.json")
    private fun iconDir(ctx: Context) = File(ctx.cacheDir, "icons_cache")

    fun save(ctx: Context, list: List<Entry>, force: Boolean = false) {
        runCatching {
            val arr = JSONArray()
            val dir = iconDir(ctx).apply { mkdirs() }
            for (e in list) {
                val comp = e.launch.component ?: continue
                arr.put(
                    JSONObject().put("p", e.pkg).put("l", e.label).put("s", e.system).put("c", comp.className)
                        .put("t", if (e.launch.categories?.contains(Intent.CATEGORY_LEANBACK_LAUNCHER) == true) "tv" else "app")
                        .put("i", e.installed).put("u", e.updated),
                )
                val f = File(dir, e.pkg + ".png")
                val bmp = e.icon.let { d -> (d as? BitmapDrawable)?.bitmap }
                if (force || !f.exists()) {
                    val b: Bitmap = bmp ?: runCatching { e.icon.toBitmap(128, 128) }.getOrNull() ?: continue
                    f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            listFile(ctx).writeText(arr.toString())
        }
    }

    fun load(ctx: Context): List<Entry> {
        val f = listFile(ctx)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            val dir = iconDir(ctx)
            val out = ArrayList<Entry>()
            for (n in 0 until arr.length()) {
                val o = arr.getJSONObject(n)
                val pkg = o.getString("p")
                val bitmap = BitmapFactory.decodeFile(File(dir, "$pkg.png").path) ?: continue
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
