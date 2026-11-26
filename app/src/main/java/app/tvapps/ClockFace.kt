package app.tvapps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

/**
 * 20.0 (просьба владелицы 26.11.2026): циферблат со стрелками вместо цифр часов верхней строки. По умолчанию выключен (включается в
 * окне значка «Время»). Стоит по центру строки, крупнее её, и выступает вниз поверх «Дома». У каждой стрелки свой цвет (набор цветов
 * выбирается), у циферблата свой фон. Секундная стрелка показывается, когда в часах включены секунды.
 * Настройки лежат в битах числа opt значка «Время»: 32 включён, биты 6-8 фон (0-4), биты 9-11 набор цветов (0-4), биты 12-13 размер (0-3).
 */
internal object ClockFace {
    /** Часовая, минутная, секундная, обод и деления. Набор 4 берёт цвет темы. */
    private val SETS = listOf(
        listOf(0xFFFFFFFF, 0xFFFFFFFF, 0xFFE53935, 0xFFFFFFFF),   // классика
        listOf(0xFFFFC107, 0xFF26C6DA, 0xFFE53935, 0xFFFFFFFF),   // цветные
        listOf(0xFF111111, 0xFF111111, 0xFFD32F2F, 0xFF111111),   // тёмные (для светлого фона)
        listOf(0xFF69F0AE, 0xFFFF4081, 0xFFFFEA00, 0xFF80D8FF),   // неон
    )
    private val SIZES = listOf(66f, 84f, 104f, 128f)      // 20.0: чуть больше строки и чуть ниже её; дальше крупнее

    fun on(opt: Int) = (opt and 32) != 0
    fun bg(opt: Int) = ((opt shr 6) and 7).coerceIn(0, 4)
    fun hands(opt: Int) = ((opt shr 9) and 7).coerceIn(0, 4)
    fun sizeStep(opt: Int) = ((opt shr 12) and 3)
    /** Во сколько раз цифровые часы по центру крупнее обычных значков: ступени размера 1-4. */
    fun digitalK(step: Int): Float = listOf(1.9f, 2.4f, 3.0f, 3.7f)[step.coerceIn(0, 3)]

    fun withBg(opt: Int, v: Int) = (opt and (7 shl 6).inv()) or ((v and 7) shl 6)
    fun withHands(opt: Int, v: Int) = (opt and (7 shl 9).inv()) or ((v and 7) shl 9)
    fun withSize(opt: Int, v: Int) = (opt and (3 shl 12).inv()) or ((v and 3) shl 12)

    /** Размер циферблата, dp: шаг размера, умноженный на «Размер верхней строки» (%); на телефоне и планшете мельче. */
    fun sizeDp(opt: Int, userScale: Int, big: Boolean): Dp = (SIZES[sizeStep(opt)] * userScale / 100f * (if (big) 1f else 0.75f)).dp

    fun colors(opt: Int, accent: Color): List<Color> {
        val h = hands(opt)
        if (h == 4) return listOf(accent, Color.White, accent, Color.White)
        return SETS[h].map { Color(it) }
    }

    fun background(opt: Int, accent: Color): Color? = when (bg(opt)) {
        1 -> Color(0xCC101820)
        2 -> Color(0xDDF5F5F5)
        3 -> accent.copy(alpha = 0.35f)
        4 -> Color(0x33FFFFFF)
        else -> null
    }
}

