package app.tvapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthMathTest {
    @Test fun normalizeStretchesToZeroOne() {
        val d = DepthMath.normalize(floatArrayOf(10f, 20f, 30f))
        assertEquals(0f, d[0], 1e-6f)
        assertEquals(0.5f, d[1], 1e-6f)
        assertEquals(1f, d[2], 1e-6f)
    }

    @Test fun otsuSplitsTwoGroups() {
        val d = FloatArray(1000) { if (it < 600) 0.1f + (it % 5) / 200f else 0.8f + (it % 5) / 200f }
        val thr = DepthMath.otsu(d)
        assertTrue("граница между группами: $thr", thr > 0.13f && thr < 0.8f)
    }

    @Test fun boxKeepsConstant() {
        val a = FloatArray(12 * 7) { 0.4f }
        for (v in DepthMath.box(a, 12, 7, 3)) assertEquals(0.4f, v, 1e-5f)
    }

    @Test fun boxIsAverageWithRepeatedEdges() {
        // строка 0 0 9: при r=1 среднее по трём с повторением края
        val out = DepthMath.box(floatArrayOf(0f, 0f, 9f), 3, 1, 1)
        assertEquals(0f, out[0], 1e-5f)
        assertEquals(3f, out[1], 1e-5f)
        assertEquals(6f, out[2], 1e-5f)
    }

    @Test fun maskKeepsNearHalfAndLevelMovesBorder() {
        val w = 64
        val h = 64
        // глубина: плавно от дальнего сверху к ближнему снизу; яркость такая же, чтобы фильтр не мешал
        val depth = FloatArray(w * h) { (it / w) / (h - 1f) }
        val gray = FloatArray(w * h) { 0.5f }
        val m = DepthMath.mask(depth, w, h, gray, w, h, 0)
        assertEquals(0, m[5 * w + 30])
        assertEquals(255, m[58 * w + 30])
        fun front(level: Int) = DepthMath.mask(depth, w, h, gray, w, h, level).count { it > 127 }
        assertTrue(front(20) > front(0))
        assertTrue(front(-20) < front(0))
    }

    @Test fun shareCountsPartOfRect() {
        val side = 10
        assertEquals(0.5f, DepthMath.share(side, side, 0f, 0f, 1f, 1f) { it / side < 5 }, 1e-6f)
        assertEquals(1f, DepthMath.share(side, side, 0f, 0.5f, 1f, 1f) { it / side >= 5 }, 1e-6f)
    }

    @Test fun onlyPartOfBottomIsCovered() {
        val side = 100
        val clock = floatArrayOf(0.2f, 0.3f, 0.8f, 0.6f)
        // под цифрами близко везде, но слева ближе, чем справа: впереди должна остаться только часть полоски, а не вся
        val uneven = FloatArray(side * side) { val x = it % side; if (it / side >= 50) 0.6f + 0.4f * (99 - x) / 99f else 0.05f }
        val lv = DepthMath.autoLevel(uneven, side, side, clock, 0.1f)
        assertTrue("сдвиг: $lv", lv < 0)
        val thr = DepthMath.otsu(uneven) - lv / 100f
        val part = DepthMath.share(side, side, 0.2f, 0.57f, 0.8f, 0.6f) { uneven[it] > thr }
        assertTrue("часть полоски: $part", part > 0.08f && part <= 0.62f)
        // ближнее одной глубины: делить нечего, обычная граница
        val flat = FloatArray(side * side) { if (it / side >= 50) 0.9f else 0.1f }
        assertEquals(0, DepthMath.autoLevel(flat, side, side, clock, 0.1f))
    }

    @Test fun clockPlaceFollowsCrop() {
        val rect = floatArrayOf(0.25f, 0.5f, 0.75f, 0.7f)
        // экран и картинка одной формы: доли совпадают
        val same = DepthMath.toPicture(rect, 1920f, 1080f, 3840f, 2160f, false, 0f, 0f, 1f)
        for (i in 0 until 4) assertEquals(rect[i], same[i], 1e-4f)
        // широкая картинка на высоком экране: по ширине видна только середина, по высоте всё
        val tall = DepthMath.toPicture(rect, 1000f, 2000f, 2000f, 1000f, false, 0f, 0f, 1f)
        assertEquals(0.4375f, tall[0], 1e-4f)
        assertEquals(0.5625f, tall[2], 1e-4f)
        assertEquals(0.5f, tall[1], 1e-4f)
        // растяжение: доли не меняются
        val st = DepthMath.toPicture(rect, 1000f, 2000f, 2000f, 1000f, true, 0f, 0f, 1f)
        for (i in 0 until 4) assertEquals(rect[i], st[i], 1e-4f)
        // увеличение вдвое вокруг середины: место на картинке сжимается к середине
        val z = DepthMath.toPicture(rect, 1920f, 1080f, 1920f, 1080f, false, 0f, 0f, 2f)
        assertEquals(0.375f, z[0], 1e-4f)
        assertEquals(0.6f, z[3], 1e-4f)
    }

    @Test fun clockOptionsKeepLevel() {
        val ref = BigClockOpt.make(true, 8, 35)
        assertTrue(BigClockOpt.h12(ref))
        assertEquals(8, BigClockOpt.drop(ref))
        assertEquals(35, BigClockOpt.cover(ref))
        assertEquals(10, BigClockOpt.cover(BigClockOpt.make(false, 12)))
        assertEquals(22, BigClockOpt.drop(""))
        assertTrue(BigClockOpt.live(BigClockOpt.make(false, 22, 10, true)))
        assertTrue(!BigClockOpt.live(ref))
    }
}
