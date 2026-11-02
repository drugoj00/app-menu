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

        private fun shown(s: StatusBarNotification, self: String) =
            s.packageName != self && (s.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
                (s.notification.extras.getCharSequence(Notification.EXTRA_TITLE) != null || s.notification.extras.getCharSequence(Notification.EXTRA_TEXT) != null)

        /** Активные уведомления (без наших и без «сводок» групп), новые сверху. */
        fun list(ctx: Context): List<StatusBarNotification> =
            runCatching { instance?.activeNotifications?.filter { shown(it, ctx.packageName) }?.sortedByDescending { it.postTime } }.getOrNull().orEmpty()

        fun count(ctx: Context): Int = list(ctx).size
        val connected get() = instance != null

        fun component(ctx: Context) = ComponentName(ctx, NotifService::class.java)

        fun enabledInSettings(ctx: Context): Boolean =
            (Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: "").contains(ctx.packageName)
    }

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
}

private data class NItem(val key: String, val app: String, val icon: Drawable?, val title: String, val text: String, val time: Long, val sbn: StatusBarNotification)

private fun load(ctx: Context): List<NItem> {
    val pm = ctx.packageManager
    return NotifService.list(ctx).map { s ->
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
    var connected by remember { mutableStateOf(NotifService.connected) }
    var trying by remember { mutableStateOf(false) }
    // при открытии без доступа пробуем включить его сами (root или ADB по Wi-Fi), список обновляется раз в секунду
    LaunchedEffect(Unit) {
        if (!NotifService.connected && !trying) {
            trying = true
            runCatching { Adb.shell("cmd notification allow_listener " + NotifService.component(ctx).flattenToString(), 8000) }
            repeat(6) { if (!NotifService.connected) delay(500) }
            trying = false
        }
        while (true) {
            connected = NotifService.connected
            val n = load(ctx)
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
            add(Opt(stringResource(R.string.nt_none), "", onClick = { onClose() }))
        } else {
            add(Opt(stringResource(R.string.nt_clear_all, items.size), "", danger = true, onClick = { runCatching { NotifService.instance?.cancelAllNotifications() }; onClose() }))
            items.forEach { n ->
                add(Opt(
                    n.app + (if (n.title.isNotBlank()) ": " + n.title else "") + (if (n.text.isNotBlank()) "\n" + n.text else ""),
                    fmt.format(Date(n.time)),
                    icon = n.icon?.let { runCatching { it.toBitmap(64, 64).asImageBitmap() }.getOrNull() },
                    onClick = {
                        val pi = n.sbn.notification.contentIntent
                        val ok = pi != null && runCatching { pi.send(); true }.getOrDefault(false)
                        if (!ok) ctx.packageManager.getLaunchIntentForPackage(n.sbn.packageName)?.let { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                        if (n.sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) runCatching { NotifService.instance?.cancelNotification(n.key) }
                        onClose()
                    },
                ))
            }
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
        (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            ctx.packageManager.hasSystemFeature("android.software.leanback")
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
    fun set(ctx: Context, nav: Boolean, v: Int) { ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE).edit().putInt(key(nav), v).apply() }
    fun hidden(ctx: Context, nav: Boolean): Boolean = when (get(ctx, nav)) { 1 -> false; 2 -> true; else -> TopMode.isTv(ctx) }

    /** Применить к окну: каждая из панелей скрывается (появляется по свайпу от края) или показывается отдельно. */
    fun apply(act: android.app.Activity) {
        val c = androidx.core.view.WindowInsetsControllerCompat(act.window, act.window.decorView)
        val hs = hidden(act, false); val hn = hidden(act, true)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(act.window, !(hs && hn))
        c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hs) c.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars()) else c.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        if (hn) c.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars()) else c.show(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
    }
}
