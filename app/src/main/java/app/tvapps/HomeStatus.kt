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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    if (opt >= 3) Pair(cap(DFmt.format("EEEE", loc, now)), cap(DFmt.format("LLLL", loc, now)) + " " + DFmt.format("dd.MM.yyyy", loc, now))
    else null

/** Одна строка даты по режиму (для образца в настройках и для коротких режимов). */
internal fun dateLine(opt: Int, now: Long, loc: Locale): String =
    dateTwoLines(opt, now, loc)?.let { it.first + ", " + it.second } ?: DFmt.format(datePattern(opt), loc, now)

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

/** Шаблон часов: бит 1 - 12-часовой формат, бит 2 - с секундами. */
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
    "time" -> SVal(null, DFmt.format(clockPattern(s.opt), locale(), now), true)
    "gear" -> SVal(HIcons.gear, null, true)
    // 12.0 (просьба владелицы: «колокольчик всегда серый, нажимаю, а там есть уведомления»): колокольчик всегда цвета остальных значков (как шестерёнка), без тусклого
    // состояния; число уведомлений рисуется красной цифрой внутри колокольчика и берётся из наблюдаемого NotifService.badge (см. BellWithCount)
    "notif" -> SVal(HIcons.bell, null, true)
    else -> dateTwoLines(s.opt, now, locale())?.let { SVal(null, null, true, null, it.first, it.second) } ?: SVal(null, DFmt.format(datePattern(s.opt), locale(), now), true)
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
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(barR((if (big) 28f else 22f).times(sc).dp))
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
        modifier.clip(shape).background(barBg(t.tile))
            .focusBorder({ focused }, 3.dp, 1.5.dp, t.accent, t.accent, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 15.0: без программы распознавания речи микрофон не показывается
        if (Voice.available(androidx.compose.ui.platform.LocalContext.current)) HomeBox(shape = androidx.compose.foundation.shape.CircleShape, onClick = onVoice, onLong = onLong) {
            TintIcon(HIcons.mic, t.accent, (if (big) 28f else 22f).times(sc).dp, (if (big) 12f else 9f).times(sc).dp)
        } else Spacer(Modifier.width((10f * sc).dp))
        androidx.compose.foundation.text.BasicTextField(
            text, { text = it }, singleLine = true, readOnly = !editing,
            textStyle = androidx.compose.ui.text.TextStyle(color = barTextTint(t.text), fontSize = fs),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(barTextTint(t.accent)),
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
            TintIcon(HIcons.search, t.accent, (if (big) 28f else 22f).times(sc).dp, (if (big) 12f else 9f).times(sc).dp)
        }
    }
}

