package app.tvapps

import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager

/** Действия над чужим приложением через ADB на этом же устройстве (как в ADB-phon). */
object Actions {
    fun ok(out: String) = !(out.contains("Exception", true) || out.contains("Failure", true) || out.contains("Error", true) || out.contains("denied", true))

    suspend fun forceStop(pkg: String): Boolean = ok(Adb.shell("am force-stop $pkg"))

    /** Кэш: на Android 11+ системная команда; на старых хотя бы внешний кэш в Android/data. */
    suspend fun clearCache(pkg: String): Boolean {
        val out = Adb.shell("pm clear --cache-only $pkg")
        if (out.contains("Success", true)) return true
        val ext = Adb.shell("rm -rf /sdcard/Android/data/$pkg/cache/* /sdcard/Android/data/$pkg/cache/.[!.]* 2>&1; echo END")
        return ext.contains("END")
    }

    suspend fun clearData(pkg: String): Boolean = Adb.shell("pm clear $pkg").contains("Success", true)

    // ---------- разрешения ----------

    suspend fun runtimePerms(pkg: String): List<Pair<String, Boolean>> {
        val out = Adb.shell("dumpsys package $pkg")
        val list = mutableListOf<Pair<String, Boolean>>()
        var inRuntime = false
        for (line in out.lines()) {
            val t = line.trim()
            if (t.startsWith("runtime permissions:")) { inRuntime = true; continue }
            if (!inRuntime) continue
            val m = Regex("^([\\w.]+): granted=(true|false)").find(t)
            if (m != null) list.add(m.groupValues[1] to (m.groupValues[2] == "true")) else if (list.isNotEmpty() || t.isNotEmpty()) break
        }
        return list.distinctBy { it.first }
    }

    suspend fun setPerm(pkg: String, perm: String, grant: Boolean): Boolean =
        ok(Adb.shell("pm ${if (grant) "grant" else "revoke"} $pkg $perm"))

    // ---------- спецвозможности ----------

    /** Специальный доступ через appops: код, название в системе. */
    val appOps = listOf(
        "overlay" to "SYSTEM_ALERT_WINDOW",
        "settings" to "WRITE_SETTINGS",
        "usage" to "GET_USAGE_STATS",
        "files" to "MANAGE_EXTERNAL_STORAGE",
        "install" to "REQUEST_INSTALL_PACKAGES",
        "restricted" to "ACCESS_RESTRICTED_SETTINGS",
    )

    suspend fun opAllowed(pkg: String, op: String): Boolean =
        Adb.shell("appops get $pkg $op").lowercase().contains(": allow")

    suspend fun setOp(pkg: String, op: String, on: Boolean): Boolean =
        ok(Adb.shell("appops set $pkg $op ${if (on) "allow" else "default"}"))

    fun serviceOf(ctx: Context, pkg: String, action: String): String? = runCatching {
        ctx.packageManager.queryIntentServices(android.content.Intent(action).setPackage(pkg), 0)
            .firstOrNull()?.serviceInfo?.let { android.content.ComponentName(it.packageName, it.name).flattenToString() }
    }.getOrNull()

    suspend fun accessibilityOn(comp: String): Boolean {
        val short = comp.replace(Regex("^([^/]+)/\\1"), "$1/")
        val cur = Adb.shell("settings get secure enabled_accessibility_services")
        return cur.split(":").any { it == comp || it == short }
    }

    suspend fun setAccessibility(comp: String, on: Boolean): Boolean {
        val cur = Adb.shell("settings get secure enabled_accessibility_services").trim().let { if (it == "null") "" else it }
        val items = cur.split(":").filter { it.isNotBlank() && it != comp }.toMutableList()
        if (on) items.add(comp)
        val value = if (items.isEmpty()) "null" else items.joinToString(":")
        val out = Adb.shell("settings put secure enabled_accessibility_services \"$value\"; settings put secure accessibility_enabled ${if (items.isEmpty()) 0 else 1}; echo OK")
        return out.contains("OK") && ok(out)
    }

    suspend fun notifOn(comp: String): Boolean =
        Adb.shell("settings get secure enabled_notification_listeners").split(":").any { it == comp }

    suspend fun setNotif(comp: String, on: Boolean): Boolean =
        ok(Adb.shell("cmd notification ${if (on) "allow_listener" else "disallow_listener"} $comp; echo OK"))

    // ---------- батарея ----------

    enum class Battery { UNRESTRICTED, OPTIMIZED, RESTRICTED }

    suspend fun batteryState(ctx: Context, pkg: String): Battery {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (runCatching { pm.isIgnoringBatteryOptimizations(pkg) }.getOrDefault(false)) return Battery.UNRESTRICTED
        val bg = Adb.shell("appops get $pkg RUN_ANY_IN_BACKGROUND").lowercase()
        return if (bg.contains(": ignore") || bg.contains(": deny")) Battery.RESTRICTED else Battery.OPTIMIZED
    }

    suspend fun setBattery(pkg: String, mode: Battery): Boolean {
        val cmd = when (mode) {
            Battery.UNRESTRICTED -> "appops set $pkg RUN_ANY_IN_BACKGROUND allow; appops set $pkg RUN_IN_BACKGROUND allow; dumpsys deviceidle whitelist +$pkg; echo OK"
            Battery.OPTIMIZED -> "appops set $pkg RUN_ANY_IN_BACKGROUND default; appops set $pkg RUN_IN_BACKGROUND default; dumpsys deviceidle whitelist -$pkg; echo OK"
            Battery.RESTRICTED -> "dumpsys deviceidle whitelist -$pkg; appops set $pkg RUN_ANY_IN_BACKGROUND ignore; appops set $pkg RUN_IN_BACKGROUND ignore; echo OK"
        }
        val out = Adb.shell(cmd)
        return out.contains("OK") && !out.contains("Exception")
    }

    @Suppress("unused")
    fun hasPackage(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}
