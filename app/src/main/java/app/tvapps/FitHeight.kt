package app.tvapps

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * 17.3 (владелица 24.11.2026: «ряды под доком обрезаться не должны ни у кого»): содержимое «Дома» измеряется в естественном размере,
 * и если оно выше свободной области [maxH] (док занимает нижнюю полосу), всё равномерно уменьшается по центру сверху так, чтобы поместиться целиком
 * (не меньше [minScale], иначе остаётся прокрутка). Касания и фокус работают как обычно: преобразование слоя учитывается.
 */
@Composable
fun FitHeight(maxH: Dp, enabled: Boolean, minScale: Float = 0.6f, content: @Composable () -> Unit) {
    Layout(content = content) { measurables, c ->
        val p = measurables.map { it.measure(Constraints(minWidth = c.minWidth, maxWidth = c.maxWidth, minHeight = 0, maxHeight = Constraints.Infinity)) }
        val w = p.maxOfOrNull { it.width } ?: c.minWidth
        val h = p.sumOf { it.height }
        val maxPx = maxH.roundToPx()
        val s = if (enabled && maxPx > 0 && h > maxPx) (maxPx.toFloat() / h).coerceAtLeast(minScale) else 1f
        if (kotlin.math.abs(HomeDock.fitScale.floatValue - s) > 0.004f) HomeDock.fitScale.floatValue = s
        layout(w, (h * s).toInt()) {
            var y = 0
            for (pl in p) {
                pl.placeWithLayer(0, (y * s).toInt()) { scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0.5f, 0f) }
                y += pl.height
            }
        }
    }
}

/**
 * 26.11.2026 (просьба владелицы): если строки «Дома» не помещаются по высоте, сжимается расстояние между строками (не масштаб),
 * ровно настолько, чтобы всё влезло и внизу не оставалось пустоты. [baseGap] - зазор из настроек, [effGap] - зазор, с которым строки нарисованы сейчас,
 * [gaps] - число зазоров между строками; [cut] - на сколько dp зазор нужно уменьшить.
 */
@Composable
fun FitGap(maxH: Dp, baseGap: Int, effGap: Int, gaps: Int, cut: androidx.compose.runtime.MutableIntState, content: @Composable () -> Unit) {
    Layout(content = content) { measurables, c ->
        val p = measurables.map { it.measure(Constraints(minWidth = c.minWidth, maxWidth = c.maxWidth, minHeight = 0, maxHeight = Constraints.Infinity)) }
        val w = p.maxOfOrNull { it.width } ?: c.minWidth
        val h = p.sumOf { it.height }
        val maxPx = maxH.roundToPx()
        val used = (baseGap - effGap).coerceAtLeast(0)
        var want = 0
        if (maxPx > 0 && gaps > 0) {
            val slope = gaps * density
            val hFull = h + used * slope
            if (hFull > maxPx) want = kotlin.math.ceil((hFull - maxPx) / slope).toInt().coerceIn(0, (baseGap - 2).coerceAtLeast(0))
        }
        // гистерезис против дёрганья: не влезает - сразу сжимаем; влезает - зазор возвращаем, только если свободного места набралось заметно (3 dp и больше)
        val next = if (maxPx > 0 && h > maxPx) want else if (want <= used - 3) want + 1 else used
        if (next != cut.intValue) cut.intValue = next
        layout(w, h) { var y = 0; for (pl in p) { pl.placeRelative(0, y); y += pl.height } }
    }
}
