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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
                val img by produceState<ImageBitmap?>(if (menu) BgMem.menuImage else BgMem.image, HomeStore.imgTick) {
                    value = withContext(Dispatchers.IO) {
                        val f = HomeBgFiles.image(ctx, menu)
                        if (!f.exists()) null else runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()
                    }
                    if (menu) BgMem.menuImage = value else BgMem.image = value
                }
                img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = if (bg.fit == "stretch") ContentScale.FillBounds else ContentScale.Crop) }
            }
            "video" -> VideoBg(HomeBgFiles.media(ctx, menu).path, bg.fit == "stretch", HomeStore.imgTick)
            "anim" -> AnimBg(HomeBgFiles.media(ctx, menu).path, bg.fit == "stretch", HomeStore.imgTick)
        }
        if (bg.dim > 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = bg.dim.coerceAtMost(90) / 100f)))
    }
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
    val t = rememberInfiniteTransition(label = "grad")
    val f by t.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "gf")
    Canvas(Modifier.fillMaxSize()) {
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

@Composable
private fun VideoBg(path: String, stretch: Boolean, version: Int) {
    androidx.compose.runtime.key(path, version) {
        AndroidView(
            factory = { c ->
                val tv = TextureView(c)
                var mp: MediaPlayer? = null
                var vw = 0
                var vh = 0
                tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        runCatching {
                            mp = MediaPlayer().apply {
                                setSurface(Surface(st)); setDataSource(path); isLooping = true; setVolume(0f, 0f)
                                setOnVideoSizeChangedListener { _, a, b -> vw = a; vh = b; fitVideo(tv, a, b, stretch) }
                                setOnPreparedListener { it.start() }
                                prepareAsync()
                            }
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { fitVideo(tv, vw, vh, stretch) }
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { runCatching { mp?.release() }; mp = null; return true }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
                tv
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
