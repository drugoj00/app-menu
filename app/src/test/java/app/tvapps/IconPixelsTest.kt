package app.tvapps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Детектор пустых и чёрных значков на синтетических картинках 108x108 (как mdpi-значок Pulsar: 669 непрозрачных пикселей из 11664). */
class IconPixelsTest {
    private val n = 108 * 108

    @Test fun almostEmptyMdpiIconIsBad() {
        val px = IntArray(n) // прозрачное
        // 669 непрозрачных пикселей (5,7%), светлые
        for (i in 0 until 669) px[i * 7] = 0xFFFFFFFF.toInt()
        assertTrue(IconPixels.isBad(px))
    }

    @Test fun fullyTransparentIsBad() { assertTrue(IconPixels.isBad(IntArray(n))) }

    @Test fun solidBlackSquareIsBad() { assertTrue(IconPixels.isBad(IntArray(n) { 0xFF000000.toInt() })) }

    @Test fun solidDarkGraySquareIsBad() { assertTrue(IconPixels.isBad(IntArray(n) { 0xFF101010.toInt() })) }

    @Test fun normalColorfulIconIsGood() {
        val px = IntArray(n)
        for (y in 0 until 108) for (x in 0 until 108) {
            val inside = (x - 54) * (x - 54) + (y - 54) * (y - 54) < 50 * 50
            px[y * 108 + x] = if (!inside) 0 else if (x < 54) 0xFF2A7FDB.toInt() else 0xFFF2B01E.toInt()
        }
        assertFalse(IconPixels.isBad(px))
    }

    @Test fun darkIconWithLightGlyphIsGood() {
        // чёрный фон со светлой фигурой: разброс яркости большой, это нормальный значок
        val px = IntArray(n) { 0xFF000000.toInt() }
        for (y in 30 until 78) for (x in 30 until 78) px[y * 108 + x] = 0xFFFFFFFF.toInt()
        assertFalse(IconPixels.isBad(px))
    }

    @Test fun smallLogoOnWhiteIsGood() {
        val px = IntArray(n) { 0xFFFFFFFF.toInt() }
        for (y in 40 until 68) for (x in 40 until 68) px[y * 108 + x] = 0xFF004488.toInt()
        assertFalse(IconPixels.isBad(px))
    }
}
