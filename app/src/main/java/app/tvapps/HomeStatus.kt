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
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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

private fun locale(): Locale = AppLoc.locale()

private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

internal fun datePattern(opt: Int) = when (opt) { 0 -> "dd.MM"; 1 -> "d MMM"; 2 -> "EEE, d MMMM"; else -> "d EEEE MMMM" }

/** Две строки даты верхней полосы (день недели; месяц и число) для режима «длинная дата»; для коротких режимов null. Язык задаётся явно. */
internal fun dateTwoLines(opt: Int, now: Long, loc: Locale): Pair<String, String>? =
    if (opt >= 3) Pair(cap(SimpleDateFormat("EEEE", loc).format(Date(now))), cap(SimpleDateFormat("LLLL", loc).format(Date(now))) + " " + SimpleDateFormat("dd.MM.yyyy", loc).format(Date(now)))
    else null

/** Одна строка даты по режиму (для образца в настройках и для коротких режимов). */
internal fun dateLine(opt: Int, now: Long, loc: Locale): String =
    dateTwoLines(opt, now, loc)?.let { it.first + ", " + it.second } ?: SimpleDateFormat(datePattern(opt), loc).format(Date(now))

internal fun dateSample(opt: Int): String = dateLine(opt, System.currentTimeMillis(), locale())

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
    else -> dateTwoLines(s.opt, now, locale())?.let { SVal(null, null, true, null, it.first, it.second) } ?: SVal(null, SimpleDateFormat(datePattern(s.opt), locale()).format(Date(now)), true)
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

/**
 * Текущее время для часов. Обновляется в начале каждой минуты, при [seconds] (в часах включены секунды) в начале каждой секунды.
 * Каждый следующий тик считается от текущего времени (а не прибавлением к прошлому), поэтому дрейфа нет; перевод часов, смена часового пояса
 * и даты подхватываются сразу (системные сообщения), пока окно не видно (сон экрана, другое приложение), тики не выполняются и процессор не нагружается;
 * при возврате время обновляется мгновенно. Для замера хода: `adb shell setprop log.tag.TVClock VERBOSE`, в журнале TVClock смещение каждого тика от границы секунды.
 */
@Composable
internal fun rememberNow(seconds: Boolean = false): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val ctx = LocalContext.current
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var active by remember { androidx.compose.runtime.mutableStateOf(owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) }
    var kick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(owner) {
        val ob = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START) { active = true; kick++ }
            else if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) active = false
        }
        owner.lifecycle.addObserver(ob)
        onDispose { owner.lifecycle.removeObserver(ob) }
    }
    androidx.compose.runtime.DisposableEffect(ctx) {
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) { now = System.currentTimeMillis(); kick++ }
        }
        val f = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_TIME_CHANGED); addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED); addAction(android.content.Intent.ACTION_DATE_CHANGED)
        }
        val ok = runCatching { androidx.core.content.ContextCompat.registerReceiver(ctx, rx, f, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED) }.isSuccess
        onDispose { if (ok) runCatching { ctx.unregisterReceiver(rx) } }
    }
    LaunchedEffect(seconds, active, kick) {
        if (!active) return@LaunchedEffect
        val trace = android.util.Log.isLoggable("TVClock", android.util.Log.VERBOSE)
        while (true) {
            val t = System.currentTimeMillis()
            now = t
            if (trace) android.util.Log.v("TVClock", "tick sec=" + (t / 1000 % 60) + " off=" + (t % 1000))
            delay(if (seconds) 1000 - t % 1000 + 5 else 60_000 - t % 60_000 + 30)
        }
    }
    return now
}

