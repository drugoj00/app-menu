package app.tvapps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Проверка подлинности копии (только в release): отпечаток подписи этой программы сверяется с отпечатком нашего ключа,
 * зашитым при сборке. Если копию переделали и подписали чужим ключом, опасные действия (ADB и root, установка, смена
 * главного экрана, очистка памяти, обновление) не работают, а в программе показывается предупреждение. Меню и запуск приложений остаются рабочими,
 * чтобы человек не остался без экрана. Обновление своим же ключом проверку проходит.
 *
 * Проверок несколько, и они независимы друг от друга (разные способы чтения подписи, разные константы, разные места вызова):
 * [ok] (подпись через PackageManager, отладчик и подмена среды) и [safe] (то же плюс подпись прямо из файла APK, подпись из META-INF,
 * контрольные суммы DEX и ресурсов). Вызываются перед каждым опасным действием, а не только при запуске.
 */
object Integrity {
    @Volatile private var cached: Boolean? = null
    @Volatile private var deepAt = 0L
    @Volatile private var deepRes = true
    /** Номер проверки, которая сработала последней (для строки предупреждения): 1 подпись, 2 файл APK, 3 META-INF, 4 суммы, 5 среда. */
    @Volatile var why = ""

    /** Предварительная полная проверка в фоне (через несколько секунд после запуска): первое опасное действие не ждёт чтения файла программы. */
    fun warm(ctx: Context) {
        if (!release()) return
        Thread {
            runCatching { Thread.sleep(4000); safe(ctx) }
        }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    private fun release() = BuildConfig.SIGNER_SHA256.isNotEmpty()

    /** true: копия наша (или это отладочная сборка). Дёшево: результат подписи запоминается, среда проверяется не чаще раза в 5 секунд. */
    fun ok(ctx: Context? = null): Boolean {
        if (!release()) return true
        val sig = cached ?: run {
            val c = ctx ?: App.instance ?: return true
            // сбой чтения подписи (менеджер пакетов занят сразу после обновления и т. п.) не запоминается как «чужая копия»: проверка повторится при следующем вызове
            val r = runCatching { sign(c).equals(BuildConfig.SIGNER_SHA256, ignoreCase = true) }.getOrNull() ?: return env()
            cached = r
            r
        }
        if (!sig) { why = "1"; return false }
        if (!env()) { why = "5"; return false }
        return true
    }

    /** Для предупреждения на экране: подпись и среда в порядке и последняя полная проверка (если уже была) тоже. Сама тяжёлых проверок не запускает. */
    fun statusOk(): Boolean = ok() && (deepAt == 0L || deepRes)

    /** Полная проверка перед опасным действием: все независимые проверки сразу; итог помнится 10 минут (файл программы за это время не меняется). */
    fun safe(ctx: Context? = null): Boolean {
        if (!release()) return true
        if (!ok(ctx)) return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (deepAt != 0L && now - deepAt < 600_000L) return deepRes
        val c = ctx ?: App.instance ?: return true
        // «null» = проверить не удалось (устройство не отдало данные, ошибка чтения): это не признак подделки, тревоги нет; тревога только при явном несовпадении
        val r2 = runCatching { archiveSign(c) }.getOrNull()
        val r3 = runCatching { metaInfSign(c) }.getOrNull()
        val r4 = runCatching { sums(c) }.getOrNull()
        val r = r2 != false && r3 != false && r4 != false
        if (!r) why = if (r2 == false) "2" else if (r3 == false) "3" else "4"
        deepAt = now; deepRes = r
        return r
    }

    // ---- проверка 1: подпись установленной программы (PackageManager)
    private fun sign(ctx: Context): String {
        val pm = ctx.packageManager
        val bytes = if (Build.VERSION.SDK_INT >= 28) {
            val si = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory).first().toByteArray()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures.first().toByteArray()
        }
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    // ---- проверка 2: подпись прямо из файла APK (другой путь чтения, другая константа)
    @Suppress("DEPRECATION")
    private fun archiveSign(ctx: Context): Boolean? {
        val pm = ctx.packageManager
        val pi = if (Build.VERSION.SDK_INT >= 28) pm.getPackageArchiveInfo(ctx.packageCodePath, PackageManager.GET_SIGNING_CERTIFICATES)
        else pm.getPackageArchiveInfo(ctx.packageCodePath, PackageManager.GET_SIGNATURES)
        pi ?: return null
        val bytes = if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo ?: return null
            (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory).first().toByteArray()
        } else (pi.signatures?.firstOrNull() ?: return null).toByteArray()
        return same(MessageDigest.getInstance("SHA-256").digest(bytes), BuildConfig.SIGNER_SHA256_R.reversed())
    }

