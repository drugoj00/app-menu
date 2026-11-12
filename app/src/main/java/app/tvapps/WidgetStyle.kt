package app.tvapps

import android.app.AlarmManager
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Вид виджета часов или погоды на «Доме» (хранится в HWidget.style как JSON, пусто = прежний вид).
 * Идея гибких настроек часов взята у Tablet Clock (MIT, github.com/Nikolay-Kha/TabletClock); код оттуда не использован.
 */
data class WStyle(
    // размеры, % от автоматического (дата и день недели считаются от размера времени: 0,3 и 0,4)
    val timeSz: Int = 100, val dateSz: Int = 100, val weekSz: Int = 100, val secSz: Int = 100, val tempSz: Int = 100, val infoSz: Int = 100,
    // цвета ARGB; 0 — как у темы
    val cTime: Long = 0L, val cDate: Long = 0L, val cWeek: Long = 0L, val cSec: Long = 0L, val cTemp: Long = 0L, val cNeg: Long = 0L, val cInfo: Long = 0L,
    // часы
    val hide0: Boolean = false,
    /** разделитель часов и минут: 0 нет, 1 мигает, 2 постоянный */
    val sep: Int = 2,
    /** день недели отдельной строкой (иначе «среда, 12 ноября» одной строкой) */
    val weekSep: Boolean = false,
    val dateFmt: Int = 0,
    /** цифры времени: 0 обычные, 1 семисегментные */
    val digits: Int = 0,
    /** шрифт: 0 как у темы, 1 без засечек, 2 с засечками, 3 моноширинный, 4 рукописный, 5 узкий, 6 свой файл */
    val font: Int = 0, val fontFile: String = "",
    val bold: Boolean = true, val ghost: Boolean = true, val slant: Boolean = false,
    val sun: Boolean = false, val alarm: Boolean = false, val moon: Boolean = false,
    // погода
    val feels: Boolean = false, val hum: Boolean = false, val press: Boolean = false, val wind: Boolean = true, val desc: Boolean = true,
    val wxSun: Boolean = false, val wxMoon: Boolean = false,
    /** режим нижнего блока прогноза: 0 «5 дней», 1 «По часам», 2 «Неделя» (7 дней); последний выбранный запоминается */
    val view: Int = 0,
    /** какие режимы входят в круг переключения (биты: 1 «5 дней», 2 «По часам», 4 «Неделя»); у прежнего вида только «5 дней» */
    val modes: Int = 1,
    /** шаг часов в режиме «По часам»: 1, 3 или 6 */
    val hourStep: Int = 3,
    /** в режиме «По часам»: показывать ветер и вероятность осадков */
    val hWind: Boolean = false, val hPop: Boolean = false,
    /** единицы: температура 0 °C 1 °F; ветер 0 м/с 1 км/ч 2 миль/ч; давление 0 гПа 1 мм рт. ст. */
    val uTemp: Int = 0, val uWind: Int = 0, val uPress: Int = 0,
    // общее
    val bgAlpha: Int = 100, val shadow: Boolean = false, val outline: Boolean = false,
    /** нажатие: у часов 0 настройки даты системы, 1 настройки вида, 2 будильник; у погоды 0 обновить, 1 день/почасовой */
    val clickT: Int = 0, val clickW: Int = 0,
    /** выбранный готовый набор вида (0 «Классический», у часов 1 «Цифровой», 2 «Крупный»; у погоды 1 «Крупный», 2 «Подробный»): по нему работает «Вернуть стандартные» */
    val preset: Int = 0,
    /** у погоды: всегда показывать подпись «данные от ЧЧ:ММ, источник …» */
    val wxSrc: Boolean = false,
) {
    fun json(): String {
        val d = WStyle()
        if (this == d) return ""
        val o = JSONObject()
        fun i(k: String, v: Int, dv: Int) { if (v != dv) o.put(k, v) }
        fun l(k: String, v: Long, dv: Long) { if (v != dv) o.put(k, v) }
        fun b(k: String, v: Boolean, dv: Boolean) { if (v != dv) o.put(k, v) }
        i("ts", timeSz, d.timeSz); i("ds", dateSz, d.dateSz); i("ws", weekSz, d.weekSz); i("ss", secSz, d.secSz); i("tp", tempSz, d.tempSz); i("is", infoSz, d.infoSz)
        l("ct", cTime, 0); l("cd", cDate, 0); l("cw", cWeek, 0); l("cs", cSec, 0); l("cp", cTemp, 0); l("cn", cNeg, 0); l("ci", cInfo, 0)
        b("h0", hide0, d.hide0); i("sp", sep, d.sep); b("wp", weekSep, d.weekSep); i("df", dateFmt, d.dateFmt)
        i("dg", digits, d.digits); i("ft", font, d.font); if (fontFile != d.fontFile) o.put("ff", fontFile)
        b("bd", bold, d.bold); b("gh", ghost, d.ghost); b("sl", slant, d.slant)
        b("su", sun, d.sun); b("al", alarm, d.alarm); b("mo", moon, d.moon)
        b("fe", feels, d.feels); b("hu", hum, d.hum); b("pr", press, d.press); b("wi", wind, d.wind); b("de", desc, d.desc)
        b("xs", wxSun, d.wxSun); b("xm", wxMoon, d.wxMoon)
        i("vw", view, d.view); i("md", modes, d.modes); i("hs", hourStep, d.hourStep); b("hw", hWind, d.hWind); b("hp", hPop, d.hPop)
        i("ut", uTemp, d.uTemp); i("uw", uWind, d.uWind); i("up", uPress, d.uPress)
        i("ba", bgAlpha, d.bgAlpha); b("sh", shadow, d.shadow); b("ol", outline, d.outline)
        i("kt", clickT, d.clickT); i("kw", clickW, d.clickW); i("ps", preset, d.preset); b("xr", wxSrc, d.wxSrc)
        return o.toString()
    }

    companion object {
        fun parse(s: String): WStyle {
            if (s.isBlank()) return WStyle()
            val d = WStyle()
            return runCatching {
                val o = JSONObject(s)
                WStyle(
                    o.optInt("ts", d.timeSz), o.optInt("ds", d.dateSz), o.optInt("ws", d.weekSz), o.optInt("ss", d.secSz), o.optInt("tp", d.tempSz), o.optInt("is", d.infoSz),
                    o.optLong("ct"), o.optLong("cd"), o.optLong("cw"), o.optLong("cs"), o.optLong("cp"), o.optLong("cn"), o.optLong("ci"),
                    o.optBoolean("h0", d.hide0), o.optInt("sp", d.sep), o.optBoolean("wp", d.weekSep), o.optInt("df", d.dateFmt),
                    o.optInt("dg", d.digits), o.optInt("ft", d.font), o.optString("ff", d.fontFile),
                    o.optBoolean("bd", d.bold), o.optBoolean("gh", d.ghost), o.optBoolean("sl", d.slant),
                    o.optBoolean("su", d.sun), o.optBoolean("al", d.alarm), o.optBoolean("mo", d.moon),
                    o.optBoolean("fe", d.feels), o.optBoolean("hu", d.hum), o.optBoolean("pr", d.press), o.optBoolean("wi", d.wind), o.optBoolean("de", d.desc),
                    o.optBoolean("xs", d.wxSun), o.optBoolean("xm", d.wxMoon),
                    o.optInt("vw", d.view), o.optInt("md", d.modes), o.optInt("hs", d.hourStep), o.optBoolean("hw", d.hWind), o.optBoolean("hp", d.hPop),
                    o.optInt("ut", d.uTemp), o.optInt("uw", d.uWind), o.optInt("up", d.uPress),
                    o.optInt("ba", d.bgAlpha), o.optBoolean("sh", d.shadow), o.optBoolean("ol", d.outline),
                    o.optInt("kt", d.clickT), o.optInt("kw", d.clickW), o.optInt("ps", d.preset), o.optBoolean("xr", d.wxSrc),
                )
            }.getOrDefault(d)
        }

        /** Готовые форматы даты: с днём недели и без него (когда день недели выведен отдельной строкой). */
        val DATE_WITH = listOf("EEEE, d MMMM", "EEEE, d MMMM yyyy", "dd.MM.yyyy", "EEE d MMM", "d MMMM", "d MMMM yyyy")
        val DATE_WITHOUT = listOf("d MMMM", "d MMMM yyyy", "dd.MM.yyyy", "d MMM", "d MMMM", "d MMMM yyyy")
        const val FORMATS = 6

        /** Готовые наборы вида: «Классический» совпадает с прежним видом (пустые настройки). */
        fun preset(type: String, id: Int): WStyle = if (type == "clock") when (id) {
            1 -> WStyle(digits = 1, ghost = true, sep = 1, weekSep = true, preset = 1)                       // цифровой: семисегментные цифры, мигающий разделитель, день недели отдельно
            2 -> WStyle(timeSz = 140, weekSep = true, weekSz = 100, dateSz = 110, bold = true, preset = 2)   // крупный: большое время, день недели и дата отдельными строками
            else -> WStyle()
        } else when (id) {
            // «Как у Tablet Clock»: внизу прогноз «По часам / 5 дней / Неделя», по нажатию режимы переключаются по кругу
            1 -> WStyle(modes = 7, view = 1, hourStep = 3, hPop = true, clickW = 1, preset = 1)
            2 -> WStyle(tempSz = 140, infoSz = 110, preset = 2)                                               // крупный: температура и подписи крупнее
            3 -> WStyle(feels = true, hum = true, press = true, wxSun = true, preset = 3)                      // подробный: ощущаемая, влажность, давление, восход и закат
            else -> WStyle()
        }

        /** Число показываемых величин: у цифрового набора часов включаются секунды (бит 2), остальное без изменений. */
        fun presetN(type: String, id: Int, n: Int): Int = if (type == "clock" && id == 1) (n or 2) else n

        /** Названия наборов по видам виджета. */
        fun presetCount(type: String): Int = if (type == "clock") 3 else 4
    }

    fun datePattern(): String = (if (weekSep) DATE_WITHOUT else DATE_WITH)[dateFmt.coerceIn(0, FORMATS - 1)]

    /** Цвет элемента: 0 — цвет темы [def]. */
    fun col(argb: Long, def: Color): Color = if (argb == 0L) def else Color(argb.toInt())

    fun weight(): FontWeight = if (bold) FontWeight.Bold else FontWeight.Normal

    /** Шрифт цифр и текста виджета; null — как у темы. */
    fun family(): FontFamily? = when (font) {
        1 -> FontFamily.SansSerif
        2 -> FontFamily.Serif
        3 -> FontFamily.Monospace
        4 -> FontFamily.Cursive
        5 -> runCatching { FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)) }.getOrNull()
        6 -> runCatching { FontFamily(android.graphics.Typeface.createFromFile(File(fontFile))) }.getOrNull()
        else -> null
    }
}

