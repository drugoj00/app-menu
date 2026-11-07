@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package app.tvapps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Полоса столов внизу под сеткой (показывается, когда столов больше одного): квадратные кнопки с цифрами 1-9, выбранный стол залит цветом, у остальных рамка.
 * Квадраты на узком экране сами уменьшаются (не меньше 28 dp), чтобы все девять помещались в ширину без прокрутки. Вся полоса одна цель для пульта:
 * ВЛЕВО и ВПРАВО на ней переключают столы по кругу (ОК не нужен), ВВЕРХ уходит к сетке. Касание и мышь: нажать на цифру. Для озвучки у каждой «Стол N».
 */
@Composable
fun DeskStrip(pages: List<DeskPage>, cur: Int, onSelect: (Int) -> Unit, onStep: (Int) -> Unit, onFocus: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTh.current
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val narrow = isNarrow()
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.Center) {
        val n = pages.size.coerceAtLeast(1)
        // в окнах телефона единицы вдвое мельче (HalfUi): 76 единиц = 38 dp на экране; на приставке 46 dp (чуть ниже кнопок шапки)
        val base = if (narrow) 76.dp else 46.dp
        val minSq = if (narrow) 56.dp else 28.dp
        val pad = if (narrow) 8.dp else 6.dp
        val avail = maxWidth - pad * 2
        var gap = if (narrow) 8.dp else 8.dp
        var sq = ((avail - gap * (n - 1)) / n).coerceIn(minSq, base)
        if (sq * n + gap * (n - 1) > avail) { gap = if (narrow) 4.dp else 4.dp; sq = ((avail - gap * (n - 1)) / n).coerceAtLeast(minSq) }
        Row(
            Modifier
                .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) false
                    else when (ev.key) {
                        Key.DirectionLeft -> { onStep(-1); true }
                        Key.DirectionRight -> { onStep(1); true }
                        else -> false
                    }
                }
                .focusable()
                .clip(RoundedCornerShape(12.dp))
                .border(if (focused) 3.dp else 0.dp, if (focused) t.accent else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(horizontal = pad, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.forEachIndexed { i, _ ->
                val sel = i == cur
                val desc = ctx.getString(R.string.desk_default, i + 1)
                Box(
                    Modifier.size(sq).clip(RoundedCornerShape(10.dp))
                        .background(if (sel) t.chipSel else Color.Transparent)
                        .border(if (sel) 2.dp else 1.dp, if (sel) t.accent else t.border, RoundedCornerShape(10.dp))
                        .semantics { contentDescription = desc; selected = sel }
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text((i + 1).toString(), color = t.text, fontSize = btnSp(19), fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** «На стол…»: выбор стола для одного или нескольких приложений (в карточке и при выделении). Если на столе нет места, лишнее уходит на следующий. */
@Composable
fun DeskPickDialog(pkgs: List<String>, pages: List<DeskPage>, onClose: () -> Unit) {
    val t = LocalTh.current
    val ctx = LocalContext.current
    val tmpl = { n: Int -> ctx.getString(R.string.desk_default, n) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val set = pkgs.toSet()
    HDialog(onDismissRequest = onClose) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.desk_pick_title), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pages.forEachIndexed { i, p ->
                    val here = p.pkgs.any { it in set } && set.all { it in p.pkgs }
                    Chip((i + 1).toString() + " (" + p.pkgs.size + ")", here, if (i == 0) Modifier.focusRequester(first) else Modifier) {
                        Desks.pin(pkgs, p.id, tmpl); onClose()
                    }
                }
                if (Desks.list.size < Desks.MAX) Chip(stringResource(R.string.desk_new), false) {
                    val d = Desks.add("", tmpl)
                    if (d != null) Desks.pin(pkgs, d.id, tmpl)
                    onClose()
                }
                if (pkgs.any { it in Desks.pins }) Chip(stringResource(R.string.desk_unpin), false) { Desks.unpin(pkgs); onClose() }
                Chip(stringResource(R.string.cancel), false) { onClose() }
            }
        }
    }
}

/** Действия меню столов, которые делает главный экран. */
class DeskMenuActions(val find: () -> Unit, val settings: () -> Unit)

@Composable
private fun MRow(text: String, enabled: Boolean = true, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Chip(text, selected, modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f)) { if (enabled) onClick() }
}

/**
 * Меню столов: долгий тап или правая кнопка мыши на пустом месте, удержание «ОК» на пустом столе, баннер «ПРИЛОЖЕНИЯ N» в шапке.
 * Первая строка переключает режим (по состоянию: «Включить» или «Выключить»); при включённом режиме дальше пункты стола.
 * Настройки стола (фон, сетка, размер значков, сортировка) хранятся по номеру стола и наследуют общие значения.
 */
@Composable
fun DeskMenuDialog(pages: List<DeskPage>, act: DeskMenuActions, onClose: () -> Unit) {
    val t = LocalTh.current
    val ctx = LocalContext.current
    val tmpl = { n: Int -> ctx.getString(R.string.desk_default, n) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    // ОК, нажатый до открытия окна, и его повторы не должны сразу нажать первый пункт
    val shownAt = remember { android.os.SystemClock.elapsedRealtime() }
    var sub by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val idx = Desks.curIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    val curUser = Desks.list.indexOfFirst { it.id == Desks.curId }
    val canAdd = Desks.on && pages.size < Desks.MAX && Desks.list.size < Desks.MAX
    val cfg = Desks.cfgOf(idx)
    val emptyPage = pages.getOrNull(idx)?.pkgs.isNullOrEmpty()
    val maxH = (screenDp().second * 0.82f).dp
    HDialog(onDismissRequest = onClose) {
        Box(Modifier.onPreviewKeyEvent { OkGuard.eats(it, shownAt) }) {
            Column(
                Modifier.clip(RoundedCornerShape(20.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(16.dp).fillMaxWidth()
                    .heightIn(max = maxH).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (sub) {
                    "goto" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pages.forEachIndexed { i, p -> Chip((i + 1).toString(), i == idx, if (i == 0) Modifier.focusRequester(first) else Modifier) { Desks.switchTo(p.id); onClose() } }
                    }
                    "sort" -> {
                        val modes = listOf("" to R.string.desk_sort_common, "name" to R.string.sort_name, "new" to R.string.sort_new, "freq" to R.string.sort_freq, "custom" to R.string.sort_custom)
                        modes.forEachIndexed { i, (m, r) -> Chip(stringResource(r), cfg.sort == m, Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier)) { Desks.setCfg(idx) { it.copy(sort = m) }; onClose() } }
                    }
                    "bg" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(0L, 0x402563EBL, 0x4010B981L, 0x40F59E0BL, 0x40A855F7L, 0x40EF4444L, 0x40475569L).forEachIndexed { i, c ->
                            ColorSwatch(c, cfg.bg == c, if (i == 0) Modifier.focusRequester(first) else Modifier) { Desks.setCfg(idx) { it.copy(bg = c) }; onClose() }
                        }
                    }
                    "grid" -> {
                        Text(stringResource(R.string.desk_m_cols), color = t.sub, fontSize = 13.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip(stringResource(R.string.desk_common), cfg.cols < 0, Modifier.focusRequester(first)) { Desks.setCfg(idx) { it.copy(cols = -1) } }
                            Chip(stringResource(R.string.cols_auto), cfg.cols == 0) { Desks.setCfg(idx) { it.copy(cols = 0) } }
                            for (n in 2..10) Chip(n.toString(), cfg.cols == n) { Desks.setCfg(idx) { it.copy(cols = n) } }
                        }
                        Text(stringResource(R.string.desk_m_rows), color = t.sub, fontSize = 13.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip(stringResource(R.string.desk_common), cfg.rows < 0) { Desks.setCfg(idx) { it.copy(rows = -1) } }
                            Chip(stringResource(R.string.cols_auto), cfg.rows == 0) { Desks.setCfg(idx) { it.copy(rows = 0) } }
                            for (n in 1..8) Chip(n.toString(), cfg.rows == n) { Desks.setCfg(idx) { it.copy(rows = n) } }
                        }
                    }
                    "size" -> {
                        val sizes = listOf(-1 to R.string.desk_common, 3 to R.string.size_xs, 0 to R.string.size_s, 1 to R.string.size_m, 2 to R.string.size_l)
                        sizes.forEachIndexed { i, (v, r) -> Chip(stringResource(r), cfg.size == v, Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier)) { Desks.setCfg(idx) { it.copy(size = v) }; onClose() } }
                    }
                    else -> {
                        // первая строка: действие по текущему состоянию режима
                        Chip(stringResource(if (Desks.on) R.string.desk_m_mode_on else R.string.desk_m_mode_off), false, Modifier.fillMaxWidth().focusRequester(first)) { Desks.enable(!Desks.on); onClose() }
                        // пункты собираются в список: на невысоком экране (ТВ, магнитола) они идут в две колонки, чтобы ничего не обрезалось
                        val rows = mutableListOf<@Composable (Modifier) -> Unit>()
                        if (Desks.on) {
                            rows += { m -> MRow(if (canAdd) stringResource(R.string.desks_add) else stringResource(R.string.desk_max, Desks.MAX), canAdd, modifier = m) {
                                // пустой стол после текущего (или в конце); на него переключаемся
                                val d = Desks.add("", tmpl, Desks.curId)
                                if (d != null) Desks.switchTo(d.id)
                                onClose()
                            } }
                            if (curUser >= 0) rows += { m -> MRow(stringResource(if (confirmDelete) R.string.desk_m_del_confirm else R.string.desk_m_del), true, confirmDelete, m) {
                                if (!confirmDelete) confirmDelete = true
                                else { Desks.remove(Desks.curId, pages.map { it.id }); onClose() }
                            } }
                            rows += { m -> MRow(stringResource(R.string.desk_m_goto), pages.size > 1, modifier = m) { sub = "goto" } }
                            rows += { m -> MRow(stringResource(R.string.desk_m_sort), !emptyPage, modifier = m) { sub = "sort" } }
                            rows += { m -> MRow(stringResource(R.string.desk_m_align), false, modifier = m) { } }
                            rows += { m -> MRow(stringResource(R.string.desk_m_bg), modifier = m) { sub = "bg" } }
                            rows += { m -> MRow(stringResource(R.string.desk_m_grid), modifier = m) { sub = "grid" } }
                            rows += { m -> MRow(stringResource(R.string.desk_m_size), modifier = m) { sub = "size" } }
                            if (curUser > 0) rows += { m -> MRow(stringResource(R.string.desk_m_left), modifier = m) { Desks.move(Desks.curId, -1); onClose() } }
                            if (curUser >= 0 && curUser < Desks.list.lastIndex) rows += { m -> MRow(stringResource(R.string.desk_m_right), modifier = m) { Desks.move(Desks.curId, 1); onClose() } }
                            rows += { m -> MRow(stringResource(if (confirmClear) R.string.desk_m_clear_confirm else R.string.desk_m_clear), pages.size > 1 && !emptyPage, confirmClear, m) {
                                if (!confirmClear) confirmClear = true
                                else {
                                    // значки стола уходят на следующий стол (по кругу); если там нет места, дальше по порядку
                                    val next = pages[(idx + 1) % pages.size]
                                    Desks.pin(pages[idx].pkgs, next.id, tmpl)
                                    onClose()
                                }
                            } }
                        }
                        rows += { m -> MRow(stringResource(R.string.desk_m_find), modifier = m) { act.find(); onClose() } }
                        rows += { m -> MRow(stringResource(R.string.desk_m_settings), modifier = m) { act.settings(); onClose() } }
                        // высота пункта около 52 dp с зазором (кнопка 44 dp), плюс первая строка и «Закрыть»
                        val two = (rows.size + 2) * 52f + 40f > maxH.value
                        if (!two) rows.forEach { r -> r(Modifier) }
                        else {
                            val half = (rows.size + 1) / 2
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { rows.take(half).forEach { r -> r(Modifier) } }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { rows.drop(half).forEach { r -> r(Modifier) } }
                            }
                        }
                    }
                }
                Chip(stringResource(R.string.close), false, Modifier.fillMaxWidth()) { onClose() }
            }
        }
    }
}

