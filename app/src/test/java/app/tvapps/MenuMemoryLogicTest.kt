package app.tvapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Сохранение и восстановление положения в меню. */
class MenuMemoryLogicTest {
    private val apps = listOf("a", "b", "c", "d", "e", "f")

    @Test fun sameTileIsRestoredNotTheFirst() {
        assertEquals("d", MenuMemoryLogic.focusTarget(apps, "d", 3))
    }

    @Test fun noSavedFocusGoesToFirst() {
        assertEquals("a", MenuMemoryLogic.focusTarget(apps, null, 0))
    }

    @Test fun removedTileGoesToNearestOnSamePlace() {
        // «d» удалён: на его место встала следующая плитка
        val after = listOf("a", "b", "c", "e", "f")
        assertEquals("e", MenuMemoryLogic.focusTarget(after, "d", 3))
        // удалён последний: ближайшая это новый последний
        assertEquals("f", MenuMemoryLogic.focusTarget(listOf("a", "b", "c", "e", "f"), "g", 9))
        assertNull(MenuMemoryLogic.focusTarget(emptyList(), "d", 3))
    }

    @Test fun scrollGoesToSameFirstVisibleTile() {
        assertEquals(2, MenuMemoryLogic.scrollIndex(apps, "c", 5))
        // порядок изменился: ищем по пакету, а не по номеру
        assertEquals(4, MenuMemoryLogic.scrollIndex(listOf("x", "y", "z", "w", "c"), "c", 1))
        // плитки нет: то же место, но не за пределы списка
        assertEquals(5, MenuMemoryLogic.scrollIndex(apps, "gone", 40))
        assertEquals(1, MenuMemoryLogic.scrollIndex(apps, null, 1))
        assertEquals(0, MenuMemoryLogic.scrollIndex(emptyList(), "c", 3))
    }

    @Test fun scrollIsAppliedOnlyWhenNeeded() {
        assertTrue(MenuMemoryLogic.needsScroll(true, 6, 0))
        assertFalse("то же место: не трогаем", MenuMemoryLogic.needsScroll(true, 6, 6))
        assertFalse("нечего восстанавливать", MenuMemoryLogic.needsScroll(false, 6, 0))
    }
}

/** Память экрана настроек: возврат на ту же кнопку и раздел. */
class SettingsMemoryLogicTest {
    private val c = listOf(
        MenuMemoryLogic.Cand("Вкл", 100f), MenuMemoryLogic.Cand("Выкл", 100f), MenuMemoryLogic.Cand("Вкл", 400f), MenuMemoryLogic.Cand("Выкл", 400f),
    )

    @Test fun sameLabelAndPlaceIsPicked() {
        // «Вкл» бывает в разных разделах: различаются положением
        assertEquals(2, MenuMemoryLogic.pickChip(c, "Вкл", 402f))
        assertEquals(0, MenuMemoryLogic.pickChip(c, "Вкл", 98f))
        assertEquals(3, MenuMemoryLogic.pickChip(c, "Выкл", 400f))
    }

    @Test fun missingChipReturnsNullSoFocusGoesToBack() {
        assertNull(MenuMemoryLogic.pickChip(c, "Нет такой", 100f))
        assertNull(MenuMemoryLogic.pickChip(c, "Вкл", 250f))
        assertNull(MenuMemoryLogic.pickChip(c, null, 100f))
        assertNull(MenuMemoryLogic.pickChip(emptyList(), "Вкл", 100f))
    }

    @Test fun unknownSubsectionFallsBackToMain() {
        assertEquals("hidden", MenuMemoryLogic.settingsSub("hidden"))
        assertEquals("main", MenuMemoryLogic.settingsSub("main"))
        assertEquals("main", MenuMemoryLogic.settingsSub("что-то"))
        assertEquals("main", MenuMemoryLogic.settingsSub(null))
    }
}
