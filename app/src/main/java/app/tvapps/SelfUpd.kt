@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.tvapps

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.animateScrollBy
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

    /** Ненавязчивая плашка «Вышла X. Обновить?» (после проверки при запуске); null: не показывается. */
    var banner by mutableStateOf<St.Found?>(null)
        private set
    /** Окно «Что нового» для этого выпуска; null: закрыто. Открывается и из плашки, и из окон «Обновления». */
    var notes by mutableStateOf<UpdCand?>(null)

    /** «Позже» и крестик: плашка скрыта до следующей проверки (раз в сутки) или до новой версии. */
    fun bannerLater() { banner = null; Cfg.setSelfUpdPending("") }
    fun bannerInstall(ctx: Context, scope: CoroutineScope) { val f = banner ?: return; banner = null; Cfg.setSelfUpdPending(""); install(ctx, scope, f) }

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
        if (!Cfg.selfUpd || busy || state != null || banner != null) return
        val now = System.currentTimeMillis()
        // раз в сутки; плюс повтор при запуске, пока плашка не закрыта ответом «Позже» (нет сети: тихо, без сообщений)
        val dayDue = now - Cfg.selfUpdLast() >= DAY_MS
        val pending = Cfg.selfUpdPending()
        if (!dayDue && pending.isEmpty()) return
        busy = true
        try {
            val (r, best) = fetch(ctx)
            // отметка времени ставится только после ответа сети: при ошибке следующая проверка не ждёт сутки
            if (r.errors.isEmpty() || r.cands.isNotEmpty()) Cfg.setSelfUpdLast(now)
            if (best != null && state == null && (dayDue || pending == best.versionName)) { Cfg.setSelfUpdPending(best.versionName); banner = St.Found(r, best) }
            else if (best == null && (r.errors.isEmpty() || r.cands.isNotEmpty())) Cfg.setSelfUpdPending("")
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
    val st = SelfUpd.state
    SelfUpd.notes?.let { NotesDialog(it) }
    if (st != null) SelfUpdDialog(st)
}

/** Плашка рисуется отдельно и ПОСЛЕДНЕЙ в экране (иначе её закрывает содержимое): ставится в конце «Меню» и «Дома». */
@Composable
fun SelfUpdBannerHost() {
    if (SelfUpd.state == null) SelfUpd.banner?.let { SelfUpdBanner(it) }
}

/** Плашка о новой версии внизу экрана: «Вышла X. Обновить?», кнопки «Обновить», «Что нового», «Позже» и крестик. Фокус сама не берёт (до неё доходят стрелками или мышью). */
@Composable
private fun SelfUpdBanner(f: SelfUpd.St.Found) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    val tv = TopMode.isTv(ctx)
    val shape = RoundedCornerShape(16.dp)
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
        Column(
            Modifier.widthIn(max = if (tv) 780.dp else 540.dp).clip(shape).background(t.tile).border(2.dp, t.accent, shape).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.upd_banner, f.cand.versionName), color = t.text, fontSize = if (tv) 22.sp else 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                Chip("✕", false) { SelfUpd.bannerLater() }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionRow(HIcons.download, stringResource(R.string.self_upd_install)) { SelfUpd.bannerInstall(ctx, scope) }
                ActionRow(HIcons.list, stringResource(R.string.notes_btn)) { SelfUpd.notes = f.cand }
                ActionRow(HIcons.recent, stringResource(R.string.self_upd_later)) { SelfUpd.bannerLater() }
            }
        }
    }
}

/** Окно «Что нового»: текст изменений этой версии из описания выпуска на языке программы; прокручивается стрелками и колесом, полоса видна. */
@Composable
private fun NotesDialog(c: UpdCand) {
    val t = LocalTh.current
    val text = remember(c) { ReleaseNotes.forLang(c.body, AppLoc.code()) }
    val sc = androidx.compose.foundation.rememberScrollState()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    HDialog(onDismissRequest = { SelfUpd.notes = null }) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.notes_title, c.versionName), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            androidx.compose.foundation.layout.Box(
                Modifier.heightIn(max = 340.dp).verticalScrollbar(sc)
                    .onPreviewKeyEvent { e ->
                        // стрелки вверх и вниз листают текст, когда фокус на кнопке «Закрыть» или на самом тексте
                        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && (e.key == androidx.compose.ui.input.key.Key.DirectionDown || e.key == androidx.compose.ui.input.key.Key.DirectionUp)) {
                            val down = e.key == androidx.compose.ui.input.key.Key.DirectionDown
                            if (if (down) sc.canScrollForward else sc.canScrollBackward) { scope.launch { sc.animateScrollBy(if (down) 160f else -160f) }; true } else false
                        } else false
                    }
                    .verticalScroll(sc).padding(end = ScrollbarMath.RESERVE_DP.dp),
            ) {
                Text(text.ifBlank { stringResource(R.string.notes_none) }, color = t.text, fontSize = 15.sp)
            }
            Chip(stringResource(R.string.close), true, Modifier.focusRequester(first)) { SelfUpd.notes = null }
        }
    }
}

/** Текст «Что нового» из описания выпуска GitHub: берётся блок на нужном языке (**English**, **Русский**, **Українська**), без заголовка «###», SHA и служебных строк. */
object ReleaseNotes {
    private val marks = mapOf("en" to "**English**", "ru" to "**Русский**", "uk" to "**Українська**")
    fun forLang(body: String, lang: String): String {
        val b = body.replace("\r\n", "\n")
        val all = marks.values
        val mark = marks[lang] ?: marks.getValue("en")
        var s = b
        val i = b.indexOf(mark)
        if (i >= 0) {
            val from = i + mark.length
            val next = all.filter { it != mark }.map { b.indexOf(it, from) }.filter { it >= 0 }.minOrNull() ?: b.length
            s = b.substring(from, next)
        }
        return s.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("###") && !it.startsWith("**") && !it.lowercase().contains("sha-256") && !it.lowercase().startsWith("android ") }
            .joinToString("\n") { if (it.startsWith("- ") || it.startsWith("* ")) "•  " + it.drop(2) else it }
    }
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
                        ActionRow(HIcons.download, stringResource(R.string.self_upd_install), "", Modifier.focusRequester(first)) { SelfUpd.install(ctx, scope, st) }
                        ActionRow(HIcons.list, stringResource(R.string.notes_btn)) { SelfUpd.notes = st.cand }
                        Chip(stringResource(R.string.self_upd_later), false) { SelfUpd.dismiss() }
                        ActionRow(HIcons.open, stringResource(R.string.self_upd_release)) { openUrl(ctx, SelfUpd.RELEASES) }
                    }
                }
                is SelfUpd.St.Latest -> {
                    Text(stringResource(R.string.self_upd_latest, st.version), color = t.text, fontSize = 15.sp)
                    Chip(stringResource(R.string.close), true, Modifier.focusRequester(first)) { SelfUpd.dismiss() }
                }
                SelfUpd.St.Failed -> {
                    Text(stringResource(R.string.self_upd_failed), color = t.text, fontSize = 15.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ActionRow(HIcons.refresh, stringResource(R.string.self_upd_retry), "", Modifier.focusRequester(first)) { SelfUpd.checkNow(ctx, scope) }
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
