package app.tvapps

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch

/** Одно приложение в списке. */
data class Entry(
    val pkg: String, val label: String, val icon: Drawable, val system: Boolean, val launch: Intent,
    val installed: Long = 0L, val updated: Long = 0L,
    /** Готовая картинка значка: рисуется в фоне при загрузке списка, чтобы первый кадр не ждал. */
    val bmp: androidx.compose.ui.graphics.ImageBitmap? = null,
)

/** После долгого «ОК» меню открывается, пока кнопка ещё зажата: её повторы и отпускание не должны попасть в первый пункт меню и что-то запустить. */
/** Сколько держать «ОК» на пульте, чтобы это считалось долгим нажатием. */
internal const val LONG_OK_MS = 500L

/** Запросы к меню от «Дома»: открыть настройки или поиск, а также признак «пришли из Дома» (тогда «Назад» возвращает в Дом). */
internal object NavReq {
    var stamp by mutableIntStateOf(0)
    var screen: String? = null
    var q: String? = null
    var fromHome by mutableStateOf(false)
    var openSearch = false
    private var lastSave = 0L

    fun read(i: Intent?) {
        fromHome = i?.getBooleanExtra("from_home", false) == true
        screen = i?.getStringExtra("screen")
        q = i?.getStringExtra("q")
        openSearch = i?.getBooleanExtra("open_search", false) == true
        stamp++
    }

    /** Автоматическая копия: не чаще раза в минуту и не в главном потоке, чтобы переходы между экранами оставались мгновенными. */
    fun saveSoon(ctx: android.content.Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSave < 60_000L) return
        lastSave = now
        val app = ctx.applicationContext
        Thread { Backup.autoSave(app) }.start()
    }
}

internal object OkGuard {
    @Volatile var until = 0L
    fun arm() { until = android.os.SystemClock.elapsedRealtime() + 6000 }
}

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun dispatchKeyEvent(ev: android.view.KeyEvent): Boolean {
        if (Voice.onKey(this, ev)) return true
        // «Назад» обрабатываем здесь, до списка: иначе первое нажатие после запуска из «Дома» гасилось списком и меню не закрывалось
        if (ev.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (ev.action == android.view.KeyEvent.ACTION_UP && !ev.isCanceled) onBackPressedDispatcher.onBackPressed()
            return true
        }
        val ok = ev.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || ev.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
            ev.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
        if (ok && OkGuard.until != 0L) {
            if (android.os.SystemClock.elapsedRealtime() > OkGuard.until) OkGuard.until = 0L
            else {
                if (ev.action == android.view.KeyEvent.ACTION_UP) OkGuard.until = 0L
                return true
            }
        }
        return super.dispatchKeyEvent(ev)
    }
    private var asHome by androidx.compose.runtime.mutableStateOf(false)

    /** Кнопка «Домой» нажата, когда меню уже на экране: это второе нажатие, уходим на родной главный экран. */
    private val homeKeyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) {
            if (i.getStringExtra("reason") == "homekey" && asHome && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) goNativeHome()
        }
    }
    private var lastNative = 0L
    @Volatile private var onScreen = false

    private fun goNativeHome() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNative < 1500) return
        lastNative = now
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { nativeHomeIntent(this)?.let { startActivity(it) } } }, 250)
    }

    /** Язык из настроек программы (пусто — как в системе). Читается напрямую: Cfg здесь ещё не создан. */
    override fun attachBaseContext(base: android.content.Context) {
        val l = runCatching { base.getSharedPreferences("tvapps", MODE_PRIVATE).getString("lang", "") }.getOrNull().orEmpty()
        if (l.isEmpty()) { super.attachBaseContext(base); return }
        val loc = java.util.Locale(l)
        java.util.Locale.setDefault(loc)
        val conf = android.content.res.Configuration(base.resources.configuration)
        conf.setLocale(loc)
        super.attachBaseContext(base.createConfigurationContext(conf))
    }

    override fun onStart() {
        super.onStart()
        runCatching { registerReceiver(homeKeyReceiver, android.content.IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cfg.init(this)
        HomeStore.init(this)
        asHome = intent?.categories?.contains(Intent.CATEGORY_HOME) == true
        NavReq.read(intent)
        if (intent?.getBooleanExtra(UpdOpen.EXTRA, false) == true) UpdOpen.requested = true
        applyFullscreen()
        MouseAsTouch.install(window)
        setContent {
            UiScaleRoot {
            val tc = currentTC()
            MaterialTheme(colorScheme = if (tc.dark) darkColorScheme() else lightColorScheme()) {
                val mbg = HomeStore.menuBg
                CompositionLocalProvider(LocalTh provides adaptTheme(tc, mbg), LocalBaseTh provides tc) {
                    Box(Modifier.fillMaxSize().background(tc.bg)) {
                        HomeBackground(mbg, menu = true)
                        AllApps(resumeTick.intValue, asHome, onFullscreenChanged = { applyFullscreen() })
                    }
                }
            }
                    }
        }
    }

    // после удаления приложения, возврата из сведений или кнопки «Домой» список обновляется;
    // если есть очередь пакетных удалений или установок без ADB, открывается следующее окно
    override fun onResume() {
        super.onResume()
        onScreen = true
        HomeNav.menuOn = true
        resumeTick.intValue++
        applyFullscreen()
        Pending.next(this)
    }

    override fun onPause() {
        super.onPause()
        HomeNav.menuOn = false
        HomeNav.menuPausedAt = android.os.SystemClock.elapsedRealtime()
        // положение в меню сохраняется (переживёт выгрузку из памяти); выход кнопкой «Назад» сбрасывает его, если не включено запоминание после выхода
        if (isFinishing && !Cfg.rememberPos) MenuMemory.clear(this) else MenuMemory.save(this)
    }

    override fun onStop() {
        super.onStop()
        onScreen = false
        runCatching { unregisterReceiver(homeKeyReceiver) }
        NavReq.saveSoon(this)
    }

    override fun onNewIntent(intent: Intent) {
        // на некоторых устройствах «Домой» при открытом меню приходит сюда: это тоже второе нажатие
        val isHome = intent.categories?.contains(Intent.CATEGORY_HOME) == true
        // меню на экране: на новых Android оно уже на паузе в момент нажатия, но ещё не остановлено
        val secondHome = isHome && onScreen
        super.onNewIntent(intent)
        setIntent(intent)
        NavReq.read(intent)
        if (intent.getBooleanExtra(UpdOpen.EXTRA, false)) UpdOpen.requested = true
        asHome = isHome
        if (secondHome) goNativeHome()
    }

    /** Полный экран: строки состояния и навигации скрыты и появляются по свайпу от края. */
    private fun applyFullscreen() { SysBars.apply(this) }
}

internal fun loadApps(ctx: android.content.Context, prev: List<Entry> = emptyList(), reuse: Boolean = false): List<Entry> {
    val pm = ctx.packageManager
    val seen = HashSet<String>()
    val out = ArrayList<Entry>()
    // готовые значки прежнего списка: у приложения с тем же временем обновления картинка не готовится заново
    // (раньше при каждом возврате в меню для всех приложений заново загружался значок и рисовался в картинку: нагрузка и мусор для сборщика на слабом железе)
    val old = if (reuse) prev.filter { it.bmp != null }.associateBy { it.pkg } else emptyMap()
    // приложения для телевизора и обычные: берём всё, что можно запустить
    for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
        val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
        for (ri in pm.queryIntentActivities(q, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == ctx.packageName || !seen.add(pkg)) continue
            val launch = Intent(Intent.ACTION_MAIN).addCategory(cat)
                .setClassName(pkg, ri.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val ai = ri.activityInfo.applicationInfo
            val pi = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
            val installed = pi?.firstInstallTime ?: 0L
            val updated = pi?.lastUpdateTime ?: 0L
            val label = ri.loadLabel(pm).toString()
            val prevE = old[pkg]
            if (prevE != null && prevE.updated == updated && prevE.launch.component == launch.component) {
                out.add(prevE.copy(label = label, system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0, launch = launch, installed = installed))
                continue
            }
            val loaded = ri.loadIcon(pm)
            val entry = Entry(pkg, label, loaded, ai.flags and ApplicationInfo.FLAG_SYSTEM != 0, launch, installed, updated)
            val own = IconPacks.override(ctx, entry)
            val finalIcon = own ?: entry.icon
            // значок готовится один раз сразу в размер плитки (128 px); в памяти остаётся только он (раньше каждый Drawable держал
            // исходные картинки по 192-512 px: десятки мегабайт на 70 приложений и частая сборка мусора)
            // свой значок владельца берётся как есть; значок приложения проверяется: крупная плотность на магнитолах, детектор пустого/чёрного значка,
            // запасной значок (цвет по пакету и буква), чтобы не было чёрных квадратов (Pulsar до 2.2 в mdpi)
            val big = if (own != null) runCatching { finalIcon.toBitmap(192, 192) }.getOrNull()
            else runCatching { IconQuality.best(ctx.resources.displayMetrics.densityDpi, pm, ri.activityInfo, loaded, label, pkg) }.getOrNull()
                ?: runCatching { finalIcon.toBitmap(192, 192) }.getOrNull()
            if (big == null) { out.add(entry.copy(icon = finalIcon)); continue }
            val small = runCatching { android.graphics.Bitmap.createScaledBitmap(big, 128, 128, true) }.getOrDefault(big)
            out.add(entry.copy(icon = android.graphics.drawable.BitmapDrawable(ctx.resources, big), bmp = small.asImageBitmap()))
        }
    }
    return out
}

/** Тот же набор приложений: пакеты, названия, признаки и время установки/обновления совпадают, значит значки и плитки перерисовывать не нужно. */
private fun sameApps(a: List<Entry>, b: List<Entry>): Boolean {
    if (a.size != b.size) return false
    for (i in a.indices) {
        val x = a[i]; val y = b[i]
        if (x.pkg != y.pkg || x.label != y.label || x.system != y.system || x.installed != y.installed || x.updated != y.updated ||
            x.launch.component != y.launch.component) return false
    }
    return true
}

/** Скрытые убираются, остальное сортируется по настройке. */
private fun arrange(all: List<Entry>, query: String): List<Entry> {
    val q = query.trim().lowercase()
    val list = all.filter { (Cfg.showHidden || !Cfg.isHidden(it.pkg)) && (q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)) }
    val sorted = when (Cfg.sort) {
        "name_desc" -> list.sortedByDescending { it.label.lowercase() }
        "new" -> list.sortedByDescending { it.installed }
        "old" -> list.sortedBy { it.installed }
        "custom" -> {
            val idx = Cfg.order.withIndex().associate { it.value to it.index }
            list.sortedWith(compareBy<Entry> { idx[it.pkg] ?: Int.MAX_VALUE }.thenBy { it.label.lowercase() })
        }
        "freq" -> list.sortedWith(compareByDescending<Entry> { Cfg.launches(it.pkg) }.thenBy { it.label.lowercase() })
        else -> list.sortedBy { it.label.lowercase() }
    }
    // папки стоят первыми, кроме своего порядка: там они на том месте, куда их поставили
    return if (Cfg.sort == "custom") sorted else sorted.sortedBy { if (it.isFolder) 0 else 1 }
}

