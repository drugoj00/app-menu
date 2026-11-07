package app.tvapps

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Положение и размер ползунка и пересчёт перетаскивания: чистая арифметика (проверяется тестом). Все числа в px. */
internal object ScrollbarMath {
    class Thumb(val top: Float, val height: Float)

    fun thumb(offset: Float, viewport: Float, content: Float, minThumb: Float): Thumb? {
        if (viewport <= 0f || content <= viewport + 1f) return null
        val maxOff = content - viewport
        val h = maxOf(viewport * viewport / content, minOf(minThumb, viewport)).coerceAtMost(viewport)
        val top = (offset.coerceIn(0f, maxOff) / maxOff) * (viewport - h)
        return Thumb(top, h)
    }

    /** Поле справа под полосу прокрутки (dp): зона нажатия и перетаскивания 16 dp у самого края и зазор 4 dp. Плитки и строки стоят целиком левее него (раскладка не прыгает, поле резервируется всегда). */
    const val RESERVE_DP = 20f
    const val ZONE_DP = 16f

    /** Ширина под плитки: полная ширина минус поля по краям (с каждой стороны [pad]) и поле полосы [reserve]. */
    fun contentWidth(total: Float, pad: Float, reserve: Float): Float = (total - 2f * pad - reserve).coerceAtLeast(1f)

    /** На сколько px содержимого прокрутить, когда ползунок сдвинули на [thumbDelta] px: пропорционально, непрерывно. */
    fun contentDelta(thumbDelta: Float, viewport: Float, content: Float, thumbHeight: Float): Float {
        val track = viewport - thumbHeight
        if (track <= 0f || content <= viewport) return 0f
        return thumbDelta * (content - viewport) / track
    }

    /** Нажатие на дорожку: на страницу (90% окна) к месту нажатия; 0, если нажали на сам ползунок. */
    fun pageDelta(y: Float, thumb: Thumb, viewport: Float): Float = when {
        y < thumb.top -> -0.9f * viewport
        y > thumb.top + thumb.height -> 0.9f * viewport
        else -> 0f
    }
}

/**
 * Вертикальная полоса прокрутки (один общий компонент для окон и главного списка): видна всегда, пока есть что прокручивать (не гаснет по таймеру),
 * в покое тонкая, при наведении мыши, касании и перетаскивании шире и ярче. Ползунок тянется мышью и пальцем (пропорциональная прокрутка),
 * нажатие на дорожку прокручивает на страницу. Зона нажатия 16 dp у самого правого края (содержимое стоит левее: поле ScrollbarMath.RESERVE_DP), при этом нарисована полоса тонкая. Фокус пульта не берёт.
 * Ставится ПЕРЕД verticalScroll / на сам список, чтобы размер полосы был размером окна, а не содержимого.
 */
internal fun Modifier.verticalScrollbar(state: ScrollState): Modifier =
    scrollbar(state) { vp -> Triple(state.value.toFloat(), vp, vp + state.maxValue) }

internal fun Modifier.verticalScrollbar(state: LazyListState): Modifier =
    scrollbar(state) { _ ->
        val info = state.layoutInfo
        val vis = info.visibleItemsInfo
        if (vis.isEmpty() || info.totalItemsCount == 0) null
        else {
            val avg = vis.sumOf { it.size }.toFloat() / vis.size
            Triple(state.firstVisibleItemIndex * avg + state.firstVisibleItemScrollOffset, info.viewportSize.height.toFloat(), avg * info.totalItemsCount)
        }
    }

