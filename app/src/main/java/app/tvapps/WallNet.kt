package app.tvapps

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.BatteryManager
import android.os.PowerManager
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Картинка из интернета: откуда, кто автор, под какой лицензией, прямая ссылка. */
class WallPic(val source: String, val title: String, val author: String, val license: String, val url: String, val page: String, val extra: String = "") {
    /** Картинка портретная (выше, чем шире): известно после скачивания. */
    @Volatile var portrait = false
    /** Видео, а не картинка. */
    @Volatile var video = false
    /** Подпись «автор · источник · лицензия» для фона. */
    fun credit(): String = listOf(author.ifBlank { title }, WallNet.sourceName(source), license).filter { it.isNotBlank() }.joinToString(" · ").take(160)
}

/** Ошибка получения обоев: net (нет сети, причина в NetErr), rate (лимит), key (ключ неверный), nokey (ключ нужен), http, none (ничего не нашлось), bad (файл не картинка). */
class WallErr(val kind: String, val code: Int = 0) : Exception(kind)

/**
 * Обои из интернета. Источники без ключа: Wikimedia Commons (избранные картинки, картинка дня), NASA (APOD без чужого авторства, запасной вариант: Библиотека NASA),
 * Openverse (только CC0 и CC BY). Pexels и Unsplash работают только со своим ключом пользователя (поле ввода, по умолчанию пусто, ключ лежит только на устройстве
 * и в резервную копию не входит). Ключей разработчика в программе нет. Ничего личного не отправляется: запрос содержит только тему и номер страницы.
 * Картинки лежат в кэше программы (до 20 штук), подпись хранится рядом с фоном.
 */
object WallNet {
    private const val UA = "AppMenu/5.3 (https://github.com/drugoj00/app-menu; Android launcher wallpaper picker)"
    val SOURCES = listOf("commons", "nasa", "openverse", "pexels", "unsplash")
    val CATS = listOf("nature", "space", "city", "abstract")
    const val CACHE_MAX = 20

    fun sourceName(s: String) = when (s) {
        "commons" -> "Wikimedia Commons"; "nasa" -> "NASA"; "openverse" -> "Openverse"; "pexels" -> "Pexels"; "unsplash" -> "Unsplash"; "pixabay" -> "Pixabay"; "link" -> "link"; else -> s
    }
    fun needsKey(s: String) = s == "pexels" || s == "unsplash" || s == "pixabay"
    fun hasKeyField(s: String) = needsKey(s) || s == "nasa"

    /** Какую картинку искать: настройка «Ориентация обоев» (auto: по экрану устройства сейчас, land: альбомная, port: портретная). */
    fun wantPortrait(c: Context): Boolean = when (str(c, "orient", "auto")) {
        "port" -> true
        "land" -> false
        else -> c.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    }

    // ---------- настройки (отдельный файл, в резервную копию меню не входит: ключи остаются только на устройстве) ----------
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("tvapps_wallnet", Context.MODE_PRIVATE)
    fun str(c: Context, k: String, d: String = "") = sp(c).getString(k, d) ?: d
    fun bool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    fun put(c: Context, k: String, v: String) { sp(c).edit().putString(k, v).apply() }
    fun put(c: Context, k: String, v: Boolean) { sp(c).edit().putBoolean(k, v).apply() }
    fun key(c: Context, src: String) = str(c, "key_$src").trim()

    /** Поверхности: home («Дом»), menu («Меню приложений»), saver («Заставка»). У каждой свои источник, тема, ориентация и режим смены; пункт «Как у «Дома»» включается только самим пользователем; по умолчанию обои действуют лишь там, где их выбрали. */
    val SURFACES = listOf("home", "menu", "saver")
    fun same(c: Context, target: String) = target != "home" && bool(c, "same_$target", false)

    /** Значение настройки поверхности: своё; если включено «Как у Дома» (кроме режима смены, он всегда свой), то от «Дома»; старые общие ключи (src, cat, orient) читаются как запасной вариант. */
    fun cfg(c: Context, target: String, k: String, d: String): String {
        val t = if (k != "mode" && same(c, target)) "home" else target
        return str(c, k + "_" + t).ifEmpty { str(c, k, d) }.ifEmpty { d }
    }

    /** Записывает настройку поверхности; при включённом «Как у Дома» сначала копирует текущие значения в свои, чтобы менялась только эта поверхность. */
    fun setCfg(c: Context, target: String, k: String, v: String) {
        if (k != "mode" && same(c, target)) {
            for ((kk, dd) in listOf("src" to "commons", "cat" to "nature", "orient" to "auto")) put(c, kk + "_" + target, cfg(c, target, kk, dd))
            put(c, "same_$target", false)
        }
        put(c, k + "_" + target, v)
    }

    /** optString отдаёт строку "null" для JSON null: здесь пусто. */
    private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private val rnd = java.util.Random()

