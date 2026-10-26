package app.tvapps

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Мышь и аэромышь. Указатель рисует система (или сама аэромышь), здесь только то, что делает программа:
 * наведение подсвечивает элемент, правая кнопка открывает действия (без этого система превращает её в «Назад»).
 * Левая кнопка работает как касание, колесо прокручивает списки.
 */
@Composable
fun rememberHover(): Pair<Modifier, Boolean> {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    return Modifier.hoverable(src).pointerHoverIcon(PointerIcon.Hand) to hovered
}

/** Правая кнопка мыши. Ставится раньше обычных нажатий в цепочке модификаторов, чтобы перехватить событие первой. */
fun Modifier.mouseSecondary(onSecondary: () -> Unit): Modifier = composed {
    val cb by rememberUpdatedState(onSecondary)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                if (ev.type == PointerEventType.Press && ev.buttons.isSecondaryPressed) {
                    ev.changes.forEach { it.consume() }
                    cb()
                }
            }
        }
    }
}
