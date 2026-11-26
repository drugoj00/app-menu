@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.tvapps

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.launch


/** Кнопки прокручиваемой части настроек: подпись, запрос фокуса и положение. Нужен, чтобы вернуть фокус на ту же кнопку после ухода и возврата. */
internal class SettingsChipReg {
    class Item(val text: String, val req: FocusRequester, val contentY: () -> Float)
    val items = ArrayList<Item>()
    var mainActive = true
    fun onFocus(text: String, y: Float) { if (mainActive) MenuMemory.setSettingsFocus(text, y) }
}

/** Блоки группы показаны вложенными (узкая раскладка, группы-аккордеон): заголовок блока сдвинут вправо и с цветной полоской слева, чтобы не путать его с группой (20.11.2026, снимок S9). */
internal val LocalBlockNest = androidx.compose.runtime.compositionLocalOf { false }
internal val LocalChipReg = androidx.compose.runtime.compositionLocalOf<SettingsChipReg?> { null }
internal val LocalSettingsScroll = androidx.compose.runtime.compositionLocalOf<androidx.compose.foundation.ScrollState?> { null }
/** Положение (в координатах root) верхнего края прокручиваемой колонки настроек - нужно, чтобы [BlockHead] при раскрытии пересчитал
 * свою позицию в координатах прокрутки и подвёл заголовок к верхнему краю ОКНА программы (18.11.2026, владелица: «если я открываю
 * пункт, то название вверх окна», тот же принцип, что и для ADB-phon). floatArrayOf(0f) - простой изменяемый держатель без пересоздания. */
internal val LocalSettingsScrollAnchor = androidx.compose.runtime.compositionLocalOf<FloatArray?> { null }
/** Y-позиции (root) заголовков групп (узкая раскладка, [GroupHead]) по id - читает [SettingsScreen] в своём LaunchedEffect(grp), чтобы
 * прокрутить к только что раскрытой группе вместо жёсткого «в начало» (18.11.2026); так нет гонки между этим эффектом и анимацией. */
internal val LocalGroupHeadTops = androidx.compose.runtime.compositionLocalOf<HashMap<String, Float>?> { null }

@Composable
internal fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    val t = LocalTh.current
    var focused by remember { mutableStateOf(false) }
    val (hm, hover) = rememberHover()
    val reg = LocalChipReg.current
    val scrollSt = LocalSettingsScroll.current
    val req = remember { FocusRequester() }
    val ypos = remember { floatArrayOf(0f) }
    androidx.compose.runtime.DisposableEffect(reg, text) {
        val item = reg?.let { r -> SettingsChipReg.Item(text, req) { ypos[0] + (scrollSt?.value ?: 0) }.also { r.items.add(it) } }
        onDispose { if (item != null) reg.items.remove(item) }
    }
    val snd = rememberClickSound()
    Box(
        modifier
            .then(if (reg != null) Modifier.focusRequester(req).onGloballyPositioned { ypos[0] = it.positionInRoot().y } else Modifier)
            .then(hm)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused && reg != null) reg.onFocus(text, ypos[0] + (scrollSt?.value ?: 0)) }
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) t.chipSel else if (hover) t.tileFocus else t.tile)
            .focusBorder({ focused }, 3.dp, 1.dp, t.accent, t.border, RoundedCornerShape(12.dp))
            .clickable(onClick = { snd(); onClick() })
            .padding(horizontal = if (isNarrow()) 11.dp else 16.dp, vertical = if (isNarrow()) 7.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        // с иконкой кнопка компактна (стрелка «Назад»), подпись остаётся для доступности и для запоминания фокуса
        if (icon != null) androidx.compose.material3.Icon(icon, text, tint = t.text, modifier = Modifier.size(24.dp))
        else Text(text, color = t.text, fontSize = btnSp(15), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

/** Пояснения пункта настроек, которые выводит [SectionHint] внутри [Section]: пункт сворачивает их под своё название. */
internal val LocalSectionHints = androidx.compose.runtime.compositionLocalOf<androidx.compose.runtime.snapshots.SnapshotStateList<String>?> { null }

/** Длинное пояснение пункта: ничего не рисует само, а передаёт текст названию пункта (раскрывается нажатием на название). */
@Composable
internal fun SectionHint(text: String) {
    val list = LocalSectionHints.current
    if (list == null) {
        Text(text, color = LocalTh.current.hint, fontSize = hintSp(12))
        return
    }
    androidx.compose.runtime.DisposableEffect(text) { list.add(text); onDispose { list.remove(text) } }
}

/**
 * Пункт настроек: название (со значком-стрелкой, если есть пояснение) и содержимое. Пояснение свёрнуто, раскрывается нажатием на название
 * (пульт: фокус на названии, ОК); состояние раскрытия между входами не сохраняется.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    // поиск по настройкам: при непустом запросе показываются только разделы, в названии которых он есть
    val q = LocalSettingsQuery.current
    if (q.isNotBlank() && !SettingsSearch.matches(q, title)) return
    val t = LocalTh.current
    val hints = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var open by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    // 18.11.2026: animateContentSize не принимает свой withContext - полагаемся на AnimScale.forceFull() (Desks.kt); компонент общий для
    // всех пунктов настроек, свою анимацию раскрытия здесь переписывать рискованно (большая часть экрана настроек проходит через Section)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hints.isEmpty()) Text(title, color = t.sub, fontSize = 13.sp)
        else Row(
            Modifier.onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape(8.dp))
                .focusBorder({ focused }, 2.dp, 0.dp, t.accent, Color.Transparent, RoundedCornerShape(8.dp))
                .clickable(role = androidx.compose.ui.semantics.Role.Button) { open = !open }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, color = t.sub, fontSize = 13.sp)
            Text(if (open) "▴" else "ⓘ", color = t.accent, fontSize = 14.sp)
        }
        if (open && hints.isNotEmpty()) Text(hints.joinToString("\n\n"), color = t.hint, fontSize = hintSp(12))
        androidx.compose.runtime.CompositionLocalProvider(LocalSectionHints provides hints) { content() }
    }
}

private val GRID_BTN_H = 76.dp
private val GRID_NUM_H = 84.dp

/** Кнопка «+» или «−»: знак рисуется линиями ровно по центру (в шрифте знаки сидят выше центра строки). Вид как у Chip. */
@Composable
private fun GridBtn(plus: Boolean, modifier: Modifier, gate: StepGate, onClick: () -> Unit) {
    val t = LocalTh.current
    var focused by remember { mutableStateOf(false) }
    val (hm, hover) = rememberHover()
    Box(
        modifier
            .then(hm)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (hover) t.tileFocus else t.tile)
            .focusBorder({ focused }, 3.dp, 1.dp, t.accent, t.border, RoundedCornerShape(12.dp))
            .stepKeys(gate, onClick)
            .holdStep(gate, onClick)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(42.dp)) {
            val w = size.width
            val h = size.height
            val sw = 6.dp.toPx()
            drawLine(t.text, androidx.compose.ui.geometry.Offset(0f, h / 2), androidx.compose.ui.geometry.Offset(w, h / 2), sw, androidx.compose.ui.graphics.StrokeCap.Round)
            if (plus) drawLine(t.text, androidx.compose.ui.geometry.Offset(w / 2, 0f), androidx.compose.ui.geometry.Offset(w / 2, h), sw, androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/** Счётчик: «+» сверху, число в окошке, «−» снизу, подпись под ним. Одно нажатие меняет число на шаг; 0 у строк и столбцов показано как «Авто». */
@Composable
internal fun GridNum(value: Int, caption: String, show: @Composable (Int) -> String, onPlus: () -> Unit, onMinus: () -> Unit, onReset: (() -> Unit)? = null, range: IntRange? = null, onSet: ((Int) -> Unit)? = null, modifier: Modifier = Modifier.width(150.dp)) {
    val t = LocalTh.current
    var numDlg by remember { mutableStateOf(false) }
    if (numDlg && onSet != null) NumberDialog(caption, value, range, "", { n -> onSet(n); numDlg = false }, { numDlg = false })
    val gate = remember { StepGate() }
    // шаг считает вызывающий по живому значению настройки (а не по значению с последнего кадра): быстрые нажатия подряд не теряются и не дают скачков
    // 20.11.2026: ширина счётчика задаётся снаружи (гибкая, 96…150 dp): три счётчика в ряд не вылезают за край при крупном интерфейсе
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GridBtn(true, Modifier.fillMaxWidth().height(GRID_BTN_H), gate) { onPlus() }
        var numFocused by remember { mutableStateOf(false) }
        Box(
            Modifier.fillMaxWidth().height(GRID_NUM_H).clip(RoundedCornerShape(12.dp)).background(t.chipSel)
                .then(if (onReset != null) Modifier.onFocusChanged { numFocused = it.isFocused }.border(if (numFocused) 3.dp else 0.dp, if (numFocused) t.accent else Color.Transparent, RoundedCornerShape(12.dp)).clickable { if (onSet != null) numDlg = true else onReset() } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(show(value), color = t.text, fontSize = btnSp(17), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
        GridBtn(false, Modifier.fillMaxWidth().height(GRID_BTN_H), gate) { onMinus() }
        // подпись переносится по словам, в столбик по буквам не идёт (у счётчика есть минимальная ширина)
        Text(caption, color = t.hint, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Options(options: List<Pair<T, String>>, current: T, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) -> Chip(label, v == current) { onPick(v) } }
    }
}

/**
 * Выбор цвета образцами (вместо кнопок со словами): кружки или одинаковые прямоугольники ([rect]); выбранный с галочкой и жирным ободком,
 * под рядом - название выбранного цвета. [lightMark] - образцы, на которых галочка чёрная.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorDots(fills: List<Color>, names: List<String>, current: Int, rect: Boolean = false, lightMark: Set<Int> = emptySet(), onPick: (Int) -> Unit) {
    val t = LocalTh.current
    val shape = if (rect) RoundedCornerShape(12.dp) else androidx.compose.foundation.shape.CircleShape
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            fills.indices.forEach { i ->
                var focused by remember { mutableStateOf(false) }
                val (hm, hover) = rememberHover()
                Box(
                    (if (rect) Modifier.size(78.dp, 46.dp) else Modifier.size(46.dp))
                        .then(hm)
                        .onFocusChanged { focused = it.isFocused }
                        .clip(shape)
                        .background(fills[i])
                        .border(if (i == current || focused) 4.dp else if (hover) 2.dp else 1.dp,
                            if (focused) t.accent else if (i == current) t.text else t.border, shape)
                        .clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { onPick(i) }
                        .semantics { contentDescription = names[i] },
                    contentAlignment = Alignment.Center,
                ) {
                    if (i == current) androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                        val c = if (i in lightMark) Color.Black else Color.White
                        val w = size.width; val h = size.height; val sw = 3.dp.toPx()
                        drawLine(c, androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.55f), androidx.compose.ui.geometry.Offset(w * 0.4f, h * 0.82f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(c, androidx.compose.ui.geometry.Offset(w * 0.4f, h * 0.82f), androidx.compose.ui.geometry.Offset(w * 0.9f, h * 0.2f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                }
            }
        }
        Text(names[current], color = t.hint, fontSize = 12.sp)
    }
}

/**
 * Живой предпросмотр «Фона кнопок верхней строки»: мини-полоска на тёмной и светлой половинах картинки; рисуется теми же функциями, что и сама строка «Дома»
 * (barBg, barEdge, BarIcon), поэтому показывает выбранный вид сразу.
 */
@Composable
private fun BarFillPreview() {
    val t = LocalTh.current
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.widthIn(max = 460.dp).fillMaxWidth().height(68.dp).clip(RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF0E1B2B), Color(0xFF2F5C8A)))))
            Box(Modifier.weight(1f).fillMaxHeight().background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFFA9C9E8), Color(0xFFF3EAD0)))))
        }
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(HIcons.apps, HIcons.recent, HIcons.memory, HIcons.search).forEach { ic ->
                Box(Modifier.clip(shape).background(barBg(t.tile)).border(1.5.dp, barEdge(t.accent), shape), contentAlignment = Alignment.Center) {
                    BarIcon(ic, null, t.accent, 24.dp, 8.dp)
                }
            }
            Box(Modifier.width(120.dp).height(40.dp).clip(RoundedCornerShape(20.dp)).background(barBg(t.tile)).border(1.5.dp, t.accent, RoundedCornerShape(20.dp)))
        }
    }
}

/** Предпросмотр «Неонового ореола»: три плитки (Нет, Слабое, Сильное) на тёмной и светлой половинах картинки, ореол рисует та же функция, что и «Дом». */
@Composable
private fun GlowPreview() {
    val t = LocalTh.current
    val ring = Cfg.homeRingOr(t.accent)
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.widthIn(max = 560.dp).fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF0E1B2B), Color(0xFF2F5C8A)))))
            Box(Modifier.weight(1f).fillMaxHeight().background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFFA9C9E8), Color(0xFFF3EAD0)))))
        }
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(44.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("none" to R.string.glow_none, "weak" to R.string.glow_weak, "strong" to R.string.glow_strong).forEach { (mode, label) ->
                Box(
                    Modifier.width(84.dp).height(56.dp)
                        .drawBehind { drawSelGlow(mode, ring, 10.dp.toPx()) }
                        .clip(shape).background(t.tileFocus).border(2.dp, ring, shape),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(label), color = t.text, fontSize = 13.sp, fontWeight = if (Cfg.selGlow == mode) FontWeight.Bold else FontWeight.Normal) }
            }
        }
    }
}

internal val LocalSettingsQuery = androidx.compose.runtime.compositionLocalOf { "" }