@Composable
internal fun AnalogFace(now: Long, seconds: Boolean, opt: Int, accent: Color, size: Dp, doors: Boolean = false) {
    val cl = ClockFace.colors(opt, accent)
    val back = ClockFace.background(opt, accent)
    Canvas(Modifier.padding(4.dp).size(size)) {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val h = cal.get(Calendar.HOUR)
        val m = cal.get(Calendar.MINUTE)
        val s = cal.get(Calendar.SECOND)
        val r = this.size.minDimension / 2f
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        fun pt(deg: Float, len: Float): Offset {
            val a = Math.toRadians(deg.toDouble())
            return Offset(c.x + len * sin(a).toFloat(), c.y - len * cos(a).toFloat())
        }
        val shade = Color(0x99000000)
        if (back != null) drawCircle(back, r, c)
        val ring = cl[3]
        drawCircle(shade, r * 0.95f, c, style = Stroke(width = r * 0.20f))
        drawCircle(ring, r * 0.95f, c, style = Stroke(width = r * 0.12f))
        for (i in 0 until 12) {
            val d = i * 30f
            val big = i % 3 == 0
            drawLine(ring, pt(d, r * (if (big) 0.62f else 0.74f)), pt(d, r * 0.82f), strokeWidth = r * (if (big) 0.13f else 0.08f), cap = StrokeCap.Round)
        }
        // дверцы кукушки внизу циферблата (у цифры 6): в режиме «Кукушка» закрыты, а в час распахиваются
        // дверцы видны только пока кукушка выходит и возвращается; в остальное время циферблат чистый
        if (doors && HourSignal.door.floatValue > 0.01f) {
            val open = HourSignal.door.floatValue
            val dcx = c.x
            val dcy = c.y + r * 0.62f
            val hw = r * 0.23f
            val hh = r * 0.27f
            drawRoundRect(Color(0xFF3B2412), Offset(dcx - hw - r * 0.04f, dcy - hh - r * 0.04f), androidx.compose.ui.geometry.Size((hw + r * 0.04f) * 2f, (hh + r * 0.04f) * 2f), androidx.compose.ui.geometry.CornerRadius(r * 0.12f))
            drawRoundRect(Color(0xFF140B05), Offset(dcx - hw, dcy - hh), androidx.compose.ui.geometry.Size(hw * 2f, hh * 2f), androidx.compose.ui.geometry.CornerRadius(r * 0.09f))
            val leaf = hw * (1f - open * 0.86f)
            if (leaf > 0.5f) {
                drawRoundRect(Color(0xFF9C6B30), Offset(dcx - hw, dcy - hh), androidx.compose.ui.geometry.Size(leaf, hh * 2f), androidx.compose.ui.geometry.CornerRadius(r * 0.05f))
                drawRoundRect(Color(0xFF9C6B30), Offset(dcx + hw - leaf, dcy - hh), androidx.compose.ui.geometry.Size(leaf, hh * 2f), androidx.compose.ui.geometry.CornerRadius(r * 0.05f))
                drawLine(Color(0xFF5D3A1A), Offset(dcx - hw + leaf * 0.5f, dcy - hh * 0.8f), Offset(dcx - hw + leaf * 0.5f, dcy + hh * 0.8f), strokeWidth = r * 0.025f)
                drawLine(Color(0xFF5D3A1A), Offset(dcx + hw - leaf * 0.5f, dcy - hh * 0.8f), Offset(dcx + hw - leaf * 0.5f, dcy + hh * 0.8f), strokeWidth = r * 0.025f)
            }
        }
        val hd = (h + m / 60f) * 30f
        val md = (m + s / 60f) * 6f
        // тёмная подложка под стрелками: видны на любом фоне, как контур у цифр
        drawLine(shade, c, pt(hd, r * 0.5f), strokeWidth = r * 0.14f, cap = StrokeCap.Round)
        drawLine(shade, c, pt(md, r * 0.72f), strokeWidth = r * 0.11f, cap = StrokeCap.Round)
        drawLine(cl[0], c, pt(hd, r * 0.5f), strokeWidth = r * 0.085f, cap = StrokeCap.Round)
        drawLine(cl[1], c, pt(md, r * 0.72f), strokeWidth = r * 0.06f, cap = StrokeCap.Round)
        if (seconds) drawLine(cl[2], c, pt(s * 6f, r * 0.80f), strokeWidth = r * 0.03f, cap = StrokeCap.Round)
        drawCircle(cl[2], r * 0.08f, c)
    }
}

