package app.tvapps

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * Заставка «Часы и погода»: общая часть (настройки, журнал, датчики бездействия, самовосстановление, сторож).
 * Всё на чистом Kotlin (без своего нативного кода): работает на любых архитектурах.
 */

// ------------------------------------------------------------------ настройки

data class SaverCfg(
    val enabled: Boolean = false,
    /** минут без нажатий до заставки */
    val idleMin: Int = 5,
    /** способ показа: 0 авто (поверх окон, при неудаче отдельное окно), 1 поверх окон, 2 отдельное окно (Activity) */
    val way: Int = 0,
    /** 0: не показывать поверх выбранных приложений; 1: показывать только в выбранных */
    val mode: Int = 0,
    val apps: Set<String> = SaverPrefs.DEFAULT_APPS,
    val onlyCharging: Boolean = false,
    /** 0: не показывать, пока играет звук (видео, музыка); 1: только когда играет музыка; 2: не учитывать звук */
    val sound: Int = 0,
    val schedOn: Boolean = false, val from: Int = 22 * 60, val to: Int = 7 * 60, val days: Int = 127,
    /** затемнение ночью, % */
    val dim: Int = 50,
    val burn: Boolean = true, val clockOnly: Boolean = false,
    /** размер на весь экран, % */
    val size: Int = 100,
    /** цвет фона ARGB; 0: чёрный */
    val bg: Long = 0L,
)

object SaverPrefs {
    /** Видеоплееры, онлайн-кинотеатры и навигаторы: поверх них заставка по умолчанию не показывается. */
    val DEFAULT_APPS: Set<String> = setOf(
        "com.google.android.youtube", "com.google.android.youtube.tv", "com.google.android.apps.youtube.music", "org.videolan.vlc", "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro",
        "com.netflix.mediaclient", "com.netflix.ninja", "tv.twitch.android.app", "ru.kinopoisk", "ru.ivi.client", "com.megogo.application", "tv.okko.androidtv", "ru.more.play", "ru.rutube.app",
        "com.google.android.apps.maps", "ru.yandex.yandexnavi", "ru.yandex.yandexmaps", "com.waze", "com.sygic.aura", "ru.dublgis.dgismobile", "com.disney.disneyplus", "com.amazon.avod.thirdpartyclient",
        "org.xbmc.kodi", "com.ottplay.ottplay", "ru.yandex.music", "com.spotify.music",
    )
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("tvapps_saver", Context.MODE_PRIVATE)

    fun get(c: Context): SaverCfg {
        val p = sp(c)
        val d = SaverCfg()
        val apps = p.getString("apps", null)?.let { j -> runCatching { JSONArray(j).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrNull() } ?: d.apps
        return SaverCfg(
            p.getBoolean("on", d.enabled), p.getInt("idle", d.idleMin), p.getInt("way", d.way), p.getInt("mode", d.mode), apps, p.getBoolean("chg", d.onlyCharging), p.getInt("snd", d.sound),
            p.getBoolean("sch", d.schedOn), p.getInt("from", d.from), p.getInt("to", d.to), p.getInt("days", d.days), p.getInt("dim", d.dim),
            p.getBoolean("burn", d.burn), p.getBoolean("clk", d.clockOnly), p.getInt("size", d.size), p.getLong("bg", d.bg),
        )
    }

    fun set(c: Context, f: (SaverCfg) -> SaverCfg) {
        val n = f(get(c))
        sp(c).edit().putBoolean("on", n.enabled).putInt("idle", n.idleMin).putInt("way", n.way).putInt("mode", n.mode).putString("apps", JSONArray(n.apps.toList()).toString())
            .putBoolean("chg", n.onlyCharging).putInt("snd", n.sound).putBoolean("sch", n.schedOn).putInt("from", n.from).putInt("to", n.to).putInt("days", n.days)
            .putInt("dim", n.dim).putBoolean("burn", n.burn).putBoolean("clk", n.clockOnly).putInt("size", n.size).putLong("bg", n.bg).apply()
    }

    /** Готовые наборы правил: 0 магнитола, 1 телевизор, 2 телефон. */
    fun preset(id: Int): (SaverCfg) -> SaverCfg = when (id) {
        0 -> { c -> c.copy(idleMin = 2, onlyCharging = false, sound = 0, dim = 60, burn = true, clockOnly = true, size = 110, mode = 0, way = 0) }
        1 -> { c -> c.copy(idleMin = 5, onlyCharging = false, sound = 0, dim = 40, burn = true, clockOnly = false, size = 100, mode = 0, way = 0) }
        else -> { c -> c.copy(idleMin = 2, onlyCharging = true, sound = 0, dim = 50, burn = true, clockOnly = false, size = 100, mode = 0, way = 0) }
    }
}

// ------------------------------------------------------------------ журнал

/** Журнал заставки: когда показана и скрыта, почему не показана, что с датчиками. Файл files/saver.log (до ~64 КБ), кнопка «Копировать журнал» в диагностике. */
object SaverLog {
    private val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)
    @Volatile private var file: File? = null
    @Volatile private var lastWhy = ""

