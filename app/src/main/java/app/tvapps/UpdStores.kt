package app.tvapps

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Магазины Huawei AppGallery и RuStore как источники обновлений. Протоколы описаны по открытым исходникам
 * APKUpdater (rumboalla, DmitryN71) и «APKUpdater OSS» (NotDev), GPL-3.0; код написан заново.
 *
 * AppGallery: анонимное рукопожатие client.front2 даёт подпись, дальше client.updateCheck по пакету (без аккаунта).
 * RuStore: публичные запросы БЕЗ подписи клиента (подписанная сессия опирается на ключ официального приложения, её не воспроизводим):
 * сервер в этом случае может отдавать чуть устаревшие данные, а из некоторых стран не отвечает вовсе.
 */
object UpdStores {
    private class Resp(val code: Int, val body: String)

    private fun send(url: String, method: String, headers: Map<String, String>, body: String? = null, timeoutMs: Int = 15_000): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.requestMethod = method
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        return try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            Resp(code, text)
        } finally { c.disconnect() }
    }

    // ---------------------------------------------------------------- AppGallery

    private const val AG_PATH = "/hwmarket/api/clientApi"
    private const val AG_UA = "HiSpace##16.5.1.301##google##Pixel 8 Pro"
    private const val AG_HOST_CN = "store-drcn.hispace.dbankcloud.com"
    private const val AG_HOST_RU = "store-drru.hispace.dbankcloud.ru"
    private const val AG_HOST_ASIA = "store-dra.hispace.dbankcloud.com"
    private const val AG_HOST_EU = "store-dre.hispace.dbankcloud.com"
    private const val AG_MAX_AGE = 24 * 3600_000L
    private val AG_ASIA = ("AE AF AG AI AM AO AQ AR AS AW AZ BB BD BF BH BI BJ BL BM BN BO BR BS BT BV BW BY BZ CC CD CF CG CI CK CL CM CO CR CU CV CX DJ DM DO DZ EC EG EH ER ET FJ FK FM GA GD " +
        "GE GF GH GM GN GP GQ GS GT GU GW GY HK HM HN HT ID IN IO IQ JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LK LR LS LY MA MG MH ML MM MN MO MP MQ MR MS MU MV " +
        "MW MX MY MZ NA NC NE NF NG NI NP NR NU OM PA PE PF PG PH PK PN PR PS PW PY QA RE RW SA SB SC SD SG SH SL SN SO SR SS ST SV SY SZ TC TD TF TG TH TJ TK TL TM TN TO " +
        "TT TV TW TZ UG UY UZ VE VG VI VN VU WF WS YE YT ZA ZM ZW").split(" ").toSet()

    private class AgSession(val host: String, val sign: String, val at: Long)
    @Volatile private var agCached: AgSession? = null

    private fun agDeviceId(ctx: Context): String {
        val sp = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE)
        return sp.getString("ag_device", null) ?: run {
            val r = java.security.SecureRandom()
            val id = (0 until 32).joinToString("") { "%02x".format(r.nextInt(256)) }
            sp.edit().putString("ag_device", id).apply(); id
        }
    }

    private fun agLocale(): String { val l = Locale.getDefault(); return if (l.country.isNullOrEmpty()) l.language else "${l.language}_${l.country}" }

    private fun agParams(ctx: Context, sign: String?, extra: Map<String, String>): Map<String, String> {
        val all = hashMapOf(
            "ver" to "1.1", "locale" to agLocale(), "serviceType" to "0", "ts" to System.currentTimeMillis().toString(), "net" to "1",
            "brand" to Build.BRAND.orEmpty().ifEmpty { "google" }, "manufacturer" to Build.MANUFACTURER.orEmpty().ifEmpty { "Google" },
            "subBrand" to "0", "deviceId" to agDeviceId(ctx), "deviceIdType" to "9",
        )
        if (sign != null) all["sign"] = sign
        all.putAll(extra)
        return all.toSortedMap()
    }

    private fun agPost(host: String, fields: Map<String, String>): JSONObject {
        val body = fields.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val r = send("https://$host$AG_PATH", "POST", mapOf("User-Agent" to AG_UA, "Content-Type" to "application/x-www-form-urlencoded"), body)
        if (r.code !in 200..299) error("HTTP ${r.code}")
        return JSONObject(r.body)
    }

    private fun agHandshake(ctx: Context, host: String, needZone: Int, locale: String = agLocale()): JSONObject = agPost(
        host,
        agParams(ctx, null, mapOf(
            "locale" to locale, "method" to "client.front2", "version" to "16.5.1", "versionCode" to "160501301", "packageName" to "com.huawei.appmarket",
            "zone" to "1", "phoneType" to Build.MODEL.orEmpty().ifEmpty { "Pixel 8 Pro" }, "firmwareVersion" to Build.VERSION.RELEASE.orEmpty().ifEmpty { "14" },
            "isFirstLaunch" to "1", "oobe" to "0", "needServiceZone" to needZone.toString(),
        )),
    )

    private fun agHostForZone(zone: String) = when {
        zone.equals("CN", true) -> AG_HOST_CN
        zone.equals("RU", true) -> AG_HOST_RU
        zone.uppercase() in AG_ASIA -> AG_HOST_ASIA
        else -> AG_HOST_EU
    }

    /** Рукопожатие (раз в сутки): сначала зонд на европейский хост, он называет зону и нужный хост. */
    private fun agSession(ctx: Context): AgSession {
        agCached?.takeIf { System.currentTimeMillis() - it.at < AG_MAX_AGE }?.let { return it }
        val probe = agHandshake(ctx, AG_HOST_EU, 1)
        val host = agHostForZone(probe.optString("serviceZone"))
        var sign = probe.optString("sign")
        var useHost = AG_HOST_EU
        if (host != AG_HOST_EU) {
            // на российском хосте поиск идёт на языке рукопожатия: своё рукопожатие на нём
            val own = runCatching { agHandshake(ctx, host, if (host == AG_HOST_RU) 0 else 1, if (host == AG_HOST_RU) "ru_RU" else agLocale()) }.getOrNull()
            val s2 = own?.optString("sign").orEmpty()
            if (s2.isNotEmpty()) { sign = s2; useHost = host }
        }
        if (sign.isEmpty()) error("handshake")
        return AgSession(useHost, sign, System.currentTimeMillis()).also { agCached = it }
    }

    /** Версия и ссылка на APK в AppGallery для пакета; null — в магазине нет более новой. */
    fun appGallery(ctx: Context, pkg: String, instCode: Long): UpdCand? {
        val s = agSession(ctx)
        val req = JSONObject().put("params", JSONArray().put(JSONObject().put("package", pkg).put("version", instCode))).toString()
        val j = agPost(s.host, agParams(ctx, s.sign, mapOf("method" to "client.updateCheck", "pkgInfo" to req)))
        if (j.optInt("rtnCode", -1) != 0) {
            agCached = null
            error("rtnCode ${j.optInt("rtnCode", -1)}")
        }
        return parseAppGallery(j, pkg)
    }

    /** Разбор ответа client.updateCheck (отдельно от сети, чтобы проверять на примерах). */
    fun parseAppGallery(j: JSONObject, pkg: String): UpdCand? {
        val list = j.optJSONArray("list") ?: return null
        val e = (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.optString("package") == pkg } ?: return null
        val url = e.optString("downurl").ifBlank { e.optString("fullDownUrl") }.ifBlank { return null }
        return UpdCand("appgallery", e.optString("version"), e.optLong("versionCode"), listOf(url), e.optLong("size"), note = e.optString("sha256").take(8))
    }

    // ---------------------------------------------------------------- RuStore

    private const val RS_BASE = "https://backapi.rustore.ru/"
    /** Потолок версии клиента без подписи: выше сервер отвечает 419. Подписанная сессия (ключ официального клиента) не используется. */
    private const val RS_VER_CODE = "1105001"

    private fun rsHeaders(deviceType: String): Map<String, String> = mapOf(
        "deviceType" to deviceType, "ruStoreVerCode" to RS_VER_CODE, "Content-Type" to "application/json", "Accept" to "application/json",
        "firmwareVer" to Build.VERSION.RELEASE.orEmpty(), "androidSdkVer" to Build.VERSION.SDK_INT.toString(),
        "deviceManufacturerName" to Build.MANUFACTURER.orEmpty(), "deviceModelName" to Build.MODEL.orEmpty(),
        "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}", "firmwareLang" to Locale.getDefault().language,
        "User-Agent" to "RuStore/1.105.0.1 (Android ${Build.VERSION.RELEASE}; SDK ${Build.VERSION.SDK_INT}; ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}; ${Build.MANUFACTURER} ${Build.MODEL}; ${Locale.getDefault().language})",
    )

    private fun rsCall(path: String, method: String, deviceType: String, body: String? = null): String {
        var r = send(RS_BASE + path, method, rsHeaders(deviceType), body, 12_000)
        if (r.code == 429) { Thread.sleep(1500); r = send(RS_BASE + path, method, rsHeaders(deviceType), body, 12_000) }
        if (r.code == 404) return ""
        if (r.code !in 200..299) error("HTTP ${r.code}")
        return r.body
    }

    /** Версия и ссылка на APK в RuStore. Спрашиваем как «телефон» и как «ТВ»: сервер отдаёт каждому свой каталог. */
    fun ruStore(pkg: String, instCode: Long): UpdCand? {
        var lastErr: Exception? = null
        for (type in listOf("mobile", "tv")) {
            try {
                val batch = JSONObject().put("content", JSONArray().put(JSONObject().put("packageName", pkg).put("versionCode", instCode))).toString()
                val newer = rsCall("applicationData/newApps", "POST", type, batch)
                if (newer.isBlank() || !hasNewer(newer, pkg)) continue
                val info = rsCall("applicationData/overallInfo/$pkg", "GET", type)
                if (info.isBlank()) continue
                val ib = JSONObject(info).optJSONObject("body") ?: continue
                val appId = ib.optLong("appId")
                if (appId == 0L) continue
                val dl = JSONObject().put("appId", appId).put("firstInstall", true).put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                    .put("sdkVersion", Build.VERSION.SDK_INT).put("withoutSplits", true).toString()
                val link = rsCall("v3/showcase/apps/download-link", "POST", type, dl)
                val url = parseRsLink(link) ?: continue
                return UpdCand("rustore", ib.optString("versionName"), ib.optLong("versionCode"), listOf(url), ib.optLong("fileSize"))
            } catch (e: Exception) { lastErr = e }
        }
        if (lastErr != null) throw lastErr
        return null
    }

    /** Есть ли пакет в ответе newApps (список приложений с обновлением). */
    fun hasNewer(json: String, pkg: String): Boolean {
        val c = JSONObject(json).optJSONObject("body")?.optJSONArray("content") ?: return false
        return (0 until c.length()).any { c.getJSONObject(it).optString("packageName") == pkg }
    }

    /** Ссылка на APK из ответа download-link: ".zip" у RuStore — обёртка, сам APK лежит по тому же пути с расширением ".apk". */
    fun parseRsLink(json: String): String? {
        val a = JSONObject(json).optJSONArray("downloadUrls") ?: return null
        val u = a.optJSONObject(0)?.optString("url").orEmpty().ifBlank { return null }
        return if (u.endsWith(".zip", true)) u.dropLast(4) + ".apk" else u
    }
}
