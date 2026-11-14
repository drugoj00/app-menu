@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.tvapps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** Поиск приложений в сети (F-Droid, GitHub) и установка по прямой ссылке. */
@Composable
fun NetDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("fdroid") }
    var results by remember { mutableStateOf<List<NetApp>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var tokenOpen by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf(Cfg.githubToken) }

    // Порядок движения пультом: поле поиска и «Найти» → источники (F-Droid, GitHub, Отмена) → ссылка и «Скачать» → токен → список.
    // Соседи заданы явно: иначе выбор ближайшего по положению элемента перескакивал (например с источника в поле ссылки или в список).
    val frQuery = remember { FocusRequester() }
    val frSearch = remember { FocusRequester() }
    val frFd = remember { FocusRequester() }
    val frGh = remember { FocusRequester() }
    val frCancel = remember { FocusRequester() }
    val frLink = remember { FocusRequester() }
    val frGet = remember { FocusRequester() }
    val frToggle = remember { FocusRequester() }

    // скачивает файлы по очереди и ставит первый, который встал (для приложений с несколькими сборками под процессоры)
    fun installFrom(urls: List<String>) {
        scope.launch {
            busy = true
            var done = false
            for (u in urls) {
                status = ctx.getString(R.string.net_downloading, u.substringAfterLast('/'), 0)
                val f = NetSearch.download(ctx, u) { mb -> status = ctx.getString(R.string.net_downloading, u.substringAfterLast('/'), mb) }
                if (f == null) continue
                if (Adb.isConnected) {
                    status = ctx.getString(R.string.net_installing)
                    val (ok, _) = Batch.installViaAdb(ctx, listOf(f))
                    if (ok > 0) { status = ctx.getString(R.string.net_done); done = true; break }
                } else {
                    Pending.installs.add(Batch.shareUri(ctx, f))
                    Pending.next(ctx)
                    status = ctx.getString(R.string.net_system_dialog)
                    done = true
                    break
                }
            }
            if (!done) status = ctx.getString(R.string.net_failed) + NetErr.reason(ctx).let { if (it.isEmpty()) "" else ": $it" }
            busy = false
        }
    }

    fun doSearch() {
        if (query.isBlank() || busy) return
        scope.launch {
            busy = true
            status = ctx.getString(R.string.net_searching)
            NetErr.clear()
            var failed = ""
            results = try { NetSearch.search(query, source) } catch (e: Exception) {
                NetErr.set(e)
                val ne = e as? NetSearch.NetSearchError
                failed = when (ne?.kind) {
                    "rate" -> { tokenOpen = true; ctx.getString(R.string.net_gh_rate) }
                    "token" -> { tokenOpen = true; ctx.getString(R.string.net_gh_token_bad) }
                    "http" -> ctx.getString(R.string.net_err_code, ne.code)
                    else -> NetErr.reason(ctx).ifEmpty { ctx.getString(R.string.net_no_network) }
                }
                emptyList()
            }
            status = when {
                failed.isNotEmpty() -> failed
                results.isNotEmpty() -> ctx.getString(R.string.net_found, results.size)
                source == "github" -> ctx.getString(R.string.net_nothing_gh)
                else -> ctx.getString(R.string.net_nothing)
            }
            busy = false
        }
    }

    val fields = OutlinedTextFieldDefaults.colors(
        focusedTextColor = t.text, unfocusedTextColor = t.text, focusedBorderColor = t.accent, unfocusedBorderColor = t.border,
        cursorColor = t.accent, focusedLabelColor = t.accent, unfocusedLabelColor = t.sub,
    )
    HDialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().padding(24.dp).clip(RoundedCornerShape(20.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.net_title), color = t.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(if (source == "github") R.string.net_hint_gh else R.string.net_hint), color = t.hint, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    query, { query = it }, label = { Text(stringResource(R.string.net_query)) }, singleLine = true, colors = fields,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                    modifier = Modifier.weight(1f).focusRequester(frQuery).focusProperties { down = frFd; right = frSearch },
                )
                Chip(stringResource(R.string.net_search), true, Modifier.focusRequester(frSearch).focusProperties { left = frQuery; down = frGh }) { doSearch() }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("F-Droid", source == "fdroid", Modifier.focusRequester(frFd).focusProperties { up = frQuery; right = frGh; down = frLink }) { source = "fdroid" }
                Chip("GitHub", source == "github", Modifier.focusRequester(frGh).focusProperties { up = frSearch; left = frFd; right = frCancel; down = frLink }) { source = "github" }
                Chip(stringResource(R.string.cancel), false, Modifier.focusRequester(frCancel).focusProperties { up = frSearch; left = frGh; down = frGet }) { if (!busy) onClose() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    link, { link = it }, label = { Text(stringResource(R.string.net_link)) }, singleLine = true, colors = fields,
                    modifier = Modifier.weight(1f).focusRequester(frLink).focusProperties { up = frFd; right = frGet; down = frToggle },
                )
                Chip(stringResource(R.string.net_get), false, Modifier.focusRequester(frGet).focusProperties { left = frLink; up = frCancel; down = frToggle }) { if (link.startsWith("http") && !busy) installFrom(listOf(link.trim())) }
            }
            // необязательный токен GitHub: без него GitHub пускает около 10 поисковых запросов в минуту
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(stringResource(if (tokenOpen) R.string.net_gh_token_hide else R.string.net_gh_token_show), tokenOpen, Modifier.focusRequester(frToggle).focusProperties { up = frLink }) { tokenOpen = !tokenOpen }
                if (tokenOpen) {
                    var tokShown by remember { mutableStateOf(false) }   // 7.0: токен скрыт, глазик справа в поле
                    OutlinedTextField(
                        token, { token = it; Cfg.changeGithubToken(it) }, label = { Text(stringResource(R.string.net_gh_token_label)) }, singleLine = true, colors = fields,
                        visualTransformation = secretTransform(tokShown), trailingIcon = { SecretEye(tokShown, t.accent) { tokShown = !tokShown } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (status.isNotEmpty()) Text(status, color = t.accent, fontSize = 14.sp)
            BarLazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(results, key = { _, it -> it.source + it.id }) { idx, app ->
                    var focused by remember { mutableStateOf(false) }
                    val (hm, hover) = rememberHover()
                    Column(
                        Modifier.fillMaxWidth()
                            .then(if (idx == 0) Modifier.focusProperties { up = frToggle } else Modifier)
                            .then(hm)
                            .onFocusChanged { focused = it.isFocused }
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (focused || hover) t.tileFocus else t.tile)
                            .border(if (focused) 3.dp else 0.dp, if (focused) t.accent else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable(enabled = !busy) {
                                scope.launch {
                                    busy = true
                                    status = ctx.getString(R.string.net_resolving)
                                    NetErr.clear()
                                    var urls = emptyList<String>()
                                    var why = ""
                                    try { urls = NetSearch.apkUrls(app) } catch (e: Exception) {
                                        NetErr.set(e)
                                        why = when ((e as? NetSearch.NetSearchError)?.kind) {
                                            "rate" -> { tokenOpen = true; ctx.getString(R.string.net_gh_rate) }
                                            else -> NetErr.reason(ctx)
                                        }
                                    }
                                    busy = false
                                    if (urls.isEmpty()) status = ctx.getString(R.string.net_no_apk) + (if (why.isEmpty()) "" else ": $why") else installFrom(urls)
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(app.name, color = t.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (app.summary.isNotBlank()) Text(app.summary, color = t.sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
