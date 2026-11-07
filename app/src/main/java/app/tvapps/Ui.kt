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

    /**
     * Коэффициент для телевизора или приставки с уменьшенной плотностью экрана (команда wm density: физическая 240, выставлена 153).
     * Размеры в dp сами не знают о таком уменьшении: верхняя полоска «Дома», часы, значки, подписи, карточки становились в 1,57 раза мельче,
     * чем на той же приставке без уменьшения. Коэффициент = стабильная (физическая) плотность / текущая; 1 = без изменений
     * (нет уменьшения, плотность выше физической, не телевизор). Не складывается с автомасштабом магнитол ([of] для телевизора даёт 1).
     */
    fun tvRatio(stableDpi: Int, currentDpi: Int): Float {
        if (stableDpi <= 0 || currentDpi <= 0) return 1f
        val r = stableDpi.toFloat() / currentDpi
        return if (r < 1.05f) 1f else minOf(r, 2.5f)
    }

    fun tvK(base: android.content.Context): Float {
        if (!TopMode.isTv(base)) return 1f
        return tvRatio(android.util.DisplayMetrics.DENSITY_DEVICE_STABLE, base.resources.displayMetrics.densityDpi)
    }

    /** Живой масштаб: пересчитывается при повороте и смене размера окна (конфигурация подменяется на уровне Compose, а не заморожена при запуске). */
    @Composable
    fun Root(content: @Composable () -> Unit) {
        val ctx = LocalContext.current
        val k = remember { tvK(ctx) }
        if (k > 1.01f) {
            // телевизор с уменьшенной плотностью: интерфейс рисуется так, как при физической плотности (и размер текста тоже)
            val d = LocalDensity.current
            val cfg = LocalConfiguration.current
            val cfgK = remember(cfg, k) {
                android.content.res.Configuration(cfg).also {
                    it.screenWidthDp = (cfg.screenWidthDp / k).toInt()
                    it.screenHeightDp = (cfg.screenHeightDp / k).toInt()
                    it.smallestScreenWidthDp = (cfg.smallestScreenWidthDp / k).toInt()
                }
            }
            CompositionLocalProvider(LocalDensity provides Density(d.density * k, d.fontScale), LocalConfiguration provides cfgK) { content() }
            return
        }
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
    else {
        // один и тот же объект, пока плотность не изменилась: LocalDensity статический, и новый (не равный) объект при каждой перекомпозиции
        // заставлял заново собирать всё содержимое под ним
        val ld = remember(d.density, d.fontScale) { LinearDensity(d.density * 0.5f, d.fontScale * TEXT_BOOST) }
        CompositionLocalProvider(LocalDensity provides ld) { content() }
    }
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
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) { MouseDragInDialog(); CompositionLocalProvider(LocalChipReg provides null) { HalfUi { content() } } }
}

/**
 * Плотность с линейным масштабом шрифта. Обычная начиная с Android 14 увеличивает крупный шрифт нелинейно, из-за чего размеры текста
 * и кнопок на телефонах с крупным шрифтом расходились; здесь размер текста всегда sp x fontScale.
 */
private data class LinearDensity(override val density: Float, override val fontScale: Float) : Density {
    override fun androidx.compose.ui.unit.TextUnit.toDp(): androidx.compose.ui.unit.Dp {
        check(type == androidx.compose.ui.unit.TextUnitType.Sp) { "Only Sp can convert to Px" }
        return androidx.compose.ui.unit.Dp(value * fontScale)
    }

    override fun androidx.compose.ui.unit.Dp.toSp(): androidx.compose.ui.unit.TextUnit = (value / fontScale).sp
    override fun Float.toSp(): androidx.compose.ui.unit.TextUnit = (this / (fontScale * density)).sp
    override fun Int.toSp(): androidx.compose.ui.unit.TextUnit = (this / (fontScale * density)).sp
}

/** Плотность корня интерфейса (после автомасштаба): по ней задан размер подписей под значками в сетке; окна с уменьшенной плотностью (HalfUi, FixedFont) берут её как образец. */
internal val LocalRootDensity = androidx.compose.runtime.compositionLocalOf<Density?> { null }

/** Корень интерфейса с автоматическим масштабом под реальный размер экрана (см. UiScale). */
@Composable
fun UiScaleRoot(content: @Composable () -> Unit) = UiScale.Root {
    val d = LocalDensity.current
    val ctx = LocalContext.current
    val adj = if (android.os.Build.VERSION.SDK_INT >= 31) LocalConfiguration.current.fontWeightAdjustment else 0
    // «Жирный шрифт» в системе (у Samsung +300) Compose добавлял к весу всех своих текстов, а подписи значков в сетке (нативный TextPaint) нет:
    // окна выходили жирнее подписей. Свой разрешитель шрифтов без этой добавки делает вес окон таким же, как у подписей.
    val resolver = remember(ctx, adj) {
        if (adj == 0) null
        else runCatching {
            val cfg = android.content.res.Configuration(ctx.resources.configuration)
            if (android.os.Build.VERSION.SDK_INT >= 31) cfg.fontWeightAdjustment = 0
            androidx.compose.ui.text.font.createFontFamilyResolver(ctx.createConfigurationContext(cfg))
        }.getOrNull()
    }
    if (resolver != null) CompositionLocalProvider(LocalRootDensity provides d, androidx.compose.ui.platform.LocalFontFamilyResolver provides resolver) { content() }
    else CompositionLocalProvider(LocalRootDensity provides d) { content() }
}

/**
 * ЕДИНЫЙ стиль подписи под значком: так же рисуются все тексты карточки действий, панели ярлыков и окон «Дома».
 * Определение подписи в сетке: LabelCache.build: TextPaint (шрифт приложения AppFont, обычное начертание, межбуквенный интервал 0), размер gridLabelSp,
 * высота строки 1,32 размера. Здесь тот же шрифт и интервал, а размер пересчитан под плотность окна так, чтобы буквы были ровно того же размера в px,
 * что и у подписи в сетке (в окнах телефона плотность вдвое меньше, а шрифт крупнее: [labelSp] это учитывает).
 */
object Typo {
    /** Размер подписи для текущей области композиции: даёт те же пиксели, что подпись значка в сетке. */
    @Composable
    fun labelSp(): androidx.compose.ui.unit.TextUnit {
        val here = LocalDensity.current
        val root = LocalRootDensity.current ?: here
        val base = gridLabelSp(isNarrow())
        if (here.density == root.density && here.fontScale == root.fontScale) return base.sp
        // размер в px у подписи сетки (с учётом нелинейного масштаба шрифта системы), пересчитанный в sp этого окна (в окнах телефона масштаб линейный)
        val px = with(root) { base.sp.toPx() }
        return (px / (here.density * here.fontScale)).sp
    }

    /** Стиль: шрифт приложения (AppFont), обычное начертание, интервал 0, строка 1,32 размера. */
    @Composable
    fun iconLabel(): androidx.compose.ui.text.TextStyle {
        val s = labelSp()
        return androidx.compose.ui.text.TextStyle(
            fontFamily = AppFont.family, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
            letterSpacing = 0.sp, fontSize = s, lineHeight = s * 1.32f,
        )
    }
}
