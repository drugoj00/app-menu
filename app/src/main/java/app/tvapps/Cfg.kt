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
    var columns by mutableIntStateOf(0); private set
    /** Размер значков: 0 малые, 1 средние, 2 крупные. */
    var iconSize by mutableIntStateOf(1); private set
    var listMode by mutableStateOf(false); private set
    /** name, name_desc, new, old, freq */
    var sort by mutableStateOf("name"); private set
    var showLabels by mutableStateOf(true); private set
    /** Строка-подсказка под заголовком: убирается нажатием на неё (на магнитоле она занимает много места). */
    var showHint by mutableStateOf(true); private set
    /** Запоминать положение в меню и после выхода кнопкой «Назад» (по умолчанию выключено: после выхода меню открывается с начала). */
    var rememberPos by mutableStateOf(false); private set
    /** Масштаб самих значков в процентах (50..200), независимо от числа столбцов и строк. */
    var iconScale by mutableIntStateOf(100); private set
    /** Плотность сетки: 0 плотно, 1 обычно, 2 просторно (отступы вокруг значков и зазоры). */
    var gridGap by mutableIntStateOf(1); private set
    /** Строк на экране (0 авто): значки подгоняются по высоте, чтобы это число строк помещалось целиком. */
    var gridRows by mutableIntStateOf(0); private set
    var fullscreen by mutableStateOf(true); private set
    var showHidden by mutableStateOf(false); private set
    var hidden by mutableStateOf<Set<String>>(emptySet()); private set
    /** dark, light или auto (как в системе). */
    var theme by mutableStateOf("dark"); private set
    /** Цвет акцента: teal, blue, orange, purple, pink, green. */
    var accent by mutableStateOf("teal"); private set
    /** Язык программы: пусто — как в системе, иначе ru, uk, en. */
    var lang by mutableStateOf(""); private set
    /** Фон плиток значков: true — прозрачный (по умолчанию), false — карточки. */
    var transparentTiles by mutableStateOf(true); private set
    /** Папки значков. */
    var folders by mutableStateOf<List<Folder>>(emptyList()); private set
    /** Сохранять копию приложения перед удалением. */
    var backupOnDelete by mutableStateOf(true); private set
    /** Пробовать права root (Magisk) вместо ADB, если они есть. */
    var useRoot by mutableStateOf(true); private set
    /** Приложения, которые не нужно предлагать обновить. */
    var updSkip by mutableStateOf<Set<String>>(emptySet()); private set
    /** Включённые источники обновлений; хрупкие (apkpure) по умолчанию выключены. */
    var updSources by mutableStateOf(setOf("fdroid", "izzy", "aptoide", "github", "gitlab", "codeberg")); private set
    /** Необязательный токен GitHub для проверки обновлений (снимает лимит запросов). */
    var ghToken by mutableStateOf(""); private set
    /** Проверять обновления в фоне по расписанию (по умолчанию выключено). */
    var autoUpd by mutableStateOf(false); private set
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
    /** Показывать ряды «Дома» (избранное, недавние) над списком приложений, когда программа работает как главный экран. */
    var homeRows by mutableStateOf(true); private set
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
    /** Меняется при каждом запуске приложения, чтобы пересчитать сортировку «часто запускаемые». */
    var launchTick by mutableIntStateOf(0); private set

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE)
        reload()
    }

    /** Заново читает настройки (после восстановления из копии). */
    fun reload() {
        columns = sp.getInt("cols", 0)
        iconSize = sp.getInt("icon", 1)
        listMode = sp.getBoolean("list", false)
        sort = sp.getString("sort", "name") ?: "name"
        showLabels = sp.getBoolean("labels", true)
        showHint = sp.getBoolean("hint", true)
        rememberPos = sp.getBoolean("rememberpos", false)
        iconScale = sp.getInt("iscale", 100).coerceIn(40, 220)
        gridGap = sp.getInt("gap", 1).coerceIn(0, 2)
        gridRows = sp.getInt("rows", 0).coerceIn(0, 12)
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
        useRoot = sp.getBoolean("use_root", true)
        updSkip = sp.getStringSet("upd_skip", emptySet())?.toSet() ?: emptySet()
        updSources = sp.getStringSet("upd_sources", null)?.toSet()?.let { st ->
            // прежний набор по умолчанию (до Codeberg) получает и Codeberg
            if (st == setOf("fdroid", "izzy", "aptoide", "github", "gitlab")) st + "codeberg" else st
        } ?: setOf("fdroid", "izzy", "aptoide", "github", "gitlab", "codeberg")
        ghToken = sp.getString("gh_token", "") ?: ""
        autoUpd = sp.getBoolean("auto_upd", false)
        autoUpdHours = sp.getInt("auto_upd_hours", 24).let { if (it == 6 || it == 24 || it == 72) it else 24 }
        autoInstall = sp.getBoolean("auto_install", false)
        updFound = sp.getInt("upd_found", 0)
        transparentTiles = sp.getBoolean("tile_transparent", true)
        iconPack = sp.getString("icon_pack", "") ?: ""
        nativeHome = sp.getString("native_home", "") ?: ""
        hardOff = sp.getStringSet("home_hard_off", emptySet())?.toSet() ?: emptySet()
        homeRows = sp.getBoolean("home_rows", true)
        hiddenChannels = sp.getStringSet("hidden_channels", emptySet())?.toSet() ?: emptySet()
        weatherCity = sp.getString("weather_city", "") ?: ""
        favorites = (sp.getString("favorites", "") ?: "").split(',').filter { it.isNotBlank() }
        returnTo = sp.getString("return_to", "menu") ?: "menu"
        homeModule = sp.getBoolean("home_module", true)
        bootWarm = sp.getBoolean("boot_warm", true)
        pinnedAccess = sp.getStringSet("pinned_access", emptySet())?.toSet() ?: emptySet()
        noRestoreAccess = sp.getStringSet("no_restore_access", emptySet())?.toSet() ?: emptySet()
        order = (sp.getString("order", "") ?: "").split("\n").filter { it.isNotBlank() }
    }

    fun changeColumns(v: Int) { columns = v; sp.edit().putInt("cols", v).apply() }
    fun changeIconSize(v: Int) { iconSize = v; sp.edit().putInt("icon", v).apply() }
    fun changeListMode(v: Boolean) { listMode = v; sp.edit().putBoolean("list", v).apply() }
    fun changeSort(v: String) { sort = v; sp.edit().putString("sort", v).apply() }
    fun changeIconScale(v: Int) { iconScale = v.coerceIn(40, 220); sp.edit().putInt("iscale", iconScale).apply() }
    fun changeGridGap(v: Int) { gridGap = v.coerceIn(0, 2); sp.edit().putInt("gap", gridGap).apply() }
    fun changeGridRows(v: Int) { gridRows = v.coerceIn(0, 12); sp.edit().putInt("rows", gridRows).apply() }
    fun changeRememberPos(v: Boolean) { rememberPos = v; sp.edit().putBoolean("rememberpos", v).apply() }
    fun changeShowHint(v: Boolean) { showHint = v; sp.edit().putBoolean("hint", v).apply() }
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
    fun changeHomeModule(v: Boolean) { homeModule = v; sp.edit().putBoolean("home_module", v).apply() }
    fun changeHomeRows(v: Boolean) { homeRows = v; sp.edit().putBoolean("home_rows", v).apply() }
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
    fun lastLaunch(pkg: String): Long = sp.getLong("t_$pkg", 0L)
    fun changeHardOff(v: Set<String>) { hardOff = v; sp.edit().putStringSet("home_hard_off", v).apply() }
    fun changeNativeHome(v: String) { nativeHome = v; sp.edit().putString("native_home", v).apply() }
    fun changeReturnTo(v: String) { returnTo = v; sp.edit().putString("return_to", v).apply() }
    fun changeTheme(v: String) { theme = v; sp.edit().putString("theme", v).apply() }
    fun changeAccent(v: String) { accent = v; sp.edit().putString("accent", v).apply() }
    fun changeLang(v: String) { lang = v; sp.edit().putString("lang", v).apply() }
    fun changeTransparentTiles(v: Boolean) { transparentTiles = v; sp.edit().putBoolean("tile_transparent", v).apply() }
    fun toggleUpdSource(id: String) {
        updSources = if (id in updSources) updSources - id else updSources + id
        sp.edit().putStringSet("upd_sources", updSources).apply()
    }
    fun toggleUpdSkip(pkg: String) {
        updSkip = if (pkg in updSkip) updSkip - pkg else updSkip + pkg
        sp.edit().putStringSet("upd_skip", updSkip).apply()
    }
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
    fun changeBackupOnDelete(v: Boolean) { backupOnDelete = v; sp.edit().putBoolean("backup_delete", v).apply() }

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
