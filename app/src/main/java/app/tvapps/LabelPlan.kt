package app.tvapps

/** Измеритель ширины текста (в Android это Paint.measureText; в юнит-тесте простая модель ширины символов). */
interface TextMetrics {
    fun width(s: String, sizePx: Float): Float
}

/**
 * Расчёт подписи под значком (чистая логика без Android, проверяется юнит-тестом).
 * Правила: размер букв по умолчанию крупный и не уменьшается без нужды; слова не рвутся посреди (перенос только по пробелам и после дефиса или косой черты);
 * если самое длинное слово не помещается, уменьшается шрифт ТОЛЬКО этой подписи, не больше чем до 80% нормы.
 */
object LabelPlan {
    /** Итог: размер шрифта (px), ширина раскладки (px), помещается ли самое длинное слово целиком. */
    data class Plan(val sizePx: Float, val widthPx: Float, val fits: Boolean)

    /** Кусок текста между местами возможного переноса; [spaceAfter]: после него стоит пробел (а не дефис или косая черта, которые остаются в куске). */
    data class Tok(val text: String, val spaceAfter: Boolean)

    fun tokens(label: String): List<Tok> {
        val out = ArrayList<Tok>()
        val cur = StringBuilder()
        for (c in label) {
            if (c == ' ' || c == ' ' || c == '\n') {
                if (cur.isNotEmpty()) { out.add(Tok(cur.toString(), true)); cur.setLength(0) }
                else if (out.isNotEmpty() && !out.last().spaceAfter) out[out.size - 1] = Tok(out.last().text, true)
            } else {
                cur.append(c)
                if (c == '-' || c == '‐' || c == '–' || c == '/') { out.add(Tok(cur.toString(), false)); cur.setLength(0) }
            }
        }
        if (cur.isNotEmpty()) out.add(Tok(cur.toString(), false))
        return out
    }

    /** Куски, между которыми разрешён перенос. */
    fun pieces(label: String): List<String> = tokens(label).map { it.text }

    fun widest(label: String, m: TextMetrics, sizePx: Float): Float = pieces(label).maxOfOrNull { m.width(it, sizePx) } ?: 0f

    /** Ширины строк при жадном переносе по кускам (не больше [maxLines] строк; слишком длинный кусок обрезается многоточием до [width]). */
    fun lineWidths(label: String, m: TextMetrics, sizePx: Float, width: Float, maxLines: Int): List<Float> {
        val lines = ArrayList<Float>()
        var cur = 0f
        var started = false
        var prevSpace = false
        val space = m.width(" ", sizePx)
        for (t in tokens(label)) {
            val w = minOf(m.width(t.text, sizePx), width)
            val sp = if (prevSpace) space else 0f
            if (!started) { cur = w; started = true }
            else if (cur + sp + w <= width + 0.01f) cur += sp + w
            else { lines.add(cur); cur = w }
            prevSpace = t.spaceAfter
        }
        if (started) lines.add(cur)
        return if (lines.size > maxLines) lines.subList(0, maxLines) else lines
    }

    fun drawnWidth(label: String, m: TextMetrics, sizePx: Float, width: Float, maxLines: Int): Float = lineWidths(label, m, sizePx, width, maxLines).maxOrNull() ?: 0f

    /**
     * [normalWidth]: ширина подписи внутри ячейки (ячейка минус поля), [maxWidth]: предельная ширина (ячейка минус зазор между подписями соседей,
     * либо больше, если соседи это позволяют). [minScale]: нижняя граница точечного уменьшения (0,8 = 80% нормы).
     */
    fun plan(label: String, m: TextMetrics, baseSizePx: Float, normalWidth: Float, maxWidth: Float, minScale: Float = 0.8f): Plan {
        val w = widest(label, m, baseSizePx)
        val normal = minOf(normalWidth, maxWidth)
        if (w <= normal) return Plan(baseSizePx, normal, true)
        if (w <= maxWidth) return Plan(baseSizePx, maxWidth, true)
        val floor = baseSizePx * minScale
        // первое приближение по пропорции, затем проверка шагами по 0,25 px вниз
        var size = Math.floor((baseSizePx * maxWidth / w * 0.99f / 0.25f).toDouble()).toFloat() * 0.25f
        if (size > baseSizePx) size = baseSizePx
        while (size > floor && widest(label, m, size) > maxWidth) size -= 0.25f
        if (size < floor) size = floor
        return Plan(size, maxWidth, widest(label, m, size) <= maxWidth)
    }
}

