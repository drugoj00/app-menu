package app.tvapps

/**
 * Шаги счётчиков настроек (+ и −). Чистая логика без экрана, чтобы границы и «Авто» проверялись тестами.
 * Значение «Авто» хранится как 0 (столбцы и строки). Шаг из «Авто» начинается с того числа, которое сейчас показывает сетка,
 * а не с единицы: раньше «+» из «Авто» (на приставке 12 столбцов) бросал сетку на 1 столбец, а «−» из 1 возвращал в «Авто», то есть сразу на 12.
 */
object Steps {
    /** Число для шага из «Авто», если сетка ещё не сообщила своё: разумное для телевизора. */
    const val FALLBACK_AUTO = 6

    /** Столбцы и строки: 0 = «Авто»; явное значение лежит в 1..[max]; ниже 1 не уходит («Авто» выбирается отдельной кнопкой). */
    fun autoStep(value: Int, delta: Int, max: Int, autoShown: Int): Int {
        val base = (if (value <= 0) (if (autoShown > 0) autoShown else FALLBACK_AUTO) else value).coerceIn(1, max)
        return (base + delta).coerceIn(1, max)
    }

    /** Обычный счётчик с шагом: значение всегда внутри [min]..[max], выход за границы невозможен. */
    fun rangeStep(value: Int, delta: Int, min: Int, max: Int, step: Int): Int =
        (value.coerceIn(min, max) + delta * step).coerceIn(min, max)

    /** Значение из настроек (возможно испорченное) приводится к допустимому: 0 или 1..[max]. */
    fun cleanAuto(v: Int, max: Int): Int = if (v <= 0) 0 else v.coerceAtMost(max)
}
