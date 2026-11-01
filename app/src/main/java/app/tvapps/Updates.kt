package app.tvapps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Найденная версия приложения в одном источнике. versionCode 0 — источник его не сообщает (сравнивается название). */
data class UpdCand(
    val source: String, val versionName: String, val versionCode: Long, val urls: List<String>,
    val size: Long = 0, val md5: String = "", val note: String = "", val manual: Boolean = false,
)

/** Результат проверки одного приложения по всем источникам. */
data class AppUpd(val pkg: String, val label: String, val instName: String, val instCode: Long, val cands: List<UpdCand>, val errors: List<String>) {
    val newer: List<UpdCand> get() = cands.filter { Updates.isNewer(it, instName, instCode) }
    /** Лучшее обновление, которое можно поставить само (не пакет с OBB/bundle): новее всех, при равенстве важнее надёжный источник. */
    val best: UpdCand? get() = newer.filter { !it.manual }.sortedWith(
        Comparator<UpdCand> { a, b -> Updates.compareVersions(b.versionName, a.versionName) }.thenBy { Updates.sourceRank(it.source) },
    ).firstOrNull()
    val hasUpdate: Boolean get() = newer.isNotEmpty()
}

/**
 * Проверка и установка обновлений (по мотивам APKUpdater): F-Droid, IzzyOnDroid, Aptoide, релизы GitHub и GitLab, APKPure (по желанию).
 * Сеть только по запросу. Перед установкой сверяются подпись, процессор и размер файла; установка тихая (root/ADB) или через окно системы.
 */
object Updates {
    private const val UA = "TVApps/1.2 (Android)"
    private const val CACHE_MS = 6 * 3600_000L
    private val cacheLock = Any()

    /** Все источники по порядку надёжности; apkpure — неофициальный API, по умолчанию выключен. */
    val ALL_SOURCES = listOf("fdroid", "izzy", "github", "gitlab", "codeberg", "aptoide", "apkpure", "appgallery", "rustore")
    val FRAGILE = setOf("apkpure", "appgallery", "rustore")
    /** Источники, которые я не смогла проверить на живом сервере (RuStore с моей сети не отвечал). */
    val UNVERIFIED = setOf("rustore")
    fun sourceRank(s: String) = ALL_SOURCES.indexOf(s).let { if (it < 0) ALL_SOURCES.size else it }
    fun sourceName(ctx: Context, s: String): String = ctx.getString(
        when (s) {
            "fdroid" -> R.string.upd_src_fdroid; "izzy" -> R.string.upd_src_izzy; "github" -> R.string.upd_src_github
            "gitlab" -> R.string.upd_src_gitlab; "codeberg" -> R.string.upd_src_codeberg; "apkpure" -> R.string.upd_src_apkpure
            "appgallery" -> R.string.upd_src_appgallery; "rustore" -> R.string.upd_src_rustore; else -> R.string.upd_src_aptoide
        },
    )

    // ---------------------------------------------------------------- сравнение версий

    private val PRE = listOf("dev", "alpha", "a", "beta", "b", "preview", "pre", "rc")

    /** Сравнение названий версий по частям: числа как числа, «rc/beta/alpha» ниже релиза. */
    fun compareVersions(a: String, b: String): Int {
        fun tok(s: String) = Regex("[0-9]+|[A-Za-z]+").findAll(s.lowercase().replaceFirst(Regex("^[^0-9]+(?=[0-9])"), "")).map { it.value }.toList()
        val x = tok(a); val y = tok(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrNull(i); val q = y.getOrNull(i)
            if (p == null && q == null) break
            val pn = p?.toBigIntegerOrNull(); val qn = q?.toBigIntegerOrNull()
            when {
                p == null -> { if (qn != null) { if (qn.signum() != 0) return -1 } else return if (q in PRE) 1 else -1 }
                q == null -> { if (pn != null) { if (pn.signum() != 0) return 1 } else return if (p in PRE) -1 else 1 }
                pn != null && qn != null -> { val c = pn.compareTo(qn); if (c != 0) return c }
                pn != null -> return 1
                qn != null -> return -1
                p != q -> {
                    val ip = PRE.indexOf(p); val iq = PRE.indexOf(q)
                    return if (ip >= 0 && iq >= 0) ip.compareTo(iq) else p.compareTo(q)
                }
            }
        }
        return 0
    }

