@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.tvapps

import android.graphics.Bitmap
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Окна настроек «Дома»: что открыто сейчас. */
sealed interface Dlg {
    data class ItemD(val rowId: String, val itemId: String) : Dlg
    data class Look(val rowId: String, val itemId: String) : Dlg
    data class Label(val rowId: String, val itemId: String) : Dlg
    data class RowD(val rowId: String) : Dlg
    data class AddRow(val afterId: String?) : Dlg
    data class AddItem(val rowId: String) : Dlg
    data class PickApps(val rowId: String) : Dlg
    data class PickHdmi(val rowId: String) : Dlg
    data class CardSource(val rowId: String) : Dlg
    data class Rename(val rowId: String) : Dlg
    data class WidgetD(val rowId: String, val slot: Int) : Dlg
    data class WidgetType(val rowId: String, val slot: Int) : Dlg
    data class WidgetCity(val rowId: String, val slot: Int) : Dlg
    data class Status(val id: String) : Dlg
    data object Top : Dlg
    data object Screen : Dlg
    data object Empty : Dlg      // долгое нажатие на пустом месте «Дома»: добавить строку и настройки
    data class MoveToRow(val itemId: String) : Dlg
    data class AppWidgetPick(val rowId: String, val slot: Int) : Dlg
    data object Bg : Dlg
    data class AppActions(val pkg: String) : Dlg
    /** Выбор стиля созданного баннера для одной плитки. */
    /** Запрос к каталогу программы для строки карточек (пакет программы). */
    data class CatQuery(val rowId: String, val pkg: String) : Dlg
    data class BannerStyle(val rowId: String, val itemId: String) : Dlg
    /** Предложение создать баннеры для плиток, у приложений которых его нет (пары «строка, плитка»). */
    data class BannerOffer(val items: List<Pair<String, String>>) : Dlg
}

/** Что запускали с «Дома»: при возврате строка прокручивается к этому элементу и курсор встаёт на него. */
internal object HomeFocus {
    @Volatile var rowId = ""
    @Volatile var key = ""
    @Volatile var pending = false
    fun mark(row: String, k: String) { rowId = row; key = k; pending = true }
}

/** Один показанный элемент строки значков: свой (из списка строки) или автоматический (избранное, недавние, входы). */
internal data class El(val item: HItem, val entry: Entry?, val tv: TvIn?, val auto: Boolean)

/** Во сколько раз растянуть плитки, чтобы строка заполнилась от края до края целым числом плиток (avail, base, gap в dp). */
internal fun fitScale(avail: Float, base: Float, gap: Float): Float = fitGrid(avail, base, gap).second

/** Масштаб карточек: при заданном числе в линии (perLine > 0) точно по нему, иначе по ширине плитки. */
internal fun fitScaleN(avail: Float, base: Float, gap: Float, perLine: Int): Float =
    if (perLine > 0 && base > 0f) ((avail - gap * (perLine - 1)) / (perLine * base)).coerceIn(0.4f, 2.5f) else 1f

/** Промежуток между плитками, чтобы линия точно заполнила ширину при точном размере плиток (n плиток помещается, остаток делится между промежутками). */
internal fun spreadGap(avail: Float, tileW: Float, gap: Float, perLine: Int): Float {
    if (perLine > 0 || tileW <= 0f) return gap
    val n = kotlin.math.floor((avail + gap) / (tileW + gap)).toInt().coerceAtLeast(1)
    return if (n > 1) ((avail - n * tileW) / (n - 1)).coerceAtLeast(gap) else gap
}

/** Сколько плиток помещается в линию и во сколько раз их растянуть, чтобы линия заполнилась от края до края. */
internal fun fitGrid(avail: Float, base: Float, gap: Float): Pair<Int, Float> {
    if (avail <= 0f || base <= 0f) return 1 to 1f
    val n = kotlin.math.floor((avail + gap) / (base + gap)).toInt().coerceAtLeast(1)
    val w = (avail - gap * (n - 1)) / n
    return n to (w / base).coerceIn(0.4f, 2.5f)
}

