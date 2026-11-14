package app.tvapps

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/** Фокус пульта на карточках каталогов: первая карточка первой строки берёт курсор по стрелке «вниз» из строки поиска. */
object CatFocus {
    @Volatile var first: (() -> Boolean)? = null
    @Volatile var hasFocus = false
    fun focusFirst(): Boolean = first?.let { runCatching { it() }.getOrDefault(false) } == true
}

/** Один найденный материал (фильм, сериал, трек…) в каталоге программы. */
data class CatItem(
    val title: String, val sub: String, val poster: String?, val rating: String?,
    val action: String, val data: String?, val extra: String?, val component: String?,
)

/** Каталог программы: свой поисковый провайдер (searchable) и найденные в нём карточки. */
data class CatGroup(val pkg: String, val label: String, val items: List<CatItem>)

private class Prov(
    val pkg: String, val label: String, val authority: String, val path: String?, val selection: String?,
    val action: String?, val activity: String, val intentData: String? = null,
)

/**
 * Поиск по каталогам установленных программ (фильмы, сериалы, музыка): у программы с поиском для Android TV есть поисковый провайдер
 * (android.app.searchable → suggestAuthority). Запрос к нему возвращает название, подпись, постер и ссылку, которой программа открывает материал.
 * Если провайдер закрыт правом GLOBAL_SEARCH, запрос идёт от root или ADB (`content query`).
 */
