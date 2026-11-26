@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package app.tvapps

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
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

    /** Планка оперативной памяти (кнопка «Очистка памяти»). */
    val memory = v("memory", "M3,6h18v10H3zM5,8v6h14V8zM7,10h2v2H7zM11,10h2v2h-2zM15,10h2v2h-2zM6,16h2v3H6zM10,16h2v3h-2zM14,16h2v3h-2zM18,16h2v3h-2z")
    val bt = v("bt", "M17.71,7.71L12,2h-1v7.59L6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 11,14.41V22h1l5.71,-5.71 -4.3,-4.29 4.3,-4.29zM13,5.83l1.88,1.88L13,9.59V5.83zM14.88,16.29L13,18.17v-3.76l1.88,1.88z")
    val btOff = v("btOff", "M13,5.83l1.88,1.88 -1.6,1.6 1.41,1.41 3.02,-3.02L12,2h-1v5.03l2,2v-3.2zM5.41,4L4,5.41 10.59,12 5,17.59 6.41,19 11,14.41V22h1l4.29,-4.29 2.3,2.29L20,18.59 5.41,4zM13,18.17v-3.76l1.88,1.88L13,18.17z")
    val wifi = v("wifi", "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9zM9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0zM5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z")
    val wifiOff = v("wifiOff", "M22.99,9C19.15,5.16 13.8,3.76 8.84,4.78l2.52,2.52c3.47,-0.17 6.99,1.05 9.63,3.7l2,-2zM18.99,13c-1.29,-1.29 -2.84,-2.13 -4.49,-2.56l3.53,3.53 0.96,-0.97zM2,3.05L5.07,6.1C3.6,6.82 2.22,7.78 1,9l1.99,2c1.24,-1.24 2.67,-2.16 4.2,-2.77l2.24,2.24C7.81,10.89 6.27,11.73 5,13v0.01L6.99,15c1.36,-1.36 3.14,-2.04 4.92,-2.06L18.98,20l1.27,-1.26L3.29,1.79 2,3.05zM9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0z")
    val eth = v("eth", "M7.77,6.76L6.23,5.48 0.82,12l5.41,6.52 1.54,-1.28L3.42,12l4.35,-5.24zM7,13h2v-2L7,11v2zM17,11h-2v2h2v-2zM11,13h2v-2h-2v2zM17.77,5.48l-1.54,1.28L20.58,12l-4.35,5.24 1.54,1.28L23.18,12l-5.41,-6.52z")
    val usb = v("usb", "M15,7v4h1v2h-3V5h2l-3,-4 -3,4h2v8H8v-2.07c0.7,-0.37 1.2,-1.08 1.2,-1.93 0,-1.21 -0.99,-2.2 -2.2,-2.2 -1.21,0 -2.2,0.99 -2.2,2.2 0,0.85 0.5,1.56 1.2,1.93V13c0,1.11 0.89,2 2,2h3v3.05c-0.71,0.37 -1.2,1.1 -1.2,1.95 0,1.22 0.99,2.2 2.2,2.2 1.21,0 2.2,-0.98 2.2,-2.2 0,-0.85 -0.49,-1.58 -1.2,-1.95V15h3c1.11,0 2,-0.89 2,-2v-2h1V7h-4z")
    val mic = v("mic", "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z")
    /** «Глазик» в полях пароля: показать и скрыть. */
    val eye = v("eye", "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z")
    val eyeOff = v("eyeOff", "M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89 3.43,-4.75 -1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16,2.16C10.74,7.13 11.35,7 12,7zM2,4.27l2.28,2.28 0.46,0.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-0.3 4.38,-0.84l0.42,0.42L19.73,22 21,20.73 3.27,3 2,4.27zM7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.66 1.34,3 3,3 0.22,0 0.44,-0.03 0.65,-0.08l1.55,1.55c-0.67,0.33 -1.41,0.53 -2.2,0.53 -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53 0.53,-2.2zM11.84,9.02l3.15,3.15 0.02,-0.16c0,-1.66 -1.34,-3 -3,-3l-0.17,0.01z")
    val apps = v("apps","M4,8h4V4H4V8zM10,20h4v-4h-4V20zM4,20h4v-4H4V20zM4,14h4v-4H4V14zM10,14h4v-4h-4V14zM16,4v4h4V4H16zM10,8h4V4h-4V8zM16,14h4v-4h-4V14zM16,20h4v-4h-4V20z")
    val gear = v("gear", "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94 0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94l-0.36,-2.54c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41l-0.36,2.54c-0.59,0.24 -1.13,0.57 -1.62,0.94l-2.39,-0.96c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87c-0.12,0.21 -0.08,0.47 0.12,0.61l2.03,1.58c-0.05,0.3 -0.09,0.63 -0.09,0.94s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6 3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z")
    val input = v("input", "M21,3.01H3c-1.1,0 -2,0.9 -2,2V9h2V4.99h18v14.03H3V15H1v4.01c0,1.1 0.9,1.98 2,1.98h18c1.1,0 2,-0.88 2,-1.98v-14c0,-1.11 -0.9,-1.99 -2,-1.99zM11,16l4,-4 -4,-4v3H1v2h10v3z")
    val search = v("search", "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z")
    val hdmi = v("hdmi", "M21,3L3,3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5c0,-1.1 -0.9,-2 -2,-2zM21,17L3,17L3,5h18v12zM9,7h2v4L9,11zM13,7h2v4h-2z")
    val mobile = v("mobile", "M17,4h3v16h-3V4zM5,14h3v6H5v-6zm6,-5h3v11h-3V9z")
    val bell = v("bell", "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z")
    val recent = v("recent", "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8L12,8z")
    val add = v("add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    val download = v("download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
    val refresh = v("refresh", "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z")
    val save = v("save", "M17,3H5C3.89,3 3,3.9 3,5v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V7l-4,-4zM12,19c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3zM15,9H5V5h10v4z")
    val folder = v("folder", "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z")
    val open = v("open", "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7zM14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z")
    val trash = v("trash", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z")
    val list = v("list", "M3,13h2v-2H3v2zM3,17h2v-2H3v2zM3,9h2V7H3v2zM7,13h14v-2H7v2zM7,17h14v-2H7v2zM7,9h14V7H7v2z")
    val dock = v("dock", "M3,3h18v18H3zM5,5v10h14V5zM5,17h14v2H5z")
    val home = v("home", "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z")
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
        androidx.compose.ui.platform.LocalDensity provides (if (narrow) androidx.compose.ui.unit.Density(d.density * 0.6f * LocalDialogBoost.current, 1.4f) else androidx.compose.ui.unit.Density(d.density, if (TopMode.isTv(androidx.compose.ui.platform.LocalContext.current)) TV_TEXT_BOOST else 1f)),
        LocalTvBoost provides (!narrow && TopMode.isTv(androidx.compose.ui.platform.LocalContext.current)),
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
    /** долгое ОК на пульте: если задано, вместо окна действий сразу начинается перемещение (окно не перекрывает экран); «Меню» и «Инфо» по-прежнему открывают окно */
    onLongKey: (() -> Unit)? = null,
    /** своя рамка при наведении и выборе пультом (цвет и толщина, dp); без неё цвет темы */
    ringColor: Color? = null,
    ringWidth: Int = 0,
    /** подсветка выбранного элемента поверх содержимого (у значков и баннеров иначе при выключенной рамке не видно, где фокус) */
    focusTint: Boolean = true,
    /** перетаскивание плитки (зажать и двигать, мышью или пальцем): начало, движение, отпускание; позиции в корне экрана */
    dragStart: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    dragMove: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    dragEnd: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    /** ключ плитки: курсор пульта запоминается и возвращается на неё после закрытия окон */
    memKey: String? = null,
    /** тонкая рамка в покое (вид «Стекло» верхней строки); Transparent: без рамки */
    edge: Color = Color.Transparent,
    /** 15.0: увеличение плитки при выделении по настройке «Увеличение при выделении» (только у плиток-значков рядов «Дома») */
    zoomOnFocus: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val selfReq = focusReq ?: remember { FocusRequester() }
    androidx.compose.runtime.DisposableEffect(memKey) {
        if (memKey != null) TileFocus.reqs[memKey] = selfReq
        onDispose { if (memKey != null && TileFocus.reqs[memKey] === selfReq) TileFocus.reqs.remove(memKey) }
    }
    var boxCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val snd = rememberClickSound()
    val t = LocalTh.current
    // 15.1 (плавность): фокус читается только при размещении и рисовании (z-порядок, фон, рамка), поэтому перемещение курсора по «Дому» не перекомпонует плитки
    val focusedS = remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var downAt by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    val (hm, hover) = rememberHover()
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLong)
    val longKey by rememberUpdatedState(onLongKey)
    val step by rememberUpdatedState(onStep)
    val moveKey by rememberUpdatedState(onMoveKey)
    val moveEnd by rememberUpdatedState(onMoveEnd)
    var suppressUntil by remember { mutableStateOf(0L) }
    val zoomTarget = if (zoomOnFocus && Cfg.focusZoom > 0 && (focusedS.value || hover) && !moving) 1f + Cfg.focusZoom / 100f else 1f
    val zoomNow = if (zoomOnFocus) fixedAnimateFloatAsState(zoomTarget, label = "hz").value else 1f
    Box(
        modifier
            .layout { m, c -> val pl = m.measure(c); layout(pl.width, pl.height) { pl.place(0, 0, if (focusedS.value || hover) 1f else 0f) } }   // то же, что zIndex, но состояние читается при размещении
            .then(if (zoomNow != 1f) Modifier.graphicsLayer { scaleX = zoomNow; scaleY = zoomNow } else Modifier)      // выбранный элемент выше соседей: рамка не прячется под правым (увеличения плитки нет)
            .then(hm)
            .focusRequester(selfReq)
            .onPreviewKeyEvent { ev ->
                if (moving) {
                    // хвост долгого ОК (повторы и отпускание), которым началось перемещение, перемещение не заканчивает
                    if (OkShield.eats(ev)) return@onPreviewKeyEvent true
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
                                        if (pending) { pending = false; OkShield.arm(); (longKey ?: long)() }
                                    }
                                } else if (pending) { pending = false; OkShield.arm(); (longKey ?: long)() }
                            }
                            KeyEventType.KeyUp -> if (pending) {
                                pending = false
                                if (android.os.SystemClock.uptimeMillis() - downAt >= LONG_OK_MS) (longKey ?: long)() else click()
                            }
                            else -> {}
                        }
                        true
                    }
                    Key.Menu, Key.Info -> { if (ev.type == KeyEventType.KeyDown) long(); true }
                    else -> false
                }
            }
            .onFocusChanged { focusedS.value = it.isFocused; if (memKey != null) { if (it.isFocused) { TileFocus.last = memKey; TileFocus.cur = memKey } else if (TileFocus.cur == memKey) TileFocus.cur = null } }
            // неоновый ореол вокруг выбранного (настройка «Неоновый ореол вокруг выбранного»: Нет, Слабое, Сильное): цвет рамки, расходится наружу за край плитки
            .drawBehind {
                val g = Cfg.selGlow
                if (g != "none" && (focusedS.value || hover) && !moving) {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    val cr = (outline as? androidx.compose.ui.graphics.Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
                    drawSelGlow(g, ringColor ?: Cfg.homeRingOr(t.accent), cr)
                }
            }
            .clip(shape)
            .drawBehind {
                val c = if (focusedS.value || hover) t.tileFocus else bg
                if (c.alpha > 0f) drawOutline(shape.createOutline(size, layoutDirection, this), c)
            }
            .drawWithContent {
                drawContent()
                val f = focusedS.value || hover
                // рамка выделения (наведение мышью и выбор пультом одинаково): по умолчанию толщина 2, цвет белый, только у выделенного; в покое тонкая рамка «Стекло», если задана
                val w = if (moving) 3.dp else if (f) (if (ringColor != null && ringWidth > 0) ringWidth.dp else 2.dp) else if (edge != Color.Transparent) 1.5.dp else 0.dp
                // подсветка выбранного поверх содержимого, затем рамка поверх неё (как в прежнем порядке модификаторов)
                if (focusTint && !moving && f) drawRect(Color.White.copy(alpha = 0.16f))
                if (w > 0.dp) insideBorder(shape, kotlin.math.ceil(w.toPx()), if (moving) Color(0xFFF59E0B) else if (f) (ringColor ?: Cfg.homeRingOr(t.accent)) else edge)
            }
            .mouseSecondary { long() }
            .combinedClickable(
                onClick = { if (android.os.SystemClock.elapsedRealtime() > suppressUntil) { snd(); click() } },
                onLongClick = if (dragStart == null) ({ suppressUntil = android.os.SystemClock.elapsedRealtime() + 700; long() }) else null,
            )
            .onGloballyPositioned { boxCoords = it }
            .then(
                if (dragStart != null && dragMove != null && dragEnd != null) Modifier.pointerInput(moving) {
                    tileDragGesture({ boxCoords }, { suppressUntil = android.os.SystemClock.elapsedRealtime() + 700; long() }, dragStart, dragMove, dragEnd, moving)
                } else Modifier.pointerInput(Unit) {
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
            ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * Ореол выбранного элемента: плотные расходящиеся кольца цвета рамки с плавным затуханием. «Слабое» около 14 dp и заметно на светлом фоне,
 * «Сильное» около 30 dp и яркое. Рисуется за плиткой, за её краем (clip плитки стоит после), поэтому не обрезается; общая функция для «Дома» и предпросмотра.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelGlow(mode: String, col: Color, cornerRadius: Float) {
    if (mode == "none") return
    val strong = mode == "strong"
    val rings = if (strong) 28 else 16
    val step = (if (strong) 1.4f else 1.1f) * density
    val maxA = if (strong) 1.0f else 0.85f
    for (i in 1..rings) {
        val d = i * step
        val k = 1f - (i - 1f) / rings
        drawRoundRect(
            color = col.copy(alpha = (maxA * k * k).coerceIn(0f, 1f)),
            topLeft = androidx.compose.ui.geometry.Offset(-d, -d),
            size = androidx.compose.ui.geometry.Size(size.width + 2 * d, size.height + 2 * d),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius + d, cornerRadius + d),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = step * 1.35f),
        )
    }
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
    /** справа крестик: убрать этот пункт (например, из «Недавних») */
    val onRemove: (() -> Unit)? = null,
    /** картинка широкая (превью баннера 16:9), а не маленький значок */
    val wide: Boolean = false,
    /** выбор цвета полосой спектра; «Как у темы» справа от названия (стрелки меняют оттенок, ОК: как у темы) */
    val bar: ColorBarSpec? = null,
    /** строка из нескольких кнопок-вариантов в ряд */
    val choices: ChoiceSpec? = null,
    /** допустимые значения числового счётчика «‹ число ›» (правило 19): нужны окну ввода для проверки; null: без проверки */
    val range: IntRange? = null,
    val onClick: (() -> Unit)? = null,
)

/** Строка с несколькими кнопками-вариантами в ряд ([selected] -1: ни один не выбран). Пульт: влево и вправо меняют вариант; касание и мышь: нажать на кнопку. */
class ChoiceSpec(val labels: List<String>, val selected: Int, val onPick: (Int) -> Unit)

@Composable
private fun ChoiceRow(o: Opt, req: FocusRequester?) {
    val t = LocalTh.current
    val ch = o.choices!!
    val focusedS = remember { mutableStateOf(false) }
    val (hm, hover) = rememberHover()
    val gate = remember { StepGate() }
    Column(
        Modifier.fillMaxWidth()
            .then(hm)
            .then(if (req != null) Modifier.focusRequester(req) else Modifier)
            .onPreviewKeyEvent { ev ->
                if (OkShield.eats(ev)) return@onPreviewKeyEvent true
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft -> { if (gate.ok()) ch.onPick((if (ch.selected < 0) 1 else ch.selected - 1).coerceIn(0, ch.labels.lastIndex)); true }
                    Key.DirectionRight -> { if (gate.ok()) ch.onPick((if (ch.selected < 0) 1 else ch.selected + 1).coerceIn(0, ch.labels.lastIndex)); true }
                    else -> false
                }
            }
            .onFocusChanged { focusedS.value = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .rowFocusLook(focusedS, hover, t.tileFocus, t.tile, t.accent, t.border)
            .focusable()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(o.label, color = t.text, style = Typo.iconLabel(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ch.labels.forEachIndexed { i, l ->
                val on = i == ch.selected
                Text(l, color = if (on) t.bg else t.text, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) t.accent else Color.Transparent)
                        .border(1.dp, if (on) t.accent else t.border, RoundedCornerShape(14.dp))
                        .clickable { ch.onPick(i) }.padding(horizontal = 8.dp, vertical = 8.dp))
            }
        }
    }
}

