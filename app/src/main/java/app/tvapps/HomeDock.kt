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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
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
    /** 19.1: запрос меню значка в доке по клавише MENU/INFO (ловится в окне, независимо от фокуса Compose): пакет значка с фокусом. */
    val menuReq = androidx.compose.runtime.mutableStateOf<String?>(null)
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
        if (ev.action != android.view.KeyEvent.ACTION_DOWN || ev.repeatCount != 0) return false
        val want = when (keyMode(ctx)) { 1 -> android.view.KeyEvent.KEYCODE_MENU; 2 -> android.view.KeyEvent.KEYCODE_INFO; 3 -> keyCode(ctx); else -> -1 }
        if (want < 0 || ev.keyCode != want) return false
        if (!on(ctx)) {
            // режим «Закрывать после запуска»: скрытый док возвращается этой же клавишей
            if (!closeAfter(ctx)) return false
            setOn(ctx, true); focusTick.intValue++
            return true
        }
        // повторное нажатие клавиши, пока фокус в доке, закрывает док
        if (hasFocus) { focusedPkgs.clear(); setOn(ctx, false); return true }
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
    /** 17.2 (просьба Евгения 24.11.2026): вид плиток дока: 0 значки (как раньше), 1 баннеры приложений (как на «Доме»). */
    fun style(c: Context) = sp(c).getInt("dock_style", 0)
    fun setStyle(c: Context, v: Int) { sp(c).edit().putInt("dock_style", v).apply(); rev.intValue++ }
    fun banners(c: Context) = style(c) == 1
    /** Сколько баннеров помещается в полосе одновременно (5-8); остальные прокручиваются вправо и влево. */
    fun bnVis(c: Context) = sp(c).getInt("dock_bn_vis", 6)
    fun setBnVis(c: Context, v: Int) { sp(c).edit().putInt("dock_bn_vis", v.coerceIn(5, 8)).apply(); rev.intValue++ }
    /** Размер баннера в процентах от места под него (50-100). */
    fun bnSize(c: Context) = sp(c).getInt("dock_bn_size", 90)
    fun setBnSize(c: Context, v: Int) { sp(c).edit().putInt("dock_bn_size", v.coerceIn(50, 100)).apply(); rev.intValue++ }
    /** Ширина баннера в dp: экран делится на число видимых баннеров и умножается на размер. */
    /** 17.3: во сколько раз «Дом» уменьшил ряды, чтобы уместиться над доком (пишет FitHeight); док берёт ту же ширину, что и ряды. */
    val fitScale = androidx.compose.runtime.mutableFloatStateOf(1f)
    /**
     * 25.11.2026 (замечание Евгения: «баннеры обрезаются»): ширина экрана из resources.configuration не учитывает плотность, в которой рисуется «Дом»
     * (масштаб интерфейса, уменьшение в FixedFont на приставках с узким в dp экраном), поэтому баннеров помещалось не столько, сколько выбрано.
     * [uiW]: фактическая ширина дока в его dp (пишет HomeDockHost); [uiCfg]: настройки экрана той же композиции (по ним «Дом» выбирает боковые поля).
     */
    @Volatile var uiCfg: android.content.res.Configuration? = null
    val uiW = androidx.compose.runtime.mutableFloatStateOf(0f)
    private fun cfg(c: Context) = uiCfg ?: c.resources.configuration
    private fun widthDp(c: Context): Float = uiW.floatValue.takeIf { it > 0f } ?: cfg(c).screenWidthDp.toFloat()
    /** Боковые поля «Дома» в dp (как в HomeActivity): ряды и док выровнены по ним. */
    fun sidePadDp(c: Context): Int = if (cfg(c).smallestScreenWidthDp < 600) 10 else 32
    /** Боковой отступ полосы баннеров: поля «Дома» плюс то, что ряды сузились при уменьшении. */
    fun bnSideDp(c: Context, fit: Float = fitScale.floatValue): Float {
        val side = sidePadDp(c); val w = widthDp(c)
        return side + (w - 2 * side) * (1f - fit.coerceIn(0.5f, 1f)) / 2f
    }
    /** Ширина ячейки баннера (баннер + воздух между баннерами) в dp. */
    fun bnSlotDp(c: Context, fit: Float = fitScale.floatValue): Float {
        val side = sidePadDp(c); val w = widthDp(c); val n = bnVis(c)
        // 16 dp: поля внутри плашки; 2 dp на ячейку: зазор самих ячеек; так N баннеров помещаются целиком
        return ((w - 2 * side) * fit.coerceIn(0.5f, 1f) - 16f - 2f * n) / n * bnSize(c) / 100f
    }
    /** Воздух между баннерами: 17% ячейки, не меньше 10 dp: выбранный баннер (увеличен в 1,2 раза) остаётся внутри своей ячейки и не заезжает на край плашки. */
    fun bnGapDp(c: Context, fit: Float = fitScale.floatValue): Int = kotlin.math.ceil(maxOf(10f, bnSlotDp(c, fit) * 0.17f)).toInt()
    fun bnWidthDp(c: Context, fit: Float = fitScale.floatValue): Int = (bnSlotDp(c, fit) - bnGapDp(c, fit)).toInt().coerceAtLeast(56)
    /** Фон дока: по умолчанию в режиме баннеров «без подложки» (как на картинке), иначе прежний. */
    fun bgEff(c: Context): Int = if (banners(c) && !sp(c).contains("dock_bg")) 5 else bg(c)
    fun bnHeightDp(c: Context, fit: Float = fitScale.floatValue): Int = bnWidthDp(c, fit) * 9 / 16
    fun hasAlign(c: Context) = sp(c).contains("dock_align")
    fun recents(c: Context) = sp(c).getBoolean("dock_recents", false)
    fun setRecents(c: Context, v: Boolean) { sp(c).edit().putBoolean("dock_recents", v).apply(); rev.intValue++ }
    fun recentN(c: Context) = sp(c).getInt("dock_recent_n", 3)
    fun setRecentN(c: Context, v: Int) { sp(c).edit().putInt("dock_recent_n", v.coerceIn(1, 8)).apply(); rev.intValue++ }
    /** Недавно запущенные приложения (не из основной секции дока): отдельная секция после разделителя. */
    fun recentApps(c: Context, all: List<Entry>, exclude: Set<String>): List<Entry> =
        all.filter { !it.system && !Cfg.isHidden(it.pkg) && it.pkg !in hiddenList(c) && it.pkg !in exclude && Cfg.lastLaunch(it.pkg) > 0 }.sortedByDescending { Cfg.lastLaunch(it.pkg) }.take(recentN(c))
    fun align(c: Context) = sp(c).getInt("dock_align", 0)   // по умолчанию слева (как в Pulsar); уже выбранное значение не меняется
    /** После запуска приложения из дока (и из панели «Все приложения») док закрыть (true) или оставить (false, по умолчанию). */
    fun closeAfter(c: Context) = sp(c).getBoolean("dock_close_after", false)
    fun setCloseAfter(c: Context, v: Boolean) { sp(c).edit().putBoolean("dock_close_after", v).apply(); rev.intValue++ }
    fun afterLaunch(c: Context) { if (closeAfter(c) && on(c)) { focusedPkgs.clear(); setOn(c, false) } }
    /** Когда из дока открывали панель «Все приложения»: запуск из неё в течение двух минут тоже закрывает док (режим «Закрывать»). */
    @Volatile var allOpenedAt = 0L
    fun labelSp(c: Context) = sp(c).getInt("dock_label_sp", 10)
    fun setLabelSp(c: Context, v: Int) { sp(c).edit().putInt("dock_label_sp", v.coerceIn(8, 16)).apply(); rev.intValue++ }
    /** 18.1: «вниз» на значке дока открывает все приложения (по умолчанию да); выключено: ничего не происходит, экран не меняется. */
    fun downOpens(c: Context) = sp(c).getBoolean("dock_down_opens", true)
    fun setDownOpens(c: Context, v: Boolean) { sp(c).edit().putBoolean("dock_down_opens", v).apply(); rev.intValue++ }
    fun scrollbar(c: Context) = sp(c).getBoolean("dock_scrollbar", false)
    fun setScrollbar(c: Context, v: Boolean) { sp(c).edit().putBoolean("dock_scrollbar", v).apply(); rev.intValue++ }
    /** Приложения, которые не показываются в доке (ни выбранные, ни автодобавление, ни недавние); отдельно от общего «Скрытые приложения». */
    fun hiddenList(c: Context): List<String> = (sp(c).getString("dock_hidden", "") ?: "").split(',').filter { it.isNotBlank() }
    fun setHiddenList(c: Context, l: List<String>) { sp(c).edit().putString("dock_hidden", l.joinToString(",")).apply(); rev.intValue++ }
    /** Открыть настройки программы сразу на группе «Док». */
    fun openSettings(c: Context, sub: String? = null) {
        SettingsGroups.last = "dock"; SettingsGroups.openSub = sub; SettingsGroups.reqTick++
        leaveVia(c, 1) { c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("from_home", true).putExtra("screen", "settings")) }
    }
    fun setAlign(c: Context, v: Int) { sp(c).edit().putInt("dock_align", v).apply(); rev.intValue++ }

    /** Приложения дока по порядку: выбор пользователя, а если он пуст, то «Избранное»; не больше заданного числа. */
    fun shown(c: Context, all0: List<Entry>): List<Entry> {
        // скрытые владельцем приложения в доке не показываются нигде (ни при выборе, ни при пустом списке, ни при автодобавлении)
        val dh = hiddenList(c).toSet()
        val all = all0.filter { !Cfg.isHidden(it.pkg) && it.pkg !in dh }
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
    fun heightDp(c: Context): Int = PulsarDock.heightDp(c) + (if (banners(c)) bnHeightDp(c, 1f) * 5 / 4 + 22 + bottom(c) else icon(c) + (if (labels(c)) (labelSp(c) * 13 / 10 + 7) else 0) + 12 + bottom(c))
}

