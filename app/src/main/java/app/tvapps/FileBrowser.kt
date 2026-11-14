@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.tvapps

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Icon
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.io.File

/** Права на чтение файлов: выдаются по ADB (так же, как другие права), без системного окна. */
object Storage {
    fun hasAccess(ctx: Context): Boolean =
        (android.os.Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) ||
            ctx.checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED

    suspend fun grant(ctx: Context) {
        if (hasAccess(ctx) || !Adb.isConnected) return
        runCatching { Adb.shell("pm grant ${ctx.packageName} android.permission.READ_EXTERNAL_STORAGE") }
        runCatching { Adb.shell("pm grant ${ctx.packageName} android.permission.WRITE_EXTERNAL_STORAGE") }
        runCatching { Adb.shell("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow") }
    }

    /** Начальные места: внутренняя память и подключённые накопители. */
    fun roots(): List<File> {
        val out = ArrayList<File>()
        out.add(Environment.getExternalStorageDirectory())
        File("/storage").listFiles()?.filter { it.isDirectory && it.name != "self" && it.name != "emulated" && it.canRead() }?.forEach { out.add(it) }
        return out
    }
}

private val archiveExt = setOf("apk", "apks", "apkm", "xapk", "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz")

/**
 * Собственный файловый менеджер для тех приставок, где нет системного окна выбора файлов.
 * Источники: память устройства, флешки и папки через ADB (то, что система приложению не показывает) и сетевые папки (SMB).
 */
