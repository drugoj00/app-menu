package app.tvapps

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.Locale

// палитра: бирюзовая, тёмная
private val BG = Color(0xFF0C1817)
private val TILE = Color(0xFF15302D)
private val TILE_F = Color(0xFF1F4642)
private val ACC = Color(0xFF5EEAD4)
private val TXT = Color(0xFFE6F4F1)
private val SUB = Color(0xFF8DB4AE)
private val OKC = Color(0xFF4ADE80)
private val BADC = Color(0xFFF87171)
private val WARNC = Color(0xFFFBBF24)

/** Настройки заставки «Часы и погода»: правила, способы показа, мастер настройки для магнитол, диагностика, журнал. Открывается из настроек «Дома» и из настроек системной заставки. */
class SaverSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cfg.init(applicationContext)
        HomeStore.init(applicationContext)
        SaverLog.init(applicationContext)
        val first = intent?.getStringExtra("screen") ?: "main"
        setContent { SaverRoot(first) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }
}

// ------------------------------------------------------------------ общие элементы

@Composable
private fun Item(
    title: String, value: String = "", sub: String? = null, valueColor: Color = ACC,
    onDec: (() -> Unit)? = null, onInc: (() -> Unit)? = null, onClick: (() -> Unit)? = null, autoFocus: Boolean = false,
) {
    var foc by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    val shape = RoundedCornerShape(10.dp)
    val act = onClick != null || onInc != null
    var m = Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(shape).background(if (foc) TILE_F else TILE)
        .border(if (foc) 2.dp else 0.dp, if (foc) ACC else Color.Transparent, shape)
    if (act) {
        m = m.focusRequester(fr).onFocusChanged { foc = it.isFocused }.onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown) when (e.key) {
                Key.DirectionLeft -> if (onDec != null) { onDec(); true } else false
                Key.DirectionRight -> if (onInc != null) { onInc(); true } else false
                else -> false
            } else false
        }.clickable { if (onClick != null) onClick() else onInc?.invoke() }
    }
    Column(m.padding(horizontal = 12.dp, vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TXT, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (value.isNotEmpty()) {
                if (onDec != null) Text("‹  ", color = SUB, fontSize = 15.sp)
                Text(value, color = valueColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (onInc != null) Text("  ›", color = SUB, fontSize = 15.sp)
            }
        }
        if (!sub.isNullOrEmpty()) Text(sub, color = SUB, fontSize = 12.sp, lineHeight = 15.sp)
    }
    if (autoFocus && act) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

@Composable
private fun SvChip(text: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var foc by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.clip(shape).background(if (on) Color(0xFF0F766E) else if (foc) TILE_F else TILE).border(if (foc) 2.dp else 0.dp, if (foc) ACC else Color.Transparent, shape)
            .onFocusChanged { foc = it.isFocused }.clickable { onClick() }.padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = TXT, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal) }
}

@Composable
private fun Head(text: String) { Text(text, color = ACC, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 3.dp, start = 4.dp)) }

private fun <T> cycle(list: List<T>, cur: T, dir: Int): T {
    val i = list.indexOf(cur).let { if (it < 0) 0 else it }
    return list[((i + dir) % list.size + list.size) % list.size]
}

private fun hm(min: Int) = String.format(Locale.US, "%02d:%02d", (min / 60) % 24, min % 60)

private fun toast(c: Context, id: Int) = Toast.makeText(c, id, Toast.LENGTH_LONG).show()

// ------------------------------------------------------------------ корень и навигация

@Composable
private fun SaverRoot(first: String) {
    var screen by remember { mutableStateOf(first) }
    val ctx = LocalContext.current
    BackHandler(enabled = screen != "main") { screen = "main" }
    Column(Modifier.fillMaxSize().background(BG).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(when (screen) { "apps" -> R.string.sv_apps_title; "wizard" -> R.string.sv_wizard; "diag" -> R.string.sv_diag; "log" -> R.string.sv_log; else -> R.string.sv_title }),
                color = TXT, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
            )
            if (screen != "main") SvChip(stringResource(R.string.sv_back), false) { screen = "main" }
        }
        Spacer(Modifier.size(6.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (screen) {
                "apps" -> AppsScreen(ctx)
                "wizard" -> WizardScreen(ctx) { screen = it }
                "diag" -> DiagScreen(ctx) { screen = it }
                "log" -> LogScreen(ctx)
                "wall" -> { MainScreen(ctx) { screen = it }; WallNetDialog("saver", onOwn = { }, onOwnVideo = { }, onClose = { screen = "main" }) }
                else -> MainScreen(ctx) { screen = it }
            }
        }
    }
}

