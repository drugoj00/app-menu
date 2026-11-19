package app.tvapps

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Единая анимация переходов (19.11.2026, слово владелицы: «анимация везде»): один переключатель «Анимация переходов» (Cfg.deskAnim) и один
 * «Стиль анимации» (Cfg.deskAnimStyle) управляют переходами меню↔«Дом», открытием и закрытием настроек и их вложенных окон, диалогами и меню,
 * столами. Все анимации идут с зафиксированным масштабом (DeskFullMotion), в лёгком режиме (Lite) не работают.
 */
object Motion {
    fun on(): Boolean = Cfg.deskAnim && !Lite.on
    @Volatile var pendingAt = 0L
    @Volatile var pendingDir = 1
}

/** Окно, у которого есть корневая анимация экрана. */
interface HasFx { val fx: ScreenFx }

/** Положение и вид экрана по значению сдвига [v] (от -ширины до +ширины); один и тот же набор стилей для столов, экранов и окон. */
fun GraphicsLayerScope.motionStyle(v: Float, w: Float, style: String) {
    val f = (v / w).coerceIn(-1f, 1f)
    val af = kotlin.math.abs(f)
    when (style) {
        "zoom" -> {
            translationX = v * 0.22f
            val s = 1f - af * 0.17f
            scaleX = s; scaleY = s
            alpha = (1f - af * 1.1f).coerceAtLeast(0f)
        }
        "fade" -> {
            val s = 1f - af * 0.09f
            scaleX = s; scaleY = s
            alpha = (1f - af * 1.1f).coerceAtLeast(0f)
        }
        "cube" -> {
            rotationY = f * 76f
            cameraDistance = 14f * density
            translationX = v * 0.1f
            alpha = (1f - af * 0.85f).coerceAtLeast(0.12f)
        }
        "parallax" -> {
            translationX = v
            val s = 1f - af * 0.06f
            scaleX = s; scaleY = s
        }
        else -> translationX = v
    }
}

class ScreenFx {
    val x = Animatable(0f)
    var w = android.content.res.Resources.getSystem().displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
    // у обычного MainScope нет часов кадров, и Animatable.animateTo падает: свои часы на Choreographer
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob() + ChoreoClock)
    @Volatile var resumed = false
    private var busy = false

    private suspend fun out(dir: Int) {
        val (outMs, _) = deskAnimDurations(Cfg.deskAnimStyle)
        val sgn = if (dir > 0) -1f else 1f
        withContext(DeskFullMotion) { x.animateTo(sgn * w, tween(outMs)) }
    }

    private suspend fun inn(dir: Int) {
        val (_, inMs) = deskAnimDurations(Cfg.deskAnimStyle)
        val sgn = if (dir > 0) -1f else 1f
        withContext(DeskFullMotion) {
            x.snapTo(-sgn * w * 0.5f)
            x.animateTo(0f, tween(inMs))
        }
    }

    /** Смена содержимого того же окна (настройки, вложенный экран): старое уезжает, [change] меняет данные, новое приезжает. */
    fun transition(dir: Int, change: () -> Unit) {
        if (!Motion.on() || busy) { change(); return }
        busy = true
        scope.launch {
            try {
                try { out(dir) } catch (e: CancellationException) { throw e } catch (_: Throwable) { }
                change()
                try { inn(dir) } catch (e: CancellationException) { throw e } catch (_: Throwable) { x.snapTo(0f) }
            } finally { busy = false }
        }
    }

    /** Уход в другое окно программы: текущий экран уезжает, затем запускается [action] (старт окна); новое окно само играет въезд в [onResume]. */
    fun leave(dir: Int, action: () -> Unit) {
        if (!Motion.on() || busy || !resumed) { action(); return }
        busy = true
        scope.launch {
            try {
                try { out(dir) } catch (e: CancellationException) { throw e } catch (_: Throwable) { }
                Motion.pendingDir = dir; Motion.pendingAt = android.os.SystemClock.elapsedRealtime()
                action()
                // запуск не удался или окно осталось на экране: вернуть экран на место
                delay(700)
                if (resumed) { Motion.pendingAt = 0L; withContext(DeskFullMotion) { x.animateTo(0f, tween(150)) } }
            } finally { busy = false }
        }
    }

    /** Вызывается из onResume окна: если мы пришли по переходу из своего же окна, играет въезд; иначе экран ставится на место. */
    fun onResume() {
        resumed = true
        val fresh = Motion.on() && Motion.pendingAt != 0L && android.os.SystemClock.elapsedRealtime() - Motion.pendingAt < 2500L
        if (fresh) {
            val dir = Motion.pendingDir
            Motion.pendingAt = 0L
            val sgn = if (dir > 0) -1f else 1f
            // до первого кадра: экран уже за краем, чтобы не мигнуть целым содержимым
            scope.launch(start = CoroutineStart.UNDISPATCHED) { x.snapTo(-sgn * w * 0.5f) }
            scope.launch { try { inn(dir) } catch (e: CancellationException) { throw e } catch (_: Throwable) { x.snapTo(0f) } }
        } else if (x.value != 0f) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { x.snapTo(0f) }
        }
        armWatch()
    }

    private var watch: Job? = null

    /**
     * Страховка (19.11.2026, разбор «чёрного экрана» у artoross): пока окно на экране, экран не может остаться сдвинутым или прозрачным без идущей анимации
     * (оборванный переход, сбой кадров, закрытое окно, куда ушёл переход). Если сдвиг не нулевой, ничего не движется и это держится две проверки подряд (~2,4 с),
     * экран возвращается на место. Обычные переходы и их ожидание (busy) не затрагиваются.
     */
    private fun armWatch() {
        watch?.cancel()
        watch = scope.launch {
            var stuck = 0
            while (true) {
                delay(1200)
                if (resumed && !busy && !x.isRunning && x.value != 0f) {
                    if (++stuck >= 2) { stuck = 0; android.util.Log.w("TVApps", "переход: экран остался сдвинутым (${x.value}), возвращён на место"); x.snapTo(0f) }
                } else stuck = 0
            }
        }
    }

    fun onPause() { resumed = false; watch?.cancel(); watch = null }
    fun destroy() { scope.cancel() }
}

