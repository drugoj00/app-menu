package app.tvapps

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Быстрые действия (15.0): список действий программы (последнее приложение, поиск, настройки, «Дом», голосовой поиск, очистка памяти,
 * выключение и перезагрузка при root или ADB) и назначение любого из них на кнопку пульта: короткое или долгое нажатие, в том числе цветные кнопки.
 * Назначения хранятся в общих настройках программы (ключ qa_bind), поэтому входят в резервную копию и синхронизацию.
 * Кнопки работают, пока открыто меню или «Дом» этой программы.
 */
object QuickActions {
    class Act(val id: String, val label: Int, val needsHome: Boolean = false, val needsControl: Boolean = false, val danger: Boolean = false)

    val all = listOf(
        Act("last", R.string.qa_a_last), Act("search", R.string.qa_a_search), Act("menu", R.string.qa_a_menu), Act("settings", R.string.qa_a_settings),
        Act("home", R.string.qa_a_home, needsHome = true), Act("voice", R.string.qa_a_voice), Act("mem", R.string.qa_a_mem), Act("list", R.string.qa_a_list),
        Act("reboot", R.string.qa_a_reboot, needsControl = true, danger = true), Act("poweroff", R.string.qa_a_poweroff, needsControl = true, danger = true),
    )

    /** Назначение: действие, код кнопки, долгое ли нажатие. */
    class Bind(val action: String, val code: Int, val long: Boolean)

    private const val KEY = "qa_bind"
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("tvapps", Context.MODE_PRIVATE)

    /** Растёт при любом изменении назначений: окна обновляют подписи. */
    val rev = mutableIntStateOf(0)
    /** Идёт ожидание кнопки для этого действия (id), иначе null. */
    val capturing = mutableStateOf<String?>(null)
    /** Окно со списком всех действий открыто (действие «Список быстрых действий»). */
    val listOpen = mutableStateOf(false)
    /** Действие, которое ждёт подтверждения (выключение, перезагрузка). */
    val confirm = mutableStateOf<String?>(null)

    @Volatile private var cache: List<Bind>? = null
    @Volatile private var cacheRaw: String = "\u0000"

    /** Разбор строки «действие=код:S;действие=код:L». Неверные записи пропускаются. */
    fun parse(raw: String): List<Bind> = raw.split(';').mapNotNull { e ->
        val a = e.substringBefore('=', "").trim()
        val r = e.substringAfter('=', "")
        val code = r.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
        val l = r.substringAfter(':', "S").trim().uppercase() == "L"
        if (a.isEmpty() || code <= 0) null else Bind(a, code, l)
    }

    fun format(l: List<Bind>): String = l.joinToString(";") { it.action + "=" + it.code + ":" + (if (it.long) "L" else "S") }

    fun binds(c: Context): List<Bind> {
        val raw = sp(c).getString(KEY, "").orEmpty()
        val cur = cache
        if (cur != null && raw == cacheRaw) return cur
        val p = parse(raw)
        cache = p; cacheRaw = raw
        return p
    }

    private fun save(c: Context, l: List<Bind>) {
        sp(c).edit().putString(KEY, format(l)).apply()
        cache = null
        rev.intValue++
    }

    fun bindOf(c: Context, action: String): Bind? = binds(c).firstOrNull { it.action == action }

    /** Назначает кнопку действию: прежняя кнопка этого действия снимается, а действие, которое сидело на той же кнопке (с тем же видом нажатия), теряет её. */
    fun assign(c: Context, action: String, code: Int, long: Boolean) {
        val l = binds(c).filter { it.action != action && !(it.code == code && it.long == long) } + Bind(action, code, long)
        save(c, l)
    }

    fun clear(c: Context, action: String) = save(c, binds(c).filter { it.action != action })

    fun setLong(c: Context, action: String, long: Boolean) {
        val b = bindOf(c, action) ?: return
        assign(c, action, b.code, long)
    }

    fun summary(c: Context): String {
        rev.intValue
        val n = binds(c).size
        return if (n == 0) c.getString(R.string.qa_none) else c.getString(R.string.qa_count, n)
    }

    fun keyName(code: Int): String = Voice.keyName(code).replace('_', ' ')

    fun actName(c: Context, id: String): String = all.firstOrNull { it.id == id }?.let { c.getString(it.label) } ?: id

    /** Действие можно выполнить сейчас: нужны «Дом» или управление (root/ADB). */
    fun available(c: Context, a: Act): Boolean = (!a.needsHome || Cfg.homeModule) && (!a.needsControl || Adb.isConnected)

