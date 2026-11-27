package app.tvapps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Где на экране лежит картинка фона «Дома»: по этому месту передний план поверх часов совпадает с фоном точка в точку. */
internal object HomeBgBounds {
    var pos by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)
}

/**
 * Передний план картинки (деревья, дома, горы): всё, что не небо. Небо ищется от верхнего края: светлые, голубые и белые участки с плавными переходами;
 * просветы неба среди листвы добавляются по цвету неба той же высоты. Если неба на картинке почти нет, переднего плана нет (часы лежат поверх всего).
 */
internal object SkyCut {
    private fun r(c: Int) = (c shr 16) and 255
    private fun g(c: Int) = (c shr 8) and 255
    private fun b(c: Int) = c and 255

    private fun skyLike(c: Int): Boolean {
        val r = r(c); val g = g(c); val b = b(c)
        val v = maxOf(r, g, b); val mn = minOf(r, g, b)
        val sat = if (v == 0) 0f else (v - mn) / v.toFloat()
        return (b >= r + 8 && b >= g - 8 && v >= 95) ||      // голубое и синее небо
            (sat < 0.20f && v >= 150) ||                     // облака и белёсое небо
            (b >= 175 && b >= r - 4 && sat < 0.45f)          // бледно-голубое
    }

    private fun dist(a: Int, c: Int) = maxOf(kotlin.math.abs(r(a) - r(c)), kotlin.math.abs(g(a) - g(c)), kotlin.math.abs(b(a) - b(c)))

    /** Маска переднего плана: 255 передний план, 0 небо. null: неба слишком мало. */
    fun foreground(px: IntArray, w: Int, h: Int): IntArray? {
        val sky = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        for (x in 0 until w) if (skyLike(px[x])) { sky[x] = true; queue[tail++] = x }
        while (head < tail) {
            val p = queue[head++]
            val x = p % w
            val y = p / w
            fun step(q: Int) {
                if (!sky[q] && skyLike(px[q]) && dist(px[p], px[q]) <= 22) { sky[q] = true; queue[tail++] = q }
            }
            if (x > 0) step(p - 1)
            if (x < w - 1) step(p + 1)
            if (y > 0) step(p - w)
            if (y < h - 1) step(p + w)
        }
        var top = 0
        for (i in 0 until w * (h / 3)) if (sky[i]) top++
        if (top < w * (h / 3) / 5) return null
        // просветы неба между ветками: цвет близок к небу той же высоты; только там, где небо ещё занимает заметную часть строки
        for (y in 0 until h) {
            var n = 0; var sr = 0L; var sg = 0L; var sb = 0L
            for (x in 0 until w) if (sky[y * w + x]) { val c = px[y * w + x]; n++; sr += r(c); sg += g(c); sb += b(c) }
            if (n < w / 12) continue
            val mean = (((sr / n).toInt()) shl 16) or (((sg / n).toInt()) shl 8) or (sb / n).toInt()
            for (x in 0 until w) {
                val i = y * w + x
                if (!sky[i] && skyLike(px[i]) && dist(px[i], mean) <= 18) sky[i] = true
            }
        }
        // мягкий край: среднее по соседям 3 на 3
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) { n++; if (!sky[yy * w + xx]) sum += 255 }
            }
            out[y * w + x] = sum / n
        }
        return out
    }

    @Volatile private var cacheKey = ""
    @Volatile private var cache: ImageBitmap? = null
    /** Слой переднего плана для файла картинки (прозрачное там, где небо). Считается один раз на картинку. */
    fun layer(path: String): ImageBitmap? {
        val f = java.io.File(path)
        val key = path + "|" + f.lastModified() + "|" + f.length()
        if (key == cacheKey) return cache
        val result = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1920) sample *= 2
            val full = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return@runCatching null
            val sw = minOf(960, full.width)
            val sh = maxOf(1, full.height * sw / full.width)
            val small = Bitmap.createScaledBitmap(full, sw, sh, true)
            val px = IntArray(sw * sh)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            val m = foreground(px, sw, sh) ?: return@runCatching null
            val mask = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            mask.setPixels(IntArray(sw * sh) { m[it] shl 24 }, 0, sw, 0, 0, sw, sh)
            val out = Bitmap.createBitmap(full.width, full.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawBitmap(full, 0f, 0f, null)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
            c.drawBitmap(mask, null, android.graphics.Rect(0, 0, full.width, full.height), paint)
            out.asImageBitmap()
        }.getOrNull()
        cache = result
        cacheKey = key
        return result
    }
}