// ------------------------------------------------------------------ главный экран настроек

@Composable
private fun MainScreen(ctx: Context, go: (String) -> Unit) {
    var cfg by remember { mutableStateOf(SaverPrefs.get(ctx)) }
    fun upd(f: (SaverCfg) -> SaverCfg) { SaverPrefs.set(ctx, f); cfg = SaverPrefs.get(ctx) }
    val on = stringResource(R.string.on); val off = stringResource(R.string.off)
    val idles = listOf(1, 2, 5, 10, 15, 30)
    val ways = listOf(stringResource(R.string.sv_way_auto), stringResource(R.string.sv_way_overlay), stringResource(R.string.sv_way_window))
    val sounds = listOf(stringResource(R.string.sv_snd_off), stringResource(R.string.sv_snd_music), stringResource(R.string.sv_snd_any))
    val modes = listOf(stringResource(R.string.sv_mode_not), stringResource(R.string.sv_mode_only))
    val bgs = listOf(0L, 0xFF0B1220L, 0xFF0A1A12L, 0xFF1A1A1AL, 0xFF1A0F0AL)
    val bgNames = listOf(R.string.sv_bg_black, R.string.sv_bg_blue, R.string.sv_bg_green, R.string.sv_bg_gray, R.string.sv_bg_brown)
    val dayNames = remember { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").mapIndexed { i, _ ->
        val c = java.util.Calendar.getInstance(); c.set(2024, 0, 1 + i)      // 1 января 2024: понедельник
        java.text.SimpleDateFormat("EE", Locale.getDefault()).format(c.time)
    } }
    BarLazyColumn(Modifier.fillMaxSize()) {
        item {
            Item(stringResource(R.string.sv_enable), if (cfg.enabled) on else off, stringResource(R.string.sv_enable_sub), onClick = {
                val n = !cfg.enabled
                upd { it.copy(enabled = n) }
                if (n) {
                    SaverLog.log("заставка включена в настройках")
                    SaverGuard.startIfEnabled(ctx, "включено")
                    if (!SaverPerm.overlay(ctx)) toast(ctx, R.string.sv_hint_overlay)
                } else { SaverLog.log("заставка выключена в настройках"); SaverGuard.disarm(ctx); SaverService.stop(ctx) }
            }, onInc = null, autoFocus = true)
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SvChip(stringResource(R.string.sv_p_car), false, Modifier.weight(1f)) { upd(SaverPrefs.preset(0)) }
                SvChip(stringResource(R.string.sv_p_tv), false, Modifier.weight(1f)) { upd(SaverPrefs.preset(1)) }
                SvChip(stringResource(R.string.sv_p_phone), false, Modifier.weight(1f)) { upd(SaverPrefs.preset(2)) }
            }
        }
        item { Item(stringResource(R.string.sv_wizard), sub = stringResource(R.string.sv_wizard_sub), onClick = { go("wizard") }) }
        item { Item(stringResource(R.string.sv_diag), sub = stringResource(R.string.sv_diag_sub), onClick = { go("diag") }) }
        item { Item(stringResource(R.string.sv_wall), if (WallNet.saverOn(ctx)) on else off, stringResource(R.string.sv_wall_sub), onClick = { go("wall") }) }
        item { Head(stringResource(R.string.sv_g_when)) }
        item { Item(stringResource(R.string.sv_idle), stringResource(R.string.sv_min, cfg.idleMin), onDec = { upd { it.copy(idleMin = cycle(idles, it.idleMin, -1)) } }, onInc = { upd { it.copy(idleMin = cycle(idles, it.idleMin, 1)) } }) }
        item { Item(stringResource(R.string.sv_way), ways[cfg.way.coerceIn(0, 2)], stringResource(R.string.sv_way_sub), onDec = { upd { it.copy(way = cycle(listOf(0, 1, 2), it.way, -1)) } }, onInc = { upd { it.copy(way = cycle(listOf(0, 1, 2), it.way, 1)) } }) }
        item { Item(stringResource(R.string.sv_apps_mode), modes[cfg.mode.coerceIn(0, 1)], onDec = { upd { it.copy(mode = 1 - it.mode) } }, onInc = { upd { it.copy(mode = 1 - it.mode) } }) }
        item { Item(stringResource(R.string.sv_apps_title), cfg.apps.size.toString(), stringResource(R.string.sv_apps_sub), onClick = { go("apps") }) }
        item { Item(stringResource(R.string.sv_sound), sounds[cfg.sound.coerceIn(0, 2)], stringResource(R.string.sv_sound_sub), onDec = { upd { it.copy(sound = cycle(listOf(0, 1, 2), it.sound, -1)) } }, onInc = { upd { it.copy(sound = cycle(listOf(0, 1, 2), it.sound, 1)) } }) }
        item { Item(stringResource(R.string.sv_charging), if (cfg.onlyCharging) on else off, onClick = { upd { it.copy(onlyCharging = !it.onlyCharging) } }) }
        item { Item(stringResource(R.string.sv_sched), if (cfg.schedOn) on else off, stringResource(R.string.sv_sched_sub), onClick = { upd { it.copy(schedOn = !it.schedOn) } }) }
        if (cfg.schedOn) {
            item { Item(stringResource(R.string.sv_from), hm(cfg.from), onDec = { upd { it.copy(from = (it.from - 30 + 1440) % 1440) } }, onInc = { upd { it.copy(from = (it.from + 30) % 1440) } }) }
            item { Item(stringResource(R.string.sv_to), hm(cfg.to), onDec = { upd { it.copy(to = (it.to - 30 + 1440) % 1440) } }, onInc = { upd { it.copy(to = (it.to + 30) % 1440) } }) }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in 0..6) SvChip(dayNames[i], (cfg.days shr i) and 1 == 1, Modifier.weight(1f)) { upd { it.copy(days = it.days xor (1 shl i)) } }
                }
            }
        }
        item { Head(stringResource(R.string.sv_g_look)) }
        item { Item(stringResource(R.string.sv_dim), if (cfg.dim == 0) off else "${cfg.dim}%", stringResource(R.string.sv_dim_sub), onDec = { upd { it.copy(dim = (it.dim - 10).coerceAtLeast(0)) } }, onInc = { upd { it.copy(dim = (it.dim + 10).coerceAtMost(90)) } }) }
        item { Item(stringResource(R.string.sv_burn), if (cfg.burn) on else off, stringResource(R.string.sv_burn_sub), onClick = { upd { it.copy(burn = !it.burn) } }) }
        item { Item(stringResource(R.string.sv_clock_only), if (cfg.clockOnly) on else off, onClick = { upd { it.copy(clockOnly = !it.clockOnly) } }) }
        item { Item(stringResource(R.string.sv_size), "${cfg.size}%", onDec = { upd { it.copy(size = (it.size - 10).coerceAtLeast(60)) } }, onInc = { upd { it.copy(size = (it.size + 10).coerceAtMost(140)) } }) }
        item {
            val i = bgs.indexOf(cfg.bg).let { if (it < 0) 0 else it }
            Item(stringResource(R.string.sv_bg), stringResource(bgNames[i]), onDec = { upd { it.copy(bg = bgs[(i + bgs.size - 1) % bgs.size]) } }, onInc = { upd { it.copy(bg = bgs[(i + 1) % bgs.size]) } })
        }
        item { Item(stringResource(R.string.sv_look_note), sub = stringResource(R.string.sv_look_note_sub)) }
        item { Head(stringResource(R.string.sv_g_show)) }
        item { Item(stringResource(R.string.sv_show_now), sub = stringResource(R.string.sv_show_now_sub), onClick = { runCatching { SaverService.start(ctx, SaverService.ACTION_SHOW, "кнопка") }.onFailure { toast(ctx, R.string.sv_start_fail) } }) }
        item { Item(stringResource(R.string.sv_dream_on), sub = stringResource(R.string.sv_dream_on_sub), onClick = { enableDream(ctx) }) }
        item { Item(stringResource(R.string.sv_dream_show), sub = stringResource(R.string.sv_dream_show_sub), onClick = { showDream(ctx) }) }
        item { Spacer(Modifier.size(16.dp)) }
    }
}

