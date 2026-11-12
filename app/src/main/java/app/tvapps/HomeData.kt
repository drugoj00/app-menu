package app.tvapps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Данные для рядов «Дома»: каналы приложений Android TV, входы HDMI, погода, картинки. */

// ------------------------------------------------------------------ каналы приложений

data class ChProgram(val id: Long, val title: String, val poster: String?, val intentUri: String?, val pkg: String, val kind: Int)
data class ChRow(val key: String, val title: String, val pkg: String, val items: List<ChProgram>)

/** Последние прочитанные каналы: при возврате на «Дом» карточки видны сразу, пока идёт новое чтение. */
object ChannelsMem { @Volatile var rows: List<ChRow>? = null }

object Channels {
    const val PERM = "android.permission.READ_TV_LISTINGS"
    private val BASE = Uri.parse("content://android.media.tv")

    fun granted(ctx: Context): Boolean = ctx.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    /**
     * Читает каналы и плитки других приложений из системной базы Android TV (TvProvider).
     * Нужно разрешение READ_TV_LISTINGS: его выдаёт пользователь или команда `pm grant` через ADB или root.
     * Без него и на устройствах без этой базы список пустой.
     */
    fun load(ctx: Context): List<ChRow> {
        if (!granted(ctx)) return emptyList()
        val cr = ctx.contentResolver
        val names = HashMap<Long, Pair<String, String>>()
        runCatching {
            cr.query(Uri.withAppendedPath(BASE, "channel"), arrayOf("_id", "package_name", "display_name", "type"), null, null, null)?.use { c ->
                while (c.moveToNext()) names[c.getLong(0)] = (c.getString(2) ?: "") to (c.getString(1) ?: "")
            }
        }.onFailure { Log.w("TVApps", "каналы: ${it.message}") }
        val byChannel = LinkedHashMap<Long, MutableList<ChProgram>>()
        runCatching {
            cr.query(Uri.withAppendedPath(BASE, "preview_program"),
                arrayOf("_id", "channel_id", "package_name", "title", "poster_art_uri", "intent_uri", "weight"), null, null, "weight DESC")?.use { c ->
                while (c.moveToNext() && c.position < 400) {
                    val ch = c.getLong(1)
                    byChannel.getOrPut(ch) { mutableListOf() }.add(ChProgram(c.getLong(0), c.getString(3) ?: "", c.getString(4), c.getString(5), c.getString(2) ?: "", 0))
                }
            }
        }.onFailure { Log.w("TVApps", "плитки каналов: ${it.message}") }
        val rows = ArrayList<ChRow>()
        // «Продолжить просмотр» (Watch Next)
        runCatching {
            val list = mutableListOf<ChProgram>()
            cr.query(Uri.withAppendedPath(BASE, "watch_next_program"), arrayOf("_id", "package_name", "title", "poster_art_uri", "intent_uri"), null, null, null)?.use { c ->
                while (c.moveToNext() && list.size < 30) list += ChProgram(c.getLong(0), c.getString(2) ?: "", c.getString(3), c.getString(4), c.getString(1) ?: "", 1)
            }
            if (list.isNotEmpty()) rows += ChRow("watch_next", "", "", list)
        }
        for ((ch, list) in byChannel) {
            val (name, pkg) = names[ch] ?: continue
            if (list.isNotEmpty() && "$pkg:$ch" !in Cfg.hiddenChannels) rows += ChRow("$pkg:$ch", name.ifBlank { pkg }, pkg, list.take(30))
        }
        return rows
    }

    /** Все каналы (для списка «скрыть канал» в настройках). */
    fun allKeys(ctx: Context): List<Pair<String, String>> = load(ctx).map { it.key to it.title }

    fun launch(ctx: Context, p: ChProgram): Boolean = runCatching {
        val uri = p.intentUri ?: return false
        val i = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    }.getOrDefault(false)
}

// ------------------------------------------------------------------ входы HDMI

data class TvIn(val id: String, val label: String, val hdmi: Boolean)

object TvInputs {
    fun list(ctx: Context): List<TvIn> = runCatching {
        val m = ctx.getSystemService(Context.TV_INPUT_SERVICE) as android.media.tv.TvInputManager
        m.tvInputList.filter { it.type == android.media.tv.TvInputInfo.TYPE_HDMI || it.isPassthroughInput }
            .map { TvIn(it.id, it.loadLabel(ctx)?.toString().orEmpty().ifBlank { it.id }, it.type == android.media.tv.TvInputInfo.TYPE_HDMI) }
    }.getOrDefault(emptyList())

