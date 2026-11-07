package app.tvapps

import org.junit.Assert.assertEquals
import org.junit.Test

class StepsTest {
    @Test fun autoPlusStartsFromShown() {
        assertEquals(13, Steps.autoStep(0, +1, 16, 12))
        assertEquals(11, Steps.autoStep(0, -1, 16, 12))
    }
    @Test fun autoUnknownUsesFallback() {
        assertEquals(Steps.FALLBACK_AUTO + 1, Steps.autoStep(0, +1, 16, 0))
        assertEquals(Steps.FALLBACK_AUTO - 1, Steps.autoStep(0, -1, 16, -3))
    }
    @Test fun neverBelowOneAndNeverBackToAuto() {
        assertEquals(1, Steps.autoStep(1, -1, 16, 12))
        assertEquals(1, Steps.autoStep(2, -1, 16, 12))
        assertEquals(1, Steps.autoStep(2, -5, 16, 12))
    }
    @Test fun upperBound() {
        assertEquals(16, Steps.autoStep(16, +1, 16, 12))
        assertEquals(12, Steps.autoStep(12, +1, 12, 12))
        assertEquals(12, Steps.autoStep(0, +1, 12, 40))   // «Авто» с большим показанным числом
    }
    @Test fun manyFastSteps() {
        var v = 10
        repeat(100) { v = Steps.autoStep(v, -1, 16, 12) }
        assertEquals(1, v)
        repeat(100) { v = Steps.autoStep(v, +1, 16, 12) }
        assertEquals(16, v)
    }
    @Test fun rangeScale() {
        assertEquals(50, Steps.rangeStep(50, -1, 50, 200, 10))
        assertEquals(200, Steps.rangeStep(200, +1, 50, 200, 10))
        assertEquals(110, Steps.rangeStep(100, +1, 50, 200, 10))
        assertEquals(90, Steps.rangeStep(100, -1, 50, 200, 10))
        assertEquals(60, Steps.rangeStep(40, +1, 50, 200, 10))   // значение ниже границы (старая запись) приводится внутрь
        assertEquals(190, Steps.rangeStep(250, -1, 50, 200, 10))
    }
    @Test fun cleanAuto() {
        assertEquals(0, Steps.cleanAuto(-4, 16))
        assertEquals(0, Steps.cleanAuto(0, 16))
        assertEquals(16, Steps.cleanAuto(99, 16))
        assertEquals(7, Steps.cleanAuto(7, 16))
    }
}