private const val DREAM_COMP = "app.tvapps/app.tvapps.ClockDream"

/** Выбирает нашу заставку системной (на ТВ без экрана «Заставка» — через защищённые настройки; исходные значения запоминаются). */
private fun enableDream(ctx: Context) {
    val sp = ctx.getSharedPreferences("tvapps_saver", Context.MODE_PRIVATE)
    fun get(k: String) = runCatching { Settings.Secure.getString(ctx.contentResolver, k) }.getOrNull()
    if (!sp.contains("dream_orig")) sp.edit().putString("dream_orig", get("screensaver_components") ?: "").putString("dream_orig_en", get("screensaver_enabled") ?: "").apply()
    if (SaverPerm.secure(ctx)) {
        runCatching {
            Settings.Secure.putString(ctx.contentResolver, "screensaver_components", DREAM_COMP)
            Settings.Secure.putInt(ctx.contentResolver, "screensaver_enabled", 1)
        }.onSuccess { SaverLog.log("системная заставка выбрана"); toast(ctx, R.string.sv_dream_done); return }
    }
    if (SaverPerm.control()) {
        runCatching { runBlocking { Adb.shell("settings put secure screensaver_components $DREAM_COMP; settings put secure screensaver_enabled 1", 12_000) } }
        SaverLog.log("системная заставка выбрана через ADB/root"); toast(ctx, R.string.sv_dream_done); return
    }
    val ok = runCatching { ctx.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    toast(ctx, if (ok) R.string.sv_dream_manual else R.string.sv_dream_none)
}

private fun showDream(ctx: Context) {
    val ok = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.android.systemui", "com.android.systemui.Somnambulator")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!ok && SaverPerm.control()) runCatching { runBlocking { Adb.shell("am start -n com.android.systemui/.Somnambulator", 8_000) } }
    else if (!ok) toast(ctx, R.string.sv_dream_none)
}

// ------------------------------------------------------------------ список приложений

@Composable
private fun AppsScreen(ctx: Context) {
    var cfg by remember { mutableStateOf(SaverPrefs.get(ctx)) }
    fun upd(f: (SaverCfg) -> SaverCfg) { SaverPrefs.set(ctx, f); cfg = SaverPrefs.get(ctx) }
    var q by remember { mutableStateOf("") }
    val all = remember {
        val pm = ctx.packageManager
        val seen = HashSet<String>()
        val l = ArrayList<Triple<String, String, android.graphics.drawable.Drawable?>>()
        for (cat in listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            for (ri in runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0) }.getOrDefault(emptyList())) {
                val p = ri.activityInfo.packageName
                if (p != ctx.packageName && seen.add(p)) l += Triple(p, ri.loadLabel(pm).toString(), null)
            }
        }
        l.sortedBy { it.second.lowercase() }
    }
    val list = all.filter { q.isBlank() || it.second.contains(q, true) || it.first.contains(q, true) }
    BarLazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                SvChip(stringResource(R.string.sv_apps_default), false) { upd { it.copy(apps = SaverPrefs.DEFAULT_APPS) } }
                SvChip(stringResource(R.string.sv_apps_none), false) { upd { it.copy(apps = emptySet()) } }
                Text(stringResource(R.string.sv_apps_count, cfg.apps.size), color = SUB, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
        item {
            Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(TILE).padding(horizontal = 12.dp, vertical = 9.dp)) {
                BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = TXT, fontSize = 15.sp), cursorBrush = SolidColor(ACC), modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner -> if (q.isEmpty()) Text(stringResource(R.string.sv_apps_search), color = SUB, fontSize = 15.sp); inner() })
            }
        }
        items(list, key = { it.first }) { (pkg, label, _) ->
            val on = pkg in cfg.apps
            val icon = remember(pkg) { runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(56, 56).asImageBitmap() }.getOrNull() }
            var foc by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape(10.dp)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(shape).background(if (foc) TILE_F else TILE).border(if (foc) 2.dp else 0.dp, if (foc) ACC else Color.Transparent, shape)
                    .onFocusChanged { foc = it.isFocused }.clickable { upd { c -> c.copy(apps = if (on) c.apps - pkg else c.apps + pkg) } }.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) Image(icon, null, Modifier.size(30.dp)) else Spacer(Modifier.size(30.dp))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(label, color = TXT, fontSize = 15.sp, maxLines = 1)
                    Text(pkg, color = SUB, fontSize = 11.sp, maxLines = 1)
                }
                Text(if (on) "✓" else "·", color = if (on) OKC else SUB, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ------------------------------------------------------------------ мастер настройки

private class Step(val key: String, val ok: (Context) -> Boolean?, val grant: String?, val open: (Context) -> Boolean)

private fun openIntent(c: Context, i: Intent): Boolean = runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)