object Catalogs {
    private val skip = setOf(
        "com.google.android.googlequicksearchbox", "com.google.android.gms", "com.android.vending", "com.android.chrome",
        "com.sec.android.app.sbrowser", "com.android.settings", "com.android.settings.intelligence", "com.google.android.apps.maps",
    )
    private var cache: List<Prov>? = null
    private val posters = CacheBudget.track(object : LruCache<String, Bitmap>(CacheBudget.bytes(20)) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount })

    private fun providers(ctx: Context): List<Prov> {
        cache?.let { return it }
        val pm = ctx.packageManager
        val sm = ctx.getSystemService(Context.SEARCH_SERVICE) as SearchManager
        val out = LinkedHashMap<String, Prov>()
        val ris = runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_SEARCH), PackageManager.MATCH_ALL) }.getOrDefault(emptyList())
        for (ri in ris) {
            val ai = ri.activityInfo ?: continue
            if (ai.packageName == ctx.packageName || ai.packageName in skip) continue
            val info = runCatching { sm.getSearchableInfo(ComponentName(ai.packageName, ai.name)) }.getOrNull() ?: continue
            val auth = info.suggestAuthority ?: continue
            if (auth in out) continue
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(ai.packageName, 0)).toString() }.getOrDefault(ai.packageName)
            out[auth] = Prov(ai.packageName, label, auth, info.suggestPath, info.suggestSelection, info.suggestIntentAction, ai.name, info.suggestIntentData)
        }
        // запасной путь: у некоторых программ (например, каталог фильмов) поисковый провайдер есть, а searchable в активности не объявлен
        val provs = runCatching { pm.queryContentProviders(null, 0, 0) }.getOrNull().orEmpty()
        for (pi in provs) {
            val auth = pi.authority?.split(';')?.firstOrNull { it.endsWith(".search") || it.endsWith(".SuggestionProvider") || it.contains("suggest", true) } ?: continue
            if (pi.packageName == ctx.packageName || pi.packageName in skip || auth in out || out.values.any { it.pkg == pi.packageName }) continue
            if (pm.getLaunchIntentForPackage(pi.packageName) == null && pm.getLeanbackLaunchIntentForPackage(pi.packageName) == null) continue
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pi.packageName, 0)).toString() }.getOrDefault(pi.packageName)
            out[auth] = Prov(pi.packageName, label, auth, null, null, Intent.ACTION_VIEW, "")
        }
        return out.values.toList().also { cache = it }
    }

    /** Сколько программ с поиском по каталогу найдено (для подсказки). */
    fun count(ctx: Context): Int = providers(ctx).size

    /** Программы с каталогом: пакет и название (источник для строки карточек «Каталог программы»). Вызывать не из главного потока. */
    fun providerApps(ctx: Context): List<Pair<String, String>> = providers(ctx).map { it.pkg to it.label }.distinctBy { it.first }.sortedBy { it.second.lowercase() }

    /** Карточки одной программы по запросу (для строки «Дома»); null, если ничего не нашлось. */
    suspend fun fetch(ctx: Context, pkg: String, q: String): CatGroup? = withContext(Dispatchers.IO) {
        for (p in providers(ctx).filter { it.pkg == pkg }) {
            val r = withTimeoutOrNull(9000) { runCatching { rows(ctx, p, q) }.getOrDefault(emptyList()) }.orEmpty()
            if (r.isNotEmpty()) return@withContext CatGroup(p.pkg, p.label, r)
        }
        null
    }

    private fun col(c: android.database.Cursor, name: String): String? = c.getColumnIndex(name).let { if (it >= 0 && !c.isNull(it)) c.getString(it) else null }

    private fun rows(ctx: Context, p: Prov, q: String): List<CatItem> {
        val b = Uri.Builder().scheme("content").authority(p.authority)
        if (!p.path.isNullOrEmpty()) b.appendEncodedPath(p.path)
        b.appendEncodedPath(SearchManager.SUGGEST_URI_PATH_QUERY).appendPath(q).appendQueryParameter("limit", "30")
        val uri = b.build()
        val out = ArrayList<CatItem>()
        val direct = runCatching {
            ctx.contentResolver.query(uri, null, p.selection, if (p.selection != null) arrayOf(q) else null, null)?.use { c ->
                while (c.moveToNext() && out.size < 30) toItem(p, q, c, null)?.let { out.add(it) }
            }
            true
        }.getOrDefault(false)
        return if (direct) out else viaShell(p, uri.toString())
    }

    private fun toItem(p: Prov, q: String, c: android.database.Cursor?, m: Map<String, String>?): CatItem? {
        fun g(n: String) = (if (c != null) col(c, n) else m?.get(n))?.takeIf { it.isNotBlank() && !it.equals("null", true) }
        val title = g("suggest_text_1")?.trim()?.substringBefore(" | ").orEmpty()
        if (title.isEmpty()) return null
        // как у системного поиска: адрес из строки или из описания поиска программы, к нему через «/» идентификатор материала
        var data = g("suggest_intent_data") ?: p.intentData
        val id = g("suggest_intent_data_id")
        if (data != null && !id.isNullOrEmpty() && !data.endsWith(id) && !data.endsWith(Uri.encode(id))) data = data + "/" + Uri.encode(id)
        // клиенты YouTube отдают числовой хэш вместо адреса; идентификатор ролика есть в адресе обложки (…/vi/<id>/…)
        val poster = listOf(g("suggest_result_card_image"), g("suggest_icon_1")).firstOrNull { it != null && (it.startsWith("http") || it.startsWith("content")) }
        val yt = poster?.let { Regex("ytimg[.]com/vi(?:_webp)?/([A-Za-z0-9_-]{11})/").find(it)?.groupValues?.get(1) }
        if (yt != null) return CatItem(title, g("suggest_text_2").orEmpty(), poster, null, Intent.ACTION_VIEW, "https://www.youtube.com/watch?v=$yt", null, null)
        val sub = listOfNotNull(g("suggest_text_2"), g("suggest_rating_score")?.let { "★ $it" }).joinToString("  ")
        return CatItem(title, sub, listOf(g("suggest_result_card_image"), g("suggest_icon_1")).firstOrNull { it != null && (it.startsWith("http") || it.startsWith("content")) }, g("suggest_rating_score"),
            g("suggest_intent_action") ?: p.action ?: Intent.ACTION_SEARCH, data, g("suggest_intent_extra_data"), p.activity)
    }

    /** Запрос от root/ADB: вывод `content query` вида «Row: 0 a=b, c=d». */
    private fun viaShell(p: Prov, uri: String): List<CatItem> {
        val txt = runCatching { kotlinx.coroutines.runBlocking { Adb.shell("content query --uri '$uri'", 8000) } }.getOrDefault("")
        val out = ArrayList<CatItem>()
        val key = Regex("(?:^|, )(suggest_[a-z0-9_]+|_id)=")
        for (line in txt.lines()) {
            if (!line.startsWith("Row:")) continue
            val body = line.substringAfter(' ').substringAfter(' ')
            val ms = key.findAll(body).toList()
            val m = HashMap<String, String>()
            ms.forEachIndexed { i, r -> m[r.groupValues[1]] = body.substring(r.range.last + 1, if (i + 1 < ms.size) ms[i + 1].range.first else body.length).trim() }
            toItem(p, "", null, m)?.let { out.add(it) }
        }
        return out
    }

    /** Опрашивает все каталоги параллельно; готовые группы отдаёт сразу по мере прихода. */
    suspend fun search(ctx: Context, q: String, onGroup: suspend (CatGroup) -> Unit) = withContext(Dispatchers.IO) {
        val ps = providers(ctx)
        coroutineScope {
            ps.map { p ->
                async {
                    val r = withTimeoutOrNull(9000) { runCatching { rows(ctx, p, q) }.getOrDefault(emptyList()) }.orEmpty()
                    if (r.isNotEmpty()) onGroup(CatGroup(p.pkg, p.label, r))
                }
            }.awaitAll()
        }
    }

    /** Ссылка запуска карточки одной строкой (для списков «Избранное» и «Что смотрел»). */
    fun intentUri(g: CatGroup, it: CatItem, q: String): String? = runCatching {
        val i = Intent(it.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!it.component.isNullOrBlank()) i.component = ComponentName(g.pkg, it.component) else i.setPackage(g.pkg)
        if (it.data != null) i.data = Uri.parse(it.data)
        if (it.extra != null) i.putExtra(SearchManager.EXTRA_DATA_KEY, it.extra)
        i.putExtra(SearchManager.USER_QUERY, q)
        i.toUri(Intent.URI_INTENT_SCHEME)
    }.getOrNull()

    /** Открывает материал в программе-источнике по её собственной ссылке. */
    fun open(ctx: Context, g: CatGroup, it: CatItem, q: String): Boolean {
        fun build(withComponent: Boolean): Intent {
            val i = Intent(it.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (withComponent && !it.component.isNullOrBlank()) i.component = ComponentName(g.pkg, it.component) else i.setPackage(g.pkg)
            if (it.data != null) i.data = Uri.parse(it.data)
            if (it.extra != null) i.putExtra(SearchManager.EXTRA_DATA_KEY, it.extra)
            i.putExtra(SearchManager.USER_QUERY, q)
            return i
        }
        // сначала так же, как системный поиск (поисковая активность программы), потом просто по пакету
        val ok = runCatching { ctx.startActivity(build(true)); true }.getOrElse { runCatching { ctx.startActivity(build(false)); true }.getOrDefault(false) }
        if (ok) { Cfg.countLaunch(g.pkg); return true }      // программа попадает в «Недавние»
        // запасной путь: просто открыть программу
        return runCatching { ctx.packageManager.getLaunchIntentForPackage(g.pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } ?: false }.getOrDefault(false)
    }

    fun poster(url: String): Bitmap? {
        posters.get(url)?.let { return it }
        return runCatching {
            val bmp = if (url.startsWith("content")) App.instance!!.contentResolver.openInputStream(Uri.parse(url))?.use { BitmapFactory.decodeStream(it) }
            else {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 8000; c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppMenu")
                c.instanceFollowRedirects = true
                c.inputStream.use { BitmapFactory.decodeStream(it) }
            }
            bmp?.also { posters.put(url, it) }
        }.onFailure { android.util.Log.w("TVApps", "постер: ${it.javaClass.simpleName} ${url.take(80)}") }.getOrNull()
    }
}

