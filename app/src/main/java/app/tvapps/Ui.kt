package app.tvapps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn

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
        // DENSITY_DEVICE_STABLE появилась в Android 7.0 (API 24): на приставках с Android 6.0 обращение к ней роняло программу при каждом запуске
        // (NoSuchFieldError в первом же кадре); там физическая плотность неизвестна, коэффициент 1 (без увеличения), как у приставок без команды wm density
        if (android.os.Build.VERSION.SDK_INT < 24) return 1f
        return tvRatio(android.util.DisplayMetrics.DENSITY_DEVICE_STABLE, base.resources.displayMetrics.densityDpi)
    }

    /** Живой масштаб: пересчитывается при повороте и смене размера окна (конфигурация подменяется на уровне Compose, а не заморожена при запуске). */
    /**
     * Правило 28 (9.0): автомасштаб для крупных экранов с низкой плотностью (автомагнитолы, планшеты): короткая сторона не меньше 600 px при плотности
     * не выше 240, либо не меньше 600 dp при плотности не выше 213, либо режим «автомобиль». Телевизоры и приставки не затрагиваются.
     * Старт 1,25 (не больше 1,5: у ADB-phon 1,75 на магнитоле оказалось слишком много). Если уже действует прежний пересчёт по физической плотности (of), новый не добавляется.
     */
    fun autoK(base: android.content.Context): Float {
        val m = base.resources.displayMetrics
        val um = base.getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        val type = um?.currentModeType
        // отладочная сборка: файл files/dbg_notv отключает проверку «телевизор» (эмулятор ТВ для проверки автомасштаба магнитолы)
        val dbgNoTv = BuildConfig.DEBUG && java.io.File(base.filesDir, "dbg_notv").exists()
        if (!dbgNoTv && type == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) return 1f
        if (!dbgNoTv && TopMode.isTv(base)) return 1f
        if (of(base) > 1.01f) return 1f
        val car = type == android.content.res.Configuration.UI_MODE_TYPE_CAR || base.packageManager.hasSystemFeature("android.hardware.type.automotive")
        val shortPx = minOf(m.widthPixels, m.heightPixels)
        val dpi = m.densityDpi
        val shortDp = if (dpi > 0) shortPx * 160f / dpi else 0f
        val bigLow = (shortPx >= 600 && dpi in 1..240) || (shortDp >= 600f && dpi in 1..213)
        return if (car || bigLow) 1.25f else 1f
    }

    /** Итоговый коэффициент: автомасштаб на ручной множитель (0,5…2,5). */
    fun totalK(base: android.content.Context, mul: Float): Float = (autoK(base) * mul).coerceIn(0.5f, 2.5f)

    /** Масштаб правила 28 для содержимого: размеры в dp и текст в sp умножаются на итоговый коэффициент, конфигурация (ширина в dp) делится на него. Используется и окнами-диалогами (у каждого своё Compose-окно со своей плотностью). */
    @Composable
    fun ExtraScale(content: @Composable () -> Unit) {
        val ctx = LocalContext.current
        val k = totalK(ctx, Cfg.uiMul)
        if (k in 0.99f..1.01f) { content(); return }
        val d = LocalDensity.current
        val cfg = LocalConfiguration.current
        val cfgK = remember(cfg, k) {
            android.content.res.Configuration(cfg).also {
                it.screenWidthDp = (cfg.screenWidthDp / k).toInt()
                it.screenHeightDp = (cfg.screenHeightDp / k).toInt()
                // smallestScreenWidthDp не делится: он выбирает «телефонный» или «широкий» вид окон; увеличение текста не должно превращать магнитолу в телефон (кнопки вдвое мельче)
            }
        }
        CompositionLocalProvider(LocalDensity provides Density(d.density * k, d.fontScale), LocalConfiguration provides cfgK) { content() }
    }

    /**
     * 20.11.2026 (владелица): «размер интерфейса» меняет ТОЛЬКО интерфейс (шрифты, кнопки, настройки, панели, меню), а сетка значков и сами значки
     * имеют свои настройки (строки, столбцы, размер значков, масштаб, подписи) и от размера интерфейса не меняются. Ручной множитель [Cfg.uiMul]
     * применяется на корне ([ExtraScale]), поэтому область сетки возвращает плотность обратно: размеры в dp внутри снова такие, как до множителя.
     * Автомасштаб магнитол/планшетов ([autoK]) сюда не входит: он про экран, а не про выбор владелицы, и остаётся в силе и для сетки.
     */
    @Composable
    fun GridUnscale(content: @Composable () -> Unit) {
        val ctx = LocalContext.current
        val auto = autoK(ctx)
        val total = totalK(ctx, Cfg.uiMul)
        val r = if (auto > 0f) total / auto else 1f   // доля, которую внёс именно ручной множитель (с учётом границ 0,5…2,5)
        if (r in 0.99f..1.01f) { content(); return }
        val d = LocalDensity.current
        val cfg = LocalConfiguration.current
        val cfgR = remember(cfg, r) {
            android.content.res.Configuration(cfg).also {
                it.screenWidthDp = (cfg.screenWidthDp * r).toInt()
                it.screenHeightDp = (cfg.screenHeightDp * r).toInt()
            }
        }
        CompositionLocalProvider(LocalDensity provides Density(d.density / r, d.fontScale), LocalConfiguration provides cfgR) { content() }
    }

    @Composable
    fun Root(content: @Composable () -> Unit) = ExtraScale { Inner(content) }

    @Composable
    private fun Inner(content: @Composable () -> Unit) {
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
fun btnSp(v: Int): androidx.compose.ui.unit.TextUnit = if (isNarrow()) (v / (phoneTextBoost() / 1.5f)).sp else v.sp

/**
 * Надбавка к тексту в окнах телефона: в портрете 1,8 (при базовом размере 1,4 обычный текст выходит около 16 sp, а не 20: заголовки не крупнее кнопок),
 * в ландшафте прежние 2,25. Надписи на кнопках ([btnSp]) компенсируют разницу, поэтому их размер не зависит от этого выбора.
 */
@Composable
fun phoneTextBoost(): Float = if (LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) 1.8f else TEXT_BOOST

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
        val k = phoneBaseK()
        val tb = phoneTextBoost()
        val ld = remember(d.density, d.fontScale, k, tb) { LinearDensity(d.density * 0.5f * k, d.fontScale * tb) }
        // межстрочный интервал стиля по умолчанию (24 sp, жёстко) в окнах телефона вдвое выше самого текста: заголовки в две строки разъезжались.
        // Относительный интервал (1,45 размера шрифта) держит строки рядом при любом размере интерфейса и шрифта
        val ts = androidx.compose.material3.LocalTextStyle.current
        CompositionLocalProvider(LocalDensity provides ld) {
            androidx.compose.material3.ProvideTextStyle(ts.copy(lineHeight = 1.45.em)) { content() }
        }
    }
}

