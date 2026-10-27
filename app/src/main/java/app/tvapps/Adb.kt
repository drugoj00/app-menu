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
    private lateinit var mgr: AdbConn

    /** Команды идут по одной: две одновременные на одном соединении иногда зависают до тайм-аута. */
    private val lock = kotlinx.coroutines.sync.Mutex()

    private lateinit var appCtx: Context

    fun init(context: Context) {
        appCtx = context.applicationContext
        mgr = AdbConn(context.applicationContext)
    }

    /** Команды идут через root (Magisk), а не через ADB: магнитолам и приставкам без компьютера ADB не нужен. */
    @Volatile private var rootOn = false
    val isRoot: Boolean get() = rootOn

    val isConnected: Boolean get() = rootOn || (::mgr.isInitialized && mgr.isConnected)

    /**
     * Проверяет, дал ли Magisk (или другой su) права root этому приложению. При первом запуске на экране появится запрос Magisk:
     * ждём ответа до 30 секунд. Успех включает режим root для всех команд ниже.
     */
    suspend fun tryRoot(): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching { rootRun("id", null, 30_000).contains("uid=0") }
            .onFailure { Log.i("TVApps", "root недоступен: ${it.message}") }.getOrDefault(false)
        rootOn = ok
        Log.i("TVApps", "root=$ok")
        ok
    }

    fun disableRoot() { rootOn = false }

    private fun q(p: String) = "'" + p.replace("'", "'\\''") + "'"

    /**
     * Выполняет команду от root: `su -c`. [input] (если есть) подаётся в стандартный ввод команды (для установки пакета и записи файла).
     * Возвращает объединённый вывод; по тайм-ауту процесс убивается.
     */
    private fun rootRun(cmd: String, input: InputStream?, timeoutMs: Long): String {
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

    /** Ждёт, пока в фоне загрузятся ключ и шифрование (до 10 секунд). */
    suspend fun awaitReady() {
        repeat(200) { if (::mgr.isInitialized) return; kotlinx.coroutines.delay(50) }
    }

    suspend fun connectLocal(port: Int, host: String = "127.0.0.1"): Result<Unit> = withContext(Dispatchers.IO) {
        rootOn = false   // выбран ADB: команды идут по ADB, а не через root
        runCatching {
            // Первое подключение к новому устройству: пока человек нажимает «Разрешить», попытка обрывается; вторая проходит сразу
            var last: Throwable? = null
            var done = false
            for (attempt in 1..2) {
                runCatching { mgr.disconnect() }
                Log.i("TVApps", "connect $host:$port (попытка $attempt)")
                val r = runCatching { withTimeout(25_000) { runInterruptible { mgr.connect(host, port) } } }
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
            if (host == "127.0.0.1") {
                ok = runCatching { mgr.autoConnect(context.applicationContext, 7000) }
                    .onFailure { Log.e("TVApps", "autoConnect error", it) }.getOrDefault(false)
            }
            if (!ok && manualPort != null) ok = mgr.connect(host, manualPort)
            if (!ok) error("connect failed")
        }.onFailure { Log.e("TVApps", "wireless connect error", it) }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) { rootOn = false; runCatching { mgr.disconnect() } }

    /** Выполнить команду оболочки и вернуть её вывод. */
    suspend fun shell(cmd: String, timeoutMs: Long = 25_000): String = withContext(Dispatchers.IO) {
        lock.lock()
        Log.i("TVApps", "shell> ${cmd.take(300)}")
        val t0 = System.currentTimeMillis()
        val res = try {
            runCatching { if (rootOn) rootRun(cmd, null, timeoutMs) else readAll(mgr.openStream("shell:$cmd"), timeoutMs, cmd) }
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
            val s = mgr.openStream("exec:cat '" + path.replace("'", "'\\''") + "'")
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
        if (rootOn) {
            val tmp = java.io.File(appCtx.cacheDir, "root-install-" + System.nanoTime() + ".apk")
            try {
                tmp.outputStream().use { input.copyTo(it) }
                return@withContext rootRun("cmd package install ${withUser(flags)} -S ${tmp.length()} < " + q(tmp.absolutePath), null, 600_000)
            } finally { tmp.delete() }
        }
        val s = mgr.openStream("exec:cmd package install ${withUser(flags)} -S $size")
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
        val s = mgr.openStream("exec:head -c $size > " + q(dest))
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
        for (f in files) {
            val res = withContext(Dispatchers.IO) {
                if (rootOn) return@withContext f.inputStream().use { rootRun("cmd package install-write -S ${f.length()} $id ${q(f.name)} - < " + q(f.absolutePath), null, 600_000) }
                val s = mgr.openStream("exec:cmd package install-write -S ${f.length()} $id ${f.name} -")
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