private fun ratio(a: String) = when (a) { "1:1" -> 1f; "2:3" -> 2f / 3f; else -> 16f / 9f }

internal fun programsFor(row: HRow, channels: List<ChRow>): List<ChProgram> {
    val src = when {
        row.auto == "watch_next" -> channels.filter { it.key == "watch_next" }
        row.auto.startsWith("pkg:") -> channels.filter { it.key != "watch_next" && it.pkg == row.auto.removePrefix("pkg:") }
        row.auto.startsWith("ch:") -> channels.filter { it.key == row.auto.removePrefix("ch:") }
        row.auto.startsWith("chm:") -> {
            val pkg = row.auto.removePrefix("chm:").substringBefore('|')
            val words = row.auto.substringAfter('|', "").split(';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            channels.filter { it.key != "watch_next" && it.pkg == pkg && words.any { w -> it.title.lowercase().contains(w) } }
        }
        else -> emptyList()
    }
    return src.flatMap { it.items }.distinctBy { it.id to it.kind }.take(row.count)
}

@Composable
internal fun HomeRowsView(
    layout: HLayout, apps: List<Entry>, tick: Int, refreshTick: Int, movingItem: String?, firstReq: FocusRequester,
    openApp: (Entry) -> Unit, openMenu: () -> Unit, onDlg: (Dlg?) -> Unit, onMoveEnd: () -> Unit,
) {
    val ctx = LocalContext.current
    val byPkg = remember(apps) { apps.associateBy { it.pkg } }
    val needChannels = layout.rows.any { it.kind == "cards" }
    var granted by androidx.compose.runtime.remember(tick) { androidx.compose.runtime.mutableStateOf(Channels.granted(ctx)) }
    var loaded by remember { androidx.compose.runtime.mutableStateOf(ChannelsMem.rows != null) }
    val channels by produceState(ChannelsMem.rows ?: emptyList(), tick, granted, Cfg.hiddenChannels, needChannels) {
        if (needChannels) {
            val r = withContext(Dispatchers.IO) { Channels.load(ctx) }
            ChannelsMem.rows = r
            value = r
            loaded = true
        } else value = emptyList()
    }
    val needInputs = layout.rows.any { it.auto == "hdmi" } || layout.rows.any { r -> r.items.any { it.kind == "hdmi" } }
    val inputs by produceState(emptyList<TvIn>(), tick, needInputs) {
        value = if (needInputs) withContext(Dispatchers.IO) { TvInputs.list(ctx) } else emptyList()
    }
    val movingReq = remember { FocusRequester() }
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(layout.rowGap.dp)) {
        layout.rows.forEachIndexed { idx, row ->
            androidx.compose.runtime.key(row.id) {
                RowTitle(row, onDlg)
                val first = if (idx == 0) firstReq else null
                when (row.kind) {
                    "cards" -> if (row.auto.startsWith("cat:")) CatCardsRow(row, tick, first, onDlg)
                        else if (row.auto.startsWith("mine:")) {
                            @Suppress("UNUSED_VARIABLE") val dep = CardStore.tick.intValue
                            CardsRow(row, CardStore.programs(ctx, row.auto.removePrefix("mine:")).take(row.count), true, tick, first, onDlg, true) {}
                        } else CardsRow(row, programsFor(row, channels), loaded, tick, first, onDlg, granted) { granted = Channels.granted(ctx) }
                    "widgets" -> WidgetsRow(row, tick, refreshTick, first, onDlg)
                    else -> AppsRow(row, byPkg, inputs, movingItem, movingReq, first, tick, openApp, openMenu, onDlg, onMoveEnd)
                }
            }
        }
        // кнопка «Добавить строку» всегда в конце: без неё нечем вернуть строки, если стёрли все
        Row(Modifier.padding(start = 6.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // кнопка нужна только когда не осталось ни одной строки (иначе нечем вернуть); обычно «Добавить строку» — в долгом меню значка и строки
            // строк не осталось: подсказка-плитка (ОК, касание или долгое нажатие открывают то же меню, что долгое нажатие на пустом месте)
            if (layout.rows.isEmpty()) HomeBox(Modifier.padding(4.dp), bg = LocalTh.current.tile, onClick = { onDlg(Dlg.Empty) }, onLong = { onDlg(Dlg.Empty) }) {
                Text(stringResource(R.string.empty_hint), color = LocalTh.current.text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
            }
        }
    }
}

/** Название строки: пустое — места над строкой нет; «невидимое» — место остаётся, текста не видно. */
@Composable
private fun RowTitle(row: HRow, onDlg: (Dlg?) -> Unit) {
    val t = LocalTh.current
    if (row.title.isBlank()) return
    Text(
        row.title, color = if (row.showTitle) t.sub else Color.Transparent, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 10.dp).pointerInput(row.id) { detectTapGestures(onLongPress = { onDlg(Dlg.RowD(row.id)) }) },
    )
}

// ------------------------------------------------------------------ строка значков

@Composable
private fun AppsRow(
    row: HRow, byPkg: Map<String, Entry>, inputs: List<TvIn>, movingItem: String?, movingReq: FocusRequester, firstReq: FocusRequester?, tick: Int,
    openApp: (Entry) -> Unit, openMenu: () -> Unit, onDlg: (Dlg?) -> Unit, onMoveEnd: () -> Unit,
) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val dep = Cfg.launchTick
    val manual = row.items.mapNotNull { it ->
        when (it.kind) {
            "app" -> byPkg[it.ref]?.let { e -> El(it, e, null, false) }
            "hdmi" -> El(it, null, inputs.firstOrNull { i -> i.id == it.ref } ?: TvIn(it.ref, it.label, true), false)
            else -> El(it, null, null, false)
        }
    }
    val taken = manual.mapNotNull { it.entry?.pkg }.toSet()
    val auto: List<El> = when (row.auto) {
        "fav" -> Cfg.favorites.mapNotNull { p -> byPkg[p] }.filter { !Cfg.isHidden(it.pkg) && it.pkg !in taken }.take(row.count)
            .map { El(HItem("a:" + it.pkg, "app", it.pkg, mode = row.itemMode, showLabel = row.itemMode == "icon"), it, null, true) }
        "recent" -> byPkg.values.filter { Cfg.lastLaunch(it.pkg) > 0L && !Cfg.isHidden(it.pkg) && it.pkg !in taken }
            .sortedByDescending { Cfg.lastLaunch(it.pkg) }.take(row.count).map { El(HItem("a:" + it.pkg, "app", it.pkg, mode = row.itemMode, showLabel = row.itemMode == "icon"), it, null, true) }
        "new" -> byPkg.values.filter { !it.system && it.pkg != ctx.packageName && !Cfg.isHidden(it.pkg) && it.pkg !in taken }
            .sortedByDescending { it.installed }.take(row.count).map { El(HItem("a:" + it.pkg, "app", it.pkg, mode = row.itemMode, showLabel = row.itemMode == "icon"), it, null, true) }
        "hdmi" -> inputs.take(row.count).map { El(HItem("a:" + it.id, "hdmi", it.id, mode = row.itemMode, showLabel = row.itemMode == "icon"), null, it, true) }
        else -> emptyList()
    }
    val els = manual + auto
    val density = LocalDensity.current
    if (els.isEmpty() && row.auto.isNotEmpty()) {
        HomeBox(
            Modifier.padding(horizontal = 4.dp), focusReq = firstReq, bg = LocalTh.current.tile,
            onClick = { onDlg(Dlg.RowD(row.id)) }, onLong = { onDlg(Dlg.RowD(row.id)) },
        ) { Text(stringResource(if (row.auto == "hdmi") R.string.home_hdmi_empty else R.string.home_auto_empty), color = LocalTh.current.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
        return
    }
    if (els.isEmpty()) {
        HomeBox(
            Modifier.padding(horizontal = 4.dp).size((row.size * 1.4f).dp, row.size.dp), focusReq = firstReq, bg = LocalTh.current.tile,
            onClick = { onDlg(Dlg.AddItem(row.id)) }, onLong = { onDlg(Dlg.RowD(row.id)) },
        ) { Icon(HIcons.add, null, tint = LocalTh.current.sub, modifier = Modifier.size(28.dp)) }
        return
    }
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val lastReq = remember { FocusRequester() }
    LaunchedEffect(tick) {
        if (HomeFocus.pending && HomeFocus.rowId == row.id) {
            val i = els.indexOfFirst { it.item.id == HomeFocus.key }
            if (i >= 0) {
                runCatching { state.scrollToItem(i) }
                delay(150)
                if (runCatching { (if (i == 0 && firstReq != null) firstReq else lastReq).requestFocus() }.isSuccess) HomeFocus.pending = false
            }
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxWidth().onGloballyPositioned { c ->
            val p = c.positionInRoot()
            TileDrag.rowRects[row.id] = androidx.compose.ui.geometry.Rect(p, androidx.compose.ui.geometry.Size(c.size.width.toFloat(), c.size.height.toFloat()))
        },
    ) {
    androidx.compose.runtime.DisposableEffect(row.id) { onDispose { TileDrag.rowRects.remove(row.id) } }
    // ширина слота каждой плитки при масштабе 1 (у значков не уже 84 dp: под ними подпись); линии набираются по реальной ширине
    val availW = maxWidth.value - 8f - 0.5f
    val gapF = row.gap.toFloat()
    val slotW = els.map { e -> val sz = row.size.toFloat(); maxOf(if (e.item.mode == "banner") sz * 16f / 9f else sz, 84f) }
    val lines = ArrayList<List<Int>>()
    if (row.fit && row.perLine > 0) lines.addAll(els.indices.toList().chunked(row.perLine)) else run {
        var cur = ArrayList<Int>(); var sum = 0f
        for (i in els.indices) {
            val add = slotW[i] + (if (cur.isEmpty()) 0f else gapF)
            if (cur.isNotEmpty() && sum + add > availW) { lines += cur; cur = ArrayList(); sum = 0f }
            sum += slotW[i] + (if (cur.isEmpty()) 0f else gapF); cur.add(i)
        }
        if (cur.isNotEmpty()) lines += cur
    }
    fun lineK(l: List<Int>): Float = ((availW - gapF * (l.size - 1)) / l.sumOf { slotW[it].toDouble() }.toFloat()).coerceIn(0.4f, 2.5f)
    // масштаб каждой линии: заполненные линии растягиваются точно до краёв, последняя берёт масштаб предыдущей (или не больше 1,5)
    // «Выровнять» без числа плиток в линии: размер плиток точный (задаётся размером строки), остаток ширины делится между промежутками;
    // с заданным числом плиток в линии: плитки растягиваются до краёв
    val scaleMode = row.fit && row.perLine > 0
    val ks = FloatArray(lines.size)
    for (li in lines.indices) ks[li] = if (!scaleMode) 1f else if (li < lines.lastIndex) lineK(lines[li]) else if (li > 0) ks[li - 1] else lineK(lines[li]).coerceAtMost(1.5f)
    val kFirst = if (lines.isEmpty()) 1f else ks[0]
    val lineGap = FloatArray(lines.size)
    for (li in lines.indices) lineGap[li] = if (!row.fit || scaleMode) gapF
        else if (li < lines.lastIndex && lines[li].size > 1) ((availW - lines[li].sumOf { slotW[it].toDouble() }.toFloat()) / (lines[li].size - 1)).coerceAtLeast(gapF)
        else if (li > 0) lineGap[li - 1] else gapF
    val tile: @Composable (El, Float) -> Unit = { el, k ->
            val isMoving = movingItem == el.item.id
            val bring = remember { BringIntoViewRequester() }
            val idx = els.indexOf(el)
            if (isMoving) LaunchedEffect(idx, row.id) { delay(30); runCatching { movingReq.requestFocus() }; runCatching { bring.bringIntoView() } }
            val sizeDp = row.size * k
            val wDp = if (el.item.mode == "banner") sizeDp * 16f / 9f else sizeDp
            val req = if (isMoving) movingReq else if (el === els.first()) firstReq else if (HomeFocus.key == el.item.id && HomeFocus.rowId == row.id) lastReq else null
            HomeAppTile(
                row, el, isMoving, req, with(density) { (wDp + row.gap).dp.toPx() }, Modifier.bringIntoViewRequester(bring), k,
                onClick = {
                    if (isMoving) onMoveEnd()
                    else when (el.item.kind) {
                        "menu" -> openMenu()
                        "settings" -> openSys(ctx, android.provider.Settings.ACTION_SETTINGS)
                        "hdmi" -> if (!TvInputs.open(ctx, el.item.ref)) Toast.makeText(ctx, ctx.getString(R.string.home_cant_open), Toast.LENGTH_SHORT).show()
                        else -> el.entry?.let { HomeFocus.mark(row.id, el.item.id); openApp(it) }
                    }
                },
                onLong = { onDlg(Dlg.ItemD(row.id, el.item.id)) },
                onMoveKey = { k2 ->
                    if (el.auto) true else when (k2) {
                        Key.DirectionLeft -> { HomeStore.moveItem(el.item.id, -1, 0); true }
                        Key.DirectionRight -> { HomeStore.moveItem(el.item.id, 1, 0); true }
                        Key.DirectionUp -> { HomeStore.moveItem(el.item.id, 0, -1); true }
                        Key.DirectionDown -> { HomeStore.moveItem(el.item.id, 0, 1); true }
                        else -> false
                    }
                },
                onStep = { d -> if (!el.auto) { onDlg(null); HomeStore.moveItem(el.item.id, d, 0) } },
                dragEnabled = !el.auto && !isMoving,
            )
    }
    if (row.wrap && row.fit) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(row.gap.dp)) {
            lines.forEachIndexed { li, line ->
                Row(horizontalArrangement = Arrangement.spacedBy(lineGap[li].dp)) {
                    line.forEach { i -> val el = els[i]; androidx.compose.runtime.key(el.item.id) { tile(el, ks[li]) } }
                }
            }
        }
    } else if (row.wrap) {
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(row.gap.dp), verticalArrangement = Arrangement.spacedBy(row.gap.dp),
        ) { els.forEach { el -> androidx.compose.runtime.key(el.item.id) { tile(el, 1f) } } }
    } else {
        LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy((lineGap.firstOrNull() ?: gapF).dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
            items(els, key = { it.item.id }) { el -> tile(el, kFirst) }
        }
    }
    }
}

