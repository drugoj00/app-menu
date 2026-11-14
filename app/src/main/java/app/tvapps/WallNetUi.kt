@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.tvapps

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Предпросмотр в рамке экрана: телевизор (альбом) или телефон (портрет), с кадрированием, размытием, затемнением
 * и заглушками «живых» часов и значков поверх (так видно, как фон ляжет под интерфейс).
 */
@Composable
internal fun WallFrame(bmp: ImageBitmap?, portrait: Boolean, crop: WallNet.Crop, dim: Int, big: Boolean = false, modifier: Modifier = Modifier) {
    val t = LocalTh.current
    val sz = if (portrait) (if (big) Modifier.width(170.dp).height(360.dp) else Modifier.width(112.dp).height(236.dp))
             else (if (big) Modifier.width(520.dp).height(293.dp) else Modifier.width(360.dp).height(203.dp))
    val shape = RoundedCornerShape(if (portrait) 18.dp else 6.dp)
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    Box(modifier.then(sz).border(3.dp, t.border, shape).padding(3.dp).clip(RoundedCornerShape(if (portrait) 15.dp else 4.dp)).background(Color(0xFF101418))) {
        bmp?.let {
            Image(
                it, null,
                Modifier.fillMaxSize().graphicsLayer { scaleX = crop.zoom; scaleY = crop.zoom; transformOrigin = TransformOrigin((crop.ox + 1f) / 2f, (crop.oy + 1f) / 2f) },
                contentScale = ContentScale.Crop, alignment = BiasAlignment(crop.ox, crop.oy),
            )
        }
        if (dim > 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim.coerceAtMost(90) / 100f)))
        val time = remember(now) { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now)) }
        Text(time, color = Color.White, fontSize = if (portrait) 18.sp else 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(if (portrait) Alignment.TopCenter else Alignment.TopEnd).padding(8.dp))
        val n = if (portrait) 3 else 5
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (i in 0 until n) Box(Modifier.size(if (portrait) 26.dp else 34.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = 0.55f)))
        }
    }
}

/** Предупреждение при включении видео-обоев: строже на устройствах с малой памятью. */
@Composable
fun VideoWarnDialog(onYes: () -> Unit, onNo: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val strict = remember { WallNet.lowRam(ctx) }
    HDialog(onDismissRequest = onNo) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.vid_warn_title), color = t.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.vid_warn), color = t.sub, fontSize = 15.sp)
            if (strict) Text(stringResource(R.string.vid_warn_low), color = t.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.cancel), strict, Modifier) { onNo() }
                Chip(stringResource(if (strict) R.string.vid_enable_anyway else R.string.vid_enable), !strict) { onYes() }
            }
        }
    }
}