/** Поиск по настройкам: запрос расширяется синонимами (бекап, копия, резервная; обновить, версия; шрифт, размер; обои, фон; adb, отладка; значки, иконки; usb, флешка и др.). */
internal object SettingsSearch {
    private val groups = listOf(
        listOf("бекап", "бэкап", "копи", "резерв", "backup", "copy", "copies", "відновл", "восстанов", "синхрон", "sync", "samba", "smb", "webdav", "облак", "яндекс", "сетев", "мереж"),
        listOf("быстр", "швидк", "quick", "кнопк", "пульт", "remote", "назначен", "призначен"),
        listOf("обнов", "обнов", "верси", "update", "version", "оновл", "новых"),
        listOf("шрифт", "размер", "font", "size", "масштаб", "розмір", "крупн"),
        listOf("обои", "фон", "wallpaper", "background", "шпалер", "картинк"),
        listOf("adb", "отладк", "debug", "налагодж", "root", "подключен"),
        listOf("значк", "иконк", "icon", "піктогр"),
        listOf("usb", "флеш", "флэш", "накопител", "внешн", "пакетн", "файл", "apk"),
        listOf("тема", "цвет", "акцент", "theme", "color", "colour", "колір"),
        listOf("язык", "language", "lang", "мова"),
        listOf("дом", "home", "главн"),
        listOf("док", "dock", "панел", "panel", "избранн"),
        listOf("автозапуск", "autostart", "запуск", "запуска", "включени", "boot", "завантаж"),
    )
    fun matches(query: String, title: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        val words = HashSet<String>().apply { add(q) }
        for (g in groups) if (g.any { w -> q.contains(w) || (q.length >= 3 && w.startsWith(q)) }) words.addAll(g)
        val tl = title.lowercase()
        return words.any { tl.contains(it) }
    }
}

/**
 * Кнопка-действие (правило 18): значок, короткое название и одна строка пояснения; одинаково во всех настройках, ширина по содержимому (не растянута),
 * высота не меньше 56 dp, рамка фокуса пульта 3 dp. Учитывается в памяти фокуса настроек так же, как [Chip].
 */
@Composable
internal fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, hint: String = "", modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = LocalTh.current
    var focused by remember { mutableStateOf(false) }
    val (hm, hover) = rememberHover()
    val reg = LocalChipReg.current
    val scrollSt = LocalSettingsScroll.current
    val req = remember { FocusRequester() }
    val ypos = remember { floatArrayOf(0f) }
    androidx.compose.runtime.DisposableEffect(reg, title) {
        val item = reg?.let { r -> SettingsChipReg.Item(title, req) { ypos[0] + (scrollSt?.value ?: 0) }.also { r.items.add(it) } }
        onDispose { if (item != null) reg.items.remove(item) }
    }
    val snd = rememberClickSound()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.then(if (reg != null) Modifier.fillMaxWidth() else Modifier).widthIn(min = 240.dp, max = 900.dp).heightIn(min = if ((androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500 && !isNarrow())) 48.dp else 56.dp)
            .then(hm)
            .then(if (reg != null) Modifier.focusRequester(req).onGloballyPositioned { ypos[0] = it.positionInRoot().y } else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused && reg != null) reg.onFocus(title, ypos[0] + (scrollSt?.value ?: 0)) }
            .clip(shape)
            .background(if (hover) t.tileFocus else t.tile)
            .focusBorder({ focused }, 3.dp, 1.dp, t.accent, t.border, shape)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = { snd(); onClick() })
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.material3.Icon(icon, null, tint = t.accent, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f, fill = false)) {
            // межстрочный зазор мал: название и пояснение стоят почти вплотную (просьба владелицы)
            Text(title, color = t.text, fontSize = btnSp(15), lineHeight = btnSp(15) * 1.1f, fontWeight = FontWeight.Medium)
            if (hint.isNotEmpty()) Text(hint, color = t.hint, fontSize = hintSp(12), lineHeight = hintSp(12) * 1.1f, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Переключатель в строках списков (16.11.2026: прежний вид, обычный Switch; крупные квадраты отменены владелицей). Нажимается вся строка. */
@Composable
internal fun BigCheck(checked: Boolean, fill: Color, line: Color, tick: Color = Color.White) {
    val t = LocalTh.current
    // цвета интерфейса программы, а не фиолетовый по умолчанию: включено = акцент темы, дорожка светлее, выключено = нейтральный серый
    androidx.compose.material3.Switch(checked, null, colors = androidx.compose.material3.SwitchDefaults.colors(
        checkedThumbColor = Color.White, checkedTrackColor = t.accent, checkedBorderColor = t.accent,
        uncheckedThumbColor = t.sub, uncheckedTrackColor = t.border.copy(alpha = 0.45f), uncheckedBorderColor = t.sub,
    ))
}

/** Окно «нажмите клавишу пульта»: первая нажатая клавиша (кроме «Назад», она отменяет) становится клавишей фокуса на док. */
@Composable
private fun DockKeyCapture(onDone: (Int?) -> Unit) {
    val t = LocalTh.current
    val req = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); runCatching { req.requestFocus() } }
    HDialog(onDismissRequest = { onDone(null) }) {
        Box(
            Modifier.clip(RoundedCornerShape(20.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(24.dp)
                .focusRequester(req).focusable()
                .onPreviewKeyEvent { ev ->
                    if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                        val code = ev.nativeKeyEvent.keyCode
                        if (code == android.view.KeyEvent.KEYCODE_BACK) onDone(null) else onDone(code)
                        true
                    } else true
                },
        ) { Text(stringResource(R.string.dock_key_wait), color = t.text, fontSize = 18.sp) }
    }
}

/** Строка-счётчик «название ‹ число с ›»: нажатие на число открывает окно ввода (правило 19). */
@Composable
private fun CounterRow(title: String, value: Int, range: IntRange, unit: String, onSet: (Int) -> Unit) {
    val t = LocalTh.current
    var dlg by remember { mutableStateOf(false) }
    if (dlg) NumberDialog(title, value, range, unit, { onSet(it); dlg = false }, { dlg = false })
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = t.text, fontSize = btnSp(15), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Chip("−", false) { onSet((value - 1).coerceAtLeast(range.first)) }
        Text("$value $unit", color = t.accent, fontSize = btnSp(16), fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { dlg = true }.padding(horizontal = 10.dp, vertical = 8.dp))
        Chip("+", false) { onSet((value + 1).coerceAtMost(range.last)) }
    }
}

/** Выбор приложений автозапуска: отмеченные сверху по порядку запуска (стрелки вверх и вниз), ниже остальные. */
@Composable
private fun AutoAppsPane(ctx: android.content.Context, apps: List<Entry>) {
    val t = LocalTh.current
    @Suppress("UNUSED_VARIABLE") val rev = Autostart.rev.intValue
    val sel = Autostart.list(ctx)
    val byPkg = remember(apps) { apps.associateBy { it.pkg } }
    @Composable
    fun row(pkg: String, e: Entry?, on: Boolean, idx: Int) {
        var focused by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp).onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(12.dp))
                .drawBehind { drawRect(if (focused) t.tileFocus else t.tile) }.focusBorder({ focused }, 3.dp, 0.dp, t.accent, Color.Transparent, RoundedCornerShape(12.dp))
                .toggleable(on, onValueChange = { v -> Autostart.setList(ctx, if (v) sel + pkg else sel - pkg) })
                .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (e != null) { val bmp = remember(pkg) { e.icon.toBitmap(96, 96).asImageBitmap() }; androidx.compose.foundation.Image(bmp, null, Modifier.size(36.dp)) }
            Column(Modifier.weight(1f)) {
                Text(e?.label ?: pkg, color = t.text, fontSize = btnSp(16), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pkg, color = t.hint, fontSize = btnSp(11), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (on) {
                val m = Autostart.mode(ctx, pkg)
                Chip(stringResource(when (m) { "bg" -> R.string.auto_mode_bg; "off" -> R.string.auto_mode_off; else -> R.string.auto_mode_screen }), m != "off") { Autostart.setMode(ctx, pkg, Autostart.nextMode(m)) }
                Chip("▲", false) { if (idx > 0) Autostart.setList(ctx, sel.toMutableList().also { java.util.Collections.swap(it, idx, idx - 1) }) }
                Chip("▼", false) { if (idx < sel.size - 1) Autostart.setList(ctx, sel.toMutableList().also { java.util.Collections.swap(it, idx, idx + 1) }) }
            }
            BigCheck(on, t.accent, t.sub)
        }
    }
    Column(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
        Text(stringResource(R.string.auto_order), color = t.sub, fontSize = btnSp(13), modifier = Modifier.padding(bottom = 6.dp))
        sel.forEachIndexed { i, pkg -> androidx.compose.runtime.key("s" + pkg) { row(pkg, byPkg[pkg], true, i) } }
        Text(stringResource(R.string.auto_all), color = t.sub, fontSize = btnSp(13), modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        apps.filter { it.pkg !in sel && !it.system }.forEach { e -> androidx.compose.runtime.key("a" + e.pkg) { row(e.pkg, e, false, -1) } }
    }
}

/** 19.2: выбор приложений для автоочистки кэша: отмеченные сверху, ниже остальные (нажатие отмечает или снимает). */
@Composable
private fun CacheAppsPane(ctx: android.content.Context, apps: List<Entry>) {
    val t = LocalTh.current
    @Suppress("UNUSED_VARIABLE") val rev = CacheAuto.rev.intValue
    val sel = CacheAuto.list(ctx)
    val byPkg = remember(apps) { apps.associateBy { it.pkg } }
    @Composable
    fun row(pkg: String, e: Entry?, on: Boolean) {
        var focused by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp).onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(12.dp))
                .drawBehind { drawRect(if (focused) t.tileFocus else t.tile) }.focusBorder({ focused }, 3.dp, 0.dp, t.accent, Color.Transparent, RoundedCornerShape(12.dp))
                .toggleable(on, onValueChange = { v -> CacheAuto.setList(ctx, if (v) sel + pkg else sel - pkg) })
                .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (e != null) { val bmp = remember(pkg) { e.icon.toBitmap(96, 96).asImageBitmap() }; androidx.compose.foundation.Image(bmp, null, Modifier.size(36.dp)) }
            Column(Modifier.weight(1f)) {
                Text(e?.label ?: pkg, color = t.text, fontSize = btnSp(16), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pkg, color = t.hint, fontSize = btnSp(11), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BigCheck(on, t.accent, t.sub)
        }
    }
    Column(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
        Text(stringResource(R.string.cache_sel), color = t.sub, fontSize = btnSp(13), modifier = Modifier.padding(bottom = 6.dp))
        sel.forEach { pkg -> androidx.compose.runtime.key("s" + pkg) { row(pkg, byPkg[pkg], true) } }
        Text(stringResource(R.string.auto_all), color = t.sub, fontSize = btnSp(13), modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        apps.filter { it.pkg !in sel && !it.system }.forEach { e -> androidx.compose.runtime.key("a" + e.pkg) { row(e.pkg, e, false) } }
    }
}

