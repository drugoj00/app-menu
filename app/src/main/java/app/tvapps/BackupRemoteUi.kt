package app.tvapps

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Короткая подпись значения в строке списка: длинное значение обрезается, чтобы название строки оставалось читаемым. */
private fun shortText(s: String, n: Int): String = if (s.length > n) s.take(n - 1) + "…" else s

/**
 * Окно «Копии в сеть и синхронизация» (15.0): назначения копий (SMB, WebDAV, Яндекс Диск, папка или USB), расписание, ручное сохранение и восстановление,
 * синхронизация настроек между устройствами. Всё на списках (пульт, касание, мышь), поля ввода открываются отдельными окнами; пароли скрыты, справа глазик.
 */
@Composable
fun BackupRemoteDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rev by remember { mutableIntStateOf(0) }
    var screen by remember { mutableStateOf("list") }
    var edit by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    var files by remember { mutableStateOf<List<BkFile>?>(null) }
    var hosts by remember { mutableStateOf<List<String>?>(null) }
    var localPath by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    rev.let { }

    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            val t = BkTarget(BkTargets.newId(), "saf", ctx.getString(R.string.bkr_k_folder), uri.toString(), "")
            BkTargets.add(ctx, t); rev++; screen = "card:" + t.id
        } else screen = "add"
    }

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    /** Выполняет операцию над назначением в фоне и сообщает итог. */
    fun op(t: BkTarget?, label: String, block: () -> String) {
        if (busy != null) return
        busy = label
        scope.launch {
            val res = withContext(Dispatchers.IO) { runCatching(block).getOrElse { ctx.getString(R.string.bkr_r_fail, BkEngine.reason(ctx, it)) } }
            if (t != null) BkTargets.setLastResult(ctx, t.id, res)
            busy = null; rev++
            toast(res)
        }
    }

    val b = busy
    if (b != null) { ListDialog(stringResource(R.string.bkr_title), listOf(Opt(b + "…")), {}); return }

    // ---- окна ввода
    val ed = edit
    if (ed != null) {
        val t = BkTargets.find(ctx, ed.first)
        if (t == null) { edit = null } else {
            val secret = ed.second == "pass"
            val title = stringResource(when (ed.second) { "name" -> R.string.bkr_f_name; "url" -> if (t.kind == "local") R.string.bkr_f_path else R.string.bkr_f_addr; "user" -> R.string.bkr_f_user; else -> R.string.bkr_f_pass })
            val hint = when {
                ed.second == "url" && t.kind == "smb" -> stringResource(R.string.bkr_h_smb)
                ed.second == "url" && t.kind == "dav" -> stringResource(R.string.bkr_h_dav)
                ed.second == "pass" && t.kind == "dav" && t.url.contains("yandex") -> stringResource(R.string.bkr_h_yandex)
                ed.second == "pass" -> stringResource(R.string.bkr_h_pass)
                else -> ""
            }
            val initial = when (ed.second) { "name" -> t.name; "url" -> t.url; "user" -> t.user; else -> "" }
            TextDialog(title, initial, hint, { v ->
                when (ed.second) {
                    "name" -> t.name = v
                    "url" -> t.url = v
                    "user" -> t.user = v
                    "pass" -> if (!BkVault.put(ctx, t.id, v)) toast(ctx.getString(R.string.bkr_pass_mem))
                }
                if (ed.second != "pass") BkTargets.update(ctx, t)
                edit = null; rev++
            }, { edit = null }, secret = secret)
        }
        return
    }
    val cf = confirm
    if (cf != null) {
        val t = BkTargets.find(ctx, cf.second)
        ListDialog(
            stringResource(when (cf.first) { "del" -> R.string.bkr_c_del; "pull" -> R.string.bkr_c_pull; else -> R.string.bkr_c_push }) + (t?.let { ": " + it.name } ?: ""),
            listOf(
                Opt(stringResource(R.string.qa_yes), danger = cf.first == "del", onClick = {
                    confirm = null
                    when (cf.first) {
                        "del" -> { BkTargets.remove(ctx, cf.second); screen = "list"; rev++ }
                        "pull" -> if (t != null) op(t, ctx.getString(R.string.bkr_op_pull)) {
                            val ok = BkEngine.pull(ctx, t)
                            if (ok) ctx.getString(R.string.bkr_r_pulled) else ctx.getString(R.string.bkr_r_fail, ctx.getString(R.string.bkr_e_other))
                        }
                        else -> if (t != null) op(t, ctx.getString(R.string.bkr_op_push)) { BkEngine.push(ctx, t); ctx.getString(R.string.bkr_r_pushed) }
                    }
                }),
                Opt(stringResource(R.string.cancel), onClick = { confirm = null }),
            ),
            { confirm = null },
            hint = stringResource(when (cf.first) { "pull" -> R.string.bkr_c_pull_h; "push" -> R.string.bkr_c_push_h; else -> R.string.bkr_c_del_h }),
        )
        return
    }

    val sched = BkTargets.schedule(ctx)
    val targets = BkTargets.all(ctx)

    when {
        screen == "list" -> {
            val opts = buildList {
                targets.forEach { t ->
                    val flags = listOfNotNull(if (t.auto) stringResource(R.string.bkr_flag_auto) else null, if (t.sync) stringResource(R.string.bkr_flag_sync) else null).joinToString(" · ")
                    add(Opt(t.name.ifBlank { BkTargets.kindName(ctx, t.kind) }, flags, onClick = { screen = "card:" + t.id }))
                }
                add(Opt(stringResource(R.string.bkr_add), onClick = { screen = "add" }))
                if (targets.isNotEmpty()) {
                    add(Opt(stringResource(R.string.bkr_schedule), stringResource(when (sched) { 1 -> R.string.bkr_sch_day; 2 -> R.string.bkr_sch_week; else -> R.string.bkr_sch_off }), onClick = { BkTargets.setSchedule(ctx, (sched + 1) % 3); rev++ }))
                    add(Opt(stringResource(R.string.bkr_save_all), onClick = {
                        op(null, ctx.getString(R.string.bkr_op_save)) {
                            var ok = 0
                            var last = ""
                            targets.forEach { t ->
                                try { BkEngine.saveNow(ctx, t); ok++; BkTargets.setLastResult(ctx, t.id, ctx.getString(R.string.bkr_r_saved)) }
                                catch (e: Throwable) { last = BkEngine.reason(ctx, e); BkTargets.setLastResult(ctx, t.id, ctx.getString(R.string.bkr_r_fail, last)) }
                            }
                            if (ok == targets.size) ctx.getString(R.string.bkr_r_saved_n, ok) else ctx.getString(R.string.bkr_r_fail, last)
                        }
                    }))
                    if (targets.any { it.sync }) add(Opt(stringResource(R.string.bkr_sync_all), onClick = {
                        op(null, ctx.getString(R.string.bkr_op_sync)) {
                            var did = 0
                            var last = ""
                            targets.filter { it.sync }.forEach { t ->
                                try {
                                    val r = BkEngine.sync(ctx, t); did++
                                    BkTargets.setLastResult(ctx, t.id, ctx.getString(when (r) { SyncRes.PUSHED -> R.string.bkr_r_pushed; SyncRes.PULLED -> R.string.bkr_r_pulled; SyncRes.SAME -> R.string.bkr_r_same }))
                                } catch (e: Throwable) { last = BkEngine.reason(ctx, e); BkTargets.setLastResult(ctx, t.id, ctx.getString(R.string.bkr_r_fail, last)) }
                            }
                            if (last.isEmpty()) ctx.getString(R.string.bkr_r_synced) else ctx.getString(R.string.bkr_r_fail, last)
                        }
                    }))
                }
            }
            ListDialog(stringResource(R.string.bkr_title), opts, onClose, hint = stringResource(R.string.bkr_dlg_hint))
        }
        screen == "add" -> {
            fun create(kind: String, name: String, url: String) { val t = BkTarget(BkTargets.newId(), kind, name, url, ""); BkTargets.add(ctx, t); rev++; screen = "card:" + t.id }
            ListDialog(
                stringResource(R.string.bkr_add),
                listOf(
                    Opt(stringResource(R.string.bkr_k_smb), onClick = { create("smb", ctx.getString(R.string.bkr_n_smb), "smb://") }),
                    Opt(stringResource(R.string.bkr_k_dav), onClick = { create("dav", "WebDAV", "https://") }),
                    Opt(stringResource(R.string.bkr_k_yandex), onClick = { create("dav", ctx.getString(R.string.bkr_n_yandex), "https://webdav.yandex.ru/AppMenu/") }),
                    Opt(stringResource(R.string.bkr_k_local), onClick = { localPath = true; screen = "local" }),
                    Opt(stringResource(R.string.back_plain), onClick = { screen = "list" }),
                ),
                { screen = "list" },
            )
        }
        screen == "local" -> {
            val roots = remember { Storage.roots() }
            fun createLocal(path: String) { val t = BkTarget(BkTargets.newId(), "local", ctx.getString(R.string.bkr_k_folder), path, ""); BkTargets.add(ctx, t); rev++; screen = "card:" + t.id }
            val opts = buildList {
                roots.forEachIndexed { i, f -> add(Opt((if (i == 0) ctx.getString(R.string.bkr_mem) else f.name) + ": " + f.absolutePath.take(28), onClick = { createLocal(f.absolutePath.trimEnd('/') + "/AppMenuBackup") })) }
                add(Opt(stringResource(R.string.bkr_pick_system), onClick = { runCatching { tree.launch(null) }.onFailure { toast(ctx.getString(R.string.icon_no_picker)); screen = "add" } }))
                add(Opt(stringResource(R.string.back_plain), onClick = { screen = "add" }))
            }
            ListDialog(stringResource(R.string.bkr_k_local), opts, { screen = "add" }, hint = stringResource(R.string.bkr_local_hint))
        }
        screen.startsWith("hosts:") -> {
            val id = screen.removePrefix("hosts:")
            val t = BkTargets.find(ctx, id)
            LaunchedEffect(screen) { hosts = null; hosts = runCatching { Lan.scanSmb() }.getOrDefault(emptyList()) }
            val hs = hosts
            val opts = buildList {
                if (hs == null) add(Opt(stringResource(R.string.working)))
                else if (hs.isEmpty()) add(Opt(stringResource(R.string.bkr_no_hosts)))
                else hs.forEach { h -> add(Opt(h, onClick = { if (t != null) { t.url = "smb://$h/"; BkTargets.update(ctx, t); rev++; edit = id to "url" }; screen = "card:$id" })) }
                add(Opt(stringResource(R.string.back_plain), onClick = { screen = "card:$id" }))
            }
            ListDialog(stringResource(R.string.bkr_find), opts, { screen = "card:$id" })
        }
        screen.startsWith("restore:") -> {
            val id = screen.removePrefix("restore:")
            val t = BkTargets.find(ctx, id)
            var err by remember { mutableStateOf("") }
            LaunchedEffect(screen) {
                files = null; err = ""
                if (t != null) files = withContext(Dispatchers.IO) { runCatching { BkEngine.copies(ctx, t) }.getOrElse { err = BkEngine.reason(ctx, it); emptyList() } }
            }
            val fl = files
            val opts = buildList {
                if (fl == null) add(Opt(stringResource(R.string.working)))
                else if (fl.isEmpty()) add(Opt(err.ifEmpty { stringResource(R.string.bkr_no_copies) }))
                else fl.forEach { f ->
                    val isSync = f.name == "AppMenu_sync.zip"
                    val core = f.name.removePrefix("AppMenu_").removeSuffix(".zip")
                    val auto = core.startsWith("auto_")
                    // устройство из имени файла: ручная копия «дата_время_устройство», автокопия «auto_устройство_дата»
                    val dev = if (isSync) "" else if (auto) core.removePrefix("auto_").substringBeforeLast('_') else core.split('_', limit = 3).getOrElse(2) { "" }
                    val title = (if (f.modified > 0) fmt.format(Date(f.modified)) else core.take(16)) + " · " + (f.size / 1024).coerceAtLeast(1) + " KB" + (if (auto) " · " + ctx.getString(R.string.bkr_flag_auto) else "")
                    add(Opt(if (isSync) ctx.getString(R.string.bkr_sync_file) + " · " + title else title, shortText(dev, 12), onClick = {
                        if (t != null) op(t, ctx.getString(R.string.bkr_op_restore)) {
                            if (BkEngine.restore(ctx, t, f.name)) ctx.getString(R.string.backup_restored_undo) else ctx.getString(R.string.failed) + ": " + ctx.getString(R.string.backup_err_notcopy)
                        }
                    }))
                }
                add(Opt(stringResource(R.string.back_plain), onClick = { screen = "card:$id" }))
            }
            ListDialog(stringResource(R.string.bkr_restore), opts, { screen = "card:$id" }, hint = stringResource(R.string.bkr_restore_hint))
        }
        screen.startsWith("card:") -> {
            val id = screen.removePrefix("card:")
            val t = BkTargets.find(ctx, id)
            if (t == null) { screen = "list"; return }
            val net = t.kind == "smb" || t.kind == "dav"
            var info by remember(id, rev) { mutableStateOf("") }
            LaunchedEffect(id, rev, t.sync) {
                if (t.sync) info = withContext(Dispatchers.IO) {
                    runCatching { BkEngine.remoteInfo(ctx, t)?.let { ctx.getString(R.string.bkr_remote_info, it.device, fmt.format(Date(it.time))) } ?: ctx.getString(R.string.bkr_remote_none) }.getOrDefault("")
                }
            }
            val last = BkTargets.lastResult(ctx, id)
            val lastTxt = last?.let { fmt.format(Date(it.first)) + ": " + it.second }.orEmpty()
            val opts = buildList {
                if (lastTxt.isNotEmpty()) add(Opt(lastTxt))
                add(Opt(stringResource(R.string.bkr_f_name) + ": " + shortText(t.name, 26), onClick = { edit = id to "name" }))
                if (t.kind == "saf") add(Opt(stringResource(R.string.bkr_f_path), stringResource(R.string.bkr_saf_set), onClick = { runCatching { tree.launch(null) } }))
                else add(Opt(stringResource(if (net) R.string.bkr_f_addr else R.string.bkr_f_path) + ": " + shortText(t.url.substringAfter("://"), 26), onClick = { edit = id to "url" }))
                if (t.kind == "smb") add(Opt(stringResource(R.string.bkr_find), onClick = { screen = "hosts:$id" }))
                if (net) {
                    add(Opt(stringResource(R.string.bkr_f_user) + ": " + shortText(t.user.ifEmpty { "-" }, 22), onClick = { edit = id to "user" }))
                    add(Opt(stringResource(R.string.bkr_f_pass), if (BkVault.has(ctx, id)) "••••••" else stringResource(R.string.bkr_pass_none), onClick = { edit = id to "pass" }))
                }
                add(Opt(stringResource(R.string.bkr_test), onClick = {
                    op(t, ctx.getString(R.string.bkr_op_test)) { BkEngine.test(ctx, t)?.let { ctx.getString(R.string.bkr_r_fail, it) } ?: ctx.getString(R.string.bkr_r_ok) }
                }))
                add(Opt(stringResource(R.string.bkr_save_now), onClick = {
                    op(t, ctx.getString(R.string.bkr_op_save)) { BkEngine.saveNow(ctx, t); ctx.getString(R.string.bkr_r_saved) }
                }))
                add(Opt(stringResource(R.string.bkr_restore), onClick = { screen = "restore:$id" }))
                add(Opt(stringResource(R.string.bkr_auto), if (t.auto) "✓" else "", onClick = {
                    t.auto = !t.auto; BkTargets.update(ctx, t)
                    // расписание выключено: при включении автокопии сразу ставится «раз в сутки», иначе галочка ничего бы не делала
                    if (t.auto && BkTargets.schedule(ctx) == 0) BkTargets.setSchedule(ctx, 1)
                    rev++
                }))
                add(Opt(stringResource(R.string.bkr_sync), if (t.sync) "✓" else "", onClick = { t.sync = !t.sync; BkTargets.update(ctx, t); rev++ }))
                if (t.sync) {
                    if (info.isNotEmpty()) add(Opt(info))
                    add(Opt(stringResource(R.string.bkr_push), onClick = { confirm = "push" to id }))
                    add(Opt(stringResource(R.string.bkr_pull), onClick = { confirm = "pull" to id }))
                }
                add(Opt(stringResource(R.string.bkr_del), danger = true, onClick = { confirm = "del" to id }))
                add(Opt(stringResource(R.string.back_plain), onClick = { screen = "list" }))
            }
            ListDialog(t.name.ifBlank { BkTargets.kindName(ctx, t.kind) }, opts, { screen = "list" }, hint = stringResource(R.string.bkr_card_hint))
        }
    }
}
