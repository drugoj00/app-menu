package app.tvapps

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Socket
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

/*
 * Резервные копии и синхронизация настроек в сетевые и внешние места (15.0): сетевая папка SMB (Samba), WebDAV (в том числе Яндекс Диск), папка на устройстве
 * или USB-накопитель. Ручное «сохранить сейчас» и «восстановить», автокопия по расписанию (раз в сутки или в неделю), синхронизация двух устройств
 * через общий файл в таком месте: побеждает последняя запись, видны дата и устройство.
 * Пароли хранятся только в зашифрованном виде (ключ Android Keystore, AES-GCM), в резервные копии и в журнал не попадают.
 */

/** Файл в хранилище: имя, размер, время изменения (мс, 0 — неизвестно). */
class BkFile(val name: String, val size: Long, val modified: Long)

/** Хранилище копий: SMB, WebDAV или папка. Все операции блокирующие, вызываются только не из главного потока. */
interface BkStore {
    fun ensureDir()
    fun list(): List<BkFile>
    fun get(name: String): ByteArray
    fun put(name: String, data: ByteArray)
    fun delete(name: String)
}

/** Одно назначение копий. [kind]: smb, dav, local (путь в памяти или на накопителе), saf (папка из системного выбора, [url] — её адрес). */
class BkTarget(
    val id: String, val kind: String, var name: String, var url: String, var user: String,
    var auto: Boolean = false, var sync: Boolean = false,
)

// ---------------------------------------------------------------------------------------------------------------- пароли

/** Пароли назначений: шифр AES-GCM, ключ в Android Keystore (из устройства не выходит). Если Keystore недоступен, пароль живёт только до выхода из программы. */
internal object BkVault {
    private const val ALIAS = "tvapps_bk_v1"
    private val mem = HashMap<String, String>()

    private fun key(): javax.crypto.SecretKey? = runCatching {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val g = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            g.init(
                android.security.keystore.KeyGenParameterSpec.Builder(ALIAS, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build(),
            )
            g.generateKey()
        }
        ks.getKey(ALIAS, null) as? javax.crypto.SecretKey
    }.getOrNull()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("tvapps_bk_sec", Context.MODE_PRIVATE)

    /** Сохраняет пароль. false: Keystore недоступен, пароль остался только в памяти процесса. */
    fun put(c: Context, id: String, secret: String): Boolean {
        if (secret.isEmpty()) { remove(c, id); return true }
        mem[id] = secret
        val k = key() ?: return false
        return runCatching {
            val ci = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            ci.init(javax.crypto.Cipher.ENCRYPT_MODE, k)
            val enc = ci.doFinal(secret.toByteArray(Charsets.UTF_8))
            val blob = android.util.Base64.encodeToString(ci.iv + enc, android.util.Base64.NO_WRAP)
            prefs(c).edit().putString(id, blob).apply()
            true
        }.getOrDefault(false)
    }

    fun get(c: Context, id: String): String {
        mem[id]?.let { return it }
        val blob = prefs(c).getString(id, null) ?: return ""
        val k = key() ?: return ""
        return runCatching {
            val raw = android.util.Base64.decode(blob, android.util.Base64.NO_WRAP)
            val ci = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            ci.init(javax.crypto.Cipher.DECRYPT_MODE, k, javax.crypto.spec.GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            String(ci.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8).also { mem[id] = it }
        }.getOrDefault("")
    }

    fun has(c: Context, id: String): Boolean = mem.containsKey(id) || prefs(c).contains(id)

    fun remove(c: Context, id: String) { mem.remove(id); prefs(c).edit().remove(id).apply() }
}

// ---------------------------------------------------------------------------------------------------------------- список назначений

/** Назначения копий и расписание. Лежат в отдельном файле настроек и в резервную копию не входят (адреса и логины остаются на этом устройстве). */
object BkTargets {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("tvapps_bk", Context.MODE_PRIVATE)

