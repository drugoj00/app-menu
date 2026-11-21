package app.tvapps

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseSumTest {
    private val a = "47eae43fda96e2871e4d0668b3080edc3077dd43e8f951a8bb39074fb885f4cb"
    private val b = "50c8e1ecccdf018406449c65b9c89f75c602d4b052e559822f6a9157e98178c0"

    @Test fun oneSumFromRealReleaseBody() {
        val body = "**versionCode: 48**\r\n\r\nAndroid 6.0 or newer.\r\n\r\nSHA-256 `$a`\r\n"
        assertEquals(a, Updates.pickBodySum(body, "AppMenu-11.0.apk"))
    }

    @Test fun upperCaseIsLowered() {
        assertEquals(a, Updates.pickBodySum("sha256: ${a.uppercase()}", "x.apk"))
    }

    @Test fun twoSumsPickLineWithFileName() {
        val body = "Signing certificate SHA-256 `$b`\nAppMenu-11.0.apk SHA-256 `$a`\n"
        assertEquals(a, Updates.pickBodySum(body, "AppMenu-11.0.apk"))
    }

    @Test fun twoSumsWithoutFileNameGiveNothing() {
        val body = "Signing certificate SHA-256 `$b`\nAPK SHA-256 `$a`\n"
        assertEquals("", Updates.pickBodySum(body, "AppMenu-11.0.apk"))
    }

    @Test fun noSum() {
        assertEquals("", Updates.pickBodySum("nothing here", "x.apk"))
    }
}
