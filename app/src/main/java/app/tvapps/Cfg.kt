package app.tvapps

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Папка значков: id, название и пакеты внутри. */
data class Folder(val id: String, val name: String, val pkgs: List<String>)

/** Настройки программы; хранятся в SharedPreferences, изменения сразу видны на экране. */
object Cfg {
    private lateinit var sp: SharedPreferences

    /** Число столбцов: 0 — подбирать по ширине экрана. */
    private var columnsB by mutableIntStateOf(0)
    /** Число столбцов; на столе со своими настройками его значение. */
    val columns: Int get() = Desks.ovCols() ?: columnsB
    /** Размер значков: 0 малые, 1 средние, 2 крупные. */
    private var iconSizeB by mutableIntStateOf(1)
    val iconSize: Int get() = Desks.ovSize() ?: iconSizeB
    var listMode by mutableStateOf(false); private set
    /** name, name_desc, new, old, freq */
    var sort by mutableStateOf("name"); private set
    var showLabels by mutableStateOf(true); private set
    /** Запоминать положение в меню и после выхода кнопкой «Назад» (по умолчанию выключено: после выхода меню открывается с начала). */
    var rememberPos by mutableStateOf(false); private set
    /** Масштаб самих значков в процентах (50..200), независимо от числа столбцов и строк. */
    var iconScale by mutableIntStateOf(100); private set
    /** Плотность сетки: 0 плотно, 1 обычно, 2 просторно (отступы вокруг значков и зазоры). */
    var gridGap by mutableIntStateOf(1); private set
    /** Строк на экране (0 авто): значки подгоняются по высоте, чтобы это число строк помещалось целиком. */
    private var gridRowsB by mutableIntStateOf(0)
    val gridRows: Int get() = Desks.ovRows() ?: gridRowsB
    var fullscreen by mutableStateOf(true); private set
    var showHidden by mutableStateOf(false); private set
    var hidden by mutableStateOf<Set<String>>(emptySet()); private set
    /** dark, light или auto (как в системе). */
    var theme by mutableStateOf("dark"); private set
    /** Цвет акцента: teal, blue, orange, purple, pink, green. */
    var accent by mutableStateOf("teal"); private set
    /** Язык программы: пусто — как в системе, иначе ru, uk, en. */
    var lang by mutableStateOf(""); private set
    /** Подложки значков (плашки под значками, рамка и фон плитки); по умолчанию включены. */
    var plates by mutableStateOf(true); private set
    /** Цвет рамки выделения (наведение, выбор пультом) в меню и на «Доме»: номер в [RING_COLORS]; 0 — «по умолчанию» (акцент темы), при первом запуске выбран белый. */
    var selRing by mutableIntStateOf(0); private set
    /** Цвет рамки выделения на экране «Дом» (свой, отдельно от меню): номер в [RING_COLORS]; 0 — акцент темы (так при первом запуске). */
    var homeRing by mutableIntStateOf(0); private set
    /** Оформление верхней строки «Дома» (по умолчанию как раньше: ничего у прежних пользователей не меняется). Контур значков состояния: "auto" (по яркости фона), "dark", "white", "none". */
    var statusOutline by androidx.compose.runtime.mutableStateOf("auto"); private set
    /** Фон кнопок и поля поиска верхней строки: "solid" (непрозрачный, как в 7.0), "glass" (полупрозрачный, цвет плиток темы), "none" (без фона). */
    var barFill by androidx.compose.runtime.mutableStateOf("solid"); private set
    /** Скругление кнопок и поля поиска верхней строки: "normal", "round" (круглые, капсулой), "square" (малое). */
    var barShape by androidx.compose.runtime.mutableStateOf("normal"); private set
    /** Название программы рядом с кнопкой меню в верхней строке «Дома» (по умолчанию выключено). */
    var barTitle by androidx.compose.runtime.mutableStateOf(false); private set
    /** Свечение рамки выделения на «Доме»: "none" (по умолчанию), "weak", "strong". */
    var selGlow by androidx.compose.runtime.mutableStateOf("none"); private set
    /** Панель «Все приложения» на «Доме» (прокручиваемая сетка всех приложений под виджетами); по умолчанию выключена. */
    var homeAllPanel by androidx.compose.runtime.mutableStateOf(false); private set
    /** Прозрачность стеклянного фона этой панели: "light", "mid" (по умолчанию), "dense". */
    var homeAllGlass by androidx.compose.runtime.mutableStateOf("mid"); private set
    /** Новые установленные приложения сами попадают на «Дом» (строка «Новые приложения»). */
    var homeAutoAdd by mutableStateOf(true); private set
    /** Фон плиток значков: true — прозрачный (по умолчанию), false — карточки. */
    var transparentTiles by mutableStateOf(true); private set
    /** Папки значков. */
    var folders by mutableStateOf<List<Folder>>(emptyList()); private set
    /** Сохранять копию приложения перед удалением. */
    var backupOnDelete by mutableStateOf(true); private set
    /** Помнить выбор главного экрана после обновления: если программа была главным экраном по умолчанию, после обновления она сама возвращает себе эту роль (нужен ADB или root). */
    var rememberHome by mutableStateOf(true); private set
    /** Необязательный токен GitHub для поиска (без токена GitHub пускает около 10 поисковых запросов в минуту); хранится только на устройстве. */
    var githubToken by mutableStateOf(""); private set
    /** Пробовать права root вместо ADB, если они есть. */
    var useRoot by mutableStateOf(true); private set
    /** Приложения, которые не нужно предлагать обновить. */
    var updSkip by mutableStateOf<Set<String>>(emptySet()); private set
    /** Включённые источники обновлений; хрупкие (apkpure) по умолчанию выключены. */
    var updSources by mutableStateOf(setOf("fdroid", "izzy", "aptoide", "github", "gitlab", "codeberg")); private set
    /** Необязательный токен GitHub для проверки обновлений (снимает лимит запросов). */
    var ghToken by mutableStateOf(""); private set
    /** Проверять обновления в фоне по расписанию (по умолчанию выключено). */
    var autoUpd by mutableStateOf(false); private set
    /** Сообщать о новых версиях самой программы (правило 17): раз в сутки, окно один раз на версию; по умолчанию включено. */
    var selfUpd by mutableStateOf(true); private set
    /** Как часто проверять, часов: 6, 24 или 72. */
    var autoUpdHours by mutableIntStateOf(24); private set
    /** Ставить найденные обновления сами (только с root/ADB; по умолчанию выключено). */
    var autoInstall by mutableStateOf(false); private set
    /** Сколько приложений с обновлением нашла последняя проверка: показывается в шапке. */
    var updFound by mutableIntStateOf(0); private set
    /** Пакет набора значков; пусто — родные значки. */
    var iconPack by mutableStateOf(""); private set
    /** Растёт при смене значков, чтобы список перезагрузился. */
    var iconTick by mutableIntStateOf(0); private set
    /** Растёт, когда список приложений надо перечитать без пересоздания всех значков (после установки пачки). */
    var listTick by mutableIntStateOf(0); private set
    fun bumpList() { listTick++ }
    /** Свой порядок значков (сортировка «своя»). */
    var order by mutableStateOf<List<String>>(emptyList()); private set
    /** Готовить кэш значков при включении устройства. */
    var bootWarm by mutableStateOf(true); private set
    /** Службы спецвозможностей, которые нужно включать заново после загрузки. */
    var pinnedAccess by mutableStateOf<Set<String>>(emptySet()); private set
    /** Службы, которые не нужно включать заново (владелец исключил). */
    var noRestoreAccess by mutableStateOf<Set<String>>(emptySet()); private set
    /** Пакет родного главного экрана, на который уходит второе нажатие «Домой»; пусто — выбрать самому. */
    var nativeHome by mutableStateOf(""); private set
    /** Родные главные экраны, отключённые железным режимом «Дом» (для возврата). */
    var hardOff by mutableStateOf<Set<String>>(emptySet()); private set
    /** Главные экраны, которые железный режим должен оставить включёнными. null: выбора не было, тогда отключаются только системные. */
    var hardKeep by mutableStateOf<Set<String>?>(null); private set
    /** Скрытые каналы приложений («пакет:номер»). */
    var hiddenChannels by mutableStateOf<Set<String>>(emptySet()); private set
    /** Город для карточки погоды в «Доме»; пусто: карточки нет. */
    var weatherCity by mutableStateOf(""); private set
    /** Избранные приложения в порядке добавления (пакеты через запятую). */
    var favorites by mutableStateOf<List<String>>(emptyList()); private set
    /** Куда возвращаться после запущенной программы: menu — в это меню, home — на родной главный экран. */
    var returnTo by mutableStateOf("menu"); private set
    /** Модуль «Дом» включён: кнопка «Дом» в меню, возврат в «Дом» по «Назад». Выключенный модуль оставляет только меню. */
    var homeModule by mutableStateOf(true); private set
    /** Меню приложений (сетка) включено. Выключенное не запускается и не грузит значки, остаётся «Дом»; игнорируется, если выключен и «Дом». */
    var menuModule by mutableStateOf(true); private set
    /** Меняется при каждом запуске приложения, чтобы пересчитать сортировку «часто запускаемые». */
    var launchTick by mutableIntStateOf(0); private set
    /** Сколько столбцов и строк сетка показывает при «Авто» (сообщает сетка): шаги «+» и «−» из «Авто» идут от этих чисел. */
    @Volatile var autoCols = 0
    @Volatile var autoRows = 0
    /** Очистка памяти при нажатии «Домой» (по умолчанию выключена). */
    var memAuto by mutableStateOf(false); private set
    /** Очистка памяти не останавливает избранные приложения. */
    var memKeepFav by mutableStateOf(true); private set
    /** Приложения, которые очистка памяти не останавливает (выбор владельца; по умолчанию пусто). */
    var memExcl by mutableStateOf<Set<String>>(emptySet()); private set

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE)
        reload()
    }

    /** Заново читает настройки (после восстановления из копии). */
    fun reload() {
        columnsB = Steps.cleanAuto(sp.getInt("cols", 0), 16)
        iconSizeB = sp.getInt("icon", 1)
        listMode = sp.getBoolean("list", false)
        sort = sp.getString("sort", "name") ?: "name"
        showLabels = sp.getBoolean("labels", true)
        rememberPos = sp.getBoolean("rememberpos", false)
        iconScale = sp.getInt("iscale", 100).coerceIn(40, 220)
        gridGap = sp.getInt("gap", 1).coerceIn(0, 2)
        gridRowsB = Steps.cleanAuto(sp.getInt("rows", 0), 12)
        fullscreen = sp.getBoolean("fullscreen", true)
        showHidden = sp.getBoolean("show_hidden", false)
        hidden = sp.getStringSet("hidden", emptySet())?.toSet() ?: emptySet()
        theme = sp.getString("theme", "dark") ?: "dark"
        accent = sp.getString("accent", "teal") ?: "teal"
        lang = sp.getString("lang", "") ?: ""
        folders = (sp.getString("folders", "") ?: "").split("\n").filter { it.isNotBlank() }.mapNotNull { l ->
            val p = l.split("\t")
            if (p.size < 3) null else Folder(p[0], p[1], p[2].split(",").filter { it.isNotBlank() })
        }
        backupOnDelete = sp.getBoolean("backup_delete", true)
        rememberHome = sp.getBoolean("remember_home", true)
        githubToken = sp.getString("github_token", "").orEmpty()
        useRoot = sp.getBoolean("use_root", true)
        updSkip = sp.getStringSet("upd_skip", emptySet())?.toSet() ?: emptySet()
        updSources = sp.getStringSet("upd_sources", null)?.toSet()?.let { st ->
            // прежний набор по умолчанию (до Codeberg) получает и Codeberg
            if (st == setOf("fdroid", "izzy", "aptoide", "github", "gitlab")) st + "codeberg" else st
        } ?: setOf("fdroid", "izzy", "aptoide", "github", "gitlab", "codeberg")
        ghToken = sp.getString("gh_token", "") ?: ""
        autoUpd = sp.getBoolean("auto_upd", false)
        selfUpd = sp.getBoolean("self_upd", true)
        autoUpdHours = sp.getInt("auto_upd_hours", 24).let { if (it == 6 || it == 24 || it == 72) it else 24 }
        autoInstall = sp.getBoolean("auto_install", false)
        updFound = sp.getInt("upd_found", 0)
        transparentTiles = sp.getBoolean("tile_transparent", true)
        plates = sp.getBoolean("plates", true)
        // рамки меню и «Дома»: при первом запуске «По умолчанию» (цвет темы), белый выбирается в настройках
        homeRing = sp.getInt("home_ring", 0).coerceIn(0, RING_COLORS.size - 1)
        statusOutline = sp.getString("status_outline", "auto").orEmpty().takeIf { it in listOf("auto", "dark", "white", "none") } ?: "auto"
        barFill = sp.getString("bar_fill", "solid").orEmpty().takeIf { it in listOf("solid", "glass", "none") } ?: "solid"
        barTitle = sp.getBoolean("bar_title", false)
        homeAllPanel = sp.getBoolean("home_all_panel", false)
        homeAllGlass = sp.getString("home_all_glass", "mid").orEmpty().takeIf { it in listOf("light", "mid", "dense") } ?: "mid"
        selGlow = sp.getString("sel_glow", "none").orEmpty().takeIf { it in listOf("none", "weak", "strong") } ?: "none"
        barShape = sp.getString("bar_shape", "normal").orEmpty().takeIf { it in listOf("normal", "round", "square") } ?: "normal"
        selRing = sp.getInt("sel_ring", 0).coerceIn(0, RING_COLORS.size - 1)
        iconPack = sp.getString("icon_pack", "") ?: ""
        nativeHome = sp.getString("native_home", "") ?: ""
        hardOff = sp.getStringSet("home_hard_off", emptySet())?.toSet() ?: emptySet()
        hardKeep = if (sp.getBoolean("home_hard_keep_set", false)) sp.getStringSet("home_hard_keep", emptySet())?.toSet() ?: emptySet() else null
        hiddenChannels = sp.getStringSet("hidden_channels", emptySet())?.toSet() ?: emptySet()
        weatherCity = sp.getString("weather_city", "") ?: ""
        favorites = (sp.getString("favorites", "") ?: "").split(',').filter { it.isNotBlank() }
        returnTo = sp.getString("return_to", "menu") ?: "menu"
        homeModule = sp.getBoolean("home_module", true)
        homeAutoAdd = sp.getBoolean("home_auto_add", true)
        menuModule = sp.getBoolean("menu_module", true)
        // оба модуля выключены быть не могут (остались от прежних версий): меню включается
        if (!homeModule && !menuModule) { menuModule = true; sp.edit().putBoolean("menu_module", true).apply() }
        bootWarm = sp.getBoolean("boot_warm", true)
        pinnedAccess = sp.getStringSet("pinned_access", emptySet())?.toSet() ?: emptySet()
        noRestoreAccess = sp.getStringSet("no_restore_access", emptySet())?.toSet() ?: emptySet()
        order = (sp.getString("order", "") ?: "").split("\n").filter { it.isNotBlank() }
        // ключи рядов над списком (убраны в 4.0) удаляются
        if (sp.contains("home_rows") || sp.contains("tip_fav_31") || sp.contains("hint")) sp.edit().remove("home_rows").remove("tip_fav_31").remove("hint").apply()
        memAuto = sp.getBoolean("mem_auto", false)
        memKeepFav = sp.getBoolean("mem_keep_fav", true)
        memExcl = sp.getStringSet("mem_excl", emptySet())?.toSet() ?: emptySet()
        Desks.reload(sp, rememberPos)
    }

    /** Запись числа с задержкой: при удержании кнопки в настройки уходит одно последнее значение после паузы, а не каждый шаг. */
    private val pendingInts = HashMap<String, Int>()
    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val flushRun = Runnable { flush() }
    private fun putIntLater(key: String, v: Int) {
        synchronized(pendingInts) { pendingInts[key] = v }
        saveHandler.removeCallbacks(flushRun)
        saveHandler.postDelayed(flushRun, 400)
    }
    /** Немедленно записывает отложенное (при уходе экрана с переднего плана). */
    fun flush() {
        saveHandler.removeCallbacks(flushRun)
        val m = synchronized(pendingInts) { HashMap(pendingInts).also { pendingInts.clear() } }
        if (m.isNotEmpty() && ::sp.isInitialized) { val e = sp.edit(); m.forEach { (k, v) -> e.putInt(k, v) }; e.apply() }
    }
    fun changeColumns(v: Int) { val c = Steps.cleanAuto(v, 16); if (c == columnsB) return; columnsB = c; putIntLater("cols", c) }
    fun changeIconSize(v: Int) { iconSizeB = v; sp.edit().putInt("icon", v).apply() }
    fun changeListMode(v: Boolean) { listMode = v; sp.edit().putBoolean("list", v).apply() }
    fun changeSort(v: String) { sort = v; sp.edit().putString("sort", v).apply() }
    fun changeIconScale(v: Int) { val c = v.coerceIn(40, 220); if (c == iconScale) return; iconScale = c; putIntLater("iscale", c) }
    fun changeGridGap(v: Int) { gridGap = v.coerceIn(0, 2); sp.edit().putInt("gap", gridGap).apply() }
    fun changeGridRows(v: Int) { val c = Steps.cleanAuto(v, 12); if (c == gridRowsB) return; gridRowsB = c; putIntLater("rows", c) }
    fun changeRememberPos(v: Boolean) { rememberPos = v; sp.edit().putBoolean("rememberpos", v).apply() }
    fun changeShowLabels(v: Boolean) { showLabels = v; sp.edit().putBoolean("labels", v).apply() }
    fun changeFullscreen(v: Boolean) { fullscreen = v; sp.edit().putBoolean("fullscreen", v).apply() }
    fun changeShowHidden(v: Boolean) { showHidden = v; sp.edit().putBoolean("show_hidden", v).apply() }

    fun changeIconPack(v: String) { iconPack = v; sp.edit().putString("icon_pack", v).apply(); iconTick++ }
    fun bumpIcons() { iconTick++ }
    fun changeOrder(v: List<String>) { order = v; sp.edit().putString("order", v.joinToString("\n")).apply() }
    fun changeBootWarm(v: Boolean) { bootWarm = v; sp.edit().putBoolean("boot_warm", v).apply() }
    fun changePinnedAccess(v: Set<String>) { pinnedAccess = v; sp.edit().putStringSet("pinned_access", v).apply() }
    fun isNoRestore(c: String) = c in noRestoreAccess
    fun toggleNoRestore(c: String) {
        noRestoreAccess = if (c in noRestoreAccess) noRestoreAccess - c else noRestoreAccess + c
        sp.edit().putStringSet("no_restore_access", noRestoreAccess).apply()
    }
    fun changeMenuModule(v: Boolean) { if (!v && !homeModule) return; menuModule = v; sp.edit().putBoolean("menu_module", v).apply() }
    /** Меню выключено и это допустимо (есть «Дом»): запуски меню без особой просьбы открывают «Дом». */
    val menuOff: Boolean get() = !menuModule && homeModule
    fun changeHomeAutoAdd(v: Boolean) { homeAutoAdd = v; sp.edit().putBoolean("home_auto_add", v).apply() }
    fun changeHomeModule(v: Boolean) { homeModule = v; sp.edit().putBoolean("home_module", v).apply() }
    fun changeWeatherCity(v: String) { weatherCity = v.trim(); sp.edit().putString("weather_city", weatherCity).apply() }
    fun toggleChannel(key: String) {
        hiddenChannels = if (key in hiddenChannels) hiddenChannels - key else hiddenChannels + key
        sp.edit().putStringSet("hidden_channels", hiddenChannels).apply()
    }
    fun isFavorite(pkg: String) = pkg in favorites
    fun toggleFavorite(pkg: String) {
        favorites = if (pkg in favorites) favorites - pkg else favorites + pkg
        sp.edit().putString("favorites", favorites.joinToString(",")).apply()
    }
    fun changeMemAuto(v: Boolean) { memAuto = v; sp.edit().putBoolean("mem_auto", v).apply() }
    fun changeMemKeepFav(v: Boolean) { memKeepFav = v; sp.edit().putBoolean("mem_keep_fav", v).apply() }
    fun toggleMemExcl(pkg: String) {
        memExcl = if (pkg in memExcl) memExcl - pkg else memExcl + pkg
        sp.edit().putStringSet("mem_excl", memExcl).apply()
    }
    fun lastLaunch(pkg: String): Long = sp.getLong("t_$pkg", 0L)
    fun changeHardKeep(v: Set<String>) { hardKeep = v; sp.edit().putStringSet("home_hard_keep", v).putBoolean("home_hard_keep_set", true).apply() }
    fun changeHardOff(v: Set<String>) { hardOff = v; sp.edit().putStringSet("home_hard_off", v).apply() }
    fun changeNativeHome(v: String) { nativeHome = v; sp.edit().putString("native_home", v).apply() }
    fun changeReturnTo(v: String) { returnTo = v; sp.edit().putString("return_to", v).apply() }
    fun changeTheme(v: String) { theme = v; sp.edit().putString("theme", v).apply() }
    fun changeAccent(v: String) { accent = v; sp.edit().putString("accent", v).apply() }
    fun changeLang(v: String) { lang = v; sp.edit().putString("lang", v).apply() }
    fun changeStatusOutline(v: String) { statusOutline = v; sp.edit().putString("status_outline", v).apply() }
    fun changeBarFill(v: String) { barFill = v; sp.edit().putString("bar_fill", v).apply() }
    fun changeSelGlow(v: String) { selGlow = v; sp.edit().putString("sel_glow", v).apply() }
    fun changeHomeAllPanel(v: Boolean) { homeAllPanel = v; sp.edit().putBoolean("home_all_panel", v).apply() }
    fun changeHomeAllGlass(v: String) { homeAllGlass = v; sp.edit().putString("home_all_glass", v).apply() }
    fun changeBarTitle(v: Boolean) { barTitle = v; sp.edit().putBoolean("bar_title", v).apply() }
    fun changeBarShape(v: String) { barShape = v; sp.edit().putString("bar_shape", v).apply() }
    fun changeHomeRing(v: Int) { homeRing = v.coerceIn(0, RING_COLORS.size - 1); sp.edit().putInt("home_ring", homeRing).apply() }
    fun homeRingOr(accent: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color =
        if (homeRing == 0) accent else androidx.compose.ui.graphics.Color(RING_COLORS[homeRing].toInt())
    fun changeSelRing(v: Int) { selRing = v.coerceIn(0, RING_COLORS.size - 1); sp.edit().putInt("sel_ring", selRing).apply() }
    /** Цвет рамки выделения: выбранный владелицей или [accent] темы, если выбран «цвет программы». */
    fun ringOr(accent: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color =
        if (selRing == 0) accent else androidx.compose.ui.graphics.Color(RING_COLORS[selRing].toInt())
    val RING_COLORS = longArrayOf(0L, 0xFFFFFFFFL, 0xFF000000L, 0xFFEF4444L, 0xFFF97316L, 0xFFFACC15L, 0xFF22C55EL, 0xFF06B6D4L, 0xFF3B82F6L, 0xFFA855F7L, 0xFFEC4899L)
    fun changePlates(v: Boolean) { plates = v; sp.edit().putBoolean("plates", v).apply() }
    fun changeTransparentTiles(v: Boolean) { transparentTiles = v; sp.edit().putBoolean("tile_transparent", v).apply() }
    fun toggleUpdSource(id: String) {
        updSources = if (id in updSources) updSources - id else updSources + id
        sp.edit().putStringSet("upd_sources", updSources).apply()
    }
    fun toggleUpdSkip(pkg: String) {
        updSkip = if (pkg in updSkip) updSkip - pkg else updSkip + pkg
        sp.edit().putStringSet("upd_skip", updSkip).apply()
    }
    fun changeSelfUpd(v: Boolean) { selfUpd = v; sp.edit().putBoolean("self_upd", v).apply() }
    fun selfUpdDismissed(): String = sp.getString("self_upd_dismissed", "") ?: ""
    fun setSelfUpdDismissed(v: String) { sp.edit().putString("self_upd_dismissed", v).apply() }
    fun selfUpdLast(): Long = sp.getLong("self_upd_last", 0L)
    fun setSelfUpdLast(v: Long) { sp.edit().putLong("self_upd_last", v).apply() }
    fun changeAutoUpd(v: Boolean) { autoUpd = v; sp.edit().putBoolean("auto_upd", v).apply() }
    fun changeAutoUpdHours(v: Int) { autoUpdHours = v; sp.edit().putInt("auto_upd_hours", v).apply() }
    fun changeAutoInstall(v: Boolean) { autoInstall = v; sp.edit().putBoolean("auto_install", v).apply() }
    /** Запоминает число найденных обновлений и «ключ» набора (чтобы не повторять одно и то же уведомление). */
    fun setUpdFound(n: Int, key: String) { updFound = n; sp.edit().putInt("upd_found", n).putString("upd_found_key", key).apply() }
    fun updFoundKey(): String = sp.getString("upd_found_key", "") ?: ""
    fun updNotifiedKey(): String = sp.getString("upd_notified_key", "") ?: ""
    fun setUpdNotifiedKey(k: String) { sp.edit().putString("upd_notified_key", k).apply() }
    fun changeGhToken(v: String) { ghToken = v.trim(); sp.edit().putString("gh_token", ghToken).apply() }
    fun changeUseRoot(v: Boolean) { useRoot = v; sp.edit().putBoolean("use_root", v).apply() }
    fun changeGithubToken(v: String) { githubToken = v.trim(); sp.edit().putString("github_token", v.trim()).apply() }
    fun changeBackupOnDelete(v: Boolean) { backupOnDelete = v; sp.edit().putBoolean("backup_delete", v).apply() }
    fun changeRememberHome(v: Boolean) { rememberHome = v; sp.edit().putBoolean("remember_home", v).apply() }
    /** Была ли программа главным экраном по умолчанию (ставится, когда это замечено или назначено самой программой). */
    fun homePinned(): Boolean = sp.getBoolean("home_pinned", false)
    fun setHomePinned(v: Boolean) { if (homePinned() != v) sp.edit().putBoolean("home_pinned", v).apply() }

    // ---- папки ----
    private fun saveFolders(list: List<Folder>) {
        // папка без значков не нужна
        folders = list.filter { it.pkgs.isNotEmpty() }
        sp.edit().putString("folders", folders.joinToString("\n") { it.id + "\t" + it.name.replace(Regex("[\t\n]"), " ") + "\t" + it.pkgs.joinToString(",") }).apply()
    }
    fun folderOf(pkg: String): Folder? = folders.firstOrNull { pkg in it.pkgs }
    fun folderById(id: String): Folder? = folders.firstOrNull { it.id == id }

    /** Создаёт папку; [replaceAt] — значок, на месте которого встанет папка в своём порядке. Возвращает id. */
    fun createFolder(name: String, pkgs: List<String>, replaceAt: String?): String {
        val id = "f" + System.currentTimeMillis().toString(36) + (folders.size % 10)
        val moved = pkgs.toSet()
        saveFolders(folders.map { it.copy(pkgs = it.pkgs.filter { p -> p !in moved }) } + Folder(id, name, pkgs))
        val key = "folder:$id"
        val ord = order.filter { it !in moved || it == replaceAt }
        changeOrder(if (replaceAt != null && replaceAt in ord) ord.map { if (it == replaceAt) key else it } else ord + key)
        return id
    }
    fun addToFolder(id: String, pkgs: List<String>) {
        val moved = pkgs.toSet()
        saveFolders(folders.map { if (it.id == id) it.copy(pkgs = (it.pkgs + pkgs).distinct()) else it.copy(pkgs = it.pkgs.filter { p -> p !in moved }) })
        changeOrder(order.filter { it !in moved })
    }
    fun removeFromFolder(pkg: String) { saveFolders(folders.map { it.copy(pkgs = it.pkgs.filter { p -> p != pkg }) }) }
    fun renameFolder(id: String, name: String) { saveFolders(folders.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it }) }
    fun deleteFolder(id: String) { saveFolders(folders.filter { it.id != id }); changeOrder(order.filter { it != "folder:$id" }) }

    fun isHidden(pkg: String) = pkg in hidden
    fun toggleHidden(pkg: String) {
        hidden = if (pkg in hidden) hidden - pkg else hidden + pkg
        sp.edit().putStringSet("hidden", hidden).apply()
    }

    /** Убрать приложение из «Недавних» (счётчик запусков остаётся). */
    fun clearLast(pkg: String) { sp.edit().remove("t_$pkg").apply(); launchTick++ }
    /** Очистить все «Недавние». */
    fun clearAllLast() {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("t_") }.forEach { e.remove(it) }
        e.apply(); launchTick++
    }

    fun launches(pkg: String): Int = sp.getInt("n_$pkg", 0)
    fun countLaunch(pkg: String) {
        sp.edit().putInt("n_$pkg", launches(pkg) + 1).putLong("t_$pkg", System.currentTimeMillis()).apply()
        launchTick++
    }
}
