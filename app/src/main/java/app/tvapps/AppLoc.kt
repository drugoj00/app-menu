package app.tvapps

import java.util.Locale

/**
 * Язык программы для дат, дней недели, месяцев и погоды. Выбранный в настройках язык (ru, uk, en) главнее всего; «авто» означает язык СИСТЕМЫ,
 * а не Locale.getDefault(): тот процесс сам переставляет при выборе языка и после возврата на «авто» залипал бы на прежнем.
 */
object AppLoc {
    /** Язык системы, читается каждый раз заново (меняется на лету; не зависит от того, что программа ставила как язык по умолчанию). */
    val system: Locale get() =
        runCatching {
            val c = android.content.res.Resources.getSystem().configuration
            if (android.os.Build.VERSION.SDK_INT >= 24) c.locales[0] else @Suppress("DEPRECATION") c.locale
        }.getOrNull() ?: Locale.getDefault()

    /** Чистая часть: язык выбора ([lang], пусто — авто) и язык системы дают язык форматирования. */
    fun resolve(lang: String, system: Locale): Locale = if (lang.isBlank()) system else Locale(lang)

    fun locale(): Locale = resolve(Cfg.lang, system)

    /** Двухбуквенный код для запросов (погода, геокодер). */
    fun code(): String = locale().language.ifBlank { "en" }

    private var lastRecreate = 0L

    /**
     * Сменился ли язык с тех пор, как экран создан: выбор в настройках ([startLang]) или, при «авто», язык системы ([startSys]).
     * Не чаще раза в 5 секунд: защита от зацикливания пересоздания.
     */
    fun changedSince(startLang: String, startSys: String): Boolean {
        val changed = Cfg.lang != startLang || (Cfg.lang.isBlank() && system.language != startSys)
        if (!changed) return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRecreate < 5000) return false
        lastRecreate = now
        return true
    }
}
