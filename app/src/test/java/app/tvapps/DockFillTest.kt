package app.tvapps

import org.junit.Assert.assertEquals
import org.junit.Test

class DockFillTest {
    private val popular = (1..30).map { "p$it" }

    @Test fun allChosenAreShown() {
        val chosen = (1..20).map { "a$it" }
        assertEquals(chosen, HomeDock.fill(chosen, popular, popular, false, 8))
        assertEquals(chosen, HomeDock.fill(chosen, popular, popular, true, 12))
    }

    @Test fun nothingChosenIsLimited() = assertEquals(popular.take(8), HomeDock.fill(emptyList(), popular, popular, false, 8))

    @Test fun autoOnlyFillsUpToCount() =
        assertEquals(listOf("a1", "a2", "p1", "p2", "p3", "p4"), HomeDock.fill(listOf("a1", "a2"), popular, popular, true, 6))

    @Test fun autoDoesNotRepeatChosen() =
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5", "p6"), HomeDock.fill(listOf("p1"), popular, popular, true, 6))

    @Test fun nothingAtAll() = assertEquals(emptyList<String>(), HomeDock.fill(emptyList<String>(), emptyList(), emptyList(), true, 8))
}
