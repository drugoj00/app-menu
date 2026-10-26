package app.tvapps

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Резервная копия: все настройки (вид, сортировка, свой порядок значков, скрытые, набор значков, счётчики запусков)
 * и свои картинки значков в одном ZIP-файле. Ключ ADB в копию не входит: на новом устройстве нужно подключиться заново.
 */
object Backup {
    private const val SETTINGS = "settings.json"

    /** Файл автоматической копии в папке программы; обновляется при каждом выходе из программы. */
    fun autoFile(ctx: Context): File? = ctx.getExternalFilesDir("backup")?.let { File(it, "tvapps-backup.zip") }

    fun write(ctx: Context, out: OutputStream) {
        val prefs = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE)
        val json = JSONObject()
        prefs.all.forEach { (k, v) ->
            val e = JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                is Set<*> -> e.put("t", "S").put("v", JSONArray(v.map { it.toString() }))
                else -> return@forEach
            }
            json.put(k, e)
        }
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(SETTINGS))
            z.write(JSONObject().put("app", "TVApps").put("format", 1).put("prefs", json).toString(2).toByteArray())
            z.closeEntry()
            File(ctx.filesDir, "icons").listFiles()?.filter { it.name.endsWith(".png") }?.forEach { f ->
                z.putNextEntry(ZipEntry("icons/" + f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    fun autoSave(ctx: Context) {
        runCatching {
            val f = autoFile(ctx) ?: return
            f.parentFile?.mkdirs()
            f.outputStream().use { write(ctx, it) }
        }
    }

    /** Восстановление: настройки заменяются на сохранённые, свои значки добавляются. Возвращает true при успехе. */
    fun restore(ctx: Context, input: InputStream): Boolean = runCatching {
        var settings: JSONObject? = null
        val icons = HashMap<String, ByteArray>()
        ZipInputStream(input).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val bytes = z.readBytes()
                when {
                    e.name == SETTINGS -> settings = JSONObject(String(bytes))
                    e.name.startsWith("icons/") && e.name.endsWith(".png") -> icons[e.name.substringAfter("icons/")] = bytes
                }
                z.closeEntry()
                e = z.nextEntry
            }
        }
        val prefsJson = settings?.getJSONObject("prefs") ?: return false
        val ed = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE).edit().clear()
        prefsJson.keys().forEach { k ->
            val e = prefsJson.getJSONObject(k)
            when (e.getString("t")) {
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "s" -> ed.putString(k, e.getString("v"))
                "S" -> ed.putStringSet(k, e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
            }
        }
        ed.apply()
        val dir = File(ctx.filesDir, "icons").apply { mkdirs() }
        icons.forEach { (name, data) -> File(dir, name.replace("/", "_")).writeBytes(data) }
        Cfg.reload()
        Cfg.bumpIcons()
        true
    }.getOrDefault(false)
}
