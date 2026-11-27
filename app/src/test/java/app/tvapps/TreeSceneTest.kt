package app.tvapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeSceneTest {
    private val w = 200
    private val h = 120
    private val clock = floatArrayOf(0.25f, 0.2f, 0.75f, 0.55f)

    /** Небо (дальнее, светлое) над лесом (ближнее, тёмное): слева лес выше, справа ниже. */
    private fun treeTop(x: Int) = (if (x < 112) 60 else 68) + (x % 3)
    private val depth = FloatArray(w * h) { if (it / w >= treeTop(it % w)) 0.7f else 0.05f }
    private val gray = FloatArray(w * h) { if (it / w >= treeTop(it % w)) 0.2f else 0.85f }

    @Test fun skylineFollowsTreeTops() {
        val line = TreeScene.skyline(depth, gray, w, h, clock)
        assertNotNull(line)
        for (x in listOf(20, 70, 160)) assertEquals("столбец $x", treeTop(x).toFloat(), line!![x], 3f)
    }

    @Test fun noSkylineForFlatOrEmptyPicture() {
        assertNull(TreeScene.skyline(FloatArray(w * h) { 0.7f }, gray, w, h, clock))
        assertNull(TreeScene.skyline(FloatArray(w * h) { 0.05f }, gray, w, h, clock))
    }

    @Test fun maskIsFrontBelowTheLineOnly() {
        val line = TreeScene.skyline(depth, gray, w, h, clock)!!
        val m = TreeScene.mask(line, gray, w, h)
        assertEquals(0, m[30 * w + 70])
        assertEquals(255, m[100 * w + 70])
    }

    @Test fun lowerTreesUnderMinutesGetTallerOnesBehind() {
        val tops = FloatArray(w) { treeTop(it).toFloat() }
        val bottom = TreeScene.digitBottom(tops, w, h, clock, 0.1f)
        val high = (clock[3] - clock[1]) * h
        val plan = TreeScene.planClone(tops, w, clock, bottom, high)
        assertNotNull(plan)
        val pix = IntArray(w * h) { if (it / w >= treeTop(it % w)) 0xFF204020.toInt() else 0xFFB0C8D8.toInt() }
        val mask = IntArray(w * h) { if (it / w >= treeTop(it % w)) 255 else 0 }
        TreeScene.applyClone(pix, mask, w, h, tops, plan!!)
        // справа, где лес был ниже, теперь есть передний план выше прежнего
        var higher = 0
        for (x in plan.start + 8 until minOf(w, plan.start + plan.width - 8)) if (mask[(treeTop(x) - 3) * w + x] > 127) higher++
        assertTrue("столбцов с деревьями выше прежних: $higher", higher > 3)
        // слева ничего не изменилось
        assertEquals(0, mask[(treeTop(20) - 3) * w + 20])
    }

    @Test fun evenForestNeedsNoExtraTrees() {
        val tops = FloatArray(w) { 60f + it % 3 }
        val bottom = TreeScene.digitBottom(tops, w, h, clock, 0.1f)
        assertNull(TreeScene.planClone(tops, w, clock, bottom, (clock[3] - clock[1]) * h))
    }

    @Test fun backdropChangesOnlyWhatFrontCovers() {
        val pix = IntArray(w * h) { if (it / w >= treeTop(it % w)) 0xFF204020.toInt() else 0xFFB0C8D8.toInt() }
        val mask = IntArray(w * h) { if (it / w >= treeTop(it % w) && it % w < 100) 255 else 0 }
        val back = TreeScene.backdrop(pix, mask, w, h)
        // под передним планом вместо дерева цвет неба, где переднего плана нет, всё по-прежнему
        assertEquals(0xFFB0C8D8.toInt(), back[70 * w + 40])
        for (y in 0 until h) assertEquals(pix[y * w + 150], back[y * w + 150])
        assertEquals(pix[20 * w + 40], back[20 * w + 40])
    }

    @Test fun cloudsOnlyOnOpenLightSky() {
        val far = FloatArray(w * h) { if (it / w >= treeTop(it % w)) 0.7f else 0.02f }
        val pix = IntArray(w * h) { if (it / w >= treeTop(it % w)) 0xFF204020.toInt() else 0xFFB0C8D8.toInt() }
        val sky = TreeScene.skyMask(far, pix, w, h)
        assertNotNull(sky)
        assertEquals(255, sky!![20 * w + 100])
        assertEquals(0, sky[100 * w + 100])
        // тёмный фон за предметом небом не считается
        assertNull(TreeScene.skyMask(far, IntArray(w * h) { 0xFF101018.toInt() }, w, h))
        val a = TreeScene.clouds(160, 60)
        assertTrue(a.any { it > 80 })
        assertTrue(a.any { it == 0 })
        // у нижнего края облака тают
        assertTrue((0 until 160).all { a[59 * 160 + it] < 40 })
    }

    @Test fun greenTreesSwayAndSmoothBlueWaterRipples() {
        val rnd = java.util.Random(1)
        val pix = IntArray(w * h) {
            val y = it / w
            when {
                y < 60 -> 0xFFB0C8D8.toInt()                                                   // небо
                y < 90 -> if (rnd.nextBoolean()) 0xFF1C3A14.toInt() else 0xFF5A7A40.toInt()   // кроны
                else -> 0xFF3A8090.toInt()                                                     // вода
            }
        }
        val mask = IntArray(w * h) { if (it / w >= 60) 255 else 0 }
        val tops = FloatArray(w) { 60f }
        val cols = 20
        val rows = 12
        val (sway, water) = TreeScene.motionMap(pix, mask, w, h, tops, cols, rows)
        fun at(a: FloatArray, y: Int) = a[(y * rows / (h - 1)) * (cols + 1) + cols / 2]
        assertTrue(at(sway, 70) > 0.2f)
        assertEquals(0f, at(sway, 20), 1e-6f)
        assertEquals(1f, at(water, 110), 1e-6f)
        assertEquals(0f, at(sway, 110), 1e-6f)
    }
}