    fun all(c: Context): List<BkTarget> = runCatching {
        val a = JSONArray(sp(c).getString("targets", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            BkTarget(o.getString("id"), o.getString("kind"), o.optString("name"), o.optString("url"), o.optString("user"), o.optBoolean("auto"), o.optBoolean("sync"))
        }
    }.getOrDefault(emptyList())

    fun saveAll(c: Context, l: List<BkTarget>) {
        val a = JSONArray()
        l.forEach { t -> a.put(JSONObject().put("id", t.id).put("kind", t.kind).put("name", t.name).put("url", t.url).put("user", t.user).put("auto", t.auto).put("sync", t.sync)) }
        sp(c).edit().putString("targets", a.toString()).apply()
        BkSched.apply(c)
    }

    fun add(c: Context, t: BkTarget) = saveAll(c, all(c) + t)
    fun update(c: Context, t: BkTarget) = saveAll(c, all(c).map { if (it.id == t.id) t else it })
    fun remove(c: Context, id: String) {
        saveAll(c, all(c).filter { it.id != id })
        BkVault.remove(c, id)
        sp(c).edit().remove("hash_$id").remove("rt_$id").remove("last_$id").apply()
    }

    fun find(c: Context, id: String): BkTarget? = all(c).firstOrNull { it.id == id }
    fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + (Math.random() * 1000).toInt()

    /** Расписание автокопий: 0 выкл, 1 раз в сутки, 2 раз в неделю. */
    fun schedule(c: Context): Int = sp(c).getInt("sched", 0).coerceIn(0, 2)
    fun setSchedule(c: Context, v: Int) { sp(c).edit().putInt("sched", v.coerceIn(0, 2)).apply(); BkSched.apply(c) }
    fun lastAuto(c: Context): Long = sp(c).getLong("last_auto", 0L)
    fun setLastAuto(c: Context, v: Long) { sp(c).edit().putLong("last_auto", v).apply() }

    /** Состояние синхронизации назначения: хэш настроек на момент последней синхронизации и время общего файла. */
    fun syncHash(c: Context, id: String): String? = sp(c).getString("hash_$id", null)
    fun syncRemote(c: Context, id: String): Long = sp(c).getLong("rt_$id", 0L)
    fun setSyncState(c: Context, id: String, hash: String, remoteTime: Long) { sp(c).edit().putString("hash_$id", hash).putLong("rt_$id", remoteTime).apply() }

    /** Итог последней операции с назначением: «время|текст». */
    fun lastResult(c: Context, id: String): Pair<Long, String>? = sp(c).getString("last_$id", null)?.let { it.substringBefore('|').toLongOrNull()?.let { t -> t to it.substringAfter('|') } }
    fun setLastResult(c: Context, id: String, text: String) { sp(c).edit().putString("last_$id", System.currentTimeMillis().toString() + "|" + text).apply() }

    /** Время последнего изменения настроек на этом устройстве (по слушателю общих настроек). */
    fun localChangedAt(c: Context): Long = sp(c).getLong("local_changed", 0L)
    fun setLocalChangedAt(c: Context, v: Long) { sp(c).edit().putLong("local_changed", v).apply() }

    fun summary(c: Context): String {
        val l = all(c)
        if (l.isEmpty()) return c.getString(R.string.bkr_summary_none)
        val s = schedule(c)
        return c.getString(R.string.bkr_summary, l.size, c.getString(when (s) { 1 -> R.string.bkr_sch_day; 2 -> R.string.bkr_sch_week; else -> R.string.bkr_sch_off }))
    }

    fun kindName(c: Context, kind: String): String = c.getString(when (kind) { "smb" -> R.string.bkr_k_smb; "dav" -> R.string.bkr_k_dav; else -> R.string.bkr_k_local })
}

// ---------------------------------------------------------------------------------------------------------------- HTTP по сокету

/** Ответ сервера. Заголовки с именами в нижнем регистре. */
class HttpResp(val code: Int, val headers: Map<String, String>, val body: ByteArray)

/**
 * Минимальный клиент HTTP/1.1 поверх сокета: нужен для WebDAV (методы PROPFIND, MKCOL, которых нет в системном клиенте Android), работает и по http, и по https
 * (проверка сертификата и имени сайта полная, корни из [TlsFix]). Только Basic-авторизация. Повторяет запрос по перенаправлениям.
 */
object RawHttp {
    var connectTimeoutMs = 10_000
    var readTimeoutMs = 30_000

