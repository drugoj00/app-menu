package app.tvapps

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Устройство экрана «Дом»: верхняя полоска (голос и значки состояния) и строки.
 * Строка бывает трёх видов: apps (значки и баннеры приложений), cards (карточки каналов приложений Android TV), widgets (виджеты).
 * Всё хранится одной JSON-строкой в общих настройках программы, поэтому резервная копия настроек уносит и «Дом».
 */

/** Элемент строки значков: приложение, «Все приложения», настройки Android или вход HDMI. */
data class HItem(
    val id: String,
    /** app, menu, settings, hdmi */
    val kind: String,
    /** пакет приложения или номер входа */
    val ref: String = "",
    /** своя подпись; пусто: родная */
    val label: String = "",
    /** icon или banner */
    val mode: String = "banner",
    /** есть своя картинка (файл hm_<id>.png) */
    val img: Boolean = false,
    /** скругление углов, dp */
    val radius: Int = 14,
    /** фон: 0 по виду (у значка прозрачный, у баннера плитка), 1 прозрачный, 2 плитка */
    val bg: Int = 0,
    val showLabel: Boolean = false,
    /** тень сзади */
    val shadow: Boolean = false,
    /** обводка: толщина, dp (0 — без обводки) */
    val borderW: Int = 0,
    /** цвет обводки ARGB; 0 — цвет программы (акцент темы) */
    val borderC: Long = 0L,
    /** обводка видна только при наведении мыши и выборе пультом, а не всегда */
    val borderHover: Boolean = false,
)

/** Одна ячейка строки виджетов. type: weather, clock, date, storage, ram. */
/** fs: масштаб шрифта, % (100 по умолчанию); wt: доля ширины в строке (100 у всех: поровну). */
data class HWidget(
    val type: String = "weather", val city: String = "", val n: Int = 5, val flag: Boolean = true, val ref: String = "", val fs: Int = 100, val wt: Int = 100,
    /** скругление углов, dp */
    val radius: Int = 16,
    /** обводка: толщина, dp (0: без обводки), цвет ARGB (0: цвет программы), только при наведении */
    val borderW: Int = 0, val borderC: Long = 0L, val borderHover: Boolean = false,
    /** Android-виджет: компонент поставщика (пакет/класс). Номер виджета (ref) привязан к устройству и после восстановления копии недействителен, по компоненту виджет добавляется заново. */
    val prov: String = "",
    /** Вид часов и погоды: JSON настроек (размеры, цвета, формат, цифры), пусто — прежний вид (см. WidgetStyle.kt). */
    val style: String = "",
)

data class HRow(
    val id: String,
    val title: String = "",
    val showTitle: Boolean = true,
    /** apps, cards, widgets */
    val kind: String = "apps",
    val items: List<HItem> = emptyList(),
    /** apps: пусто, fav, recent, hdmi (добавляется к ручным); cards: watch_next, pkg:<пакет>, ch:<ключ канала> */
    val auto: String = "",
    /** сколько элементов брать из автоисточника или канала */
    val count: Int = 10,
    /** высота: размер значка, высота карточки или высота ряда виджетов, dp */
    val size: Int = 72,
    /** форма карточки: 16:9, 1:1, 2:3 */
    val aspect: String = "16:9",
    val slots: List<HWidget> = emptyList(),
    /** подписи под значками */
    val labels: Boolean = true,
    /** вид элементов по умолчанию: banner (баннеры) или icon (значки) */
    val itemMode: String = "banner",
    /** расстояние между плитками, dp */
    val gap: Int = 8,
    /** плитки растягиваются так, чтобы строка заполнялась от края до края целым числом плиток */
    val fit: Boolean = true,
    /** значки, не влезающие в экран, переносятся на следующую линию (иначе строка листается вбок) */
    val wrap: Boolean = false,
    /** при подгонке по ширине: сколько плиток в линии (0: считается по размеру значков) */
    val perLine: Int = 0,
    /** убранные при уменьшении числа виджетов: возвращаются, когда число увеличивают */
    val spare: List<HWidget> = emptyList(),
)

/** Значок состояния в верхней полоске: bt, net, usb, time, date. */
data class HStatus(val id: String, val visible: Boolean = true, val opt: Int = 0)

/**
 * Верхняя полоска: значок меню, голос с полем ввода текста и значки состояния.
 * big: крупные значки, часы и дата (по умолчанию), иначе обычные.
 */
