package app.tvapps

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FlyersTest {
    @Test fun staysAroundTheScreenAndMoves() {
        val a = FloatArray(5)
        val b = FloatArray(5)
        for (kind in Flyers.MONEY until Flyers.KINDS) {
            var moved = 0
            for (i in 0 until Flyers.count(kind)) {
                Flyers.at(kind, i, 1000L, 1920f, 1080f, a)
                Flyers.at(kind, i, 1100L, 1920f, 1080f, b)
                assertTrue(a[0] > -400f && a[0] < 2320f && a[1] > -400f && a[1] < 1480f)
                if (abs(a[0] - b[0]) + abs(a[1] - b[1]) > 3f) moved++
            }
            assertTrue("вид $kind: двигаются $moved", moved > Flyers.count(kind) * 9 / 10)
        }
    }

    @Test fun nearOnesAreBigger() {
        val p = FloatArray(5)
        var near = 0f; var far = 0f; var nn = 0; var nf = 0
        for (i in 0 until Flyers.count(Flyers.MONEY)) {
            Flyers.at(Flyers.MONEY, i, 0L, 1280f, 720f, p)
            if (Flyers.isNear(i)) { near += p[4]; nn++ } else { far += p[4]; nf++ }
        }
        assertTrue(near / nn > far / nf)
    }
}
