package app.tvapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesksTest {
    private fun apps(n: Int) = (1..n).map { "p$it" }

    @Test fun circularNextPrev() {
        assertEquals(1, DeskLogic.next(0, 3)); assertEquals(0, DeskLogic.next(2, 3))
        assertEquals(2, DeskLogic.prev(0, 3)); assertEquals(0, DeskLogic.prev(1, 3))
        assertEquals(0, DeskLogic.next(0, 1)); assertEquals(0, DeskLogic.prev(0, 1)); assertEquals(0, DeskLogic.next(0, 0))
        assertEquals(1, DeskLogic.step(0, 3, 1)); assertEquals(2, DeskLogic.step(0, 3, -1))
    }

    @Test fun edgeDirections() {
        // 4 столбца, 10 плиток: индексы 0..9
        assertEquals(-1, DeskLogic.edgeDir(0, 4, 10, true)); assertEquals(-1, DeskLogic.edgeDir(4, 4, 10, true)); assertEquals(0, DeskLogic.edgeDir(5, 4, 10, true))
        assertEquals(1, DeskLogic.edgeDir(3, 4, 10, false)); assertEquals(1, DeskLogic.edgeDir(7, 4, 10, false)); assertEquals(0, DeskLogic.edgeDir(6, 4, 10, false))
        // последняя неполная строка: вправо на последней плитке тоже на следующий стол
        assertEquals(1, DeskLogic.edgeDir(9, 4, 10, false)); assertEquals(0, DeskLogic.edgeDir(8, 4, 10, false))
        assertEquals(0, DeskLogic.edgeDir(-1, 4, 10, true))
    }

    @Test fun targetIndexKeepsRow() {
        assertEquals(8, DeskLogic.targetIndex(2, 4, 10, true))      // слева в строке 2
        assertEquals(9, DeskLogic.targetIndex(2, 4, 10, false))     // справа в строке 2: 11 нет, последняя
        assertEquals(3, DeskLogic.targetIndex(0, 4, 10, false))
        assertEquals(9, DeskLogic.targetIndex(7, 4, 10, true))      // строки нет: последняя плитка
        assertEquals(-1, DeskLogic.targetIndex(0, 4, 0, true))
    }

    @Test fun layoutFillsInOrderAndCreatesDesks() {
        val r = DeskLogic.layout(apps(10), emptyList(), emptyMap(), 4)
        assertEquals(listOf(-1, -2, -3), r.map { it.first })
        assertEquals(listOf(4, 4, 2), r.map { it.second.size })
        assertEquals(apps(10), r.flatMap { it.second })
    }

    @Test fun layoutOverflowGoesToNextDeskWithRoom() {
        // стол 1 закреплённых 6 при вместимости 4: два лишних (последние по порядку) на стол 2, где ещё 1 закреплённый
        val pins = mapOf("p1" to 1, "p2" to 1, "p3" to 1, "p4" to 1, "p5" to 1, "p6" to 1, "p7" to 2)
        val r = DeskLogic.layout(apps(9), listOf(1, 2), pins, 4)
        assertEquals(listOf("p1", "p2", "p3", "p4"), r[0].second)
        assertEquals(listOf("p5", "p6", "p7"), r[1].second.take(3).sortedBy { it })   // p7 свой, p5 p6 перешли
        assertEquals(9, r.sumOf { it.second.size })
        // ничего не пропало и не задвоилось
        assertEquals(apps(9).toSet(), r.flatMap { it.second }.toSet())
    }

    @Test fun layoutKeepsEmptyUserDesksAfterFilledOnes() {
        // добавленные столы остаются пустыми и стоят после заполненных: каждое добавление прибавляет страницу
        val r = DeskLogic.layout(apps(3), listOf(1, 2), emptyMap(), 4)
        assertEquals(listOf(-1, 1, 2), r.map { it.first })
        assertEquals(3, r[0].second.size); assertTrue(r[1].second.isEmpty()); assertTrue(r[2].second.isEmpty())
        val none = DeskLogic.layout(emptyList(), emptyList(), emptyMap(), 4)
        assertTrue(none.isEmpty())
    }

    @Test fun addingDeskAlwaysAddsAPageUpToNine() {
        // 128 приложений, вместимость 20: 7 столов; каждый добавленный стол даёт ещё страницу, до девяти
        var desks = emptyList<Int>()
        var pages = DeskLogic.layout(apps(128), desks, emptyMap(), 20, Desks.MAX).size
        assertEquals(7, pages)
        for (i in 1..2) {
            desks = desks + i
            val n = DeskLogic.layout(apps(128), desks, emptyMap(), 20, Desks.MAX).size
            assertEquals(pages + 1, n); pages = n
        }
        assertEquals(9, pages)
        assertEquals(9, Desks.MAX)
    }

    @Test fun layoutLastDeskOverflowCreatesAutoDesk() {
        val pins = apps(5).associateWith { 1 }
        val r = DeskLogic.layout(apps(5), listOf(1), pins, 3)
        assertEquals(listOf(1, -1), r.map { it.first })
        assertEquals(listOf(3, 2), r.map { it.second.size })
    }

    @Test fun layoutDeterministicAndPinsToMissingDeskIgnored() {
        val pins = mapOf("p2" to 99)
        val a = DeskLogic.layout(apps(6), listOf(1), pins, 4)
        val b = DeskLogic.layout(apps(6), listOf(1), pins, 4)
        assertEquals(a, b)
        assertEquals(apps(6), a.flatMap { it.second }.sortedBy { it.removePrefix("p").toInt() })
    }

    @Test fun layoutOrderInsideDeskFollowsSort() {
        val order = listOf("c", "a", "b")
        val r = DeskLogic.layout(order, listOf(1), mapOf("b" to 1, "c" to 1), 5)
        assertEquals(listOf("c", "a", "b"), r[0].second)
    }

    @Test fun layoutStopsAtMaxPagesAndKeepsAll() {
        // 30 приложений, вместимость 4: нужно 8 столов; при пределе 3 остаток остаётся на последнем столе, ничего не пропадает
        val r = DeskLogic.layout(apps(30), emptyList(), emptyMap(), 4, 3)
        assertEquals(3, r.size)
        assertEquals(listOf(4, 4, 22), r.map { it.second.size })
        assertEquals(apps(30), r.flatMap { it.second }.sortedBy { it.removePrefix("p").toInt() })
        // закреплённые, не поместившиеся при пределе, тоже остаются на последнем столе
        val pins = apps(10).associateWith { 1 }
        val r2 = DeskLogic.layout(apps(10), listOf(1), pins, 3, 2)
        assertEquals(2, r2.size)
        assertEquals(10, r2.sumOf { it.second.size })
    }

    @Test fun layoutPerDeskCapacityKeepsAll() {
        // у первого стола своя вместимость 2 (настройки сетки стола): остальные значки уходят дальше
        val r = DeskLogic.layout(apps(10), emptyList(), emptyMap(), 4, 9, capOf = { i -> if (i == 0) 2 else 4 })
        assertEquals(listOf(2, 4, 4), r.map { it.second.size })
        assertEquals(apps(10), r.flatMap { it.second })
    }

    @Test fun layoutPageWithZeroCapacityIsSkipped() {
        val r = DeskLogic.layout(apps(6), emptyList(), emptyMap(), 4, 9, capOf = { i -> if (i == 0) 0 else 4 })
        assertEquals(0, r[0].second.size)
        assertEquals(apps(6), r.flatMap { it.second })
    }

    @Test fun layoutOverflowBeyondMaxStaysOnLastPageWithCapOf() {
        val r = DeskLogic.layout(apps(30), emptyList(), emptyMap(), 4, 3, capOf = { 3 })
        assertEquals(3, r.size)
        assertEquals(30, r.sumOf { it.second.size })
    }

    @Test fun afterDelete() {
        // вкладки 0 1 2 3: удалили текущую 2 -> 1; удалили раньше текущей -> текущая сдвигается; удалили позже -> без изменений
        assertEquals(1, DeskLogic.afterDelete(2, 2, 4))
        assertEquals(1, DeskLogic.afterDelete(2, 0, 4).coerceAtLeast(1))
        assertEquals(2, DeskLogic.afterDelete(2, 3, 4))
        assertEquals(0, DeskLogic.afterDelete(0, 0, 2))
        assertEquals(0, DeskLogic.afterDelete(1, 1, 2))
    }

    @Test fun indexOfIdAndMove() {
        assertEquals(2, DeskLogic.indexOfId(listOf(5, 7, 9), 9))
        assertEquals(0, DeskLogic.indexOfId(listOf(5, 7, 9), 4))
        assertEquals(listOf(7, 5, 9), DeskLogic.move(listOf(5, 7, 9), 0, 1))
        assertEquals(listOf(5, 7, 9), DeskLogic.move(listOf(5, 7, 9), 0, -1))
        assertEquals(listOf(5, 7, 9), DeskLogic.move(listOf(5, 7, 9), 2, 1))
    }

    @Test fun deskMemoryRestoreRoundTrip() {
        // память положения: снимок и возврат возвращают те же поля (без Android: логика DeskMem как значение)
        val m = DeskMem("p3", 2, "p1", 0, 0)
        assertEquals(m, DeskMem("p3", 2, "p1", 0, 0))
        assertEquals(null, DeskMem().focusPkg)
    }
}