/** 19.2: окно «нужны права» (правило владелицы 25.11.2026: функции нужно право, значит окно со ссылкой в настройки этого права). */
@Composable
private fun CachePermDialog(onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val needAcc = !CacheAuto.accessEnabled(ctx)
    val needShell = !CacheAuto.shellReady()
    val opts = mutableListOf<Opt>()
    if (needAcc) opts += Opt(stringResource(R.string.cache_perm_open_acc)) {
        onClose(); runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    if (needShell) opts += Opt(stringResource(R.string.cache_perm_open_adb)) {
        onClose(); runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    opts += Opt(stringResource(R.string.close)) { onClose() }
    val why = (if (needAcc) stringResource(R.string.cache_perm_acc) + " " else "") + (if (needShell) stringResource(R.string.cache_perm_adb) else "")
    ListDialog(stringResource(R.string.cache_perm_title) + ": " + why.trim(), opts, onClose)
}

/** Выбор приложений дока: отмеченные сверху по порядку запуска (стрелки вверх и вниз), ниже остальные. */
@Composable
private fun DockAppsPane(ctx: android.content.Context, apps: List<Entry>) {
    val t = LocalTh.current
    @Suppress("UNUSED_VARIABLE") val rev = HomeDock.rev.intValue
    val sel = HomeDock.list(ctx)
    val byPkg = remember(apps) { apps.associateBy { it.pkg } }
    @Composable
    fun row(pkg: String, e: Entry?, on: Boolean, idx: Int) {
        var focused by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp).onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(12.dp))
                .drawBehind { drawRect(if (focused) t.tileFocus else t.tile) }.focusBorder({ focused }, 3.dp, 0.dp, t.accent, Color.Transparent, RoundedCornerShape(12.dp))
                .toggleable(on, onValueChange = { v -> HomeDock.setList(ctx, if (v) sel + pkg else sel - pkg) })
                .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (e != null) { val bmp = remember(pkg) { e.icon.toBitmap(96, 96).asImageBitmap() }; androidx.compose.foundation.Image(bmp, null, Modifier.size(36.dp)) }
            Column(Modifier.weight(1f)) {
                Text(e?.label ?: pkg, color = t.text, fontSize = btnSp(16), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pkg, color = t.hint, fontSize = btnSp(11), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (on) {
                Chip("▲", false) { if (idx > 0) HomeDock.setList(ctx, sel.toMutableList().also { java.util.Collections.swap(it, idx, idx - 1) }) }
                Chip("▼", false) { if (idx < sel.size - 1) HomeDock.setList(ctx, sel.toMutableList().also { java.util.Collections.swap(it, idx, idx + 1) }) }
            }
            BigCheck(on, t.accent, t.sub)
        }
    }
    Column(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
        Text(stringResource(R.string.dock_order), color = t.sub, fontSize = btnSp(13), modifier = Modifier.padding(bottom = 6.dp))
        sel.forEachIndexed { i, pkg -> androidx.compose.runtime.key("s" + pkg) { row(pkg, byPkg[pkg], true, i) } }
        Text(stringResource(R.string.dock_all), color = t.sub, fontSize = btnSp(13), modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        apps.filter { it.pkg !in sel && !it.system && !Cfg.isHidden(it.pkg) }.forEach { e -> androidx.compose.runtime.key("a" + e.pkg) { row(e.pkg, e, false, -1) } }
    }
}

/** «Скрыть приложения» в доке: отмеченные не показываются в доке (ни выбранные, ни автодобавление, ни недавние); отметку можно снять. */
@Composable
private fun DockHidePane(ctx: android.content.Context, apps: List<Entry>) {
    val t = LocalTh.current
    @Suppress("UNUSED_VARIABLE") val rev = HomeDock.rev.intValue
    val sel = HomeDock.hiddenList(ctx)
    val byPkg = remember(apps) { apps.associateBy { it.pkg } }
    @Composable
    fun row(pkg: String, e: Entry?, on: Boolean) {
        var focused by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp).onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(12.dp))
                .drawBehind { drawRect(if (focused) t.tileFocus else t.tile) }.focusBorder({ focused }, 3.dp, 0.dp, t.accent, Color.Transparent, RoundedCornerShape(12.dp))
                .toggleable(on, onValueChange = { v -> HomeDock.setHiddenList(ctx, if (v) sel + pkg else sel - pkg) })
                .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (e != null) { val bmp = remember(pkg) { e.icon.toBitmap(96, 96).asImageBitmap() }; androidx.compose.foundation.Image(bmp, null, Modifier.size(36.dp)) }
            Column(Modifier.weight(1f)) {
                Text(e?.label ?: pkg, color = t.text, fontSize = btnSp(16), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pkg, color = t.hint, fontSize = hintSp(11), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BigCheck(on, t.accent, t.sub)
        }
    }
    Column(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
        Text(stringResource(R.string.dock_hide_h), color = t.sub, fontSize = hintSp(13), modifier = Modifier.padding(bottom = 6.dp))
        sel.forEach { pkg -> androidx.compose.runtime.key("h" + pkg) { row(pkg, byPkg[pkg], true) } }
        Spacer(Modifier.height(10.dp))
        apps.filter { it.pkg !in sel && !it.system && !Cfg.isHidden(it.pkg) }.forEach { e -> androidx.compose.runtime.key("n" + e.pkg) { row(e.pkg, e, false) } }
    }
}

/**
 * Заголовок блока внутри группы: сворачивается и раскрывается; при поиске всё раскрыто.
 * 18.11.2026 (владелица): при раскрытии (не при сворачивании) список сам прокручивается так, чтобы заголовок оказался
 * у верхнего края окна - иначе раскрытое содержимое уходит ниже видимой области и приходится прокручивать руками.
 * Фиксированный масштаб (DeskFullMotion) - независимо от системной настройки «Масштаб анимации».
 */
@Composable
private fun BlockHead(id: String, title: String, open: androidx.compose.runtime.snapshots.SnapshotStateList<String>, searching: Boolean) {
    if (searching) return
    val t = LocalTh.current
    var focused by remember { mutableStateOf(false) }
    val isOpen = id in open
    val scrollSt = LocalSettingsScroll.current
    val anchor = LocalSettingsScrollAnchor.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val headerTop = remember { floatArrayOf(0f) }
    val nest = LocalBlockNest.current
    val reg = LocalChipReg.current
    val req = remember { FocusRequester() }
    androidx.compose.runtime.DisposableEffect(reg, title) {
        val item = reg?.let { r -> SettingsChipReg.Item(title, req) { headerTop[0] + (scrollSt?.value ?: 0) }.also { r.items.add(it) } }
        onDispose { if (item != null) reg.items.remove(item) }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, start = if (nest) 20.dp else 0.dp)
            .onGloballyPositioned { headerTop[0] = it.positionInRoot().y }
            .then(if (reg != null) Modifier.focusRequester(req) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused && reg != null) reg.onFocus(title, headerTop[0] + (scrollSt?.value ?: 0)) }
            .clip(RoundedCornerShape(10.dp))
            .background(if (isOpen) t.chipSel else t.tile)
            .drawBehind { if (nest) drawRect(t.accent, size = androidx.compose.ui.geometry.Size(6.dp.toPx(), size.height)) }
            .focusBorder({ focused }, 3.dp, 1.dp, t.accent, t.border, RoundedCornerShape(10.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                if (isOpen) open.remove(id)
                else {
                    open.add(id)
                    val sc = scrollSt; val a = anchor
                    if (sc != null && a != null) {
                        // anchor - позиция НАЧАЛА всего прокручиваемого содержимого (Modifier.verticalScroll даёт его так, уже со
                        // сдвигом на текущую прокрутку), а не видимого края окна - поэтому здесь НЕ прибавляется scroll.value,
                        // иначе текущая прокрутка учтётся дважды (ровно это и было причиной перелёта при первой проверке вживую).
                        val target = (headerTop[0] - a[0]).toInt().coerceAtLeast(0)
                        scope.launch { kotlinx.coroutines.withContext(DeskFullMotion) { sc.animateScrollTo(target) } }
                    }
                }
            }
            .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, color = t.text, fontSize = btnSp(15), fontWeight = if (nest) FontWeight.Medium else FontWeight.Bold, modifier = Modifier.weight(1f).padding(end = 8.dp, start = if (nest) 6.dp else 0.dp))
        Text(if (isOpen) "▴" else "▾", color = t.accent, fontSize = 16.sp)
    }
}

/** Строка-переключатель: название слева, квадратик-галочка справа; пояснения раскрываются значком ⓘ. */
@Composable
private fun SwitchRow(title: String, checked: Boolean, hints: List<String> = emptyList(), onChange: (Boolean) -> Unit) {
    val q = LocalSettingsQuery.current
    if (q.isNotBlank() && !SettingsSearch.matches(q, title)) return
    val t = LocalTh.current
    var focused by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    val (hm, hover) = rememberHover()
    val reg = LocalChipReg.current
    val scrollSt = LocalSettingsScroll.current
    val req = remember { FocusRequester() }
    val ypos = remember { floatArrayOf(0f) }
    androidx.compose.runtime.DisposableEffect(reg, title) {
        val item = reg?.let { r -> SettingsChipReg.Item(title, req) { ypos[0] + (scrollSt?.value ?: 0) }.also { r.items.add(it) } }
        onDispose { if (item != null) reg.items.remove(item) }
    }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .then(hm)
                .then(if (reg != null) Modifier.focusRequester(req).onGloballyPositioned { ypos[0] = it.positionInRoot().y } else Modifier)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused && reg != null) reg.onFocus(title, ypos[0] + (scrollSt?.value ?: 0)) }
                .clip(shape).background(if (hover) t.tileFocus else t.tile)
                .focusBorder({ focused }, 3.dp, 1.dp, t.accent, t.border, shape)
                .toggleable(checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = t.text, fontSize = btnSp(15), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (hints.isNotEmpty()) Text(if (open) "▴" else "ⓘ", color = t.accent, fontSize = 16.sp, modifier = Modifier.clickable { open = !open }.padding(6.dp))
            BigCheck(checked, t.accent, t.sub)
        }
        if (open && hints.isNotEmpty()) Text(hints.joinToString("\n\n"), color = t.hint, fontSize = hintSp(12), modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
    }
}

/** Какая группа настроек открыта (помнится, пока жив процесс). */
internal object SettingsGroups {
    /** Открытая группа (портрет: раскрытая; ландшафт и ТВ: выбранная слева). Пусто - все группы свёрнуты (20.11.2026: при каждом новом входе в настройки). */
    var last: String = ""
    /** Раскрытые блоки внутри групп: помнятся, пока настройки открыты (смена ориентации, вложенные окна), при новом входе сбрасываются. */
    val openBlocks = androidx.compose.runtime.mutableStateListOf<String>()
    // Правило владелицы 20.11.2026 (для всех программ): при ЗАПУСКЕ программы всё свёрнуто; раскрытое и положение (группа, блоки, прокрутка, фокус) помнятся до закрытия
    // программы (только в памяти процесса): выход из настроек и возврат ничего не сбрасывают.
    /** Запрос открыть группу из другого места (кнопка «Док-панель», меню дока): уже открытые настройки переключаются на нужную группу. */
    var reqTick by androidx.compose.runtime.mutableStateOf(0)
    /** подэкран, который нужно открыть вместе с группой (например «dockapps»); null: сама группа */
    var openSub: String? = null
    var handledTick = 0
    val ids = listOf("updates", "ui", "menu", "home", "dock", "wall", "widgets", "dl", "auto", "files", "system")
    fun icon(id: String) = when (id) {
        "updates" -> HIcons.refresh; "ui" -> HIcons.eye; "menu" -> HIcons.apps; "home" -> HIcons.home; "dock" -> HIcons.recent; "wall" -> HIcons.eye; "widgets" -> HIcons.list; "dl" -> HIcons.download; "auto" -> HIcons.recent; "files" -> HIcons.folder; else -> HIcons.gear
    }
    fun title(id: String): Int = when (id) {
        "updates" -> R.string.grp_upd; "files" -> R.string.grp_files; "system" -> R.string.grp_system; "ui" -> R.string.grp_ui
        "menu" -> R.string.grp_menu; "dock" -> R.string.grp_dock; "wall" -> R.string.grp_wall; "widgets" -> R.string.grp_widgets; "dl" -> R.string.grp_dl; "auto" -> R.string.grp_auto; else -> R.string.grp_home
    }
}

/** Кнопка группы: компактная, выбранная подсвечена; на пульте группа выбирается при наведении фокуса, мышью и касанием по нажатию. */
@Composable
private fun GroupBtn(id: String, selected: Boolean, modifier: Modifier = Modifier, onTap: (String) -> Unit = {}, onFocusPick: (String) -> Unit = {}, onPick: (String) -> Unit) {
    val t = LocalTh.current
    var focused by remember { mutableStateOf(false) }
    val req = remember { FocusRequester() }
    val (hm, hover) = rememberHover()
    Row(
        modifier
            .then(hm)
            .focusRequester(req)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocusPick(id) }
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) t.chipSel else if (hover) t.tileFocus else t.tile)
            .focusBorder({ focused }, 3.dp, 1.dp, t.accent, t.border, RoundedCornerShape(10.dp))
            // касание и мышь: фокус встаёт на кнопку группы (иначе первая стрелка пульта уходила на самую верхнюю группу), результаты поиска сбрасываются
            .clickable(role = androidx.compose.ui.semantics.Role.Tab) { runCatching { req.requestFocus() }; onTap(id); onPick(id) }
            .padding(horizontal = 24.dp, vertical = if ((androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500 && !isNarrow())) 1.dp else if (isNarrow()) 10.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(SettingsGroups.icon(id), null, tint = t.accent, modifier = Modifier.size(if ((androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500 && !isNarrow())) 16.dp else 28.dp))
        Spacer(Modifier.width(14.dp))
        Text(stringResource(SettingsGroups.title(id)), color = t.text, fontSize = btnSp(if ((androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500 && !isNarrow())) 13 else 18), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false)
    }
}

/** Каркас окна настроек: узкая колонка групп слева (ТВ и ландшафт), содержимое занимает остальное место. */
@Composable
private fun SettingsFrame(pane: @Composable () -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) { pane(); Box(Modifier.weight(1f).fillMaxHeight()) { content() } }
}

/** Левая панель широкого экрана: список групп настроек. */
@Composable
private fun GroupsPane(current: String, onTap: (String) -> Unit = {}, onPick: (String) -> Unit) {
    // 15.1 (плавность): смена группы при наведении фокуса собирает всё содержимое группы (на приставке 100-190 мс); при быстром пролистывании списка групп
    // (следующий шаг раньше чем через 300 мс после прошлой смены) содержимое сменяется один раз, когда курсор остановился на 110 мс. Одиночный шаг - сразу, как раньше.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val gate = remember { GroupPickGate() }
    // 16.0 (плавность): ширина колонки групп считается один раз по самой широкой подписи (жирной), а не IntrinsicSize.Max при каждой смене
    // группы (на приставке это сотни вызовов maxIntrinsicWidth на каждое нажатие); подпись выбранной группы жирная, поэтому мерим жирной
    val shortScreen = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500 && !isNarrow()
    val titles = SettingsGroups.ids.map { stringResource(SettingsGroups.title(it)) }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val dens = androidx.compose.ui.platform.LocalDensity.current
    val baseStyle = androidx.compose.material3.LocalTextStyle.current
    val fsz = btnSp(if (shortScreen) 13 else 18)
    // ширины каждой подписи жирной и обычной: подпись выбранной группы жирная (колонка, как раньше, зависит от выбранной группы)
    val widths = remember(titles, dens.density, dens.fontScale, shortScreen, fsz, baseStyle) {
        titles.map { tt ->
            IntArray(2) { k -> measurer.measure(tt, baseStyle.merge(androidx.compose.ui.text.TextStyle(fontSize = fsz, fontWeight = if (k == 0) FontWeight.Bold else FontWeight.Normal)), maxLines = 1, softWrap = false).size.width }
        }
    }
    var mx = 0
    for (i in titles.indices) mx = maxOf(mx, widths[i][if (SettingsGroups.ids[i] == current) 0 else 1])
    // -1 px: так же, как у IntrinsicSize.Max (сверено по снимкам X96)
    val colW = with(dens) { (mx - 1).toDp() + 48.dp + (if (shortScreen) 16.dp else 28.dp) + 14.dp + 4.dp }
    Column(
        Modifier.width(colW)
            .fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 8.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(if ((androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500 && !isNarrow())) 2.dp else 6.dp),
    ) {
        SettingsGroups.ids.forEach { id -> GroupBtn(id, id == current, Modifier.fillMaxWidth(), onTap, { gate.request(scope, it, onPick) }, onPick) }
    }
}

