package app.tvapps

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.launch

/**
 * Док у нижнего края «Дома» (16.11.2026, просьба Евгения и владелицы): тонкая постоянная полоса избранных приложений по образцу док-панели Pulsar
 * (AppBar.kt): значок 30 dp с подписью 10 sp в ячейке 62 dp, фокус заливается акцентом, фон тёмный или светлый с настраиваемой прозрачностью,
 * прокрутка вбок, расположение слева, по центру или справа. Кнопка «вниз» на доке открывает панель «Все приложения» (или меню, если панель выключена).
 * По умолчанию выключен.
 */
object HomeDock {
    val rev = mutableIntStateOf(0)
    /** Растёт, когда нужно перевести фокус на док (клавиша фокуса). */
    val focusTick = mutableIntStateOf(0)
    /** Какие значки дока сейчас в фокусе (нужно кнопке «Назад»: из дока она возвращает фокус на «Дом»). */
    val focusedPkgs = androidx.compose.runtime.mutableStateListOf<String>()
    val hasFocus: Boolean get() = focusedPkgs.isNotEmpty()
    /** Клавиша фокуса на док: 0 «вниз» (обычная навигация), 1 «Меню», 2 «Инфо», 3 своя (код в dock_key_code). */
    fun keyMode(c: Context) = sp(c).getInt("dock_key", 0)
    fun setKeyMode(c: Context, v: Int) { sp(c).edit().putInt("dock_key", v).apply(); rev.intValue++ }
    fun keyCode(c: Context) = sp(c).getInt("dock_key_code", -1)
    fun setKeyCode(c: Context, v: Int) { sp(c).edit().putInt("dock_key_code", v).apply(); rev.intValue++ }
    /** Значок, который сейчас двигают («Переместить»); клавиши идут через dispatchKeyEvent «Дома», фокус на значке не нужен. */
    val moving = mutableStateOf<String?>(null)
    fun moveBy(c: Context, pkg: String, d: Int) {
        val l = list(c).toMutableList(); val i = l.indexOf(pkg); val j = i + d
        if (i < 0 || j < 0 || j >= l.size) return
        java.util.Collections.swap(l, i, j); setList(c, l)
    }
    /** Клавиши режима «Переместить»: влево и вправо двигают значок, ОК и «Назад» заканчивают; остальные стрелки глотаются. */
    fun moveKey(c: Context, ev: android.view.KeyEvent): Boolean {
        val m = moving.value ?: return false
        if (ev.action != android.view.KeyEvent.ACTION_DOWN) return ev.keyCode in listOf(21, 22, 19, 20, 23, 66, 4)
        when (ev.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> moveBy(c, m, -1)
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> moveBy(c, m, 1)
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_ESCAPE -> moving.value = null
            android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {}
            else -> return false
        }
        return true
    }

