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

    /**
     * «Заменить установленную версию» без ADB и root: системное окно удаления, затем системный установщик.
     * [stage]: 0 — удаление ещё не начато, 1 — открыто окно удаления (ждём возврата), 2 — удалено, можно ставить,
     * 3 — открыт системный установщик (ждём возврата и проверяем итог). Очередь хранится и в памяти (возврат из окна или поворот),
     * и в файле настроек: программа могла быть закрыта системой, пока пользователь был в системном окне.
     */
    class FixJob(val pkg: String, val name: String, val path: String, val code: Long, var stage: Int = 0, var ts: Long = System.currentTimeMillis())
    val fixes = ArrayDeque<FixJob>()
    private var fixLoaded = false
    private const val FIX_PREFS = "tvapps_fix"
    /** Срок годности задания: старое (например, после перезапуска через час) не должно само открывать системное окно. */
    private const val FIX_TTL_MS = 10 * 60 * 1000L
    private var pollBusy = false

    private fun fixSave(ctx: Context) {
        val s = fixes.joinToString("\n") { listOf(it.stage, it.code, it.pkg, it.name.replace('\t', ' ').replace('\n', ' '), it.path, it.ts).joinToString("\t") }
        ctx.getSharedPreferences(FIX_PREFS, Context.MODE_PRIVATE).edit().putString("q", s).apply()
    }

    private fun fixLoad(ctx: Context) {
        if (fixLoaded) return
        fixLoaded = true
        if (fixes.isNotEmpty()) return
        runCatching {
            ctx.getSharedPreferences(FIX_PREFS, Context.MODE_PRIVATE).getString("q", "").orEmpty().lineSequence().filter { it.isNotBlank() }.forEach { l ->
                val p = l.split('\t')
                if (p.size >= 5) fixes.add(FixJob(p[2], p[3], p[4], p[1].toLongOrNull() ?: 0L, p[0].toIntOrNull() ?: 0, p.getOrNull(5)?.toLongOrNull() ?: 0L))
            }
        }
        fixExpire(ctx)
    }

    /** Убирает задания старше срока годности (задание без метки времени из прошлой версии тоже считается старым). */
    private fun fixExpire(ctx: Context) {
        val now = System.currentTimeMillis()
        if (fixes.removeAll { now - it.ts > FIX_TTL_MS }) fixSave(ctx)
    }

    fun addFix(ctx: Context, pkg: String, name: String, path: String, code: Long) {
        fixLoad(ctx)
        fixes.add(FixJob(pkg, name, path, code))
        fixSave(ctx)
    }

    private fun fixDone(ctx: Context, j: FixJob, ok: Boolean, note: String) {
        fixes.remove(j)
        fixSave(ctx)
        InstallLog.add(ctx, InstallRes(j.name, j.pkg, ok, note))
        android.widget.Toast.makeText(ctx, j.name + ": " + note, android.widget.Toast.LENGTH_LONG).show()
        AppsMem.stamp = 0L; Cfg.bumpList()
    }

    /** Один шаг очереди замены. Возвращает true, если открыто системное окно (дальше продолжит возврат в программу). */
    private fun fixNext(ctx: Context): Boolean {
        fixLoad(ctx)
        fixExpire(ctx)
        if (pollBusy) return true
        while (true) {
            val j = fixes.firstOrNull() ?: return false
            val installed = InstallLog.installedCode(ctx, j.pkg) != null
            when (j.stage) {
                0 -> {
                    if (!installed) { j.stage = 2; continue }
                    j.stage = 1; j.ts = System.currentTimeMillis(); fixSave(ctx)
                    val ok = runCatching { ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + j.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                    if (ok) return true
                    fixDone(ctx, j, false, ctx.getString(R.string.install_fix_unfail))
                }
                1 -> {
                    // вернулись из системного окна удаления: если приложение всё ещё стоит, пользователь отказался
                    // система может не успеть снять пакет к возврату: ждём до ~5 с (шаг 300 мс), и только потом считаем отказом
                    if (installed) {
                        pollBusy = true
                        val app = ctx.applicationContext
                        Thread {
                            var gone = false
                            var i = 0
                            while (i < 17 && !gone) {
                                try { Thread.sleep(300) } catch (_: Exception) {}
                                gone = InstallLog.installedCode(app, j.pkg) == null
                                i++
                            }
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                pollBusy = false
                                if (gone) { j.stage = 2; j.ts = System.currentTimeMillis(); fixSave(app) }
                                else fixDone(app, j, false, app.getString(R.string.install_fix_declined))
                                next(app)
                            }
                        }.start()
                        return true
                    }
                    j.stage = 2
                }
                2 -> {
                    val f = File(j.path)
                    if (!f.exists()) { fixDone(ctx, j, false, ctx.getString(R.string.install_fix_nofile)); continue }
                    j.stage = 3; j.ts = System.currentTimeMillis(); fixSave(ctx)
                    val uri = Batch.shareUri(ctx, ApkFile(j.name, Uri.fromFile(f), f.length(), f))
                    val ok = runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                    }.isSuccess
                    if (ok) return true
                    fixDone(ctx, j, false, ctx.getString(R.string.install_fix_sys_fail))
                }
                else -> {
                    // вернулись из системного установщика: итог по факту (стоит ли именно эта версия)
                    if (InstallLog.installedByFact(ctx, j.pkg, j.code)) fixDone(ctx, j, true, ctx.getString(R.string.install_fix_ok_new))
                    else fixDone(ctx, j, false, ctx.getString(R.string.install_fix_sys_fail))
                }
            }
        }
    }

    fun next(ctx: Context): Boolean {
        if (fixNext(ctx)) return true
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
    fun shareUri(ctx: Context, f: ApkFile): Uri {
        val file = f.file ?: return f.uri
        return try {
            FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        } catch (e: Throwable) {
            // файл лежит вне описанных папок (приём по Wi‑Fi, флешка и т.п.): копия в cache/batch и адрес оттуда
            android.util.Log.w("TVApps", "shareUri: ${file.name}: ${e.javaClass.simpleName}: ${e.message}; копия в cache/batch")
            val dir = File(ctx.cacheDir, "batch").apply { mkdirs() }
            val copy = File(dir, "share_" + file.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            file.copyTo(copy, true)
            FileProvider.getUriForFile(ctx, ctx.packageName + ".files", copy)
        }
    }

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
            raw.contains("UPDATE_INCOMPATIBLE") || raw.contains("SIGNATURES") || raw.contains("INCONSISTENT_CERTIFICATES") -> R.string.install_err_sig
            // файл точно не APK или манифест нечитаем: так и пишем; любой другой отказ при разборе (UNEXPECTED_EXCEPTION, NO_CERTIFICATES и др.)
            // не обязательно значит «файл повреждён», поэтому нейтрально и с кодом системы
            raw.contains("PARSE_FAILED_NOT_APK") || raw.contains("MANIFEST_MALFORMED") || raw.contains("BAD_MANIFEST") || raw.contains("INVALID_APK") -> R.string.install_err_bad
            raw.contains("PARSE_FAILED") -> R.string.install_err_parse
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
            else -> InstallRes(f0.name, pkg, false, friendlyReason(ctx, text), raw = text)
        }
    }

    /** Чем можно исправить отказ: поставить версию ниже установленной или заменить установленную с другой подписью. */
    enum class Fix { DOWNGRADE, SIGNATURE }

    /**
     * Можно ли предложить исправление для файла [f], не принятого системой с ответом [raw]. Только когда файл сам по себе читается
     * (пакет и версия определились) и такое приложение уже стоит: иначе удалять установленное было бы незачем и опасно.
     */
    suspend fun fixFor(ctx: Context, f: ApkFile, raw: String): Fix? {
        if (raw.isBlank()) return null
        val ff = runCatching { materialize(f) }.getOrDefault(f)
        val files = ff.parts ?: ff.file?.let { listOf(it) } ?: return null
        val m = InstallLog.meta(ctx, files) ?: return null
        // саму программу не удаляем: её заменяет обычная установка поверх
        if (m.first == ctx.packageName) return null
        val now = InstallLog.installedCode(ctx, m.first) ?: return null
        return when {
            raw.contains("VERSION_DOWNGRADE") -> Fix.DOWNGRADE
            raw.contains("UPDATE_INCOMPATIBLE") || raw.contains("INCONSISTENT_CERTIFICATES") || raw.contains("SIGNATURES") -> Fix.SIGNATURE
            // общий отказ разбора: исправление предлагаем только при реальном несовпадении подписей или версии ниже (сравнение самой системой), иначе нейтральное сообщение с кодом
            raw.contains("PARSE_FAILED") -> predictConflict(ctx, f)
            else -> null
        }
    }

    /**
     * Заранее (без ADB и root, силами самой системы) определяет, что файл [f] система не поставит поверх установленного приложения:
     * подпись файла не входит в подписи установленного (SIGNATURE) или версия в файле ниже установленной (DOWNGRADE).
     * Отдельные файлы, не наборы частей; саму программу не трогаем. null — конфликта нет или определить нельзя.
     */
    suspend fun predictConflict(ctx: Context, f: ApkFile): Fix? {
        if (f.parts != null) return null
        val ff = runCatching { materialize(f) }.getOrDefault(f)
        val file = ff.file ?: return null
        return runCatching {
            val pm = ctx.packageManager
            val sdk = android.os.Build.VERSION.SDK_INT
            @Suppress("DEPRECATION")
            val flags = if (sdk >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES
            val ai = pm.getPackageArchiveInfo(file.path, flags) ?: return@runCatching null
            val pkg = ai.packageName ?: return@runCatching null
            if (pkg == ctx.packageName) return@runCatching null
            val installed = pm.getPackageInfo(pkg, flags)
            @Suppress("DEPRECATION")
            val newCode = if (sdk >= 28) ai.longVersionCode else ai.versionCode.toLong()
            @Suppress("DEPRECATION")
            val oldCode = if (sdk >= 28) installed.longVersionCode else installed.versionCode.toLong()
            val sigMismatch = if (sdk >= 28) {
                val signers = ai.signingInfo?.apkContentsSigners
                if (signers == null || signers.isEmpty()) false
                else signers.none { pm.hasSigningCertificate(pkg, it.toByteArray(), android.content.pm.PackageManager.CERT_INPUT_RAW_X509) }
            } else {
                @Suppress("DEPRECATION")
                val a = ai.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
                @Suppress("DEPRECATION")
                val b = installed.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
                a.isNotEmpty() && b.isNotEmpty() && a != b
            }
            when {
                sigMismatch -> Fix.SIGNATURE
                oldCode > newCode -> Fix.DOWNGRADE
                else -> null
            }
        }.getOrNull()
    }

    /** Копия установленной сейчас версии (основной APK и части) на случай, если новую поставить не выйдет. */
    private fun backupInstalled(ctx: Context, pkg: String): List<File>? = runCatching {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        val srcs = listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
        val dir = File(ctx.cacheDir, "batch/rollback").apply { deleteRecursively(); mkdirs() }
        srcs.mapIndexed { i, s -> File(s).copyTo(File(dir, if (i == 0) "base.apk" else "split$i.apk"), true) }
    }.getOrNull()

    /**
     * Исправление отказа после подтверждения пользователя. Понижение: сначала поверх («-r -d»; Android на некоторых прошивках разрешает это
     * только для отлаживаемых приложений), если система снова отказала, установленная версия удаляется с сохранением данных («pm uninstall -k»)
     * и ставится версия из файла. Другая подпись: установленная версия удаляется вместе с данными и ставится версия из файла.
     * Перед удалением снимается копия установленной версии: если новая не встанет, прежняя возвращается (без данных при другой подписи).
     */
    suspend fun fixInstall(ctx: Context, f0: ApkFile, fix: Fix): InstallRes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val f = runCatching { materialize(f0) }.getOrDefault(f0)
        val files = f.parts ?: f.file?.let { listOf(it) } ?: return@withContext InstallRes(f0.name, f0.pkg.orEmpty(), false, ctx.getString(R.string.install_fix_nofile))
        val m = InstallLog.meta(ctx, files)
        val pkg = m?.first ?: f0.pkg.orEmpty()
        val code = m?.second ?: 0L
        if (pkg.isBlank()) return@withContext InstallRes(f0.name, pkg, false, ctx.getString(R.string.install_fix_nofile))
        fun good(t: String) = t.contains("Success", true) || InstallLog.installedByFact(ctx, pkg, code)
        var text = ""
        try {
            if (fix == Fix.DOWNGRADE) {
                text = Adb.installFiles(files, "-r -d")
                if (good(text)) return@withContext InstallRes(f0.name, pkg, true, ctx.getString(R.string.install_fix_ok_over))
            }
            val backup = backupInstalled(ctx, pkg)
            val un = runCatching { Adb.shell("pm uninstall " + (if (fix == Fix.DOWNGRADE) "-k " else "") + pkg) }.getOrDefault("")
            if (!un.contains("Success", true)) {
                val why = friendlyReason(ctx, un).ifBlank { un.take(80) }
                return@withContext InstallRes(f0.name, pkg, false, ctx.getString(R.string.install_fix_unfail) + ": " + why, raw = un)
            }
            text = Adb.installFiles(files, "-r")
            if (good(text)) return@withContext InstallRes(f0.name, pkg, true, ctx.getString(if (fix == Fix.DOWNGRADE) R.string.install_fix_ok_keep else R.string.install_fix_ok_new))
            // новая версия не встала: возвращаем прежнюю из копии
            val back = backup?.let { runCatching { Adb.installFiles(it, "-r") }.getOrDefault("") }.orEmpty()
            val restored = back.contains("Success", true) || InstallLog.installedCode(ctx, pkg) != null
            InstallRes(f0.name, pkg, false, friendlyReason(ctx, text) + "; " + ctx.getString(if (restored) R.string.install_fix_restored else R.string.install_fix_lost), raw = text)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
            InstallLog.err(ctx, f0.name, e)
            InstallRes(f0.name, pkg, false, friendlyReason(ctx, e.message ?: e.javaClass.simpleName))
        } finally { runCatching { File(ctx.cacheDir, "batch/rollback").deleteRecursively() } }
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
