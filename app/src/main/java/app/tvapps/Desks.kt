package app.tvapps

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Стол, созданный пользователем: номер (больше 0) и название. */
data class Desk(val id: Int, val name: String)

/** Страница меню: номер стола (отрицательный у созданных сами при переполнении), название и приложения в порядке показа. */
data class DeskPage(val id: Int, val name: String, val pkgs: List<String>, val auto: Boolean)

/** Настройки одного стола (по номеру): фон (ARGB, 0 общий), столбцы, строки, размер значков (-1 общие), сортировка (пусто общая). */
data class DeskCfg(val bg: Long = 0L, val cols: Int = -1, val rows: Int = -1, val size: Int = -1, val sort: String = "")

/** Положение на одном столе: плитка в фокусе и первая видимая плитка с отступом. */
data class DeskMem(val focusPkg: String? = null, val focusIndex: Int = 0, val firstPkg: String? = null, val firstIndex: Int = 0, val firstOffset: Int = 0)

/** Чистая логика столов (проверяется юнит-тестом): раскладка по страницам с переполнением, круговое переключение, края сетки, удаление. */
object DeskLogic {
    /** Следующий стол по кругу: после последнего первый. */
    fun next(i: Int, n: Int): Int = if (n <= 0) 0 else (i + 1) % n

    /** Предыдущий стол по кругу: перед первым последний. */
    fun prev(i: Int, n: Int): Int = if (n <= 0) 0 else (i - 1 + n) % n

    fun step(i: Int, n: Int, dir: Int): Int = if (dir >= 0) next(i, n) else prev(i, n)

    /**
     * Нужно ли перейти на другой стол при стрелке в сторону: влево на первом столбце это -1, вправо на последнем столбце (и на последней плитке стола) это +1,
     * иначе 0. [i] номер плитки на столе, [cols] число столбцов, [size] число плиток на столе.
     */
    fun edgeDir(i: Int, cols: Int, size: Int, left: Boolean): Int {
        if (i < 0 || size <= 0) return 0
        val c = cols.coerceAtLeast(1)
        return if (left) (if (i % c == 0) -1 else 0) else (if (i % c == c - 1 || i == size - 1) 1 else 0)
    }

    /** Плитка на новом столе: в той же строке, что была, у ближайшего края (куда пришли), не дальше последней. */
    fun targetIndex(row: Int, cols: Int, size: Int, fromLeftEdge: Boolean): Int {
        if (size <= 0) return -1
        val c = cols.coerceAtLeast(1)
        val idx = if (fromLeftEdge) row * c else row * c + c - 1
        return idx.coerceIn(0, size - 1)
    }

    /** Номер страницы после удаления страницы [del] (всего было [n]): если удалена текущая, то предыдущая. */
    fun afterDelete(cur: Int, del: Int, n: Int): Int {
        val left = (n - 1).coerceAtLeast(1)
        return when {
            del < cur -> cur - 1
            del == cur -> (cur - 1).coerceAtLeast(0)
            else -> cur
        }.coerceIn(0, left - 1)
    }

    /** Номер страницы по запомненному id стола; нет такого, то первая. */
    fun indexOfId(ids: List<Int>, id: Int): Int = ids.indexOf(id).let { if (it < 0) 0 else it }

    /** Порядок вверх/вниз в списке: меняет соседей местами; за край не выходит. */
    fun <T> move(list: List<T>, index: Int, dir: Int): List<T> {
        val j = index + dir
        if (index !in list.indices || j !in list.indices) return list
        val m = list.toMutableList()
        val x = m[index]; m[index] = m[j]; m[j] = x
        return m
    }