private fun iconDp(): Dp = (when (Cfg.iconSize) { 3 -> 34f; 0 -> 52f; 2 -> 96f; else -> 72f } * Cfg.iconScale / 100f).dp
/** Отступ вокруг значка в плитке и зазор между плитками по настройке «Плотность». */
private fun tilePad(): Float = when (Cfg.gridGap) { 0 -> 2f; 2 -> 14f; else -> if (Cfg.iconSize == 3) 3f else 10f }
private fun tileGap(narrow: Boolean): Float = when (Cfg.gridGap) { 0 -> 0f; 2 -> if (narrow) 8f else 18f; else -> if (narrow) 3f else 10f }
/** Базовый размер подписи в сетке (sp, умножается на системный масштаб шрифта). На магнитолах (облегчённый режим) 13 sp: на 140% это ~18 px, как у системных значков. */
internal fun gridLabelSp(narrow: Boolean): Int = if (Cfg.iconSize == 3) 11 else if (narrow) (if (Lite.on) 13 else 12) else (labelSp() * 1.15f).toInt()
private fun labelSp() = when (Cfg.iconSize) { 3 -> 10; 0 -> 12; 2 -> 16; else -> 14 }
/** Минимальная ширина ячейки на узком экране (телефон) по выбранному размеру значков и масштабу. */
private fun narrowCell(): Float = ((when (Cfg.iconSize) { 3 -> 48f; 0 -> 62f; 2 -> 96f; else -> 78f }) * Cfg.iconScale / 100f).coerceAtLeast(34f)

/** Предельный размер значка в сетке: на низком широком экране (магнитола 1200x600) значки ужимаются так, чтобы строки помещались целиком, без обрезанной снизу. null = без ограничения. */
private val LocalIconCap = androidx.compose.runtime.compositionLocalOf<Dp?> { null }

