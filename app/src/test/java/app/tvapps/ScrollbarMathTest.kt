package app.tvapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollbarMathTest {
    @Test fun nothingToScroll() {
        assertNull(ScrollbarMath.thumb(0f, 500f, 500f, 30f))
        assertNull(ScrollbarMath.thumb(0f, 0f, 500f, 30f))
    }
    @Test fun thumbAtStartAndEnd() {
        val a = ScrollbarMath.thumb(0f, 500f, 2000f, 30f)!!
        assertEquals(0f, a.top, 0.01f)
        assertEquals(125f, a.height, 0.01f)
        val b = ScrollbarMath.thumb(1500f, 500f, 2000f, 30f)!!
        assertEquals(500f, b.top + b.height, 0.01f)
    }
    @Test fun minimumThumbHeight() {
        val a = ScrollbarMath.thumb(0f, 500f, 100000f, 40f)!!
        assertEquals(40f, a.height, 0.01f)
    }
    @Test fun offsetOutOfRangeIsClamped() {
        val a = ScrollbarMath.thumb(99999f, 500f, 2000f, 30f)!!
        assertTrue(a.top + a.height <= 500.01f)
        assertNotNull(ScrollbarMath.thumb(-5f, 500f, 2000f, 30f))
    }

    @Test fun dragIsProportional() {
        // окно 500, содержимое 2000, ползунок 125: вся дорожка 375 px = вся прокрутка 1500 px
        val d = ScrollbarMath.contentDelta(375f, 500f, 2000f, 125f)
        assertEquals(1500f, d, 0.01f)
        assertEquals(750f, ScrollbarMath.contentDelta(187.5f, 500f, 2000f, 125f), 0.01f)
        assertEquals(0f, ScrollbarMath.contentDelta(50f, 500f, 500f, 125f), 0f)
    }
    @Test fun dragThenThumbFollowsFinger() {
        val vp = 500f; val c = 2000f
        var off = 300f
        val t0 = ScrollbarMath.thumb(off, vp, c, 36f)!!
        val move = 40f
        off += ScrollbarMath.contentDelta(move, vp, c, t0.height)
        val t1 = ScrollbarMath.thumb(off, vp, c, 36f)!!
        assertEquals(t0.top + move, t1.top, 0.01f)
    }
    @Test fun trackClickPagesTowardsTheClick() {
        val th = ScrollbarMath.Thumb(100f, 50f)
        assertEquals(-450f, ScrollbarMath.pageDelta(20f, th, 500f), 0.01f)
        assertEquals(450f, ScrollbarMath.pageDelta(400f, th, 500f), 0.01f)
        assertEquals(0f, ScrollbarMath.pageDelta(120f, th, 500f), 0f)
    }

    @Test fun contentIsLeftOfTheBar() {
        // 1080 px экрана, поля по 8 с каждой стороны, поле полосы 20: плитки занимают 1080 - 16 - 20
        assertEquals(1044f, ScrollbarMath.contentWidth(1080f, 8f, 20f), 0.01f)
        assertTrue(ScrollbarMath.ZONE_DP < ScrollbarMath.RESERVE_DP)
        assertTrue("резерв не больше 24 dp", ScrollbarMath.RESERVE_DP <= 24f)
        assertTrue("зона 14-18 dp", ScrollbarMath.ZONE_DP in 14f..18f)
        assertEquals(1f, ScrollbarMath.contentWidth(10f, 8f, 28f), 0f)
    }
    @Test fun columnsStillFitWithReserve() {
        val d = 2.625f
        val total = 1080f
        val avail = ScrollbarMath.contentWidth(total, 2f * d, ScrollbarMath.RESERVE_DP * d)
        for (g in 0..2) {
            val m = GridMetrics.dense(1080f, 2400f, avail, 2000f, d, 12f * d, 2, gapFactor = GridMetrics.gapFactor(g), minGapPx = GridMetrics.minGapDp(g) * d)
            assertTrue("ячейки умещаются: g=$g", m.cols * m.cellPx <= avail + 0.5f)
            assertTrue(m.cols >= 3)
        }
    }
}
