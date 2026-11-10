@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.tvapps

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Обновление самой программы (правила 14 и 17 из Общее\ПРАВИЛА_ПРОГРАММ.md): выпуски GitHub drugoj00/app-menu, файл проверяется по SHA-256 из описания,
 * установка через Updates.install (подпись, процессор, root/ADB или системный установщик).
 * Окно «Обновить / Позже» появляется само не чаще раза в сутки и ОДИН раз для каждой версии: закрыл (Позже, «Назад», касание вне окна) —
 * эта версия сама больше не предлагается. Кнопка «Проверить обновления» в настройках показывает доступную версию всегда.
 */
object SelfUpd {
    const val PKG = "app.tvapps"
    const val RELEASES = "https://github.com/drugoj00/app-menu/releases/latest"
    private const val DAY_MS = 24 * 3600_000L

    sealed interface St {
        data object Checking : St
        data class Found(val upd: AppUpd, val cand: UpdCand) : St
        data class Latest(val version: String) : St
        data object Failed : St
        data class Installing(val status: String) : St
        data class Result(val text: String) : St
    }

    /** Состояние окна (общее для «Меню» и «Дома»: один процесс); null — окно закрыто. */
    var state by mutableStateOf<St?>(null)
        private set
    @Volatile private var busy = false

    private suspend fun fetch(ctx: Context): Pair<AppUpd, UpdCand?> {
        val r = Updates.check(ctx, PKG, force = true, only = setOf("github"))
        val best = r.newer.filter { !it.manual }.maxWithOrNull { a, b -> Updates.compareVersions(a.versionName, b.versionName) }
        return r to best
    }

    /** Кнопка «Проверить обновления»: показывает результат всегда. */
    fun checkNow(ctx: Context, scope: CoroutineScope) {
        if (busy) return
        busy = true
        state = St.Checking
        scope.launch(Dispatchers.Default) {
            try {
                val (r, best) = fetch(ctx)
                state = when {
                    best != null -> St.Found(r, best)
                    r.cands.isEmpty() && r.errors.isNotEmpty() -> St.Failed
                    else -> St.Latest(r.instName)
                }
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { state = St.Failed } finally { busy = false }
        }
    }

    /** Фоновая проверка при открытии программы: не чаще раза в сутки, окно только для версии, которую ещё не закрывали. */
    suspend fun autoCheck(ctx: Context) {
        if (!Cfg.selfUpd || busy || state != null) return
        val now = System.currentTimeMillis()
        if (now - Cfg.selfUpdLast() < DAY_MS) return
        busy = true
        try {
            val (r, best) = fetch(ctx)
            // отметка времени ставится только после ответа сети: при ошибке следующая проверка не ждёт сутки
            if (r.errors.isEmpty() || r.cands.isNotEmpty()) Cfg.setSelfUpdLast(now)
            if (best != null && Cfg.selfUpdDismissed() != best.versionName && state == null) state = St.Found(r, best)
        } catch (e: CancellationException) { throw e } catch (_: Throwable) { } finally { busy = false }
    }

    /** Закрытие окна любым способом: найденная версия больше сама не предлагается. */
    fun dismiss() {
        (state as? St.Found)?.let { Cfg.setSelfUpdDismissed(it.cand.versionName) }
        state = null
    }

    fun install(ctx: Context, scope: CoroutineScope, f: St.Found) {
        Cfg.setSelfUpdDismissed(f.cand.versionName)
        state = St.Installing("")
        scope.launch(Dispatchers.Default) {
            val res = try {
                updResultText(ctx, Updates.install(ctx, f.upd, f.cand) { s -> state = St.Installing(s) })
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { ctx.getString(R.string.ver_failed) + ": " + (e.message ?: "") }
            state = St.Result(res)
        }
    }
}

/** Окно самообновления: рисуется там, где поставлен [SelfUpdHost] (главное меню и «Дом»), поэтому видно и поверх настроек. */
@Composable
fun SelfUpdHost() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { SelfUpd.autoCheck(ctx.applicationContext) }
    val st = SelfUpd.state ?: return
    SelfUpdDialog(st)
}

@Composable
private fun SelfUpdDialog(st: SelfUpd.St) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(st::class) { runCatching { first.requestFocus() } }
    HDialog(onDismissRequest = { if (st !is SelfUpd.St.Installing) SelfUpd.dismiss() }) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.self_upd_title), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            when (st) {
                SelfUpd.St.Checking -> Text(stringResource(R.string.self_upd_checking), color = t.accent, fontSize = 14.sp)
                is SelfUpd.St.Found -> {
                    Text(stringResource(R.string.self_upd_found, st.cand.versionName, st.upd.instName), color = t.text, fontSize = 15.sp)
                    if (st.cand.size > 0) Text(stringResource(R.string.self_upd_size, "%.1f".format(java.util.Locale.US, st.cand.size / 1048576.0)), color = t.hint, fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip(stringResource(R.string.self_upd_install), true, Modifier.focusRequester(first)) { SelfUpd.install(ctx, scope, st) }
                        Chip(stringResource(R.string.self_upd_later), false) { SelfUpd.dismiss() }
                        Chip(stringResource(R.string.self_upd_release), false) { openUrl(ctx, SelfUpd.RELEASES) }
                    }
                }
                is SelfUpd.St.Latest -> {
                    Text(stringResource(R.string.self_upd_latest, st.version), color = t.text, fontSize = 15.sp)
                    Chip(stringResource(R.string.close), true, Modifier.focusRequester(first)) { SelfUpd.dismiss() }
                }
                SelfUpd.St.Failed -> {
                    Text(stringResource(R.string.self_upd_failed), color = t.text, fontSize = 15.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip(stringResource(R.string.self_upd_retry), true, Modifier.focusRequester(first)) { SelfUpd.checkNow(ctx, scope) }
                        Chip(stringResource(R.string.close), false) { SelfUpd.dismiss() }
                    }
                }
                is SelfUpd.St.Installing -> {
                    Text(st.status.ifBlank { stringResource(R.string.self_upd_installing) }, color = t.accent, fontSize = 14.sp)
                    Text(stringResource(R.string.self_upd_wait), color = t.hint, fontSize = 12.sp)
                }
                is SelfUpd.St.Result -> {
                    Text(st.text, color = t.text, fontSize = 15.sp, modifier = Modifier.padding(vertical = 2.dp))
                    Chip(stringResource(R.string.close), true, Modifier.focusRequester(first)) { SelfUpd.dismiss() }
                }
            }
        }
    }
}