/** Поле поиска с микрофоном: слева голос, справа набор текста (Enter открывает меню с этим запросом). */
@Composable
private fun SearchBar(big: Boolean, onVoice: () -> Unit, onSearch: (String) -> Unit, onLong: () -> Unit, modifier: Modifier = Modifier, sc: Float = 1f) {
    val t = LocalTh.current
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // ввод включается только по «ОК» (пульт) или щелчку/касанию: при простом проходе пультом поле лишь подсвечивается, клавиатура не открывается
    var editing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val fieldReq = remember { FocusRequester() }
    val starting = remember { booleanArrayOf(false) }
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape((if (big) 28f else 22f).times(sc).dp)
    val fs = (if (big) 20f else 15f).times(sc).sp
    fun startEdit() {
        if (editing) { kb?.show(); return }
        editing = true
        starting[0] = true
        scope.launch {
            // поле становится редактируемым, фокус берётся заново: тогда система открывает клавиатуру
            delay(30)
            runCatching { focusManager.clearFocus(force = true) }
            delay(30)
            runCatching { fieldReq.requestFocus() }
            delay(60)
            starting[0] = false
            kb?.show()
        }
    }
    Row(
        modifier.clip(shape).background(t.tile)
            .border(if (focused) 3.dp else 1.dp, if (focused) t.accent else t.border, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeBox(shape = androidx.compose.foundation.shape.CircleShape, onClick = onVoice, onLong = onLong) {
            Icon(HIcons.mic, null, tint = t.accent, modifier = Modifier.padding((if (big) 12f else 9f).times(sc).dp).size((if (big) 28f else 22f).times(sc).dp))
        }
        androidx.compose.foundation.text.BasicTextField(
            text, { text = it }, singleLine = true, readOnly = !editing,
            textStyle = androidx.compose.ui.text.TextStyle(color = t.text, fontSize = fs),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(t.accent),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch(text.trim()); text = ""; editing = false }),
            // поле сжимается, когда справа тесно (часы и дата не должны переноситься на вторую строку)
            modifier = Modifier.widthIn(min = 16.dp).weight(1f).focusRequester(fieldReq)
                .onFocusChanged { focused = it.isFocused; if (!it.isFocused && editing && !starting[0]) editing = false }
                .padding(horizontal = (8f * sc).dp)
                // щелчок мыши и касание тоже включают ввод (до того, как поле само обработает нажатие)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                        if (up != null) startEdit()
                    }
                }
                .onPreviewKeyEvent { ev ->
                    if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                        // «ОК» на поле открывает ввод и клавиатуру
                        if (!editing && (ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter)) { startEdit(); true }
                        // без ввода (или пока текста нет) стрелки влево и вправо уводят курсор к соседним кнопкам, а не двигают курсор ввода
                        else if ((!editing || text.isEmpty()) && ev.key == Key.DirectionLeft) { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Left); true }
                        else if ((!editing || text.isEmpty()) && ev.key == Key.DirectionRight) { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Right); true }
                        else false
                    } else false
                },
        )
        HomeBox(shape = androidx.compose.foundation.shape.CircleShape, onClick = { onSearch(text.trim()); text = ""; editing = false }, onLong = onLong) {
            Icon(HIcons.search, null, tint = t.accent, modifier = Modifier.padding((if (big) 12f else 9f).times(sc).dp).size((if (big) 28f else 22f).times(sc).dp))
        }
    }
}

