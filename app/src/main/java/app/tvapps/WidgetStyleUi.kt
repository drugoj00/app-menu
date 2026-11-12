package app.tvapps

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date

/** Одна строка списка настроек: получает запрос фокуса, если это первая строка. */
private typealias SRow = @Composable (FocusRequester?) -> Unit

/**
 * Экран «Вид часов и погоды»: сверху живой предпросмотр самого виджета, под ним готовые наборы, ниже группы настроек
 * (Время, Дата, Цвета, Дополнительно; у погоды Погода, Прогноз, Цвета, Дополнительно). Ползунки с числом и кнопкой «По умолчанию»,
 * цвета полосой с «Как у темы». Пульт: вверх и вниз по пунктам, влево и вправо меняют значение, ОК включает, раскрывает или возвращает по умолчанию;
 * касание и мышь тоже. Значения сразу пишутся в виджет (HWidget.style): «Дом» за окном и предпросмотр перерисовываются сразу.
 */
@Composable
internal fun WidgetStyleDialog(dlg: Dlg.WidgetStyle, setDlg: (Dlg?) -> Unit) {
    val ctx = LocalContext.current
    val t = LocalBaseTh.current ?: LocalTh.current
    val row = HomeStore.row(dlg.rowId)
    val w = row?.slots?.getOrNull(dlg.slot)
    if (row == null || w == null || (w.type != "clock" && w.type != "weather")) { setDlg(null); return }
    val clock = w.type == "clock"
    val s = WStyle.parse(w.style)
    val on = stringResource(R.string.on)
    val off = stringResource(R.string.off)
    val tog = { b: Boolean -> if (b) on else off }
    val upd = { f: (WStyle) -> WStyle -> HomeStore.updateSlot(row.id, dlg.slot) { it.copy(style = f(WStyle.parse(it.style)).json()) } }
    val updW = { f: (HWidget) -> HWidget -> HomeStore.updateSlot(row.id, dlg.slot, f) }
    val close = { setDlg(Dlg.WidgetD(row.id, dlg.slot)) }
    var open by remember(dlg.rowId, dlg.slot) { mutableStateOf(if (clock) "time" else "weather") }
    var sureReset by remember(dlg) { mutableStateOf(false) }
    var fonts by remember { mutableStateOf(false) }
    var hintOpen by remember { mutableStateOf(false) }
    var keyDlg by remember { mutableStateOf(false) }
    var statTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val srcRev = WxSrc.rev.intValue
    val first = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.92f).dp

    // ------------------------------------------------ строки
    fun slider(label: Int, v: Int, def: Int, lo: Int, hi: Int, step: Int = 5, set: (WStyle, Int) -> WStyle): SRow =
        { _ -> SliderRow(stringResource(label), v, def, lo, hi, step) { x -> upd { set(it, x) } } }
    fun opt(o: @Composable () -> Opt): SRow = { r -> OptRow(o(), r) }
    @Composable fun toggle(label: Int, v: Boolean, set: (WStyle, Boolean) -> WStyle): Opt = Opt(stringResource(label), tog(v), onClick = { upd { set(it, !v) } })
    @Composable fun choice(label: Int, names: List<Int>, v: Int, set: (WStyle, Int) -> WStyle): Opt {
        val n = names.size
        val cur = v.coerceIn(0, n - 1)
        return Opt(stringResource(label), stringResource(names[cur]), { upd { set(it, (cur + n - 1) % n) } }, { upd { set(it, (cur + 1) % n) } })
    }
    @Composable fun colorBar(label: Int, c: Long, set: (WStyle, Long) -> WStyle): Opt = Opt(stringResource(label), bar = ColorBarSpec(c) { v -> upd { set(it, v) } })
    @Composable fun modeToggle(label: Int, bit: Int): Opt {
        val b = (s.modes and bit) != 0
        return Opt(stringResource(label), tog(b), onClick = {
            upd { val m = if (b) it.modes and bit.inv() else it.modes or bit; if (m == 0) it else it.copy(modes = m, view = if ((m and (1 shl it.view.coerceIn(0, 2))) != 0) it.view else nextForecastMode(it.view, m)) }
        })
    }

    val items = ArrayList<SRow>()
    fun group(id: String, title: Int, content: List<SRow>) {
        items += { r -> OptRow(Opt(stringResource(title), if (open == id) "▴" else "▾", onClick = { open = if (open == id) "" else id }), r) }
        if (open == id) items.addAll(content)
    }

    if (clock) {
        group("time", R.string.ws_g_time, buildList<SRow> {
            add(slider(R.string.ws_sz_time, s.timeSz, 100, 40, 300) { a, x -> a.copy(timeSz = x) })
            add(opt { Opt(stringResource(R.string.wg_time_fmt), if ((w.n and 1) != 0) "12 h" else "24 h", onClick = { updW { it.copy(n = it.n xor 1) } }) })
            add(opt { Opt(stringResource(R.string.wg_seconds), tog((w.n and 2) != 0), onClick = { updW { it.copy(n = it.n xor 2) } }) })
            if ((w.n and 2) != 0) add(slider(R.string.ws_sz_sec, s.secSz, 100, 40, 200) { a, x -> a.copy(secSz = x) })
            add(opt { toggle(R.string.ws_hide0, s.hide0) { a, v -> a.copy(hide0 = v) } })
            add(opt { choice(R.string.ws_sep, listOf(R.string.ws_sep0, R.string.ws_sep1, R.string.ws_sep2), s.sep) { a, v -> a.copy(sep = v) } })
            add(opt { choice(R.string.ws_digits, listOf(R.string.ws_dg0, R.string.ws_dg1), s.digits) { a, v -> a.copy(digits = v) } })
            if (s.digits == 0) {
                add(opt { choice(R.string.ws_font, listOf(R.string.ws_f0, R.string.ws_f1, R.string.ws_f2, R.string.ws_f3, R.string.ws_f4, R.string.ws_f5, R.string.ws_f6), s.font) { a, v -> a.copy(font = v) } })
                if (s.font == 6) add(opt { Opt(stringResource(R.string.ws_font_file), s.fontFile.substringAfterLast('/').ifBlank { "—" }, onClick = { fonts = true }) })
                add(opt { toggle(R.string.ws_bold, s.bold) { a, v -> a.copy(bold = v) } })
            } else {
                add(opt { toggle(R.string.ws_ghost, s.ghost) { a, v -> a.copy(ghost = v) } })
                add(opt { toggle(R.string.ws_slant, s.slant) { a, v -> a.copy(slant = v) } })
            }
        })
        group("date", R.string.ws_g_date, buildList<SRow> {
            add(opt { Opt(stringResource(R.string.wg_show_date), tog(w.flag), onClick = { updW { it.copy(flag = !it.flag) } }) })
            add(opt { toggle(R.string.ws_week_sep, s.weekSep) { a, v -> a.copy(weekSep = v) } })
            add(opt { Opt(stringResource(R.string.ws_date_fmt), SimpleDateFormat(s.datePattern(), AppLoc.locale()).format(Date()),
                { upd { it.copy(dateFmt = (it.dateFmt + WStyle.FORMATS - 1) % WStyle.FORMATS) } }, { upd { it.copy(dateFmt = (it.dateFmt + 1) % WStyle.FORMATS) } }) })
            if (s.weekSep) add(slider(R.string.ws_sz_week, s.weekSz, 100, 40, 300) { a, x -> a.copy(weekSz = x) })
            add(slider(R.string.ws_sz_date, s.dateSz, 100, 40, 300) { a, x -> a.copy(dateSz = x) })
        })
        group("colors", R.string.ws_g_colors, listOf(
            opt { colorBar(R.string.ws_c_time, s.cTime) { a, v -> a.copy(cTime = v) } },
            opt { colorBar(R.string.ws_c_sec, s.cSec) { a, v -> a.copy(cSec = v) } },
            opt { colorBar(R.string.ws_c_week, s.cWeek) { a, v -> a.copy(cWeek = v) } },
            opt { colorBar(R.string.ws_c_date, s.cDate) { a, v -> a.copy(cDate = v) } },
        ))
        group("extra", R.string.ws_g_extra, listOf(
            opt { toggle(R.string.ws_sun, s.sun) { a, v -> a.copy(sun = v) } },
            opt { toggle(R.string.ws_alarm, s.alarm) { a, v -> a.copy(alarm = v) } },
            opt { toggle(R.string.ws_moon, s.moon) { a, v -> a.copy(moon = v) } },
            slider(R.string.ws_bg_alpha, s.bgAlpha, 100, 0, 100) { a, x -> a.copy(bgAlpha = x) },
            opt { toggle(R.string.ws_shadow, s.shadow) { a, v -> a.copy(shadow = v) } },
            opt { toggle(R.string.ws_outline, s.outline) { a, v -> a.copy(outline = v) } },
            opt { choice(R.string.ws_click_time, listOf(R.string.ws_ck0, R.string.ws_ck1, R.string.ws_ck2), s.clickT) { a, v -> a.copy(clickT = v) } },
        ))
    } else {
        group("weather", R.string.ws_g_weather, listOf(
            slider(R.string.ws_sz_temp, s.tempSz, 100, 40, 300) { a, x -> a.copy(tempSz = x) },
            slider(R.string.ws_sz_info, s.infoSz, 100, 40, 250) { a, x -> a.copy(infoSz = x) },
            opt { toggle(R.string.ws_desc, s.desc) { a, v -> a.copy(desc = v) } },
            opt { toggle(R.string.ws_wind, s.wind) { a, v -> a.copy(wind = v) } },
            opt { toggle(R.string.ws_feels, s.feels) { a, v -> a.copy(feels = v) } },
            opt { toggle(R.string.ws_hum, s.hum) { a, v -> a.copy(hum = v) } },
            opt { toggle(R.string.ws_press, s.press) { a, v -> a.copy(press = v) } },
            opt { toggle(R.string.ws_sun, s.wxSun) { a, v -> a.copy(wxSun = v) } },
            opt { toggle(R.string.ws_moon, s.wxMoon) { a, v -> a.copy(wxMoon = v) } },
            opt { choice(R.string.ws_u_temp, listOf(R.string.ws_ut0, R.string.ws_ut1), s.uTemp) { a, v -> a.copy(uTemp = v) } },
            opt { choice(R.string.ws_u_wind, listOf(R.string.ws_uw0, R.string.ws_uw1, R.string.ws_uw2), s.uWind) { a, v -> a.copy(uWind = v) } },
            opt { choice(R.string.ws_u_press, listOf(R.string.ws_up0, R.string.ws_up1), s.uPress) { a, v -> a.copy(uPress = v) } },
        ))
        group("fcst", R.string.ws_g_fcst, listOf(
            opt { choice(R.string.ws_view, listOf(R.string.ws_view0, R.string.ws_view1, R.string.ws_view2), s.view) { a, v -> a.copy(view = v) } },
            opt { modeToggle(R.string.ws_mode0, 1) }, opt { modeToggle(R.string.ws_mode1, 2) }, opt { modeToggle(R.string.ws_mode2, 4) },
            opt { Opt(stringResource(R.string.ws_hour_step), "${s.hourStep}", { upd { it.copy(hourStep = HOUR_STEPS[(HOUR_STEPS.indexOf(it.hourStep).coerceAtLeast(0) + HOUR_STEPS.size - 1) % HOUR_STEPS.size]) } },
                { upd { it.copy(hourStep = HOUR_STEPS[(HOUR_STEPS.indexOf(it.hourStep).coerceAtLeast(0) + 1) % HOUR_STEPS.size]) } }) },
            opt { toggle(R.string.ws_h_wind, s.hWind) { a, v -> a.copy(hWind = v) } },
            opt { toggle(R.string.ws_h_pop, s.hPop) { a, v -> a.copy(hPop = v) } },
            opt { Opt(stringResource(R.string.wg_days), "${w.n}", { updW { it.copy(n = (it.n - 1).coerceAtLeast(0)) } }, { updW { it.copy(n = (it.n + 1).coerceAtMost(5)) } }) },
            opt { choice(R.string.ws_click_wx, listOf(R.string.ws_cw0, R.string.ws_cw1), s.clickW) { a, v -> a.copy(clickW = v) } },
        ))
        group("src", R.string.wsrc_title, listOf(
            opt {
                val cur = WxSrc.id(ctx)
                val ids = WxSrc.IDS
                val i = ids.indexOf(cur).coerceAtLeast(0)
                Opt(stringResource(R.string.wsrc_title), stringResource(WxSrc.nameRes(cur)), { WxSrc.set(ctx, ids[(i + ids.size - 1) % ids.size]) }, { WxSrc.set(ctx, ids[(i + 1) % ids.size]) })
            },
            opt {
                val cur = WxSrc.id(ctx)
                if (WxSrc.needsKey(cur)) Opt(stringResource(R.string.wsrc_key), if (WxSrc.key(ctx).isBlank()) stringResource(R.string.wsrc_key_none) else "••••" + WxSrc.key(ctx).takeLast(3), onClick = { keyDlg = true })
                else Opt(stringResource(R.string.wsrc_key), stringResource(R.string.wsrc_key_not_needed))
            },
            opt {
                @Suppress("UNUSED_EXPRESSION") srcRev; statTick
                val c = WeatherData.cached(ctx, w.city)
                Opt(stringResource(R.string.wsrc_data),
                    if (c == null) stringResource(R.string.wsrc_none) else stringResource(R.string.wsrc_note, SimpleDateFormat("HH:mm", java.util.Locale.US).format(Date(c.at)), WxSrc.shortName(if (c.src == 0) WxSrc.OPEN_METEO else c.src)),
                    onClick = { Thread { WeatherData.load(ctx, w.city, AppLoc.code(), true); WxSrc.rev.intValue++; statTick++ }.start() })
            },
            opt { toggle(R.string.wsrc_show, s.wxSrc) { a, v -> a.copy(wxSrc = v) } },
        ))
        group("colors", R.string.ws_g_colors, listOf(
            opt { colorBar(R.string.ws_c_temp, s.cTemp) { a, v -> a.copy(cTemp = v) } },
            opt { colorBar(R.string.ws_c_neg, s.cNeg) { a, v -> a.copy(cNeg = v) } },
            opt { colorBar(R.string.ws_c_info, s.cInfo) { a, v -> a.copy(cInfo = v) } },
        ))
        group("extra", R.string.ws_g_extra, listOf(
            slider(R.string.ws_bg_alpha, s.bgAlpha, 100, 0, 100) { a, x -> a.copy(bgAlpha = x) },
            opt { toggle(R.string.ws_shadow, s.shadow) { a, v -> a.copy(shadow = v) } },
            opt { toggle(R.string.ws_outline, s.outline) { a, v -> a.copy(outline = v) } },
        ))
    }

    // ------------------------------------------------ окно
    if (fonts) {
        val files = remember { fontFiles(ctx) }
        val folder = remember { fontFolders(ctx).firstOrNull()?.absolutePath.orEmpty() }
        val opts = if (files.isEmpty()) listOf(Opt(stringResource(R.string.ws_no_fonts)) { fonts = false })
        else files.map { f -> Opt(f.name, if (s.fontFile == f.absolutePath) "✓" else "") { upd { it.copy(font = 6, fontFile = f.absolutePath) }; fonts = false } }
        ListDialog(stringResource(R.string.ws_font_file), opts, { fonts = false }, stringResource(R.string.ws_h_fontfile, folder))
    }
    if (keyDlg) TextDialog(stringResource(R.string.wsrc_key), WxSrc.key(ctx), stringResource(R.string.wsrc_key_hint), { WxSrc.setKey(ctx, it); keyDlg = false }, { keyDlg = false })
    val names = presetNames(clock)
    androidx.compose.ui.window.Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MouseDragInDialog()
        FixedFont { CompositionLocalProvider(LocalTh provides t) { Column(
            Modifier.widthIn(min = 340.dp, max = 640.dp).heightIn(max = maxH)
                .clip(RoundedCornerShape(20.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(if (clock) R.string.ws_title_clock else R.string.ws_title_weather), color = t.text, style = Typo.iconLabel(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            // живой предпросмотр: тот же виджет, что на «Доме»
            val fsDensity = LocalDensity.current
            val shape = RoundedCornerShape(w.radius.coerceIn(0, 40).dp)
            Box(
                Modifier.fillMaxWidth().height(if (clock) 120.dp else 140.dp).clip(shape)
                    .background(t.tile.copy(alpha = t.tile.alpha * (s.bgAlpha / 100f))).border(1.dp, t.border, shape),
            ) {
                CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(fsDensity.density, fsDensity.fontScale * w.fs.coerceIn(50, 250) / 100f)) {
                    WidgetBody(w, 0, 0)
                }
            }
            // готовые наборы одним нажатием
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                names.forEachIndexed { id, nm -> Chip(stringResource(nm), s.preset == id) { updW { applyPreset(it, id) } } }
            }
            Column(Modifier.animateContentSize()) {
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { hintOpen = !hintOpen }.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(stringResource(R.string.hint_more), color = t.sub, fontSize = 13.sp)
                    Text(if (hintOpen) "▴" else "ⓘ", color = t.accent, fontSize = 14.sp)
                }
                if (hintOpen) Text(stringResource(if (clock) R.string.ws_h_main_clock else R.string.ws_h_main_weather), color = t.hint, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            BarLazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(items) { i, c -> c(if (i == 0) first else null) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // «Вернуть стандартные»: все настройки вида возвращаются к значениям выбранного набора
                Chip(if (sureReset) stringResource(R.string.ws_reset_sure) else stringResource(R.string.ws_reset), false) {
                    if (sureReset) { updW { applyPreset(it, WStyle.parse(it.style).preset) }; sureReset = false } else sureReset = true
                }
                Chip(stringResource(R.string.ws_done), true) { close() }
            }
        } } }
    }
}

