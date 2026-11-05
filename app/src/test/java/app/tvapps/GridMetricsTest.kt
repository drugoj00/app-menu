package app.tvapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Размеры плотной сетки на экране магнитолы, на 1920x1080 и на высоком телефоне. */
class GridMetricsTest {
    private val label = 13f * 1.4f

    @Test fun headUnit1280x720IsThirteenByFourWithStep90x141() {
        // ширина сетки: экран минус поля (10 dp + 2 dp с двух сторон при масштабе 1,675), высота области: экран минус шапка и поля
        val g = GridMetrics.dense(1280f, 720f, 1240f, 600f, 1.675f, label, 2)
        assertEquals(13, g.cols)
        assertEquals("шаг по горизонтали около 90 px", 90f, g.cellPx, 6f)
        assertEquals("шаг по вертикали 141 px", 141f, g.pitchY, 1f)
        assertEquals("плашка 89% шага", 0.89f * g.cellPx, g.platePx, 0.01f)
        assertEquals("значок 75% плашки", 0.75f * g.platePx, g.iconPx, 0.01f)
        assertEquals(4, g.rows)
        assertTrue("плашка около 80 px", g.platePx in 76f..90f)
        assertTrue("значок около 60 px", g.iconPx in 57f..68f)
    }

    @Test fun fullHd() {
        val g = GridMetrics.dense(1920f, 1080f, 1880f, 940f, 1f, label, 2)
        assertTrue("столбцов 12..14, а не 7 и не 30: ${g.cols}", g.cols in 12..14)
        assertTrue(g.rows in 3..5)
        assertTrue(g.platePx < g.cellPx && g.iconPx < g.platePx)
        assertTrue(g.gapYPx >= 2f)
    }

    @Test fun tallPhoneDoesNotBreak() {
        // 1080x2400, плотность 2,625 (420 dpi): минимальный шаг 48 dp = 126 px, строки не разъезжаются
        val g = GridMetrics.dense(1080f, 2400f, 1040f, 2000f, 2.625f, 12f * 1.0f * 2.625f, 2)
        assertTrue("не меньше 3 столбцов: ${g.cols}", g.cols >= 3)
        assertTrue("не больше 9 столбцов: ${g.cols}", g.cols <= 9)
        assertTrue(g.cellPx >= 48f * 2.625f - 0.01f)
        assertTrue(g.iconPx > 0f && g.iconPx < g.platePx)
        assertTrue(g.pitchY <= 1.6f * g.cellPx + g.tileHeightPx)
        assertTrue(g.rows >= 1)
    }

    @Test fun settingsStillChangeTheGrid() {
        val auto = GridMetrics.dense(1280f, 720f, 1240f, 600f, 1.675f, label, 2)
        val big = GridMetrics.dense(1280f, 720f, 1240f, 600f, 1.675f, label, 2, sizeFactor = 96f / 72f)
        assertTrue("крупнее значки: меньше столбцов", big.cols < auto.cols)
        val fixed = GridMetrics.dense(1280f, 720f, 1240f, 600f, 1.675f, label, 2, fixedCols = 8, fixedRows = 3)
        assertEquals(8, fixed.cols)
        assertEquals(3, fixed.rows)
    }

    @Test fun labelsFitAtStep90() {
        val metrics = object : TextMetrics {
            private val narrow = "iIlj.,:;'!|() "
            private val mid = "tfrJ-1/"
            private val wide = "mwMWЖШЩЫЮФжшщыюфм"
            override fun width(s: String, sizePx: Float): Float {
                var em = 0f
                for (c in s) em += when {
                    c in narrow -> 0.27f; c in mid -> 0.36f; c in wide -> 0.86f; c.isUpperCase() -> 0.68f; c.isDigit() -> 0.56f; else -> 0.55f
                }
                return em * sizePx
            }
        }
        val cell = 90f
        val normal = cell - 2 * 3f
        val bleed = cell * 1.2f - 2f
        for (n in listOf("Калькулятор", "Переводчик", "Ассистент", "приложений", "Меню приложений", "SmarterTube", "ADB-phon", "TorrServe MatriX", "Casse-o-player", "Touch ассистент", "WiFi Automatic", "Эквалайзер", "Менеджер", "RadioPlayer")) {
            val p = LabelPlan.plan(n, metrics, label, normal, bleed)
            assertTrue("помещается без разрыва слова: $n", p.fits)
            assertTrue("шрифт не меньше 80%: $n", p.sizePx >= label * 0.8f - 0.001f)
        }
        // самое длинное слово «Калькулятор» чуть шире 106 px: сужение только у него, и не сильно
        val k = LabelPlan.plan("Калькулятор", metrics, label, normal, bleed)
        assertTrue(k.sizePx >= label * 0.9f)
    }
}
