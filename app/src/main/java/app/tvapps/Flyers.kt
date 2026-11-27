package app.tvapps

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

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()

    /** Нарисовать ближние ([near] = true) или дальние вещицы. */
    fun draw(c: Canvas, kind: Int, ms: Long, w: Float, h: Float, near: Boolean) {
        if (kind <= OFF || kind >= KINDS || w <= 0f || h <= 0f) return
        val p = FloatArray(5)
        for (i in 0 until count(kind)) {
            if (isNear(i) != near) continue
            at(kind, i, ms, w, h, p)
            val s = p[4]
            c.save()
            c.translate(p[0], p[1])
            c.rotate(if (kind == MONEY) sin(p[2] * 0.01745f) * 38f else p[2])
            val alpha = if (near) 235 else 200
            when (kind) {
                MONEY -> {
                    c.scale(1f, 0.25f + 0.75f * abs(p[3]))
                    val face = p[3] >= 0
                    fill(if (face) 0xFF8FBF7F.toInt() else 0xFF6E9F66.toInt(), alpha)
                    c.drawRect(-s, -s * 0.45f, s, s * 0.45f, paint)
                    line(0xFFE4F1D8.toInt(), alpha, s * 0.04f)
                    c.drawRect(-s * 0.9f, -s * 0.36f, s * 0.9f, s * 0.36f, paint)
                    fill(0xFFD3E8C4.toInt(), alpha)
                    oval.set(-s * 0.27f, -s * 0.30f, s * 0.27f, s * 0.30f)
                    c.drawOval(oval, paint)
                    fill(0xFF2F5C2A.toInt(), alpha)
                    paint.textAlign = Paint.Align.CENTER
                    paint.isFakeBoldText = true
                    paint.textSize = s * 0.46f
                    c.drawText("$", 0f, s * 0.17f, paint)
                    paint.textSize = s * 0.20f
                    c.drawText("100", -s * 0.62f, -s * 0.12f, paint)
                    c.drawText("100", s * 0.62f, s * 0.28f, paint)
                }
                LEAVES -> {
                    c.scale(0.35f + 0.65f * abs(p[3]), 1f)
                    fill(intArrayOf(0xFFD9822B.toInt(), 0xFFC4531F.toInt(), 0xFFE0A92E.toInt(), 0xFF8C9A2E.toInt())[i % 4], alpha)
                    path.reset()
                    path.moveTo(0f, -s)
                    path.cubicTo(s * 0.75f, -s * 0.55f, s * 0.65f, s * 0.45f, 0f, s)
                    path.cubicTo(-s * 0.65f, s * 0.45f, -s * 0.75f, -s * 0.55f, 0f, -s)
                    c.drawPath(path, paint)
                    line(0xFF6B3A12.toInt(), alpha, s * 0.08f)
                    c.drawLine(0f, -s * 0.75f, 0f, s * 1.3f, paint)
                }
                SNOW -> {
                    fill(0xFFFFFFFF.toInt(), if (near) 120 else 190)
                    if (near) c.drawCircle(0f, 0f, s * 0.9f, paint)
                    fill(0xFFFFFFFF.toInt(), alpha)
                    c.drawCircle(0f, 0f, s * 0.5f, paint)
                }
                else -> {
                    c.scale(1f, 0.3f + 0.7f * abs(p[3]))
                    fill(if (i % 3 == 0) 0xFFF8BBD0.toInt() else 0xFFF48FB1.toInt(), alpha)
                    path.reset()
                    path.moveTo(0f, -s * 0.7f)
                    path.cubicTo(s, -s, s * 0.8f, s * 0.5f, 0f, s * 0.8f)
                    path.cubicTo(-s * 0.8f, s * 0.5f, -s, -s, 0f, -s * 0.7f)
                    c.drawPath(path, paint)
                }
            }
            c.restore()
        }
    }

    private fun fill(color: Int, alpha: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.alpha = alpha
    }

    private fun line(color: Int, alpha: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = color
        paint.alpha = alpha
    }
}
