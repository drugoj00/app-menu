package app.tvapps

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Результат поиска приложения в сети. */
data class NetApp(val name: String, val summary: String, val source: String, val id: String)

/**
 * Поиск и скачивание APK из открытых каталогов: F-Droid (свободные приложения) и релизы на GitHub.
 * Плюс скачивание по прямой ссылке. Неофициальные зеркала платных программ не используются.
 */
object NetSearch {
    private const val UA = "TVApps/1.0 (Android)"

    /** Ошибка каталога с понятным видом: rate (лимит запросов GitHub), token (токен не принят), http (другой код ответа). */
    class NetSearchError(val kind: String, val code: Int = 0, msg: String = "") : Exception(msg)

    private fun get(url: String, accept: String = "application/json"): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 25_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", if (url.startsWith("https://api.github.com/")) "application/vnd.github+json" else accept)
        if (url.startsWith("https://api.github.com/") && Cfg.githubToken.isNotBlank()) c.setRequestProperty("Authorization", "Bearer " + Cfg.githubToken)
        return try {
            val code = c.responseCode
            if (code !in 200..299) {
                if (url.startsWith("https://api.github.com/")) {
                    val left = c.getHeaderField("X-RateLimit-Remaining")
                    if (code == 429 || (code == 403 && (left == "0" || (runCatching { c.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty().contains("rate limit", true))))) throw NetSearchError("rate", code, "HTTP $code")
                    if (code == 401) throw NetSearchError("token", code, "HTTP 401")
                }
                throw NetSearchError("http", code, "HTTP $code")
            }
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Наши программы: по имени пакета сразу находится их репозиторий (в README и описаниях имени пакета нет, поиск GitHub по нему ничего не даёт). */
    private val knownPackages = mapOf(
        "app.tvapps" to "drugoj00/app-menu",
        "app.adbphon" to "drugoj00/adb-phon",
        "app.adbphon.clouds" to "drugoj00/adb-phon",
    )

    /** Вид запроса: имя пакета (app.tvapps), «владелец/репозиторий», готовый вид user:… или обычное слово/имя владельца. */
    private fun githubQueries(s: String): List<String> {
        val q = s.trim()
        val low = q.lowercase()
        val out = LinkedHashSet<String>()
        val owner = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")
        val pkg = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+\$")
        knownPackages[low]?.let { out += "repo:$it" }
        when {
            Regex("^(user|org|repo|topic|in|language|stars):").containsMatchIn(low) -> out += q
            q.contains('/') && !q.contains(' ') -> { out += "repo:$q"; out += "$q in:name" }
            pkg.matches(low) -> { out += "\"$q\" in:readme,description,name"; out += q.substringAfterLast('.') + " in:name"; out += "topic:" + q.replace('.', '-') }
            owner.matches(q) -> { out += "user:$q"; out += "$q in:name,description"; out += "topic:" + low }
            else -> { out += "$q in:name,description,readme" }
        }
        return out.toList()
    }

    suspend fun search(q: String, source: String): List<NetApp> = withContext(Dispatchers.IO) {
        val enc = URLEncoder.encode(q.trim(), "UTF-8")
        when (source) {
            "github" -> {
                val found = LinkedHashMap<String, NetApp>()
                var firstError: Exception? = null
                var okCount = 0
                for (qq in githubQueries(q)) {
                    try {
                        val e = URLEncoder.encode(qq, "UTF-8")
                        val items = JSONObject(get("https://api.github.com/search/repositories?q=$e&sort=stars&per_page=20")).optJSONArray("items") ?: JSONArray()
                        okCount++
                        for (k in 0 until items.length()) {
                            val it = items.getJSONObject(k)
                            val full = it.optString("full_name")
                            if (full.isBlank() || found.containsKey(full)) continue
                            val d = it.optString("description", "").takeIf { x -> x != "null" }.orEmpty()
                            val st = it.optInt("stargazers_count")
                            found[full] = NetApp(full, (if (st > 0) "★$st " else "") + d, "github", full)
                        }
                    } catch (e: NetSearchError) {
                        if (e.kind == "rate" || e.kind == "token") { if (found.isNotEmpty()) break else throw e }
                        if (firstError == null) firstError = e
                    } catch (e: Exception) {
                        if (firstError == null) firstError = e
                    }
                    if (found.size >= 30) break
                }
                if (found.isEmpty() && okCount == 0 && firstError != null) throw firstError
                found.values.toList()
            }
            else -> {
                val apps = JSONObject(get("https://search.f-droid.org/api/search_apps?q=$enc&lang=ru")).optJSONArray("apps") ?: JSONArray()
                (0 until apps.length()).map { apps.getJSONObject(it) }.mapNotNull {
                    val pkg = it.optString("url").trimEnd('/').substringAfterLast('/')
                    if (pkg.isBlank()) null else NetApp(it.optString("name"), it.optString("summary"), "fdroid", pkg)
                }
            }
        }
    }

    /** Адреса APK для выбранного приложения: сначала самый подходящий, дальше запасные (для других процессоров). */
    suspend fun apkUrls(app: NetApp): List<String> = withContext(Dispatchers.IO) {
        if (app.source == "github") {
            fun apksOf(rel: JSONObject): List<String> {
                val assets = rel.optJSONArray("assets") ?: JSONArray()
                return (0 until assets.length()).map { assets.getJSONObject(it) }
                    .filter { it.optString("name").lowercase().endsWith(".apk") }
                    .map { it.getString("browser_download_url") }
            }
            // сначала последний обычный релиз; если в нём нет APK (или релизов нет), смотрим последние релизы подряд
            var urls = runCatching { apksOf(JSONObject(get("https://api.github.com/repos/${app.id}/releases/latest"))) }.getOrDefault(emptyList())
            if (urls.isEmpty()) {
                val rels = runCatching { JSONArray(get("https://api.github.com/repos/${app.id}/releases?per_page=8")) }.getOrNull()
                if (rels != null) for (x in 0 until rels.length()) { val u = apksOf(rels.getJSONObject(x)); if (u.isNotEmpty()) { urls = u; break } }
            }
            val abis = Build.SUPPORTED_ABIS.map { it.lowercase() }
            // сначала файлы под процессор устройства или универсальные, потом остальные
            urls.sortedBy { u ->
                val n = u.lowercase()
                when {
                    abis.any { n.contains(it) } -> 0
                    n.contains("universal") -> 1
                    listOf("arm64", "armeabi", "x86").none { n.contains(it) } -> 2
                    else -> 3
                }
            }
        } else {
            val j = JSONObject(get("https://f-droid.org/api/v1/packages/${app.id}"))
            val suggested = j.optInt("suggestedVersionCode")
            val pk = j.optJSONArray("packages") ?: JSONArray()
            val all = (0 until pk.length()).map { pk.getJSONObject(it) }
            val name = all.firstOrNull { it.optInt("versionCode") == suggested }?.optString("versionName")
            // одна версия может выпускаться несколькими файлами под разные процессоры: пробуем по очереди
            val codes = all.filter { it.optString("versionName") == name }.map { it.optInt("versionCode") }.sortedByDescending { it == suggested }.take(5)
            codes.map { "https://f-droid.org/repo/${app.id}_$it.apk" }
        }
    }

    /** Все версии приложения в каталоге F-Droid (новые первыми). */
    suspend fun fdroidVersions(pkg: String): List<FdVersion> = withContext(Dispatchers.IO) {
        val text = try { get("https://f-droid.org/api/v1/packages/$pkg") } catch (e: Exception) {
            if (e.message?.contains("404") == true) return@withContext emptyList() else throw e
        }
        val pk = JSONObject(text).optJSONArray("packages") ?: JSONArray()
        (0 until pk.length()).map { pk.getJSONObject(it) }
            .map { FdVersion(it.optString("versionName"), it.optLong("versionCode"), "https://f-droid.org/repo/${pkg}_${it.optLong("versionCode")}.apk") }
            .distinctBy { it.code }.sortedByDescending { it.code }.take(40)
    }

    /** Скачивает файл в папку временных файлов; onProgress получает мегабайты. */
    suspend fun download(ctx: Context, url: String, onProgress: (Long) -> Unit): ApkFile? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(ctx.cacheDir, "batch").apply { mkdirs() }
            val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "app.apk" }.let { if (it.lowercase().endsWith(".apk")) it else "$it.apk" }
            val out = File(dir, "net_" + name.replace(Regex("[^\\w.\\-]"), "_"))
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 40_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            c.inputStream.use { i -> out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    total += n
                    onProgress(total / 1024 / 1024)
                }
            } }
            c.disconnect()
            ApkFile(name, Uri.fromFile(out), out.length(), out)
        }.onFailure { NetErr.set(it) }.getOrNull()
    }
}
