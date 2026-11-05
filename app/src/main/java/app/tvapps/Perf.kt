package app.tvapps

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 * Полная компиляция программы в машинный код (cmd package compile -m speed) с помощью root.
 * Установленная «с флешки» программа на Android 10 сначала выполняется интерпретатором и JIT, из-за этого первые прокрутки и первая раскладка
 * (журнал магнитолы: 560 мс) идут рывками; готовая компиляция убирает эту «холодность» сразу, а не после ночной оптимизации системы.
 * Делается один раз на версию, в фоне, только при включённом root.
 */
object CompileBoost {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE)
    private fun marker(ctx: Context): String {
        val pi = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        return BuildConfig.VERSION_NAME + "@" + (pi?.lastUpdateTime ?: 0L)
    }

    var running by mutableStateOf(false); private set
    var result by mutableStateOf(""); private set

    /** Текст состояния для настроек. */
    fun status(ctx: Context): String {
        val s = sp(ctx).getString("compile_res", "") ?: ""
        val m = sp(ctx).getString("compile_for", "") ?: ""
        return if (running) "компиляция идёт" else if (s.isEmpty()) "компиляция не выполнялась" else if (m != marker(ctx)) "компиляция прежней версии: $s" else "компиляция: $s"
    }

    fun loadResult(ctx: Context) { if (!running) result = status(ctx) }

    /** Запускает компиляцию, если root включён и для этой версии она ещё не сделана (или force). */
    fun runIfNeeded(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        if (running || !Adb.isRoot) return
        if (!force && sp(app).getString("compile_for", "") == marker(app) && (sp(app).getString("compile_res", "") ?: "").startsWith("speed")) return
        running = true
        result = "компиляция идёт"
        Thread {
            // немного подождать, чтобы не мешать запуску и первым кадрам
            runCatching { Thread.sleep(15_000) }
            val res: String = runCatching {
                val p = ProcessBuilder("su", "-c", "cmd package compile -m speed -f " + app.packageName).redirectErrorStream(true).start()
                val out = StringBuilder()
                val rd = Thread { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }.also { it.start() }
                val t0 = System.currentTimeMillis()
                var done = false
                while (System.currentTimeMillis() - t0 < 15 * 60_000L) {
                    if (runCatching { p.exitValue() }.isSuccess) { done = true; break }
                    Thread.sleep(500)
                }
                if (!done) p.destroy()
                rd.join(2000)
                val txt = out.toString().trim().replace("\n", " ").take(80)
                if (done && txt.contains("Success", true)) "speed, " + SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date()) else "не вышло (" + txt + ")"
            }.getOrElse { "не вышло (" + (it.message ?: "?").take(60) + ")" }
            sp(app).edit().putString("compile_res", res).putString("compile_for", marker(app)).apply()
            result = status(app)
            running = false
        }.also { it.isDaemon = true; it.name = "compile-boost"; it.start() }
    }
}
