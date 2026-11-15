package app.tvapps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbGuardTest {
    @Test fun installWithoutUserIsRejected() {
        assertTrue(AdbGuard.violates("pm install -r /sdcard/a.apk"))
        assertTrue(AdbGuard.violates("cmd package install -r -S 100"))
        assertTrue(AdbGuard.violates("cmd package install-create -r -S 100"))
        assertTrue(AdbGuard.violates("pm install-existing com.x"))
        assertTrue(AdbGuard.violates("pm uninstall -k com.x"))
        assertTrue(AdbGuard.violates("echo 1; pm install a.apk"))
    }

    @Test fun commandsWithUserPass() {
        assertFalse(AdbGuard.violates("pm install -r --user 0 /sdcard/a.apk"))
        assertFalse(AdbGuard.violates("cmd package install -r --user 10 -S 100"))
        assertFalse(AdbGuard.violates("cmd package install-create -r --user 0 -S 100"))
        assertFalse(AdbGuard.violates("pm uninstall -k --user 0 com.x"))
        assertFalse(AdbGuard.violates("pm install-existing --user 0 com.x"))
    }

    @Test fun sessionStepsAndOtherCommandsPass() {
        assertFalse(AdbGuard.violates("cmd package install-write -S 100 5 base.apk -"))
        assertFalse(AdbGuard.violates("cmd package install-commit 5"))
        assertFalse(AdbGuard.violates("cmd package install-abandon 5"))
        assertFalse(AdbGuard.violates("pm list packages"))
        assertFalse(AdbGuard.violates("am get-current-user"))
        assertFalse(AdbGuard.violates("pm disable-user --user 0 com.x"))
    }
}
