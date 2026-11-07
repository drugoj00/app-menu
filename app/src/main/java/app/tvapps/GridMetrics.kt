package app.tvapps

/**
 * Размеры «плотной» сетки (чистая арифметика без Android, проверяется юнит-тестом). Образец: экран магнитолы 1280x720 с системным лаунчером:
 * 13 столбцов x 4 строки, шаг 90 x 141 px (7% ширины и 19,6% высоты экрана), плашка под значком 89% шага, значок 75% плашки.
 */
object GridMetrics {
    const val STEP_X = 0.07f
    const val STEP_Y = 0.196f
    const val PLATE = 0.89f
    const val ICON = 0.75f
    /** Множитель промежутков по настройке «Плотность значков»: 0 плотно, 1 обычно, 2 просторно. */
    fun gapFactor(gridGap: Int): Float = when (gridGap) { 0 -> 0.5f; 2 -> 1.8f; else -> 1f }

    /** Наименьший зазор между подложками соседних ячеек в dp по настройке «Плотность значков»: плотно 4, обычно 8, просторно 12. */
    fun minGapDp(gridGap: Int): Float = when (gridGap) { 0 -> 4f; 2 -> 12f; else -> 8f }

    /** Наименьший шаг ячейки в dp: на телефоне плотная сетка не должна давать мелочь. */
    const val MIN_STEP_DP = 48f

    /** Все размеры в px. [pitchY] = шаг строк (плитка + промежуток). */
    data class Dense(
        val cols: Int, val cellPx: Float, val platePx: Float, val iconPx: Float, val labelGapPx: Float, val labelHeightPx: Float,
        val tileHeightPx: Float, val gapYPx: Float, val pitchY: Float, val rows: Int,
    )

    /**
     * [screenW], [screenH]: размер экрана; [availW]: ширина сетки (без полей); [viewportH]: высота области сетки; [density]: px на dp;
     * [labelSizePx], [labelLines]: подпись под плашкой; [sizeFactor]: множитель шага по настройке «Размер» и «Масштаб» (1 = обычный);
     * [padPx]: поля плитки (с каждой стороны); [fixedCols], [fixedRows]: если заданы в настройках, переопределяют расчёт.
     * [gapFactor]: множитель промежутков между плитками (см. [gapFactor]): 1 = как у образца, меньше = плотнее (плашка шире, строк больше),
     * больше = просторнее (плашка уже, промежуток по вертикали больше, строк меньше).
     */
    fun dense(
        screenW: Float, screenH: Float, availW: Float, viewportH: Float, density: Float, labelSizePx: Float, labelLines: Int,
        sizeFactor: Float = 1f, fixedCols: Int = 0, fixedRows: Int = 0, padPx: Float = 0f, gapFactor: Float = 1f, minGapPx: Float = 0f,
    ): Dense {
        val minStep = MIN_STEP_DP * density
        val stepX = maxOf(STEP_X * screenW * sizeFactor, minStep).coerceAtMost(maxOf(availW / 2f, 1f))
        val cols = if (fixedCols > 0) fixedCols else maxOf(1, Math.floor((availW / stepX).toDouble()).toInt())
        val cell = availW / cols
        // промежуток между плашками по горизонтали: 11% ячейки у образца, растёт и убывает с множителем (плашка при этом шире или уже)
        val plateFrac = if (gapFactor == 1f) PLATE else (1f - (1f - PLATE) * gapFactor).coerceIn(0.55f, 0.97f)
        // подложки соседних ячеек никогда не слипаются: между ними не меньше minGapPx (на телефоне 11% ячейки это 3-4 dp)
        val plate = minOf(plateFrac * cell, cell - minGapPx).coerceAtLeast(0.5f * cell)
        val icon = ICON * plate
        val labelGap = 0.05f * plate
        val labelH = Math.ceil((labelSizePx * 1.32f * labelLines).toDouble()).toFloat()
        val tileH = plate + labelGap + labelH + 2f * padPx
        // шаг строк: 19,6% высоты экрана, но не больше 1,6 ширины ячейки (на высоком телефоне иначе строки разъезжаются)
        val stepY = if (fixedRows > 0) viewportH / fixedRows else minOf(STEP_Y * screenH * sizeFactor, 1.6f * cell)
        // строки заданы числом: шаг равен высоте области, делённой на число строк, меняется только плашка; иначе множитель меняет сам промежуток
        val tileH1 = PLATE * cell * 1.05f + labelH + 2f * padPx
        // шаг строк меняется на 15% от отклонения множителя (плотно: строки ближе, просторно: дальше), промежуток не меньше 2 px
        val pitch = maxOf(stepY, tileH1 + 2f) * (1f + (gapFactor - 1f) * 0.15f)
        val gapY = maxOf(if (fixedRows > 0) stepY - tileH else pitch - tileH, 2f, minGapPx)
        val rows = maxOf(1, Math.floor(((viewportH + gapY) / (tileH + gapY)).toDouble()).toInt())
        return Dense(cols, cell, plate, icon, labelGap, labelH, tileH, gapY, tileH + gapY, rows)
    }
}
