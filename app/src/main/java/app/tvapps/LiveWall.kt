package app.tvapps

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Слои картинки, разделённой на ближнее и дальнее. */
internal class LiveScene(
    val key: String,
    /** Картинка целиком (с добавленными позади деревьями, если они понадобились). */
    val scene: Bitmap,
    /** Только передний план, остальное прозрачное. */
    val front: Bitmap,
    /** Задний план без переднего (для объёма при движении). */
    val back: Bitmap,
    val sway: FloatArray,
    val water: FloatArray,
    /** Облака и всё, что их заслоняет (задний план без неба); null: неба на картинке нет. */
    val clouds: Bitmap?,
    val land: Bitmap?,
    /** Нижний край неба в точках картинки. */
    val skyHeight: Int,
)

/**
 * 27.11.2026 (слово владелицы: «не 3д и я не видел как трава шевелится»; «надо пересобирание в прогу»).
 * Живая картинка под крупными часами: «камера» чуть плывёт (дальнее сдвигается меньше, ближнее больше, часы между ними),
 * деревья и трава качаются, по воде идёт рябь. Из одной фотографии это не видео, а лёгкая имитация.
 */
internal object LiveWall {
    const val COLS = 80
    const val ROWS = 45
    private const val PERIOD_MS = 6000L

    /** Разделённая картинка, которую «Дом» рисует вместо обычной; null: обычный фон. */
    var scene by mutableStateOf<LiveScene?>(null)
    var motion by mutableStateOf(false)
    /** Движение, включённое в настройках крупных часов. */
    var clockMotion by mutableStateOf(false)
    /** Крупные часы показывают передний план обоев: тогда «3D-обои» не двигают картинку сами по себе без их слоёв. */
    var clockFront by mutableStateOf(false)
    /** Сколько разделённых картинок сейчас на экране. */
    var shown by mutableStateOf(0)
    /** Место в круге движения, 0..1. */
    var phase by mutableFloatStateOf(0f)
        private set

    /** Время для облаков: они плывут без повторяющегося круга. */
    var time by mutableLongStateOf(0L)
        private set

    fun tick(millis: Long) { phase = (millis % PERIOD_MS) / PERIOD_MS.toFloat(); time = millis }

    private val noise = FloatArray((COLS + 1) * (ROWS + 1)).also {
        val r = java.util.Random(7)
        for (i in it.indices) it[i] = r.nextGaussian().toFloat()
    }

    /** Узлы сетки переднего плана в точках картинки: ветер и рябь. */
    fun verts(s: LiveScene, t: Float, out: FloatArray) {
        val bw = s.front.width.toFloat()
        val bh = s.front.height.toFloat()
        val unit = bw / 1280f
        val a = (2 * PI).toFloat() * t
        val moving = motion
        for (j in 0..ROWS) for (i in 0..COLS) {
            val at = j * (COLS + 1) + i
            val x = i * bw / COLS
            val y = j * bh / ROWS
            var dx = 0f
            var dy = 0f
            if (moving) {
                val sw = s.sway[at]
                if (sw > 0f) {
                    val x1 = x / unit
                    val wind = sin(a * 2 + x1 / 90f + noise[at] * 1.3f) * 0.7f + sin(a * 3 - x1 / 41f + noise[at] * 2.1f) * 0.3f
                    val gust = 0.75f + 0.25f * sin(a - x1 / 300f)
                    dx += wind * gust * sw * 18f * unit
                    dy += cos(a * 2 + x1 / 70f + noise[at]) * sw * 3f * unit
                }
                val wt = s.water[at]
                if (wt > 0f) {
                    val near = ((y / bh - 0.55f) / 0.45f).coerceIn(0f, 1f)
                    dx += sin(a * 4 + y / unit / 14f + x / unit / 55f + noise[at] * 0.8f) * wt * (2.5f + 5.5f * near) * unit
                    dy += sin(a * 3 + y / unit / 19f - x / unit / 80f) * wt * (1.2f + 2.6f * near) * unit
                }
            }
            out[at * 2] = x + dx
            out[at * 2 + 1] = y + dy
        }
    }

    /** Как фон ложится на экран: обрезка по экрану или растяжение, сдвиг и увеличение из «Сдвинуть / Масштаб». */
    fun place(m: Matrix, bw: Int, bh: Int, boxW: Float, boxH: Float, stretch: Boolean, ox: Float, oy: Float, zoom: Float) {
        val k = maxOf(boxW / bw, boxH / bh)
        val sx = if (stretch) boxW / bw else k
        val sy = if (stretch) boxH / bh else k
        val px = (ox + 1f) / 2f
        val py = (oy + 1f) / 2f
        m.reset()
        m.postScale(sx, sy)
        m.postTranslate((boxW - bw * sx) * px, (boxH - bh * sy) * py)
        m.postScale(zoom, zoom, px * boxW, py * boxH)
    }

    /** Движение «камеры» для слоя: [depth] 0 дальний план, 1 часы, 2 ближний. */
    fun camera(m: Matrix, depth: Int, t: Float, boxW: Float, boxH: Float) {
        if (!motion) return
        val a = (2 * PI).toFloat() * t
        val unit = boxW / 1280f
        val (shift, base, wave) = when (depth) {
            0 -> Triple(6f, 1.028f, 0.008f)
            1 -> Triple(12f, 1.036f, 0f)
            else -> Triple(20f, 1.062f, 0.020f)
        }
        val z = base + wave * sin(a)
        m.postScale(z, z, boxW / 2, boxH / 2)
        m.postTranslate(sin(a) * shift * unit, sin(a + 1.2f) * shift * 0.3f * unit)
    }

    /** Смещение цифр при движении, в точках экрана. */
    fun clockShift(t: Float, boxW: Float): Pair<Float, Float> {
        if (!motion) return 0f to 0f
        val a = (2 * PI).toFloat() * t
        val unit = boxW / 1280f
        return sin(a) * 12f * unit to sin(a + 1.2f) * 3.6f * unit
    }

    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
}
