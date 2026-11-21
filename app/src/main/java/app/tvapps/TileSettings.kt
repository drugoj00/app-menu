package app.tvapps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

/**
 * Блок «Плитки» (15.0): «Размер значка» (масштаб значков, прежний ключ iscale), «Значок на плитке» (доля значка в плитке, icon_fill),
 * «Увеличение при выделении» (focus_zoom), кнопка «По умолчанию» и живой предпросмотр плитки в покое и выделенной. Прежние ключи настроек не меняются.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TilesBlock() {
    val t = LocalTh.current
    // предпросмотр сверху: на ТВ и в ландшафте он виден сразу при изменении счётчиков (раньше стоял под ними, за краем экрана)
    Section(stringResource(R.string.tl_preview)) { TilePreview() }
    Section(stringResource(R.string.tl_size_title)) {
        FlowRow(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GridNum(
                Cfg.iconScale.coerceIn(50, 200), stringResource(R.string.tl_size_cap), { "$it%" },
                onPlus = { Cfg.changeIconScale(Steps.rangeStep(Cfg.iconScale, +1, 50, 200, 10)) },
                onMinus = { Cfg.changeIconScale(Steps.rangeStep(Cfg.iconScale, -1, 50, 200, 10)) },
                onReset = { Cfg.changeIconScale(100) }, range = 50..200, onSet = { Cfg.changeIconScale(it) },
                modifier = Modifier.width(156.dp),
            )
            GridNum(
                Cfg.iconFill, stringResource(R.string.tl_fill_cap), { "$it%" },
                onPlus = { Cfg.changeIconFill(Steps.rangeStep(Cfg.iconFill, +1, 50, 100, 5)) },
                onMinus = { Cfg.changeIconFill(Steps.rangeStep(Cfg.iconFill, -1, 50, 100, 5)) },
                onReset = { Cfg.changeIconFill(75) }, range = 50..100, onSet = { Cfg.changeIconFill(it) },
                modifier = Modifier.width(156.dp),
            )
            GridNum(
                Cfg.focusZoom, stringResource(R.string.tl_zoom_cap), { "$it%" },
                onPlus = { Cfg.changeFocusZoom(Steps.rangeStep(Cfg.focusZoom, +1, 0, 30, 5)) },
                onMinus = { Cfg.changeFocusZoom(Steps.rangeStep(Cfg.focusZoom, -1, 0, 30, 5)) },
                onReset = { Cfg.changeFocusZoom(0) }, range = 0..30, onSet = { Cfg.changeFocusZoom(it) },
                modifier = Modifier.width(156.dp),
            )
        }
        Text(stringResource(R.string.tl_size_hint), color = t.hint, fontSize = hintSp(12))
        Text(stringResource(R.string.tl_fill_hint), color = t.hint, fontSize = hintSp(12))
        Text(stringResource(R.string.tl_zoom_hint), color = t.hint, fontSize = hintSp(12))
        Chip("↺ " + stringResource(R.string.tl_defaults), false) { Cfg.changeIconScale(100); Cfg.changeIconFill(75); Cfg.changeFocusZoom(0) }
    }
}

/** Живой предпросмотр: две плитки (в покое и выделенная) с теми же размерами, подложкой и увеличением, что в меню. Значок самой программы. */
@Composable
private fun TilePreview() {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val bmp = remember { runCatching { ctx.packageManager.getApplicationIcon(ctx.packageName).toBitmap(128, 128).asImageBitmap() }.getOrNull() }
    // размер подложки как у меню (пресет «Размер значков» и масштаб), но не больше 96 dp, чтобы две плитки поместились рядом
    val plate: Dp = ((when (Cfg.iconSize) { 3 -> 34f; 0 -> 52f; 2 -> 96f; else -> 72f }) * Cfg.iconScale / 100f).coerceIn(30f, 96f).dp
    val zoom = 1f + Cfg.focusZoom / 100f
    val boxW = (plate.value + 54f).dp
    val boxH = (plate.value + 96f).dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Top) {
        listOf(false, true).forEach { sel ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.width(boxW).height(boxH), contentAlignment = Alignment.Center) {
                    val shape = RoundedCornerShape(14.dp)
                    Column(
                        Modifier
                            .graphicsLayer { if (sel) { scaleX = zoom; scaleY = zoom } }
                            .clip(shape)
                            .background(if (sel) t.tileFocus else Color.Transparent, shape)
                            .border(if (sel) 3.dp else 1.dp, if (sel) Cfg.ringOr(t.accent) else Cfg.ringOr(t.accent).copy(alpha = 0.35f), shape)
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (bmp != null) IconPlate(plate, plate * 0.75f, plate, bmp) else Spacer(Modifier.height(plate))
                        if (Cfg.showLabels) Text(stringResource(R.string.tl_prev_label), color = t.text, fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                Text(stringResource(if (sel) R.string.tl_prev_sel else R.string.tl_prev_rest), color = t.hint, fontSize = hintSp(12))
            }
        }
    }
}
