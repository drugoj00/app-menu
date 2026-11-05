package app.tvapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Подписи соседних значков не слипаются: зазор между краями подписей в строке при ячейках 48, 58, 72, 90 и 133 px. */
class LabelFlowTest {
    private val base = 13f * 1.4f

    private val metrics = object : TextMetrics {
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

    private val names = listOf(
        "ADB-phon", "Pulsar", "Меню приложений", "SmarterTube", "TorrServe MatriX", "Casse-o-player", "Seena", "Menu.TEYES", "ChatGPT", "Gemini",
        "WiFi Automatic", "VTube", "Tablet Clock", "SmartTube", "Magic Earth", "ClockSync", "MX Player", "Muzio Player", "MEGOGO", "AnyDesk",
        "Интернет", "HD VideoBox", "Ace Stream", "Link2SD", "HDrezka App", "Viber", "Telegram", "Радио", "ADM Pro", "RadioPlayer", "YTDLnis",
        "Google Earth", "Переводчик", "X-plore", "AUX", "Музыка", "Видео", "Эквалайзер", "Галерея", "Touch ассистент", "Менеджер", "Калькулятор",
        "Ассистент", "Waze", "Google Play", "Карты", "YouTube", "Google",
    )

    private fun checkRow(labels: List<String>, cols: Int, cell: Float) {
        val pad = 3f
        val res = LabelFlow.solve(labels, cols, cell, pad, base, 2, metrics)
        val g = LabelFlow.gap(cell)
        val size0 = LabelFlow.baseSize(base, cell)
        for (i in labels.indices) {
            assertTrue("шрифт не меньше 80% нормы: ${labels[i]} @ $cell", res[i].sizePx >= size0 * 0.8f - 0.001f)
            assertTrue("шрифт не больше базового: ${labels[i]} @ $cell", res[i].sizePx <= size0 + 0.001f)
            assertTrue("реальная ширина не больше ширины раскладки: ${labels[i]} @ $cell", res[i].drawnPx <= res[i].widthPx + 0.01f)
            if (i % cols != cols - 1 && i + 1 < labels.size) {
                val gap = cell - (res[i].drawnPx + res[i + 1].drawnPx) / 2f
                assertTrue("зазор между «${labels[i]}» и «${labels[i + 1]}» при ячейке $cell: $gap < $g", gap >= g - 0.01f)
            }
        }
    }

    @Test fun neighbourLabelsNeverTouchAtAnyCellSize() {
        for (cell in listOf(48f, 58f, 72f, 90f, 133f)) {
            val cols = Math.floor((1240f / cell).toDouble()).toInt()
            checkRow(names, cols, cell)
            // другой сдвиг строк: соседи меняются
            checkRow(names.drop(3), cols, cell)
            checkRow(names.reversed(), cols, cell)
        }
    }

    @Test fun letterSizeIsProportionalToSmallCells() {
        assertEquals(base, LabelFlow.baseSize(base, 133f), 0.001f)
        assertTrue(LabelFlow.baseSize(base, 48f) < 11f)
        assertTrue(LabelFlow.baseSize(base, 72f) < base)
    }

    @Test fun largeCellKeepsFullSizeAndWholeWords() {
        val res = LabelFlow.solve(names, 9, 133f, 3f, base, 2, metrics)
        for (i in names.indices) {
            assertTrue("${names[i]} помещается целиком", res[i].fits)
            assertEquals("шрифт не уменьшен: ${names[i]}", base, res[i].sizePx, 0.001f)
        }
    }

    @Test fun overhangOnlyWhenBothNeighboursLeaveRoom() {
        val cell = 90f
        val g = LabelFlow.gap(cell)
        val strict = cell - g
        // в середине строки между короткими подписями: слово шире строгой ширины получает выступ
        val mid = LabelFlow.solve(listOf("Waze", "Калькулятор", "AUX"), 9, cell, 3f, base, 2, metrics)
        assertTrue("выступ дан", mid[1].widthPx > strict + 0.5f)
        assertTrue(mid[1].widthPx <= cell * 1.2f)
        // рядом с другим длинным словом выступа нет
        val crowded = LabelFlow.solve(listOf("Waze", "Калькулятор", "Переводчик"), 9, cell, 3f, base, 2, metrics)
        assertTrue("выступа нет", crowded[1].widthPx <= strict + 0.01f)
        // на краю строки выступа нет
        val edge = LabelFlow.solve(listOf("Waze", "Калькулятор"), 2, cell, 3f, base, 2, metrics)
        assertTrue(edge[1].widthPx <= strict + 0.01f)
    }
}
