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
    /** Куда возвращаться после запущенной программы: menu — в это меню, home — на родной главный экран. */
    var returnTo by mutableStateOf("menu"); private set
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
        transparentTiles = sp.getBoolean("tile_transparent", true)
        iconPack = sp.getString("icon_pack", "") ?: ""
        nativeHome = sp.getString("native_home", "") ?: ""
        returnTo = sp.getString("return_to", "menu") ?: "menu"
        bootWarm = sp.getBoolean("boot_warm", true)
        pinnedAccess = sp.getStringSet("pinned_access", emptySet())?.toSet() ?: emptySet()
        noRestoreAccess = sp.getStringSet("no_restore_access", emptySet())?.toSet() ?: emptySet()
        order = (sp.getString("order", "") ?: "").split("\n").filter { it.isNotBlank() }
    }

    fun changeColumns(v: Int) { columns = v; sp.edit().putInt("cols", v).apply() }
    fun changeIconSize(v: Int) { iconSize = v; sp.edit().putInt("icon", v).apply() }
    fun changeListMode(v: Boolean) { listMode = v; sp.edit().putBoolean("list", v).apply() }
    fun changeSort(v: String) { sort = v; sp.edit().putString("sort", v).apply() }
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
    fun changeNativeHome(v: String) { nativeHome = v; sp.edit().putString("native_home", v).apply() }
    fun changeReturnTo(v: String) { returnTo = v; sp.edit().putString("return_to", v).apply() }
    fun changeTheme(v: String) { theme = v; sp.edit().putString("theme", v).apply() }
    fun changeAccent(v: String) { accent = v; sp.edit().putString("accent", v).apply() }
    fun changeLang(v: String) { lang = v; sp.edit().putString("lang", v).apply() }
    fun changeTransparentTiles(v: Boolean) { transparentTiles = v; sp.edit().putBoolean("tile_transparent", v).apply() }
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

    fun launches(pkg: String): Int = sp.getInt("n_$pkg", 0)
    fun countLaunch(pkg: String) {
        sp.edit().putInt("n_$pkg", launches(pkg) + 1).apply()
        launchTick++
    }
}
