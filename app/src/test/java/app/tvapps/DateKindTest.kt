package app.tvapps

import org.junit.Assert.assertEquals
import org.junit.Test

class DateKindTest {
    @Test fun eightKindsGoRoundAndKeepOtherBits() {
        // биты 8 и 16 (год) и 32 не должны меняться при переключении вида
        var opt = 8 or 32
        val seen = ArrayList<Int>()
        repeat(8) { seen.add(dateKind(opt)); opt = dateNext(opt) }
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), seen)
        assertEquals(8 or 32, opt)
    }

    @Test fun savedSettingsOfOldVersionsStayTheSame() {
        for (old in 0..3) assertEquals(old, dateKind(old))
        assertEquals("d MMMM, EEEE", datePattern(64 or 16))
    }
}