/** Корневая анимация экрана окна (не в лёгком режиме). */
fun Modifier.screenFx(fx: ScreenFx): Modifier =
    if (Lite.on) this else this.onSizeChanged { fx.w = it.width.toFloat().coerceAtLeast(1f) }.graphicsLayer {
        val v = fx.x.value
        if (v != 0f) motionStyle(v, size.width.coerceAtLeast(1f), Cfg.deskAnimStyle)
    }

/** Переход в другое окно программы с анимацией (если включена); без неё просто выполняет [action]. */
fun leaveVia(ctx: Context, dir: Int, action: () -> Unit) {
    val fx = (ctx as? HasFx)?.fx
    if (fx == null) action() else fx.leave(dir, action)
}

/** Значение, смена которого (экран настроек, вложенное окно) идёт через анимацию окна; читается и пишется как обычная переменная. */
class MotionVar<T>(init: T, private val fxOf: () -> ScreenFx?, private val dirOf: (T) -> Int) {
    private val st = mutableStateOf(init)
    operator fun getValue(thisRef: Any?, p: kotlin.reflect.KProperty<*>): T = st.value
    operator fun setValue(thisRef: Any?, p: kotlin.reflect.KProperty<*>, v: T) {
        if (v == st.value) return
        val fx = fxOf()
        if (fx == null) st.value = v else fx.transition(dirOf(v)) { st.value = v }
    }
}

/** Появление окна, меню или панели: прозрачность и небольшое движение в выбранном стиле (при выключенной анимации и в лёгком режиме без изменений). */
@Composable
fun MotionPop(content: @Composable () -> Unit) {
    if (!Motion.on()) { content(); return }
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // страховка (19.11.2026, разбор «чёрного экрана» у artoross): окно, меню или панель не могут остаться невидимыми (прозрачность 0, а само окно
        // при этом перехватывало бы нажатия), если анимация не пошла или оборвалась; delay не зависит от кадров, через 0,6 с содержимое включается принудительно
        val guard = launch { delay(600); p.snapTo(1f) }
        try { withContext(DeskFullMotion) { p.animateTo(1f, tween(190)) } } finally { guard.cancel() }
    }
    Box(Modifier.graphicsLayer {
        val f = 1f - p.value
        if (f > 0f) when (Cfg.deskAnimStyle) {
            "zoom" -> { val s = 0.82f + 0.18f * p.value; scaleX = s; scaleY = s; alpha = p.value }
            "cube" -> { rotationY = f * 55f; cameraDistance = 14f * density; alpha = p.value }
            "parallax" -> { translationX = f * 0.12f * size.width; alpha = p.value }
            "fade" -> { alpha = p.value }
            else -> { translationY = f * 0.07f * size.height; alpha = p.value }
        }
    }) { content() }
}

/** Окно Compose с анимацией появления (то же, что Dialog из библиотеки, но содержимое появляется в выбранном стиле). */
@Composable
fun MotionDialog(onDismissRequest: () -> Unit, properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties(), content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) { MotionPop(content) }
}

/** Содержимое всплывающего меню: уменьшение на телефоне (HalfUi) и появление в выбранном стиле. */
@Composable
fun HalfUiPop(content: @Composable () -> Unit) { MotionPop { HalfUi(content) } }

/** Часы кадров на Choreographer для анимаций вне композиции (переходы между окнами). */
private object ChoreoClock : androidx.compose.runtime.MonotonicFrameClock {
    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = kotlinx.coroutines.suspendCancellableCoroutine { c ->
        val cb = android.view.Choreographer.FrameCallback { t -> c.resumeWith(runCatching { onFrame(t) }) }
        android.view.Choreographer.getInstance().postFrameCallback(cb)
        c.invokeOnCancellation { android.view.Choreographer.getInstance().removeFrameCallback(cb) }
    }
}