/** Сетка: содержимое оценивается по строкам (число столбцов по первой видимой строке, шаг строк по двум первым). */
internal fun Modifier.verticalScrollbar(state: LazyGridState): Modifier =
    scrollbar(state) { _ ->
        val info = state.layoutInfo
        val vis = info.visibleItemsInfo
        if (vis.isEmpty() || info.totalItemsCount == 0) null
        else {
            val y0 = vis.first().offset.y
            val cols = vis.takeWhile { it.offset.y == y0 }.size.coerceAtLeast(1)
            val second = vis.firstOrNull { it.offset.y > y0 }
            val pitch = if (second != null) (second.offset.y - y0).toFloat() else vis.first().size.height.toFloat()
            val rows = (info.totalItemsCount + cols - 1) / cols
            val firstRow = state.firstVisibleItemIndex / cols
            Triple(firstRow * pitch + state.firstVisibleItemScrollOffset, info.viewportSize.height.toFloat(), rows * pitch)
        }
    }

private fun Modifier.scrollbar(state: ScrollableState, metrics: (Float) -> Triple<Float, Float, Float>?): Modifier = composed {
    val t = LocalTh.current
    val tv = TopMode.isTv(LocalContext.current)
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf(false) }
    val widthDp by animateFloatAsState(if (active) (if (tv) 8f else 6f) else (if (tv) 6f else 4f), label = "barW")
    val alpha by animateFloatAsState(if (active) 1f else if (tv) 0.6f else 0.5f, label = "barA")
    this
        .pointerInput(state) {
            val zone = ScrollbarMath.ZONE_DP.dp.toPx()
            val minThumb = 36.dp.toPx()
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val ch = ev.changes.firstOrNull() ?: continue
                    val vp = size.height.toFloat()
                    val th = metrics(vp)?.let { ScrollbarMath.thumb(it.first, it.second, it.third, minThumb) }
                    val inZone = th != null && ch.position.x >= size.width - zone
                    // наведение мыши: полоса чуть шире и ярче
                    if (ev.type == PointerEventType.Move || ev.type == PointerEventType.Enter) active = inZone
                    if (ev.type == PointerEventType.Exit) active = false
                    if (th == null || !inZone || !(ch.pressed && !ch.previousPressed)) continue
                    // нажатие в зоне полосы: забираем у содержимого
                    ch.consume()
                    active = true
                    val onThumb = ch.position.y >= th.top && ch.position.y <= th.top + th.height
                    if (!onThumb) {
                        val d = ScrollbarMath.pageDelta(ch.position.y, th, vp)
                        scope.launch { runCatching { state.animateScrollBy(d) } }
                    }
                    var lastY = ch.position.y
                    while (true) {
                        val e2 = awaitPointerEvent(PointerEventPass.Initial)
                        val c2 = e2.changes.firstOrNull { it.id == ch.id } ?: break
                        c2.consume()
                        if (!c2.pressed) break
                        if (onThumb) {
                            val mm = metrics(vp)
                            val tt = mm?.let { ScrollbarMath.thumb(it.first, it.second, it.third, minThumb) }
                            if (mm != null && tt != null) state.dispatchRawDelta(ScrollbarMath.contentDelta(c2.position.y - lastY, mm.second, mm.third, tt.height))
                        }
                        lastY = c2.position.y
                    }
                    active = false
                }
            }
        }
        .drawWithContent {
            drawContent()
            val m = metrics(size.height) ?: return@drawWithContent
            val th = ScrollbarMath.thumb(m.first, m.second, m.third, 36.dp.toPx()) ?: return@drawWithContent
            val w = widthDp.dp.toPx()
            val x = size.width - w
            drawRoundRect(t.accent.copy(alpha = 0.16f * alpha), Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2))
            drawRoundRect(t.accent.copy(alpha = alpha), Offset(x, th.top), Size(w, th.height), CornerRadius(w / 2))
        }
}

/** Прокручиваемый список окна: LazyColumn с общей полосой прокрутки. */
@Composable
internal fun BarLazyColumn(
    modifier: Modifier = Modifier, verticalArrangement: Arrangement.Vertical = Arrangement.Top, content: LazyListScope.() -> Unit,
) {
    val st = rememberLazyListState()
    LazyColumn(modifier.verticalScrollbar(st), state = st, contentPadding = androidx.compose.foundation.layout.PaddingValues(end = ScrollbarMath.RESERVE_DP.dp), verticalArrangement = verticalArrangement, content = content)
}
