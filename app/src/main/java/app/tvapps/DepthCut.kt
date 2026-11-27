package app.tvapps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Расчёты по карте глубины. Без Android, чтобы проверяться обычными тестами. */
internal object DepthMath {
    /** Растянуть значения в 0..1. */
    fun normalize(d: FloatArray): FloatArray {
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (v in d) { if (v < mn) mn = v; if (v > mx) mx = v }
        val span = maxOf(1e-6f, mx - mn)
        return FloatArray(d.size) { (d[it] - mn) / span }
    }

    /** Граница между ближним и дальним (метод Оцу) для значений 0..1. */
    fun otsu(d: FloatArray): Float {
        val hist = IntArray(256)
        for (v in d) hist[(v * 255f).toInt().coerceIn(0, 255)]++
        var total = 0.0
        for (i in 0 until 256) total += hist[i] * ((i + 0.5) / 256)
        var count = 0
        var sum = 0.0
        var best = -1.0
        var first = 127
        var last = 127
        for (i in 0 until 255) {
            count += hist[i]
            sum += hist[i] * ((i + 0.5) / 256)
            if (count == 0) continue
            if (count == d.size) break
            val a = sum / count
            val b = (total - sum) / (d.size - count)
            val between = count.toDouble() * (d.size - count) * (a - b) * (a - b)
            // в пустом промежутке между группами значения равны: берётся его середина, а не край ближней группы
            if (between > best) { best = between; first = i; last = i } else if (between == best) last = i
        }
        return ((first + last) / 2f + 1f) / 256f
    }

