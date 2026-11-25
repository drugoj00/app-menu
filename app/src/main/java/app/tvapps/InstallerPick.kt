@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.tvapps

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Окно «Установить через…» (8.1): при обновлении программы, обновлении других приложений и установке принятых файлов.
 * Варианты: ADB-phon 10.2+ (его окно приёма APK), системный установщик (сессия PackageInstaller), ADB этого устройства,
 * другие установщики из системного списка (App Manager и др.). Недоступные варианты серые с причиной.
 * Настройка «Спрашивать, чем устанавливать» включена по умолчанию; «Запомнить выбор» выключает вопрос и запоминает установщик.
 * Итог установки у передачи другому установщику определяется по версии (SelfUpd.onBack), без ожидания ответа.
 */
object InstallerPick {
    const val ADBPHON = "adbphon"
    const val SYSTEM = "system"
    const val ADB = "adb"
    private const val ADBPHON_PKG = "app.adbphon"
    private const val ADBPHON_INTAKE = "app.adbphon.ApkIntakeActivity"
    private const val MIME = "application/vnd.android.package-archive"

    class Opt(val id: String, val label: String, val enabled: Boolean, val reason: String = "", val other: Boolean = false)

    class Req(val name: String, val opts: List<Opt>, val done: CompletableDeferred<Pair<String, Boolean>?>)

    /** Открытый запрос выбора; рисуется [InstallerPickHost]. */
    var req by mutableStateOf<Req?>(null)
    private val lock = Mutex()

    /** Версия ADB-phon не ниже 10.2 и есть его окно приёма APK. Возвращает (годен, причина). */
    private fun adbphonState(ctx: Context): Pair<Boolean, String> {
        val pi = runCatching { ctx.packageManager.getPackageInfo(ADBPHON_PKG, 0) }.getOrNull()
            ?: return false to ctx.getString(R.string.inst_no_adbphon)
        val v = pi.versionName.orEmpty()
        if (Updates.compareVersions(v, "10.2") < 0) return false to ctx.getString(R.string.inst_old_adbphon, v)
        val has = runCatching { ctx.packageManager.getActivityInfo(ComponentName(ADBPHON_PKG, ADBPHON_INTAKE), 0) }.isSuccess
        return if (has) true to "" else false to ctx.getString(R.string.inst_no_adbphon)
    }