private val OEM_AUTOSTART = listOf(
    "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
    "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
    "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
    "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
    "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
    "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
    "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
)

private val STEPS = listOf(
    Step("secure", { SaverPerm.secure(it) }, "secure") { false },
    Step("overlay", { SaverPerm.overlay(it) }, "overlay") { openIntent(it, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + it.packageName))) },
    Step("access", { SaverPerm.accessListed(it) && SaverBus.sensors.getValue("access").alive }, null) { openIntent(it, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
    Step("usage", { SaverPerm.usage(it) }, "usage") { openIntent(it, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
    Step("battery", { SaverPerm.battery(it) }, "battery") { openIntent(it, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
    Step("notif", { SaverPerm.notif(it) }, "notif") { c ->
        if (Build.VERSION.SDK_INT >= 26) openIntent(c, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName))
        else openIntent(c, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName)))
    },
    Step("autostart", { null }, null) { c ->
        val pm = c.packageManager
        val hit = OEM_AUTOSTART.firstOrNull { (p, a) -> runCatching { pm.getActivityInfo(ComponentName(p, a), 0); true }.getOrDefault(false) }
        if (hit != null && openIntent(c, Intent().setComponent(ComponentName(hit.first, hit.second)))) true
        else openIntent(c, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName)))
    },
    Step("service", { SaverService.running }, null) { false },
)

