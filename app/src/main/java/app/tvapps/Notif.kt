package app.tvapps

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 26.11.2026 (владелица: «жму колокольчик, экран затемняется и всё»): окна верхней полоски читают общие флаги [TopUi], а «Дом» и «Приложения» живут одновременно,
 * поэтому окно открывалось и в той activity, что на заднем плане (вторая подложка затемнения). Теперь окно показывает только та activity, которая сейчас на экране.
 */
@Composable
fun rememberOnScreen(): Boolean {
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var on by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    androidx.compose.runtime.DisposableEffect(owner) {
        val ob = androidx.lifecycle.LifecycleEventObserver { _, _ -> on = owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(ob)
        onDispose { owner.lifecycle.removeObserver(ob) }
    }
    return on
}

/** Окна верхней полоски «Дома»: открыты ли «Недавние» и «Уведомления» (рисует HomeActivity). */
object TopUi {
    val recents = mutableStateOf(false)
    val notifs = mutableStateOf(false)
    val hdmi = mutableStateOf(false)
    val emptyMenu = mutableStateOf(false)
    val tick = androidx.compose.runtime.mutableIntStateOf(0)
}

/**
 * Служба чтения уведомлений. Нужна, чтобы показать их в своём окне по значку колокольчика.
 * Доступ выдаётся в настройках («Доступ к уведомлениям») или автоматически через root/ADB (команда cmd notification allow_listener).
 */
class NotifService : NotificationListenerService() {
    companion object {
        @Volatile var instance: NotifService? = null

        /**
         * Число уведомлений для красной цифры внутри колокольчика «Дома» (12.0). Наблюдаемое: меняется при подключении службы, поступлении и снятии уведомления
         * и при отключении службы (тогда 0); значок читает его прямо в композиции и меняется сразу, а не раз в несколько секунд по тику строки состояния.
         * Считается тем же фильтром, что список в окне «Уведомления» ([list]): без наших и без «сводок» групп.
         */
        val badge = androidx.compose.runtime.mutableIntStateOf(0)

        @Volatile private var lastRebind = 0L

        /** Пересчитать цифру колокольчика сразу (после «Очистить все»). */
        fun refreshBadgeNow() {
            val inst = instance ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ inst.refreshBadgePublic() }, 400L)
        }

        private fun shown(s: StatusBarNotification, self: String) =
            s.packageName != self && (s.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
                s.packageName !in Cfg.notifHidden &&
                (s.notification.extras.getCharSequence(Notification.EXTRA_TITLE) != null || s.notification.extras.getCharSequence(Notification.EXTRA_TEXT) != null)

        /** Активные уведомления (без наших и без «сводок» групп), новые сверху. */
        fun list(ctx: Context, withHidden: Boolean = false): List<StatusBarNotification> =
            runCatching { instance?.activeNotifications?.filter { if (withHidden) shownAny(it, ctx.packageName) else shown(it, ctx.packageName) }?.sortedByDescending { it.postTime } }.getOrNull().orEmpty()

        private fun shownAny(s: StatusBarNotification, self: String) =
            s.packageName != self && (s.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
                (s.notification.extras.getCharSequence(Notification.EXTRA_TITLE) != null || s.notification.extras.getCharSequence(Notification.EXTRA_TEXT) != null)

        fun count(ctx: Context): Int = list(ctx).size
        val connected get() = instance != null

        fun component(ctx: Context) = ComponentName(ctx, NotifService::class.java)

        fun enabledInSettings(ctx: Context): Boolean =
            (Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: "").contains(ctx.packageName)

        @Volatile private var toggles = 0

        /**
         * Доступ к уведомлениям выдан, а служба не подключена (после обновления программы, перезапуска, остановки процесса система сама её не всегда подключает обратно,
         * на Android 7 не подключает вовсе, и колокольчик остаётся без числа, хотя в окне «Уведомления» всё видно после его ручной попытки включить доступ).
         * Что делается: (1) штатная просьба `requestRebind` (API 24+; система выполняет её только для службы, отключённой запросом `requestUnbind`, поэтому обычно её мало);
         * (2) если за 1,5 секунды служба не подключилась, переключение состояния собственного компонента службы (отключить и вернуть по умолчанию): система получает
         * сообщение об изменении пакета и заново подключает все включённые службы уведомлений (приём не требует особых прав). Не чаще раза в минуту и не больше трёх
         * переключений за жизнь процесса (каждое переключение даёт системе повод разослать «пакет изменён», а «Дом» после него перечитывает список приложений);
         * без выданного доступа ничего не делает.
         */
        fun rebindIfNeeded(ctx: Context) {
            if (instance != null) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastRebind != 0L && now - lastRebind < 60_000L) return
            val app = ctx.applicationContext
            if (!runCatching { enabledInSettings(app) }.getOrDefault(false)) return
            lastRebind = now
            if (android.os.Build.VERSION.SDK_INT >= 24) runCatching { android.service.notification.NotificationListenerService.requestRebind(component(app)) }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (instance == null && toggles < 3) runCatching {
                    toggles++
                    val pm = app.packageManager
                    val cn = component(app)
                    pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                    pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, android.content.pm.PackageManager.DONT_KILL_APP)
                    android.util.Log.i("TVApps", "уведомления: доступ выдан, служба не подключена, компонент переключён для повторного подключения (" + toggles + ")")
                }
            }, 1500L)
            android.util.Log.i("TVApps", "уведомления: доступ выдан, служба не подключена, запрошено повторное подключение")
        }
    }

    /** Пересчитывает число для колокольчика по текущему списку (главный поток). */
    fun refreshBadgePublic() = refreshBadge()
    private fun refreshBadge() {
        val n = runCatching { list(this).size }.getOrDefault(0)
        if (badge.intValue != n) badge.intValue = n
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var badgePending = false

    /**
     * Пересчёт через 100 мс, один на всю «пачку» событий: список читается у системы целиком, а приложения-«болтуны» (мессенджеры, загрузчики с процентами)
     * шлют по десятку обновлений в секунду; без склейки каждое событие тянуло бы весь список на слабой приставке. Число меняется в пределах доли секунды.
     */
    private fun scheduleBadge() {
        if (badgePending) return
        badgePending = true
        main.postDelayed({ badgePending = false; refreshBadge() }, 100L)
    }

    override fun onListenerConnected() { instance = this; refreshBadge() }
    override fun onListenerDisconnected() { if (instance === this) instance = null; main.removeCallbacksAndMessages(null); badgePending = false; badge.intValue = 0 }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { scheduleBadge() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { scheduleBadge() }
}

private data class NItem(val key: String, val app: String, val icon: Drawable?, val title: String, val text: String, val time: Long, val sbn: StatusBarNotification)

private fun load(ctx: Context, withHidden: Boolean = false): List<NItem> {
    val pm = ctx.packageManager
    return NotifService.list(ctx, withHidden).map { s ->
        val ex = s.notification.extras
        val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(s.packageName, 0)).toString() }.getOrDefault(s.packageName)
        val icon = runCatching { pm.getApplicationIcon(s.packageName) }.getOrNull()
        val title = (ex.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString()
        NItem(s.key, app, icon, title, text, s.postTime, s)
    }
}

