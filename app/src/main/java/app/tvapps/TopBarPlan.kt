package app.tvapps

/**
 * Раскладка верхней полосы «Дома»: когда все кнопки и значки включены, они должны помещаться в ширину экрана без наложения и обрезки.
 * Сначала плавно сужаются зазоры между значками (10→8→4→3→2 dp), затем уменьшается размер значков, отступов и шрифтов (100→88→76→66 %).
 * Поле поиска сжимается до минимума (микрофон и значок поиска), оно забирает всё, что остаётся.
 */
internal data class TopLevel(val left: Float, val status: Float, val scale: Float)

internal object TopBarPlan {
    val levels = listOf(
        TopLevel(10f, 6f, 1f), TopLevel(8f, 4f, 1f), TopLevel(4f, 2f, 1f),
        TopLevel(3f, 1f, 0.88f), TopLevel(2f, 0f, 0.76f), TopLevel(2f, 0f, 0.66f),
    )

    /**
     * Ширина, нужная при уровне [lv], dp. [left] — ширины кнопок слева (при масштабе 100 %), [searchMin] — наименьшая ширина поля поиска (0, если его нет),
     * [status] — ширины значков состояния справа. Все ширины пропорциональны масштабу; зазоры от него не зависят.
     */
    fun required(lv: TopLevel, left: List<Float>, searchMin: Float, status: List<Float>): Float {
        val groups = left.size + (if (searchMin > 0f) 1 else 0) + (if (status.isNotEmpty()) 1 else 0)
        val gaps = (groups - 1).coerceAtLeast(0) * lv.left + (status.size - 1).coerceAtLeast(0) * lv.status
        return lv.scale * (left.sum() + searchMin + status.sum()) + gaps
    }

    /** Первый (самый просторный) уровень, при котором всё помещается; если нет такого, самый тесный. */
    fun choose(avail: Float, left: List<Float>, searchMin: Float, status: List<Float>): Int {
        val i = levels.indexOfFirst { required(it, left, searchMin, status) <= avail }
        return if (i < 0) levels.lastIndex else i
    }
}
