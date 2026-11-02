package app.tvapps

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Что показывает значок состояния сейчас: рисунок или текст, и включён ли он. */
internal data class SVal(val icon: ImageVector?, val text: String?, val on: Boolean, val day: String? = null, val l1: String? = null, val l2: String? = null)

private fun locale(): Locale = Locale(Cfg.lang.ifBlank { Locale.getDefault().language })

private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

internal fun datePattern(opt: Int) = when (opt) { 0 -> "dd.MM"; 1 -> "d MMM"; 2 -> "EEE, d MMMM"; else -> "d EEEE MMMM" }

internal fun dateSample(opt: Int): String =
    if (opt >= 3) cap(SimpleDateFormat("EEEE", locale()).format(Date())) + ", " + cap(SimpleDateFormat("LLLL", locale()).format(Date())) + " " + SimpleDateFormat("dd.MM.yyyy", locale()).format(Date())
    else SimpleDateFormat(datePattern(opt), locale()).format(Date())

internal fun bluetoothOn(ctx: Context): Boolean =
    runCatching { Settings.Global.getInt(ctx.contentResolver, "bluetooth_on", 0) == 1 }.getOrDefault(false)

/** 0 нет сети, 1 Wi-Fi, 2 кабель. */
internal fun netKind(ctx: Context): Int {
    return try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nw = cm.activeNetwork
        val caps = if (nw != null) cm.getNetworkCapabilities(nw) else null
        if (caps == null) 0
        else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) 2
        else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 1
        else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) 3      // Wi-Fi выключен, идёт мобильный интернет
        else 1
    } catch (e: Exception) { 0 }
}

/** Есть ли подключённый внешний накопитель (флешка, карта, диск). */
internal fun externalMounted(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < 24) return false
    return try {
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        sm.storageVolumes.any { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
    } catch (e: Exception) { false }
}

/** Шаблон часов: бит 1 — 12-часовой формат, бит 2 — с секундами. */
internal fun clockPattern(o: Int): String = (if ((o and 1) != 0) "h:mm" else "HH:mm") + (if ((o and 2) != 0) ":ss" else "") + (if ((o and 1) != 0) " a" else "")

/** Время с секундами: секунды (":ss") и пометка AM/PM мельче и тоньше часов с минутами. */
internal fun withSmallSeconds(text: String, seconds: Boolean): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        val i = if (seconds) text.indexOf(':', text.indexOf(':') + 1) else -1
        val small = androidx.compose.ui.text.SpanStyle(fontSize = 0.55.em, fontWeight = FontWeight.Medium)
        if (i < 0 || i + 3 > text.length) {
            val sp = text.indexOf(' ')
            if (sp > 0 && text.length - sp <= 4 && text.endsWith("M")) { append(text.substring(0, sp)); pushStyle(small); append(text.substring(sp)); pop() }
            else append(text)
        } else {
            append(text.substring(0, i))
            pushStyle(small); append(text.substring(i, i + 3)); pop()
            if (i + 3 < text.length) { pushStyle(small); append(text.substring(i + 3)); pop() }
        }
    }

internal fun statusValue(ctx: Context, s: HStatus, now: Long): SVal = when (s.id) {
    "bt" -> bluetoothOn(ctx).let { SVal(if (it) HIcons.bt else HIcons.btOff, null, it) }
    "net" -> netKind(ctx).let { SVal(if (it == 2) HIcons.eth else if (it == 1) HIcons.wifi else if (it == 3) HIcons.mobile else HIcons.wifiOff, null, it != 0) }
    "usb" -> externalMounted(ctx).let { SVal(HIcons.usb, null, it) }
    "time" -> SVal(null, SimpleDateFormat(clockPattern(s.opt), locale()).format(Date(now)), true)
    "gear" -> SVal(HIcons.gear, null, true)
    "notif" -> SVal(HIcons.bell, null, NotifService.count(ctx) > 0)
    else -> if (s.opt >= 3) SVal(null, null, true, null, cap(SimpleDateFormat("EEEE", locale()).format(Date(now))), cap(SimpleDateFormat("LLLL", locale()).format(Date(now))) + " " + SimpleDateFormat("dd.MM.yyyy", locale()).format(Date(now)))
    else SVal(null, SimpleDateFormat(datePattern(s.opt), locale()).format(Date(now)), true)
}

