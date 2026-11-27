package app.tvapps

import kotlin.math.abs

/**
 * 26.11.2026 (слово владелицы: часы «за деревьями», и минуты тоже; «20 реально, а 45 не из нашего района»).
 * Расчёты без Android, чтобы проверяться обычными тестами: линия верхушек под часами, настоящий передний план по ней,
 * и, если под минутами деревья ниже, более высокие деревья из этой же картинки позади передних.
 */
internal object TreeScene {
    private fun median(v: FloatArray, from: Int, to: Int): Float {
        val c = v.copyOfRange(from.coerceAtLeast(0), to.coerceAtMost(v.size))
        c.sort()
        return c[c.size / 2]
    }

    private fun percentile(v: FloatArray, from: Int, to: Int, p: Float): Float {
        val c = v.copyOfRange(from.coerceAtLeast(0), to.coerceAtMost(v.size))
        c.sort()
        return c[((c.size - 1) * p).toInt()]
    }

    private fun smooth(v: FloatArray, r: Int): FloatArray = FloatArray(v.size) { median(v, it - r, it + r + 1) }

    /**
     * Линия, где сверху начинается передний план, по каждому столбцу (в точках картинки [w] на [h]).
     * [depth] 0..1 того же размера, [gray] яркость, [clock] место цифр в долях картинки.
     * В столбце снизу вверх ищется первый заметный скачок глубины, затем край уточняется по самой картинке (контур веток).
     * null: ровной линии под часами нет (сплошной предмет, пусто или всё вразнобой).
     */
    fun skyline(depth: FloatArray, gray: FloatArray, w: Int, h: Int, clock: FloatArray): FloatArray? {
        val high = (clock[3] - clock[1]) * h
        if (high < 8f) return null
        val x0 = (w * clock[0]).toInt().coerceIn(0, w - 8)
        val x1 = (w * clock[2]).toInt().coerceIn(x0 + 8, w)
        val k = maxOf(2, (high * 0.04f).toInt())
        val top = (h * clock[1] + high * 0.35f).toInt().coerceIn(k, h - 1 - k)
        val bottom = (h * clock[3] + high * 0.35f).toInt().coerceIn(top, h - 1 - k)
        val found = FloatArray(w) { -1f }
        for (x in 0 until w) {
            var y = bottom
            while (y > top) {
                if (depth[(y + k) * w + x] - depth[(y - k) * w + x] >= 0.08f) {
                    var best = 0f
                    var by = y
                    var yy = y
                    val stop = maxOf(top - 1, y - 3 * k)
                    while (yy > stop && yy - k >= 0) {
                        val s = depth[(yy + k) * w + x] - depth[(yy - k) * w + x]
                        if (s > best) { best = s; by = yy }
                        yy--
                    }
                    found[x] = by.toFloat()
                    break
                }
                y--
            }
        }
        val inClock = ArrayList<Float>()
        for (x in x0 until x1) if (found[x] >= 0f) inClock.add(found[x])
        if (inClock.size < (x1 - x0) * 55 / 100) return null
        inClock.sort()
        if (inClock[inClock.size * 3 / 4] - inClock[inClock.size / 4] > high * 0.3f) return null
        val mid = inClock[inClock.size / 2]
        val coarse = smooth(FloatArray(w) { if (found[it] >= 0f) found[it] else mid }, 6)
        // уточнение по картинке: первый резкий перепад яркости чуть выше или ниже найденной линии
        val line = coarse.copyOf()
        for (x in 0 until w) {
            val a = maxOf(3, (coarse[x] - 0.05f * high).toInt())
            val b = minOf(h - 4, (coarse[x] + 0.40f * high).toInt())
            var y = a
            while (y < b) {
                if (abs(gray[(y + 2) * w + x] - gray[(y - 2) * w + x]) >= 0.17f) { line[x] = y.toFloat(); break }
                y++
            }
        }
        return smooth(line, 4)
    }