    fun request(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null, user: String = "", pass: String = "", redirects: Int = 5): HttpResp {
        val u = java.net.URI(url)
        val https = u.scheme.equals("https", true)
        if (!https && !u.scheme.equals("http", true)) error("bad scheme")
        val host = u.host ?: error("bad host")
        val port = if (u.port > 0) u.port else if (https) 443 else 80
        val path = (u.rawPath.ifEmpty { "/" }) + (u.rawQuery?.let { "?$it" } ?: "")
        var sock: Socket = Socket()
        sock.connect(java.net.InetSocketAddress(host, port), connectTimeoutMs)
        sock.soTimeout = readTimeoutMs
        if (https) {
            val ssl = javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory().createSocket(sock, host, port, true) as javax.net.ssl.SSLSocket
            ssl.startHandshake()
            if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) { ssl.close(); error("hostname") }
            sock = ssl
        }
        sock.use { s ->
            val out = s.getOutputStream()
            val sb = StringBuilder()
            sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            sb.append("Host: ").append(host).append(if (u.port > 0 && u.port != (if (https) 443 else 80)) ":" + u.port else "").append("\r\n")
            sb.append("User-Agent: AppMenu\r\nConnection: close\r\nAccept: */*\r\n")
            if (user.isNotEmpty()) sb.append("Authorization: Basic ").append(android.util.Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)).append("\r\n")
            headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
            if (body != null || method == "PUT" || method == "POST" || method == "PROPFIND") sb.append("Content-Length: ").append(body?.size ?: 0).append("\r\n")
            sb.append("\r\n")
            val head = sb.toString().toByteArray(Charsets.ISO_8859_1)
            // заголовки и небольшое тело одним куском: сервер, ответивший сразу (404, 401), не сбрасывает соединение из-за недочитанного тела
            if (body != null && body.size <= 256 * 1024) { out.write(head + body); out.flush() }
            else {
                out.write(head); out.flush()
                if (body != null) try { out.write(body); out.flush() } catch (e: java.io.IOException) { /* сервер мог ответить и закрыть соединение: читаем ответ */ }
            }
            val resp = readResponse(s.getInputStream().buffered(64 * 1024), method == "HEAD")
            if (resp.code in intArrayOf(301, 302, 303, 307, 308) && redirects > 0) {
                val loc = resp.headers["location"]
                if (!loc.isNullOrEmpty()) {
                    val nu = u.resolve(loc)
                    // логин и пароль уходят только на тот же сервер: на другой адрес (в том числе по перенаправлению) учётные данные не передаются
                    val same = nu.host.equals(u.host, true) && nu.scheme.equals(u.scheme, true)
                    return request(if (resp.code == 303) "GET" else method, nu.toString(), headers, if (resp.code == 303) null else body, if (same) user else "", if (same) pass else "", redirects - 1)
                }
            }
            return resp
        }
    }

    private fun readLine(i: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = i.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun readResponse(i: java.io.InputStream, head: Boolean): HttpResp {
        var status = readLine(i) ?: error("no response")
        var code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: error("bad response")
        var h: MutableMap<String, String>
        while (true) {
            h = HashMap()
            while (true) {
                val l = readLine(i) ?: break
                if (l.isEmpty()) break
                val k = l.substringBefore(':').trim().lowercase()
                h[k] = l.substringAfter(':', "").trim()
            }
            if (code != 100) break
            status = readLine(i) ?: error("no response"); code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: error("bad response")
        }
        if (head || code == 204 || code == 304 || code in 100..199) return HttpResp(code, h, ByteArray(0))
        val bo = ByteArrayOutputStream()
        val te = h["transfer-encoding"].orEmpty().lowercase()
        if (te.contains("chunked")) {
            while (true) {
                val size = (readLine(i) ?: break).substringBefore(';').trim().toIntOrNull(16) ?: break
                if (size == 0) { while (true) { val t = readLine(i) ?: break; if (t.isEmpty()) break }; break }
                val buf = ByteArray(size)
                var got = 0
                while (got < size) { val n = i.read(buf, got, size - got); if (n < 0) error("truncated"); got += n }
                bo.write(buf)
                readLine(i)
            }
        } else {
            val len = h["content-length"]?.toLongOrNull()
            val buf = ByteArray(64 * 1024)
            var left = len ?: Long.MAX_VALUE
            while (left > 0) {
                val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) { if (len != null) error("truncated"); break }
                bo.write(buf, 0, n)
                left -= n
            }
        }
        return HttpResp(code, h, bo.toByteArray())
    }
}

// ---------------------------------------------------------------------------------------------------------------- WebDAV

/** Ошибка хранилища с понятной причиной (ключ [kind]: auth, notfound, net, digest, other). */
class BkError(val kind: String, message: String) : Exception(message)

