package app.tvapps

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.service.dreams.DreamService
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Виджеты для заставки: те же часы и погода, что на «Доме» (вид и наборы общие); если их там нет, цифровые часы и погода по часам и дням. */
internal fun saverWidgets(ctx: Context): Pair<HWidget, HWidget> {
    HomeStore.init(ctx)
    var clock: HWidget? = null
    var wx: HWidget? = null
    for (r in HomeStore.layout.rows) for (sl in r.slots) {
        if (sl.type == "clock" && clock == null) clock = sl
        if (sl.type == "weather" && wx == null) wx = sl
    }
    val c = clock ?: HWidget("clock", n = WStyle.presetN("clock", 1, 0), style = WStyle.preset("clock", 1).json())
    val w = wx ?: HWidget("weather", n = 5, style = WStyle.preset("weather", 1).json())
    return c to w
}

/** Содержимое заставки: часы, дата, день недели, погода и прогноз; режимы прогноза листаются сами; чтобы не выгорал экран, всё плавно смещается раз в минуту. */
@Composable
internal fun SaverScreen(ctx: Context, cfg: SaverCfg) {
    val (clockW, wxW) = remember { saverWidgets(ctx) }
    val wst = remember(wxW.style) { WStyle.parse(wxW.style) }
    val modes = if (wst.modes <= 1) 7 else wst.modes
    var view by remember { mutableIntStateOf(if ((modes and (1 shl wst.view.coerceIn(0, 2))) != 0) wst.view.coerceIn(0, 2) else 0) }
    LaunchedEffect(modes) { while (true) { delay(12_000); view = nextForecastMode(view, modes) } }
    var drift by remember { mutableStateOf(IntOffset.Zero) }
    LaunchedEffect(cfg.burn) { while (cfg.burn) { delay(60_000); drift = IntOffset(Random.nextInt(-40, 41), Random.nextInt(-30, 31)) } }
    val moved by animateIntOffsetAsState(drift, tween(4000), label = "drift")
    var night by remember { mutableStateOf(SaverGuard.isNight(cfg)) }
    LaunchedEffect(cfg) { while (true) { night = SaverGuard.isNight(cfg); delay(30_000) } }
    val bg = if (cfg.bg == 0L) Color.Black else Color(cfg.bg.toInt())
    val sctx = androidx.compose.ui.platform.LocalContext.current
    val pic = remember { if (WallNet.saverOn(sctx)) runCatching { saverPicture(WallNet.saverFile(sctx).path) }.getOrNull() else null }
    val saverPortrait = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    val scrop = remember(saverPortrait) { WallNet.crop(sctx, "saver", saverPortrait) }
    val vid = remember { WallNet.saverVideoOn(sctx) }
    Box(Modifier.fillMaxSize().background(bg)) {
        // картинка из интернета за часами (выбирается в «Обои из интернета»): приглушена, чтобы часы читались и экран не выгорал
        // видео на заставке: играет, пока заставка на экране (остановится вместе с ней); с подписью автора внизу
        if (vid) {
            VideoBg(WallNet.saverVideoFile(sctx).path, false, 0)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        } else pic?.let {
            androidx.compose.foundation.Image(
                it, null,
                Modifier.fillMaxSize().graphicsLayer { scaleX = scrop.zoom; scaleY = scrop.zoom; transformOrigin = androidx.compose.ui.graphics.TransformOrigin((scrop.ox + 1f) / 2f, (scrop.oy + 1f) / 2f) },
                contentScale = androidx.compose.ui.layout.ContentScale.Crop, alignment = androidx.compose.ui.BiasAlignment(scrop.ox, scrop.oy),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        }
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val d = LocalDensity.current
            // всё рисуется в «виртуальных» 640 dp по ширине и масштабируется на весь экран (размер из настроек, но так, чтобы помещалось по высоте)
            val vh = if (cfg.clockOnly) 200f else 380f
            var k = constraints.maxWidth / (640f * d.density) * cfg.size / 100f
            k = minOf(k, constraints.maxHeight / (vh * d.density) * 0.96f)
            CompositionLocalProvider(LocalDensity provides Density(d.density * k, d.fontScale)) {
                Column(Modifier.offset { moved }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.width(640.dp).height(190.dp)) { WidgetBody(clockW, 0, 0) }
                    if (!cfg.clockOnly) {
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.width(640.dp).height(160.dp)) { WidgetBody(wxW.copy(style = wst.copy(view = view, modes = modes).json()), 0, 0) }
                    }
                }
            }
        }
        if (night && cfg.dim > 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (cfg.dim / 100f).coerceIn(0f, 0.9f))))
    }
}

