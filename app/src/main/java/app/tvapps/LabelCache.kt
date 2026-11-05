package app.tvapps

import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf

/**
 * Параметры подписи в плитке сетки (все в px): размер шрифта, ширина ячейки, поля плитки, число строк, число столбцов (для расчёта соседей в строке).
 * Подписи раскладываются заранее в фоне (LabelCache) и в плитке только рисуются: Compose Text в плитке измерял текст (StaticLayout, Paint) прямо в кадре,
 * и журнал магнитолы показывал на этом стеки по 60-165 мс.
 */
class LabelSpec(val sizePx: Float, val slotPx: Float, val padPx: Float, val lines: Int, val cols: Int = 1) {
    val key: String = "$sizePx|$slotPx|$padPx|$lines"
}

/** Готовая раскладка подписи. */
class LabelLayout(val layout: StaticLayout, val widthPx: Int, val heightPx: Int, val offsetY: Float, val sizePx: Float, val fits: Boolean)

internal val LocalLabelSpec = compositionLocalOf<LabelSpec?> { null }

object LabelCache {
    private val map = object : LinkedHashMap<String, LabelLayout>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LabelLayout>?): Boolean = size > 800
    }

    /**
     * Подписи с выступом за ячейку (только когда соседи в строке это позволяют, см. LabelFlow.solve): ключ «текст|параметры» -> итог.
     * Состояние Compose: плитка читает свою запись и перерисовывается только когда она появилась или изменилась.
     */
    val overrides = mutableStateMapOf<String, LabelFlow.Resolved>()

    private fun metricsFor(paint: TextPaint) = object : TextMetrics {
        override fun width(s: String, sizePx: Float): Float { paint.textSize = sizePx; return paint.measureText(s) }
    }

    /** Из кэша или (если ещё не готова) строится сразу; в фоне вызывается заранее для всех приложений. Потокобезопасно. */
    fun get(label: String, spec: LabelSpec): LabelLayout = getWith(label, spec, overrides[label + "|" + spec.key])

    fun getWith(label: String, spec: LabelSpec, ov: LabelFlow.Resolved?): LabelLayout {
        val key = label + "|" + spec.key + "|" + (ov?.widthPx ?: 0f)
        synchronized(map) { map[key]?.let { return it } }
        val built = build(label, spec, ov)
        synchronized(map) { map[key] = built }
        return built
    }

    /** Расчёт всех подписей в порядке сетки с учётом соседей (в фоне): ключ «текст|параметры» -> итог для подписей, получивших выступ. */
    fun solveAll(labels: List<String>, spec: LabelSpec): Map<String, LabelFlow.Resolved> {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val p = LabelFlow.params(spec.slotPx, spec.padPx, spec.sizePx)
        val res = LabelFlow.solve(labels, spec.cols, spec.slotPx, spec.padPx, spec.sizePx, spec.lines, metricsFor(paint))
        val out = HashMap<String, LabelFlow.Resolved>()
        for (i in labels.indices) if (res[i].widthPx > p.strictW + 0.5f) out[labels[i] + "|" + spec.key] = res[i]
        return out
    }

    /** Обновляет выступы для этих параметров (вызывать в главном потоке после предварительной раскладки). */
    fun publish(spec: LabelSpec, fresh: Map<String, LabelFlow.Resolved>) {
        val suffix = "|" + spec.key
        for (k in overrides.keys.toList()) if (k.endsWith(suffix) && k !in fresh) overrides.remove(k)
        for ((k, v) in fresh) if (overrides[k] != v) overrides[k] = v
    }

    fun clear() { synchronized(map) { map.clear() }; overrides.clear() }

    private fun build(label: String, spec: LabelSpec, ov: LabelFlow.Resolved?): LabelLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        val metrics = metricsFor(paint)
        val p = LabelFlow.params(spec.slotPx, spec.padPx, spec.sizePx)
        val r = ov ?: LabelFlow.single(label, metrics, p, spec.lines)
        paint.textSize = r.sizePx
        val w = Math.ceil(r.widthPx.toDouble()).toInt().coerceAtLeast(1)
        // слово, которое не помещается даже после сужения: обрезается многоточием (но не рвётся посреди и не заходит на соседа)
        var text: CharSequence = label
        if (!r.fits) {
            val sb = StringBuilder()
            for (t in LabelPlan.tokens(label)) {
                sb.append(if (paint.measureText(t.text) > w) TextUtils.ellipsize(t.text, paint, w.toFloat(), TextUtils.TruncateAt.END) else t.text)
                if (t.spaceAfter) sb.append(' ')
            }
            text = sb.toString().trim()
        }
        // после дефиса и косой черты явная возможность переноса (невидимый пробел нулевой ширины): иначе «ADB-phon» рвалось посреди слова («ADB-pho/n»)
        if (label.indexOf('-') >= 0 || label.indexOf('/') >= 0) {
            val sbz = StringBuilder()
            val src = text.toString()
            for (i in src.indices) {
                sbz.append(src[i])
                if ((src[i] == '-' || src[i] == '/') && i + 1 < src.length && src[i + 1] != ' ') sbz.append('\u200B')
            }
            text = sbz.toString()
        }
        // высота строки как у прежнего Text (1,32 размера), место под все строки резервируется всегда: плитки в ряду одной высоты
        val fm = paint.fontMetrics
        val fontLine = fm.descent - fm.ascent
        val line = p.size0 * 1.32f
        val extra = (r.sizePx * 1.32f - fontLine).coerceAtLeast(0f)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(spec.lines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setEllipsizedWidth(w)
            .setIncludePad(false)
            .setLineSpacing(extra, 1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
        return LabelLayout(layout, w, Math.ceil((line * spec.lines).toDouble()).toInt(), extra / 2f, r.sizePx, r.fits)
    }
}
