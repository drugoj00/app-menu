@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.tvapps

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Свой выбор картинки/видео для фона «Дома» и меню (замена системного окна, которое на ряде приставок с Android 7.1.2 показывало «ничего нет»):
 * файлы берутся прямо из папок wallpaper, DCIM, Pictures, Download (вложенные — до двух уровней). Листать можно стрелками «влево/вправо», ОК выбирает.
 */
object FilePick {
    private val IMG = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    private val VID = setOf("mp4", "mkv", "webm", "mov", "3gp", "m4v", "avi", "gif")

    private fun roots(): List<File> {
        val base = Environment.getExternalStorageDirectory()
        val out = ArrayList<File>()
        for (n in listOf("wallpaper", "Wallpaper", "wallpapers", "Wallpapers", "DCIM", "Pictures", "Movies", "Download")) out.add(File(base, n))
        // подключённые накопители: та же папка wallpaper
        File("/storage").listFiles()?.filter { it.isDirectory && it.name != "self" && it.name != "emulated" && it.canRead() }?.forEach { out.add(File(it, "wallpaper")); out.add(File(it, "Wallpapers")) }
        return out
    }

    /** Файлы подходящего вида; папки wallpaper идут первыми. */
    fun list(kind: String): List<File> {
        val ext = if (kind == "image") IMG else VID
        val out = LinkedHashMap<String, File>()
        fun walk(d: File, depth: Int) {
            val items = d.listFiles() ?: return
            for (f in items) {
                if (out.size >= 400) return
                if (f.isFile && f.extension.lowercase() in ext) out[f.absolutePath] = f
                else if (f.isDirectory && depth < 2 && !f.name.startsWith(".")) walk(f, depth + 1)
            }
        }
        roots().filter { it.isDirectory }.forEach { walk(it, 0) }
        return out.values.toList()
    }
}

@Composable
fun FilePickDialog(kind: String, onPick: (Uri) -> Unit, onSystem: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    var files by remember { mutableStateOf<List<File>?>(null) }
    var idx by remember { mutableIntStateOf(0) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { Storage.grant(ctx) }
        files = withContext(Dispatchers.IO) { FilePick.list(kind) }
    }
    val list = files
    LaunchedEffect(list?.size) { runCatching { fr.requestFocus() } }
    fun step(d: Int) { val n = list?.size ?: 0; if (n > 0) idx = (idx + d + n) % n }
    HDialog(onDismissRequest = onClose) {
        androidx.compose.foundation.layout.Box(
            Modifier.onPreviewKeyEvent { e ->
                // стрелки пульта листают файлы; обработка на корне окна, до перемещения фокуса между кнопками
                val code = e.nativeKeyEvent.keyCode
                if (!list.isNullOrEmpty() && (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    if (e.type == KeyEventType.KeyDown) step(if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                    true
                } else false
            },
        ) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(18.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(if (kind == "image") R.string.imgp_title_image else R.string.imgp_title_video), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (list == null) Text(stringResource(R.string.working), color = t.accent, fontSize = 14.sp)
            else if (list.isEmpty()) Text(stringResource(if (Storage.hasAccess(ctx)) R.string.imgp_none else R.string.imgp_no_access), color = t.sub, fontSize = 14.sp)
            else {
                val f = list[idx.coerceIn(0, list.size - 1)]
                if (kind == "image") {
                    val bmp by produceState<ImageBitmap?>(null, f.absolutePath) {
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(f.absolutePath, o)
                                var s = 1
                                while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 800) s *= 2
                                BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
                            }.getOrNull()
                        }
                    }
                    bmp?.let { Image(it, null, Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit) }
                }
                Text("${idx + 1} / ${list.size} · ${f.name}", color = t.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(f.parent.orEmpty() + " · " + (f.length() / 1024) + " KB", color = t.hint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.imgp_hint), color = t.hint, fontSize = 11.sp)
            }
            if (!list.isNullOrEmpty()) {
                // кнопки листания нужны для касания; пультом листают стрелки «влево/вправо»
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.imgp_prev), false) { step(-1) }
                    Chip(stringResource(R.string.imgp_next), false) { step(1) }
                }
                Chip(stringResource(R.string.imgp_pick), true, Modifier.focusRequester(fr)) { onPick(Uri.fromFile(list[idx.coerceIn(0, list.size - 1)])) }
            }
            Chip(stringResource(R.string.imgp_system), false, if (list.isNullOrEmpty()) Modifier.focusRequester(fr) else Modifier) { onSystem() }
            Chip(stringResource(R.string.close), false) { onClose() }
        }
        }
    }
}