/** «Сдвинуть / Масштаб»: пульт (стрелки двигают, ОК переключает на масштаб), касание и мышь (перетаскивание, щипок, колесо). Значение запоминается для поверхности и ориентации. */
@Composable
fun CropDialog(target: String, startPortrait: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    var portrait by remember { mutableStateOf(startPortrait) }
    val file = remember(portrait, target) {
        val menu = target == "menu"
        when {
            target == "saver" -> WallNet.saverFile(ctx)
            portrait && HomeBgFiles.image(ctx, menu, true).exists() -> HomeBgFiles.image(ctx, menu, true)
            else -> HomeBgFiles.image(ctx, menu)
        }
    }
    val c0 = remember(portrait, target) { WallNet.crop(ctx, target, portrait) }
    var ox by remember(portrait, target) { mutableFloatStateOf(c0.ox) }
    var oy by remember(portrait, target) { mutableFloatStateOf(c0.oy) }
    var zoom by remember(portrait, target) { mutableFloatStateOf(c0.zoom) }
    var zoomMode by remember { mutableStateOf(false) }
    var w by remember { mutableStateOf(1) }
    var h by remember { mutableStateOf(1) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    fun mv(dx: Float, dy: Float) { ox = (ox + dx).coerceIn(-1f, 1f); oy = (oy + dy).coerceIn(-1f, 1f) }
    fun zm(d: Float) { zoom = (zoom + d).coerceIn(1f, 4f) }
    val blur = if (target == "menu") HomeStore.menuBg.blur else if (target == "home") HomeStore.layout.bg.blur else 0
    val dim = if (target == "menu") HomeStore.menuBg.dim else if (target == "home") HomeStore.layout.bg.dim else 0
    val shown by produceState<ImageBitmap?>(null, file.path, blur) { value = withContext(Dispatchers.IO) { WallNet.decode(file.path, 1000, blur)?.asImageBitmap() } }

    HDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().padding(24.dp).clip(RoundedCornerShape(20.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(18.dp).barScroll(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.crop_title), color = t.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (!file.exists()) Text(stringResource(R.string.crop_none), color = t.accent, fontSize = 14.sp)
            else {
                Text(stringResource(if (zoomMode) R.string.crop_mode_zoom else R.string.crop_mode_move) + " · " + (zoom * 100).toInt() + "%", color = t.accent, fontSize = 14.sp)
                Box(
                    Modifier.focusRequester(fr).focusable()
                        .onSizeChanged { w = it.width.coerceAtLeast(1); h = it.height.coerceAtLeast(1) }
                        .onPreviewKeyEvent { e ->
                            val code = e.nativeKeyEvent.keyCode
                            val arrow = code == android.view.KeyEvent.KEYCODE_DPAD_LEFT || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT || code == android.view.KeyEvent.KEYCODE_DPAD_UP || code == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                            val ok = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER
                            if (!arrow && !ok) false
                            else {
                                if (e.type == KeyEventType.KeyDown) {
                                    if (ok) zoomMode = !zoomMode
                                    else if (zoomMode) when (code) {
                                        android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> zm(0.1f)
                                        else -> zm(-0.1f)
                                    } else when (code) {
                                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> mv(-0.06f, 0f)
                                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> mv(0.06f, 0f)
                                        android.view.KeyEvent.KEYCODE_DPAD_UP -> mv(0f, -0.06f)
                                        else -> mv(0f, 0.06f)
                                    }
                                }
                                true
                            }
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, z, _ -> mv(-pan.x / (w / 2f), -pan.y / (h / 2f)); zoom = (zoom * z).coerceIn(1f, 4f) }
                        }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    if (ev.type == PointerEventType.Scroll) { zoom = (zoom - ev.changes.first().scrollDelta.y * 0.1f).coerceIn(1f, 4f); ev.changes.forEach { it.consume() } }
                                }
                            }
                        },
                ) { WallFrame(shown, portrait, WallNet.Crop(ox, oy, zoom), dim, big = true) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("◀", false) { mv(-0.06f, 0f) }
                Chip("▶", false) { mv(0.06f, 0f) }
                Chip("▲", false) { mv(0f, -0.06f) }
                Chip("▼", false) { mv(0f, 0.06f) }
                Chip("＋", false) { zm(0.1f) }
                Chip("－", false) { zm(-0.1f) }
                Chip(stringResource(R.string.crop_reset), false) { ox = 0f; oy = 0f; zoom = 1f }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(stringResource(if (portrait) R.string.wn_orient_port else R.string.wn_orient_land) + " ⇄", false) { portrait = !portrait }
                Chip(stringResource(R.string.crop_done), true) {
                    WallNet.setCrop(ctx, target, portrait, WallNet.Crop(ox, oy, zoom))
                    Toast.makeText(ctx, ctx.getString(R.string.crop_saved), Toast.LENGTH_SHORT).show()
                    onClose()
                }
                Chip(stringResource(R.string.cancel), false) { onClose() }
            }
        }
    }
}

