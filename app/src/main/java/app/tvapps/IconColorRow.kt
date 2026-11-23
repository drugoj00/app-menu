package app.tvapps

import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Выбор цвета значков верхней строки «Дома» (12.0, «Цвет значков строки состояния»): кнопки «Авто» (по умолчанию), «Белый», «Чёрный», образец выбранного цвета
 * и полоска: слева чёрный, дальше спектр, справа белый. Пульт: стрелки влево и вправо двигают ползунок ровными шагами без ускорения (не чаще [StepGate.MIN_MS]),
 * ОК переключает «Авто» и белый; касание и мышь: нажать или вести по полоске. Значение хранится в [Cfg.statusIconPos] (входит в резервную копию настроек).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IconColorRow() {
    val t = LocalTh.current
    val pos = Cfg.statusIconPos
    val auto = pos < 0f
    var focused by remember { mutableStateOf(false) }
    val (hm, hover) = rememberHover()
    val gate = remember { StepGate() }
    val label = stringResource(R.string.top_icol)
    val spectrum = remember { Brush.horizontalGradient((0..48).map { IconColor.at(it / 48f) }) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.top_o_auto), auto) { Cfg.changeStatusIconPos(-1f) }
            Chip(stringResource(R.string.top_o_white), !auto && pos >= 0.999f) { Cfg.changeStatusIconPos(1f) }
            Chip(stringResource(R.string.top_ic_black), !auto && pos <= 0.001f) { Cfg.changeStatusIconPos(0f) }
            // образец выбранного цвета (при «Авто» пусто: цвет задаёт тема)
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(if (auto) Color.Transparent else IconColor.at(pos)).border(2.dp, t.border, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (auto) androidx.compose.material3.Text("A", color = t.hint, fontSize = 14.sp) }
        }
        Column(
            Modifier.fillMaxWidth()
                .then(hm)
                .onPreviewKeyEvent { ev ->
                    if (OkShield.eats(ev)) return@onPreviewKeyEvent true
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val cur = Cfg.statusIconPos
                    when (ev.key) {
                        // из «Авто» первое нажатие ставит ползунок на соответствующий край полоски
                        Key.DirectionLeft -> { if (gate.ok()) Cfg.changeStatusIconPos(if (cur < 0f) 1f else cur - STEP); true }
                        Key.DirectionRight -> { if (gate.ok()) Cfg.changeStatusIconPos(if (cur < 0f) 0f else cur + STEP); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { Cfg.changeStatusIconPos(if (cur < 0f) 1f else -1f); true }
                        else -> false
                    }
                }
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label }
                .clip(RoundedCornerShape(12.dp))
                .drawBehind { drawRect(if (focused || hover) t.tileFocus else t.tile) }
                .focusBorder({ focused }, 3.dp, 1.dp, t.accent, t.border, RoundedCornerShape(12.dp))
                .focusable()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Canvas(
                Modifier.fillMaxWidth().height(28.dp)
                    .pointerInput(Unit) { detectTapGestures { p -> Cfg.changeStatusIconPos((p.x / size.width).coerceIn(0f, 1f)) } }
                    .pointerInput(Unit) { detectDragGestures { c, _ -> c.consume(); Cfg.changeStatusIconPos((c.position.x / size.width).coerceIn(0f, 1f)) } },
            ) {
                val h = size.height
                drawRoundRect(spectrum, Offset(0f, h * 0.2f), androidx.compose.ui.geometry.Size(size.width, h * 0.6f), CornerRadius(h * 0.3f))
                val cur = Cfg.statusIconPos
                val tx = (if (cur < 0f) 0.5f else cur) * size.width
                val x = tx.coerceIn(h * 0.5f, size.width - h * 0.5f)
                drawCircle(Color.White, h * 0.5f, Offset(x, h / 2f))
                drawCircle(Color.Black.copy(alpha = 0.35f), h * 0.5f, Offset(x, h / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                if (cur < 0f) drawCircle(Color(0xFFB0B0B0), h * 0.3f, Offset(x, h / 2f)) else drawCircle(IconColor.at(cur), h * 0.36f, Offset(x, h / 2f))
            }
        }
    }
}

/** Шаг ползунка пультом: 2 % длины полоски (50 нажатий от края до края). */
private const val STEP = 0.02f