    /** Какая доля прямоугольника (в долях картинки) отмечена [near]. */
    fun share(w: Int, h: Int, left: Float, top: Float, right: Float, bottom: Float, near: (Int) -> Boolean): Float {
        val x0 = (w * left).toInt().coerceIn(0, w - 1)
        val x1 = (w * right).toInt().coerceIn(x0 + 1, w)
        val y0 = (h * top).toInt().coerceIn(0, h - 1)
        val y1 = (h * bottom).toInt().coerceIn(y0 + 1, h)
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) if (near(y * w + x)) n++
        return n.toFloat() / ((x1 - x0) * (y1 - y0))
    }

    /**
     * Закрыт бывает только низ цифр (это держит отрисовка в BigClock), и то не весь (слово владелицы 26.11.2026: «слегонца низ и то не весь»):
     * в узкой полоске у низа цифр ([cover] их высоты). Если по обычной границе она закрыта почти целиком, впереди оставляется
     * только самое ближнее, около [part] площади. Возвращает сдвиг
     * границы в сотых (0 или меньше). Если ближнее там одинаково по глубине и делить нечего, берётся обычная граница.
     */
    fun autoLevel(depth: FloatArray, w: Int, h: Int, clock: FloatArray, cover: Float, part: Float = 0.55f): Int {
        val base = otsu(depth)
        val line = clock[3] - cover * (clock[3] - clock[1])
        val x0 = (w * clock[0]).toInt().coerceIn(0, w - 1)
        val x1 = (w * clock[2]).toInt().coerceIn(x0 + 1, w)
        val y0 = (h * line).toInt().coerceIn(0, h - 1)
        val y1 = (h * clock[3]).toInt().coerceIn(y0 + 1, h)
        // если по обычной границе (по очертаниям предметов) полоска закрыта не целиком, лучше неё ничего нет: край остаётся настоящим
        if (share(w, h, clock[0], line, clock[2], clock[3]) { depth[it] > base } <= 0.7f) return 0
        val band = FloatArray((x1 - x0) * (y1 - y0)) { depth[(y0 + it / (x1 - x0)) * w + x0 + it % (x1 - x0)] }
        band.sort()
        val thr = maxOf(base, band[(band.size * (1f - part)).toInt().coerceIn(0, band.size - 1)])
        if (share(w, h, clock[0], line, clock[2], clock[3]) { depth[it] > thr } < 0.08f) return 0
        return -kotlin.math.ceil((thr - base) * 100f).toInt().coerceIn(0, 96)
    }

    /**
     * Перевод прямоугольника из долей экрана в доли картинки: так же, как фон рисуется на «Доме» (обрезка по экрану или растяжение,
     * сдвиг [ox], [oy] от -1 до 1 и увеличение [zoom] вокруг точки сдвига).
     */
    fun toPicture(rect: FloatArray, boxW: Float, boxH: Float, imgW: Float, imgH: Float, stretch: Boolean, ox: Float, oy: Float, zoom: Float): FloatArray {
        val k = maxOf(boxW / imgW, boxH / imgH)
        val cw = if (stretch) boxW else imgW * k
        val ch = if (stretch) boxH else imgH * k
        val px = (ox + 1f) / 2f
        val py = (oy + 1f) / 2f
        val z = maxOf(0.1f, zoom)
        fun x(f: Float) = ((px * boxW + (f * boxW - px * boxW) / z - (boxW - cw) * px) / cw).coerceIn(0f, 1f)
        fun y(f: Float) = ((py * boxH + (f * boxH - py * boxH) / z - (boxH - ch) * py) / ch).coerceIn(0f, 1f)
        return floatArrayOf(x(rect[0]), y(rect[1]), x(rect[2]), y(rect[3]))
    }

    /** Увеличение или уменьшение картинки из чисел с плавным переходом между соседями. */
    fun resize(src: FloatArray, sw: Int, sh: Int, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val fy = ((y + 0.5f) * sh / h - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = minOf(sh - 1, y0 + 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) * sw / w - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = minOf(sw - 1, x0 + 1)
                val tx = fx - x0
                val top = src[y0 * sw + x0] * (1 - tx) + src[y0 * sw + x1] * tx
                val bot = src[y1 * sw + x0] * (1 - tx) + src[y1 * sw + x1] * tx
                out[y * w + x] = top * (1 - ty) + bot * ty
            }
        }
        return out
    }

    /** Среднее по квадрату со стороной 2r+1 (за краем повторяется крайнее значение). */
    fun box(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val k = (2 * r + 1).toFloat()
        val mid = FloatArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var s = 0.0
            for (i in -r..r) s += a[row + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                mid[row + x] = (s / k).toFloat()
                s += a[row + minOf(w - 1, x + r + 1)] - a[row + maxOf(0, x - r)]
            }
        }
        val out = FloatArray(w * h)
        for (x in 0 until w) {
            var s = 0.0
            for (i in -r..r) s += mid[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = (s / k).toFloat()
                s += mid[minOf(h - 1, y + r + 1) * w + x] - mid[maxOf(0, y - r) * w + x]
            }
        }
        return out
    }

    /** «Направляемый фильтр»: край маски подтягивается к краям на самой картинке (ветки, листья, волосы). */
    fun guided(guide: FloatArray, src: FloatArray, w: Int, h: Int, r: Int = 8, eps: Float = 1e-3f): FloatArray {
        val n = w * h
        val mi = box(guide, w, h, r)
        val mp = box(src, w, h, r)
        val gp = box(FloatArray(n) { guide[it] * src[it] }, w, h, r)
        val gg = box(FloatArray(n) { guide[it] * guide[it] }, w, h, r)
        val a = FloatArray(n) { (gp[it] - mi[it] * mp[it]) / (gg[it] - mi[it] * mi[it] + eps) }
        val b = FloatArray(n) { mp[it] - a[it] * mi[it] }
        val ma = box(a, w, h, r)
        val mb = box(b, w, h, r)
        return FloatArray(n) { ma[it] * guide[it] + mb[it] }
    }

    /**
     * Маска переднего плана 0..255. [depth] 0..1 (больше значит ближе) размером dw на dh, [gray] яркость картинки w на h.
     * [level]: сколько брать вперёд от границы, в сотых: больше нуля перед часами оказывается больше картинки, меньше нуля меньше.
     */
    fun mask(depth: FloatArray, dw: Int, dh: Int, gray: FloatArray, w: Int, h: Int, level: Int): IntArray {
        val thr = (otsu(depth) - level / 100f).coerceIn(0.02f, 0.98f)
        val big = resize(depth, dw, dh, w, h)
        val soft = FloatArray(w * h) { ((big[it] - thr) / 0.05f + 0.5f).coerceIn(0f, 1f) }
        val g = guided(gray, soft, w, h)
        return IntArray(w * h) { (((g[it] - 0.5f) * 2.5f + 0.5f).coerceIn(0f, 1f) * 255f + 0.5f).toInt() }
    }
}

