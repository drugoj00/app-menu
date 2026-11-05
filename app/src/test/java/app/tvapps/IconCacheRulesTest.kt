package app.tvapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Логика имён и устаревания кэша значков и замена плохого значка из кэша. */
class IconCacheRulesTest {
    private val v = IconCacheRules.ICON_PIPELINE_VERSION

    @Test fun nameRoundTrip() {
        val n = IconCacheRules.name("app.pulsar_tv", 1730000000000L, v, 10)
        val q = IconCacheRules.parse(n)!!
        assertEquals("app.pulsar_tv", q.pkg); assertEquals(1730000000000L, q.updated); assertEquals(v, q.pipeline); assertEquals(10, q.appVersion)
    }

    @Test fun oldFormatNameIsNotCurrent() {
        assertNull(IconCacheRules.parse("com.pulsar.player.png"))
        assertFalse(IconCacheRules.isCurrent("com.pulsar.player.png", "com.pulsar.player", 5L, v, 10))
    }

    @Test fun currentOnlyWhenAllMatch() {
        val n = IconCacheRules.name("a.b", 100L, v, 10)
        assertTrue(IconCacheRules.isCurrent(n, "a.b", 100L, v, 10))
        assertFalse("приложение обновилось", IconCacheRules.isCurrent(n, "a.b", 101L, v, 10))
        assertFalse("сменился способ отрисовки", IconCacheRules.isCurrent(n, "a.b", 100L, v + 1, 10))
        assertFalse("сменилась версия программы", IconCacheRules.isCurrent(n, "a.b", 100L, v, 11))
        assertFalse("другой пакет", IconCacheRules.isCurrent(n, "a.c", 100L, v, 10))
    }

    @Test fun staleFilesAreOldFormatOldTimeAndOtherPackages() {
        val keepName = IconCacheRules.name("a.b", 200L, v, 10)
        val files = listOf(
            keepName, IconCacheRules.name("a.b", 100L, v, 10), "a.b.png", IconCacheRules.name("gone.app", 1L, v, 10),
            IconCacheRules.name("a.b_c", 5L, v, 10),
        )
        val keep = mapOf("a.b" to keepName, "a.b_c" to IconCacheRules.name("a.b_c", 5L, v, 10))
        val del = IconCacheRules.stale(files, keep)
        assertEquals(setOf(IconCacheRules.name("a.b", 100L, v, 10), "a.b.png", IconCacheRules.name("gone.app", 1L, v, 10)), del.toSet())
    }

    @Test fun badIconInCacheIsReplaced() {
        // файл актуален по имени, но внутри чёрный квадрат (старый мусор): использовать нельзя
        val black = IntArray(108 * 108) { 0xFF000000.toInt() }
        assertFalse(IconCacheRules.useCached(true, IconPixels.isBad(black)))
        // нормальный значок из актуального файла используется
        val good = IntArray(108 * 108) { if (it % 2 == 0) 0xFF2A7FDB.toInt() else 0xFFF2B01E.toInt() }
        assertTrue(IconCacheRules.useCached(true, IconPixels.isBad(good)))
        // устаревший файл не используется даже если картинка хорошая
        assertFalse(IconCacheRules.useCached(false, false))
    }
}