    // ---- проверка 3: сертификат из META-INF файла APK (разбор самого файла)
    private fun metaInfSign(ctx: Context): Boolean? {
        ZipFile(ctx.packageCodePath).use { z ->
            val e = z.entries().asSequence().firstOrNull { it.name.startsWith("META-INF/") && (it.name.endsWith(".RSA") || it.name.endsWith(".DSA") || it.name.endsWith(".EC")) }
                // подпись только схемой v2 без META-INF: тогда эта проверка опирается на две остальные
                ?: return Build.VERSION.SDK_INT >= 24
            val certs = z.getInputStream(e).use { java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(it) }
            val c = certs.firstOrNull() ?: return null
            return same(MessageDigest.getInstance("SHA-256").digest(c.encoded), BuildConfig.SIGNER_SHA256_R.reversed())
        }
    }

    // ---- проверка 4: контрольные суммы DEX и ресурсов (записаны при сборке в assets/ic.bin)
    private fun sums(ctx: Context): Boolean? {
        // файл сумм дописывает выпускной скрипт (finalize_apk.py); в сборке без него эта проверка не действует
        val want = runCatching { ctx.assets.open("ic.bin").bufferedReader().use { it.readText() } }.getOrNull() ?: return true
        val expect = want.split(';').filter { it.contains('=') }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        if (expect.isEmpty()) return false
        ZipFile(ctx.packageCodePath).use { z ->
            for ((name, crc) in expect) {
                val e = z.getEntry(name) ?: return false
                if (!java.lang.Long.toHexString(e.crc).equals(crc, ignoreCase = true)) return false
            }
            // лишних файлов classes*.dex быть не должно
            val dex = z.entries().asSequence().map { it.name }.filter { it.startsWith("classes") && it.endsWith(".dex") }.toList()
            if (dex.any { it !in expect }) return false
        }
        return true
    }

    // ---- проверка 5: отладчик и подмена среды (Frida, Xposed)
    @Volatile private var envAt = 0L
    @Volatile private var envRes = true
    private fun env(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (envAt != 0L && now - envAt < 5_000L) return envRes
        val r = runCatching { envNow() }.getOrDefault(true)
        envAt = now; envRes = r
        return r
    }

    private fun envNow(): Boolean {
        // к выпускной копии подключён отладчик или она собрана отлаживаемой (подделка переупаковкой)
        if (android.os.Debug.isDebuggerConnected()) return false
        val c = App.instance ?: return true
        if ((c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) return false
        // Xposed/LSPosed внутри процесса программы: класс моста загружен только когда процесс подменяют
        for (n in arrayOf("de.robv.android.xposed.XposedBridge", "org.lsposed.lspd.core.Main")) {
            val loaded = runCatching { Class.forName(n); true }.getOrDefault(false)
            if (loaded) return false
        }
        // Frida: её библиотеки в памяти процесса
        val maps = runCatching { File("/proc/self/maps").readText() }.getOrDefault("")
        if (maps.contains("frida-agent") || maps.contains("frida-gadget") || maps.contains("gum-js-loop")) return false
        return true
    }

    private fun same(digest: ByteArray, expectHex: String): Boolean = MessageDigest.isEqual(hex(digest).lowercase().toByteArray(), expectHex.lowercase().toByteArray())
    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
}