data class HTop(
    val voice: Boolean = true,
    val status: List<HStatus> = emptyList(),
    val big: Boolean = true,
    val search: Boolean = true,
    val menu: Boolean = true,
    val recent: Boolean = true,      // кнопка «Недавние» справа от кнопки меню
    val hdmi: Boolean = true,        // кнопка «Входы HDMI» справа от «Недавних»
    val clean: Boolean = true,       // кнопка «Очистка памяти» после «Недавних» (нет ключа в сохранённой раскладке: включена)
)

/** Фон экрана: none (по теме), color, gradient (живой градиент), image, video, anim (GIF/WebP). */
data class HBg(
    val type: String = "none",
    val color: Long = 0xFF0B0B10,
    /** crop: обрезать по экрану; stretch: растянуть */
    val fit: String = "crop",
    /** затемнение поверх фона, % */
    val dim: Int = 0,
    /** размытие картинки и видео, % (0: нет) */
    val blur: Int = 0,
)

/** rowGap: расстояние между строками «Дома», dp. */
data class HLayout(val top: HTop, val rows: List<HRow>, val bg: HBg = HBg(), val rowGap: Int = 8)

private fun HItem.toJson() = JSONObject().put("id", id).put("k", kind).put("r", ref).put("l", label).put("m", mode)
    .put("i", img).put("rd", radius).put("bg", bg).put("sl", showLabel).put("sh", shadow).put("bw", borderW).put("bc", borderC).put("bh", borderHover)

private fun JSONObject.toItem() = HItem(
    getString("id"), getString("k"), optString("r"), optString("l"), optString("m", "banner"),
    optBoolean("i"), optInt("rd", 14), optInt("bg"), optBoolean("sl", false), optBoolean("sh"),
    optInt("bw"), optLong("bc"), optBoolean("bh"),
)

private fun HWidget.toJson() = JSONObject().put("t", type).put("c", city).put("n", n).put("f", flag).put("r", ref).put("fs", fs).put("wt", wt).put("rd", radius).put("bw", borderW).put("bc", borderC).put("bh", borderHover).put("p", prov).put("st", style)
private fun JSONObject.toWidget() = HWidget(optString("t", "weather"), optString("c"), optInt("n", 5), optBoolean("f", true), optString("r"), optInt("fs", 100), optInt("wt", 100), optInt("rd", 16), optInt("bw"), optLong("bc"), optBoolean("bh"), optString("p"), optString("st"))

private fun HRow.toJson() = JSONObject().put("id", id).put("t", title).put("st", showTitle).put("k", kind)
    .put("it", JSONArray(items.map { it.toJson() })).put("a", auto).put("c", count).put("s", size).put("as", aspect)
    .put("sl", JSONArray(slots.map { it.toJson() })).put("lb", labels).put("im", itemMode).put("gp", gap).put("ft", fit).put("wr", wrap).put("pl", perLine).put("sp", JSONArray(spare.map { it.toJson() }))

private fun JSONObject.toRow(): HRow {
    val its = optJSONArray("it")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).toItem() } } ?: emptyList()
    val sl = optJSONArray("sl")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).toWidget() } } ?: emptyList()
    return HRow(
        getString("id"), optString("t"), optBoolean("st", true), optString("k", "apps"), its, optString("a"),
        optInt("c", 10), optInt("s", 72), optString("as", "16:9"), sl, optBoolean("lb", true), optString("im", "banner"),
        optInt("gp", 8), optBoolean("ft", true), optBoolean("wr", false), optInt("pl", 0),
        optJSONArray("sp")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).toWidget() } } ?: emptyList(),
    )
}

private fun HLayout.toJson() = JSONObject()
    .put("v", 1).put("rg", rowGap)
    .put("voice", top.voice).put("big", top.big).put("search", top.search).put("menu", top.menu).put("recent", top.recent).put("hdmi", top.hdmi).put("clean", top.clean)
    .put("bg", JSONObject().put("t", bg.type).put("c", bg.color).put("f", bg.fit).put("d", bg.dim).put("b", bg.blur))
    .put("st", JSONArray(top.status.map { JSONObject().put("id", it.id).put("v", it.visible).put("o", it.opt) }))
    .put("rows", JSONArray(rows.map { it.toJson() }))

