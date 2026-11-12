package app.tvapps

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val WIDGET_TYPES = listOf("weather", "clock", "date", "storage", "ram")

/** Пустое место на месте удалённого виджета: ничего не рисуется, долгое нажатие открывает меню (там можно выбрать вид виджета). */
const val WIDGET_EMPTY = "empty"

@Composable
internal fun widgetName(type: String): String = stringResource(
    when (type) {
        "weather" -> R.string.wg_weather; "clock" -> R.string.wg_clock; "date" -> R.string.wg_date; "empty" -> R.string.wg_empty
        "storage" -> R.string.wg_storage; "appwidget" -> R.string.wg_android; else -> R.string.wg_ram
    },
)

private fun loc(): Locale = AppLoc.locale()

/** Нажатие на виджет: часы и дата ведут в настройки даты, память в настройки хранилища, погода обновляется. */
/** Проверки Android-виджетов по номеру. */
internal fun AppWidgets.alive(ctx: Context, ref: String): Boolean =
    ref.toIntOrNull()?.let { runCatching { android.appwidget.AppWidgetManager.getInstance(ctx).getAppWidgetInfo(it) != null }.getOrDefault(false) } ?: false

internal fun AppWidgets.providerOf(ctx: Context, ref: String): String? =
    ref.toIntOrNull()?.let { runCatching { android.appwidget.AppWidgetManager.getInstance(ctx).getAppWidgetInfo(it)?.provider?.flattenToString() }.getOrNull() }

internal fun widgetClick(ctx: Context, w: HWidget, refresh: () -> Unit) {
    when (w.type) {
        // у часов можно выбрать нажатие «будильник» (настройки вида открываются в строке «Дома», где есть окна)
        "clock" -> if (WStyle.parse(w.style).clickT == 2) openSys(ctx, android.provider.AlarmClock.ACTION_SHOW_ALARMS) else openSys(ctx, Settings.ACTION_DATE_SETTINGS)
        "date" -> openSys(ctx, Settings.ACTION_DATE_SETTINGS)
        // «Память»: ОК или щелчок мышью чистит память (как кнопка очистки), «Хранилище» открывает настройки хранилища
        "ram" -> MemoryCleaner.cleanAsync(ctx, refresh)
        "storage" -> openSys(ctx, Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        else -> refresh()
    }
}

/** Содержимое одной ячейки виджетов. */
@Composable
internal fun WidgetBody(w: HWidget, tick: Int, refreshTick: Int, onLong: () -> Unit = {}) {
    when (w.type) {
        "empty" -> {}
        "weather" -> WeatherBody(w, tick, refreshTick)
        "clock" -> ClockBody(w)
        "date" -> DateBody()
        "appwidget" -> AppWidgetBody(w, onLong)
        "storage" -> MemBody(w.type, refreshTick)
        else -> MemBody(w.type, refreshTick)
    }
}

/** Тень или контур текста виджета (по настройкам вида). */
internal fun WStyle.textShadow(light: Boolean): androidx.compose.ui.graphics.Shadow? = when {
    outline -> androidx.compose.ui.graphics.Shadow(if (light) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White, androidx.compose.ui.geometry.Offset.Zero, 10f)
    shadow -> androidx.compose.ui.graphics.Shadow(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.65f), androidx.compose.ui.geometry.Offset(2f, 2f), 5f)
    else -> null
}

