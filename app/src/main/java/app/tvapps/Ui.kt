package app.tvapps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/**
 * Масштаб интерфейса для экранов, где система занижает плотность: автомагнитолы показывают 160 dpi при реальных ~270 ppi (экран 121 x 68 мм при 1280 x 720),
 * и значки с текстом выходят в полтора раза мельче задуманного. Телевизоры и обычные телефоны (ppi близок к плотности) не затрагиваются: 1 = без изменений.
 * Размеры в dp и конфигурация окна пересчитываются целиком, поэтому раскладка «узкий экран / широкий» выбирается уже по эффективным dp.
 */
object UiScale {
    fun of(base: android.content.Context): Float {
        val m = base.resources.displayMetrics
        val tv = (base.getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager)?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val ppi = (m.xdpi + m.ydpi) / 2f
        if (tv || ppi < 100f || m.densityDpi <= 0) return 1f
        val ratio = ppi / m.densityDpi
        return if (ratio >= 1.25f) minOf(ratio, 1.6f) else 1f
    }

    /** Живой масштаб: пересчитывается при повороте и смене размера окна (конфигурация подменяется на уровне Compose, а не заморожена при запуске). */
    @Composable
    fun Root(content: @Composable () -> Unit) {
        val ctx = LocalContext.current
        val s = remember { of(ctx) }
        if (s <= 1.01f) { content(); return }
        val d = LocalDensity.current
        val cfg = LocalConfiguration.current
        val cfg2 = remember(cfg, s) {
            android.content.res.Configuration(cfg).also {
                it.screenWidthDp = (cfg.screenWidthDp / s).toInt()
                it.screenHeightDp = (cfg.screenHeightDp / s).toInt()
                it.smallestScreenWidthDp = (cfg.smallestScreenWidthDp / s).toInt()
            }
        }
        // размер текста остаётся таким, как выбран в системе (140% на магнитоле): масштабируются значки и отступы, а не шрифт
        CompositionLocalProvider(LocalDensity provides Density(d.density * s, d.fontScale / s), LocalConfiguration provides cfg2) { content() }
    }
}

/**
 * Содержимое в одну строку, которое при нехватке ширины пропорционально уменьшается целиком (шапка меню на узких экранах и магнитолах),
 * а не переносится на вторую строку. Если места хватает, растягивается на всю ширину как обычный Row.
 */
@Composable
fun ShrinkToFit(modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier) { ms, c ->
        val m = ms.firstOrNull() ?: return@Layout layout(0, 0) {}
        val avail = if (c.hasBoundedWidth) c.maxWidth else m.maxIntrinsicWidth(Int.MAX_VALUE)
        val natural = m.maxIntrinsicWidth(Int.MAX_VALUE).coerceAtLeast(1)
        val w = maxOf(avail, natural)
        val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(w, 0).copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
        val s = if (natural > avail) avail.toFloat() / natural else 1f
        layout(avail, (p.height * s).toInt()) {
            p.placeWithLayer(0, 0) {
                scaleX = s; scaleY = s
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
            }
        }
    }
}

/** Во сколько раз текст на телефоне крупнее при вдвое меньшей плотности: 0,5 x 2,25 = 1,125 от обычного. */
const val TEXT_BOOST = 2.25f

/** Размер шрифта надписей на кнопках: на телефоне остаётся прежним, чтобы кнопки не росли вместе с остальным текстом. */
@Composable
fun btnSp(v: Int): androidx.compose.ui.unit.TextUnit = if (isNarrow()) (v / (TEXT_BOOST / 1.5f)).sp else v.sp

/** Телефон (в портрете и в ландшафте): меньшая сторона экрана меньше 600 dp; у приставок и телевизоров она больше. */
@Composable
fun isNarrow(): Boolean = LocalConfiguration.current.smallestScreenWidthDp < 600

/**
 * Кнопки и элементы в два раза мельче, текст на треть крупнее половины (то есть две трети прежнего): на телефоне в портрете (особенно при крупном шрифте и плотности) интерфейс иначе не помещается.
 * Значки сетки не оборачиваются, их размер задаётся настройкой «Размер значков».
 */
@Composable
fun HalfUi(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    if (!isNarrow()) content()
    else CompositionLocalProvider(LocalDensity provides LinearDensity(d.density * 0.5f, d.fontScale * TEXT_BOOST)) { content() }
}

/** Размеры экрана в dp текущей плотности (внутри HalfUi они вдвое больше, чем в настройках системы). */
@Composable
fun screenDp(): Pair<Float, Float> {
    val m = LocalContext.current.resources.displayMetrics
    val d = LocalDensity.current.density
    return m.widthPixels / d to m.heightPixels / d
}

/**
 * Окно, содержимое которого тоже уменьшается на телефоне: окна Compose берут плотность экрана заново, поэтому HalfUi снаружи до них не доходит.
 */
@Composable
fun HDialog(onDismissRequest: () -> Unit, properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties(), content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) { MouseDragInDialog(); HalfUi { content() } }
}

/**
 * Плотность с линейным масштабом шрифта. Обычная начиная с Android 14 увеличивает крупный шрифт нелинейно, из-за чего размеры текста
 * и кнопок на телефонах с крупным шрифтом расходились; здесь размер текста всегда sp x fontScale.
 */
private class LinearDensity(override val density: Float, override val fontScale: Float) : Density {
    override fun androidx.compose.ui.unit.TextUnit.toDp(): androidx.compose.ui.unit.Dp {
        check(type == androidx.compose.ui.unit.TextUnitType.Sp) { "Only Sp can convert to Px" }
        return androidx.compose.ui.unit.Dp(value * fontScale)
    }

    override fun androidx.compose.ui.unit.Dp.toSp(): androidx.compose.ui.unit.TextUnit = (value / fontScale).sp
    override fun Float.toSp(): androidx.compose.ui.unit.TextUnit = (this / (fontScale * density)).sp
    override fun Int.toSp(): androidx.compose.ui.unit.TextUnit = (this / (fontScale * density)).sp
}

/** Корень интерфейса с автоматическим масштабом под реальный размер экрана (см. UiScale). */
@Composable
fun UiScaleRoot(content: @Composable () -> Unit) = UiScale.Root(content)