/** Окно уведомлений: список (значок, программа, заголовок, текст, время). ОК открывает уведомление, первая строка очищает все. */
@Composable
fun NotificationsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<NItem>() }
    var showHidden by remember { mutableStateOf(false) }
    var lastEye by remember { mutableStateOf(0L) }
    var connected by remember { mutableStateOf(NotifService.connected) }
    var trying by remember { mutableStateOf(false) }
    // при открытии без доступа пробуем включить его сами (root или ADB по Wi-Fi), список обновляется раз в секунду
    // 26.11.2026 (владелица: «надо закрыть окно и открыть, чтобы изменилось»): цикл перезапускается при каждом скрытии или показе, и список перечитывается сразу
    val hiddenNow = Cfg.notifHidden
    LaunchedEffect(showHidden, hiddenNow) {
        if (!NotifService.connected && !trying) {
            trying = true
            runCatching { Adb.shell("cmd notification allow_listener " + NotifService.component(ctx).flattenToString(), 8000) }
            repeat(6) { if (!NotifService.connected) delay(500) }
            trying = false
        }
        while (true) {
            connected = NotifService.connected
            val n = load(ctx, showHidden)
            items.clear(); items.addAll(n)
            delay(1000)
        }
    }
    val fmt = remember { SimpleDateFormat("HH:mm") }
    val opts = buildList {
        if (!connected) {
            add(Opt(stringResource(if (trying) R.string.nt_enabling else R.string.nt_need_access), "", onClick = {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Toast.makeText(ctx, ctx.getString(R.string.home_cant_open), Toast.LENGTH_SHORT).show() }
                onClose()
            }))
        } else if (items.isEmpty()) {
            // 26.11.2026 (владелица, X98plus gost: «пусто и показать нет»): все уведомления скрыты глазком, кнопка «Показать скрытые» нужна и в пустом списке
            val hiddenAll0 = NotifService.list(ctx, true).count { it.packageName in Cfg.notifHidden }
            add(Opt(stringResource(R.string.nt_none), "", onClick = { onClose() }))
            if (hiddenAll0 > 0 || showHidden) add(Opt(stringResource(if (showHidden) R.string.nt_hide_hidden else R.string.nt_show_hidden, hiddenAll0), "", removeIcon = if (showHidden) HIcons.eyeOff else HIcons.eye, onRemove = { val now = android.os.SystemClock.elapsedRealtime(); if (now - lastEye > 500L) { lastEye = now; showHidden = !showHidden } }, onClick = { val now = android.os.SystemClock.elapsedRealtime(); if (now - lastEye > 500L) { lastEye = now; showHidden = !showHidden } }))
        } else {
            val clearable = items.filter { it.sbn.isClearable }
            val hiddenAll = remember(items.size, showHidden, Cfg.notifHidden) { NotifService.list(ctx, true).count { it.packageName in Cfg.notifHidden } }
            // 26.11.2026 (st0p0r на 4PDA): кнопка убирает всё, что система позволяет убрать; постоянные уведомления (VPN, музыка, служебные) не смахиваются, об этом сказано в списке
            add(Opt(stringResource(if (clearable.isNotEmpty()) R.string.nt_clear_all else R.string.nt_clear_all_zero, clearable.size), "", danger = true, onClick = {
                // каждое уведомление снимается по ключу (общий «снять все» на части прошивок пропускает часть), затем список и цифра пересчитываются
                val svc = NotifService.instance
                clearable.forEach { c -> runCatching { svc?.cancelNotification(c.key) } }
                runCatching { svc?.cancelAllNotifications() }
                items.removeAll { it.sbn.isClearable }
                NotifService.refreshBadgeNow()
            }))
            items.forEach { n ->
                add(Opt(
                    n.app + (if (n.title.isNotBlank()) ": " + n.title else "") + (if (n.text.isNotBlank()) "\n" + n.text else ""),
                    fmt.format(Date(n.time)),
                    icon = n.icon?.let { runCatching { it.toBitmap(64, 64).asImageBitmap() }.getOrNull() },
                    removeIcon = if (n.sbn.packageName in Cfg.notifHidden) HIcons.eyeOff else HIcons.eye,
                    onRemove = { val now = android.os.SystemClock.elapsedRealtime(); if (now - lastEye > 500L) { lastEye = now; val willHide = n.sbn.packageName !in Cfg.notifHidden; Cfg.toggleNotifHidden(n.sbn.packageName); if (willHide) showHidden = false } },   // скрыли: строка сразу пропадает, режим «показать скрытые» выключается
                    onClick = {
                        val pi = n.sbn.notification.contentIntent
                        val ok = pi != null && runCatching { pi.send(); true }.getOrDefault(false)
                        if (!ok) ctx.packageManager.getLaunchIntentForPackage(n.sbn.packageName)?.let { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                        if (n.sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) runCatching { NotifService.instance?.cancelNotification(n.key) }
                        onClose()
                    },
                ))
            }
            if (hiddenAll > 0 || showHidden) add(Opt(stringResource(if (showHidden) R.string.nt_hide_hidden else R.string.nt_show_hidden, hiddenAll), "", removeIcon = if (showHidden) HIcons.eyeOff else HIcons.eye, onRemove = { val now = android.os.SystemClock.elapsedRealtime(); if (now - lastEye > 500L) { lastEye = now; showHidden = !showHidden } }, onClick = { val now = android.os.SystemClock.elapsedRealtime(); if (now - lastEye > 500L) { lastEye = now; showHidden = !showHidden } }))
        }
    }
    ListDialog(stringResource(R.string.nt_title), opts, onClose, hint = if (connected) "" else stringResource(R.string.nt_hint))
}

