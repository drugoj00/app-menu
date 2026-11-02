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

private fun loc(): Locale = Locale(Cfg.lang.ifBlank { Locale.getDefault().language })

/** Нажатие на виджет: часы и дата ведут в настройки даты, память в настройки хранилища, погода обновляется. */
internal fun widgetClick(ctx: Context, w: HWidget, refresh: () -> Unit) {
    when (w.type) {
        "clock", "date" -> openSys(ctx, Settings.ACTION_DATE_SETTINGS)
        "storage", "ram" -> openSys(ctx, Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        else -> refresh()
    }
}

/** Содержимое одной ячейки виджетов. */
@Composable
internal fun WidgetBody(w: HWidget, tick: Int, refreshTick: Int) {
    when (w.type) {
        "empty" -> {}
        "weather" -> WeatherBody(w, tick, refreshTick)
        "clock" -> ClockBody(w)
        "date" -> DateBody()
        "appwidget" -> AppWidgetBody(w)
        "storage" -> MemBody(w.type)
        else -> MemBody(w.type)
    }
}

@Composable
private fun ClockBody(w: HWidget) {
    val t = LocalTh.current
    val now = rememberNow((w.n and 2) != 0)
    BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
        val big = (maxHeight.value * 0.42f).coerceIn(20f, 64f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(withSmallSeconds(SimpleDateFormat(clockPattern(w.n), loc()).format(Date(now)), (w.n and 2) != 0), color = t.text, fontSize = big.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (w.flag) Text(SimpleDateFormat("EEEE, d MMMM", loc()).format(Date(now)), color = t.sub, fontSize = (big * 0.3f).coerceAtLeast(11f).sp, maxLines = 1)
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
private fun MemBody(type: String) {
    val t = LocalTh.current
    val ctx = LocalContext.current
    val info = remember(type) {
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
    val lang = Cfg.lang.ifBlank { Locale.getDefault().language }
    val seen = remember { intArrayOf(0) }
    val weather by produceState<Weather?>(WeatherData.cached(ctx, w.city), tick, refreshTick, w.city) {
        val old = value
        val force = refreshTick != seen[0]
        seen[0] = refreshTick
        value = withContext(Dispatchers.IO) { WeatherData.load(ctx, w.city, lang, force) ?: old }
    }
    val wd = weather
    if (wd == null) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.wg_weather), color = t.sub, fontSize = 13.sp)
            Text(stringResource(R.string.wg_loading), color = t.hint, fontSize = 14.sp)
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
        val daysFit = (if (mw.value - leftW - 12f >= 26f * w.n) w.n else ((mw.value - leftW - 12f) / 26f).toInt()).coerceIn(0, w.n)
        // ширина слота одного дня: значок и подписи подгоняются под неё, чтобы пять дней всегда влезали
        val dayW = if (daysFit > 0) (mw.value - leftW - 12f) / daysFit else 0f
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(if (daysFit > 0 || showDesc) leftW.dp else mw), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (wide) WxIcon(wd.code, (bh * 0.50f).coerceIn(40f, 64f))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(wd.city + (if (showDesc) " · " + stringResource(WeatherData.descRes(wd.code)) else ""), color = t.sub, fontSize = (15f * k).sp, maxLines = if (bh >= 120f) 2 else 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text((if (wide) "" else WeatherData.emoji(wd.code) + " ") + "${wd.temp}°", color = t.text, fontSize = (big * (if (wide) 1.1f else 1f)).sp, maxLines = 1)
                    Text("${wd.wind} " + stringResource(R.string.home_wind), color = t.hint, fontSize = (14f * k).sp, maxLines = 1)
                }
            }
            if (daysFit > 0) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                wd.days.take(daysFit).forEach { d ->
                    val tf = minOf(15f * k, dayW / 3.1f).coerceAtLeast(9f)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(d.label, color = t.sub, fontSize = tf.sp, maxLines = 1)
                        WxIcon(d.code, (if (wide) minOf((bh * 0.16f).coerceIn(18f, 30f), dayW * 0.6f) else minOf(15f, dayW * 0.6f).coerceAtLeast(11f)))
                        // в узкой плитке максимум и минимум ставятся столбиком, чтобы значения соседних дней не слипались
                        if (dayW < 44f) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${d.max}°", color = t.text, fontSize = tf.sp, maxLines = 1, softWrap = false)
                            Text("${d.min}°", color = t.hint, fontSize = tf.sp, maxLines = 1, softWrap = false)
                        } else Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("${d.max}°", color = t.text, fontSize = tf.sp, maxLines = 1, softWrap = false)
                            Text("${d.min}°", color = t.hint, fontSize = tf.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppWidgetBody(w: HWidget) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val id = w.ref.toIntOrNull()
    val info = remember(id) { id?.let { runCatching { android.appwidget.AppWidgetManager.getInstance(ctx).getAppWidgetInfo(it) }.getOrNull() } }
    if (id == null || info == null) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.wg_android), color = t.sub, fontSize = 13.sp)
            Text(stringResource(R.string.wg_unavail), color = t.hint, fontSize = 14.sp)
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        // «Масштаб шрифта» для чужого виджета: ему даётся площадь в k раз меньше, а нарисованное увеличивается в k раз (текст и значки крупнее, как при большом шрифте)
        val k = w.fs.coerceIn(50, 250) / 100f
        val wd = (maxWidth.value / k).toInt()
        val hd = (maxHeight.value / k).toInt()
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { c -> AppWidgets.host(c).createView(c, id, info).also { it.setPadding(0, 0, 0, 0); it.updateAppWidgetSize(null, wd, hd, wd, hd) } },
            update = { it.updateAppWidgetSize(null, wd, hd, wd, hd) },
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
