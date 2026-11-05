package app.tvapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Расчёт подписей на параметрах магнитолы: экран 1280x720, 160 dpi, системный шрифт 140%, масштаб интерфейса 1,675.
 * Подпись 13 sp x 1,4 = 18,2 px (ячейка ~133 px, поля 16,75 px: ширина в ячейке ~99 px, с выступом 20% ~157 px).
 * Ширина символов моделируется долями em, близкими к Roboto (Robolectric и Android в JVM-тесте недоступны).
 */
class LabelPlanTest {
    private val base = 13f * 1.4f
    private val cell = 133f
    private val normal = cell - 2 * 16.75f
    private val bleed = cell * 1.2f - 2f

    private val metrics = object : TextMetrics {
        private val narrow = "iIlj.,:;'!|() "
        private val mid = "tfrJ-1/"
        private val wide = "mwMWЖШЩЫЮФжшщыюфм"
        override fun width(s: String, sizePx: Float): Float {
            var em = 0f
            for (c in s) em += when {
                c in narrow -> 0.27f
                c in mid -> 0.36f
                c in wide -> 0.86f
                c.isUpperCase() -> 0.68f
                c.isDigit() -> 0.56f
                else -> 0.55f
            }
            return em * sizePx
        }
    }

    private fun plan(s: String) = LabelPlan.plan(s, metrics, base, normal, bleed)

    @Test fun piecesSplitOnSpacesHyphensAndSlashes() {
        assertEquals(listOf("Casse-", "o-", "player"), LabelPlan.pieces("Casse-o-player"))
        assertEquals(listOf("TorrServe", "MatriX"), LabelPlan.pieces("TorrServe MatriX"))
        assertEquals(listOf("ADB-", "phon"), LabelPlan.pieces("ADB-phon"))
        assertEquals(listOf("Touch", "ассистент"), LabelPlan.pieces("Touch  ассистент"))
    }

    @Test fun namesFromOwnerScreenshotFitWithoutShrinkAndWithoutBreakingWords() {
        val names = listOf(
            "Калькулятор", "Переводчик", "Ассистент", "приложений", "Меню приложений", "SmarterTube", "ADB-phon", "TorrServe MatriX",
            "Casse-o-player", "Touch ассистент", "WiFi Automatic", "Эквалайзер", "Менеджер", "RadioPlayer", "WiFi Automati",
        )
        for (n in names) {
            val p = plan(n)
            assertTrue("помещается: $n", p.fits)
            assertEquals("размер не уменьшен: $n", base, p.sizePx, 0.001f)
            assertTrue("ширина не больше предельной: $n", p.widthPx <= bleed + 0.01f)
            assertTrue("самое длинное слово внутри ширины: $n", LabelPlan.widest(n, metrics, p.sizePx) <= p.widthPx)
        }
    }

    @Test fun longestWordUsesBleedOnlyWhenItDoesNotFitInCell() {
        val short = plan("Waze")
        assertEquals(normal, short.widthPx, 0.001f)
        val kalk = plan("Калькулятор")
        assertTrue("слово шире ячейки: выступ в зазор", LabelPlan.widest("Калькулятор", metrics, base) > normal)
        assertEquals(bleed, kalk.widthPx, 0.001f)
        assertEquals(base, kalk.sizePx, 0.001f)
    }

    @Test fun veryLongWordShrinksOnlyItsOwnLabelNotBelowEightyPercent() {
        val p = plan("Телекоммуникации")
        assertTrue(p.fits)
        assertTrue("шрифт уменьшен", p.sizePx < base)
        assertTrue("не меньше 80% нормы", p.sizePx >= base * 0.8f - 0.001f)
        // обычная подпись рядом остаётся нормальной
        assertEquals(base, plan("Gemini").sizePx, 0.001f)
    }

    @Test fun impossibleWordStopsAtFloorAndIsMarkedNotFitting() {
        val p = plan("Электрокардиографический")
        assertFalse(p.fits)
        assertEquals(base * 0.8f, p.sizePx, 0.001f)
    }
}
