package app.tvapps

import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Фон экрана «Дом»: цвет, живой градиент, картинка, видео или анимация; поверх можно положить затемнение. */
@Composable
internal fun HomeBackground(bg: HBg, menu: Boolean = false) {
    if (bg.type == "none") return
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        when (bg.type) {
            "color" -> Box(Modifier.fillMaxSize().background(Color(bg.color.toInt())))
            "gradient" -> LiveGradient(Color(bg.color.toInt()))
            "image" -> {
                // на портретном экране (телефон) берётся своя картинка, если она есть; при повороте экрана подставляется файл из памяти, без новой загрузки
                val portrait = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
                val blur = bg.blur
                val img by produceState<ImageBitmap?>(if (portrait || blur > 0) null else if (menu) BgMem.menuImage else BgMem.image, HomeStore.imgTick, portrait, blur) {
                    value = withContext(Dispatchers.IO) {
                        val f = HomeBgFiles.image(ctx, menu, portrait).takeIf { portrait && it.exists() } ?: HomeBgFiles.image(ctx, menu)
                        if (!f.exists()) null
                        else if (blur > 0) WallNet.decode(f.path, 1920, blur)?.asImageBitmap()   // размытие: дешёвое, через уменьшенное чтение
                        else runCatching { decodeBg(f.path)?.asImageBitmap() }.getOrNull()
                    }
                    if (!portrait && blur == 0) { if (menu) BgMem.menuImage = value else BgMem.image = value }
                }
                // «Сдвинуть / Масштаб»: запоминается для поверхности и ориентации
                val crop = remember(HomeStore.imgTick, menu, portrait) { WallNet.crop(ctx, if (menu) "menu" else "home", portrait) }
                img?.let {
                    Image(
                        it, null,
                        Modifier.fillMaxSize().graphicsLayer { scaleX = crop.zoom; scaleY = crop.zoom; transformOrigin = androidx.compose.ui.graphics.TransformOrigin((crop.ox + 1f) / 2f, (crop.oy + 1f) / 2f) },
                        contentScale = if (bg.fit == "stretch") ContentScale.FillBounds else ContentScale.Crop,
                        alignment = androidx.compose.ui.BiasAlignment(crop.ox, crop.oy),
                    )
                }
            }
            "video" -> VideoBg(HomeBgFiles.media(ctx, menu).path, bg.fit == "stretch", HomeStore.imgTick, bg.blur)
            "anim" -> AnimBg(HomeBgFiles.media(ctx, menu).path, bg.fit == "stretch", HomeStore.imgTick)
        }
        if (bg.dim > 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = bg.dim.coerceAtMost(90) / 100f)))
        if (bg.type == "image" || bg.type == "video") {
            // подпись «автор · источник · лицензия» у картинки или видео из интернета (в настройках обоев её можно скрыть)
            val portrait = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            val isVideo = bg.type == "video"
            val credit = remember(HomeStore.imgTick, menu, portrait, isVideo) { if (isVideo) WallNet.credit(ctx, (if (menu) "menu" else "home") + "_v", false) else WallNet.credit(ctx, (if (menu) "menu" else "home"), portrait) }
            if (credit.isNotEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomEnd) {
                Text(
                    credit, color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.35f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Фон «Дома» на Android 8+ декодируется в память видеокарты (HARDWARE): без второй копии в обычной памяти (на 1080p это около 8 МБ).
 * Если так не вышло, обычный декодер.
 */
private fun decodeBg(path: String): android.graphics.Bitmap? {
    if (Build.VERSION.SDK_INT >= 26) {
        val o = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.HARDWARE }
        runCatching { BitmapFactory.decodeFile(path, o) }.getOrNull()?.let { return it }
    }
    return BitmapFactory.decodeFile(path)
}

/** Последняя показанная картинка фона: при возврате на «Дом» не мигает. */
internal object BgMem { @Volatile var image: ImageBitmap? = null; @Volatile var menuImage: ImageBitmap? = null }

@Composable
private fun LiveGradient(base: Color) {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base.toArgb(), hsv)
    hsv[1] = maxOf(hsv[1], 0.6f); hsv[2] = maxOf(hsv[2], 0.38f)
    val first = Color(android.graphics.Color.HSVToColor(hsv))
    val second = Color(android.graphics.Color.HSVToColor(floatArrayOf((hsv[0] + 70f) % 360f, hsv[1], (hsv[2] * 1.5f).coerceAtMost(1f))))
    val third = Color(android.graphics.Color.HSVToColor(floatArrayOf((hsv[0] + 300f) % 360f, hsv[1], hsv[2])))
    // облегчённый режим (магнитолы): градиент неподвижный. Движущийся градиент заставляет GPU слабой магнитолы перерисовывать весь экран
    // 60 раз в секунду, даже когда ничего не происходит (в журнале: «команды GPU» ~5,6 мс в среднем в покое).
    if (Lite.on) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.linearGradient(listOf(first, second, third), start = Offset(size.width * 0.5f, 0f), end = Offset(size.width * 0.5f, size.height)))
        }
        return
    }
    val t = rememberInfiniteTransition(label = "grad")
    val f by t.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "gf")
    // свой слой: движение градиента перерисовывает только его, а не весь экран с плитками (на слабых процессорах это давало дёргание)
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        drawRect(
            Brush.linearGradient(
                listOf(first, second, third),
                start = Offset(size.width * f, 0f), end = Offset(size.width * (1f - f), size.height),
            ),
        )
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

