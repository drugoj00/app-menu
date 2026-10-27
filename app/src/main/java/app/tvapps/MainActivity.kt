package app.tvapps

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)
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
        asHome = intent?.categories?.contains(Intent.CATEGORY_HOME) == true
        applyFullscreen()
        setContent {
            val tc = currentTC()
            MaterialTheme(colorScheme = if (tc.dark) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalTh provides tc) {
                    AllApps(resumeTick.intValue, asHome, onFullscreenChanged = { applyFullscreen() })
                }
            }
        }
    }

    // после удаления приложения, возврата из сведений или кнопки «Домой» список обновляется;
    // если есть очередь пакетных удалений или установок без ADB, открывается следующее окно
    override fun onResume() {
        super.onResume()
        onScreen = true
        resumeTick.intValue++
        applyFullscreen()
        Pending.next(this)
    }

    override fun onStop() {
        super.onStop()
        onScreen = false
        runCatching { unregisterReceiver(homeKeyReceiver) }
        Backup.autoSave(this)
    }

    override fun onNewIntent(intent: Intent) {
        // на некоторых устройствах «Домой» при открытом меню приходит сюда: это тоже второе нажатие
        val isHome = intent.categories?.contains(Intent.CATEGORY_HOME) == true
        // меню на экране: на новых Android оно уже на паузе в момент нажатия, но ещё не остановлено
        val secondHome = isHome && onScreen
        super.onNewIntent(intent)
        setIntent(intent)
        asHome = isHome
        if (secondHome) goNativeHome()
    }

    /** Полный экран: строки состояния и навигации скрыты и появляются по свайпу от края. */
    private fun applyFullscreen() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (Cfg.fullscreen) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }
    }
}

internal fun loadApps(ctx: android.content.Context): List<Entry> {
    val pm = ctx.packageManager
    val seen = HashSet<String>()
    val out = ArrayList<Entry>()
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
            val entry = Entry(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm), ai.flags and ApplicationInfo.FLAG_SYSTEM != 0, launch,
                pi?.firstInstallTime ?: 0L, pi?.lastUpdateTime ?: 0L)
            val own = IconPacks.override(ctx, entry)
            val finalIcon = own ?: entry.icon
            out.add(entry.copy(icon = finalIcon, bmp = runCatching { finalIcon.toBitmap(128, 128).asImageBitmap() }.getOrNull()))
        }
    }
    return out
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

private fun iconDp(): Dp = when (Cfg.iconSize) { 3 -> 34.dp; 0 -> 52.dp; 2 -> 96.dp; else -> 72.dp }
private fun labelSp() = when (Cfg.iconSize) { 3 -> 10; 0 -> 12; 2 -> 16; else -> 14 }