/** Передний план картинки. [bottomAt]: куда по высоте (доля фона) поставить низ цифр, чтобы они сели за линию верхушек; null: не двигать. */
internal class FrontLayer(val image: ImageBitmap, val bottomAt: Float?, val live: LiveScene? = null)

/** Где на экране стоят цифры (доли фона) и как нарисован фон: по этому «авто» следит, чтобы время читалось. */
internal data class ClockPlace(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val boxW: Int, val boxH: Int, val stretch: Boolean, val ox: Float, val oy: Float, val zoom: Float, val cover: Float,
)

/**
 * 26.11.2026 (слово владелицы: «чтобы любую картинку на раб стол эти часы делили на впереди и вдалеке, а сами посередине»).
 * Нейросеть MiDaS v2.1 small (лицензия MIT, авторы isl-org) оценивает на самой приставке, что на картинке ближе, а что дальше.
 * Ни нейросеть, ни запускающая её библиотека в APK не лежат: оба файла скачиваются один раз по нажатию в настройках часов
 * (нейросеть из релиза MiDaS, библиотека LiteRT из хранилища Google, Apache 2.0) и проверяются по SHA-256.
 * Картинки никуда не отправляются. Результат для каждой картинки считается один раз и запоминается.
 */
internal object DepthCut {
    private const val URL_MODEL = "https://github.com/isl-org/MiDaS/releases/download/v2_1/model_opt.tflite"
    private const val SHA_MODEL = "93d871071edff1218973ce25ee27ce95ccd20450c70a55e1b89efa3f5a772cdd"
    private const val SIZE_MODEL = 66_338_288L
    // архив библиотеки: тот же файл, из которого собирается программа (контрольная сумма совпадает с копией сборщика)
    private const val URL_ENGINE = "https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert/1.0.1/litert-1.0.1.aar"
    private const val SHA_ENGINE = "467679820f836fe70418f01c9f6689f328f79fbf2cbabe197eab70b209b43e8d"
    private const val SIZE_ENGINE = 6_487_444L
    /** Размер самой библиотеки внутри архива для каждого процессора. */
    private val ENGINE_SO = mapOf("arm64-v8a" to 3_549_384L, "armeabi-v7a" to 2_366_508L, "x86" to 4_610_960L, "x86_64" to 4_687_272L)
    private const val SIDE = 256

    /** Меняется, когда нейросеть скачана или удалена: часы пересчитывают передний план. */
    var ver by mutableIntStateOf(0)
        private set
    /** Скачивание: -1 не идёт, иначе проценты. */
    var progress by mutableIntStateOf(-1)
        private set
    var error by mutableStateOf("")
        private set

    private fun dir(ctx: Context) = File(ctx.filesDir, "depth").apply { mkdirs() }
    private fun model(ctx: Context) = File(dir(ctx), "midas_small.tflite")
    private fun engine(ctx: Context) = File(dir(ctx), "litert-1.0.1.so")

    /** Процессор, под который запущена программа (у 64-битной приставки с 32-битной системой это 32-битный). */
    private val abi: String? by lazy {
        val own = if (android.os.Process.is64Bit()) android.os.Build.SUPPORTED_64_BIT_ABIS else android.os.Build.SUPPORTED_32_BIT_ABIS
        own.firstOrNull { it in ENGINE_SO }
    }
    val supported: Boolean get() = abi != null

    private fun modelReady(ctx: Context) = model(ctx).length() == SIZE_MODEL
    private fun engineReady(ctx: Context) = engine(ctx).length() == ENGINE_SO[abi]
    fun ready(ctx: Context): Boolean = supported && modelReady(ctx) && engineReady(ctx)

    private var loaded = false

    /** Библиотека загружается из скачанного файла; её классы в программе находят уже загруженный код сами. */
    @Synchronized
    private fun loadEngine(ctx: Context): Boolean {
        if (!loaded) loaded = runCatching { System.load(engine(ctx).absolutePath) }
            .onFailure { android.util.Log.w("AppMenu", "depth engine: $it") }.isSuccess
        return loaded
    }

