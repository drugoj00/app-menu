package app.tvapps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.async

/** APK-файл из выбранной папки или из распакованного архива (тогда [file] задан). */
data class ApkFile(
    val name: String, val uri: Uri, val size: Long, val file: File? = null,
    /** Как получить файл, когда его ставят (для флешек по ADB и сетевых папок); null — файл уже есть. */
    val fetch: (suspend () -> File)? = null,
    /** Пакет из нескольких частей (APKS, APKM, XAPK): все нужные части; ставятся одним набором. */
    val parts: List<File>? = null,
    /** Файлы OBB этого пакета: копируются в память устройства после установки. */
    val obb: List<ObbItem> = emptyList(),
    val pkg: String? = null,
)

/**
 * Очередь для случая, когда нет ADB: системные окна подтверждения показываются по одному,
 * следующее открывается, когда пользователь вернулся в программу.
 */
object Pending {
    val uninstalls = ArrayDeque<String>()
    val installs = ArrayDeque<Uri>()

    fun next(ctx: Context): Boolean {
        uninstalls.removeFirstOrNull()?.let {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$it")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return true
        }
        installs.removeFirstOrNull()?.let {
            runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(it, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
            }
            return true
        }
        return false
    }
}

/** Пакетные действия: удаление выбранных и установка APK из папки или архива. */
object Batch {
    private fun batchDir(ctx: Context) = File(ctx.cacheDir, "batch")