/** Верхняя полоска: слева значок меню и поле «голос и текст», справа значки состояния (по правому краю, порядок свой). */
@Composable
internal fun HomeTopBar(
    top0: HTop, tick: Int, movingStatus: String?, menuReq: FocusRequester,
    onMenu: () -> Unit, onVoice: () -> Unit, onSearch: (String) -> Unit, onTopLong: () -> Unit, onStatusLong: (String) -> Unit,
    onStatusMoveKey: (String, Key) -> Boolean, onStatusStep: (String, Int) -> Unit, onStatusMoveEnd: () -> Unit,
) {
    // меню приложений выключено в настройках: кнопки меню нет
    val top = if (Cfg.menuOff && top0.menu) top0.copy(menu = false) else top0
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
    if (!top.menu && !top.recent && !top.hdmi && !top.clean && !top.voice && !top.search && vis.isEmpty()) return
    // состояние читаем заново по тику (каждые несколько секунд и при возвращении на экран)
    val vals = remember(tick, now, top, Cfg.lang) { vis.associate { it.id to statusValue(ctx, it, now) } }
    val appIcon = remember { runCatching { ctx.packageManager.getApplicationIcon(ctx.packageName).toBitmap(128, 128).asImageBitmap() }.getOrNull() }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    // ширина полосы известна: подбираем зазоры и размер значков так, чтобы всё поместилось без наложения и обрезки
    val dn = androidx.compose.ui.platform.LocalDensity.current
    val lvIdx = run {
        val tw = { txt: String, sp: Float, w: Int -> AppFont.measure(txt, with(dn) { sp.sp.toPx() }, w) / dn.density }
        val big0 = big
        val box = { icon: Float, pad: Float -> icon + 2 * pad }
        val left = mutableListOf<Float>()
        if (top.menu) left += box(if (big0) 38f else 28f, 4f)
        if (top.recent && tv) left += box(if (big0) 34f else 26f, if (big0) 9f else 7f)
        if (top.clean) left += box(if (big0) 34f else 26f, if (big0) 9f else 7f)
        if (showHdmi) left += box(if (big0) 34f else 26f, if (big0) 9f else 7f)
        if (!(top.search && !stack) && top.voice && !top.search) left += 32f + (if (big0) 28f else 22f) + 8f + tw(stringResource(R.string.home_voice), if (big0) 19f else 15f, 400)
        val searchMin = if (top.search && !stack) 2 * box(if (big0) 28f else 22f, if (big0) 12f else 9f) + 16f + 16f else 0f
        val st = vis.map { s0 ->
            val v = vals[s0.id] ?: SVal(null, "", false)
            when {
                v.icon != null -> box(if (big0) 36f else 26f, if (big0) 13f else 10f)
                v.l1 != null -> maxOf(tw(v.l1, if (big0) 19f else if (tv) 14f else 9f, 500), tw(v.l2.orEmpty(), if (big0) 19f else if (tv) 14f else 9f, 400)) + 24f
                s0.id == "time" -> {
                    val txt = v.text.orEmpty().map { if (it.isDigit()) '8' else it }.joinToString("")
                    val fsT = if (big0) 34f else 20f
                    val i2 = if ((s0.opt and 2) != 0) txt.indexOf(':', txt.indexOf(':') + 1) else -1
                    val w = if (i2 > 0) tw(txt.substring(0, i2), fsT, 700) + tw(txt.substring(i2), fsT * 0.55f, 500) else tw(txt, fsT, 700)
                    w + 2 * (if (big0) 14f else 12f)
                }
                else -> tw(v.text.orEmpty(), if (big0) 21f else 15f, 400) + 2 * (if (big0) 14f else 12f)
            }
        }
        // запас на рамки и округления: 3 % и 6 dp
        TopBarPlan.choose((maxWidth.value - 6f) / 1.03f, left, searchMin, st)
    }
    val lv = TopBarPlan.levels[lvIdx]
    val sc = lv.scale
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(lv.left.dp)) {
        if (top.menu) HomeBox(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), bg = t.tile, focusReq = menuReq,
            // долгое нажатие на кнопку меню — недавние (настройки верхней полоски: «Настройки экрана»)
            onClick = onMenu, onLong = { TopUi.recents.value = true },
        ) {
            if (appIcon != null) androidx.compose.foundation.Image(appIcon, null, Modifier.padding((4f * sc).dp).size((if (big) 38f else 28f).times(sc).dp))
            else Icon(HIcons.apps, null, tint = t.accent, modifier = Modifier.padding((6f * sc).dp).size((if (big) 32f else 24f).times(sc).dp))
        }
        if (top.recent && tv) HomeBox(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), bg = t.tile,
            onClick = { TopUi.recents.value = true }, onLong = onTopLong,
        ) { Icon(HIcons.recent, null, tint = t.accent, modifier = Modifier.padding((if (big) 9f else 7f).times(sc).dp).size((if (big) 34f else 26f).times(sc).dp)) }
        // «Очистка памяти»: после «Недавних»; на телефоне и планшете тоже (кнопка небольшая)
        if (top.clean) {
            val cleanScope = androidx.compose.runtime.rememberCoroutineScope()
            val cleanBusy = remember { booleanArrayOf(false) }
            HomeBox(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), bg = t.tile,
                onClick = {
                    if (!cleanBusy[0]) {
                        cleanBusy[0] = true
                        cleanScope.launch { MemoryCleaner.cleanWithToast(ctx); cleanBusy[0] = false }
                    }
                },
                onLong = {
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.mem_clean_tip), android.widget.Toast.LENGTH_LONG).show()
                },
            ) { Icon(HIcons.memory, ctx.getString(R.string.mem_clean), tint = t.accent, modifier = Modifier.padding((if (big) 9f else 7f).times(sc).dp).size((if (big) 34f else 26f).times(sc).dp)) }
        }
        if (showHdmi) HomeBox(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), bg = t.tile,
            onClick = { TopUi.hdmi.value = true }, onLong = onTopLong,
        ) { Icon(HIcons.hdmi, null, tint = t.accent, modifier = Modifier.padding((if (big) 9f else 7f).times(sc).dp).size((if (big) 34f else 26f).times(sc).dp)) }
        // строка поиска занимает всё свободное место между кнопками слева и значками справа
        if (top.search && !stack) SearchBar(big, onVoice, onSearch, onTopLong, Modifier.weight(1f), sc)
        else if (top.voice && !top.search) HomeBox(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), bg = t.tile, onClick = onVoice, onLong = onTopLong,
        ) {
            Row(Modifier.padding(horizontal = (16f * sc).dp, vertical = (8f * sc).dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((8f * sc).dp)) {
                Icon(HIcons.mic, null, tint = t.accent, modifier = Modifier.size((if (big) 28f else 22f).times(sc).dp))
                Text(stringResource(R.string.home_voice), color = t.text, fontSize = (if (big) 19f else 15f).times(sc).sp)
            }
        }
        if (!top.search || stack) Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(lv.status.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        if (v.icon != null) OutlinedIcon(v.icon, c, size = (if (big) 36f else 26f).times(sc).dp, pad = (if (big) 13f else 10f).times(sc).dp)
                        else if (v.l1 != null) Column(Modifier.padding(horizontal = (12f * sc).dp, vertical = (4f * sc).dp)) {
                            // на телефоне и планшете дата вдвое мельче, чем на ТВ
                            // на телефоне шрифт покрупнее, но строки плотно друг к другу
                            val lh = if (big || tv) androidx.compose.ui.unit.TextUnit.Unspecified else (10f * sc).sp
                            OutlinedText(androidx.compose.ui.text.AnnotatedString(v.l1), c, (if (big) 19f else if (tv) 14f else 9f).times(sc).sp, lineHeight = lh, fontWeight = FontWeight.Medium)
                            OutlinedText(androidx.compose.ui.text.AnnotatedString(v.l2.orEmpty()), c, (if (big) 19f else if (tv) 14f else 9f).times(sc).sp, lineHeight = lh)
                        }
                        else OutlinedText(
                            if (s.id == "time") withSmallSeconds(v.text.orEmpty(), (s.opt and 2) != 0) else androidx.compose.ui.text.AnnotatedString(v.text.orEmpty()), c,
                            (if (s.id == "time") (if (big) 34f else 20f) else (if (big) 21f else 15f)).times(sc).sp,
                            fontWeight = if (s.id == "time") FontWeight.Bold else FontWeight.Normal,
                            style = androidx.compose.material3.LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                            modifier = Modifier.padding(horizontal = (if (big) 14f else 12f).times(sc).dp, vertical = (8f * sc).dp),
                        )
                    }
                }
            }
        }
    }
    if (top.search && stack) Row(Modifier.fillMaxWidth().padding(top = 6.dp)) { SearchBar(big, onVoice, onSearch, onTopLong, Modifier.weight(1f)) }
    }
    }
}

