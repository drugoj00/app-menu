package app.tvapps

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.json.JSONArray
import org.json.JSONObject

/** Сохранённая карточка из любой программы: ссылка запуска хранится готовой строкой интента. */
data class SavedCard(val title: String, val poster: String?, val pkg: String, val uri: String, val at: Long)

/**
 * Свои списки карточек «Дома»: «fav» (избранное: выбранные вручную) и «seen» (что смотрел: карточки, которые открывали).
 * Карточки попадают сюда из строк каналов, из каталогов программ и из поиска; открываются по сохранённой ссылке.
 */
object CardStore {
    val tick = mutableIntStateOf(0)
    private fun key(kind: String) = "cards_$kind"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE)

    fun list(ctx: Context, kind: String): List<SavedCard> = runCatching {
        val a = JSONArray(prefs(ctx).getString(key(kind), "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            SavedCard(o.getString("t"), o.optString("p").ifBlank { null }, o.optString("k"), o.getString("u"), o.optLong("a"))
        }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, kind: String, l: List<SavedCard>) {
        val a = JSONArray(l.map { JSONObject().put("t", it.title).put("p", it.poster ?: "").put("k", it.pkg).put("u", it.uri).put("a", it.at) })
        prefs(ctx).edit().putString(key(kind), a.toString()).apply()
        HomeStore.dirty = true
        tick.intValue++
    }

    /** Новая карточка встаёт первой; повтор поднимается наверх. «Что смотрел» хранит последние 40. */
    fun add(ctx: Context, kind: String, c: SavedCard) {
        val rest = list(ctx, kind).filter { it.uri != c.uri }
        save(ctx, kind, (listOf(c) + rest).take(if (kind == "seen") 40 else 200))
    }

    fun remove(ctx: Context, kind: String, uri: String) = save(ctx, kind, list(ctx, kind).filter { it.uri != uri })
    fun has(ctx: Context, kind: String, uri: String) = list(ctx, kind).any { it.uri == uri }

    fun record(ctx: Context, title: String, poster: String?, pkg: String, uri: String?) {
        if (uri != null) add(ctx, "seen", SavedCard(title, poster, pkg, uri, System.currentTimeMillis()))
    }

    fun programs(ctx: Context, kind: String): List<ChProgram> =
        list(ctx, kind).map { ChProgram(it.uri.hashCode().toLong(), it.title, it.poster, it.uri, it.pkg, 2) }
}

/** Меню карточки (долгое нажатие): в избранное и обратно, убрать из «Что смотрел», настройки строки. */
@Composable
internal fun CardMenu(title: String, poster: String?, pkg: String, uri: String?, seenRow: Boolean, onRow: (() -> Unit)?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val dep = CardStore.tick.intValue
    val fav = uri != null && CardStore.has(ctx, "fav", uri)
    val opts = buildList {
        if (uri != null) add(Opt(stringResource(if (fav) R.string.mine_unfav else R.string.mine_fav)) {
            if (fav) CardStore.remove(ctx, "fav", uri) else CardStore.add(ctx, "fav", SavedCard(title, poster, pkg, uri, System.currentTimeMillis()))
            Toast.makeText(ctx, ctx.getString(if (fav) R.string.mine_removed else R.string.mine_added), Toast.LENGTH_SHORT).show()
            onClose()
        })
        if (seenRow && uri != null) add(Opt(stringResource(R.string.mine_forget)) { CardStore.remove(ctx, "seen", uri); onClose() })
        if (onRow != null) add(Opt(stringResource(R.string.d_row_settings)) { onClose(); onRow() })
    }
    ListDialog(title, opts, onClose)
}
