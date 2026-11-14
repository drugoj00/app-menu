package app.tvapps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Режим «Дом»: управление главным экраном устройства.
 *
 * Мягкий режим: программа включается как главный экран (HomeAlias) и назначается по умолчанию.
 * Железный режим: родные главные экраны (которые прошивка запускает первыми и не даёт убить)
 * отключаются для пользователя командой `pm disable-user`, поэтому «Домой» и загрузка ведут только сюда.
 * Всё это обратимо: список отключённого хранится в настройках и во внешнем файле, возврат одной кнопкой.
 */
object HomeControl {
    const val ALIAS = "app.tvapps.HomeAlias"
    /** Файл с командами возврата для компьютера: на случай, когда сама программа недоступна. */
    private const val RESTORE_FILE = "/sdcard/tvapps_home_restore.txt"

    /** Пакеты чужих главных экранов (кроме этой программы и служебной заставки системы). */
    fun otherHomePackages(ctx: Context): List<String> =
        ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo }
            .filter { it.packageName != ctx.packageName && it.packageName != "android" && !it.name.endsWith("FallbackHome") }
            .map { it.packageName }.distinct()

    private fun isSystemPkg(ctx: Context, p: String): Boolean = runCatching {
        (ctx.packageManager.getApplicationInfo(p, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    }.getOrDefault(true)

    /** Какие из чужих главных экранов отключать: по выбору владельца, а без выбора только системные (сторонний любимый лаунчер остаётся). */
    fun toDisable(ctx: Context): List<String> {
        val keep = Cfg.hardKeep
        return otherHomePackages(ctx).filter { if (keep != null) it !in keep else isSystemPkg(ctx, it) }
    }

    /** Какой пакет сейчас главный экран по умолчанию. */
    fun currentHome(ctx: Context): String? =
        ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName

    fun aliasEnabled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getComponentEnabledSetting(ComponentName(ctx.packageName, ALIAS)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }.getOrDefault(false)

    fun setAlias(ctx: Context, on: Boolean) {
        ctx.packageManager.setComponentEnabledSetting(
            ComponentName(ctx.packageName, ALIAS),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    private suspend fun setDefault(ctx: Context): Boolean = setHomeCmd("${ctx.packageName}/$ALIAS")

    /** Назначает главный экран: сначала «cmd package», а если не вышло (Android 7 и некоторые прошивки), запасной «pm set-home-activity». Результат пишется в журнал. */
    suspend fun setHomeCmd(component: String): Boolean {
        val out1 = runCatching { Adb.shell("cmd package set-home-activity $component") }.getOrDefault("")
        if (out1.contains("Success", true)) return true
        Log.w("TVApps", "cmd package set-home-activity: ${out1.take(160).replace('\n', ' ')}; пробую pm")
        val out2 = runCatching { Adb.shell("pm set-home-activity $component") }.getOrDefault("")
        val ok = out2.contains("Success", true)
        Log.i("TVApps", "pm set-home-activity: ${out2.take(160).replace('\n', ' ')} -> ${if (ok) "готово" else "не вышло"}")
        return ok
    }

    private fun fgPrefs(ctx: Context) = ctx.getSharedPreferences("tvapps_fg", Context.MODE_PRIVATE)

    /** «Дом» на экране (true) или ушёл с экрана (false, со временем): нужно, чтобы после обновления вернуть человека на «Дом», а не оставить в предыдущей задаче. */
    fun noteVisible(ctx: Context, on: Boolean) {
        runCatching { fgPrefs(ctx).edit().putBoolean("home_vis", on).also { if (!on) it.putLong("home_pause_at", System.currentTimeMillis()) }.commit() }
    }

    /**
     * После обновления Android убивает программу и откатывается к предыдущей задаче (часто это настройки, открытые с «Дома»).
     * Открываем «Дом» снова, только если он был на экране (процесс убит на экране) или человек недавно ушёл с него в системные настройки или установщик.
     * Если человек был в другом приложении, экран не перехватываем.
     */
    suspend fun reopenAfterUpdate(ctx: Context) {
        val fg = fgPrefs(ctx)
        val vis = fg.getBoolean("home_vis", false)
        val recent = System.currentTimeMillis() - fg.getLong("home_pause_at", 0L) < 10 * 60_000L
        if (currentHome(ctx) != ctx.packageName) { Log.i("TVApps", "после обновления: главный экран не наш, «Дом» не открываю"); return }
        var focus = ""; var top = ""
        if (Adb.isConnected) {
            focus = runCatching { Adb.shell("dumpsys window | grep mCurrentFocus", 8_000) }.getOrDefault("")
            // верхняя задача после обновления: туда Android откатился (пустой фокус в первые секунды ненадёжен)
            top = Regex("""Recent #0: \S+ #\d+ A=([^ ]+)""").find(runCatching { Adb.shell("dumpsys activity recents | grep -m1 'Recent #0'", 8_000) }.getOrDefault(""))?.groupValues?.get(1).orEmpty()
        }
        if (focus.contains(ctx.packageName)) { Log.i("TVApps", "после обновления: программа уже на экране, ничего не запускаю"); return }
        val sysTop = Regex("settings|packageinstaller|permissioncontroller", RegexOption.IGNORE_CASE).containsMatchIn(top)
        // другое приложение сверху: человек там, экран не перехватываем; «Дом» был на экране при убийстве: открываем, если ADB/root нет и проверить нечем
        val go = if (top.isNotEmpty()) (top == ctx.packageName || sysTop) && (vis || recent) else vis
        Log.i("TVApps", "после обновления: «Дом» был на экране=$vis, недавно=$recent, верхняя задача=$top, фокус=${focus.trim().take(100)} -> ${if (go) "открываю" else "не трогаю"}")
        if (!go) return
        val started = if (Adb.isConnected) runCatching { Adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME -n ${ctx.packageName}/$ALIAS") }.map { !it.contains("Error", true) }.getOrDefault(false) else false
        if (!started) runCatching {
            ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClassName(ctx.packageName, ALIAS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w("TVApps", "после обновления: запуск «Дома» не вышел: ${it.message}") }
    }

    /**
     * После обновления программы Android сбрасывает «приложение по умолчанию» для главного экрана, и при нажатии «Домой» появляется окно выбора.
     * Если программа была главным экраном по умолчанию и включено «Помнить выбор», она сама возвращает себе эту роль командой set-home-activity
     * (через root или ADB). Если по умолчанию выбран другой экран, это выбор человека: ничего не трогаем.
     * Результат: ok_already, restored, off, alias_off, other, not_pinned, no_adb, failed.
     */
    suspend fun restoreDefault(ctx: Context, firstDelayMs: Long = 0L): String {
        if (!Cfg.rememberHome) return "off"
        if (!aliasEnabled(ctx)) return "alias_off"
        if (firstDelayMs > 0) kotlinx.coroutines.delay(firstDelayMs)
        val cur = currentHome(ctx)
        if (cur == ctx.packageName) { Cfg.setHomePinned(true); return "ok_already" }
        // другой настоящий главный экран выбран по умолчанию: так решил человек
        if (cur != null && cur != "android") { Cfg.setHomePinned(false); return "other" }
        if (!Cfg.homePinned()) return "not_pinned"
        runCatching { autoConnect(ctx) }
        if (!Adb.isConnected) {
            Log.w("TVApps", "главный экран: вернуть выбор нечем (нет ADB и root)")
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            h.post { runCatching { android.widget.Toast.makeText(ctx, ctx.getString(R.string.home_keep_pick), android.widget.Toast.LENGTH_LONG).show() } }
            return "no_adb"
        }
        val ok = setDefault(ctx)
        val now = currentHome(ctx) == ctx.packageName
        Log.i("TVApps", "главный экран: возврат выбора после обновления: " + if (ok || now) "готово" else "не вышло")
        return if (ok || now) "restored" else "failed"
    }

    /** Кнопка «Сделать домашним экраном сейчас». */
    suspend fun makeDefaultNow(ctx: Context): Boolean {
        runCatching { autoConnect(ctx) }
        if (!Adb.isConnected) return false
        setAlias(ctx, true)
        val ok = setDefault(ctx) || currentHome(ctx) == ctx.packageName
        if (ok) Cfg.setHomePinned(true)
        return ok
    }

    /**
     * Кнопка «Включить и выбрать...»: включает режим лаунчера и назначает программу главным экраном.
     * Раньше без уже подключённых ADB/root кнопка только открывала системный список: на магнитолах и приставках,
     * где главный экран по умолчанию уже выбран, список не появляется и казалось, что ничего не происходит.
     * Теперь: сначала пробуется root (по запросу менеджера root), затем ADB; через них назначение идёт командами
     * cmd package / pm set-home-activity, а на Android 10+ ещё и ролью HOME (cmd role). Результат: ok, no_control, cmd_failed.
     */
    suspend fun enableAsHome(ctx: Context): String {
        prepareAliasForPicker(ctx)
        runCatching { autoConnect(ctx) }
        // человек сам нажал кнопку: можно запросить root у менеджера (раньше root проверялся только в окне «Подключить ADB...»)
        if (!Adb.isConnected && Cfg.useRoot) runCatching { if (Adb.tryRoot()) Conn.refresh() }
        if (!Adb.isConnected) { Log.w("TVApps", "включение «Дома»: нет ни ADB, ни root, открываю системный выбор"); return "no_control" }
        if (setDefault(ctx) || currentHome(ctx) == ctx.packageName) { Cfg.setHomePinned(true); fgPrefs(ctx).edit().putBoolean(PICKER_PREF, false).commit(); return "ok" }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val out = runCatching { Adb.shell("cmd role add-role-holder --user 0 android.app.role.HOME ${ctx.packageName} 0") }.getOrDefault("")
            Log.i("TVApps", "cmd role add-role-holder HOME: ${out.take(160)}")
            kotlinx.coroutines.delay(600)
            if (currentHome(ctx) == ctx.packageName) { Cfg.setHomePinned(true); fgPrefs(ctx).edit().putBoolean(PICKER_PREF, false).commit(); return "ok" }
        }
        Log.w("TVApps", "включение «Дома»: команды не назначили главный экран, текущий=${currentHome(ctx)}")
        return "cmd_failed"
    }

    /**
     * Системное окно выбора главного экрана без ADB и root. Порядок: запрос роли HOME (Android 10+), настройки
     * «Главный экран/Лаунчер по умолчанию», общие «Приложения по умолчанию», обычный запрос «Домой».
     * Возвращает, что открыто: role, home_settings, default_apps, home_intent, none.
     */
    fun openHomePicker(ctx: Context): String {
        prepareAliasForPicker(ctx)
        fun go(i: Intent): Boolean = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val rm = runCatching { ctx.getSystemService(android.app.role.RoleManager::class.java) }.getOrNull()
            if (rm != null && runCatching { rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) }.getOrDefault(false) &&
                go(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME))) return "role"
        }
        return openHomeSettings(ctx, true)
    }

    /** Настройки лаунчера по умолчанию (кнопка «Настройки лаунчера по умолчанию»). */
    fun openHomeSettings(ctx: Context, withIntent: Boolean = false): String {
        prepareAliasForPicker(ctx)
        fun go(i: Intent): Boolean = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        if (go(Intent(android.provider.Settings.ACTION_HOME_SETTINGS))) return "home_settings"
        if (go(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))) return "default_apps"
        if (withIntent && go(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))) return "home_intent"
        restoreAliasAfterPicker(ctx)
        return "none"
    }

    private const val PICKER_PREF = "picker_alias_was_off"

    /**
     * Системный список «Главный экран» показывает только включённые лаунчеры, а HomeAlias по умолчанию выключен
     * (пока «Дом» и режим лаунчера не включены), поэтому наш лаунчер в списке отсутствовал. Перед открытием выбора
     * алиас включается; если он был выключен, это запоминается и после закрытия выбора состояние возвращается
     * (restoreAliasAfterPicker), когда выбран не наш лаунчер.
     */
    fun prepareAliasForPicker(ctx: Context) {
        if (aliasEnabled(ctx)) return
        runCatching { setAlias(ctx, true) }
        fgPrefs(ctx).edit().putBoolean(PICKER_PREF, true).commit()
        Log.i("TVApps", "выбор главного экрана: HomeAlias временно включён, чтобы лаунчер был в списке")
    }

    /** Вызывается при возврате в программу: если алиас включали только ради выбора и наш лаунчер не выбран, выключает его снова. */
    fun restoreAliasAfterPicker(ctx: Context) {
        val p = fgPrefs(ctx)
        if (!p.getBoolean(PICKER_PREF, false)) return
        p.edit().putBoolean(PICKER_PREF, false).commit()
        if (currentHome(ctx) == ctx.packageName) { Cfg.setHomePinned(true); Log.i("TVApps", "выбор главного экрана: выбран наш лаунчер, HomeAlias оставлен включённым"); return }
        runCatching { setAlias(ctx, false) }
        Log.i("TVApps", "выбор главного экрана: выбран другой лаунчер, HomeAlias выключен снова")
    }

    /** Запоминает, что программа сейчас главный экран по умолчанию (дешёвая проверка при запуске окон). */
    fun notePinned(ctx: Context) { runCatching { if (aliasEnabled(ctx) && currentHome(ctx) == ctx.packageName) Cfg.setHomePinned(true) } }

    /** Результат: пустая строка — всё хорошо, иначе причина отказа (для сообщения). */
    suspend fun hardOn(ctx: Context): String {
        if (!Integrity.safe(ctx)) return "integrity"
        if (!Adb.isConnected) return "no_adb"
        val others = toDisable(ctx)
        if (others.isEmpty()) return "no_others"
        if (Cfg.nativeHome.isEmpty()) Cfg.changeNativeHome(others.first())
        setAlias(ctx, true)
        if (!setDefault(ctx) && currentHome(ctx) != ctx.packageName) { return "no_default" }
        val done = mutableListOf<String>()
        for (p in others) {
            val out = runCatching { Adb.shell("pm disable-user --user 0 $p") }.getOrDefault("")
            if (out.contains("disabled", true)) done += p else Log.w("TVApps", "не отключился $p: ${out.take(200)}")
        }
        if (done.isEmpty()) return "no_disable"
        Cfg.changeHardOff(Cfg.hardOff + done)
        writeRestoreFile(ctx)
        // проверка: главным остался только этот экран; иначе сразу возвращаем всё как было
        if (currentHome(ctx).let { it != ctx.packageName }) {
            hardOff(ctx)
            return "check_failed"
        }
        return ""
    }

    /** Возврат родных главных экранов. Не выключает мягкий режим: это делает «Выключить». */
    suspend fun hardOff(ctx: Context): String {
        val list = Cfg.hardOff.toList()
        if (list.isEmpty()) return ""
        if (!Adb.isConnected) return "no_adb"
        val left = mutableSetOf<String>()
        for (p in list) {
            val out = runCatching { Adb.shell("pm enable --user 0 $p") }.getOrDefault("")
            if (!out.contains("enabled", true)) left += p
        }
        Cfg.changeHardOff(left)
        // назначаем прежний родной экран главным
        val native = Cfg.nativeHome.ifEmpty { list.first() }
        val ri = nativeHomes(ctx).firstOrNull { it.activityInfo.packageName == native } ?: nativeHomes(ctx).firstOrNull()
        if (ri != null) runCatching { setHomeCmd("${ri.activityInfo.packageName}/${ri.activityInfo.name}") }
        runCatching { Adb.shell("rm -f $RESTORE_FILE") }
        return if (left.isEmpty()) "" else "partial"
    }

    /** Команды возврата в файле на устройстве: пригодится, если программа не открывается. Только ASCII: оболочка не принимает кириллицу. */
    private suspend fun writeRestoreFile(ctx: Context) {
        val lines = listOf("Restore the built-in home screen (App Menu). Run from a computer:") +
            Cfg.hardOff.map { "adb shell pm enable --user 0 $it" } +
            listOf("adb shell pm clear-preferred-activities ${ctx.packageName}")
        val args = lines.joinToString(" ") { "'" + it.replace("'", "") + "'" }
        runCatching { Adb.shell("printf '%s\n' $args > $RESTORE_FILE") }
    }

    // ---- защита от зависаний ----

    /**
     * Вызывается при каждом запуске процесса. Если процесс перезапускается слишком часто (4 раза за 25 секунд),
     * а родные экраны отключены, сразу возвращаем их: человек не должен остаться без главного экрана.
     */
    fun guardOnProcessStart(ctx: Context) {
        if (!Integrity.ok(ctx)) return
        val sp = ctx.getSharedPreferences("tvapps_guard", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val times = (sp.getString("starts", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.filter { now - it < 25_000 } + now
        sp.edit().putString("starts", times.takeLast(8).joinToString(",")).apply()
        // возврат «липкий»: если процесс убили, пока шёл возврат, его продолжит следующий запуск
        val pending = sp.getBoolean("restore_pending", false)
        if (Cfg.hardOff.isEmpty()) { if (pending) sp.edit().putBoolean("restore_pending", false).apply(); return }
        if (times.size < 4 && !pending) return
        sp.edit().putString("starts", "").putBoolean("restore_pending", true).putLong("tripped", now).apply()
        Thread {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    // подключаемся так же, как при обычном запуске (root, затем ADB)
                    var tries = 0
                    while (!Adb.isConnected && tries++ < 3) { autoConnect(ctx); if (!Adb.isConnected) kotlinx.coroutines.delay(2000) }
                    val r = if (Adb.isConnected) hardOff(ctx) else "no_adb"
                    // наш экран выключаем ТОЛЬКО если родной действительно вернулся: иначе человек останется без главного экрана
                    if (r.isEmpty() && Cfg.hardOff.isEmpty()) {
                        setAlias(ctx, false)
                        sp.edit().putBoolean("restore_pending", false).apply()
                        Log.w("TVApps", "защита Дома: родной экран возвращён")
                    } else Log.w("TVApps", "защита Дома: родной экран вернуть не удалось ($r), наш экран оставлен")
                }
            }
        }.start()
    }

    /** Защита сработала недавно (для сообщения в настройках). */
    fun guardTripped(ctx: Context): Boolean =
        System.currentTimeMillis() - ctx.getSharedPreferences("tvapps_guard", Context.MODE_PRIVATE).getLong("tripped", 0L) < 10 * 60_000L
}