/**
 * Заголовок группы в узкой раскладке (портрет телефона): нажатие раскрывает группу и сворачивает остальные. На широком экране ничего не рисует.
 * 18.11.2026 (владелица): при раскрытии группа сама прокручивается к верхнему краю окна - см. [BlockHead]. Саму прокрутку делает
 * LaunchedEffect(grp) в [SettingsScreen] (там же раньше жил сброс «в начало» - гонка с анимацией тут привела бы к рывку); эта функция
 * только запоминает свою Y-позицию в [LocalGroupHeadTops], чтобы тому эффекту было куда скроллить.
 */
@Composable
private fun GroupHead(id: String, wide: Boolean, current: String, onPick: (String) -> Unit) {
    if (wide) return
    val t = LocalTh.current
    var focused by remember { mutableStateOf(false) }
    val open = id == current
    val tops = LocalGroupHeadTops.current
    // заголовок группы входит в память фокуса настроек (как кнопки и строки): после возврата в настройки фокус на нём же (20.11.2026)
    val title = stringResource(SettingsGroups.title(id))
    val reg = LocalChipReg.current
    val scrollSt = LocalSettingsScroll.current
    val req = remember { FocusRequester() }
    val ypos = remember { floatArrayOf(0f) }
    androidx.compose.runtime.DisposableEffect(reg, title) {
        val item = reg?.let { r -> SettingsChipReg.Item(title, req) { ypos[0] + (scrollSt?.value ?: 0) }.also { r.items.add(it) } }
        onDispose { if (item != null) reg.items.remove(item) }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp)
            .onGloballyPositioned { tops?.put(id, it.positionInRoot().y); ypos[0] = it.positionInRoot().y }
            .then(if (reg != null) Modifier.focusRequester(req) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused && reg != null) reg.onFocus(title, ypos[0] + (scrollSt?.value ?: 0)) }
            .clip(RoundedCornerShape(10.dp))
            .background(if (open) t.chipSel else t.tile)
            .focusBorder({ focused }, 3.dp, 1.dp, t.accent, t.border, RoundedCornerShape(10.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.Button) { onPick(if (open) "" else id) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(SettingsGroups.title(id)), color = t.text, fontSize = btnSp(15), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Text(if (open) "▴" else "▾", color = t.accent, fontSize = 16.sp)
    }
}