private fun stepText(key: String, part: String): Int = when (part + key) {
    "tsecure" -> R.string.sv_w_secure_t; "dsecure" -> R.string.sv_w_secure_d
    "toverlay" -> R.string.sv_w_overlay_t; "doverlay" -> R.string.sv_w_overlay_d
    "taccess" -> R.string.sv_w_access_t; "daccess" -> R.string.sv_w_access_d
    "tusage" -> R.string.sv_w_usage_t; "dusage" -> R.string.sv_w_usage_d
    "tbattery" -> R.string.sv_w_battery_t; "dbattery" -> R.string.sv_w_battery_d
    "tnotif" -> R.string.sv_w_notif_t; "dnotif" -> R.string.sv_w_notif_d
    "tautostart" -> R.string.sv_w_autostart_t; "dautostart" -> R.string.sv_w_autostart_d
    "tservice" -> R.string.sv_w_service_t; else -> R.string.sv_w_service_d
}

@Composable
private fun WizardScreen(ctx: Context, go: (String) -> Unit) {
    var idx by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var manualDone by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    @Suppress("UNUSED_EXPRESSION") tick
    val s = STEPS[idx]
    val st = s.ok(ctx)
    val done = st == true || (st == null && s.key in manualDone)
    BarLazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                STEPS.forEachIndexed { i, x ->
                    val r = x.ok(ctx)
                    val d = r == true || (r == null && x.key in manualDone)
                    SvChip((if (d) "✓ " else if (r == null) "• " else "✗ ") + (i + 1), i == idx, Modifier.weight(1f)) { idx = i }
                }
            }
        }
        item {
            Item(stringResource(R.string.sv_w_step, idx + 1, STEPS.size) + ": " + stringResource(stepText(s.key, "t")),
                if (done) "✓" else if (st == null) "•" else "✗", valueColor = if (done) OKC else if (st == null) WARNC else BADC, sub = stringResource(stepText(s.key, "d")))
        }
        if (!done && s.grant != null) item {
            Item(stringResource(R.string.sv_w_auto), sub = stringResource(R.string.sv_w_auto_sub), autoFocus = true, onClick = { Thread { if (!SaverPerm.grant(ctx, s.grant)) Handler(Looper.getMainLooper()).post { toast(ctx, R.string.sv_w_auto_fail) } }.start() })
        }
        if (s.key == "access" && !done) item {
            Item(stringResource(R.string.sv_w_auto), sub = stringResource(R.string.sv_w_access_auto), onClick = {
                Thread { SaverPrefs.set(ctx) { it.copy(enabled = true) }; SaverGuard.ensureAccess(ctx, "мастер") }.start()
            })
        }
        if (s.key == "service" && !done) item {
            Item(stringResource(R.string.sv_w_start), autoFocus = true, onClick = {
                SaverPrefs.set(ctx) { it.copy(enabled = true) }
                SaverGuard.startIfEnabled(ctx, "мастер")
            })
        }
        if (!done && s.key != "secure" && s.key != "service") item {
            Item(stringResource(R.string.sv_w_open), sub = stringResource(R.string.sv_w_open_sub), autoFocus = s.grant == null, onClick = { if (!s.open(ctx)) toast(ctx, R.string.sv_w_open_fail) })
        }
        if (st == null && !done) item { Item(stringResource(R.string.sv_w_mark), onClick = { manualDone = manualDone + s.key }) }
        item {
            Item(if (idx < STEPS.size - 1) stringResource(R.string.sv_w_next) else stringResource(R.string.sv_diag),
                autoFocus = done, onClick = { if (idx < STEPS.size - 1) idx++ else go("diag") })
        }
        if (idx > 0) item { Item(stringResource(R.string.sv_w_prev), onClick = { idx-- }) }
    }
}

