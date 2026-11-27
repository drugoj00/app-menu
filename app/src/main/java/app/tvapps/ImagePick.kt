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
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        for (n in listOf("wallpaper", "Wallpaper", "wallpapers", "Wallpapers", "Обои", "обои", "Картинки", "Изображения", "Фото", "DCIM", "Pictures", "Movies", "Download", "Загрузки")) out.add(File(base, n))
        // подключённые накопители: та же папка wallpaper
        File("/storage").listFiles()?.filter { it.isDirectory && it.name != "self" && it.name != "emulated" && it.canRead() }?.forEach { out.add(File(it, "wallpaper")); out.add(File(it, "Wallpapers")); out.add(File(it, "Обои")) }
        return out
    }

    /** Файлы подходящего вида; папки wallpaper идут первыми. */
    fun list(kind: String, ctx: android.content.Context? = null): List<File> {
        // «wall»: наши файлы живых обоев и снимки, внутри которых может лежать движение («живое» фото телефона)
        val ext = if (kind == "image") IMG else if (kind == "wall") setOf(WallFile.EXT, "jpg", "jpeg", "heic") else VID
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
        val all = out.values.toList()
        return if (ctx != null && WallRes.on(ctx)) WallRes.sorted(ctx, all) else all
    }
}

/**
 * Приложения, которые умеют выбирать файл заданного вида (файловые менеджеры, галереи, «Файлы»): для кнопки «Выбрать в приложении…» окна выбора картинки.
 * 19.11.2026 (просьба st0p0r на 4PDA: «к "Системный выбор" можно добавить опцию выбора своего приложения»): системный выбор на части ТВ бедный, а у человека
 * стоит свой файловый менеджер; здесь список таких приложений (ACTION_GET_CONTENT и ACTION_PICK), выбранное запускается напрямую, а картинка возвращается в программу.
 */
object PickerApps {
    class App(val pkg: String, val label: String, val intent: android.content.Intent, val icon: ImageBitmap?)

    /** Список приложений для [mime] (image и video): без нас самих и без закрытых окон; по одному на пакет, по алфавиту. Вызывать не из главного потока. */
    fun list(ctx: android.content.Context, mime: String): List<App> {
        val pm = ctx.packageManager
        val out = LinkedHashMap<String, App>()
        for (action in listOf(android.content.Intent.ACTION_GET_CONTENT, android.content.Intent.ACTION_PICK)) {
            val base = android.content.Intent(action).setType(mime)
            if (action == android.content.Intent.ACTION_GET_CONTENT) base.addCategory(android.content.Intent.CATEGORY_OPENABLE)
            val infos = runCatching { pm.queryIntentActivities(base, 0) }.getOrDefault(emptyList())
            for (ri in infos) {
                val ai = ri.activityInfo ?: continue
                if (!ai.exported || ai.packageName == ctx.packageName || ai.packageName in out) continue
                val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(ai.packageName).ifBlank { ai.packageName }
                val icon = runCatching { ri.loadIcon(pm).toBitmap(72, 72).asImageBitmap() }.getOrNull()
                val i = android.content.Intent(base).setComponent(android.content.ComponentName(ai.packageName, ai.name))
                out[ai.packageName] = App(ai.packageName, label, i, icon)
            }
        }
        return out.values.sortedBy { it.label.lowercase() }
    }

    /** Адрес выбранного файла из ответа приложения: сначала data, затем первый элемент clipData. */
    fun uriOf(r: androidx.activity.result.ActivityResult): Uri? {
        if (r.resultCode != android.app.Activity.RESULT_OK) return null
        val d = r.data ?: return null
        return d.data ?: d.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }
}