@Composable
fun SettingsScreen(apps: List<Entry>, onBack: () -> Unit, onFullscreenChanged: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // положение в настройках помнится: раздел, прокрутка и кнопка в фокусе (MenuMemory); при выходе из меню кнопкой «Назад» сбрасывается
    // окна внутри настроек («Скрытые приложения», «Исключения») не запоминают положение: открываются сверху; помнится только основной экран настроек
    MenuMemory.init(ctx)
    // вложенные окна настроек («О программе», списки) открываются и закрываются через общую анимацию переходов (ScreenFx.kt)
    val subV = remember { MotionVar<String>("main", { (ctx as? HasFx)?.fx }, { if (it == "main") -1 else 1 }) }
    var sub: String by subV
    // группы настроек (15.11.2026): на ТВ и приставке (признак TopMode.isTv) слева список групп, справа выбранная; на телефоне (портрет и ландшафт) и магнитоле одна колонка
    // со сворачиваемыми группами (правило 17: правила приставок не действуют на ландшафт телефонов и магнитолы, поэтому не по ширине экрана)
    // правило 17а (слово владелицы 15.11.2026): ландшафт (телефон, магнитола) и ТВ: две половины; портрет: одна колонка
    val tvMode = TopMode.isTv(ctx)
    val wide = tvMode || androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var grp by remember { mutableStateOf(SettingsGroups.last.takeIf { it in SettingsGroups.ids } ?: "") }
    // широкая раскладка (ландшафт, ТВ): слева всегда выбрана какая-то группа, при «все свёрнуты» это первая; блоки внутри групп свёрнуты
    val g = if (wide && grp.isEmpty()) "updates" else grp
    var query by remember { mutableStateOf("") }
    val groupReq = SettingsGroups.reqTick
    LaunchedEffect(groupReq) {
        if (groupReq != SettingsGroups.handledTick) {
            SettingsGroups.handledTick = groupReq
            SettingsGroups.last.takeIf { it in SettingsGroups.ids }?.let { grp = it; query = "" }
            SettingsGroups.openSub?.let { sub = it }; SettingsGroups.openSub = null
        }
    }
    var homeBgDlg by remember { mutableStateOf(false) }
    var dockKeyDlg by remember { mutableStateOf(false) }
    var cachePerm by remember { mutableStateOf(false) }   // 19.2: окно «нужны права» для автоочистки кэша
    val openBlk = SettingsGroups.openBlocks
    // аппаратная «Назад» на вложенном экране («О программе», списки) возвращает на уровень выше, а не закрывает настройки
    androidx.activity.compose.BackHandler(enabled = sub != "main") { sub = "main" }
    var searchDlg by remember { mutableStateOf(false) }
    var adbDialog by remember { mutableStateOf(false) }
    var adbNet by remember { mutableStateOf(false) }
    var appsUpd by remember { mutableStateOf(false) }
    var instLog by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val scroll = androidx.compose.foundation.rememberScrollState(if (sub == "main") MenuMemory.settingsScroll else 0)
    val chipReg = remember { SettingsChipReg() }
    val firstRun = remember { booleanArrayOf(true) }
    LaunchedEffect(sub) {
        val initial = firstRun[0]
        firstRun[0] = false
        chipReg.mainActive = sub == "main"
        MenuMemory.setSettingsSub(sub)
        if (!initial) runCatching { if (sub == "main") scroll.scrollTo(MenuMemory.settingsScroll) else scroll.scrollTo(0) }
        // кнопки появились после первой раскладки; на ту же кнопку, что была в фокусе, иначе на «Назад»
        kotlinx.coroutines.delay(250)
        val cand = chipReg.items.map { MenuMemoryLogic.Cand(it.text, it.contentY()) }
        val idx = if (sub == "main") MenuMemoryLogic.pickChip(cand, MenuMemory.settingsFocusText, MenuMemory.settingsFocusY) else null
        if (idx != null) runCatching { chipReg.items[idx].req.requestFocus() } else runCatching { first.requestFocus() }
    }
    val rvl = remember { RevealGate() }
    LaunchedEffect(g, query.isNotBlank(), sub, openBlk.size) {      // 26.11.2026: и при раскрытии раздела, иначе его строки (больше, чем уже показано) так и не рисовались: «Порядок и скрытые» не открывался
        if (query.isNotBlank() || sub != "main") return@LaunchedEffect
        // следующие порции добавляются по кадру, пока не покажется всё содержимое группы
        while (rvl.limit < rvl.total) {
            runCatching { androidx.compose.runtime.withFrameNanos { }; androidx.compose.runtime.withFrameNanos { } }
            rvl.limit += RevealGate.CHUNK
            rvl.tick++
        }
    }
    val grpFirst = remember { booleanArrayOf(true) }
    val groupHeadTops = remember { HashMap<String, Float>() }
    val scrollAnchor = remember { floatArrayOf(0f) }
    LaunchedEffect(grp) {
        SettingsGroups.last = grp
        // 18.11.2026 (владелица): в узкой раскладке (без сайдбара) раскрытая группа подъезжает к верхнему краю окна, а не сбрасывается
        // «в начало» (её GroupHead сам сообщает свою Y-позицию через groupHeadTops, см. LocalGroupHeadTops); в широкой раскладке
        // (сайдбар + содержимое) GroupHead не рисуется вообще, там прежнее поведение - содержимое пане просто начинается сверху.
        if (grpFirst[0]) grpFirst[0] = false
        else if (!wide) {
            // открытие группы ОДНОВРЕМЕННО сворачивает предыдущую открытую - всё выше нужной группы сдвигается, и Y, записанный
            // GroupHead ДО этого сдвига, устаревает; та же пара кадров, что и в HomeRows.kt, даёт разметке осесть перед чтением
            runCatching { androidx.compose.runtime.withFrameNanos {}; androidx.compose.runtime.withFrameNanos {} }
            val top = groupHeadTops[grp]
            // то же самое: scrollAnchor уже содержит текущую прокрутку (позиция начала содержимого, см. BlockHead), прибавлять scroll.value второй раз не нужно
            if (top != null) runCatching { kotlinx.coroutines.withContext(DeskFullMotion) { scroll.animateScrollTo((top - scrollAnchor[0]).toInt().coerceAtLeast(0)) } }
        } else runCatching { scroll.scrollTo(0) }
    }
    LaunchedEffect(scroll) {
        androidx.compose.runtime.snapshotFlow { scroll.value }.collect { v -> if (sub == "main") MenuMemory.setSettingsScroll(v) }
    }
    var installFiles by remember { mutableStateOf<List<ApkFile>?>(null) }
    var browser by remember { mutableStateOf(false) }
    var net by remember { mutableStateOf(false) }
    var wifi by remember { mutableStateOf(false) }
    var backups by remember { mutableStateOf(false) }
    var menuBgDlg by remember { mutableStateOf(false) }
    var bkpPick by remember { mutableStateOf<String?>(null) }
    var iconPacksDlg by remember { mutableStateOf(false) }
    var bkRemote by remember { mutableStateOf(false) }
    var qaDlg by remember { mutableStateOf(false) }
    val anyDialog = iconPacksDlg || bkRemote || qaDlg || dockKeyDlg || homeBgDlg || searchDlg || adbDialog || adbNet || appsUpd || instLog || installFiles != null || browser || net || wifi || backups || menuBgDlg || bkpPick != null
    val dialogWasOpen = remember { booleanArrayOf(false) }
    LaunchedEffect(anyDialog) {
        if (!anyDialog && dialogWasOpen[0]) {
            kotlinx.coroutines.delay(150)
            val cand = chipReg.items.map { MenuMemoryLogic.Cand(it.text, it.contentY()) }
            val idx = if (sub == "main") MenuMemoryLogic.pickChip(cand, MenuMemory.settingsFocusText, MenuMemory.settingsFocusY) else null
            if (idx != null) runCatching { chipReg.items[idx].req.requestFocus() }
        }
        dialogWasOpen[0] = anyDialog
    }
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            val ok = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { Backup.write(ctx, it) } != null }.getOrDefault(false)
            Toast.makeText(ctx, ctx.getString(if (ok) R.string.backup_saved else R.string.failed), Toast.LENGTH_LONG).show()
        }
    }
    val loadBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val ok = runCatching { ctx.contentResolver.openInputStream(uri)?.use { Backup.restore(ctx, it) } == true }.getOrElse { Backup.lastFail = "open"; false }
            val why = when (Backup.lastFail) { "notcopy" -> R.string.backup_err_notcopy; "broken" -> R.string.backup_err_broken; "open" -> R.string.backup_err_open; else -> R.string.backup_err_other }
            Toast.makeText(ctx, if (ok) ctx.getString(R.string.backup_restored_undo) else ctx.getString(R.string.failed) + ": " + ctx.getString(why), Toast.LENGTH_LONG).show()
        }
    }
    val pickApkTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) installFiles = Batch.listApks(ctx, uri)
    }
    val pickApkZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) installFiles = Batch.listArchive(ctx, uri)
    }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val n = IconPacks.importTree(ctx, uri, apps)
            Toast.makeText(ctx, ctx.getString(R.string.import_done, n), Toast.LENGTH_LONG).show()
            Cfg.bumpIcons()
        }
    }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val n = IconPacks.importZip(ctx, uri, apps)
            Toast.makeText(ctx, ctx.getString(R.string.import_done, n), Toast.LENGTH_LONG).show()
            Cfg.bumpIcons()
        }
    }

    // на узком окне (телефон, крупный интерфейс) боковые поля меньше, а «Назад» - компактная стрелка; заголовок занимает остаток и переносится по словам
    val sideDp = if (screenDp().first < 560f) 16.dp else 32.dp
    Column(Modifier.fillMaxSize().background(t.bg).padding(horizontal = sideDp, vertical = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (isNarrow()) 12.dp else 16.dp)) {
            val backText = stringResource(R.string.back_plain)
            val goBack = { if (sub == "main") onBack() else sub = "main" }
            if (isNarrow()) Chip(backText, false, Modifier.focusRequester(first), icon = Icons.Outlined.ArrowBack) { goBack() }
            else Chip("← " + backText, false, Modifier.focusRequester(first)) { goBack() }
            Text(
                stringResource(when (sub) { "main" -> R.string.settings; "about" -> R.string.about_title; "autoapps" -> R.string.auto_pick; "cacheapps" -> R.string.cache_pick; "dockapps" -> R.string.dock_pick; "dockhide" -> R.string.dock_hide_title; "excl" -> R.string.mem_excl_title; else -> R.string.hidden_list }),
                fontSize = btnSp(24), color = t.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
        // ТВ, ландшафт телефона и магнитолы: слева узкий список групп в одну строку, справа содержимое; портрет: одна колонка
        SettingsFrame({ if (wide && sub == "main") GroupsPane(g, onTap = { query = "" }) { grp = it } }) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalChipReg provides chipReg, LocalSettingsScroll provides scroll, LocalSettingsQuery provides query,
            LocalSettingsScrollAnchor provides scrollAnchor, LocalGroupHeadTops provides groupHeadTops, LocalBlockNest provides (!wide && query.isBlank()),
        ) {
        Column(
            Modifier.fillMaxSize().verticalScrollbar(scroll).verticalScroll(scroll)
                .onGloballyPositioned { scrollAnchor[0] = it.positionInRoot().y }
                .padding(top = 8.dp, end = ScrollbarMath.RESERVE_DP.dp, start = if (wide && sub == "main") 20.dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (sub == "main") Column(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
                // 16.0 (плавность): содержимое группы собирается порциями (по несколько элементов за кадр), а не целиком за один кадр: на приставке
                // сборка большой группы занимала 100-200 мс одним кадром. При поиске и вне главной страницы всё сразу.
                rvl.begin(g, query.isNotBlank())
                if (rvl.next(1)) { ActionRow(HIcons.search, stringResource(R.string.set_search), if (query.isBlank()) stringResource(R.string.set_search_hint) else "«" + query + "»") { searchDlg = true } }
                if (rvl.next(1)) { GroupHead("updates", wide || query.isNotBlank(), g) { grp = it } }
                if (g == "updates" || query.isNotBlank()) {
                    // главное по обновлениям и копиям: четыре крупные кнопки по центру, видны сразу без прокрутки
                    if (query.isBlank()) if (rvl.next(5)) { Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionRow(HIcons.refresh, stringResource(R.string.about_check_upd), stringResource(R.string.about_version, BuildConfig.VERSION_NAME)) { SelfUpd.checkNow(ctx.applicationContext, scope) }
                        ActionRow(HIcons.save, stringResource(R.string.backup_hero_save), stringResource(R.string.backup_save_h)) {
                            runCatching { saveBackup.launch(Backup.fileName(ctx)) }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.icon_no_picker), Toast.LENGTH_LONG).show() }
                        }
                        ActionRow(HIcons.recent, stringResource(R.string.backup_hero_load), stringResource(R.string.backup_load_h)) { bkpPick = "file" }
                        // 15.0: копии в сеть (SMB, WebDAV, Яндекс Диск, папка, USB) и синхронизация рядом с обычной копией
                        ActionRow(HIcons.wifi, stringResource(R.string.bkr_title), BkTargets.summary(ctx)) { bkRemote = true }
                        ActionRow(HIcons.open, stringResource(R.string.about_title), stringResource(R.string.about_hero_h)) { sub = "about" }
                    } }
                    if (rvl.next(1)) { SwitchRow(stringResource(R.string.self_upd_auto), Cfg.selfUpd, listOf()) { Cfg.changeSelfUpd(it) } }
                    // 9.0 (просьба Евгения): на виду, один переключатель на удаление и обновление; путь папки копий в пояснении
                    if (rvl.next(1)) { SwitchRow(stringResource(R.string.bk_del_switch), Cfg.backupOnDelete, listOf(stringResource(R.string.bk_del_hint, remember { AppBackups.root(ctx).absolutePath }))) { Cfg.changeBackupOnDelete(it) } }
                    if (rvl.next(1)) { CleanCopiesRow() }
                    if (rvl.next(1)) { SwitchRow(stringResource(R.string.inst_ask_setting), Cfg.askInstaller, listOf(stringResource(R.string.inst_ask_hint), stringResource(R.string.inst_default_now, InstallerPick.labelOf(ctx, Cfg.instSaved)))) { Cfg.changeAskInstaller(it) } }
                    if (rvl.next(3)) { Section(stringResource(R.string.backup_title)) {
                        SectionHint(stringResource(R.string.backup_hint))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionRow(HIcons.folder, stringResource(R.string.backup_here), stringResource(R.string.backup_here_h)) {
                                Backup.autoSave(ctx)
                                Toast.makeText(ctx, ctx.getString(R.string.backup_here_done, Backup.autoFile(ctx)?.absolutePath.orEmpty()), Toast.LENGTH_LONG).show()
                            }
                            ActionRow(HIcons.recent, stringResource(R.string.backup_restore_here), stringResource(R.string.backup_restore_here_h)) { bkpPick = "app" }
                        }
                    } }
                    // при обычном просмотре кнопка стоит среди главных (выше); раздел нужен для результатов поиска по настройкам
                    if (query.isNotBlank()) if (rvl.next(2)) { Section(stringResource(R.string.bkr_title)) {
                        SectionHint(stringResource(R.string.bkr_hint))
                        ActionRow(HIcons.wifi, stringResource(R.string.bkr_open), BkTargets.summary(ctx)) { bkRemote = true }
                    } }
                    if (rvl.next(3)) { Section(stringResource(R.string.set_backups)) {
                        Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.backupOnDelete) { Cfg.changeBackupOnDelete(it) }
                        SectionHint(stringResource(R.string.backups_hint))
                        ActionRow(HIcons.save, stringResource(R.string.backups_open), stringResource(R.string.backups_open_h)) { backups = true }
                    } }
                }
                if (rvl.next(1)) { GroupHead("ui", wide || query.isNotBlank(), g) { grp = it } }
                if (g == "ui" || query.isNotBlank()) {
                    if (rvl.next(1)) { BlockHead("ui_a", stringResource(R.string.blk_ui_a), openBlk, query.isNotBlank()) }
                    if ("ui_a" in openBlk || query.isNotBlank()) {
                    if (rvl.next(2)) { Section(stringResource(R.string.set_lang)) {
                        Options(
                            listOf("" to stringResource(R.string.lang_system), "ru" to "Русский", "uk" to "Українська", "en" to "English"),
                            Cfg.lang,
                        ) {
                            Cfg.changeLang(it)
                            // язык применяется при создании окна: пересоздаём его
                            (ctx as? android.app.Activity)?.recreate()
                        }
                    } }
                    if (rvl.next(2)) { Section(stringResource(R.string.ui_size_title)) {
                        SectionHint(stringResource(R.string.ui_size_hint))
                        UiSizeSetting()
                    } }
                    if (rvl.next(2)) { Section(stringResource(R.string.text_size_title)) {
                        SectionHint(stringResource(R.string.text_size_hint))
                        TextSizeSetting()
                    } }
                    if (rvl.next(1)) { SwitchRow(stringResource(R.string.click_sound_title), Cfg.clickSound, listOf(stringResource(R.string.click_sound_hint))) { Cfg.changeClickSound(it) } }
                    if (rvl.next(1)) { SwitchRow(stringResource(R.string.set_desk_anim), Cfg.deskAnim, listOf(stringResource(R.string.set_desk_anim_hint))) { Cfg.changeDeskAnim(it) } }
                    if (Cfg.deskAnim) if (rvl.next(2)) { Section(stringResource(R.string.set_desk_anim_style)) {
                        Options(
                            listOf(
                                "slide" to stringResource(R.string.desk_anim_slide), "zoom" to stringResource(R.string.desk_anim_zoom),
                                "cube" to stringResource(R.string.desk_anim_cube), "parallax" to stringResource(R.string.desk_anim_parallax),
                                "fade" to stringResource(R.string.desk_anim_fade),
                            ),
                            Cfg.deskAnimStyle,
                        ) { Cfg.changeDeskAnimStyle(it) }
                    } }
                    if (rvl.next(1)) { Section(stringResource(R.string.set_control)) {
                        SectionHint(stringResource(R.string.ctl_help))
                    } }
                    if (rvl.next(2)) { Section(stringResource(R.string.qa_title)) {
                        SectionHint(stringResource(R.string.qa_hint))
                        ActionRow(HIcons.gear, stringResource(R.string.qa_open), QuickActions.summary(ctx)) { qaDlg = true }
                    } }
                    if (rvl.next(1)) { SwitchRow(stringResource(R.string.back_start_title), Cfg.backToStart, listOf(stringResource(R.string.back_start_hint))) { Cfg.changeBackToStart(it) } }
                    }
                    if (rvl.next(1)) { BlockHead("st_a", stringResource(R.string.blk_st_a), openBlk, query.isNotBlank()) }
                    if ("st_a" in openBlk || query.isNotBlank()) {
                    if (rvl.next(2)) { Section(stringResource(R.string.set_theme)) {
                        Options(
                            listOf(
                                "dark" to stringResource(R.string.theme_dark), "black" to stringResource(R.string.theme_black),
                                "light" to stringResource(R.string.theme_light), "auto" to stringResource(R.string.theme_auto),
                            ),
                            Cfg.theme,
                        ) { Cfg.changeTheme(it) }
                    } }
                    if (rvl.next(2)) { Section(stringResource(R.string.set_accent)) {
                        val names = mapOf(
                            "teal" to R.string.accent_teal, "blue" to R.string.accent_blue, "orange" to R.string.accent_orange,
                            "purple" to R.string.accent_purple, "pink" to R.string.accent_pink, "green" to R.string.accent_green,
                        )
                        // одинаковые прямоугольники с цветами акцента (вместо кнопок со словами); название выбранного под рядом
                        val dark = t.dark
                        ColorDots(accentKeys.map { accentSwatch(it, dark) }, accentKeys.map { stringResource(names.getValue(it)) }, accentKeys.indexOf(Cfg.accent).coerceAtLeast(0), rect = true) { Cfg.changeAccent(accentKeys[it]) }
                    } }
                    if (rvl.next(2)) { Section(stringResource(R.string.set_ring)) {
                        val ringNames = listOf(R.string.ring_default, R.string.bd_c1, R.string.bd_c2, R.string.bd_c3, R.string.bd_c4, R.string.bd_c5, R.string.bd_c6, R.string.bd_c7, R.string.bd_c8, R.string.bd_c9, R.string.bd_c10)
                        val ringAccent = LocalTh.current.accent
                        ColorDots(Cfg.RING_COLORS.indices.map { if (it == 0) ringAccent else Color(Cfg.RING_COLORS[it].toInt()) }, ringNames.map { stringResource(it) }, Cfg.selRing, lightMark = setOf(1, 5)) { Cfg.changeSelRing(it) }
                    } }
                    }
                }
                if (rvl.next(1)) { GroupHead("menu", wide || query.isNotBlank(), g) { grp = it } }
                if (g == "menu" || query.isNotBlank()) {
                    if (rvl.next(1)) { BlockHead("ui_b", stringResource(R.string.blk_ui_b), openBlk, query.isNotBlank()) }
                    if ("ui_b" in openBlk || query.isNotBlank()) {
                    if (rvl.next(2)) { Section(stringResource(R.string.set_view)) {
                        Options(listOf(false to stringResource(R.string.view_grid), true to stringResource(R.string.view_list)), Cfg.listMode) { Cfg.changeListMode(it) }
                    } }
                    if (rvl.next(3)) { Section(stringResource(R.string.set_grid)) {
                        // три счётчика: строки × столбцы и масштаб значков
                        // 20.11.2026: строки × столбцы в одной строке с гибкой шириной, масштаб переносится на следующую строку, если места мало
                        FlowRow(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!Cfg.listMode) Row(Modifier.widthIn(max = 316.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GridNum(
                                    Cfg.gridRows, stringResource(R.string.grid_rows), { if (it == 0) stringResource(R.string.cols_auto) else it.toString() },
                                    onPlus = { Cfg.changeGridRows(Steps.autoStep(Cfg.gridRows, +1, 12, Cfg.autoRows)) },
                                    onMinus = { Cfg.changeGridRows(Steps.autoStep(Cfg.gridRows, -1, 12, Cfg.autoRows)) },
                                    onReset = { Cfg.changeGridRows(0) }, range = 0..12, onSet = { Cfg.changeGridRows(it) },
                                    modifier = Modifier.weight(1f).widthIn(min = 88.dp, max = 132.dp),
                                )
                                // «×» по центру окошка с числом: сверху пустое место высотой кнопки «+», затем окошко той же высоты
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Spacer(Modifier.height(GRID_BTN_H))
                                    Box(Modifier.height(GRID_NUM_H), contentAlignment = Alignment.Center) { Text("×", color = t.text, fontSize = btnSp(34), fontWeight = FontWeight.Bold) }
                                }
                                GridNum(
                                    Cfg.columns, stringResource(R.string.grid_cols), { if (it == 0) stringResource(R.string.cols_auto) else it.toString() },
                                    onPlus = { Cfg.changeColumns(Steps.autoStep(Cfg.columns, +1, 16, Cfg.autoCols)) },
                                    onMinus = { Cfg.changeColumns(Steps.autoStep(Cfg.columns, -1, 16, Cfg.autoCols)) },
                                    onReset = { Cfg.changeColumns(0) }, range = 0..16, onSet = { Cfg.changeColumns(it) },
                                    modifier = Modifier.weight(1f).widthIn(min = 88.dp, max = 132.dp),
                                )
                            }
                        }
                    } }
                    if (rvl.next(2)) { Section(stringResource(R.string.set_icon)) {
                        Options(listOf(3 to stringResource(R.string.size_xs), 0 to stringResource(R.string.size_s), 1 to stringResource(R.string.size_m), 2 to stringResource(R.string.size_l)), Cfg.iconSize) { Cfg.changeIconSize(it) }
                    } }
                    if (rvl.next(2)) { Section(stringResource(R.string.set_gap)) {
                        Options(listOf(0 to stringResource(R.string.gap_tight), 1 to stringResource(R.string.gap_normal), 2 to stringResource(R.string.gap_airy)), Cfg.gridGap) { Cfg.changeGridGap(it) }
                    } }
                    if (rvl.next(1)) { SwitchRow(stringResource(R.string.set_labels), Cfg.showLabels, listOf()) { Cfg.changeShowLabels(it) } }
                    }
                    if (rvl.next(1)) { BlockHead("tl_b", stringResource(R.string.blk_tl_b), openBlk, query.isNotBlank()) }
                    if ("tl_b" in openBlk || query.isNotBlank()) {
                    if (rvl.next(7)) { TilesBlock() }
                    }
                    if (rvl.next(1)) { BlockHead("st_b", stringResource(R.string.blk_st_b), openBlk, query.isNotBlank()) }
                    if ("st_b" in openBlk || query.isNotBlank()) {
                    if (rvl.next(2)) { Section(stringResource(R.string.set_tilebg)) {
                        Options(listOf(true to stringResource(R.string.tilebg_clear), false to stringResource(R.string.tilebg_card)), Cfg.transparentTiles) { Cfg.changeTransparentTiles(it) }
                    } }
                    if (rvl.next(1)) { SwitchRow(stringResource(R.string.set_plates), Cfg.plates, listOf()) { Cfg.changePlates(it) } }
                    if (rvl.next(6)) { Section(stringResource(R.string.set_iconpack)) {
                        val packs = remember { IconPacks.installed(ctx) }
                        ActionRow(HIcons.apps, stringResource(R.string.ip_open), (packs.firstOrNull { it.first == Cfg.iconPack }?.second ?: stringResource(R.string.iconpack_native)) + " · " + stringResource(R.string.ip_open_h)) { iconPacksDlg = true }
                        if (packs.isEmpty()) Text(stringResource(R.string.iconpack_none), color = t.hint, fontSize = 12.sp)
                        SectionHint(stringResource(R.string.import_hint))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionRow(HIcons.folder, stringResource(R.string.import_tree), stringResource(R.string.import_tree_h)) {
                                runCatching { pickTree.launch(null) }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.icon_no_picker), Toast.LENGTH_LONG).show() }
                            }
                            ActionRow(HIcons.folder, stringResource(R.string.import_zip), stringResource(R.string.import_zip_h)) {
                                runCatching { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
                                    .onFailure { Toast.makeText(ctx, ctx.getString(R.string.icon_no_picker), Toast.LENGTH_LONG).show() }
                            }
                        }
                        ActionRow(HIcons.trash, stringResource(R.string.icons_reset_all), stringResource(R.string.icons_reset_all_h)) { IconPacks.removeAllOwn(ctx); Cfg.bumpIcons() }
                        ActionRow(HIcons.refresh, stringResource(R.string.icons_cache_clear), stringResource(R.string.icons_cache_clear_h)) {
                            AppCache.clearIcons(ctx); Cfg.bumpIcons()
                            Toast.makeText(ctx, ctx.getString(R.string.icons_cache_cleared), Toast.LENGTH_SHORT).show()
                        }
                    } }
                    }
                    if (rvl.next(1)) { BlockHead("ui_c", stringResource(R.string.blk_ui_c), openBlk, query.isNotBlank()) }
                    if ("ui_c" in openBlk || query.isNotBlank()) {
                    if (rvl.next(1)) { SwitchRow(stringResource(R.string.set_remember), Cfg.rememberPos, listOf(stringResource(R.string.remember_hint))) { Cfg.changeRememberPos(it) } }
                    if (rvl.next(2)) { Section(stringResource(R.string.set_dense)) {
                        Options(
                            listOf(0 to stringResource(R.string.lite_auto), 1 to stringResource(R.string.dense_on), 2 to stringResource(R.string.dense_off)), DenseGrid.mode,
                        ) { DenseGrid.change(ctx, it) }
                        SectionHint(stringResource(R.string.dense_hint))
                    } }
                    if (rvl.next(2)) { Section(stringResource(R.string.set_sort)) {
                        Options(
                            listOf(
                                "name" to stringResource(R.string.sort_name), "name_desc" to stringResource(R.string.sort_name_desc),
                                "new" to stringResource(R.string.sort_new), "old" to stringResource(R.string.sort_old),
                                "freq" to stringResource(R.string.sort_freq), "custom" to stringResource(R.string.sort_custom),
                            ),
                            Cfg.sort,
                        ) { Cfg.changeSort(it) }
                    } }
                    if (rvl.next(1)) { SwitchRow(stringResource(R.string.desks_title), Desks.on, listOf()) { Desks.enable(it) } }
                    if (rvl.next(3)) { Section(stringResource(R.string.set_hidden)) {
                        Options(listOf(false to stringResource(R.string.hidden_hide), true to stringResource(R.string.hidden_show)), Cfg.showHidden) { Cfg.changeShowHidden(it) }
                        ActionRow(HIcons.eyeOff, stringResource(R.string.hidden_list) + " (" + Cfg.hidden.size + ")", stringResource(R.string.hidden_list_h)) { sub = "hidden" }
                    } }
                    }
                    if (rvl.next(1)) { BlockHead("mn_d", stringResource(R.string.blk_mn_d), openBlk, query.isNotBlank()) }
                    if ("mn_d" in openBlk || query.isNotBlank()) {
                    // выключатель меню виден всегда (раньше исчезал при выключенном «Доме»); оба модуля сразу выключить нельзя
                    if (rvl.next(2)) { Section(stringResource(R.string.menu_module_title)) {
                        SectionHint(stringResource(R.string.menu_module_hint))
                        Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.menuModule) { v ->
                            if (!v && !Cfg.homeModule) Toast.makeText(ctx, ctx.getString(R.string.menu_module_need_home), Toast.LENGTH_LONG).show()
                            else Cfg.changeMenuModule(v)
                        }
                    } }
                    }
                }
                if (rvl.next(1)) { GroupHead("home", wide || query.isNotBlank(), g) { grp = it } }
                if (g == "home" || query.isNotBlank()) {
                    if (rvl.next(1)) { BlockHead("hm_a", stringResource(R.string.blk_hm_a), openBlk, query.isNotBlank()) }
                    if ("hm_a" in openBlk || query.isNotBlank()) {
                    if (rvl.next(1)) { SwitchRow(stringResource(R.string.home_auto_add_title), Cfg.homeAutoAdd, listOf(stringResource(R.string.home_auto_add_hint))) { Cfg.changeHomeAutoAdd(it) } }
                    // оформление верхней строки и выделения на «Доме» (по умолчанию вид прежний; выбор применяется сразу; пояснения свёрнуты под названием)
                    if (rvl.next(2)) { Section(stringResource(R.string.top_outline)) {
                        SectionHint(stringResource(R.string.top_outline_hint))
                        Options(listOf("auto" to stringResource(R.string.top_o_auto), "dark" to stringResource(R.string.top_o_dark), "white" to stringResource(R.string.top_o_white), "none" to stringResource(R.string.top_o_none)), Cfg.statusOutline) { Cfg.changeStatusOutline(it) }
                    } }
                    if (rvl.next(2)) { Section(stringResource(R.string.top_icol)) {
                        SectionHint(stringResource(R.string.top_icol_hint))
                        IconColorRow()
                    } }
                    if (rvl.next(3)) { Section(stringResource(R.string.top_fill)) {
                        SectionHint(stringResource(R.string.top_fill_hint))
                    BarFillPreview()
                        Options(listOf("solid" to stringResource(R.string.top_f_solid), "glass" to stringResource(R.string.top_f_glass), "none" to stringResource(R.string.top_f_none)), Cfg.barFill) { Cfg.changeBarFill(it) }
                    } }
                    if (rvl.next(2)) { Section(stringResource(R.string.top_shape)) {
                        SectionHint(stringResource(R.string.top_shape_hint))
                        Options(listOf("normal" to stringResource(R.string.top_s_normal), "round" to stringResource(R.string.top_s_round), "square" to stringResource(R.string.top_s_square)), Cfg.barShape) { Cfg.changeBarShape(it) }
                    } }
                    if (rvl.next(3)) { Section(stringResource(R.string.sel_glow)) {
                        SectionHint(stringResource(R.string.sel_glow_hint))
                        GlowPreview()
                        Options(listOf("none" to stringResource(R.string.glow_none), "weak" to stringResource(R.string.glow_weak), "strong" to stringResource(R.string.glow_strong)), Cfg.selGlow) { Cfg.changeSelGlow(it) }
                    } }
                    }
                    if (rvl.next(1)) { BlockHead("hm_b", stringResource(R.string.blk_hm_b), openBlk, query.isNotBlank()) }
                    if ("hm_b" in openBlk || query.isNotBlank()) {
                    if (rvl.next(1)) { SwitchRow(stringResource(R.string.all_panel), Cfg.homeAllPanel, listOf(stringResource(R.string.all_panel_hint))) { Cfg.changeHomeAllPanel(it) } }
                    if (Cfg.homeAllPanel) if (rvl.next(2)) { Section(stringResource(R.string.all_panel_glass)) {
                        SectionHint(stringResource(R.string.all_panel_glass_hint))
                        Options(listOf("light" to stringResource(R.string.glass_light), "mid" to stringResource(R.string.glass_mid), "dense" to stringResource(R.string.glass_dense)), Cfg.homeAllGlass) { Cfg.changeHomeAllGlass(it) }
                    } }
                    }
                    if (rvl.next(1)) { BlockHead("hm_c", stringResource(R.string.blk_sy_a), openBlk, query.isNotBlank()) }
                    if ("hm_c" in openBlk || query.isNotBlank()) {
                    if (rvl.next(2)) { Section(stringResource(R.string.home_module_title)) {
                        SectionHint(stringResource(R.string.home_module_hint))
                        Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.homeModule) { v ->
                            if (v) HomeModule.enable(ctx)
                            else scope.launch {
                                val r = HomeModule.disable(ctx)
                                Toast.makeText(ctx, ctx.getString(if (r.isEmpty()) R.string.home_module_off_ok else if (r == HomeModule.NEED_MENU) R.string.home_module_need_menu else R.string.home_module_off_fail), Toast.LENGTH_LONG).show()
                            }
                        }
                    } }
                    if (rvl.next(6)) { Section(stringResource(R.string.set_home)) {
                        SectionHint(stringResource(R.string.set_home_hint))
                        SectionHint(stringResource(R.string.native_home_hint))
                        // список родных экранов с названиями собирается в фоне: loadLabel обращается к ресурсам и Binder, в главном потоке это до 150 мс (журнал магнитолы)
                        val natives by androidx.compose.runtime.produceState(emptyList<Pair<String, String>>()) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { nativeHomes(ctx).map { it.activityInfo.packageName to it.loadLabel(ctx.packageManager).toString() } }
                        }
                        if (natives.isNotEmpty()) Options(listOf("" to stringResource(R.string.native_auto)) + natives, Cfg.nativeHome) { Cfg.changeNativeHome(it) }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionRow(HIcons.home, stringResource(R.string.set_home_btn), stringResource(R.string.set_home_btn_h)) {
                                scope.launch {
                                    // запоминаем нынешний главный экран, чтобы второе «Домой» и «Выключить» вели на него
                                    if (Cfg.nativeHome.isEmpty()) {
                                        val cur = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
                                        if (cur != null && cur != ctx.packageName && cur != "android") Cfg.changeNativeHome(cur)
                                    }
                                    when (HomeControl.enableAsHome(ctx)) {
                                        "ok" -> Toast.makeText(ctx, ctx.getString(R.string.home_set_ok), Toast.LENGTH_LONG).show()
                                        "no_control" -> { Toast.makeText(ctx, ctx.getString(R.string.home_choose_always), Toast.LENGTH_LONG).show(); HomeControl.openHomePicker(ctx) }
                                        else -> { Toast.makeText(ctx, ctx.getString(R.string.home_cmd_failed), Toast.LENGTH_LONG).show(); HomeControl.openHomePicker(ctx) }
                                    }
                                }
                            }
                            ActionRow(HIcons.gear, stringResource(R.string.home_settings_btn), stringResource(R.string.home_settings_btn_h)) {
                                val r = HomeControl.openHomeSettings(ctx)
                                if (r == "none") Toast.makeText(ctx, ctx.getString(R.string.home_settings_none), Toast.LENGTH_LONG).show()
                            }
                            ActionRow(HIcons.eyeOff, stringResource(R.string.set_home_off), stringResource(R.string.set_home_off_h)) {
                                scope.launch {
                                    // возвращаем прежний главный экран и только потом выключаем режим
                                    val ri = nativeHomes(ctx).firstOrNull { it.activityInfo.packageName == Cfg.nativeHome } ?: nativeHomes(ctx).firstOrNull()
                                    if (Adb.isConnected && ri != null) {
                                        runCatching { HomeControl.setHomeCmd("${ri.activityInfo.packageName}/${ri.activityInfo.name}") }
                                    }
                                    setHomeMode(ctx, false)
                                    Toast.makeText(ctx, ctx.getString(R.string.set_home_off_done), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        HomeHardBlock()
                    } }
                    if (rvl.next(3)) { Section(stringResource(R.string.home_keep_title)) {
                        Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.rememberHome) { Cfg.changeRememberHome(it) }
                        SectionHint(stringResource(R.string.home_remember_hint))
                        ActionRow(HIcons.home, stringResource(R.string.home_now), stringResource(R.string.home_now_h)) {
                            scope.launch {
                                val ok = HomeControl.makeDefaultNow(ctx)
                                Toast.makeText(ctx, ctx.getString(if (ok) R.string.home_now_ok else R.string.home_now_fail), Toast.LENGTH_LONG).show()
                            }
                        }
                    } }
                    if (rvl.next(2)) { Section(stringResource(R.string.set_return)) {
                        Options(listOf("menu" to stringResource(R.string.return_menu), "home" to stringResource(R.string.return_home)), Cfg.returnTo) { Cfg.changeReturnTo(it) }
                    } }
                    }
                }
                if (rvl.next(1)) { GroupHead("dock", wide || query.isNotBlank(), g) { grp = it } }
                if (g == "dock" || query.isNotBlank()) {
                    @Suppress("UNUSED_VARIABLE") val dockRev = HomeDock.rev.intValue
                    if (rvl.next(1)) { SwitchRow(stringResource(R.string.dock_title), HomeDock.on(ctx), listOf(stringResource(R.string.dock_hint))) { HomeDock.setOn(ctx, it) } }
                    // блок «Приложения»: что в доке, добавить, скрыть
                    if (rvl.next(5)) { Section(stringResource(R.string.dock_apps_title)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionRow(HIcons.list, stringResource(R.string.dock_pick) + " (" + HomeDock.list(ctx).size + ")", stringResource(R.string.dock_pick_h)) { sub = "dockapps" }
                            ActionRow(HIcons.list, stringResource(R.string.dock_m_add), stringResource(R.string.dock_add_h)) { sub = "dockapps" }
                            ActionRow(HIcons.eyeOff, stringResource(R.string.dock_hide_pick) + " (" + HomeDock.hiddenList(ctx).size + ")", stringResource(R.string.dock_hide_h)) { sub = "dockhide" }
                        }
                        CounterRow(stringResource(R.string.dock_count), HomeDock.count(ctx), 6..12, "") { HomeDock.setCount(ctx, it) }
                    } }
                    // блок «Вид»: расположение, размеры, подписи, фон и прозрачность рядом
                    if (rvl.next(10)) { Section(stringResource(R.string.dock_look)) {
                        Text(stringResource(R.string.dock_style), color = t.sub, fontSize = hintSp(13), modifier = Modifier.padding(top = 2.dp))
                        Options(listOf(0 to stringResource(R.string.dock_st_icons), 1 to stringResource(R.string.dock_st_banners)), HomeDock.style(ctx)) { HomeDock.setStyle(ctx, it) }
                        if (HomeDock.banners(ctx)) {
                            CounterRow(stringResource(R.string.dock_bn_vis), HomeDock.bnVis(ctx), 5..8, "") { HomeDock.setBnVis(ctx, it) }
                            CounterRow(stringResource(R.string.dock_bn_size), HomeDock.bnSize(ctx), 50..100, "%") { HomeDock.setBnSize(ctx, it) }
                        }
                        Text(stringResource(R.string.dock_align), color = t.sub, fontSize = hintSp(13), modifier = Modifier.padding(top = 6.dp))
                        Options(listOf(0 to stringResource(R.string.dock_al_l), 1 to stringResource(R.string.dock_al_c), 2 to stringResource(R.string.dock_al_r)), HomeDock.align(ctx)) { HomeDock.setAlign(ctx, it) }
                        CounterRow(stringResource(R.string.dock_icon), HomeDock.icon(ctx), 24..72, "dp") { HomeDock.setIcon(ctx, it) }
                        SwitchRow(stringResource(R.string.dock_labels), HomeDock.labels(ctx), emptyList()) { HomeDock.setLabels(ctx, it) }
                        if (HomeDock.labels(ctx)) CounterRow(stringResource(R.string.dock_label_sp), HomeDock.labelSp(ctx), 8..16, "sp") { HomeDock.setLabelSp(ctx, it) }
                        Text(stringResource(R.string.dock_bg), color = t.sub, fontSize = hintSp(13), modifier = Modifier.padding(top = 6.dp))
                        Options(listOf(1 to stringResource(R.string.dock_bg_light), 0 to stringResource(R.string.dock_bg_dark), 2 to stringResource(R.string.dock_bg_auto), 3 to stringResource(R.string.dock_bg_glass), 4 to stringResource(R.string.dock_bg_frost), 5 to stringResource(R.string.dock_bg_none)), HomeDock.bgEff(ctx)) { HomeDock.setBg(ctx, it) }
                        CounterRow(stringResource(R.string.dock_transp), HomeDock.transp(ctx), 0..100, "%") { HomeDock.setTransp(ctx, it) }
                        Options(listOf(0 to "0 %", 25 to "25 %", 50 to "50 %", 75 to "75 %", 100 to "100 %"), HomeDock.transp(ctx)) { HomeDock.setTransp(ctx, it) }
                        CounterRow(stringResource(R.string.dock_bottom), HomeDock.bottom(ctx), 0..60, "dp") { HomeDock.setBottom(ctx, it) }
                        CounterRow(stringResource(R.string.dock_radius), HomeDock.radius(ctx), 0..30, "dp") { HomeDock.setRadius(ctx, it) }
                    } }
                    // блок «Поведение»: порядок, недавние, автодобавление, прокрутка, кнопка
                    if (rvl.next(8)) { Section(stringResource(R.string.dock_behave)) {
                        Text(stringResource(R.string.dock_sort), color = t.sub, fontSize = hintSp(13))
                        Options(listOf(0 to stringResource(R.string.dock_s0), 1 to stringResource(R.string.dock_s1), 2 to stringResource(R.string.dock_s2), 3 to stringResource(R.string.dock_s3)), HomeDock.sort(ctx)) { HomeDock.setSort(ctx, it) }
                        SwitchRow(stringResource(R.string.dock_recents), HomeDock.recents(ctx), listOf(stringResource(R.string.dock_recents_h))) { HomeDock.setRecents(ctx, it) }
                        if (HomeDock.recents(ctx)) CounterRow(stringResource(R.string.dock_recent_n), HomeDock.recentN(ctx), 1..8, "") { HomeDock.setRecentN(ctx, it) }
                        SwitchRow(stringResource(R.string.dock_auto), HomeDock.auto(ctx), listOf(stringResource(R.string.dock_auto_h))) { HomeDock.setAuto(ctx, it) }
                        SwitchRow(stringResource(R.string.dock_close_after), HomeDock.closeAfter(ctx), listOf(stringResource(R.string.dock_close_after_h))) { HomeDock.setCloseAfter(ctx, it) }
                        SwitchRow(stringResource(R.string.dock_scroll), HomeDock.scrollbar(ctx), listOf(stringResource(R.string.dock_scroll_h))) { HomeDock.setScrollbar(ctx, it) }
                        SwitchRow(stringResource(R.string.dock_down), HomeDock.downOpens(ctx), listOf(stringResource(R.string.dock_down_h))) { HomeDock.setDownOpens(ctx, it) }
                        SwitchRow(stringResource(R.string.dock_btn), HomeDock.btn(ctx), listOf(stringResource(R.string.dock_btn_h))) { HomeDock.setBtn(ctx, it) }
                    } }
                    if (rvl.next(3)) { Section(stringResource(R.string.dock_key)) {
                        Options(listOf(0 to stringResource(R.string.dock_key_0), 1 to stringResource(R.string.dock_key_1), 2 to stringResource(R.string.dock_key_2), 3 to stringResource(R.string.dock_key_3)), HomeDock.keyMode(ctx)) { HomeDock.setKeyMode(ctx, it) }
                        if (HomeDock.keyMode(ctx) == 3) ActionRow(HIcons.gear, stringResource(R.string.dock_key_press) + (if (HomeDock.keyCode(ctx) >= 0) ": " + Voice.keyName(HomeDock.keyCode(ctx)) else ""), stringResource(R.string.dock_key_press_h)) { dockKeyDlg = true }
                        Text(stringResource(R.string.dock_keys_help), color = t.sub, fontSize = hintSp(12), modifier = Modifier.padding(top = 4.dp))
                    } }
                }
                if (rvl.next(1)) { GroupHead("wall", wide || query.isNotBlank(), g) { grp = it } }
                if (g == "wall" || query.isNotBlank()) {
                    @Suppress("UNUSED_VARIABLE") val wallRev = HomeDock.rev.intValue
                    if (rvl.next(1)) { SwitchRow(stringResource(R.string.wall_res), WallRes.on(ctx), listOf(stringResource(R.string.wall_res_h))) { WallRes.setOn(ctx, it); HomeDock.rev.intValue++ } }
                    if (rvl.next(3)) { Section(stringResource(R.string.grp_wall)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionRow(HIcons.home, stringResource(R.string.wall_home), stringResource(R.string.wall_home_h)) { homeBgDlg = true }
                            ActionRow(HIcons.apps, stringResource(R.string.bg_menu_btn), stringResource(R.string.bg_menu_btn_h)) { menuBgDlg = true }
                        }
                    } }
                }
                if (rvl.next(1)) { GroupHead("widgets", wide || query.isNotBlank(), g) { grp = it } }
                if (g == "widgets" || query.isNotBlank()) {
                    if (rvl.next(2)) { Section(stringResource(R.string.grp_widgets)) {
                        ActionRow(HIcons.home, stringResource(R.string.wid_open), stringResource(R.string.wid_open_h)) { leaveVia(ctx, -1) { ctx.startActivity(Intent(ctx, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                    } }
                }
                if (rvl.next(1)) { GroupHead("dl", wide || query.isNotBlank(), g) { grp = it } }
                if (g == "dl" || query.isNotBlank()) {
                    if (rvl.next(3)) { Section(stringResource(R.string.grp_dl)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionRow(HIcons.download, stringResource(R.string.about_apps_upd), stringResource(R.string.about_apps_upd_h)) { appsUpd = true }
                            ActionRow(HIcons.download, stringResource(R.string.dl_wall), stringResource(R.string.dl_wall_h)) { homeBgDlg = true }
                        }
                    } }
                }
                if (rvl.next(1)) { GroupHead("auto", wide || query.isNotBlank(), g) { grp = it } }
                if (g == "auto" || query.isNotBlank()) {
                    @Suppress("UNUSED_VARIABLE") val autoRev = Autostart.rev.intValue
                    if (rvl.next(1)) { SwitchRow(stringResource(R.string.auto_title), Autostart.on(ctx), listOf(stringResource(R.string.auto_limits))) { Autostart.setOn(ctx, it) } }
                    if (rvl.next(2)) { Section(stringResource(R.string.auto_apps_title)) {
                        ActionRow(HIcons.list, stringResource(R.string.auto_pick) + " (" + Autostart.list(ctx).size + ")", stringResource(R.string.auto_pick_h)) { sub = "autoapps" }
                    } }
                    if (rvl.next(3)) { Section(stringResource(R.string.auto_timing)) {
                        CounterRow(stringResource(R.string.auto_delay), Autostart.delaySec(ctx), 0..600, stringResource(R.string.auto_unit)) { Autostart.setDelaySec(ctx, it) }
                        CounterRow(stringResource(R.string.auto_gap), Autostart.gapSec(ctx), 0..120, stringResource(R.string.auto_unit)) { Autostart.setGapSec(ctx, it) }
                    } }
                    // 19.2: автоочистка кэша выбранных приложений (после выхода из приложения и при включении устройства)
                    @Suppress("UNUSED_VARIABLE") val cacheRev = CacheAuto.rev.intValue
                    if (rvl.next(5)) { Section(stringResource(R.string.cache_title)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SwitchRow(stringResource(R.string.cache_on), CacheAuto.on(ctx), listOf(stringResource(R.string.cache_hint))) { CacheAuto.setOn(ctx, it); if (it && !(CacheAuto.accessEnabled(ctx) && CacheAuto.shellReady())) cachePerm = true }
                            ActionRow(HIcons.list, stringResource(R.string.cache_pick) + " (" + CacheAuto.list(ctx).size + ")", stringResource(R.string.cache_pick_h)) { sub = "cacheapps" }
                            SwitchRow(stringResource(R.string.cache_after), CacheAuto.afterClose(ctx), listOf()) { CacheAuto.setAfterClose(ctx, it) }
                            if (CacheAuto.afterClose(ctx)) CounterRow(stringResource(R.string.cache_delay), CacheAuto.delaySec(ctx), 0..600, stringResource(R.string.auto_unit)) { CacheAuto.setDelaySec(ctx, it) }
                            SwitchRow(stringResource(R.string.cache_boot), CacheAuto.onBootOn(ctx), listOf()) { CacheAuto.setOnBoot(ctx, it) }
                            ActionRow(HIcons.list, stringResource(R.string.cache_now), stringResource(R.string.cache_now_h)) { if (CacheAuto.shellReady()) CacheAuto.runNow(ctx) else cachePerm = true }
                            val cl = CacheAuto.logText(ctx)
                            if (cl.isNotBlank()) Text(cl, color = LocalTh.current.hint, fontSize = btnSp(11))
                        }
                    } }
                    if (rvl.next(2)) { Section(stringResource(R.string.auto_check)) {
                        ActionRow(HIcons.refresh, stringResource(R.string.auto_now), stringResource(R.string.auto_now_h)) { Autostart.runNow(ctx, scope) }
                    } }
                    if (rvl.next(1)) { Section(stringResource(R.string.auto_log)) {
                        Text(Autostart.logText(ctx).ifBlank { stringResource(R.string.auto_log_none) }, color = t.sub, fontSize = btnSp(12))
                    } }
                }
                if (rvl.next(1)) { GroupHead("files", wide || query.isNotBlank(), g) { grp = it } }
                if (g == "files" || query.isNotBlank()) {
                    if (rvl.next(7)) { Section(stringResource(R.string.set_install)) {
                        SectionHint(stringResource(R.string.install_hint))
                        ActionRow(HIcons.folder, stringResource(R.string.fb_open), stringResource(R.string.fb_open_h)) { browser = true }
                        ActionRow(HIcons.wifi, stringResource(R.string.wifi_open), stringResource(R.string.wifi_open_h)) { wifi = true }
                        ActionRow(HIcons.list, stringResource(R.string.install_log_btn), stringResource(R.string.install_log_btn_h)) { instLog = true }
                        ActionRow(HIcons.download, stringResource(R.string.net_open), stringResource(R.string.net_open_h)) { net = true }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionRow(HIcons.folder, stringResource(R.string.install_tree), stringResource(R.string.install_tree_h)) {
                                runCatching { pickApkTree.launch(null) }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.icon_no_picker), Toast.LENGTH_LONG).show() }
                            }
                            ActionRow(HIcons.folder, stringResource(R.string.install_zip), stringResource(R.string.install_zip_h)) {
                                runCatching { pickApkZip.launch(arrayOf("*/*")) }
                                    .onFailure { Toast.makeText(ctx, ctx.getString(R.string.icon_no_picker), Toast.LENGTH_LONG).show() }
                            }
                        }
                    } }
                }
                if (rvl.next(1)) { GroupHead("system", wide || query.isNotBlank(), g) { grp = it } }
                if (g == "system" || query.isNotBlank()) {
                    if (rvl.next(1)) { BlockHead("sy_b", stringResource(R.string.blk_sy_b), openBlk, query.isNotBlank()) }
                    if ("sy_b" in openBlk || query.isNotBlank()) {
                    if (rvl.next(3)) { Section(stringResource(R.string.set_fullscreen)) {
                        val ctxSb = androidx.compose.ui.platform.LocalContext.current
                        var sbS by remember { mutableStateOf(!SysBars.hidden(ctxSb, false)) }
                        var sbN by remember { mutableStateOf(!SysBars.hidden(ctxSb, true)) }
                        val onOff = listOf(true to stringResource(R.string.on), false to stringResource(R.string.off))
                        Text(stringResource(R.string.sb_status), color = t.sub, fontSize = 13.sp)
                        Options(onOff, sbS) { sbS = it; SysBars.set(ctxSb, false, if (it) 1 else 2); onFullscreenChanged() }
                        Text(stringResource(R.string.sb_nav), color = t.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                        Options(onOff, sbN) { sbN = it; SysBars.set(ctxSb, true, if (it) 1 else 2); onFullscreenChanged() }
                    } }
                    if (rvl.next(7)) { Section(stringResource(R.string.set_mem)) {
                        FoldItem(stringResource(R.string.mem_auto), stringResource(R.string.mem_auto_hint)) {
                            Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.memAuto) { Cfg.changeMemAuto(it) }
                        }
                        FoldItem(stringResource(R.string.mem_keep_fav), null) {
                            Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.memKeepFav) { Cfg.changeMemKeepFav(it) }
                        }
                        FoldItem(stringResource(R.string.mem_excl_title), stringResource(R.string.mem_excl_hint)) {
                            ActionRow(HIcons.list, stringResource(R.string.mem_excl_open) + " (" + Cfg.memExcl.size + ")", stringResource(R.string.mem_excl_open_h)) { sub = "excl" }
                        }
                    } }
                    if (rvl.next(1)) { SwitchRow(stringResource(R.string.boot_title), Cfg.bootWarm, listOf(stringResource(R.string.boot_hint))) { Cfg.changeBootWarm(it) } }
                    if (rvl.next(2)) { Section(stringResource(R.string.set_lite)) {
                        Options(
                            listOf(0 to stringResource(R.string.lite_auto), 1 to stringResource(R.string.on), 2 to stringResource(R.string.off)), Lite.mode,
                        ) { Lite.change(ctx, it) }
                        SectionHint(stringResource(R.string.lite_hint))
                    } }
                    }
                    if (rvl.next(1)) { BlockHead("sy_c", stringResource(R.string.blk_sy_c), openBlk, query.isNotBlank()) }
                    if ("sy_c" in openBlk || query.isNotBlank()) {
                    if (rvl.next(1)) { SwitchRow(stringResource(R.string.ask_cache_title), Cfg.askClearCache, listOf(stringResource(R.string.ask_cache_hint))) { Cfg.changeAskClearCache(it) } }
                    if (rvl.next(5)) { Section(stringResource(R.string.adb_title)) {
                        Text(stringResource(Conn.statusRes), color = if (Conn.connected) t.accent else t.sub, fontSize = 14.sp)
                        ActionRow(HIcons.usb, stringResource(R.string.adb_connect_btn), stringResource(R.string.adb_connect_btn_h)) { adbDialog = true }
                        ActionRow(HIcons.wifi, stringResource(R.string.adbn_btn), stringResource(R.string.adbn_btn_h)) { adbNet = true }
                        Text(stringResource(R.string.root_use_title), color = t.sub, fontSize = 13.sp)
                        Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.useRoot) {
                            Cfg.changeUseRoot(it)
                            if (!it) { Adb.disableRoot(); Conn.refresh() }
                            // включили root, а он не выдан: окно с подсказкой, где его выдать (14.1)
                            else if (!Adb.isRoot) scope.launch { if (Adb.tryRoot(RootHelp.EXPLICIT)) Conn.refresh() }
                        }
                        SectionHint(stringResource(R.string.root_hint))
                        // по умолчанию выключено: su в фоне не запускается, пока человек сам не включит датчик бездействия через root
                        var rootSensor by remember { mutableStateOf(ctx.getSharedPreferences("tvapps", android.content.Context.MODE_PRIVATE).getBoolean("saver_root_sensor", false)) }
                        Text(stringResource(R.string.root_sensor_title), color = t.sub, fontSize = 13.sp)
                        Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), rootSensor) {
                            rootSensor = it
                            ctx.getSharedPreferences("tvapps", android.content.Context.MODE_PRIVATE).edit().putBoolean("saver_root_sensor", it).apply()
                        }
                        SectionHint(stringResource(R.string.root_sensor_hint))
                    } }
                    }
                }
                rvl.end()
                Spacer(Modifier.size(24.dp))
            } else if (sub == "about") {
                Column(Modifier.widthIn(max = 900.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), color = t.sub, fontSize = hintSp(14))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionRow(HIcons.open, stringResource(R.string.about_site), stringResource(R.string.about_site_h)) { openUrl(ctx, "https://drugoj00.github.io/phone-in-window-with-sound/app-menu.html") }
                    ActionRow(HIcons.open, stringResource(R.string.about_tg), stringResource(R.string.about_tg_h)) { openUrl(ctx, "https://t.me/PhoneInWindowSound") }
                    ActionRow(HIcons.open, stringResource(R.string.about_gh), stringResource(R.string.about_gh_h)) { openUrl(ctx, "https://github.com/drugoj00/app-menu") }
                    ActionRow(HIcons.open, stringResource(R.string.about_all), stringResource(R.string.about_all_h)) { openUrl(ctx, "https://drugoj00.github.io/") }
                }
                Text(stringResource(R.string.about_support), color = t.sub, fontSize = hintSp(13), modifier = Modifier.padding(top = 6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionRow(HIcons.open, stringResource(R.string.about_card), stringResource(R.string.about_card_h)) { openUrl(ctx, "https://secure.wayforpay.com/button/bba92579a4647") }
                    ActionRow(HIcons.open, stringResource(R.string.about_usdt), stringResource(R.string.about_usdt_h)) { copyText(ctx, "TXk32CLkwJryTqhmkw6urcM5CacJgPsSpJ") }
                }
                }
            } else if (sub == "dockapps") {
                DockAppsPane(ctx, apps)
            } else if (sub == "dockhide") {
                DockHidePane(ctx, apps)
            } else if (sub == "autoapps") {
                AutoAppsPane(ctx, apps)
            } else if (sub == "cacheapps") {
                CacheAppsPane(ctx, apps)
            } else if (sub == "excl") {
                ExcludeList(apps)
            } else {
                HiddenList(apps)
            }
        }
        }
        }
    }
    if (iconPacksDlg) IconPacksDialog(apps) { iconPacksDlg = false }
    if (bkRemote) BackupRemoteDialog { bkRemote = false }
    if (qaDlg) QuickActionsDialog { qaDlg = false }
    if (cachePerm) CachePermDialog { cachePerm = false }
    if (menuBgDlg) BgDialogHost(true) { menuBgDlg = false }
    if (homeBgDlg) BgDialogHost(false) { homeBgDlg = false }
    if (dockKeyDlg) DockKeyCapture { code -> if (code != null) { HomeDock.setKeyCode(ctx, code); HomeDock.setKeyMode(ctx, 3) }; dockKeyDlg = false }
    if (searchDlg) TextDialog(stringResource(R.string.set_search), query, stringResource(R.string.set_search_hint), { query = it.trim(); searchDlg = false }, { searchDlg = false })
    bkpPick?.let { k ->
        BackupPickDialog(
            onlyApp = k == "app",
            onSystem = if (k == "file") ({
                bkpPick = null
                runCatching { loadBackup.launch(arrayOf("*/*")) }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.icon_no_picker), Toast.LENGTH_LONG).show() }
            }) else null,
            onClose = { bkpPick = null },
        )
    }
    if (adbDialog) AdbConnectDialog(onClose = { adbDialog = false; Conn.refresh() })
    if (adbNet) AdbNetWizardDialog { adbNet = false; Conn.refresh() }
    if (appsUpd) UpdatesAllDialog { appsUpd = false }
    if (instLog) InstallLogDialog { instLog = false }
    installFiles?.let { files -> InstallDialog(files) { installFiles = null } }
    if (net) NetDialog { net = false }
    if (backups) BackupsDialog { backups = false }
    if (browser) FileBrowser(onApks = { browser = false; installFiles = it }, onClose = { browser = false })
    if (wifi) UploadDialog(onApks = { wifi = false; installFiles = it }, onClose = { wifi = false })
}

