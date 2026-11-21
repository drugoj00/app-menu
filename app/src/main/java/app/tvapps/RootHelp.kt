package app.tvapps

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Подсказка, если root не выдан этому приложению (14.1). В новых программах управления root приложение получает root только через список
 * внутри самой программы (вкладка «Суперпользователь»), окно запроса при обращении не появляется: su молча отказывает.
 * Окно рисуется там, где поставлен [RootHelpHost] (внутри SelfUpdHost: главное меню и «Дом»).
 */
object RootHelp {
    /** Кто вызвал проверку root: фон (ничего не показывать), явное действие человека (всегда показать) или автоматическая попытка (показать, только если su есть, но отказал). */
    const val NO = 0
    const val EXPLICIT = 1
    const val AUTO = 2

    /** Окно показано сейчас. */
    var show by mutableStateOf(false)

    /** Известные программы управления root (пакеты). */
    val MANAGERS = listOf(
        "com.topjohnwu.magisk", "io.github.huskydg.magisk", "io.github.vvb2060.magisk", "com.fox2code.mmm",
        "me.weishu.kernelsu", "com.rifsxd.ksunext", "com.sukisu.ultra", "me.bmax.apatch", "me.garfieldhan.apatchnext",
        "eu.chainfire.supersu", "com.koushikdutta.superuser", "com.thirdparty.superuser", "com.noshufou.android.su", "me.phh.superuser",
    )

    /** Названия, по которым ищется программа с переименованным пакетом (внутренняя сверка, пользователю не показывается). */
    private val LABELS = listOf("magisk", "kitsune", "kernelsu", "ksunext", "apatch", "supersu", "superuser")

    /** Окно подсказки (одно). Причина отказа идёт одной строкой в журнал TVApps. */
    fun offer(why: String) {
        Log.w("TVApps", "root не выдан: $why")
        show = true
    }

    /** Интент запуска программы управления root или null, если ни одной не нашли. */
    fun managerIntent(ctx: Context): Intent? {
        val pm = ctx.packageManager
        for (p in MANAGERS) {
            val i = runCatching { pm.getLaunchIntentForPackage(p) }.getOrNull()
            if (i != null) return i
        }
        // пакет мог быть переименован («скрыть приложение»): ищем среди запускаемых по названию
        runCatching {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            for (ri in pm.queryIntentActivities(main, 0)) {
                val label = ri.loadLabel(pm).toString().lowercase()
                if (LABELS.any { label.contains(it) }) return pm.getLaunchIntentForPackage(ri.activityInfo.packageName)
            }
        }
        return null
    }

    /** Открывает программу управления root. false: не найдена или не открылась. */
    fun openManager(ctx: Context): Boolean {
        val i = managerIntent(ctx) ?: return false
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }
}

/** Окно «Нет root для этого приложения»: кнопки «Открыть программу управления root», «Повторить», «Закрыть». */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun RootHelpHost() {
    if (!RootHelp.show) return
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val notFound = stringResource(R.string.root_help_nomgr)
    val still = stringResource(R.string.root_help_still)
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    HDialog(onDismissRequest = { if (!busy) RootHelp.show = false }) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(18.dp)
                // в ландшафте телефона окно выше экрана: высота ограничена, содержимое прокручивается (кнопки остаются доступными)
                .heightIn(max = with(androidx.compose.ui.platform.LocalDensity.current) { (LocalContext.current.resources.displayMetrics.heightPixels * 0.86f).toDp() }).barScroll(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.root_help_title), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.root_help_body, stringResource(R.string.app_name)), color = t.text, fontSize = 14.sp)
            if (busy) Text(stringResource(R.string.working), color = t.accent, fontSize = 14.sp)
            else if (msg.isNotEmpty()) Text(msg, color = t.accent, fontSize = 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.root_help_open), false, Modifier.focusRequester(first)) {
                    if (busy) return@Chip
                    msg = if (RootHelp.openManager(ctx)) "" else notFound
                }
                Chip(stringResource(R.string.root_help_retry), false) {
                    if (busy) return@Chip
                    scope.launch {
                        busy = true; msg = ""
                        val ok = Adb.tryRoot(RootHelp.NO)
                        busy = false
                        if (ok) {
                            Conn.refresh()
                            RootHelp.show = false
                            Toast.makeText(ctx, ctx.getString(R.string.adb_ok), Toast.LENGTH_SHORT).show()
                        } else msg = still
                    }
                }
                Chip(stringResource(R.string.close), false) { if (!busy) RootHelp.show = false }
            }
        }
    }
}
