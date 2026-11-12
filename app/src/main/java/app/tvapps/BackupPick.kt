@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.tvapps

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Свой выбор копии для восстановления (на части приставок системное окно выбора файла пустое или не открывается пультом):
 * копии находятся сами: папка программы (автокопии по дням и копия перед восстановлением), корень памяти, Download, Documents, Backup, AppMenuBackups.
 * onlyApp: только папка программы. Системное окно остаётся запасным (onSystem).
 */
@Composable
fun BackupPickDialog(onlyApp: Boolean, onSystem: (() -> Unit)?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<File>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!onlyApp) withContext(Dispatchers.IO) { Storage.grant(ctx) }
        files = withContext(Dispatchers.IO) { Backup.findCopies(ctx, onlyApp) }
    }
    val list = files
    LaunchedEffect(list?.size) { runCatching { fr.requestFocus() } }
    val fmt = remember { java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()) }

    fun restore(f: File) {
        if (busy) return
        scope.launch {
            busy = true
            val ok = runCatching { f.inputStream().use { Backup.restore(ctx, it) } }.getOrElse { Backup.lastFail = "open"; false }
            busy = false
            val why = when (Backup.lastFail) { "open" -> R.string.backup_err_open; "notcopy" -> R.string.backup_err_notcopy; "broken" -> R.string.backup_err_broken; else -> R.string.backup_err_other }
            Toast.makeText(ctx, if (ok) ctx.getString(R.string.backup_restored_undo) else ctx.getString(R.string.failed) + ": " + ctx.getString(why), Toast.LENGTH_LONG).show()
            if (ok) onClose()
        }
    }

    HDialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().padding(24.dp).clip(RoundedCornerShape(20.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.bkp_pick_title), color = t.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.bkp_pick_hint), color = t.hint, fontSize = 12.sp)
            if (list == null) Text(stringResource(R.string.working), color = t.accent, fontSize = 14.sp)
            else if (list.isEmpty()) Text(
                stringResource(if (onlyApp) R.string.bkp_pick_none_app else if (Storage.hasAccess(ctx)) R.string.bkp_pick_none else R.string.imgp_no_access),
                color = t.sub, fontSize = 14.sp,
            )
            BarLazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(list.orEmpty(), key = { _, f -> f.absolutePath }) { idx, f ->
                    var focused by remember { mutableStateOf(false) }
                    val (hm, hover) = rememberHover()
                    Column(
                        Modifier.fillMaxWidth()
                            .then(if (idx == 0) Modifier.focusRequester(fr) else Modifier)
                            .then(hm)
                            .onFocusChanged { focused = it.isFocused }
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (focused || hover) t.tileFocus else t.tile)
                            .border(if (focused) 3.dp else 0.dp, if (focused) t.accent else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable(enabled = !busy) { restore(f) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(f.name, color = t.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(fmt.format(java.util.Date(f.lastModified())) + " · " + (f.length() / 1024).coerceAtLeast(1) + " KB · " + f.parent.orEmpty(), color = t.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onSystem != null) Chip(stringResource(R.string.imgp_system), false, if (list != null && list.isEmpty()) Modifier.focusRequester(fr) else Modifier) { if (!busy) onSystem() }
                Chip(stringResource(R.string.close), false) { if (!busy) onClose() }
            }
        }
    }
}
