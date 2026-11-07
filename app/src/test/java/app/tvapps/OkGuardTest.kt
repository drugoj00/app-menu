package app.tvapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OkGuardTest {
    @Test fun holdRepeatsAndReleaseAreEatenThenNextPressWorks() {
        var until = 10_000L
        // повторы «ОК», пока кнопка зажата
        for (t in 1000L..1500L step 100L) { val s = OkGuard.step(until, t, true, false); assertTrue(s.eat); until = s.until }
        // отпускание съедается и снимает защиту
        val up = OkGuard.step(until, 1700L, true, true)
        assertTrue(up.eat); assertEquals(0L, up.until); until = up.until
        // новое нажатие действует
        assertFalse(OkGuard.step(until, 2500L, true, false).eat)
        assertFalse(OkGuard.step(until, 2600L, true, true).eat)
    }
    @Test fun otherKeysAreNeverEaten() {
        assertFalse(OkGuard.step(10_000L, 1000L, false, false).eat)
        assertFalse(OkGuard.step(10_000L, 1000L, false, true).eat)
    }
    @Test fun staleGuardExpires() {
        val s = OkGuard.step(5_000L, 9_000L, true, false)
        assertFalse(s.eat); assertEquals(0L, s.until)
    }
    @Test fun noGuardNoEating() = assertFalse(OkGuard.step(0L, 100L, true, true).eat)
    @Test fun itemsDoNotReactRightAfterOpening() {
        assertTrue(OkGuard.tooEarly(1100L, 1000L, true))
        assertFalse(OkGuard.tooEarly(1000L + OkGuard.OPEN_DELAY_MS, 1000L, true))
        assertFalse(OkGuard.tooEarly(1100L, 1000L, false))
    }
}
