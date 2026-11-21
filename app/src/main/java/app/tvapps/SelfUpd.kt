@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.tvapps

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Обновление самой программы (правила 14 и 17 из Общее\ПРАВИЛА_ПРОГРАММ.md): выпуски GitHub drugoj00/app-menu, файл проверяется по SHA-256 из описания,
 * установка через Updates.install (подпись, процессор, root/ADB или системный установщик).
 * Окно «Обновить / Позже» появляется само не чаще раза в сутки и ОДИН раз для каждой версии: закрыл (Позже, «Назад», касание вне окна) —
 * эта версия сама больше не предлагается. Кнопка «Проверить обновления» в настройках показывает доступную версию всегда.
 */
object SelfUpd {
    const val PKG = "app.tvapps"
    const val RELEASES = "https://github.com/drugoj00/app-menu/releases/latest"
    private const val DAY_MS = 24 * 3600_000L

    sealed interface St {
        data object Checking : St
        data class Found(val upd: AppUpd, val cand: UpdCand) : St
        data class Latest(val version: String) : St
        data object Failed : St
        /** Окно «Установка» (13.0, код 57): что ставится ([info]), способ ([method]), ход ([status], [pct] 0..100 или -1 пока ход неизвестен). */
        data class Installing(val status: String, val pct: Int = -1, val info: String = "", val method: String = "") : St
        data class Result(val text: String) : St
        /** Установка не завершена (отмена во внешнем установщике, таймаут): «Повторить / Закрыть». */
        data class Cancelled(val f: Found, val text: String) : St
        /** Android не разрешает этой программе устанавливать (нет «Установки из неизвестных источников»): кнопка настроек, продолжение другим способом, ручная загрузка. [waiting]: настройки открыты, при возврате установка продолжится сама. */
        data class NoPerm(val f: Found, val text: String, val waiting: Boolean = false) : St
    }

    /** Причина последней неудачной проверки (для окна «Не удалось проверить» и для «Скопировать подробности»). */
    @Volatile var failReason: String = ""

