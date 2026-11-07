package app.tvapps

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Очистка памяти: останавливает пользовательские приложения, работающие в фоне.
 * Не трогает: саму программу, главные экраны, поставщиков виджетов «Дома», клавиатуры, службы спецвозможностей и уведомлений, голосовых помощников, VPN, администраторов устройства,
 * приложения с играющим звуком или видео, избранные (по настройке), исключения владельца и несколько известных критичных программ (удалённый доступ, пульт, ADB-phon).
 * С ADB или root приложения останавливаются полностью (am force-stop) и считаются по списку процессов; без них только killBackgroundProcesses (часть процессов).
 */
object MemoryCleaner {
    class Result(val count: Int, val freedMb: Int, val soft: Boolean)

    @Volatile private var running = false
    private var lastAuto = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Части имени пакета, которые не останавливаются никогда (удалённый доступ, пульт и ключи, экранное управление, свои программы владельца). */
    private val ALWAYS_KEEP = listOf(
        "anydesk", "teamviewer", "rustdesk", "magisk", "adbphon", "app.click", "app.tvapps", "voicesearch", "tailscale", "wireguard", "openvpn",
        "remote", "keyboard", "quickactions", "smartlife", "phoneinwindow",
    )

    /** Пакеты, которые очистка не останавливает по устройству (не по настройкам): определяются заново при каждой очистке. */
    private fun systemProtected(ctx: Context): MutableSet<String> {
        val s = HashSet<String>()
        val pm = ctx.packageManager
        s += ctx.packageName
        // все главные экраны (родной, этот, чужие): остановка одного из них оставит экран без «Домой»
        runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).forEach { s += it.activityInfo.packageName } }
        if (Cfg.nativeHome.isNotEmpty()) s += Cfg.nativeHome
        // клавиатуры
        runCatching { (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList.forEach { s += it.packageName } }
        // службы спецвозможностей (экранное управление, перехват кнопок пульта)
        runCatching {
            (ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
                .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK).forEach { s += it.resolveInfo.serviceInfo.packageName }
        }
        // слушатели уведомлений, голосовые помощники, клавиатура по умолчанию
        for (key in listOf("enabled_notification_listeners", "enabled_accessibility_services", "voice_interaction_service", "assistant", "voice_recognition_service", "default_input_method")) {
            runCatching {
                Settings.Secure.getString(ctx.contentResolver, key).orEmpty().split(':', ',', ';').forEach { c ->
                    val p = c.trim().substringBefore('/')
                    if (p.contains('.')) s += p
                }
            }
        }
        // VPN: остановка приложения обрывает подключение
        runCatching { pm.queryIntentServices(Intent("android.net.VpnService"), 0).forEach { s += it.serviceInfo.packageName } }
        // живые обои
        runCatching { android.app.WallpaperManager.getInstance(ctx).wallpaperInfo?.packageName?.let { s += it } }
        // поставщики виджетов Android, стоящих на «Доме»: остановленный (force-stop) поставщик перестаёт обновлять виджет, и часы и погода в нём застывают
        runCatching {
            val wm = android.appwidget.AppWidgetManager.getInstance(ctx)
            HomeStore.layout.rows.forEach { r -> r.slots.forEach { w -> if (w.type == "appwidget") w.ref.toIntOrNull()?.let { id -> wm.getAppWidgetInfo(id)?.provider?.packageName?.let { s += it } } } }
        }
        // администраторы устройства
        runCatching { (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).activeAdmins?.forEach { s += it.packageName } }
        return s
    }

    /** Пакеты с играющим сейчас звуком или видео (сеанс мультимедиа в состоянии «играет»). null: определить не удалось (нет доступа к уведомлениям). */
    private fun playingPackages(ctx: Context): Set<String>? = runCatching {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm.getActiveSessions(NotifService.component(ctx)).filter {
            when (it.playbackState?.state) {
                PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_FAST_FORWARDING,
                PlaybackState.STATE_REWINDING, PlaybackState.STATE_CONNECTING, PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> true
                else -> false
            }
        }.map { it.packageName }.toSet()
    }.getOrNull()

    private fun musicActive(ctx: Context): Boolean = runCatching { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive }.getOrDefault(false)

    /** Пользовательские приложения, которые можно остановить (без защищённых). */
    private fun candidates(ctx: Context, protect: Set<String>): List<ApplicationInfo> {
        val keepFav = Cfg.memKeepFav
        return runCatching { ctx.packageManager.getInstalledApplications(0) }.getOrDefault(emptyList()).filter { ai ->
            val p = ai.packageName
            ai.flags and ApplicationInfo.FLAG_SYSTEM == 0 && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0 && ai.enabled &&
                p !in protect && p !in Cfg.memExcl && !(keepFav && p in Cfg.favorites) && ALWAYS_KEEP.none { p.contains(it, true) }
        }
    }

    /** Имена пакетов запущенных процессов (через ADB/root); null, если список получить не удалось. */
    private suspend fun runningPackages(): Set<String>? {
        val out = runCatching { Adb.shell("ps -A -o NAME 2>/dev/null; ps 2>/dev/null", 10_000) }.getOrDefault("")
        val set = HashSet<String>()
        for (line in out.lineSequence()) {
            val tok = line.trim().split(Regex("\\s+")).lastOrNull().orEmpty()
            if (tok.isEmpty() || tok.startsWith("/") || tok.startsWith("[")) continue
            val base = tok.substringBefore(':')
            if (base.contains('.')) set += base
        }
        return set.takeIf { it.size >= 5 }
    }

    /**
     * Очистка. [auto]: тихий вызов по кнопке «Домой»; если звук играет, а чьё именно приложение, неизвестно, ничего не делает.
     * Возвращает null, если очистка уже идёт или отложена.
     */
    suspend fun clean(ctx: Context, auto: Boolean): Result? = withContext(Dispatchers.IO) {
        if (running) return@withContext null
        if (!Integrity.safe(ctx)) return@withContext null
        running = true
        try {
            val app = ctx.applicationContext
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val playing = playingPackages(app)
            if (auto && playing.isNullOrEmpty() && musicActive(app)) return@withContext null
            // сразу после запуска процесса по «Домой» соединение ещё не поднято: даём ему шанс (недолго)
            if (auto && !Adb.isConnected) withTimeoutOrNull(5000) { autoConnect(app) }
            val before = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
            val protect = systemProtected(app)
            if (playing != null) protect += playing
            var list = candidates(app, protect)
            val hard = Adb.isConnected
            val run = if (hard) runningPackages() else null
            list = when {
                run != null -> list.filter { it.packageName in run }
                // нет списка процессов: берём приложения, которые запускались и не были остановлены (признак «остановлено» снимается при запуске)
                else -> list.filter { it.flags and 0x200000 == 0 }
            }
            Log.i("TVApps", "очистка памяти: к остановке ${list.size}, hard=$hard, список процессов=${run != null}")
            if (list.isEmpty()) return@withContext Result(0, 0, !hard)
            if (hard) list.map { it.packageName }.chunked(12).forEach { chunk ->
                runCatching { Adb.shell(chunk.joinToString("; ") { "am force-stop $it" }, 25_000) }
            } else list.forEach { runCatching { am.killBackgroundProcesses(it.packageName) } }
            delay(1500)
            val after = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
            Result(list.size, ((after - before) / (1024 * 1024)).toInt().coerceAtLeast(0), !hard)
        } finally { running = false }
    }

    /** Очистка по нажатию кнопки со сводкой в сообщении («Остановлено: N, освобождено: M МБ», «Нечего очищать»). */
    suspend fun cleanWithToast(ctx: Context) {
        val r = clean(ctx, false) ?: return
        val msg = if (r.count == 0) ctx.getString(R.string.mem_nothing)
        else ctx.getString(if (r.soft) R.string.mem_done_soft else R.string.mem_done, r.count, r.freedMb)
        withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show() }
    }

    /** Очистка по нажатию на виджет «Память» на «Доме»: сводка в сообщении, затем [after] (в главном потоке) обновляет цифры виджета. */
    fun cleanAsync(ctx: Context, after: () -> Unit) {
        val app = ctx.applicationContext
        scope.launch {
            runCatching { cleanWithToast(app) }
            withContext(Dispatchers.Main) { after() }
        }
    }

    /** Нажатие «Домой»: тихая очистка, если включена в настройках; не чаще раза в 20 секунд. */
    fun onHome(ctx: Context) {
        if (!Cfg.memAuto) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastAuto != 0L && now - lastAuto < 20_000L) return
        lastAuto = now
        val app = ctx.applicationContext
        scope.launch {
            delay(900)   // экран «Домой» успевает отрисоваться, прежнее приложение уходит в фон
            runCatching { clean(app, true) }
        }
    }
}