    fun listApks(ctx: Context, tree: Uri): List<ApkFile> {
        val out = ArrayList<ApkFile>()
        runCatching {
            val docId = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE)
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (!name.lowercase().endsWith(".apk")) continue
                    out.add(ApkFile(name, DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), c.getLong(2)))
                }
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /** APK из обычной папки и её вложенных папок (до двух уровней). */
    fun listApksDir(dir: File): List<ApkFile> {
        val out = ArrayList<ApkFile>()
        fun walk(d: File, depth: Int) {
            d.listFiles()?.forEach { f ->
                if (out.size >= 300) return
                if (f.isFile && f.name.lowercase().endsWith(".apk")) out.add(ApkFile(f.name, Uri.fromFile(f), f.length(), f))
                else if (f.isDirectory && depth < 2 && !f.name.startsWith(".")) walk(f, depth + 1)
            }
        }
        walk(dir, 0)
        return out.sortedBy { it.name.lowercase() }
    }

    /** Достаёт APK из архива любого распространённого вида (ZIP, 7z, RAR, TAR и др.) во временную папку. */
    fun listArchive(ctx: Context, archive: Uri): List<ApkFile> = Archives.extractApks(ctx, archive)

    /** Адрес файла для системной программы установки: из архива через FileProvider, из папки как есть. */
    fun shareUri(ctx: Context, f: ApkFile): Uri =
        if (f.file != null) FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f.file) else f.uri

    /** Получает файл на устройство, если он ещё на флешке или в сетевой папке. */
    suspend fun materialize(f: ApkFile): ApkFile {
        if (f.file != null) return f
        val fl = f.fetch?.invoke() ?: return f
        return f.copy(file = fl, uri = Uri.fromFile(fl), size = fl.length())
    }

    /** Тихая установка по ADB. Возвращает (удачно, неудачно). */
    suspend fun installViaAdb(ctx: Context, files: List<ApkFile>): Pair<Int, Int> {
        val r = installDetailed(ctx, files) { _, _, _, _ -> }
        return r.count { it.ok } to r.count { !it.ok }
    }

    /** Долгая установка одного файла прерывается, чтобы пачка не висела: 8 минут (файлы до 200 МБ по слабому ADB). */
    private const val ONE_TIMEOUT_MS = 8 * 60_000L

    /**
     * Установка по ADB по одному файлу: [onStep] получает (сколько готово, всего, имя текущего или пусто, результаты). Итог каждого файла считается
     * по системе: «Success» в ответе или версия уже стоит на устройстве; каждый результат пишется в журнал [InstallLog].
     */
    suspend fun installDetailed(ctx: Context, files: List<ApkFile>, onStep: (Int, Int, String, List<InstallRes>) -> Unit): List<InstallRes> {
        val out = ArrayList<InstallRes>()
        // пока идёт пачка, «Дом» и меню не перечитывают список после каждого файла (общий пересчёт в конце)
        AppsMem.holdReload = true
        try {
            files.forEachIndexed { i, f0 ->
                onStep(i, files.size, f0.name, out.toList())
                // одна ошибка (в том числе нехватка памяти) не обрывает пачку и не роняет программу: запись в журнал и дальше
                val r = try { installOne(ctx, f0) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                    InstallLog.err(ctx, f0.name, e)
                    InstallRes(f0.name, f0.pkg.orEmpty(), false, friendlyReason(ctx, e.message ?: e.javaClass.simpleName))
                }
                out.add(r)
                InstallLog.add(ctx, r)
                onStep(i + 1, files.size, "", out.toList())
            }
        } finally { AppsMem.holdReload = false }
        return out
    }

    /** Понятная причина по коду отказа системы (код остаётся в скобках). */
    internal fun friendlyReason(ctx: Context, raw: String): String {
        val first = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val code = Regex("INSTALL_[A-Z_]+").find(raw)?.value
        val id = when {
            raw.contains("OLDER_SDK") -> R.string.install_err_sdk
            raw.contains("CONTAINER_ERROR") || raw.contains("INSUFFICIENT_STORAGE") -> R.string.install_err_space
            raw.contains("NO_MATCHING_ABIS") -> R.string.install_err_abi
            raw.contains("VERSION_DOWNGRADE") -> R.string.install_err_downgrade
            raw.contains("UPDATE_INCOMPATIBLE") || raw.contains("SIGNATURES") -> R.string.install_err_sig
            raw.contains("PARSE_FAILED") || raw.contains("INVALID_APK") -> R.string.install_err_bad
            else -> 0
        }
        if (id == 0) return first.take(120).ifBlank { "?" }
        val text = if (id == R.string.install_err_sdk) ctx.getString(id, android.os.Build.VERSION.RELEASE) else ctx.getString(id)
        return if (code != null) "$text ($code)" else text
    }

    private suspend fun installOne(ctx: Context, f0: ApkFile): InstallRes = kotlinx.coroutines.coroutineScope {
        var pkg = f0.pkg.orEmpty()
        var code = 0L
        // место в памяти устройства: файл копируется в сессию установки и распаковывается, нужно примерно вдвое больше его размера
        val need = f0.parts?.sumOf { it.length() } ?: f0.size
        if (need > 0) {
            val free = runCatching { android.os.StatFs(android.os.Environment.getDataDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)
            val want = need * 2 + 50L * 1024 * 1024
            if (free < want) return@coroutineScope InstallRes(f0.name, pkg, false, ctx.getString(R.string.install_no_space, free / 1048576L, want / 1048576L))
        }
        val job = async(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (f0.parts != null) {
                    InstallLog.meta(ctx, f0.parts)?.let { pkg = it.first; code = it.second }
                    Bundles.installAdbText(ctx, f0)
                } else {
                    val f = runCatching { materialize(f0) }.getOrDefault(f0)
                    f.file?.let { fl -> InstallLog.meta(ctx, listOf(fl))?.let { pkg = it.first; code = it.second } }
                    val input = if (f.file != null) f.file.inputStream() else ctx.contentResolver.openInputStream(f.uri)!!
                    input.use { Adb.installApk(it, f.size) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { InstallLog.err(ctx, f0.name, e); "ERROR " + (e.message ?: e.javaClass.simpleName) }
        }
        val text = kotlinx.coroutines.withTimeoutOrNull(ONE_TIMEOUT_MS) { job.await() } ?: "TIMEOUT"
        when {
            text.contains("Success", true) -> InstallRes(f0.name, pkg, true, if (text.contains("(")) ctx.getString(R.string.install_verified) else "")
            InstallLog.installedByFact(ctx, pkg, code) -> InstallRes(f0.name, pkg, true, ctx.getString(R.string.install_verified))
            text == "TIMEOUT" -> InstallRes(f0.name, pkg, false, ctx.getString(R.string.install_timeout))
            else -> InstallRes(f0.name, pkg, false, friendlyReason(ctx, text))
        }
    }

    /** Тихое удаление по ADB. Возвращает (удачно, неудачно). */
    suspend fun uninstallViaAdb(pkgs: List<String>): Pair<Int, Int> {
        var ok = 0
        var bad = 0
        for (p in pkgs) {
            val res = runCatching { Adb.shell("pm uninstall $p") }.getOrDefault("")
            if (res.contains("Success", true)) ok++ else bad++
        }
        return ok to bad
    }
}