/** Владельцы жизненного цикла для ComposeView, добавляемого прямо в окно (служба, заставка): иначе Compose не запускается. */
internal class ComposeHost : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val reg = LifecycleRegistry(this)
    private val ssc = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = reg
    override val savedStateRegistry: SavedStateRegistry get() = ssc.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    init { ssc.performAttach(); ssc.performRestore(null); reg.currentState = Lifecycle.State.CREATED }

    fun attach(v: View) {
        v.setViewTreeLifecycleOwner(this)
        v.setViewTreeSavedStateRegistryOwner(this)
        v.setViewTreeViewModelStoreOwner(this)
    }
    fun start() { reg.currentState = Lifecycle.State.RESUMED }
    fun destroy() { reg.currentState = Lifecycle.State.DESTROYED; store.clear() }
}

/** Заставка системы: выбирается в настройках Android («Заставка») или включается кнопкой в программе. Выход по любой клавише, касанию, мыши. */
class ClockDream : DreamService() {
    private var host: ComposeHost? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        val app = applicationContext
        Cfg.init(app)
        HomeStore.init(app)
        val h = ComposeHost().also { host = it }
        val frame = FrameLayout(this)
        h.attach(frame)
        frame.addView(ComposeView(this).apply { setContent { SaverScreen(app, SaverPrefs.get(app)) } })
        h.start()
        setContentView(frame)
    }

    override fun onDetachedFromWindow() {
        host?.destroy(); host = null
        super.onDetachedFromWindow()
    }
}

/**
 * Заставка в отдельном окне (Activity): запасной способ, когда поверх приложений рисовать нельзя (нет разрешения «Поверх других окон» или прошивка его блокирует).
 * Любая клавиша, касание или щелчок мыши закрывает её.
 */
class SaverActivity : ComponentActivity() {
    private val born = SystemClock.uptimeMillis()
    private var hx = Float.NaN; private var hy = Float.NaN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cfg.init(applicationContext)
        HomeStore.init(applicationContext)
        SaverLog.init(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val cfg = SaverPrefs.get(applicationContext)
        setContent { SaverScreen(applicationContext, cfg) }
        SaverBus.shown = true; SaverBus.shownBy = "window"
        SaverBus.exitHook = { finish() }
        SaverLog.log("заставка показана: отдельное окно")
    }

    override fun onDestroy() {
        if (SaverBus.shownBy == "window") { SaverBus.shown = false; SaverBus.exitHook = null; SaverBus.reset(); SaverLog.log("заставка скрыта (окно)") }
        super.onDestroy()
    }

    private fun settled() = SystemClock.uptimeMillis() - born > 700
    override fun dispatchKeyEvent(e: KeyEvent): Boolean { if (e.action == KeyEvent.ACTION_DOWN && settled()) finish(); return true }
    override fun dispatchTouchEvent(e: MotionEvent): Boolean { if (e.actionMasked == MotionEvent.ACTION_DOWN && settled()) finish(); return true }
    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (!settled()) return true
        if (e.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || e.actionMasked == MotionEvent.ACTION_SCROLL) finish()
        else if (e.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            if (hx.isNaN()) { hx = e.x; hy = e.y } else if (Math.abs(e.x - hx) + Math.abs(e.y - hy) > 40f) finish()
        }
        return true
    }
}

/** Корень окна-оверлея: любая клавиша, касание или щелчок мыши закрывает заставку и дальше не передаётся. */
internal class SaverFrame(ctx: Context, private val onExit: () -> Unit) : FrameLayout(ctx) {
    private val born = SystemClock.uptimeMillis()
    private var hx = Float.NaN; private var hy = Float.NaN
    init { isFocusable = true; isFocusableInTouchMode = true; setBackgroundColor(android.graphics.Color.BLACK) }
    private fun settled() = SystemClock.uptimeMillis() - born > 700
    override fun dispatchKeyEvent(e: KeyEvent): Boolean { if (e.action == KeyEvent.ACTION_DOWN && settled()) onExit(); return true }
    override fun dispatchTouchEvent(e: MotionEvent): Boolean { if (e.actionMasked == MotionEvent.ACTION_DOWN && settled()) onExit(); return true }
    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (!settled()) return true
        if (e.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || e.actionMasked == MotionEvent.ACTION_SCROLL) onExit()
        else if (e.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            if (hx.isNaN()) { hx = e.x; hy = e.y } else if (Math.abs(e.x - hx) + Math.abs(e.y - hy) > 40f) onExit()
        }
        return true
    }
}

/** Картинка заставки с уменьшением до 1280 по длинной стороне: заставка живёт в слабом процессе службы. */
private fun saverPicture(path: String): androidx.compose.ui.graphics.ImageBitmap? {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, o)
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 1280) s *= 2
    return android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}
