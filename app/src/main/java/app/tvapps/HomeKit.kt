@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package app.tvapps

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.core.animateFloatAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ значки (контуры Material)

internal object HIcons {
    private fun v(name: String, d: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black)).build()

    val bt = v("bt", "M17.71,7.71L12,2h-1v7.59L6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 11,14.41V22h1l5.71,-5.71 -4.3,-4.29 4.3,-4.29zM13,5.83l1.88,1.88L13,9.59V5.83zM14.88,16.29L13,18.17v-3.76l1.88,1.88z")
    val btOff = v("btOff", "M13,5.83l1.88,1.88 -1.6,1.6 1.41,1.41 3.02,-3.02L12,2h-1v5.03l2,2v-3.2zM5.41,4L4,5.41 10.59,12 5,17.59 6.41,19 11,14.41V22h1l4.29,-4.29 2.3,2.29L20,18.59 5.41,4zM13,18.17v-3.76l1.88,1.88L13,18.17z")
    val wifi = v("wifi", "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9zM9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0zM5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z")
    val wifiOff = v("wifiOff", "M22.99,9C19.15,5.16 13.8,3.76 8.84,4.78l2.52,2.52c3.47,-0.17 6.99,1.05 9.63,3.7l2,-2zM18.99,13c-1.29,-1.29 -2.84,-2.13 -4.49,-2.56l3.53,3.53 0.96,-0.97zM2,3.05L5.07,6.1C3.6,6.82 2.22,7.78 1,9l1.99,2c1.24,-1.24 2.67,-2.16 4.2,-2.77l2.24,2.24C7.81,10.89 6.27,11.73 5,13v0.01L6.99,15c1.36,-1.36 3.14,-2.04 4.92,-2.06L18.98,20l1.27,-1.26L3.29,1.79 2,3.05zM9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0z")
    val eth = v("eth", "M7.77,6.76L6.23,5.48 0.82,12l5.41,6.52 1.54,-1.28L3.42,12l4.35,-5.24zM7,13h2v-2L7,11v2zM17,11h-2v2h2v-2zM11,13h2v-2h-2v2zM17.77,5.48l-1.54,1.28L20.58,12l-4.35,5.24 1.54,1.28L23.18,12l-5.41,-6.52z")
    val usb = v("usb", "M15,7v4h1v2h-3V5h2l-3,-4 -3,4h2v8H8v-2.07c0.7,-0.37 1.2,-1.08 1.2,-1.93 0,-1.21 -0.99,-2.2 -2.2,-2.2 -1.21,0 -2.2,0.99 -2.2,2.2 0,0.85 0.5,1.56 1.2,1.93V13c0,1.11 0.89,2 2,2h3v3.05c-0.71,0.37 -1.2,1.1 -1.2,1.95 0,1.22 0.99,2.2 2.2,2.2 1.21,0 2.2,-0.98 2.2,-2.2 0,-0.85 -0.49,-1.58 -1.2,-1.95V15h3c1.11,0 2,-0.89 2,-2v-2h1V7h-4z")
    val mic = v("mic", "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z")
    val apps = v("apps", "M4,8h4V4H4V8zM10,20h4v-4h-4V20zM4,20h4v-4H4V20zM4,14h4v-4H4V14zM10,14h4v-4h-4V14zM16,4v4h4V4H16zM10,8h4V4h-4V8zM16,14h4v-4h-4V14zM16,20h4v-4h-4V20z")
    val gear = v("gear", "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94 0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94l-0.36,-2.54c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41l-0.36,2.54c-0.59,0.24 -1.13,0.57 -1.62,0.94l-2.39,-0.96c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87c-0.12,0.21 -0.08,0.47 0.12,0.61l2.03,1.58c-0.05,0.3 -0.09,0.63 -0.09,0.94s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6 3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z")
    val input = v("input", "M21,3.01H3c-1.1,0 -2,0.9 -2,2V9h2V4.99h18v14.03H3V15H1v4.01c0,1.1 0.9,1.98 2,1.98h18c1.1,0 2,-0.88 2,-1.98v-14c0,-1.11 -0.9,-1.99 -2,-1.99zM11,16l4,-4 -4,-4v3H1v2h10v3z")
    val search = v("search", "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z")
    val hdmi = v("hdmi", "M21,3L3,3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5c0,-1.1 -0.9,-2 -2,-2zM21,17L3,17L3,5h18v12zM9,7h2v4L9,11zM13,7h2v4h-2z")
    val mobile = v("mobile", "M17,4h3v16h-3V4zM5,14h3v6H5v-6zm6,-5h3v11h-3V9z")
    val bell = v("bell", "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z")
    val recent = v("recent", "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8L12,8z")
    val add = v("add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
}

/** Тема без подстройки под фон: окна настроек всегда в цветах выбранной темы, даже когда сам экран на тёмном фоне. */
internal val LocalBaseTh = androidx.compose.runtime.compositionLocalOf<TC?> { null }

