package app.tvapps

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/*
 * Источники погоды. Каждый приводится к одному внутреннему виду (текущая, по часам, по дням, восход и закат), поэтому режимы
 * «По часам», «5 дней» и «Неделя» работают с любым из них. Ключи пользователя лежат в обычных настройках программы и никуда не отправляются, кроме самого сервиса погоды.
 * Свой код: чистый Kotlin. Данные: Open-Meteo, MET Norway (api.met.no), wttr.in, OpenWeatherMap, WeatherAPI.com, Tomorrow.io.
 */

/** Место: широта, долгота, название, часовой пояс (если геокодер его дал). */
internal data class WxPlace(val lat: Double, val lon: Double, val name: String, val tz: String? = null)

internal object WxSrc {
    const val AUTO = 0
    const val OPEN_METEO = 1
    const val MET = 2
    const val WTTR = 3
    const val OWM = 4
    const val WAPI = 5
    const val TOMORROW = 6
    val IDS = listOf(AUTO, OPEN_METEO, MET, WTTR, OWM, WAPI, TOMORROW)

    /** Меняется при смене источника или ключа: виджеты следят за ним и перезагружают погоду. */
    val rev = mutableIntStateOf(0)

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("tvapps_weather", Context.MODE_PRIVATE)
    fun id(c: Context): Int = sp(c).getInt("src", AUTO).takeIf { it in IDS } ?: AUTO
    fun key(c: Context): String = sp(c).getString("apikey", "").orEmpty()
    fun set(c: Context, id: Int) { sp(c).edit().putInt("src", id).putLong("force_after", System.currentTimeMillis()).apply(); rev.intValue++ }
    fun setKey(c: Context, k: String) { sp(c).edit().putString("apikey", k.trim()).putLong("force_after", System.currentTimeMillis()).apply(); rev.intValue++ }
    fun forceAfter(c: Context): Long = sp(c).getLong("force_after", 0L)
    fun needsKey(id: Int) = id >= OWM

    fun nameRes(id: Int): Int = when (id) {
        OPEN_METEO -> R.string.wsrc_om; MET -> R.string.wsrc_met; WTTR -> R.string.wsrc_wttr; OWM -> R.string.wsrc_owm; WAPI -> R.string.wsrc_wapi; TOMORROW -> R.string.wsrc_tom
        else -> R.string.wsrc_auto
    }
    /** Короткое имя источника для подписи («данные от 14:05, источник …»). */
    fun shortName(id: Int): String = when (id) {
        OPEN_METEO -> "Open-Meteo"; MET -> "MET Norway"; WTTR -> "wttr.in"; OWM -> "OpenWeatherMap"; WAPI -> "WeatherAPI.com"; TOMORROW -> "Tomorrow.io"; else -> "?"
    }
}

internal object WxProviders {
    private const val UA = "AppMenu/5.3 (Android launcher weather widget)"

    // ------------------------------------------------------------------ общие помощники

    private fun r(d: Double) = Math.round(d).toInt()
    private val utc = TimeZone.getTimeZone("UTC")

