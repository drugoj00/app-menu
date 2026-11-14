package app.tvapps

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runInterruptible
import android.util.Log
import java.io.InputStream

/** Единая точка работы с ADB этого же телефона. */
object Adb {
    /** Соединение ADB создаётся при первом обращении (вместе с шифрованием Conscrypt и ключом): на приставках с root и у тех, кто ADB не использует, оно не занимает память. */
    @Volatile private var mgrInst: AdbConn? = null
    private val mgr: AdbConn get() = ensure()

    private fun ensure(): AdbConn = mgrInst ?: synchronized(this) {
        mgrInst ?: run {
            // TLS 1.3 с экспортом ключевого материала нужен для сопряжения и беспроводной отладки
            java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1)
            AdbConn(appCtx).also { mgrInst = it }
        }
    }

    /** Команды идут по одной: две одновременные на одном соединении иногда зависают до тайм-аута. */
    private val lock = kotlinx.coroutines.sync.Mutex()

    private lateinit var appCtx: Context

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    /** Команды идут через root, а не через ADB: магнитолам и приставкам без компьютера ADB не нужен. */
    @Volatile private var rootOn = false
    val isRoot: Boolean get() = rootOn

    /** Подключено ли управление (ADB или root). У копии с чужой подписью всегда нет: см. Integrity. */
    val isConnected: Boolean get() = Integrity.ok() && (rootOn || (mgrInst?.isConnected == true))

    /** Есть ли подключение root или ADB само по себе, без учёта проверки целостности (только для выдачи разрешения на привязку виджета, см. shellWidgetGrant). */
    val controlAvailable: Boolean get() = rootOn || (mgrInst?.isConnected == true)

    /**
     * Проверяет, дал ли менеджер root (или другой su) права root этому приложению. При первом запуске на экране появится запрос менеджере root:
     * ждём ответа до 30 секунд. Успех включает режим root для всех команд ниже.
     */
    suspend fun tryRoot(): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching { rootRun("id", null, 30_000).contains("uid=0") }
            .onFailure { Log.i("TVApps", "root недоступен: ${it.message}") }.getOrDefault(false)
        rootOn = ok
        saveRootCache(ok)
        Log.i("TVApps", "root=$ok")
        ok
    }

    /** Результат прошлой проверки root хранится, чтобы не запускать su при старте программы «на всякий случай». */
    private fun rootPrefs() = appCtx?.getSharedPreferences("tvapps_root", android.content.Context.MODE_PRIVATE)
    private fun saveRootCache(ok: Boolean) { runCatching { rootPrefs()?.edit()?.putBoolean("granted", ok)?.apply() } }

    /**
     * Без запуска su включает режим root, если раньше он уже был выдан и подтверждён. Сам su запустится позже, лениво, при первой команде,
     * которая реально требует root. Возвращает true, если режим root включён по сохранённому признаку.
     */
    fun useCachedRoot(): Boolean {
        if (rootOn) return true
        val granted = runCatching { rootPrefs()?.getBoolean("granted", false) }.getOrNull() == true
        if (granted) { rootOn = true; Log.i("TVApps", "root: по сохранённому признаку, su не запускается до первой команды") }
        return granted
    }

    fun disableRoot() { rootOn = false; synchronized(rsLock) { rsStop() } }

    private fun q(p: String) = "'" + p.replace("'", "'\\''") + "'"

    /**
     * Выполняет команду от root: `su -c`. [input] (если есть) подаётся в стандартный ввод команды (для установки пакета и записи файла).
     * Возвращает объединённый вывод; по тайм-ауту процесс убивается.
     */
    // Один постоянный root-процесс на все команды без ввода: su запускается один раз, а не на каждую команду
    // (каждый запуск su показывал у части систем уведомление «права суперпользователя предоставлены»).
    private val rsLock = Any()
    private var rsProc: Process? = null
    private var rsIn: java.io.OutputStream? = null
    private var rsQueue: java.util.concurrent.LinkedBlockingQueue<String>? = null

    private fun rsStop() {
        runCatching { rsProc?.destroy() }
        rsProc = null; rsIn = null; rsQueue = null
    }

    private fun rsEnsure(): Boolean {
        if (rsProc != null && runCatching { rsProc!!.exitValue() }.isFailure) return true
        rsStop()
        return runCatching {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            val q = java.util.concurrent.LinkedBlockingQueue<String>()
            Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine { q.put(it) } }
                q.put("\u0000EOF")
            }.also { it.isDaemon = true; it.name = "root-shell-reader" }.start()
            rsProc = p; rsIn = p.outputStream; rsQueue = q
            true
        }.getOrDefault(false)
    }

    /** Команда в постоянной root-оболочке. null: оболочка недоступна (запасной путь — отдельный su). */
    private fun rootShellRun(cmd: String, timeoutMs: Long): String? = synchronized(rsLock) {
        if (!rsEnsure()) return null
        val q = rsQueue!!
        q.clear()
        val mark = "__TVA_" + System.nanoTime() + "__"
        val sb = StringBuilder()
        try {
            rsIn!!.write("(\n$cmd\n) </dev/null 2>&1\necho\necho $mark\n".toByteArray(Charsets.UTF_8)); rsIn!!.flush()
        } catch (_: Throwable) { rsStop(); return null }
        val t0 = System.currentTimeMillis()
        while (true) {
            val left = timeoutMs - (System.currentTimeMillis() - t0)
            if (left <= 0) { Log.w("TVApps", "timeout root: ${cmd.take(100)}"); rsStop(); break }
            val line = q.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
            if (line == "\u0000EOF") { rsStop(); if (sb.isEmpty() && rootOn) { Log.w("TVApps", "root: su закрылся без ответа, признак сброшен"); rootOn = false; saveRootCache(false) }; break }
            if (line.contains(mark)) break
            sb.append(line).append('\n')
        }
        sb.toString().trim()
    }

    private fun rootRun(cmd: String, input: InputStream?, timeoutMs: Long): String {
        if (input == null) rootShellRun(cmd, timeoutMs)?.let { return it }
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = java.io.ByteArrayOutputStream()
        val reader = Thread { runCatching { p.inputStream.copyTo(out) } }
        reader.start()
        val writer = Thread {
            runCatching { p.outputStream.use { o -> input?.copyTo(o) } }
        }
        writer.start()
        val t0 = System.currentTimeMillis()
        var finished = false
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (runCatching { p.exitValue() }.isSuccess) { finished = true; break }
            Thread.sleep(50)
        }
        if (!finished) { Log.w("TVApps", "timeout root: ${cmd.take(100)}"); p.destroy() }
        reader.join(2000)
        writer.join(500)
        return synchronized(out) { out.toString("UTF-8") }.trim()
    }

    /** Раньше ждала фоновой загрузки ключа и шифрования; теперь они создаются при первом обращении к ADB, ждать нечего. */
    suspend fun awaitReady() {}

    suspend fun connectLocal(port: Int, host: String = "127.0.0.1"): Result<Unit> = withContext(Dispatchers.IO) {
        rootOn = false   // выбран ADB: команды идут по ADB, а не через root
        runCatching {
            // Первое подключение к новому устройству: пока человек нажимает «Разрешить», попытка обрывается; вторая проходит сразу
            var last: Throwable? = null
            var done = false
            // порт ADB не слушается (на старых Android без сетевого ADB): не ждать 2×25 с, а сразу сообщить об ошибке
            val portOpen = runCatching { java.net.Socket().use { s -> s.connect(java.net.InetSocketAddress(host, port), 2_000); true } }.getOrDefault(false)
            if (!portOpen) { Log.w("TVApps", "connect $host:$port: порт не отвечает, ожидание пропущено"); throw IllegalStateException("adb port closed") }
            val perTry = 25_000L
            for (attempt in 1..2) {
                runCatching { mgr.disconnect() }
                Log.i("TVApps", "connect $host:$port (попытка $attempt)")
                val r = runCatching { withTimeout(perTry) { runInterruptible { mgr.connect(host, port) } } }
                Log.i("TVApps", "connect result=${r.getOrNull()}")
                if (r.getOrNull() == true) { done = true; break }
                last = r.exceptionOrNull() ?: IllegalStateException("connect failed")
            }
            if (!done) throw last ?: IllegalStateException("connect failed")
        }.onFailure { Log.e("TVApps", "connect error", it) }
    }

    suspend fun pair(port: Int, code: String, host: String = "127.0.0.1"): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { mgr.pair(host, port, code) }.map { }.onFailure { Log.e("TVApps", "pair error", it) }
    }

    /** Беспроводная отладка: порт ищется сам (mDNS), при неудаче используется порт, введённый вручную. */
    suspend fun connectWireless(context: Context, manualPort: Int?, host: String = "127.0.0.1"): Result<Unit> = withContext(Dispatchers.IO) {
        rootOn = false   // выбран ADB: команды идут по ADB, а не через root
        runCatching {
            runCatching { mgr.disconnect() }
            var ok = false
            if (host == "127.0.0.1" && android.os.Build.VERSION.SDK_INT >= 30) {
                ok = runCatching { mgr.autoConnect(context.applicationContext, 7000) }
                    .onFailure { Log.e("TVApps", "autoConnect error", it) }.getOrDefault(false)
            }
            if (!ok && manualPort != null) ok = mgr.connect(host, manualPort)
            if (!ok) error("connect failed")
        }.onFailure { Log.e("TVApps", "wireless connect error", it) }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) { rootOn = false; runCatching { mgr.disconnect() } }

    /**
     * Узкое исключение из проверки целостности: ТОЛЬКО выдача этому же приложению права на привязку виджета
     * («cmd appwidget grantbind --package <наш пакет> …» или «appops set <наш пакет> BIND_APPWIDGET allow»). Остальные команды сюда не проходят
     * (самообновление, установка, остановка приложений по-прежнему требуют Integrity.safe()). Причина «чужой» среды пишется в журнал TVApps.
     */
    suspend fun shellWidgetGrant(cmd: String, pkg: String, timeoutMs: Long = 12_000): String = withContext(Dispatchers.IO) {
        val allowed = cmd == "cmd appwidget grantbind --package $pkg --user 0" ||
            cmd == "appwidget grantbind --package $pkg --user 0" ||
            cmd == "appops set $pkg BIND_APPWIDGET allow"
        if (!allowed) return@withContext ""
        if (!Integrity.ok()) Log.w("TVApps", "виджет: проверка целостности не пройдена (why=${Integrity.why}), разрешение на привязку выдаётся только для нашего пакета")
        lock.lock()
        Log.i("TVApps", "shell(widget)> ${cmd.take(200)}")
        val res = try {
            runCatching { if (rootOn) rootRun(cmd, null, timeoutMs) else readAll(mgr.openStream(AdbText.safe("shell:$cmd")), timeoutMs, cmd) }
        } finally { lock.unlock() }
        Log.i("TVApps", "shell(widget)< ${(res.getOrNull() ?: res.exceptionOrNull()?.message).orEmpty().take(200).replace("\n", " ⏎ ")}")
        res.getOrDefault("")
    }

    /** Выполнить команду оболочки и вернуть её вывод. */
    suspend fun shell(cmd: String, timeoutMs: Long = 25_000): String = withContext(Dispatchers.IO) {
        if (!Integrity.safe()) return@withContext ""
        lock.lock()
        Log.i("TVApps", "shell> ${cmd.take(300)}")
        val t0 = System.currentTimeMillis()
        val res = try {
            runCatching { if (rootOn) rootRun(cmd, null, timeoutMs) else readAll(mgr.openStream(AdbText.safe("shell:$cmd")), timeoutMs, cmd) }
        } finally { lock.unlock() }
        Log.i("TVApps", "shell< ${System.currentTimeMillis() - t0} мс, ${res.getOrNull()?.length ?: -1} симв: ${(res.getOrNull() ?: res.exceptionOrNull()?.message).orEmpty().take(300).replace("\n", " ⏎ ")}")
        res.getOrThrow()
    }

    /**
     * Читает поток до конца с ограничением по времени. Библиотека иногда бросает «Stream closed» вместо конца потока
     * или не закрывает поток: прочитанное считаем ответом, зависший поток закрываем сами.
     */
    private fun readAll(s: io.github.muntashirakon.adb.AdbStream, timeoutMs: Long, what: String): String {
        val out = java.io.ByteArrayOutputStream()
        var error: Throwable? = null
        val lastData = java.util.concurrent.atomic.AtomicLong(0L)
        val reader = Thread {
            try {
                val input = s.openInputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    synchronized(out) { out.write(buf, 0, n) }
                    lastData.set(System.currentTimeMillis())
                }
            } catch (e: Throwable) {
                error = e
            }
        }
        reader.start()
        val t0 = System.currentTimeMillis()
        // Ответ получен, а поток не закрылся (первая команда после подключения): через 2 с тишины считаем её завершённой
        while (reader.isAlive && System.currentTimeMillis() - t0 < timeoutMs) {
            reader.join(100)
            val l = lastData.get()
            if (l != 0L && reader.isAlive && System.currentTimeMillis() - l > 2000) break
        }
        if (reader.isAlive) {
            if (System.currentTimeMillis() - t0 >= timeoutMs) Log.w("TVApps", "timeout reading: $what")
            runCatching { s.close() }
            reader.join(2000)
        } else {
            runCatching { s.close() }
        }
        val text = synchronized(out) { out.toString("UTF-8") }.trim()
        val e = error
        if (e != null && text.isEmpty() && e.message?.contains("closed", ignoreCase = true) != true) throw e
        return text
    }

    /** Копирует файл с устройства через оболочку ADB (так читаются флешки, которые система приложению не показывает). */
    suspend fun pull(path: String, dest: java.io.File) = withContext(Dispatchers.IO) {
        lock.lock()
        try {
            if (rootOn) {
                val p = ProcessBuilder("su", "-c", "cat " + q(path)).start()
                runCatching { p.outputStream.close() }
                Thread { runCatching { p.errorStream.copyTo(java.io.ByteArrayOutputStream()) } }.start()
                p.inputStream.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                p.waitFor()
                return@withContext
            }
            val s = mgr.openStream(AdbText.safe("exec:cat '" + path.replace("'", "'\\''") + "'"))
            try {
                s.openInputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            } finally { runCatching { s.close() } }
        } finally { lock.unlock() }
    }

    /**
     * Ставим только текущему пользователю: без этого Samsung кладёт копию ещё и в «Двойное приложение» (DUAL_APP),
     * и значки в меню телефона задваиваются.
     */
    private suspend fun withUser(flags: String): String {
        if (flags.contains("--user")) return flags
        val u = runCatching { shell("am get-current-user").trim().toInt() }.getOrDefault(0)
        return "$flags --user $u"
    }

    /** Установить APK потоком (cmd package install -S). */
    suspend fun installApk(input: InputStream, size: Long, flags: String = "-r"): String = withContext(Dispatchers.IO) {
        if (!Integrity.safe()) return@withContext ""
        if (rootOn) {
            val tmp = java.io.File(appCtx.cacheDir, "root-install-" + System.nanoTime() + ".apk")
            try {
                tmp.outputStream().use { input.copyTo(it) }
                return@withContext rootRun("cmd package install ${withUser(flags)} -S ${tmp.length()} < " + q(tmp.absolutePath), null, 600_000)
            } finally { tmp.delete() }
        }
        val s = mgr.openStream(AdbText.safe("exec:cmd package install ${withUser(flags)} -S $size"))
        val out = s.openOutputStream()
        input.copyTo(out)
        out.flush()
        readAll(s, 120_000, "install")
    }

    /** Записывает [size] байт из потока в файл на устройстве от имени shell (туда, куда обычному приложению писать нельзя, например Android/obb). */
    suspend fun pushStream(input: InputStream, size: Long, dest: String) = withContext(Dispatchers.IO) {
        shell("mkdir -p " + q(dest.substringBeforeLast('/')))
        if (rootOn) {
            rootRun("head -c $size > " + q(dest), input, 1_800_000)
            val m = Regex("^(.*/Android/(?:obb|data))/([^/]+)/").find(dest)
            if (m != null) runCatching { rootRun("chown -R \$(stat -c %u:%g " + q(m.groupValues[1]) + ") " + q(m.groupValues[1] + "/" + m.groupValues[2]), null, 60_000) }
            return@withContext Unit
        }
        val s = mgr.openStream(AdbText.safe("exec:head -c $size > " + q(dest)))
        val out = s.openOutputStream()
        input.copyTo(out)
        out.flush()
        readAll(s, 600_000, "push")
        Unit
    }

    /** Установка одного APK или набора частей одного приложения (сессия установки). flags: например "-r" или "-r -d". */
    suspend fun installFiles(files: List<java.io.File>, flags: String = "-r"): String {
        if (files.isEmpty()) return "no files"
        if (files.size == 1) return files[0].inputStream().use { installApk(it, files[0].length(), flags) }
        val made = shell("cmd package install-create ${withUser(flags)} -S ${files.sumOf { it.length() }}")
        val id = Regex("\\[(\\d+)\\]").find(made)?.groupValues?.get(1) ?: return made
        for ((idx, f) in files.withIndex()) {
            // имя части в сессии: только ASCII и номер (кириллица, пробелы, скобки, апострофы и эмодзи в имени файла не попадают в команду)
            val partName = idx.toString() + "_" + f.name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(60)
            val res = withContext(Dispatchers.IO) {
                if (rootOn) return@withContext f.inputStream().use { rootRun("cmd package install-write -S ${f.length()} $id ${q(partName)} - < " + q(f.absolutePath), null, 600_000) }
                val s = mgr.openStream(AdbText.safe("exec:cmd package install-write -S ${f.length()} $id $partName -"))
                val out = s.openOutputStream()
                f.inputStream().use { it.copyTo(out) }
                out.flush()
                readAll(s, 120_000, "install-write")
            }
            if (!res.contains("Success", true)) { runCatching { shell("cmd package install-abandon $id") }; return res }
        }
        return shell("cmd package install-commit $id", 120_000)
    }
}
