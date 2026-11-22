package app.tvapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Логика копий в сеть и быстрых действий: правило синхронизации «последняя запись побеждает», разбор ответа WebDAV, назначения кнопок. */
class BkLogicTest {
    private fun d(lh: String, ll: String?, lc: Long, rt: Long?, lr: Long) = BkEngine.decide(lh, ll, lc, rt, lr)

    @Test fun noSharedFilePushes() = assertEquals(BkEngine.Act.PUSH, d("a", null, 0, null, 0))
    @Test fun nothingChanged() = assertEquals(BkEngine.Act.NONE, d("a", "a", 100, 500, 500))
    @Test fun onlyRemoteChangedPulls() = assertEquals(BkEngine.Act.PULL, d("a", "a", 100, 900, 500))
    @Test fun onlyLocalChangedPushes() = assertEquals(BkEngine.Act.PUSH, d("b", "a", 700, 500, 500))
    @Test fun bothChangedLocalNewerPushes() = assertEquals(BkEngine.Act.PUSH, d("b", "a", 1000, 900, 500))
    @Test fun bothChangedRemoteNewerPulls() = assertEquals(BkEngine.Act.PULL, d("b", "a", 800, 900, 500))
    @Test fun firstSyncWithExistingFilePulls() = assertEquals(BkEngine.Act.PULL, d("a", null, 0, 900, 0))

    @Test fun multistatusParsesFilesAndSkipsFolders() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
 <d:response><d:href>/AppMenu/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
 <d:response><d:href>/AppMenu/AppMenu_sync.json</d:href><d:propstat><d:prop><d:getcontentlength>120</d:getcontentlength><d:getlastmodified>Sat, 21 Nov 2026 10:00:00 GMT</d:getlastmodified><d:resourcetype/></d:prop></d:propstat></d:response>
 <d:response><d:href>/AppMenu/%D0%9A%D0%BE%D0%BF%D0%B8%D1%8F%20%D1%82%D0%B5%D1%81%D1%82.zip</d:href><d:propstat><d:prop><d:getcontentlength>2048</d:getcontentlength><d:resourcetype/></d:prop></d:propstat></d:response>
 <d:response><d:href>/AppMenu/sub/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
</d:multistatus>"""
        val l = DavStore.parseMultistatus(xml, "/AppMenu")
        assertEquals(listOf("AppMenu_sync.json", "Копия тест.zip"), l.map { it.name })
        assertEquals(120L, l[0].size)
        assertTrue(l[0].modified > 0L)
        assertEquals(2048L, l[1].size)
    }

    @Test fun multistatusWithOtherPrefix() {
        val xml = """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:"><D:response><D:href>http://h/x/a.zip</D:href><D:propstat><D:prop><D:getcontentlength>5</D:getcontentlength></D:prop></D:propstat></D:response></D:multistatus>"""
        assertEquals(listOf("a.zip"), DavStore.parseMultistatus(xml, "/x").map { it.name })
    }

    @Test fun bindParseFormatRoundTrip() {
        val l = QuickActions.parse("last=183:S;search=184:L;bad;x=0:S;mem=abc:S")
        assertEquals(2, l.size)
        assertEquals("last", l[0].action); assertEquals(183, l[0].code); assertFalse(l[0].long)
        assertTrue(l[1].long)
        assertEquals("last=183:S;search=184:L", QuickActions.format(l))
    }

    @Test fun assignableRejectsNavigationAndLetters() {
        assertFalse(QuickActions.assignable(android.view.KeyEvent.KEYCODE_DPAD_CENTER, false))
        assertFalse(QuickActions.assignable(android.view.KeyEvent.KEYCODE_BACK, false))
        assertFalse(QuickActions.assignable(android.view.KeyEvent.KEYCODE_A, true))
        assertTrue(QuickActions.assignable(android.view.KeyEvent.KEYCODE_PROG_RED, false))
    }
}