private fun parseLayout(s: String): HLayout? = runCatching {
    val j = JSONObject(s)
    val st = j.optJSONArray("st")?.let { a ->
        (0 until a.length()).map { val o = a.getJSONObject(it); HStatus(o.getString("id"), o.optBoolean("v", true), o.optInt("o")) }
    } ?: emptyList()
    val rows = j.optJSONArray("rows")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).toRow() } } ?: emptyList()
    // значки состояния, которых нет в сохранённом, добавляются в конец (новые версии)
    val missing = STATUS_IDS.filter { id -> st.none { it.id == id } }.map { HStatus(it, true) }
    val ti = st.indexOfFirst { it.id == "date" }.let { if (it >= 0) it else st.indexOfFirst { x -> x.id == "time" } }
    val all = missing
    // новые значки встают перед датой; колокольчик уведомлений — первым, слева от Bluetooth
    val newNotif = missing.filter { it.id == "notif" }
    val others = missing.filter { it.id != "notif" }
    val st1 = if (others.isEmpty()) st else if (ti >= 0) st.take(ti) + others + st.drop(ti) else st + others
    val stFull = if (newNotif.isEmpty()) st1 else newNotif + st1
    val b = j.optJSONObject("bg")
    val bg = if (b == null) HBg() else HBg(b.optString("t", "none"), b.optLong("c", 0xFF0B0B10), b.optString("f", "crop"), b.optInt("d"), b.optInt("b"))
    HLayout(HTop(j.optBoolean("voice", true), stFull, j.optBoolean("big", true), j.optBoolean("search", true), true, j.optBoolean("recent", true), j.optBoolean("hdmi", true), j.optBoolean("clean", true)), rows, bg, j.optInt("rg", 8))
}.getOrNull()

internal val STATUS_IDS = listOf("notif", "bt", "net", "usb", "gear", "date", "time")

object HomeStore {
    private lateinit var sp: SharedPreferences
    private lateinit var app: Context
    private var seq = 0

    var layout by mutableStateOf(HLayout(HTop(true, STATUS_IDS.map { HStatus(it) }), emptyList())); private set

    fun setBg(b: HBg) = set(layout.copy(bg = b))

    /** Фон меню приложений: свой, выбирается так же, как фон «Дома». */
    var menuBg by mutableStateOf(HBg()); private set

    fun changeMenuBg(b: HBg) {
        menuBg = b
        sp.edit().putString("menu_bg", JSONObject().put("t", b.type).put("c", b.color).put("f", b.fit).put("d", b.dim).put("b", b.blur).toString()).apply()
        dirty = true
    }

    private fun loadMenuBg() {
        val j = runCatching { JSONObject(sp.getString("menu_bg", "") ?: "") }.getOrNull()
        menuBg = if (j == null) HBg() else HBg(j.optString("t", "none"), j.optLong("c", 0xFF0B0B10), j.optString("f", "crop"), j.optInt("d"), j.optInt("b"))
    }

    /** Растёт, когда меняется своя картинка: плитки перечитывают файл. */
    var imgTick by mutableIntStateOf(0); private set

