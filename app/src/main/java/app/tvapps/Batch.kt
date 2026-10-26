package app.tvapps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File

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
        var ok = 0
        var bad = 0
        for (f0 in files) {
            if (f0.parts != null) { if (runCatching { Bundles.installAdb(ctx, f0) }.getOrDefault(false)) ok++ else bad++; continue }
            val f = runCatching { materialize(f0) }.getOrDefault(f0)
            val res = runCatching {
                val input = if (f.file != null) f.file.inputStream() else ctx.contentResolver.openInputStream(f.uri)!!
                input.use { Adb.installApk(it, f.size) }
            }.getOrDefault("")
            if (res.contains("Success", true)) ok++ else bad++
        }
        return ok to bad
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
