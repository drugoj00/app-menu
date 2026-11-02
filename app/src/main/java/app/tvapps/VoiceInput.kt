package app.tvapps

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/** Программа, которая умеет распознавать речь (отвечает на RecognizerIntent). */
data class VoiceEngine(val id: String, val label: String, val component: ComponentName?)

/**
 * Голосовой ввод. Кнопка микрофона на пульте или аэромыши (клавиши «Поиск», «Голосовой ассистент», «Ассистент») открывает распознавание
 * той программой, которую выбрали при первом нажатии; выбор можно сменить в «Настройках экрана «Дом»» и долгим нажатием на значок микрофона.
 * Распознанный текст уходит в поиск меню.
 */
object Voice {
    /** Увеличивается при каждом нажатии кнопки микрофона (клавиша пульта или значок): экран, который открыт, запускает голос. */
    val trigger = mutableIntStateOf(0)
    /** Окно выбора программы распознавания открыто. */
    val picking = mutableStateOf(false)

    private const val KEY = "voice_engine"
    private val keys = setOf(KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST)

    /** Идёт привязка: следующая нажатая кнопка пульта становится кнопкой микрофона. */
    val capturing = mutableStateOf(false)
    private const val KEY_CUSTOM = "voice_key"
    private var customKey = -1
    private var customLoaded = false

    private fun custom(ctx: Context?): Int {
        if (!customLoaded && ctx != null) { customKey = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE).getInt(KEY_CUSTOM, -1); customLoaded = true }
        return customKey
    }
    fun customCode(ctx: Context): Int = custom(ctx)
    fun saveCustom(ctx: Context, code: Int) {
        customKey = code; customLoaded = true
        ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE).edit().putInt(KEY_CUSTOM, code).apply()
    }
    fun keyName(code: Int): String = if (code < 0) "" else KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

    fun isMicKey(code: Int) = code in keys || (customKey >= 0 && code == customKey)

    /** Вызывается из dispatchKeyEvent активности: true — клавишу взяли. */
    fun onKey(ctx: Context, ev: KeyEvent): Boolean {
        custom(ctx)
        if (capturing.value) {
            // «Назад» отменяет привязку; остальные кнопки (кроме стрелок и ОК) становятся кнопкой микрофона
            if (ev.keyCode == KeyEvent.KEYCODE_BACK) { if (ev.action == KeyEvent.ACTION_UP) capturing.value = false; return true }
            val nav = ev.keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
            if (!nav) {
                if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                    saveCustom(ctx, ev.keyCode); capturing.value = false
                    Toast.makeText(ctx, ctx.getString(R.string.voice_key_saved, keyName(ev.keyCode)), Toast.LENGTH_SHORT).show()
                }
                return true
            }
            return false
        }
        if (!isMicKey(ev.keyCode)) return false
        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) trigger.intValue++
        return true
    }

    fun request() { trigger.intValue++ }

    /** "" — ещё не выбирали (спросить), "system" — системное окно, иначе пакет/класс выбранной программы. */
    fun saved(ctx: Context): String = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE).getString(KEY, "") ?: ""
    fun save(ctx: Context, id: String) { ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE).edit().putString(KEY, id).apply() }

    fun engines(ctx: Context): List<VoiceEngine> {
        val pm = ctx.packageManager
        val ris = runCatching { pm.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), PackageManager.MATCH_ALL) }.getOrDefault(emptyList())
        val out = ArrayList<VoiceEngine>()
        for (ri in ris) {
            val ai = ri.activityInfo ?: continue
            val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(ai.packageName, 0)).toString() }.getOrDefault(ai.packageName)
            val act = ri.loadLabel(pm).toString()
            out.add(VoiceEngine(ai.packageName + "/" + ai.name, if (act.isBlank() || act == app) app else "$app: $act", ComponentName(ai.packageName, ai.name)))
        }
        // одинаковые названия различаем: известным пакетам даём понятное имя, остальным добавляем имя пакета
        val named = out.map { e ->
            val pkg = e.component?.packageName.orEmpty()
            when (pkg) {
                "com.google.android.katniss" -> e.copy(label = ctx.getString(R.string.voice_google_tv))
                "com.google.android.googlequicksearchbox" -> e.copy(label = ctx.getString(R.string.voice_google_app))
                else -> e
            }
        }
        val dup = named.groupBy { it.label }.filterValues { it.size > 1 }.keys
        return named.map { if (it.label in dup) it.copy(label = it.label + " (" + it.component?.packageName.orEmpty().substringAfterLast('.') + ")") else it }.distinctBy { it.id }
    }

    fun label(ctx: Context): String {
        val s = saved(ctx)
        return when {
            s.isEmpty() -> ctx.getString(R.string.voice_ask)
            s == "system" -> ctx.getString(R.string.voice_system)
            else -> engines(ctx).firstOrNull { it.id == s }?.label ?: ctx.getString(R.string.voice_system)
        }
    }

    fun intent(ctx: Context): Intent {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
        val s = saved(ctx)
        if (s.isNotEmpty() && s != "system") engines(ctx).firstOrNull { it.id == s }?.component?.let { i.component = it }
        return i
    }
}