/**
 * Кукушка на циферблате (просьба владелицы 26.11.2026): каждый час из циферблата выпадает лестница, кукушка медленно спускается по ней,
 * кукует (по числу часов, в такт звуку «ку-ку») и так же медленно залезает обратно, лестница прячется. Лестница в перспективе: низ ближе
 * (шире, перекладины чаще и толще), птица внизу крупнее. Времена, мс: лестница 800, спуск 2200, кукование 1200 на раз, подъём 2200,
 * лестница обратно 800. Звук стартует после спуска (см. [HourSignal.fire]).
 */
internal const val DOOR_MS = 800f        // дверцы открываются (и закрываются) за это время
internal const val LADDER_MS = 1600f    // дверцы и лестница
internal const val WALK_MS = 7500f
internal const val CUCKOO_MS = 1800f

@Composable
internal fun CuckooBird() {
    val tick = HourSignal.tick.intValue
    val clock = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val running = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val total = LADDER_MS + WALK_MS + CUCKOO_MS * HourSignal.count + WALK_MS + LADDER_MS
    androidx.compose.runtime.LaunchedEffect(tick) {
        if (tick > 0) {
            val tot = LADDER_MS + WALK_MS + CUCKOO_MS * HourSignal.count + WALK_MS + LADDER_MS
            running.value = true
            clock.floatValue = 0f
            // свои часы по System.nanoTime: масштаб анимаций системы (у некоторых приставок 0,25) на кукушку не действует, звук идёт в реальном времени
            val t0 = System.nanoTime()
            try {
                while (true) {
                    val ms = (System.nanoTime() - t0) / 1_000_000f
                    clock.floatValue = ms
                    HourSignal.door.floatValue = if (ms < DOOR_MS) ms / DOOR_MS else if (ms > tot - DOOR_MS) ((tot - ms) / DOOR_MS).coerceAtLeast(0f) else 1f
                    if (ms >= tot) break
                    androidx.compose.runtime.withFrameNanos { }
                }
            } finally {
                HourSignal.door.floatValue = 0f
                running.value = false
            }
        }
    }
    if (!running.value) return
    // время читается в самом рисовании (не в составе): каждый кадр перерисовывается только птица с лестницей, без пересборки строки
    Canvas(Modifier.fillMaxSize()) {
        val t = clock.floatValue
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val ladderP = if (t < DOOR_MS) 0f else if (t < LADDER_MS) (t - DOOR_MS) / (LADDER_MS - DOOR_MS) else if (t > total - LADDER_MS) ((total - DOOR_MS - t) / (LADDER_MS - DOOR_MS)).coerceIn(0f, 1f) else 1f
        val walk = when {
            t < LADDER_MS -> 0f
            t < LADDER_MS + WALK_MS -> (t - LADDER_MS) / WALK_MS
            t < total - LADDER_MS - WALK_MS -> 1f
            t < total - LADDER_MS -> 1f - (t - (total - LADDER_MS - WALK_MS)) / WALK_MS
            else -> 0f
        }
        val cuckooPhase = t - (LADDER_MS + WALK_MS)
        val singing = cuckooPhase >= 0f && t < total - LADDER_MS - WALK_MS
        val ph = if (singing) (cuckooPhase % CUCKOO_MS) / CUCKOO_MS else 0f
        val open = singing && (ph in 0.03f..0.25f || ph in 0.34f..0.78f)      // «ку» и протяжное «куу» настоящей записи
        val wood = Color(0xFF9C6B30)
        val woodLight = Color(0xFFB98240)
        val shade = Color(0xAA000000)
        // перспектива: u от 0 (верх, у циферблата, далеко) до 1 (низ, близко)
        fun yOf(u: Float) = h * (0.55f * u + 0.45f * u * u)
        fun halfW(u: Float) = w * (0.16f + 0.30f * u)
        fun sc(u: Float) = 0.55f + 0.65f * u
        val uMax = ladderP
        if (uMax > 0.01f) {
            // стойки: короткие отрезки, чем ниже, тем толще
            val n = 26
            var prevU = 0f
            for (k in 1..n) {
                val u = uMax * k / n
                for (side in intArrayOf(-1, 1)) {
                    val a = Offset(cx + side * halfW(prevU), yOf(prevU))
                    val b = Offset(cx + side * halfW(u), yOf(u))
                    drawLine(shade, a, b, strokeWidth = w * 0.095f * sc(u), cap = StrokeCap.Round)
                    drawLine(wood, a, b, strokeWidth = w * 0.06f * sc(u), cap = StrokeCap.Round)
                }
                prevU = u
            }
            // перекладины: чем ниже, тем чаще по экрану и толще
            var r = 0.08f
            while (r <= uMax) {
                val y = yOf(r)
                drawLine(shade, Offset(cx - halfW(r), y), Offset(cx + halfW(r), y), strokeWidth = w * 0.075f * sc(r))
                drawLine(woodLight, Offset(cx - halfW(r), y), Offset(cx + halfW(r), y), strokeWidth = w * 0.045f * sc(r))
                r += 0.115f
            }
        }
        if (walk > 0.001f || singing) {
            val u = (walk * walk * (3f - 2f * walk)) * 0.97f          // плавно трогается и плавно останавливается: ленивая
            val s = sc(u)
            val bs = w * 0.68f * s                                   // птица внизу крупнее
            val walking = walk in 0.001f..0.999f
            val phase = t / 520f * 3.1416f            // один шаг ~0,5 с: лапки по очереди, птица переваливается с боку на бок
            val step = if (walking) kotlin.math.abs(kotlin.math.sin(phase)) * bs * 0.025f else 0f
            val wobble = if (walking) kotlin.math.sin(phase) * 7f else 0f
            val bob = if (singing && open) -bs * 0.04f else 0f
            val x0 = cx - bs / 2f
            val y0 = yOf(u) - bs * 0.84f + step + bob
            fun P(x: Float, y: Float) = Offset(x0 + x * bs, y0 + y * bs)
            val grey = Color(0xFF8A98A3)
            val greyDark = Color(0xFF59636C)
            val cream = Color(0xFFEFE9DE)
            val orange = Color(0xFFFFA000)
            // длинный ступенчатый хвост с белыми пятнами на конце
            val tail = androidx.compose.ui.graphics.Path().apply { moveTo(P(0.30f, 0.50f).x, P(0.30f, 0.50f).y); lineTo(P(0.30f, 0.64f).x, P(0.30f, 0.64f).y); lineTo(P(-0.04f, 0.93f).x, P(-0.04f, 0.93f).y); lineTo(P(-0.10f, 0.82f).x, P(-0.10f, 0.82f).y); close() }
            drawPath(tail, Color(0xFF4F5962))
            drawCircle(Color.White, bs * 0.018f, P(-0.05f, 0.86f)); drawCircle(Color.White, bs * 0.018f, P(0.00f, 0.88f)); drawCircle(Color.White, bs * 0.018f, P(0.05f, 0.82f))
            // лапки
            // лапки: при ходьбе поочерёдно поднимаются и переставляются (одна вперёд, другая назад), на месте стоят на перекладине
            val liftA = if (walking) maxOf(0f, kotlin.math.sin(phase)) * 0.11f else 0f
            val liftB = if (walking) maxOf(0f, -kotlin.math.sin(phase)) * 0.11f else 0f
            val swA = if (walking) kotlin.math.cos(phase) * 0.05f else 0f
            drawLine(orange, P(0.44f, 0.80f), P(0.44f + swA, 0.97f - liftA), strokeWidth = bs * 0.035f, cap = StrokeCap.Round)
            drawLine(orange, P(0.58f, 0.80f), P(0.58f - swA, 0.97f - liftB), strokeWidth = bs * 0.035f, cap = StrokeCap.Round)
            drawLine(orange, P(0.44f + swA, 0.97f - liftA), P(0.50f + swA, 0.97f - liftA), strokeWidth = bs * 0.03f, cap = StrokeCap.Round)
            drawLine(orange, P(0.58f - swA, 0.97f - liftB), P(0.64f - swA, 0.97f - liftB), strokeWidth = bs * 0.03f, cap = StrokeCap.Round)
            // тело: серая спина, светлое брюшко с поперечными полосками
            drawOval(grey, P(0.22f, 0.36f), androidx.compose.ui.geometry.Size(0.54f * bs, 0.46f * bs))
            drawOval(cream, P(0.34f, 0.52f), androidx.compose.ui.geometry.Size(0.42f * bs, 0.30f * bs))
            for (k in 0 until 5) drawLine(Color(0xFF55606A), P(0.40f + k * 0.065f, 0.55f + (if (k == 4) 0.02f else 0f)), P(0.42f + k * 0.065f, 0.76f - (if (k == 0) 0.04f else 0f)), strokeWidth = bs * 0.012f)
            // голова с серой «шапочкой», светлым горлом, жёлтым глазом с кольцом
            drawCircle(Color(0xFF7F8E99), 0.145f * bs, P(0.70f, 0.31f))
            drawOval(cream, P(0.66f, 0.34f), androidx.compose.ui.geometry.Size(0.17f * bs, 0.10f * bs))
            drawCircle(Color(0xFFFFC107), 0.042f * bs, P(0.74f, 0.285f))
            drawCircle(Color.Black, 0.02f * bs, P(0.75f, 0.285f))
            // клюв: тёмный, слегка загнутый; при кукованье открыт, виден оранжевый зев
            val upper = androidx.compose.ui.graphics.Path().apply { moveTo(P(0.82f, 0.255f).x, P(0.82f, 0.255f).y); lineTo(P(1.00f, 0.325f).x, P(1.00f, 0.325f).y); lineTo(P(0.82f, 0.335f).x, P(0.82f, 0.335f).y); close() }
            drawPath(upper, Color(0xFF37474F))
            if (open) {
                val mouth = androidx.compose.ui.graphics.Path().apply { moveTo(P(0.82f, 0.335f).x, P(0.82f, 0.335f).y); lineTo(P(0.96f, 0.43f).x, P(0.96f, 0.43f).y); lineTo(P(0.82f, 0.40f).x, P(0.82f, 0.40f).y); close() }
                drawPath(mouth, Color(0xFFFF7043))
            } else {
                val lower = androidx.compose.ui.graphics.Path().apply { moveTo(P(0.82f, 0.335f).x, P(0.82f, 0.335f).y); lineTo(P(0.96f, 0.345f).x, P(0.96f, 0.345f).y); lineTo(P(0.82f, 0.375f).x, P(0.82f, 0.375f).y); close() }
                drawPath(lower, Color(0xFF455A64))
            }
            // крыло: в покое прижато к телу, при «ку-куууу» поднимается ко рту (как рука к губам) и дрожит
            val raise = if (singing) (if (ph < 0.06f) ph / 0.06f else if (ph < 0.80f) 1f else ((1f - ph) / 0.20f).coerceIn(0f, 1f)) else 0f
            val ang = 165f + (-29f - 165f) * raise + (if (raise > 0.95f) kotlin.math.sin(t / 55f) * 5f else 0f)
            val pv = P(0.46f, 0.55f)
            rotate(ang, pv) {
                drawOval(Color(0xFF2E353B), Offset(pv.x - 0.02f * bs, pv.y - 0.09f * bs), androidx.compose.ui.geometry.Size(0.42f * bs, 0.18f * bs))
                drawOval(greyDark, Offset(pv.x, pv.y - 0.075f * bs), androidx.compose.ui.geometry.Size(0.38f * bs, 0.15f * bs))
                drawLine(Color(0xFF9AA7B1), Offset(pv.x + 0.06f * bs, pv.y), Offset(pv.x + 0.34f * bs, pv.y), strokeWidth = bs * 0.012f)
            }
        }
    }
}
