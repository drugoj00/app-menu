package app.tvapps

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 19.2 (просьба владелицы 25.11.2026): автоочистка КЭША выбранных приложений: после выхода из приложения (с паузой, чтобы быстрый возврат
 * не сбрасывал кэш у работающего видео) и при включении устройства. Данные, вход в аккаунт и настройки приложения не затрагиваются
 * (команда `pm clear --cache-only`, на старых версиях стирается внешний кэш). Выход из приложения определяет служба спецвозможностей
 * заставки ([SaverAccess]); команды выполняются через root или ADB программы. По умолчанию выключено.
 */
object CacheAuto {
    private const val SP = "tvapps"
    val rev = mutableIntStateOf(0)
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)
    private val PKG = Regex("[A-Za-z0-9_.]+")

    fun on(c: Context) = sp(c).getBoolean("cache_auto_on", false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean("cache_auto_on", v).apply(); rev.intValue++ }
    fun list(c: Context): List<String> = (sp(c).getString("cache_auto_list", "") ?: "").split(',').filter { it.isNotBlank() }
    fun setList(c: Context, l: List<String>) { sp(c).edit().putString("cache_auto_list", l.joinToString(",")).apply(); rev.intValue++ }
    fun afterClose(c: Context) = sp(c).getBoolean("cache_auto_close", true)
    fun setAfterClose(c: Context, v: Boolean) { sp(c).edit().putBoolean("cache_auto_close", v).apply(); rev.intValue++ }
    fun onBootOn(c: Context) = sp(c).getBoolean("cache_auto_boot", false)
    fun setOnBoot(c: Context, v: Boolean) { sp(c).edit().putBoolean("cache_auto_boot", v).apply(); rev.intValue++ }
    fun delaySec(c: Context) = sp(c).getInt("cache_auto_delay", 15)
    fun setDelaySec(c: Context, v: Int) { sp(c).edit().putInt("cache_auto_delay", v.coerceIn(0, 600)).apply(); rev.intValue++ }
    fun logText(c: Context): String = sp(c).getString("cache_auto_log", "") ?: ""
    private fun addLog(c: Context, line: String) {
        val t = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(Date())
        val old = logText(c).split('\n').filter { it.isNotBlank() }
        sp(c).edit().putString("cache_auto_log", (old + "$t  $line").takeLast(40).joinToString("\n")).apply(); rev.intValue++
    }

    /** Служба спецвозможностей заставки включена в системе (нужна, чтобы видеть выход из приложения). */
    fun accessEnabled(c: Context): Boolean {
        val raw = runCatching { android.provider.Settings.Secure.getString(c.contentResolver, "enabled_accessibility_services") }.getOrNull().orEmpty()
        return raw.split(':').any { it.startsWith(c.packageName + "/") && it.contains("SaverAccess") }
    }

    /** Есть ли чем выполнять команды: root или подключённый ADB. */
    fun shellReady(): Boolean = Adb.isConnected

    private val pending = ConcurrentHashMap<String, Long>()
    /** Какое приложение (включая «Дом» этой программы) было на экране последним: по нему служба видит выход. */
    @Volatile var fg = ""

    /** Вызывается службой при смене приложения на экране. */
    fun onFg(ctx: Context, old: String, new: String) {
        val app = ctx.applicationContext
        if (new.isNotEmpty()) pending.remove(new)
        if (old.isEmpty() || old == new || old == app.packageName) return
        if (!on(app) || !afterClose(app) || old !in list(app)) return
        val token = System.nanoTime()
        pending[old] = token
        Thread {
            runCatching {
                Thread.sleep(delaySec(app) * 1000L)
                if (pending[old] != token) return@Thread          // приложение вернулось на экран
                pending.remove(old)
                kotlinx.coroutines.runBlocking { cleanOne(app, old, app.getString(R.string.cache_why_close)) }
            }
        }.start()
    }

    /** Загрузка устройства: один раз на одну загрузку, после паузы, пока поднимается ADB. */
    fun onBoot(ctx: Context) {
        val app = ctx.applicationContext
        if (!on(app) || !onBootOn(app) || list(app).isEmpty()) return
        val id = runCatching { android.provider.Settings.Global.getInt(app.contentResolver, "boot_count") }.getOrDefault(-1)
        if (id >= 0 && sp(app).getInt("cache_auto_last_boot", -2) == id) return
        sp(app).edit().putInt("cache_auto_last_boot", id).apply()
        Thread { kotlinx.coroutines.runBlocking { delay(25_000); runAll(app, app.getString(R.string.cache_why_boot)) } }.start()
    }

    /** Кнопка «Очистить сейчас». */
    fun runNow(ctx: Context) {
        val app = ctx.applicationContext
        Thread { kotlinx.coroutines.runBlocking { runAll(app, app.getString(R.string.cache_why_manual), 4) } }.start()
    }

    private suspend fun runAll(app: Context, why: String, maxWait: Int = 40) {
        val pkgs = list(app).filter { it != app.packageName }
        if (pkgs.isEmpty()) { addLog(app, app.getString(R.string.cache_l_empty)); return }
        runCatching { if (Cfg.useRoot && !Adb.isConnected) Adb.tryRoot() }
        var waited = 0
        while (!Adb.isConnected && waited < maxWait) { delay(2000); waited += 2 }
        for (p in pkgs) cleanOne(app, p, why)
    }

    private suspend fun cleanOne(app: Context, pkg: String, why: String) {
        if (!PKG.matches(pkg) || pkg == app.packageName) return
        if (!Adb.isConnected) { addLog(app, "$pkg: " + app.getString(R.string.cache_l_noshell)); return }
        val ok = runCatching { Actions.clearCache(pkg) }.getOrDefault(false)
        addLog(app, "$pkg: " + app.getString(if (ok) R.string.cache_l_ok else R.string.cache_l_fail) + " ($why)")
    }
}