    /**
     * Раскладка по столам. [order]: все приложения в порядке сортировки; [desks]: столы пользователя по порядку (могут быть пустыми);
     * [pins]: желаемый стол приложения; [cap]: сколько значков помещается на стол.
     * 1) Закреплённые идут на свой стол; если там больше вместимости, лишние (последние по порядку) уходят на следующий стол, и так дальше.
     * 2) Остальные в порядке сортировки занимают первый стол со свободным местом; мест нигде нет: создаётся стол в конце (id -1, -2, ...).
     * Ничего не пропадает; пустые столы пользователя остаются, лишних пустых нет. Внутри стола порядок сортировки. Результат не зависит от прошлых раскладок.
     */
    fun layout(
        order: List<String>, desks: List<Int>, pins: Map<String, Int>, cap: Int, maxPages: Int = Int.MAX_VALUE,
        capOf: ((Int) -> Int)? = null,
    ): List<Pair<Int, List<String>>> {
        val base = cap.coerceAtLeast(1)
        // вместимость значков страницы по её номеру в итоге (у столов с настройками сетки она своя)
        fun capAt(i: Int): Int = (capOf?.invoke(i) ?: base).coerceAtLeast(0)
        val rank = HashMap<String, Int>(order.size * 2)
        order.forEachIndexed { i, p -> rank[p] = i }
        // стол без закреплённых приложений (только что добавленный) остаётся пустым и стоит после всех заполненных: так «Добавить стол» всегда прибавляет страницу
        val pinnedAll = order.filter { (pins[it] ?: 0) in desks }.groupBy { pins[it]!! }
        val active = desks.filter { pinnedAll.containsKey(it) }
        val emptyDesks = desks.filter { !pinnedAll.containsKey(it) }
        val ids = ArrayList<Int>(active)
        val slots = HashMap<Int, MutableList<String>>()
        for (d in desks) slots[d] = ArrayList()
        var carry: List<String> = emptyList()
        for ((k, d) in active.withIndex()) {
            val all = pinnedAll[d].orEmpty() + carry
            val c = capAt(k)
            slots[d]!!.addAll(all.take(c))
            carry = all.drop(c)
        }
        var virt = 0
        fun room() = ids.size + emptyDesks.size < maxPages
        fun newVirtual(): Int { virt++; val id = -virt; ids.add(id); slots[id] = ArrayList(); return id }
        var guard = 0
        while (carry.isNotEmpty() && guard++ < 64) {
            if (!room() && ids.isNotEmpty()) break
            val id = newVirtual()
            val c = capAt(ids.size - 1)
            slots[id]!!.addAll(carry.take(c))
            carry = carry.drop(c)
        }
        if (carry.isNotEmpty()) slots[ids.last()]!!.addAll(carry)
        // свободные места: первая страница, где есть место
        var firstFree = 0
        val deskSet = desks.toSet()
        fun pickTarget(): Int {
            while (true) {
                while (firstFree < ids.size && slots[ids[firstFree]]!!.size >= capAt(firstFree)) firstFree++
                if (firstFree < ids.size) return ids[firstFree]
                if (room() || ids.isEmpty()) { newVirtual(); continue }
                return ids.last()
            }
        }
        for (p in order) {
            if ((pins[p] ?: 0) in deskSet) continue
            slots[pickTarget()]!!.add(p)
        }
        return (ids + emptyDesks).map { id -> id to slots[id]!!.sortedBy { rank[it] ?: Int.MAX_VALUE } }
    }
}

/**
 * Столы главного меню. По умолчанию выключены: меню с вертикальной прокруткой, как всегда. Включено: прокрутки нет, значки раскладываются по столам-страницам
 * (вместимость стола = столбцы x строки), остаток уходит на следующий стол со свободными местами. Столы и желаемые места приложений лежат в настройках
 * («tvapps», попадают в резервную копию). Текущий стол и фокус на каждом столе живут в памяти процесса: переживают уход в другое окно и возврат;
 * после закрытия программы запоминаются, только если включено «Запоминать положение после выхода».
 */
object Desks {
    /** Номер текущей страницы (с нуля). */
    val curIndex: Int get() = pageIds.indexOf(curId).let { if (it < 0) 0 else it }
    private fun cfgNow(): DeskCfg? = if (overrideActive && on) cfgs[curIndex] else null
    fun ovCols(): Int? = cfgNow()?.cols?.takeIf { it >= 0 }
    fun ovRows(): Int? = cfgNow()?.rows?.takeIf { it >= 0 }
    fun ovSize(): Int? = cfgNow()?.size?.takeIf { it >= 0 }
    fun cfgOf(i: Int): DeskCfg = cfgs[i] ?: DeskCfg()
    fun capTotal(i: Int): Int = caps[i] ?: cap

    /** Столов не больше девяти: цифры 1-9 на кнопках и на пульте. На последнем столе остаток, если он не помещается, листается. */
    const val MAX = 9
    /** Режим «Несколько столов» (переключатель в меню на пустом месте, по умолчанию выключен). Выключенный режим ничего не стирает: столы и закрепления ждут повторного включения. */
    var on by mutableStateOf(false); private set
    /** Переход на соседний стол стрелкой на краю сетки: всегда включён. */
    val edge: Boolean get() = true
    /** Столы пользователя в порядке показа. */
    var list by mutableStateOf<List<Desk>>(emptyList()); private set
    /** Желаемый стол приложения (пакет -> номер стола). */
    var pins by mutableStateOf<Map<String, Int>>(emptyMap()); private set
    /** Текущий стол: номер (у созданных сами при переполнении отрицательный). */
    var curId by mutableIntStateOf(0); private set
    /** Сколько значков помещается на стол (сообщает сетка; последнее значение хранится, чтобы первый кадр раскладывался сразу). */
    var cap by mutableIntStateOf(0); private set
    /** Настройки столов по номеру стола (с нуля). */
    var cfgs by mutableStateOf<Map<Int, DeskCfg>>(emptyMap()); private set
    /** Вместимость каждого стола (клеток всего), сообщает сетка при показе. */
    var caps by mutableStateOf<Map<Int, Int>>(emptyMap()); private set
    /** Номера страниц в порядке показа (задаёт главный экран) и признак, что настройки стола сейчас действуют (меню приложений, не настройки программы). */
    var pageIds by mutableStateOf<List<Int>>(emptyList())
    var overrideActive by mutableStateOf(false)
    private var nextId = 1
    private val mem = HashMap<Int, DeskMem>()
    private var loaded = false
    private lateinit var sp: SharedPreferences