    /** Скачать файл [url] в [to] и проверить размер и SHA-256. [done]: сколько уже скачано до этого файла, [total]: всего. */
    private fun fetch(ctx: Context, url: String, size: Long, sha: String, to: File, done: Long, total: Long) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        if (c.responseCode !in 200..299) throw java.io.IOException("HTTP " + c.responseCode)
        val md = MessageDigest.getInstance("SHA-256")
        var got = 0L
        c.inputStream.use { input ->
            to.outputStream().use { out ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    got += n
                    if (got > size) throw java.io.IOException(ctx.getString(R.string.bc_nn_bad))
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                    val p = ((done + got) * 100 / total).toInt().coerceAtMost(99)
                    if (p != progress) progress = p
                }
            }
        }
        val sum = md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (got != size || sum != sha) throw java.io.IOException(ctx.getString(R.string.bc_nn_bad))
    }

    private fun place(ctx: Context, part: File, to: File) {
        to.delete()
        if (!part.renameTo(to)) throw java.io.IOException(ctx.getString(R.string.bc_nn_bad))
    }

    fun download(ctx: Context) {
        val cpu = abi ?: return
        if (progress >= 0) return
        progress = 0
        error = ""
        val app = ctx.applicationContext
        Thread {
            val part = File(dir(app), "download.part")
            val unpacked = File(dir(app), "engine.part")
            try {
                if (dir(app).usableSpace < SIZE_MODEL + 2 * SIZE_ENGINE + 20_000_000L) throw java.io.IOException(app.getString(R.string.bc_nn_space))
                val needEngine = !engineReady(app)
                val needModel = !modelReady(app)
                val total = (if (needEngine) SIZE_ENGINE else 0L) + (if (needModel) SIZE_MODEL else 0L)
                var done = 0L
                if (needEngine) {
                    fetch(app, URL_ENGINE, SIZE_ENGINE, SHA_ENGINE, part, done, total)
                    done += SIZE_ENGINE
                    java.util.zip.ZipFile(part).use { zip ->
                        val entry = zip.getEntry("jni/$cpu/libtensorflowlite_jni.so") ?: throw java.io.IOException(app.getString(R.string.bc_nn_bad))
                        zip.getInputStream(entry).use { input -> unpacked.outputStream().use { input.copyTo(it) } }
                    }
                    if (unpacked.length() != ENGINE_SO[cpu]) throw java.io.IOException(app.getString(R.string.bc_nn_bad))
                    unpacked.setReadOnly()
                    place(app, unpacked, engine(app))
                }
                if (needModel) {
                    fetch(app, URL_MODEL, SIZE_MODEL, SHA_MODEL, part, done, total)
                    place(app, part, model(app))
                }
                ver++
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                part.delete()
                unpacked.delete()
                progress = -1
            }
        }.start()
    }

    /** Убрать нейросеть и всё посчитанное: освобождает место. */
    fun remove(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
        synchronized(this) { cacheKey = ""; cache = null; wallKey = ""; wallCache = null }
        LiveWall.scene = null
        ver++
    }

    private var cacheKey = ""
    private var cache: FrontLayer? = null

    private fun mapFile(ctx: Context, picture: File): File {
        val id = MessageDigest.getInstance("SHA-256").digest((picture.path + "|" + picture.lastModified() + "|" + picture.length()).toByteArray())
            .take(10).joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(dir(ctx), "map_$id.bin")
    }

    /** Готовая карта глубины из файла обоев .amwall: для этой картинки нейросеть не нужна. */
    fun putDepth(ctx: Context, picture: File, depth: ByteArray) {
        if (depth.size == SIDE * SIDE) mapFile(ctx, picture).writeBytes(depth)
        synchronized(this) { cacheKey = ""; cache = null; wallKey = ""; wallCache = null }
        ver++
    }

    fun hasDepth(ctx: Context, picture: File) = mapFile(ctx, picture).length() == (SIDE * SIDE).toLong()

    /** Карта глубины картинки для сохранения в файл обоев; null: посчитать нечем. */
    @Synchronized
    fun depthBytes(ctx: Context, picture: File): ByteArray? {
        if (!hasDepth(ctx, picture)) {
            if (!ready(ctx) || !loadEngine(ctx)) return null
            val small = BitmapFactory.decodeFile(picture.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
            runCatching { depthOf(ctx, picture, small) }.getOrElse { return null }
        }
        return mapFile(ctx, picture).takeIf { it.length() == (SIDE * SIDE).toLong() }?.readBytes()
    }

    /** Можно ли разделить эту картинку: карта уже есть или есть чем её посчитать. */
    private fun canSplit(ctx: Context, picture: File) = hasDepth(ctx, picture) || (ready(ctx) && loadEngine(ctx))

    /** Карта глубины 256 на 256 для картинки: из запомненного или счёт нейросетью. */
    private fun depthOf(ctx: Context, picture: File, small: Bitmap): FloatArray {
        val saved = mapFile(ctx, picture)
        if (saved.length() == (SIDE * SIDE).toLong()) {
            val b = saved.readBytes()
            return FloatArray(b.size) { (b[it].toInt() and 255) / 255f }
        }
        val scaled = Bitmap.createScaledBitmap(small, SIDE, SIDE, true)
        val px = IntArray(SIDE * SIDE)
        scaled.getPixels(px, 0, SIDE, 0, 0, SIDE, SIDE)
        val input = ByteBuffer.allocateDirect(SIDE * SIDE * 3 * 4).order(ByteOrder.nativeOrder())
        for (c in px) {
            input.putFloat((((c shr 16) and 255) / 255f - 0.485f) / 0.229f)
            input.putFloat((((c shr 8) and 255) / 255f - 0.456f) / 0.224f)
            input.putFloat(((c and 255) / 255f - 0.406f) / 0.225f)
        }
        input.rewind()
        val output = ByteBuffer.allocateDirect(SIDE * SIDE * 4).order(ByteOrder.nativeOrder())
        val options = org.tensorflow.lite.Interpreter.Options().setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        org.tensorflow.lite.Interpreter(model(ctx), options).use { it.run(input, output) }
        output.rewind()
        val raw = FloatArray(SIDE * SIDE)
        output.asFloatBuffer().get(raw)
        val d = DepthMath.normalize(raw)
        dir(ctx).listFiles()?.filter { it.name.startsWith("map_") }?.sortedByDescending { it.lastModified() }?.drop(11)?.forEach { it.delete() }
        saved.writeBytes(ByteArray(d.size) { (d[it] * 255f + 0.5f).toInt().toByte() })
        return d
    }

    private fun behindTrees(key: String, source: Bitmap, line: FloatArray, gray: FloatArray, depthNow: FloatArray, mw: Int, mh: Int, clock: FloatArray, place: ClockPlace): FrontLayer {
        // работаем не крупнее 1600 по ширине: на слабой приставке памяти немного
        val full = if (source.width > 1600) Bitmap.createScaledBitmap(source, 1600, source.height * 1600 / source.width, true) else source
        val w = full.width
        val h = full.height
        val small = TreeScene.mask(line, gray, mw, mh)
        val smallTops = TreeScene.tops(small, mw, mh, line)
        val bottom = TreeScene.digitBottom(smallTops, mw, mh, clock, place.cover) / mh   // доля высоты картинки
        val pix = IntArray(w * h)
        full.getPixels(pix, 0, w, 0, 0, w, h)
        val grown = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
        grown.setPixels(IntArray(mw * mh) { (small[it] shl 24) }, 0, mw, 0, 0, mw, mh)
        val big = Bitmap.createScaledBitmap(grown, w, h, true)
        val mask = IntArray(w * h)
        big.getPixels(mask, 0, w, 0, 0, w, h)
        for (i in mask.indices) mask[i] = mask[i] ushr 24
        val tops = DepthMath.resize(smallTops, mw, 1, w, 1).also { for (x in it.indices) it[x] = it[x] * h / mh }
        val high = (clock[3] - clock[1]) * h
        TreeScene.planClone(tops, w, clock, bottom * h, high)?.let { TreeScene.applyClone(pix, mask, w, h, tops, it) }
        // верх переднего плана после вставки
        for (x in 0 until w) {
            var y = maxOf(0, tops[x].toInt() - (high * 0.5f).toInt())
            while (y < h - 1 && mask[y * w + x] <= 127) y++
            tops[x] = minOf(tops[x], y.toFloat())
        }
        val live = assemble(key, full, pix, mask, tops, gray, depthNow, mw, mh)
        // перевод найденной высоты с картинки обратно на экран: перевод по высоте прямой, поэтому хватает двух точек
        fun onPic(f: Float) = DepthMath.toPicture(floatArrayOf(0f, f, 1f, f), place.boxW.toFloat(), place.boxH.toFloat(),
            w.toFloat(), h.toFloat(), place.stretch, place.ox, place.oy, place.zoom)[1]
        val a = onPic(0.4f)
        val b = onPic(0.6f)
        val bottomAt = if (b - a > 1e-4f) 0.4f + (bottom - a) * 0.2f / (b - a) else null
        return FrontLayer(live.front.asImageBitmap(), bottomAt, live)
    }

    /** Слои для движения из готовых точек картинки и маски переднего плана. */
    private fun assemble(key: String, full: Bitmap, pix: IntArray, mask: IntArray, tops: FloatArray, gray: FloatArray, depthNow: FloatArray, mw: Int, mh: Int): LiveScene {
        val w = full.width
        val h = full.height
        val scene = Bitmap.createBitmap(pix, w, h, Bitmap.Config.ARGB_8888)
        val front = Bitmap.createBitmap(IntArray(w * h) { (pix[it] and 0x00FFFFFF) or (mask[it] shl 24) }, w, h, Bitmap.Config.ARGB_8888)
        val back = Bitmap.createBitmap(TreeScene.backdrop(pix, mask, w, h), w, h, Bitmap.Config.ARGB_8888)
        val (sway, water) = TreeScene.motionMap(pix, mask, w, h, tops, LiveWall.COLS, LiveWall.ROWS)
        // облака плывут по небу позади гор и всего ближнего
        var cloudMap: Bitmap? = null
        var land: Bitmap? = null
        var skyHeight = 0
        val smallPix = IntArray(mw * mh)
        Bitmap.createScaledBitmap(full, mw, mh, true).getPixels(smallPix, 0, mw, 0, 0, mw, mh)
        val sky = TreeScene.skyMask(DepthMath.resize(depthNow, SIDE, SIDE, mw, mh), smallPix, mw, mh)
        if (sky != null) {
            var low = 0
            for (i in sky.indices) if (sky[i] != 0) low = maxOf(low, i / mw)
            skyHeight = ((low + 1) * h / mh).coerceIn(h / 8, h)
            val soft = DepthMath.guided(gray, FloatArray(mw * mh) { sky[it] / 255f }, mw, mh, 4, 4e-4f)
            val cover = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
            cover.setPixels(IntArray(mw * mh) { ((((soft[it] - 0.5f) * 2.5f + 0.5f).coerceIn(0f, 1f)) * 255f).toInt() shl 24 }, 0, mw, 0, 0, mw, mh)
            val skyBig = IntArray(w * h)
            Bitmap.createScaledBitmap(cover, w, h, true).getPixels(skyBig, 0, w, 0, 0, w, h)
            val behind = IntArray(w * h)
            back.getPixels(behind, 0, w, 0, 0, w, h)
            land = Bitmap.createBitmap(IntArray(w * h) { (behind[it] and 0x00FFFFFF) or ((255 - (skyBig[it] ushr 24)) shl 24) }, w, h, Bitmap.Config.ARGB_8888)
            val cw = 640
            val ch = maxOf(40, cw * skyHeight / w)
            val a = TreeScene.clouds(cw, ch)
            cloudMap = Bitmap.createBitmap(IntArray(cw * ch) { (a[it] shl 24) or 0x00FFFBF6 }, cw, ch, Bitmap.Config.ARGB_8888)
        }
        return LiveScene(key, scene, front, back, sway, water, cloudMap, land, skyHeight)
    }

    private var wallKey = ""
    private var wallCache: LiveScene? = null

    /** «3D-обои» без часов: передний план по глубине целиком. null: нейросети нет или делить на картинке нечего. */
    @Synchronized
    fun wallpaper(ctx: Context, path: String): LiveScene? {
        val f = File(path)
        if (!canSplit(ctx, f)) return null
        val key = path + "|" + f.lastModified() + "|" + f.length() + "|wall"
        if (key == wallKey) return wallCache
        val result = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1600) sample *= 2
            var full = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
            if (full.width > 1600) full = Bitmap.createScaledBitmap(full, 1600, full.height * 1600 / full.width, true)
            val w = full.width
            val h = full.height
            val k = minOf(1f, 640f / maxOf(w, h))
            val mw = maxOf(1, (w * k).toInt())
            val mh = maxOf(1, (h * k).toInt())
            val small = Bitmap.createScaledBitmap(full, mw, mh, true)
            val px = IntArray(mw * mh)
            small.getPixels(px, 0, mw, 0, 0, mw, mh)
            val gray = FloatArray(mw * mh) { val c = px[it]; (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f }
            val depth = depthOf(ctx, f, small)
            val m = DepthMath.mask(depth, SIDE, SIDE, gray, mw, mh, 0)
            val share = m.count { it > 127 }.toFloat() / m.size
            if (share < 0.04f || share > 0.96f) null
            else {
                val grown = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
                grown.setPixels(IntArray(mw * mh) { m[it] shl 24 }, 0, mw, 0, 0, mw, mh)
                val mask = IntArray(w * h)
                Bitmap.createScaledBitmap(grown, w, h, true).getPixels(mask, 0, w, 0, 0, w, h)
                for (i in mask.indices) mask[i] = mask[i] ushr 24
                val pix = IntArray(w * h)
                full.getPixels(pix, 0, w, 0, 0, w, h)
                val tops = FloatArray(w) { x -> var y = 0; while (y < h - 1 && mask[y * w + x] <= 127) y++; y.toFloat() }
                assemble(key, full, pix, mask, tops, gray, depth, mw, mh)
            }
        } catch (e: Throwable) {
            android.util.Log.w("AppMenu", "depth wallpaper: $e")
            return null
        }
        wallCache = result
        wallKey = key
        return result
    }

    /** Слой переднего плана картинки (прозрачный там, где дальний план). null: нейросети нет или не получилось. */
    @Synchronized
    fun layer(ctx: Context, path: String, place: ClockPlace): FrontLayer? {
        val f = File(path)
        if (!canSplit(ctx, f)) return null
        val key = path + "|" + f.lastModified() + "|" + f.length() + "|" + place
        if (key == cacheKey) return cache
        val result = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1920) sample *= 2
            val full = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
            // маска считается на уменьшенной копии: 640 по длинной стороне
            val long = maxOf(full.width, full.height)
            val k = minOf(1f, 640f / long)
            val mw = maxOf(1, (full.width * k).toInt())
            val mh = maxOf(1, (full.height * k).toInt())
            val small = Bitmap.createScaledBitmap(full, mw, mh, true)
            val px = IntArray(mw * mh)
            small.getPixels(px, 0, mw, 0, 0, mw, mh)
            val gray = FloatArray(mw * mh) { val c = px[it]; (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f }
            val depth = depthOf(ctx, f, small)
            // граница подбирается под место цифр, чтобы край перекрытия шёл по очертаниям предметов
            val onPicture = DepthMath.toPicture(floatArrayOf(place.left, place.top, place.right, place.bottom), place.boxW.toFloat(), place.boxH.toFloat(),
                full.width.toFloat(), full.height.toFloat(), place.stretch, place.ox, place.oy, place.zoom)
            // 27.11.2026: сначала ищется линия верхушек под часами (лес, кусты, гребень): тогда впереди она целиком, с настоящими
            // ветками, а часы садятся за неё. Если такой линии нет (сплошной предмет), впереди остаётся самое ближнее у низа цифр
            val line = TreeScene.skyline(DepthMath.resize(depth, SIDE, SIDE, mw, mh), gray, mw, mh, onPicture)
            if (line != null) behindTrees(key, full, line, gray, depth, mw, mh, onPicture, place)
            else {
                val m = DepthMath.mask(depth, SIDE, SIDE, gray, mw, mh, DepthMath.autoLevel(depth, SIDE, SIDE, onPicture, place.cover))
                val mask = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
                mask.setPixels(IntArray(mw * mh) { m[it] shl 24 }, 0, mw, 0, 0, mw, mh)
                val out = Bitmap.createBitmap(full.width, full.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                canvas.drawBitmap(full, 0f, 0f, null)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
                canvas.drawBitmap(mask, null, android.graphics.Rect(0, 0, full.width, full.height), paint)
                FrontLayer(out.asImageBitmap(), null)
            }
        } catch (e: Throwable) {   // в том числе нехватка памяти на слабой приставке: тогда остаётся простой способ по небу
            android.util.Log.w("AppMenu", "depth: $e")
            return null
        }
        cache = result
        cacheKey = key
        return result
    }
}
