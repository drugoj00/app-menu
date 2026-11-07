package app.tvapps

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat

/**
 * Шрифт текста приложения: один источник для подписей значков в сетке (LabelCache) и для текста окон (NativeText).
 *
 * Эталон: подписи значков на телефоне (там системный шрифт, и подписи «то что надо»). На телефоне остаётся системный шрифт, как до версии 4.0
 * (Typeface по умолчанию, ничего не подменяется). На приставках и магнитолах системный шрифт другой (старая версия, шире и плотнее),
 * поэтому там и подписи, и окна рисуются встроенными начертаниями (res/font), подобранными так, чтобы выглядеть как на телефоне.
 */
object AppFont {
    /** Приставка, телевизор или магнитола (широкий экран, системный шрифт отличается от телефона). */
    val bundled: Boolean by lazy {
        val c = App.instance
        c != null && (c.resources.configuration.smallestScreenWidthDp >= 600 || runCatching { TopMode.isTv(c) }.getOrDefault(false))
    }

    @Volatile private var regular: Typeface? = null
    @Volatile private var bold: Typeface? = null

    private fun load(res: Int, fallback: Typeface): Typeface {
        val c: Context = App.instance ?: return fallback
        return runCatching { ResourcesCompat.getFont(c, res) }.getOrNull() ?: fallback
    }

    /** Нативное начертание; null означает «системный шрифт по умолчанию, как до 4.0» (ничего не назначать). */
    fun typeface(weight: Int = 400, italic: Boolean = false): Typeface? {
        if (!bundled) {
            if (weight < 600 && !italic) return null
            return if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, weight, italic)
            else Typeface.create(Typeface.DEFAULT, if (weight >= 600 && italic) Typeface.BOLD_ITALIC else if (weight >= 600) Typeface.BOLD else Typeface.ITALIC)
        }
        return if (weight >= 600) bold ?: load(R.font.roboto_bold, Typeface.DEFAULT_BOLD).also { bold = it }
        else regular ?: load(R.font.roboto_regular, Typeface.DEFAULT).also { regular = it }
    }

    /** Кисть текста: как у подписи значка (ANTI_ALIAS, целые ширины букв, интервал 0). */
    fun textPaint(weight: Int = 400, italic: Boolean = false): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface(weight, italic)?.let { typeface = it }
    }

    /** Кисть подписи значка в сетке. */
    fun labelPaint(): TextPaint = textPaint()

    /** Ширина строки в px тем же способом, каким она рисуется. */
    fun measure(text: String, sizePx: Float, weight: Int = 400): Float {
        val p = textPaint(weight)
        p.textSize = sizePx
        return p.measureText(text)
    }

    /** Compose: семейство для Material-компонентов (поля ввода и т. п.): на телефоне системное, на приставке встроенное. */
    val family: FontFamily by lazy {
        if (!bundled) FontFamily.Default
        else FontFamily(Font(R.font.roboto_regular, FontWeight.Normal), Font(R.font.roboto_bold, FontWeight.Bold))
    }

    /** Типографика Material с этим семейством во всех стилях. */
    val typography: Typography by lazy {
        val d = Typography()
        Typography(
            displayLarge = d.displayLarge.copy(fontFamily = family), displayMedium = d.displayMedium.copy(fontFamily = family),
            displaySmall = d.displaySmall.copy(fontFamily = family), headlineLarge = d.headlineLarge.copy(fontFamily = family),
            headlineMedium = d.headlineMedium.copy(fontFamily = family), headlineSmall = d.headlineSmall.copy(fontFamily = family),
            titleLarge = d.titleLarge.copy(fontFamily = family), titleMedium = d.titleMedium.copy(fontFamily = family),
            titleSmall = d.titleSmall.copy(fontFamily = family), bodyLarge = d.bodyLarge.copy(fontFamily = family),
            bodyMedium = d.bodyMedium.copy(fontFamily = family), bodySmall = d.bodySmall.copy(fontFamily = family),
            labelLarge = d.labelLarge.copy(fontFamily = family), labelMedium = d.labelMedium.copy(fontFamily = family),
            labelSmall = d.labelSmall.copy(fontFamily = family),
        )
    }
}
