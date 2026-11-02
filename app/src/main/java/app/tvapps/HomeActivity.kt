package app.tvapps

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Список приложений, общий для «Дома» и меню: второй экран открывается мгновенно, без повторной загрузки. */
internal object AppsMem {
    @Volatile var list: List<Entry> = emptyList()
    @Volatile var stamp = 0L
}

/**
 * Главный экран «Дом»: отдельный экран, не часть меню приложений. Устройство экрана (верхняя полоска и строки)
 * хранит [HomeStore]; всё меняется долгим нажатием «ОК» на элементе. Меню всех приложений открывается значком «Все приложения».
 * Включается как главный экран через настройки (HomeAlias указывает сюда).
 */
/** Нажатия «Домой» на самом «Доме» (передаются экрану). */
internal object HomeNav {
    val press = mutableIntStateOf(0)
    /** меню на экране; когда оно ушло с экрана (миг назад); «Назад» из меню просит вернуть на «Дом»: тогда это не «Домой на Доме» */
    @Volatile var menuOn = false
    @Volatile var menuPausedAt = 0L
    @Volatile var fromMenu = false
}

class HomeActivity : ComponentActivity() {
    private var homeShown = false
    private val resumeTick = mutableIntStateOf(0)
    private val againTick = mutableIntStateOf(0)

    // кнопка микрофона на пульте или аэромыши (клавиши «Поиск», «Голосовой ассистент», «Ассистент»)
    override fun dispatchKeyEvent(ev: android.view.KeyEvent): Boolean = Voice.onKey(this, ev) || super.dispatchKeyEvent(ev)

    // клавиша «Меню» (или «Инфо») на пульте, когда элемент не выбран и никто её не обработал: меню пустого места («Добавить строку», настройки)
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if ((keyCode == android.view.KeyEvent.KEYCODE_MENU || keyCode == android.view.KeyEvent.KEYCODE_INFO) && event.repeatCount == 0) { TopUi.emptyMenu.value = true; return true }
        return super.onKeyDown(keyCode, event)
    }

    private val pkgReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) { AppsMem.stamp = 0L; resumeTick.intValue++ }
    }

    override fun attachBaseContext(base: android.content.Context) {
        val l = runCatching { base.getSharedPreferences("tvapps", MODE_PRIVATE).getString("lang", "") }.getOrNull().orEmpty()
        if (l.isEmpty()) { super.attachBaseContext(base); return }
        val loc = java.util.Locale(l)
        java.util.Locale.setDefault(loc)
        val conf = android.content.res.Configuration(base.resources.configuration)
        conf.setLocale(loc)
        super.attachBaseContext(base.createConfigurationContext(conf))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cfg.init(this)
        // модуль «Дом» выключен: открываем меню и закрываем этот экран
        if (!Cfg.homeModule) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        HomeStore.init(this)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        SysBars.apply(this)       // по умолчанию скрыты на ТВ/приставке и видны на телефоне; переключатель в настройках
        MouseAsTouch.install(window)
        setContent {
            val tc = currentTC()
            MaterialTheme(colorScheme = if (tc.dark) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalTh provides tc) { FixedFont { HomeScreen(resumeTick.intValue, againTick.intValue) } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val f = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        }
        runCatching { registerReceiver(pkgReceiver, f) }
        if (Cfg.homeModule) runCatching { AppWidgets.host(this).startListening() }
    }

    override fun onResume() {
        super.onResume()
        homeShown = true
        SysBars.apply(this)
        resumeTick.intValue++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // «Домой» из другой программы: на «Дом», в начало и курсор на первый элемент;
        // «Домой» на самом «Доме» (мы уже здесь): в начало, а если уже в начале, то открывается меню приложений
        // «Домой на Доме» (мы уже на «Доме», меню поверх нет и «Назад» из меню не нажимали): открывается меню; иначе просто вернулись на «Дом»
        val covered = HomeNav.menuOn || HomeNav.fromMenu || android.os.SystemClock.elapsedRealtime() - HomeNav.menuPausedAt < 1500L
        HomeNav.fromMenu = false
        if (homeShown && !covered) HomeNav.press.intValue++ else againTick.intValue++
    }

    override fun onStop() {
        super.onStop()
        homeShown = false
        runCatching { unregisterReceiver(pkgReceiver) }
        runCatching { AppWidgets.host(this).stopListening() }
        if (HomeStore.dirty) {
            HomeStore.dirty = false
            val app = applicationContext
            Thread { Backup.autoSave(app) }.start()
        }
    }
}

