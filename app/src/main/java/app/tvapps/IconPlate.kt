package app.tvapps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Подложка значка: один общий вид для всех режимов сетки (обычная, плотная, список) и всех устройств: скруглённый квадрат ТОЛЬКО вокруг значка,
 * подпись рисуется вне подложки, цвет и обводка по теме. [plate]: размер подложки; [icon]: размер значка на подложке (3/4 подложки);
 * [iconOff]: размер значка, когда подложки выключены в настройках (подложка не рисуется, значок на том же месте, раскладка не прыгает).
 * Зазор между подложками соседних ячеек задают GridMetrics (плотная сетка) и поля плитки (обычная): не меньше GridMetrics.minGapDp.
 */
@Composable
internal fun IconPlate(plate: Dp, icon: Dp, iconOff: Dp, bmp: ImageBitmap, modifier: Modifier = Modifier, fill: Int = Cfg.iconFill) {
    val t = LocalTh.current
    // 15.0: «Значок на плитке» (настройка, 75% = прежний вид): доля значка в подложке, не больше самой подложки
    val k = fill / 75f
    val icon = (icon * k).let { if (it > plate) plate else it }
    val iconOff = (iconOff * k).let { if (it > plate) plate else it }
    if (!Cfg.plates) {
        Box(modifier.size(plate), contentAlignment = Alignment.Center) { Image(bmp, null, Modifier.size(iconOff)) }
        return
    }
    val shape = RoundedCornerShape(percent = 20)
    Box(
        modifier.size(plate)
            .then(if (Cfg.transparentTiles) Modifier else Modifier.background(t.tile, shape))
            .border(1.dp, t.border, shape),
        contentAlignment = Alignment.Center,
    ) { Image(bmp, null, Modifier.size(icon)) }
}