/** Подгоняет видео под вид: обрезка по экрану (cover) или растяжение. */
private fun fitVideo(v: TextureView, vw: Int, vh: Int, stretch: Boolean) {
    val w = v.width.toFloat(); val h = v.height.toFloat()
    if (w <= 0f || h <= 0f || vw <= 0 || vh <= 0) return
    val m = Matrix()
    if (!stretch) {
        val va = vw.toFloat() / vh; val sa = w / h
        if (va > sa) m.setScale(va / sa, 1f, w / 2f, h / 2f) else m.setScale(1f, sa / va, w / 2f, h / 2f)
    }
    v.setTransform(m)
}

/**
 * Видео-фон: MediaPlayer на TextureView позади интерфейса, зацикливание, без звука, аппаратное декодирование системой.
 * Пауза, когда окно уходит с экрана (другое приложение, выключенный экран), продолжение при возврате. Не запускается при экономии батареи и низком заряде:
 * вместо него кадр из ролика. При ошибке декодера тоже кадр и сообщение. blur: на Android 12+ размывается средствами системы (на старых без размытия).
 */
@Composable
internal fun VideoBg(path: String, stretch: Boolean, version: Int, blur: Int = 0) {
    val ctx = LocalContext.current
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val allowed = remember(path, version) { WallNet.videoAllowed(ctx) }
    var failed by androidx.compose.runtime.remember(path, version) { androidx.compose.runtime.mutableStateOf(false) }
    val mpRef = remember { arrayOfNulls<MediaPlayer>(1) }
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> runCatching { mpRef[0]?.pause() }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> runCatching { mpRef[0]?.start() }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    if (!allowed || failed) {
        val still by produceState<ImageBitmap?>(null, path, version) { value = withContext(Dispatchers.IO) { WallNet.videoStill(path)?.asImageBitmap() } }
        androidx.compose.runtime.LaunchedEffect(failed) { if (failed) android.widget.Toast.makeText(ctx, ctx.getString(R.string.vid_err_decoder), android.widget.Toast.LENGTH_LONG).show() }
        still?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = if (stretch) ContentScale.FillBounds else ContentScale.Crop) }
        return
    }
    androidx.compose.runtime.key(path, version) {
        AndroidView(
            factory = { c ->
                val tv = TextureView(c)
                var vw = 0
                var vh = 0
                tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        runCatching {
                            mpRef[0] = MediaPlayer().apply {
                                setSurface(Surface(st)); setDataSource(path); isLooping = true; setVolume(0f, 0f)
                                setOnVideoSizeChangedListener { _, a, b -> vw = a; vh = b; fitVideo(tv, a, b, stretch) }
                                setOnPreparedListener { it.start() }
                                setOnErrorListener { _, _, _ -> failed = true; true }
                                prepareAsync()
                            }
                        }.onFailure { failed = true }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { fitVideo(tv, vw, vh, stretch) }
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { runCatching { mpRef[0]?.release() }; mpRef[0] = null; return true }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
                tv
            },
            update = { tv ->
                if (Build.VERSION.SDK_INT >= 31) tv.setRenderEffect(if (blur > 0) android.graphics.RenderEffect.createBlurEffect(blur * 0.4f + 1f, blur * 0.4f + 1f, android.graphics.Shader.TileMode.CLAMP) else null)
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun AnimBg(path: String, stretch: Boolean, version: Int) {
    val drawable by produceState<android.graphics.drawable.Drawable?>(null, path, version) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 28) android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(java.io.File(path)))
                else BitmapFactory.decodeFile(path)?.let { android.graphics.drawable.BitmapDrawable(null, it) }
            }.getOrNull()
        }
    }
    val d = drawable ?: return
    AndroidView(
        factory = { c ->
            ImageView(c).apply {
                scaleType = if (stretch) ImageView.ScaleType.FIT_XY else ImageView.ScaleType.CENTER_CROP
                setImageDrawable(d)
                if (Build.VERSION.SDK_INT >= 28 && d is android.graphics.drawable.AnimatedImageDrawable) {
                    d.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
                    d.start()
                }
            }
        },
        update = { it.scaleType = if (stretch) ImageView.ScaleType.FIT_XY else ImageView.ScaleType.CENTER_CROP },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Цвета «Дома» поверх выбранного фона: на тёмном фоне и на картинке светлый текст и полупрозрачные тёмные плитки,
 * на светлом цвете тёмный текст и светлые плитки. Без своего фона остаются цвета темы.
 */
internal fun adaptTheme(tc: TC, bg: HBg): TC {
    if (bg.type == "none") return tc
    val c = Color(bg.color.toInt())
    val lum = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
    val lightBg = (bg.type == "color" || bg.type == "gradient") && lum > 0.55f && bg.dim < 40
    return if (lightBg) tc.copy(
        dark = false, bg = Color(0xFFF1F5F9), text = Color(0xFF111827), sub = Color(0xFF334155), hint = Color(0xFF475569),
        tile = Color(0xCCFFFFFF), tileFocus = Color(0xFFD7EEEC), border = Color(0xFFCBD5E1),
    ) else tc.copy(
        dark = true, bg = Color(0xFF14141C), text = Color.White, sub = Color(0xFFD1D9E6), hint = Color(0xFFA7B3C6),
        tile = Color(0xA0101018), tileFocus = Color(0xE01E293B), border = Color(0x55FFFFFF),
    )
}