/** Копирует текст в буфер обмена и сообщает об этом. */
private fun copyText(ctx: android.content.Context, text: String) {
    runCatching {
        (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("", text))
        Toast.makeText(ctx, ctx.getString(R.string.about_copied, text), Toast.LENGTH_SHORT).show()
    }
}

internal fun openUrl(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(ctx, ctx.getString(R.string.home_cant_open), Toast.LENGTH_SHORT).show() }
}

/** Пункт настроек, где длинное пояснение свёрнуто: оно раскрывается нажатием на название. */
@Composable
private fun FoldItem(title: String, hint: String?, content: @Composable () -> Unit) {
    val t = LocalTh.current
    var open by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape(8.dp))
                .focusBorder({ focused }, 2.dp, 0.dp, t.accent, Color.Transparent, RoundedCornerShape(8.dp))
                .then(if (hint != null) Modifier.clickable { open = !open } else Modifier)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, color = t.text, fontSize = 15.sp)
            if (hint != null) Text(if (open) "▴" else "▾", color = t.accent, fontSize = 15.sp)
        }
        if (open && hint != null) Text(hint, color = t.hint, fontSize = hintSp(12))
        content()
    }
}

/** Приложения, которые очистка памяти не останавливает: отметки по установленным (не системным). */
@Composable
private fun ExcludeList(apps: List<Entry>) {
    val t = LocalTh.current
    val list = apps.filter { !it.system }
    if (list.isEmpty()) { Text(stringResource(R.string.mem_excl_none), color = t.sub, fontSize = 14.sp); return }
    list.forEach { e ->
        val on = e.pkg in Cfg.memExcl
        var focused by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp)
                .onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape(12.dp))
                .drawBehind { drawRect(if (focused) t.tileFocus else t.tile) }
                .focusBorder({ focused }, 3.dp, 0.dp, t.accent, Color.Transparent, RoundedCornerShape(12.dp))
                .toggleable(on, onValueChange = { Cfg.toggleMemExcl(e.pkg) })
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val bmp = remember(e.pkg) { e.icon.toBitmap(96, 96).asImageBitmap() }
            Image(bmp, null, Modifier.size(36.dp))
            Column(Modifier.weight(1f)) {
                Text(e.label, color = t.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e.pkg, color = t.hint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BigCheck(on, t.accent, t.sub)
        }
    }
}