    /** Разрешена ли этой программе установка приложений (Android 8+: «Установка из неизвестных источников» для программы); до Android 8 разрешение общее. */
    fun canInstall(ctx: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 26) runCatching { ctx.packageManager.canRequestPackageInstalls() }.getOrDefault(true)
        // Android 7.1 и старше (старые автомагнитолы): общее переключение «Неизвестные источники» в безопасности
        else runCatching { @Suppress("DEPRECATION") android.provider.Settings.Secure.getInt(ctx.contentResolver, android.provider.Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1 }.getOrDefault(true)

    /** Открывает системную страницу разрешения «Установка из неизвестных источников» для этой программы (если страницы нет, общие настройки приложения). */
    fun openInstallPermission(ctx: Context, f: St.Found) {
        // Android 8+: страница «Установка из неизвестных источников» именно для этой программы; Android 7.1 и старше: общие настройки безопасности
        val i = (if (android.os.Build.VERSION.SDK_INT >= 26) android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + ctx.packageName))
        else android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { ctx.startActivity(i); true }.getOrDefault(false)
        if (!ok) runCatching {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        state = St.NoPerm(f, ctx.getString(R.string.su_noperm_text), waiting = true)
    }

    /** Подробности для сообщения об ошибке: версия, Android, устройство, разрешение, ADB, последняя сетевая ошибка, адрес файла. */
    fun details(ctx: Context, f: St.Found?): String = buildString {
        val inst = runCatching { ctx.packageManager.getPackageInfo(PKG, 0) }.getOrNull()
        appendLine("App Menu " + (inst?.versionName ?: "?") + " (" + (if (inst != null) (if (android.os.Build.VERSION.SDK_INT >= 28) inst.longVersionCode else @Suppress("DEPRECATION") inst.versionCode.toLong()) else 0) + ")")
        appendLine("Android " + android.os.Build.VERSION.RELEASE + " (SDK " + android.os.Build.VERSION.SDK_INT + "), " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
        appendLine("install permission: " + canInstall(ctx) + ", ADB: " + runCatching { Adb.isConnected }.getOrDefault(false))
        if (f != null) { appendLine("new: " + f.cand.versionName + " (" + f.cand.versionCode + ")"); f.cand.urls.firstOrNull()?.let { appendLine("url: $it") } }
        if (failReason.isNotBlank()) appendLine("check: $failReason")
        NetErr.last?.let { appendLine("net: " + it.javaClass.simpleName + ": " + it.message) }
        if (Updates.lastHashInfo.isNotBlank()) appendLine("hash: " + Updates.lastHashInfo)
        appendLine("file: " + java.io.File(ctx.cacheDir, "batch/self_update.apk").let { if (it.isFile) it.path + " (" + it.length() + ")" else "-" })
    }

    fun copyDetails(ctx: Context, f: St.Found?) {
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("App Menu", details(ctx, f)))
            android.widget.Toast.makeText(ctx, ctx.getString(R.string.su_copied), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Адрес для ручной загрузки: файл выпуска или страница выпусков. */
    fun manualUrl(f: St.Found?): String = f?.cand?.urls?.firstOrNull() ?: RELEASES

    /** Скачанный файл обновления (копия остаётся до следующей загрузки): для «Открыть файл». */
    fun savedFile(ctx: Context): java.io.File? = java.io.File(ctx.cacheDir, "batch/self_update.apk").takeIf { it.isFile && it.length() > 0 }

    /** Открывает скачанный APK системным окном выбора (ACTION_VIEW + FileProvider): запасной путь, если установка из программы не запускается. */
    fun openSavedFile(ctx: Context): Boolean {
        val f = savedFile(ctx) ?: return false
        return runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(android.content.Intent.createChooser(i, ctx.getString(R.string.su_open_file)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    /** Отказ системной сессии установки (получает приёмник SessionInstaller) для обновления самой программы. */
    fun sessionFailed(ctx: Context, status: Int, msg: String) {
        val f = awaitedFound ?: return
        awaited = ""; leftApp = false; watching = false
        state = if (status == android.content.pm.PackageInstaller.STATUS_FAILURE_BLOCKED || !canInstall(ctx)) St.NoPerm(f, ctx.getString(R.string.su_noperm_text))
        else St.Cancelled(f, ctx.getString(R.string.su_session_failed, status, msg))
    }

    /** Состояние окна (общее для «Меню» и «Дома»: один процесс); null — окно закрыто. */
    var state by mutableStateOf<St?>(null)
        private set
    @Volatile private var busy = false

    /** Ненавязчивая плашка «Вышла X. Обновить?» (после проверки при запуске); null: не показывается. */
    var banner by mutableStateOf<St.Found?>(null)
        private set
    /** Окно «Что нового» для этого выпуска; null: закрыто. Открывается и из плашки, и из окон «Обновления». */
    var notes by mutableStateOf<UpdCand?>(null)

    /** «Позже» и крестик: плашка скрыта до следующей проверки (раз в сутки) или до новой версии. */
    fun bannerLater() { banner = null; Cfg.setSelfUpdPending("") }
    fun bannerInstall(ctx: Context, scope: CoroutineScope) { val f = banner ?: return; banner = null; Cfg.setSelfUpdPending(""); install(ctx, scope, f) }

    private suspend fun fetch(ctx: Context): Pair<AppUpd, UpdCand?> {
        val r = testRelease(ctx) ?: Updates.check(ctx, PKG, force = true, only = setOf("github"))
        val best = r.newer.filter { !it.manual }.maxWithOrNull { a, b -> Updates.compareVersions(a.versionName, b.versionName) }
        return r to best
    }

    /**
     * Проверка без настоящего выпуска (ТОЛЬКО в отладочной сборке): если в папке программы есть файл test_release.txt, из него берётся «последний выпуск»:
     * первая строка — версия (например 99.0), дальше описание выпуска. В выпускной сборке этого пути нет.
     */
    private fun testRelease(ctx: Context): AppUpd? {
        if (!BuildConfig.DEBUG) return null
        val f = java.io.File(ctx.filesDir, "test_release.txt")
        if (!f.isFile) return null
        val lines = f.readText().lines()
        val v = lines.firstOrNull()?.trim().orEmpty().ifBlank { return null }
        val inst = runCatching { ctx.packageManager.getPackageInfo(PKG, 0) }.getOrNull()
        val tcode = lines.drop(1).firstOrNull { it.startsWith("code=") }?.removePrefix("code=")?.trim()?.toLongOrNull() ?: 0L
        val cand = UpdCand("github", v, tcode, listOf(lines.drop(1).firstOrNull { it.startsWith("url=") }?.removePrefix("url=")?.trim() ?: "https://127.0.0.1/none.apk"), lines.drop(1).firstOrNull { it.startsWith("size=") }?.removePrefix("size=")?.trim()?.toLongOrNull() ?: 1048576, note = "test", body = lines.drop(1).filter { !it.startsWith("url=") && !it.startsWith("code=") }.joinToString("\n"))
        return AppUpd(PKG, "", inst?.versionName ?: "0", if (tcode > 0 && inst != null) (if (android.os.Build.VERSION.SDK_INT >= 28) inst.longVersionCode else @Suppress("DEPRECATION") inst.versionCode.toLong()) else 0, listOf(cand), emptyList())
    }

    /**
     * Обязательное обновление: если в описании ПОСЛЕДНЕГО выпуска GitHub есть строка «Обязательное обновление / Required update: да» и версия программы ниже,
     * при запуске открывается окно «Нужно обновиться для работы программы» (кнопки «Обновить», «Что нового», «Выйти»; закрыть иначе нельзя).
     * Нет сети или проверка не удалась: ничего не блокируется. Проверка идёт при запуске и не чаще раза в 30 минут; выключена вместе с автопроверкой в настройках.
     */
    var required by mutableStateOf<St.Found?>(null)
        private set
    @Volatile private var reqBusy = false
    @Volatile private var reqAt = 0L
    suspend fun checkRequired(ctx: Context) {
        if (!Cfg.selfUpd || reqBusy || required != null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (reqAt != 0L && now - reqAt < 30 * 60_000L) return
        reqBusy = true
        try {
            val (r, best) = fetch(ctx)
            val netOk = r.errors.isEmpty() || r.cands.isNotEmpty()
            if (netOk) reqAt = now
            if (netOk && best != null && ReleaseNotes.isRequired(best.body)) required = St.Found(r, best)
        } catch (e: CancellationException) { throw e } catch (_: Throwable) { } finally { reqBusy = false }
    }

    /** Кнопка «Проверить обновления»: показывает результат всегда. */
    fun checkNow(ctx: Context, scope: CoroutineScope) {
        if (busy) return
        busy = true
        state = St.Checking
        scope.launch(Dispatchers.Default) {
            try {
                val (r, best) = fetch(ctx)
                failReason = if (r.errors.isNotEmpty()) (NetErr.reason(ctx).ifBlank { "" } + " " + r.errors.joinToString("; ")).trim() else ""
                state = when {
                    best != null -> St.Found(r, best)
                    r.cands.isEmpty() && r.errors.isNotEmpty() -> St.Failed
                    else -> St.Latest(r.instName)
                }
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { failReason = e.javaClass.simpleName + ": " + e.message; state = St.Failed } finally { busy = false }
        }
    }

    /** Фоновая проверка при открытии программы: не чаще раза в сутки, окно только для версии, которую ещё не закрывали. */
    suspend fun autoCheck(ctx: Context) {
        if (!Cfg.selfUpd || busy || state != null || banner != null) return
        val now = System.currentTimeMillis()
        // раз в сутки; плюс повтор при запуске, пока плашка не закрыта ответом «Позже» (нет сети: тихо, без сообщений)
        val dayDue = now - Cfg.selfUpdLast() >= DAY_MS
        val pending = Cfg.selfUpdPending()
        if (!dayDue && pending.isEmpty()) return
        busy = true
        try {
            val (r, best) = fetch(ctx)
            // отметка времени ставится только после ответа сети: при ошибке следующая проверка не ждёт сутки
            if (r.errors.isEmpty() || r.cands.isNotEmpty()) Cfg.setSelfUpdLast(now)
            if (best != null && state == null && (dayDue || pending == best.versionName)) { Cfg.setSelfUpdPending(best.versionName); banner = St.Found(r, best) }
            else if (best == null && (r.errors.isEmpty() || r.cands.isNotEmpty())) Cfg.setSelfUpdPending("")
        } catch (e: CancellationException) { throw e } catch (_: Throwable) { } finally { busy = false }
    }

    /** Старый результат ручной проверки («Установлена последняя версия», ошибка) не должен всплывать позже, например после восстановления из копии. */
    fun clearStale() { if (state is St.Latest || state is St.Failed) state = null }

    /** Закрытие окна любым способом: найденная версия больше сама не предлагается. */
    fun dismiss() {
        (state as? St.Found)?.let { Cfg.setSelfUpdDismissed(it.cand.versionName) }
        state = null
    }

    /** Своя область: установка не должна зависеть от окна, из которого её запустили (плашка исчезает, и её область отменяла установку на середине). */
    private val appScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    @Volatile private var token = 0
    private const val INSTALL_TIMEOUT_MS = 5 * 60_000L

    /** Версия, которую ждём после передачи файла внешнему установщику; пустая: ничего не ждём. */
    @Volatile private var awaited = ""
    @Volatile private var awaitedFound: St.Found? = null
    @Volatile private var leftApp = false

    /** Окно ушло с экрана (открылся внешний установщик). */
    @Volatile private var watching = false
    fun onLeft() { if (watching) leftApp = true; android.util.Log.i("TVApps", "самообновление: окно ушло, ждём=" + watching) }

    /** Возврат из внешнего установщика: версия выросла — «Готово»; нет — «Установка не завершена: повторить / закрыть». */
    fun onBack(ctx: Context) {
        // вернулись из настроек разрешения: если разрешение выдано, установка продолжается сама
        (state as? St.NoPerm)?.let { np -> if (np.waiting && canInstall(ctx)) { state = null; install(ctx, appScope, np.f); return } }
        val want = awaited
        android.util.Log.i("TVApps", "самообновление: возврат, ждём версию '" + want + "', уходили=" + leftApp)
        if (want.isEmpty() || !leftApp) return
        // сессия установки ещё идёт (установщик работает в фоне): итог не объявляем, проверим при следующем возврате
        val active = runCatching { ctx.packageManager.packageInstaller.mySessions.any { it.isActive } }.getOrDefault(false)
        if (active) return
        leftApp = false; watching = false
        val cur = runCatching { ctx.packageManager.getPackageInfo(PKG, 0).versionName }.getOrNull().orEmpty()
        awaited = ""
        val f = awaitedFound
        state = if (cur.isNotEmpty() && Updates.compareVersions(cur, want) >= 0) St.Result(ctx.getString(R.string.ver_done))
        else if (f != null) (if (!canInstall(ctx)) St.NoPerm(f, ctx.getString(R.string.su_noperm_text)) else St.Cancelled(f, ctx.getString(R.string.self_upd_cancelled))) else null
    }

    /** Закрыть окно «Установка…» (кнопка «Закрыть» и «Назад»): установка в фоне может закончиться позже, тогда результат не показывается. */
    fun closeInstalling() { token++; if (state is St.Installing) state = null }

    @Volatile private var curInfo = ""
    @Volatile private var curMethod = ""
    private fun inst(status: String): St.Installing {
        val pct = Regex("""^\s*(\d{1,3})\s*%""").find(status)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100) ?: -1
        return St.Installing(status, pct, curInfo, curMethod)
    }

    // итог обновления самой программы: после замены файла процесс перезапускается, поэтому цель записывается заранее, а новый процесс при запуске показывает итог
    private const val PEND = "selfupd_target"
    fun markTarget(ctx: Context, f: St.Found) {
        ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE).edit().putString(PEND, f.cand.versionName + "|" + f.cand.versionCode + "|" + System.currentTimeMillis()).apply()
    }

    /** При запуске: если обновление было начато и версия выросла, показывается «Установлено: …»; старая незавершённая отметка (больше суток) молча стирается. */
    fun showResultAfterRestart(ctx: Context) {
        val sp = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE)
        val v = sp.getString(PEND, null) ?: return
        val p = v.split("|")
        val name = p.getOrNull(0).orEmpty(); val code = p.getOrNull(1)?.toLongOrNull() ?: 0L; val at = p.getOrNull(2)?.toLongOrNull() ?: 0L
        val inst = runCatching { ctx.packageManager.getPackageInfo(PKG, 0) }.getOrNull() ?: return
        val icode = if (android.os.Build.VERSION.SDK_INT >= 28) inst.longVersionCode else @Suppress("DEPRECATION") inst.versionCode.toLong()
        val done = icode >= code && (code > 0 || Updates.compareVersions(inst.versionName.orEmpty(), name) >= 0)
        if (done) {
            sp.edit().remove(PEND).apply()
            if (state == null) state = St.Result(ctx.getString(R.string.su_installed, inst.versionName.orEmpty(), icode))
        } else if (System.currentTimeMillis() - at > 24 * 3600_000L) sp.edit().remove(PEND).apply()
    }

    fun install(ctx: Context, scope: CoroutineScope, f: St.Found, ignorePerm: Boolean = false) {
        Cfg.setSelfUpdDismissed(f.cand.versionName)
        val my = ++token
        awaited = ""; leftApp = false; watching = true
        val app = ctx.applicationContext
        // что ставится: название, установлено X (код) → новая Y (код), размер (окно «Установка», 13.0 код 57)
        curInfo = app.getString(R.string.su_info, f.upd.label.ifBlank { app.getString(R.string.self_upd_title) }, f.upd.instName, f.upd.instCode, f.cand.versionName, f.cand.versionCode,
            if (f.cand.size > 0) "%.1f".format(java.util.Locale.US, f.cand.size / 1048576.0) else "?")
        curMethod = ""
        markTarget(app, f)
        state = inst("")
        // страховка: если через 5 минут окно всё ещё «Установка…», оно превращается в «Не удалось: повторить / закрыть»
        appScope.launch {
            kotlinx.coroutines.delay(INSTALL_TIMEOUT_MS)
            if (token == my && state is St.Installing) { awaited = ""; state = St.Cancelled(f, app.getString(R.string.self_upd_timeout)) }
        }
        appScope.launch {
            val res = try {
                InstallerPick.progressSink = { t -> if (token == my) state = inst(t) }
                InstallerPick.methodSink = { m -> curMethod = m; if (token == my && state is St.Installing) state = inst((state as St.Installing).status) }
                Updates.install(app, f.upd, f.cand) { s -> if (token == my) state = inst(s) }
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { "fail:" + (e.message ?: "") } finally { InstallerPick.progressSink = null }
            if (res == "noperm") {
                // системный способ без разрешения «Установка из неизвестных источников» (ADB и ADB-phon его не требуют): названа причина и дана кнопка настроек
                if (token == my) state = St.NoPerm(f, app.getString(R.string.su_noperm_text))
            } else if (res == "system") {
                // файл передан внешнему установщику (системному, App Manager и др.): окно закрывается сразу, итог проверяется тихо по версии при возврате
                awaited = f.cand.versionName; awaitedFound = f
                if (token == my) state = null
            } else if (token == my) {
                // отказ в окне «Установить через…»: «Установка отменена. Повторить / Закрыть»
                state = if (res == "fail:" + app.getString(R.string.inst_cancelled)) St.Cancelled(f, app.getString(R.string.inst_cancelled)) else St.Result(updResultText(app, res))
            }
        }
    }
}

/** Окно самообновления: рисуется там, где поставлен [SelfUpdHost] (главное меню и «Дом»), поэтому видно и поверх настроек. */
@Composable
fun SelfUpdHost() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { SelfUpd.clearStale(); SelfUpd.showResultAfterRestart(ctx.applicationContext); SelfUpd.checkRequired(ctx.applicationContext); SelfUpd.autoCheck(ctx.applicationContext) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val ob = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) SelfUpd.onLeft()
            else if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) SelfUpd.onBack(ctx.applicationContext)
        }
        owner.lifecycle.addObserver(ob)
        onDispose { owner.lifecycle.removeObserver(ob) }
    }
    SelfUpd.required?.let { RequiredUpdateScreen(it) }
    val st = SelfUpd.state
    SelfUpd.notes?.let { NotesDialog(it) }
    if (st != null) SelfUpdDialog(st)
    // «Установить через…» поверх остальных окон
    InstallerPickHost()
    InstallInfoHost()
    RootHelpHost()
}

/** Окно обязательного обновления на весь экран: «Назад» и касание вне окна не закрывают; есть «Обновить» (как у плашки: «Что нового» и установка) и «Выйти». */
@Composable
private fun RequiredUpdateScreen(f: SelfUpd.St.Found) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val tv = TopMode.isTv(ctx)
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); runCatching { first.requestFocus() } }
    HDialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(t.bg).padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Column(
                Modifier.widthIn(max = if (tv) 780.dp else 560.dp).clip(RoundedCornerShape(20.dp)).background(t.tile).border(2.dp, t.accent, RoundedCornerShape(20.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.req_upd_title), color = t.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.req_upd_text, f.cand.versionName), color = t.text, fontSize = 16.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionRow(HIcons.download, stringResource(R.string.self_upd_install), "", Modifier.focusRequester(first)) { SelfUpd.install(ctx, scope, f) }
                    ActionRow(HIcons.list, stringResource(R.string.notes_btn)) { SelfUpd.notes = f.cand }
                    ActionRow(HIcons.open, stringResource(R.string.req_upd_exit)) {
                        var c: Context? = ctx
                        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
                        (c as? android.app.Activity)?.finishAffinity()
                    }
                }
            }
        }
    }
}