/** Контур значков и текста строки состояния: цвет противоположный цвету значка (светлому тёмный, тёмному светлый), чтобы их было видно на любом фоне без настроек. */
private fun outlineOf(c: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color =
    if (c.luminance() > 0.5f) androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.85f) else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f)

private val OUTLINE_DIRS = listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f, 0.7f to 0.7f, -0.7f to 0.7f, 0.7f to -0.7f, -0.7f to -0.7f)

/** Значок с контуром: под ним восемь смещённых копий цвета контура. */
@Composable
private fun OutlinedIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp, pad: androidx.compose.ui.unit.Dp) {
    val o = outlineOf(tint)
    Box(Modifier.padding(pad)) {
        for ((dx, dy) in OUTLINE_DIRS) Icon(icon, null, tint = o, modifier = Modifier.size(size).offset((dx * 1.4f).dp, (dy * 1.4f).dp))
        Icon(icon, null, tint = tint, modifier = Modifier.size(size))
    }
}

/** Текст с контуром: сначала обводка цветом контура (штрих), поверх обычный текст. */
@Composable
private fun OutlinedText(
    text: androidx.compose.ui.text.AnnotatedString, color: androidx.compose.ui.graphics.Color, fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier, fontWeight: FontWeight? = null, lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    style: androidx.compose.ui.text.TextStyle = androidx.compose.material3.LocalTextStyle.current,
) {
    val o = outlineOf(color)
    val strokePx = with(androidx.compose.ui.platform.LocalDensity.current) { fontSize.toPx() * 0.2f }
    Box(modifier) {
        Text(
            text, color = o, fontSize = fontSize, fontWeight = fontWeight, lineHeight = lineHeight, maxLines = 1, softWrap = false,
            style = style.copy(drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = strokePx, join = androidx.compose.ui.graphics.StrokeJoin.Round)),
        )
        Text(text, color = color, fontSize = fontSize, fontWeight = fontWeight, lineHeight = lineHeight, maxLines = 1, softWrap = false, style = style)
    }
}
