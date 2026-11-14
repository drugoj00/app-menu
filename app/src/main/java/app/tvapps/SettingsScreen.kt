@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.tvapps

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.Settings
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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

internal val LocalChipReg = androidx.compose.runtime.compositionLocalOf<SettingsChipReg?> { null }
internal val LocalSettingsScroll = androidx.compose.runtime.compositionLocalOf<androidx.compose.foundation.ScrollState?> { null }

@Composable
internal fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
    Box(
        modifier
            .then(if (reg != null) Modifier.focusRequester(req).onGloballyPositioned { ypos[0] = it.positionInRoot().y } else Modifier)
            .then(hm)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused && reg != null) reg.onFocus(text, ypos[0] + (scrollSt?.value ?: 0)) }
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) t.chipSel else if (hover) t.tileFocus else t.tile)
            .border(if (focused) 3.dp else 1.dp, if (focused) t.accent else t.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (isNarrow()) 11.dp else 16.dp, vertical = if (isNarrow()) 5.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = t.text, fontSize = btnSp(15), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
}

/** Пояснения пункта настроек, которые выводит [SectionHint] внутри [Section]: пункт сворачивает их под своё название. */
internal val LocalSectionHints = androidx.compose.runtime.compositionLocalOf<androidx.compose.runtime.snapshots.SnapshotStateList<String>?> { null }