@Composable
fun FilePickDialog(kind: String, onPick: (Uri) -> Unit, onSystem: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    var files by remember { mutableStateOf<List<File>?>(null) }
    var idx by remember { mutableIntStateOf(0) }
    val fr = remember { FocusRequester() }
    // «Выбрать в приложении…»: список приложений, затем запуск выбранного; окно остаётся открытым, пока приложение не вернёт файл
    var viaApps by remember { mutableStateOf<List<PickerApps.App>?>(null) }
    var viaOpen by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val viaLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val u = PickerApps.uriOf(r)
        if (u != null) onPick(u)
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { Storage.grant(ctx) }
        files = withContext(Dispatchers.IO) { FilePick.list(kind, ctx) }
    }
    val list = files
    LaunchedEffect(list?.size) { runCatching { fr.requestFocus() } }
    fun step(d: Int) { val n = list?.size ?: 0; if (n > 0) idx = (idx + d + n) % n }
    if (viaOpen) {
        val apps = viaApps
        val opts = when {
            apps == null -> listOf(Opt(stringResource(R.string.working), "", onClick = {}))
            apps.isEmpty() -> listOf(Opt(stringResource(R.string.imgp_via_none), "", onClick = { viaOpen = false }))
            else -> apps.map { a ->
                Opt(a.label, "", icon = a.icon, onClick = {
                    viaOpen = false
                    runCatching { viaLauncher.launch(a.intent) }.onFailure { android.widget.Toast.makeText(ctx, ctx.getString(R.string.imgp_via_fail), android.widget.Toast.LENGTH_SHORT).show() }
                })
            }
        }
        ListDialog(stringResource(if (kind == "image") R.string.imgp_via_title else R.string.imgp_via_title_video), opts, { viaOpen = false }, hint = stringResource(R.string.imgp_via_hint))
    }
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
            Text(stringResource(if (kind == "image") R.string.imgp_title_image else if (kind == "wall") R.string.wall_open else R.string.imgp_title_video), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
            Chip(stringResource(R.string.imgp_via_app), false) {
                viaOpen = true
                if (viaApps == null) scope.launch { viaApps = withContext(Dispatchers.IO) { PickerApps.list(ctx, if (kind == "image") "image/*" else "video/*") } }
            }
            Chip(stringResource(R.string.close), false) { onClose() }
        }
        }
    }
}


/**
 * Папки обоев по разрешению экрана (16.11.2026, просьба Евгения): подпапки с именами 4K/2160p/4p, 2K/1440p/2p, 1080/1080p, 720/720p, 480/480p.
 * Сначала файлы из папки, подходящей экрану; затем из более крупных (ближайшая первой); затем остальные; в конце из более мелких.
 * Настройка «Папки по разрешению» (по умолчанию включена) в группе «Обои».
 */
object WallRes {
    fun on(c: android.content.Context) = c.applicationContext.getSharedPreferences("tvapps", android.content.Context.MODE_PRIVATE).getBoolean("wall_res_on", true)
    fun setOn(c: android.content.Context, v: Boolean) { c.applicationContext.getSharedPreferences("tvapps", android.content.Context.MODE_PRIVATE).edit().putBoolean("wall_res_on", v).apply() }

    private val names = listOf(
        4 to listOf("4k", "2160p", "2160", "uhd", "4p"),
        3 to listOf("2k", "1440p", "1440", "qhd", "2p"),
        2 to listOf("1080p", "1080", "fhd", "fullhd"),
        1 to listOf("720p", "720", "hd"),
        0 to listOf("480p", "480", "sd"),
    )

    /** Ступень по имени папки: 0 (480) ... 4 (4K); -1: имя не разрешение. */
    fun tierOf(name: String): Int {
        val n = name.trim().lowercase()
        for ((tier, ns) in names) if (n in ns) return tier
        return -1
    }

    /** Ступень экрана по короткой стороне в пикселях. */
    fun screenTier(c: android.content.Context): Int {
        val m = c.resources.displayMetrics
        val s = minOf(m.widthPixels, m.heightPixels)
        return when { s >= 2000 -> 4; s >= 1400 -> 3; s >= 1000 -> 2; s >= 700 -> 1; else -> 0 }
    }

    private fun fileTier(f: File): Int {
        var d = f.parentFile
        var i = 0
        while (d != null && i < 4) { val t = tierOf(d.name); if (t >= 0) return t; d = d.parentFile; i++ }
        return -1
    }

    fun sorted(c: android.content.Context, files: List<File>): List<File> {
        val dev = screenTier(c)
        fun rank(f: File): Int {
            val t = fileTier(f)
            return when {
                t == dev -> 0
                t > dev -> t - dev            // более крупная: ближайшая первой
                t < 0 -> 5                    // обычная папка
                else -> 10 + (dev - t)        // мельче экрана: в самом конце
            }
        }
        return files.sortedBy { rank(it) }    // сортировка устойчива: прежний порядок внутри ступени
    }
}