    /** Передний план ниже линии: край «прилипает» к настоящим веткам на картинке. Возвращает 0..255. */
    fun mask(line: FloatArray, gray: FloatArray, w: Int, h: Int): IntArray {
        val soft = FloatArray(w * h) { (((it / w) - (line[it % w] - 1f)) / 4f + 0.5f).coerceIn(0f, 1f) }
        val g = DepthMath.guided(gray, soft, w, h, 8, 4e-4f)
        return IntArray(w * h) { (((g[it] - 0.5f) * 2.5f + 0.5f).coerceIn(0f, 1f) * 255f + 0.5f).toInt() }
    }

    /** Верх переднего плана по столбцам (по готовой маске), начиная чуть выше линии. */
    fun tops(mask: IntArray, w: Int, h: Int, line: FloatArray): FloatArray = FloatArray(w) { x ->
        var y = maxOf(0, line[x].toInt() - h / 20)
        while (y < h - 1 && mask[y * w + x] <= 127) y++
        y.toFloat()
    }

    /** Где должен оказаться низ цифр (в точках маски): чуть ниже общих верхушек, чтобы они заходили на цифры. */
    fun digitBottom(tops: FloatArray, w: Int, h: Int, clock: FloatArray, cover: Float): Float {
        val x0 = (w * clock[0]).toInt()
        val x1 = (w * clock[2]).toInt()
        return percentile(tops, x0, x1, 0.35f) + cover * 0.65f * (clock[3] - clock[1]) * h
    }

    /** Похож ли край на кроны (изрезанный), а не на гладкий предмет: только тогда деревья можно переставлять. */
    fun rough(tops: FloatArray, x0: Int, x1: Int): Float {
        var s = 0f
        var n = 0
        for (x in maxOf(1, x0) until minOf(tops.size, x1)) { s += abs(tops[x] - tops[x - 1]); n++ }
        return if (n == 0) 0f else s / n
    }

    /** Откуда и насколько выше брать деревья под минуты. */
    class Clone(val start: Int, val width: Int, val source: Int, val shift: Int)

    /**
     * Нужны ли под минутами более высокие деревья. [tops] и всё остальное в точках картинки шириной [w].
     * [clock] место цифр в долях, [bottom] низ цифр в точках, [high] высота цифр в точках.
     */
    fun planClone(tops: FloatArray, w: Int, clock: FloatArray, bottom: Float, high: Float): Clone? {
        val cw = (clock[2] - clock[0]) * w
        val start = (clock[0] * w + cw * 0.56f).toInt().coerceIn(0, w - 4)
        val width = (cw * 0.44f + cw * 0.14f).toInt().coerceAtMost(w - start)
        if (width < 24) return null
        val old = percentile(tops, start, start + width, 0.35f)
        val need = old - (bottom - 0.08f * high)
        if (need <= 0.03f * high) return null
        if (rough(tops, 0, w) < 0.35f) return null
        val rise = minOf(need + 0.05f * high, 0.30f * high)
        // самый «высокий» участок леса той же ширины, который не пересекается с местом вставки
        var best = -1
        var bestTop = Float.MAX_VALUE
        var s = 0
        while (s + width <= w) {
            if (s + width <= start - width / 8) {
                val t = percentile(tops, s, s + width, 0.35f)
                if (t < bestTop) { bestTop = t; best = s }
            }
            s += maxOf(1, width / 24)
        }
        if (best < 0) return null
        val shift = (bestTop - (old - rise)).toInt()
        return Clone(start, width, best, shift)
    }

    /**
     * Вставляет участок леса [c] позади передних деревьев: виден только выше прежних верхушек, края неровные.
     * [pix] и [mask] (0..255) размером [w] на [h] меняются на месте.
     */
    fun applyClone(pix: IntArray, mask: IntArray, w: Int, h: Int, tops: FloatArray, c: Clone) {
        val srcPix = pix.copyOf()
        val srcMask = mask.copyOf()
        val unit = maxOf(0.15f, w / 1280f)
        val rnd = java.util.Random(3)
        val step = maxOf(2, (6 * unit).toInt())
        val knots = FloatArray(h / step + 2) { (rnd.nextGaussian() * 9 * unit).toFloat() }
        for (y in 0 until h) {
            val sy = y + c.shift
            if (sy < 0 || sy >= h) continue
            val i = y / step
            val wob = knots[i] + (knots[i + 1] - knots[i]) * ((y % step) / step.toFloat())
            for (x in c.start until minOf(w, c.start + c.width)) {
                val sx = c.source + (x - c.start)
                if (sx < 0 || sx >= w) continue
                val edge = (minOf(x + wob - c.start, c.start + c.width - (x + wob)) / (26f * unit)).coerceIn(0f, 1f)
                val low = ((tops[x] + 30f * unit - y) / (14f * unit)).coerceIn(0f, 1f)
                val a = srcMask[sy * w + sx] / 255f * edge * low
                if (a <= 0f) continue
                val above = ((tops[x] + 12f * unit - y) / (12f * unit)).coerceIn(0f, 1f)
                val k = a * above
                if (k > 0f) pix[y * w + x] = blend(pix[y * w + x], srcPix[sy * w + sx], k)
                val alpha = (a * 255f).toInt()
                if (alpha > mask[y * w + x]) mask[y * w + x] = alpha
            }
        }
    }