/** Выбор цвета полосой: [argb] 0 - цвет темы; [onPick] получает новый цвет (0 - «Как у темы»). */
class ColorBarSpec(val argb: Long, val resetRes: Int = R.string.ws_like_theme, val sat: Float = 1f, val value: Float = 1f, val onPick: (Long) -> Unit)

/** Строка выбора цвета: название и справа кнопка «Как у темы», ниже полоса спектра с бегунком. Пульт: влево/вправо меняют оттенок, ОК сбрасывает на цвет темы; касание и мышь: нажать или вести по полосе. */
@Composable
private fun ColorBarRow(o: Opt, req: FocusRequester?) {
    val t = LocalTh.current
    val bar = o.bar!!
    val focusedS = remember { mutableStateOf(false) }   // 15.1: фокус читается только при рисовании
    val (hm, hover) = rememberHover()
    val gate = remember { StepGate() }
    val hue = remember(bar.argb) { if (bar.argb == 0L) -1f else FloatArray(3).also { android.graphics.Color.colorToHSV(bar.argb.toInt(), it) }[0] }
    val pick = { h: Float ->
        val hh = ((h % 360f) + 360f) % 360f
        bar.onPick(android.graphics.Color.HSVToColor(floatArrayOf(hh, bar.sat, bar.value)).toLong() and 0xFFFFFFFFL)
    }
    val spectrum = remember(bar.sat, bar.value) { androidx.compose.ui.graphics.Brush.horizontalGradient((0..12).map { Color.hsv((it * 30f) % 360f, bar.sat, bar.value) }) }
    Column(
        Modifier.fillMaxWidth()
            .then(hm)
            .then(if (req != null) Modifier.focusRequester(req) else Modifier)
            .onPreviewKeyEvent { ev ->
                if (OkShield.eats(ev)) return@onPreviewKeyEvent true
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft -> { if (gate.ok()) pick((if (hue < 0f) 0f else hue) - 5f); true }
                    Key.DirectionRight -> { if (gate.ok()) pick((if (hue < 0f) 0f else hue) + 5f); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { bar.onPick(0L); true }
                    else -> false
                }
            }
            .onFocusChanged { focusedS.value = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .rowFocusLook(focusedS, hover, t.tileFocus, t.tile, t.accent, t.border)
            .focusable()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(o.label, color = t.text, style = Typo.iconLabel(), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(androidx.compose.ui.res.stringResource(bar.resetRes), color = if (bar.argb == 0L) t.bg else t.sub, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (bar.argb == 0L) t.accent else Color.Transparent)
                    .border(1.dp, if (bar.argb == 0L) t.accent else t.border, RoundedCornerShape(14.dp))
                    .clickable { bar.onPick(0L) }.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        androidx.compose.foundation.Canvas(
            Modifier.fillMaxWidth().height(24.dp)
                .pointerInput(Unit) {
                    detectTapGestures { p -> pick(p.x / size.width * 360f) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ -> change.consume(); pick(change.position.x.coerceIn(0f, size.width.toFloat()) / size.width * 360f) }
                },
        ) {
            drawRoundRect(spectrum, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f))
            val cx = (if (hue < 0f) 0.5f else hue / 360f).coerceIn(0.03f, 0.97f) * size.width
            val cy = size.height / 2f
            drawCircle(Color.White, size.height * 0.62f, androidx.compose.ui.geometry.Offset(cx, cy))
            drawCircle(if (hue < 0f) Color(0xFFB0B0B0) else Color.hsv(hue, bar.sat, bar.value), size.height * 0.46f, androidx.compose.ui.geometry.Offset(cx, cy))
        }
    }
}

@Composable
internal fun OptRow(o: Opt, req: FocusRequester?) {
    if (o.bar != null) { ColorBarRow(o, req); return }
    if (o.choices != null) { ChoiceRow(o, req); return }
    val t = LocalTh.current
    val focusedS = remember { mutableStateOf(false) }   // 15.1: фокус читается только при рисовании
    val (hm, hover) = rememberHover()
    val stepper = o.onDec != null && o.onInc != null
    // правило 19: у числового счётчика нажатие на число (ОК на ТВ, касание, щелчок) открывает окно ввода цифр; стрелки и кнопки ‹ › остаются
    val numeric = stepper && Regex("-?\\d+ ?%?").matches(o.value.trim())
    var numDlg by remember { mutableStateOf(false) }
    val gate = remember { StepGate() }
    val rowReq = remember { FocusRequester() }
    val rmReq = remember { FocusRequester() }
    Row(
        Modifier.fillMaxWidth()
            .then(hm)
            .then(if (req != null) Modifier.focusRequester(req) else Modifier)
            .focusRequester(rowReq)
            .onPreviewKeyEvent { ev ->
                if (OkShield.eats(ev)) return@onPreviewKeyEvent true
                if (o.onRemove != null && ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight) { runCatching { rmReq.requestFocus() }; return@onPreviewKeyEvent true }
                if (stepper && ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) { if (gate.ok()) o.onDec?.invoke(); true }
                else if (stepper && ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight) { if (gate.ok()) o.onInc?.invoke(); true }
                else false
            }
            .onFocusChanged { focusedS.value = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .rowFocusLook(focusedS, hover, t.tileFocus, t.tile, t.accent, t.border)
            .combinedClickable(onClick = { if (numeric) numDlg = true else (o.onClick ?: o.onInc)?.invoke() })
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (numDlg) {
            val cur = o.value.filter { it.isDigit() || it == '-' }.toIntOrNull() ?: 0
            NumberDialog(o.label, cur, o.range, o.value.trim().takeIf { it.endsWith("%") }?.let { "%" } ?: "", { n ->
                // единица шага счётчика равна 1: значение ставится нужным числом нажатий (сам счётчик держит свои границы)
                val d = n - cur
                repeat(kotlin.math.abs(d).coerceAtMost(1000)) { if (d > 0) o.onInc?.invoke() else o.onDec?.invoke() }
                numDlg = false
            }, { numDlg = false })
        }
        o.icon?.let { Image(it, null, if (o.wide) Modifier.size(128.dp, 72.dp).clip(RoundedCornerShape(8.dp)) else Modifier.size(30.dp)) }
        Text(o.label, color = if (o.danger) Color(0xFFF87171) else t.text, style = Typo.iconLabel(), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (stepper) {
            Text("‹", color = t.sub, fontSize = 20.sp, modifier = Modifier.holdStep(gate) { o.onDec?.invoke() }.padding(horizontal = 8.dp))
            Text(o.value, color = t.accent, style = Typo.iconLabel())
            Text("›", color = t.sub, fontSize = 20.sp, modifier = Modifier.holdStep(gate) { o.onInc?.invoke() }.padding(horizontal = 8.dp))
        } else if (o.value.isNotEmpty()) Text(o.value, color = t.accent, style = Typo.iconLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        o.onRemove?.let { rm ->
            var cf by remember { mutableStateOf(false) }
            Box(
                Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (cf) t.accent else Color.Transparent)
                    .focusRequester(rmReq)
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) { runCatching { rowReq.requestFocus() }; true } else false
                    }
                    .onFocusChanged { cf = it.isFocused }.clickable { rm() },
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = if (cf) t.bg else t.sub, fontSize = 15.sp) }
        }
    }
}