    /** Менялось с последней автоматической копии. */
    @Volatile var dirty = false

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        app = ctx.applicationContext
        sp = app.getSharedPreferences("tvapps", Context.MODE_PRIVATE)
        reload()
    }

    fun reload() {
        val s = sp.getString("home_layout", null)
        val hadSaved = s != null
        layout = (if (s != null) parseLayout(s) else null) ?: defaultLayout().also { save(it) }
        // 02.11.2026: строку «Недавние» убрали (просьба владелицы); из уже сохранённой раскладки удаляем один раз, потом её можно вернуть вручную
        if (!sp.getBoolean("recent_row_removed", false)) {
            sp.edit().putBoolean("recent_row_removed", true).apply()
            if (layout.rows.any { it.auto == "recent" }) set(layout.copy(rows = layout.rows.filter { it.auto != "recent" }))
        }
        // 02.11.2026: порядок в конце полоски — шестерёнка, дата, часы (владелица); один раз переставляем в уже сохранённой раскладке
        if (!sp.getBoolean("status_order_v2", false)) {
            sp.edit().putBoolean("status_order_v2", true).apply()
            val st = layout.top.status
            val tail = listOf("gear", "date", "time")
            val head = st.filter { it.id !in tail }
            val moved = tail.mapNotNull { id -> st.firstOrNull { it.id == id } }
            if (st.map { it.id } != (head + moved).map { it.id }) set(layout.copy(top = layout.top.copy(status = head + moved)))
        }
        // 02.11.2026: строки «Избранное» (пустая) и «Все приложения» (одна плитка) убраны по просьбе владелицы: меню открывается кнопкой, свайпом вверх и клавишей; один раз удаляем из сохранённой раскладки
        // 02.11.2026: из-за повторного добавления стартовых строк на «Доме» появлялись одинаковые; оставляем первую из совпадающих (виджеты не трогаем)
        if (!sp.getBoolean("rows_dedupe_v1", false)) {
            sp.edit().putBoolean("rows_dedupe_v1", true).apply()
            if (hadSaved) sp.edit().putBoolean("home_seeded_apps", true).apply()   // у кого раскладка уже есть, стартовые строки приложений заново не добавляем
            val seen = HashSet<String>()
            val uniq = layout.rows.filter { r ->
                r.kind == "widgets" || seen.add(r.kind + "|" + r.auto + "|" + r.title + "|" + r.items.joinToString(",") { it.kind + ":" + it.ref })
            }
            if (uniq.size != layout.rows.size) set(layout.copy(rows = uniq))
        }
        if (!sp.getBoolean("rows_clean_v5", false)) {
            sp.edit().putBoolean("rows_clean_v5", true).apply()
            val keep = layout.rows.filter { r -> !(r.auto == "fav" && r.items.isEmpty()) && !(r.kind == "apps" && r.auto != "recent" && r.items.size == 1 && r.items[0].kind == "menu") }
            if (keep.size != layout.rows.size) set(layout.copy(rows = keep))
        }
        // 02.11.2026: формат даты как на приставке (две строки: день недели и «Месяц дд.мм.гггг») и на телефоне; один раз переключаем короткие форматы
        if (!sp.getBoolean("date_fmt_v3", false)) {
            sp.edit().putBoolean("date_fmt_v3", true).apply()
            val st = layout.top.status
            if (st.any { it.id == "date" && it.opt < 3 }) set(layout.copy(top = layout.top.copy(status = st.map { if (it.id == "date" && it.opt < 3) it.copy(opt = 3) else it })))
        }
        loadMenuBg()
    }

    /** Раскладка пишется после паузы в изменениях (удержание «+»/«−» не пишет JSON на каждый шаг); flush() при уходе экрана. */
    private var pendingSave: HLayout? = null
    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val saveRun = Runnable { flush() }
    fun flush() {
        saveHandler.removeCallbacks(saveRun)
        val l = pendingSave ?: return
        pendingSave = null
        sp.edit().putString("home_layout", l.toJson().toString()).apply()
    }
    private fun save(l: HLayout) {
        pendingSave = l
        dirty = true
        saveHandler.removeCallbacks(saveRun)
        saveHandler.postDelayed(saveRun, 400)
    }

    fun set(l: HLayout) { layout = l; save(l) }

    fun bumpImg() { imgTick++; dirty = true }

    fun appContext(): Context? = if (::app.isInitialized) app else null

    fun newId(prefix: String): String = prefix + java.lang.Long.toString(System.currentTimeMillis(), 36) + (seq++ % 36).toString(36)

    /** Новые виджеты часов получают цифровой набор вида (только на новой установке; у обновившихся с 5.2 и раньше всё осталось по-старому). */
    val digitalDefault: Boolean get() = ::sp.isInitialized && sp.getBoolean("wstyle_new_default", false)

    private fun defaultLayout(): HLayout {
        val c = app
        sp.edit().putBoolean("wstyle_new_default", true).apply()
        return HLayout(
            HTop(true, STATUS_IDS.map { HStatus(it, true, if (it == "date") 3 else 0) }),
            listOf(
                HRow(newId("r"), "", true, "widgets", size = 120, slots = listOf(HWidget("weather", n = 5), HWidget("clock", n = WStyle.presetN("clock", 1, 0), style = WStyle.preset("clock", 1).json()))),
            ),
        )
    }

    // ------------------------------------------------------------ строки

    fun row(id: String): HRow? = layout.rows.firstOrNull { it.id == id }

    fun updateRow(id: String, f: (HRow) -> HRow) = set(layout.copy(rows = layout.rows.map { if (it.id == id) f(it) else it }))

    fun addRow(r: HRow, afterId: String?) {
        val i = layout.rows.indexOfFirst { it.id == afterId }
        val rows = layout.rows.toMutableList()
        rows.add(if (i < 0) rows.size else i + 1, r)
        set(layout.copy(rows = rows))
    }

    fun removeRow(id: String) {
        layout.rows.firstOrNull { it.id == id }?.items?.forEach { HomeImg.delete(it.id) }
        set(layout.copy(rows = layout.rows.filter { it.id != id }))
    }

    /** Переносит значок в другую строку значков (в конец). */
    fun moveToRow(itemId: String, rowId: String) {
        val src = rowOfItem(itemId) ?: return
        if (src.id == rowId) return
        val item = src.items.first { it.id == itemId }
        set(layout.copy(rows = layout.rows.map {
            when (it.id) { src.id -> it.copy(items = it.items.filter { i -> i.id != itemId }); rowId -> it.copy(items = it.items + item); else -> it }
        }))
    }

    /** Перетаскивание: ставит плитку в строку rowId на место index (внутри строки или в другую строку значков). */
    fun moveItemTo(itemId: String, rowId: String, index: Int) {
        val rows = layout.rows.toMutableList()
        val si = rows.indexOfFirst { r -> r.items.any { it.id == itemId } }
        val ti = rows.indexOfFirst { it.id == rowId }
        if (si < 0 || ti < 0 || rows[ti].kind != "apps") return
        val item = rows[si].items.first { it.id == itemId }
        val srcList = rows[si].items.filter { it.id != itemId }
        if (si == ti) {
            rows[si] = rows[si].copy(items = srcList.toMutableList().also { it.add(index.coerceIn(0, it.size), item) })
        } else {
            rows[si] = rows[si].copy(items = srcList)
            rows[ti] = rows[ti].copy(items = rows[ti].items.toMutableList().also { it.add(index.coerceIn(0, it.size), item) })
        }
        set(layout.copy(rows = rows))
    }

    /** Возвращает раскладку «Дома» по умолчанию (фон и верхняя полоска тоже). */
    fun resetLayout() {
        layout.rows.forEach { r -> r.items.forEach { HomeImg.delete(it.id) } }
        sp.edit().putBoolean("home_seeded", false).putBoolean("home_seeded_apps", false).apply()
        set(defaultLayout())
    }

    fun moveRow(id: String, delta: Int) {
        val rows = layout.rows.toMutableList()
        val i = rows.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j < 0 || j > rows.lastIndex) return
        val r = rows.removeAt(i)
        rows.add(j, r)
        set(layout.copy(rows = rows))
    }

    // ------------------------------------------------------------ элементы

    /** Новый значок получает оформление строки (скругление, фон, тень, обводка, вид), как у уже стоящих там значков. */
    fun addItem(rowId: String, item: HItem) = updateRow(rowId) { r ->
        val ref = r.items.firstOrNull { it.id != item.id }
        val styled = if (ref == null) item else item.copy(
            mode = ref.mode, radius = ref.radius, bg = ref.bg, shadow = ref.shadow,
            borderW = ref.borderW, borderC = ref.borderC, borderHover = ref.borderHover,
        )
        r.copy(items = r.items + styled)
    }

    fun removeItem(rowId: String, itemId: String) {
        HomeImg.delete(itemId)
        updateRow(rowId) { it.copy(items = it.items.filter { i -> i.id != itemId }) }
    }

    fun updateItem(rowId: String, itemId: String, f: (HItem) -> HItem) =
        updateRow(rowId) { r -> r.copy(items = r.items.map { if (it.id == itemId) f(it) else it }) }

    /** Ряд, в котором лежит элемент. */
    fun rowOfItem(itemId: String): HRow? = layout.rows.firstOrNull { r -> r.items.any { it.id == itemId } }

    /** Двигает элемент: dx внутри строки, dy в соседнюю строку значков. Возвращает true, если что-то сдвинулось. */
    fun moveItem(itemId: String, dx: Int, dy: Int): Boolean {
        val rows = layout.rows.toMutableList()
        val ri = rows.indexOfFirst { r -> r.items.any { it.id == itemId } }
        if (ri < 0) return false
        val row = rows[ri]
        val i = row.items.indexOfFirst { it.id == itemId }
        val item = row.items[i]
        if (dy != 0) {
            var t = ri + dy
            while (t in rows.indices && rows[t].kind != "apps") t += dy
            if (t !in rows.indices) return false
            val target = rows[t]
            val list = row.items.toMutableList().also { it.removeAt(i) }
            val tl = target.items.toMutableList().also { it.add(i.coerceAtMost(it.size), item) }
            rows[ri] = row.copy(items = list)
            rows[t] = target.copy(items = tl)
            set(layout.copy(rows = rows))
            return true
        }
        val j = i + dx
        if (j < 0 || j > row.items.lastIndex) return false
        val list = row.items.toMutableList()
        list.removeAt(i)
        list.add(j, item)
        rows[ri] = row.copy(items = list)
        set(layout.copy(rows = rows))
        return true
    }

    // ------------------------------------------------------------ виджеты

    fun updateSlot(rowId: String, slot: Int, f: (HWidget) -> HWidget) =
        updateRow(rowId) { r -> r.copy(slots = r.slots.mapIndexed { i, w -> if (i == slot) f(w) else w }) }

    /** Доля ширины виджета в процентах (целое); у всех виджетов строки в сумме 100. */
    fun widgetShare(r: HRow, slot: Int): Int {
        val total = r.slots.sumOf { it.wt.coerceAtLeast(1) }.coerceAtLeast(1)
        return Math.round(r.slots.getOrNull(slot)?.wt?.coerceAtLeast(1)?.times(100f)?.div(total) ?: 0f)
    }

    /**
     * Ставит доле виджета slot значение pct процентов; остальные виджеты делят оставшееся пропорционально прежним долям.
     * Каждый шаг счётчика (±1) меняет показанное число ровно на 1; два виджета: 50 и 50 это середина.
     */
    fun setWidgetShare(rowId: String, slot: Int, pct: Int) = updateRow(rowId) { r ->
        val n = r.slots.size
        if (n < 2) return@updateRow r
        val p = pct.coerceIn(5, 100 - 5 * (n - 1))
        val others = r.slots.indices.filter { it != slot }
        val oSum = others.sumOf { r.slots[it].wt.coerceAtLeast(1) }.coerceAtLeast(1)
        val rest = 100 - p
        // доли в целых процентах (сумма точно 100): остальным пропорционально, остаток по порядку
        val sh = IntArray(n)
        sh[slot] = p
        var left = rest
        others.forEachIndexed { k, i ->
            val v = if (k == others.lastIndex) left else Math.round(rest * r.slots[i].wt.coerceAtLeast(1).toFloat() / oSum).coerceIn(5, left - 5 * (others.size - 1 - k))
            sh[i] = v; left -= v
        }
        r.copy(slots = r.slots.mapIndexed { i, w -> w.copy(wt = sh[i] * 10) })
    }

    fun setSlotCount(rowId: String, n: Int) = updateRow(rowId) { r ->
        val k = n.coerceIn(1, 4)
        if (k <= r.slots.size) r.copy(slots = r.slots.take(k), spare = (r.slots.drop(k) + r.spare).take(8))
        else {
            // сначала возвращаются убранные ранее виджеты (в прежнем порядке), недостающие добавляются как часы
            val back = r.spare.take(k - r.slots.size)
            r.copy(slots = r.slots + back + List(k - r.slots.size - back.size) { HWidget("clock", n = 0) }, spare = r.spare.drop(back.size))
        }
    }

    // ------------------------------------------------------------ верхняя полоска

    fun updateTop(f: (HTop) -> HTop) = set(layout.copy(top = f(layout.top)))

    fun updateStatus(id: String, f: (HStatus) -> HStatus) =
        updateTop { t -> t.copy(status = t.status.map { if (it.id == id) f(it) else it }) }

    fun moveStatus(id: String, delta: Int): Boolean {
        val list = layout.top.status.toMutableList()
        // двигаем среди видимых: прыжок через скрытые
        val i = list.indexOfFirst { it.id == id }
        var j = i + delta
        while (j in list.indices && !list[j].visible) j += delta
        if (i < 0 || j !in list.indices) return false
        val s = list.removeAt(i)
        list.add(j, s)
        updateTop { it.copy(status = list) }
        return true
    }

    /**
     * Новые приложения (07.11.2026): всё, что не было известно раньше, попадает в строку «Новые приложения» на «Доме».
     * Известные пакеты запоминаются; первый вызов после стартовой расстановки только записывает исходный набор, чтобы не высыпать всё сразу.
     * При выключенной настройке пакеты тоже запоминаются, но на «Дом» не попадают.
     */
    fun addNewApps(ctx: Context, own: List<String>, enabled: Boolean) {
        val known = sp.getStringSet("home_known_pkgs", null)
        if (known == null) {
            if (sp.getBoolean("home_seeded_apps", false) && own.isNotEmpty()) sp.edit().putStringSet("home_known_pkgs", own.toSet()).apply()
            return
        }
        // удалённые приложения забываются: после переустановки приложение снова считается новым
        val pm = ctx.packageManager
        val alive = known.filter { p -> runCatching { pm.getApplicationInfo(p, 0) }.isSuccess }.toSet()
        val fresh = own.filter { it !in alive }
        if (alive.size != known.size || fresh.isNotEmpty()) sp.edit().putStringSet("home_known_pkgs", alive + fresh).apply()
        if (fresh.isEmpty() || !enabled) return
        val placed = layout.rows.flatMap { it.items }.filter { it.kind == "app" }.map { it.ref }.toSet()
        val add = fresh.filter { it !in placed }
        if (add.isEmpty()) return
        val row = layout.rows.firstOrNull { it.id == sp.getString("home_newrow_id", "") }
        if (row != null) {
            for (p in add) addItem(row.id, HItem(newId("i"), "app", p, mode = "icon", showLabel = true))
        } else {
            val nr = HRow(newId("r"), ctx.getString(R.string.home_new_apps_row), true, "apps", add.map { HItem(newId("i"), "app", it, mode = "icon", showLabel = true) }, size = 72, itemMode = "icon")
            val last = layout.rows.lastOrNull()
            val keepLast = last != null && last.kind == "apps" && last.auto.isEmpty() && last.items.size == 1 && last.items[0].kind == "menu"
            set(layout.copy(rows = if (keepLast) layout.rows.dropLast(1) + nr + last!! else layout.rows + nr))
            sp.edit().putString("home_newrow_id", nr.id).apply()
        }
    }

    // ------------------------------------------------------------ первый запуск: каналы и входы

    /** Один раз добавляет строки для каналов приложений и входов HDMI, которые уже есть на устройстве. */
    fun seed(ctx: Context, channels: List<ChRow>, inputs: List<TvIn>, bannerApps: List<String> = emptyList(), otherApps: List<String> = emptyList(), youtube: String? = null) {
        val chDone = sp.getBoolean("home_seeded", false)
        val appsDone = sp.getBoolean("home_seeded_apps", false)
        if (chDone && appsDone) return
        val rows = ArrayList<HRow>()
        if (!chDone && inputs.isNotEmpty()) rows += HRow(newId("r"), ctx.getString(R.string.home_inputs), true, "apps", auto = "hdmi", count = 8, size = 56)
        if (!chDone && channels.any { it.key == "watch_next" }) rows += HRow(newId("r"), ctx.getString(R.string.home_watch_next), true, "cards", auto = "watch_next", count = 20, size = 100)
        // официальный YouTube есть почти у всех: две строки его каналов, «все» и «музыка»
        // строки создаются, только если у YouTube на этом устройстве есть каналы (на мобильной версии их нет)
        val ytCh = if (!chDone && youtube != null) channels.filter { it.key != "watch_next" && it.pkg == youtube } else emptyList()
        if (ytCh.isNotEmpty()) {
            rows += HRow(newId("r"), ctx.getString(R.string.yt_all), true, "cards", auto = "pkg:$youtube", count = 24, size = 100)
            if (ytCh.any { c -> listOf("музык", "music", "музик").any { c.title.lowercase().contains(it) } })
                rows += HRow(newId("r"), ctx.getString(R.string.yt_music), true, "cards", auto = "chm:$youtube|музык;music;музик", count = 24, size = 100)
        }
        val byPkg = if (chDone) emptyMap() else channels.filter { it.key != "watch_next" && it.pkg.isNotBlank() && (ytCh.isEmpty() || it.pkg != youtube) }.groupBy { it.pkg }
        for (pkg in byPkg.keys.take(3)) {
            val label = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            rows += HRow(newId("r"), label, true, "cards", auto = "pkg:$pkg", count = 24, size = 100)
        }
        fun appItems(pkgs: List<String>, mode: String) = pkgs.map { HItem(newId("i"), "app", it, mode = mode, showLabel = mode == "icon") }
        if (!appsDone && bannerApps.isNotEmpty()) rows += HRow(newId("r"), ctx.getString(R.string.home_apps_row), true, "apps", appItems(bannerApps, "banner"), size = 72, itemMode = "banner")
        if (!appsDone && otherApps.isNotEmpty()) rows += HRow(newId("r"), ctx.getString(R.string.home_more_apps), true, "apps", appItems(otherApps, "icon"), size = 72, itemMode = "icon")
        if (rows.isNotEmpty()) {
            val last = layout.rows.lastOrNull()
            val keepLast = last != null && last.kind == "apps" && last.auto.isEmpty() && last.items.size == 1 && last.items[0].kind == "menu"
            set(layout.copy(rows = if (keepLast) layout.rows.dropLast(1) + rows + last!! else layout.rows + rows))
        }
        // строки приложений добавляются один раз; каналы без разрешения читаются пустыми: для них пробуем снова при следующем запуске
        if (!appsDone && (bannerApps.isNotEmpty() || otherApps.isNotEmpty())) sp.edit().putBoolean("home_seeded_apps", true).apply()
        if (channels.isNotEmpty() || Channels.granted(ctx)) sp.edit().putBoolean("home_seeded", true).apply()
    }
}