/** Плашка рисуется отдельно и ПОСЛЕДНЕЙ в экране (иначе её закрывает содержимое): ставится в конце «Меню» и «Дома». */
@Composable
fun SelfUpdBannerHost() {
    if (SelfUpd.state == null) SelfUpd.banner?.let { SelfUpdBanner(it) }
}

/** Плашка о новой версии внизу экрана: «Вышла X. Обновить?», кнопки «Обновить», «Что нового», «Позже» и крестик. Фокус сама не берёт (до неё доходят стрелками или мышью). */
@Composable
private fun SelfUpdBanner(f: SelfUpd.St.Found) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    val tv = TopMode.isTv(ctx)
    val shape = RoundedCornerShape(16.dp)
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
        Column(
            Modifier.widthIn(max = if (tv) 780.dp else 540.dp).clip(shape).background(t.tile).border(2.dp, t.accent, shape).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.upd_banner, f.cand.versionName), color = t.text, fontSize = if (tv) 22.sp else 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                Chip("✕", false) { SelfUpd.bannerLater() }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionRow(HIcons.download, stringResource(R.string.self_upd_install)) { SelfUpd.bannerInstall(ctx, scope) }
                ActionRow(HIcons.list, stringResource(R.string.notes_btn)) { SelfUpd.notes = f.cand }
                ActionRow(HIcons.recent, stringResource(R.string.self_upd_later)) { SelfUpd.bannerLater() }
            }
        }
    }
}