/** Верхняя полоска: слева значок меню и поле «голос и текст», справа значки состояния (по правому краю, порядок свой). */
@Composable
internal fun HomeTopBar(
    top0: HTop, tickOf: () -> Int, movingStatus: String?, menuReq: FocusRequester,
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
    // на телефоне и планшете нет значков внешнего накопителя и шестерёнки (настройки доступны системой), но если пользователь сам включил
    // «Показывать верхнюю строку» (режим 1), значки USB и «Настройки» с включённым показом должны быть видны и на приставке, которая не назвалась ТВ
    // (просьба пользователя 4PDA: «не отображаются USB и Settings, отображение включено»)
    val vis = top.status.filter { it.visible }   // 15.11.2026 (слова владелицы): значки USB и «Настройки» и кнопки верхней строки показываются на любом устройстве по настройке «показан/скрыт», без условий ТВ/не ТВ (HDMI по-прежнему только на ТВ)
    // узкий экран (телефон в портрете): поле поиска вторым рядом, чтобы часы не обрезались
    val stack = !tv && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
    val showHdmi = top.hdmi && tv
    if (!top.menu && !top.recent && !top.hdmi && !top.clean && !top.voice && !top.search && vis.isEmpty()) return
    // состояние читаем заново по тику (каждые несколько секунд и при возвращении на экран)
    // 15.1 (плавность): значки Bluetooth, сети и накопителя обращаются к системным службам (несколько вызовов Binder на значок); раньше они опрашивались
    // каждую секунду вместе с часами. Теперь только по tick (раз в несколько секунд и при возврате на экран), а время и дата пересчитываются каждую секунду.
    val tick = tickOf()
    val sysVals = remember(tick, top) { vis.filter { it.id == "bt" || it.id == "net" || it.id == "usb" }.associate { it.id to statusValue(ctx, it, 0L) } }
    val vals = remember(sysVals, now, top, Cfg.lang) { vis.associate { it.id to (sysVals[it.id] ?: statusValue(ctx, it, now)) } }
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
        if (top.recent) left += box(if (big0) 34f else 26f, if (big0) 9f else 7f)
        if (HomeDock.btn(ctx)) left += box(if (big0) 34f else 26f, if (big0) 9f else 7f)
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
            shape = androidx.compose.foundation.shape.RoundedCornerShape(barR(14.dp)), bg = barBg(t.tile), edge = barEdge(t.accent), focusReq = menuReq,
            // долгое нажатие на кнопку меню - недавние (настройки верхней полоски: «Настройки экрана»)
            onClick = onMenu, onLong = { TopUi.recents.value = true },
        ) {
            if (appIcon != null) androidx.compose.foundation.Image(appIcon, null, Modifier.padding((4f * sc).dp).size((if (big) 38f else 28f).times(sc).dp))
            else BarIcon(HIcons.apps, null, t.accent, (if (big) 32f else 24f).times(sc).dp, (6f * sc).dp)
        }
        // название программы рядом с кнопкой меню (по выбору в настройках верхней строки; по умолчанию выключено)
        if (top.recent) HomeBox(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(barR(14.dp)), bg = barBg(t.tile), edge = barEdge(t.accent),
            onClick = { TopUi.recents.value = true }, onLong = onTopLong,
        ) { BarIcon(HIcons.recent, null, t.accent, (if (big) 34f else 26f).times(sc).dp, (if (big) 9f else 7f).times(sc).dp) }
        // кнопка «Док-панель» (по умолчанию включена, отключается в «Верхней полоске»): нажатие показывает док или переводит фокус в него, повторное скрывает; долгое: меню верхней полоски
        if (HomeDock.btn(ctx)) HomeBox(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(barR(14.dp)), bg = barBg(t.tile), edge = barEdge(t.accent),
            onClick = { if (!HomeDock.on(ctx)) { HomeDock.setOn(ctx, true); HomeDock.focusTick.intValue++ } else { HomeDock.focusedPkgs.clear(); HomeDock.setOn(ctx, false) } },
            onLong = { HomeDock.openSettings(ctx) },
        ) { BarIcon(HIcons.dock, ctx.getString(R.string.dock_btn), t.accent, (if (big) 34f else 26f).times(sc).dp, (if (big) 9f else 7f).times(sc).dp) }
        // «Очистка памяти»: после «Недавних»; на телефоне и планшете тоже (кнопка небольшая)
        if (top.clean) {
            val cleanScope = androidx.compose.runtime.rememberCoroutineScope()
            val cleanBusy = remember { booleanArrayOf(false) }
            HomeBox(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(barR(14.dp)), bg = barBg(t.tile), edge = barEdge(t.accent),
                onClick = {
                    if (!cleanBusy[0]) {
                        cleanBusy[0] = true
                        cleanScope.launch { MemoryCleaner.cleanWithToast(ctx); cleanBusy[0] = false }
                    }
                },
                onLong = {
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.mem_clean_tip), android.widget.Toast.LENGTH_LONG).show()
                },
            ) { BarIcon(HIcons.memory, ctx.getString(R.string.mem_clean), t.accent, (if (big) 34f else 26f).times(sc).dp, (if (big) 9f else 7f).times(sc).dp) }
        }
        if (showHdmi) HomeBox(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(barR(14.dp)), bg = barBg(t.tile), edge = barEdge(t.accent),
            onClick = { TopUi.hdmi.value = true }, onLong = onTopLong,
        ) { BarIcon(HIcons.hdmi, null, t.accent, (if (big) 34f else 26f).times(sc).dp, (if (big) 9f else 7f).times(sc).dp) }
        // строка поиска занимает всё свободное место между кнопками слева и значками справа
        if (top.search && !stack) SearchBar(big, onVoice, onSearch, onTopLong, Modifier.weight(1f), sc)
        else if (top.voice && !top.search && Voice.available(androidx.compose.ui.platform.LocalContext.current)) HomeBox(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(barR(24.dp)), bg = barBg(t.tile), edge = barEdge(t.accent), onClick = onVoice, onLong = onTopLong,
        ) {
            Row(Modifier.padding(horizontal = (16f * sc).dp, vertical = (8f * sc).dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((8f * sc).dp)) {
                TintIcon(HIcons.mic, t.accent, (if (big) 28f else 22f).times(sc).dp, 0.dp)
                Text(stringResource(R.string.home_voice), color = barTextTint(t.text), fontSize = (if (big) 19f else 15f).times(sc).sp)
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
                        // 12.0: цвет по выбору пользователя («Цвет значков строки состояния», полоска): Авто как раньше; выбранный цвет: тусклое состояние = тот же цвет с пониженной непрозрачностью
                        val c = IconColor.forced?.let { f -> if (v.on) f else f.copy(alpha = 0.5f) } ?: (if (v.on) t.text else t.hint)
                        if (v.icon != null && s.id == "notif") BellWithCount(v.icon, c, (if (big) 36f else 26f).times(sc).dp, (if (big) 13f else 10f).times(sc).dp)
                        else if (v.icon != null) OutlinedIcon(v.icon, c, size = (if (big) 36f else 26f).times(sc).dp, pad = (if (big) 13f else 10f).times(sc).dp)
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

/**
 * Контур значков и текста строки состояния: тёмная тень вокруг светлых значков и букв, чтобы их было видно на светлой картинке фона.
 * Белого контура нет: раньше тусклые значки (колокольчик без уведомлений, USB без накопителя; цвет «подсказки» ниже порога яркости 0,5) получали
 * белую окантовку, а яркие чёрную, и значки в одном ряду выглядели по-разному. Тёмные значки (светлая тема на светлом фоне) идут без контура.
 */
private fun outlineOf(c: androidx.compose.ui.graphics.Color, behind: Float? = null): androidx.compose.ui.graphics.Color {
    // выбор пользователя («Контур значков строки состояния»): Тёмный, Белый, Нет или Авто (по яркости фона)
    when (Cfg.statusOutline) {
        "none" -> return androidx.compose.ui.graphics.Color.Transparent
        "dark" -> return androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.85f)
        "white" -> return androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f)
    }
    // 12.0: выбран цвет значков (полоска): тусклый (полупрозрачный) без контура; яркий, теряющийся на фоне под значком, с контуром противоположной яркости
    if (behind != null) {
        // тусклый (полупрозрачный) значок: контур вдвое слабее, чтобы он не просвечивал сквозь значок, но тёмный на тёмном (или светлый на светлом) не пропадал
        if (c.alpha < 1f) return forcedOutline(c.copy(alpha = 1f), behind)?.let { it.copy(alpha = it.alpha * 0.5f) } ?: androidx.compose.ui.graphics.Color.Transparent
        forcedOutline(c, behind)?.let { return it }
    }
    if (c.luminance() <= 0.2f) return androidx.compose.ui.graphics.Color.Transparent
    // Авто: сила тёмного контура по яркости фона под строкой (просьба пользователя 4PDA: на светлой картинке чёрная обводка не подходит): светлый фон - мягкая
    // тень, средний - заметный контур, тёмный - лишь лёгкая тень (светлым значкам там и так видно); фон неизвестен (видео, нет фона) - средний
    val l = BgLum.top
    val a = when {
        l == null -> 0.6f
        l >= 0.7f -> 0.25f
        l >= 0.55f -> 0.4f
        l >= 0.3f -> 0.7f
        else -> 0.3f
    }
    return androidx.compose.ui.graphics.Color.Black.copy(alpha = a)
}

/** Фон кнопок и поля поиска верхней строки по выбору пользователя: непрозрачный (по умолчанию), полупрозрачный цвет плиток темы, без фона. */
internal fun barBg(c: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color = when (Cfg.barFill) {
    // 15.11.2026: три вида стали заметно разными: «Сплошной» (непрозрачная плитка), «Стекло» (прозрачная плитка с тонкой рамкой цвета акцента, картинка просвечивает), «Без фона» (только значок с контуром)
    "glass" -> c.copy(alpha = 0.78f)
    "none" -> androidx.compose.ui.graphics.Color.Transparent
    else -> c.copy(alpha = 1f)
}

/** Тонкая рамка кнопок верхней строки в покое: только у вида «Стекло». */
internal fun barEdge(accent: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color =
    if (Cfg.barFill == "glass") accent.copy(alpha = 0.85f) else androidx.compose.ui.graphics.Color.Transparent

/** Значок кнопки верхней строки: у вида «Без фона» с контуром (иначе теряется на любой картинке). */
@Composable
internal fun BarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String?, tint0: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp, pad: androidx.compose.ui.unit.Dp) {
    val tint = barTint(tint0)
    // 12.0: при выбранном цвете значок, теряющийся на плашке (или на фоне при виде «Без фона»), получает контур противоположной яркости
    val ol = barForcedOutline(tint)
    if (Cfg.barFill == "none") {
        // только тёмная тень (белого контура вокруг значков нет ни на какой картинке), кроме выбранного цвета, которому может понадобиться светлый контур
        val o = ol ?: androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.75f)
        OutlinedIcon(icon, tint, size, pad, o)
    } else if (ol != null) OutlinedIcon(icon, tint, size, pad, ol)
    else Icon(icon, desc, tint = tint, modifier = Modifier.padding(pad).size(size))
}

/**
 * 12.0: цвет значков и надписей верхней строки по выбору пользователя (настройка «Цвет значков строки состояния»): положение на полоске [Cfg.statusIconPos]
 * (0 чёрный, спектр от красного до розового, 1 белый); Авто (по умолчанию): цвета темы и фона, как раньше.
 */
internal object IconColor {
    private const val DARK_END = 0.06f
    private const val LIGHT_START = 0.94f
    private const val HUE_MAX = 330f

    /** Выбранный цвет или null (Авто). */
    val forced: androidx.compose.ui.graphics.Color? get() = Cfg.statusIconPos.takeIf { it >= 0f }?.let { at(it) }

    /** Цвет в точке полоски: 0..[DARK_END] от чёрного к красному, дальше оттенки красный → розовый, с [LIGHT_START] к белому. */
    fun at(pos: Float): androidx.compose.ui.graphics.Color {
        val p = pos.coerceIn(0f, 1f)
        return when {
            p <= DARK_END -> androidx.compose.ui.graphics.lerp(androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.hsv(0f, 1f, 1f), p / DARK_END)
            p >= LIGHT_START -> androidx.compose.ui.graphics.lerp(androidx.compose.ui.graphics.Color.hsv(HUE_MAX, 1f, 1f), androidx.compose.ui.graphics.Color.White, (p - LIGHT_START) / (1f - LIGHT_START))
            else -> androidx.compose.ui.graphics.Color.hsv((p - DARK_END) / (LIGHT_START - DARK_END) * HUE_MAX, 1f, 1f)
        }
    }

    /** Воспринимаемая яркость цвета 0..1 (по сжатым значениям, как яркость фона [BgLum]). */
    fun perc(c: androidx.compose.ui.graphics.Color): Float = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
}

/** Цвет значков верхней строки: выбранный пользователем, иначе def (как задано темой). */
internal fun barTint(def: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color = IconColor.forced ?: def

/** Контур значка выбранного цвета, если яркость значка близка к яркости фона под ним (иначе значок теряется): противоположная яркость; null: контур не нужен. */
private fun forcedOutline(icon: androidx.compose.ui.graphics.Color, behindLum: Float): androidx.compose.ui.graphics.Color? {
    val il = IconColor.perc(icon)
    if (kotlin.math.abs(il - behindLum) >= 0.22f) return null
    return if (il > 0.5f) androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.8f) else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f)
}

