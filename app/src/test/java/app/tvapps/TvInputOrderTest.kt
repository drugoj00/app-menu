package app.tvapps

import android.media.tv.TvInputInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Порядок строки «Входы» (19.11.2026): HDMI по номеру, затем AV, компонентный и остальные; названия без номера после нумерованных. */
class TvInputOrderTest {
    private class In(val type: Int, val label: String, val id: String)

    private fun sorted(vararg l: In): List<String> =
        l.sortedWith { a, b -> TvInputOrder.compareFull(a.type, a.label, a.id, b.type, b.label, b.id) }.map { it.label }

    @Test fun hdmiAreInNumericOrderNotSystemOrder() {
        val s = sorted(
            In(TvInputInfo.TYPE_HDMI, "HDMI 3", "x/HW7"), In(TvInputInfo.TYPE_HDMI, "HDMI 4", "x/HW8"),
            In(TvInputInfo.TYPE_HDMI, "HDMI 1", "x/HW5"), In(TvInputInfo.TYPE_HDMI, "HDMI 2", "x/HW6"),
        )
        assertEquals(listOf("HDMI 1", "HDMI 2", "HDMI 3", "HDMI 4"), s)
    }

    @Test fun tenComesAfterNine() {
        val s = sorted(In(TvInputInfo.TYPE_HDMI, "HDMI 10", "a"), In(TvInputInfo.TYPE_HDMI, "HDMI 9", "b"), In(TvInputInfo.TYPE_HDMI, "HDMI 2", "c"))
        assertEquals(listOf("HDMI 2", "HDMI 9", "HDMI 10"), s)
    }

    @Test fun hdmiFirstThenAvComponentOthers() {
        val s = sorted(
            In(TvInputInfo.TYPE_COMPONENT, "Component", "c"), In(TvInputInfo.TYPE_HDMI, "HDMI 2", "h2"), In(TvInputInfo.TYPE_COMPOSITE, "AV", "av"),
            In(TvInputInfo.TYPE_HDMI, "HDMI 1", "h1"), In(TvInputInfo.TYPE_VGA, "VGA", "vga"),
        )
        assertEquals(listOf("HDMI 1", "HDMI 2", "AV", "Component", "VGA"), s)
    }

    @Test fun renamedInputWithoutNumberGoesAfterNumbered() {
        val s = sorted(In(TvInputInfo.TYPE_HDMI, "Apple TV", "h3"), In(TvInputInfo.TYPE_HDMI, "HDMI 2", "h2"), In(TvInputInfo.TYPE_HDMI, "HDMI 1(ARC)", "h1"))
        assertEquals(listOf("HDMI 1(ARC)", "HDMI 2", "Apple TV"), s)
    }

    @Test fun sameLabelsKeepStableOrderById() {
        assertTrue(TvInputOrder.compare("HDMI 1", "a", "HDMI 1", "b") < 0)
        assertEquals(0, TvInputOrder.compare("HDMI 1", "a", "HDMI 1", "a"))
    }
}