/**
 * «Недавние»: свои, не системные (на части прошивок системных нет): программы, которые запускали из меню и «Дома», новые сверху.
 */
@Composable
fun RecentsDialog(apps: List<Entry>, onOpen: (Entry) -> Unit, onClose: () -> Unit) {
    var rev by remember { mutableStateOf(0) }
    val list = remember(apps, rev) {
        apps.filter { Cfg.lastLaunch(it.pkg) > 0L && !Cfg.isHidden(it.pkg) }.sortedByDescending { Cfg.lastLaunch(it.pkg) }.take(15)
    }
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm") }
    val opts = if (list.isEmpty()) listOf(Opt(stringResource(R.string.rc_none), "", onClick = { onClose() }))
    else list.map { e ->
        Opt(
            e.label, fmt.format(Date(Cfg.lastLaunch(e.pkg))), icon = e.bmp ?: runCatching { e.icon.toBitmap(64, 64).asImageBitmap() }.getOrNull(),
            onRemove = { Cfg.clearLast(e.pkg); rev++ }, onClick = { onClose(); onOpen(e) },
        )
    }
    ListDialog(
        stringResource(R.string.rc_title), opts, onClose,
        footer = if (list.isEmpty()) null else Opt(stringResource(R.string.rc_clear_all), danger = true, onClick = { Cfg.clearAllLast(); rev++ }),
    )
}