    /** Новее ли найденная версия установленной. Код версии разных магазинов может не совпадать, поэтому код учитывается вместе с названием. */
    fun isNewer(c: UpdCand, instName: String, instCode: Long): Boolean {
        val cmp = compareVersions(c.versionName, instName)
        return if (c.versionCode > 0) c.versionCode > instCode && cmp >= 0 else cmp > 0
    }

    // ---------------------------------------------------------------- сеть

    private class Http(val code: Int, val body: String)

    private fun http(url: String, token: String? = null): Http {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        if (!token.isNullOrBlank() && url.startsWith("https://api.github.com/")) c.setRequestProperty("Authorization", "Bearer $token")
        return try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            Http(code, body)
        } finally { c.disconnect() }
    }

    private fun postJson(url: String, body: String, headers: Map<String, String>): Http {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 20_000
        c.requestMethod = "POST"
        c.doOutput = true
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.setRequestProperty("Content-Type", "application/json")
        return try {
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            Http(code, text)
        } finally { c.disconnect() }
    }

    /** Тело ответа; null — не найдено (404), ошибка — исключение. */
    private fun getOrNull(url: String, token: String? = null): String? {
        val r = http(url, token)
        if (r.code == 404) return null
        if (r.code !in 200..299) error("HTTP ${r.code}")
        return r.body
    }

    /** F-Droid и IzzyOnDroid отвечают в одном формате. Возвращает «рекомендуемую» версию (не пред-релиз). */
    private fun fdroidLike(pkg: String, source: String, api: String, repo: String): UpdCand? {
        val body = getOrNull(api + pkg) ?: return null
        val j = JSONObject(body)
        val suggested = j.optString("suggestedVersionCode").toLongOrNull() ?: 0L
        val list = j.optJSONArray("packages") ?: JSONArray()
        val all = (0 until list.length()).map { list.getJSONObject(it) }.mapNotNull { o ->
            val code = o.optString("versionCode").toLongOrNull() ?: return@mapNotNull null
            code to o.optString("versionName")
        }
        val pick = all.firstOrNull { it.first == suggested } ?: all.maxByOrNull { it.first } ?: return null
        // одна версия может быть выпущена несколькими файлами под разные процессоры: запасные адреса
        val same = all.filter { it.second == pick.second }.map { it.first }.distinct().sortedByDescending { it == pick.first }.take(5)
        return UpdCand(source, pick.second, pick.first, same.map { "$repo${pkg}_$it.apk" })
    }

    private fun aptoide(pkg: String): UpdCand? {
        val body = getOrNull("https://ws75.aptoide.com/api/7/app/getMeta?package_name=$pkg") ?: return null
        val d = JSONObject(body).optJSONObject("data") ?: return null
        val f = d.optJSONObject("file") ?: return null
        val path = f.optString("path").ifBlank { return null }
        val rank = f.optJSONObject("malware")?.optString("rank").orEmpty()
        if (rank.equals("CRITICAL", true)) return null
        val manual = d.optJSONObject("obb") != null || d.optJSONObject("aab") != null
        return UpdCand(
            "aptoide", f.optString("vername"), f.optLong("vercode"), listOf(path), f.optLong("filesize"),
            md5 = f.optString("md5sum"), note = if (rank.isNotBlank() && !rank.equals("TRUSTED", true)) rank else "", manual = manual,
        )
    }

    /** Справочник «пакет → репозитории GitHub» (assets/github_apps.json: данные проекта APKUpdater, GPL-3.0, 377 приложений). */
    private var ghMap: JSONObject? = null

    private fun ghKnown(ctx: Context, pkg: String): List<Triple<String, String, String?>> {
        val m = synchronized(this) {
            ghMap ?: runCatching { JSONObject(ctx.assets.open("github_apps.json").bufferedReader().use { it.readText() }) }.getOrDefault(JSONObject()).also { ghMap = it }
        }
        val a = m.optJSONArray(pkg) ?: return emptyList()
        return (0 until a.length()).map { a.getJSONArray(it) }.map { Triple(it.getString(0), it.getString(1), it.optString(2).ifBlank { null }) }
    }

    /** Репозитории исходников (хост, путь) из описания приложения в каталогах F-Droid и IzzyOnDroid. */
    private fun sourceRepos(pkg: String): List<Pair<String, String>> {
        val urls = listOf(
            "https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/$pkg.yml",
            "https://gitlab.com/IzzyOnDroid/repo/-/raw/master/metadata/$pkg.yml",
        )
        val rx = Regex("(github\\.com|gitlab\\.com|codeberg\\.org)/([\\w.-]+(?:/[\\w.-]+)*)")
        val out = LinkedHashSet<Pair<String, String>>()
        for (u in urls) {
            val text = runCatching { getOrNull(u) }.getOrNull() ?: continue
            for (line in text.lineSequence()) {
                if (!(line.startsWith("SourceCode:") || line.startsWith("Repo:") || line.startsWith("WebSite:"))) continue
                val m = rx.find(line) ?: continue
                var path = m.groupValues[2].removeSuffix(".git").trimEnd('/')
                path = path.substringBefore("/-/").substringBefore("/tree/").substringBefore("/blob/")
                // GitHub: «владелец/репозиторий»; GitLab допускает подгруппы
                if (m.groupValues[1] == "github.com" || m.groupValues[1] == "codeberg.org") path = path.split('/').take(2).joinToString("/")
                if (path.contains('/')) out.add(m.groupValues[1].substringBefore('.') to path)
            }
        }
        return out.toList()
    }

    /** Выбор APK под процессор устройства: сначала сборки под него или универсальные, потом остальные. */
    private fun <T> sortByAbi(items: List<T>, name: (T) -> String): List<T> {
        val abis = Build.SUPPORTED_ABIS.map { it.lowercase() }
        return items.sortedBy { a ->
            val n = name(a).lowercase()
            when {
                abis.any { n.contains(it) } -> 0
                n.contains("universal") -> 1
                listOf("arm64", "armeabi", "x86", "x64").none { n.contains(it) } -> 2
                else -> 3
            }
        }
    }

    /** Варианты сборки, которые заведомо не подходят для обычного обновления. */
    private fun variantOk(name: String): Boolean = listOf("preload", "-hw", "huawei", "debug", "unsigned").none { name.lowercase().contains(it) }

    private fun extraOk(name: String, extra: String?): Boolean = variantOk(name) && when {
        extra == null -> true
        extra.startsWith("!") -> !name.contains(extra.substring(1), true)
        else -> name.contains(extra, true)
    }

    private fun github(ctx: Context, pkg: String, token: String, fromCatalog: Boolean): UpdCand? {
        val repos = ghKnown(ctx, pkg).ifEmpty {
            if (!fromCatalog) emptyList() else sourceRepos(pkg).filter { it.first == "github" }.map { Triple(it.second.substringBefore('/'), it.second.substringAfter('/'), null) }
        }
        var best: UpdCand? = null
        for ((user, repo, extra) in repos) {
            // с фильтром по названию файла нужен не «latest», а список выпусков
            val j = if (extra == null) {
                JSONObject(getOrNull("https://api.github.com/repos/$user/$repo/releases/latest", token) ?: continue)
            } else {
                val arr = JSONArray(getOrNull("https://api.github.com/repos/$user/$repo/releases?per_page=20", token) ?: continue)
                (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { r ->
                    !r.optBoolean("prerelease") && !r.optBoolean("draft") && (r.optJSONArray("assets") ?: JSONArray()).let { a ->
                        (0 until a.length()).any { a.getJSONObject(it).optString("name").lowercase().endsWith(".apk") && extraOk(a.getJSONObject(it).optString("name"), extra) }
                    }
                } ?: continue
            }
            val name = j.optString("tag_name").replaceFirst(Regex("^[^0-9]*"), "")
            if (name.isBlank()) continue
            val assets = j.optJSONArray("assets") ?: JSONArray()
            val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.optString("name").lowercase().endsWith(".apk") && extraOk(it.optString("name"), extra) }
            if (apks.isEmpty()) continue
            val sorted = sortByAbi(apks) { it.optString("name") }
            val cand = UpdCand("github", name, 0, sorted.take(4).map { it.getString("browser_download_url") }, sorted.first().optLong("size"), note = "$user/$repo")
            if (best == null || compareVersions(cand.versionName, best.versionName) > 0) best = cand
        }
        return best
    }

    /** Релизы GitLab (публичный API без ключа): APK из ссылок выпуска или из описания (Aurora Store кладёт их туда). */
    private fun gitlab(pkg: String, fromCatalog: Boolean): UpdCand? {
        val repos = (if (pkg == "com.aurora.store") listOf("gitlab" to "AuroraOSS/AuroraStore") else emptyList()) +
            (if (fromCatalog) sourceRepos(pkg).filter { it.first == "gitlab" } else emptyList())
        for ((_, path) in repos.distinct()) {
            val enc = java.net.URLEncoder.encode(path, "UTF-8")
            val arr = JSONArray(getOrNull("https://gitlab.com/api/v4/projects/$enc/releases?per_page=5") ?: continue)
            val r = (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull() ?: continue
            val name = r.optString("tag_name").replaceFirst(Regex("^[^0-9]*"), "")
            if (name.isBlank()) continue
            val links = r.optJSONObject("assets")?.optJSONArray("links") ?: JSONArray()
            val fromLinks = (0 until links.length()).map { links.getJSONObject(it) }
                .filter { (it.optString("name").lowercase().endsWith(".apk") || it.optString("url").lowercase().endsWith(".apk")) && variantOk(it.optString("name").ifBlank { it.optString("url") }) }
                .map { it.optString("url") to it.optString("name") }
            var urls = sortByAbi(fromLinks) { it.second.ifBlank { it.first } }.map { it.first }
            if (urls.isEmpty()) {
                // как у Aurora Store: APK лежат в описании выпуска ссылками /uploads/<хеш>/<файл>.apk
                val rel = Regex("\\(([^)\\s]+\\.apk)\\)", RegexOption.IGNORE_CASE).findAll(r.optString("description")).map { it.groupValues[1] }.toList()
                urls = sortByAbi(rel.filter { variantOk(it) }) { it }.map { u ->
                    if (u.startsWith("http", true)) u
                    else if (u.startsWith("/uploads/")) {
                        val id = runCatching { JSONObject(getOrNull("https://gitlab.com/api/v4/projects/$enc") ?: "{}").optLong("id") }.getOrDefault(0L)
                        if (id > 0) "https://gitlab.com/-/project/$id$u" else ""
                    } else ""
                }.filter { it.isNotBlank() }
            }
            if (urls.isEmpty()) continue
            return UpdCand("gitlab", name, 0, urls.take(4), note = path)
        }
        return null
    }

    /**
     * Релизы Codeberg (Forgejo/Gitea, публичный API без ключа): репозиторий берётся из метаданных F-Droid/IzzyOnDroid.
     * Запрос один на приложение и только по действию пользователя; пред-релизы и черновики пропускаются.
     */
    private fun codeberg(pkg: String, fromCatalog: Boolean): UpdCand? {
        if (!fromCatalog) return null
        for ((_, path) in sourceRepos(pkg).filter { it.first == "codeberg" }.distinct()) {
            val arr = JSONArray(getOrNull("https://codeberg.org/api/v1/repos/$path/releases?limit=5") ?: continue)
            val r = (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { x ->
                !x.optBoolean("prerelease") && !x.optBoolean("draft") && (x.optJSONArray("assets") ?: JSONArray()).let { a ->
                    (0 until a.length()).any { a.getJSONObject(it).optString("name").lowercase().endsWith(".apk") }
                }
            } ?: continue
            val name = r.optString("tag_name").replaceFirst(Regex("^[^0-9]*"), "")
            if (name.isBlank()) continue
            val assets = r.getJSONArray("assets")
            val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.optString("name").lowercase().endsWith(".apk") && variantOk(it.optString("name")) }
            if (apks.isEmpty()) continue
            val sorted = sortByAbi(apks) { it.optString("name") }
            return UpdCand("codeberg", name, 0, sorted.take(4).map { it.getString("browser_download_url") }, sorted.first().optLong("size"), note = path)
        }
        return null
    }

    /**
     * APKPure: неофициальный (закрытый) API приложения APKPure, поэтому источник выключен по умолчанию и может перестать работать.
     * Запрос «обновление для пакета с versionCode N» вернёт запись только если у них версия новее.
     */
    private fun apkpure(pkg: String, instCode: Long): UpdCand? {
        if (instCode > Int.MAX_VALUE) return null   // их сервер читает код версии как 32-битное число
        fun rnd() = java.lang.Long.toHexString(java.util.Random().nextLong())
        val dm = android.content.res.Resources.getSystem().displayMetrics
        val dev = JSONObject().put("device_info", JSONObject()
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("android_id", rnd()).put("os_ver", Build.VERSION.SDK_INT.toString())
            .put("os_ver_name", Build.VERSION.RELEASE).put("platform", 1)
            .put("screen_height", dm.heightPixels).put("screen_width", dm.widthPixels))
        val body = JSONObject().put("app_info_for_update", JSONArray().put(JSONObject().put("package_name", pkg).put("version_code", instCode)
            .put("is_system", false).put("version_id", "").put("cached_size", -1)))
            .put("android_id", rnd()).put("application_id", "com.apkpure.aegon").put("cached_size", -1).toString()
        val lang = java.util.Locale.getDefault().toLanguageTag()
        val headers = mapOf("ual-access-businessid" to "projecta", "ual-access-projecta" to dev.toString(), "Accept-Language" to lang, "User-Agent" to "APKPure/3.19.0 (Linux; Android ${Build.VERSION.RELEASE})")
        val url = "https://tapi.pureapk.com/v3/get_app_update?hl=$lang"
        var r = postJson(url, body, headers)
        if (r.code == 555) { Thread.sleep(2000); r = postJson(url, body, headers) }   // их ограничение частоты запросов
        if (r.code !in 200..299) error("HTTP ${r.code}")
        val arr = JSONObject(r.body).optJSONArray("app_update_response") ?: return null
        val o = (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("package_name") == pkg } ?: return null
        val asset = o.optJSONObject("asset") ?: return null
        val u = asset.optString("url").replace("http://", "https://").ifBlank { return null }
        val bundle = asset.optString("type").equals("XAPK", true) || u.contains("/XAPK")
        return UpdCand("apkpure", o.optString("version_name"), o.optLong("version_code"), listOf(u), asset.optLong("size"), manual = bundle)
    }

    // ---------------------------------------------------------------- проверка

    /** Набор включённых источников: после его смены кеш старой проверки не используется. */
    private fun srcKey() = ALL_SOURCES.filter { it in Cfg.updSources }.joinToString(",")

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "upd_cache.json")

    private fun candToJson(c: UpdCand) = JSONObject().put("s", c.source).put("n", c.versionName).put("c", c.versionCode)
        .put("u", JSONArray(c.urls)).put("z", c.size).put("m", c.md5).put("o", c.note).put("x", c.manual)

    private fun candFromJson(o: JSONObject) = UpdCand(
        o.getString("s"), o.optString("n"), o.optLong("c"), (0 until o.getJSONArray("u").length()).map { o.getJSONArray("u").getString(it) },
        o.optLong("z"), o.optString("m"), o.optString("o"), o.optBoolean("x"),
    )

    private fun readCache(ctx: Context, pkg: String, instCode: Long): List<UpdCand>? = synchronized(cacheLock) {
        runCatching {
            val e = JSONObject(cacheFile(ctx).readText()).optJSONObject(pkg) ?: return@runCatching null
            if (System.currentTimeMillis() - e.optLong("t") > CACHE_MS || e.optLong("ic") != instCode || e.optString("ss") != srcKey()) return@runCatching null
            val a = e.getJSONArray("c")
            (0 until a.length()).map { candFromJson(a.getJSONObject(it)) }
        }.getOrNull()
    }

    private fun writeCache(ctx: Context, pkg: String, instCode: Long, cands: List<UpdCand>) = synchronized(cacheLock) {
        runCatching {
            val f = cacheFile(ctx)
            val root = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
            root.put(pkg, JSONObject().put("t", System.currentTimeMillis()).put("ic", instCode).put("ss", srcKey()).put("c", JSONArray(cands.map { candToJson(it) })))
            f.writeText(root.toString())
        }
    }

    fun clearCache(ctx: Context) = synchronized(cacheLock) { runCatching { cacheFile(ctx).delete() } }

    private fun versionCodeOf(pi: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()

    /** Проверяет одно приложение по всем источникам. Ошибка одного источника не мешает остальным. */
    suspend fun check(ctx: Context, pkg: String, force: Boolean = false): AppUpd = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val pi = pm.getPackageInfo(pkg, 0)
        val label = pi.applicationInfo?.let { pm.getApplicationLabel(it).toString() } ?: pkg
        val instName = pi.versionName ?: "0"
        val instCode = versionCodeOf(pi)
        if (!force) readCache(ctx, pkg, instCode)?.let { return@withContext AppUpd(pkg, label, instName, instCode, it, emptyList()) }
        val errors = ArrayList<String>()
        fun <T> guard(name: String, f: () -> T?): T? = try { f() } catch (e: Exception) { synchronized(errors) { errors.add("$name: ${e.message ?: e.javaClass.simpleName}") }; null }
        val token = Cfg.ghToken
        val on = Cfg.updSources
        val cands = coroutineScope {
            val fd = async { if ("fdroid" in on) guard("F-Droid") { fdroidLike(pkg, "fdroid", "https://f-droid.org/api/v1/packages/", "https://f-droid.org/repo/") } else null }
            val iz = async { if ("izzy" in on) guard("IzzyOnDroid") { fdroidLike(pkg, "izzy", "https://apt.izzysoft.de/fdroid/api/v1/packages/", "https://apt.izzysoft.de/fdroid/repo/") } else null }
            val ap = async { if ("aptoide" in on) guard("Aptoide") { aptoide(pkg) } else null }
            val pure = async { if ("apkpure" in on) guard("APKPure") { apkpure(pkg, instCode) } else null }
            val agal = async { if ("appgallery" in on) guard("AppGallery") { UpdStores.appGallery(ctx, pkg, instCode) } else null }
            val rust = async { if ("rustore" in on) guard("RuStore") { UpdStores.ruStore(pkg, instCode) } else null }
            val f = fd.await(); val i = iz.await()
            // репозиторий исходников ищется в метаданных каталогов F-Droid и IzzyOnDroid (если приложение в них есть) или в справочнике
            val cat = f != null || i != null
            val gh = if ("github" in on) guard("GitHub") { github(ctx, pkg, token, cat) } else null
            val gl = if ("gitlab" in on) guard("GitLab") { gitlab(pkg, cat) } else null
            val cb = if ("codeberg" in on) guard("Codeberg") { codeberg(pkg, cat) } else null
            listOfNotNull(f, i, gh, gl, cb, ap.await(), pure.await(), agal.await(), rust.await())
        }
        // кешируем только полный ответ: после ошибки сети повторная проверка должна сходить в сеть снова
        if (errors.isEmpty()) writeCache(ctx, pkg, instCode, cands)
        AppUpd(pkg, label, instName, instCode, cands, errors)
    }

    /** Проверяет все установленные пользовательские приложения (по 4 одновременно). */
    suspend fun checkAll(ctx: Context, force: Boolean, onProgress: (Int, Int) -> Unit): List<AppUpd> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val pkgs = pm.getInstalledApplications(0).filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }.map { it.packageName }.distinct()
        val sem = Semaphore(4)
        var done = 0
        onProgress(0, pkgs.size)
        coroutineScope {
            pkgs.map { p ->
                async {
                    val r = sem.withPermit { runCatching { check(ctx, p, force) }.getOrNull() }
                    synchronized(this@Updates) { done++; onProgress(done, pkgs.size) }
                    r
                }
            }.map { it.await() }.filterNotNull()
        }.sortedBy { it.label.lowercase() }
    }

    // ---------------------------------------------------------------- проверки файла и установка

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    private fun certs(pi: PackageInfo?): Set<String> {
        pi ?: return emptySet()
        val out = HashSet<String>()
        if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo
            if (si != null) {
                si.apkContentsSigners?.forEach { out.add(sha256(it.toByteArray())) }
                if (!si.hasMultipleSigners()) si.signingCertificateHistory?.forEach { out.add(sha256(it.toByteArray())) }
            }
        } else pi.signatures?.forEach { out.add(sha256(it.toByteArray())) }
        return out
    }

    @Suppress("DEPRECATION")
    private val sigFlags get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    /** Есть ли в APK машинный код под процессор устройства (нет библиотек вообще — подходит любой). */
    private fun abiOk(apk: File): Boolean = runCatching {
        ZipFile(apk).use { z ->
            val abis = z.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") }.map { it.removePrefix("lib/").substringBefore('/') }.toSet()
            if (abis.isEmpty()) return@use true
            val mine = Build.SUPPORTED_ABIS.map { it.lowercase() }
            abis.any { a -> mine.any { it == a.lowercase() || (it.startsWith("armeabi") && a.lowercase().startsWith("armeabi")) } }
        }
    }.getOrDefault(true)

    private fun md5(f: File): String {
        val d = MessageDigest.getInstance("MD5")
        f.inputStream().use { i -> val buf = ByteArray(64 * 1024); while (true) { val n = i.read(buf); if (n < 0) break; d.update(buf, 0, n) } }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Скачивает и ставит обновление. Возвращает "ok", "system", "need_adb", "fail:<причина>" либо код проверки:
     * "sig" (подпись не совпала), "abi", "minsdk", "hash", "uptodate", "manual", "net".
     */
    suspend fun install(ctx: Context, app: AppUpd, cand: UpdCand, onStatus: (String) -> Unit): String = withContext(Dispatchers.IO) {
        if (cand.manual) return@withContext "manual"
        val pm = ctx.packageManager
        val inst = runCatching { pm.getPackageInfo(app.pkg, sigFlags) }.getOrNull() ?: return@withContext "fail:not installed"
        val instCerts = certs(inst)
        var verdict = "net"
        for (url in cand.urls) {
            val name = cand.versionName
            val file = NetSearch.download(ctx, url) { mb -> onStatus(ctx.getString(R.string.net_downloading, name, mb.toInt())) }?.file ?: continue
            try {
                val ai = pm.getPackageArchiveInfo(file.path, sigFlags)
                if (ai == null || ai.packageName != app.pkg) { verdict = "fail:" + ctx.getString(R.string.ver_not_apk); continue }
                if (cand.md5.isNotBlank() && !md5(file).equals(cand.md5, true)) { verdict = "hash"; continue }
                if (!abiOk(file)) { verdict = "abi"; continue }
                if (Build.VERSION.SDK_INT >= 24 && (ai.applicationInfo?.minSdkVersion ?: 0) > Build.VERSION.SDK_INT) { verdict = "minsdk"; continue }
                val newCode = versionCodeOf(ai)
                if (newCode <= app.instCode) { verdict = "uptodate"; continue }
                val newCerts = certs(ai)
                if (instCerts.isEmpty() || newCerts.isEmpty() || newCerts.intersect(instCerts).isEmpty()) return@withContext "sig"
                // копия прежней версии (если включено): к ней можно вернуться через «Версии»
                if (Cfg.backupOnDelete) runCatching { AppBackups.save(ctx, app.pkg) }
                val copy = AppBackups.importApk(ctx, file) ?: return@withContext "fail:" + ctx.getString(R.string.ver_not_apk)
                return@withContext AppBackups.installFiles(ctx, app.pkg, copy.files, copy.versionCode)
            } finally {
                // скачанный файл больше не нужен: установка идёт из копии в AppMenuBackups
                runCatching { file.delete() }
            }
        }
        verdict
    }
}
