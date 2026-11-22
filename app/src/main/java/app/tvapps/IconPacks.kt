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

    /** Подпись набора своих картинок (имена, размеры, время): если она изменилась, готовые значки в списке пересобираются (файл, положенный по ADB или с флешки, подхватывается без «Сброса значков»). */
    fun ownSig(ctx: Context): String {
        val sb = StringBuilder()
        for (d in ownDirs(ctx)) d.listFiles()?.filter { it.name.endsWith(".png", true) }?.sortedBy { it.name }?.forEach { sb.append(it.name).append(':').append(it.length()).append(':').append(it.lastModified()).append(';') }
        return sb.toString().hashCode().toString() + ":" + sb.length
    }

    /** true, если набор своих картинок изменился с прошлой загрузки списка (запоминается в настройках, поэтому холодный старт без изменений не пересобирает всё). */
    fun ownChanged(ctx: Context): Boolean {
        val sp = ctx.applicationContext.getSharedPreferences("tvapps_icons", Context.MODE_PRIVATE)
        val now = ownSig(ctx)
        if (sp.getString("sig", null) == now) return false
        sp.edit().putString("sig", now).apply()
        return true
    }

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

    /** Разобранный набор значков (15.0): картинки приложений по компоненту и по пакету, а также фон, маска и накладка для приложений, которых в наборе нет. */
    class Pack(
        val byComp: Map<String, String>, val byPkg: Map<String, String>,
        val back: List<String>, val mask: String?, val upon: String?, val scale: Float,
    ) {
        val total get() = byComp.size
        val hasStyle get() = back.isNotEmpty() || mask != null || upon != null
    }

    private val cache = HashMap<String, Pack>()

    /** Сбрасывает разобранные наборы: нужен после смены набора. */
    fun clearCache() { synchronized(cache) { cache.clear() } }

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

    /** Разбор appfilter.xml набора: компонент приложения → имя картинки, фон/маска/накладка (iconback, iconmask, iconupon) и масштаб (scale). */
    fun load(ctx: Context, pack: String): Pack = synchronized(cache) {
        cache.getOrPut(pack) {
            runCatching {
                val res = ctx.packageManager.getResourcesForApplication(pack)
                val comps = HashMap<String, String>()
                val pkgs = HashMap<String, String>()
                val back = ArrayList<String>()
                var mask: String? = null
                var upon: String? = null
                var scale = 1f
                fun read(p: XmlPullParser) {
                    while (p.next() != XmlPullParser.END_DOCUMENT) {
                        if (p.eventType != XmlPullParser.START_TAG) continue
                        when (p.name) {
                            "item" -> {
                                val comp = p.getAttributeValue(null, "component") ?: continue
                                val dr = p.getAttributeValue(null, "drawable") ?: continue
                                // не putIfAbsent: его нет в Android 6.0 (API 23)
                                val key = norm(comp)
                                if (!comps.containsKey(key)) comps[key] = dr
                                val pk = key.substringBefore('/')
                                if (pk.isNotEmpty() && !pkgs.containsKey(pk)) pkgs[pk] = dr
                            }
                            "iconback" -> for (i in 0 until p.attributeCount) if (p.getAttributeName(i).startsWith("img")) back.add(p.getAttributeValue(i))
                            "iconmask" -> if (p.attributeCount > 0) mask = p.getAttributeValue(0)
                            "iconupon" -> if (p.attributeCount > 0) upon = p.getAttributeValue(0)
                            "scale" -> p.getAttributeValue(null, "factor")?.toFloatOrNull()?.let { scale = it.coerceIn(0.3f, 1.2f) }
                        }
                    }
                }
                val xmlId = res.getIdentifier("appfilter", "xml", pack)
                if (xmlId != 0) read(res.getXml(xmlId))
                else res.assets.open("appfilter.xml").use { st ->
                    val p = Xml.newPullParser(); p.setInput(st, null); read(p)
                }
                Pack(comps, pkgs, back, mask, upon, scale)
            }.getOrDefault(Pack(emptyMap(), emptyMap(), emptyList(), null, null, 1f))
        }
    }

    private fun drawableOf(ctx: Context, pack: String, name: String?): Drawable? {
        if (name.isNullOrBlank()) return null
        return runCatching {
            val res = ctx.packageManager.getResourcesForApplication(pack)
            val id = res.getIdentifier(name, "drawable", pack).takeIf { it != 0 } ?: res.getIdentifier(name, "mipmap", pack)
            if (id != 0) res.getDrawable(id, null) else null
        }.getOrNull()
    }

    /** Название картинки набора для компонента или (запасной вариант) для пакета приложения. */
    fun nameFor(ctx: Context, pack: String, e: Entry): String? {
        val d = load(ctx, pack)
        val comp = e.launch.component?.flattenToString()?.let { norm(it) }
        return (comp?.let { d.byComp[it] }) ?: d.byPkg[e.pkg.lowercase()]
    }

    /** Значок приложения из набора (или null, если в наборе его нет). */
    fun fromPack(ctx: Context, pack: String, e: Entry): Drawable? = drawableOf(ctx, pack, nameFor(ctx, pack, e))

    private fun toBmp(d: Drawable?, size: Int): Bitmap? {
        if (d == null) return null
        return runCatching {
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, size, size)
            d.draw(android.graphics.Canvas(b))
            b
        }.getOrNull()
    }

    /**
     * Значок приложения, которого в наборе нет, но набор задаёт общее оформление (фон, маска, накладка, масштаб): родной значок кладётся на фон набора
     * с маской. Работает только при включённой настройке «Оформлять остальные значки».
     */
    fun styled(ctx: Context, pack: String, native: Drawable, size: Int = 192): Drawable? {
        val d = load(ctx, pack)
        if (!d.hasStyle) return null
        return runCatching {
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(out)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
            val full = android.graphics.Rect(0, 0, size, size)
            if (d.back.isNotEmpty()) toBmp(drawableOf(ctx, pack, d.back[(native.intrinsicWidth.coerceAtLeast(1) + native.intrinsicHeight.coerceAtLeast(0)) % d.back.size]), size)?.let { c.drawBitmap(it, null, full, paint) }
            // родной значок рисуется во временный слой, затем вырезается по маске
            val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val lc = android.graphics.Canvas(layer)
            val inner = (size * d.scale).toInt().coerceAtLeast(1)
            val off = (size - inner) / 2
            native.setBounds(off, off, off + inner, off + inner)
            native.draw(lc)
            toBmp(drawableOf(ctx, pack, d.mask), size)?.let { m ->
                val mp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }
                // как в Nova и ADW: непрозрачные места маски вырезаются из значка (DST_OUT)
                lc.drawBitmap(m, null, full, mp)
            }
            c.drawBitmap(layer, 0f, 0f, paint)
            toBmp(drawableOf(ctx, pack, d.upon), size)?.let { c.drawBitmap(it, null, full, paint) }
            BitmapDrawable(ctx.resources, out)
        }.getOrNull()
    }

    /** Установленный набор: сколько значков подходит к установленным приложениям и первые примеры для предпросмотра. */
    class Preview(val matched: Int, val total: Int, val samples: List<Pair<Entry, Drawable>>)

    fun preview(ctx: Context, pack: String, apps: List<Entry>, max: Int = 12): Preview {
        val d = load(ctx, pack)
        var n = 0
        val samples = ArrayList<Pair<Entry, Drawable>>()
        for (e in apps) {
            val dr = fromPack(ctx, pack, e) ?: continue
            n++
            if (samples.size < max) samples.add(e to dr)
        }
        return Preview(n, d.total, samples)
    }

    /** Свой значок, если он есть: сначала своя картинка, затем набор значков (по компоненту, затем по пакету), затем общее оформление набора. */
    fun override(ctx: Context, e: Entry): Drawable? {
        for (d in ownDirs(ctx)) {
            val f = File(d, "${e.pkg}.png")
            if (f.exists()) BitmapFactory.decodeFile(f.path)?.let { return BitmapDrawable(ctx.resources, it) }
        }
        val pack = Cfg.iconPack
        if (pack.isNotEmpty()) {
            fromPack(ctx, pack, e)?.let { return it }
            if (Cfg.iconPackStyle) styled(ctx, pack, e.icon)?.let { return it }
        }
        return null
    }
}