@Composable
private fun HiddenList(apps: List<Entry>) {
    val t = LocalTh.current
    if (apps.isEmpty()) return
    var anyHidden = false
    apps.forEach { e ->
        val h = Cfg.isHidden(e.pkg)
        if (h) anyHidden = true
        var focused by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp)
                .onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape(12.dp))
                .drawBehind { drawRect(if (focused) t.tileFocus else t.tile) }
                .focusBorder({ focused }, 3.dp, 0.dp, t.accent, Color.Transparent, RoundedCornerShape(12.dp))
                .toggleable(h, onValueChange = { Cfg.toggleHidden(e.pkg) })
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val bmp = remember(e.pkg) { e.icon.toBitmap(96, 96).asImageBitmap() }
            Image(bmp, null, Modifier.size(36.dp))
            Column(Modifier.weight(1f)) {
                Text(e.label, color = t.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e.pkg, color = t.hint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BigCheck(h, t.accent, t.sub)
        }
    }
    @Suppress("UNUSED_EXPRESSION") anyHidden
}


/** Включает или выключает возможность быть лаунчером; по умолчанию она выключена. */
private fun setHomeMode(ctx: android.content.Context, on: Boolean) {
    val c = android.content.ComponentName(ctx, "app.tvapps.HomeAlias")
    ctx.packageManager.setComponentEnabledSetting(
        c,
        if (on) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        android.content.pm.PackageManager.DONT_KILL_APP,
    )
}