    /**
     * Где на картинке небо (0..255): дальше всего по глубине, светлое и не тёплое, достаётся от верхнего края.
     * null: неба наверху мало (тёмный фон, комната, предмет во весь кадр): облака там не нужны.
     */
    fun skyMask(depth: FloatArray, pix: IntArray, w: Int, h: Int): IntArray? {
        fun open(i: Int): Boolean {
            if (depth[i] > 0.08f) return false
            val c = pix[i]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            return 0.299f * r + 0.587f * g + 0.114f * b > 105f && b + 14 >= r
        }
        val sky = IntArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        for (x in 0 until w) if (open(x)) { sky[x] = 255; queue[tail++] = x }
        while (head < tail) {
            val p = queue[head++]
            val x = p % w
            fun step(q: Int) { if (sky[q] == 0 && open(q)) { sky[q] = 255; queue[tail++] = q } }
            if (x > 0) step(p - 1)
            if (x < w - 1) step(p + 1)
            if (p >= w) step(p - w)
            if (p + w < w * h) step(p + w)
        }
        var top = 0
        for (i in 0 until w * (h / 4)) if (sky[i] != 0) top++
        return if (top < w * (h / 4) / 2) null else sky
    }

    /** Мягкие облака (альфа 0..255) размером [w] на [h]; по ширине стыкуются сами с собой, к низу тают. */
    fun clouds(w: Int, h: Int, seed: Long = 11): IntArray {
        val rnd = java.util.Random(seed)
        val sum = FloatArray(w * h)
        var amp = 1f
        var total = 0f
        for (cells in intArrayOf(4, 9, 19, 40)) {
            val rows = maxOf(2, cells * h * 2 / w + 1)
            val lattice = FloatArray(cells * (rows + 1)) { rnd.nextFloat() }
            for (y in 0 until h) {
                val fy = y.toFloat() * (rows - 1) / h
                val y0 = fy.toInt()
                val ty = (fy - y0).let { it * it * (3 - 2 * it) }
                for (x in 0 until w) {
                    val fx = x.toFloat() * cells / w
                    val x0 = fx.toInt()
                    val tx = (fx - x0).let { it * it * (3 - 2 * it) }
                    val x1 = (x0 + 1) % cells
                    val a = lattice[y0 * cells + x0 % cells] * (1 - tx) + lattice[y0 * cells + x1] * tx
                    val b = lattice[(y0 + 1) * cells + x0 % cells] * (1 - tx) + lattice[(y0 + 1) * cells + x1] * tx
                    sum[y * w + x] += (a * (1 - ty) + b * ty) * amp
                }
            }
            total += amp
            amp *= 0.55f
        }
        return IntArray(w * h) {
            val v = ((sum[it] / total - 0.47f) * 3.4f).coerceIn(0f, 1f)
            val low = (1f - ((it / w).toFloat() / h - 0.65f) / 0.35f).coerceIn(0f, 1f)
            (v * low * 215f).toInt()
        }
    }

