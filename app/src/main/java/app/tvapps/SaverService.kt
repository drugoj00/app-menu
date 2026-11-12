package app.tvapps

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobParameters
import android.app.job.JobService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.runBlocking
import java.util.Calendar

/** Служба спецвозможностей: один из датчиков бездействия (не единственный: прошивки магнитол её нередко выключают). Содержимое экранов не читает. */
class SaverAccess : AccessibilityService() {
    private val keysDown = HashSet<Int>()

    override fun onServiceConnected() {
        SaverLog.init(applicationContext)
        val s = SaverBus.sensors.getValue("access")
        s.alive = true
        SaverBus.sig("access")
        SaverLog.log("служба спецвозможностей подключена")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        SaverBus.sensors.getValue("access").alive = false
        SaverLog.log("служба спецвозможностей отключена системой")
        // пробуем вернуть через несколько секунд, если процесс жив
        Handler(Looper.getMainLooper()).postDelayed({ Thread { SaverGuard.ensureAccess(applicationContext, "служба отключилась") }.start() }, 4000)
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        val p = e.packageName?.toString().orEmpty()
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (p.isNotEmpty() && p != packageName && p != "com.android.systemui" && p != "android") SaverBus.fgPkg = p
        }
        // события самого окна заставки не считаются действием человека
        if (!(SaverBus.shown && p == packageName)) SaverBus.sig("access")
    }

    /** Клавиша пульта: действие; если заставка показана, первое нажатие закрывает её и в приложение под ней не проходит. */
    override fun onKeyEvent(ev: KeyEvent): Boolean {
        if (ev.action == KeyEvent.ACTION_DOWN) {
            if (SaverBus.shown) { keysDown += ev.keyCode; Handler(Looper.getMainLooper()).post { SaverBus.hide() }; return true }
            SaverBus.sig("access")
        } else if (ev.action == KeyEvent.ACTION_UP && keysDown.remove(ev.keyCode)) return true
        return false
    }
}

/** Приёмник событий для сторожа: загрузка (в том числе быстрая у магнитол), разблокировка, обновление программы, будильник сторожа. */
class SaverReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        SaverLog.init(app)
        if (!SaverPrefs.get(app).enabled) return
        SaverLog.log("событие: ${intent.action?.substringAfterLast('.')}")
        val pending = goAsync()
        Thread {
            try { SaverGuard.startIfEnabled(app, intent.action?.substringAfterLast('.').orEmpty()) } finally { pending.finish() }
        }.start()
    }
}

/** Периодическое задание (раз в 15 минут, переживает перезагрузку): поднимает службу, если систему убили. */
class SaverJob : JobService() {
    override fun onStartJob(p: JobParameters?): Boolean {
        SaverGuard.startIfEnabled(applicationContext, "задание")
        return false
    }
    override fun onStopJob(p: JobParameters?): Boolean = true
}

/**
 * Служба заставки по бездействию. Показывает часы и погоду на весь экран после N минут без действий поверх любого приложения.
 * Два способа показа: окно поверх приложений и отдельное окно (Activity); выбирается рабочий. Датчики бездействия (несколько, независимые):
 * невидимое окно 1×1 (касания вне него), счётчики прерываний, root/ADB (getevent, dumpsys power), статистика использования, служба спецвозможностей.
 */
