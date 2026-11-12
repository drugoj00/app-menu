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
 * Резервная копия: все настройки меню и экрана «Дом» (вид, сортировка, свой порядок значков, скрытые, набор значков, счётчики запусков,
 * строки, виджеты и верхняя полоска «Дома») и свои картинки значков и баннеров в одном ZIP-файле. Ключ ADB в копию не входит: на новом устройстве нужно подключиться заново.
 */
object Backup {
    private const val SETTINGS = "settings.json"

    /** Файл автоматической копии в папке программы; обновляется при каждом выходе из программы. */
    /** Имя файла копии: AppMenu_дата (для ручного сохранения), AppMenu_авто (одна автоматическая копия, перезаписывается). */
    fun fileName(ctx: Context): String = "AppMenu_" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".zip"

    fun autoFile(ctx: Context): File? = ctx.getExternalFilesDir("backup")?.let { dir ->
        val f = File(dir, "AppMenu_auto.zip")
        // копия прежних версий называлась tvapps-backup.zip: переименовываем, чтобы «восстановить отсюда» её находило
        val old = File(dir, "tvapps-backup.zip")
        if (!f.exists() && old.exists()) runCatching { old.renameTo(f) }
        f
    }

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
            // суточные копии: после сброса настроек «последняя» копия уже новая, а копия за прошлые дни остаётся (хранятся 7 последних)
            val day = File(f.parentFile, DAY_PREFIX + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".zip")
            f.copyTo(day, overwrite = true)
            f.parentFile?.listFiles { x -> x.name.startsWith(DAY_PREFIX) && x.name.endsWith(".zip") }?.sortedByDescending { it.name }?.drop(7)?.forEach { it.delete() }
        }
    }

    private const val DAY_PREFIX = "AppMenu_auto_"
    private const val BEFORE = "AppMenu_before_restore.zip"

    /** Копия настроек перед восстановлением: чтобы восстановление можно было отменить. */
    private fun saveBeforeRestore(ctx: Context) {
        runCatching {
            val dir = ctx.getExternalFilesDir("backup") ?: return
            dir.mkdirs()
            File(dir, BEFORE).outputStream().use { write(ctx, it) }
        }
    }

    /** Файл копии: пригодным считается ZIP с settings.json внутри. */
    private fun isCopy(f: File): Boolean = runCatching {
        f.isFile && f.length() in 100..300_000_000L && java.util.zip.ZipFile(f).use { it.getEntry(SETTINGS) != null }
    }.getOrDefault(false)

    /**
     * Копии, которые можно восстановить. onlyApp: только папка программы (автокопии и копия перед восстановлением);
     * иначе ещё память устройства и накопители (корень, Download, Documents, Backup, AppMenuBackups), где лежат копии, сохранённые вручную.
     * Новые файлы идут первыми.
     */
    fun findCopies(ctx: Context, onlyApp: Boolean): List<File> {
        val out = LinkedHashMap<String, File>()
        fun scan(d: File?, deep: Int) {
            val items = d?.listFiles() ?: return
            for (f in items) {
                if (out.size >= 60) return
                if (f.isFile && f.name.endsWith(".zip", ignoreCase = true)) {
                    val n = f.name.lowercase()
                    if (!onlyApp && !(n.contains("tvapps") || n.contains("appmenu") || n.contains("menu") || n.contains("backup") || n.contains("копи"))) continue
                    if (isCopy(f)) out[f.absolutePath] = f
                } else if (f.isDirectory && deep > 0 && !f.name.startsWith(".")) scan(f, deep - 1)
            }
        }
        scan(ctx.getExternalFilesDir("backup"), 0)
        if (!onlyApp) {
            val roots = ArrayList<File>()
            roots.add(android.os.Environment.getExternalStorageDirectory())
            File("/storage").listFiles()?.filter { it.isDirectory && it.name != "self" && it.name != "emulated" && it.canRead() }?.forEach { roots.add(it) }
            for (r in roots) {
                scan(r, 0)
                for (n in listOf("Download", "Documents", "Backup", "Backups", "AppMenuBackups", "AppMenu", "Резервные копии", "Копии")) scan(File(r, n), 1)
            }
        }
        return out.values.sortedByDescending { it.lastModified() }
    }

    /** Причина последней неудачи восстановления: "" (нет), "open", "notcopy", "broken", "other". */
    @Volatile var lastFail: String = ""

    /** Восстановление: настройки заменяются на сохранённые, свои значки добавляются. Возвращает true при успехе. */
    fun restore(ctx: Context, input: InputStream): Boolean = runCatching {
        lastFail = ""
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
        val prefsJson = settings?.optJSONObject("prefs")
        if (prefsJson == null) { lastFail = "notcopy"; return false }
        saveBeforeRestore(ctx)
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
        HomeStore.init(ctx)
        HomeStore.reload()
        Cfg.bumpIcons()
        true
    }.getOrElse {
        lastFail = if (it is java.util.zip.ZipException || it is org.json.JSONException || it is java.io.EOFException) "broken" else "other"
        android.util.Log.w("TVApps", "восстановление: " + it.javaClass.simpleName + ": " + it.message)
        false
    }
}