/** Яркость под значком кнопки верхней строки: плашка кнопки, а при виде «Без фона» фон «Дома». */
@Composable
private fun barBehindLum(): Float {
    val t = LocalTh.current
    return if (Cfg.barFill == "none") (BgLum.top ?: IconColor.perc(t.bg)) else IconColor.perc(t.tile)
}

/** Контур для значка кнопки верхней строки при выбранном цвете (null при «Авто» и когда значок и так виден). */
@Composable
private fun barForcedOutline(tint: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color? =
    if (IconColor.forced == null) null else forcedOutline(tint, barBehindLum())

/**
 * Цвет надписи на кнопке (поле поиска, «Голос»): выбранный цвет, если он виден на плашке кнопки, иначе цвет темы (текст в поле нельзя обвести, а цвет,
 * близкий к цвету плашки, не читается).
 */
@Composable
private fun barTextTint(def: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color {
    val f = IconColor.forced ?: return def
    return if (kotlin.math.abs(IconColor.perc(f) - barBehindLum()) >= 0.22f) f else def
}

/** Значок внутри кнопки верхней строки (микрофон, лупа): цвет по выбору пользователя, при необходимости с контуром; при «Авто» обычный значок. */
@Composable
private fun TintIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, def: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp, pad: androidx.compose.ui.unit.Dp) {
    val tint = barTint(def)
    val ol = barForcedOutline(tint)
    if (ol != null) OutlinedIcon(icon, tint, size, pad, ol)
    else Icon(icon, null, tint = tint, modifier = Modifier.padding(pad).size(size))
}

/** Скругление кнопок и поля поиска верхней строки по выбору пользователя. */
private fun barR(d: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp = when (Cfg.barShape) {
    "round" -> 40.dp
    "square" -> 6.dp
    else -> d
}

private val OUTLINE_DIRS = listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f, 0.7f to 0.7f, -0.7f to 0.7f, 0.7f to -0.7f, -0.7f to -0.7f)

/** Значок с контуром: под ним восемь смещённых копий цвета контура. */
@Composable
private fun OutlinedIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp, pad: androidx.compose.ui.unit.Dp, outline: androidx.compose.ui.graphics.Color? = null) {
    val o = outline ?: outlineOf(tint, if (IconColor.forced != null) (BgLum.top ?: IconColor.perc(LocalTh.current.bg)) else null)
    Box(Modifier.padding(pad)) {
        for ((dx, dy) in OUTLINE_DIRS) Icon(icon, null, tint = o, modifier = Modifier.size(size).offset((dx * 1.4f).dp, (dy * 1.4f).dp))
        Icon(icon, null, tint = tint, modifier = Modifier.size(size))
    }
}

