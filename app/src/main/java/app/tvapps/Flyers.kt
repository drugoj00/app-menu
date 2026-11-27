package app.tvapps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 27.11.2026 (слово владелицы: «не заметно, сделай летающие от ветра предметы, деньги там или ещё что»).
 * То, что носит ветер по живой картинке: деньги, листья, снег, лепестки. Положение каждой вещицы зависит только от времени:
 * ничего не копится, на слабой приставке это несколько простых фигур на кадр. Ближние (каждая четвёртая, крупнее) пролетают
 * перед крупными часами, остальные за ними.
 */
internal object Flyers {
    const val OFF = 0
    const val MONEY = 1
    const val LEAVES = 2
    const val SNOW = 3
    const val PETALS = 4
    const val KINDS = 5

    fun count(kind: Int) = when (kind) { MONEY -> 26; LEAVES -> 34; SNOW -> 90; PETALS -> 44; else -> 0 }

    fun isNear(i: Int) = i % 4 == 0

    /** Случайное, но постоянное число 0..1 для вещицы [i]. */
    fun rnd(i: Int, k: Int): Float {
        var x = (i * 374761393 + k * 668265263) xor 0x5bf03635
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0xFFFFFF) / 16777216f
    }

    /** Где вещица в миг [ms] на поле [w] на [h]: x, y, поворот в градусах, «переворот» -1..1 и размер. */
    fun at(kind: Int, i: Int, ms: Long, w: Float, h: Float, out: FloatArray) {
        val unit = w / 1280f
        val near = isNear(i)
        val t = ms / 1000f
        val size = (if (near) 1.0f + 0.5f * rnd(i, 1) else 0.45f + 0.35f * rnd(i, 1)) *
            (when (kind) { MONEY -> 66f; LEAVES -> 30f; SNOW -> 9f; else -> 18f }) * unit
        val wide = w + 4 * size
        val tall = h + 4 * size
        // ветер дует вправо порывами; ближнее летит быстрее
        val speed = (if (near) 190f else 105f) * (0.7f + 0.6f * rnd(i, 2)) * (if (kind == SNOW) 0.45f else 1f) * unit
        val fall = (when (kind) { MONEY -> 42f; LEAVES -> 55f; SNOW -> 70f; else -> 34f }) * (0.6f + 0.8f * rnd(i, 3)) * unit
        val gust = sin(t * 0.9f + rnd(i, 4) * 6.28f) * 60f * unit
        var x = (rnd(i, 5) * wide + speed * t + gust) % wide
        if (x < 0) x += wide
        val wave = sin(t * (1.2f + rnd(i, 6) * 1.6f) + rnd(i, 7) * 6.28f) * (if (kind == SNOW) 14f else 38f) * unit
        var y = (rnd(i, 8) * tall + fall * t + wave) % tall
        if (y < 0) y += tall
        out[0] = x - 2 * size
        out[1] = y - 2 * size
        out[2] = (rnd(i, 9) * 360f + t * (40f + 140f * rnd(i, 10)) * (if (i % 2 == 0) 1f else -1f)) % 360f
        out[3] = cos(t * (2f + 3f * rnd(i, 11)) + rnd(i, 12) * 6.28f)
        out[4] = size
    }

    // 23.0: на приставке S905X деньги, рисуемые каждый кадр по частям (с надписями разных размеров), держали кадр 65-69 мс.
    // Теперь каждый вид рисуется один раз в заготовку, а в кадре заготовки только ставятся на место и поворачиваются.
    private const val BASE = 96f

    /** Границы вещицы в долях её размера: лево, верх, право, низ. */
    private fun bounds(kind: Int): FloatArray = when (kind) {
        MONEY -> floatArrayOf(-1.02f, -0.48f, 1.02f, 0.48f)
        LEAVES -> floatArrayOf(-0.66f, -1.05f, 0.66f, 1.36f)
        SNOW -> floatArrayOf(-0.92f, -0.92f, 0.92f, 0.92f)
        else -> floatArrayOf(-0.84f, -0.88f, 0.84f, 0.86f)
    }

    private fun variant(kind: Int, i: Int, flip: Float, near: Boolean): Int = when (kind) {
        MONEY -> if (flip >= 0) 0 else 1
        LEAVES -> i % 4
        SNOW -> if (near) 1 else 0
        else -> if (i % 3 == 0) 0 else 1
    }

    private val sprites = arrayOfNulls<Bitmap>(KINDS * 4)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()
    private val dst = RectF()

    private fun sprite(kind: Int, v: Int): Bitmap {
        sprites[kind * 4 + v]?.let { return it }
        val r = bounds(kind)
        val bmp = Bitmap.createBitmap(((r[2] - r[0]) * BASE).toInt(), ((r[3] - r[1]) * BASE).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(-r[0] * BASE, -r[1] * BASE)
        shape(c, kind, v, BASE)
        bmp.setHasMipMap(true)
        bmp.prepareToDraw()
        sprites[kind * 4 + v] = bmp
        return bmp
    }

    private fun shape(c: Canvas, kind: Int, v: Int, s: Float) {
        when (kind) {
            MONEY -> {
                fill(if (v == 0) 0xFF8FBF7F.toInt() else 0xFF6E9F66.toInt(), 255)
                c.drawRect(-s, -s * 0.45f, s, s * 0.45f, brush)
                line(0xFFE4F1D8.toInt(), 255, s * 0.04f)
                c.drawRect(-s * 0.9f, -s * 0.36f, s * 0.9f, s * 0.36f, brush)
                fill(0xFFD3E8C4.toInt(), 255)
                oval.set(-s * 0.27f, -s * 0.30f, s * 0.27f, s * 0.30f)
                c.drawOval(oval, brush)
                fill(0xFF2F5C2A.toInt(), 255)
                brush.textAlign = Paint.Align.CENTER
                brush.isFakeBoldText = true
                brush.textSize = s * 0.46f
                c.drawText("$", 0f, s * 0.17f, brush)
                brush.textSize = s * 0.20f
                c.drawText("100", -s * 0.62f, -s * 0.12f, brush)
                c.drawText("100", s * 0.62f, s * 0.28f, brush)
            }
            LEAVES -> {
                fill(intArrayOf(0xFFD9822B.toInt(), 0xFFC4531F.toInt(), 0xFFE0A92E.toInt(), 0xFF8C9A2E.toInt())[v], 255)
                path.reset()
                path.moveTo(0f, -s)
                path.cubicTo(s * 0.75f, -s * 0.55f, s * 0.65f, s * 0.45f, 0f, s)
                path.cubicTo(-s * 0.65f, s * 0.45f, -s * 0.75f, -s * 0.55f, 0f, -s)
                c.drawPath(path, brush)
                line(0xFF6B3A12.toInt(), 255, s * 0.08f)
                c.drawLine(0f, -s * 0.75f, 0f, s * 1.3f, brush)
            }
            SNOW -> {
                if (v == 1) { fill(0xFFFFFFFF.toInt(), 130); c.drawCircle(0f, 0f, s * 0.9f, brush) }
                fill(0xFFFFFFFF.toInt(), 255)
                c.drawCircle(0f, 0f, s * 0.5f, brush)
            }
            else -> {
                fill(if (v == 0) 0xFFF8BBD0.toInt() else 0xFFF48FB1.toInt(), 255)
                path.reset()
                path.moveTo(0f, -s * 0.7f)
                path.cubicTo(s, -s, s * 0.8f, s * 0.5f, 0f, s * 0.8f)
                path.cubicTo(-s * 0.8f, s * 0.5f, -s, -s, 0f, -s * 0.7f)
                c.drawPath(path, brush)
            }
        }
    }

    private val place = FloatArray(5)

    /** Нарисовать ближние ([near] = true) или дальние вещицы. */
    fun draw(c: Canvas, kind: Int, ms: Long, w: Float, h: Float, near: Boolean) {
        if (kind <= OFF || kind >= KINDS || w <= 0f || h <= 0f) return
        val p = place
        val r = bounds(kind)
        paint.alpha = if (near) 235 else 200
        for (i in 0 until count(kind)) {
            if (isNear(i) != near) continue
            at(kind, i, ms, w, h, p)
            val s = p[4]
            c.save()
            c.translate(p[0], p[1])
            c.rotate(if (kind == MONEY) sin(p[2] * 0.01745f) * 38f else p[2])
            when (kind) {
                MONEY -> c.scale(1f, 0.25f + 0.75f * abs(p[3]))
                LEAVES -> c.scale(0.35f + 0.65f * abs(p[3]), 1f)
                PETALS -> c.scale(1f, 0.3f + 0.7f * abs(p[3]))
            }
            dst.set(r[0] * s, r[1] * s, r[2] * s, r[3] * s)
            c.drawBitmap(sprite(kind, variant(kind, i, p[3], near)), null, dst, paint)
            c.restore()
        }
    }

    private fun fill(color: Int, alpha: Int) {
        brush.style = Paint.Style.FILL
        brush.color = color
        brush.alpha = alpha
    }

    private fun line(color: Int, alpha: Int, width: Float) {
        brush.style = Paint.Style.STROKE
        brush.strokeWidth = width
        brush.color = color
        brush.alpha = alpha
    }
}
