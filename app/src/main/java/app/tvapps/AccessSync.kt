package app.tvapps

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Автоматическое «закрепление» служб спецвозможностей: программа запоминает, какие службы были включены,
 * и после загрузки устройства включает заново те, которые прошивка не подняла.
 * Службу можно исключить в меню приложения («Не включать заново после загрузки»).
 */
object AccessSync {
    private const val KEY = "enabled_accessibility_services"

    private fun norm(s: String) = ComponentName.unflattenFromString(s.trim())?.flattenToString() ?: s.trim()

    fun enabledNow(ctx: Context): Set<String> =
        Settings.Secure.getString(ctx.contentResolver, KEY).orEmpty().split(":").filter { it.isNotBlank() }.map(::norm).toSet()

    /**
     * Обновляет список «что должно быть включено»: всё, что включено сейчас. Если устройство работает давно,
     * то выключенное служба считается выключенной нарочно и из списка уходит; сразу после загрузки список не трогаем,
     * чтобы не принять потерю службы за решение владельца.
     */
    fun sync(ctx: Context) {
        val now = enabledNow(ctx)
        val justBooted = SystemClock.elapsedRealtime() < 15 * 60 * 1000
        val keep = if (justBooted) Cfg.pinnedAccess.map(::norm).toSet() else emptySet()
        Cfg.changePinnedAccess((now + keep) - Cfg.noRestoreAccess.map(::norm).toSet())
    }

    fun hasSecurePermission(ctx: Context) =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    /** Один раз выдаёт программе право менять защищённые настройки (по ADB), чтобы дальше обходиться без ADB. */
    suspend fun grantPermission(ctx: Context) {
        if (hasSecurePermission(ctx) || !Adb.isConnected) return
        runCatching { Adb.shell("pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS") }
    }

    /** Включает заново закреплённые службы, которых сейчас нет среди включённых. */
    suspend fun restore(ctx: Context) {
        fun missing(): List<String> {
            val now = enabledNow(ctx)
            return Cfg.pinnedAccess.map(::norm).filter { it !in now && it !in Cfg.noRestoreAccess.map(::norm) }
        }
        if (missing().isEmpty()) return
        if (hasSecurePermission(ctx)) {
            val list = (enabledNow(ctx) + missing()).joinToString(":")
            withContext(Dispatchers.IO) {
                runCatching {
                    Settings.Secure.putString(ctx.contentResolver, KEY, list)
                    Settings.Secure.putInt(ctx.contentResolver, "accessibility_enabled", 1)
                }
            }
            return
        }
        // права ещё нет: подключаемся по ADB и включаем через него
        Adb.awaitReady()
        repeat(18) {
            if (!Adb.isConnected) autoConnect(ctx)
            if (Adb.isConnected) return@repeat
            delay(10_000)
        }
        if (Adb.isConnected) {
            grantPermission(ctx)
            for (c in missing()) runCatching { Actions.setAccessibility(c, true) }
        }
    }
}