@Composable
private fun ClockBody(w: HWidget) {
    val t = LocalTh.current
    val ctx = LocalContext.current
    val s = remember(w.style) { WStyle.parse(w.style) }
    val seconds = (w.n and 2) != 0
    val h12 = (w.n and 1) != 0
    val now = rememberNow(seconds || s.sep == 1)
    val fam = remember(s.font, s.fontFile) { s.family() }
    val sh = s.textShadow(t.dark)
    val base = androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = fam, shadow = sh)
    val lang = AppLoc.code()
    // восход и закат: из данных погоды (место: город этого виджета, пусто — по адресу в интернете)
    val wx by produceState<Weather?>(if (s.sun) WeatherData.cached(ctx, w.city) else null, s.sun, w.city, lang) {
        if (!s.sun) { value = null; return@produceState }
        val old = value
        value = withContext(Dispatchers.IO) { WeatherData.load(ctx, w.city, lang) ?: old }
    }
    val alarm = if (s.alarm) nextAlarmText(ctx, loc(), h12) else null
    val extra = (if (s.sun) 1 else 0) + (if (s.alarm && alarm != null) 1 else 0) + (if (s.moon) 1 else 0) + (if (s.weekSep && w.flag) 1 else 0)
    BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
        // дополнительные строки уменьшают всё поровну, чтобы помещалось (без них размер прежний)
        val fit = 1f / (1f + 0.3f * extra)
        val big = (maxHeight.value * 0.42f).coerceIn(20f, 64f) * fit * s.timeSz / 100f
        val unit = (maxHeight.value * 0.42f).coerceIn(20f, 64f) * fit * s.timeSz / 100f
        val cTime = s.col(s.cTime, t.text)
        val cSec = s.col(s.cSec, cTime)
        val cDate = s.col(s.cDate, t.sub)
        val cWeek = s.col(s.cWeek, t.sub)
        val fmt = { p: String -> SimpleDateFormat(p, loc()).format(Date(now)) }
        val hours = fmt(if (h12) "h" else if (s.hide0) "H" else "HH")
        val mins = fmt("mm")
        val secs = fmt("ss")
        val ampm = if (h12) fmt("a") else ""
        val sepOn = s.sep == 2 || (s.sep == 1 && (now / 1000) % 2 == 0L)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (s.digits == 1) {
                val hDp = with(androidx.compose.ui.platform.LocalDensity.current) { (big * 0.92f).sp.toDp() }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SevenSegText(hours + (if (s.sep == 0) " " else ":") + mins, hDp, cTime, s.ghost, s.slant, colonHidden = !sepOn)
                    if (seconds) SevenSegText(secs, hDp * 0.55f, cSec, s.ghost, s.slant)
                    if (ampm.isNotEmpty()) Text(ampm, color = cTime, fontSize = (big * 0.3f).sp, fontWeight = FontWeight.Medium, maxLines = 1, style = base)
                }
            } else {
                val txt = androidx.compose.ui.text.buildAnnotatedString {
                    append(hours)
                    if (s.sep == 0) append(" ") else {
                        if (sepOn) append(":") else { pushStyle(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color.Transparent)); append(":"); pop() }
                    }
                    append(mins)
                    val small = androidx.compose.ui.text.SpanStyle(fontSize = (0.55 * s.secSz / 100.0).em, fontWeight = FontWeight.Medium)
                    if (seconds) { pushStyle(small.copy(color = cSec)); append(":" + secs); pop() }
                    if (ampm.isNotEmpty()) { pushStyle(androidx.compose.ui.text.SpanStyle(fontSize = 0.55.em, fontWeight = FontWeight.Medium)); append(" " + ampm); pop() }
                }
                Text(txt, color = cTime, fontSize = big.sp, fontWeight = s.weight(), maxLines = 1,
                    style = base.copy(fontFeatureSettings = "tnum"))
            }
            if (w.flag) {
                if (s.weekSep) Text(fmt("EEEE"), color = cWeek, fontSize = (unit * 0.4f * s.weekSz / 100f).coerceAtLeast(11f).sp, maxLines = 1, style = base)
                Text(fmt(s.datePattern()), color = cDate, fontSize = (unit * 0.3f * s.dateSz / 100f).coerceAtLeast(11f).sp, maxLines = 1, style = base)
            }
            val small = (unit * 0.26f).coerceAtLeast(11f).sp
            val wd = wx
            if (s.sun && wd != null && wd.sunrise.isNotEmpty()) Text("🌅 ${wd.sunrise}   🌇 ${wd.sunset}", color = cDate, fontSize = small, maxLines = 1, style = base)
            if (s.alarm && alarm != null) Text("⏰ $alarm", color = cDate, fontSize = small, maxLines = 1, style = base)
            if (s.moon) Text(Moon.text(now), color = cDate, fontSize = small, maxLines = 1, style = base)
        }
    }
}