    /** Читает настройки. Первый раз за процесс ещё и положение (только при «Запоминать положение»); после восстановления из копии положение не сбрасывается. */
    fun reload(prefs: SharedPreferences, rememberPos: Boolean) {
        sp = prefs
        nextId = prefs.getInt("desks_nextid", 1).coerceAtLeast(1)
        cap = prefs.getInt("desks_cap", 0).coerceAtLeast(0)
        caps = (prefs.getString("desks_caps", "") ?: "").split(",").mapNotNull { x -> x.split(":").let { p -> (p.getOrNull(0)?.toIntOrNull() ?: return@let null) to (p.getOrNull(1)?.toIntOrNull() ?: return@let null) } }.toMap()
        cfgs = (prefs.getString("desks_cfg", "") ?: "").split("\n").filter { it.isNotBlank() }.mapNotNull { l ->
            val p = l.split("\t")
            val i = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            i to DeskCfg(p.getOrNull(1)?.toLongOrNull() ?: 0L, p.getOrNull(2)?.toIntOrNull() ?: -1, p.getOrNull(3)?.toIntOrNull() ?: -1, p.getOrNull(4)?.toIntOrNull() ?: -1, p.getOrNull(5).orEmpty())
        }.toMap()
        list = (prefs.getString("desks_list", "") ?: "").split("\n").filter { it.isNotBlank() }.mapNotNull { l ->
            val p = l.split("\t")
            val id = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            if (id <= 0) null else Desk(id, p.getOrNull(1).orEmpty().ifBlank { "#" + id })
        }.take(MAX)
        pins = (prefs.getString("desks_pins", "") ?: "").split("\n").filter { it.isNotBlank() }.mapNotNull { l ->
            val p = l.split("\t")
            val id = p.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            val pkg = p.getOrNull(0).orEmpty()
            if (pkg.isBlank()) null else pkg to id
        }.toMap()
        // прежние ключи (переключатель режима, переход краями, новые приложения) больше не нужны: режим определяется числом столов
        on = prefs.getBoolean("desks_on", false)
        val old = listOf("desks_edge", "desks_newcur", "desks_known", "desks_wg", "desks_cols").filter { prefs.contains(it) }
        if (old.isNotEmpty()) prefs.edit().also { e -> old.forEach { e.remove(it) } }.apply()
        if (!loaded) {
            loaded = true
            if (rememberPos && on) {
                curId = prefs.getInt("desks_cur", 0)
                (prefs.getString("desks_dmem", "") ?: "").split("\n").filter { it.isNotBlank() }.forEach { l ->
                    val p = l.split("\t")
                    val id = p.getOrNull(0)?.toIntOrNull() ?: return@forEach
                    mem[id] = DeskMem(p.getOrNull(1)?.ifBlank { null }, p.getOrNull(2)?.toIntOrNull() ?: 0)
                }
            }
        }
        if (!on) curId = 0
    }

    private fun saveExtra() {
        sp.edit()
            .putString("desks_cfg", cfgs.entries.joinToString("\n") { (i, c) -> i.toString() + "\t" + c.bg + "\t" + c.cols + "\t" + c.rows + "\t" + c.size + "\t" + c.sort })
            .apply()
    }

    private fun saveList() {
        sp.edit()
            .putString("desks_list", list.joinToString("\n") { it.id.toString() + "\t" + it.name.replace(Regex("[\t\n]"), " ") })
            .putString("desks_pins", pins.entries.joinToString("\n") { it.key + "\t" + it.value })
            .putInt("desks_nextid", nextId)
            .apply()
    }

    /** Включает или выключает режим столов. Выключение возвращает обычное меню с прокруткой, столы и закрепления остаются в настройках. */
    fun enable(v: Boolean) {
        if (on == v) return
        if (!v) { mem[curId] = MenuMemory.snapshot(); curId = 0; MenuMemory.restore(null) }
        on = v
        sp.edit().putBoolean("desks_on", v).apply()
    }