@Composable
private fun HomeScreen(tick: Int, again: Int) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val narrow = isNarrow()
    var apps by remember { mutableStateOf(AppsMem.list) }
    var versionsFor by remember { mutableStateOf<Entry?>(null) }
    var updatesFor by remember { mutableStateOf<Entry?>(null) }
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var movingItem by remember { mutableStateOf<String?>(null) }
    var movingStatus by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var statusTick by remember { mutableIntStateOf(0) }
    var pickFor by remember { mutableStateOf<String?>(null) }
    val menuReq = remember { FocusRequester() }
    val bgScope = androidx.compose.runtime.rememberCoroutineScope()
    var flow by remember { mutableStateOf<WidgetFlow?>(null) }
    fun finishWidget(f: WidgetFlow) { HomeStore.updateSlot(f.rowId, f.slot) { it.copy(type = "appwidget", ref = f.id.toString(), n = 0) }; flow = null }
    fun dropWidget(f: WidgetFlow) { runCatching { AppWidgets.host(ctx).deleteAppWidgetId(f.id) }; flow = null }
    val cfgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val f = flow
        if (f != null) { if (r.resultCode == android.app.Activity.RESULT_OK) finishWidget(f) else dropWidget(f) }
    }
    fun afterBind(f: WidgetFlow) {
        val cfg = f.info.configure
        if (cfg == null) finishWidget(f)
        else {
            flow = f
            runCatching {
                cfgLauncher.launch(Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).setComponent(cfg).putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, f.id))
            }.onFailure { finishWidget(f) }
        }
    }
    val bindLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val f = flow
        if (f != null) {
            if (r.resultCode == android.app.Activity.RESULT_OK) afterBind(f)
            else { dropWidget(f); Toast.makeText(ctx, ctx.getString(R.string.wg_bind_fail), Toast.LENGTH_LONG).show() }
        }
    }
    val addAppWidget: (String, Int, android.appwidget.AppWidgetProviderInfo) -> Unit = { rowId, slot, info ->
        bgScope.launch {
            val host = AppWidgets.host(ctx)
            val mgr = android.appwidget.AppWidgetManager.getInstance(ctx)
            val id = host.allocateAppWidgetId()
            var ok = runCatching { mgr.bindAppWidgetIdIfAllowed(id, info.provider) }.getOrDefault(false)
            // без системного разрешения привязку разрешает ADB или root: appops BIND_APPWIDGET
            if (!ok && Adb.isConnected) {
                withContext(Dispatchers.IO) { runCatching { Adb.shell("appops set ${ctx.packageName} BIND_APPWIDGET allow") } }
                ok = runCatching { mgr.bindAppWidgetIdIfAllowed(id, info.provider) }.getOrDefault(false)
            }
            val f = WidgetFlow(rowId, slot, id, info)
            if (ok) afterBind(f)
            else {
                flow = f
                runCatching {
                    bindLauncher.launch(
                        Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_BIND)
                            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider),
                    )
                }.onFailure { dropWidget(f); Toast.makeText(ctx, ctx.getString(R.string.wg_bind_fail), Toast.LENGTH_LONG).show() }
            }
        }
    }
    val firstReq = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val layout = HomeStore.layout

    // список приложений: сначала то, что уже есть в памяти или в кэше, затем свежая загрузка (не чаще раза в полминуты)
    LaunchedEffect(tick, Cfg.iconTick) {
        if (apps.isEmpty()) {
            val cached = withContext(Dispatchers.Default) { AppCache.load(ctx) }
            if (cached.isNotEmpty() && apps.isEmpty()) { apps = cached; AppsMem.list = cached }
        }
        if (Cfg.iconTick > 0 || System.currentTimeMillis() - AppsMem.stamp > 30_000L || apps.isEmpty()) {
            val fresh = withContext(Dispatchers.Default) { loadApps(ctx) }
            apps = fresh; AppsMem.list = fresh; AppsMem.stamp = System.currentTimeMillis()
        }
    }
    // тяжёлое подключение ADB и первичная расстановка каналов — после первого кадра
    LaunchedEffect(Unit) {
        delay(1200)
        autoConnect(ctx)
    }
    LaunchedEffect(apps.isNotEmpty()) {
        if (apps.isEmpty()) return@LaunchedEffect
        delay(900)
        val seeded = withContext(Dispatchers.IO) {
            val (ch, ins) = Channels.load(ctx) to TvInputs.list(ctx)
            // приложения для стартовых строк: свои (не системные), сначала те, у кого есть баннер
            val own = apps.filter { !it.system && it.pkg != ctx.packageName && !Cfg.isHidden(it.pkg) }.sortedBy { it.label.lowercase() }.distinctBy { it.label.lowercase() }
            val withBanner = own.filter { Banners.load(ctx, it) != null }.take(14)
            val rest = own.filter { a -> withBanner.none { it.pkg == a.pkg } }.take(14)
            val yt = listOf("com.google.android.youtube.tv", "com.google.android.youtube").firstOrNull { p -> apps.any { it.pkg == p } }
            arrayOf<Any?>(ch, ins, withBanner.map { it.pkg }, rest.map { it.pkg }, yt)
        }
        @Suppress("UNCHECKED_CAST")
        HomeStore.seed(ctx, seeded[0] as List<ChRow>, seeded[1] as List<TvIn>, seeded[2] as List<String>, seeded[3] as List<String>, seeded[4] as String?)
    }
    // у приложения на «Доме» нет баннера (новое или без своего): один раз предлагаем создать красивый
    LaunchedEffect(apps.size, layout.rows.sumOf { it.items.size }, tick) {
        if (apps.isEmpty()) return@LaunchedEffect
        delay(2500)
        if (dlg != null || movingItem != null) return@LaunchedEffect
        val miss = withContext(Dispatchers.IO) { BannerGen.missing(ctx, apps) }
        if (miss.isNotEmpty() && dlg == null) {
            BannerGen.markOffered(ctx, miss.map { it.second.ref })
            dlg = Dlg.BannerOffer(miss.map { it.first to it.second.id })
        }
    }
    // состояние значков (Bluetooth, сеть, накопитель) читается заново каждые несколько секунд
    LaunchedEffect(Unit) { while (true) { delay(3000); statusTick++ } }
    // курсор на первый элемент: пульт сразу работает; «Домой» ещё раз возвращает в начало
    LaunchedEffect(again) {
        if (again > 0) scroll.scrollTo(0)
        var n = 0
        while (n++ < 12) {
            delay(150)
            if (runCatching { firstReq.requestFocus() }.isSuccess) break
        }
    }

    // кнопка микрофона (значок и клавиша пульта): выбор программы распознавания при первом нажатии, дальше она же
    VoiceHost { txt ->
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("from_home", true).putExtra("q", txt))
    }
    val startVoice = { Voice.request() }
    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = pickFor
        pickFor = null
        if (uri != null && id != null) {
            val ok = HomeImg.save(ctx, id, uri)
            val row = HomeStore.rowOfItem(id)
            if (ok && row != null) HomeStore.updateItem(row.id, id) { it.copy(img = true) }
            Toast.makeText(ctx, ctx.getString(if (ok) R.string.icon_saved else R.string.failed), Toast.LENGTH_SHORT).show()
        }
    }
    val openApp: (Entry) -> Unit = { e ->
        Cfg.countLaunch(e.pkg)
        runCatching { ctx.startActivity(e.launch) }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.cant_open), Toast.LENGTH_SHORT).show() }
    }
    val openMenu = {
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("from_home", true))
        (ctx as? android.app.Activity)?.overridePendingTransition(0, 0)
    }
    // «Домой» на «Доме»: если страница прокручена, в начало; если уже в начале, открывается меню приложений
    // реагируем только на НОВОЕ нажатие (прежнее значение при пересоздании экрана игнорируется, иначе меню открывалось бы снова и снова)
    val seenPress = remember { intArrayOf(HomeNav.press.intValue) }
    LaunchedEffect(HomeNav.press.intValue) {
        val v = HomeNav.press.intValue
        if (v != seenPress[0]) {
            seenPress[0] = v
            if (scroll.value > 0) { scroll.scrollTo(0); runCatching { firstReq.requestFocus() } } else openMenu()
        }
    }
    // когда закрывается любое окно, курсор пульта возвращается на плитку, где он был (а не прыгает наверх)
    val overlaysOpen = dlg != null || TopUi.recents.value || TopUi.notifs.value || TopUi.hdmi.value || versionsFor != null || updatesFor != null ||
        Voice.picking.value || Voice.capturing.value
    val wasOpen = remember { booleanArrayOf(false) }
    LaunchedEffect(overlaysOpen) {
        if (!overlaysOpen && wasOpen[0]) {
            delay(150)
            val r = TileFocus.reqs[TileFocus.last]
            if (r != null) runCatching { r.requestFocus() }
        }
        wasOpen[0] = overlaysOpen
    }
    // «Назад» на главном экране ничего не делает; в режиме перемещения заканчивает перемещение
    BackHandler {
        if (movingItem != null || movingStatus != null) { movingItem = null; movingStatus = null }
        else bgScope.launch { scroll.animateScrollTo(0); runCatching { menuReq.requestFocus() } }
    }
    val host = remember {
        object : HostOps {
            override fun openFolder(id: String) {}
            override fun renameFolder(id: String) {}
            override fun addToFolder(id: String) {}
            override fun openVersions(e: Entry) { versionsFor = e }
            override fun openUpdates(e: Entry) { updatesFor = e }
        }
    }
    CompositionLocalProvider(LocalHost provides host, LocalTh provides adaptTheme(t, layout.bg), LocalBaseTh provides t) {
        Box(Modifier.fillMaxSize().background(t.bg).pointerInput(Unit) {
            // долгое нажатие пальцем или мышью на пустом месте: «Добавить строку» и настройки (элементы забирают своё долгое нажатие сами)
            detectTapGestures(onLongPress = { if (dlg == null) dlg = Dlg.Empty })
        }) {
            HomeBackground(layout.bg)
            Column(Modifier.fillMaxSize().padding(horizontal = if (narrow) 10.dp else 32.dp, vertical = if (narrow) 10.dp else 16.dp)) {
                run {
                    Column(Modifier.fillMaxSize()) {
                        if (!Integrity.ok()) androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.integrity_warn), color = androidx.compose.ui.graphics.Color(0xFFF87171), fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp))
                        // на телефоне и планшете полоска не нужна (есть системная); на ТВ и приставках показывается всегда
                        if (TopMode.visible(ctx) || TopUi.tick.intValue < 0) {
                        HomeTopBar(
                            layout.top, statusTick + tick, movingStatus, menuReq,
                            onMenu = { openMenu() }, onVoice = { startVoice() },
                            onSearch = { q ->
                                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("from_home", true).putExtra("q", q).putExtra("open_search", q.isBlank()))
                                (ctx as? android.app.Activity)?.overridePendingTransition(0, 0)
                            },
                            onTopLong = { dlg = Dlg.Top },
                            onStatusLong = { dlg = Dlg.Status(it) },
                            onStatusMoveKey = { id, k ->
                                when (k) {
                                    Key.DirectionLeft -> { HomeStore.moveStatus(id, -1); true }
                                    Key.DirectionRight -> { HomeStore.moveStatus(id, 1); true }
                                    else -> true
                                }
                            },
                            onStatusStep = { id, d -> dlg = null; HomeStore.moveStatus(id, d) },
                            onStatusMoveEnd = { movingStatus = null },
                        )
                        }
                        // на телефоне и планшете: свайп вверх по экрану открывает меню приложений (прокручивать больше нечего — «лишнее» движение уходит сюда)
                        val swipeMenu = remember {
                            object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                                var acc = 0f
                                override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource) = androidx.compose.ui.geometry.Offset.Zero
                                override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                                    if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.Drag) {
                                        acc = if (available.y < 0) acc + available.y else 0f
                                        if (acc < -140f && !TopMode.isTv(ctx)) { acc = 0f; openMenu() }
                                    }
                                    return androidx.compose.ui.geometry.Offset.Zero
                                }
                                override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity { acc = 0f; return androidx.compose.ui.unit.Velocity.Zero }
                            }
                        }
                        Column(Modifier.weight(1f).nestedScroll(swipeMenu).verticalScroll(scroll)) {
                            // поля сверху и снизу: выбранный элемент увеличивается и не должен обрезаться краем прокручиваемой области
                            Column(Modifier.padding(vertical = 10.dp)) {
                                HomeRowsView(
                                    layout, apps, tick, refreshTick, movingItem, firstReq,
                                    openApp = openApp, openMenu = { openMenu() }, onDlg = { dlg = it }, onMoveEnd = { movingItem = null },
                                )
                            }
                        }
                    }
                }
            }
            TileDragOverlay(scroll)
            if (TopUi.recents.value) RecentsDialog(apps, openApp) { TopUi.recents.value = false }
            if (TopUi.notifs.value) NotificationsDialog { TopUi.notifs.value = false }
            if (TopUi.hdmi.value) HdmiDialog { TopUi.hdmi.value = false }
            LaunchedEffect(TopUi.emptyMenu.value) { if (TopUi.emptyMenu.value) { TopUi.emptyMenu.value = false; if (dlg == null) dlg = Dlg.Empty } }
            HomeDialogs(
                dlg, apps, { dlg = it },
                startMove = { movingItem = it }, startMoveStatus = { movingStatus = it },
                pickImage = { id ->
                    pickFor = id
                    dlg = null
                    runCatching { imgPicker.launch("image/*") }.onFailure {
                        pickFor = null
                        Toast.makeText(ctx, ctx.getString(R.string.icon_no_picker), Toast.LENGTH_LONG).show()
                    }
                },
                refreshWeather = { refreshTick++ },
                addAppWidget = addAppWidget,
                disableHome = {
                    bgScope.launch {
                        val r = HomeModule.disable(ctx)
                        if (r.isEmpty()) {
                            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            (ctx as? android.app.Activity)?.finish()
                        } else Toast.makeText(ctx, ctx.getString(R.string.home_module_off_fail), Toast.LENGTH_LONG).show()
                    }
                },
            )
        }
        versionsFor?.let { e -> HalfUi { VersionsDialog(e.pkg, e.label) { versionsFor = null } } }
        updatesFor?.let { e -> HalfUi { UpdatesDialog(e.pkg, e.label) { updatesFor = null } } }
    }
}