/** Окно со списком пунктов: пульт, касание и мышь. Стрелки влево/вправо на пункте-счётчике меняют число. */
@Composable
internal fun ListDialog(title: String, opts: List<Opt>, onClose: () -> Unit, hint: String = "", onRawKey: ((android.view.KeyEvent) -> Boolean)? = null, footer: Opt? = null) {
    val t = LocalBaseTh.current ?: LocalTh.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.86f).dp
    MotionDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MouseDragInDialog()
        FixedFont { androidx.compose.runtime.CompositionLocalProvider(LocalTh provides t) { Column(
            dlgWidth(320.dp, 540.dp).heightIn(max = maxH)
                .then(if (onRawKey != null) Modifier.onPreviewKeyEvent { onRawKey(it.nativeKeyEvent) } else Modifier)
                .clip(RoundedCornerShape(20.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = t.text, style = Typo.iconLabel(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (hint.isNotEmpty()) {
                var hintOpen by remember { mutableStateOf(false) }
                var hintFocus by remember { mutableStateOf(false) }
                // 18.11.2026: animateContentSize не принимает свой withContext (как Modifier.animateItem) - полагаемся на AnimScale.forceFull() (Desks.kt)
                Column(Modifier.animateContentSize()) {
                    Row(
                        Modifier.onFocusChanged { hintFocus = it.isFocused }.clip(RoundedCornerShape(8.dp))
                            .border(if (hintFocus) 2.dp else 0.dp, if (hintFocus) t.accent else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable(role = androidx.compose.ui.semantics.Role.Button) { hintOpen = !hintOpen }.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(androidx.compose.ui.res.stringResource(R.string.hint_more), color = t.sub, fontSize = 13.sp)
                        Text(if (hintOpen) "▴" else "ⓘ", color = t.accent, fontSize = 14.sp)
                    }
                    if (hintOpen) Text(hint, color = t.hint, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            BarLazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(opts) { i, o -> OptRow(o, if (i == 0) first else null) }
            }
            if (footer != null) OptRow(footer, null)
        } } }
    }
}

/** Окно ввода строки. */
@Composable
internal fun NumberDialog(title: String, initial: Int, range: IntRange?, unit: String, onApply: (Int) -> Unit, onClose: () -> Unit) {
    val t = LocalBaseTh.current ?: LocalTh.current
    var text by remember { mutableStateOf(initial.toString()) }
    var err by remember { mutableStateOf<String?>(null) }
    val fr = remember { FocusRequester() }
    val bad = androidx.compose.ui.res.stringResource(R.string.num_bad)
    val outMsg = if (range != null) androidx.compose.ui.res.stringResource(R.string.num_range, range.first, range.last) else ""
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    fun apply() {
        val n = text.trim().toIntOrNull()
        if (n == null) { err = bad; return }
        if (range != null && n !in range) { err = outMsg; return }
        onApply(n)
    }
    MotionDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MouseDragInDialog()
        FixedFont { androidx.compose.runtime.CompositionLocalProvider(LocalTh provides t) { Column(
            dlgWidth(300.dp, 420.dp).clip(RoundedCornerShape(20.dp)).background(t.bg)
                .border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = t.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                text, { text = it.filter { c -> c.isDigit() || c == '-' }.take(6); err = null }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 22.sp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { apply() }),
                suffix = { if (unit.isNotEmpty()) Text(unit, color = t.sub) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = t.text, unfocusedTextColor = t.text,
                    focusedBorderColor = t.accent, unfocusedBorderColor = t.border, cursorColor = t.accent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(fr),
            )
            Text(err ?: (if (range != null) androidx.compose.ui.res.stringResource(R.string.num_hint_range, range.first, range.last) else androidx.compose.ui.res.stringResource(R.string.num_hint)),
                color = if (err != null) Color(0xFFF87171) else t.hint, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(androidx.compose.ui.res.stringResource(R.string.cancel), false) { onClose() }
                Chip(androidx.compose.ui.res.stringResource(R.string.home_apply), true) { apply() }
            }
        } } }
    }
}

@Composable
internal fun TextDialog(title: String, initial: String, hint: String = "", onDone: (String) -> Unit, onClose: () -> Unit, secret: Boolean = false) {
    val t = LocalBaseTh.current ?: LocalTh.current
    var text by remember { mutableStateOf(initial) }
    var shown by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    MotionDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MouseDragInDialog()
        FixedFont { androidx.compose.runtime.CompositionLocalProvider(LocalTh provides t) { Column(
            dlgWidth(320.dp, 520.dp).clip(RoundedCornerShape(20.dp)).background(t.bg)
                .border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = t.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                text, { text = it }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp),
                // 15.0: для паролей поле скрыто, справа глазик (как во всех полях ввода секретов)
                visualTransformation = if (secret) secretTransform(shown) else androidx.compose.ui.text.input.VisualTransformation.None,
                trailingIcon = if (secret) ({ SecretEye(shown, t.accent) { shown = !shown } }) else null,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = t.text, unfocusedTextColor = t.text,
                    focusedBorderColor = t.accent, unfocusedBorderColor = t.border, cursorColor = t.accent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(fr),
            )
            if (hint.isNotEmpty()) Text(hint, color = t.hint, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(androidx.compose.ui.res.stringResource(R.string.cancel), false) { onClose() }
                Chip(androidx.compose.ui.res.stringResource(R.string.home_apply), true) { onDone(if (secret) text else text.trim()) }
            }
        } } }
    }
}