/** Размер цифры в колокольчике как доля размера значка и сдвиг центра цифры вниз (доля размера значка). */
private const val BELL_DIGIT_EM = 0.40f
private const val BELL_DIGIT_DY = 0.05f

/**
 * Колокольчик уведомлений (12.0): цвет как у остальных значков строки (как у шестерёнки, тусклого состояния нет); число уведомлений рисуется жирной цифрой
 * по центру тела колокольчика, прямо на его заливке, без плашки, кружка, контура и тени; цвет цифры контрастный колокольчику (на белом чёрная, на тёмном белая). 0 уведомлений:
 * цифры нет; 100 и больше показываются как «99». Число читается из наблюдаемого [NotifService.badge] прямо здесь, поэтому меняется сразу при поступлении и снятии
 * уведомления. Цифра рисуется поверх значка и его контура и не влияет на размеры: ширину значка и расчёт [TopBarPlan] не меняет, фокус и нажатия не перехватывает
 * (нажатие по колокольчику открывает окно уведомлений, как и раньше). Размер цифры считается от размера значка (он масштабируется с [big] и [sc]);
 * цвет цифры выбирается по яркости цвета колокольчика (светлый колокольчик: чёрная цифра, тёмный: белая).
 */
@Composable
private fun BellWithCount(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp, pad: androidx.compose.ui.unit.Dp) {
    val n = NotifService.badge.intValue
    val desc = stringResource(R.string.nt_title) + (if (n > 0) ": $n" else "")
    Box(Modifier.semantics { contentDescription = desc }) {
        OutlinedIcon(icon, tint, size, pad)
        if (n > 0) {
            // 100 и больше показываются как «99» тем же размером; размер цифр ОДИН при любом числе (слова владелицы: «всегда одинаковое»), подобран так, чтобы две цифры
            // помещались в тело колокольчика (оно занимает примерно половину ширины значка); масштабируется только вместе со значком
            val txt = if (n > 99) "99" else n.toString()
            val em = size * BELL_DIGIT_EM
            val dn = androidx.compose.ui.platform.LocalDensity.current
            val fs = with(dn) { em.toSp() }
            // цвет цифры ОДИН и контрастный колокольчику, без контура, тени и красного (слова владелицы: «на белом чёрная, на чёрном белая, без обводок вообще»);
            // выбирается по яркости цвета колокольчика, а не по названию темы («Дом» бывает и поверх картинки)
            val digit = if (IconColor.perc(tint) > 0.55f) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                // оптический центр тела колокольчика чуть ниже геометрического центра значка (внизу он шире)
                Text(txt, color = digit, fontSize = fs, lineHeight = fs, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false, modifier = Modifier.offset(y = size * BELL_DIGIT_DY))
            }
        }
    }
}

/** Текст с контуром: сначала обводка цветом контура (штрих), поверх обычный текст. */
@Composable
private fun OutlinedText(
    text: androidx.compose.ui.text.AnnotatedString, color: androidx.compose.ui.graphics.Color, fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier, fontWeight: FontWeight? = null, lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    style: androidx.compose.ui.text.TextStyle = androidx.compose.material3.LocalTextStyle.current,
) {
    val o = outlineOf(color, if (IconColor.forced != null) (BgLum.top ?: IconColor.perc(LocalTh.current.bg)) else null)
    val strokePx = with(androidx.compose.ui.platform.LocalDensity.current) { fontSize.toPx() * 0.2f }
    Box(modifier) {
        Text(
            text, color = o, fontSize = fontSize, fontWeight = fontWeight, lineHeight = lineHeight, maxLines = 1, softWrap = false,
            style = style.copy(drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = strokePx, join = androidx.compose.ui.graphics.StrokeJoin.Round)),
        )
        Text(text, color = color, fontSize = fontSize, fontWeight = fontWeight, lineHeight = lineHeight, maxLines = 1, softWrap = false, style = style)
    }
}