/** Свои картинки значков и баннеров: файлы hm_<id>.png рядом с остальными значками (их забирает резервная копия). */
object HomeImg {
    private val cache = CacheBudget.track(object : LruCache<String, Bitmap>(CacheBudget.bytes(24)) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount })

    private fun file(ctx: Context, id: String) = File(File(ctx.filesDir, "icons").apply { mkdirs() }, "hm_$id.png")

    fun load(ctx: Context, id: String): Bitmap? {
        val f = file(ctx, id)
        if (!f.exists()) return null
        val key = id + "|" + f.lastModified()
        cache.get(key)?.let { return it }
        val b = runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() ?: return null
        cache.put(key, b)
        return b
    }

    /** Читает выбранную картинку, уменьшает до 640 по длинной стороне и сохраняет как PNG. */
    fun save(ctx: Context, id: String, uri: Uri): Boolean = runCatching {
        fun open() = ctx.contentResolver.openInputStream(uri)!!
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        var s = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (s * 2) >= 640) s *= 2
        val bmp = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) } ?: error("decode")
        file(ctx, id).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        HomeStore.bumpImg()
        true
    }.getOrDefault(false)

    /** Сохраняет готовую картинку (например, созданный баннер) как свою картинку плитки. */
    fun saveBitmap(ctx: Context, id: String, bmp: Bitmap): Boolean = runCatching {
        file(ctx, id).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        HomeStore.bumpImg()
        true
    }.getOrDefault(false)

    fun delete(id: String) {
        val c = HomeStore.appContext() ?: return
        if (runCatching { file(c, id).delete() }.getOrDefault(false)) HomeStore.bumpImg()
    }
}