/**
 * Базовый размер интерфейса телефона (20.11.2026, владелица: «в портрете всё маленькое»): в портрете элементы и текст на 40% крупнее прежнего
 * (кнопка ≈ 40 dp, основной текст ≈ 16 sp: как в стандартных настройках Android); в ландшафте телефона высота мала, там размер прежний.
 * Ручной «Размер интерфейса» (мелкий/средний/крупный/очень крупный) умножает это поверх и сетки значков не касается.
 */
const val PHONE_PORTRAIT_K = 1.4f

@Composable
fun phoneBaseK(): Float = if (LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) PHONE_PORTRAIT_K else 1f

/** Размеры экрана в dp текущей плотности (внутри HalfUi они вдвое больше, чем в настройках системы). */
@Composable
fun screenDp(): Pair<Float, Float> {
    val m = LocalContext.current.resources.displayMetrics
    val d = LocalDensity.current.density
    return m.widthPixels / d to m.heightPixels / d
}

/** Правило 21 (16.11.2026): на ТВ-приставке информационные окна и диалоги чуть крупнее (+13% к шрифту); телефон, ландшафт телефона и магнитола без изменений. */
const val TV_TEXT_BOOST = 1.13f
internal val LocalTvBoost = androidx.compose.runtime.compositionLocalOf { false }

/** Приставка (ТВ) с широким экраном: признак для надбавки к шрифту. */
@Composable
fun isTvWide(): Boolean = !isNarrow() && TopMode.isTv(LocalContext.current)

/** Окно получает крупнее шрифт только на ТВ (по признаку TopMode.isTv); плотность в dp не меняется, поэтому размеры элементов прежние. */
@Composable
fun TvBoost(content: @Composable () -> Unit) {
    if (!isTvWide() || LocalTvBoost.current) { content(); return }
    val d = LocalDensity.current
    val dd = remember(d.density, d.fontScale) { androidx.compose.ui.unit.Density(d.density, d.fontScale * TV_TEXT_BOOST) }
    CompositionLocalProvider(LocalDensity provides dd, LocalTvBoost provides true) { content() }
}

/** Размер подсказки в sp: на ТВ вне окон (там уже есть надбавка шрифта) на 2 sp крупнее. */
@Composable
fun hintSp(v: Int): androidx.compose.ui.unit.TextUnit {
    val add = if (isTvWide() && !LocalTvBoost.current) 2 else 0
    return btnSp(v + add)
}

/**
 * Окно, содержимое которого тоже уменьшается на телефоне: окна Compose берут плотность экрана заново, поэтому HalfUi снаружи до них не доходит.
 */
@Composable
fun HDialog(onDismissRequest: () -> Unit, properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties(), content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) { MouseDragInDialog(); CompositionLocalProvider(LocalChipReg provides null) { UiScale.ExtraScale { HalfUi { TvBoost { MotionPop { content() } } } } } }
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

/** Ширина окна-диалога: на телефоне почти во всю ширину экрана, но не больше [max] (при крупном интерфейсе минимальная ширина в dp иначе не помещалась); на широком экране прежняя. */
@Composable
fun dlgWidth(min: androidx.compose.ui.unit.Dp, max: androidx.compose.ui.unit.Dp): androidx.compose.ui.Modifier =
    if (isNarrow()) androidx.compose.ui.Modifier.fillMaxWidth(0.94f).widthIn(max = max) else androidx.compose.ui.Modifier.widthIn(min = min, max = max)
