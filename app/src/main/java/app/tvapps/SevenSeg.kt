package app.tvapps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Цифры, нарисованные сегментами (как на электронных часах): рисуются кодом, внешний шрифт не нужен.
 * Знаки: цифры, «:», пробел, «-», «.»; другие знаки пропускаются.
 * [ghost] показывает погашенные сегменты бледным цветом, [slant] наклоняет цифры, [colonHidden] прячет двоеточия (мигание).
 */
@Composable
internal fun SevenSegText(text: String, height: Dp, color: Color, ghost: Boolean, slant: Boolean, colonHidden: Boolean = false, modifier: Modifier = Modifier) {
    val d = LocalDensity.current
    val h = with(d) { height.toPx() }
    val t = h * 0.135f
    val chars = text.filter { it.isDigit() || it == ':' || it == ' ' || it == '-' || it == '.' }
    fun cw(c: Char) = when {
        c.isDigit() -> h * 0.52f
        c == ':' -> h * 0.22f
        c == '.' -> h * 0.16f
        c == '-' -> h * 0.40f
        else -> h * 0.16f
    }
    val gapW = t * 0.85f
    val total = chars.sumOf { (cw(it) + gapW).toDouble() }.toFloat() - gapW
    val widthDp = with(d) { total.coerceAtLeast(1f).toDp() }
    Canvas(modifier.size(widthDp + (if (slant) with(d) { (h * 0.14f).toDp() } else 0.dp), height)) {
        if (slant) {
            drawContext.canvas.save()
            drawContext.canvas.nativeCanvas.translate(0f, h)
            drawContext.canvas.nativeCanvas.skew(-0.14f, 0f)
            drawContext.canvas.nativeCanvas.translate(0f, -h)
        }
        var x = if (slant) h * 0.14f * 0.5f else 0f
        for (c in chars) {
            val w = cw(c)
            when {
                c.isDigit() -> digit(x, w, h, t, c - '0', color, ghost)
                c == ':' -> if (!colonHidden) {
                    drawCircle(color, t * 0.62f, Offset(x + w / 2f, h * 0.33f))
                    drawCircle(color, t * 0.62f, Offset(x + w / 2f, h * 0.67f))
                }
                c == '.' -> drawCircle(color, t * 0.62f, Offset(x + w / 2f, h - t * 0.62f))
                c == '-' -> drawRoundRect(color, Offset(x, (h - t) / 2f), Size(w, t), CornerRadius(t / 2f))
            }
            x += w + gapW
        }
        if (slant) drawContext.canvas.restore()
    }
}

/** Семь сегментов цифры: a сверху, b справа сверху, c справа снизу, d снизу, e слева снизу, f слева сверху, g посередине. */
private val SEGS = intArrayOf(
    0b0111111, // 0: a b c d e f
    0b0000110, // 1: b c
    0b1011011, // 2: a b d e g
    0b1001111, // 3: a b c d g
    0b1100110, // 4: b c f g
    0b1101101, // 5: a c d f g
    0b1111101, // 6: a c d e f g
    0b0000111, // 7: a b c
    0b1111111, // 8
    0b1101111, // 9: a b c d f g
)

private fun DrawScope.digit(x0: Float, w: Float, h: Float, t: Float, n: Int, color: Color, ghost: Boolean) {
    val m = SEGS[n.coerceIn(0, 9)]
    val off = color.copy(alpha = 0.10f)
    val r = CornerRadius(t / 2f)
    val vh = h / 2f - t * 0.62f
    fun seg(bit: Int, tl: Offset, size: Size) {
        val lit = ((m shr bit) and 1) == 1
        if (lit) drawRoundRect(color, tl, size, r) else if (ghost) drawRoundRect(off, tl, size, r)
    }
    val hw = w - t
    seg(0, Offset(x0 + t * 0.5f, 0f), Size(hw, t))                           // a
    seg(1, Offset(x0 + w - t, t * 0.55f), Size(t, vh))                       // b
    seg(2, Offset(x0 + w - t, h / 2f + t * 0.07f), Size(t, vh))              // c
    seg(3, Offset(x0 + t * 0.5f, h - t), Size(hw, t))                        // d
    seg(4, Offset(x0, h / 2f + t * 0.07f), Size(t, vh))                      // e
    seg(5, Offset(x0, t * 0.55f), Size(t, vh))                               // f
    seg(6, Offset(x0 + t * 0.5f, (h - t) / 2f), Size(hw, t))                 // g
}