    fun blend(a: Int, b: Int, k: Float): Int {
        fun mix(s: Int) = (((a shr s) and 255) + ((((b shr s) and 255) - ((a shr s) and 255)) * k)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /**
     * Задний план без передних деревьев. Дорисовываются только те точки, которые передний план действительно закрывает (с запасом на
     * сдвиг слоёв): цветом того, что видно прямо над ними, усреднённым по соседним столбцам, чтобы не было полос.
     * Там, где переднего плана нет, картинка остаётся как есть.
     */
    fun backdrop(pix: IntArray, mask: IntArray, w: Int, h: Int): IntArray {
        val out = pix.copyOf()
        val gap = maxOf(3, h / 100)
        val deep = h / 7
        val run = maxOf(3, h / 90)
        val first = IntArray(w) { x ->
            var y = 0
            var solid = 0
            while (y < h && solid < run) { solid = if (mask[y * w + x] > 100) solid + 1 else 0; y++ }
            if (solid >= run) y - run else -1
        }
        val red = IntArray(w); val green = IntArray(w); val blue = IntArray(w)
        for (x in 0 until w) if (first[x] >= 0) {
            val c = pix[maxOf(0, first[x] - gap - 1) * w + x]
            red[x] = (c shr 16) and 255; green[x] = (c shr 8) and 255; blue[x] = c and 255
        }
        val reach = maxOf(4, w / 60)
        for (x in 0 until w) {
            if (first[x] < 0) continue
            var r = 0; var g = 0; var b = 0; var n = 0
            for (xx in maxOf(0, x - reach)..minOf(w - 1, x + reach)) if (first[xx] >= 0) { r += red[xx]; g += green[xx]; b += blue[xx]; n++ }
            val fill = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            val from = maxOf(0, first[x] - gap)
            for (y in from until minOf(h, first[x] + deep)) {
                var cover = 0
                var d = -gap
                while (d <= gap) { cover = maxOf(cover, mask[(y + d).coerceIn(0, h - 1) * w + x]); d += gap }
                if (cover > 30) out[y * w + x] = blend(pix[y * w + x], fill, minOf(1f, cover / 160f))
            }
        }
        return out
    }

    /**
     * Что и насколько шевелится, по точкам сетки [cols] на [rows] (узлов на один больше в каждую сторону).
     * Растительность (зелёная или сухая жёлтая, с мелкими деталями) качается, гладкая синяя вода рябит; остальное неподвижно.
     * Возвращает два массива: качание и рябь, 0..1.
     */
    fun motionMap(pix: IntArray, mask: IntArray, w: Int, h: Int, tops: FloatArray, cols: Int, rows: Int): Pair<FloatArray, FloatArray> {
        val sway = FloatArray((cols + 1) * (rows + 1))
        val water = FloatArray((cols + 1) * (rows + 1))
        val rx = maxOf(2, w / cols / 2)
        val ry = maxOf(2, h / rows / 2)
        for (j in 0..rows) for (i in 0..cols) {
            val cx = (i * (w - 1) / cols)
            val cy = (j * (h - 1) / rows)
            var n = 0; var front = 0; var veg = 0; var blue = 0
            var sum = 0f; var sum2 = 0f
            var y = maxOf(0, cy - ry)
            while (y <= minOf(h - 1, cy + ry)) {
                var x = maxOf(0, cx - rx)
                while (x <= minOf(w - 1, cx + rx)) {
                    val c = pix[y * w + x]
                    val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                    val l = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                    n++; sum += l; sum2 += l * l
                    if (mask[y * w + x] > 127) {
                        front++
                        if ((g >= b + 6 && g + 12 >= r) || (r > b + 18 && g > b + 6)) veg++
                        if (b > r + 14) blue++
                    }
                    x += 2
                }
                y += 2
            }
            if (n == 0 || front * 2 < n) continue
            val at = j * (cols + 1) + i
            val rough = kotlin.math.sqrt(maxOf(0f, sum2 / n - (sum / n) * (sum / n)))
            val vegShare = veg.toFloat() / n
            if (vegShare > 0.3f && rough > 0.035f) {
                // у макушек качание сильнее, глубже в чаще слабее, но не пропадает
                val below = ((cy - tops[cx]) / (0.2f * h)).coerceIn(0f, 1f)
                sway[at] = (1f - 0.65f * below) * ((vegShare - 0.3f) / 0.3f).coerceIn(0f, 1f)
            } else if (blue.toFloat() / n > 0.55f && rough < 0.09f && cy > h / 2) water[at] = 1f
        }
        return sway to water
    }
}