/** Окно «Что нового»: текст изменений этой версии из описания выпуска на языке программы; прокручивается стрелками и колесом, полоса видна. */
@Composable
private fun NotesDialog(c: UpdCand) {
    val t = LocalTh.current
    val text = remember(c) { ReleaseNotes.forLang(c.body, AppLoc.code()) }
    val sc = androidx.compose.foundation.rememberScrollState()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    var textFocused by remember { mutableStateOf(false) }
    val maxH = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.9f).dp
    HDialog(onDismissRequest = { SelfUpd.notes = null }) {
        Column(
            Modifier.heightIn(max = maxH).clip(RoundedCornerShape(20.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(20.dp).fillMaxWidth()
                .onPreviewKeyEvent { e ->
                    // стрелки и «страница» листают текст при любом фокусе (на тексте или на кнопке «Закрыть»); когда дальше листать нечего, фокус идёт как обычно
                    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = when (e.key) {
                        androidx.compose.ui.input.key.Key.DirectionDown -> 1f
                        androidx.compose.ui.input.key.Key.DirectionUp -> -1f
                        androidx.compose.ui.input.key.Key.PageDown -> 4f
                        androidx.compose.ui.input.key.Key.PageUp -> -4f
                        else -> return@onPreviewKeyEvent false
                    }
                    if (if (step > 0) sc.canScrollForward else sc.canScrollBackward) { scope.launch { sc.animateScrollBy(step * 160f) }; true } else false
                },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.notes_title, c.versionName), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            androidx.compose.foundation.layout.Box(
                Modifier.weight(1f, fill = false).verticalScrollbar(sc)
                    .onFocusChanged { textFocused = it.isFocused }
                    .then(if (textFocused) Modifier.border(2.dp, t.accent, RoundedCornerShape(8.dp)) else Modifier)
                    .focusable()
                    .verticalScroll(sc).padding(end = ScrollbarMath.RESERVE_DP.dp),
            ) {
                Text(text.ifBlank { stringResource(R.string.notes_none) }, color = t.text, fontSize = 15.sp)
            }
            Chip(stringResource(R.string.close), true, Modifier.focusRequester(first)) { SelfUpd.notes = null }
        }
    }
}

