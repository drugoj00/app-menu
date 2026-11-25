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
    return Modifier.hoverable(src).pointerHoverIcon(PointerIcon.Hand) to (hovered && !HoverGate.muted.value)
}

/**
 * 25.11.2026 (замечание Евгения: убрал стрелку над виджетом, а выделение осталось): когда указатель аэромыши гаснет, Android не сообщает об уходе
 * наведения, и подсветка висела вместе с курсором пульта. Стрелка пульта гасит подсветку наведения, первое движение мыши возвращает её.
 */
object HoverGate {
    val muted = androidx.compose.runtime.mutableStateOf(false)
    fun onKey(ev: android.view.KeyEvent) {
        if (ev.action != android.view.KeyEvent.ACTION_DOWN) return
        val k = ev.keyCode
        if (k == android.view.KeyEvent.KEYCODE_DPAD_UP || k == android.view.KeyEvent.KEYCODE_DPAD_DOWN ||
            k == android.view.KeyEvent.KEYCODE_DPAD_LEFT || k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) muted.value = true
    }
    fun onMotion(ev: android.view.MotionEvent) {
        val a = ev.actionMasked
        if (muted.value && (a == android.view.MotionEvent.ACTION_HOVER_MOVE || a == android.view.MotionEvent.ACTION_HOVER_ENTER)) muted.value = false
    }
}

/** Правая кнопка мыши. Ставится раньше обычных нажатий в цепочке модификаторов, чтобы перехватить событие первой. */
fun Modifier.mouseSecondary(onSecondary: () -> Unit): Modifier =
    // не composed (дешевле в создании на каждой плитке): перезапускается, только если сменилась сама функция
    pointerInput(onSecondary) {
        awaitPointerEventScope {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                if (ev.type == PointerEventType.Press && ev.buttons.isSecondaryPressed) {
                    ev.changes.forEach { it.consume() }
                    onSecondary()
                }
            }
        }
    }