/** Док поверх «Дома» у нижнего края. Ставится после содержимого экрана. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeDockHost() {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val rv = HomeDock.rev.intValue
    // 17.3: слушаем Pulsar (его нижняя панель) и поднимаем док выше неё
    androidx.compose.runtime.DisposableEffect(Unit) {
        val r = PulsarDock.register(ctx)
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
    HomeDock.uiCfg = androidx.compose.ui.platform.LocalConfiguration.current
    if (!HomeDock.on(ctx)) return
    val t = LocalTh.current
    // список приложений «Дома» не является состоянием Compose: перечитываем его, пока экран открыт (иначе док появлялся только после переключения)
    var all by remember { mutableStateOf(AppsMem.list) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        var n = 0
        try {
            while (true) { kotlinx.coroutines.delay(600); if (AppsMem.list !== all) all = AppsMem.list; PulsarDock.refresh(); if (n++ % 5 == 0) PulsarDock.announce(ctx, true) }
        } finally { PulsarDock.announce(ctx, false) }
    }
    val items = HomeDock.shown(ctx, all)
    if (items.isEmpty()) return
    val rec = if (HomeDock.recents(ctx)) HomeDock.recentApps(ctx, all, items.map { it.pkg }.toSet()) else emptyList()
    val light = when (HomeDock.bgEff(ctx)) { 1 -> true; 2 -> !t.dark; else -> false }
    val alpha = (100 - HomeDock.transp(ctx)).coerceIn(0, 100) / 100f
    val barBg = (if (light) Color(0xFFF5F5F5) else Color(0xFF101418)).copy(alpha = alpha)
    val txt = if (light) Color(0xFF111111) else Color.White
    // тёмные подписи только на светлой панели, которая достаточно непрозрачна; иначе светлые с тёмной тенью (на прозрачной панели под ней картинка, обычно тёмная внизу)
    val darkLabels = light && HomeDock.transp(ctx) < 60
    val iconDp = HomeDock.icon(ctx)
    // 17.2: «стекло» (bg 3 прозрачное, 4 матовое): светлая дымка с тонкой рамкой; баннеры вместо значков
    val bgm = HomeDock.bgEff(ctx)
    val glass = bgm == 3 || bgm == 4
    val noBg = bgm == 5
    val bnOn = HomeDock.banners(ctx)
    val cellW = if (bnOn) HomeDock.bnWidthDp(ctx) + HomeDock.bnGapDp(ctx) else iconDp + 26
    val dockRad = if ((glass || bnOn) && HomeDock.radius(ctx) == 0) 22 else HomeDock.radius(ctx)
    val glassBrush = androidx.compose.ui.graphics.Brush.verticalGradient(if (bgm == 4) listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.18f)) else listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.07f)))
    val showLabels = HomeDock.labels(ctx)
    val align = when (if (bnOn && !HomeDock.hasAlign(ctx)) 1 else HomeDock.align(ctx)) { 0 -> Alignment.CenterStart; 2 -> Alignment.CenterEnd; else -> Alignment.Center }
    val sel = androidx.compose.runtime.rememberCoroutineScope()
    val cellPx = with(LocalDensity.current) { cellW.dp.toPx() }
    var menuFor by remember { mutableStateOf<Entry?>(null) }
    val menuReqNow = HomeDock.menuReq.value
    androidx.compose.runtime.LaunchedEffect(menuReqNow) {
        if (menuReqNow != null) { HomeDock.menuReq.value = null; items.firstOrNull { it.pkg == menuReqNow }?.let { menuFor = it } ?: rec.firstOrNull { it.pkg == menuReqNow }?.let { menuFor = it } }
    }
    var barMenu by remember { mutableStateOf(false) }
    val firstReq = remember { androidx.compose.ui.focus.FocusRequester() }
    val cellReqs = remember { mutableMapOf<String, androidx.compose.ui.focus.FocusRequester>() }
    androidx.compose.runtime.LaunchedEffect(HomeDock.focusTick.intValue) { if (HomeDock.focusTick.intValue > 0) { runCatching { firstReq.requestFocus() } } }
    // порядок хранится явно, как только его меняют (пустой список означает «Избранное»)
    fun materialize(): MutableList<String> = items.map { it.pkg }.toMutableList()
    val openAll = {
        HomeDock.allOpenedAt = System.currentTimeMillis()
        if (Cfg.homeAllPanel) AllPanel.search("")
        else leaveVia(ctx, 1) { runCatching { ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        Unit
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val realW = maxWidth.value
        androidx.compose.runtime.SideEffect { if (kotlin.math.abs(HomeDock.uiW.floatValue - realW) > 0.5f) HomeDock.uiW.floatValue = realW }
        Box(Modifier.fillMaxWidth().padding(horizontal = if (bnOn) HomeDock.bnSideDp(ctx).dp else 0.dp).padding(bottom = (HomeDock.bottom(ctx) + PulsarDock.heightDp(ctx)).dp).clip(RoundedCornerShape(dockRad.dp)).then(if (glass) Modifier.background(glassBrush).border(1.2.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.60f), Color.White.copy(alpha = 0.10f))), RoundedCornerShape(dockRad.dp)) else if (noBg) Modifier else Modifier.background(barBg))
            // долгое нажатие на пустом месте дока: меню дока (значки обрабатывают своё долгое нажатие сами)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { barMenu = true }) }, contentAlignment = align) {
            val dockScroll = rememberScrollState()
            // баннеры: видимая часть ровно в N целых ячеек (по центру), прокрутка идёт по ячейкам, обрезанных баннеров по краям нет
            val viewW = if (bnOn) (minOf(items.size + rec.size, HomeDock.bnVis(ctx)) * (cellW + 2)) else 0
            val rowMod = if (bnOn) Modifier.padding(horizontal = 8.dp).width(viewW.dp).horizontalScroll(dockScroll).padding(vertical = 3.dp)
                else Modifier.horizontalScroll(dockScroll).padding(horizontal = 8.dp, vertical = 3.dp)
            // на ТВ Compose по умолчанию держит выбранный элемент ближе к середине, и по краям оставались половинки баннеров:
            // в режиме баннеров лента сдвигается ровно настолько, чтобы выбранная ячейка стала целиком видна (всегда на целую ячейку)
            val edgePx = with(LocalDensity.current) { 1.dp.toPx() }
            val defSpec = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
            val bivSpec = remember(bnOn, edgePx, defSpec) {
                if (!bnOn) defSpec else object : androidx.compose.foundation.gestures.BringIntoViewSpec {
                    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                        if (offset - edgePx < 0f) offset - edgePx else if (offset + size + edgePx > containerSize) offset + size + edgePx - containerSize else 0f
                }
            }
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides bivSpec) {
            Row(rowMod, verticalAlignment = Alignment.CenterVertically) {
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
                        Modifier.padding(horizontal = 1.dp).width(cellW.dp)
                            .then(if (idx == 0) Modifier.focusRequester(firstReq) else Modifier)
                            .focusRequester(cellReq)
                            .onFocusChanged { focused = it.isFocused; if (it.isFocused) { if (e.pkg !in HomeDock.focusedPkgs) HomeDock.focusedPkgs.add(e.pkg) } else HomeDock.focusedPkgs.remove(e.pkg) }
                            .onPreviewKeyEvent { ev ->
                                val ok = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                                if (ok && ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount >= 1 && !longKey) { longKey = true; menuFor = e; return@onPreviewKeyEvent true }
                                if (ok && ev.type == KeyEventType.KeyUp && longKey) { longKey = false; return@onPreviewKeyEvent true }
                                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                // клавиша «Меню» («Инфо») на ячейке дока: меню ячейки (убрать из дока, настройки дока)
                                if ((ev.key == Key.Menu || ev.key == Key.Info) && ev.nativeKeyEvent.repeatCount == 0) { menuFor = e; return@onPreviewKeyEvent true }
                                // «вниз» с дока: остальные приложения в стекле, фокус центра освобождается
                                if (ev.key == Key.DirectionDown) { if (PulsarDock.heightDp(ctx) > 0) PulsarDock.focusDock(ctx) else if (HomeDock.downOpens(ctx)) openAll(); true } else false
                            }
                            .then(if (bnOn) Modifier.zIndex(if (focused) 1f else 0f) else Modifier.clip(RoundedCornerShape(10.dp)))
                            .background(if (HomeDock.moving.value == e.pkg) t.accent.copy(alpha = 0.55f) else if (focused && !bnOn) t.accent else Color.Transparent)
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
                                // баннер в фокусе сам увеличивается: стандартная серая подсветка ячейки позади него не нужна
                                interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = if (bnOn) null else androidx.compose.foundation.LocalIndication.current,
                                onClick = {
                                    if (HomeDock.moving.value == e.pkg) HomeDock.moving.value = null
                                    else if (System.currentTimeMillis() - lpAt > 900)
                                        ctx.packageManager.getLaunchIntentForPackage(e.pkg)?.let { Cfg.countLaunch(e.pkg); runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; HomeDock.afterLaunch(ctx) }
                                },
                            )
                            .padding(horizontal = 4.dp, vertical = if (bnOn) (6 + HomeDock.bnHeightDp(ctx) * 0.12f).dp else 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (bnOn) {
                            // 17.2: баннер приложения (как плитка на «Доме»); у приложения без баннера: значок на тёмной плитке с подписью
                            val bw = HomeDock.bnWidthDp(ctx); val bh = HomeDock.bnHeightDp(ctx)
                            val banner by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(Banners.peek(e.pkg), e.pkg) {
                                if (value == null && !Banners.isNone(e.pkg)) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Banners.load(ctx, e) }
                            }
                            val bscale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.2f else 1f, label = "dockBn")
                            Box(Modifier.size(bw.dp, bh.dp).graphicsLayer { scaleX = bscale; scaleY = bscale }
                                .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.30f)), contentAlignment = Alignment.Center) {
                                val bb = banner
                                if (bb != null) Image(bb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Image(bmp, null, Modifier.size((bh * 0.5f).dp))
                                    Text(e.label, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
                                }
                                // как у плиток «Дома» (картинка Евгения): тонкий скруглённый контур цвета акцента поверх баннера и лёгкая подсветка
                                if (focused) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.10f)))
                            }
                        } else {
                        Image(bmp, null, Modifier.size(iconDp.dp))
                        }
                        if (!bnOn && showLabels) Text(e.label, color = if (focused || !darkLabels) Color.White else txt, fontSize = HomeDock.labelSp(ctx).sp,
                            // мягкая тень контрастного цвета: подпись читается на любой картинке и при прозрачной панели
                            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(if (focused || !darkLabels) Color.Black.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.9f), androidx.compose.ui.geometry.Offset(0f, 1f), 5f)), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
                items.forEachIndexed { idx, e -> androidx.compose.runtime.key("d" + e.pkg) { Cell(e, idx, true) } }
                if (rec.isNotEmpty()) {
                    // разделитель между основной секцией и недавними
                    // в режиме баннеров разделителя нет: он сдвигал бы ячейки и крайний баннер обрезался
                    if (!bnOn) Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height((iconDp + 8).dp).background(txt.copy(alpha = 0.3f)))
                    rec.forEach { e -> androidx.compose.runtime.key("r" + e.pkg) { Cell(e, -1, false) } }
                }
            }
            }
            // полоса прокрутки дока (настройка «Полоса прокрутки дока»): видна, когда значки не помещаются
            if (HomeDock.scrollbar(ctx) && dockScroll.maxValue > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).drawBehind {
                val total = size.width + dockScroll.maxValue
                val w = size.width * size.width / total
                val x = (size.width - w) * dockScroll.value / dockScroll.maxValue
                drawRect(txt.copy(alpha = 0.25f))
                drawRect(t.accent, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height))
            })
        }
    }
    if (barMenu) {
        val closeBar = { barMenu = false }
        ListDialog(
            androidx.compose.ui.res.stringResource(R.string.dock_title),
            listOf(
                Opt(androidx.compose.ui.res.stringResource(R.string.dock_settings)) { closeBar(); HomeDock.openSettings(ctx) },
                Opt(androidx.compose.ui.res.stringResource(R.string.dock_m_hide)) { closeBar(); HomeDock.setOn(ctx, false) },
                Opt(androidx.compose.ui.res.stringResource(R.string.dock_m_add)) { closeBar(); HomeDock.openSettings(ctx, "dockapps") },
                Opt(androidx.compose.ui.res.stringResource(R.string.dock_m_close_menu)) { closeBar() },
            ),
            closeBar,
        )
    }
    menuFor?.let { e ->
        val close = { menuFor = null }
        val fav = Cfg.isFavorite(e.pkg)
        ListDialog(
            e.label,
            listOf(
                Opt(androidx.compose.ui.res.stringResource(R.string.dock_m_move)) { HomeDock.setSort(ctx, 0); HomeDock.setList(ctx, materialize()); HomeDock.moving.value = e.pkg; close() },
                Opt(androidx.compose.ui.res.stringResource(R.string.dock_m_remove)) { val l = materialize(); l.remove(e.pkg); HomeDock.setList(ctx, l.ifEmpty { listOf() }); close() },
                Opt(androidx.compose.ui.res.stringResource(R.string.dock_settings)) { close(); HomeDock.openSettings(ctx) },
                Opt(androidx.compose.ui.res.stringResource(R.string.dock_m_hide_app)) { Cfg.toggleHidden(e.pkg); close() },
                Opt(androidx.compose.ui.res.stringResource(if (fav) R.string.dock_m_unfav else R.string.dock_m_fav)) { Cfg.toggleFavorite(e.pkg); close() },
                Opt(androidx.compose.ui.res.stringResource(R.string.close)) { close() },
            ),
            close,
        )
    }
}