@Composable
private fun DateBody() {
    val t = LocalTh.current
    val now = rememberNow()
    BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
        val big = (maxHeight.value * 0.5f).coerceIn(20f, 72f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(SimpleDateFormat("d", loc()).format(Date(now)), color = t.accent, fontSize = big.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(SimpleDateFormat("EEEE, MMMM", loc()).format(Date(now)), color = t.sub, fontSize = (big * 0.28f).coerceAtLeast(11f).sp, maxLines = 1)
        }
    }
}

private fun fmtGb(b: Long) = String.format(Locale.US, "%.1f", b / 1073741824.0)

@Composable
private fun MemBody(type: String, refreshTick: Int = 0) {
    val t = LocalTh.current
    val ctx = LocalContext.current
    val info = remember(type, refreshTick) {
        if (type == "storage") {
            val s = StatFs(Environment.getDataDirectory().path)
            s.totalBytes to s.availableBytes
        } else {
            val mi = ActivityManager.MemoryInfo()
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            mi.totalMem to mi.availMem
        }
    }
    val used = (info.first - info.second).coerceAtLeast(0L)
    val frac = if (info.first > 0) used.toFloat() / info.first else 0f
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) {
        Text(stringResource(if (type == "storage") R.string.wg_storage else R.string.wg_ram), color = t.sub, fontSize = 13.sp)
        Text(fmtGb(info.second) + " / " + fmtGb(info.first) + " GB", color = t.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(stringResource(R.string.wg_free), color = t.hint, fontSize = 12.sp)
        Row(Modifier.padding(top = 8.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(t.border)) {
            Row(Modifier.fillMaxHeight().fillMaxWidth(frac.coerceIn(0.02f, 1f)).background(t.accent)) {}
        }
    }
}

@Composable
private fun WeatherBody(w: HWidget, tick: Int, refreshTick: Int) {
    val t = LocalTh.current
    val ctx = LocalContext.current
    val lang = AppLoc.code()
    val s = remember(w.style) { WStyle.parse(w.style) }
    val ts = androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = remember(s.font, s.fontFile) { s.family() }, shadow = s.textShadow(t.dark))
    // дополнительные данные (ощущаемая, влажность, давление, восход и закат, почасовой прогноз) нужны только если включены; у кэша прежних версий их нет: один раз грузим заново
    val needExtra = s.feels || s.hum || s.press || s.wxSun || s.view == 1 || (s.modes and 2) != 0
    val triedExtra = remember(w.city, lang) { booleanArrayOf(false) }
    val seen = remember { intArrayOf(0) }
    // после первой попытки без результата вместо вечной «Загрузка…» показывается причина (сертификаты, нет сети, город не найден)
    var failed by remember(w.city, lang) { androidx.compose.runtime.mutableStateOf(false) }
    val srcRev = WxSrc.rev.intValue
    val weather by produceState<Weather?>(WeatherData.cached(ctx, w.city), tick, refreshTick, w.city, lang, srcRev) {
        val old = value
        val wantExtra = needExtra && old != null && !old.hasExtra && !triedExtra[0]
        if (wantExtra) triedExtra[0] = true
        val force = refreshTick != seen[0] || wantExtra
        seen[0] = refreshTick
        value = withContext(Dispatchers.IO) { WeatherData.load(ctx, w.city, lang, force) ?: old }
        failed = value == null
    }
    val wd = weather
    if (wd == null) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.wg_weather), color = t.sub, fontSize = 13.sp)
            if (failed) {
                Text(stringResource(R.string.wg_weather_err), color = t.hint, fontSize = 13.sp, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                val why = WeatherData.lastError
                if (!why.isNullOrBlank()) Text(why, color = t.hint, fontSize = 11.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            } else Text(stringResource(R.string.wg_loading), color = t.hint, fontSize = 14.sp)
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
        val bh = maxHeight.value
        val big = (bh * 0.30f).coerceIn(20f, 46f)
        val mw = maxWidth
        // описание («пасмурно») показывается, пока хватает места; в маленьком виджете оно пропадает само
        val portrait = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        // в портрете описание не показывается совсем: оно бывает длинным и вытесняет остальное
        val showDesc = !portrait && mw.value >= 420f && bh >= 80f
        // широкая плитка в ландшафте и на ТВ: крупный значок погоды слева и шрифты побольше (портрет не трогаем)
        val wide = !portrait && mw.value >= 560f
        val k = if (wide) (bh / 96f).coerceIn(1.15f, 1.45f) else 1f
        val leftW = if (wide) (if (mw.value >= 760f) 330f else 250f) else minOf(if (showDesc) 165f else 124f, mw.value * 0.34f).coerceAtLeast(80f)
        // на день нужно около 58 dp (в широкой плитке 66 dp); оставшееся место делится между днями поровну
        // все выбранные дни помещаются всегда: чем уже плитка, тем мельче значки и подписи (но не меньше читаемого)
        // подпись «данные от ЧЧ:ММ, источник …»: по настройке, а также когда данные старше полутора часов (источник не отвечает)
        val stale = System.currentTimeMillis() - wd.at > 90 * 60_000L
        if ((s.wxSrc || stale) && bh >= 60f) Text(
            stringResource(R.string.wsrc_note, SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(wd.at)), WxSrc.shortName(if (wd.src == 0) WxSrc.OPEN_METEO else wd.src)),
            color = if (stale) t.accent else t.hint, fontSize = 9.sp, maxLines = 1, softWrap = false, modifier = Modifier.align(Alignment.BottomEnd),
        )
        val mode = if ((s.modes and (1 shl s.view.coerceIn(0, 2))) != 0) s.view.coerceIn(0, 2) else (0..2).firstOrNull { (s.modes and (1 shl it)) != 0 } ?: 0
        val nWant = if (w.n == 0) 0 else when (mode) { 2 -> 7; 1 -> 8; else -> w.n }
        val daysFit = (if (mw.value - leftW - 12f >= 26f * nWant) nWant else ((mw.value - leftW - 12f) / 26f).toInt()).coerceIn(0, nWant)
        // ширина слота одного дня: значок и подписи подгоняются под неё, чтобы пять дней всегда влезали
        val dayW = if (daysFit > 0) (mw.value - leftW - 12f) / daysFit else 0f
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(if (daysFit > 0 || showDesc) leftW.dp else mw), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (wide) WxIcon(wd.code, (bh * 0.50f).coerceIn(40f, 64f))
                Column(Modifier.weight(1f, fill = false)) {
                    val cInfo = s.col(s.cInfo, t.sub)
                    val tempColor = if (wd.temp < 0 && s.cNeg != 0L) s.col(s.cNeg, t.text) else s.col(s.cTemp, t.text)
                    Text(wd.city + (if (showDesc && s.desc) " · " + stringResource(WeatherData.descRes(wd.code)) else ""), color = cInfo, fontSize = (15f * k * s.infoSz / 100f).sp, maxLines = if (bh >= 120f) 2 else 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = ts)
                    Text((if (wide) "" else WeatherData.emoji(wd.code) + " ") + WxUnits.temp(wd.temp, s), color = tempColor, fontSize = (big * (if (wide) 1.1f else 1f) * s.tempSz / 100f).sp, maxLines = 1, style = ts)
                    val windUnit = stringResource(when (s.uWind) { 1 -> R.string.ws_uw1s; 2 -> R.string.ws_uw2s; else -> R.string.home_wind })
                    val pressUnit = stringResource(if (s.uPress == 1) R.string.ws_up1s else R.string.ws_up0s)
                    val feelsLabel = stringResource(R.string.ws_feels_short)
                    val parts = buildList {
                        if (s.wind) add("${WxUnits.wind(wd.wind, s)} $windUnit")
                        if (s.feels && wd.feels != Weather.NA) add(feelsLabel + " " + WxUnits.tempShort(wd.feels, s))
                        if (s.hum && wd.hum != Weather.NA) add("💧 ${wd.hum}%")
                        if (s.press && wd.press != Weather.NA) add("${WxUnits.press(wd.press, s)} $pressUnit")
                    }
                    if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = if (s.cInfo != 0L) cInfo else t.hint, fontSize = (14f * k * s.infoSz / 100f).sp, maxLines = if (bh >= 120f) 2 else 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = ts)
                    val sunMoon = buildList {
                        if (s.wxSun && wd.sunrise.isNotEmpty()) add("🌅 ${wd.sunrise}  🌇 ${wd.sunset}")
                        if (s.wxMoon) add(Moon.text(System.currentTimeMillis()))
                    }
                    if (sunMoon.isNotEmpty() && bh >= 100f) Text(sunMoon.joinToString("  "), color = if (s.cInfo != 0L) cInfo else t.hint, fontSize = (13f * k * s.infoSz / 100f).sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = ts)
                }
            }
            if (mode == 1 && wd.hours.isNotEmpty() && daysFit > 0) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                // почасовой прогноз: каждый [hourStep]-й час от ближайшего, столько колонок, сколько выбрано дней и помещается
                wd.hours.filterIndexed { i, _ -> i % s.hourStep.coerceAtLeast(1) == 0 }.take(daysFit).forEach { hr ->
                    val tf = minOf(15f * k, dayW / 3.1f).coerceAtLeast(9f)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (dayW >= 44f) hr.label + ":00" else hr.label, color = s.col(s.cInfo, t.sub), fontSize = tf.sp, maxLines = 1, softWrap = false, style = ts)
                        WxIcon(hr.code, (if (wide) minOf((bh * 0.16f).coerceIn(18f, 30f), dayW * 0.6f) else minOf(15f, dayW * 0.6f).coerceAtLeast(11f)))
                        Text(WxUnits.tempShort(hr.temp, s), color = if (hr.temp < 0 && s.cNeg != 0L) s.col(s.cNeg, t.text) else s.col(s.cTemp, t.text), fontSize = tf.sp, maxLines = 1, softWrap = false, style = ts)
                        if (s.hWind) Text("${WxUnits.wind(hr.wind, s)}", color = if (s.cInfo != 0L) s.col(s.cInfo, t.hint) else t.hint, fontSize = (tf * 0.85f).sp, maxLines = 1, softWrap = false, style = ts)
                        if (s.hPop && hr.pop != Weather.NA) Text("💧${hr.pop}%", color = if (s.cInfo != 0L) s.col(s.cInfo, t.hint) else t.hint, fontSize = (tf * 0.85f).sp, maxLines = 1, softWrap = false, style = ts)
                    }
                }
            } else if (daysFit > 0) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                wd.days.take(daysFit).forEach { d ->
                    val tf = minOf(15f * k, dayW / 3.1f).coerceAtLeast(9f)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(d.label, color = s.col(s.cInfo, t.sub), fontSize = tf.sp, maxLines = 1, style = ts)
                        WxIcon(d.code, (if (wide) minOf((bh * 0.16f).coerceIn(18f, 30f), dayW * 0.6f) else minOf(15f, dayW * 0.6f).coerceAtLeast(11f)))
                        // в узкой плитке максимум и минимум ставятся столбиком, чтобы значения соседних дней не слипались
                        val maxC = if (d.max < 0 && s.cNeg != 0L) s.col(s.cNeg, t.text) else s.col(s.cTemp, t.text)
                        val minC = if (d.min < 0 && s.cNeg != 0L) s.col(s.cNeg, t.hint) else s.col(s.cInfo, t.hint)
                        if (dayW < 44f) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(WxUnits.tempShort(d.max, s), color = maxC, fontSize = tf.sp, maxLines = 1, softWrap = false, style = ts)
                            Text(WxUnits.tempShort(d.min, s), color = minC, fontSize = tf.sp, maxLines = 1, softWrap = false, style = ts)
                        } else Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(WxUnits.tempShort(d.max, s), color = maxC, fontSize = tf.sp, maxLines = 1, softWrap = false, style = ts)
                            Text(WxUnits.tempShort(d.min, s), color = minC, fontSize = tf.sp, maxLines = 1, softWrap = false, style = ts)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppWidgetBody(w: HWidget, onLong: () -> Unit) {
    val longNow by androidx.compose.runtime.rememberUpdatedState(onLong)
    val ctx = LocalContext.current
    val t = LocalTh.current
    val id = w.ref.toIntOrNull()
    val info = remember(id) { id?.let { runCatching { android.appwidget.AppWidgetManager.getInstance(ctx).getAppWidgetInfo(it) }.getOrNull() } }
    if (id == null || info == null) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.wg_android), color = t.sub, fontSize = 13.sp)
            Text(stringResource(R.string.wg_unavail), color = t.hint, fontSize = 14.sp)
            Text(stringResource(R.string.wg_restore_hint), color = t.accent, fontSize = 13.sp)
        }
        return
    }
    // чужой виджет сам себя не обновляет (часы застывают): раз в минуту, в начале минуты, и при возврате на «Дом» просим его перерисоваться
    val lc = (ctx as? androidx.activity.ComponentActivity)?.lifecycle
    val hv = remember(id) { arrayOfNulls<android.appwidget.AppWidgetHostView>(1) }
    val sz = remember(id) { intArrayOf(0, 0) }
    androidx.compose.runtime.DisposableEffect(id) {
        val ob = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START) hv[0]?.let { v -> if (sz[0] > 0) AppWidgets.kick(v, id, info, sz[0], sz[1]) }
        }
        lc?.addObserver(ob)
        onDispose { lc?.removeObserver(ob) }
    }
    LaunchedEffect(id) {
        while (true) {
            val n = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000 - n % 60_000 + 2_000)
            if (lc == null || lc.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) hv[0]?.let { v -> if (sz[0] > 0) AppWidgets.kick(v, id, info, sz[0], sz[1]) }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        // «Масштаб шрифта» для чужого виджета: ему даётся площадь в k раз меньше, а нарисованное увеличивается в k раз (текст и значки крупнее, как при большом шрифте)
        val k = w.fs.coerceIn(50, 250) / 100f
        val wd = (maxWidth.value / k).toInt()
        val hd = (maxHeight.value / k).toInt()
        sz[0] = wd; sz[1] = hd
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { c ->
                // виджет лежит внутри обёртки, которая ловит долгое нажатие в любом месте (сам виджет забирает касания себе)
                val frame = LongPressFrame(c) { longNow() }
                val v = AppWidgets.host(c).createView(c, id, info).also { hv[0] = it; it.setPadding(0, 0, 0, 0); it.updateAppWidgetSize(null, wd, hd, wd, hd) }
                frame.addView(v, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                frame
            },
            update = { hv[0]?.updateAppWidgetSize(null, wd, hd, wd, hd) },
            modifier = Modifier.requiredSize(maxWidth / k, maxHeight / k)
                .graphicsLayer(scaleX = k, scaleY = k, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)),
        )
    }
}

