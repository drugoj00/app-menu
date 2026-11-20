@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.tvapps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun updResultText(ctx: android.content.Context, r: String): String = when {
    r == "ok" -> ctx.getString(R.string.ver_done)
    r == "system" -> ctx.getString(R.string.net_system_dialog)
    r == "need_adb" -> ctx.getString(R.string.ver_need_adb)
    r == "sig" -> ctx.getString(R.string.upd_sig_bad)
    r == "abi" -> ctx.getString(R.string.upd_abi_bad)
    r == "minsdk" -> ctx.getString(R.string.upd_minsdk_bad)
    r == "hash" -> ctx.getString(R.string.upd_hash_bad) + Updates.lastHashInfo.let { if (it.isBlank()) "" else "\n$it" }
    r == "uptodate" -> ctx.getString(R.string.upd_uptodate)
    r == "manual" -> ctx.getString(R.string.upd_manual)
    r == "net" -> ctx.getString(R.string.net_failed) + NetErr.reason(ctx).let { if (it.isBlank()) "" else ": " + it }
    else -> ctx.getString(R.string.ver_failed) + r.removePrefix("fail").let { if (it.length > 1) ": " + it.removePrefix(":") else "" }
}

/** Что ставится: установлено X (код) → новая Y (код), размер, источник. */
internal fun updInfoText(ctx: android.content.Context, a: AppUpd, c: UpdCand): String =
    "${a.instName} (${a.instCode}) → ${c.versionName} (${c.versionCode}) · " + (if (c.size > 0) ctx.getString(R.string.upd_size_mb, "%.1f".format(java.util.Locale.US, c.size / 1048576.0)) + " · " else "") + Updates.sourceName(ctx, c.source)

private fun candTag(ctx: android.content.Context, c: UpdCand, a: AppUpd): String = when {
    Updates.isNewer(c, a.instName, a.instCode) -> ctx.getString(R.string.upd_newer)
    Updates.compareVersions(c.versionName, a.instName) == 0 -> ctx.getString(R.string.upd_same)
    else -> ctx.getString(R.string.upd_older)
}

/** Строка списка обновлений «сверху вниз»: сведения на всю ширину, кнопки под ними (в узком окне телефона сведения не сжимаются в столбик). */
@Composable
private fun UpdRowV(content: @Composable () -> Unit) {
    val t = LocalTh.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(t.tile).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun UpdRow(content: @Composable () -> Unit) {
    val t = LocalTh.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(t.tile).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** Обновления одного приложения: найденные версии по источникам и установка. */
@Composable
fun UpdatesDialog(pkg: String, label: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var res by remember { mutableStateOf<AppUpd?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    LaunchedEffect(tick) {
        busy = true
        status = ctx.getString(R.string.upd_checking)
        res = try { Updates.check(ctx, pkg, force = tick > 0) } catch (e: CancellationException) { throw e } catch (e: Throwable) { null }
        status = if (res == null) ctx.getString(R.string.ver_failed) else ""
        busy = false
    }

    fun run(block: suspend () -> String) {
        if (busy) return
        scope.launch {
            busy = true
            status = ctx.getString(R.string.ver_working)
            status = try { block() } catch (e: CancellationException) { throw e } catch (e: Throwable) { ctx.getString(R.string.ver_failed) + ": " + (e.message ?: "") }
            busy = false
        }
    }

    HDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.88f).clip(RoundedCornerShape(24.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(24.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.upd_title, label), color = t.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val inst = AppBackups.installedVersion(ctx, pkg)
            Text(
                if (inst != null) stringResource(R.string.ver_installed, inst.first, inst.second) else stringResource(R.string.ver_not_installed),
                color = t.sub, fontSize = 14.sp,
            )
            Text(stringResource(R.string.upd_hint), color = t.hint, fontSize = 12.sp)
            if (status.isNotEmpty()) Text(status, color = t.accent, fontSize = 14.sp)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionRow(HIcons.refresh, stringResource(R.string.upd_check_again), "", Modifier.focusRequester(first)) { if (!busy) tick++ }
                Chip(stringResource(R.string.close), true) { onClose() }
            }
            val r = res
            BarLazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (r != null) {
                    if (r.cands.isEmpty()) item { Text(stringResource(R.string.upd_none), color = t.hint, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
                    else if (!r.hasUpdate) item { Text(stringResource(R.string.upd_nothing_new), color = t.sub, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
                    items(r.cands, key = { it.source }) { c ->
                        UpdRow {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    Updates.sourceName(ctx, c.source) + " · " + c.versionName + if (c.versionCode > 0) " (${c.versionCode})" else "",
                                    color = t.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    candTag(ctx, c, r) + (if (c.manual) " · " + stringResource(R.string.upd_manual) else "") + (if (c.note.isNotBlank()) " · ${c.note}" else ""),
                                    color = t.hint, fontSize = 12.sp,
                                )
                            }
                            if (Updates.isNewer(c, r.instName, r.instCode) && !c.manual) {
                                ActionRow(HIcons.download, stringResource(R.string.upd_install)) {
                                    run { updResultText(ctx, Updates.install(ctx, r, c) { s -> status = s }) }
                                }
                            }
                        }
                    }
                    if (r.errors.isNotEmpty()) item { Text(stringResource(R.string.upd_errors, r.errors.joinToString("; ")), color = t.hint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
        }
    }
}

/** Включение и выключение источников обновлений; хрупкие помечены. */
@Composable
private fun SourcesDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    HDialog(onDismissRequest = onClose) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.upd_sources_title), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.upd_sources_hint), color = t.hint, fontSize = 12.sp)
            Updates.ALL_SOURCES.forEachIndexed { idx, id ->
                val on = id in Cfg.updSources
                val frag = id in Updates.FRAGILE
                Chip(
                    Updates.sourceName(ctx, id) + " · " + ctx.getString(if (on) R.string.upd_src_on else R.string.upd_src_off) + (if (frag) " · " + ctx.getString(R.string.upd_src_fragile) else "") +
                        (if (id in Updates.UNVERIFIED) " · " + ctx.getString(R.string.upd_src_unverified) else ""),
                    on, if (idx == 0) Modifier.focusRequester(first) else Modifier,
                ) { Cfg.toggleUpdSource(id) }
            }
            Chip(stringResource(R.string.close), true) { onClose() }
        }
    }
}