/**
 * Слушатель кнопки микрофона на экране: запускает распознавание и отдаёт текст в [onText].
 * Первый раз (программа ещё не выбрана) показывает список программ, умеющих распознавать речь; выбор запоминается.
 */
@Composable
fun VoiceHost(onText: (String) -> Unit) {
    val ctx = LocalContext.current
    val onT by rememberUpdatedState(onText)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val txt = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!txt.isNullOrBlank()) onT(txt.trim())
    }
    fun launch() {
        try { launcher.launch(Voice.intent(ctx)) }
        catch (e: Exception) { Toast.makeText(ctx, ctx.getString(R.string.home_no_voice), Toast.LENGTH_LONG).show() }
    }
    var seen by remember { mutableIntStateOf(Voice.trigger.intValue) }
    LaunchedEffect(Voice.trigger.intValue) {
        val t = Voice.trigger.intValue
        if (t == seen) return@LaunchedEffect
        seen = t
        if ((ctx as? Activity)?.hasWindowFocus() == false) return@LaunchedEffect
        if (Voice.saved(ctx).isEmpty()) Voice.picking.value = true else launch()
    }
    if (Voice.picking.value) VoicePicker(onPicked = { Voice.picking.value = false; launch() }, onClose = { Voice.picking.value = false })
    if (Voice.capturing.value) {
        // ждём нажатия кнопки на пульте; окно подсказки закрывается кнопкой «Назад» или после привязки
        ListDialog(stringResource(R.string.voice_cap_title), listOf(Opt(stringResource(R.string.voice_cap_cancel), "", onClick = { Voice.capturing.value = false })), { Voice.capturing.value = false }, hint = stringResource(R.string.voice_cap_hint), onRawKey = { ev -> Voice.onKey(ctx, ev) })
    }
}

/** Окно выбора программы распознавания речи. */
@Composable
fun VoicePicker(onPicked: (() -> Unit)? = null, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cur = Voice.saved(ctx)
    val list = remember { Voice.engines(ctx) }
    val opts = buildList {
        add(Opt(stringResource(R.string.voice_system), if (cur == "system") "✓" else "", onClick = { Voice.save(ctx, "system"); onClose(); onPicked?.invoke() }))
        list.forEach { e -> add(Opt(e.label, if (cur == e.id) "✓" else "", onClick = { Voice.save(ctx, e.id); onClose(); onPicked?.invoke() })) }
        add(Opt(stringResource(R.string.voice_bind_key), Voice.keyName(Voice.customCode(ctx)), onClick = { onClose(); Voice.capturing.value = true }))
    }
    ListDialog(stringResource(R.string.voice_pick_title), opts, onClose, hint = stringResource(R.string.voice_pick_hint))
}

/**
 * Голосовой поиск «одним действием»: сразу открывает окно «Говорите» и отдаёт распознанный текст в поиск меню.
 * Нужна для кнопки микрофона вне нашего меню: её можно повесить на любую кнопку (например, в Pulsar действием «Ссылка»: tvapps://voice),
 * ярлык или другую программу (интент app.tvapps.VOICE). Если программу распознавания выбрали, берёт её, иначе системную.
 */
class VoiceActivity : androidx.activity.ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val txt = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!txt.isNullOrBlank()) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("from_home", true).putExtra("q", txt.trim()))
        }
        finish()
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        try { launcher.launch(Voice.intent(this)) }
        catch (e: Exception) { Toast.makeText(this, getString(R.string.home_no_voice), Toast.LENGTH_LONG).show(); finish() }
    }
}