/** Длинное пояснение пункта: ничего не рисует само, а передаёт текст названию пункта (раскрывается нажатием на название). */
@Composable
internal fun SectionHint(text: String) {
    val list = LocalSectionHints.current
    if (list == null) {
        Text(text, color = LocalTh.current.hint, fontSize = 12.sp)
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
private fun Section(title: String, content: @Composable () -> Unit) {
    val t = LocalTh.current
    val hints = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var open by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hints.isEmpty()) Text(title, color = t.sub, fontSize = 13.sp)
        else Row(
            Modifier.onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape(8.dp))
                .border(if (focused) 2.dp else 0.dp, if (focused) t.accent else Color.Transparent, RoundedCornerShape(8.dp))
                .clickable(role = androidx.compose.ui.semantics.Role.Button) { open = !open }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, color = t.sub, fontSize = 13.sp)
            Text(if (open) "▴" else "ⓘ", color = t.accent, fontSize = 14.sp)
        }
        if (open && hints.isNotEmpty()) Text(hints.joinToString("\n\n"), color = t.hint, fontSize = 12.sp)
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
            .border(if (focused) 3.dp else 1.dp, if (focused) t.accent else t.border, RoundedCornerShape(12.dp))
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
private fun GridNum(value: Int, caption: String, show: @Composable (Int) -> String, onPlus: () -> Unit, onMinus: () -> Unit, onReset: (() -> Unit)? = null) {
    val t = LocalTh.current
    val gate = remember { StepGate() }
    // шаг считает вызывающий по живому значению настройки (а не по значению с последнего кадра): быстрые нажатия подряд не теряются и не дают скачков
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GridBtn(true, Modifier.width(150.dp).height(GRID_BTN_H), gate) { onPlus() }
        var numFocused by remember { mutableStateOf(false) }
        Box(
            Modifier.width(150.dp).height(GRID_NUM_H).clip(RoundedCornerShape(12.dp)).background(t.chipSel)
                .then(if (onReset != null) Modifier.onFocusChanged { numFocused = it.isFocused }.border(if (numFocused) 3.dp else 0.dp, if (numFocused) t.accent else Color.Transparent, RoundedCornerShape(12.dp)).clickable { onReset() } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(show(value), color = t.text, fontSize = btnSp(17), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
        GridBtn(false, Modifier.width(150.dp).height(GRID_BTN_H), gate) { onMinus() }
        Text(caption, color = t.hint, fontSize = 12.sp)
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
 * под рядом — название выбранного цвета. [lightMark] — образцы, на которых галочка чёрная.
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

@Composable
fun SettingsScreen(apps: List<Entry>, onBack: () -> Unit, onFullscreenChanged: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // положение в настройках помнится: раздел, прокрутка и кнопка в фокусе (MenuMemory); при выходе из меню кнопкой «Назад» сбрасывается
    // окна внутри настроек («Скрытые приложения», «Исключения») не запоминают положение: открываются сверху; помнится только основной экран настроек
    MenuMemory.init(ctx)
    var sub by remember { mutableStateOf("main") }
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
    val anyDialog = adbDialog || adbNet || appsUpd || instLog || installFiles != null || browser || net || wifi || backups || menuBgDlg || bkpPick != null
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

    Column(Modifier.fillMaxSize().background(t.bg).padding(horizontal = 32.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Chip("← " + stringResource(R.string.back_plain), false, Modifier.focusRequester(first)) { if (sub == "main") onBack() else sub = "main" }
            Text(stringResource(when (sub) { "main" -> R.string.settings; "excl" -> R.string.mem_excl_title; else -> R.string.hidden_list }), fontSize = 24.sp, color = t.text)
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalChipReg provides chipReg, LocalSettingsScroll provides scroll) {
        Column(Modifier.fillMaxSize().verticalScrollbar(scroll).verticalScroll(scroll).padding(top = 8.dp, end = ScrollbarMath.RESERVE_DP.dp)) {
            if (sub == "main") {
                Section(stringResource(R.string.set_theme)) {
                    Options(
                        listOf(
                            "dark" to stringResource(R.string.theme_dark), "black" to stringResource(R.string.theme_black),
                            "light" to stringResource(R.string.theme_light), "auto" to stringResource(R.string.theme_auto),
                        ),
                        Cfg.theme,
                    ) { Cfg.changeTheme(it) }
                }
                Section(stringResource(R.string.set_accent)) {
                    val names = mapOf(
                        "teal" to R.string.accent_teal, "blue" to R.string.accent_blue, "orange" to R.string.accent_orange,
                        "purple" to R.string.accent_purple, "pink" to R.string.accent_pink, "green" to R.string.accent_green,
                    )
                    // одинаковые прямоугольники с цветами акцента (вместо кнопок со словами); название выбранного под рядом
                    val dark = t.dark
                    ColorDots(accentKeys.map { accentSwatch(it, dark) }, accentKeys.map { stringResource(names.getValue(it)) }, accentKeys.indexOf(Cfg.accent).coerceAtLeast(0), rect = true) { Cfg.changeAccent(accentKeys[it]) }
                }
                Section(stringResource(R.string.set_lang)) {
                    Options(
                        listOf("" to stringResource(R.string.lang_system), "ru" to "Русский", "uk" to "Українська", "en" to "English"),
                        Cfg.lang,
                    ) {
                        Cfg.changeLang(it)
                        // язык применяется при создании окна: пересоздаём его
                        (ctx as? android.app.Activity)?.recreate()
                    }
                }
                Section(stringResource(R.string.set_control)) {
                    SectionHint(stringResource(R.string.ctl_help))
                }
                Section(stringResource(R.string.set_ring)) {
                    val ringNames = listOf(R.string.ring_default, R.string.bd_c1, R.string.bd_c2, R.string.bd_c3, R.string.bd_c4, R.string.bd_c5, R.string.bd_c6, R.string.bd_c7, R.string.bd_c8, R.string.bd_c9, R.string.bd_c10)
                    val ringAccent = LocalTh.current.accent
                    ColorDots(Cfg.RING_COLORS.indices.map { if (it == 0) ringAccent else Color(Cfg.RING_COLORS[it].toInt()) }, ringNames.map { stringResource(it) }, Cfg.selRing, lightMark = setOf(1, 5)) { Cfg.changeSelRing(it) }
                }
                Section(stringResource(R.string.set_tilebg)) {
                    Options(listOf(true to stringResource(R.string.tilebg_clear), false to stringResource(R.string.tilebg_card)), Cfg.transparentTiles) { Cfg.changeTransparentTiles(it) }
                }
                Section(stringResource(R.string.set_plates)) {
                    Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.plates) { Cfg.changePlates(it) }
                }
                Section(stringResource(R.string.bg_menu_title)) {
                    Chip(stringResource(R.string.bg_menu_btn), false) { menuBgDlg = true }
                }
                Section(stringResource(R.string.set_view)) {
                    Options(listOf(false to stringResource(R.string.view_grid), true to stringResource(R.string.view_list)), Cfg.listMode) { Cfg.changeListMode(it) }
                }
                Section(stringResource(R.string.set_grid)) {
                    // три счётчика: строки × столбцы и масштаб значков
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!Cfg.listMode) {
                            GridNum(
                                Cfg.gridRows, stringResource(R.string.grid_rows), { if (it == 0) stringResource(R.string.cols_auto) else it.toString() },
                                onPlus = { Cfg.changeGridRows(Steps.autoStep(Cfg.gridRows, +1, 12, Cfg.autoRows)) },
                                onMinus = { Cfg.changeGridRows(Steps.autoStep(Cfg.gridRows, -1, 12, Cfg.autoRows)) },
                                onReset = { Cfg.changeGridRows(0) },
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
                                onReset = { Cfg.changeColumns(0) },
                            )
                            Spacer(Modifier.width(28.dp))
                        }
                        GridNum(
                            Cfg.iconScale.coerceIn(50, 200), stringResource(R.string.grid_scale), { "$it%" },
                            onPlus = { Cfg.changeIconScale(Steps.rangeStep(Cfg.iconScale, +1, 50, 200, 10)) },
                            onMinus = { Cfg.changeIconScale(Steps.rangeStep(Cfg.iconScale, -1, 50, 200, 10)) },
                            onReset = { Cfg.changeIconScale(100) },
                        )
                    }
                }
                Section(stringResource(R.string.set_iconpack)) {
                    val packs = remember { IconPacks.installed(ctx) }
                    Options(listOf("" to stringResource(R.string.iconpack_native)) + packs, Cfg.iconPack) { Cfg.changeIconPack(it) }
                    if (packs.isEmpty()) Text(stringResource(R.string.iconpack_none), color = t.hint, fontSize = 12.sp)
                    SectionHint(stringResource(R.string.import_hint))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(stringResource(R.string.import_tree), false) {
                            runCatching { pickTree.launch(null) }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.icon_no_picker), Toast.LENGTH_LONG).show() }
                        }
                        Chip(stringResource(R.string.import_zip), false) {
                            runCatching { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
                                .onFailure { Toast.makeText(ctx, ctx.getString(R.string.icon_no_picker), Toast.LENGTH_LONG).show() }
                        }
                    }
                    Chip(stringResource(R.string.icons_reset_all), false) { IconPacks.removeAllOwn(ctx); Cfg.bumpIcons() }
                    Chip(stringResource(R.string.icons_cache_clear), false) {
                        AppCache.clearIcons(ctx); Cfg.bumpIcons()
                        Toast.makeText(ctx, ctx.getString(R.string.icons_cache_cleared), Toast.LENGTH_SHORT).show()
                    }
                }
                Section(stringResource(R.string.set_icon)) {
                    Options(listOf(3 to stringResource(R.string.size_xs), 0 to stringResource(R.string.size_s), 1 to stringResource(R.string.size_m), 2 to stringResource(R.string.size_l)), Cfg.iconSize) { Cfg.changeIconSize(it) }
                }
                Section(stringResource(R.string.set_gap)) {
                    Options(listOf(0 to stringResource(R.string.gap_tight), 1 to stringResource(R.string.gap_normal), 2 to stringResource(R.string.gap_airy)), Cfg.gridGap) { Cfg.changeGridGap(it) }
                }
                Section(stringResource(R.string.set_labels)) {
                    Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.showLabels) { Cfg.changeShowLabels(it) }
                }
                Section(stringResource(R.string.set_remember)) {
                    Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.rememberPos) { Cfg.changeRememberPos(it) }
                    SectionHint(stringResource(R.string.remember_hint))
                }
                Section(stringResource(R.string.set_dense)) {
                    Options(
                        listOf(0 to stringResource(R.string.lite_auto), 1 to stringResource(R.string.dense_on), 2 to stringResource(R.string.dense_off)), DenseGrid.mode,
                    ) { DenseGrid.change(ctx, it) }
                    SectionHint(stringResource(R.string.dense_hint))
                }
                Section(stringResource(R.string.set_lite)) {
                    Options(
                        listOf(0 to stringResource(R.string.lite_auto), 1 to stringResource(R.string.on), 2 to stringResource(R.string.off)), Lite.mode,
                    ) { Lite.change(ctx, it) }
                    SectionHint(stringResource(R.string.lite_hint))
                }
                Section(stringResource(R.string.set_sort)) {
                    Options(
                        listOf(
                            "name" to stringResource(R.string.sort_name), "name_desc" to stringResource(R.string.sort_name_desc),
                            "new" to stringResource(R.string.sort_new), "old" to stringResource(R.string.sort_old),
                            "freq" to stringResource(R.string.sort_freq), "custom" to stringResource(R.string.sort_custom),
                        ),
                        Cfg.sort,
                    ) { Cfg.changeSort(it) }
                }
                Section(stringResource(R.string.desks_title)) {
                    Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Desks.on) { Desks.enable(it) }
                }
                Section(stringResource(R.string.set_mem)) {
                    FoldItem(stringResource(R.string.mem_auto), stringResource(R.string.mem_auto_hint)) {
                        Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.memAuto) { Cfg.changeMemAuto(it) }
                    }
                    FoldItem(stringResource(R.string.mem_keep_fav), null) {
                        Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.memKeepFav) { Cfg.changeMemKeepFav(it) }
                    }
                    FoldItem(stringResource(R.string.mem_excl_title), stringResource(R.string.mem_excl_hint)) {
                        Chip(stringResource(R.string.mem_excl_open) + " (" + Cfg.memExcl.size + ")", false) { sub = "excl" }
                    }
                }
                Section(stringResource(R.string.boot_title)) {
                    SectionHint(stringResource(R.string.boot_hint))
                    Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.bootWarm) { Cfg.changeBootWarm(it) }
                }
                Section(stringResource(R.string.set_fullscreen)) {
                    val ctxSb = androidx.compose.ui.platform.LocalContext.current
                    var sbS by remember { mutableStateOf(!SysBars.hidden(ctxSb, false)) }
                    var sbN by remember { mutableStateOf(!SysBars.hidden(ctxSb, true)) }
                    val onOff = listOf(true to stringResource(R.string.on), false to stringResource(R.string.off))
                    Text(stringResource(R.string.sb_status), color = t.sub, fontSize = 13.sp)
                    Options(onOff, sbS) { sbS = it; SysBars.set(ctxSb, false, if (it) 1 else 2); onFullscreenChanged() }
                    Text(stringResource(R.string.sb_nav), color = t.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    Options(onOff, sbN) { sbN = it; SysBars.set(ctxSb, true, if (it) 1 else 2); onFullscreenChanged() }
                }
                Section(stringResource(R.string.set_hidden)) {
                    Options(listOf(false to stringResource(R.string.hidden_hide), true to stringResource(R.string.hidden_show)), Cfg.showHidden) { Cfg.changeShowHidden(it) }
                    Chip(stringResource(R.string.hidden_list) + " (" + Cfg.hidden.size + ")", false) { sub = "hidden" }
                }
                Section(stringResource(R.string.set_install)) {
                    SectionHint(stringResource(R.string.install_hint))
                    Chip(stringResource(R.string.fb_open), true) { browser = true }
                    Chip(stringResource(R.string.wifi_open), true) { wifi = true }
                    Chip(stringResource(R.string.install_log_btn), false) { instLog = true }
                    Chip(stringResource(R.string.net_open), true) { net = true }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(stringResource(R.string.install_tree), false) {
                            runCatching { pickApkTree.launch(null) }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.icon_no_picker), Toast.LENGTH_LONG).show() }
                        }
                        Chip(stringResource(R.string.install_zip), false) {
                            runCatching { pickApkZip.launch(arrayOf("*/*")) }
                                .onFailure { Toast.makeText(ctx, ctx.getString(R.string.icon_no_picker), Toast.LENGTH_LONG).show() }
                        }
                    }
                }
                Section(stringResource(R.string.set_return)) {
                    Options(listOf("menu" to stringResource(R.string.return_menu), "home" to stringResource(R.string.return_home)), Cfg.returnTo) { Cfg.changeReturnTo(it) }
                }
                Section(stringResource(R.string.home_module_title)) {
                    SectionHint(stringResource(R.string.home_module_hint))
                    Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.homeModule) { v ->
                        if (v) HomeModule.enable(ctx)
                        else scope.launch {
                            val r = HomeModule.disable(ctx)
                            Toast.makeText(ctx, ctx.getString(if (r.isEmpty()) R.string.home_module_off_ok else if (r == HomeModule.NEED_MENU) R.string.home_module_need_menu else R.string.home_module_off_fail), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                Section(stringResource(R.string.home_auto_add_title)) {
                    SectionHint(stringResource(R.string.home_auto_add_hint))
                    Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.homeAutoAdd) { Cfg.changeHomeAutoAdd(it) }
                }
                // оформление верхней строки и выделения на «Доме» (по умолчанию вид прежний; выбор применяется сразу; пояснения свёрнуты под названием)
                Section(stringResource(R.string.top_outline)) {
                    SectionHint(stringResource(R.string.top_outline_hint))
                    Options(listOf("auto" to stringResource(R.string.top_o_auto), "dark" to stringResource(R.string.top_o_dark), "white" to stringResource(R.string.top_o_white), "none" to stringResource(R.string.top_o_none)), Cfg.statusOutline) { Cfg.changeStatusOutline(it) }
                }
                Section(stringResource(R.string.top_fill)) {
                    SectionHint(stringResource(R.string.top_fill_hint))
                    Options(listOf("solid" to stringResource(R.string.top_f_solid), "glass" to stringResource(R.string.top_f_glass), "none" to stringResource(R.string.top_f_none)), Cfg.barFill) { Cfg.changeBarFill(it) }
                }
                Section(stringResource(R.string.top_shape)) {
                    SectionHint(stringResource(R.string.top_shape_hint))
                    Options(listOf("normal" to stringResource(R.string.top_s_normal), "round" to stringResource(R.string.top_s_round), "square" to stringResource(R.string.top_s_square)), Cfg.barShape) { Cfg.changeBarShape(it) }
                }
                Section(stringResource(R.string.top_title_btn)) {
                    SectionHint(stringResource(R.string.top_title_hint))
                    Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.barTitle) { Cfg.changeBarTitle(it) }
                }
                Section(stringResource(R.string.sel_glow)) {
                    SectionHint(stringResource(R.string.sel_glow_hint))
                    Options(listOf("none" to stringResource(R.string.glow_none), "weak" to stringResource(R.string.glow_weak), "strong" to stringResource(R.string.glow_strong)), Cfg.selGlow) { Cfg.changeSelGlow(it) }
                }
                Section(stringResource(R.string.all_panel)) {
                    SectionHint(stringResource(R.string.all_panel_hint))
                    Options(listOf(false to stringResource(R.string.off), true to stringResource(R.string.on)), Cfg.homeAllPanel) { Cfg.changeHomeAllPanel(it) }
                }
                if (Cfg.homeAllPanel) Section(stringResource(R.string.all_panel_glass)) {
                    SectionHint(stringResource(R.string.all_panel_glass_hint))
                    Options(listOf("light" to stringResource(R.string.glass_light), "mid" to stringResource(R.string.glass_mid), "dense" to stringResource(R.string.glass_dense)), Cfg.homeAllGlass) { Cfg.changeHomeAllGlass(it) }
                }
                // выключатель меню виден всегда (раньше исчезал при выключенном «Доме»); оба модуля сразу выключить нельзя
                Section(stringResource(R.string.menu_module_title)) {
                    SectionHint(stringResource(R.string.menu_module_hint))
                    Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.menuModule) { v ->
                        if (!v && !Cfg.homeModule) Toast.makeText(ctx, ctx.getString(R.string.menu_module_need_home), Toast.LENGTH_LONG).show()
                        else Cfg.changeMenuModule(v)
                    }
                }
                Section(stringResource(R.string.set_home)) {
                    SectionHint(stringResource(R.string.set_home_hint))
                    SectionHint(stringResource(R.string.native_home_hint))
                    // список родных экранов с названиями собирается в фоне: loadLabel обращается к ресурсам и Binder, в главном потоке это до 150 мс (журнал магнитолы)
                    val natives by androidx.compose.runtime.produceState(emptyList<Pair<String, String>>()) {
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { nativeHomes(ctx).map { it.activityInfo.packageName to it.loadLabel(ctx.packageManager).toString() } }
                    }
                    if (natives.isNotEmpty()) Options(listOf("" to stringResource(R.string.native_auto)) + natives, Cfg.nativeHome) { Cfg.changeNativeHome(it) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(stringResource(R.string.set_home_btn), false) {
                            scope.launch {
                                // запоминаем нынешний главный экран, чтобы второе «Домой» и «Выключить» вели на него
                                if (Cfg.nativeHome.isEmpty()) {
                                    val cur = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
                                    if (cur != null && cur != ctx.packageName && cur != "android") Cfg.changeNativeHome(cur)
                                }
                                setHomeMode(ctx, true)
                                if (Adb.isConnected) {
                                    val okSet = HomeControl.setHomeCmd("${ctx.packageName}/app.tvapps.HomeAlias") || HomeControl.currentHome(ctx) == ctx.packageName
                                    Toast.makeText(ctx, ctx.getString(if (okSet) R.string.home_set_ok else R.string.failed), Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(ctx, ctx.getString(R.string.home_choose_always), Toast.LENGTH_LONG).show()
                                    runCatching { ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                }
                            }
                        }
                        Chip(stringResource(R.string.set_home_off), false) {
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
                }
                Section(stringResource(R.string.home_keep_title)) {
                    Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.rememberHome) { Cfg.changeRememberHome(it) }
                    SectionHint(stringResource(R.string.home_remember_hint))
                    Chip(stringResource(R.string.home_now), false) {
                        scope.launch {
                            val ok = HomeControl.makeDefaultNow(ctx)
                            Toast.makeText(ctx, ctx.getString(if (ok) R.string.home_now_ok else R.string.home_now_fail), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                Section(stringResource(R.string.set_backups)) {
                    Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.backupOnDelete) { Cfg.changeBackupOnDelete(it) }
                    SectionHint(stringResource(R.string.backups_hint))
                    Chip(stringResource(R.string.backups_open), false) { backups = true }
                }
                Section(stringResource(R.string.backup_title)) {
                    SectionHint(stringResource(R.string.backup_hint))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(stringResource(R.string.backup_save), false) {
                            runCatching { saveBackup.launch(Backup.fileName(ctx)) }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.icon_no_picker), Toast.LENGTH_LONG).show() }
                        }
                        Chip(stringResource(R.string.backup_load), false) { bkpPick = "file" }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(stringResource(R.string.backup_here), false) {
                            Backup.autoSave(ctx)
                            Toast.makeText(ctx, ctx.getString(R.string.backup_here_done, Backup.autoFile(ctx)?.absolutePath.orEmpty()), Toast.LENGTH_LONG).show()
                        }
                        Chip(stringResource(R.string.backup_restore_here), false) { bkpPick = "app" }
                    }
                }
                Section(stringResource(R.string.adb_title)) {
                    Text(stringResource(Conn.statusRes), color = if (Conn.connected) t.accent else t.sub, fontSize = 14.sp)
                    Chip(stringResource(R.string.adb_connect_btn), false) { adbDialog = true }
                    Chip(stringResource(R.string.adbn_btn), false) { adbNet = true }
                    Text(stringResource(R.string.root_use_title), color = t.sub, fontSize = 13.sp)
                    Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.useRoot) {
                        Cfg.changeUseRoot(it)
                        if (!it) { Adb.disableRoot(); Conn.refresh() }
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
                }
                Section(stringResource(R.string.about_title)) {
                    Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), color = t.sub, fontSize = 14.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(stringResource(R.string.about_site), false) { openUrl(ctx, "https://drugoj00.github.io/phone-in-window-with-sound/app-menu.html") }
                        Chip(stringResource(R.string.about_tg), false) { openUrl(ctx, "https://t.me/PhoneInWindowSound") }
                        Chip(stringResource(R.string.about_gh), false) { openUrl(ctx, "https://github.com/drugoj00/app-menu") }
                        Chip(stringResource(R.string.about_all), false) { openUrl(ctx, "https://drugoj00.github.io/") }
                        Chip(stringResource(R.string.about_check_upd), false) { SelfUpd.checkNow(ctx.applicationContext, scope) }
                        Chip(stringResource(R.string.about_apps_upd), false) { appsUpd = true }
                        Chip(stringResource(R.string.self_upd_auto) + " · " + stringResource(if (Cfg.selfUpd) R.string.on else R.string.off), Cfg.selfUpd) { Cfg.changeSelfUpd(!Cfg.selfUpd) }
                    }
                    Text(stringResource(R.string.about_support), color = t.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(stringResource(R.string.about_card), false) { openUrl(ctx, "https://secure.wayforpay.com/button/bba92579a4647") }
                        Chip(stringResource(R.string.about_usdt), false) { copyText(ctx, "TXk32CLkwJryTqhmkw6urcM5CacJgPsSpJ") }
                    }
                }
                Spacer(Modifier.size(24.dp))
            } else if (sub == "excl") {
                ExcludeList(apps)
            } else {
                HiddenList(apps)
            }
        }
        }
    }
    if (menuBgDlg) BgDialogHost(true) { menuBgDlg = false }
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
                .border(if (focused) 2.dp else 0.dp, if (focused) t.accent else Color.Transparent, RoundedCornerShape(8.dp))
                .then(if (hint != null) Modifier.clickable { open = !open } else Modifier)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, color = t.text, fontSize = 15.sp)
            if (hint != null) Text(if (open) "▴" else "▾", color = t.accent, fontSize = 15.sp)
        }
        if (open && hint != null) Text(hint, color = t.hint, fontSize = 12.sp)
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
                .background(if (focused) t.tileFocus else t.tile)
                .border(if (focused) 3.dp else 0.dp, if (focused) t.accent else Color.Transparent, RoundedCornerShape(12.dp))
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
            Switch(on, null)
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
                .background(if (focused) t.tileFocus else t.tile)
                .border(if (focused) 3.dp else 0.dp, if (focused) t.accent else Color.Transparent, RoundedCornerShape(12.dp))
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
            Switch(h, null)
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