    // ---------------------------------------------------------------- нажатия кнопок

    private var longFired = false
    private var downAt = 0L
    private var swallowCode = -1
    private var swallowUntil = 0L

    private val reserved = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
    )

    /** Подходит ли кнопка для назначения: не стрелки, не «ОК», не «Назад», не буквы и цифры (они нужны для ввода текста). */
    fun assignable(code: Int, printing: Boolean): Boolean = code > 0 && code !in reserved && !printing

    /**
     * Вызывается из dispatchKeyEvent меню и «Дома» до остальной обработки. true: клавишу взяли.
     * Режим ожидания кнопки: первая подходящая кнопка назначается действию, «Назад» отменяет.
     */
    fun onKey(a: Activity, ev: KeyEvent): Boolean {
        // отпускание только что назначенной кнопки не должно сразу выполнить действие
        if (ev.action == KeyEvent.ACTION_UP && ev.keyCode == swallowCode && android.os.SystemClock.uptimeMillis() < swallowUntil) { swallowCode = -1; return true }
        val cap = capturing.value
        if (cap != null) {
            if (ev.keyCode == KeyEvent.KEYCODE_BACK) { if (ev.action == KeyEvent.ACTION_UP) capturing.value = null; return true }
            if (ev.keyCode in reserved || ev.isPrintingKey) return false
            if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                if (Voice.isMicKey(ev.keyCode) || (HomeDock.keyMode(a) == 3 && HomeDock.keyCode(a) == ev.keyCode)) {
                    Toast.makeText(a, a.getString(R.string.qa_taken, keyName(ev.keyCode)), Toast.LENGTH_LONG).show()
                } else {
                    val old = bindOf(a, cap)
                    assign(a, cap, ev.keyCode, old?.long ?: false)
                    swallowCode = ev.keyCode; swallowUntil = android.os.SystemClock.uptimeMillis() + 1500L
                    Toast.makeText(a, a.getString(R.string.qa_saved, keyName(ev.keyCode)), Toast.LENGTH_SHORT).show()
                    capturing.value = null
                }
            }
            return true
        }
        val l = binds(a)
        if (l.isEmpty()) return false
        val code = ev.keyCode
        val sb = l.firstOrNull { it.code == code && !it.long }
        val lb = l.firstOrNull { it.code == code && it.long }
        if (sb == null && lb == null) return false
        when (ev.action) {
            KeyEvent.ACTION_DOWN -> {
                if (ev.repeatCount == 0) { longFired = false; downAt = ev.eventTime; return sb != null }
                if (lb != null && !longFired && ev.repeatCount >= 1) { longFired = true; run(a, lb.action); return true }
                return sb != null || longFired
            }
            KeyEvent.ACTION_UP -> {
                if (longFired) { longFired = false; return true }
                if (ev.isCanceled) return sb != null
                // удержание без повторов от пульта: долгим считается удержание от полсекунды
                if (lb != null && ev.eventTime - downAt >= 600L) { run(a, lb.action); return true }
                if (sb != null) { run(a, sb.action); return true }
                return false
            }
        }
        return false
    }

    // ---------------------------------------------------------------- выполнение

    private fun lastApp(a: Activity) {
        val e = AppsMem.list.filter { it.pkg != a.packageName && Cfg.lastLaunch(it.pkg) > 0L }.maxByOrNull { Cfg.lastLaunch(it.pkg) }
        if (e == null) { Toast.makeText(a, a.getString(R.string.qa_no_last), Toast.LENGTH_SHORT).show(); return }
        runCatching { Cfg.countLaunch(e.pkg); a.startActivity(e.launch) }
            .onFailure { Toast.makeText(a, a.getString(R.string.home_cant_open), Toast.LENGTH_SHORT).show() }
    }

    private fun toMenu(a: Activity, extra: (Intent) -> Unit = {}) {
        a.startActivity(Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).also(extra))
    }

    /** Выполняет действие из активности меню или «Дома». */
    fun run(a: Activity, id: String) {
        val act = all.firstOrNull { it.id == id } ?: return
        if (!available(a, act)) {
            Toast.makeText(a, a.getString(if (act.needsControl) R.string.qa_need_ctl else R.string.qa_need_home), Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            when (id) {
                "last" -> lastApp(a)
                "search" -> toMenu(a) { it.putExtra("open_search", true) }
                "menu" -> toMenu(a)
                "settings" -> toMenu(a) { it.putExtra("screen", "settings") }
                "home" -> if (a is MainActivity) goHome(a) else if (a !is HomeActivity) a.startActivity(Intent(a, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "voice" -> Voice.request()
                "mem" -> MemoryCleaner.cleanAsync(a) {}
                "list" -> listOpen.value = true
                "reboot", "poweroff" -> confirm.value = id
            }
        }
    }

    /** Выключение и перезагрузка после подтверждения. */
    fun power(c: Context, id: String) {
        val app = c.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { Adb.shell(if (id == "poweroff") "reboot -p" else "reboot") }
            if (!Adb.isConnected) android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(app, app.getString(R.string.qa_need_ctl), Toast.LENGTH_LONG).show() }
        }
    }
}