    fun init(ctx: Context) { if (file == null) file = File(ctx.applicationContext.filesDir, "saver.log") }

    @Synchronized fun log(msg: String) {
        Log.i("TVSaver", msg)
        val f = file ?: return
        runCatching {
            f.appendText(fmt.format(Date()) + " " + msg + "\n")
            if (f.length() > 64_000) f.writeText(f.readLines().takeLast(250).joinToString("\n") + "\n")
        }
    }

    /** Причина «не показана» пишется, только когда она сменилась (иначе журнал забьётся повторами). */
    fun why(reason: String) { if (reason != lastWhy) { lastWhy = reason; log("не показана: $reason") } }
    fun clearWhy() { lastWhy = "" }

    @Synchronized fun text(max: Int = 200): String = runCatching { file?.readLines()?.takeLast(max)?.joinToString("\n") }.getOrNull().orEmpty()
}

// ------------------------------------------------------------------ датчики бездействия

/** Один датчик: состояние (alive), время последнего сигнала, число сигналов, число самовосстановлений. */
class SaverSensor(val id: String) {
    @Volatile var last = 0L
    @Volatile var count = 0
    @Volatile var restored = 0
    @Volatile var alive = false
    @Volatile var note = ""
}

/** Шина: любой свежий сигнал любого датчика сбрасывает таймер бездействия. */
object SaverBus {
    val sensors: Map<String, SaverSensor> = listOf("overlay", "irq", "power", "root", "usage", "access").associateWith { SaverSensor(it) }
    @Volatile var lastActive = SystemClock.elapsedRealtime()
    @Volatile var fgPkg = ""
    @Volatile var shown = false
    @Volatile var shownBy = ""
    @Volatile var reason = ""
    @Volatile var exitHook: (() -> Unit)? = null

    fun sig(id: String, at: Long = SystemClock.elapsedRealtime()) {
        val s = sensors[id] ?: return
        if (at > s.last) s.last = at
        s.count++
        s.alive = true
        if (at > lastActive) lastActive = at
    }
    /** Действие самой заставки (закрытие): отсчёт простоя начинается заново, но датчики не помечаются. */
    fun reset() { lastActive = SystemClock.elapsedRealtime() }
    fun hide() { exitHook?.invoke() }
    fun anyAlive(): Boolean = sensors.values.any { it.alive }
}

// ------------------------------------------------------------------ разрешения и права

