@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.tvapps

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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


@Composable
internal fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = LocalTh.current
    var focused by remember { mutableStateOf(false) }
    val (hm, hover) = rememberHover()
    Box(
        modifier
            .then(hm)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) t.chipSel else if (hover) t.tileFocus else t.tile)
            .border(if (focused) 3.dp else 1.dp, if (focused) t.accent else t.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (isNarrow()) 11.dp else 16.dp, vertical = if (isNarrow()) 5.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = t.text, fontSize = btnSp(15), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val t = LocalTh.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = t.sub, fontSize = 13.sp)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Options(options: List<Pair<T, String>>, current: T, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) -> Chip(label, v == current) { onPick(v) } }
    }
}

@Composable
fun SettingsScreen(apps: List<Entry>, onBack: () -> Unit, onFullscreenChanged: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var sub by remember { mutableStateOf("main") }
    var adbDialog by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(sub) { runCatching { first.requestFocus() } }
    var installFiles by remember { mutableStateOf<List<ApkFile>?>(null) }
    var browser by remember { mutableStateOf(false) }
    var net by remember { mutableStateOf(false) }
    var wifi by remember { mutableStateOf(false) }
    var backups by remember { mutableStateOf(false) }
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            val ok = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { Backup.write(ctx, it) } != null }.getOrDefault(false)
            Toast.makeText(ctx, ctx.getString(if (ok) R.string.backup_saved else R.string.failed), Toast.LENGTH_LONG).show()
        }
    }
    val loadBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val ok = runCatching { ctx.contentResolver.openInputStream(uri)?.use { Backup.restore(ctx, it) } == true }.getOrDefault(false)
            Toast.makeText(ctx, ctx.getString(if (ok) R.string.backup_restored else R.string.failed), Toast.LENGTH_LONG).show()
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
            Text(stringResource(if (sub == "main") R.string.settings else R.string.hidden_list), fontSize = 24.sp, color = t.text)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
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
                    Options(accentKeys.map { it to stringResource(names.getValue(it)) }, Cfg.accent) { Cfg.changeAccent(it) }
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
                    Text(stringResource(R.string.ctl_help), color = t.hint, fontSize = 13.sp)
                }
                Section(stringResource(R.string.set_tilebg)) {
                    Options(listOf(true to stringResource(R.string.tilebg_clear), false to stringResource(R.string.tilebg_card)), Cfg.transparentTiles) { Cfg.changeTransparentTiles(it) }
                }
                Section(stringResource(R.string.set_view)) {
                    Options(listOf(false to stringResource(R.string.view_grid), true to stringResource(R.string.view_list)), Cfg.listMode) { Cfg.changeListMode(it) }
                }
                if (!Cfg.listMode) Section(stringResource(R.string.set_cols)) {
                    Options(
                        listOf(0 to stringResource(R.string.cols_auto)) + listOf(3, 4, 5, 6, 7, 8, 10).map { it to it.toString() },
                        Cfg.columns,
                    ) { Cfg.changeColumns(it) }
                }
                Section(stringResource(R.string.set_backups)) {
                    Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.backupOnDelete) { Cfg.changeBackupOnDelete(it) }
                    Text(stringResource(R.string.backups_hint), color = t.hint, fontSize = 12.sp)
                    Chip(stringResource(R.string.backups_open), false) { backups = true }
                }
                Section(stringResource(R.string.set_iconpack)) {
                    val packs = remember { IconPacks.installed(ctx) }
                    Options(listOf("" to stringResource(R.string.iconpack_native)) + packs, Cfg.iconPack) { Cfg.changeIconPack(it) }
                    if (packs.isEmpty()) Text(stringResource(R.string.iconpack_none), color = t.hint, fontSize = 12.sp)
                    Text(stringResource(R.string.import_hint), color = t.hint, fontSize = 12.sp)
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
                }
                Section(stringResource(R.string.set_icon)) {
                    Options(listOf(3 to stringResource(R.string.size_xs), 0 to stringResource(R.string.size_s), 1 to stringResource(R.string.size_m), 2 to stringResource(R.string.size_l)), Cfg.iconSize) { Cfg.changeIconSize(it) }
                }
                Section(stringResource(R.string.set_labels)) {
                    Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.showLabels) { Cfg.changeShowLabels(it) }
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
                Section(stringResource(R.string.boot_title)) {
                    Text(stringResource(R.string.boot_hint), color = t.hint, fontSize = 12.sp)
                    Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.bootWarm) { Cfg.changeBootWarm(it) }
                }
                Section(stringResource(R.string.set_fullscreen)) {
                    Options(listOf(true to stringResource(R.string.on), false to stringResource(R.string.off)), Cfg.fullscreen) { Cfg.changeFullscreen(it); onFullscreenChanged() }
                }
                Section(stringResource(R.string.set_hidden)) {
                    Options(listOf(false to stringResource(R.string.hidden_hide), true to stringResource(R.string.hidden_show)), Cfg.showHidden) { Cfg.changeShowHidden(it) }
                    Chip(stringResource(R.string.hidden_list) + " (" + Cfg.hidden.size + ")", false) { sub = "hidden" }
                }
                Section(stringResource(R.string.set_install)) {
                    Text(stringResource(R.string.install_hint), color = t.hint, fontSize = 12.sp)
                    Chip(stringResource(R.string.fb_open), true) { browser = true }
                    Chip(stringResource(R.string.wifi_open), true) { wifi = true }
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
                Section(stringResource(R.string.set_home)) {
                    Text(stringResource(R.string.set_home_hint), color = t.hint, fontSize = 12.sp)
                    Text(stringResource(R.string.native_home_hint), color = t.hint, fontSize = 12.sp)
                    val natives = remember { nativeHomes(ctx).map { it.activityInfo.packageName to it.loadLabel(ctx.packageManager).toString() } }
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
                                    val out = runCatching { Adb.shell("cmd package set-home-activity ${ctx.packageName}/app.tvapps.HomeAlias") }.getOrDefault("")
                                    Toast.makeText(ctx, ctx.getString(if (out.contains("Success", true)) R.string.home_set_ok else R.string.failed), Toast.LENGTH_LONG).show()
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
                                    runCatching { Adb.shell("cmd package set-home-activity ${ri.activityInfo.packageName}/${ri.activityInfo.name}") }
                                }
                                setHomeMode(ctx, false)
                                Toast.makeText(ctx, ctx.getString(R.string.set_home_off_done), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                Section(stringResource(R.string.backup_title)) {
                    Text(stringResource(R.string.backup_hint), color = t.hint, fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(stringResource(R.string.backup_save), false) {
                            runCatching { saveBackup.launch("tvapps-backup.zip") }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.icon_no_picker), Toast.LENGTH_LONG).show() }
                        }
                        Chip(stringResource(R.string.backup_load), false) {
                            runCatching { loadBackup.launch(arrayOf("*/*")) }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.icon_no_picker), Toast.LENGTH_LONG).show() }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(stringResource(R.string.backup_here), false) {
                            Backup.autoSave(ctx)
                            Toast.makeText(ctx, ctx.getString(R.string.backup_here_done, Backup.autoFile(ctx)?.absolutePath.orEmpty()), Toast.LENGTH_LONG).show()
                        }
                        Chip(stringResource(R.string.backup_restore_here), false) {
                            val f = Backup.autoFile(ctx)
                            val ok = f != null && f.exists() && runCatching { f.inputStream().use { Backup.restore(ctx, it) } }.getOrDefault(false)
                            Toast.makeText(ctx, ctx.getString(if (ok) R.string.backup_restored else R.string.failed), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                Section(stringResource(R.string.adb_title)) {
                    Text(stringResource(if (Conn.connected) R.string.adb_on else R.string.adb_off), color = if (Conn.connected) t.accent else t.sub, fontSize = 14.sp)
                    Chip(stringResource(R.string.adb_connect_btn), false) { adbDialog = true }
                }
                Spacer(Modifier.size(24.dp))
            } else {
                HiddenList(apps)
            }
        }
    }
    if (adbDialog) AdbConnectDialog(onClose = { adbDialog = false; Conn.refresh() })
    installFiles?.let { files -> InstallDialog(files) { installFiles = null } }
    if (net) NetDialog { net = false }
    if (backups) BackupsDialog { backups = false }
    if (browser) FileBrowser(onApks = { browser = false; installFiles = it }, onClose = { browser = false })
    if (wifi) UploadDialog(onApks = { wifi = false; installFiles = it }, onClose = { wifi = false })
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


