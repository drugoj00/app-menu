package app.tvapps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallSafeTest {
    @Test fun nudeIsRejected() {
        // настоящий случай 26.11.2026: тема «Природа» на телефоне
        assertFalse(WallSafe.ok("File:Bewegungsstudie (Movement Study) 1925 by Rudolf Koppitz|1925 nude photographs|Dance"))
        assertFalse(WallSafe.ok("Female_nude_in_a_landscape"))
        assertFalse(WallSafe.ok("Erotic art of the 19th century"))
        assertFalse(WallSafe.ok("Aktstudie am Strand"))
    }

    @Test fun natureStays() {
        assertTrue(WallSafe.ok("File:Hampi, India, Rocky landscape|Landscapes of Karnataka"))
        assertTrue(WallSafe.ok("Red-breasted merganser|Featured pictures of birds|Sexual dimorphism in birds"))
        assertTrue(WallSafe.ok("Robin redbreast in snow"))
        assertTrue(WallSafe.ok("Denuded hills of Iceland"))
    }
}