/** Значок погоды: эмодзи, а туман рисуется сам (системный эмодзи тумана — квадратная картинка): облако и три полосы. [sp] — размер как у текста. */
@Composable
internal fun WxIcon(code: Int, sp: Float) {
    if (code != 45 && code != 48) { Text(WeatherData.emoji(code), fontSize = sp.sp, maxLines = 1); return }
    val d = androidx.compose.ui.platform.LocalDensity.current
    val side = with(d) { (sp * 0.95f).sp.toDp() }
    androidx.compose.foundation.Canvas(Modifier.size(side)) {
        val w = size.width; val h = size.height
        val cloud = androidx.compose.ui.graphics.Color(0xFFD7DEE8)
        val line = androidx.compose.ui.graphics.Color(0xFFB4C0CF)
        // облако: две окружности и основание
        drawCircle(cloud, radius = h * 0.20f, center = androidx.compose.ui.geometry.Offset(w * 0.38f, h * 0.36f))
        drawCircle(cloud, radius = h * 0.26f, center = androidx.compose.ui.geometry.Offset(w * 0.58f, h * 0.30f))
        drawRoundRect(cloud, androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.34f), androidx.compose.ui.geometry.Size(w * 0.78f, h * 0.24f), androidx.compose.ui.geometry.CornerRadius(h * 0.12f))
        // полосы тумана
        for ((i, x0) in listOf(0.08f, 0.22f, 0.10f).withIndex()) {
            val y = h * (0.68f + 0.12f * i)
            drawRoundRect(line, androidx.compose.ui.geometry.Offset(w * x0, y), androidx.compose.ui.geometry.Size(w * (0.84f - x0 + 0.08f), h * 0.06f), androidx.compose.ui.geometry.CornerRadius(h * 0.03f))
        }
    }
}