/** Окна быстрых действий, не привязанные к настройкам: список действий и подтверждение выключения. Ставится рядом с VoiceHost. */
@Composable
fun QuickActionsHost() {
    val ctx = LocalContext.current
    val act = ctx as? Activity ?: return
    QuickActions.rev.intValue
    if (QuickActions.listOpen.value) {
        val opts = QuickActions.all.filter { QuickActions.available(ctx, it) }.filter { it.id != "list" }.map { a ->
            val b = QuickActions.bindOf(ctx, a.id)
            Opt(stringResource(a.label), b?.let { QuickActions.keyName(it.code) + if (it.long) " ⏱" else "" }.orEmpty(), danger = a.danger, onClick = { QuickActions.listOpen.value = false; QuickActions.run(act, a.id) })
        }
        ListDialog(stringResource(R.string.qa_list_title), opts, { QuickActions.listOpen.value = false })
    }
    val cf = QuickActions.confirm.value
    if (cf != null) {
        ListDialog(
            stringResource(if (cf == "poweroff") R.string.qa_confirm_off else R.string.qa_confirm_reboot),
            listOf(
                Opt(stringResource(R.string.qa_yes), danger = true, onClick = { QuickActions.confirm.value = null; QuickActions.power(ctx, cf) }),
                Opt(stringResource(R.string.cancel), onClick = { QuickActions.confirm.value = null }),
            ),
            { QuickActions.confirm.value = null },
        )
    }
}

/** Окно настройки быстрых действий: список действий с назначенными кнопками; нажатие на действие открывает его карточку. */
@Composable
fun QuickActionsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    QuickActions.rev.intValue
    var detail by remember { mutableStateOf<String?>(null) }
    val d = detail
    val cap = QuickActions.capturing.value
    if (cap != null) {
        // ожидание кнопки: сами нажатия ловит QuickActions.onKey в активности
        ListDialog(
            stringResource(R.string.qa_wait_title) + ": " + QuickActions.actName(ctx, cap),
            listOf(Opt(stringResource(R.string.cancel), onClick = { QuickActions.capturing.value = null })),
            { QuickActions.capturing.value = null },
            hint = stringResource(R.string.qa_wait_hint),
            // окно со списком получает клавиши раньше активности: ловим кнопку здесь
            onRawKey = { ev -> (ctx as? Activity)?.let { QuickActions.onKey(it, ev) } ?: false },
        )
        return
    }
    if (d == null) {
        val opts = QuickActions.all.map { a ->
            val b = QuickActions.bindOf(ctx, a.id)
            Opt(stringResource(a.label), b?.let { QuickActions.keyName(it.code) + if (it.long) " ⏱" else "" }.orEmpty(), danger = a.danger, onClick = { detail = a.id })
        }
        ListDialog(stringResource(R.string.qa_title), opts, onClose, hint = stringResource(R.string.qa_dlg_hint))
    } else {
        val a = QuickActions.all.first { it.id == d }
        val b = QuickActions.bindOf(ctx, d)
        val act = ctx as? Activity
        val opts = buildList {
            add(Opt(stringResource(R.string.qa_assign), b?.let { QuickActions.keyName(it.code) } ?: stringResource(R.string.qa_key_none), onClick = { QuickActions.capturing.value = d }))
            if (b != null) {
                add(Opt(stringResource(R.string.qa_long), if (b.long) "✓" else "", onClick = { QuickActions.setLong(ctx, d, !b.long) }))
                add(Opt(stringResource(R.string.qa_clear), danger = true, onClick = { QuickActions.clear(ctx, d); detail = null }))
            }
            if (act != null && d != "list") add(Opt(stringResource(R.string.qa_run), onClick = { onClose(); QuickActions.run(act, d) }))
            add(Opt(stringResource(R.string.back_plain), onClick = { detail = null }))
        }
        ListDialog(stringResource(a.label), opts, { detail = null }, hint = stringResource(R.string.qa_card_hint))
    }
}
