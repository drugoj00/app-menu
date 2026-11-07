package app.tvapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TopBarLangTest {
    // 2026-11-06 12:00 по местному времени
    private val day = java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.NOVEMBER, 6, 12, 0, 0) }.timeInMillis

    @Test fun datesFollowChosenLanguage() {
        assertEquals("Пятница" to "Ноябрь 06.11.2026", dateTwoLines(3, day, Locale("ru")))
        assertEquals("П’ятниця" to "Листопад 06.11.2026", dateTwoLines(3, day, Locale("uk")).let { it!!.first.replace('ʼ', '’') to it.second })
        assertEquals("Friday" to "November 06.11.2026", dateTwoLines(3, day, Locale("en")))
    }

    @Test fun monthGenitive() {
        assertTrue(dateLine(2, day, Locale("ru")).contains("ноября"))
        assertTrue(dateLine(2, day, Locale("uk")).contains("листопада"))
        assertTrue(dateLine(2, day, Locale("en")).contains("November"))
    }

    @Test fun weatherDayLabelsByLanguage() {
        assertEquals("пт", WeatherData.dayLabel("2026-11-06", "ru").lowercase())
        assertEquals("пт", WeatherData.dayLabel("2026-11-06", "uk").lowercase())
        assertEquals("Fri", WeatherData.dayLabel("2026-11-06", "en"))
    }

    @Test fun autoLanguageIsSystemLanguage() {
        val ru = Locale("ru"); val en = Locale("en")
        assertEquals(ru, AppLoc.resolve("", ru))       // «авто», система ru
        assertEquals(en, AppLoc.resolve("en", ru))     // выбран en при системном ru
        assertEquals(Locale("uk"), AppLoc.resolve("uk", en))
        // en -> авто(ru): дата перестаёт быть английской
        val before = dateLine(2, day, AppLoc.resolve("en", ru)); val after = dateLine(2, day, AppLoc.resolve("", ru))
        assertTrue(before != after && after.contains("ноября"))
        // uk -> авто(ru), ru -> en, en -> uk
        assertTrue(dateLine(2, day, AppLoc.resolve("", ru)).contains("ноября"))
        assertTrue(dateLine(2, day, AppLoc.resolve("en", ru)).contains("November"))
        assertTrue(dateLine(2, day, AppLoc.resolve("uk", ru)).contains("листопада"))
    }

    private val left = listOf(46f, 52f, 52f, 52f)             // меню, недавние, очистка, HDMI (крупные)
    private val status = listOf(62f, 62f, 62f, 62f, 62f, 190f, 150f) // 5 значков, дата, часы
    @Test fun roomyScreenKeepsNormalLevel() {
        assertEquals(0, TopBarPlan.choose(1900f, left, 120f, status))
    }
    @Test fun tighterScreenShrinksGapsThenSizes() {
        val need0 = TopBarPlan.required(TopBarPlan.levels[0], left, 120f, status)
        val i = TopBarPlan.choose(need0 - 5f, left, 120f, status)
        assertTrue(i in 1..2)                                  // сначала зазоры, значки прежнего размера
        assertEquals(1f, TopBarPlan.levels[i].scale)
        val j = TopBarPlan.choose(TopBarPlan.required(TopBarPlan.levels[2], left, 120f, status) - 5f, left, 120f, status)
        assertTrue(j >= 3 && TopBarPlan.levels[j].scale < 1f)  // потом уменьшаются значки
    }
    @Test fun chosenLevelAlwaysFitsWhenPossible() {
        for (w in 700..1900 step 25) {
            val i = TopBarPlan.choose(w.toFloat(), left, 120f, status)
            val last = TopBarPlan.levels.lastIndex
            assertTrue(TopBarPlan.required(TopBarPlan.levels[i], left, 120f, status) <= w || i == last)
        }
    }
    @Test fun levelsOnlyGetTighter() {
        for (k in 1 until TopBarPlan.levels.size) {
            assertTrue(TopBarPlan.required(TopBarPlan.levels[k], left, 120f, status) < TopBarPlan.required(TopBarPlan.levels[k - 1], left, 120f, status))
        }
    }
}