// ------------------------------------------------------------------ переход в системные настройки

/** Пробует открыть системную страницу настроек по очереди; последний вариант - общие настройки. */
internal fun openSys(ctx: android.content.Context, vararg actions: String): Boolean {
    for (a in actions.toList() + android.provider.Settings.ACTION_SETTINGS) {
        val ok = runCatching { ctx.startActivity(android.content.Intent(a).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (ok) return true
    }
    return false
}


/**
 * Жест плитки: короткое нажатие и прокрутка работают как обычно; если удержать ~полсекунды и затем двигать, плитка «берётся»
 * (start, move, end с позицией в корне экрана); удержание без движения и отпускание открывает меню плитки (onLong).
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.tileDragGesture(
    coords: () -> androidx.compose.ui.layout.LayoutCoordinates?,
    onLong: () -> Unit,
    start: (androidx.compose.ui.geometry.Offset) -> Unit,
    move: (androidx.compose.ui.geometry.Offset) -> Unit,
    end: (androidx.compose.ui.geometry.Offset) -> Unit,
    immediate: Boolean = false,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    if (down.type == androidx.compose.ui.input.pointer.PointerType.Mouse && currentEvent.buttons.isSecondaryPressed) return@awaitEachGesture
    val slop = viewConfiguration.touchSlop
    // 26.11.2026 (владелица): после «Переместить» держать не нужно, плитка берётся сразу: нажал и повёл (мышью или пальцем)
    val early = if (immediate) null else withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
            if (ch.isConsumed || !ch.pressed || (ch.position - down.position).getDistance() > slop) return@withTimeoutOrNull true
        }
        @Suppress("UNREACHABLE_CODE") false
    }
    if (early != null) return@awaitEachGesture          // обычное нажатие или прокрутка: не наш жест
    fun root(p: androidx.compose.ui.geometry.Offset) = coords()?.localToRoot(p) ?: p
    var started = false
    var last = down.position
    while (true) {
        val ev = awaitPointerEvent()
        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
        last = ch.position
        if (!ch.pressed) { ch.consume(); break }
        if (!started && (ch.position - down.position).getDistance() > slop) { started = true; start(root(ch.position)) }
        if (started) { ch.consume(); move(root(ch.position)) }
    }
    if (started) end(root(last)) else if (!immediate) onLong()
}


/** Какая плитка «Дома» была под курсором пульта: после закрытия окон курсор возвращается на неё, а не прыгает наверх. */
internal object TileFocus {
    @Volatile var last: String = ""
    /** Плитка, на которой курсор сейчас (null, если курсор вне плиток строк). */
    @Volatile var cur: String? = null
    val reqs = HashMap<String, FocusRequester>()
}

/**
 * «Назад» на пульте: курсор внутри строки «Дома», но не на первой карточке, возвращается на первую карточку этой же строки (просьба пользователя 4PDA,
 * 19.11.2026); если курсор уже на первой, «Назад» работает как прежде. Каждая строка при показе записывает сюда свою первую плитку.
 */
internal object RowBack {
    class Row(val contains: (String) -> Boolean, val firstKey: String, val goFirst: () -> Unit)
    val rows = HashMap<String, Row>()
    /** Порядок строк-кандидатов для «в начало» (13.0): первая строка после виджетов, далее остальные; задаёт HomeRowsView. */
    @Volatile var startOrder: List<String> = emptyList()

    /**
     * Второе «Назад» (13.0, просьба с 4PDA): курсор на плитке, но не на первой карточке первой строки после виджетов (нет виджетов: первой строки) -
     * перевести его туда; true, если переведён. Курсор вне плиток строк или уже там: false, «Назад» работает как прежде.
     */
    fun goStart(): Boolean {
        val cur = TileFocus.cur ?: return false
        for (id in startOrder) {
            val r = rows[id] ?: continue
            if (cur == r.firstKey) return false
            r.goFirst(); return true
        }
        return false
    }
    fun tryBack(): Boolean {
        val cur = TileFocus.cur ?: return false
        for (r in rows.values) if (r.contains(cur)) {
            if (cur == r.firstKey) return false
            r.goFirst(); return true
        }
        return false
    }
}


/**
 * Фон и рамка строки списка (15.1, плавность): состояние фокуса читается при рисовании, а не в композиции. Раньше смена фокуса перекомпоновывала строку
 * и меняла цепочку модификаторов (толщина рамки 1 dp / 3 dp), на приставке это давало лишние кадры при каждом нажатии стрелки. Вид прежний:
 * фон строки скруглением 12 dp, рамка 1 dp (3 dp в фокусе) внутри границ поверх содержимого.
 */
internal fun Modifier.rowFocusLook(focused: androidx.compose.runtime.State<Boolean>, hover: Boolean, focusFill: Color, fill: Color, accent: Color, border: Color): Modifier =
    this.drawBehind {
        drawRoundRect(if (focused.value || hover) focusFill else fill, cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))
    }.drawWithContent {
        drawContent()
        val f = focused.value
        insideBorder(RoundedCornerShape(12.dp), kotlin.math.ceil((if (f) 3.dp else 1.dp).toPx()), if (f) accent else border)
    }

/** Рамка внутри границ формы, как у Modifier.border: простое скругление рисуется штрихом внутрь, остальные формы через обрезку по контуру. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.insideBorder(shape: Shape, w: Float, color: Color) {
    if (w <= 0f) return
    val outline = shape.createOutline(size, layoutDirection, this)
    when (outline) {
        is androidx.compose.ui.graphics.Outline.Rounded -> {
            val rr = outline.roundRect
            val simple = rr.topLeftCornerRadius == rr.topRightCornerRadius && rr.topLeftCornerRadius == rr.bottomLeftCornerRadius && rr.topLeftCornerRadius == rr.bottomRightCornerRadius
            if (simple) {
                val half = w / 2f
                drawRoundRect(
                    color, topLeft = androidx.compose.ui.geometry.Offset(half, half),
                    size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(maxOf(0f, rr.topLeftCornerRadius.x - half), maxOf(0f, rr.topLeftCornerRadius.y - half)),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(w),
                )
            } else {
                val path = androidx.compose.ui.graphics.Path().apply { addRoundRect(rr) }
                clipPath(path) { drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(w * 2f)) }
            }
        }
        is androidx.compose.ui.graphics.Outline.Rectangle -> {
            val half = w / 2f
            drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(half, half), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        }
        is androidx.compose.ui.graphics.Outline.Generic -> {
            val path = outline.path
            clipPath(path) { drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(w * 2f)) }
        }
    }
}

/**
 * Рамка по фокусу, как Modifier.border(if (focused) w else w0, if (focused) c else c0, shape), но состояние [focused] читается при рисовании,
 * а не в композиции (15.1, плавность): смена фокуса не перекомпоновывает строку и не меняет цепочку модификаторов, перерисовывается одна рамка.
 * Рамка рисуется поверх содержимого, внутри границ (как у border); толщина 0 не рисуется.
 */
internal fun Modifier.focusBorder(focused: () -> Boolean, w: androidx.compose.ui.unit.Dp, w0: androidx.compose.ui.unit.Dp, c: Color, c0: Color, shape: Shape): Modifier =
    this.drawWithContent {
        drawContent()
        val f = focused()
        val wd = if (f) w else w0
        if (wd > 0.dp) insideBorder(shape, kotlin.math.ceil(wd.toPx()), if (f) c else c0)
    }

/** Заливка при фокусе: условие читается при рисовании (15.1, плавность), цвет передаётся готовым (его берут в композиции). */
internal fun Modifier.focusFillDraw(on: () -> Boolean, color: Color): Modifier = this.drawBehind { if (on()) drawRect(color) }
