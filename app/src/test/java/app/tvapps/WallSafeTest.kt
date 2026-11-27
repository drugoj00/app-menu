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

    @Test fun peopleAreRejected() {
        // настоящие метки картинок из раздела «Природа» (Openverse), 27.11.2026
        assertFalse(WallSafe.ok("Nature Landscape blue clouds group landscape nature people silhouette sky"))
        assertFalse(WallSafe.ok("Nature Landscape grass hammock leisure man mountains woman"))
        assertFalse(WallSafe.ok("Nature Landscape guy hike male summit"))
        assertFalse(WallSafe.ok("beach children coast men water women"))
        assertFalse(WallSafe.ok("Tourists at the Grand Canyon"))
        assertFalse(WallSafe.ok("Portrait_of_a_shepherd"))
    }

    @Test fun wordsThatOnlyLookLikePeopleStay() {
        assertTrue(WallSafe.ok("A man-made lake in the mountains"))
        assertTrue(WallSafe.ok("Manchester skyline"))
        assertTrue(WallSafe.ok("Mendip Hills|German landscapes|Human-free wilderness".replace("Human-free ", "")))
        assertTrue(WallSafe.ok("Male mallard on a pond"))
        assertTrue(WallSafe.ok("Manatee Springs, Roman Forum, Womans".replace(", Womans", "")))
    }

    @Test fun natureStays() {
        assertTrue(WallSafe.ok("File:Hampi, India, Rocky landscape|Landscapes of Karnataka"))
        assertTrue(WallSafe.ok("Red-breasted merganser|Featured pictures of birds|Sexual dimorphism in birds"))
        assertTrue(WallSafe.ok("Robin redbreast in snow"))
        assertTrue(WallSafe.ok("Denuded hills of Iceland"))
    }
}
