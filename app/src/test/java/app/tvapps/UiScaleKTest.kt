package app.tvapps

import org.junit.Assert.assertEquals
import org.junit.Test

class UiScaleKTest {
    @Test fun overrideDensityGivesRatio() {
        assertEquals(240f / 153f, UiScale.tvRatio(240, 153), 0.001f)
    }
    @Test fun noOverrideIsOne() {
        assertEquals(1f, UiScale.tvRatio(240, 240), 0f)
        assertEquals(1f, UiScale.tvRatio(160, 160), 0f)
    }
    @Test fun smallDifferenceIgnored() {
        assertEquals(1f, UiScale.tvRatio(240, 230), 0f)
    }
    @Test fun higherThanPhysicalIsOne() {
        assertEquals(1f, UiScale.tvRatio(240, 320), 0f)
    }
    @Test fun badValues() {
        assertEquals(1f, UiScale.tvRatio(0, 153), 0f)
        assertEquals(1f, UiScale.tvRatio(240, 0), 0f)
    }
    @Test fun capped() {
        assertEquals(2.5f, UiScale.tvRatio(480, 120), 0f)
    }
    @Test fun gridWidthInDpMatchesReference() {
        // 1920 px: при 153 dpi 2008 dp, после коэффициента 1,57 снова 1280 dp, как при физических 240
        val k = UiScale.tvRatio(240, 153)
        assertEquals(1280f, 1920f / (153f / 160f) / k, 1f)
    }
}