class DavStore(baseUrl: String, private val user: String, private val pass: String) : BkStore {
    private val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    private fun enc(name: String) = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    private fun call(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): HttpResp {
        val r = try { RawHttp.request(method, url, headers, body, user, pass) }
        catch (e: javax.net.ssl.SSLException) { android.util.Log.w("TVApps", "WebDAV: " + e.javaClass.simpleName); throw BkError("tls", e.javaClass.simpleName) }
        catch (e: java.io.IOException) { android.util.Log.w("TVApps", "WebDAV: " + e.javaClass.simpleName + " " + e.message?.take(80)); throw BkError("net", e.javaClass.simpleName) }
        catch (e: IllegalStateException) { android.util.Log.w("TVApps", "WebDAV: " + e.message?.take(80)); throw BkError("net", e.message.orEmpty()) }
        catch (e: RuntimeException) { android.util.Log.w("TVApps", "WebDAV: " + e.javaClass.simpleName + " " + e.message?.take(80)); throw BkError("other", e.javaClass.simpleName) }
        if (r.code == 401) throw BkError(if (r.headers["www-authenticate"].orEmpty().lowercase().startsWith("digest")) "digest" else "auth", "401")
        if (r.code == 403) throw BkError("auth", "403")
        return r
    }

    override fun ensureDir() {
        val r = call("PROPFIND", base, mapOf("Depth" to "0", "Content-Type" to "application/xml"), PROPFIND.toByteArray())
        if (r.code in 200..299) return
        if (r.code != 404) throw BkError("other", "HTTP ${r.code}")
        // создаём папки по одной, начиная с корня
        val u = java.net.URI(base)
        val segs = u.rawPath.split('/').filter { it.isNotEmpty() }
        var cur = u.scheme + "://" + u.rawAuthority + "/"
        for (s in segs) {
            cur += "$s/"
            val m = call("MKCOL", cur)
            if (m.code !in intArrayOf(201, 405, 301)) throw BkError("other", "MKCOL ${m.code}")
        }
    }

    override fun list(): List<BkFile> {
        val r = call("PROPFIND", base, mapOf("Depth" to "1", "Content-Type" to "application/xml"), PROPFIND.toByteArray())
        if (r.code == 404) return emptyList()
        if (r.code !in 200..299) throw BkError("other", "HTTP ${r.code}")
        return parseMultistatus(String(r.body, Charsets.UTF_8), java.net.URI(base).path.trimEnd('/'))
    }

    override fun get(name: String): ByteArray {
        val r = call("GET", base + enc(name))
        if (r.code == 404) throw BkError("notfound", name)
        if (r.code !in 200..299) throw BkError("other", "HTTP ${r.code}")
        return r.body
    }

    override fun put(name: String, data: ByteArray) {
        val r = call("PUT", base + enc(name), mapOf("Content-Type" to "application/octet-stream"), data)
        if (r.code !in 200..299) throw BkError("other", "PUT ${r.code}")
    }

    override fun delete(name: String) {
        val r = call("DELETE", base + enc(name))
        if (r.code !in 200..299 && r.code != 404) throw BkError("other", "DELETE ${r.code}")
    }