/** Настройки проверки по расписанию: вкл/выкл, период, автоустановка. Расписание ставится сразу. */
@Composable
private fun AutoUpdDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    // Android 13+: без разрешения на уведомления найденное видно только в шапке и в журнале
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotif() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            runCatching { perm.launch("android.permission.POST_NOTIFICATIONS") }
    }
    HDialog(onDismissRequest = onClose) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.upd_auto_title), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.upd_auto_hint), color = t.hint, fontSize = 12.sp)
            Chip(
                stringResource(R.string.upd_auto_on) + " · " + stringResource(if (Cfg.autoUpd) R.string.on else R.string.off),
                Cfg.autoUpd, Modifier.focusRequester(first),
            ) {
                Cfg.changeAutoUpd(!Cfg.autoUpd)
                if (Cfg.autoUpd) askNotif()
                UpdSched.apply(ctx)
            }
            Text(stringResource(R.string.upd_auto_every), color = t.sub, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(6 to R.string.upd_every_6, 24 to R.string.upd_every_24, 72 to R.string.upd_every_72).forEach { (h, r) ->
                    Chip(stringResource(r), Cfg.autoUpdHours == h) { Cfg.changeAutoUpdHours(h); UpdSched.apply(ctx) }
                }
            }
            Chip(
                stringResource(R.string.upd_auto_install) + " · " + stringResource(if (Cfg.autoInstall) R.string.on else R.string.off),
                Cfg.autoInstall,
            ) { Cfg.changeAutoInstall(!Cfg.autoInstall) }
            Text(stringResource(R.string.upd_auto_install_hint), color = t.hint, fontSize = 12.sp)
            Chip(stringResource(R.string.close), true) { onClose() }
        }
    }
}

/** Токен GitHub (необязательно): снимает ограничение на число запросов. */
@Composable
private fun TokenDialog(onClose: () -> Unit) {
    val t = LocalTh.current
    var v by remember { mutableStateOf(Cfg.ghToken) }
    val okReq = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { okReq.requestFocus() } }
    HDialog(onDismissRequest = onClose) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.upd_token_title), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                v, { v = it.trim().take(200) }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = t.text, unfocusedTextColor = t.text,
                    focusedBorderColor = t.accent, unfocusedBorderColor = t.border, cursorColor = t.accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(stringResource(R.string.cancel), false) { onClose() }
                Chip(stringResource(R.string.folder_ok), true, Modifier.focusRequester(okReq)) { Cfg.changeGhToken(v); onClose() }
            }
        }
    }
}