/**
 * «Обои из интернета»: поверхность (Дом, меню, заставка), картинки или видео, источник, тема, ориентация, предпросмотр в рамке, «Другая»,
 * автосмена картинок раз в сутки, подпись, кэш. Применение идёт прямо отсюда (WallNet.applyTo / applyVideoTo).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun WallNetDialog(initial: String, onOwn: () -> Unit, onOwnVideo: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(initial) }
    var kind by remember { mutableStateOf("image") }
    var tick by remember { mutableStateOf(0) }
    val video = kind == "video"
    val src = remember(target, kind, tick) { if (video) WallNet.cfg(ctx, target, "vsrc", "commons") else WallNet.cfg(ctx, target, "src", "commons") }
    val cat = remember(target, tick) { WallNet.cfg(ctx, target, "cat", "nature") }
    val mode = remember(target, tick) { WallNet.str(ctx, "mode_$target", "off") }
    val sameAsHome = remember(target, tick) { WallNet.same(ctx, target) }
    val orient = remember(target, tick) { WallNet.cfg(ctx, target, "orient", "auto") }
    var wifi by remember { mutableStateOf(WallNet.bool(ctx, "wifi_only", true)) }
    var creditOn by remember { mutableStateOf(WallNet.bool(ctx, "credit_on", true)) }
    var key by remember(src) { mutableStateOf(WallNet.key(ctx, src)) }
    var link by remember { mutableStateOf(WallNet.str(ctx, "vlink")) }
    var helpOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var cur by remember { mutableStateOf<Pair<WallPic, File>?>(null) }
    var cacheN by remember { mutableStateOf(WallNet.cacheCount(ctx) + WallNet.videoCacheCount(ctx)) }
    var cropFor by remember { mutableStateOf<Boolean?>(null) }
    var warn by remember { mutableStateOf<(() -> Unit)?>(null) }
    val applyReq = remember { FocusRequester() }
    val applyBiv = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    var appliedPath by remember { mutableStateOf("") }   // файл, который уже поставлен: для напоминания при закрытии окна
    var askClose by remember { mutableStateOf(false) }
    val pending = cur != null && cur?.second?.path != appliedPath
    fun requestClose() { if (busy) return; if (pending) askClose = true else onClose() }
    // после загрузки картинки фокус сам переходит на «Поставить»: шаг «Поставить» не теряется под предпросмотром
    LaunchedEffect(cur?.second?.path) { if (cur != null) { delay(300); val okF = runCatching { applyReq.requestFocus() }; okF.onFailure { android.util.Log.w("TVApps", "обои: фокус на «Поставить» не встал: " + it.message) }; android.util.Log.i("TVApps", "обои: фокус на «Поставить»: " + okF.getOrNull()); delay(100); runCatching { applyBiv.bringIntoView() } } }
    val surfaceNames = mapOf("home" to R.string.wn_surf_home, "menu" to R.string.wn_surf_menu, "saver" to R.string.wn_surf_saver)
    val screenPortrait = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    val wantPortrait = when (orient) { "port" -> true; "land" -> false; else -> screenPortrait }

    fun load(today: Boolean) {
        if (busy) return
        if (video && src == "link" && !link.startsWith("http")) { status = ctx.getString(R.string.vid_link_bad); return }
        scope.launch {
            busy = true
            status = ctx.getString(R.string.wn_searching)
            NetErr.clear()
            try {
                val r = withContext(Dispatchers.IO) {
                    if (video) {
                        val pic = if (src == "link") WallNet.linkPic(link.trim()) else WallNet.pickVideo(ctx, src, cat, wantPortrait)
                        withContext(Dispatchers.Main) { status = ctx.getString(R.string.vid_downloading) }
                        pic to WallNet.downloadVideo(ctx, pic)
                    } else {
                        // картинка может не пройти проверку (не изображение, слишком мала): берём другую, до 3 раз, и только потом показываем ошибку
                        var attempt = 0
                        var res: Pair<WallPic, File>? = null
                        while (res == null) {
                            attempt++
                            val pic = WallNet.pick(ctx, src, cat, today, wantPortrait)
                            withContext(Dispatchers.Main) { status = ctx.getString(R.string.wn_downloading) }
                            try { res = pic to WallNet.download(ctx, pic) } catch (e: WallErr) {
                                if (e.kind == "bad" && attempt < 3) android.util.Log.i("TVApps", "обои: картинка не подошла, попытка " + (attempt + 1) + " из 3") else throw e
                            }
                        }
                        res!!
                    }
                }
                cur = r
                status = ""
                cacheN = WallNet.cacheCount(ctx) + WallNet.videoCacheCount(ctx)
                android.util.Log.i("TVApps", "обои: скачано " + r.first.source + (if (video) " (видео)" else "") + ", " + (r.second.length() / 1024) + " КБ")
            } catch (e: Throwable) {
                android.util.Log.w("TVApps", "обои: ошибка загрузки (" + src + ", " + cat + "): " + e.javaClass.simpleName + ": " + e.message, e)
                status = WallNet.errText(ctx, e)
            }
            busy = false
        }
    }

    fun applyNow(pv: Pair<WallPic, File>) {
        scope.launch {
            busy = true
            android.util.Log.i("TVApps", "обои: нажато «Поставить»: " + target + (if (pv.first.video) " (видео)" else "") + ", файл " + pv.second.name + ", " + (pv.second.length() / 1024) + " КБ")
            var why = ""
            val ok = withContext(Dispatchers.IO) {
                runCatching { if (pv.first.video) WallNet.applyVideoTo(ctx, target, pv.second, pv.first) else WallNet.applyTo(ctx, target, pv.second, pv.first) }
                    .onFailure { why = it.javaClass.simpleName + ": " + it.message; android.util.Log.w("TVApps", "обои: «Поставить» не вышло, исключение: " + why, it) }
                    .getOrDefault(false)
            }
            busy = false
            if (ok) appliedPath = pv.second.path
            if (!ok && why.isEmpty() && HomeBgFiles.lastFail.isNotEmpty()) why = HomeBgFiles.lastFail
            android.util.Log.i("TVApps", "обои: «Поставить» " + target + " -> " + (if (ok) "готово" else "не вышло" + (if (why.isNotEmpty()) ": $why" else "")))
            if (!ok && why.isNotEmpty()) status = ctx.getString(R.string.failed) + ": " + why
            Toast.makeText(ctx, ctx.getString(if (ok) R.string.wn_applied else R.string.failed), Toast.LENGTH_SHORT).show()
        }
    }

    val fields = OutlinedTextFieldDefaults.colors(
        focusedTextColor = t.text, unfocusedTextColor = t.text, focusedBorderColor = t.accent, unfocusedBorderColor = t.border,
        cursorColor = t.accent, focusedLabelColor = t.accent, unfocusedLabelColor = t.sub,
    )
    val srcLabels = mapOf(
        "commons" to "Wikimedia Commons", "nasa" to "NASA", "openverse" to "Openverse",
        "pexels" to "Pexels · " + stringResource(R.string.wn_own_key), "unsplash" to "Unsplash · " + stringResource(R.string.wn_own_key),
        "pixabay" to "Pixabay · " + stringResource(R.string.wn_own_key), "link" to stringResource(R.string.vid_link),
    )
    val catLabels = mapOf("nature" to R.string.wn_cat_nature, "space" to R.string.wn_cat_space, "city" to R.string.wn_cat_city, "abstract" to R.string.wn_cat_abstract)

    HDialog(onDismissRequest = { requestClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().padding(24.dp).clip(RoundedCornerShape(20.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(18.dp).barScroll(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.wn_title), color = t.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (pending) Text(stringResource(R.string.wn_pending, stringResource(surfaceNames[target]!!)), color = t.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            // состояние и ошибка загрузки видны сразу, а не внизу длинного окна, куда пультом не долистать
            if (status.isNotEmpty()) Text(status, color = t.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.wn_surface), color = t.sub, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (sf in WallNet.SURFACES) Chip(stringResource(surfaceNames[sf]!!), target == sf) { target = sf; cur = null; status = "" }
                if (target != "home") Chip(stringResource(R.string.wn_same) + " · " + stringResource(if (sameAsHome) R.string.on else R.string.off), sameAsHome) {
                    if (sameAsHome) { WallNet.put(ctx, "same_$target", false); for ((kk, dd) in listOf("src" to "commons", "cat" to "nature", "orient" to "auto")) WallNet.put(ctx, kk + "_" + target, WallNet.cfg(ctx, "home", kk, dd)) }
                    else WallNet.put(ctx, "same_$target", true)
                    tick++
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(stringResource(R.string.wn_kind_image), !video) { kind = "image"; cur = null; status = "" }
                Chip(stringResource(R.string.wn_kind_video), video) { kind = "video"; cur = null; status = "" }
                Chip(stringResource(R.string.wn_help_btn), helpOpen) { helpOpen = !helpOpen }
            }
            if (helpOpen) Text(stringResource(if (video) R.string.vid_help else R.string.wn_help), color = t.hint, fontSize = 12.sp)

            Text(stringResource(R.string.wn_source), color = t.sub, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in (if (video) WallNet.VSOURCES else WallNet.SOURCES)) Chip(srcLabels[s].orEmpty(), src == s) { WallNet.setCfg(ctx, target, if (video) "vsrc" else "src", s); tick++ }
            }
            if (video && src == "link") OutlinedTextField(
                link, { link = it; WallNet.put(ctx, "vlink", it) }, singleLine = true, colors = fields,
                label = { Text(stringResource(R.string.vid_link_label)) }, modifier = Modifier.fillMaxWidth(),
            )
            if (WallNet.hasKeyField(src) && src != "link") {
                var keyShown by remember { mutableStateOf(false) }   // 7.0: ключ API скрыт, глазик справа в поле
                OutlinedTextField(
                    key, { key = it; WallNet.put(ctx, "key_$src", it.trim()) }, singleLine = true, colors = fields,
                    label = { Text(stringResource(if (src == "nasa") R.string.wn_key_nasa else R.string.wn_key_label)) },
                    visualTransformation = secretTransform(keyShown), trailingIcon = { SecretEye(keyShown, t.accent) { keyShown = !keyShown } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (!(video && src == "link")) {
                Text(stringResource(R.string.wn_cat), color = t.sub, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in WallNet.CATS) Chip(stringResource(catLabels[c]!!), cat == c) { WallNet.setCfg(ctx, target, "cat", c); tick++ }
                    if (target == initial && initial != "saver") Chip(stringResource(if (video) R.string.vid_own else R.string.wn_cat_own), false) { if (video) onOwnVideo() else onOwn() }
                }
            } else if (target == initial && initial != "saver") Chip(stringResource(R.string.vid_own), false) { onOwnVideo() }
            Text(stringResource(R.string.wn_orient), color = t.sub, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((o, r) in listOf("auto" to R.string.wn_orient_auto, "land" to R.string.wn_orient_land, "port" to R.string.wn_orient_port))
                    Chip(stringResource(r), orient == o) { WallNet.setCfg(ctx, target, "orient", o); tick++ }
            }

            // предпросмотр в рамке ТВ или телефона
            val pv = cur
            if (pv != null) {
                val bmp by produceState<ImageBitmap?>(null, pv.second.path) {
                    value = withContext(Dispatchers.IO) { runCatching { (if (pv.first.video) WallNet.videoStill(pv.second.path) else WallNet.decode(pv.second.path, 800))?.asImageBitmap() }.getOrNull() }
                }
                val menuS = target == "menu"
                val sblur = if (video) 0 else if (menuS) HomeStore.menuBg.blur else if (target == "home") HomeStore.layout.bg.blur else 0
                val shown by produceState<ImageBitmap?>(null, pv.second.path, sblur) {
                    value = if (sblur == 0 || pv.first.video) null else withContext(Dispatchers.IO) { WallNet.decode(pv.second.path, 800, sblur)?.asImageBitmap() }
                }
                WallFrame(shown ?: bmp, pv.first.portrait, if (video) WallNet.Crop(0f, 0f, 1f) else WallNet.crop(ctx, target, pv.first.portrait),
                    if (menuS) HomeStore.menuBg.dim else if (target == "home") HomeStore.layout.bg.dim else 0)
                Text(pv.first.credit(), color = t.text, fontSize = 13.sp)
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pv != null) Chip(stringResource(R.string.wn_apply) + ": " + stringResource(surfaceNames[target]!!), true, Modifier.focusRequester(applyReq).bringIntoViewRequester(applyBiv)) {
                    if (!busy) {
                        // видео нагружает устройство: при включении спрашиваем
                        if (pv.first.video && !WallNet.videoActive(ctx, target)) warn = { applyNow(pv) } else applyNow(pv)
                    }
                }
                Chip(stringResource(R.string.wn_other), pv == null) { load(false) }
                if (!video) Chip(stringResource(R.string.wn_today), false) { load(true) }
                if (!video) Chip(stringResource(R.string.crop_btn), false) { cropFor = pv?.first?.portrait ?: wantPortrait }
            }

            if (!video) {
                Text(stringResource(R.string.wn_auto), color = t.sub, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((m, r) in listOf("off" to R.string.wn_mode_off, "day" to R.string.wn_mode_day, "random" to R.string.wn_mode_random))
                        Chip(stringResource(r), mode == m) { WallNet.put(ctx, "mode_$target", m); WallNet.put(ctx, "last_$target", ""); WallNet.resetTry(ctx, target); if (m != "off") WallNet.autoRefresh(ctx); tick++ }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(stringResource(R.string.wn_wifi) + " · " + stringResource(if (wifi) R.string.on else R.string.off), wifi) { wifi = !wifi; WallNet.put(ctx, "wifi_only", wifi) }
                Chip(stringResource(R.string.wn_credit) + " · " + stringResource(if (creditOn) R.string.on else R.string.off), creditOn) { creditOn = !creditOn; WallNet.put(ctx, "credit_on", creditOn); HomeStore.bumpImg() }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (target == "saver" && WallNet.saverOn(ctx)) Chip(stringResource(R.string.wn_saver_off), false) { WallNet.put(ctx, "saver_pic", false); Toast.makeText(ctx, ctx.getString(R.string.wn_saver_removed), Toast.LENGTH_SHORT).show(); tick++ }
                Chip(stringResource(R.string.wn_remove) + ": " + stringResource(surfaceNames[target]!!), false) {
                    if (!busy) { WallNet.remove(ctx, target); cur = null; tick++; Toast.makeText(ctx, ctx.getString(R.string.wn_removed), Toast.LENGTH_SHORT).show() }
                }
                Chip(stringResource(R.string.wn_clear_cache, cacheN), false) {
                    val n = WallNet.clearCache(ctx) + WallNet.clearVideoCache(ctx); cacheN = WallNet.cacheCount(ctx) + WallNet.videoCacheCount(ctx)
                    Toast.makeText(ctx, ctx.getString(R.string.wn_cache_cleared, n), Toast.LENGTH_SHORT).show()
                }
                Chip(stringResource(R.string.close), false) { requestClose() }
            }
        }
    }
    warn?.let { w -> VideoWarnDialog(onYes = { warn = null; w() }, onNo = { warn = null }) }
    if (askClose) {
        val pv2 = cur
        HDialog(onDismissRequest = { askClose = false }) {
            Column(
                Modifier.clip(RoundedCornerShape(20.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(20.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val askReq = remember { FocusRequester() }
                LaunchedEffect(Unit) { delay(150); runCatching { askReq.requestFocus() } }
                Text(stringResource(R.string.wn_ask_title), color = t.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.wn_ask_text, stringResource(surfaceNames[target]!!)), color = t.sub, fontSize = 15.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.wn_ask_yes), true, Modifier.focusRequester(askReq)) {
                        askClose = false
                        if (pv2 != null) { applyNow(pv2); onClose() } else onClose()
                    }
                    Chip(stringResource(R.string.wn_ask_no), false) { askClose = false; onClose() }
                }
            }
        }
    }
    cropFor?.let { p -> CropDialog(target, p) { cropFor = null; tick++ } }
}
