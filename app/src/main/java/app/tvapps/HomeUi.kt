@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.tvapps

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Железный режим «Дом» в настройках: отключить родной экран (с подтверждением) и большая кнопка возврата. */
@Composable
internal fun HomeHardBlock() {
    val t = LocalTh.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var armedAt by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    val risky = remember { grabbers(ctx) }
    if (risky.isNotEmpty()) Text(stringResource(R.string.home_acc_warn, risky.joinToString(", ")), color = t.accent, fontSize = 13.sp)
    Text(stringResource(R.string.home_hard_hint), color = t.hint, fontSize = 12.sp)
    if (Cfg.hardOff.isNotEmpty()) {
        Text(stringResource(R.string.home_hard_state, Cfg.hardOff.joinToString(", ")), color = t.accent, fontSize = 13.sp)
        Text(stringResource(R.string.home_hard_manual), color = t.hint, fontSize = 12.sp)
    }
    if (HomeControl.guardTripped(ctx)) Text(stringResource(R.string.home_hard_guard), color = t.accent, fontSize = 13.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(stringResource(R.string.home_hard_btn), Cfg.hardOff.isNotEmpty()) {
            if (busy) return@Chip
            val now = android.os.SystemClock.elapsedRealtime()
            val others = HomeControl.otherHomePackages(ctx)
            when {
                !Adb.isConnected -> Toast.makeText(ctx, ctx.getString(R.string.home_hard_no_adb), Toast.LENGTH_LONG).show()
                others.isEmpty() -> Toast.makeText(ctx, ctx.getString(R.string.home_hard_no_others), Toast.LENGTH_LONG).show()
                now - armedAt > 15_000 -> {
                    armedAt = now
                    Toast.makeText(ctx, ctx.getString(R.string.home_hard_confirm, others.joinToString(", ")), Toast.LENGTH_LONG).show()
                }
                else -> {
                    armedAt = 0L
                    busy = true
                    scope.launch {
                        val r = HomeControl.hardOn(ctx)
                        busy = false
                        val msg = if (r.isEmpty()) ctx.getString(R.string.home_hard_on_ok) else ctx.getString(R.string.home_hard_fail, r)
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        Chip(stringResource(R.string.home_hard_back), false) {
            if (busy) return@Chip
            busy = true
            scope.launch {
                val r = HomeControl.hardOff(ctx)
                busy = false
                val msg = when (r) {
                    "" -> ctx.getString(R.string.home_hard_off_ok)
                    "no_adb" -> ctx.getString(R.string.home_hard_no_adb)
                    else -> ctx.getString(R.string.home_hard_partial)
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** Службы спецвозможностей, которые известны перехватом кнопок (в том числе «Домой») и могут мешать главному экрану. */
internal fun grabbers(ctx: android.content.Context): List<String> {
    val raw = runCatching { android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services") }.getOrNull().orEmpty()
    val known = listOf("tvquickactions" to "tvQuickActions", "tvvoicesearch" to "ATV voice search", "app.click" to "Pulsar", "keymapper" to "Key Mapper", "buttonmapper" to "Button Mapper", "remap" to "Remapper")
    return raw.split(':').filter { it.isNotBlank() }.mapNotNull { svc -> known.firstOrNull { svc.lowercase().contains(it.first) }?.second }.distinct()
}

/** Включение и выключение всего модуля «Дом»: при выключении остаётся только меню приложений. */
internal object HomeModule {
    /** Возвращает пустую строку при успехе. Если родные экраны отключены, а вернуть их нечем, модуль остаётся включённым. */
    suspend fun disable(ctx: android.content.Context): String {
        if (Cfg.hardOff.isNotEmpty()) {
            val r = HomeControl.hardOff(ctx)
            if (r.isNotEmpty()) return r
        }
        HomeControl.setAlias(ctx, false)
        Cfg.changeHomeModule(false)
        return ""
    }

    fun enable() = Cfg.changeHomeModule(true)
}