private fun bgFile(ctx: Context, portrait: Boolean): java.io.File =
    HomeBgFiles.image(ctx, false, portrait).takeIf { portrait && it.exists() } ?: HomeBgFiles.image(ctx, false)

/** Настройки крупных часов в строке ref: «h12» и «drop=N» (на сколько процентов высоты экрана опустить, по умолчанию 12). */
internal object BigClockOpt {
    fun h12(ref: String) = ref.split(';').contains("h12")
    fun drop(ref: String): Int = ref.split(';').firstOrNull { it.startsWith("drop=") }?.substring(5)?.toIntOrNull()?.coerceIn(0, 40) ?: 22
    /** «live»: движение картинки (объём, ветер, рябь). По умолчанию выключено. */
    fun live(ref: String) = ref.split(';').contains("live")
    /** «cov=N»: на сколько процентов высоты цифр картинке можно закрыть их низ (по умолчанию десятая часть). */
    fun cover(ref: String): Int = ref.split(';').firstOrNull { it.startsWith("cov=") }?.substring(4)?.toIntOrNull()?.coerceIn(5, 40) ?: 10
    fun make(h12: Boolean, drop: Int, cover: Int = 10, live: Boolean = false) =
        (if (h12) "h12;" else "") + "drop=" + drop.coerceIn(0, 40) + (if (cover != 10) ";cov=" + cover.coerceIn(5, 40) else "") + (if (live) ";live" else "")
}

/**
 * 26.11.2026 (владелица и Евгений, «как в TvOS»): крупные полупрозрачные часы без подложки; передний план обоев рисуется поверх цифр,
 * поэтому часы выглядят стоящими за деревьями. n: непрозрачность цифр в процентах; flag: передний план поверх часов; ref "h12": 12-часовой вид.
 */