class SaverService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var frame: SaverFrame? = null
    private var host: ComposeHost? = null
    private var probe: View? = null
    private var worker: HandlerThread? = null
    private var wh: Handler? = null
    private var rootProc: Process? = null
    @Volatile private var rootProcs: List<Process> = emptyList()
    private val rootBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private var screenRx: BroadcastReceiver? = null
    private var windowTried = 0L
    private var manual = false

    private val irqPrev = HashMap<String, Long>()
    private val irqRun = HashMap<String, Int>()
    private var irqDirect = true
    private var usageQ = 0L
    private var loopN = 0

    private val tick = object : Runnable {
        override fun run() {
            runCatching { check() }.onFailure { SaverLog.log("ошибка проверки: ${it.javaClass.simpleName} ${it.message}") }
            h.postDelayed(this, 3_000)
        }
    }
    private val loop = object : Runnable {
        override fun run() {
            runCatching { poll() }.onFailure { SaverLog.log("ошибка датчиков: ${it.javaClass.simpleName} ${it.message}") }
            wh?.postDelayed(this, 2_000)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = applicationContext
        SaverLog.init(app)
        Cfg.init(app)
        HomeStore.init(app)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        running = true
        SaverBus.reset()
        worker = HandlerThread("saver-sensors").also { it.start(); wh = Handler(it.looper) }
        registerScreen()
        SaverLog.log("служба запущена")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        when (intent?.action) {
            ACTION_SHOW -> { manual = true; val w = intent.getIntExtra(EXTRA_WAY, -1); h.post { showNow(SaverPrefs.get(this), "по просьбе", w) } }
            ACTION_STOP -> { SaverPrefs.set(this) { it.copy(enabled = false) }; SaverGuard.disarm(this); stopSelf(); return START_NOT_STICKY }
            ACTION_RESTART -> restartSensors("по просьбе")
        }
        SaverGuard.arm(this)
        attachProbe()
        startRootWatch()
        h.removeCallbacks(tick); h.postDelayed(tick, 2_000)
        wh?.removeCallbacks(loop); wh?.post(loop)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacks(tick)
        wh?.removeCallbacks(loop)
        worker?.quitSafely(); worker = null
        hideOverlay(silent = true)
        detachProbe()
        rootProcs.forEach { runCatching { it.destroy() } }; rootProcs = emptyList(); rootProc?.destroy(); rootProc = null
        screenRx?.let { runCatching { unregisterReceiver(it) } }
        SaverBus.exitHook = null
        for (s in SaverBus.sensors.values) s.alive = false
        SaverLog.log("служба остановлена")
        // если заставка ещё включена, а процесс убили: будильник сторожа поднимет службу снова
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (SaverPrefs.get(this).enabled) SaverGuard.arm(this)
    }

    // ------------------------------------------------------------------ служба переднего плана

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.sv_channel), NotificationManager.IMPORTANCE_MIN))
        fun pi(a: String, code: Int) = PendingIntent.getService(this, code, Intent(this, SaverService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_saver).setContentTitle(getString(R.string.sv_notif_title)).setContentText(getString(R.string.sv_notif_text))
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(0, getString(R.string.sv_show_now), pi(ACTION_SHOW, 1))
            .addAction(0, getString(R.string.sv_turn_off), pi(ACTION_STOP, 2)).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIF_ID, n)
        }.onFailure { SaverLog.log("служба переднего плана не стартовала: ${it.javaClass.simpleName} ${it.message}") }
    }

    private fun registerScreen() {
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> { SaverBus.reset(); restartSensors(i.action!!.substringAfterLast('.')) }
                    Intent.ACTION_SCREEN_OFF -> { SaverLog.log("экран выключен"); if (SaverBus.shown) SaverBus.hide() }
                    Intent.ACTION_TIME_TICK -> { SaverGuard.arm(c); Thread { SaverGuard.ensureAccess(c, "минутный тик") }.start() }
                }
            }
        }
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_TIME_TICK) }
        ContextCompat.registerReceiver(this, rx, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenRx = rx
    }

    /** После пробуждения заново поднимаем окно-датчик, getevent, проверяем службу спецвозможностей. */
    private fun restartSensors(why: String) {
        SaverLog.log("пробуждение/перезапуск датчиков ($why)")
        detachProbe(); attachProbe()
        if (rootProc == null) startRootWatch()
        irqPrev.clear(); irqRun.clear()
        Thread { SaverGuard.ensureAccess(applicationContext, why) }.start()
    }

    // ------------------------------------------------------------------ датчики

    /** Невидимое окно 1×1: любое касание за его пределами приходит как ACTION_OUTSIDE (службе спецвозможностей это не нужно). На Android 12+ не гарантируется. */
    private fun attachProbe() {
        if (probe != null) return
        val s = SaverBus.sensors.getValue("overlay")
        if (!SaverPerm.overlay(this)) { s.alive = false; s.note = "no_perm"; return }
        val v = View(this)
        v.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) SaverBus.sig("overlay"); false }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(1, 1, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        runCatching { wm?.addView(v, lp); probe = v; s.alive = true; s.note = "" }.onFailure { s.alive = false; s.note = it.javaClass.simpleName; SaverLog.log("датчик-окно 1×1 не добавлено: ${it.message}") }
    }

    private fun detachProbe() {
        val v = probe ?: return
        probe = null
        runCatching { wm?.removeView(v) }
        SaverBus.sensors.getValue("overlay").alive = false
    }

    /**
     * Root: читаются сами файлы устройств ввода (/dev/input/eventN): любые байты считаются действием.
     * Утилита getevent при выводе в канал буферизует строки и опаздывает, поэтому читаем устройства напрямую; датчики положения, света и т. п. пропускаются.
     */
    private fun startRootWatch() {
        val s = SaverBus.sensors.getValue("root")
        if (rootProc != null) return
        if (!Adb.isRoot) { s.alive = false; s.note = "no_root"; return }
        if (!rootBusy.compareAndSet(false, true)) return
        Thread {
            runCatching {
                val list = ProcessBuilder("su", "-c", "for d in /sys/class/input/event*; do echo \"$(basename \$d)|\$(cat \$d/device/name)\"; done").redirectErrorStream(true).start()
                    .inputStream.bufferedReader().readLines()
                val skip = Regex("sensor|accel|gyro|compass|magnet|proxim|lis3|bma|mma|jack|headset|lid|switch", RegexOption.IGNORE_CASE)
                val devs = list.mapNotNull { l -> l.split("|", limit = 2).takeIf { it.size == 2 && it[0].startsWith("event") } }.filter { !skip.containsMatchIn(it[1]) }.map { it[0] }
                if (devs.isEmpty()) error("нет устройств ввода")
                val procs = ArrayList<Process>()
                for (d in devs) {
                    val p = ProcessBuilder("su", "-c", "cat /dev/input/$d").redirectErrorStream(true).start()
                    procs += p
                    Thread {
                        runCatching {
                            val buf = ByteArray(512)
                            while (true) { val n = p.inputStream.read(buf); if (n < 0) break; SaverBus.sig("root") }
                        }
                    }.start()
                }
                rootProc = procs.first()
                rootProcs = procs
                s.alive = true; s.note = ""
                SaverLog.log("root-датчик: устройства ввода ${devs.joinToString(",")}")
                procs.first().waitFor()
            }.onFailure { SaverLog.log("root-датчик не запущен: ${it.message}") }
            rootProcs.forEach { runCatching { it.destroy() } }; rootProcs = emptyList()
            s.alive = false; s.note = "ended"; rootProc = null; rootBusy.set(false)
        }.start()
    }

    private val irqRe = Regex("touch|ts_|_ts|tp_|goodix|gt9|ft5|focal|synap|atmel|elan|cyttsp|mxt|himax|novatek|nvt|keypad|gpio-keys|gpio_keys|button|rmi4|remote|mouse|hid", RegexOption.IGNORE_CASE)

    /** Счётчики прерываний: растёт строка тач-контроллера или кнопок — было касание/нажатие. Шумящие строки (меняются постоянно) отбрасываются. */
    private fun scanIrq(text: String) {
        val s = SaverBus.sensors.getValue("irq")
        val rx = Regex("^\\s*(\\w+):\\s+((?:\\d+\\s+)+)(.*)$")
        var matched = 0
        for (line in text.lineSequence()) {
            val m = rx.find(line) ?: continue
            val name = m.groupValues[3].trim()
            if (!irqRe.containsMatchIn(name)) continue
            matched++
            val key = m.groupValues[1] + name
            val sum = m.groupValues[2].trim().split(Regex("\\s+")).sumOf { it.toLongOrNull() ?: 0L }
            val prev = irqPrev.put(key, sum)
            if (prev != null && sum != prev) {
                val run = (irqRun[key] ?: 0) + 1
                irqRun[key] = run
                if (run < 8) SaverBus.sig("irq")      // 8 опросов подряд с ростом: строка шумит, не считаем
            } else irqRun[key] = 0
        }
        s.alive = matched > 0
        s.note = if (matched > 0) "" else "no_lines"
    }

    private fun scanUsage() {
        val s = SaverBus.sensors.getValue("usage")
        if (!SaverPerm.usage(this)) { s.alive = false; s.note = "no_perm"; return }
        val um = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        if (usageQ == 0L) usageQ = now - 5000
        val ev = um.queryEvents(usageQ, now)
        usageQ = now
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            when (e.eventType) {
                1 -> { val p = e.packageName.orEmpty(); if (p != packageName && p != "com.android.systemui") SaverBus.fgPkg = p; SaverBus.sig("usage") }
                7, 15, 18 -> SaverBus.sig("usage")
            }
        }
        s.alive = true; s.note = ""
    }

    private fun scanElevated() {
        if (!SaverPerm.control()) return
        val needFg = !SaverBus.sensors.getValue("access").alive && !SaverBus.sensors.getValue("usage").alive
        val cmd = (if (!irqDirect) "cat /proc/interrupts; " else "") + "dumpsys power | grep mLastUserActivityTime=" +
            (if (needFg) "; dumpsys activity activities | grep -E 'mResumedActivity|mFocusedActivity' | head -2" else "")
        val out = runCatching { runBlocking { Adb.shell(cmd, 8_000) } }.getOrDefault("")
        if (!irqDirect && out.isNotBlank()) scanIrq(out)
        val s = SaverBus.sensors.getValue("power")
        val m = Regex("mLastUserActivityTime=\\d+ \\((\\d+) ms ago\\)").find(out)
        if (m != null) {
            val ago = m.groupValues[1].toLong()
            s.alive = true; s.note = ""
            if (ago < 12_000) SaverBus.sig("power", SystemClock.elapsedRealtime() - ago)
        } else { s.alive = false; s.note = "no_data" }
        if (needFg) Regex("\\s([a-zA-Z0-9_.]+)/[\\w.$]+").find(out.substringAfter("Activity", ""))?.let { val p = it.groupValues[1]; if (p != packageName && p != "com.android.systemui") SaverBus.fgPkg = p }
    }

    private fun poll() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return
        loopN++
        if (irqDirect) {
            val t = runCatching { java.io.File("/proc/interrupts").readText() }.getOrNull()
            if (t.isNullOrBlank()) { irqDirect = false; SaverBus.sensors.getValue("irq").note = "unreadable" } else scanIrq(t)
        }
        if (loopN % 2 == 0) runCatching { scanUsage() }
        if (loopN % 15 == 1) SaverPerm.ensureControl(applicationContext)
        if (loopN % 15 == 5) SaverGuard.ensureAccess(applicationContext, "таймер 30 с")
        if (rootProc == null && Adb.isRoot && loopN % 3 == 2) startRootWatch()
        if (loopN % 3 == 0) scanElevated()
        if (loopN % 8 == 0 && probe == null) h.post { attachProbe() }
    }

    // ------------------------------------------------------------------ правила и показ

    private fun charging(): Boolean {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return true
        if (!i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) return true
        return i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    /** null — можно показывать; иначе причина (с «~» в начале: обычное ожидание, в журнал не пишется). */
    private fun blocker(c: SaverCfg): String? {
        val now = SystemClock.elapsedRealtime()
        val idle = now - SaverBus.lastActive
        if (idle < c.idleMin * 60_000L) return "~" + getString(R.string.sv_r_idle, c.idleMin, (idle / 1000).toInt())
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return "~" + getString(R.string.sv_r_screen_off)
        if (!SaverBus.anyAlive() && !c.schedOn) return getString(R.string.sv_r_no_sensors)
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.mode != AudioManager.MODE_NORMAL) return getString(R.string.sv_r_call)
        val music = am.isMusicActive
        if (c.sound == 0 && music) return getString(R.string.sv_r_sound)
        if (c.sound == 1 && !music) return getString(R.string.sv_r_no_music)
        if (c.onlyCharging && !charging()) return getString(R.string.sv_r_no_charge)
        if (c.schedOn) {
            val cal = Calendar.getInstance()
            val dayBit = 1 shl ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7)
            if ((c.days and dayBit) == 0) return getString(R.string.sv_r_day)
            val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val inside = if (c.from <= c.to) m in c.from until c.to else (m >= c.from || m < c.to)
            if (!inside) return getString(R.string.sv_r_hours)
        }
        val pkg = SaverBus.fgPkg
        if (c.mode == 0 && pkg.isNotEmpty() && pkg in c.apps) return getString(R.string.sv_r_app_excl, pkg)
        if (c.mode == 1 && pkg.isEmpty()) return getString(R.string.sv_r_app_unknown)
        if (c.mode == 1 && pkg !in c.apps) return getString(R.string.sv_r_app_not_listed, pkg)
        return null
    }

    private fun check() {
        val c = SaverPrefs.get(this)
        if (!c.enabled) { if (!(manual && SaverBus.shown)) stopSelf(); return }
        if (SaverBus.shown) return
        val b = blocker(c)
        SaverBus.reason = b?.removePrefix("~").orEmpty()
        if (b != null) { if (!b.startsWith("~")) SaverLog.why(b); return }
        SaverLog.clearWhy()
        showNow(c, "бездействие", -1)
    }

    /** Способы показа по очереди: оверлей и отдельное окно; [only] = 1 или 2 — только этот способ (для проверки), -1 — по настройке с запасным. */
    private fun showNow(c: SaverCfg, why: String, only: Int) {
        if (SaverBus.shown) return
        val w = if (only >= 0) only else c.way
        SaverLog.log("показываю заставку ($why), способ: ${arrayOf("авто", "поверх окон", "отдельное окно")[w.coerceIn(0, 2)]}")
        val order = when (w) { 1 -> listOf("overlay"); 2 -> listOf("window"); else -> listOf("overlay", "window") }
        val fallback = only < 0 && w != 0
        var ok = false
        for (way in order) { if (tryWay(way, c)) { ok = true; break } }
        if (!ok && fallback) {
            for (way in listOf("overlay", "window").filter { it !in order }) { if (tryWay(way, c)) { ok = true; break } }
        }
        if (!ok) SaverLog.log("заставку показать не удалось")
    }

    private fun tryWay(way: String, c: SaverCfg): Boolean = if (way == "overlay") showOverlay(c) else showWindow(c)

    private fun showWindow(c: SaverCfg): Boolean {
        val ok = runCatching {
            startActivity(Intent(this, SaverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        }.onFailure { SaverLog.log("отдельное окно не запущено: ${it.javaClass.simpleName} ${it.message}") }.isSuccess
        if (ok) {
            windowTried = SystemClock.elapsedRealtime()
            h.postDelayed({
                if (!SaverBus.shown && SystemClock.elapsedRealtime() - windowTried >= 2800) {
                    SaverLog.log("отдельное окно не появилось за 3 с (прошивка заблокировала запуск из фона?)")
                    if (SaverPrefs.get(this).way == 2 || SaverPrefs.get(this).way == 0) showOverlay(c)
                }
            }, 3000)
        }
        return ok
    }

    private fun showOverlay(c: SaverCfg): Boolean {
        if (SaverBus.shown || frame != null) return true
        if (!SaverPerm.overlay(this)) { SaverLog.log("оверлей недоступен: нет разрешения «Поверх других окон»"); return false }
        val hst = ComposeHost().also { host = it }
        val f = SaverFrame(this) { SaverBus.hide() }
        hst.attach(f)
        f.addView(ComposeView(this).apply { setContent { SaverScreen(applicationContext, c) } })
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        )
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        @Suppress("DEPRECATION")
        f.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val ok = runCatching { wm?.addView(f, lp) }.onFailure { SaverLog.log("оверлей не добавлен: ${it.javaClass.simpleName} ${it.message}") }.isSuccess
        if (!ok) { hst.destroy(); host = null; return false }
        frame = f
        hst.start()
        SaverBus.shown = true; SaverBus.shownBy = "overlay"
        SaverBus.exitHook = { hideOverlay(false) }
        SaverLog.log("заставка показана: поверх окон")
        f.requestFocus()
        return true
    }

    private fun hideOverlay(silent: Boolean) {
        val f = frame ?: return
        frame = null
        runCatching { wm?.removeView(f) }
        host?.destroy(); host = null
        if (SaverBus.shownBy == "overlay") { SaverBus.shown = false; SaverBus.exitHook = null }
        SaverBus.reset()
        if (!silent) SaverLog.log("заставка скрыта (оверлей)")
    }

    companion object {
        const val ACTION_SHOW = "app.tvapps.SAVER_SHOW"
        const val ACTION_STOP = "app.tvapps.SAVER_STOP"
        const val ACTION_RESTART = "app.tvapps.SAVER_RESTART"
        const val EXTRA_WAY = "way"
        private const val CHANNEL = "tvapps_saver"
        private const val NOTIF_ID = 5310
        @Volatile var running = false

        fun start(ctx: Context, action: String? = null, why: String = "", way: Int = -1) {
            val i = Intent(ctx, SaverService::class.java).apply { if (action != null) setAction(action); if (way >= 0) putExtra(EXTRA_WAY, way) }
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, SaverService::class.java)) }
    }
}