    private fun parseUtc(s: String): Long = runCatching {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US); f.timeZone = utc
        f.parse(s.take(19))!!.time
    }.getOrDefault(0L)

    private fun zoneOf(p: WxPlace): TimeZone = p.tz?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault()
    private fun fmt(pattern: String, zone: TimeZone, t: Long): String { val f = SimpleDateFormat(pattern, Locale.US); f.timeZone = zone; return f.format(java.util.Date(t)) }

    /** «07:03 AM» → «07:03». */
    private fun to24(s: String): String = runCatching {
        SimpleDateFormat("HH:mm", Locale.US).format(SimpleDateFormat("hh:mm a", Locale.US).parse(s.trim())!!)
    }.getOrDefault("")

    /** Ощущаемая температура по температуре, влажности и ветру (формула Steadman), когда источник её не даёт. */
    private fun apparent(t: Double, rh: Double, windMs: Double): Double {
        val e = rh / 100.0 * 6.105 * exp(17.27 * t / (237.7 + t))
        return t + 0.33 * e - 0.70 * windMs - 4.00
    }

    /** Восход и закат (алгоритм NOAA), «ЧЧ:ММ» по часовому поясу места; null — полярный день или ночь. */
    private fun sunTimes(lat: Double, lon: Double, zone: TimeZone, at: Long): Pair<String, String>? {
        val cal = Calendar.getInstance(zone); cal.timeInMillis = at
        val n = cal.get(Calendar.DAY_OF_YEAR)
        fun calc(rising: Boolean): String? {
            val lngHour = lon / 15.0
            val t = n + ((if (rising) 6.0 else 18.0) - lngHour) / 24.0
            val m = 0.9856 * t - 3.289
            val mr = Math.toRadians(m)
            var l = m + 1.916 * sin(mr) + 0.020 * sin(2 * mr) + 282.634
            l = ((l % 360) + 360) % 360
            var ra = Math.toDegrees(atan(0.91764 * tan(Math.toRadians(l))))
            ra = ((ra % 360) + 360) % 360
            ra += floor(l / 90) * 90 - floor(ra / 90) * 90
            ra /= 15.0
            val sinDec = 0.39782 * sin(Math.toRadians(l))
            val cosDec = cos(asin(sinDec))
            val cosH = (cos(Math.toRadians(90.833)) - sinDec * sin(Math.toRadians(lat))) / (cosDec * cos(Math.toRadians(lat)))
            if (cosH > 1 || cosH < -1) return null
            var h = Math.toDegrees(acos(cosH)); if (rising) h = 360 - h
            h /= 15.0
            val tt = h + ra - 0.06571 * t - 6.622
            val ut = (((tt - lngHour) % 24) + 24) % 24
            val off = zone.getOffset(at) / 3_600_000.0
            val loc = (((ut + off) % 24) + 24) % 24
            val hh = loc.toInt(); val mm = ((loc - hh) * 60).roundToInt()
            return String.format(Locale.US, "%02d:%02d", if (mm == 60) (hh + 1) % 24 else hh, if (mm == 60) 0 else mm)
        }
        val a = calc(true) ?: return null
        val b = calc(false) ?: return null
        return a to b
    }

    private fun hourObj(label: String, code: Int, t: Double, w: Double?, pop: Int?): JSONObject =
        JSONObject().put("l", label).put("c", code).put("x", r(t)).also { if (w != null) it.put("w", r(w)); if (pop != null) it.put("p", pop) }

    private fun dayObj(date: String, code: Int, max: Double, min: Double): JSONObject = JSONObject().put("d", date).put("c", code).put("x", r(max)).put("n", r(min))

    /** Общая сборка в формат кэша (см. WeatherData.parse). */
    private fun assemble(t: Double, code: Int, windMs: Double, days: JSONArray, feels: Double?, hum: Double?, press: Double?, sr: String?, ss: String?, hours: JSONArray): JSONObject {
        val j = JSONObject().put("t", r(t)).put("c", code).put("w", r(windMs)).put("days", days)
        if (feels != null) j.put("fe", r(feels))
        if (hum != null) j.put("hu", r(hum))
        if (press != null) j.put("pr", r(press))
        if (!sr.isNullOrEmpty()) j.put("sr", sr)
        if (!ss.isNullOrEmpty()) j.put("ss", ss)
        j.put("hh", hours)
        return j
    }

    // ------------------------------------------------------------------ MET Norway (yr.no)

    private fun metCode(s: String): Int {
        val c = s.substringBefore("_")
        return when {
            c.contains("thunder") -> 95
            c.contains("sleet") -> 67
            c.contains("snow") -> if (c.startsWith("light")) 71 else if (c.startsWith("heavy")) 75 else 73
            c.contains("showers") -> if (c.startsWith("heavy")) 82 else if (c.startsWith("light")) 80 else 81
            c.contains("rain") -> if (c.startsWith("heavy")) 65 else if (c.startsWith("light")) 61 else 63
            c.contains("fog") -> 45
            c == "clearsky" -> 0
            c == "fair" -> 1
            c == "partlycloudy" -> 2
            c == "cloudy" -> 3
            else -> 2
        }
    }

    fun met(p: WxPlace, get: (String, Map<String, String>) -> String, now: Long): JSONObject {
        val url = String.format(Locale.US, "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f", p.lat, p.lon)
        val ts = JSONObject(get(url, mapOf("User-Agent" to UA))).getJSONObject("properties").getJSONArray("timeseries")
        val zone = zoneOf(p)
        class E(val t: Long, val temp: Double, val rh: Double, val press: Double, val wind: Double, val sym: String)
        val es = ArrayList<E>()
        for (i in 0 until ts.length()) {
            val o = ts.getJSONObject(i)
            val d = o.getJSONObject("data"); val det = d.getJSONObject("instant").getJSONObject("details")
            val sym = listOf("next_1_hours", "next_6_hours", "next_12_hours").firstNotNullOfOrNull { k -> d.optJSONObject(k)?.optJSONObject("summary")?.optString("symbol_code") } ?: ""
            es += E(parseUtc(o.getString("time")), det.optDouble("air_temperature"), det.optDouble("relative_humidity", Double.NaN), det.optDouble("air_pressure_at_sea_level", Double.NaN), det.optDouble("wind_speed", 0.0), sym)
        }
        val cur = es.firstOrNull() ?: error("пустой ответ MET")
        val rh = cur.rh.takeIf { !it.isNaN() }
        val hours = JSONArray()
        for (e in es) { if (hours.length() >= 36) break; if (e.t >= now - 3_000_000L) hours.put(hourObj(fmt("HH", zone, e.t), metCode(e.sym), e.temp, e.wind, null)) }
        // дни: максимум и минимум по местной дате; значок по записи, ближайшей к полудню
        val byDay = LinkedHashMap<String, MutableList<E>>()
        for (e in es) byDay.getOrPut(fmt("yyyy-MM-dd", zone, e.t)) { ArrayList() } += e
        val days = JSONArray()
        for ((date, list) in byDay) {
            if (days.length() >= 7) break
            val noon = list.minByOrNull { Math.abs(fmt("HH", zone, it.t).toInt() - 12) }!!
            days.put(dayObj(date, metCode(noon.sym), list.maxOf { it.temp }, list.minOf { it.temp }))
        }
        val sun = sunTimes(p.lat, p.lon, zone, now)
        return assemble(cur.temp, metCode(cur.sym), cur.wind, days, if (rh != null) apparent(cur.temp, rh, cur.wind) else null, rh, cur.press.takeIf { !it.isNaN() }, sun?.first, sun?.second, hours)
    }

    // ------------------------------------------------------------------ wttr.in

    private fun wwoCode(c: Int): Int = when (c) {
        113 -> 0; 116 -> 2; 119, 122 -> 3
        143, 248, 260 -> 45
        200, 386, 389, 392, 395 -> 95
        176, 263, 266, 293, 296, 353 -> 61
        299, 302, 305, 308, 356, 359 -> if (c >= 305) 65 else 63
        185, 281, 284, 311, 314, 317, 320, 350, 362, 365, 374, 377 -> 67
        179, 227, 230, 323, 326, 329, 332, 335, 338, 368, 371 -> if (c == 230 || c == 338 || c == 335) 75 else if (c == 323 || c == 326 || c == 368) 71 else 73
        182 -> 67
        else -> 2
    }

    fun wttr(p: WxPlace, get: (String, Map<String, String>) -> String, now: Long): JSONObject {
        val j = JSONObject(get(String.format(Locale.US, "https://wttr.in/%.4f,%.4f?format=j1", p.lat, p.lon), mapOf("User-Agent" to "curl/8.0 AppMenu")))
        val cc = j.getJSONArray("current_condition").getJSONObject(0)
        val wk = j.getJSONArray("weather")
        val zone = zoneOf(p)
        val days = JSONArray(); val hours = JSONArray()
        val nowHourKey = fmt("yyyy-MM-dd HH", zone, now)
        for (i in 0 until wk.length()) {
            val d = wk.getJSONObject(i)
            val hs = d.getJSONArray("hourly")
            // значок дня: дневной слот (около 12:00)
            val noon = hs.optJSONObject(hs.length() / 2)
            days.put(dayObj(d.getString("date"), wwoCode(noon?.optString("weatherCode")?.toIntOrNull() ?: 116), d.getString("maxtempC").toDouble(), d.getString("mintempC").toDouble()))
            for (k in 0 until hs.length()) {
                val h = hs.getJSONObject(k)
                val hr = h.getString("time").toInt() / 100
                val key = d.getString("date") + " " + String.format(Locale.US, "%02d", hr)
                if (key >= nowHourKey && hours.length() < 36)
                    hours.put(hourObj(String.format(Locale.US, "%02d", hr), wwoCode(h.optString("weatherCode").toIntOrNull() ?: 116), h.getString("tempC").toDouble(), h.optString("windspeedKmph").toDoubleOrNull()?.div(3.6), h.optString("chanceofrain").toIntOrNull()))
            }
        }
        val astro = wk.getJSONObject(0).optJSONArray("astronomy")?.optJSONObject(0)
        val wind = cc.optString("windspeedKmph").toDoubleOrNull()?.div(3.6) ?: 0.0
        return assemble(cc.getString("temp_C").toDouble(), wwoCode(cc.optString("weatherCode").toIntOrNull() ?: 116), wind, days,
            cc.optString("FeelsLikeC").toDoubleOrNull(), cc.optString("humidity").toDoubleOrNull(), cc.optString("pressure").toDoubleOrNull(),
            to24(astro?.optString("sunrise").orEmpty()), to24(astro?.optString("sunset").orEmpty()), hours)
    }

    // ------------------------------------------------------------------ OpenWeatherMap (свой ключ)

    private fun owmCode(id: Int): Int = when {
        id in 200..232 -> 95
        id in 300..321 -> if (id >= 313) 80 else 51
        id == 500 -> 61; id == 501 -> 63; id in 502..504 -> 65; id == 511 -> 67
        id in 520..531 -> if (id == 520) 80 else if (id == 521) 81 else 82
        id in 611..616 -> 67
        id == 600 || id == 620 -> 71; id == 601 || id == 621 -> 73; id in 602..622 -> 75
        id in 701..781 -> 45
        id == 800 -> 0; id == 801 -> 1; id == 802 -> 2; id == 803 || id == 804 -> 3
        else -> 2
    }

    fun owm(p: WxPlace, key: String, get: (String, Map<String, String>) -> String, now: Long): JSONObject {
        val q = String.format(Locale.US, "lat=%.4f&lon=%.4f&units=metric&appid=%s", p.lat, p.lon, java.net.URLEncoder.encode(key, "UTF-8"))
        val c = JSONObject(get("https://api.openweathermap.org/data/2.5/weather?$q", emptyMap()))
        val f = JSONObject(get("https://api.openweathermap.org/data/2.5/forecast?$q", emptyMap()))
        val zone: TimeZone = java.util.SimpleTimeZone(c.optInt("timezone", 0) * 1000, "owm")
        val list = f.getJSONArray("list")
        val hours = JSONArray()
        class E(val t: Long, val temp: Double, val code: Int)
        val es = ArrayList<E>()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val t = o.getLong("dt") * 1000
            val code = owmCode(o.getJSONArray("weather").getJSONObject(0).getInt("id"))
            val temp = o.getJSONObject("main").getDouble("temp")
            es += E(t, temp, code)
            if (t >= now - 5_400_000L && hours.length() < 36)
                hours.put(hourObj(fmt("HH", zone, t), code, temp, o.optJSONObject("wind")?.optDouble("speed"), if (o.has("pop")) r(o.getDouble("pop") * 100) else null))
        }
        val byDay = LinkedHashMap<String, MutableList<E>>()
        for (e in es) byDay.getOrPut(fmt("yyyy-MM-dd", zone, e.t)) { ArrayList() } += e
        val days = JSONArray()
        for ((date, l) in byDay) { val noon = l.minByOrNull { Math.abs(fmt("HH", zone, it.t).toInt() - 12) }!!; days.put(dayObj(date, noon.code, l.maxOf { it.temp }, l.minOf { it.temp })) }
        val m = c.getJSONObject("main")
        val sys = c.optJSONObject("sys")
        return assemble(m.getDouble("temp"), owmCode(c.getJSONArray("weather").getJSONObject(0).getInt("id")), c.optJSONObject("wind")?.optDouble("speed", 0.0) ?: 0.0, days,
            m.optDouble("feels_like").takeIf { !it.isNaN() }, m.optDouble("humidity").takeIf { !it.isNaN() }, m.optDouble("pressure").takeIf { !it.isNaN() },
            sys?.optLong("sunrise")?.takeIf { it > 0 }?.let { fmt("HH:mm", zone, it * 1000) }, sys?.optLong("sunset")?.takeIf { it > 0 }?.let { fmt("HH:mm", zone, it * 1000) }, hours)
    }

    // ------------------------------------------------------------------ WeatherAPI.com (свой ключ)

    private fun wapiCode(c: Int): Int = when (c) {
        1000 -> 0; 1003 -> 2; 1006, 1009 -> 3
        1030, 1135, 1147 -> 45
        1087, 1273, 1276, 1279, 1282 -> 95
        1063, 1150, 1153, 1180, 1183 -> 61; 1186, 1189 -> 63; 1192, 1195 -> 65
        1240 -> 80; 1243 -> 81; 1246 -> 82
        1168, 1171, 1198, 1201, 1069, 1204, 1207, 1249, 1252, 1072 -> 67
        1066, 1210, 1213, 1255 -> 71; 1216, 1219, 1258 -> 73; 1114, 1117, 1222, 1225, 1237, 1261, 1264 -> 75
        else -> 2
    }

    fun wapi(p: WxPlace, key: String, get: (String, Map<String, String>) -> String, now: Long): JSONObject {
        val j = JSONObject(get(String.format(Locale.US, "https://api.weatherapi.com/v1/forecast.json?key=%s&q=%.4f,%.4f&days=7&aqi=no&alerts=no", java.net.URLEncoder.encode(key, "UTF-8"), p.lat, p.lon), emptyMap()))
        val c = j.getJSONObject("current")
        val tz = TimeZone.getTimeZone(j.optJSONObject("location")?.optString("tz_id").orEmpty().ifEmpty { zoneOf(p).id })
        val fd = j.getJSONObject("forecast").getJSONArray("forecastday")
        val days = JSONArray(); val hours = JSONArray()
        val nowKey = fmt("yyyy-MM-dd HH", tz, now)
        for (i in 0 until fd.length()) {
            val d = fd.getJSONObject(i); val day = d.getJSONObject("day")
            days.put(dayObj(d.getString("date"), wapiCode(day.getJSONObject("condition").getInt("code")), day.getDouble("maxtemp_c"), day.getDouble("mintemp_c")))
            val hs = d.getJSONArray("hour")
            for (k in 0 until hs.length()) {
                val h = hs.getJSONObject(k)
                val tm = h.getString("time")
                if (tm.take(13) >= nowKey && hours.length() < 36) hours.put(hourObj(tm.substring(11, 13), wapiCode(h.getJSONObject("condition").getInt("code")), h.getDouble("temp_c"), h.optDouble("wind_kph", 0.0) / 3.6, h.optInt("chance_of_rain", 0)))
            }
        }
        val astro = fd.getJSONObject(0).optJSONObject("astro")
        return assemble(c.getDouble("temp_c"), wapiCode(c.getJSONObject("condition").getInt("code")), c.optDouble("wind_kph", 0.0) / 3.6, days,
            c.optDouble("feelslike_c").takeIf { !it.isNaN() }, c.optDouble("humidity").takeIf { !it.isNaN() }, c.optDouble("pressure_mb").takeIf { !it.isNaN() },
            to24(astro?.optString("sunrise").orEmpty()), to24(astro?.optString("sunset").orEmpty()), hours)
    }

    // ------------------------------------------------------------------ Tomorrow.io (свой ключ)

    private fun tomCode(c: Int): Int = when (c) {
        1000 -> 0; 1100 -> 1; 1101 -> 2; 1102, 1001 -> 3
        2000, 2100 -> 45
        4000 -> 51; 4200 -> 61; 4001 -> 63; 4201 -> 65
        5001, 5100 -> 71; 5000 -> 73; 5101 -> 75
        6000, 6001, 6200, 6201, 7000, 7101, 7102 -> 67
        8000 -> 95
        else -> 2
    }

    fun tomorrow(p: WxPlace, key: String, get: (String, Map<String, String>) -> String, now: Long): JSONObject {
        val j = JSONObject(get(String.format(Locale.US, "https://api.tomorrow.io/v4/weather/forecast?location=%.4f,%.4f&timesteps=1h,1d&units=metric&apikey=%s", p.lat, p.lon, java.net.URLEncoder.encode(key, "UTF-8")), emptyMap()))
        val tl = j.getJSONObject("timelines")
        val hs = tl.getJSONArray("hourly"); val ds = tl.getJSONArray("daily")
        val zone = zoneOf(p)
        val hours = JSONArray()
        var cur: JSONObject? = null
        for (i in 0 until hs.length()) {
            val o = hs.getJSONObject(i); val t = parseUtc(o.getString("time")); val v = o.getJSONObject("values")
            if (t >= now - 3_000_000L) {
                if (cur == null) cur = v
                if (hours.length() < 36) hours.put(hourObj(fmt("HH", zone, t), tomCode(v.optInt("weatherCode")), v.getDouble("temperature"), v.optDouble("windSpeed", 0.0), if (v.has("precipitationProbability")) v.optInt("precipitationProbability") else null))
            }
        }
        val c = cur ?: error("пустой ответ Tomorrow.io")
        val days = JSONArray()
        var sr: String? = null; var ss: String? = null
        for (i in 0 until minOf(ds.length(), 7)) {
            val o = ds.getJSONObject(i); val v = o.getJSONObject("values"); val t = parseUtc(o.getString("time"))
            days.put(dayObj(fmt("yyyy-MM-dd", zone, t + 6 * 3_600_000L), tomCode(v.optInt("weatherCodeMax", v.optInt("weatherCode"))), v.getDouble("temperatureMax"), v.getDouble("temperatureMin")))
            if (i == 0) { sr = v.optString("sunriseTime").takeIf { it.length >= 19 }?.let { fmt("HH:mm", zone, parseUtc(it)) }; ss = v.optString("sunsetTime").takeIf { it.length >= 19 }?.let { fmt("HH:mm", zone, parseUtc(it)) } }
        }
        return assemble(c.getDouble("temperature"), tomCode(c.optInt("weatherCode")), c.optDouble("windSpeed", 0.0), days,
            c.optDouble("temperatureApparent").takeIf { !it.isNaN() }, c.optDouble("humidity").takeIf { !it.isNaN() }, c.optDouble("pressureSurfaceLevel").takeIf { !it.isNaN() }, sr, ss, hours)
    }
}