object SaverPerm {
    fun overlay(c: Context) = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c)

    fun usage(c: Context): Boolean = runCatching {
        val ao = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val m = if (Build.VERSION.SDK_INT >= 29) ao.unsafeCheckOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), c.packageName)
        else ao.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), c.packageName)
        m == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun battery(c: Context): Boolean = runCatching { (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName) }.getOrDefault(false)

    fun notif(c: Context): Boolean = Build.VERSION.SDK_INT < 33 || c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED

    fun secure(c: Context) = AccessSync.hasSecurePermission(c)

    fun accessComp(c: Context): String = ComponentName(c, SaverAccess::class.java).flattenToString()
    fun accessListed(c: Context): Boolean = accessComp(c) in AccessSync.enabledNow(c)

    /** Видит ли система нашу службу спецвозможностей в списке установленных и подключена ли она (по данным AccessibilityManager). */
    fun accessSystemView(c: Context): String = runCatching {
        val am = c.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val me = accessComp(c)
        val inst = am.installedAccessibilityServiceList.any { ComponentName.unflattenFromString(it.id)?.flattenToString() == me }
        val run = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { ComponentName.unflattenFromString(it.id)?.flattenToString() == me }
        "installed=$inst bound=$run"
    }.getOrDefault("?")

    /** Есть ли управление через ADB или root (тогда права выдаются без участия человека). */
    fun control(): Boolean = Adb.isConnected

    @Volatile private var ctlTry = 0L

    /** Пробует подключить управление (root или ADB по сети) не чаще раза в 10 минут; [force] — немедленно. Вызывать не из главного потока. */
    fun ensureControl(c: Context, force: Boolean = false) {
        if (Adb.isConnected) return
        val now = SystemClock.elapsedRealtime()
        if (!force && ctlTry != 0L && now - ctlTry < 10 * 60_000L) return
        ctlTry = now
        runCatching { runBlocking { kotlinx.coroutines.withTimeoutOrNull(25_000) { autoConnect(c.applicationContext) } } }
        SaverLog.log("управление ADB/root: " + if (Adb.isConnected) (if (Adb.isRoot) "root" else "ADB") else "нет")
    }

    private fun sh(cmd: String): String = runCatching { runBlocking { Adb.shell(cmd, 12_000) } }.getOrDefault("")

    /** Выдаёт через ADB или root то, что нельзя выдать из самой программы. Возвращает true, если команда выполнялась. */
    fun grant(c: Context, what: String): Boolean {
        ensureControl(c, true)
        if (!control()) return false
        val p = c.packageName
        when (what) {
            "overlay" -> sh("appops set $p SYSTEM_ALERT_WINDOW allow")
            "usage" -> sh("appops set $p GET_USAGE_STATS allow")
            "battery" -> sh("dumpsys deviceidle whitelist +$p")
            "notif" -> sh("pm grant $p android.permission.POST_NOTIFICATIONS")
            "secure" -> sh("pm grant $p android.permission.WRITE_SECURE_SETTINGS")
        }
        SaverLog.log("выдано через ADB/root: $what")
        return true
    }
}

// ------------------------------------------------------------------ самовосстановление, сторож

object SaverGuard {
    const val ACT_WATCH = "app.tvapps.SAVER_WATCH"
    private const val JOB_ID = 5311
    @Volatile var accessCheckedAt = 0L
    private var badSince = 0L
    private var nextKick = 0L

    /**
     * Следит, чтобы служба спецвозможностей была включена и подключена: прошивка магнитолы может выключить её после сна или перезагрузки.
     * Нужна выданная защищённая настройка (WRITE_SECURE_SETTINGS) или ADB/root; без них только записывает причину в журнал.
     */
    fun ensureAccess(ctx: Context, why: String) {
        val c = ctx.applicationContext
        if (!SaverPrefs.get(c).enabled) return
        accessCheckedAt = System.currentTimeMillis()
        val s = SaverBus.sensors.getValue("access")
        val listed = SaverPerm.accessListed(c)
        val comp = SaverPerm.accessComp(c)
        if (!listed) {
            if (!SaverPerm.secure(c) && SaverPerm.control()) SaverPerm.grant(c, "secure")
            val ok = writeAccess(c, AccessSync.enabledNow(c) + comp)
            if (ok) { s.restored++; SaverLog.log("служба спецвозможностей пропала и восстановлена ($why)") }
            else SaverLog.why("служба спецвозможностей выключена, включить самому нельзя: нужен ADB/root или выданное право ($why)")
            return
        }
        // в списке есть, но не подключена дольше минуты: выключаем и включаем заново
        if (!s.alive) {
            SaverLog.why("служба спецвозможностей в списке, но не подключена (" + SaverPerm.accessSystemView(c) + ")")
            val nowE = SystemClock.elapsedRealtime()
            if (badSince == 0L) badSince = nowE
            // система помечает службу «упавшей», если процесс убили: тогда она не подключится до перезагрузки или переустановки; пробуем переключить не чаще раза в 10 минут
            if (nowE - badSince > 60_000L && nowE >= nextKick && (SaverPerm.secure(c) || SaverPerm.control())) {
                nextKick = nowE + 10 * 60_000L
                val without = AccessSync.enabledNow(c) - comp
                if (writeAccess(c, without)) {
                    Thread.sleep(700)
                    if (writeAccess(c, without + comp)) { s.restored++; SaverLog.log("служба спецвозможностей была в списке, но не работала: переключена заново ($why)") }
                }
                badSince = SystemClock.elapsedRealtime()
            }
        } else badSince = 0L
    }