    companion object {
        private const val PROPFIND = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:getlastmodified/><d:getcontentlength/><d:resourcetype/></d:prop></d:propfind>"

        /** Разбор ответа PROPFIND (Depth 1): файлы папки; сама папка и вложенные папки пропускаются. */
        fun parseMultistatus(xml: String, basePath: String): List<BkFile> {
            val f = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val rs = doc.getElementsByTagNameNS("DAV:", "response")
            val out = ArrayList<BkFile>()
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            for (i in 0 until rs.length) {
                val r = rs.item(i) as org.w3c.dom.Element
                fun text(tag: String) = r.getElementsByTagNameNS("DAV:", tag).item(0)?.textContent?.trim().orEmpty()
                val href = runCatching { java.net.URLDecoder.decode(text("href").replace("+", "%2B"), "UTF-8") }.getOrDefault(text("href"))
                val path = runCatching { java.net.URI(href.replace(" ", "%20")).path }.getOrDefault(href)
                if (r.getElementsByTagNameNS("DAV:", "collection").length > 0) continue
                val name = path.trimEnd('/').substringAfterLast('/')
                if (name.isEmpty() || path.trimEnd('/') == basePath) continue
                val size = text("getcontentlength").toLongOrNull() ?: 0L
                val mod = runCatching { fmt.parse(text("getlastmodified"))?.time ?: 0L }.getOrDefault(0L)
                out.add(BkFile(name, size, mod))
            }
            return out
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------- SMB

class SmbStore(baseUrl: String, private val user: String, private val pass: String) : BkStore {
    private val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    private val cx by lazy {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.connTimeout", "8000")
            setProperty("jcifs.smb.client.responseTimeout", "25000")
            setProperty("jcifs.smb.client.soTimeout", "25000")
        }
        val b = jcifs.context.BaseContext(jcifs.config.PropertyConfiguration(props))
        b.withCredentials(if (user.isBlank()) jcifs.smb.NtlmPasswordAuthenticator() else jcifs.smb.NtlmPasswordAuthenticator(null, user, pass))
    }

    private fun <T> guard(f: () -> T): T = try { f() } catch (e: BkError) { throw e } catch (e: jcifs.smb.SmbAuthException) { throw BkError("auth", "auth") }
    catch (e: java.io.IOException) { android.util.Log.w("TVApps", "SMB: " + e.javaClass.simpleName + " " + e.message?.take(100) + (e.cause?.let { " / " + it.javaClass.simpleName + " " + it.message?.take(100) } ?: "")); throw BkError("net", e.javaClass.simpleName) }

    override fun ensureDir() = guard {
        val d = jcifs.smb.SmbFile(base, cx)
        if (!d.exists()) d.mkdirs()
    }

    override fun list(): List<BkFile> = guard {
        val d = jcifs.smb.SmbFile(base, cx)
        if (!d.exists()) return@guard emptyList()
        d.listFiles().orEmpty().filter { !it.isDirectory }.map { BkFile(it.name.trimEnd('/'), it.length(), it.lastModified()) }
    }

    override fun get(name: String): ByteArray = guard {
        val f = jcifs.smb.SmbFile(base + name, cx)
        if (!f.exists()) throw BkError("notfound", name)
        f.inputStream.use { it.readBytes() }
    }

    override fun put(name: String, data: ByteArray) = guard {
        jcifs.smb.SmbFile(base + name, cx).outputStream.use { it.write(data) }
    }

    override fun delete(name: String) = guard {
        val f = jcifs.smb.SmbFile(base + name, cx)
        if (f.exists()) f.delete()
    }
}

// ---------------------------------------------------------------------------------------------------------------- папка на устройстве, USB

class LocalStore(path: String) : BkStore {
    private val dir = File(path)
    // папку создать не вышло: почти всегда нет доступа к памяти (на Android 11 и новее нужен доступ ко всем файлам или выбор папки системным окном)
    override fun ensureDir() { if (!dir.isDirectory && !dir.mkdirs()) throw BkError("perm", "mkdir") }
    override fun list(): List<BkFile> = dir.listFiles().orEmpty().filter { it.isFile }.map { BkFile(it.name, it.length(), it.lastModified()) }
    override fun get(name: String): ByteArray = File(dir, name).let { if (!it.isFile) throw BkError("notfound", name) else it.readBytes() }
    override fun put(name: String, data: ByteArray) {
        // сначала временный файл, затем замена: сбой посреди записи не портит прежнюю копию
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(data)
        val dst = File(dir, name)
        if (!tmp.renameTo(dst)) { dst.writeBytes(data); tmp.delete() }
    }
    override fun delete(name: String) { File(dir, name).delete() }
}

/** Папка, выбранная системным окном (адрес дерева документов): работает и для USB-накопителей без доступа ко всей памяти. */
class SafStore(private val ctx: Context, tree: String) : BkStore {
    private val treeUri = Uri.parse(tree)
    private val cr = ctx.contentResolver
    private val rootDoc: Uri get() = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri))

    private class Child(val id: String, val name: String, val size: Long, val mod: Long, val dir: Boolean)