/** Файлы фона: картинка лежит рядом со значками (её забирает резервная копия), видео и анимация в папке программы. menu: фон меню. */
object HomeBgFiles {
    /** Файл картинки фона; portrait: отдельная картинка для портретного экрана (телефон), если её выбирали (имя входит в резервную копию как значки). */
    fun image(ctx: Context, menu: Boolean = false, portrait: Boolean = false) =
        File(File(ctx.filesDir, "icons").apply { mkdirs() }, (if (menu) "hm_mbg" else "hm_bg") + (if (portrait) "_p" else "") + ".png")
    fun media(ctx: Context, menu: Boolean = false) = File(ctx.filesDir, if (menu) "menu_bg_media" else "home_bg_media")

    /** Причина последней неудачи сохранения фона: "" (нет), "read" (файл не открылся), "decode" (не картинка), "mem" (не хватило памяти). */
    @Volatile var lastFail: String = ""

    fun saveImage(ctx: Context, uri: Uri, menu: Boolean = false, portrait: Boolean = false): Boolean = runCatching {
        lastFail = ""
        val t0 = android.os.SystemClock.elapsedRealtime()
        fun open() = ctx.contentResolver.openInputStream(uri) ?: run { lastFail = "read"; error("open") }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { lastFail = "decode"; error("decode") }
        var s = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (s * 2) >= 1920) s *= 2
        val bmp = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) } ?: run { lastFail = "decode"; error("decode") }
        // файл называется .png, но фото без прозрачности пишем как JPEG: на слабых приставках PNG-сжатие 1920x1080 занимает секунды, а файл выходит в разы больше
        // (читается любым декодером независимо от имени; резервная копия берёт файл по имени)
        val tmp = File(image(ctx, menu).parentFile, "bg_img.tmp")
        tmp.outputStream().use { bmp.compress(if (bmp.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (bmp.hasAlpha()) 100 else 92, it) }
        val dst = image(ctx, menu, portrait)
        dst.delete()
        if (!tmp.renameTo(dst)) { tmp.copyTo(dst, overwrite = true); tmp.delete() }
        HomeStore.bumpImg()
        android.util.Log.i("TVApps", "фон: сохранён за " + (android.os.SystemClock.elapsedRealtime() - t0) + " мс, " + dst.length() / 1024 + " КБ")
        true
    }.getOrElse {
        if (lastFail.isEmpty()) lastFail = if (it is OutOfMemoryError) "mem" else "read"
        android.util.Log.w("TVApps", "фон: " + it.javaClass.simpleName + ": " + it.message)
        false
    }

    fun saveMedia(ctx: Context, uri: Uri, menu: Boolean = false): Boolean = runCatching {
        lastFail = ""
        val tmp = File(ctx.filesDir, "bg_media.tmp")
        ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        val f = media(ctx, menu)
        f.delete()
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        HomeStore.bumpImg()
        true
    }.getOrElse {
        lastFail = if (it is OutOfMemoryError) "mem" else "read"
        android.util.Log.w("TVApps", "фон (видео): " + it.javaClass.simpleName + ": " + it.message)
        false
    }
}
