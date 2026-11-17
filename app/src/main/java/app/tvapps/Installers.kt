package app.tvapps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * 9.0 (код 44): ЕДИНАЯ ТОЧКА запуска установки и удаления. Любое окно установщика, любую установку и любое удаление Меню запускает только отсюда.
 *
 * Правила:
 *  • «ADB» (собственный клиент Меню) никогда не уходит на системные и неявные намерения: итог или причина отказа возвращаются вызывающему;
 *  • «ADB-phon» — только явная служба InstallService (тихо) либо явное окно ApkIntakeActivity, и только когда этот способ выбран;
 *  • «Системный» — сессия PackageInstaller нашего пакета (подтверждение показывает система через явное намерение) либо явный компонент системного
 *    установщика/удаления. Неявный ACTION_VIEW с типом APK не используется: его мог бы взять ADB-phon (в нём есть фильтр на APK и на любые файлы).
 *  • Каждый запуск пишется в журнал files/install_guard.log. Если способ и компонент не сходятся (системный способ ушёл на app.adbphon,
 *    намерение без компонента), запуск отклоняется, в отладочной сборке дополнительно падает AssertionError.
 */
object Installers {
    const val ADBPHON_PKG = "app.adbphon"
    const val M_SYSTEM = "system"
    const val M_ADBPHON = "adbphon"
    const val M_OTHER = "other"
    const val M_DELETE = "delete"
    const val M_SESSION_UI = "session_ui"

    private const val TAG = "Installers"
    private const val LOG = "install_guard.log"
    private const val LOG_MAX = 64 * 1024L

    /** Запись в журнал защиты (и в logcat). */
    fun note(ctx: Context, text: String) {
        Log.i(TAG, text)
        runCatching {
            val f = File(ctx.applicationContext.filesDir, LOG)
            if (f.length() > LOG_MAX) f.delete()
            f.appendText(java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + text + "\n")
        }
    }

    private fun describe(i: Intent): String = "act=" + i.action + " cmp=" + i.component?.flattenToShortString() + " pkg=" + i.`package` + " type=" + i.type

    /**
     * Проверка намерения перед запуском. Возвращает null, если можно запускать, иначе причину отказа.
     * [method] — выбранный способ (M_*); для окна, которое отдаёт сама система при сессии (M_SESSION_UI), ждём явный компонент не из ADB-phon.
     */
    fun verdict(method: String, i: Intent): String? {
        val cn = i.component
        return when (method) {
            M_ADBPHON -> if (cn?.packageName == ADBPHON_PKG) null else "способ ADB-phon, а компонент ${cn?.flattenToShortString()}"
            M_OTHER -> if (cn != null && cn.packageName != ADBPHON_PKG) null else "выбран другой установщик, а компонент ${cn?.flattenToShortString()}"
            else -> when {
                cn == null && i.`package` == null -> "системный способ, намерение без компонента"
                (cn?.packageName ?: i.`package`) == ADBPHON_PKG -> "системный способ ушёл на ADB-phon"
                else -> null
            }
        }
    }

    /** Единственный запуск окна установщика/удаления. true — окно запущено. */
    fun start(ctx: Context, method: String, i: Intent): Boolean {
        val app = ctx.applicationContext
        val bad = verdict(method, i)
        note(app, "start method=$method " + describe(i) + (if (bad != null) " ОТКАЗ: $bad" else " ok"))
        if (bad != null) {
            if (BuildConfig.DEBUG) throw AssertionError("Installers: $bad (${describe(i)})")
            return false
        }
        return try { app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Throwable) { note(app, "start error: " + (e.message ?: e.javaClass.simpleName)); false }
    }

    /** Системная установка: сессия PackageInstaller нашего пакета. Подтверждение показывает система. */
    fun sessionInstall(ctx: Context, files: List<File>, onFail: () -> Unit = {}, name: String = "") {
        note(ctx.applicationContext, "session files=${files.size} name=$name")
        SessionInstaller.install(ctx, files, onFail, name)
    }

    /** То же для файла, заданного адресом (очередь Pending): копия в кэш и сессия, без ACTION_VIEW. */
    fun sessionInstallUri(ctx: Context, uri: Uri, name: String = ""): Boolean {
        val app = ctx.applicationContext
        return runCatching {
            val tmp = File(File(app.cacheDir, "batch").apply { mkdirs() }, "q_" + System.nanoTime() + ".apk")
            val input = if (uri.scheme == "file") File(uri.path!!).inputStream() else app.contentResolver.openInputStream(uri)!!
            input.use { src -> tmp.outputStream().use { src.copyTo(it) } }
            sessionInstall(app, listOf(tmp), { runCatching { tmp.delete() } }, name.ifBlank { uri.lastPathSegment.orEmpty() })
            true
        }.onFailure { note(app, "sessionUri error: " + (it.message ?: it.javaClass.simpleName)) }.getOrDefault(false)
    }

    /** Явное системное окно удаления пакета (без выбора обработчика пользователем и без чужих приложений). */
    fun deleteIntent(ctx: Context, pkg: String): Intent {
        val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
        val pm = ctx.packageManager
        val list = runCatching { pm.queryIntentActivities(i, 0) }.getOrDefault(emptyList())
        val sys = list.mapNotNull { it.activityInfo }.filter { it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 && it.packageName != ADBPHON_PKG }
        val pick = sys.firstOrNull { it.packageName.contains("packageinstaller", true) } ?: sys.firstOrNull()
        if (pick != null) i.component = ComponentName(pick.packageName, pick.name)
        return i
    }

    /** Окно удаления пакета: только явный компонент. */
    fun uninstall(ctx: Context, pkg: String): Boolean {
        val i = deleteIntent(ctx, pkg)
        if (i.component == null) {
            // системного обработчика не нашлось: запуск без компонента отклоняется проверкой
            note(ctx.applicationContext, "uninstall $pkg: системный обработчик не найден")
            return false
        }
        return start(ctx, M_DELETE, i)
    }

    /** Окно действия пользователя, которое отдала система при сессии (STATUS_PENDING_USER_ACTION): явный компонент системного установщика. */
    fun sessionUi(ctx: Context, next: Intent?): Boolean = if (next == null) false else start(ctx, M_SESSION_UI, next)
}