// ------------------------------------------------------------------ диагностика

private class DRow(val ok: Boolean?, val title: String, val detail: String)

@Composable
private fun sensorName(id: String) = stringResource(when (id) {
    "overlay" -> R.string.sv_s_overlay; "irq" -> R.string.sv_s_irq; "power" -> R.string.sv_s_power; "root" -> R.string.sv_s_root; "usage" -> R.string.sv_s_usage; else -> R.string.sv_s_access
})

@Composable
private fun sensorHint(id: String, s: SaverSensor): String = when {
    s.alive -> ""
    id == "overlay" -> stringResource(R.string.sv_h_overlay)
    id == "irq" -> stringResource(if (s.note == "no_lines") R.string.sv_h_irq_lines else R.string.sv_h_irq)
    id == "power" -> stringResource(R.string.sv_h_power)
    id == "root" -> stringResource(R.string.sv_h_root)
    id == "usage" -> stringResource(R.string.sv_h_usage)
    else -> stringResource(R.string.sv_h_access)
}

@Composable
private fun DiagScreen(ctx: Context, go: (String) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    var testAt by remember { mutableStateOf(0L) }
    var seen by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(700); tick++
            if (testAt != 0L && SystemClock.elapsedRealtime() - testAt > 10_000) {
                seen = SaverBus.sensors.filter { it.value.last >= testAt }.keys
                testAt = 0L
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") tick
    val now = SystemClock.elapsedRealtime()
    val ago = { t: Long -> if (t == 0L) ctx.getString(R.string.sv_never) else ctx.getString(R.string.sv_ago, ((now - t) / 1000).toInt()) }
    val ov = SaverPerm.overlay(ctx)
    BarLazyColumn(Modifier.fillMaxSize()) {
        item {
            val shown = if (SaverBus.shown) stringResource(R.string.sv_now_shown, SaverBus.shownBy) else stringResource(R.string.sv_now_hidden, SaverBus.reason.ifBlank { "—" })
            Item(stringResource(R.string.sv_state), sub = shown)
        }
        item { Head(stringResource(R.string.sv_g_ways)) }
        item {
            Item(stringResource(R.string.sv_d_overlay), if (ov) "✓" else "✗", if (ov) null else stringResource(R.string.sv_hint_overlay), if (ov) OKC else BADC,
                onClick = { runCatching { SaverService.start(ctx, SaverService.ACTION_SHOW, "проверка", 1) } })
        }
        item {
            val w = if (Build.VERSION.SDK_INT >= 29 && !ov) null else true
            Item(stringResource(R.string.sv_d_window), if (w == true) "✓" else "!", if (w == true) null else stringResource(R.string.sv_d_window_warn), if (w == true) OKC else WARNC,
                onClick = { runCatching { SaverService.start(ctx, SaverService.ACTION_SHOW, "проверка", 2) } })
        }
        item { Head(stringResource(R.string.sv_g_sensors)) }
        for (id in SaverBus.sensors.keys) item(key = id) {
            val s = SaverBus.sensors.getValue(id)
            val seenNow = seen?.contains(id)
            val line = ago(s.last).let { a -> stringResource(R.string.sv_s_line, a, s.count, s.restored) } +
                (if (seen != null) "  ·  " + stringResource(if (seenNow == true) R.string.sv_saw else R.string.sv_not_saw) else "")
            val hint = sensorHint(id, s)
            Item(sensorName(id), if (s.alive) "✓" else "✗", line + (if (hint.isNotEmpty()) "\n" + hint else ""), if (s.alive) OKC else BADC)
        }
        item {
            val running = testAt != 0L
            Item(
                stringResource(R.string.sv_test), if (running) stringResource(R.string.sv_test_left, (10 - (now - testAt) / 1000).toInt().coerceAtLeast(0)) else "",
                stringResource(R.string.sv_test_sub), onClick = { seen = null; testAt = SystemClock.elapsedRealtime() },
            )
        }
        item { Head(stringResource(R.string.sv_g_guard)) }
        item {
            val r = SaverService.running
            Item(stringResource(R.string.sv_d_service), if (r) "✓" else "✗", if (r) null else stringResource(R.string.sv_d_service_no), if (r) OKC else BADC,
                onClick = { SaverGuard.startIfEnabled(ctx, "диагностика") })
        }
        item {
            val a = SaverGuard.alarmArmed(ctx); val j = SaverGuard.jobArmed(ctx)
            Item(stringResource(R.string.sv_d_watch), if (a && j) "✓" else "!", stringResource(R.string.sv_d_watch_sub, if (a) "✓" else "✗", if (j) "✓" else "✗"), if (a && j) OKC else WARNC)
        }
        item { val b = SaverPerm.battery(ctx); Item(stringResource(R.string.sv_d_battery), if (b) "✓" else "✗", if (b) null else stringResource(R.string.sv_d_battery_no), if (b) OKC else WARNC) }
        item { val b = SaverPerm.secure(ctx); Item(stringResource(R.string.sv_d_secure), if (b) "✓" else "✗", if (b) null else stringResource(R.string.sv_d_secure_no), if (b) OKC else WARNC) }
        item { Item(stringResource(R.string.sv_d_control), if (SaverPerm.control()) "✓" else "✗", stringResource(R.string.sv_d_control_sub), if (SaverPerm.control()) OKC else WARNC) }
        item { Head(stringResource(R.string.sv_log)) }
        item { Item(stringResource(R.string.sv_log), sub = stringResource(R.string.sv_log_sub), onClick = { go("log") }) }
        item { Item(stringResource(R.string.sv_log_copy), onClick = { copyLog(ctx) }) }
        item { Item(stringResource(R.string.sv_wizard), onClick = { go("wizard") }) }
        item { Spacer(Modifier.size(16.dp)) }
    }
}

private fun header(ctx: Context): String {
    val sb = StringBuilder()
    sb.append("AppMenu saver; SDK ").append(Build.VERSION.SDK_INT).append("; ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("; ABI ").append(Build.SUPPORTED_ABIS.joinToString("/")).append('\n')
    sb.append("overlay=").append(SaverPerm.overlay(ctx)).append(" usage=").append(SaverPerm.usage(ctx)).append(" battery=").append(SaverPerm.battery(ctx))
        .append(" secure=").append(SaverPerm.secure(ctx)).append(" control=").append(SaverPerm.control()).append(" service=").append(SaverService.running).append('\n')
    val now = SystemClock.elapsedRealtime()
    for ((id, s) in SaverBus.sensors) sb.append(id).append(": alive=").append(s.alive).append(" count=").append(s.count).append(" restored=").append(s.restored)
        .append(" last=").append(if (s.last == 0L) "never" else "${(now - s.last) / 1000}s ago").append(" note=").append(s.note).append('\n')
    return sb.toString()
}

private fun copyLog(ctx: Context) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("saver log", header(ctx) + "\n" + SaverLog.text(300)))
    toast(ctx, R.string.sv_log_copied)
}

@Composable
private fun LogScreen(ctx: Context) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    @Suppress("UNUSED_EXPRESSION") tick
    val text = SaverLog.text(120)
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            SvChip(stringResource(R.string.sv_log_copy), false) { copyLog(ctx) }
        }
        Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp)).background(TILE).barScroll(Int.MAX_VALUE).padding(10.dp)) {
            Text(header(ctx) + "\n" + text.ifBlank { "—" }, color = TXT, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}