internal fun statusClick(ctx: Context, id: String) {
    val ok = when (id) {
        "bt" -> openSys(ctx, Settings.ACTION_BLUETOOTH_SETTINGS)
        "net" -> if (netKind(ctx) == 3) openSys(ctx, Settings.ACTION_DATA_ROAMING_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)
        else if (netKind(ctx) == 2) openSys(ctx, "android.settings.ETHERNET_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_WIFI_SETTINGS)
        else openSys(ctx, Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)
        "usb" -> openSys(ctx, Settings.ACTION_INTERNAL_STORAGE_SETTINGS, Settings.ACTION_MEMORY_CARD_SETTINGS)
        "gear" -> openSys(ctx, Settings.ACTION_SETTINGS)
        "notif" -> { TopUi.notifs.value = true; true }
        else -> openSys(ctx, Settings.ACTION_DATE_SETTINGS)
    }
    if (!ok) android.widget.Toast.makeText(ctx, ctx.getString(R.string.home_cant_open), android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
internal fun statusName(id: String): String = stringResource(
    when (id) { "notif" -> R.string.st_notif; "bt" -> R.string.st_bt; "net" -> R.string.st_net; "usb" -> R.string.st_usb; "time" -> R.string.st_time; "gear" -> R.string.st_gear; else -> R.string.st_date },
)

/** Время «сейчас», обновляется раз в минуту (в начале минуты). */
@Composable
/** Текущее время: обновляется раз в минуту, а при [seconds] (в часах включены секунды) раз в секунду. */
internal fun rememberNow(seconds: Boolean = false): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(seconds) {
        while (true) {
            val t = System.currentTimeMillis()
            now = t
            delay(if (seconds) 1000 - t % 1000 + 5 else 60_000 - t % 60_000 + 30)
        }
    }
    return now
}

/** Поле поиска с микрофоном: слева голос, справа набор текста (Enter открывает меню с этим запросом). */
@Composable
private fun SearchBar(big: Boolean, onVoice: () -> Unit, onSearch: (String) -> Unit, onLong: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTh.current
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(if (big) 28.dp else 22.dp)
    val fs = if (big) 20.sp else 15.sp
    Row(
        modifier.clip(shape).background(t.tile)
            .border(if (focused) 3.dp else 1.dp, if (focused) t.accent else t.border, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeBox(shape = androidx.compose.foundation.shape.CircleShape, onClick = onVoice, onLong = onLong) {
            Icon(HIcons.mic, null, tint = t.accent, modifier = Modifier.padding(if (big) 12.dp else 9.dp).size(if (big) 28.dp else 22.dp))
        }
        androidx.compose.foundation.text.BasicTextField(
            text, { text = it }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = t.text, fontSize = fs),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(t.accent),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch(text.trim()); text = "" }),
            // поле сжимается, когда справа тесно (часы и дата не должны переноситься на вторую строку)
            modifier = Modifier.widthIn(min = 40.dp).weight(1f).onFocusChanged { focused = it.isFocused }.padding(horizontal = 8.dp)
                // пока текста нет, стрелки влево и вправо уводят курсор к соседним кнопкам, а не двигают курсор ввода
                .onPreviewKeyEvent { ev ->
                    if (text.isEmpty() && ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                        if (ev.key == Key.DirectionLeft) { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Left); true }
                        else if (ev.key == Key.DirectionRight) { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Right); true }
                        else false
                    } else false
                },
        )
        HomeBox(shape = androidx.compose.foundation.shape.CircleShape, onClick = { onSearch(text.trim()); text = "" }, onLong = onLong) {
            Icon(HIcons.search, null, tint = t.accent, modifier = Modifier.padding(if (big) 12.dp else 9.dp).size(if (big) 28.dp else 22.dp))
        }
    }
}

