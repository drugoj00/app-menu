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

private fun updResultText(ctx: android.content.Context, r: String): String = when {
    r == "ok" -> ctx.getString(R.string.ver_done)
    r == "system" -> ctx.getString(R.string.net_system_dialog)
    r == "need_adb" -> ctx.getString(R.string.ver_need_adb)
    r == "sig" -> ctx.getString(R.string.upd_sig_bad)
    r == "abi" -> ctx.getString(R.string.upd_abi_bad)
    r == "minsdk" -> ctx.getString(R.string.upd_minsdk_bad)
    r == "hash" -> ctx.getString(R.string.upd_hash_bad)
    r == "uptodate" -> ctx.getString(R.string.upd_uptodate)
    r == "manual" -> ctx.getString(R.string.upd_manual)
    r == "net" -> ctx.getString(R.string.net_failed)
    else -> ctx.getString(R.string.ver_failed) + r.removePrefix("fail").let { if (it.length > 1) ": " + it.removePrefix(":") else "" }
}

private fun candTag(ctx: android.content.Context, c: UpdCand, a: AppUpd): String = when {
    Updates.isNewer(c, a.instName, a.instCode) -> ctx.getString(R.string.upd_newer)
    Updates.compareVersions(c.versionName, a.instName) == 0 -> ctx.getString(R.string.upd_same)
    else -> ctx.getString(R.string.upd_older)
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
                Chip(stringResource(R.string.upd_check_again), false, Modifier.focusRequester(first)) { if (!busy) tick++ }
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
                                Chip(stringResource(R.string.upd_install), false) {
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
    var busy by remember { mutableStateOf(false) }
    var showSkipped by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(false) }
    var single by remember { mutableStateOf<AppUpd?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    fun check(force: Boolean) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                list = Updates.checkAll(ctx, force) { d, n -> status = ctx.getString(R.string.upd_progress, d, n) }
                list?.let { UpdSched.record(it) }   // число для шапки
                status = ""
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                status = ctx.getString(R.string.ver_failed) + ": " + (e.message ?: "")
            }
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
    val updatable = withUpd.filter { it.pkg !in Cfg.updSkip }

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
            Text(stringResource(R.string.upd_hint), color = t.hint, fontSize = 12.sp)
            if (status.isNotEmpty()) Text(status, color = t.accent, fontSize = 14.sp)
            else if (list != null) Text(if (withUpd.isEmpty()) stringResource(R.string.upd_nothing_new) else stringResource(R.string.upd_found, withUpd.size), color = t.sub, fontSize = 14.sp)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(stringResource(R.string.upd_check), false, Modifier.focusRequester(first)) { check(list != null) }
                if (updatable.isNotEmpty()) Chip(stringResource(R.string.upd_update_all, updatable.size), true) {
                    if (busy) return@Chip
                    if (!Adb.isConnected) { status = ctx.getString(R.string.upd_need_adb_all); return@Chip }
                    scope.launch {
                        busy = true
                        var ok = 0
                        updatable.forEachIndexed { i, a ->
                            val c = a.best ?: return@forEachIndexed
                            status = ctx.getString(R.string.upd_updating, i + 1, updatable.size, a.label)
                            val r = try { Updates.install(ctx, a, c) { s -> status = s } } catch (e: CancellationException) { throw e } catch (e: Throwable) { "fail:" + (e.message ?: "") }
                            if (r == "ok") { ok++; refreshOne(a.pkg) }
                        }
                        status = ctx.getString(R.string.upd_all_done, ok, updatable.size)
                        busy = false
                    }
                }
                if (skippedN > 0) Chip(stringResource(if (showSkipped) R.string.upd_hide_skipped else R.string.upd_show_skipped, skippedN), false) { showSkipped = !showSkipped }
                Chip(stringResource(R.string.upd_sources), false) { sources = true }
                Chip(stringResource(R.string.upd_auto) + if (Cfg.autoUpd) " ✓" else "", Cfg.autoUpd) { auto = true }
                Chip(stringResource(R.string.upd_token), false) { token = true }
                Chip(stringResource(R.string.close), true) { onClose() }
            }
            BarLazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.pkg }) { a ->
                    val c = a.best!!
                    val skipped = a.pkg in Cfg.updSkip
                    UpdRow {
                        Column(Modifier.weight(1f)) {
                            Text(a.label, color = if (skipped) t.hint else t.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${a.instName} → ${c.versionName} · ${Updates.sourceName(ctx, c.source)}" + if (a.newer.size > 1) " · +${a.newer.size - 1}" else "",
                                color = t.hint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Chip(stringResource(R.string.upd_update), false) {
                            if (busy) return@Chip
                            scope.launch {
                                busy = true
                                status = ctx.getString(R.string.ver_working)
                                val r = try { Updates.install(ctx, a, c) { s -> status = s } } catch (e: CancellationException) { throw e } catch (e: Throwable) { "fail:" + (e.message ?: "") }
                                status = a.label + ": " + updResultText(ctx, r)
                                if (r == "ok") refreshOne(a.pkg)
                                busy = false
                            }
                        }
                        Chip(stringResource(R.string.upd_details), false) { single = a }
                        Chip(stringResource(if (skipped) R.string.upd_unskip else R.string.upd_skip), skipped) { Cfg.toggleUpdSkip(a.pkg) }
                    }
                }
            }
        }
    }
}
