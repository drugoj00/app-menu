@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.tvapps

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap

val Entry.isFolder: Boolean get() = pkg.startsWith("folder:")
val Entry.folderId: String get() = pkg.removePrefix("folder:")

/** Действия, которые меню значка и меню папки просят у главного экрана. */
interface HostOps {
    fun openFolder(id: String)
    fun renameFolder(id: String)
    fun addToFolder(id: String)
    fun openVersions(e: Entry)
}

val LocalHost = compositionLocalOf<HostOps?> { null }

/** Плитка-папка: значок из четырёх первых приложений внутри. */
fun folderEntry(ctx: Context, f: Folder, members: List<Entry>): Entry {
    val s = 128
    val bm = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bm)
    val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    p.color = 0xFF55657F.toInt()
    c.drawRoundRect(RectF(0f, 0f, s.toFloat(), s.toFloat()), 30f, 30f, p)
    val cell = 46f
    val gap = 12f
    members.take(4).forEachIndexed { i, m ->
        val x = gap + (i % 2) * (cell + gap)
        val y = gap + (i / 2) * (cell + gap)
        val src = runCatching { m.bmp?.asAndroidBitmap() ?: m.icon.toBitmap(64, 64) }.getOrNull() ?: return@forEachIndexed
        c.drawBitmap(src, null, RectF(x, y, x + cell, y + cell), p)
    }
    return Entry(
        "folder:${f.id}", f.name, BitmapDrawable(ctx.resources, bm), false,
        Intent(), 0L, 0L, bm.asImageBitmap(),
    )
}

/** Главный список: приложения из папок убираются, вместо них добавляются плитки-папки. */
fun withFolders(ctx: Context, all: List<Entry>): List<Entry> {
    if (Cfg.folders.isEmpty()) return all
    val inFolder = Cfg.folders.flatMap { it.pkgs }.toSet()
    val byPkg = all.associateBy { it.pkg }
    val folders = Cfg.folders.filter { f -> f.pkgs.any { it in byPkg } }.map { f ->
        folderEntry(ctx, f, f.pkgs.mapNotNull { byPkg[it] }.filter { Cfg.showHidden || !Cfg.isHidden(it.pkg) })
    }
    return folders + all.filter { it.pkg !in inFolder }
}

/** Содержимое открытой папки. Значки внутри открываются, имеют то же меню действий и могут быть убраны из папки. */
@Composable
fun FolderDialog(id: String, apps: List<Entry>, menuFor: Entry?, setMenu: (Entry?) -> Unit, openApp: (Entry) -> Unit, onClose: () -> Unit) {
    val t = LocalTh.current
    val host = LocalHost.current
    val f = Cfg.folderById(id)
    if (f == null) { LaunchedEffect(Unit) { onClose() }; return }
    val byPkg = apps.associateBy { it.pkg }
    val members = f.pkgs.mapNotNull { byPkg[it] }.filter { Cfg.showHidden || !Cfg.isHidden(it.pkg) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    // окно квадратное и по размеру содержимого: чем больше значков, тем крупнее, но не выше 80% экрана
    val scr = screenDp()
    // сетка в папке всегда 5×5 (25 значков без прокрутки); больше — листается
    val cols = 5
    val side = 660.dp.coerceAtMost((scr.second * 0.94f).dp).coerceAtMost((scr.first * 0.96f).dp)
    HDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.size(side).clip(RoundedCornerShape(24.dp)).background(t.bg.copy(alpha = 0.9f)).border(1.dp, t.border, RoundedCornerShape(24.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(f.name, fontSize = 20.sp, color = t.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(stringResource(R.string.folder_add), false, Modifier.focusRequester(first)) { host?.addToFolder(id) }
                Chip(stringResource(R.string.folder_rename), false) { host?.renameFolder(id) }
                Chip(stringResource(R.string.folder_delete), false) { Cfg.deleteFolder(id); onClose() }
                Chip(stringResource(R.string.close), true) { onClose() }
            }
            if (members.isEmpty()) Text(stringResource(R.string.folder_empty), color = t.sub, fontSize = 13.sp)
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols), modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                gridItems(members, key = { it.pkg }) { e ->
                    AppTile(
                        e, false, menuFor?.pkg == e.pkg, null, false, false, false,
                        { openApp(e) }, { setMenu(e) }, {}, {}, { false }, {}, {}, {}, { setMenu(null); Conn.refresh() },
                        compact = true,
                    )
                }
                // пустые места сетки 5×5 видны слабыми подложками
                val total = ((members.size + cols - 1) / cols).coerceAtLeast(5) * cols
                items(count = total - members.size, key = { "slot$it" }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1.15f).clip(RoundedCornerShape(14.dp)).background(t.tile.copy(alpha = 0.55f)))
                }
            }
        }
    }
}