@Composable
internal fun BigClockBody(w: HWidget) {
    val ctx = LocalContext.current
    val bg = HomeStore.layout.bg
    val now = rememberNow(false)
    val h12 = BigClockOpt.h12(w.ref)
    val text = DFmt.format(if (h12) "h:mm" else "H:mm", AppLoc.locale(), now)
    val alpha = (if (w.n in 20..100) w.n else 75) / 100f
    val portrait = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    val cover = BigClockOpt.cover(w.ref) / 100f
    // где в самом виджете верх и низ цифр: выше черты картинка поверх часов не рисуется никогда
    var glyphs by remember { mutableStateOf(0f to 0f) }
    var baseline by remember { mutableStateOf(0f) }
    var widgetAt by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    // на сколько цифры подняты, чтобы сесть за линию верхушек (в точках виджета, ноль или меньше)
    var lift by remember { mutableStateOf(0f) }
    // где по ширине стоит двоеточие: его картинка не закрывает, по нему читается время
    var colonInText by remember { mutableStateOf(0f to 0f) }
    var colon by remember { mutableStateOf(0f to 0f) }
    val ink = LocalTh.current.text
    // где на фоне стоят цифры (доли фона): меняется только при заметном сдвиге, чтобы передний план не считался заново из-за мелочей
    var digits by remember { mutableStateOf<List<Float>?>(null) }
    val front by produceState<FrontLayer?>(null, w.flag, bg.type, bg.blur, HomeStore.imgTick, portrait, cover, digits, DepthCut.ver) {
        value = if (!w.flag || bg.type != "image" || bg.blur > 0) null
        else withContext(Dispatchers.IO) {
            val f = bgFile(ctx, portrait)
            // нейросеть делит любую картинку; пока она не скачана (или процессор x86), часы заходят только за то, что стоит на фоне неба
            val at = digits
            if (!f.exists() || at == null) null else {
                kotlinx.coroutines.delay(300)   // пока экран листают, не считаем на каждом шаге
                val crop = WallNet.crop(ctx, "home", portrait)
                val box = HomeBgBounds.size
                val spot = ClockPlace(at[0], at[1], at[2], at[3], box.width, box.height, bg.fit == "stretch", crop.ox, crop.oy, crop.zoom, cover)
                // нейросеть делит любую картинку; пока она не скачана, часы заходят только за то, что стоит на фоне неба
                DepthCut.layer(ctx, f.path, spot) ?: SkyCut.layer(f.path)?.let { FrontLayer(it, null) }
            }
        }
    }
    // разделённую картинку «Дом» рисует вместо обычной: в ней могут быть добавленные деревья и движение
    val moving = BigClockOpt.live(w.ref) && w.flag
    androidx.compose.runtime.SideEffect { LiveWall.scene = front?.live; LiveWall.clockMotion = moving; LiveWall.clockFront = front != null }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { LiveWall.scene = null; LiveWall.clockMotion = false; LiveWall.clockFront = false } }
    // где лежит фон в собственных координатах виджета: «Дом» умеет уменьшать все строки целиком (FitHeight), это тоже учитывается
    var frame by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    Box(Modifier.fillMaxSize().clipToBounds().onGloballyPositioned { c ->
        widgetAt = c
        val a = c.windowToLocal(HomeBgBounds.pos)
        val b = c.windowToLocal(HomeBgBounds.pos + Offset(HomeBgBounds.size.width.toFloat(), HomeBgBounds.size.height.toFloat()))
        frame = androidx.compose.ui.geometry.Rect(a, b)
    }, contentAlignment = Alignment.TopStart) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            val d = LocalDensity.current
            val dropDp = BigClockOpt.drop(w.ref) * androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp / 100
            // цифры берут почти всю высоту виджета, но обязаны помещаться по ширине
            val byH = (maxHeight.value - dropDp) * 0.98f
            val byW = maxWidth.value / (if (text.length > 4) 2.75f else 2.25f)
            val sizeDp = minOf(byH * w.fs.coerceIn(50, 250) / 100f, byW)
            val size = with(d) { sizeDp.dp.toSp() }
            androidx.compose.material3.Text(
                text, color = ink.copy(alpha = alpha), fontSize = size, lineHeight = size, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                // под цифрами шрифт оставляет пустое поле: цифры опускаются на него к нижнему краю виджета
                onTextLayout = { r ->
                    baseline = r.firstBaseline
                    val at = text.indexOf(':')
                    if (at >= 0) { val bx = r.getBoundingBox(at); colonInText = bx.left to bx.right }
                },
                // при движении цифры чуть плывут между дальним и ближним планом (только рисунок: расчёты этого не видят)
                modifier = Modifier.offset(y = (sizeDp * 0.15f).dp).offset { androidx.compose.ui.unit.IntOffset(0, kotlin.math.round(lift).toInt()) }.drawWithContent {
                    val (sx, sy) = LiveWall.clockShift(LiveWall.phase, frame.width)
                    translate(sx, sy) { this@drawWithContent.drawContent() }
                }.onGloballyPositioned { c ->
                    val box = HomeBgBounds.size
                    val own = widgetAt
                    if (baseline > 0f && own != null && own.isAttached && box.width > 0 && box.height > 0 && c.size.width > 1) {
                        // высота цифр этого шрифта: 0,71 его размера, низ цифр на базовой линии
                        val high = with(d) { sizeDp.dp.toPx() } * 0.71f
                        val inWidget = own.localPositionOf(c, Offset(0f, baseline - high)).y to own.localPositionOf(c, Offset(0f, baseline)).y
                        val dots = own.localPositionOf(c, Offset(colonInText.first, 0f)).x to own.localPositionOf(c, Offset(colonInText.second, 0f)).x
                        if (kotlin.math.abs(dots.first - colon.first) > 1f || kotlin.math.abs(dots.second - colon.second) > 1f) colon = dots
                        if (kotlin.math.abs(inWidget.first - glyphs.first) > 1f || kotlin.math.abs(inWidget.second - glyphs.second) > 1f) glyphs = inWidget
                        val a = c.localToWindow(Offset(0f, baseline - high))
                        val b = c.localToWindow(Offset(c.size.width.toFloat(), baseline))
                        // подъём из этих чисел вычитается: передний план считается по исходному месту
                        val risen = own.localToWindow(Offset(0f, kotlin.math.round(lift))).y - own.localToWindow(Offset.Zero).y
                        val now2 = listOf(
                            (a.x - HomeBgBounds.pos.x) / box.width, (a.y - risen - HomeBgBounds.pos.y) / box.height,
                            (b.x - HomeBgBounds.pos.x) / box.width, (b.y - risen - HomeBgBounds.pos.y) / box.height,
                        )
                        val old = digits
                        if (old == null || old.indices.any { kotlin.math.abs(old[it] - now2[it]) > 0.02f }) digits = now2
                    }
                },
                style = TextStyle(fontFeatureSettings = "tnum", platformStyle = PlatformTextStyle(includeFontPadding = false), shadow = Shadow(Color.Black.copy(alpha = 0.18f), Offset(0f, 2f), 12f)),
            )
        }
        val img = front?.image
        val fr = frame
        val (top, bottom) = glyphs
        val bottomAt = front?.bottomAt
        androidx.compose.runtime.SideEffect {
            val base = digits
            val want = if (bottomAt != null && base != null && fr.height > 1f && bottom > top) (bottomAt - base[3]) * fr.height else 0f
            // выше верхнего края виджета цифры не поднимаются
            val room = if (bottom > top) maxOf(0f, top - lift - 4f) else 0f
            // вниз можно только на свободное поле под цифрами; опустить сильнее позволяет настройка «Опустить ниже»
            val down = if (bottom > top) (bottom - top) * 0.08f else 0f
            val next = want.coerceIn(-room, down)
            if (kotlin.math.abs(next - lift) > 1f) lift = next
        }
        if (img != null && fr.width > 1f && fr.height > 1f && bottom > top) {
            val crop = remember(HomeStore.imgTick, portrait) { WallNet.crop(ctx, "home", portrait) }
            val k = 1f - bg.dim.coerceIn(0, 90) / 100f
            // та же картинка тем же способом, что у фона (обрезка, масштаб, затемнение), сдвинутая на положение фона относительно этого виджета
            // 26.11.2026 (слово владелицы: «достаточно чуть закрыть низ числа, и оно уже сзади»): слой переднего плана виден только
            // ниже черты у низа цифр, с узким плавным краем; выше черты цифры всегда целые
            // за линией верхушек одному дереву или кусту повыше можно закрыть низ побольше (до трети с небольшим), остальные только чуть
            val live = front?.live
            val line = bottom - (bottom - top) * (if (live != null) minOf(0.45f, cover * 2.6f) else cover)
            val soft = (bottom - top) * 0.03f
            val verts = remember { FloatArray((LiveWall.COLS + 1) * (LiveWall.ROWS + 1) * 2) }
            val matrix = remember { android.graphics.Matrix() }
            val tint = remember(k) {
                if (k >= 1f) LiveWall.paint else android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(k, 0f, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
                }
            }
            Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }.drawWithContent {
                drawContent()
                drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = line - soft, endY = line + soft), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                if (live != null && colon.second > colon.first) {
                    val pad = (colon.second - colon.first) * 0.1f
                    drawRect(Color.Transparent, Offset(colon.first + pad, 0f), androidx.compose.ui.geometry.Size(colon.second - colon.first - 2 * pad, size.height), blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
                }
            }, contentAlignment = Alignment.TopStart) {
            if (live != null) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                // тот же передний план теми же движениями, что у фона «Дома», только ниже черты у низа цифр
                val t = LiveWall.phase
                LiveWall.verts(live, t, verts)
                LiveWall.place(matrix, live.front.width, live.front.height, fr.width, fr.height, bg.fit == "stretch", crop.ox, crop.oy, crop.zoom)
                LiveWall.camera(matrix, 2, t, fr.width, fr.height)
                matrix.postTranslate(fr.left, fr.top)
                drawContext.canvas.nativeCanvas.let { c ->
                    c.save()
                    c.concat(matrix)
                    c.drawBitmapMesh(live.front, LiveWall.COLS, LiveWall.ROWS, verts, 0, null, 0, tint)
                    c.restore()
                }
            } else Image(
                img, null,
                Modifier.layout { measurable, constraints ->
                    val p = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(kotlin.math.round(fr.width).toInt(), kotlin.math.round(fr.height).toInt()))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        p.place(kotlin.math.round(fr.left).toInt(), kotlin.math.round(fr.top).toInt())
                    }
                }.graphicsLayer { scaleX = crop.zoom; scaleY = crop.zoom; transformOrigin = androidx.compose.ui.graphics.TransformOrigin((crop.ox + 1f) / 2f, (crop.oy + 1f) / 2f) },
                contentScale = if (bg.fit == "stretch") ContentScale.FillBounds else ContentScale.Crop,
                alignment = androidx.compose.ui.BiasAlignment(crop.ox, crop.oy),
                colorFilter = if (k < 1f) ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 0f, 1f, 0f))) else null,
            )
            }
        }
    }
}
