package app.tvapps

import android.view.MotionEvent
import android.view.View
import android.view.Window
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Мышь как палец: нажал левую кнопку, тянешь, отпустил, и список, строка значков, настройки прокручиваются так же, как пальцем.
 * Compose по умолчанию прокручивает мышью только колесом. Здесь нажатие, перемещение и отпускание левой кнопки получают тип «палец»,
 * поэтому всё, что листается пальцем, листается и мышью (долгое нажатие, правая кнопка, наведение и колесо не затрагиваются).
 */
object MouseAsTouch {
    /** Перехват касаний окна: события мыши с левой кнопкой превращаются в события пальца. */
    private class Wrapper(val base: Window.Callback) : Window.Callback by base {
        private var converting = false

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.pointerCount == 1 && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        converting = (event.buttonState and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_TERTIARY)) == 0
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {}
                }
                if (converting) {
                    val c = asFinger(event)
                    val r = base.dispatchTouchEvent(c)
                    c.recycle()
                    if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) converting = false
                    return r
                }
            } else if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) converting = false
            return base.dispatchTouchEvent(event)
        }
    }

    private fun asFinger(ev: MotionEvent): MotionEvent {
        val props = MotionEvent.PointerProperties().also { ev.getPointerProperties(0, it); it.toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().also { ev.getPointerCoords(0, it) }
        return MotionEvent.obtain(
            ev.downTime, ev.eventTime, ev.action, 1, arrayOf(props), arrayOf(coords),
            ev.metaState, ev.buttonState, ev.xPrecision, ev.yPrecision, ev.deviceId, ev.edgeFlags, ev.source, ev.flags,
        )
    }

    fun install(window: Window?) {
        val cb = window?.callback ?: return
        if (cb is Wrapper) return
        window.callback = Wrapper(cb)
    }
}

/** Ставится внутрь окна Compose (Dialog): включает «мышь как палец» и для него (у окна своя обработка касаний). */
@Composable
fun MouseDragInDialog() {
    val v: View = LocalView.current
    SideEffect {
        val w = (v as? DialogWindowProvider)?.window ?: (v.parent as? DialogWindowProvider)?.window
        MouseAsTouch.install(w)
    }
}

/**
 * Для окон-подсказок (Popup), у которых нет окна-обёртки: прокрутка перетаскиванием мышью (левая кнопка) вдоль оси.
 * Начинается после небольшого сдвига и забирает движение у вложенных нажатий, чтобы протаскивание не открывало пункт.
 */
fun Modifier.mouseDragScroll(state: ScrollableState, vertical: Boolean = true): Modifier = pointerInput(state, vertical) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent(PointerEventPass.Initial)
            val ch = down.changes.firstOrNull() ?: continue
            if (ch.type != PointerType.Mouse || !down.buttons.isPrimaryPressed || !ch.pressed || ch.previousPressed) continue
            val slop = viewConfiguration.touchSlop
            var moved = 0f
            var last = ch.position
            var taken = false
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val c = ev.changes.firstOrNull { it.id == ch.id } ?: break
                if (!c.pressed) break
                val d = if (vertical) c.position.y - last.y else c.position.x - last.x
                last = c.position
                moved += d
                if (!taken && kotlin.math.abs(moved) > slop) taken = true
                if (taken) {
                    state.dispatchRawDelta(-d)
                    c.consume()
                }
            }
        }
    }
}