/** Текст «Что нового» из описания выпуска GitHub: берётся блок на нужном языке (**English**, **Русский**, **Українська**), без заголовка «###», SHA и служебных строк. */
object ReleaseNotes {
    /** Строка-признак в описании выпуска: «Обязательное обновление / Required update: да» (также «Обов'язкове оновлення: так», «Required update: yes»). */
    private const val REQ_WORDS = """(?:обязательное обновление|обов['’ʼ`]?язкове оновлення|required update)"""
    private val reqOn = Regex("""(?im)^[\s>*_•-]*$REQ_WORDS[^\n:：]*[:：]\s*[*_`]*\s*(?:да|так|yes|true)(?![\p{L}\d])""")
    /** Строка-признак с любым значением (да/нет): в окно «Что нового» не попадает. */
    private val reqAny = Regex("""(?i)^[\s>*_•-]*$REQ_WORDS[^\n:：]*[:：]\s*[*_`]*\s*(?:да|нет|так|ні|yes|no|true|false)(?![\p{L}\d])""")
    fun isRequired(body: String): Boolean = reqOn.containsMatchIn(body.replace("\r\n", "\n"))
    private val marks = mapOf("en" to "**English**", "ru" to "**Русский**", "uk" to "**Українська**")
    fun forLang(body: String, lang: String): String {
        val b = body.replace("\r\n", "\n")
        val all = marks.values
        val mark = marks[lang] ?: marks.getValue("en")
        var s = b
        val i = b.indexOf(mark)
        if (i >= 0) {
            val from = i + mark.length
            val next = all.filter { it != mark }.map { b.indexOf(it, from) }.filter { it >= 0 }.minOrNull() ?: b.length
            s = b.substring(from, next)
        }
        return s.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("###") && !it.startsWith("**") && !it.lowercase().contains("sha-256") && !it.lowercase().startsWith("android ") && !reqAny.containsMatchIn(it) && !Regex("""(?i)^version ?code\s*[:=]""").containsMatchIn(it) }
            .joinToString("\n") { if (it.startsWith("- ") || it.startsWith("* ")) "•  " + it.drop(2) else it }
    }
}

