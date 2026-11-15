package app.tvapps

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Автозапуск приложений при включении устройства (16.11.2026, просьба владелицы): список приложений по порядку, задержка перед первым и пауза между ними.
 * Запуск идёт командой `am start` через root/ADB программы (работает на любой версии Android, в том числе 10 и новее); без них только обычный запуск окна
 * из фонового приёма BOOT_COMPLETED, который Android 10 и новее может запретить (тогда в журнале будет ошибка или «не запущено»).
 * По умолчанию выключен. Чужой BootReceiver (например tv.autostart) не затрагивается: у нас свой приёмник и свой список.
 */
object Autostart {
    private const val SP = "tvapps"
    val rev = mutableIntStateOf(0)
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun on(c: Context) = sp(c).getBoolean("auto_on", false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean("auto_on", v).apply(); rev.intValue++ }
    fun list(c: Context): List<String> = (sp(c).getString("auto_list", "") ?: "").split(',').filter { it.isNotBlank() }
    fun setList(c: Context, l: List<String>) { sp(c).edit().putString("auto_list", l.joinToString(",")).apply(); rev.intValue++ }
    fun delaySec(c: Context) = sp(c).getInt("auto_delay", 20)
    fun setDelaySec(c: Context, v: Int) { sp(c).edit().putInt("auto_delay", v.coerceIn(0, 600)).apply(); rev.intValue++ }
    fun gapSec(c: Context) = sp(c).getInt("auto_gap", 5)
    fun setGapSec(c: Context, v: Int) { sp(c).edit().putInt("auto_gap", v.coerceIn(0, 120)).apply(); rev.intValue++ }
    fun logText(c: Context): String = sp(c).getString("auto_log", "") ?: ""
    private fun addLog(c: Context, line: String) {
        val t = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(Date())
        val old = logText(c).split('\n').filter { it.isNotBlank() }
        sp(c).edit().putString("auto_log", (old + "$t  $line").takeLast(30).joinToString("\n")).apply(); rev.intValue++
    }
    private fun clearLog(c: Context) { sp(c).edit().putString("auto_log", "").apply() }

    /** Номер загрузки системы: один запуск на одну загрузку (приёмник и «Дом» не запускают список дважды). */
    private fun bootId(c: Context): Int = runCatching { Settings.Global.getInt(c.contentResolver, "boot_count") }.getOrDefault(-1)

    /** Вызывается приёмником загрузки и при старте «Дома» вскоре после включения. */
    fun onBoot(ctx: Context, reason: String) {
        val app = ctx.applicationContext
        if (!on(app) || list(app).isEmpty()) return
        val id = bootId(app)
        if (id >= 0 && sp(app).getInt("auto_last_boot", -2) == id) return
        sp(app).edit().putInt("auto_last_boot", id).apply()
        Thread { kotlinx.coroutines.runBlocking { runList(app, false, reason) } }.start()
    }

    /** Кнопка «Проверить сейчас»: запускает список сразу (без начальной задержки). */
    fun runNow(ctx: Context, scope: CoroutineScope) {
        val app = ctx.applicationContext
        scope.launch(Dispatchers.Default) { runList(app, true, "manual") }
    }

    private suspend fun runList(app: Context, manual: Boolean, reason: String) {
        val pkgs = list(app)
        if (pkgs.isEmpty()) { addLog(app, app.getString(R.string.auto_l_empty)); return }
        clearLog(app)
        addLog(app, app.getString(R.string.auto_l_start, reason, pkgs.size))
        if (!manual) delay(delaySec(app) * 1000L)
        // root или ADB: нужны для запуска из фона на Android 10 и новее; пробуем root, если он разрешён в настройках
        runCatching { if (Cfg.useRoot && !Adb.isConnected) Adb.tryRoot() }
        val viaShell = Adb.isConnected
        for ((i, pkg) in pkgs.withIndex()) {
            if (i > 0) delay(gapSec(app) * 1000L)
            val pm = app.packageManager
            val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            val comp = intent?.component
            if (intent == null || comp == null) { addLog(app, "$pkg: " + app.getString(R.string.auto_l_nolaunch)); continue }
            // уже на экране: не запускать повторно
            if (viaShell) {
                val focus = runCatching { Adb.shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'") }.getOrDefault("")
                if (focus.contains("$pkg/")) { addLog(app, "$pkg: " + app.getString(R.string.auto_l_skip)); continue }
            }
            var ok = false; var how = ""
            if (viaShell) {
                val r = runCatching { Adb.shell("am start -n '" + comp.flattenToString() + "'") }.getOrDefault("")
                ok = !r.contains("Error", true) && !r.contains("Exception", true)
                how = "root/ADB"
                if (!ok) addLog(app, "$pkg: am start: " + r.trim().take(80))
            }
            if (!ok) {
                ok = runCatching { app.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrElse { addLog(app, "$pkg: " + app.getString(R.string.auto_l_err) + ": " + (it.message ?: "").take(60)); false }
                how = app.getString(R.string.auto_l_plain)
            }
            if (ok) addLog(app, "$pkg: " + app.getString(R.string.auto_l_ok) + " ($how)")
        }
        addLog(app, app.getString(R.string.auto_l_done))
    }
}