/** Откладывает смену группы настроек при быстром пролистывании списка групп (15.1): см. [GroupsPane]. */
private class GroupPickGate {
    private var lastAt = 0L
    private var job: kotlinx.coroutines.Job? = null
    fun request(scope: kotlinx.coroutines.CoroutineScope, id: String, onPick: (String) -> Unit) {
        job?.cancel(); job = null
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastAt > 300L) { lastAt = now; onPick(id); return }
        job = scope.launch { kotlinx.coroutines.delay(110); lastAt = android.os.SystemClock.uptimeMillis(); onPick(id) }
    }
}

/** Порционная сборка содержимого группы настроек (16.0): первые [FIRST] элементов сразу, остальные по [CHUNK] за кадр. */
private class RevealGate {
    companion object { const val FIRST = 3; const val CHUNK = 3 }
    @JvmField var limit = Int.MAX_VALUE
    @JvmField var n = 0
    @JvmField var total = 0
    private var lastG = ""
    private var lastAll = true
    /** Есть запомненное положение (прокрутка, кнопка в фокусе): первую сборку делаем целиком, иначе прокрутка упрётся в короткое содержимое и потеряется. */
    private val restore = MenuMemory.settingsScroll > 0 || MenuMemory.settingsFocusText != null
    /** Меняется, чтобы экран настроек перекомпоновался и показал очередную порцию. */
    var tick by androidx.compose.runtime.mutableIntStateOf(0)
    fun begin(g: String, all: Boolean) {
        tick   // чтение состояния: перекомпоновка при новой порции
        n = 0
        if (all) { limit = Int.MAX_VALUE; lastAll = true; lastG = g; return }
        if (lastG.isEmpty() && restore) { limit = Int.MAX_VALUE; lastAll = false; lastG = g; return }
        if (g != lastG || lastAll) { lastG = g; lastAll = false; limit = FIRST }
    }
    /** Оператор весом [w] (число тяжёлых строк внутри) показывается, если до него набрано меньше предела: тяжёлый оператор занимает свой кадр. */
    fun next(w: Int): Boolean { val show = n < limit; n += w; return show }
    fun end() { total = n }
}