/** Меню плитки-папки (долгое касание, удержание ОК, правая кнопка мыши). */
@Composable
fun FolderMenu(id: String, onClose: () -> Unit) {
    val t = LocalTh.current
    val host = LocalHost.current
    val f = Cfg.folderById(id) ?: run { LaunchedEffect(Unit) { onClose() }; return }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    HDialog(onDismissRequest = onClose) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(f.name, color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Chip(stringResource(R.string.folder_open), true, Modifier.focusRequester(first)) { onClose(); host?.openFolder(id) }
            Chip(stringResource(R.string.folder_add), false) { onClose(); host?.addToFolder(id) }
            Chip(stringResource(R.string.folder_rename), false) { onClose(); host?.renameFolder(id) }
            Chip(stringResource(R.string.folder_delete), false) { Cfg.deleteFolder(id); onClose() }
            Chip(stringResource(R.string.cancel), false) { onClose() }
        }
    }
}

@Composable
fun RenameFolderDialog(id: String, onClose: () -> Unit) {
    val t = LocalTh.current
    val f = Cfg.folderById(id) ?: run { LaunchedEffect(Unit) { onClose() }; return }
    var v by remember { mutableStateOf(f.name) }
    // фокус на «ОК»: экранная клавиатура открывается, только когда сам нажмёшь на поле
    val okReq = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { okReq.requestFocus() } }
    HDialog(onDismissRequest = onClose) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.folder_name), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                v, { v = it.take(40) }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = t.text, unfocusedTextColor = t.text,
                    focusedBorderColor = t.accent, unfocusedBorderColor = t.border, cursorColor = t.accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(stringResource(R.string.cancel), false) { onClose() }
                Chip(stringResource(R.string.folder_ok), true, Modifier.focusRequester(okReq)) { Cfg.renameFolder(id, v); onClose() }
            }
        }
    }
}

/** Добавление значков в папку прямо из неё: отметь приложения и нажми «Добавить». */
@Composable
fun AddToFolderDialog(id: String, apps: List<Entry>, onClose: () -> Unit) {
    val t = LocalTh.current
    val f = Cfg.folderById(id) ?: run { LaunchedEffect(Unit) { onClose() }; return }
    val candidates = apps.filter { it.pkg !in f.pkgs && (Cfg.showHidden || !Cfg.isHidden(it.pkg)) }.sortedBy { it.label.lowercase() }
    var sel by remember { mutableStateOf<Set<String>>(emptySet()) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    HDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.7f).fillMaxHeight(0.88f).clip(RoundedCornerShape(24.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(24.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.folder_add_title, f.name), color = t.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(stringResource(R.string.cancel), false, Modifier.focusRequester(first)) { onClose() }
                Chip(stringResource(R.string.folder_add_do, sel.size), true) {
                    if (sel.isNotEmpty()) Cfg.addToFolder(id, candidates.filter { it.pkg in sel }.map { it.pkg })
                    onClose()
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(candidates, key = { it.pkg }) { e ->
                    val on = e.pkg in sel
                    var focused by remember { mutableStateOf(false) }
                    val (hm, hover) = rememberHover()
                    val bmp = remember(e.pkg, e.bmp) { e.bmp ?: e.icon.toBitmap(64, 64).asImageBitmap() }
                    Row(
                        Modifier.fillMaxWidth().then(hm).onFocusChanged { focused = it.isFocused }
                            .clip(RoundedCornerShape(10.dp)).background(if (focused || hover) t.tileFocus else t.tile)
                            .border(if (focused) 3.dp else 0.dp, if (focused) t.accent else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { sel = if (on) sel - e.pkg else sel + e.pkg }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        androidx.compose.foundation.Image(bmp, null, Modifier.size(36.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.label, color = t.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Cfg.folderOf(e.pkg)?.let { Text(stringResource(R.string.folder_now_in, it.name), color = t.hint, fontSize = 11.sp) }
                        }
                        Box(
                            Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(if (on) t.accent else Color.Transparent).border(2.dp, if (on) t.accent else t.sub, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Icon(Icons.Outlined.Check, null, tint = if (t.dark) Color.Black else Color.White, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
}