    fun open(ctx: Context, id: String): Boolean = runCatching {
        val uri = android.media.tv.TvContract.buildChannelUriForPassthroughInput(id)
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}

// ------------------------------------------------------------------ картинки

object Pics {
    private val cache = CacheBudget.track(object : LruCache<String, Bitmap>(CacheBudget.bytes(40)) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount })
    private val failed = HashSet<String>()

    fun cached(url: String): Bitmap? = cache.get(url)

    /** Скачивает или читает картинку и уменьшает до нужной ширины. Вызывать не из главного потока. */
    fun load(ctx: Context, url: String, maxW: Int = 480): Bitmap? {
        cache.get(url)?.let { return it }
        if (url in failed) return null
        return runCatching {
            val u = Uri.parse(url)
            fun open() = if (u.scheme == "http" || u.scheme == "https") {
                (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 12000; setRequestProperty("User-Agent", "AppMenu") }.inputStream
            } else ctx.contentResolver.openInputStream(u)!!
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open().use { BitmapFactory.decodeStream(it, null, bounds) }
            var s = 1
            while (bounds.outWidth / (s * 2) >= maxW) s *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = s }
            val bmp = open().use { BitmapFactory.decodeStream(it, null, opts) }
            if (bmp != null) cache.put(url, bmp) else failed += url
            bmp
        }.onFailure { failed += url }.getOrNull()
    }
}

// ------------------------------------------------------------------ баннеры приложений

object Banners {
    private val cache = CacheBudget.track(object : LruCache<String, Bitmap>(CacheBudget.bytes(24)) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount })
    private val none = HashSet<String>()

    fun peek(pkg: String): Bitmap? = cache.get(pkg)

    /** Баннер приложения для телевизора (если у него есть); вызывать не из главного потока. */
    fun load(ctx: Context, e: Entry): Bitmap? {
        cache.get(e.pkg)?.let { return it }
        if (e.pkg in none) return null
        val pm = ctx.packageManager
        val d = runCatching { e.launch.component?.let { pm.getActivityBanner(it) } }.getOrNull()
            ?: runCatching { pm.getApplicationBanner(e.pkg) }.getOrNull()
        // «баннер» почти квадратный (по сути значок приложения) растянулся бы и выглядел плохо: считаем, что баннера нет
        val ratio = if (d != null && d.intrinsicWidth > 0 && d.intrinsicHeight > 0) d.intrinsicWidth.toFloat() / d.intrinsicHeight else 1.78f
        val bmp = d?.takeIf { ratio >= 1.5f }?.let { runCatching { it.toBitmap(320, 180) }.getOrNull() }
        if (bmp != null) cache.put(e.pkg, bmp) else none += e.pkg
        return bmp
    }
}

// ------------------------------------------------------------------ погода (Open-Meteo, без ключа)

data class WDay(val label: String, val code: Int, val max: Int, val min: Int)
/** Почасовой прогноз: подпись часа («14»), код погоды, температура. */
data class WHour(val label: String, val code: Int, val temp: Int, val wind: Int = 0, val pop: Int = Int.MIN_VALUE)

/** Погода; feels, hum, press — «нет данных» (NA) у записей из кэша прежних версий; sunrise, sunset — «ЧЧ:ММ» на сегодня. */
data class Weather(
    val city: String, val temp: Int, val code: Int, val wind: Int, val days: List<WDay>, val at: Long,
    val feels: Int = NA, val hum: Int = NA, val press: Int = NA, val sunrise: String = "", val sunset: String = "", val hours: List<WHour> = emptyList(),
    /** номер источника данных (см. WxSrc), 0: запись прежних версий (Open-Meteo) */
    val src: Int = 0,
) {
    companion object { const val NA = Int.MIN_VALUE }
    /** Есть ли дополнительные данные (ощущаемая, влажность, давление, восход, почасовой прогноз). */
    val hasExtra: Boolean get() = feels != NA && hours.isNotEmpty()
}

object WeatherData {
    /** Причина последней неудачи (для виджета: вместо вечной «Загрузка…»). */
    @Volatile var lastError: String? = null