/** Следующий включённый режим прогноза в круге (по [modes]); если включён один, возвращает его же. */
internal fun nextForecastMode(view: Int, modes: Int): Int {
    for (d in 1..3) { val m = (view + d) % 3; if ((modes and (1 shl m)) != 0) return m }
    return view
}

/** Единицы и подписи погоды. */
internal object WxUnits {
    fun temp(c: Int, s: WStyle): String = if (s.uTemp == 1) "${(c * 9f / 5f + 32f).roundToInt()}°F" else "$c°"
    fun tempShort(c: Int, s: WStyle): String = if (s.uTemp == 1) "${(c * 9f / 5f + 32f).roundToInt()}°" else "$c°"
    fun wind(ms: Int, s: WStyle): Int = when (s.uWind) { 1 -> (ms * 3.6f).roundToInt(); 2 -> (ms * 2.23694f).roundToInt(); else -> ms }
    fun press(hpa: Int, s: WStyle): Int = if (s.uPress == 1) (hpa * 0.750062f).roundToInt() else hpa
}

/** Фаза луны по дате: 0..1 (0 новая, 0,5 полная) и значок. */
internal object Moon {
    fun phase(now: Long): Float {
        val jd = now / 86400000.0 + 2440587.5
        val p = ((jd - 2451550.1) / 29.530588853)
        return (p - Math.floor(p)).toFloat()
    }
    fun emoji(ph: Float): String = listOf("🌑", "🌒", "🌓", "🌔", "🌕", "🌖", "🌗", "🌘")[((ph * 8f).roundToInt()) % 8]
    fun lit(ph: Float): Int = (((1f - cos(2f * PI.toFloat() * ph)) / 2f) * 100f).roundToInt()
    fun text(now: Long): String { val p = phase(now); return emoji(p) + " " + lit(p) + "%" }
}

/** Ближайший будильник системы: время и подпись «Пн 07:30» либо null. */
internal fun nextAlarmText(ctx: Context, loc: Locale, h12: Boolean): String? = runCatching {
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val t = am.nextAlarmClock?.triggerTime ?: return null
    SimpleDateFormat(if (h12) "EEE h:mm a" else "EEE HH:mm", loc).format(Date(t))
}.getOrNull()

/** Папки, где лежат свои шрифты (.ttf, .otf): своя папка программы и «Загрузки/AppMenu/fonts». */
internal fun fontFolders(ctx: Context): List<File> = listOfNotNull(
    ctx.getExternalFilesDir("fonts"),
    File(android.os.Environment.getExternalStorageDirectory(), "Download/AppMenu/fonts"),
)

internal fun fontFiles(ctx: Context): List<File> = fontFolders(ctx).flatMap { d ->
    runCatching { d.listFiles()?.filter { it.isFile && (it.name.endsWith(".ttf", true) || it.name.endsWith(".otf", true)) }?.toList() }.getOrNull().orEmpty()
}.sortedBy { it.name.lowercase() }