private val HOUR_STEPS = listOf(1, 3, 6)

/** Названия наборов вида: у часов «Классический, Цифровой, Крупный», у погоды «Классический, Как у Tablet Clock, Крупный, Подробный». */
private fun presetNames(clock: Boolean): List<Int> =
    if (clock) listOf(R.string.ws_pc0, R.string.ws_pc1, R.string.ws_pc2) else listOf(R.string.ws_pw0, R.string.ws_pw1, R.string.ws_pw2, R.string.ws_pw3)

/** Применить набор вида к виджету: настройки заменяются значениями набора (у «Цифрового» часов включаются секунды). */
internal fun applyPreset(w: HWidget, id: Int): HWidget = w.copy(style = WStyle.preset(w.type, id).json(), n = WStyle.presetN(w.type, id, w.n))

/**
 * Ползунок с числом: название, справа значение и кнопка «По умолчанию»; ниже полоса. Пульт: влево и вправо меняют на шаг, ОК возвращает значение по умолчанию.
 * Касание и мышь: нажать или вести по полосе.
 */
@Composable
internal fun SliderRow(label: String, value: Int, def: Int, lo: Int, hi: Int, step: Int, onChange: (Int) -> Unit) {
    val t = LocalTh.current
    var focused by remember { mutableStateOf(false) }
    val (hm, hover) = rememberHover()
    val gate = remember { StepGate() }
    val set = { v: Int -> onChange(v.coerceIn(lo, hi)) }
    Column(
        Modifier.fillMaxWidth()
            .then(hm)
            .onPreviewKeyEvent { ev ->
                if (OkShield.eats(ev)) return@onPreviewKeyEvent true
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft -> { if (gate.ok()) set(value - step); true }
                    Key.DirectionRight -> { if (gate.ok()) set(value + step); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { set(def); true }
                    else -> false
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused || hover) t.tileFocus else t.tile)
            .border(if (focused) 3.dp else 1.dp, if (focused) t.accent else t.border, RoundedCornerShape(12.dp))
            .focusable()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, color = t.text, style = Typo.iconLabel(), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$value%", color = t.accent, style = Typo.iconLabel())
            Text(stringResource(R.string.ws_default), color = if (value == def) t.bg else t.sub, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (value == def) t.accent else Color.Transparent)
                    .border(1.dp, if (value == def) t.accent else t.border, RoundedCornerShape(14.dp))
                    .clickable { set(def) }.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        Canvas(
            Modifier.fillMaxWidth().height(24.dp)
                .pointerInput(lo, hi) { detectTapGestures { p -> set(lo + ((hi - lo) * (p.x / size.width)).toInt()) } }
                .pointerInput(lo, hi) { detectDragGestures { c, _ -> c.consume(); set(lo + ((hi - lo) * (c.position.x.coerceIn(0f, size.width.toFloat()) / size.width)).toInt()) } },
        ) {
            val h = size.height
            drawRoundRect(t.border, androidx.compose.ui.geometry.Offset(0f, h * 0.36f), androidx.compose.ui.geometry.Size(size.width, h * 0.28f), androidx.compose.ui.geometry.CornerRadius(h * 0.14f))
            val f = ((value - lo).toFloat() / (hi - lo).coerceAtLeast(1)).coerceIn(0f, 1f)
            val cx = f * size.width
            drawRoundRect(t.accent, androidx.compose.ui.geometry.Offset(0f, h * 0.36f), androidx.compose.ui.geometry.Size(cx, h * 0.28f), androidx.compose.ui.geometry.CornerRadius(h * 0.14f))
            // метка «по умолчанию»
            val dx = ((def - lo).toFloat() / (hi - lo).coerceAtLeast(1)).coerceIn(0f, 1f) * size.width
            drawRoundRect(t.sub, androidx.compose.ui.geometry.Offset(dx - 1.5f, h * 0.18f), androidx.compose.ui.geometry.Size(3f, h * 0.64f), androidx.compose.ui.geometry.CornerRadius(1.5f))
            val tx = cx.coerceIn(h * 0.5f, size.width - h * 0.5f)
            drawCircle(Color.White, h * 0.5f, androidx.compose.ui.geometry.Offset(tx, h / 2f))
            drawCircle(t.accent, h * 0.36f, androidx.compose.ui.geometry.Offset(tx, h / 2f))
        }
    }
}

/** Первый виджет часов или погоды на «Доме»: пара (строка, место) или null. */
internal fun firstStyledWidget(): Pair<String, Int>? {
    for (r in HomeStore.layout.rows) for ((i, sl) in r.slots.withIndex()) if (sl.type == "clock" || sl.type == "weather") return r.id to i
    return null
}

internal fun toastNoWidget(ctx: android.content.Context) = Toast.makeText(ctx, ctx.getString(R.string.ws_no_widget), Toast.LENGTH_LONG).show()