/** Окно «Входы»: HDMI и другие внешние входы телевизора; ОК переключает на вход. Если входов нет (телефон, планшет), говорит об этом. */
@Composable
fun HdmiDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val inputs = remember { TvInputs.list(ctx) }
    val opts = if (inputs.isEmpty()) listOf(Opt(stringResource(R.string.hd_none), "", onClick = { onClose() }))
    else inputs.map { i ->
        Opt(i.label, if (i.hdmi) "HDMI" else "", onClick = {
            onClose()
            if (!TvInputs.open(ctx, i.id)) Toast.makeText(ctx, ctx.getString(R.string.home_cant_open), Toast.LENGTH_SHORT).show()
        })
    }
    ListDialog(stringResource(R.string.hd_title), opts, onClose, hint = if (inputs.isEmpty()) "" else stringResource(R.string.hd_hint))
}

/**
 * Верхняя полоска «Дома» нужна на телевизорах и приставках (там нет системной строки состояния). На телефоне и планшете она есть у системы,
 * поэтому «Авто» показывает полоску только на ТВ/приставке (режим интерфейса «телевизор» или Leanback). Можно принудительно показать или скрыть.
 */
object TopMode {
    private const val KEY = "top_mode"       // 0 авто, 1 показывать, 2 скрывать
    fun get(ctx: Context): Int = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE).getInt(KEY, 0)
    fun set(ctx: Context, v: Int) { ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE).edit().putInt(KEY, v).apply(); TopUi.tick.intValue++ }
    fun isTv(ctx: Context): Boolean =
        // отладочная сборка: файл files/dbg_notv выдаёт эмулятор ТВ за телефон (проверка портрета без телефона); в выпуске BuildConfig.DEBUG = false
        !(BuildConfig.DEBUG && java.io.File(ctx.filesDir, "dbg_notv").exists()) && ((ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            ctx.packageManager.hasSystemFeature("android.software.leanback"))
    fun visible(ctx: Context): Boolean = when (get(ctx)) { 1 -> true; 2 -> false; else -> isTv(ctx) }
}

/**
 * Системные панели (строка состояния и навигация): режим «авто» прячет их на ТВ и приставках (там они мешают) и оставляет на телефоне и планшете;
 * можно принудительно скрыть или показать на любом устройстве.
 */
object SysBars {
    // отдельно для строки состояния ("sb_status") и панели навигации ("sb_nav"): 0 авто, 1 показывать, 2 скрывать
    private fun key(nav: Boolean) = if (nav) "sb_nav" else "sb_status"
    fun get(ctx: Context, nav: Boolean): Int {
        val p = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE)
        // прежняя общая настройка "sys_bars" переносится в обе
        return p.getInt(key(nav), p.getInt("sys_bars", 0))
    }
    /** Растёт при смене настройки: открытые окна-диалоги заново применяют панели. */
    val tick = androidx.compose.runtime.mutableIntStateOf(0)
    fun set(ctx: Context, nav: Boolean, v: Int) { ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE).edit().putInt(key(nav), v).apply(); tick.intValue++ }
    fun hidden(ctx: Context, nav: Boolean): Boolean = when (get(ctx, nav)) { 1 -> false; 2 -> true; else -> TopMode.isTv(ctx) }

    /** Применить к окну: каждая из панелей скрывается (появляется по свайпу от края) или показывается отдельно. */
    fun apply(act: android.app.Activity) {
        val hs = hidden(act, false); val hn = hidden(act, true)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(act.window, !(hs && hn))
        applyWindow(act, act.window)
    }

    /** То же для любого окна (и окна-диалога). На Android до 11 строке состояния добавляется флаг «на весь экран»: часть прошивок TV Box иначе её не прячет. */
    @Suppress("DEPRECATION")
    fun applyWindow(ctx: Context, w: android.view.Window) {
        val c = androidx.core.view.WindowInsetsControllerCompat(w, w.decorView)
        val hs = hidden(ctx, false); val hn = hidden(ctx, true)
        if (android.os.Build.VERSION.SDK_INT < 30) {
            if (hs) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN) else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hs) c.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars()) else c.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        if (hn) c.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars()) else c.show(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
    }
}

/** Окно-диалог на Android 7-10 заново показывает системные панели: прячет их так же, как «Дом», и сразу после смены настройки. */
@Composable
fun DialogBars() {
    if (android.os.Build.VERSION.SDK_INT >= 30) return
    val view = androidx.compose.ui.platform.LocalView.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    @Suppress("UNUSED_VARIABLE") val t = SysBars.tick.intValue
    val w = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: return
    androidx.compose.runtime.SideEffect { SysBars.applyWindow(ctx, w) }
}
