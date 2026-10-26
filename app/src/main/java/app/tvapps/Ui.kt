package app.tvapps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

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
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) { HalfUi { content() } }
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