@Composable
private fun AllApps(tick: Int, asHome: Boolean, onFullscreenChanged: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var menuFor by remember { mutableStateOf<Entry?>(null) }
    var screen by remember { mutableStateOf("main") }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<String?>(null) }
    var folderOpen by remember { mutableStateOf<String?>(null) }
    var folderRename by remember { mutableStateOf<String?>(null) }
    var folderAdd by remember { mutableStateOf<String?>(null) }
    var versionsFor by remember { mutableStateOf<Entry?>(null) }
    // перетаскивание: над какой плиткой палец (и в центре ли) и готова ли папка принять значок
    var dragHover by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var mergeReady by remember { mutableStateOf<String?>(null) }
    // место курсора запоминается, чтобы после прокрутки и возвращения из программы оно не терялось
    // только пока программа жива (уход «Домой» и возврат): после выгрузки из памяти курсор снова на первом значке
    var lastFocus by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var gridHasFocus by remember { mutableStateOf(false) }
    var headerHasFocus by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val reqs = remember { HashMap<String, FocusRequester>() }
    fun reqFor(p: String): FocusRequester = reqs.getOrPut(p) { FocusRequester() }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick, Cfg.iconTick, reload) {
        // первый кадр рисуется сразу, список из кэша подставляется из фона, затем настоящая загрузка обновляет его
        if (apps.isEmpty()) {
            val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { AppCache.load(ctx) }
            if (cached.isNotEmpty() && apps.isEmpty()) apps = cached
        }
        apps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { loadApps(ctx).also { AppCache.save(ctx, it, force = Cfg.iconTick > 0) } }
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
    }

    // «Назад»: из настроек — на главную страницу; из выбора — выйти из выбора; как главный экран — остаёмся на месте
    BackHandler(enabled = screen != "main" || asHome || searching || selectMode || moving != null) {
        if (moving != null) moving = null
        else if (screen != "main") screen = "main"
        else if (selectMode) { selectMode = false; selected = emptySet() }
        else if (searching) { searching = false; query = "" }
        else if (asHome) runCatching { nativeHomeIntent(ctx)?.let { ctx.startActivity(it) } }
    }

    if (screen != "main") {
        HalfUi { SettingsScreen(apps.sortedBy { it.label.lowercase() }, onBack = { screen = "main" }, onFullscreenChanged = onFullscreenChanged) }
        return
    }

    @Suppress("UNUSED_VARIABLE") val dependsOn = Cfg.launchTick
    val items = remember(apps, Cfg.folders, Cfg.iconTick, Cfg.showHidden, Cfg.hidden) { withFolders(ctx, apps) }
    val shown = arrange(if (query.isBlank()) items else apps, query)
    val shownRef = androidx.compose.runtime.rememberUpdatedState(shown)
    // на узком экране (телефон в портрете) шапка идёт двумя строками, значки мельче
    val narrow = isNarrow()
    val rootView = androidx.compose.ui.platform.LocalView.current
    /** Ставит курсор на запомненный значок (прокручивая к нему), а если его нет — на первый. */
    fun restoreFocus() {
        val list = shownRef.value
        val p = lastFocus?.takeIf { k -> list.any { it.pkg == k } } ?: list.firstOrNull()?.pkg ?: return
        val idx = list.indexOfFirst { it.pkg == p }
        scope.launch {
            val visible = if (Cfg.listMode) listState.layoutInfo.visibleItemsInfo.any { it.key == p } else gridState.layoutInfo.visibleItemsInfo.any { it.key == p }
            if (!visible) runCatching { if (Cfg.listMode) listState.scrollToItem(idx) else gridState.scrollToItem(idx) }
            kotlinx.coroutines.delay(80)
            runCatching { reqFor(p).requestFocus() }
        }
    }
    LaunchedEffect(shown.isNotEmpty()) {
        if (shown.isNotEmpty()) {
            kotlinx.coroutines.delay(250)
            runCatching { rootView.requestFocus() }
            restoreFocus()
        }
    }
    val noDialogs = menuFor == null && folderOpen == null && folderRename == null && folderAdd == null && versionsFor == null && !confirmDelete && moving == null
    // фокус потерян (например, значок с курсором ушёл за край при прокрутке): держим его на корне, чтобы первая стрелка вернула курсор на место
    LaunchedEffect(gridHasFocus, headerHasFocus, noDialogs) {
        if (!gridHasFocus && !headerHasFocus && noDialogs) {
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
    fun moveTo(pkg: String, newIndex: Int) {
        val list = shownRef.value.map { it.pkg }.toMutableList()
        val i = list.indexOf(pkg)
        if (i < 0) return
        val j = newIndex.coerceIn(0, list.lastIndex)
        if (i == j) return
        list.removeAt(i)
        list.add(j, pkg)
        saveOrder(list)
        scope.launch { runCatching { if (Cfg.listMode) listState.animateScrollToItem(j) else gridState.animateScrollToItem(j) } }
    }
    // перетаскивание пальцем или мышью: значок тянут по сетке. Короткая остановка у края плитки переставляет его на её место,
    // более долгая в центре плитки сливает: два значка становятся папкой, значок на папке попадает в неё
    fun dragBegin(e: Entry) { menuFor = null; dragHover = null; mergeReady = null; startMove(e) }
    fun dragMove(pkg: String, local: androidx.compose.ui.geometry.Offset) {
        val canMerge = shownRef.value.firstOrNull { it.pkg == pkg }?.isFolder == false
        if (Cfg.listMode) {
            val info = listState.layoutInfo
            val me = info.visibleItemsInfo.firstOrNull { it.key == pkg } ?: return
            val y = me.offset + local.y
            val tgt = info.visibleItemsInfo.firstOrNull { it.key != pkg && y >= it.offset && y < it.offset + it.size }
            val nh = tgt?.let { (it.key as String) to (canMerge && kotlin.math.abs(y - (it.offset + it.size / 2f)) < it.size * 0.22f) }
            if (dragHover != nh) dragHover = nh
            if (y < 80f) scope.launch { listState.scrollBy(-40f) } else if (y > info.viewportSize.height - 80f) scope.launch { listState.scrollBy(40f) }
        } else {
            val info = gridState.layoutInfo
            val me = info.visibleItemsInfo.firstOrNull { it.key == pkg } ?: return
            val x = me.offset.x + local.x
            val y = me.offset.y + local.y
            val tgt = info.visibleItemsInfo.firstOrNull {
                it.key != pkg && x >= it.offset.x && x < it.offset.x + it.size.width && y >= it.offset.y && y < it.offset.y + it.size.height
            }
            val nh = tgt?.let {
                // середина плитки по ширине (значок можно бросить на иконку или подпись) — слияние, левый и правый край — вставка рядом
                val dx = kotlin.math.abs(x - (it.offset.x + it.size.width / 2f)) / it.size.width
                (it.key as String) to (canMerge && dx < 0.3f)
            }
            if (dragHover != nh) dragHover = nh
            if (y < 80f) scope.launch { gridState.scrollBy(-40f) } else if (y > info.viewportSize.height - 80f) scope.launch { gridState.scrollBy(40f) }
        }
    }
    LaunchedEffect(dragHover) {
        mergeReady = null
        val h = dragHover ?: return@LaunchedEffect
        val m = moving ?: return@LaunchedEffect
        if (h.second) {
            kotlinx.coroutines.delay(450)
            mergeReady = h.first
        } else {
            kotlinx.coroutines.delay(320)
            moveTo(m, shownRef.value.indexOfFirst { it.pkg == h.first })
            dragHover = null
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
        moving = null
        if (m != null && target != null) mergeInto(m, target)
    }
    val host = remember {
        object : HostOps {
            override fun openFolder(id: String) { folderOpen = id }
            override fun renameFolder(id: String) { folderRename = id }
            override fun addToFolder(id: String) { folderAdd = id }
            override fun openVersions(e: Entry) { versionsFor = e }
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
        Modifier.fillMaxSize().background(t.bg)
            .focusRequester(rootFocus).focusable()
            .onPreviewKeyEvent { ev ->
                val nav = ev.key == Key.DirectionDown || ev.key == Key.DirectionUp || ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight ||
                    ev.key == Key.DirectionCenter || ev.key == Key.Enter
                if (ev.type == KeyEventType.KeyDown && nav && noDialogs && !gridHasFocus &&
                    (!headerHasFocus || ev.key == Key.DirectionDown)
                ) { restoreFocus(); true } else false
            }
            .padding(horizontal = if (narrow) 10.dp else 32.dp, vertical = if (narrow) 10.dp else 16.dp),
    ) {
        Column(Modifier.onFocusChanged { headerHasFocus = it.hasFocus }) {
        HalfUi {
        val adbText = stringResource(Conn.statusRes)
        val headerButtons: @Composable () -> Unit = {
            HeaderTextButton(stringResource(R.string.restart)) { restartApp(ctx) }
            HeaderTextButton(stringResource(R.string.reset_icons)) {
                AppCache.clearIcons(ctx)
                Cfg.bumpIcons()
                Toast.makeText(ctx, ctx.getString(R.string.reset_icons_done), Toast.LENGTH_SHORT).show()
            }
            if (searching || query.isNotEmpty()) SearchBox(query, wide = !narrow) { query = it }
            else HeaderTextButton(stringResource(R.string.search_hint)) { searching = true }
            HeaderTextButton(stringResource(R.string.settings)) { screen = "settings" }
        }
        if (narrow && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 720) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.title), fontSize = 20.sp, color = t.text, maxLines = 1, softWrap = false)
                Text(stringResource(R.string.count, shown.size), fontSize = 13.sp, color = t.sub, maxLines = 1)
            }
            Text(adbText, fontSize = 11.sp, color = if (Conn.connected) t.accent else t.sub, maxLines = 1)
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { headerButtons() }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.title), fontSize = 24.sp, color = t.text)
                Text(stringResource(R.string.count, shown.size), fontSize = 15.sp, color = t.sub, modifier = Modifier.padding(start = 6.dp))
                Spacer(Modifier.weight(1f))
                headerButtons()
                Text(adbText, fontSize = 12.sp, color = if (Conn.connected) t.accent else t.sub)
            }
        }
        if (moving != null) {
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
            if (!narrow) Text(stringResource(R.string.hint), fontSize = 12.sp, color = t.hint, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
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
        val tileFor: @Composable (Entry, Boolean) -> Unit = { e, listRow ->
            AppTile(
                e, listRow, menuFor?.pkg == e.pkg, reqFor(e.pkg),
                selectMode, e.pkg in selected, moving == e.pkg,
                { if (moving != null) { if (moving != e.pkg) moveTo(moving!!, shown.indexOfFirst { it.pkg == e.pkg }) else moving = null } else if (selectMode) toggleSel(e) else openApp(e) },
                { if (moving != null) { moving = null } else if (selectMode) toggleSel(e) else menuFor = e },
                { startSel(e) }, { startMove(e) }, { moveKey(e.pkg, it) }, { dragBegin(e) }, { dragMove(e.pkg, it) }, { endDrag() }, { menuFor = null; Conn.refresh() },
                onFocused = { lastFocus = e.pkg }, marked = e.pkg == lastFocus && !gridHasFocus, merge = mergeReady == e.pkg,
            )
        }
        folderOpen?.let { id -> HalfUi { FolderDialog(id, apps, menuFor, { menuFor = it }, openApp) { folderOpen = null; menuFor = null } } }
        if (shown.isEmpty()) {
            Text(stringResource(R.string.nothing_found), color = t.sub, fontSize = 16.sp, modifier = Modifier.padding(top = 24.dp))
        } else if (Cfg.listMode) {
            LazyColumn(Modifier.fillMaxSize().onFocusChanged { gridHasFocus = it.hasFocus }, state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.pkg }) { e -> tileFor(e, true) }
            }
        } else {
            LazyVerticalGrid(
                columns = if (Cfg.columns > 0) GridCells.Fixed(Cfg.columns) else if (narrow && Cfg.iconSize == 3) GridCells.Fixed(5) else GridCells.Adaptive(
                    if (narrow) (if (Cfg.iconSize == 3) 48.dp else if (Cfg.iconSize == 0) 62.dp else if (Cfg.iconSize == 2) 96.dp else 78.dp)
                    else if (Cfg.iconSize == 3) 76.dp else if (Cfg.iconSize == 0) 110.dp else if (Cfg.iconSize == 2) 190.dp else 150.dp,
                ),
                modifier = Modifier.fillMaxSize().onFocusChanged { gridHasFocus = it.hasFocus },
                state = gridState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (narrow) 2.dp else 8.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(if (narrow) 2.dp else 10.dp),
                verticalArrangement = Arrangement.spacedBy(if (narrow) (if (Cfg.iconSize == 3) 0.dp else 4.dp) else 10.dp),
            ) {
                items(shown, key = { it.pkg }) { e -> tileFor(e, false) }
            }
        }
    }
    }

    folderRename?.let { HalfUi { RenameFolderDialog(it) { folderRename = null } } }
    folderAdd?.let { HalfUi { AddToFolderDialog(it, apps) { folderAdd = null } } }
    versionsFor?.let { e -> HalfUi { VersionsDialog(e.pkg, e.label) { versionsFor = null } } }

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
private fun HeaderTextButton(text: String, onClick: () -> Unit) {
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
            .combinedClickable(onClick = onClick)
            .padding(horizontal = if (isNarrow()) 11.dp else 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = btnSp(16), color = t.text, maxLines = 1) }
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
) {
    val icSize = if (compact) 44.dp else if (isNarrow()) iconDp().coerceAtMost(60.dp) else iconDp()
    val narrowScreen = isNarrow()
    val lbSp = if (Cfg.iconSize == 3) 9 else if (compact || narrowScreen) 9 else labelSp()
    val dragBegin by androidx.compose.runtime.rememberUpdatedState(onDragBegin)
    val dragMove by androidx.compose.runtime.rememberUpdatedState(onDragMove)
    val dragEnd by androidx.compose.runtime.rememberUpdatedState(onDragEnd)
    val t = LocalTh.current
    var focused by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    val (hm, hover) = rememberHover()
    val scale by animateFloatAsState(if (merge) 1.12f else if (focused || hover) 1.05f else 1f, label = "scale")
    val bmp = remember(e.pkg, e.icon, e.bmp) { e.bmp ?: e.icon.toBitmap(128, 128).asImageBitmap() }
    val hiddenMark = Cfg.isHidden(e.pkg)
    Box {
        val base = Modifier
            .scale(scale)
            .alpha(if (hiddenMark) 0.5f else 1f)
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
                                if (rc == 0) pending = true
                                else if (rc == 1 && pending) { pending = false; onMenu() }
                            }
                            KeyEventType.KeyUp -> if (pending) { pending = false; onOpen() }
                            else -> {}
                        }
                        true
                    }
                    Key.Menu, Key.Info -> { if (ev.type == KeyEventType.KeyDown) onMenu(); true }
                    else -> false
                }
            }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .border(
                if (merge) 4.dp else if (focused || isSelected || isMoving) 3.dp else if (marked) 2.dp else if (hover) 1.dp else 0.dp,
                if (merge || isMoving) Color(0xFFF59E0B) else if (isSelected) t.accent else if (focused || hover) t.accent
                else if (marked) t.accent.copy(alpha = 0.55f) else Color.Transparent,
                RoundedCornerShape(14.dp),
            )
            .background(
                if (isSelected) t.chipSel else if (focused || hover || merge) t.tileFocus else if (Cfg.transparentTiles) Color.Transparent else t.tile,
                RoundedCornerShape(14.dp),
            )
            // правая кнопка мыши (и аэромыши) открывает те же действия, что долгое касание
            .mouseSecondary(onMenu)
            .combinedClickable(onClick = onOpen, onLongClick = onMenu)
            // долгое нажатие открывает меню, а если после него потянуть — значок перетаскивается (как у Samsung)
            .pointerInput(e.pkg) {
                var origin = androidx.compose.ui.geometry.Offset.Zero
                var started = false
                detectDragGesturesAfterLongPress(
                    onDragStart = { origin = it; started = false },
                    onDrag = { change, _ ->
                        change.consume()
                        if (!started && (change.position - origin).getDistance() > 24f) { started = true; dragBegin() }
                        if (started) dragMove(change.position)
                    },
                    onDragEnd = { if (started) dragEnd() },
                    onDragCancel = { if (started) dragEnd() },
                )
            }
            .padding(if (Cfg.iconSize == 3) 3.dp else 10.dp)
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
                Image(bmp, null, Modifier.size(icSize))
                if (Cfg.showLabels) {
                    Spacer(Modifier.height(if (Cfg.iconSize == 3) 2.dp else 6.dp))
                    // две строки всегда: иначе плитка с длинным названием выше соседних и фон ряда «ломается»
                    Text(e.label, fontSize = lbSp.sp, color = t.text, minLines = if (Cfg.iconSize == 3) 1 else 2, maxLines = if (Cfg.iconSize == 3) 1 else 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
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