    internal fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        var err: Exception? = null
        for (attempt in 0..1) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 12000; c.setRequestProperty("User-Agent", "AppMenu"); for ((k, v) in headers) c.setRequestProperty(k, v)
                try {
                    if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                    return c.inputStream.bufferedReader().use { it.readText() }
                } finally { c.disconnect() }
            } catch (e: Exception) {
                err = e
                NetErr.set(e)
                // ошибка сертификата или имени сайта от повтора не пройдёт; сетевой сбой повторяем через паузу
                if (e is javax.net.ssl.SSLException || e is java.net.UnknownHostException) break
                try { Thread.sleep(700) } catch (_: InterruptedException) { }
            }
        }
        throw err ?: IllegalStateException("нет ответа")
    }

    /** Запасной поиск города (OpenStreetMap Nominatim), если геокодер погоды не ответил или не знает название. */
    private fun nominatim(city: String, lang: String): Triple<Double, Double, String>? = runCatching {
        val a = org.json.JSONArray(get("https://nominatim.openstreetmap.org/search?format=json&limit=1&accept-language=" + lang + "&q=" + java.net.URLEncoder.encode(city, "UTF-8")))
        val o = a.optJSONObject(0) ?: return@runCatching null
        Triple(o.getString("lat").toDouble(), o.getString("lon").toDouble(), o.optString("name").ifBlank { o.optString("display_name", city).substringBefore(",") })
    }.getOrNull()

    private fun key(city: String, lang: String) = "w_" + city.trim().lowercase().ifBlank { "auto" } + "_" + lang

    fun cached(ctx: Context, city: String, lang: String = AppLoc.code()): Weather? = runCatching {
        val j = JSONObject(ctx.getSharedPreferences("tvapps_weather", Context.MODE_PRIVATE).getString(key(city, lang), null) ?: return null)
        parse(j, lang)
    }.getOrNull()

    /** Короткое название дня недели по дате ГГГГ-ММ-ДД на языке [lang]. */
    fun dayLabel(iso: String, lang: String): String = runCatching {
        java.text.SimpleDateFormat("EEE", java.util.Locale(lang)).format(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(iso)!!)
    }.getOrDefault(iso)

    private fun parse(j: JSONObject, lang: String): Weather {
        val days = mutableListOf<WDay>()
        val d = j.getJSONArray("days")
        // подпись дня строится из даты при чтении: в кэше хранится дата, а не слово на каком-то языке
        for (i in 0 until d.length()) { val o = d.getJSONObject(i); days += WDay(if (o.has("d")) dayLabel(o.getString("d"), lang) else o.getString("l"), o.getInt("c"), o.getInt("x"), o.getInt("n")) }
        val hours = mutableListOf<WHour>()
        j.optJSONArray("hh")?.let { a -> for (i in 0 until a.length()) { val o = a.getJSONObject(i); hours += WHour(o.getString("l"), o.getInt("c"), o.getInt("x"), o.optInt("w"), o.optInt("p", Int.MIN_VALUE)) } }
        return Weather(j.optString("shown", j.optString("city")), j.getInt("t"), j.getInt("c"), j.getInt("w"), days, j.getLong("at"),
            j.optInt("fe", Weather.NA), j.optInt("hu", Weather.NA), j.optInt("pr", Weather.NA), j.optString("sr"), j.optString("ss"), hours, j.optInt("src", 0))
    }

    /** Место по адресу в интернете (когда город не задан): широта, долгота, название. Запоминается на 12 часов. */
    private fun locateByIp(sp: android.content.SharedPreferences): Triple<Double, Double, String>? {
        val at = sp.getLong("ip_at", 0L)
        if (System.currentTimeMillis() - at < 12 * 3600_000L && sp.contains("ip_lat"))
            return Triple(sp.getFloat("ip_lat", 0f).toDouble(), sp.getFloat("ip_lon", 0f).toDouble(), sp.getString("ip_city", "") ?: "")
        val found = runCatching {
            val j = JSONObject(get("https://ipwho.is/"))
            if (!j.optBoolean("success", true)) null else {
                sp.edit().putString("ip_tz", j.optJSONObject("timezone")?.optString("id")).apply()
                Triple(j.getDouble("latitude"), j.getDouble("longitude"), j.optString("city"))
            }
        }.getOrNull() ?: runCatching {
            val j = JSONObject(get("https://ipapi.co/json/"))
            Triple(j.getDouble("latitude"), j.getDouble("longitude"), j.optString("city"))
        }.getOrNull()
        if (found != null) sp.edit().putLong("ip_at", System.currentTimeMillis()).putFloat("ip_lat", found.first.toFloat())
            .putFloat("ip_lon", found.second.toFloat()).putString("ip_city", found.third).apply()
        return found ?: if (sp.contains("ip_lat")) Triple(sp.getFloat("ip_lat", 0f).toDouble(), sp.getFloat("ip_lon", 0f).toDouble(), sp.getString("ip_city", "") ?: "") else null
    }

    /** Open-Meteo: основной источник (без ключа). Возвращает данные во внутреннем виде кэша. */
    private fun openMeteo(lat: Double, lon: Double): JSONObject {
        val f = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,wind_speed_10m,apparent_temperature,relative_humidity_2m,surface_pressure" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset&hourly=temperature_2m,weather_code,wind_speed_10m,precipitation_probability&timezone=auto&forecast_days=7&wind_speed_unit=ms"))
        val cur = f.getJSONObject("current")
        val daily = f.getJSONObject("daily")
        val times = daily.getJSONArray("time"); val codes = daily.getJSONArray("weather_code")
        val mx = daily.getJSONArray("temperature_2m_max"); val mn = daily.getJSONArray("temperature_2m_min")
        val arr = org.json.JSONArray()
        for (i in 0 until times.length()) {
            arr.put(JSONObject().put("d", times.getString(i)).put("c", codes.getInt(i)).put("x", Math.round(mx.getDouble(i)).toInt()).put("n", Math.round(mn.getDouble(i)).toInt()))
        }
        val j = JSONObject().put("t", Math.round(cur.getDouble("temperature_2m")).toInt())
            .put("c", cur.getInt("weather_code")).put("w", Math.round(cur.getDouble("wind_speed_10m")).toInt()).put("days", arr)
        // дополнительные данные (необязательные: при сбое разбора основная погода всё равно сохраняется)
        runCatching {
            if (cur.has("apparent_temperature") && !cur.isNull("apparent_temperature")) j.put("fe", Math.round(cur.getDouble("apparent_temperature")).toInt())
            if (cur.has("relative_humidity_2m") && !cur.isNull("relative_humidity_2m")) j.put("hu", Math.round(cur.getDouble("relative_humidity_2m")).toInt())
            if (cur.has("surface_pressure") && !cur.isNull("surface_pressure")) j.put("pr", Math.round(cur.getDouble("surface_pressure")).toInt())
            val sr = daily.optJSONArray("sunrise")?.optString(0).orEmpty(); val ss = daily.optJSONArray("sunset")?.optString(0).orEmpty()
            if (sr.length >= 16) j.put("sr", sr.substring(11, 16)); if (ss.length >= 16) j.put("ss", ss.substring(11, 16))
            val ht = f.getJSONObject("hourly"); val tt = ht.getJSONArray("time"); val tc = ht.getJSONArray("weather_code"); val tx = ht.getJSONArray("temperature_2m")
            val tw = ht.optJSONArray("wind_speed_10m"); val tpo = ht.optJSONArray("precipitation_probability")
            val nowKey = cur.optString("time").take(13)     // «ГГГГ-ММ-ДДTЧЧ»: первый час прогноза не раньше текущего
            var from = 0
            while (from < tt.length() && tt.getString(from).take(13) < nowKey) from++
            val ha = org.json.JSONArray()
            var i = from
            while (i < tt.length() && ha.length() < 36) {
                if (!tx.isNull(i)) {
                    val ho = JSONObject().put("l", tt.getString(i).substring(11, 13)).put("c", tc.optInt(i)).put("x", Math.round(tx.getDouble(i)).toInt())
                    if (tw != null && !tw.isNull(i)) ho.put("w", Math.round(tw.getDouble(i)).toInt())
                    if (tpo != null && !tpo.isNull(i)) ho.put("p", tpo.optInt(i))
                    ha.put(ho)
                }
                i++
            }
            j.put("hh", ha)
        }
        return j
    }

    /** Один источник погоды по номеру (см. WxSrc); результат во внутреннем виде кэша. */
    private fun fetchFrom(id: Int, p: WxPlace, key: String, now: Long): JSONObject = when (id) {
        WxSrc.OPEN_METEO -> openMeteo(p.lat, p.lon)
        WxSrc.MET -> WxProviders.met(p, ::get, now)
        WxSrc.WTTR -> WxProviders.wttr(p, ::get, now)
        WxSrc.OWM -> WxProviders.owm(p, key, ::get, now)
        WxSrc.WAPI -> WxProviders.wapi(p, key, ::get, now)
        WxSrc.TOMORROW -> WxProviders.tomorrow(p, key, ::get, now)
        else -> throw IllegalArgumentException("источник $id")
    }

    /**
     * Загружает погоду; пустой город: место определяется по адресу в интернете. Свежее значение (до 30 минут) берётся из памяти.
     * Источник выбирается в настройках виджета: «Авто» пробует Open-Meteo, потом MET Norway, потом wttr.in; при сбое всех возвращается последний удачный ответ из памяти.
     */
    fun load(ctx: Context, city: String, lang: String, force: Boolean = false): Weather? {
        val sp = ctx.getSharedPreferences("tvapps_weather", Context.MODE_PRIVATE)
        val sel = WxSrc.id(ctx)
        val old = cached(ctx, city, lang)
        old?.let { if (!force && System.currentTimeMillis() - it.at < 30 * 60_000L && it.at >= WxSrc.forceAfter(ctx) && (sel == WxSrc.AUTO || it.src == sel)) return it }
        return runCatching {
            val place: WxPlace
            if (city.isBlank()) {
                val p = locateByIp(sp)!!
                place = WxPlace(p.first, p.second, p.third, sp.getString("ip_tz", null))
            } else {
                val xy = Regex("^\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*[,;\\s]\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$").matchEntire(city)
                if (xy != null) {
                    // место задано координатами: «широта, долгота»
                    val la = xy.groupValues[1].toDouble(); val lo = xy.groupValues[2].toDouble()
                    place = WxPlace(la, lo, java.lang.String.format(java.util.Locale.US, "%.2f, %.2f", la, lo))
                } else {
                    // геокодер ищет название на выбранном языке: пробуем язык интерфейса, потом украинский, русский и английский
                    var found: WxPlace? = null
                    try {
                        for (l in listOf(lang, "uk", "ru", "en").distinct()) {
                            val g = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=" + java.net.URLEncoder.encode(city, "UTF-8") + "&count=1&language=" + l))
                            val r = g.optJSONArray("results")?.optJSONObject(0)
                            if (r != null) { found = WxPlace(r.getDouble("latitude"), r.getDouble("longitude"), r.optString("name", city), r.optString("timezone").ifBlank { null }); break }
                        }
                    } catch (e: Exception) { Log.w("TVApps", "геокодер погоды: ${e.message}") }
                    if (found == null) found = nominatim(city, lang)?.let { WxPlace(it.first, it.second, it.third) }
                    place = found ?: throw IllegalStateException("город не найден: " + city)
                }
            }
            val apiKey = WxSrc.key(ctx)
            Log.i("TVApps", "погода: выбран источник $sel, ключ ${if (apiKey.isBlank()) "пуст" else "задан"}")
            val order = if (sel == WxSrc.AUTO || (WxSrc.needsKey(sel) && apiKey.isBlank())) listOf(WxSrc.OPEN_METEO, WxSrc.MET, WxSrc.WTTR) else listOf(sel)
            var j: JSONObject? = null
            var used = 0
            val errs = ArrayList<String>()
            val t0 = System.currentTimeMillis()
            for (id in order) {
                val res = runCatching { fetchFrom(id, place, apiKey, t0) }
                if (res.isSuccess) { j = res.getOrNull(); used = id; break }
                val msg = WxSrc.shortName(id) + ": " + res.exceptionOrNull()?.javaClass?.simpleName + (res.exceptionOrNull()?.message?.let { ": " + it.take(80) } ?: "")
                errs += msg
                Log.w("TVApps", "погода, источник не ответил: $msg")
            }
            val data = j ?: throw IllegalStateException(errs.joinToString("; "))
            data.put("shown", place.name).put("at", System.currentTimeMillis()).put("src", used)
            sp.edit().putString(key(city, lang), data.toString()).apply()
            lastError = null
            Log.i("TVApps", "погода: источник ${WxSrc.shortName(used)}" + (if (errs.isNotEmpty()) " (до него не ответили: ${errs.joinToString("; ")})" else ""))
            parse(data, lang)
        }.onFailure { lastError = it.javaClass.simpleName + (it.message?.let { m -> ": " + m } ?: ""); Log.w("TVApps", "погода: ${it.message}") }.getOrNull() ?: old
    }

    /** Слово о погоде по коду Open-Meteo (WMO). */
    fun descRes(code: Int): Int = when (code) {
        0 -> R.string.w_clear; 1 -> R.string.w_mclear; 2 -> R.string.w_partly; 3 -> R.string.w_overcast
        45, 48 -> R.string.w_fog; 51, 53, 55, 56, 57 -> R.string.w_drizzle; 61, 63, 65, 66, 67 -> R.string.w_rain
        71, 73, 75, 77, 85, 86 -> R.string.w_snow; 80, 81, 82 -> R.string.w_showers; 95, 96, 99 -> R.string.w_thunder
        else -> R.string.w_partly
    }

    fun emoji(code: Int): String = when (code) {
        0 -> "☀️"; 1, 2 -> "🌤️"; 3 -> "☁️"; 45, 48 -> "🌫️"
        51, 53, 55, 56, 57 -> "🌦️"; 61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "❄️"; 95, 96, 99 -> "⛈️"; else -> "🌡️"
    }
}
