package app.tvapps

/**
 * Правила кэша значков на диске (чистая логика, проверяется юнит-тестом).
 * Имя файла: <пакет>_<lastUpdateTime>_<версия отрисовки значков>_<versionCode программы>.png. Файл с другим временем обновления приложения,
 * другой версией отрисовки или другой версией программы просто не читается и удаляется: старый «чёрный» значок не может жить в кэше вечно
 * (раньше имя было <пакет>.png и файл писался только если его не было).
 */
object IconCacheRules {
    /** Увеличивать при любой смене способа отрисовки значков (IconQuality, размеры, детектор). */
    const val ICON_PIPELINE_VERSION = 3

    private val NAME = Regex("^(.+)_(\\d+)_(\\d+)_(\\d+)\\.png$")

    fun name(pkg: String, updated: Long, pipeline: Int, appVersion: Int): String = "${pkg}_${updated}_${pipeline}_$appVersion.png"

    data class Quad(val pkg: String, val updated: Long, val pipeline: Int, val appVersion: Int)

    /** Разбор имени: пакет, время обновления, версия отрисовки, версия программы; null, если имя не наше (например старое <пакет>.png). */
    fun parse(file: String): Quad? {
        val m = NAME.find(file) ?: return null
        return Quad(m.groupValues[1], m.groupValues[2].toLong(), m.groupValues[3].toInt(), m.groupValues[4].toInt())
    }

    /** Можно ли читать этот файл для пакета с указанным (по данным PackageManager) временем обновления. */
    fun isCurrent(file: String, pkg: String, pmUpdated: Long, pipeline: Int, appVersion: Int): Boolean {
        val q = parse(file) ?: return false
        return q.pkg == pkg && q.updated == pmUpdated && q.pipeline == pipeline && q.appVersion == appVersion
    }

    /** Файлы, которые надо удалить: все файлы пакета, кроме текущего ([keep]: пакет -> имя), и файлы старого формата имени. */
    fun stale(files: List<String>, keep: Map<String, String>): List<String> = files.filter { f ->
        val q = parse(f)
        q == null || keep[q.pkg] != f
    }

    /** Брать ли значок из кэша: имя актуально и картинка не пустая и не чёрная (иначе перерисовать). */
    fun useCached(fileIsCurrent: Boolean, pixelsBad: Boolean): Boolean = fileIsCurrent && !pixelsBad
}