/** Список найденных APK с отметками; установка тихо по ADB или по одному через системное окно. */
@Composable
private fun InstallDialog(files: List<ApkFile>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var chosen by remember { mutableStateOf(files.map { it.name }.toSet()) }
    var busy by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    HDialog(onDismissRequest = { if (!busy) onClose() }) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(18.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.install_title, files.size), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (files.isEmpty()) Text(stringResource(R.string.install_none), color = t.sub, fontSize = 14.sp)
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                files.forEach { f ->
                    val on = f.name in chosen
                    var focused by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .onFocusChanged { focused = it.isFocused }
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (focused) t.tileFocus else androidx.compose.ui.graphics.Color.Transparent)
                            .border(if (focused) 2.dp else 0.dp, if (focused) t.accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(10.dp))
                            .toggleable(on, onValueChange = { v -> chosen = if (v) chosen + f.name else chosen - f.name })
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(f.name, color = t.text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((f.size / 1024 / 1024).toString() + " MB", color = t.hint, fontSize = 11.sp)
                        Switch(on, null)
                    }
                }
            }
            if (busy) Text(stringResource(R.string.working), color = t.accent, fontSize = 13.sp)
            Text(stringResource(if (Adb.isConnected) R.string.install_silent else R.string.install_dialogs), color = t.hint, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.cancel), false, Modifier.focusRequester(fr)) { if (!busy) onClose() }
                Chip(stringResource(R.string.install_go, chosen.size), true) {
                    val picked = files.filter { it.name in chosen }
                    if (picked.isEmpty() || busy) return@Chip
                    if (Adb.isConnected) {
                        busy = true
                        scope.launch {
                            val (ok, bad) = Batch.installViaAdb(ctx, picked)
                            busy = false
                            Toast.makeText(ctx, ctx.getString(R.string.install_result, ok, bad), Toast.LENGTH_LONG).show()
                            Cfg.bumpIcons()
                            onClose()
                        }
                    } else {
                        busy = true
                        scope.launch {
                            picked.forEach {
                                val parts = it.parts
                                if (parts != null) {
                                    // OBB кладём до установки, при неудаче убираем
                                    val copied = if (it.obb.isEmpty()) 0 else Bundles.copyObbAny(it.obb)
                                    if (copied < 0) Toast.makeText(ctx, ctx.getString(R.string.bundle_no_storage), Toast.LENGTH_LONG).show()
                                    else runCatching { SessionInstaller.install(ctx, parts) { Bundles.deleteObb(it.obb) } }
                                        .onFailure { e -> Bundles.deleteObb(it.obb); Toast.makeText(ctx, ctx.getString(R.string.bundle_failed) + ": " + (e.message ?: ""), Toast.LENGTH_LONG).show() }
                                }
                                else Pending.installs.add(Batch.shareUri(ctx, Batch.materialize(it)))
                            }
                            busy = false
                            Pending.next(ctx)
                            onClose()
                        }
                    }
                }
            }
        }
    }
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
