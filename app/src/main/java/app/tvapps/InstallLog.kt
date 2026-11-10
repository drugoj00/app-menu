package app.tvapps

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/** Результат установки одного файла (APK или набора частей: APKS, APKM, XAPK). */
data class InstallRes(val name: String, val pkg: String, val ok: Boolean, val note: String, val time: Long = System.currentTimeMillis())

/**
 * Журнал установок (последние 200 записей, файл install_log.tsv во внутренней памяти программы): остаётся после закрытия окна,
 * чтобы после долгой установки пачки было видно, что поставилось и что нет (просьба пользователя 4PDA, 09.11.2026).
 */
object InstallLog {
    private const val MAX = 200
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "install_log.tsv")
    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    fun add(ctx: Context, r: InstallRes) = synchronized(lock) {
        runCatching {
            val f = file(ctx)
            val old = if (f.exists()) f.readLines() else emptyList()
            val line = listOf(r.time.toString(), if (r.ok) "1" else "0", clean(r.pkg), clean(r.name), clean(r.note)).joinToString("\t")
            f.writeText((old + line).takeLast(MAX).joinToString("\n") + "\n")
        }
    }

    /** Записи, новые первыми. */
    fun read(ctx: Context): List<InstallRes> = synchronized(lock) {
        runCatching {
            val f = file(ctx)
            if (!f.exists()) return emptyList()
            f.readLines().mapNotNull { l ->
                val p = l.split('\t')
                if (p.size < 5) null else InstallRes(p[3], p[2], p[1] == "1", p[4], p[0].toLongOrNull() ?: 0L)
            }.reversed()
        }.getOrDefault(emptyList())
    }

    fun clear(ctx: Context) = synchronized(lock) { runCatching { file(ctx).delete() } }

    /** Пакет и код версии основной части набора (или одного APK): по ним установка проверяется по факту. */
    @Suppress("DEPRECATION")
    fun meta(ctx: Context, parts: List<File>): Pair<String, Long>? {
        val pm = ctx.packageManager
        var fallback: Pair<String, Long>? = null
        for (p in parts) {
            val pi = runCatching { pm.getPackageArchiveInfo(p.path, 0) }.getOrNull() ?: continue
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            val pair = pi.packageName to code
            // основная часть без названия разбиения; части с названием (config.*, split_*) идут запасным вариантом
            val n = p.name.lowercase()
            if (!n.startsWith("split") && !n.startsWith("config")) return pair
            if (fallback == null) fallback = pair
        }
        return fallback
    }

    /** Установленный сейчас код версии пакета; null — не установлен. */
    @Suppress("DEPRECATION")
    fun installedCode(ctx: Context, pkg: String): Long? = runCatching {
        val pi = ctx.packageManager.getPackageInfo(pkg, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }.getOrNull()

    /** Поставлено ли на самом деле (версия не ниже ожидаемой): успех считается по системе, а не по тексту ответа. */
    fun installedByFact(ctx: Context, pkg: String, code: Long): Boolean {
        if (pkg.isBlank() || code <= 0) return false
        val now = installedCode(ctx, pkg) ?: return false
        return now >= code
    }
}