    /** Сторонние установщики APK из системного списка (без нас, ADB-phon и системного установщика: они отдельными строками). */
    private fun others(ctx: Context, sample: Intent): List<Opt> {
        val pm = ctx.packageManager
        val list = runCatching { pm.queryIntentActivities(sample, 0) }.getOrDefault(emptyList())
        return list.mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            val p = ai.packageName
            if (p == ctx.packageName || p == ADBPHON_PKG || p.contains("packageinstaller", true)) return@mapNotNull null
            if (!ai.exported) return@mapNotNull null
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(p).ifBlank { p }
            Opt("cmp:" + p + "/" + ai.name, label, true, other = true)
        }.distinctBy { it.id }.sortedBy { it.label.lowercase() }
    }

    private fun sampleIntent(ctx: Context, file: File?): Intent {
        val uri = file?.let { runCatching { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) }.getOrNull() }
            ?: android.net.Uri.parse("content://" + ctx.packageName + ".files/batch/a.apk")
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, MIME)
    }

    /** Все варианты окна для [count] файлов; [sample] нужен для списка сторонних установщиков. */
    fun options(ctx: Context, count: Int, sample: File?): List<Opt> {
        val out = ArrayList<Opt>()
        val ap = adbphonState(ctx)
        out += Opt(ADBPHON, ctx.getString(R.string.inst_adbphon), ap.first && count == 1, if (!ap.first) ap.second else if (count != 1) ctx.getString(R.string.inst_one_file) else "")
        out += Opt(SYSTEM, ctx.getString(R.string.inst_system), true)
        out += Opt(ADB, ctx.getString(R.string.inst_adb), Adb.isConnected, if (Adb.isConnected) "" else ctx.getString(R.string.inst_no_adb))
        if (count == 1) out += others(ctx, sampleIntent(ctx, sample))
        return out
    }

    fun labelOf(ctx: Context, id: String): String = when {
        id.isBlank() -> ctx.getString(R.string.inst_none)
        id == ADBPHON -> ctx.getString(R.string.inst_adbphon)
        id == SYSTEM -> ctx.getString(R.string.inst_system)
        id == ADB -> ctx.getString(R.string.inst_adb)
        id.startsWith("cmp:") -> {
            val p = id.removePrefix("cmp:").substringBefore('/')
            runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
        }
        else -> id
    }

    /** Окно выбора: null — отказ. Пара: id и «запомнить». */
    private suspend fun ask(name: String, opts: List<Opt>): Pair<String, Boolean>? = lock.withLock {
        val d = CompletableDeferred<Pair<String, Boolean>?>()
        withContext(Dispatchers.Main) { req = Req(name, opts, d) }
        android.util.Log.i("TVApps", "установщик: окно выбора показано для " + name)
        // 24.11.2026 (Евгений, Android 6: «снова ставится не хочет, не выходит по Назад и Домой»): окно выбора, потерянное при пересоздании окна «Дома»,
        // раньше висело вечно и держало замок: все следующие установки молча ждали его. Теперь отказ через 3 минуты, а «Назад» и «Домой» отменяют выбор сразу.
        try { kotlinx.coroutines.withTimeoutOrNull(180_000L) { d.await() } } finally { d.complete(null); withContext(Dispatchers.Main) { req = null }; android.util.Log.i("TVApps", "установщик: окно выбора закрыто") }
    }

    /** Отменяет открытое окно «Установить через…» (клавиши «Назад», «Домой»). */
    fun cancelPending(): Boolean { val r = req ?: return false; r.done.complete(null); return true }

    /** Какой установщик взять: окно (если включено «Спрашивать»), иначе запомненный, а если он недоступен, ADB при наличии, иначе системный. null — отказ. */
    suspend fun choose(ctx: Context, name: String, count: Int, sample: File?): String? {
        val app = ctx.applicationContext
        val opts = withContext(Dispatchers.IO) { options(app, count, sample) }
        if (Cfg.askInstaller) {
            val r = ask(name, opts) ?: return null
            if (r.second) { Cfg.saveInstaller(r.first); Cfg.changeAskInstaller(false) }
            return r.first
        }
        val saved = Cfg.instSaved
        val o = opts.firstOrNull { it.id == saved && it.enabled }
            ?: opts.firstOrNull { it.id.startsWith("cmp:") && saved.startsWith("cmp:") && it.id.substringBefore('/') == saved.substringBefore('/') && it.enabled }
        if (o != null) return o.id
        // запомненный способ сейчас недоступен (например, ADB отключён): окно Меню с причиной вместо тихого перехода на другой способ
        if (saved.isNotBlank()) { val r = ask(name, opts) ?: return null; if (r.second) Cfg.saveInstaller(r.first); return r.first }
        return if (Adb.isConnected) ADB else SYSTEM
    }

    /** Передача файла другому установщику (ADB-phon или стороннему): тот же файл через FileProvider. Возвращает "system" (результат смотрим по версии). */
    fun handoff(ctx: Context, id: String, file: File, label: String): String {
        val app = ctx.applicationContext
        val tmp = File(File(app.cacheDir, "batch").apply { mkdirs() }, "inst_" + label.replace(Regex("[^\\w.]"), "_").take(40) + ".apk")
        file.copyTo(tmp, true)
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", tmp)
        val cn = if (id == ADBPHON) ComponentName(ADBPHON_PKG, ADBPHON_INTAKE)
        else ComponentName(id.removePrefix("cmp:").substringBefore('/'), id.substringAfter('/'))
        val i = Intent(Intent.ACTION_VIEW).setComponent(cn).setDataAndType(uri, MIME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("", uri)
        runCatching { app.grantUriPermission(cn.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        return try { if (Installers.start(app, if (id == ADBPHON) Installers.M_ADBPHON else Installers.M_OTHER, i)) "system" else "fail:" + app.getString(R.string.inst_cancelled) } catch (e: Throwable) { "fail:" + (e.message ?: e.javaClass.simpleName) }
    }

    /** Ход тихой установки для окна «Установка…» вызывающего (задаётся им на время установки). */
    @Volatile var progressSink: ((String) -> Unit)? = null

    /** Название выбранного способа установки для окна «Установка» (13.0, код 57): окно показывает, чем именно ставится. */
    @Volatile var methodSink: ((String) -> Unit)? = null

    /**
     * Установка выбранным способом. Порядок (9.0): выбор установщика уже сделан → ОКНО СВЕДЕНИЙ О ПАКЕТЕ для ВСЕХ способов (до любого запуска установщика) → «Установить» → установка.
     * Другие приложения до окна сведений не открываются. «ADB-phon»: тихая передача файла службе ADB-phon без его экрана; если службы нет, откат на окно ADB-phon (в окне сведений об этом сказано).
     * «Через ADB этого устройства» — только собственный ADB-клиент программы. Итог: "ok", "system" (передано, итог по версии) или "fail:…".
     */
    suspend fun dispatch(ctx: Context, id: String, pkg: String, files: List<File>, name: String, confirm: Boolean = true): String {
        val app = ctx.applicationContext
        val label = name.ifBlank { pkg }
        val silent = if (id == ADBPHON && files.size == 1) AdbPhoneSilent.available(app) else (false to "")
        if (confirm) {
            val note = if (id == ADBPHON && !silent.first) app.getString(R.string.inst_note_fallback, silent.second) else ""
            if (!InstallInfo.confirm(app, files, label, note)) return "fail:" + app.getString(R.string.inst_cancelled)
        }
        methodSink?.invoke(labelOf(app, id))
        return when {
            id == ADB -> {
                if (!Adb.isConnected) return "need_adb"
                progressSink?.invoke(app.getString(R.string.net_installing))
                val r = Adb.installFiles(files, "-r")
                if (r.contains("Success", true)) "ok" else "fail:" + r.take(140)
            }
            id == SYSTEM && pkg == app.packageName && !SelfUpd.canInstall(app) -> "noperm"
            id == SYSTEM -> try {
                withContext(Dispatchers.IO) { Installers.sessionInstall(app, files, {}, label) }
                "system"
            } catch (e: Throwable) { "fail:" + (e.message ?: e.javaClass.simpleName) }
            id == ADBPHON && silent.first -> {
                progressSink?.invoke(app.getString(R.string.net_installing))
                val r = AdbPhoneSilent.install(app, files[0], label) { progressSink?.invoke(it) }
                if (r == "unavailable") withContext(Dispatchers.IO) { handoff(app, id, files[0], label) } else r
            }
            files.size == 1 -> withContext(Dispatchers.IO) { handoff(app, id, files[0], label) }
            else -> "need_adb"
        }
    }

    /** Полный путь для обновления и версий: выбор + сведения + установка. */
    suspend fun run(ctx: Context, pkg: String, files: List<File>, name: String): String {
        val label = name.ifBlank { pkg }
        val id = choose(ctx, label, files.size, files.firstOrNull()) ?: return "fail:" + ctx.getString(R.string.inst_cancelled)
        val r = dispatch(ctx, id, pkg, files, label)
        // 9.0 (код 44): «ADB» не подключён или не справился: окно Меню с причиной и выбором другого способа, без перехода на системные окна само по себе
        if (id == ADB && (r == "need_adb" || (r.startsWith("fail:") && !r.contains(ctx.getString(R.string.inst_cancelled))))) {
            val why = if (r == "need_adb") ctx.getString(R.string.inst_no_adb) else r.removePrefix("fail:")
            val opts = withContext(Dispatchers.IO) { options(ctx, files.size, files.firstOrNull()) }.map { if (it.id == ADB) Opt(it.id, it.label, false, why) else it }
            val pick = ask(label, opts) ?: return if (r == "need_adb") "fail:$why" else r
            return dispatch(ctx, pick.first, pkg, files, label, confirm = false)
        }
        return r
    }
}

/** Окно «Установить через…»: строки вариантов (серые с причиной), «Запомнить выбор», «Отмена». Ставится рядом с SelfUpdHost. */
@Composable
fun InstallerPickHost() {
    val r = InstallerPick.req ?: return
    val t = LocalTh.current
    var remember by remember(r) { mutableStateOf(true) }
    val first = remember(r) { FocusRequester() }
    LaunchedEffect(r) { kotlinx.coroutines.delay(150); runCatching { first.requestFocus() } }
    val sc = rememberScrollState()
    val firstEnabled = r.opts.indexOfFirst { it.enabled }
    HDialog(onDismissRequest = { r.done.complete(null) }) {
        Column(
            Modifier.heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.9f).dp)
                .clip(RoundedCornerShape(20.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(20.dp)).verticalScroll(sc).padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.inst_title), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.inst_for, r.name), color = t.hint, fontSize = 13.sp, maxLines = 2)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var othersShown = false
                r.opts.forEachIndexed { i, o ->
                    if (o.other && !othersShown) {
                        othersShown = true
                        Text(stringResource(R.string.inst_others), color = t.sub, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (o.enabled) {
                        ActionRow(HIcons.download, o.label, "", if (i == firstEnabled) Modifier.focusRequester(first) else Modifier) { r.done.complete(o.id to remember) }
                    } else {
                        // недоступный вариант: серая строка с причиной, без нажатия
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Text(o.label, color = t.hint, fontSize = 16.sp)
                            Text(o.reason, color = t.hint, fontSize = 12.sp)
                        }
                    }
                }
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.inst_remember) + ": " + stringResource(if (remember) R.string.on else R.string.off), remember) { remember = !remember }
                Chip(stringResource(R.string.cancel), false) { r.done.complete(null) }
            }
        }
    }
}