    // ---------- сеть ----------
    private fun open(url: String, headers: Map<String, String>, method: String = "GET"): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 25_000; c.requestMethod = method
        c.setRequestProperty("User-Agent", UA)
        for ((k, v) in headers) c.setRequestProperty(k, v)
        return c
    }

    private fun check(c: HttpURLConnection) {
        val code = c.responseCode
        if (code in 200..299) return
        val body = runCatching { c.errorStream?.bufferedReader()?.use { it.readText().take(400) } }.getOrNull().orEmpty().lowercase()
        throw when {
            code == 429 -> WallErr("rate")
            code == 401 -> WallErr("key")
            code == 403 && (body.contains("rate") || body.contains("limit")) -> WallErr("rate")
            code == 403 -> WallErr("key")
            else -> WallErr("http", code)
        }
    }

    private fun http(url: String, headers: Map<String, String> = emptyMap()): String {
        var err: Exception? = null
        for (attempt in 0..1) {
            try {
                val c = open(url, headers)
                try {
                    check(c)
                    return c.inputStream.bufferedReader().use { it.readText() }
                } finally { c.disconnect() }
            } catch (e: WallErr) {
                // сбой на стороне сервиса (502, 503, 504): один повтор через паузу
                if (e.kind == "http" && e.code >= 500 && attempt == 0) { try { Thread.sleep(2000) } catch (_: InterruptedException) { }; continue }
                throw e
            } catch (e: Exception) {
                err = e
                NetErr.set(e)
                if (e is javax.net.ssl.SSLException || e is java.net.UnknownHostException) break
                try { Thread.sleep(700) } catch (_: InterruptedException) { }
            }
        }
        android.util.Log.w("TVApps", "обои: сеть: " + err?.javaClass?.simpleName + ": " + err?.message)
        throw WallErr("net")
    }

    /** Имена авторов в Openverse иногда приходят с процентным кодированием («Ashim%20D%u2019Silva»). */
    private fun unpercent(s: String): String {
        if (!s.contains('%')) return s
        var t = Regex("%u([0-9A-Fa-f]{4})").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
        t = runCatching { java.net.URLDecoder.decode(t.replace("+", "%2B"), "UTF-8") }.getOrDefault(t)
        return t
    }

    private fun strip(s: String): String = s.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()

    // ---------- темы ----------
    private fun term(src: String, cat: String): String = when (src) {
        "commons" -> when (cat) { "space" -> "astronomy"; "city" -> "cityscape"; "abstract" -> "abstract"; else -> "landscape" }
        "openverse", "pexels", "unsplash" -> when (cat) { "space" -> "galaxy nebula stars"; "city" -> "city skyline"; "abstract" -> "abstract pattern"; else -> "nature landscape" }
        else -> when (cat) { "space" -> "galaxy nebula hubble"; "city" -> "city lights at night from space"; "abstract" -> "nebula colors"; else -> "earth horizon from space" }
    }

    private fun day(back: Int = 0): String {
        val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, -back)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
    }

    // ---------- Wikimedia Commons ----------
    private fun commonsPics(json: String, portrait: Boolean = false): List<WallPic> {
        val pages = JSONObject(json).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val out = ArrayList<WallPic>()
        for (k in pages.keys()) {
            val p = pages.getJSONObject(k)
            val ii = p.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val w = ii.optInt("width"); val h = ii.optInt("height")
            val mime = ii.str("mime")
            if (h <= 0 || !(mime == "image/jpeg" || mime == "image/png")) continue
            if (portrait) { if (w < 900 || h < 1500 || h.toFloat() / w < 1.2f) continue } else if (w < 1600 || w.toFloat() / h < 1.3f) continue
            val em = ii.optJSONObject("extmetadata")
            val url = ii.str("thumburl").ifEmpty { ii.str("url") }
            if (url.isEmpty()) continue
            out.add(WallPic("commons", p.str("title").removePrefix("File:").substringBeforeLast('.'),
                strip(em?.optJSONObject("Artist")?.str("value").orEmpty()).take(60),
                strip(em?.optJSONObject("LicenseShortName")?.str("value").orEmpty()), url, ii.str("descriptionurl")))
        }
        return out
    }

    private fun commonsBase(portrait: Boolean) = "https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata&iiurlwidth=" + (if (portrait) 1080 else 1920) + "&iiextmetadatafilter=Artist%7CLicenseShortName"

    private fun commons(cat: String, today: Boolean, portrait: Boolean): List<WallPic> {
        val COMMONS = commonsBase(portrait)
        if (today) {
            for (back in 0..2) {
                val t = JSONObject(http("https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=images&imlimit=5&titles=" + enc("Template:Potd/" + day(back))))
                val pages = t.optJSONObject("query")?.optJSONObject("pages") ?: continue
                for (k in pages.keys()) {
                    val imgs = pages.getJSONObject(k).optJSONArray("images") ?: continue
                    for (i in 0 until imgs.length()) {
                        val title = imgs.getJSONObject(i).str("title")
                        if (!title.startsWith("File:")) continue
                        val r = commonsPics(http("$COMMONS&titles=" + enc(title)), portrait)
                        if (r.isNotEmpty()) return r
                    }
                }
            }
            return emptyList()
        }
        // портретных среди избранных мало: берём больше записей и добавляем к запросу слово portrait
        val q = enc("incategory:\"Featured pictures on Wikimedia Commons\" " + term("commons", cat) + (if (portrait) " portrait" else ""))
        val base = "$COMMONS&generator=search&gsrnamespace=6&gsrlimit=" + (if (portrait) 50 else 20) + "&gsrsearch=$q"
        var r = commonsPics(http("$base&gsroffset=" + rnd.nextInt(if (portrait) 40 else 100)), portrait)
        if (r.isEmpty()) r = commonsPics(http("$base&gsroffset=0"), portrait)
        return r
    }

    // ---------- NASA ----------
    private fun nasaApod(today: Boolean, key: String): List<WallPic> {
        val body = http("https://api.nasa.gov/planetary/apod?api_key=" + enc(key) + (if (today) "" else "&count=8")).trim()
        val arr = if (body.startsWith("[")) JSONArray(body) else JSONArray().put(JSONObject(body))
        val out = ArrayList<WallPic>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.str("media_type") != "image" || o.has("copyright")) continue
            val url = o.str("hdurl").ifEmpty { o.str("url") }
            // иногда служба отдаёт заглушку со значком NASA вместо снимка дня
            if (url.isEmpty() || url.contains("nasa-logo", true) || o.str("title") == "NASA Science") continue
            out.add(WallPic("nasa", o.str("title"), "NASA", "public domain", url, "https://apod.nasa.gov/apod/"))
        }
        return out
    }

    private fun nasaLibrary(cat: String): List<WallPic> {
        val q = enc(term("nasa", cat))
        val j = JSONObject(http("https://images-api.nasa.gov/search?media_type=image&page_size=24&year_start=2000&q=$q&page=" + (1 + rnd.nextInt(4))))
        val items = j.optJSONObject("collection")?.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<WallPic>()
        for (i in 0 until items.length()) {
            val d = items.getJSONObject(i).optJSONArray("data")?.optJSONObject(0) ?: continue
            val id = d.str("nasa_id")
            if (id.isEmpty()) continue
            // нашивки экипажей, логотипы и портреты для обоев не годятся
            if (Regex("patch|logo|insignia|emblem|crew|portrait|astronaut|official", RegexOption.IGNORE_CASE).containsMatchIn(d.str("title"))) continue
            val who = d.str("photographer").ifEmpty { d.str("secondary_creator") }.ifEmpty { "NASA" }
            out.add(WallPic("nasa", d.str("title"), who.take(60), "public domain", "", "https://images.nasa.gov/details/" + enc(id), id))
        }
        return out
    }

    private fun nasa(cat: String, today: Boolean, ctx: Context): List<WallPic> {
        val own = key(ctx, "nasa")
        val r = try { nasaApod(today, own.ifEmpty { "DEMO_KEY" }) } catch (e: WallErr) {
            if (e.kind == "net") throw e
            if (e.kind == "key" && own.isNotEmpty()) throw e
            emptyList()
        }
        return r.ifEmpty { nasaLibrary(cat) }
    }

    /** Адрес файла Библиотеки NASA: оригинал, если не больше 10 МБ, иначе большой вариант. */
    private fun nasaResolve(id: String): String {
        val items = JSONObject(http("https://images-api.nasa.gov/asset/" + enc(id))).optJSONObject("collection")?.optJSONArray("items") ?: throw WallErr("none")
        val urls = (0 until items.length()).map { items.getJSONObject(it).str("href").replace("http://", "https://") }.filter { it.endsWith(".jpg", true) || it.endsWith(".png", true) }
        fun find(tag: String) = urls.firstOrNull { it.contains("~$tag.") }
        val orig = find("orig")
        if (orig != null) {
            val size = runCatching { open(orig, emptyMap(), "HEAD").let { c -> try { c.contentLengthLong } finally { c.disconnect() } } }.getOrDefault(-1L)
            if (size in 1..10_000_000L) return orig
        }
        return find("large") ?: find("medium") ?: orig ?: throw WallErr("none")
    }

    // ---------- Openverse ----------
    private fun openverse(cat: String, today: Boolean, portrait: Boolean): List<WallPic> {
        val base = "https://api.openverse.org/v1/images/?license=cc0,by&page_size=20" + (if (portrait) "&aspect_ratio=tall" else "") + "&q=" + enc(term("openverse", cat))
        val page = if (today) 1 + Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % 5 else 1 + rnd.nextInt(5)
        val body = try { http("$base&page=$page") } catch (e: WallErr) { if (e.kind == "http") http("$base&page=1") else throw e }
        val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<WallPic>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val lic = o.str("license").lowercase()
            if (lic != "cc0" && lic != "by") continue
            // фильтры size и aspect_ratio у Openverse часто отвечают ошибкой 504, поэтому размер и пропорции проверяются здесь
            val w = o.optInt("width"); val h = o.optInt("height")
            if (maxOf(w, h) < 1600 || h <= 0 || w <= 0) continue
            if (portrait) { if (h.toFloat() / w < 1.2f) continue } else if (w.toFloat() / h < 1.3f) continue
            val url = o.str("url")
            if (!url.startsWith("http")) continue
            val licName = if (lic == "cc0") "CC0" else "CC BY " + o.str("license_version")
            out.add(WallPic("openverse", o.str("title"), unpercent(o.str("creator")).take(60), licName.trim(), url.replace("http://", "https://"), o.str("foreign_landing_url")))
        }
        return out
    }

    // ---------- Pexels и Unsplash (только со своим ключом) ----------
    private fun pexels(cat: String, today: Boolean, key: String, portrait: Boolean): List<WallPic> {
        val page = if (today) 1 + Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % 8 else 1 + rnd.nextInt(8)
        val arr = JSONObject(http("https://api.pexels.com/v1/search?orientation=" + (if (portrait) "portrait" else "landscape") + "&size=large&per_page=20&page=$page&query=" + enc(term("pexels", cat)), mapOf("Authorization" to key))).optJSONArray("photos") ?: return emptyList()
        val out = ArrayList<WallPic>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val src = o.optJSONObject("src") ?: continue
            // под экран: у портретной картинки размер 1080x2340 (телефон), у альбомной large2x (около 1880 по ширине)
            val url = if (portrait && src.str("original").isNotEmpty()) src.str("original") + "?auto=compress&cs=tinysrgb&fit=crop&w=1080&h=2340" else src.str("large2x").ifEmpty { src.str("original") }
            if (url.isEmpty()) continue
            out.add(WallPic("pexels", o.str("alt"), o.str("photographer").take(60), "Pexels License", url, o.str("url")))
        }
        return out
    }

    private fun unsplash(cat: String, today: Boolean, key: String, portrait: Boolean): List<WallPic> {
        val page = if (today) 1 + Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % 8 else 1 + rnd.nextInt(8)
        val arr = JSONObject(http("https://api.unsplash.com/search/photos?orientation=" + (if (portrait) "portrait" else "landscape") + "&per_page=20&page=$page&query=" + enc(term("unsplash", cat)), mapOf("Authorization" to "Client-ID $key", "Accept-Version" to "v1"))).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<WallPic>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val raw = o.optJSONObject("urls")?.str("raw").orEmpty()
            if (raw.isEmpty()) continue
            val links = o.optJSONObject("links")
            val url = raw + (if (raw.contains('?')) "&" else "?") + (if (portrait) "w=1080&h=2340&fit=crop&q=80&fm=jpg" else "w=1920&q=80&fm=jpg&fit=max")
            // по условиям Unsplash автор и ссылка на него с метками показываются, а скачивание отмечается отдельным запросом
            out.add(WallPic("unsplash", o.str("alt_description"), o.optJSONObject("user")?.str("name").orEmpty().take(60), "Unsplash License", url,
                links?.str("html").orEmpty() + "?utm_source=app_menu&utm_medium=referral", links?.str("download_location").orEmpty()))
        }
        return out
    }

    // ---------- выбор и загрузка ----------
    /** Список подходящих картинок источника. today: «картинка дня» (если у источника нет такой, берётся первая из свежей выдачи). */
    fun list(ctx: Context, src: String, cat: String, today: Boolean, portrait: Boolean = wantPortrait(ctx)): List<WallPic> = when (src) {
        "commons" -> commons(cat, today, portrait)
        // у NASA снимки почти все альбомные: экран заполняется кадрированием по центру
        "nasa" -> nasa(cat, today, ctx)
        "openverse" -> openverse(cat, today, portrait)
        "pexels" -> key(ctx, "pexels").let { if (it.isEmpty()) throw WallErr("nokey") else pexels(cat, today, it, portrait) }
        "unsplash" -> key(ctx, "unsplash").let { if (it.isEmpty()) throw WallErr("nokey") else unsplash(cat, today, it, portrait) }
        else -> emptyList()
    }

    fun pick(ctx: Context, src: String, cat: String, today: Boolean, portrait: Boolean = wantPortrait(ctx)): WallPic {
        var l = list(ctx, src, cat, today, portrait)
        if (l.isEmpty() && today) l = list(ctx, src, cat, false, portrait)
        // подходящей по ориентации нет: берём любую подходящую по размеру, экран заполнится кадрированием по центру
        if (l.isEmpty() && portrait) l = list(ctx, src, cat, false, false)
        if (l.isEmpty()) throw WallErr("none")
        return if (today) l[0] else l[rnd.nextInt(l.size)]
    }

    fun cacheDir(ctx: Context) = File(ctx.cacheDir, "wallnet").apply { mkdirs() }
    fun cacheCount(ctx: Context) = cacheDir(ctx).listFiles()?.count { it.isFile } ?: 0
    fun clearCache(ctx: Context): Int {
        var n = 0
        cacheDir(ctx).listFiles()?.forEach { if (it.delete()) n++ }
        return n
    }

    private fun prune(ctx: Context) {
        cacheDir(ctx).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.drop(CACHE_MAX)?.forEach { it.delete() }
    }

    /** Скачивает картинку в кэш и проверяет, что это картинка. */
    fun download(ctx: Context, p: WallPic): File {
        val url = when {
            p.url.isNotEmpty() -> p.url
            p.source == "nasa" -> nasaResolve(p.extra)
            else -> throw WallErr("none")
        }
        val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(16) + ".img"
        val f = File(cacheDir(ctx), name)
        if (f.isFile && f.length() > 10_000) { f.setLastModified(System.currentTimeMillis()); p.portrait = isPortrait(f); return f }
        val tmp = File(cacheDir(ctx), "$name.part")
        try {
            val c = open(url, emptyMap())
            try {
                check(c)
                val len = c.contentLengthLong
                if (len > 30_000_000L) throw WallErr("bad")
                var total = 0L
                c.inputStream.use { i -> tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > 30_000_000L) throw WallErr("bad")
                        o.write(buf, 0, n)
                    }
                } }
            } finally { c.disconnect() }
        } catch (e: WallErr) { tmp.delete(); throw e } catch (e: Exception) {
            tmp.delete(); NetErr.set(e)
            android.util.Log.w("TVApps", "обои: загрузка: " + e.javaClass.simpleName + ": " + e.message)
            throw WallErr("net")
        }
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tmp.path, b)
        if (b.outWidth < 300 || b.outHeight < 200) { tmp.delete(); throw WallErr("bad") }
        f.delete()
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        p.portrait = b.outHeight > b.outWidth
        prune(ctx)
        // отметка о скачивании у Unsplash (по условиям их программного интерфейса); личных данных в запросе нет
        if (p.source == "unsplash" && p.extra.isNotEmpty()) runCatching {
            val k = key(ctx, "unsplash")
            val c = open(p.extra, mapOf("Authorization" to "Client-ID $k")); try { c.responseCode } finally { c.disconnect() }
        }
        return f
    }

    private fun isPortrait(f: File): Boolean {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, b)
        return b.outHeight > b.outWidth
    }

    /** Понятный текст причины неудачи. */
    fun errText(ctx: Context, e: Throwable): String = when ((e as? WallErr)?.kind) {
        "net" -> NetErr.reason(ctx).ifEmpty { ctx.getString(R.string.net_no_network) }
        "rate" -> ctx.getString(R.string.wn_err_rate)
        "key" -> ctx.getString(R.string.wn_err_key)
        "nokey" -> ctx.getString(R.string.wn_err_nokey)
        "http" -> if ((e as WallErr).code >= 500) ctx.getString(R.string.wn_err_server, e.code) else ctx.getString(R.string.net_err_code, e.code)
        "none" -> ctx.getString(R.string.wn_err_none)
        "bad" -> ctx.getString(R.string.wn_err_bad)
        "big" -> ctx.getString(R.string.wn_err_big)
        else -> (e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
    }

    // ---------- подпись ----------
    /** Запоминает подпись к текущему фону (target: menu, home, saver); pic == null: подпись убирается. */
    fun setCredit(ctx: Context, target: String, pic: WallPic?, file: File?) {
        val e = sp(ctx).edit()
        if (pic == null || file == null) { e.remove("credit_$target").remove("stamp_$target") }
        else e.putString("credit_$target", pic.credit()).putLong("stamp_$target", file.lastModified())
        e.apply()
    }

    private fun targetFile(ctx: Context, target: String): File = when {
        target == "saver" -> saverFile(ctx)
        target == "saver_v" -> saverVideoFile(ctx)
        target.endsWith("_v") -> HomeBgFiles.media(ctx, target.startsWith("menu"))
        else -> HomeBgFiles.image(ctx, target.startsWith("menu"), target.endsWith("_p"))
    }

    /** Подпись, которую надо показать на фоне; пусто, если подпись скрыта, фон заменили или файла нет. */
    fun credit(ctx: Context, target0: String, portrait: Boolean = false): String {
        if (!bool(ctx, "credit_on", true)) return ""
        // на портретном экране подпись той картинки, что реально показана: портретной, если она есть, иначе общей
        val target = if (portrait && targetFile(ctx, target0 + "_p").exists()) target0 + "_p" else target0
        val c = str(ctx, "credit_$target")
        if (c.isEmpty()) return ""
        val f = targetFile(ctx, target)
        return if (f.exists() && f.lastModified() == sp(ctx).getLong("stamp_$target", -1L)) c else ""
    }

    // ---------- заставка ----------
    fun saverFile(ctx: Context) = File(ctx.filesDir, "saver_bg.img")
    fun saverOn(ctx: Context) = bool(ctx, "saver_pic", false) && saverFile(ctx).exists()

    fun applySaver(ctx: Context, src: File, pic: WallPic?) {
        src.copyTo(saverFile(ctx), overwrite = true)
        put(ctx, "saver_pic", true)
        setCredit(ctx, "saver", pic, saverFile(ctx))
    }

    /** «Убрать обои»: поверхность возвращается к прежнему виду без картинки (для «Дома» и меню: «По теме», для заставки: чёрный фон), автосмена выключается. */
    fun remove(ctx: Context, target: String) {
        put(ctx, "mode_$target", "off")
        put(ctx, "last_$target", "")
        if (target == "saver") { put(ctx, "saver_pic", false); put(ctx, "saver_vid", false); saverFile(ctx).delete(); saverVideoFile(ctx).delete(); setCredit(ctx, "saver", null, null); setCredit(ctx, "saver_v", null, null); return }
        val menu = target == "menu"
        for (portrait in listOf(false, true)) HomeBgFiles.image(ctx, menu, portrait).delete()
        setCredit(ctx, target, null, null); setCredit(ctx, target + "_p", null, null); setCredit(ctx, target + "_v", null, null)
        HomeBgFiles.media(ctx, menu).delete()
        Handler(Looper.getMainLooper()).post {
            if (menu) HomeStore.changeMenuBg(HomeStore.menuBg.copy(type = "none")) else HomeStore.setBg(HomeStore.layout.bg.copy(type = "none"))
            HomeStore.bumpImg()
        }
    }

    /** Ставит картинку на поверхность и запоминает подпись. Вызывать не в главном потоке. true при успехе. */
    fun applyTo(ctx: Context, target: String, file: File, pic: WallPic): Boolean {
        if (target == "saver") { applySaver(ctx, file, pic); return true }
        val menu = target == "menu"
        if (!HomeBgFiles.saveImage(ctx, Uri.fromFile(file), menu, pic.portrait)) return false
        setCredit(ctx, target + (if (pic.portrait) "_p" else ""), pic, HomeBgFiles.image(ctx, menu, pic.portrait))
        Handler(Looper.getMainLooper()).post {
            if (menu) { if (HomeStore.menuBg.type != "image") HomeStore.changeMenuBg(HomeStore.menuBg.copy(type = "image")) }
            else if (HomeStore.layout.bg.type != "image") HomeStore.setBg(HomeStore.layout.bg.copy(type = "image"))
            HomeStore.bumpImg()
        }
        return true
    }


    // ---------- кадрирование («Сдвинуть / Масштаб») ----------
    /** ox, oy: сдвиг от -1 до 1 (0: по центру), zoom: от 1 до 4. */
    class Crop(val ox: Float, val oy: Float, val zoom: Float)

    private fun cropKey(target: String, portrait: Boolean) = "crop_" + target + (if (portrait) "_p" else "")

    fun crop(ctx: Context, target: String, portrait: Boolean): Crop {
        val p = str(ctx, cropKey(target, portrait)).split(',')
        if (p.size != 3) return Crop(0f, 0f, 1f)
        return Crop(p[0].toFloatOrNull()?.coerceIn(-1f, 1f) ?: 0f, p[1].toFloatOrNull()?.coerceIn(-1f, 1f) ?: 0f, p[2].toFloatOrNull()?.coerceIn(1f, 4f) ?: 1f)
    }

    fun setCrop(ctx: Context, target: String, portrait: Boolean, c: Crop) {
        put(ctx, cropKey(target, portrait), "%.3f,%.3f,%.3f".format(Locale.US, c.ox, c.oy, c.zoom))
        HomeStore.bumpImg()
    }

    // ---------- размытие и общий разбор картинки ----------
    /** Размытие дешёвое: картинка читается в уменьшенном виде (1/2 ... 1/32), а при показе растягивается сглаживанием. Нагрузки сверх обычной картинки нет. */
    fun blurFactor(blur: Int): Int = when { blur <= 0 -> 1; blur <= 20 -> 2; blur <= 40 -> 4; blur <= 60 -> 8; blur <= 80 -> 16; else -> 32 }

    /** Читает файл не крупнее maxSide по длинной стороне; blur уменьшает ещё сильнее (размытие). */
    fun decode(path: String, maxSide: Int, blur: Int = 0): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s * blurFactor(blur) })
    }.getOrNull()

    // ---------- видео-обои ----------
    val VSOURCES = listOf("commons", "nasa", "pexels", "pixabay", "link")
    const val VCACHE_MAX = 3
    private const val VMAX_BYTES = 120_000_000L

    fun lowRam(c: Context): Boolean = runCatching {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        am.isLowRamDevice || mi.totalMem <= 1_150_000_000L
    }.getOrDefault(false)

    /** Потолок высоты видео: 1080, на слабых устройствах 720. */
    fun videoCap(c: Context) = if (lowRam(c)) 720 else 1080

    /** Видео не запускается при включённой экономии батареи и при низком заряде (15% и меньше, не на зарядке): вместо него показывается кадр. */
    fun videoAllowed(c: Context): Boolean = runCatching {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isPowerSaveMode) return@runCatching false
        val b: Intent? = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (b != null && b.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) {
            val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            if (level <= 15 && !charging) return@runCatching false
        }
        true
    }.getOrDefault(true)

    fun saverVideoFile(ctx: Context) = File(ctx.filesDir, "saver_bg_video")
    fun saverVideoOn(ctx: Context) = bool(ctx, "saver_vid", false) && saverVideoFile(ctx).exists()
    fun videoCacheDir(ctx: Context) = File(ctx.cacheDir, "wallvid").apply { mkdirs() }
    fun videoCacheCount(ctx: Context) = videoCacheDir(ctx).listFiles()?.count { it.isFile } ?: 0

    /** Кадр из видео (для предпросмотра и на случай, когда видео не запускается). */
    fun videoStill(path: String): Bitmap? {
        val r = MediaMetadataRetriever()
        return try { r.setDataSource(path); r.getFrameAtTime(1_000_000L).also { } ?: r.getFrameAtTime(0L) } catch (_: Throwable) { null } finally { runCatching { r.release() } }
    }

    fun linkPic(url: String): WallPic {
        val host = runCatching { URL(url).host }.getOrDefault("")
        return WallPic("link", host, host, "", url, url).also { it.video = true }
    }

    private fun vpic(src: String, title: String, author: String, license: String, url: String, page: String, extra: String = "") =
        WallPic(src, title, author, license, url, page, extra).also { it.video = true }

    private fun commonsVideos(cat: String, portrait: Boolean, cap: Int): List<WallPic> {
        val q = enc("filetype:video " + term("commons", cat) + (if (portrait) " vertical" else ""))
        val j = JSONObject(http("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=20&gsroffset=" + rnd.nextInt(40) +
            "&gsrsearch=$q&prop=videoinfo&viprop=url%7Csize%7Cmime%7Cderivatives%7Cextmetadata%7Cduration&viextmetadatafilter=Artist%7CLicenseShortName"))
        val pages = j.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val out = ArrayList<WallPic>()
        for (k in pages.keys()) {
            val p = pages.getJSONObject(k)
            val vi = p.optJSONArray("videoinfo")?.optJSONObject(0) ?: continue
            val w = vi.optInt("width"); val h = vi.optInt("height"); val dur = vi.optDouble("duration", 0.0)
            if (w <= 0 || h <= 0 || dur < 4 || dur > 600) continue
            if (portrait && h < w) continue
            if (!portrait && w.toFloat() / h < 1.3f) continue
            // готовые уменьшенные версии Commons: webm (VP9); берётся самая большая в пределах потолка по высоте (для портрета по длинной стороне)
            val ds = vi.optJSONArray("derivatives") ?: continue
            var best: JSONObject? = null; var bestH = 0
            for (i in 0 until ds.length()) {
                val d = ds.getJSONObject(i)
                if (!d.str("type").startsWith("video/webm")) continue
                val long = maxOf(d.optInt("width"), d.optInt("height"))
                if (long <= (cap * 16 / 9) + 2 && long > bestH) { best = d; bestH = long }
            }
            val b = best ?: continue
            val em = vi.optJSONObject("extmetadata")
            out.add(vpic("commons", p.str("title").removePrefix("File:").substringBeforeLast('.'), strip(em?.optJSONObject("Artist")?.str("value").orEmpty()).take(60),
                strip(em?.optJSONObject("LicenseShortName")?.str("value").orEmpty()), b.str("src").replace("http://", "https://"), vi.str("descriptionurl")))
        }
        return out
    }

    private fun nasaVideos(cat: String): List<WallPic> {
        val j = JSONObject(http("https://images-api.nasa.gov/search?media_type=video&page_size=20&q=" + enc(term("nasa", cat).let { if (cat == "city") "earth at night" else if (cat == "nature") "earth from orbit" else it }) + "&page=" + (1 + rnd.nextInt(3))))
        val items = j.optJSONObject("collection")?.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<WallPic>()
        for (i in 0 until items.length()) {
            val d = items.getJSONObject(i).optJSONArray("data")?.optJSONObject(0) ?: continue
            val id = d.str("nasa_id"); if (id.isEmpty()) continue
            out.add(vpic("nasa", d.str("title"), d.str("photographer").ifEmpty { "NASA" }.take(60), "public domain", "", "https://images.nasa.gov/details/" + enc(id), id))
        }
        return out
    }

    /** Адрес mp4 из Библиотеки NASA: средний размер (до 720p), иначе меньше. */
    private fun nasaVideoResolve(id: String): String {
        val items = JSONObject(http("https://images-api.nasa.gov/asset/" + enc(id))).optJSONObject("collection")?.optJSONArray("items") ?: throw WallErr("none")
        val urls = (0 until items.length()).map { items.getJSONObject(it).str("href").replace("http://", "https://") }
        for (tag in listOf("medium", "small", "mobile")) urls.firstOrNull { it.contains("~$tag.mp4") }?.let { return it }
        throw WallErr("none")
    }

    private fun pexelsVideos(cat: String, portrait: Boolean, key: String, cap: Int): List<WallPic> {
        val arr = JSONObject(http("https://api.pexels.com/videos/search?size=medium&per_page=15&page=" + (1 + rnd.nextInt(6)) + "&orientation=" + (if (portrait) "portrait" else "landscape") + "&query=" + enc(term("pexels", cat)), mapOf("Authorization" to key))).optJSONArray("videos") ?: return emptyList()
        val out = ArrayList<WallPic>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val fs = o.optJSONArray("video_files") ?: continue
            var best: JSONObject? = null; var bestH = 0
            for (k in 0 until fs.length()) {
                val f = fs.getJSONObject(k)
                val long = maxOf(f.optInt("width"), f.optInt("height"))
                if (f.str("file_type") == "video/mp4" && long <= cap * 16 / 9 + 2 && long > bestH) { best = f; bestH = long }
            }
            val b = best ?: continue
            out.add(vpic("pexels", "", o.optJSONObject("user")?.str("name").orEmpty().take(60), "Pexels License", b.str("link"), o.str("url")))
        }
        return out
    }

    private fun pixabayVideos(cat: String, portrait: Boolean, key: String, cap: Int): List<WallPic> {
        val arr = JSONObject(http("https://pixabay.com/api/videos/?key=" + enc(key) + "&per_page=20&page=" + (1 + rnd.nextInt(4)) + "&q=" + enc(term("pexels", cat)))).optJSONArray("hits") ?: return emptyList()
        val out = ArrayList<WallPic>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val vs = o.optJSONObject("videos") ?: continue
            var best: JSONObject? = null; var bestH = 0
            for (tag in listOf("large", "medium", "small", "tiny")) {
                val v = vs.optJSONObject(tag) ?: continue
                val w = v.optInt("width"); val h = v.optInt("height")
                if (w <= 0 || h <= 0 || v.str("url").isEmpty()) continue
                if (portrait != (h > w)) continue
                if (maxOf(w, h) <= cap * 16 / 9 + 2 && maxOf(w, h) > bestH) { best = v; bestH = maxOf(w, h) }
            }
            val b = best ?: continue
            out.add(vpic("pixabay", o.str("tags"), o.str("user").take(60), "Pixabay Content License", b.str("url"), o.str("pageURL")))
        }
        return out
    }

    fun pickVideo(ctx: Context, src: String, cat: String, portrait: Boolean): WallPic {
        val cap = videoCap(ctx)
        val l = when (src) {
            "commons" -> commonsVideos(cat, portrait, cap)
            "nasa" -> nasaVideos(cat)
            "pexels" -> key(ctx, "pexels").let { if (it.isEmpty()) throw WallErr("nokey") else pexelsVideos(cat, portrait, it, cap) }
            "pixabay" -> key(ctx, "pixabay").let { if (it.isEmpty()) throw WallErr("nokey") else pixabayVideos(cat, portrait, it, cap) }
            else -> emptyList()
        }
        if (l.isEmpty()) throw WallErr("none")
        return l[rnd.nextInt(l.size)]
    }

    /** Скачивает видео в кэш (до 3 файлов, файл не больше 120 МБ). */
    fun downloadVideo(ctx: Context, p: WallPic): File {
        val url = if (p.url.isNotEmpty()) p.url else if (p.source == "nasa") nasaVideoResolve(p.extra) else throw WallErr("none")
        if (!url.startsWith("http")) throw WallErr("bad")
        val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(16) + ".vid"
        val dir = videoCacheDir(ctx)
        val f = File(dir, name)
        if (f.isFile && f.length() > 50_000) { f.setLastModified(System.currentTimeMillis()); return f }
        val tmp = File(dir, "$name.part")
        try {
            val c = open(url, emptyMap())
            try {
                check(c)
                if (c.contentLengthLong > VMAX_BYTES) throw WallErr("big")
                var total = 0L
                c.inputStream.use { i -> tmp.outputStream().use { o ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        total += n; if (total > VMAX_BYTES) throw WallErr("big")
                        o.write(buf, 0, n)
                    }
                } }
            } finally { c.disconnect() }
        } catch (e: WallErr) { tmp.delete(); throw e } catch (e: Exception) { tmp.delete(); NetErr.set(e); throw WallErr("net") }
        // проверка: файл должен открываться как видео
        val still = videoStill(tmp.path)
        if (still == null) { tmp.delete(); throw WallErr("bad") }
        f.delete()
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        p.portrait = still.height > still.width
        videoCacheDir(ctx).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.drop(VCACHE_MAX)?.forEach { it.delete() }
        return f
    }

    fun clearVideoCache(ctx: Context): Int { var n = 0; videoCacheDir(ctx).listFiles()?.forEach { if (it.delete()) n++ }; return n }

    /** Ставит видео на поверхность. Вызывать не в главном потоке. */
    fun applyVideoTo(ctx: Context, target: String, file: File, pic: WallPic): Boolean {
        if (target == "saver") {
            file.copyTo(saverVideoFile(ctx), overwrite = true)
            put(ctx, "saver_vid", true)
            setCredit(ctx, "saver_v", pic, saverVideoFile(ctx))
            return true
        }
        val menu = target == "menu"
        if (!HomeBgFiles.saveMedia(ctx, Uri.fromFile(file), menu)) return false
        setCredit(ctx, target + "_v", pic, HomeBgFiles.media(ctx, menu))
        Handler(Looper.getMainLooper()).post {
            if (menu) { if (HomeStore.menuBg.type != "video") HomeStore.changeMenuBg(HomeStore.menuBg.copy(type = "video")) }
            else if (HomeStore.layout.bg.type != "video") HomeStore.setBg(HomeStore.layout.bg.copy(type = "video"))
            HomeStore.bumpImg()
        }
        return true
    }

    /** Включено ли сейчас видео на поверхности (для предупреждения при включении). */
    fun videoActive(ctx: Context, target: String): Boolean = when (target) {
        "saver" -> saverVideoOn(ctx)
        "menu" -> HomeStore.menuBg.type == "video"
        else -> HomeStore.layout.bg.type == "video"
    }

    // ---------- автоматическая смена ----------
    private val running = AtomicBoolean(false)

    private fun unmetered(ctx: Context): Boolean = runCatching {
        !(ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    }.getOrDefault(true)

    /**
     * Раз в сутки по выбранному режиму («картинка дня» или «случайная») меняет фон меню и «Дома» (и картинку заставки, если она включена).
     * Вызывается при запуске меню и «Дома»; сеть только по Wi-Fi/кабелю, если так выбрано; при неудаче повтор не чаще раза в 3 часа.
     */
    fun autoRefresh(ctx0: Context) {
        val ctx = ctx0.applicationContext
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                for (target in SURFACES) {
                    val mode = str(ctx, "mode_$target", "off")
                    if (mode == "off" || str(ctx, "last_$target") == today) continue
                    if (target == "saver" && !saverOn(ctx)) continue
                    val now = System.currentTimeMillis()
                    if (now - sp(ctx).getLong("try_$target", 0L) < 3 * 3600_000L) continue
                    if (bool(ctx, "wifi_only", true) && !unmetered(ctx)) continue
                    sp(ctx).edit().putLong("try_$target", now).apply()
                    try {
                        // «Авто»: и альбомная, и портретная картинка (телефон при повороте берёт нужную из памяти); иначе только выбранная ориентация
                        val sides = when (cfg(ctx, target, "orient", "auto")) { "port" -> listOf(true); "land" -> listOf(false); else -> if (target == "saver") listOf(wantPortrait(ctx)) else listOf(false, true) }
                        var last: WallPic? = null
                        for (portrait in sides) {
                            val pc = pick(ctx, cfg(ctx, target, "src", "commons"), cfg(ctx, target, "cat", "nature"), mode == "day", portrait)
                            val f = download(ctx, pc)
                            if (!applyTo(ctx, target, f, pc)) throw WallErr("bad")
                            last = pc
                        }
                        put(ctx, "last_$target", today)
                        android.util.Log.i("TVApps", "обои: $target обновлён (${last?.source})")
                    } catch (e: Throwable) {
                        android.util.Log.w("TVApps", "обои: $target: " + e.javaClass.simpleName + " " + ((e as? WallErr)?.kind ?: e.message))
                    }
                }
            } finally { running.set(false) }
        }.start()
    }
}