/**
 * Подписи в строке сетки: между подписями соседних ячеек ВСЕГДА остаётся зазор (подписи не слипаются), независимо от размера значков.
 * Правила: размер букв не больше доли от ячейки (на мелких размерах шрифт пропорционален ячейке); ширина подписи не больше ячейки минус зазор;
 * длинное слово получает выступ в зазор, только если оба соседа в строке не выступают и их реальная ширина оставляет место;
 * если и так не помещается: сужение шрифта этой подписи до 80%, и лишь в крайнем случае слово обрезается многоточием (но не слипание).
 */
object LabelFlow {
    /** Итог для одной подписи. [drawnPx]: реальная ширина самой широкой строки. */
    data class Resolved(val sizePx: Float, val widthPx: Float, val fits: Boolean, val drawnPx: Float)

    class Params(val size0: Float, val strictW: Float, val normalW: Float, val gap: Float, val cell: Float)

    /** Зазор между подписями соседних ячеек: не меньше 6 px и не меньше 7% ячейки. */
    fun gap(cell: Float): Float = maxOf(6f, 0.07f * cell)

    /** Размер шрифта: заданный, но не больше 21% ширины ячейки (на мелких размерах значков буквы пропорциональны ячейке). */
    fun baseSize(baseSizePx: Float, cell: Float): Float = minOf(baseSizePx, 0.21f * cell)

    fun params(cell: Float, pad: Float, baseSizePx: Float): Params {
        val g = gap(cell)
        val strict = maxOf(cell - g, 8f)
        return Params(baseSize(baseSizePx, cell), strict, minOf(maxOf(cell - 2f * pad, 8f), strict), g, cell)
    }

    /** Подпись без выступа за ячейку (ширина не больше ячейки минус зазор). */
    fun single(label: String, m: TextMetrics, p: Params, lines: Int, maxW: Float = p.strictW): Resolved {
        val pl = LabelPlan.plan(label, m, p.size0, p.normalW, maxW)
        val drawn = LabelPlan.drawnWidth(label, m, pl.sizePx, pl.widthPx, lines)
        return Resolved(pl.sizePx, pl.widthPx, pl.fits, drawn)
    }

    /**
     * Все подписи в порядке сетки ([cols] столбцов). Выступ получают только «нуждающиеся» подписи (самое длинное слово шире ячейки минус зазор)
     * в середине строки, у которых оба соседа не нуждаются: их реальная ширина определяет, сколько места осталось.
     */
    fun solve(labels: List<String>, cols: Int, cell: Float, pad: Float, baseSizePx: Float, lines: Int, m: TextMetrics): List<Resolved> {
        val p = params(cell, pad, baseSizePx)
        val res0 = labels.map { single(it, m, p, lines) }
        val needy = labels.map { LabelPlan.widest(it, m, p.size0) > p.strictW }
        val out = ArrayList(res0)
        val c = maxOf(cols, 1)
        for (i in labels.indices) {
            if (!needy[i]) continue
            val col = i % c
            if (col == 0 || col == c - 1 || i + 1 >= labels.size) continue
            if (needy[i - 1] || needy[i + 1]) continue
            val allowed = minOf(2f * (cell - p.gap - res0[i - 1].drawnPx / 2f), 2f * (cell - p.gap - res0[i + 1].drawnPx / 2f), cell * 1.2f - 2f)
            if (allowed > p.strictW + 0.5f) out[i] = single(labels[i], m, p, lines, allowed)
        }
        return out
    }
}
