@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.tvapps

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** Снимок состояния сетевого ADB: что включено сейчас и что можно сделать. */
class AdbNetSnap(val usb: Boolean?, val tcpPort: String, val ip: String, val reach: Boolean, val root: Boolean)

/** Проверки и включение ADB по сети (порт 5555) на ТВ-боксах; без root и без USB-подключения к компьютеру программа включить его не может. */
object AdbNet {
    private fun prop(name: String): String = runCatching {
        Runtime.getRuntime().exec(arrayOf("getprop", name)).inputStream.bufferedReader().use { it.readText() }.trim()
    }.getOrDefault("")

    /** IPv4-адрес в локальной сети (Wi-Fi или кабель), без адресов обратной петли. */
    fun localIp(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }?.hostAddress
    }.getOrNull().orEmpty()

    private fun reach(ip: String, port: Int = 5555): Boolean = ip.isNotEmpty() && runCatching {
        Socket().use { it.connect(InetSocketAddress(ip, port), 900); true }
    }.getOrDefault(false)

    suspend fun probe(ctx: Context): AdbNetSnap = withContext(Dispatchers.IO) {
        val usb = runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrNull()
        val port = prop("service.adb.tcp.port").ifEmpty { prop("persist.adb.tcp.port") }
        val ip = localIp()
        AdbNetSnap(usb, port, ip, reach(ip), Adb.isRoot)
    }

    /** Поднимает adbd на порту 5555 через root. Возвращает true, если порт после этого отвечает. */
    suspend fun raiseRoot(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val had = Adb.isRoot
        if (!had && !Adb.tryRoot()) return@withContext false
        runCatching { Adb.shell("setprop service.adb.tcp.port 5555; setprop persist.adb.tcp.port 5555; stop adbd; sleep 1; start adbd", 25_000) }
        delay(2500)
        val ok = reach(localIp())
        // прежний режим программы (root вместо ADB включается настройкой): временно взятые права root не оставляем
        if (!had && !Cfg.useRoot) Adb.disableRoot()
        Conn.refresh()
        ok
    }
}

private fun copyPlain(ctx: Context, text: String) {
    runCatching {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("adb", text))
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }
}

/** Мастер «Включить ADB по сети» для ТВ-боксов (Android 7.x и выше): проверка состояния, переход в «Для разработчиков», root-кнопка и готовая команда для компьютера. */
@Composable
fun AdbNetWizardDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    var snap by remember { mutableStateOf<AdbNetSnap?>(null) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { snap = AdbNet.probe(ctx) }
    val yes = stringResource(R.string.adbn_yes)
    val no = stringResource(R.string.adbn_no)
    val unk = stringResource(R.string.adbn_unknown)
    fun yn(v: Boolean?) = when (v) { true -> yes; false -> no; null -> unk }

    HDialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().padding(24.dp).clip(RoundedCornerShape(20.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(18.dp).barScroll(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.adbn_title), color = t.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            val s = snap
            if (s != null) {
                Text(stringResource(R.string.adbn_usb) + ": " + yn(s.usb), color = t.text, fontSize = 15.sp)
                Text(stringResource(R.string.adbn_port) + ": " + s.tcpPort.ifEmpty { unk }, color = t.text, fontSize = 15.sp)
                Text(stringResource(R.string.adbn_ip) + ": " + s.ip.ifEmpty { unk }, color = t.text, fontSize = 15.sp)
                Text(stringResource(R.string.adbn_reach) + ": " + yn(s.reach), color = if (s.reach) t.accent else t.text, fontSize = 15.sp)
                Text(stringResource(R.string.adbn_root) + ": " + yn(if (s.root) true else null), color = t.text, fontSize = 15.sp)
                if (!s.reach) Text(stringResource(R.string.adbn_noroot), color = t.hint, fontSize = 12.sp)
                if (s.ip.isNotEmpty()) Text(stringResource(R.string.adbn_cmd, s.ip), color = t.hint, fontSize = 12.sp)
            } else Text(stringResource(R.string.adbn_working), color = t.sub, fontSize = 14.sp)
            if (msg.isNotEmpty()) Text(msg, color = t.accent, fontSize = 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.adbn_dev), false) {
                    val ok = runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
                    if (!ok) {
                        msg = ctx.getString(R.string.adbn_dev_fail)
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
                Chip(stringResource(R.string.adbn_root_btn), false) {
                    if (busy) return@Chip
                    scope.launch {
                        busy = true; msg = ctx.getString(R.string.adbn_working)
                        val ok = AdbNet.raiseRoot(ctx)
                        snap = AdbNet.probe(ctx)
                        msg = if (ok) ctx.getString(R.string.adbn_ok, snap?.ip.orEmpty()) else ctx.getString(R.string.adbn_fail)
                        busy = false
                    }
                }
                Chip(stringResource(R.string.adbn_check), false) {
                    if (busy) return@Chip
                    scope.launch { busy = true; snap = AdbNet.probe(ctx); msg = ""; busy = false }
                }
                Chip(stringResource(R.string.adbn_copy), false) {
                    val ip = snap?.ip.orEmpty()
                    if (ip.isNotEmpty()) copyPlain(ctx, "adb connect $ip:5555")
                }
                Chip(stringResource(R.string.adbn_close), true) { if (!busy) onClose() }
            }
        }
    }
}
