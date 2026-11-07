package app.tvapps

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Постоянный предел частоты шагов счётчиков: не чаще одного шага за [MIN_MS] мс, одинаково с первого повтора до последнего.
 * Защита от лавины событий (удержание кнопки пульта, поток нажатий из окна «Телефон в окне со звуком»): без разгона и без прогрессивного шага.
 */
internal class StepGate {
    private var last = 0L
    fun ok(): Boolean {
        val n = android.os.SystemClock.elapsedRealtime()
        if (last != 0L && n - last < MIN_MS) return false
        last = n
        return true
    }
    companion object {
        const val MIN_MS = 130L
        /** Пауза между нажатием и началом повторов при удержании мышью или пальцем. */
        const val HOLD_MS = 350L
    }
}

/** Нажатие «ОК»/Enter на пульте и клавиатуре: шаг на каждое нажатие и на каждый автоповтор, но не чаще предела; отпускание ничего не делает. */
internal fun Modifier.stepKeys(gate: StepGate, action: () -> Unit): Modifier = composed {
    val cur by rememberUpdatedState(action)
    onPreviewKeyEvent { ev ->
        if (ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter) {
            if (ev.type == KeyEventType.KeyDown && gate.ok()) runCatching { cur() }
            true
        } else false
    }
}

/** Удержание мышью или пальцем: шаг сразу, затем после паузы повторы ровным темпом (не чаще предела), пока кнопка не отпущена. */
internal fun Modifier.holdStep(gate: StepGate, action: () -> Unit): Modifier = composed {
    // действие обновляется при каждой перерисовке: иначе удержание держит устаревшее значение и каждый шаг ставит одно и то же число
    val cur by rememberUpdatedState(action)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            if (gate.ok()) runCatching { cur() }
            var wait = StepGate.HOLD_MS
            while (true) {
                val ev = withTimeoutOrNull(wait) { awaitPointerEvent() }
                if (ev == null) {
                    if (gate.ok()) runCatching { cur() }
                    wait = StepGate.MIN_MS
                    continue
                }
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                c.consume()
                if (!c.pressed) break
            }
        }
    }
}
