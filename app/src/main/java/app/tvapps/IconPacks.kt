package app.tvapps

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Замена значков: своя картинка для приложения (файл pkg.png) и наборы значков из установленных программ
 * (формат, который знают популярные лаунчеры: файл appfilter.xml внутри набора).
 */
object IconPacks {
    private val actions = listOf(
        "org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.anddoes.launcher.THEME", "com.fede.launcher.THEME_ICONPACK",
        "com.gau.go.launcherex.theme",
    )

    /** Установленные наборы значков: пакет и название. */
    fun installed(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, String>()
        for (a in actions) {
            for (ri in pm.queryIntentActivities(Intent(a), 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg !in seen) seen[pkg] = ri.loadLabel(pm).toString()
            }
        }
        return seen.map { it.key to it.value }.sortedBy { it.second.lowercase() }
    }

    private fun ownDirs(ctx: Context): List<File> = listOfNotNull(File(ctx.filesDir, "icons"), ctx.getExternalFilesDir("icons"))

    /** Папка, куда можно положить файлы вида имя.пакета.png (подходит и для ручной загрузки по ADB или с флешки). */
    fun manualDir(ctx: Context): File? = ctx.getExternalFilesDir("icons")

    fun hasOwn(ctx: Context, pkg: String) = ownDirs(ctx).any { File(it, "$pkg.png").exists() }

    fun removeOwn(ctx: Context, pkg: String) { ownDirs(ctx).forEach { File(it, "$pkg.png").delete() } }

    fun removeAllOwn(ctx: Context) { ownDirs(ctx).forEach { d -> d.listFiles()?.forEach { it.delete() } } }

    /** Сохраняет выбранную картинку как значок приложения (сжимает до 256×256). */
    fun saveOwn(ctx: Context, pkg: String, uri: Uri): Boolean = saveFrom(ctx, pkg) { ctx.contentResolver.openInputStream(uri) }

    private fun saveFrom(ctx: Context, pkg: String, open: () -> java.io.InputStream?): Boolean = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = open()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false
        val side = minOf(bmp.width, bmp.height)
        val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val out = Bitmap.createScaledBitmap(square, 256, 256, true)
        val dir = File(ctx.filesDir, "icons").apply { mkdirs() }
        File(dir, "$pkg.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    }.getOrDefault(false)

    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Имя файла → пакет: точное имя пакета или название приложения (без регистра, пробелов и знаков). */
    private fun matchPkg(fileName: String, apps: List<Entry>): String? {
        val base = fileName.substringAfterLast('/').substringBeforeLast('.')
        apps.firstOrNull { it.pkg.equals(base, true) }?.let { return it.pkg }
        val k = key(base)
        return if (k.isEmpty()) null else apps.firstOrNull { key(it.label) == k }?.pkg
    }

    private fun isImageName(n: String) = n.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp")

    /** Пакетная замена: все картинки из выбранной папки, подходящие по имени. Возвращает число заменённых значков. */
    fun importTree(ctx: Context, tree: Uri, apps: List<Entry>): Int {
        var n = 0
        runCatching {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(tree)
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            ctx.contentResolver.query(children, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    if (!isImageName(name)) continue
                    val pkg = matchPkg(name, apps) ?: continue
                    val doc = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    if (saveFrom(ctx, pkg) { ctx.contentResolver.openInputStream(doc) }) n++
                }
            }
        }
        return n
    }

    /** Пакетная замена из ZIP-архива с картинками. */
    fun importZip(ctx: Context, zip: Uri, apps: List<Entry>): Int {
        var n = 0
        runCatching {
            ctx.contentResolver.openInputStream(zip)?.use { raw ->
                java.util.zip.ZipInputStream(raw).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        if (!e.isDirectory && isImageName(e.name)) {
                            val pkg = matchPkg(e.name, apps)
                            if (pkg != null) {
                                val bytes = z.readBytes()
                                if (bytes.size < 8_000_000 && saveFrom(ctx, pkg) { java.io.ByteArrayInputStream(bytes) }) n++
                            }
                        }
                        z.closeEntry()
                        e = z.nextEntry
                    }
                }
            }
        }
        return n
    }

    private val cache = HashMap<String, Map<String, String>>()

    private fun norm(c: String): String {
        var s = c.trim()
        if (s.startsWith("ComponentInfo{")) s = s.removePrefix("ComponentInfo{").removeSuffix("}")
        val slash = s.indexOf('/')
        if (slash > 0) {
            val pkg = s.substring(0, slash)
            var cls = s.substring(slash + 1)
            if (cls.startsWith(".")) cls = pkg + cls
            s = "$pkg/$cls"
        }
        return s.lowercase()
    }

    /** Разбор appfilter.xml набора: компонент приложения → имя картинки. */
    private fun map(ctx: Context, pack: String): Map<String, String> = cache.getOrPut(pack) {
        runCatching {
            val res = ctx.packageManager.getResourcesForApplication(pack)
            val out = HashMap<String, String>()
            fun read(p: XmlPullParser) {
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "item") {
                        val comp = p.getAttributeValue(null, "component") ?: continue
                        val dr = p.getAttributeValue(null, "drawable") ?: continue
                        out.putIfAbsent(norm(comp), dr)
                    }
                }
            }
            val xmlId = res.getIdentifier("appfilter", "xml", pack)
            if (xmlId != 0) read(res.getXml(xmlId))
            else ctx.packageManager.getResourcesForApplication(pack).assets.open("appfilter.xml").use { st ->
                val p = Xml.newPullParser(); p.setInput(st, null); read(p)
            }
            out
        }.getOrDefault(emptyMap())
    }

    /** Свой значок, если он есть: сначала своя картинка, затем набор значков. */
    fun override(ctx: Context, e: Entry): Drawable? {
        for (d in ownDirs(ctx)) {
            val f = File(d, "${e.pkg}.png")
            if (f.exists()) BitmapFactory.decodeFile(f.path)?.let { return BitmapDrawable(ctx.resources, it) }
        }
        val pack = Cfg.iconPack
        if (pack.isNotEmpty()) {
            val comp = e.launch.component?.flattenToString()?.lowercase()
            val name = comp?.let { map(ctx, pack)[it] }
            if (name != null) {
                val res = ctx.packageManager.getResourcesForApplication(pack)
                val id = res.getIdentifier(name, "drawable", pack)
                if (id != 0) return runCatching { res.getDrawable(id, null) }.getOrNull()
            }
        }
        return null
    }
}