    private fun children(): List<Child> {
        val uri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri))
        val out = ArrayList<Child>()
        cr.query(uri, arrayOf("document_id", "_display_name", "_size", "last_modified", "mime_type"), null, null, null)?.use { c ->
            while (c.moveToNext()) out.add(Child(c.getString(0), c.getString(1).orEmpty(), c.getLong(2), c.getLong(3), c.getString(4) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR))
        }
        return out
    }

    override fun ensureDir() { if (runCatching { children() }.isFailure) throw BkError("other", "saf") }
    override fun list(): List<BkFile> = children().filter { !it.dir }.map { BkFile(it.name, it.size, it.mod) }
    private fun docOf(name: String): Uri? = children().firstOrNull { it.name == name && !it.dir }?.let { android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, it.id) }
    override fun get(name: String): ByteArray = (docOf(name) ?: throw BkError("notfound", name)).let { u -> cr.openInputStream(u)?.use { it.readBytes() } ?: throw BkError("other", "open") }
    override fun put(name: String, data: ByteArray) {
        val existing = docOf(name)
        val u = existing ?: android.provider.DocumentsContract.createDocument(cr, rootDoc, "application/octet-stream", name) ?: throw BkError("other", "create")
        cr.openOutputStream(u, "wt")?.use { it.write(data) } ?: throw BkError("other", "open")
    }
    override fun delete(name: String) { docOf(name)?.let { runCatching { android.provider.DocumentsContract.deleteDocument(cr, it) } } }
}

// ---------------------------------------------------------------------------------------------------------------- работа

/** Результат синхронизации одного назначения. */
enum class SyncRes { PUSHED, PULLED, SAME }

object BkEngine {
    private val lock = Any()
    private val SYNC_ZIP = "AppMenu_sync.zip"
    private val SYNC_JSON = "AppMenu_sync.json"

    fun store(ctx: Context, t: BkTarget): BkStore = when (t.kind) {
        "smb" -> SmbStore(t.url, t.user, BkVault.get(ctx, t.id))
        "dav" -> DavStore(t.url, t.user, BkVault.get(ctx, t.id))
        "saf" -> SafStore(ctx, t.url)
        else -> {
            // доступ к памяти выдаётся через ADB или root, если они подключены (как в файловом менеджере); иначе остаётся системный выбор папки
            if (!Storage.hasAccess(ctx)) runCatching { kotlinx.coroutines.runBlocking { Storage.grant(ctx) } }
            LocalStore(t.url)
        }
    }

    fun deviceName(ctx: Context): String = Announcer.deviceName(ctx).replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_').ifEmpty { "device" }.take(24)

    private fun stamp(fmt: String) = SimpleDateFormat(fmt, Locale.US).format(Date())

    /** Настройки как ZIP-файл. Секреты (токен GitHub) в сетевые копии не попадают. */
    fun snapshot(ctx: Context, sync: Boolean = false): ByteArray = ByteArrayOutputStream().also { Backup.write(ctx, it, forRemote = true, sync = sync) }.toByteArray()