/** Верхняя полоска: слева значок меню и поле «голос и текст», справа значки состояния (по правому краю, порядок свой). */
@Composable
internal fun HomeTopBar(
    top: HTop, tick: Int, movingStatus: String?, menuReq: FocusRequester,
    onMenu: () -> Unit, onVoice: () -> Unit, onSearch: (String) -> Unit, onTopLong: () -> Unit, onStatusLong: (String) -> Unit,
    onStatusMoveKey: (String, Key) -> Boolean, onStatusStep: (String, Int) -> Unit, onStatusMoveEnd: () -> Unit,
) {
    val t = LocalTh.current
    val ctx = LocalContext.current
    val now = rememberNow(top.status.any { it.id == "time" && it.visible && (it.opt and 2) != 0 })
    // на телефоне и планшете полоска мельче (значки, часы, поле поиска)
    val big = top.big && TopMode.isTv(ctx)
    val tv = TopMode.isTv(ctx)
    // на телефоне и планшете нет значков внешнего накопителя и шестерёнки (настройки доступны системой)
    val vis = top.status.filter { it.visible && (tv || (it.id != "usb" && it.id != "gear")) }
    // узкий экран (телефон в портрете): поле поиска вторым рядом, чтобы часы не обрезались
    val stack = !tv && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
    val showHdmi = top.hdmi && tv
    if (!top.menu && !top.recent && !top.hdmi && !top.voice && !top.search && vis.isEmpty()) return
    // состояние читаем заново по тику (каждые несколько секунд и при возвращении на экран)
    val vals = remember(tick, now, top) { vis.associate { it.id to statusValue(ctx, it, now) } }
    val appIcon = remember { runCatching { ctx.packageManager.getApplicationIcon(ctx.packageName).toBitmap(128, 128).asImageBitmap() }.getOrNull() }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (top.menu) HomeBox(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), bg = t.tile, focusReq = menuReq,
            // долгое нажатие на кнопку меню — недавние (настройки верхней полоски: «Настройки экрана»)
            onClick = onMenu, onLong = { TopUi.recents.value = true },
        ) {
            if (appIcon != null) androidx.compose.foundation.Image(appIcon, null, Modifier.padding(4.dp).size(if (big) 38.dp else 28.dp))
            else Icon(HIcons.apps, null, tint = t.accent, modifier = Modifier.padding(6.dp).size(if (big) 32.dp else 24.dp))
        }
        if (top.recent && tv) HomeBox(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), bg = t.tile,
            onClick = { TopUi.recents.value = true }, onLong = onTopLong,
        ) { Icon(HIcons.recent, null, tint = t.accent, modifier = Modifier.padding(if (big) 9.dp else 7.dp).size(if (big) 34.dp else 26.dp)) }
        if (showHdmi) HomeBox(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), bg = t.tile,
            onClick = { TopUi.hdmi.value = true }, onLong = onTopLong,
        ) { Icon(HIcons.hdmi, null, tint = t.accent, modifier = Modifier.padding(if (big) 9.dp else 7.dp).size(if (big) 34.dp else 26.dp)) }
        // строка поиска занимает всё свободное место между кнопками слева и значками справа
        if (top.search && !stack) SearchBar(big, onVoice, onSearch, onTopLong, Modifier.weight(1f))
        else if (top.voice && !top.search) HomeBox(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), bg = t.tile, onClick = onVoice, onLong = onTopLong,
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(HIcons.mic, null, tint = t.accent, modifier = Modifier.size(if (big) 28.dp else 22.dp))
                Text(stringResource(R.string.home_voice), color = t.text, fontSize = if (big) 19.sp else 15.sp)
            }
        }
        if (!top.search || stack) Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            vis.forEach { s ->
                androidx.compose.runtime.key(s.id) {
                    val v = vals[s.id] ?: SVal(null, "", false)
                    HomeBox(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                        moving = movingStatus == s.id, stepPx = 90f,
                        onMoveKey = { k -> onStatusMoveKey(s.id, k) }, onStep = { d -> onStatusStep(s.id, d) }, onMoveEnd = onStatusMoveEnd,
                        onClick = { if (movingStatus == s.id) onStatusMoveEnd() else statusClick(ctx, s.id) },
                        onLong = { onStatusLong(s.id) },
                    ) {
                        val c = if (v.on) t.text else t.hint
                        if (v.icon != null) Icon(v.icon, null, tint = c, modifier = Modifier.padding(if (big) 13.dp else 10.dp).size(if (big) 36.dp else 26.dp))
                        else if (v.l1 != null) Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            // на телефоне и планшете дата вдвое мельче, чем на ТВ
                            // на телефоне шрифт покрупнее, но строки плотно друг к другу
                            val lh = if (big || tv) androidx.compose.ui.unit.TextUnit.Unspecified else 10.sp
                            Text(v.l1, color = c, fontSize = if (big) 19.sp else if (tv) 14.sp else 9.sp, lineHeight = lh, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                            Text(v.l2.orEmpty(), color = c, fontSize = if (big) 19.sp else if (tv) 14.sp else 9.sp, lineHeight = lh, maxLines = 1, softWrap = false)
                        }
                        else Text(
                            if (s.id == "time") withSmallSeconds(v.text.orEmpty(), (s.opt and 2) != 0) else androidx.compose.ui.text.AnnotatedString(v.text.orEmpty()), color = c, fontSize = if (s.id == "time") (if (big) 34.sp else 20.sp) else (if (big) 21.sp else 15.sp),
                            fontWeight = if (s.id == "time") FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = if (big) 14.dp else 12.dp, vertical = if (big) 8.dp else 8.dp), maxLines = 1, softWrap = false,
                        )
                    }
                }
            }
        }
    }
    if (top.search && stack) Row(Modifier.fillMaxWidth().padding(top = 6.dp)) { SearchBar(big, onVoice, onSearch, onTopLong, Modifier.weight(1f)) }
    }
}