/**
 * Результаты поиска по каталогам: для каждой программы своя строка карточек (постер, название, год, рейтинг).
 * Нажатие (ОК, касание, мышь) открывает программу на этом материале.
 */
@Composable
fun CatalogResults(query: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val groups = remember { mutableStateListOf<CatGroup>() }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        groups.clear(); done = false
        val q = query.trim()
        if (q.length < 2) { busy = false; return@LaunchedEffect }
        delay(500)                               // не спрашивать на каждую букву
        busy = true
        // порядок строк не зависит от скорости ответа: по алфавиту, новая строка встаёт на своё место
        Catalogs.search(ctx, q) { g -> kotlinx.coroutines.withContext(Dispatchers.Main) { val i = groups.indexOfFirst { it.label.lowercase() > g.label.lowercase() }; if (i < 0) groups.add(g) else groups.add(i, g) } }
        busy = false; done = true
    }
    if (query.trim().length < 2) return
    Box(modifier) {
        if (groups.isEmpty()) {
            Text(
                stringResource(if (busy) R.string.cat_searching else if (done) R.string.cat_none else R.string.cat_searching),
                color = t.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else {
            // у каждой строки свой запрос фокуса на её первую карточку; «первая» строка выбирается по текущему порядку
            val reqs = remember { HashMap<String, androidx.compose.ui.focus.FocusRequester>() }
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            // курсор уходит на первую карточку верхней из видимых строк (если строка вставилась выше и список сдвинулся, сначала прокрутка вверх)
            DisposableEffect(Unit) {
                CatFocus.first = {
                    val top = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
                    val r = top?.let { reqs[it] }
                    val ok = r?.let { runCatching { it.requestFocus() }.isSuccess } == true
                    if (!ok) scope.launch { runCatching { listState.scrollToItem(0) } }
                    ok
                }
                onDispose { CatFocus.first = null; CatFocus.hasFocus = false }
            }
            // новая строка встала над текущей — список остаётся на самом верху, пока владелица ничего не прокручивала
            LaunchedEffect(groups.size) { if (listState.firstVisibleItemIndex <= 1) runCatching { listState.scrollToItem(0) } }
            LazyColumn(Modifier.fillMaxSize().verticalScrollbar(listState).onFocusChanged { CatFocus.hasFocus = it.hasFocus }, state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(groups.toList(), key = { _, g -> g.pkg }) { gi, g -> CatRow(g, query.trim(), reqs.getOrPut(g.pkg) { androidx.compose.ui.focus.FocusRequester() }) }
            }
        }
    }
}

@Composable
private fun CatRow(g: CatGroup, q: String, firstReq: androidx.compose.ui.focus.FocusRequester? = null) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val icon = remember(g.pkg) { runCatching { ctx.packageManager.getApplicationIcon(g.pkg).toBitmap(64, 64).asImageBitmap() }.getOrNull() }
    Column {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Image(icon, null, Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)))
            Text(g.label, color = t.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
            Text("  ${g.items.size}", color = t.hint, fontSize = 13.sp)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
            itemsIndexed(g.items, key = { _, it -> (it.data ?: it.title) + it.sub }) { ci, it -> CatCard(g, it, q, if (ci == 0) firstReq else null) }
        }
    }
}

