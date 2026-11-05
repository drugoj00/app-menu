package app.tvapps

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/**
 * Подбор размера подписи под значком за один проход.
 * Раньше подпись уменьшалась на 0,5 sp за кадр: каждый шаг перекомпоновывал и заново раскладывал плитку (onTextLayout), и на слабой магнитоле
 * (узкие ячейки, системный шрифт 140%) каждая новая строка сетки при прокрутке и при старте дёргалась около десяти кадров подряд.
 * Здесь нужный размер вычисляется сразу по ширине подписи и запоминается: плитка, вернувшаяся на экран при прокрутке, рисуется сразу с готовым размером.
 */
internal class LabelFit(val measurer: TextMeasurer, val widthPx: Float)

/** Ширина подписи в плитке сетки (px) и измеритель текста; null там, где ширина заранее не известна (список, окно папки). */
internal val LocalLabelFit = compositionLocalOf<LabelFit?> { null }

internal object LabelSizes {
    private val cache = object : LinkedHashMap<String, Float>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?): Boolean = size > 600
    }

    /** Слово разрезано посреди переносом строки: тогда подпись надо ужимать. */
    fun cutsWord(r: TextLayoutResult, s: String): Boolean =
        (0 until r.lineCount - 1).any { i -> val end = r.getLineEnd(i); end in 1 until s.length && s[end - 1].isLetterOrDigit() && s[end].isLetterOrDigit() }

    /** Размер (sp), при котором ни одно слово не рвётся посреди; не меньше 60% от базового. Вызывается из композиции (главный поток). */
    fun fit(f: LabelFit, label: String, baseSp: Float, style: TextStyle, density: Density, lines: Int): Float {
        val wpx = f.widthPx.toInt()
        if (wpx <= 8 || label.isEmpty()) return baseSp
        val key = "$label|$baseSp|$wpx|${density.density}|${density.fontScale}|$lines"
        cache[key]?.let { return it }
        val floor = baseSp * 0.6f
        var size = baseSp
        runCatching {
            repeat(5) {
                val r = f.measurer.measure(
                    label, style.copy(fontSize = size.sp, lineHeight = (size * 1.32f).sp), TextOverflow.Ellipsis, true, lines,
                    constraints = Constraints(maxWidth = wpx), density = density,
                )
                if (size <= floor || !cutsWord(r, label)) return@runCatching
                // слово, разрезанное на границе строки: берём его целиком и сразу считаем, во сколько раз оно шире ячейки
                var lineEnd = 0
                for (i in 0 until r.lineCount - 1) {
                    val end = r.getLineEnd(i)
                    if (end in 1 until label.length && label[end - 1].isLetterOrDigit() && label[end].isLetterOrDigit()) { lineEnd = end; break }
                }
                var a = lineEnd; while (a > 0 && label[a - 1].isLetterOrDigit()) a--
                var b = lineEnd; while (b < label.length && label[b].isLetterOrDigit()) b++
                val word = label.substring(a, b)
                val w = f.measurer.measure(
                    word, style.copy(fontSize = size.sp), TextOverflow.Clip, false, 1, constraints = Constraints(), density = density,
                ).size.width
                val k = if (w > 0) wpx.toFloat() / w else 0.9f
                // шаг вниз кратен 0,25 sp, небольшой запас, чтобы слово точно поместилось
                val next = (Math.floor((size * k * 0.98f / 0.25f).toDouble()) * 0.25).toFloat()
                size = maxOf(floor, minOf(next, size - 0.25f))
            }
        }
        cache[key] = size
        return size
    }
}