/**
 * Заголовок «ПРИЛОЖЕНИЯ N» в шапке меню: долгий тап, правая кнопка мыши и удержание «ОК» на пульте открывают меню столов.
 * Заголовок фокусируется пультом (ВВЕРХ из первой строки сетки, затем ВЛЕВО от кнопок шапки) ровно настолько, чтобы удержание «ОК» работало.
 */
@Composable
fun TitleMenuBox(onMenu: () -> Unit, content: @Composable () -> Unit) {
    val t = LocalTh.current
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && (ev.key == Key.DirectionCenter || ev.key == Key.Enter) && ev.nativeKeyEvent.repeatCount == 1) { OkGuard.arm(); onMenu(); true }
                else false
            }
            .focusable()
            .clip(RoundedCornerShape(10.dp))
            .border(if (focused) 2.dp else 0.dp, if (focused) t.accent else Color.Transparent, RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.type == androidx.compose.ui.input.pointer.PointerEventType.Press && ev.buttons.isSecondaryPressed) { onMenu(); ev.changes.forEach { it.consume() } }
                    }
                }
            }
            .combinedClickable(onClick = {}, onLongClick = onMenu)
            .padding(horizontal = 4.dp),
    ) { content() }
}

/**
 * Квадрат выбора цвета: цвет (0 означает «без цвета» с крестиком), рамка у выбранного и у того, на ком фокус. Крестик нарисован линиями ровно по центру квадрата
 * (размер 55 % стороны), а не текстовым символом, который сдвигается межстрочным интервалом шрифта.
 */
@Composable
fun ColorSwatch(argb: Long, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = LocalTh.current
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(if (argb == 0L) t.bg else Color(argb.toInt()))
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused || selected) 3.dp else 1.dp, if (focused || selected) t.accent else t.border, RoundedCornerShape(12.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (argb == 0L) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val h = size.minDimension * 0.55f / 2f
            val c = center
            val w = size.minDimension * 0.07f
            drawLine(t.sub, androidx.compose.ui.geometry.Offset(c.x - h, c.y - h), androidx.compose.ui.geometry.Offset(c.x + h, c.y + h), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(t.sub, androidx.compose.ui.geometry.Offset(c.x - h, c.y + h), androidx.compose.ui.geometry.Offset(c.x + h, c.y - h), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}
