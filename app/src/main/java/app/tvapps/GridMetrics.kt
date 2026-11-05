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
     */
    fun dense(
        screenW: Float, screenH: Float, availW: Float, viewportH: Float, density: Float, labelSizePx: Float, labelLines: Int,
        sizeFactor: Float = 1f, fixedCols: Int = 0, fixedRows: Int = 0, padPx: Float = 0f,
    ): Dense {
        val minStep = MIN_STEP_DP * density
        val stepX = maxOf(STEP_X * screenW * sizeFactor, minStep).coerceAtMost(maxOf(availW / 2f, 1f))
        val cols = if (fixedCols > 0) fixedCols else maxOf(1, Math.floor((availW / stepX).toDouble()).toInt())
        val cell = availW / cols
        val plate = PLATE * cell
        val icon = ICON * plate
        val labelGap = 0.05f * plate
        val labelH = Math.ceil((labelSizePx * 1.32f * labelLines).toDouble()).toFloat()
        val tileH = plate + labelGap + labelH + 2f * padPx
        // шаг строк: 19,6% высоты экрана, но не больше 1,6 ширины ячейки (на высоком телефоне иначе строки разъезжаются)
        val stepY = if (fixedRows > 0) viewportH / fixedRows else minOf(STEP_Y * screenH * sizeFactor, 1.6f * cell)
        val gapY = maxOf(stepY - tileH, 2f)
        val rows = maxOf(1, Math.floor(((viewportH + gapY) / (tileH + gapY)).toDouble()).toInt())
        return Dense(cols, cell, plate, icon, labelGap, labelH, tileH, gapY, tileH + gapY, rows)
    }
}