    /** Хэш настроек этого устройства для поиска изменений: без счётчиков запусков и без секретов, плюс подпись своих значков. */
    fun settingsHash(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE).all
        val md = MessageDigest.getInstance("SHA-256")
        prefs.entries.filter { Backup.syncKey(it.key) }.sortedBy { it.key }.forEach { (k, v) -> md.update((k + "=" + v.toString() + ";").toByteArray()) }
        md.update(IconPacks.ownSig(ctx).toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Текст причины для человека. */
    fun reason(ctx: Context, e: Throwable): String = when ((e as? BkError)?.kind) {
        "auth" -> ctx.getString(R.string.bkr_e_auth)
        "digest" -> ctx.getString(R.string.bkr_e_digest)
        "notfound" -> ctx.getString(R.string.bkr_e_notfound)
        "perm" -> ctx.getString(R.string.bkr_e_perm)
        "tls" -> ctx.getString(R.string.bkr_e_tls)
        "net" -> ctx.getString(R.string.bkr_e_net)
        else -> if (e is SecurityException || (e is java.io.FileNotFoundException && e.message.orEmpty().contains("denied", true))) ctx.getString(R.string.bkr_e_perm) else ctx.getString(R.string.bkr_e_other)
    }

    fun nowIo(): Long = System.currentTimeMillis()

    /** Проверка назначения: подключение, папка, запись и чтение тестового файла. Возвращает null при успехе или причину. */
    fun test(ctx: Context, t: BkTarget): String? = try {
        val s = store(ctx, t)
        s.ensureDir()
        val probe = "AppMenu_probe_" + stamp("HHmmss") + ".tmp"
        s.put(probe, "ok".toByteArray())
        val back = String(s.get(probe))
        s.delete(probe)
        if (back == "ok") null else ctx.getString(R.string.bkr_e_other)
    } catch (e: Throwable) { reason(ctx, e) }

    /** Ручное сохранение: файл с датой и именем устройства. Возвращает имя файла. */
    fun saveNow(ctx: Context, t: BkTarget): String {
        val s = store(ctx, t)
        s.ensureDir()
        val name = "AppMenu_" + stamp("yyyy-MM-dd_HH-mm") + "_" + deviceName(ctx) + ".zip"
        s.put(name, snapshot(ctx))
        return name
    }

    /** Автокопия: один файл на сутки и устройство, хранятся 7 последних. */
    fun saveAuto(ctx: Context, t: BkTarget): String {
        val s = store(ctx, t)
        s.ensureDir()
        val prefix = "AppMenu_auto_" + deviceName(ctx) + "_"
        val name = prefix + stamp("yyyy-MM-dd") + ".zip"
        s.put(name, snapshot(ctx))
        runCatching { s.list().filter { it.name.startsWith(prefix) && it.name.endsWith(".zip") }.sortedByDescending { it.name }.drop(7).forEach { s.delete(it.name) } }
        return name
    }

    /** Копии, которые можно восстановить: файлы AppMenu_*.zip, новые первыми. */
    fun copies(ctx: Context, t: BkTarget): List<BkFile> = store(ctx, t).list().filter { it.name.startsWith("AppMenu") && it.name.endsWith(".zip") }.sortedByDescending { it.modified }

    // общий файл синхронизации не содержит настроек самого устройства: при восстановлении они остаются своими
    fun restore(ctx: Context, t: BkTarget, name: String): Boolean = Backup.restore(ctx, ByteArrayInputStream(store(ctx, t).get(name)), keepLocal = name == SYNC_ZIP)

    // ----------------------------------------------------------------------------------- синхронизация

    class Meta(val device: String, val time: Long)

    fun remoteMeta(s: BkStore): Meta? = runCatching {
        val j = JSONObject(String(s.get(SYNC_JSON), Charsets.UTF_8))
        Meta(j.optString("device"), j.optLong("time"))
    }.getOrNull()

    enum class Act { NONE, PUSH, PULL }

    /** Что делать при синхронизации: чистая логика. Побеждает последняя запись; без общего файла сначала отправка. */
    fun decide(localHash: String, lastHash: String?, localChangedAt: Long, remoteTime: Long?, lastRemoteTime: Long): Act {
        if (remoteTime == null) return Act.PUSH
        val remoteChanged = remoteTime > lastRemoteTime
        val localChanged = lastHash == null || localHash != lastHash
        return when {
            remoteChanged && localChanged -> if (localChangedAt > remoteTime) Act.PUSH else Act.PULL
            remoteChanged -> Act.PULL
            localChanged -> Act.PUSH
            else -> Act.NONE
        }
    }

    fun push(ctx: Context, t: BkTarget): Long {
        val s = store(ctx, t)
        s.ensureDir()
        val now = nowIo()
        val h = settingsHash(ctx)
        s.put(SYNC_ZIP, snapshot(ctx, sync = true))
        s.put(SYNC_JSON, JSONObject().put("device", deviceName(ctx)).put("time", now).put("app", BuildConfig.VERSION_NAME).toString().toByteArray(Charsets.UTF_8))
        BkTargets.setSyncState(ctx, t.id, h, now)
        return now
    }

    fun pull(ctx: Context, t: BkTarget): Boolean {
        val s = store(ctx, t)
        val meta = remoteMeta(s)
        val ok = Backup.restore(ctx, ByteArrayInputStream(s.get(SYNC_ZIP)), keepLocal = true)
        if (ok) BkTargets.setSyncState(ctx, t.id, settingsHash(ctx), meta?.time ?: nowIo())
        return ok
    }

    /** Синхронизация одного назначения по правилу «последняя запись побеждает». */
    fun sync(ctx: Context, t: BkTarget): SyncRes {
        val s = store(ctx, t)
        s.ensureDir()
        val meta = remoteMeta(s)
        val act = decide(settingsHash(ctx), BkTargets.syncHash(ctx, t.id), BkTargets.localChangedAt(ctx), meta?.time, BkTargets.syncRemote(ctx, t.id))
        return when (act) {
            Act.PUSH -> { push(ctx, t); SyncRes.PUSHED }
            Act.PULL -> { if (!pull(ctx, t)) throw BkError("other", "restore"); SyncRes.PULLED }
            Act.NONE -> SyncRes.SAME
        }
    }

    /** Сведения об общем файле для окна: устройство и время последней записи, null — файла нет. */
    fun remoteInfo(ctx: Context, t: BkTarget): Meta? = remoteMeta(store(ctx, t))

    private fun online(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.activeNetworkInfo?.isConnected == true
    }.getOrDefault(false)

    private fun needsNet(t: BkTarget) = t.kind == "smb" || t.kind == "dav"

    /** Автоматические действия: автокопия по расписанию и синхронизация. Безопасно вызывать часто; всё идёт в одном потоке за раз. Возвращает true, если что-то выполнялось. */
    fun runAuto(ctx0: Context, forceSync: Boolean = true): Boolean {
        val ctx = ctx0.applicationContext
        val targets = BkTargets.all(ctx)
        if (targets.isEmpty()) return false
        var did = false
        synchronized(lock) {
            val sch = BkTargets.schedule(ctx)
            val every = if (sch == 1) 24L * 3600_000L else if (sch == 2) 7L * 24 * 3600_000L else 0L
            val now = nowIo()
            if (every > 0 && now - BkTargets.lastAuto(ctx) >= every - 5 * 60_000L) {
                var okAny = false
                for (t in targets.filter { it.auto }) {
                    if (needsNet(t) && !online(ctx)) continue
                    try { val n = saveAuto(ctx, t); BkTargets.setLastResult(ctx, t.id, ctx.getString(R.string.bkr_r_auto, n)); okAny = true; did = true }
                    catch (e: Throwable) { BkTargets.setLastResult(ctx, t.id, ctx.getString(R.string.bkr_r_fail, reason(ctx, e))) }
                }
                // если не вышло нигде, повторим при следующем запуске, а не через сутки
                if (okAny || targets.none { it.auto }) BkTargets.setLastAuto(ctx, now)
            }
            if (forceSync) for (t in targets.filter { it.sync }) {
                if (needsNet(t) && !online(ctx)) continue
                try {
                    val r = sync(ctx, t)
                    BkTargets.setLastResult(ctx, t.id, ctx.getString(when (r) { SyncRes.PUSHED -> R.string.bkr_r_pushed; SyncRes.PULLED -> R.string.bkr_r_pulled; SyncRes.SAME -> R.string.bkr_r_same }))
                    did = true
                } catch (e: Throwable) { BkTargets.setLastResult(ctx, t.id, ctx.getString(R.string.bkr_r_fail, reason(ctx, e))) }
            }
        }
        return did
    }

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Запускает [runAuto] в фоне (не больше одного запуска одновременно). */
    fun runAutoAsync(ctx: Context, forceSync: Boolean = true) {
        if (BkTargets.all(ctx).none { it.auto || it.sync }) return
        if (!running.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread { try { runAuto(app, forceSync) } catch (_: Throwable) {} finally { running.set(false) } }.start()
    }

    /** Следит за изменениями настроек: запоминает время последнего изменения (нужно синхронизации, чтобы выбрать «последнюю запись»). */
    private var watcher: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    fun watch(ctx: Context) {
        if (watcher != null) return
        val app = ctx.applicationContext
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key != null && Backup.syncKey(key)) BkTargets.setLocalChangedAt(app, System.currentTimeMillis()) }
        watcher = l
        app.getSharedPreferences("tvapps", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(l)
    }
}

// ---------------------------------------------------------------------------------------------------------------- расписание

/** Фоновая задача раз в сутки: проверяет, пора ли делать автокопию и синхронизацию. Без служб Google, как и проверка обновлений. */
object BkSched {
    private const val JOB_ID = 4720

    fun apply(ctx: Context) {
        runCatching {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val ts = BkTargets.all(ctx).filter { it.auto || it.sync }
            if (ts.isEmpty()) { js.cancel(JOB_ID); return }
            val every = 6L * 3600_000L
            val cur = js.allPendingJobs.firstOrNull { it.id == JOB_ID }
            if (cur != null && cur.isPeriodic && cur.intervalMillis == every) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, BkJobService::class.java)).setPeriodic(every).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build(),
            )
        }
    }
}

class BkJobService : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        val app = applicationContext
        Thread { try { Cfg.init(app); BkEngine.runAuto(app) } catch (_: Throwable) {} finally { jobFinished(p, false) } }.start()
        return true
    }
    override fun onStopJob(p: JobParameters): Boolean = true
}