@Composable
private fun SelfUpdDialog(st: SelfUpd.St) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(st::class) { runCatching { first.requestFocus() } }
    HDialog(onDismissRequest = { if (st is SelfUpd.St.Installing) SelfUpd.closeInstalling() else SelfUpd.dismiss() }) {
        // 13.0: при крупном интерфейсе и шрифте окно выше экрана: прокручивается, кнопки внизу («Скопировать», «Закрыть») не пропадают
        Column(
            Modifier.heightIn(max = (screenDp().second * 0.92f).dp).verticalScroll(androidx.compose.foundation.rememberScrollState()).clip(RoundedCornerShape(20.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.self_upd_title), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            when (st) {
                SelfUpd.St.Checking -> Text(stringResource(R.string.self_upd_checking), color = t.accent, fontSize = 14.sp)
                is SelfUpd.St.Found -> {
                    Text(stringResource(R.string.self_upd_found, st.cand.versionName, st.upd.instName), color = t.text, fontSize = 15.sp)
                    if (st.cand.size > 0) Text(stringResource(R.string.self_upd_size, "%.1f".format(java.util.Locale.US, st.cand.size / 1048576.0)), color = t.hint, fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ActionRow(HIcons.download, stringResource(R.string.self_upd_install), "", Modifier.focusRequester(first)) { SelfUpd.install(ctx, scope, st) }
                        ActionRow(HIcons.list, stringResource(R.string.notes_btn)) { SelfUpd.notes = st.cand }
                        Chip(stringResource(R.string.self_upd_later), false) { SelfUpd.dismiss() }
                        ActionRow(HIcons.open, stringResource(R.string.self_upd_release)) { openUrl(ctx, SelfUpd.RELEASES) }
                    }
                }
                is SelfUpd.St.Latest -> {
                    Text(stringResource(R.string.self_upd_latest, st.version), color = t.text, fontSize = 15.sp)
                    Chip(stringResource(R.string.close), true, Modifier.focusRequester(first)) { SelfUpd.dismiss() }
                }
                SelfUpd.St.Failed -> {
                    Text(stringResource(R.string.self_upd_failed), color = t.text, fontSize = 15.sp)
                    if (SelfUpd.failReason.isNotBlank()) Text(stringResource(R.string.su_fail_reason, SelfUpd.failReason.take(220)), color = t.hint, fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ActionRow(HIcons.refresh, stringResource(R.string.self_upd_retry), "", Modifier.focusRequester(first)) { SelfUpd.checkNow(ctx, scope) }
                        ActionRow(HIcons.open, stringResource(R.string.su_manual)) { openUrl(ctx, SelfUpd.manualUrl(null)) }
                        ActionRow(HIcons.list, stringResource(R.string.su_copy)) { SelfUpd.copyDetails(ctx, null) }
                        Chip(stringResource(R.string.close), false) { SelfUpd.dismiss() }
                    }
                }
                is SelfUpd.St.NoPerm -> {
                    Text(st.text, color = t.text, fontSize = 15.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ActionRow(HIcons.gear, stringResource(R.string.su_open_settings), "", Modifier.focusRequester(first)) { SelfUpd.openInstallPermission(ctx.applicationContext, st.f) }
                        if (SelfUpd.savedFile(ctx) != null) ActionRow(HIcons.open, stringResource(R.string.su_open_file), stringResource(R.string.su_file_path, SelfUpd.savedFile(ctx)?.path.orEmpty())) { SelfUpd.openSavedFile(ctx) }
                        ActionRow(HIcons.open, stringResource(R.string.su_manual)) { openUrl(ctx, SelfUpd.manualUrl(st.f)) }
                        ActionRow(HIcons.list, stringResource(R.string.su_copy)) { SelfUpd.copyDetails(ctx, st.f) }
                        Chip(stringResource(R.string.close), false) { SelfUpd.dismiss() }
                    }
                }
                is SelfUpd.St.Installing -> {
                    if (st.info.isNotBlank()) Text(st.info, color = t.text, fontSize = 14.sp)
                    if (st.method.isNotBlank()) Text(stringResource(R.string.su_method, st.method), color = t.sub, fontSize = 13.sp)
                    // определённая полоска хода (не бегущая): только когда ход известен (ADB-phon сообщает проценты)
                    if (st.pct >= 0) androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().heightIn(min = 10.dp, max = 10.dp).clip(RoundedCornerShape(5.dp)).background(t.chipSel)) {
                        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(st.pct / 100f).heightIn(min = 10.dp, max = 10.dp).background(t.accent))
                    }
                    Text(st.status.ifBlank { stringResource(R.string.self_upd_installing) }, color = t.accent, fontSize = 14.sp)
                    Text(stringResource(R.string.self_upd_wait), color = t.hint, fontSize = 12.sp)
                    Chip(stringResource(R.string.close), true, Modifier.focusRequester(first)) { SelfUpd.closeInstalling() }
                }
                is SelfUpd.St.Cancelled -> {
                    Text(st.text, color = t.text, fontSize = 15.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ActionRow(HIcons.refresh, stringResource(R.string.self_upd_retry), "", Modifier.focusRequester(first)) { SelfUpd.install(ctx, scope, st.f) }
                        if (SelfUpd.savedFile(ctx) != null) ActionRow(HIcons.open, stringResource(R.string.su_open_file), stringResource(R.string.su_file_path, SelfUpd.savedFile(ctx)?.path.orEmpty())) { SelfUpd.openSavedFile(ctx) }
                        ActionRow(HIcons.open, stringResource(R.string.su_manual)) { openUrl(ctx, SelfUpd.manualUrl(st.f)) }
                        ActionRow(HIcons.list, stringResource(R.string.su_copy)) { SelfUpd.copyDetails(ctx, st.f) }
                        Chip(stringResource(R.string.close), false) { SelfUpd.dismiss() }
                    }
                }
                is SelfUpd.St.Result -> {
                    Text(st.text, color = t.text, fontSize = 15.sp, modifier = Modifier.padding(vertical = 2.dp))
                    Chip(stringResource(R.string.close), true, Modifier.focusRequester(first)) { SelfUpd.dismiss() }
                }
            }
        }
    }
}