/** «Дом» рисуется без увеличения шрифта системы: размеры строк и надписей задаются самой программой. */
@Composable
internal fun FixedFont(content: @Composable () -> Unit) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    val narrow = LocalConfiguration.current.smallestScreenWidthDp < 600
    // на телефоне элементы мельче (0,6), надписи чуть меньше обычных: на экране помещается больше строк
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides (if (narrow) androidx.compose.ui.unit.Density(d.density * 0.6f, 1.4f) else androidx.compose.ui.unit.Density(d.density, 1f)),
    ) { content() }
}

// ------------------------------------------------------------------ защита от «хвоста» долгого нажатия ОК

/** После долгого «ОК» окно открывается, пока кнопка ещё зажата: её повторы и отпускание не должны попасть в первый пункт. */
internal object OkShield {
    @Volatile private var until = 0L
    fun arm() { until = android.os.SystemClock.elapsedRealtime() + 4000 }
    fun eats(ev: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val ok = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
        if (!ok || until == 0L) return false
        if (android.os.SystemClock.elapsedRealtime() > until) { until = 0L; return false }
        if (ev.type == KeyEventType.KeyUp) until = 0L
        return true
    }
}

// ------------------------------------------------------------------ плитка «Дома»

/**
 * Нажимаемая плитка «Дома»: пульт (короткое ОК запускает, долгое ОК или «Меню» открывает список настроек),
 * касание и мышь (долгое нажатие и правая кнопка открывают список, после долгого нажатия можно тянуть вбок).
 * В режиме перемещения стрелки двигают плитку, ОК заканчивает.
 */
@Composable
internal fun HomeBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    bg: Color = Color.Transparent,
    moving: Boolean = false,
    focusReq: FocusRequester? = null,
    stepPx: Float = 220f,
    onMoveKey: (Key) -> Boolean = { false },
    onStep: (Int) -> Unit = {},
    onMoveEnd: () -> Unit = {},
    onClick: () -> Unit,
    onLong: () -> Unit,
    /** своя рамка при наведении и выборе пультом (цвет и толщина, dp); без неё цвет темы */
    ringColor: Color? = null,
    ringWidth: Int = 0,
    content: @Composable BoxScope.() -> Unit,
) {
    val t = LocalTh.current
    var focused by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var downAt by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    val (hm, hover) = rememberHover()
    val scale by animateFloatAsState(if (focused || hover) 1.04f else 1f, label = "hs")
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLong)
    val step by rememberUpdatedState(onStep)
    val moveKey by rememberUpdatedState(onMoveKey)
    val moveEnd by rememberUpdatedState(onMoveEnd)
    var suppressUntil by remember { mutableStateOf(0L) }
    Box(
        modifier
            .zIndex(if (focused || hover) 1f else 0f)      // выбранный элемент выше соседей: увеличенная рамка не прячется под правым
            .scale(scale)
            .then(hm)
            .then(if (focusReq != null) Modifier.focusRequester(focusReq) else Modifier)
            .onPreviewKeyEvent { ev ->
                if (moving) {
                    return@onPreviewKeyEvent when (ev.key) {
                        Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown ->
                            if (ev.type == KeyEventType.KeyDown) moveKey(ev.key) else true
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Back -> { if (ev.type == KeyEventType.KeyUp) moveEnd(); true }
                        else -> false
                    }
                }
                when (ev.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        when (ev.type) {
                            KeyEventType.KeyDown -> {
                                val rc = ev.nativeKeyEvent.repeatCount
                                if (rc == 0) {
                                    pending = true
                                    downAt = android.os.SystemClock.uptimeMillis()
                                    // у части пультов при удержании нет повторов: долгое нажатие определяем по времени
                                    scope.launch {
                                        delay(LONG_OK_MS)
                                        if (pending) { pending = false; OkShield.arm(); long() }
                                    }
                                } else if (pending) { pending = false; OkShield.arm(); long() }
                            }
                            KeyEventType.KeyUp -> if (pending) {
                                pending = false
                                if (android.os.SystemClock.uptimeMillis() - downAt >= LONG_OK_MS) long() else click()
                            }
                            else -> {}
                        }
                        true
                    }
                    Key.Menu, Key.Info -> { if (ev.type == KeyEventType.KeyDown) long(); true }
                    else -> false
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused || hover) t.tileFocus else bg, shape)
            .border(
                if (moving) 3.dp else if (focused) (if (ringColor != null && ringWidth > 0) ringWidth.dp else 3.dp) else if (hover) (if (ringColor != null && ringWidth > 0) ringWidth.dp else 1.dp) else 0.dp,
                if (moving) Color(0xFFF59E0B) else if (focused || hover) (ringColor ?: t.accent) else Color.Transparent, shape,
            )
            .mouseSecondary { long() }
            .combinedClickable(
                onClick = { if (android.os.SystemClock.elapsedRealtime() > suppressUntil) click() },
                onLongClick = { suppressUntil = android.os.SystemClock.elapsedRealtime() + 700; long() },
            )
            .pointerInput(Unit) {
                var acc = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { acc = 0f },
                    onDrag = { change, amount ->
                        change.consume()
                        acc += amount.x
                        while (acc > stepPx) { acc -= stepPx; step(1) }
                        while (acc < -stepPx) { acc += stepPx; step(-1) }
                    },
                )
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