@Composable
private fun AllApps(tick: Int, asHome: Boolean, onFullscreenChanged: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<Entry>>(AppsMem.list) }
    var loadedOnce by remember { mutableStateOf(AppsMem.list.isNotEmpty()) }
    var menuFor by remember { mutableStateOf<Entry?>(null) }
    var screen by remember { mutableStateOf((ctx as? android.app.Activity)?.intent?.getStringExtra("screen") ?: "main") }
    var searching by remember { mutableStateOf(!(ctx as? android.app.Activity)?.intent?.getStringExtra("q").isNullOrBlank()) }
    var query by remember { mutableStateOf((ctx as? android.app.Activity)?.intent?.getStringExtra("q").orEmpty()) }
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<String?>(null) }
    var folderOpen by remember { mutableStateOf<String?>(null) }
    var folderRename by remember { mutableStateOf<String?>(null) }
    var folderAdd by remember { mutableStateOf<String?>(null) }
    var versionsFor by remember { mutableStateOf<Entry?>(null) }
    var updatesFor by remember { mutableStateOf<Entry?>(null) }
    var updatesAll by remember { mutableStateOf(false) }
    // кнопка микрофона: распознанный текст идёт в поиск
    VoiceHost { txt -> query = txt; searching = true }
    // «Дом» открывает меню, которое уже живёт в фоне: настройки или поиск приходят запросом
    LaunchedEffect(NavReq.stamp) {
        if (NavReq.stamp > 0) {
            screen = NavReq.screen ?: "main"
            val q = NavReq.q
            if (!q.isNullOrBlank()) { query = q; searching = true }
            else if (NavReq.openSearch) { query = ""; searching = true }
            else if (NavReq.fromHome) { query = ""; searching = false; selectMode = false; selected = emptySet() }
        }
    }
    // нажатие на уведомление «Доступно обновлений»: сразу открываем окно «Обновления»
    LaunchedEffect(UpdOpen.requested) { if (UpdOpen.requested) { updatesAll = true; UpdOpen.requested = false } }
    // перетаскивание: над какой плиткой палец (и в центре ли) и готова ли папка принять значок
    var dragHover by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var mergeReady by remember { mutableStateOf<String?>(null) }
    // Перетаскивание пальцем: значок «летит» под пальцем отдельным слоем (положение читается только при отрисовке, без перекомпоновки сетки),
    // раскладка при этом не меняется (подсказка про стрелки не вставляется над сеткой), прокрутка у края идёт плавно по кадрам.
    var dragging by remember { mutableStateOf(false) }
    var dragEntry by remember { mutableStateOf<Entry?>(null) }
    var dragXY by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val moveHintOn by remember { derivedStateOf { moving != null && !dragging } }
    val dragDensity = androidx.compose.ui.platform.LocalDensity.current.density
    val gridLc = remember { arrayOfNulls<LayoutCoordinates>(1) }   // живые координаты контейнера сетки: палец переводится из окна в сетку без оглядки на плитку
    // место курсора запоминается, чтобы после прокрутки и возвращения из программы оно не терялось
    // только пока программа жива (уход «Домой» и возврат): после выгрузки из памяти курсор снова на первом значке
    var lastFocus by remember { mutableStateOf(MenuMemory.init(ctx).focusPkg) }
    var gridHasFocus by remember { mutableStateOf(false) }
    var headerHasFocus by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val reqs = remember { HashMap<String, FocusRequester>() }
    fun reqFor(p: String): FocusRequester = reqs.getOrPut(p) { FocusRequester() }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var reload by remember { mutableIntStateOf(0) }
    val seenIconTick = remember { intArrayOf(Cfg.iconTick) }
    LaunchedEffect(tick, Cfg.iconTick, reload) {
        // первый кадр рисуется сразу, список из кэша подставляется из фона, затем настоящая загрузка обновляет его
        if (apps.isEmpty()) {
            val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                AppCache.load(ctx)
            }
            if (cached.isNotEmpty() && apps.isEmpty()) apps = cached
        }
        val forced = seenIconTick[0] != Cfg.iconTick
        seenIconTick[0] = Cfg.iconTick
        val before = apps
        // загрузка в фоне с пониженным приоритетом: не мешает кадрам; готовые значки неизменившихся приложений берутся из прежнего списка
        val fresh = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            loadApps(ctx, before, reuse = !forced).also { AppCache.save(ctx, it, force = Cfg.iconTick > 0) }
        }
        // при каждом возврате в меню список загружался заново и подменялся целиком: новые объекты и новые картинки перекомпоновывали и
        // перерисовывали все плитки разом (рывок на слабом железе). Если приложения те же (пакет, название, время обновления), остаётся прежний список.
        if (forced || !sameApps(apps, fresh)) apps = fresh
        AppsMem.list = apps; AppsMem.stamp = System.currentTimeMillis()
        loadedOnce = true
    }
    LaunchedEffect(Unit) {
        // запоминаем, какие службы спецвозможностей включены (для автоматического восстановления после загрузки)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { AccessSync.sync(ctx) }
            runCatching {
                val cur = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
                if (cur != null && cur != ctx.packageName && cur != "android" && cur != Cfg.nativeHome) Cfg.changeNativeHome(cur)
            }
        }
        autoConnect(ctx)
        AccessSync.grantPermission(ctx)
        // с root один раз на версию программа компилируется в машинный код (меньше «холодных» рывков при прокрутке и первом показе)
        CompileBoost.runIfNeeded(ctx)
    }

    // «Назад»: из настроек — на главную страницу; из выбора — выйти из выбора; как главный экран — остаёмся на месте
    BackHandler(enabled = screen != "main" || asHome || searching || selectMode || (NavReq.fromHome && Cfg.homeModule)) {
        if (moving != null) moving = null
        else if (screen != "main") screen = "main"
        else if (selectMode) { selectMode = false; selected = emptySet() }
        else if (searching) { searching = false; query = "" }
        else if (asHome) {
            // выход из меню на родной экран: без «Запоминать положение после выхода» следующее открытие начинается с начала
            if (!Cfg.rememberPos) { MenuMemory.clear(ctx); lastFocus = null; scope.launch { runCatching { if (Cfg.listMode) listState.scrollToItem(0) else gridState.scrollToItem(0) } } }
            runCatching { nativeHomeIntent(ctx)?.let { ctx.startActivity(it) } }
        }
        else if (NavReq.fromHome && Cfg.homeModule) goHome(ctx)
    }
    // зарегистрирован позже основного: при переносе значка «Назад» сначала отменяет перенос
    MoveBackHandler({ moving != null }) { moving = null }

    if (screen != "main") {
        HalfUi { SettingsScreen(apps.sortedBy { it.label.lowercase() }, onBack = { screen = "main" }, onFullscreenChanged = onFullscreenChanged) }
        return
    }

    @Suppress("UNUSED_VARIABLE") val dependsOn = Cfg.launchTick
    val items = remember(apps, Cfg.folders, Cfg.iconTick, Cfg.showHidden, Cfg.hidden) { withFolders(ctx, apps) }
    val shown = remember(items, apps, query, Cfg.sort, Cfg.order, Cfg.showHidden, Cfg.hidden, Cfg.launchTick, Cfg.folders) {
        arrange(if (query.isBlank()) items else apps, query)
    }
    val shownRef = androidx.compose.runtime.rememberUpdatedState(shown)
    val lite = Lite.on
    // на узком экране (телефон в портрете) шапка идёт двумя строками, значки мельче
    val narrow = isNarrow()
    // низкий широкий экран (магнитола 1200x600): отступы шапки меньше, чтобы осталось место под значки
    // две строки шапки только на телефоне в портрете; на всём остальном шапка одна и ужимается под ширину
    val twoRow = narrow && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 420
    val shortWide = !narrow && androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 640
    val rootView = androidx.compose.ui.platform.LocalView.current
    /** Ставит курсор на запомненный значок (прокручивая к нему), а если его нет — на первый. */
    fun restoreFocus() {
        val list = shownRef.value
        // приложений по запросу нет, но есть карточки каталогов: курсор на первую из них
        if (list.isEmpty() && CatFocus.focusFirst()) return
        // та же плитка; если её уже нет (удалено, скрыто), ближайшая на прежнем месте; без запомненного фокуса первая
        val p = MenuMemoryLogic.focusTarget(list.map { it.pkg }, lastFocus, MenuMemory.focusIndex) ?: return
        val idx = list.indexOfFirst { it.pkg == p }
        scope.launch {
            val visible = if (Cfg.listMode) listState.layoutInfo.visibleItemsInfo.any { it.key == p } else gridState.layoutInfo.visibleItemsInfo.any { it.key == p }
            if (!visible) runCatching { if (Cfg.listMode) listState.scrollToItem(idx) else gridState.scrollToItem(idx) }
            kotlinx.coroutines.delay(80)
            runCatching { reqFor(p).requestFocus() }
        }
    }
    // прокрутка и фокус запоминаются и восстанавливаются при возврате из настроек, диалогов, папки, запущенного приложения и после пересоздания окна
    val scrollRestored = remember { booleanArrayOf(false) }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow {
            if (Cfg.listMode) listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            else gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
        }.collect { (i, o) ->
            if (scrollRestored[0] && query.isBlank()) MenuMemory.setScroll(shownRef.value.getOrNull(i)?.pkg, i, o)
        }
    }
    LaunchedEffect(shown.isNotEmpty()) {
        if (shown.isNotEmpty()) {
            if (query.isBlank() && MenuMemory.hasScroll) {
                val idx = MenuMemoryLogic.scrollIndex(shownRef.value.map { it.pkg }, MenuMemory.firstPkg, MenuMemory.firstIndex)
                val cur = if (Cfg.listMode) listState.firstVisibleItemIndex else gridState.firstVisibleItemIndex
                if (MenuMemoryLogic.needsScroll(true, idx, cur)) runCatching {
                    if (Cfg.listMode) listState.scrollToItem(idx, MenuMemory.firstOffset) else gridState.scrollToItem(idx, MenuMemory.firstOffset)
                }
            }
            scrollRestored[0] = true
            kotlinx.coroutines.delay(250)
            runCatching { rootView.requestFocus() }
            restoreFocus()
        }
    }
    // без чтения moving: перенос значка пальцем не перекомпонует тело экрана; moving проверяется в момент действия
    val noDialogsBase = menuFor == null && folderOpen == null && folderRename == null && folderAdd == null && versionsFor == null && updatesFor == null && !updatesAll && !confirmDelete
    // закрылось окно (действия, папка, обновления): курсор возвращается на запомненную плитку, если он был
    val dialogWasOpen = remember { booleanArrayOf(false) }
    LaunchedEffect(noDialogsBase) {
        if (noDialogsBase && dialogWasOpen[0] && lastFocus != null) { kotlinx.coroutines.delay(120); restoreFocus() }
        dialogWasOpen[0] = !noDialogsBase
    }
    // фокус потерян (например, значок с курсором ушёл за край при прокрутке): держим его на корне, чтобы первая стрелка вернула курсор на место
    LaunchedEffect(gridHasFocus, headerHasFocus, noDialogsBase) {
        if (!gridHasFocus && !headerHasFocus && noDialogsBase && moving == null) {
            kotlinx.coroutines.delay(300)
            runCatching { rootFocus.requestFocus() }
        }
    }

    val selectedApps = apps.filter { it.pkg in selected }
    fun endSelect() { selectMode = false; selected = emptySet() }

    // свой порядок: значок перемещается стрелками пульта или касанием по новому месту
    fun saveOrder(list: List<String>) { Cfg.changeOrder(list + Cfg.order.filter { it !in list }) }
    fun startMove(e: Entry) {
        if (Cfg.sort != "custom") { saveOrder(shown.map { it.pkg }); Cfg.changeSort("custom") }
        moving = e.pkg
    }
    fun colsNow(): Int {
        if (Cfg.listMode) return 1
        val vis = gridState.layoutInfo.visibleItemsInfo
        val first = vis.firstOrNull() ?: return 1
        return vis.takeWhile { it.offset.y == first.offset.y }.size.coerceAtLeast(1)
    }
    /** Плавно показывает плитку, только если она не видна целиком (прежний animateScrollToItem каждый раз выталкивал её к верху экрана: сетка «прыгала»). */
    suspend fun ensureVisible(index: Int) {
        if (Cfg.listMode) {
            val info = listState.layoutInfo
            val it = info.visibleItemsInfo.firstOrNull { x -> x.index == index }
            if (it == null) { listState.animateScrollToItem(index); return }
            val vh = info.viewportSize.height
            val d = if (it.offset < 0) it.offset.toFloat() else if (it.offset + it.size > vh) (it.offset + it.size - vh).toFloat() else 0f
            if (d != 0f) listState.animateScrollBy(d)
        } else {
            val info = gridState.layoutInfo
            val it = info.visibleItemsInfo.firstOrNull { x -> x.index == index }
            if (it == null) { gridState.animateScrollToItem(index); return }
            val vh = info.viewportSize.height
            val d = if (it.offset.y < 0) it.offset.y.toFloat() else if (it.offset.y + it.size.height > vh) (it.offset.y + it.size.height - vh).toFloat() else 0f
            if (d != 0f) gridState.animateScrollBy(d)
        }
    }
    fun moveTo(pkg: String, newIndex: Int, follow: Boolean = true) {
        val list = shownRef.value.map { it.pkg }.toMutableList()
        val i = list.indexOf(pkg)
        if (i < 0) return
        val j = newIndex.coerceIn(0, list.lastIndex)
        if (i == j) return
        list.removeAt(i)
        list.add(j, pkg)
        saveOrder(list)
        if (Cfg.sort != "custom") Cfg.changeSort("custom")
        // стрелки пульта ведут за плиткой, но только если она ушла за край; при перетаскивании пальцем сетка сама не прокручивается
        if (follow) scope.launch { runCatching { ensureVisible(j) } }
    }
    // перетаскивание пальцем или мышью: значок тянут по сетке. Короткая остановка у края плитки переставляет его на её место,
    // более долгая в центре плитки сливает: два значка становятся папкой, значок на папке попадает в неё
    // сортировка «свой порядок» включается при первой настоящей перестановке (в moveTo), а не в момент касания: пересортировка всех плиток в начале жеста давала задержку кадра
    fun dragBegin(e: Entry) { menuFor = null; dragHover = null; mergeReady = null; dragEntry = e; moving = e.pkg; dragging = true }
    /** Над какой плиткой палец (x, y в координатах сетки) и в центре ли она: центр сливает значки в папку, край переставляет. */
    fun hoverAt(pkg: String, x: Float, y: Float) {
        if (Cfg.listMode) {
            val info = listState.layoutInfo
            val tgt = info.visibleItemsInfo.firstOrNull { it.key != pkg && y >= it.offset && y < it.offset + it.size }
            val nh = tgt?.let {
                val canMerge = shownRef.value.firstOrNull { e -> e.pkg == pkg }?.isFolder == false
                (it.key as String) to (canMerge && kotlin.math.abs(y - (it.offset + it.size / 2f)) < it.size * 0.22f)
            }
            if (dragHover != nh) dragHover = nh
        } else {
            val info = gridState.layoutInfo
            val tgt = info.visibleItemsInfo.firstOrNull {
                it.key != pkg && x >= it.offset.x && x < it.offset.x + it.size.width && y >= it.offset.y && y < it.offset.y + it.size.height
            }
            val nh = tgt?.let {
                // середина плитки по ширине (значок можно бросить на иконку или подпись) — слияние, левый и правый край — вставка рядом
                val canMerge = shownRef.value.firstOrNull { e -> e.pkg == pkg }?.isFolder == false
                val dx = kotlin.math.abs(x - (it.offset.x + it.size.width / 2f)) / it.size.width
                (it.key as String) to (canMerge && dx < 0.3f)
            }
            if (dragHover != nh) dragHover = nh
        }
    }
    /** win: положение пальца в окне. Плитка, которую тянут, может уйти за экран (автопрокрутка) и не числиться среди видимых: от неё не зависим. */
    fun dragMove(pkg: String, win: androidx.compose.ui.geometry.Offset) {
        val c = gridLc[0]?.takeIf { it.isAttached } ?: return
        val p = c.windowToLocal(win)
        dragXY = p   // читается только в слое значка под пальцем: сетка не перекомпонуется
        hoverAt(pkg, p.x, p.y)
    }
    // Короткая остановка над краем плитки переставляет значок (180 мс вместо 320: отклик быстрее), в центре через 450 мс сливает в папку.
    // Читается через snapshotFlow: смена плитки под пальцем не перекомпонует весь экран (раньше каждый переход на новую плитку = перерисовка всех плиток).
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { dragHover }.collectLatest { h ->
            mergeReady = null
            if (h == null) return@collectLatest
            val m = moving ?: return@collectLatest
            if (h.second) {
                kotlinx.coroutines.delay(450)
                mergeReady = h.first
            } else {
                kotlinx.coroutines.delay(180)
                moveTo(m, shownRef.value.indexOfFirst { it.pkg == h.first }, follow = false)
                dragHover = null
            }
        }
    }
    // Плавная автопрокрутка у верхнего и нижнего края: скорость растёт по мере приближения пальца к краю и считается по времени кадра
    // (раньше при каждом движении пальца сетка прыгала на 40 px, а когда палец замирал, останавливалась).
    LaunchedEffect(Unit) {
      androidx.compose.runtime.snapshotFlow { dragging }.collectLatest { on ->
        if (!on) return@collectLatest
        var last = 0L
        while (true) {
            val t = androidx.compose.runtime.withFrameNanos { it }
            val dt = if (last == 0L) 16_666_667L else (t - last).coerceAtMost(50_000_000L)
            last = t
            val h = (if (Cfg.listMode) listState.layoutInfo.viewportSize.height else gridState.layoutInfo.viewportSize.height).toFloat()
            if (h <= 0f) continue
            val zone = (h * 0.14f).coerceIn(56f * dragDensity / 2f, 240f)
            val y = dragXY.y
            val v = (if (y < zone) -(zone - y) / zone else if (y > h - zone) (y - (h - zone)) / zone else 0f).coerceIn(-1f, 1f)
            if (v != 0f) {
                val px = v * 450f * dragDensity * (dt / 1_000_000_000f)    // до 450 dp/с у самого края
                val moved = if (Cfg.listMode) listState.scrollBy(px) else gridState.scrollBy(px)
                // плитки сдвинулись под неподвижным пальцем: цель перестановки пересчитываем без новых касаний
                val mp = moving
                if (moved != 0f && mp != null) hoverAt(mp, dragXY.x, dragXY.y)
            }
        }
      }
    }
    fun mergeInto(src: String, target: String) {
        if (src.startsWith("folder:") || src == target) return
        if (target.startsWith("folder:")) {
            Cfg.addToFolder(target.removePrefix("folder:"), listOf(src))
            Toast.makeText(ctx, ctx.getString(R.string.folder_added, Cfg.folderById(target.removePrefix("folder:"))?.name.orEmpty()), Toast.LENGTH_SHORT).show()
        } else {
            val name = ctx.getString(R.string.folder_default, Cfg.folders.size + 1)
            val id = Cfg.createFolder(name, listOf(target, src), target)
            Toast.makeText(ctx, ctx.getString(R.string.folder_created, name), Toast.LENGTH_SHORT).show()
            folderRename = id
        }
    }
    fun endDrag() {
        val m = moving
        val target = mergeReady
        dragHover = null
        mergeReady = null
        dragging = false
        dragEntry = null
        moving = null
        if (m != null && target != null) {
            // папка остаётся на месте плитки, на которую бросили значок: для этого включается «свой порядок» (раньше он включался в начале жеста)
            if (Cfg.sort != "custom") { saveOrder(shownRef.value.map { it.pkg }); Cfg.changeSort("custom") }
            mergeInto(m, target)
        }
    }
    val host = remember {
        object : HostOps {
            override fun openFolder(id: String) { folderOpen = id }
            override fun renameFolder(id: String) { folderRename = id }
            override fun addToFolder(id: String) { folderAdd = id }
            override fun openVersions(e: Entry) { versionsFor = e }
            override fun openUpdates(e: Entry) { updatesFor = e }
        }
    }
    fun moveKey(pkg: String, key: androidx.compose.ui.input.key.Key): Boolean {
        val i = shown.indexOfFirst { it.pkg == pkg }
        return when (key) {
            androidx.compose.ui.input.key.Key.DirectionLeft -> { moveTo(pkg, i - 1); true }
            androidx.compose.ui.input.key.Key.DirectionRight -> { moveTo(pkg, i + 1); true }
            androidx.compose.ui.input.key.Key.DirectionUp -> { moveTo(pkg, i - colsNow()); true }
            androidx.compose.ui.input.key.Key.DirectionDown -> { moveTo(pkg, i + colsNow()); true }
            else -> false
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalHost provides host) {
    Column(
        Modifier.fillMaxSize().background(if (HomeStore.menuBg.type == "none") t.bg else Color.Transparent)
            .focusRequester(rootFocus).focusable()
            .onPreviewKeyEvent { ev ->
                val nav = ev.key == Key.DirectionDown || ev.key == Key.DirectionUp || ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight ||
                    ev.key == Key.DirectionCenter || ev.key == Key.Enter
                if (ev.type == KeyEventType.KeyDown && nav && noDialogsBase && moving == null && !gridHasFocus && !CatFocus.hasFocus &&
                    (!headerHasFocus || ev.key == Key.DirectionDown)
                ) { restoreFocus(); true } else false
            }
            .padding(
                horizontal = if (narrow) 10.dp else if (shortWide) 20.dp else 32.dp,
                vertical = if (narrow) 10.dp else if (shortWide) 6.dp else 16.dp,
            ),
    ) {
        Column(Modifier.onFocusChanged { headerHasFocus = it.hasFocus }) {
        HalfUi {
        if (!Integrity.ok()) Text(stringResource(R.string.integrity_warn), color = Color(0xFFF87171), fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
        val leftButtons: @Composable () -> Unit = {
            // долгое нажатие «Дом» — уведомления
            if (Cfg.homeModule) HeaderTextButton(stringResource(R.string.home_btn), onLong = { TopUi.notifs.value = true }) { goHome(ctx) }
            HeaderTextButton(stringResource(R.string.restart)) { restartApp(ctx) }
            HeaderTextButton(stringResource(R.string.reset_icons)) {
                AppCache.clearIcons(ctx)
                Cfg.bumpIcons()
                Toast.makeText(ctx, ctx.getString(R.string.reset_icons_done), Toast.LENGTH_SHORT).show()
            }
            if (searching || query.isNotEmpty()) SearchBox(query, wide = !narrow) { query = it }
            else HeaderTextButton(stringResource(R.string.search_hint)) { searching = true }
        }
        val rightButtons: @Composable () -> Unit = {
            HeaderTextButton(if (Cfg.updFound > 0) stringResource(R.string.upd_badge, Cfg.updFound) else stringResource(R.string.upd_cell)) { updatesAll = true }
            HeaderTextButton(stringResource(R.string.settings), icon = Icons.Outlined.Settings) { screen = "settings" }
        }
        val headerButtons: @Composable () -> Unit = { leftButtons(); rightButtons() }
        if (twoRow) {
            // портрет: сверху «Приложения 128» и справа «Обновления» с шестерёнкой, под ними остальные кнопки в одну строку
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.title).uppercase(), fontSize = btnSp(19), color = Color(0xFF4ADE80), maxLines = 1, softWrap = false)
                Text(stringResource(R.string.count, shown.size), fontSize = btnSp(19), color = t.sub, maxLines = 1)
                Spacer(Modifier.weight(1f))
                rightButtons()
            }
            ShrinkToFit(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { leftButtons() }
            }
        } else {
            ShrinkToFit(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.title).uppercase(), fontSize = btnSp(19), color = Color(0xFF4ADE80), maxLines = 1, softWrap = false)
                    Text(stringResource(R.string.count, shown.size), fontSize = btnSp(19), color = t.sub, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                        Spacer(Modifier.weight(1f))
                    headerButtons()
                }
            }
        }
        // подсказка про стрелки нужна режиму «Переместить» с пульта; при перетаскивании пальцем она не вставляется над сеткой:
        // иначе вся сетка сдвигалась вниз на несколько строк в момент касания и возвращалась обратно при отпускании (главная причина «дёргания»)
        if (moveHintOn) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.move_hint), color = t.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Chip(stringResource(R.string.sel_done), true) { moving = null }
            }
        } else if (selectMode) {
            // панель выбора: пакетные действия над отмеченными значками
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.selected_n, selected.size), color = t.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
                Chip(stringResource(R.string.sel_all), false) { selected = shown.map { it.pkg }.toSet() }
                Chip(stringResource(R.string.sel_none), false) { selected = emptySet() }
                Chip(stringResource(R.string.sel_delete, selected.size), false) { if (selected.isNotEmpty()) confirmDelete = true }
                Chip(stringResource(R.string.hide), false) { selectedApps.forEach { if (!Cfg.isHidden(it.pkg)) Cfg.toggleHidden(it.pkg) }; endSelect() }
                Chip(stringResource(R.string.stop), false) {
                    scope.launch {
                        selectedApps.forEach {
                            if (Adb.isConnected) Actions.forceStop(it.pkg)
                            else runCatching { (ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).killBackgroundProcesses(it.pkg) }
                        }
                        Toast.makeText(ctx, ctx.getString(R.string.stopped_n, selectedApps.size), Toast.LENGTH_SHORT).show()
                        endSelect()
                    }
                }
                Chip(stringResource(R.string.sel_done), true) { endSelect() }
            }
        } else {
            // подсказка убирается нажатием на неё (возвращается в настройках); состояние Root/ADB в шапке только точкой, текст в настройках
            if (!narrow && Cfg.showHint) Text(
                stringResource(R.string.hint), fontSize = 12.sp, color = t.hint,
                modifier = Modifier.fillMaxWidth().clickable { Cfg.changeShowHint(false) }.padding(top = 4.dp, bottom = if (shortWide) 4.dp else 10.dp),
            )
            else Spacer(Modifier.height(6.dp))
        }
        }
        }
        val openApp: (Entry) -> Unit = openApp@{ e ->
            if (e.isFolder) { folderOpen = e.folderId; return@openApp }
            Cfg.countLaunch(e.pkg)
            val toHome = Cfg.returnTo == "home"
            // «Назад» из запущенной программы ведёт туда, куда выбрано: родной экран кладём под программу
            if (toHome && asHome) runCatching { nativeHomeIntent(ctx)?.let { ctx.startActivity(it) } }
            runCatching { ctx.startActivity(e.launch) }.onFailure {
                Toast.makeText(ctx, ctx.getString(R.string.cant_open), Toast.LENGTH_SHORT).show()
            }
            if (toHome && !asHome) (ctx as? android.app.Activity)?.let { a ->
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ a.moveTaskToBack(true) }, 300)
            }
        }
        val toggleSel: (Entry) -> Unit = { e -> if (!e.isFolder) selected = if (e.pkg in selected) selected - e.pkg else selected + e.pkg }
        val startSel: (Entry) -> Unit = { e -> if (!e.isFolder) { selectMode = true; selected = selected + e.pkg } }
        // Обработчики плиток читаются через rememberUpdatedState: их объекты не меняются при перекомпозиции экрана,
        // поэтому плитки не перерисовываются, пока их собственные состояния те же (раньше любая смена состояния перекомпоновывала все видимые плитки).
        val opsRef = androidx.compose.runtime.rememberUpdatedState(TileOps(
            click = { e -> if (moving != null) { if (moving != e.pkg) moveTo(moving!!, shown.indexOfFirst { it.pkg == e.pkg }) else moving = null } else if (selectMode) toggleSel(e) else openApp(e) },
            long = { e -> if (moving != null) { moving = null } else if (selectMode) toggleSel(e) else menuFor = e },
            startSel = { e -> startSel(e) }, startMove = { e -> startMove(e) }, moveKey = { p, k -> moveKey(p, k) },
            dragBegin = { e -> dragBegin(e) }, dragMove = { p, o -> dragMove(p, o) }, dragEnd = { endDrag() },
            closeMenu = { menuFor = null; Conn.refresh() }, focused = { p -> lastFocus = p; MenuMemory.setFocus(p, shownRef.value.indexOfFirst { it.pkg == p }) },
        ))
        val tileFor: @Composable (Entry, Boolean) -> Unit = { e, listRow ->
            val pkg = e.pkg
            // шесть признаков одним производным состоянием (раньше шесть derivedStateOf на каждую плитку)
            val bits by remember(pkg) {
                derivedStateOf {
                    (if (menuFor?.pkg == pkg) 1 else 0) or (if (pkg in selected) 2 else 0) or (if (moving == pkg) 4 else 0) or
                        (if (mergeReady == pkg) 8 else 0) or (if (dragging && moving == pkg) 16 else 0) or (if (pkg == lastFocus && !gridHasFocus) 32 else 0)
                }
            }
            val menuOpen = bits and 1 != 0
            val isSel = bits and 2 != 0
            val isMov = bits and 4 != 0
            val isMerge = bits and 8 != 0
            val isGhost = bits and 16 != 0
            val isMarked = bits and 32 != 0
            val cClick = remember(e) { { opsRef.value.click(e) } }
            val cLong = remember(e) { { opsRef.value.long(e) } }
            val cSel = remember(e) { { opsRef.value.startSel(e) } }
            val cMove = remember(e) { { opsRef.value.startMove(e) } }
            val cKey = remember(e) { { k: androidx.compose.ui.input.key.Key -> opsRef.value.moveKey(pkg, k) } }
            val cDragBegin = remember(e) { { opsRef.value.dragBegin(e) } }
            val cDragMove = remember(e) { { o: androidx.compose.ui.geometry.Offset -> opsRef.value.dragMove(pkg, o) } }
            val cDragEnd = remember(e) { { opsRef.value.dragEnd() } }
            val cClose = remember(e) { { opsRef.value.closeMenu() } }
            val cFocused = remember(e) { { opsRef.value.focused(pkg) } }
            AppTile(
                e, listRow, menuOpen, reqFor(pkg),
                selectMode, isSel, isMov,
                cClick, cLong, cSel, cMove, cKey, cDragBegin, cDragMove, cDragEnd, cClose,
                onFocused = cFocused, marked = isMarked, merge = isMerge,
                ghostActive = isGhost,
            )
        }
        folderOpen?.let { id -> HalfUi { FolderDialog(id, apps, menuFor, { menuFor = it }, openApp) { folderOpen = null; menuFor = null } } }
        val searchCat = query.trim().length >= 2
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        // приложений по запросу нет, а каталоги ищутся: пустую полосу не показываем, карточки занимают весь экран
        Box(if (searchCat && shown.isEmpty()) Modifier else Modifier.weight(if (searchCat) 0.36f else 1f)) {
        if (shown.isEmpty()) {
            if (!searchCat && (loadedOnce || query.isNotBlank())) Text(stringResource(R.string.nothing_found), color = t.sub, fontSize = 16.sp, modifier = Modifier.padding(top = 24.dp))
        } else if (Cfg.listMode) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().onGloballyPositioned { gridLc[0] = it }) {
            LazyColumn(Modifier.fillMaxSize().onFocusChanged { gridHasFocus = it.hasFocus }, state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.pkg }) { e -> Box(if (lite) Modifier else Modifier.animateItem(fadeInSpec = null, placementSpec = spring(stiffness = Spring.StiffnessMedium, visibilityThreshold = IntOffset.VisibilityThreshold), fadeOutSpec = null)) { tileFor(e, true) } }
            }
            DragGhostHost({ dragging }, { dragEntry }) { dragXY }
            }
        } else androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { gridLc[0] = it }) {
            // Правила сетки (все настройки работают вместе и не отменяют друг друга):
            //  • «Размер значков» и «Масштаб» задают размер значка (размер x масштаб) и ширину ячейки при авто-столбцах;
            //  • «Столбцы» задают число столбцов, значок при этом не шире своей ячейки;
            //  • «Строки» задают число строк, значок такой, чтобы все строки поместились по высоте;
            //  • «Плотность» меняет только отступы и зазоры.
            val h = maxHeight.value - 20f
            val fs = androidx.compose.ui.platform.LocalDensity.current.fontScale
            val lbEst = gridLabelSp(narrow)
            // высота плитки без значка: поля + зазор + две строки подписи
            val over = 2 * tilePad() + (if (Cfg.showLabels) (if (Cfg.gridGap == 0 || Cfg.iconSize == 3) 2f else 6f) + 2 * lbEst * fs * 1.32f else 0f)
            val gv = tileGap(narrow)
            val padH = if (narrow) 2f else 8f
            val capRows: Float? = if (Cfg.gridRows > 0) ((h - (Cfg.gridRows - 1) * gv) / Cfg.gridRows - over).coerceAtLeast(20f) else null
            val capCols: Float? = if (Cfg.columns > 0) (((maxWidth.value - 2 * padH - (Cfg.columns - 1) * gv) / Cfg.columns) - 2 * tilePad()).coerceAtLeast(20f) else null
            val cap: Dp? = listOfNotNull(capRows, capCols).minOrNull()?.dp
            val minCell: Dp = if (narrow) narrowCell().dp
                else ((if (Cfg.iconSize == 3) 76f else if (Cfg.iconSize == 0) 110f else if (Cfg.iconSize == 2) 190f else 150f) * Cfg.iconScale / 100f).coerceAtLeast(34f).dp
            // параметры подписи под значком (в px): ширина ячейки считается так же, как у сетки (Fixed или Adaptive). Подписи раскладываются заранее в фоне
            // (LabelCache), плитка только рисует готовую раскладку: Compose Text в плитке измерял текст прямо в кадре (стеки 60-165 мс в журнале магнитолы)
            val dn = androidx.compose.ui.platform.LocalDensity.current
            val dm = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics
            val denseOn = DenseGrid.on
            // плотная сетка (магнитолы): столбцы и строки считаются из размера экрана (7% ширины x 19,6% высоты), см. GridMetrics
            val sizeFactor = (when (Cfg.iconSize) { 3 -> 34f; 0 -> 52f; 2 -> 96f; else -> 72f } / 72f) * Cfg.iconScale / 100f
            val dense: GridMetrics.Dense? = if (!denseOn) null else with(dn) {
                val availPx = (maxWidth.roundToPx() - 2 * (if (narrow) 2.dp else 8.dp).roundToPx()).toFloat()
                GridMetrics.dense(
                    dm.widthPixels.toFloat(), dm.heightPixels.toFloat(), availPx, (maxHeight.toPx() - 20.dp.toPx()), dn.density,
                    gridLabelSp(narrow).sp.toPx(), if (Cfg.iconSize == 3) 1 else 2, sizeFactor, Cfg.columns, Cfg.gridRows, 2.dp.toPx(),
                )
            }
            val denseSpec: DenseSpec? = dense?.let { with(dn) { DenseSpec(it.platePx.toDp(), it.iconPx.toDp(), it.labelGapPx.toDp(), 2.dp) } }
            val labelSpec = remember(maxWidth, maxHeight, Cfg.columns, Cfg.gridRows, Cfg.iconSize, Cfg.iconScale, Cfg.gridGap, narrow, dn.density, dn.fontScale, Lite.mode, denseOn) {
                with(dn) {
                    if (dense != null) LabelSpec(gridLabelSp(narrow).sp.toPx(), dense.cellPx, 2.dp.toPx(), if (Cfg.iconSize == 3) 1 else 2, dense.cols)
                    else {
                        val avail = (maxWidth.roundToPx() - 2 * (if (narrow) 2.dp else 8.dp).roundToPx())
                        val gap = tileGap(narrow).dp.roundToPx()
                        val cnt = if (Cfg.columns > 0) Cfg.columns else if (narrow && Cfg.iconSize == 3) 5 else maxOf((avail + gap) / (minCell.roundToPx() + gap), 1)
                        val slot = (avail - gap * (cnt - 1)) / cnt
                        LabelSpec(gridLabelSp(narrow).sp.toPx(), slot.toFloat(), tilePad().dp.toPx(), if (Cfg.iconSize == 3) 1 else 2, cnt)
                    }
                }
            }
            // после списка приложений раскладка всех подписей готовится в фоне с пониженным приоритетом
            LaunchedEffect(labelSpec, shown, Cfg.showLabels, dense) {
                if (!Cfg.showLabels) return@LaunchedEffect
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                    val t0 = System.nanoTime()
                    for (e in shown) LabelCache.get(e.label, labelSpec)
                    // выступ в зазор получают только подписи, которым соседи в строке оставляют место (между подписями соседей всегда есть зазор)
                    val fresh = LabelCache.solveAll(shown.map { it.label }, labelSpec)
                    for ((k, v) in fresh) LabelCache.getWith(k.removeSuffix("|" + labelSpec.key), labelSpec, v)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { LabelCache.publish(labelSpec, fresh) }
                }
            }
            CompositionLocalProvider(LocalIconCap provides cap, LocalLabelSpec provides labelSpec, LocalDense provides denseSpec) {
            LazyVerticalGrid(
                columns = if (dense != null) GridCells.Fixed(dense.cols) else if (Cfg.columns > 0) GridCells.Fixed(Cfg.columns) else if (narrow && Cfg.iconSize == 3) GridCells.Fixed(5) else GridCells.Adaptive(minCell),
                modifier = Modifier.fillMaxSize().onFocusChanged { gridHasFocus = it.hasFocus },
                state = gridState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (narrow) 2.dp else 8.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(if (dense != null) 0.dp else tileGap(narrow).dp),
                verticalArrangement = Arrangement.spacedBy(if (dense != null) with(dn) { dense.gapYPx.toDp() } else tileGap(narrow).dp),
            ) {
                items(shown, key = { it.pkg }) { e -> Box(if (lite) Modifier else Modifier.animateItem(fadeInSpec = null, placementSpec = spring(stiffness = Spring.StiffnessMedium, visibilityThreshold = IntOffset.VisibilityThreshold), fadeOutSpec = null)) { tileFor(e, false) } }
            }
            DragGhostHost({ dragging }, { dragEntry }) { dragXY }
            }
        }
        }
        if (searchCat) CatalogResults(query, Modifier.weight(0.64f).fillMaxWidth())
        }
    }
    }

    if (TopUi.notifs.value) NotificationsDialog { TopUi.notifs.value = false }
    if (TopUi.recents.value) RecentsDialog(apps, { e -> Cfg.countLaunch(e.pkg); runCatching { ctx.startActivity(e.launch) } }) { TopUi.recents.value = false }
    folderRename?.let { HalfUi { RenameFolderDialog(it) { folderRename = null } } }
    folderAdd?.let { HalfUi { AddToFolderDialog(it, apps) { folderAdd = null } } }
    versionsFor?.let { e -> HalfUi { VersionsDialog(e.pkg, e.label) { versionsFor = null } } }
    updatesFor?.let { e -> HalfUi { UpdatesDialog(e.pkg, e.label) { updatesFor = null } } }
    if (updatesAll) HalfUi { UpdatesAllDialog { updatesAll = false } }

    if (confirmDelete) {
        val removable = selectedApps.filter { !it.system }
        val skipped = selectedApps.size - removable.size
        val noFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { noFocus.requestFocus() } }
        HDialog(onDismissRequest = { confirmDelete = false }) {
            Column(
                Modifier.clip(RoundedCornerShape(20.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(20.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.del_title, removable.size), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(removable.take(8).joinToString("\n") { "• " + it.label } + if (removable.size > 8) "\n…" else "", color = t.sub, fontSize = 14.sp)
                if (skipped > 0) Text(stringResource(R.string.del_skipped, skipped), color = t.hint, fontSize = 12.sp)
                Text(stringResource(if (Adb.isConnected) R.string.del_silent else R.string.del_dialogs), color = t.hint, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip(stringResource(R.string.cancel), false, Modifier.focusRequester(noFocus)) { confirmDelete = false }
                    Chip(stringResource(R.string.del_yes), true) {
                        confirmDelete = false
                        val pkgs = removable.map { it.pkg }
                        if (pkgs.isNotEmpty()) {
                            // сначала копии APK (если включено), потом удаление
                            AppBackups.saveThen(ctx, pkgs) {
                                if (Adb.isConnected) {
                                    scope.launch {
                                        val (ok, bad) = Batch.uninstallViaAdb(pkgs)
                                        Toast.makeText(ctx, ctx.getString(R.string.del_result, ok, bad), Toast.LENGTH_LONG).show()
                                        reload++
                                    }
                                } else {
                                    Pending.uninstalls.addAll(pkgs)
                                    Pending.next(ctx)
                                }
                            }
                        }
                        endSelect()
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBox(value: String, wide: Boolean = true, onChange: (String) -> Unit) {
    val t = LocalTh.current
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    OutlinedTextField(
        value, onChange,
        placeholder = { Text(stringResource(R.string.search_hint), fontSize = 13.sp) },
        leadingIcon = { Icon(Icons.Outlined.Search, null, tint = t.sub) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = t.text, unfocusedTextColor = t.text,
            focusedBorderColor = t.accent, unfocusedBorderColor = t.border, cursorColor = t.accent,
        ),
        modifier = (if (wide) Modifier.height(52.dp).fillMaxWidth(0.3f) else Modifier.height(52.dp).width(360.dp)).focusRequester(fr),
    )
}

/** Кнопка шапки со словом: на телевизоре нет подсказок при наведении, поэтому значков без подписи нет. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeaderTextButton(text: String, onLong: (() -> Unit)? = null, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    val t = LocalTh.current
    var focused by remember { mutableStateOf(false) }
    val (hm, hover) = rememberHover()
    val small = false // размеры задаёт HalfUi
    Box(
        Modifier.heightIn(min = 48.dp)
            .then(hm)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused || hover) t.tileFocus else t.tile)
            .border(if (focused) 3.dp else 1.dp, if (focused) t.accent else t.border, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = if (isNarrow()) 11.dp else 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) androidx.compose.material3.Icon(icon, contentDescription = text, tint = t.text, modifier = Modifier.size(if (isNarrow()) 52.dp else 34.dp))
        else Text(text, fontSize = btnSp(16), color = t.text, maxLines = 1)
    }
}

/** Размеры плашки и значка плотной сетки. */
internal class DenseSpec(val plate: Dp, val icon: Dp, val labelGap: Dp, val pad: Dp)
internal val LocalDense = androidx.compose.runtime.compositionLocalOf<DenseSpec?> { null }

private val NoHover: Pair<Modifier, Boolean> = Modifier to false
private val NoTextLayout: (androidx.compose.ui.text.TextLayoutResult) -> Unit = {}

/** Обработчики плиток одним объектом: берутся через rememberUpdatedState, чтобы лямбды плиток не менялись при перекомпозиции экрана. */
private class TileOps(
    val click: (Entry) -> Unit, val long: (Entry) -> Unit, val startSel: (Entry) -> Unit, val startMove: (Entry) -> Unit,
    val moveKey: (String, androidx.compose.ui.input.key.Key) -> Boolean,
    val dragBegin: (Entry) -> Unit, val dragMove: (String, androidx.compose.ui.geometry.Offset) -> Unit, val dragEnd: () -> Unit,
    val closeMenu: () -> Unit, val focused: (String) -> Unit,
)

/** «Назад» во время переноса значка: своя область композиции, чтобы начало и конец переноса не перекомпоновывали тело экрана. */
@Composable
private fun MoveBackHandler(active: () -> Boolean, onBack: () -> Unit) {
    val on = active()
    BackHandler(enabled = on, onBack = onBack)
}

/** Значок под пальцем: active и entry читаются здесь, в своей области. */
@Composable
private fun DragGhostHost(active: () -> Boolean, entry: () -> Entry?, pos: () -> androidx.compose.ui.geometry.Offset) {
    DragGhost(active(), entry(), pos)
}

/** Значок, который «летит» под пальцем при перетаскивании. Положение читается только в слое отрисовки: сетка из-за движения пальца не перекомпонуется. */
@Composable
private fun DragGhost(active: Boolean, e: Entry?, pos: () -> androidx.compose.ui.geometry.Offset) {
    if (!active || e == null) return
    val bmp = remember(e.pkg, e.icon, e.bmp) { e.bmp ?: e.icon.toBitmap(128, 128).asImageBitmap() }
    val cap = LocalIconCap.current
    val base = if (isNarrow()) iconDp().coerceAtMost((narrowCell() - 2 * tilePad()).coerceAtLeast(24f).dp) else iconDp()
    val dnsp = LocalDense.current
    val size = if (dnsp != null) dnsp.plate * 1.1f else (if (cap != null) base.coerceAtMost(cap) else base) * 1.18f
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.toPx() }
    Image(bmp, null, Modifier.size(size).graphicsLayer {
        val p = pos()
        translationX = p.x - px / 2f
        translationY = p.y - px * 0.75f   // чуть выше пальца, чтобы палец не закрывал значок
        alpha = 0.95f
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha   // без отдельного буфера под прозрачность
        shadowElevation = 14f * density
        shape = RoundedCornerShape(18.dp)
        clip = false
    })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppTile(
    e: Entry, listRow: Boolean, menuOpen: Boolean, focusReq: FocusRequester?,
    selectMode: Boolean, isSelected: Boolean, isMoving: Boolean,
    onOpen: () -> Unit, onMenu: () -> Unit, onStartSelect: () -> Unit, onStartMove: () -> Unit,
    onMoveKey: (androidx.compose.ui.input.key.Key) -> Boolean,
    onDragBegin: () -> Unit, onDragMove: (androidx.compose.ui.geometry.Offset) -> Unit, onDragEnd: () -> Unit,
    onCloseMenu: () -> Unit,
    onFocused: () -> Unit = {}, marked: Boolean = false, merge: Boolean = false,
    /** Мелкий значок для окна папки. */
    compact: Boolean = false,
    /** Значок этой плитки сейчас «летит» под пальцем отдельным слоем: на месте плитка бледнеет. */
    ghostActive: Boolean = false,
) {
    val iconCap = LocalIconCap.current
    val dense = if (compact) null else LocalDense.current
    val baseIc = if (isNarrow()) iconDp().coerceAtMost((narrowCell() - 2 * tilePad()).coerceAtLeast(24f).dp) else iconDp()
    val icSize = if (compact) 44.dp else if (iconCap != null) baseIc.coerceAtMost(iconCap) else baseIc
    val narrowScreen = isNarrow()
    // подпись крупнее прежнего: на магнитоле (узкий по dp экран) было 9 sp и текст читался плохо
    val lbSp = if (compact) 11 else gridLabelSp(narrowScreen)
    val dragBegin by androidx.compose.runtime.rememberUpdatedState(onDragBegin)
    val dragMove by androidx.compose.runtime.rememberUpdatedState(onDragMove)
    val dragEnd by androidx.compose.runtime.rememberUpdatedState(onDragEnd)
    val t = LocalTh.current
    var focused by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var downAt by remember { mutableStateOf(0L) }
    val okScope = androidx.compose.runtime.rememberCoroutineScope()
    val dragFlag = remember { booleanArrayOf(false) }   // палец уже потянул значок: меню действий открывать не надо
    val tileLc = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // пока значок тянут, плитка закреплена в ленивой сетке: уйдя за край при автопрокрутке, она не разрушается и жест не обрывается
    val pinContainer = LocalPinnableContainer.current
    if (ghostActive) DisposableEffect(Unit) {
        val handle = pinContainer?.pin()
        onDispose { handle?.release() }
    }
    // облегчённый режим (магнитолы): без наведения мыши (отдельный источник событий и корутина на каждую плитку) и без анимации масштаба
    val lite = Lite.on
    val (hm, hover) = if (lite) NoHover else rememberHover()
    val scaleTarget = if (merge) 1.12f else if (focused || hover) 1.05f else 1f
    val scale = if (lite) scaleTarget else animateFloatAsState(scaleTarget, label = "scale").value
    val bmp = remember(e.pkg, e.icon, e.bmp) { e.bmp ?: e.icon.toBitmap(128, 128).asImageBitmap() }
    val hiddenMark = Cfg.isHidden(e.pkg)
    val tileAlpha = if (ghostActive) 0.28f else if (hiddenMark) 0.5f else 1f
    Box {
        val base = Modifier
            // один слой вместо двух (scale и alpha были отдельными слоями); без слоя, если плитка в покое
            .then(if (scale != 1f || tileAlpha != 1f) Modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = tileAlpha } else Modifier)
            .then(hm)
            .then(if (focusReq != null) Modifier.focusRequester(focusReq) else Modifier)
            // пульт: короткое «ОК» запускает, долгое «ОК» или кнопка «Меню» открывают список действий
            .onPreviewKeyEvent { ev ->
                if (isMoving) {
                    // в режиме перемещения стрелки двигают значок, «ОК» заканчивает
                    return@onPreviewKeyEvent when (ev.key) {
                        Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown ->
                            if (ev.type == KeyEventType.KeyDown) onMoveKey(ev.key) else true
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (ev.type == KeyEventType.KeyUp) onOpen(); true }
                        else -> false
                    }
                }
                when (ev.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        when (ev.type) {
                            KeyEventType.KeyDown -> {
                                val rc = ev.nativeKeyEvent.repeatCount
                                if (rc == 0) {
                                    pending = true
                                    downAt = android.os.SystemClock.uptimeMillis()
                                    // у части пультов при удержании нет повторов: долгое нажатие определяем по времени
                                    okScope.launch {
                                        kotlinx.coroutines.delay(LONG_OK_MS)
                                        if (pending) { pending = false; OkGuard.arm(); onMenu() }
                                    }
                                } else if (pending) {
                                    pending = false; OkGuard.arm(); onMenu()
                                }
                            }
                            KeyEventType.KeyUp -> if (pending) {
                                pending = false
                                if (android.os.SystemClock.uptimeMillis() - downAt >= LONG_OK_MS) onMenu() else onOpen()
                            }
                            else -> {}
                        }
                        true
                    }
                    Key.Menu, Key.Info -> { if (ev.type == KeyEventType.KeyDown) onMenu(); true }
                    else -> false
                }
            }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            // рамка только когда она нужна (в покое плитка без узла рамки: меньше узлов и рисования)
            .then(
                if (merge || focused || isSelected || isMoving || marked || hover) Modifier.border(
                    if (merge) 4.dp else if (focused || isSelected || isMoving) 3.dp else if (marked) 2.dp else 1.dp,
                    if (merge || isMoving) Color(0xFFF59E0B) else if (isSelected) t.accent else if (focused || hover) t.accent
                    else t.accent.copy(alpha = 0.55f),
                    RoundedCornerShape(14.dp),
                ) else Modifier
            )
            .background(
                if (isSelected) t.chipSel else if (focused || hover || merge) t.tileFocus else if (Cfg.transparentTiles || (dense != null && !listRow)) Color.Transparent else t.tile,
                RoundedCornerShape(14.dp),
            )
            // правая кнопка мыши (и аэромыши) открывает те же действия, что долгое касание
            .mouseSecondary(onMenu)
            // долгое нажатие открывает меню, но с короткой задержкой: если палец сразу потянул (перетаскивание), меню не успевает мигнуть
            .then(
                // в облегчённом режиме явный вариант без волны нажатия и без composed-модификатора (дешевле в создании)
                if (lite) Modifier.combinedClickable(
                    interactionSource = null, indication = null, onClick = onOpen,
                    onLongClick = { dragFlag[0] = false; okScope.launch { kotlinx.coroutines.delay(170); if (!dragFlag[0]) onMenu() } },
                )
                else Modifier.combinedClickable(onClick = onOpen, onLongClick = {
                    dragFlag[0] = false
                    okScope.launch { kotlinx.coroutines.delay(170); if (!dragFlag[0]) onMenu() }
                })
            )
            // долгое нажатие открывает меню, а если после него потянуть — значок перетаскивается (как у Samsung)
            .onGloballyPositioned { tileLc[0] = it }
            .pointerInput(e.pkg) {
                var origin = androidx.compose.ui.geometry.Offset.Zero
                var started = false
                detectDragGesturesAfterLongPress(
                    onDragStart = { origin = it; started = false; dragFlag[0] = false },
                    onDrag = { change, _ ->
                        change.consume()
                        if (!started && (change.position - origin).getDistance() > viewConfiguration.touchSlop) { started = true; dragFlag[0] = true; dragBegin() }
                        if (started) tileLc[0]?.takeIf { it.isAttached }?.let { dragMove(it.localToWindow(change.position)) }
                    },
                    onDragEnd = { if (started) dragEnd(); dragFlag[0] = false },
                    onDragCancel = { if (started) dragEnd(); dragFlag[0] = false },
                )
            }
            .padding(if (dense != null && !listRow) dense.pad else tilePad().dp)
        if (listRow) {
            Row(base.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Image(bmp, null, Modifier.size(iconDp().coerceAtMost(64.dp)))
                Column(Modifier.weight(1f)) {
                    if (Cfg.showLabels) Text(e.label, fontSize = (labelSp() + 2).sp, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(e.pkg, fontSize = 11.sp, color = t.hint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Column(base, horizontalAlignment = Alignment.CenterHorizontally) {
                if (dense != null) {
                    // плотная сетка: значок на тёмной плашке с тонкой каймой, без теней (как у системного лаунчера магнитолы)
                    val shape = RoundedCornerShape(percent = 20)
                    Box(
                        Modifier.size(dense.plate)
                            .then(if (Cfg.transparentTiles) Modifier else Modifier.background(t.tile, shape))
                            .border(1.dp, t.border, shape),
                        contentAlignment = Alignment.Center,
                    ) { Image(bmp, null, Modifier.size(dense.icon)) }
                    Spacer(Modifier.height(dense.labelGap))
                } else Image(bmp, null, Modifier.size(icSize))
                if (Cfg.showLabels) {
                    if (dense == null) Spacer(Modifier.height(if (Cfg.gridGap == 0 || Cfg.iconSize == 3) 2.dp else 6.dp))
                    // две строки всегда: иначе плитка с длинным названием выше соседних и фон ряда «ломается»
                    // сетка: готовая раскладка из LabelCache (слова не рвутся, длинное слово выступает в зазор, шрифт сужается только у этой подписи);
                    // запасной путь на Text остаётся для списка и окна папки, где ширина заранее не известна
                    val spec = LocalLabelSpec.current
                    if (spec != null) {
                        val ll = LabelCache.get(e.label, spec)
                        val textColor = t.text.toArgb()
                        val hDp = with(androidx.compose.ui.platform.LocalDensity.current) { ll.heightPx.toDp() }
                        Spacer(
                            Modifier.fillMaxWidth().height(hDp).semantics { contentDescription = e.label }.drawBehind {
                                drawIntoCanvas { c ->
                                    val nc = c.nativeCanvas
                                    nc.save()
                                    nc.translate((size.width - ll.widthPx) / 2f, ll.offsetY)
                                    ll.layout.paint.color = textColor
                                    ll.layout.draw(nc)
                                    nc.restore()
                                }
                            },
                        )
                    } else {
                    val lf = LocalLabelFit.current
                    val dens = androidx.compose.ui.platform.LocalDensity.current
                    val baseStyle = androidx.compose.material3.LocalTextStyle.current.merge(androidx.compose.ui.text.TextStyle(textAlign = TextAlign.Center))
                    val lblLines = if (Cfg.iconSize == 3) 1 else 2
                    var lblSize by remember(e.label, lbSp, lf?.widthPx) {
                        mutableStateOf(if (lf != null) LabelSizes.fit(lf, e.label, lbSp.toFloat(), baseStyle, dens, lblLines) else lbSp.toFloat())
                    }
                    Text(
                        e.label, fontSize = lblSize.sp, lineHeight = (lblSize * 1.32f).sp, color = t.text, minLines = lblLines, maxLines = lblLines,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                        onTextLayout = if (lf != null) NoTextLayout else ({ r: androidx.compose.ui.text.TextLayoutResult ->
                            if (LabelSizes.cutsWord(r, e.label) && lblSize > lbSp * 0.6f) lblSize -= 0.5f
                        }),
                    )
                    }
                }
            }
        }
        if (selectMode && !e.isFolder) {
            // отметка выбора в углу значка
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape)
                    .background(if (isSelected) t.accent else Color(0x66000000))
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (isSelected) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
        if (menuOpen) {
            HalfUi {
                if (e.isFolder) FolderMenu(e.folderId, onCloseMenu)
                else AppMenu(
                    e, hidden = hiddenMark, onToggleHide = { Cfg.toggleHidden(e.pkg) },
                    onSelect = onStartSelect, onMove = onStartMove, onClose = onCloseMenu,
                )
            }
        }
    }
}


/** Возврат на экран «Дом»: он живёт в своей задаче, поэтому открывается сразу. */
internal fun goHome(ctx: android.content.Context) {
    HomeNav.fromMenu = true
    runCatching { ctx.startActivity(Intent(ctx, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    (ctx as? android.app.Activity)?.overridePendingTransition(0, 0)
}

/** Перезапуск программы: новый запуск назначается через будильник, затем текущий процесс завершается. */
private fun restartApp(ctx: android.content.Context) {
    val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    val pi = android.app.PendingIntent.getActivity(ctx, 77, intent, android.app.PendingIntent.FLAG_CANCEL_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
    (ctx.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager)
        .set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 400, pi)
    Toast.makeText(ctx, ctx.getString(R.string.restarting), Toast.LENGTH_SHORT).show()
    android.os.Process.killProcess(android.os.Process.myPid())
}


/** Родные главные экраны устройства (кроме этой программы). */
internal fun nativeHomes(ctx: android.content.Context): List<android.content.pm.ResolveInfo> =
    ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        // служебная заставка загрузки Android без названия не годится как главный экран
        .filter { !it.activityInfo.name.endsWith("FallbackHome") && it.loadLabel(ctx.packageManager).toString().isNotBlank() }

/** Запуск родного главного экрана: выбранного в настройках или первого найденного. */
internal fun nativeHomeIntent(ctx: android.content.Context): Intent? {
    val list = nativeHomes(ctx)
    val ri = list.firstOrNull { it.activityInfo.packageName == Cfg.nativeHome } ?: list.firstOrNull() ?: return null
    return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        .setClassName(ri.activityInfo.packageName, ri.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