@Composable
private fun HomeAppTile(
    row: HRow, el: El, moving: Boolean, req: FocusRequester?, stepPx: Float, mod: Modifier,
    scale: Float = 1f,
    onClick: () -> Unit, onLong: () -> Unit, onMoveKey: (Key) -> Boolean, onStep: (Int) -> Unit,
    dragEnabled: Boolean = false,
) {
    val t = LocalTh.current
    val ctx = LocalContext.current
    val item = el.item
    val banner = item.mode == "banner"
    val sizeDp = row.size * scale
    val wDp = if (banner) sizeDp * 16f / 9f else sizeDp
    val radius = item.radius.dp
    val custom = if (item.img) remember(item.id, HomeStore.imgTick) { HomeImg.load(ctx, item.id)?.asImageBitmap() } else null
    val bannerBmp by produceState<Bitmap?>(el.entry?.let { Banners.peek(it.pkg) }, el.entry?.pkg, banner) {
        val e = el.entry
        if (banner && e != null && value == null) value = withContext(Dispatchers.IO) { Banners.load(ctx, e) }
    }
    val iconBmp = el.entry?.let { e -> remember(e.pkg, e.icon, e.bmp) { e.bmp ?: e.icon.toBitmap(128, 128).asImageBitmap() } }
    val card = when (item.bg) { 1 -> false; 2 -> true; else -> banner }
    val label = item.label.ifBlank {
        when (item.kind) {
            "menu" -> stringResource(R.string.home_all_btn)
            "settings" -> stringResource(R.string.settings)
            "hdmi" -> el.tv?.label.orEmpty()
            else -> el.entry?.label.orEmpty()
        }
    }
    val vec = when (item.kind) { "menu" -> HIcons.apps; "settings" -> HIcons.gear; else -> HIcons.input }
    val showLabel = row.labels && item.mode == "icon"      // подписи только общие на строку (у значков), у баннеров название уже на картинке
    val shape = RoundedCornerShape(radius)
    val dens = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.DisposableEffect(item.id) { onDispose { TileDrag.tileRects.remove(item.id) } }
    HomeBox(
        mod.onGloballyPositioned { c ->
            if (dragEnabled) TileDrag.tileRects[item.id] = row.id to androidx.compose.ui.geometry.Rect(c.positionInRoot(), androidx.compose.ui.geometry.Size(c.size.width.toFloat(), c.size.height.toFloat()))
        }.graphicsLayer { alpha = if (TileDrag.active?.el?.item?.id == item.id) 0.25f else 1f },
        RoundedCornerShape(radius + 2.dp), Color.Transparent, moving, req, stepPx, onMoveKey, onStep, onClick = onClick, onMoveEnd = onClick, onLong = onLong,
        memKey = row.id + "|" + item.id,
        dragStart = if (dragEnabled) ({ p -> TileDrag.active = TileDrag.Info(row, el, scale, with(dens) { wDp.dp.toPx() }, with(dens) { sizeDp.dp.toPx() }); TileDrag.pos = p }) else null,
        dragMove = if (dragEnabled) ({ p -> TileDrag.pos = p }) else null,
        dragEnd = if (dragEnabled) ({ p -> TileDrag.drop(item.id, p) }) else null,
        ringColor = if (item.borderW > 0 && item.borderHover) (if (item.borderC == 0L) t.accent else Color(item.borderC.toInt())) else null,
        ringWidth = if (item.borderHover) item.borderW else 0,
    ) {
        Column(Modifier.width(maxOf(wDp, 84f * scale).dp).padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(wDp.dp, sizeDp.dp).then(if (item.shadow) Modifier.shadow(10.dp, shape) else Modifier)
                    .clip(shape).background(if (card) t.tile else Color.Transparent)
                    .then(if (item.borderW > 0 && !item.borderHover) Modifier.border(item.borderW.dp, if (item.borderC == 0L) t.accent else Color(item.borderC.toInt()), shape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                val bb = bannerBmp
                when {
                    custom != null -> Image(custom, null, Modifier.fillMaxSize(), contentScale = if (banner) ContentScale.Crop else ContentScale.Fit)
                    banner && bb != null -> Image(bb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    // у приложения нет баннера: на карточке баннера стоит его значок и название
                    banner -> Column(Modifier.fillMaxSize().padding(4.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        if (iconBmp != null) Image(iconBmp, null, Modifier.size((sizeDp * 0.52f).dp), contentScale = ContentScale.Fit)
                        else Icon(vec, null, tint = t.accent, modifier = Modifier.size((sizeDp * 0.46f).dp))
                        if (sizeDp >= 56) Text(label, color = t.text, fontSize = (sizeDp / 6f).coerceIn(9f, 13f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    iconBmp != null -> Image(iconBmp, null, Modifier.fillMaxSize(0.9f), contentScale = ContentScale.Fit)
                    else -> Icon(vec, null, tint = t.accent, modifier = Modifier.size((sizeDp * 0.55f).dp))
                }
            }
            if (showLabel) Text(
                label, color = t.text, fontSize = (sizeDp / 5.5f).coerceIn(10f, 15f).sp, maxLines = 2, minLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.width(maxOf(wDp, 84f * scale).dp).padding(top = 3.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ строка карточек

@Composable
private fun CardsRow(
    row: HRow, programs: List<ChProgram>, loaded: Boolean, tick: Int, firstReq: FocusRequester?, onDlg: (Dlg?) -> Unit, granted: Boolean, onGranted: () -> Unit,
) {
    val t = LocalTh.current
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val h = row.size
    val w = (h * ratio(row.aspect)).toInt()
    if (programs.isEmpty() && !loaded) { androidx.compose.foundation.layout.Spacer(Modifier.height(h.dp)); return }
    if (programs.isEmpty()) {
        HomeBox(
            Modifier.padding(horizontal = 4.dp).height(h.coerceAtMost(110).dp), RoundedCornerShape(12.dp), t.tile, focusReq = firstReq,
            onClick = {
                if (!granted && ctx.packageManager.hasSystemFeature("android.software.leanback")) scope.launch {
                    if (Adb.isConnected) withContext(Dispatchers.IO) { runCatching { Adb.shell("pm grant ${ctx.packageName} ${Channels.PERM}") } }
                    else runCatching { (ctx as? android.app.Activity)?.requestPermissions(arrayOf(Channels.PERM), 77) }
                    onGranted()
                    Toast.makeText(ctx, ctx.getString(if (Channels.granted(ctx)) R.string.home_channels_ok else R.string.home_channels_fail), Toast.LENGTH_LONG).show()
                } else onDlg(Dlg.CardSource(row.id))
            },
            onLong = { onDlg(Dlg.RowD(row.id)) },
        ) {
            Text(
                stringResource(if (row.auto.startsWith("mine:")) R.string.mine_empty else if (!granted) R.string.home_channels_on else R.string.home_no_cards), color = t.sub, fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        return
    }
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val lastReq = remember { FocusRequester() }
    LaunchedEffect(tick) {
        if (HomeFocus.pending && HomeFocus.rowId == row.id) {
            val i = programs.indexOfFirst { it.kind.toString() + "|" + it.id == HomeFocus.key }
            if (i >= 0) {
                runCatching { state.scrollToItem(i) }
                delay(150)
                if (runCatching { (if (i == 0 && firstReq != null) firstReq else lastReq).requestFocus() }.isSuccess) HomeFocus.pending = false
            }
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    val k = if (row.fit) fitScaleN(maxWidth.value - 8f, h * ratio(row.aspect), row.gap.toFloat(), row.perLine) else 1f
    val cg = if (row.fit) spreadGap(maxWidth.value - 8f - 0.5f, h * ratio(row.aspect), row.gap.toFloat(), row.perLine) else row.gap.toFloat()
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(cg.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
        items(programs, key = { it.kind.toString() + "|" + it.id }) { p ->
            val pk = p.kind.toString() + "|" + p.id
            ProgramTile(p, w * k, h * k, if (p === programs.first()) firstReq else if (HomeFocus.key == pk && HomeFocus.rowId == row.id) lastReq else null, row.auto, { onDlg(Dlg.RowD(row.id)) }, { HomeFocus.mark(row.id, pk) })
        }
    }
    }
}

@Composable
private fun ProgramTile(p: ChProgram, w: Float, h: Float, req: FocusRequester?, rowAuto: String, onLong: () -> Unit, onLaunch: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { androidx.compose.runtime.mutableStateOf(false) }
    if (menu) CardMenu(p.title, p.poster, p.pkg, p.intentUri, rowAuto == "mine:seen", onLong, { menu = false })
    val bmp by produceState<Bitmap?>(p.poster?.let { Pics.cached(it) }, p.poster) {
        if (value == null && p.poster != null) value = withContext(Dispatchers.IO) { Pics.load(ctx, p.poster) }
    }
    HomeBox(
        Modifier.size(w.dp, h.dp), RoundedCornerShape(12.dp), LocalTh.current.tile, focusReq = req, memKey = "p|" + p.kind + "|" + p.id,
        onClick = {
            onLaunch()
            CardStore.record(ctx, p.title, p.poster, p.pkg, p.intentUri)
            if (!Channels.launch(ctx, p)) Toast.makeText(ctx, ctx.getString(R.string.home_cant_open), Toast.LENGTH_SHORT).show()
        },
        onLong = { menu = true },
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 3.dp)) {
            Text(p.title, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ------------------------------------------------------------------ строка виджетов

@Composable
private fun WidgetsRow(row: HRow, tick: Int, refreshTick: Int, firstReq: FocusRequester?, onDlg: (Dlg?) -> Unit) {
    val t = LocalTh.current
    val ctx = LocalContext.current
    var refresh by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // на широком экране (ТВ, плотность мелкая) стандартная высота 120 dp слишком мала для крупных значков и подписей: берём 160 dp;
    // если владелица сама поменяла высоту (не 120), берётся её значение
    val wideScreen = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 900
    val rowH = if (wideScreen && row.size == 120) 160 else row.size
    Row(Modifier.fillMaxWidth().height(rowH.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(row.gap.dp)) {
        row.slots.forEachIndexed { i, w ->
            androidx.compose.runtime.key(i) {
                val wShape = RoundedCornerShape(w.radius.coerceIn(0, 60).dp)
                val wRing = if (w.borderC == 0L) t.accent else Color(w.borderC.toInt())
                HomeBox(
                    Modifier.weight(w.wt.coerceAtLeast(1).toFloat()).fillMaxHeight(), wShape, if (w.type == "appwidget" || w.type == WIDGET_EMPTY) Color.Transparent else t.tile, focusReq = if (i == 0) firstReq else null, memKey = row.id + "|w" + i,
                    ringColor = if (w.borderW > 0 && w.borderHover) wRing else null, ringWidth = if (w.borderHover) w.borderW else 0,
                    onClick = { widgetClick(ctx, w) { refresh++ } },
                    onLong = { onDlg(Dlg.WidgetD(row.id, i)) },
                ) {
                    // масштаб шрифта виджета: меняет только размер текста (sp), размеры в dp остаются
                    val d0 = androidx.compose.ui.platform.LocalDensity.current
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d0.density, d0.fontScale * w.fs.coerceIn(50, 250) / 100f),
                    ) {
                        Box(Modifier.fillMaxSize().then(if (w.borderW > 0 && !w.borderHover) Modifier.border(w.borderW.dp, wRing, wShape) else Modifier)) {
                            WidgetBody(w, tick, refreshTick + refresh)
                        }
                    }
                }
            }
        }
    }
}


// ------------------------------------------------------------------ перетаскивание плиток

/** Состояние перетаскивания плитки (зажать и двигать): плитка «летит» за указателем, при отпускании встаёт в строку под ним. */
internal object TileDrag {
    class Info(val row: HRow, val el: El, val scale: Float, val w: Float, val h: Float)
    var active by androidx.compose.runtime.mutableStateOf<Info?>(null)
    var pos by androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
    /** Прямоугольники строк значков и ручных плиток в корне экрана (обновляются самими плитками). */
    val rowRects = HashMap<String, androidx.compose.ui.geometry.Rect>()
    val tileRects = HashMap<String, Pair<String, androidx.compose.ui.geometry.Rect>>()

    /** Отпускание: найти строку под указателем и место в ней. */
    fun drop(id: String, p: androidx.compose.ui.geometry.Offset) {
        active = null
        val rowId = rowRects.entries.firstOrNull { it.value.contains(p) }?.key ?: return
        val order = HomeStore.row(rowId)?.items?.filter { it.id != id } ?: return
        var index = order.size
        for ((i, it) in order.withIndex()) {
            val r = tileRects[it.id]?.second ?: continue
            if (p.y < r.top || (p.y <= r.bottom && p.x < r.center.x)) { index = i; break }
        }
        HomeStore.moveItemTo(id, rowId, index)
    }
}

/** Плитка, летящая за указателем, и прокрутка страницы у верхнего и нижнего края. */
@Composable
internal fun TileDragOverlay(scroll: androidx.compose.foundation.ScrollState) {
    val d = TileDrag.active ?: return
    var h by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(d) {
        while (true) {
            delay(16)
            val y = TileDrag.pos.y
            if (y < 110f) scroll.dispatchRawDelta(-20f) else if (h > 0 && y > h - 110f) scroll.dispatchRawDelta(20f)
        }
    }
    Box(Modifier.fillMaxSize().onSizeChanged { h = it.height }) {
        Box(
            Modifier.offset { androidx.compose.ui.unit.IntOffset((TileDrag.pos.x - d.w / 2).roundToInt(), (TileDrag.pos.y - d.h / 2).roundToInt()) }
                .graphicsLayer { alpha = 0.92f; scaleX = 1.08f; scaleY = 1.08f },
        ) { HomeAppTile(d.row, d.el, false, null, 0f, Modifier, d.scale, {}, {}, { false }, {}) }
    }
}