// ------------------------------------------------------------------ списки и окна

class Opt(
    val label: String,
    val value: String = "",
    /** стрелки влево/вправо меняют значение */
    val onDec: (() -> Unit)? = null,
    val onInc: (() -> Unit)? = null,
    val danger: Boolean = false,
    val icon: ImageBitmap? = null,
    /** картинка широкая (превью баннера 16:9), а не маленький значок */
    val wide: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

@Composable
private fun OptRow(o: Opt, req: FocusRequester?) {
    val t = LocalTh.current
    var focused by remember { mutableStateOf(false) }
    val (hm, hover) = rememberHover()
    val stepper = o.onDec != null && o.onInc != null
    Row(
        Modifier.fillMaxWidth()
            .then(hm)
            .then(if (req != null) Modifier.focusRequester(req) else Modifier)
            .onPreviewKeyEvent { ev ->
                if (OkShield.eats(ev)) return@onPreviewKeyEvent true
                if (stepper && ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) { o.onDec?.invoke(); true }
                else if (stepper && ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight) { o.onInc?.invoke(); true }
                else false
            }
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused || hover) t.tileFocus else t.tile)
            .border(if (focused) 3.dp else 1.dp, if (focused) t.accent else t.border, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = { (o.onClick ?: o.onInc)?.invoke() })
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        o.icon?.let { Image(it, null, if (o.wide) Modifier.size(128.dp, 72.dp).clip(RoundedCornerShape(8.dp)) else Modifier.size(30.dp)) }
        Text(o.label, color = if (o.danger) Color(0xFFF87171) else t.text, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (stepper) {
            Text("‹", color = t.sub, fontSize = 20.sp, modifier = Modifier.combinedClickable(onClick = { o.onDec?.invoke() }).padding(horizontal = 8.dp))
            Text(o.value, color = t.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("›", color = t.sub, fontSize = 20.sp, modifier = Modifier.combinedClickable(onClick = { o.onInc?.invoke() }).padding(horizontal = 8.dp))
        } else if (o.value.isNotEmpty()) Text(o.value, color = t.accent, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
    }
}

/** Окно со списком пунктов: пульт, касание и мышь. Стрелки влево/вправо на пункте-счётчике меняют число. */
@Composable
internal fun ListDialog(title: String, opts: List<Opt>, onClose: () -> Unit, hint: String = "", onRawKey: ((android.view.KeyEvent) -> Boolean)? = null) {
    val t = LocalBaseTh.current ?: LocalTh.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.86f).dp
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FixedFont { androidx.compose.runtime.CompositionLocalProvider(LocalTh provides t) { Column(
            Modifier.widthIn(min = 320.dp, max = 540.dp).heightIn(max = maxH)
                .then(if (onRawKey != null) Modifier.onPreviewKeyEvent { onRawKey(it.nativeKeyEvent) } else Modifier)
                .clip(RoundedCornerShape(20.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = t.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (hint.isNotEmpty()) Text(hint, color = t.hint, fontSize = 12.sp)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(opts) { i, o -> OptRow(o, if (i == 0) first else null) }
            }
        } } }
    }
}

/** Окно ввода строки. */
@Composable
internal fun TextDialog(title: String, initial: String, hint: String = "", onDone: (String) -> Unit, onClose: () -> Unit) {
    val t = LocalBaseTh.current ?: LocalTh.current
    var text by remember { mutableStateOf(initial) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FixedFont { androidx.compose.runtime.CompositionLocalProvider(LocalTh provides t) { Column(
            Modifier.widthIn(min = 320.dp, max = 520.dp).clip(RoundedCornerShape(20.dp)).background(t.bg)
                .border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = t.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                text, { text = it }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = t.text, unfocusedTextColor = t.text,
                    focusedBorderColor = t.accent, unfocusedBorderColor = t.border, cursorColor = t.accent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(fr),
            )
            if (hint.isNotEmpty()) Text(hint, color = t.hint, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(androidx.compose.ui.res.stringResource(R.string.cancel), false) { onClose() }
                Chip(androidx.compose.ui.res.stringResource(R.string.home_apply), true) { onDone(text.trim()) }
            }
        } } }
    }
}

// ------------------------------------------------------------------ переход в системные настройки

/** Пробует открыть системную страницу настроек по очереди; последний вариант — общие настройки. */
internal fun openSys(ctx: android.content.Context, vararg actions: String): Boolean {
    for (a in actions.toList() + android.provider.Settings.ACTION_SETTINGS) {
        val ok = runCatching { ctx.startActivity(android.content.Intent(a).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (ok) return true
    }
    return false
}