    private fun writeAccess(c: Context, list: Set<String>): Boolean {
        val joined = list.joinToString(":")
        if (SaverPerm.secure(c)) {
            return runCatching {
                Settings.Secure.putString(c.contentResolver, "enabled_accessibility_services", joined)
                Settings.Secure.putInt(c.contentResolver, "accessibility_enabled", 1)
                true
            }.getOrDefault(false)
        }
        if (SaverPerm.control()) {
            runCatching { runBlocking { Adb.shell("settings put secure enabled_accessibility_services '$joined'; settings put secure accessibility_enabled 1", 12_000) } }
            return true
        }
        return false
    }

    /** Следующий пробуждающий будильник и периодическое задание: переживут сон устройства и гибель процесса. */
    fun arm(ctx: Context) {
        val c = ctx.applicationContext
        runCatching {
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(c, 7, Intent(c, SaverReceiver::class.java).setAction(ACT_WATCH), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val at = System.currentTimeMillis() + 5 * 60_000L
            try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
            catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }.onFailure { SaverLog.log("будильник сторожа не поставлен: ${it.message}") }
        runCatching {
            val js = c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (js.allPendingJobs.none { it.id == JOB_ID }) {
                js.schedule(JobInfo.Builder(JOB_ID, ComponentName(c, SaverJob::class.java)).setPeriodic(15 * 60_000L).setPersisted(true).build())
            }
        }
    }

    fun disarm(ctx: Context) {
        val c = ctx.applicationContext
        runCatching {
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(PendingIntent.getBroadcast(c, 7, Intent(c, SaverReceiver::class.java).setAction(ACT_WATCH), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            (c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(JOB_ID)
        }
    }

    fun alarmArmed(c: Context): Boolean = runCatching {
        PendingIntent.getBroadcast(c, 7, Intent(c, SaverReceiver::class.java).setAction(ACT_WATCH), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) != null
    }.getOrDefault(false)

    fun jobArmed(c: Context): Boolean = runCatching { (c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).allPendingJobs.any { it.id == JOB_ID } }.getOrDefault(false)

    /** Запускает службу, если заставка включена; на новых Android запуск из фона может быть запрещён: причина попадает в журнал. */
    fun startIfEnabled(ctx: Context, why: String) {
        val c = ctx.applicationContext
        SaverLog.init(c)
        if (!SaverPrefs.get(c).enabled) return
        arm(c)
        Thread { ensureAccess(c, why) }.start()
        runCatching { SaverService.start(c, null, why) }.onFailure { SaverLog.log("служба не запущена ($why): ${it.javaClass.simpleName} ${it.message}") }
    }

    /** Ночь для затемнения: по расписанию, если оно включено, иначе с 22:00 до 07:00. */
    fun isNight(c: SaverCfg): Boolean {
        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val a = if (c.schedOn) c.from else 22 * 60
        val b = if (c.schedOn) c.to else 7 * 60
        return if (a <= b) now in a until b else (now >= a || now < b)
    }
}