@Composable
fun FileBrowser(onApks: (List<ApkFile>) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf("local") }
    var smb by remember { mutableStateOf<SmbFs?>(null) }
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var roots by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(Storage.hasAccess(ctx)) }
    var hosts by remember { mutableStateOf<List<String>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var passShow by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }

    val fs: Fs? = when (kind) { "local" -> LocalFs; "adb" -> AdbFs; else -> smb }

    LaunchedEffect(Unit) {
        Storage.grant(ctx)
        granted = Storage.hasAccess(ctx)
    }
    // при смене источника выбираем начальное место
    LaunchedEffect(kind, smb) {
        error = ""
        entries = emptyList()
        val f = fs
        if (f == null) { roots = emptyList(); path = ""; return@LaunchedEffect }
        roots = try { f.roots() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { emptyList() }
        path = when (kind) {
            "local" -> File(Environment.getExternalStorageDirectory(), "Download").takeIf { it.isDirectory }?.absolutePath ?: Environment.getExternalStorageDirectory().absolutePath
            "adb" -> roots.firstOrNull()?.second ?: "/sdcard"
            else -> smb?.root.orEmpty()
        }
    }
    LaunchedEffect(kind, smb, path, granted) {
        val f = fs ?: return@LaunchedEffect
        if (path.isEmpty()) return@LaunchedEffect
        busy = true
        error = ""
        try {
            val all = f.list(path)
            entries = all.filter { it.isDir || it.name.substringAfterLast('.', "").lowercase() in archiveExt }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // путь уже сменился: новый запуск покажет свой результат
        } catch (e: Throwable) {
            entries = emptyList(); error = e.message ?: e.javaClass.simpleName
        }
        busy = false
        runCatching { fr.requestFocus() }
    }

    fun take(folder: String?, file: FsEntry?) {
        val f = fs ?: return
        scope.launch {
            busy = true
            error = ""
            val list = runCatching { if (file != null) f.apksFromFile(ctx, file) else f.apksIn(ctx, folder!!) }
                .onFailure { error = it.message ?: it.javaClass.simpleName }.getOrNull()
            busy = false
            if (list != null) onApks(list)
        }
    }

    val fields = OutlinedTextFieldDefaults.colors(
        focusedTextColor = t.text, unfocusedTextColor = t.text, focusedBorderColor = t.accent, unfocusedBorderColor = t.border,
        cursorColor = t.accent, focusedLabelColor = t.accent, unfocusedLabelColor = t.sub,
    )

    HDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().padding(24.dp).clip(RoundedCornerShape(20.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.fb_title), color = t.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(stringResource(R.string.fb_src_local), kind == "local") { kind = "local" }
                Chip(stringResource(R.string.fb_src_adb), kind == "adb") { kind = "adb" }
                Chip(stringResource(R.string.fb_src_net), kind == "smb") { kind = "smb" }
                Chip(stringResource(R.string.cancel), false) { onClose() }
            }
            if (kind == "adb" && !Adb.isConnected) Text(stringResource(R.string.fb_need_adb), color = Color(0xFFF59E0B), fontSize = 13.sp)
            if (kind == "local" && !granted) Text(stringResource(R.string.fb_no_access), color = Color(0xFFF59E0B), fontSize = 13.sp)

            if (kind == "smb" && smb == null) {
                // экран «Сеть»: поиск устройств с общими папками или ввод адреса
                Text(stringResource(R.string.fb_net_hint), color = t.hint, fontSize = 12.sp)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(stringResource(if (scanning) R.string.fb_scanning else R.string.fb_scan), true, Modifier.focusRequester(fr)) {
                        if (!scanning) scope.launch { scanning = true; hosts = runCatching { Lan.scanSmb() }.getOrDefault(emptyList()); scanning = false }
                    }
                    hosts.forEach { h -> Chip(h, host == h) { host = h } }
                }
                if (hosts.isEmpty() && !scanning) Text(stringResource(R.string.fb_hosts_none), color = t.sub, fontSize = 13.sp)
                OutlinedTextField(host, { host = it.trim() }, label = { Text(stringResource(R.string.fb_host)) }, singleLine = true, colors = fields, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.fb_user)) }, singleLine = true, colors = fields, modifier = Modifier.weight(1f))
                    OutlinedTextField(pass, { pass = it }, label = { Text(stringResource(R.string.fb_pass)) }, singleLine = true, colors = fields,
                        visualTransformation = if (passShow) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                        // «глазик» справа в полосе поля: по умолчанию пароль скрыт; цвет акцента, компактно
                        trailingIcon = {
                            Icon(
                                if (passShow) HIcons.eyeOff else HIcons.eye, stringResource(R.string.pass_show), tint = t.accent,
                                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).clickable { passShow = !passShow }.padding(5.dp),
                            )
                        },
                        modifier = Modifier.weight(1f))
                }
                Chip(stringResource(R.string.fb_connect), true) { if (host.isNotBlank()) smb = SmbFs(host, user, pass) }
            } else {
                Text(path, color = t.sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(stringResource(R.string.fb_pick_folder), true, Modifier.focusRequester(fr)) { if (!busy && path.isNotEmpty()) take(path, null) }
                    roots.forEach { (label, p) -> Chip(label, false) { path = p } }
                    if (kind == "smb") Chip(stringResource(R.string.fb_other_host), false) { smb = null }
                }
                if (busy) Text(stringResource(R.string.working), color = t.accent, fontSize = 13.sp)
                if (error.isNotEmpty()) Text(error, color = Color(0xFFF87171), fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                BarLazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val parent = fs?.parent(path)
                    if (parent != null) item { FileRow("↑  " + stringResource(R.string.fb_up), "", true) { path = parent } }
                    items(entries, key = { it.path }) { e ->
                        if (e.isDir) FileRow("▸  " + e.name, "", true) { path = e.path }
                        else FileRow(e.name, (e.size / 1024 / 1024).toString() + " MB", false) { if (!busy) take(null, e) }
                    }
                    if (entries.isEmpty() && !busy && error.isEmpty()) item { Text(stringResource(R.string.fb_empty), color = t.sub, fontSize = 14.sp, modifier = Modifier.padding(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun FileRow(name: String, info: String, folder: Boolean, onClick: () -> Unit) {
    val t = LocalTh.current
    var focused by remember { mutableStateOf(false) }
    val (hm, hover) = rememberHover()
    Row(
        Modifier.fillMaxWidth()
            .then(hm)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused || hover) t.tileFocus else t.tile)
            .border(if (focused) 3.dp else 0.dp, if (focused) t.accent else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(name, color = if (folder) t.accent else t.text, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (info.isNotEmpty()) Text(info, color = t.hint, fontSize = 12.sp)
    }
}

/** QR-код (тёмные модули на белом поле с рамкой, чтобы читался и в тёмной теме). */
@Composable
fun QrView(text: String, size: androidx.compose.ui.unit.Dp) {
    val m = remember(text) { Qr.encode(text) }
    if (m == null) return
    Canvas(Modifier.size(size)) {
        drawRect(Color.White)
        val n = m.size
        val quiet = 2
        val cell = this.size.minDimension / (n + quiet * 2)
        for (y in 0 until n) for (x in 0 until n) if (m[y][x]) drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.6f, cell + 0.6f))
    }
}

/** Приём файлов: через общую сеть (страница в браузере) или напрямую по Wi‑Fi Direct без роутера. */
@Composable
fun UploadDialog(onApks: (List<ApkFile>) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val addr = remember { Lan.myAddress()?.first }
    val fr = remember { FocusRequester() }
    val canDirect = remember { DirectGroup.supported(ctx) }
    val modePrefs = remember { ctx.getSharedPreferences("tvapps_recv", Context.MODE_PRIVATE) }
    var direct by remember { mutableStateOf(canDirect && modePrefs.getString("mode", "net") == "direct") }
    var dInfo by remember { mutableStateOf<DirectGroup.Info?>(null) }
    var dErr by remember { mutableStateOf("") }
    var dBusy by remember { mutableStateOf(false) }
    var permTick by remember { mutableStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permTick++ }
    androidx.compose.runtime.DisposableEffect(Unit) {
        runCatching { UploadServer.start(ctx) }.onFailure { error = it.message.orEmpty() }
        onDispose { UploadServer.stop(); DirectGroup.stop() }
    }
    androidx.compose.runtime.DisposableEffect(direct) { onDispose { DirectGroup.stop() } }
    LaunchedEffect(direct, permTick) {
        if (!direct) { dInfo = null; dErr = ""; dBusy = false; return@LaunchedEffect }
        dInfo = null; dErr = ""
        if (!DirectGroup.hasPermissions(ctx)) {
            if (permTick == 0) { dBusy = true; permLauncher.launch(DirectGroup.permissions()) } else { dBusy = false; dErr = "noperm" }
            return@LaunchedEffect
        }
        dBusy = true
        DirectGroup.start(ctx) { i, e -> dBusy = false; dInfo = i; dErr = e.orEmpty() }
    }
    LaunchedEffect(Unit) {
        runCatching { fr.requestFocus() }
        while (true) { files = UploadServer.received(); kotlinx.coroutines.delay(1000) }
    }
    HDialog(onDismissRequest = onClose) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.wifi_title), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (canDirect) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(stringResource(R.string.wifi_mode_net), !direct) { direct = false; modePrefs.edit().putString("mode", "net").apply() }
                Chip(stringResource(R.string.wifi_mode_direct), direct) { direct = true; modePrefs.edit().putString("mode", "direct").apply() }
            }
            if (!direct) {
                Text(stringResource(R.string.wifi_hint), color = t.sub, fontSize = 13.sp)
                if (addr != null) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    QrView("http://$addr:${UploadServer.PORT}", 130.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("http://$addr:${UploadServer.PORT}", color = t.accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.wifi_discover, Announcer.deviceName(ctx)), color = t.sub, fontSize = 12.sp)
                    }
                }
                else Text(stringResource(R.string.wifi_no_net), color = Color(0xFFF59E0B), fontSize = 14.sp)
            } else {
                Text(stringResource(R.string.wifi_direct_hint), color = t.sub, fontSize = 13.sp)
                val di = dInfo
                if (di != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        QrView(DirectGroup.wifiQr(di), 150.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.wifi_direct_ssid, di.ssid), color = t.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.wifi_direct_pass, di.pass), color = t.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("http://${di.ip}:${UploadServer.PORT}", color = t.accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else if (dErr.isNotEmpty()) {
                    val msg = when {
                        dErr == "noperm" -> stringResource(R.string.wifi_direct_err_perm)
                        dErr == "busy" || dErr == "p2p-unsupported" -> stringResource(R.string.wifi_direct_err_busy)
                        dErr == "timeout" -> stringResource(R.string.wifi_direct_err_timeout)
                        else -> stringResource(R.string.wifi_direct_err_other, dErr)
                    }
                    Text(msg, color = Color(0xFFF59E0B), fontSize = 14.sp)
                } else if (dBusy) Text(stringResource(R.string.wifi_direct_starting), color = t.accent, fontSize = 14.sp)
            }
            if (error.isNotEmpty()) Text(error, color = Color(0xFFF87171), fontSize = 12.sp)
            Text(stringResource(R.string.wifi_received, files.size) + if (files.isNotEmpty()) ": " + files.joinToString(", ") { it.name }.take(160) else "", color = t.text, fontSize = 14.sp)
            if (busy) Text(stringResource(R.string.working), color = t.accent, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(stringResource(R.string.wifi_check, files.size), true, Modifier.focusRequester(fr)) {
                    if (files.isEmpty() || busy) return@Chip
                    scope.launch {
                        busy = true
                        val list = files.flatMap { f -> runCatching { LocalFs.apksFromFile(ctx, FsEntry(f.name, f.absolutePath, false, f.length())) }.getOrDefault(emptyList()) }
                        busy = false
                        onApks(list)
                    }
                }
                if (direct && (dInfo != null || dErr.isNotEmpty())) Chip(stringResource(R.string.wifi_direct_stop), false) { direct = false; modePrefs.edit().putString("mode", "net").apply() }
                Chip(stringResource(R.string.cancel), false) { onClose() }
                Chip(stringResource(R.string.wifi_clear), false) { UploadServer.dir?.listFiles()?.forEach { it.delete() }; files = emptyList() }
            }
        }
    }
}
