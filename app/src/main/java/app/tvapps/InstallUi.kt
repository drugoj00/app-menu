@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.tvapps

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Окно приёма файлов по Wi‑Fi как отдельное окно: открывается из контекстного меню (долгое ОК) и из настроек; за ним идёт окно установки. */
object WifiReceive {
    var open by mutableStateOf(false)
}

/** Узел окон приёма и установки: ставится в корне «Меню» и «Дома», рисует окна поверх любого экрана. */
@Composable
fun ReceiveHost() {
    var files by remember { mutableStateOf<List<ApkFile>?>(null) }
    if (WifiReceive.open) UploadDialog(onApks = { WifiReceive.open = false; files = it }, onClose = { WifiReceive.open = false })
    files?.let { InstallDialog(it) { files = null } }
}

private fun timeText(ms: Long): String = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(ms))

/** Строка журнала, на которую можно встать пультом: стрелки вверх и вниз листают список, фокус виден рамкой. */
@Composable
private fun FocusItem(content: @Composable () -> Unit) {
    val t = LocalTh.current
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(8.dp))
            .border(if (focused) 2.dp else 0.dp, if (focused) t.accent else Color.Transparent, RoundedCornerShape(8.dp))
            .focusable().padding(horizontal = 4.dp, vertical = 2.dp),
    ) { content() }
}

/** PageUp и PageDown листают список на экран. */
private fun Modifier.pageKeys(sc: androidx.compose.foundation.ScrollState, scope: kotlinx.coroutines.CoroutineScope): Modifier = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (e.key) {
        Key.PageDown -> { scope.launch { sc.animateScrollBy(320f) }; true }
        Key.PageUp -> { scope.launch { sc.animateScrollBy(-320f) }; true }
        else -> false
    }
}

@Composable
private fun ResRow(r: InstallRes) {
    val t = LocalTh.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (r.ok) "✓" else "✗", color = if (r.ok) t.accent else Color(0xFFDC2626), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f)) {
            Text(r.name, color = t.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (r.note.isNotBlank()) Text(r.note, color = if (r.ok) t.hint else Color(0xFFDC2626), fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Список найденных APK с отметками. Установка по ADB идёт по одному файлу с прогрессом; итог по каждому пакету остаётся в окне и в журнале. */
@Composable
fun InstallDialog(files: List<ApkFile>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf(files.map { it.name }.toSet()) }
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<InstallRes>?>(null) }
    var cur by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0 to 0) }
    var logOpen by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(results == null, busy) { runCatching { fr.requestFocus() } }
    if (logOpen) InstallLogDialog { logOpen = false }
    HDialog(onDismissRequest = { if (!busy) onClose() }) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(18.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val res = results
            if (res == null) {
                Text(stringResource(R.string.install_title, files.size), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (files.isEmpty()) Text(stringResource(R.string.install_none), color = t.sub, fontSize = 14.sp)
                val instScroll = rememberScrollState()
                Column(Modifier.heightIn(max = 340.dp).verticalScrollbar(instScroll).verticalScroll(instScroll).padding(end = ScrollbarMath.RESERVE_DP.dp)) {
                    files.forEach { f ->
                        val on = f.name in chosen
                        var focused by remember { mutableStateOf(false) }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                .onFocusChanged { focused = it.isFocused }
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (focused) t.tileFocus else Color.Transparent)
                                .border(if (focused) 2.dp else 0.dp, if (focused) t.accent else Color.Transparent, RoundedCornerShape(10.dp))
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
                Text(stringResource(if (Adb.isConnected) R.string.install_silent else R.string.install_dialogs), color = t.hint, fontSize = 12.sp)
            } else {
                val ok = res.count { it.ok }
                val bad = res.size - ok
                Text(
                    if (busy) stringResource(R.string.install_progress, progress.first + 1, progress.second, cur) else stringResource(R.string.install_summary, ok, bad),
                    color = if (busy) t.accent else t.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                val sc = rememberScrollState()
                Column(Modifier.heightIn(max = 340.dp).pageKeys(sc, scope).verticalScrollbar(sc).verticalScroll(sc).padding(end = ScrollbarMath.RESERVE_DP.dp)) { res.forEach { FocusItem { ResRow(it) } } }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (res == null) {
                    // порядок по просьбе пользователя: сначала «Установить», потом «Отмена»
                    Chip(stringResource(R.string.install_go, chosen.size), true, Modifier.focusRequester(fr)) {
                        val picked = files.filter { it.name in chosen }
                        if (picked.isEmpty() || busy) return@Chip
                        if (Adb.isConnected) {
                            busy = true
                            results = emptyList()
                            scope.launch {
                                val all = Batch.installDetailed(ctx, picked) { done, total, name, list ->
                                    progress = done to total; cur = name; results = list
                                }
                                results = all
                                busy = false
                                AppsMem.stamp = 0L; Cfg.bumpList()
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
                                        else runCatching { SessionInstaller.install(ctx, parts, { Bundles.deleteObb(it.obb) }, it.name) }
                                            .onFailure { e ->
                                                Bundles.deleteObb(it.obb)
                                                InstallLog.add(ctx, InstallRes(it.name, it.pkg.orEmpty(), false, e.message ?: e.javaClass.simpleName))
                                                Toast.makeText(ctx, ctx.getString(R.string.bundle_failed) + ": " + (e.message ?: ""), Toast.LENGTH_LONG).show()
                                            }
                                    }
                                    else Pending.installs.add(Batch.shareUri(ctx, Batch.materialize(it)))
                                }
                                busy = false
                                Pending.next(ctx)
                                onClose()
                            }
                        }
                    }
                    Chip(stringResource(R.string.cancel), false) { if (!busy) onClose() }
                    Chip(stringResource(R.string.install_log_btn), false) { logOpen = true }
                } else {
                    Chip(stringResource(R.string.close), !busy, Modifier.focusRequester(fr)) { if (!busy) onClose() }
                    Chip(stringResource(R.string.install_log_btn), false) { logOpen = true }
                }
            }
        }
    }
}

/** Журнал установок: что и когда поставилось, с причиной отказа. */
@Composable
fun InstallLogDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    var rows by remember { mutableStateOf(InstallLog.read(ctx)) }
    val scope = rememberCoroutineScope()
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    HDialog(onDismissRequest = onClose) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(18.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.install_log_title), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (rows.isEmpty()) Text(stringResource(R.string.install_log_empty), color = t.sub, fontSize = 14.sp)
            val sc = rememberScrollState()
            Column(Modifier.heightIn(max = 360.dp).pageKeys(sc, scope).verticalScrollbar(sc).verticalScroll(sc).padding(end = ScrollbarMath.RESERVE_DP.dp)) {
                rows.forEach { r ->
                    FocusItem {
                        Text(timeText(r.time), color = t.hint, fontSize = 10.sp)
                        ResRow(r)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.close), true, Modifier.focusRequester(fr)) { onClose() }
                if (rows.isNotEmpty()) Chip(stringResource(R.string.wifi_clear), false) { InstallLog.clear(ctx); rows = emptyList() }
            }
        }
    }
}