    /** Вызывается из dispatchKeyEvent «Дома»: клавиша фокуса на док (если док включён). true: клавишу взяли. */
    fun onKey(ctx: Context, ev: android.view.KeyEvent): Boolean {
        if (ev.action != android.view.KeyEvent.ACTION_DOWN || ev.repeatCount != 0 || !on(ctx)) return false
        val want = when (keyMode(ctx)) { 1 -> android.view.KeyEvent.KEYCODE_MENU; 2 -> android.view.KeyEvent.KEYCODE_INFO; 3 -> keyCode(ctx); else -> -1 }
        if (want < 0 || ev.keyCode != want) return false
        focusTick.intValue++
        return true
    }
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("tvapps", Context.MODE_PRIVATE)
    fun on(c: Context) = sp(c).getBoolean("dock_on", false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean("dock_on", v).apply(); rev.intValue++ }
    fun list(c: Context): List<String> = (sp(c).getString("dock_apps", "") ?: "").split(',').filter { it.isNotBlank() }
    fun setList(c: Context, l: List<String>) { sp(c).edit().putString("dock_apps", l.joinToString(",")).apply(); rev.intValue++ }
    fun count(c: Context) = sp(c).getInt("dock_count", 8)
    fun setCount(c: Context, v: Int) { sp(c).edit().putInt("dock_count", v.coerceIn(6, 12)).apply(); rev.intValue++ }
    fun icon(c: Context) = sp(c).getInt("dock_icon", 36)
    fun setIcon(c: Context, v: Int) { sp(c).edit().putInt("dock_icon", v.coerceIn(24, 72)).apply(); rev.intValue++ }
    fun transp(c: Context) = sp(c).getInt("dock_transp", 10)
    fun setTransp(c: Context, v: Int) { sp(c).edit().putInt("dock_transp", v.coerceIn(0, 100)).apply(); rev.intValue++ }
    fun bg(c: Context) = sp(c).getInt("dock_bg", 0)
    fun setBg(c: Context, v: Int) { sp(c).edit().putInt("dock_bg", v).apply(); rev.intValue++ }
    fun labels(c: Context) = sp(c).getBoolean("dock_labels", true)
    fun setLabels(c: Context, v: Boolean) { sp(c).edit().putBoolean("dock_labels", v).apply(); rev.intValue++ }
    fun bottom(c: Context) = sp(c).getInt("dock_bottom", 6)
    fun setBottom(c: Context, v: Int) { sp(c).edit().putInt("dock_bottom", v.coerceIn(0, 60)).apply(); rev.intValue++ }
    fun btn(c: Context) = sp(c).getBoolean("dock_btn", true)
    fun setBtn(c: Context, v: Boolean) { sp(c).edit().putBoolean("dock_btn", v).apply(); rev.intValue++ }
    fun auto(c: Context) = sp(c).getBoolean("dock_auto", false)
    fun setAuto(c: Context, v: Boolean) { sp(c).edit().putBoolean("dock_auto", v).apply(); rev.intValue++ }
    fun sort(c: Context) = sp(c).getInt("dock_sort", 0)
    fun setSort(c: Context, v: Int) { sp(c).edit().putInt("dock_sort", v).apply(); rev.intValue++ }
    fun radius(c: Context) = sp(c).getInt("dock_radius", 0)
    fun setRadius(c: Context, v: Int) { sp(c).edit().putInt("dock_radius", v.coerceIn(0, 30)).apply(); rev.intValue++ }
    fun recents(c: Context) = sp(c).getBoolean("dock_recents", false)
    fun setRecents(c: Context, v: Boolean) { sp(c).edit().putBoolean("dock_recents", v).apply(); rev.intValue++ }
    fun recentN(c: Context) = sp(c).getInt("dock_recent_n", 3)
    fun setRecentN(c: Context, v: Int) { sp(c).edit().putInt("dock_recent_n", v.coerceIn(1, 8)).apply(); rev.intValue++ }
    /** Недавно запущенные приложения (не из основной секции дока): отдельная секция после разделителя. */
    fun recentApps(c: Context, all: List<Entry>, exclude: Set<String>): List<Entry> =
        all.filter { !it.system && !Cfg.isHidden(it.pkg) && it.pkg !in exclude && Cfg.lastLaunch(it.pkg) > 0 }.sortedByDescending { Cfg.lastLaunch(it.pkg) }.take(recentN(c))
    fun align(c: Context) = sp(c).getInt("dock_align", 1)
    fun setAlign(c: Context, v: Int) { sp(c).edit().putInt("dock_align", v).apply(); rev.intValue++ }

    /** Приложения дока по порядку: выбор пользователя, а если он пуст, то «Избранное»; не больше заданного числа. */
    fun shown(c: Context, all0: List<Entry>): List<Entry> {
        // скрытые владельцем приложения в доке не показываются нигде (ни при выборе, ни при пустом списке, ни при автодобавлении)
        val all = all0.filter { !Cfg.isHidden(it.pkg) }
        val byPkg = all.associateBy { it.pkg }
        val src = list(c).ifEmpty { Cfg.favorites }.ifEmpty { all.filter { !it.system }.sortedByDescending { Cfg.launches(it.pkg) }.map { it.pkg } }
        var res = src.mapNotNull { byPkg[it] }
        // автодобавление: часто запускаемые приложения сами попадают в док (если выключено, только выбранные)
        if (auto(c)) {
            val have = res.map { it.pkg }.toSet()
            res = res + all.filter { !it.system && it.pkg !in have && Cfg.launches(it.pkg) > 0 }.sortedByDescending { Cfg.launches(it.pkg) }
        }
        res = when (sort(c)) {
            1 -> res.sortedBy { it.label.lowercase() }
            2 -> res.sortedByDescending { Cfg.launches(it.pkg) }
            3 -> res.sortedByDescending { Cfg.lastLaunch(it.pkg) }
            else -> res
        }
        return res.take(count(c))
    }