    fun updateCap(index: Int, total: Int) {
        if (total <= 0) return
        if (caps[index] == total && cap == total) return
        caps = caps + (index to total)
        cap = total
        sp.edit().putInt("desks_cap", total).putString("desks_caps", caps.entries.joinToString(",") { it.key.toString() + ":" + it.value }).apply()
    }

    /** Меняет настройки стола с номером [i]; пустые значения убираются. */
    fun setCfg(i: Int, f: (DeskCfg) -> DeskCfg) {
        val n = f(cfgOf(i))
        cfgs = if (n == DeskCfg()) cfgs - i else cfgs + (i to n)
        saveExtra()
    }


    /** Добавляет стол пользователя (название пустое: «Стол N»). */
    fun add(name: String, template: (Int) -> String, afterId: Int = 0): Desk? {
        if (list.size >= MAX) return null
        val n = list.size + 1
        val d = Desk(nextId++, name.trim().ifBlank { template(n) }.take(24))
        val at = list.indexOfFirst { it.id == afterId }
        list = if (at >= 0) list.take(at + 1) + d + list.drop(at + 1) else list + d
        saveList()
        return d
    }

    fun rename(id: Int, name: String) {
        val v = name.trim().take(24)
        if (v.isBlank()) return
        list = list.map { if (it.id == id) it.copy(name = v) else it }
        saveList()
    }

    fun move(id: Int, dir: Int) {
        val i = list.indexOfFirst { it.id == id }
        list = DeskLogic.move(list, i, dir)
        saveList()
    }

    /** Удаляет стол: приложения перераспределяются; если он был текущим, показывается предыдущая страница. */
    fun remove(id: Int, pageIds: List<Int>) {
        if (list.none { it.id == id }) return
        val cur = pageIds.indexOf(curId).let { if (it < 0) 0 else it }
        val del = pageIds.indexOf(id)
        list = list.filter { it.id != id }
        pins = pins.filterValues { it != id }
        mem.remove(id)
        saveList()
        if (list.isEmpty()) { mem.clear(); curId = 0; MenuMemory.restore(null); return }
        if (curId == id) {
            val to = pageIds.getOrNull(DeskLogic.afterDelete(cur, del.coerceAtLeast(0), pageIds.size).coerceAtLeast(0)) ?: 0
            curId = if (to == id) 0 else to
            MenuMemory.restore(mem[curId])
        }
    }

    /**
     * Закрепляет приложения за столом [id]. Стол, созданный самим меню (отрицательный номер), при этом становится настоящим столом пользователя
     * (вместе с более ранними созданными сами). Возвращает номер настоящего стола.
     */
    fun pin(pkgs: Collection<String>, id: Int, template: (Int) -> String): Int {
        var real = id
        if (id < 0) {
            var last = 0
            for (v in 1..(-id)) {
                val nid = add("", template)?.id ?: last
                if (curId == -v) curId = nid
                last = nid
            }
            real = last
        }
        if (real <= 0) return 0
        pins = pins + pkgs.associateWith { real }
        saveList()
        return real
    }

    /** Снимает закрепление: приложение снова раскладывается по порядку. */
    fun unpin(pkgs: Collection<String>) {
        val s = pkgs.toSet()
        pins = pins.filterKeys { it !in s }
        saveList()
    }

    /** Приложение удалено: закрепление не нужно. */
    fun forget(gone: Collection<String>) {
        if (gone.isEmpty()) return
        val g = gone.toSet()
        if (pins.keys.none { it in g }) return
        pins = pins.filterKeys { it !in g }
        saveList()
    }

    /** Переключает на страницу [id]: фокус прежней запоминается, фокус новой возвращается. */
    fun switchTo(id: Int) {
        if (id == curId) return
        mem[curId] = MenuMemory.snapshot()
        curId = id
        MenuMemory.restore(mem[id])
    }

    /** На соседнюю страницу по кругу: [dir] +1 вперёд, -1 назад; [ids] номера страниц. */
    fun switchBy(dir: Int, ids: List<Int>) {
        if (ids.size < 2) return
        switchTo(ids[DeskLogic.step(DeskLogic.indexOfId(ids, curId), ids.size, dir)])
    }

    /** Сброс положения при выходе из меню без «Запоминать положение»: следующее открытие на первом столе, фокус сверху. */
    fun resetNav() { mem.clear(); curId = 0 }

    /** Положение текущего стола в память; при «Запоминать положение» ещё и в настройки. */
    fun save(rememberPos: Boolean) {
        if (!::sp.isInitialized || !on) return
        mem[curId] = MenuMemory.snapshot()
        if (rememberPos) {
            sp.edit().putInt("desks_cur", curId)
                .putString("desks_dmem", mem.entries.joinToString("\n") { (id, m) -> id.toString() + "\t" + (m.focusPkg ?: "") + "\t" + m.focusIndex })
                .apply()
        }
    }
}