/** Проверка всех установленных приложений: список обновлений, «Обновить» и «Обновить все», пропуск ненужных. */
@Composable
fun UpdatesAllDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<AppUpd>?>(null) }
    var status by remember { mutableStateOf("") }
    var statFrac by remember { mutableStateOf<Float?>(null) }   // полоска хода: проверка и «обновить всё» (определённая, без бегущей)
    var busy by remember { mutableStateOf(false) }
    var showSkipped by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(false) }
    var single by remember { mutableStateOf<AppUpd?>(null) }
    // 13.0 код 57 (правило «никаких тихих установок»): ход и итог по каждому приложению показываются в самом списке
    val rowStat = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    val rowPct = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() }
    val doneSet = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var method by remember { mutableStateOf("") }
    val resLines = remember { androidx.compose.runtime.mutableStateListOf<String>() }   // итоги уже обработанных в этом запуске: «название: итог»
    var curApp by remember { mutableStateOf<Pair<AppUpd, UpdCand>?>(null) }   // приложение, которое ставится сейчас: его сведения и ход видны над списком
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    fun check(force: Boolean) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                statFrac = 0f
                list = Updates.checkAll(ctx, force) { d, n -> status = ctx.getString(R.string.upd_progress, d, n); statFrac = if (n > 0) d.toFloat() / n else 0f }
                list?.let { UpdSched.record(it) }   // число для шапки
                statFrac = 1f
                status = ""
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                status = ctx.getString(R.string.ver_failed) + ": " + (e.message ?: "")
            }
            busy = false
        }
    }

    fun setRow(a: AppUpd, c: UpdCand, s: String) {
        rowStat[a.pkg] = s
        // определённый ход: проценты установки («NN%») или доля скачанных мегабайт от размера из выпуска
        val p = Regex("""^\s*(\d{1,3})\s*%""").find(s)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (c.size > 0 && s.startsWith(ctx.getString(R.string.net_downloading, c.versionName, 0).substringBefore(c.versionName))) Regex("""(\d+)\D*$""").find(s)?.groupValues?.get(1)?.toLongOrNull()?.let { (it * 1048576L * 100 / c.size).toInt() } else null
        if (p != null) rowPct[a.pkg] = p.coerceIn(0, 100)
    }

    /** Установка списка в этом окне: viaAdb = способ выбран заранее (ADB/root, «Обновить все»), иначе обычный выбор установщика. Ход и итог по каждому видны в строке. */
    fun installList(items: List<AppUpd>, viaAdb: Boolean) {
        if (busy) return
        if (viaAdb && !Adb.isConnected) { status = ctx.getString(R.string.upd_need_adb_all); return }
        scope.launch {
            busy = true
            var ok = 0
            statFrac = 0f
            method = if (viaAdb) InstallerPick.labelOf(ctx, InstallerPick.ADB) else ""
            resLines.clear()
            items.forEach { rowStat[it.pkg] = ctx.getString(R.string.upd_row_wait); rowPct.remove(it.pkg) }
            items.forEachIndexed { i, a ->
                val c = a.best ?: return@forEachIndexed
                statFrac = i.toFloat() / items.size
                status = ctx.getString(R.string.upd_updating, i + 1, items.size, a.label)
                curApp = a to c
                InstallerPick.methodSink = { m -> method = m }
                InstallerPick.progressSink = { s -> setRow(a, c, s) }
                val r = try { Updates.install(ctx, a, c, quiet = viaAdb) { s -> setRow(a, c, s) } } catch (e: CancellationException) { throw e } catch (e: Throwable) { "fail:" + (e.message ?: "") }
                InstallerPick.methodSink = null; InstallerPick.progressSink = null
                if (r == "ok") {
                    ok++; doneSet.add(a.pkg); rowPct[a.pkg] = 100
                    rowStat[a.pkg] = ctx.getString(R.string.upd_row_done, c.versionName, c.versionCode.toInt())
                } else rowStat[a.pkg] = updResultText(ctx, r)
                resLines.add(a.label + ": " + rowStat[a.pkg])
            }
            statFrac = 1f
            status = ctx.getString(R.string.upd_all_done, ok, items.size)
            if (ok > 0) list?.let { l -> UpdSched.record(l.map { x -> if (x.pkg in doneSet) x.copy(cands = emptyList()) else x }) }
            busy = false
        }
    }

    fun refreshOne(pkg: String) {
        scope.launch {
            val fresh = runCatching { Updates.check(ctx, pkg, force = true) }.getOrNull()
            list = list?.map { if (it.pkg == pkg && fresh != null) fresh else it }
        }
    }

    val all = list.orEmpty()
    val withUpd = all.filter { it.best != null }
    val skippedN = withUpd.count { it.pkg in Cfg.updSkip }
    val shown = withUpd.filter { showSkipped || it.pkg !in Cfg.updSkip }
    val updatable = withUpd.filter { it.pkg !in Cfg.updSkip && it.pkg !in doneSet }

    // обновление по расписанию с «Устанавливать автоматически»: окно открывается и ставит само, но всё видно (программа или уведомление открыли это окно)
    LaunchedEffect(Unit) { if (UpdOpen.autoStart && list == null) check(false) }   // автоустановка: окно само проверяет и затем ставит на виду
    LaunchedEffect(list) {
        if (list != null && UpdOpen.autoStart) {
            UpdOpen.autoStart = false
            val todo = list.orEmpty().filter { it.best != null && it.pkg !in Cfg.updSkip }
            if (todo.isNotEmpty()) { if (!Adb.isConnected) runCatching { autoConnect(ctx) }; installList(todo, true) }
        }
    }

    if (token) TokenDialog { token = false }
    if (sources) SourcesDialog { sources = false }
    if (auto) AutoUpdDialog { auto = false }
    single?.let { a -> UpdatesDialog(a.pkg, a.label) { single = null; refreshOne(a.pkg) } }

    HDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.9f).clip(RoundedCornerShape(24.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(24.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.upd_cell), color = t.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            BarLazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 13.0 код 57: шапка (ход, итоги, кнопки) лежит внутри прокручиваемого списка: на телефоне, где шапка занимает весь экран, строки обновлений остаются доступны
                item(key = "head") { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (list == null) Text(stringResource(R.string.upd_hint), color = t.hint, fontSize = 12.sp)   // длинная подсказка уходит, когда нужно место под список, ход и итоги
                if (status.isNotEmpty()) Text(status, color = t.accent, fontSize = 14.sp)
                else if (list != null) Text(if (withUpd.isEmpty()) stringResource(R.string.upd_nothing_new) else stringResource(R.string.upd_found, withUpd.size), color = t.sub, fontSize = 14.sp)
                if (method.isNotEmpty()) Text(stringResource(R.string.su_method, method), color = t.sub, fontSize = 13.sp)
                curApp?.let { (a, c) ->
                    // что ставится сейчас: название, установлено X (код) → новая Y (код), размер; ход этого приложения (определённый) и итог
                    Text(a.label + ": " + updInfoText(ctx, a, c), color = t.text, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    rowStat[a.pkg]?.let { st -> Text(st, color = if (a.pkg in doneSet) t.accent else t.sub, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    rowPct[a.pkg]?.let { FlatProgress(it / 100f) }
                }
                statFrac?.let { FlatProgress(it) }
                resLines.takeLast(6).forEach { Text(it, color = t.sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionRow(HIcons.refresh, stringResource(R.string.upd_check), "", Modifier.focusRequester(first)) { check(list != null) }
                    if (updatable.isNotEmpty()) ActionRow(HIcons.download, stringResource(R.string.upd_update_all, updatable.size)) { installList(updatable, true) }
                    if (skippedN > 0) Chip(stringResource(if (showSkipped) R.string.upd_hide_skipped else R.string.upd_show_skipped, skippedN), false) { showSkipped = !showSkipped }
                    ActionRow(HIcons.list, stringResource(R.string.upd_sources)) { sources = true }
                    Chip(stringResource(R.string.upd_auto) + if (Cfg.autoUpd) " ✓" else "", Cfg.autoUpd) { auto = true }
                    ActionRow(HIcons.gear, stringResource(R.string.upd_token)) { token = true }
                    Chip(stringResource(R.string.close), true) { onClose() }
                }
                } }
                items(shown, key = { it.pkg }) { a ->
                    val c = a.best!!
                    val skipped = a.pkg in Cfg.updSkip
                    UpdRowV {
                        Column(Modifier.fillMaxWidth()) {
                            Text(a.label, color = if (skipped) t.hint else t.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // что ставится: установлено X (код) → новая Y (код), размер, источник
                            Text(updInfoText(ctx, a, c) + if (a.newer.size > 1) " · +${a.newer.size - 1}" else "", color = t.hint, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            rowStat[a.pkg]?.let { st ->
                                Text(st, color = if (a.pkg in doneSet) t.accent else t.sub, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                rowPct[a.pkg]?.let { FlatProgress(it / 100f) }
                            }
                        }
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (a.pkg !in doneSet) ActionRow(HIcons.download, stringResource(R.string.upd_update)) { installList(listOf(a), false) }
                            if (c.body.isNotBlank()) ActionRow(HIcons.list, stringResource(R.string.notes_btn)) { SelfUpd.notes = c }
                            ActionRow(HIcons.list, stringResource(R.string.upd_details)) { single = a }
                            Chip(stringResource(if (skipped) R.string.upd_unskip else R.string.upd_skip), skipped) { Cfg.toggleUpdSkip(a.pkg) }
                        }
                    }
                }
            }
        }
    }
}
