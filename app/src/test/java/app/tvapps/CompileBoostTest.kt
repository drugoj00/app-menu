package app.tvapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompileBoostTest {
    private val speedDump = """
        Dexopt state:
          [app.tvapps]
            path: /data/app/~~x/app.tvapps-y/base.apk
              arm: [status=speed] [reason=cmdline]
    """.trimIndent()

    @Test fun speedIsCompiled() = assertTrue(CompileBoost.allSpeed(speedDump))
    @Test fun verifyMeansReset() = assertFalse(CompileBoost.allSpeed(speedDump.replace("status=speed", "status=verify")))
    @Test fun quickenMeansReset() = assertFalse(CompileBoost.allSpeed(speedDump.replace("status=speed", "status=quicken")))
    @Test fun runFromApkMeansReset() = assertFalse(CompileBoost.allSpeed(speedDump.replace("status=speed", "status=run-from-apk")))
    @Test fun emptyOrNoStatusIsNotCompiled() {
        assertFalse(CompileBoost.allSpeed(""))
        assertFalse(CompileBoost.allSpeed("Dexopt state:\n  [app.tvapps]\n"))
    }
    @Test fun oneSplitNotSpeedIsNotCompiled() {
        val two = speedDump + "\n      arm: [status=verify] [reason=install]"
        assertFalse(CompileBoost.allSpeed(two))
    }
    @Test fun checkOncePerTwelveHours() {
        val h = 60 * 60_000L
        assertFalse("недавно проверяли и версия готова", CompileBoost.needsStateCheck(true, 1000L, 1000L + 11 * h))
        assertTrue("прошло 12 часов", CompileBoost.needsStateCheck(true, 1000L, 1000L + 12 * h))
        assertTrue("версия ещё не компилировалась", CompileBoost.needsStateCheck(false, 1000L, 1500L))
        assertTrue("проверки не было", CompileBoost.needsStateCheck(true, 0L, 1500L))
        assertEquals(true, CompileBoost.needsStateCheck(true, 5000L, 1000L))   // часы ушли назад
    }
}