    /** Высота дока в dp (чтобы строки «Дома» не уезжали под него). */
    fun heightDp(c: Context): Int = icon(c) + (if (labels(c)) 20 else 0) + 12 + bottom(c)
}

/** Док поверх «Дома» у нижнего края. Ставится после содержимого экрана. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeDockHost() {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val rv = HomeDock.rev.intValue
    if (!HomeDock.on(ctx)) return
    val t = LocalTh.current
    // список приложений «Дома» не является состоянием Compose: перечитываем его, пока экран открыт (иначе док появлялся только после переключения)
    var all by remember { mutableStateOf(AppsMem.list) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(600); if (AppsMem.list !== all) all = AppsMem.list } }
    val items = HomeDock.shown(ctx, all)
    if (items.isEmpty()) return
    val rec = if (HomeDock.recents(ctx)) HomeDock.recentApps(ctx, all, items.map { it.pkg }.toSet()) else emptyList()
    val light = when (HomeDock.bg(ctx)) { 1 -> true; 2 -> !t.dark; else -> false }
    val alpha = (100 - HomeDock.transp(ctx)).coerceIn(0, 100) / 100f
    val barBg = (if (light) Color(0xFFF5F5F5) else Color(0xFF101418)).copy(alpha = alpha)
    val txt = if (light) Color(0xFF111111) else Color.White
    val iconDp = HomeDock.icon(ctx)
    val showLabels = HomeDock.labels(ctx)
    val align = when (HomeDock.align(ctx)) { 0 -> Alignment.CenterStart; 2 -> Alignment.CenterEnd; else -> Alignment.Center }
    val sel = androidx.compose.runtime.rememberCoroutineScope()
    val cellPx = with(LocalDensity.current) { (iconDp + 26).dp.toPx() }
    var menuFor by remember { mutableStateOf<Entry?>(null) }
    val firstReq = remember { androidx.compose.ui.focus.FocusRequester() }
    val cellReqs = remember { mutableMapOf<String, androidx.compose.ui.focus.FocusRequester>() }
    androidx.compose.runtime.LaunchedEffect(HomeDock.focusTick.intValue) { if (HomeDock.focusTick.intValue > 0) { runCatching { firstReq.requestFocus() } } }
    // порядок хранится явно, как только его меняют (пустой список означает «Избранное»)
    fun materialize(): MutableList<String> = items.map { it.pkg }.toMutableList()
    val openAll = {
        if (Cfg.homeAllPanel) AllPanel.search("")
        else runCatching { ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        Unit
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.fillMaxWidth().padding(bottom = HomeDock.bottom(ctx).dp).clip(RoundedCornerShape(HomeDock.radius(ctx).dp)).background(barBg), contentAlignment = align) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                @Composable
                fun Cell(e: Entry, idx: Int, movable: Boolean) {
                    var focused by remember { mutableStateOf(false) }
                    val cellReq = remember { androidx.compose.ui.focus.FocusRequester() }
                    cellReqs[e.pkg] = cellReq
                    androidx.compose.runtime.DisposableEffect(e.pkg) { onDispose { HomeDock.focusedPkgs.remove(e.pkg) } }
                    val bmp = remember(e.pkg) { e.icon.toBitmap(96, 96).asImageBitmap() }
                    var lpAt by remember { mutableStateOf(0L) }          // момент долгого касания: отпускание после него не запускает приложение
                    var longKey by remember { mutableStateOf(false) }    // долгое ОК на пульте уже открыло меню
                    Column(
                        Modifier.padding(horizontal = 1.dp).width((iconDp + 26).dp)
                            .then(if (idx == 0) Modifier.focusRequester(firstReq) else Modifier)
                            .focusRequester(cellReq)
                            .onFocusChanged { focused = it.isFocused; if (it.isFocused) { if (e.pkg !in HomeDock.focusedPkgs) HomeDock.focusedPkgs.add(e.pkg) } else HomeDock.focusedPkgs.remove(e.pkg) }
                            .onPreviewKeyEvent { ev ->
                                val ok = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                                if (ok && ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount >= 1 && !longKey) { longKey = true; menuFor = e; return@onPreviewKeyEvent true }
                                if (ok && ev.type == KeyEventType.KeyUp && longKey) { longKey = false; return@onPreviewKeyEvent true }
                                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                // «вниз» с дока: остальные приложения в стекле, фокус центра освобождается
                                if (ev.key == Key.DirectionDown) { openAll(); true } else false
                            }
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (HomeDock.moving.value == e.pkg) t.accent.copy(alpha = 0.55f) else if (focused) t.accent else Color.Transparent)
                            .then(if (HomeDock.moving.value == e.pkg) Modifier.border(3.dp, t.accent, RoundedCornerShape(10.dp)) else Modifier)
                            .pointerInput(e.pkg, movable) {
                                // долгое касание, затем тянуть влево или вправо: значок переезжает на соседнее место; без движения: меню значка
                                var acc = 0f; var moved = false
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        lpAt = System.currentTimeMillis(); acc = 0f; moved = false
                                        if (movable) { HomeDock.setSort(ctx, 0); HomeDock.setList(ctx, materialize()) }
                                    },
                                    onDrag = { change, d ->
                                        change.consume()
                                        if (!movable) return@detectDragGesturesAfterLongPress
                                        acc += d.x
                                        if (kotlin.math.abs(acc) > cellPx * 0.6f) {
                                            moved = true; HomeDock.moving.value = e.pkg
                                            HomeDock.moveBy(ctx, e.pkg, if (acc > 0) 1 else -1); acc = 0f
                                        }
                                    },
                                    onDragEnd = { if (!moved) menuFor = e; HomeDock.moving.value = null },
                                    onDragCancel = { HomeDock.moving.value = null },
                                )
                            }
                            .combinedClickable(
                                onClick = {
                                    if (HomeDock.moving.value == e.pkg) HomeDock.moving.value = null
                                    else if (System.currentTimeMillis() - lpAt > 900)
                                        ctx.packageManager.getLaunchIntentForPackage(e.pkg)?.let { Cfg.countLaunch(e.pkg); runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                                },
                            )
                            .padding(horizontal = 4.dp, vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(bmp, null, Modifier.size(iconDp.dp))
                        if (showLabels) Text(e.label, color = if (focused) Color.White else txt, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
                items.forEachIndexed { idx, e -> androidx.compose.runtime.key("d" + e.pkg) { Cell(e, idx, true) } }
                if (rec.isNotEmpty()) {
                    // разделитель между основной секцией и недавними
                    Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height((iconDp + 8).dp).background(txt.copy(alpha = 0.3f)))
                    rec.forEach { e -> androidx.compose.runtime.key("r" + e.pkg) { Cell(e, -1, false) } }
                }
            }
        }
    }
    menuFor?.let { e ->
        val close = { menuFor = null }
        val fav = Cfg.isFavorite(e.pkg)
        ListDialog(
            e.label,
            listOf(
                Opt(androidx.compose.ui.res.stringResource(R.string.dock_m_move)) { HomeDock.setSort(ctx, 0); HomeDock.setList(ctx, materialize()); HomeDock.moving.value = e.pkg; close() },
                Opt(androidx.compose.ui.res.stringResource(R.string.dock_m_remove)) { val l = materialize(); l.remove(e.pkg); HomeDock.setList(ctx, l.ifEmpty { listOf() }); close() },
                Opt(androidx.compose.ui.res.stringResource(if (fav) R.string.dock_m_unfav else R.string.dock_m_fav)) { Cfg.toggleFavorite(e.pkg); close() },
                Opt(androidx.compose.ui.res.stringResource(R.string.close)) { close() },
            ),
            close,
        )
    }
}
