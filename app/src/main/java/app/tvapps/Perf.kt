package app.tvapps

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Метки времени запуска в журнале (тег TVApps): сколько прошло от старта процесса до этапа загрузки списка. */
object Startup {
    fun mark(what: String) {
        val since = if (android.os.Build.VERSION.SDK_INT >= 24) android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime() else -1L
        android.util.Log.i("TVApps", "запуск: $what, ${since} мс от старта процесса")
    }
}

/**
 * Облегчённый режим для слабого железа (автомагнитолы: Unisoc, 160 dpi при реальных ~270 ppi). В нём плитка значка дешевле в создании и в показе:
 * без анимации перестановки при прокрутке, без анимации масштаба, без наведения мыши и волны нажатия, градиент фона неподвижный.
 * Настройка: авто (магнитола = включён), всегда включён, всегда выключен.
 */
object Lite {
    /** 0 = авто, 1 = включён, 2 = выключен. */
    var mode by mutableStateOf(0); private set
    internal var auto = false; private set

    val on: Boolean get() = when (mode) { 1 -> true; 2 -> false; else -> auto }

    fun init(ctx: Context) {
        auto = UiScale.of(ctx) > 1.01f
        mode = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE).getInt("lite", 0)
        DenseGrid.init(ctx)
    }

    fun change(ctx: Context, m: Int) {
        mode = m
        ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE).edit().putInt("lite", m).apply()
    }
}

/**
 * Плотная сетка как у системного лаунчера магнитолы: шаг 7% ширины и 19,6% высоты экрана, значок на тёмной плашке, подпись 13 sp (см. GridMetrics).
 * Настройка: авто (на магнитолах), всегда плотная, обычная.
 */
object DenseGrid {
    /** 0 = авто, 1 = плотная, 2 = обычная. */
    var mode by mutableStateOf(0); private set
    val on: Boolean get() = when (mode) { 1 -> true; 2 -> false; else -> Lite.auto }

    fun init(ctx: Context) { mode = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE).getInt("dense", 0) }

    fun change(ctx: Context, m: Int) {
        mode = m
        ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE).edit().putInt("dense", m).apply()
    }
}

/**
 * Полная компиляция программы в машинный код (cmd package compile -m speed) с помощью root, без участия пользователя и без пункта в настройках.
 * Установленная «с флешки» программа на Android 10 сначала выполняется интерпретатором и JIT, из-за этого первые прокрутки и первая раскладка
 * идут рывками; готовая компиляция убирает эту «холодность» сразу, а не после ночной оптимизации системы.
 * Запускается через 15 с после старта (не мешает первым кадрам), только при root или подключённом ADB, когда это нужно:
 *  - новая версия или обновление программы, результата ещё нет или он от другой версии;
 *  - система сбросила компиляцию (обновление ART или системы, фоновая оптимизация, очистка): состояние читается из `cmd package dump`
 *    (статус `speed` = скомпилировано) при запуске, не чаще раза в 12 часов.
 * Не запускается, пока играет звук или видео. Команда `cmd package compile -m speed -f` разрешена и пользователю shell (ADB): проверено на Android 15 (A14) без root. Без root и без ADB ничего не делает и не пишет. Результат только в настройках программы (compile_res, compile_for), ошибки в журнале.
 */
object CompileBoost {
    private const val TAG = "TVApps"
    private const val CHECK_EVERY_MS = 12 * 60 * 60_000L
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE)
    private fun marker(ctx: Context): String {
        val pi = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        return BuildConfig.VERSION_NAME + "@" + (pi?.lastUpdateTime ?: 0L)
    }

    /** Все ли статусы dex-файлов в выводе `cmd package dump` равны speed (разбор вынесен отдельно ради теста). Нет ни одного статуса: false. */
    internal fun allSpeed(dump: String): Boolean {
        val st = Regex("\\[status=([\\w-]+)\\]").findAll(dump).map { it.groupValues[1] }.toList()
        return st.isNotEmpty() && st.all { it == "speed" || it == "everything" }
    }

    /** Нужно ли запускать: [doneForThisVersion] компиляция этой версии уже сделана; [lastCheck] время последней проверки состояния; [now] сейчас. */
    internal fun needsStateCheck(doneForThisVersion: Boolean, lastCheck: Long, now: Long): Boolean =
        !doneForThisVersion || lastCheck <= 0L || now - lastCheck >= CHECK_EVERY_MS || lastCheck > now

    /** Вызывается при запуске меню и «Дома». Работает в фоне, интерфейс не блокирует; нужен root или подключённый ADB (проверяется после паузы: ADB подключается чуть позже запуска). */
    fun runIfNeeded(ctx: Context) {
        val app = ctx.applicationContext
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                // немного подождать, чтобы не мешать запуску и первым кадрам
                Thread.sleep(15_000)
                // с root в фоне su не запускается (раньше запускался): компиляция только через ADB
                if (Adb.isRoot || !Adb.isConnected) return@Thread
                val done = sp(app).getString("compile_for", "") == marker(app) && (sp(app).getString("compile_res", "") ?: "").startsWith("speed")
                val now = System.currentTimeMillis()
                if (!needsStateCheck(done, sp(app).getLong("compile_chk", 0L), now)) return@Thread
                // воспроизведение: ждём конца (до получаса), иначе в следующий запуск
                var waited = 0
                while (waited < 30 && musicActive(app)) { Thread.sleep(60_000); waited++ }
                if (musicActive(app)) return@Thread
                if (done) {
                    // версия уже компилировалась: смотрим, не сбросила ли система
                    val dump = run("cmd package dump " + app.packageName, 60_000)
                    sp(app).edit().putLong("compile_chk", now).apply()
                    if (allSpeed(dump)) return@Thread
                    Log.i(TAG, "компиляция сброшена системой, запускается заново")
                }
                val out = run("if command -v nice >/dev/null 2>&1; then nice -n 19 cmd package compile -m speed -f ${app.packageName}; else cmd package compile -m speed -f ${app.packageName}; fi", 15 * 60_000L)
                val ok = out.contains("Success", true)
                if (!ok) Log.w(TAG, "компиляция не вышла: " + out.replace("\n", " ").take(120))
                val res = if (ok) "speed, " + SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date()) else "не вышло"
                sp(app).edit().putString("compile_res", res).putString("compile_for", marker(app)).putLong("compile_chk", System.currentTimeMillis()).apply()
            } catch (e: Throwable) {
                Log.w(TAG, "компиляция: " + (e.message ?: e.javaClass.simpleName))
            } finally { running.set(false) }
        }.also { it.isDaemon = true; it.name = "compile-boost"; it.start() }
    }

    private fun musicActive(ctx: Context): Boolean = runCatching { (ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).isMusicActive }.getOrDefault(false)

    /** Команда от root (общий постоянный root-процесс) или, без root, от пользователя shell через подключённый ADB. */
    private fun run(cmd: String, timeoutMs: Long): String = kotlinx.coroutines.runBlocking { Adb.shell(cmd, timeoutMs) }
}