@Composable
private fun CatCard(g: CatGroup, item: CatItem, q: String, focusReq: androidx.compose.ui.focus.FocusRequester? = null) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val bmp by produceState<Bitmap?>(null, item.poster) { value = item.poster?.let { withContext(Dispatchers.IO) { Catalogs.poster(it) } } }
    var menu by remember { mutableStateOf(false) }
    if (menu) {
        val uri = Catalogs.intentUri(g, item, q)
        val opts = listOf(
            Opt(stringResource(R.string.cat_open), "", onClick = { menu = false; CardStore.record(ctx, item.title, item.poster, g.pkg, uri); Catalogs.open(ctx, g, item, q) }),
            Opt(stringResource(R.string.mine_fav), "", onClick = {
                menu = false
                if (uri != null) CardStore.add(ctx, "fav", SavedCard(item.title, item.poster, g.pkg, uri, System.currentTimeMillis()))
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.mine_added), android.widget.Toast.LENGTH_SHORT).show()
            }),
            Opt(stringResource(R.string.cat_launch_app, g.label), "", onClick = {
                menu = false
                ctx.packageManager.getLaunchIntentForPackage(g.pkg)?.let { Cfg.countLaunch(g.pkg); runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }),
            Opt(stringResource(R.string.cat_copy), "", onClick = {
                menu = false
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("", item.title))
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.cat_copied), android.widget.Toast.LENGTH_SHORT).show()
            }),
        )
        ListDialog(item.title, opts, { menu = false })
    }
    HomeBox(
        Modifier.width(118.dp), RoundedCornerShape(12.dp), t.tile, focusReq = focusReq,
        onClick = {
            CardStore.record(ctx, item.title, item.poster, g.pkg, Catalogs.intentUri(g, item, q))
            if (!Catalogs.open(ctx, g, item, q)) android.widget.Toast.makeText(ctx, ctx.getString(R.string.cant_open), android.widget.Toast.LENGTH_SHORT).show()
        },
        onLong = { menu = true },
    ) {
        Column(Modifier.padding(6.dp)) {
            Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(8.dp)).background(t.border), contentAlignment = Alignment.Center) {
                val b = bmp
                if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(item.title.take(1), color = t.hint, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(item.title, color = t.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.sub.isNotBlank()) Text(item.sub, color = t.sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ------------------------------------------------------------------ строка карточек «Дома» из каталога программы

/** Последние карточки каталогов: при возврате на «Дом» строка видна сразу, пока идёт новое чтение (ключ: источник строки). */
internal object CatMem { val groups = HashMap<String, CatGroup>() }

/**
 * Строка карточек из каталога любой программы с поиском (источник «cat:<пакет>|<запрос>»): работает и у программ,
 * которые не публикуют каналы Android TV. Нажатие открывает материал в программе.
 */
@Composable
internal fun CatCardsRow(row: HRow, tick: Int, firstReq: androidx.compose.ui.focus.FocusRequester?, onDlg: (Dlg?) -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val pkg = row.auto.removePrefix("cat:").substringBefore('|')
    val q = row.auto.substringAfter('|', "").trim()
    val key = row.auto
    var loaded by remember(key) { mutableStateOf(CatMem.groups.containsKey(key)) }
    val g by produceState<CatGroup?>(CatMem.groups[key], key, tick) {
        val r = Catalogs.fetch(ctx, pkg, q)
        if (r != null) CatMem.groups[key] = r
        value = r ?: value
        loaded = true
    }
    val h = row.size
    val w = (h * when (row.aspect) { "1:1" -> 1f; "2:3" -> 2f / 3f; else -> 16f / 9f }).toInt()
    val group = g
    if (group == null || group.items.isEmpty()) {
        if (!loaded) { Spacer(Modifier.height(h.dp)); return }
        HomeBox(
            Modifier.padding(horizontal = 4.dp).height(h.coerceAtMost(110).dp), RoundedCornerShape(12.dp), t.tile, focusReq = firstReq,
            onClick = { onDlg(Dlg.CatQuery(row.id, pkg)) }, onLong = { onDlg(Dlg.RowD(row.id)) },
        ) { Text(stringResource(R.string.cq_none), color = t.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) }
        return
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    val k = if (row.fit) fitScaleN(maxWidth.value - 8f, w.toFloat(), row.gap.toFloat(), row.perLine) else 1f
    val cg = if (row.fit) spreadGap(maxWidth.value - 8f - 0.5f, w.toFloat(), row.gap.toFloat(), row.perLine) else row.gap.toFloat()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(cg.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
        itemsIndexed(group.items.take(row.count), key = { i, _ -> i }) { i, item ->
            val bmp by produceState<Bitmap?>(null, item.poster) { value = item.poster?.let { withContext(Dispatchers.IO) { Catalogs.poster(it) } } }
            var menu by remember { mutableStateOf(false) }
            val uri = remember(item) { Catalogs.intentUri(group, item, q) }
            if (menu) CardMenu(item.title, item.poster, group.pkg, uri, false, { onDlg(Dlg.RowD(row.id)) }, { menu = false })
            HomeBox(
                Modifier.size((w * k).dp, (h * k).dp), RoundedCornerShape(12.dp), t.tile, focusReq = if (i == 0) firstReq else null, memKey = "c|" + row.id + "|" + i,
                onClick = {
                    CardStore.record(ctx, item.title, item.poster, group.pkg, uri)
                    if (!Catalogs.open(ctx, group, item, q)) android.widget.Toast.makeText(ctx, ctx.getString(R.string.home_cant_open), android.widget.Toast.LENGTH_SHORT).show()
                },
                onLong = { menu = true },
            ) {
                val b = bmp
                if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(item.title.take(1), color = t.hint, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(androidx.compose.ui.graphics.Color(0x99000000)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                    Text(item.title, color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }    }
}
