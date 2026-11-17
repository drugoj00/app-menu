package app.tvapps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Тихая установка через ADB-phon без показа его экрана (9.0): файл отдаётся фоновой службе ADB-phon (в самом ADB-phon она появляется с 11.0).
 * Служба ADB-phon 11.0: app.adbphon.InstallService, действие app.adbphon.action.INSTALL_SILENT, данные (data) = content-адрес файла нашего FileProvider (+ право чтения),
 * extras: caller (наш пакет) и receiver (ResultReceiver). Подписи Меню и ADB-phon разные: ADB-phon пускает по списку пакетов и ставит только APK с подписью вызывающего.
 * Коды ответа: 0 готово, 1 ход (message = проценты), 2 принято, 3 отмена, 10 отказ, 11 подпись APK, 12 ошибка.
 * Окно «Установка» с ходом рисует само «Меню приложений». Если службы нет (ADB-phon старее 11.0, другой ключ подписи), InstallerPick откатывается на окно ADB-phon с пояснением.
 */
object AdbPhoneSilent {
    const val ACTION = "app.adbphon.action.INSTALL_SILENT"
    const val EXTRA_CALLER = "caller"
    const val EXTRA_RESULT = "receiver"
    private const val PKG = "app.adbphon"
    private const val SERVICE = "app.adbphon.InstallService"

    /** Чьё это: в отладочной сборке пакет службы можно подменить файлом files/dbg_silent_pkg (заглушка внутри самой программы). */
    private fun target(ctx: Context): ComponentName {
        if (BuildConfig.DEBUG) {
            val f = File(ctx.filesDir, "dbg_silent_pkg")
            if (f.isFile) { val p = f.readText().trim(); if (p.isNotEmpty()) return ComponentName(p, if (p == ctx.packageName) "app.tvapps.DbgInstallService" else SERVICE) }
        }
        return ComponentName(PKG, SERVICE)
    }

    /** Есть ли служба тихой установки: ADB-phon установлен, версия не ниже 11.0 (versionCode 59), служба разрешается. Подписи у Меню и ADB-phon разные, их не сверяем: ADB-phon сам пускает по списку пакетов и требует APK с подписью вызывающего. Возвращает (да/нет, причина). */
    @Suppress("DEPRECATION")
    fun available(ctx: Context): Pair<Boolean, String> {
        val cn = target(ctx)
        val pm = ctx.packageManager
        val pi = runCatching { pm.getPackageInfo(cn.packageName, 0) }.getOrNull() ?: return false to ctx.getString(R.string.silent_no_service)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        if (cn.packageName != ctx.packageName && code < 59L) return false to ctx.getString(R.string.silent_no_service)
        val info = runCatching { pm.getServiceInfo(cn, 0) }.getOrNull() ?: return false to ctx.getString(R.string.silent_no_service)
        if (!info.exported) return false to ctx.getString(R.string.silent_no_service)
        return true to ""
    }

    /** Передать файл службе и дождаться ответа. Результат: "ok" или "fail:причина"; "unavailable" — службу запустить не удалось (откат на окно ADB-phon). */
    suspend fun install(ctx: Context, file: File, label: String, onProgress: (String) -> Unit): String {
        val app = ctx.applicationContext
        val cn = target(app)
        val tmp = File(File(app.cacheDir, "batch").apply { mkdirs() }, "silent_" + label.replace(Regex("""[^\w.]"""), "_").take(40) + ".apk")
        withContext(Dispatchers.IO) { file.copyTo(tmp, true) }
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", tmp)
        runCatching { app.grantUriPermission(cn.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val done = CompletableDeferred<String>()
        val rr = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(code: Int, data: Bundle?) {
                val msg = data?.getString("message").orEmpty()
                // коды службы ADB-phon: 0 готово, 1 ход (message = проценты), 2 принято, 3 отмена в системном окне, 10 отказ, 11 подпись APK, 12 ошибка
                when (code) {
                    1 -> if (msg.isNotBlank()) onProgress(if (msg.all { it.isDigit() }) "$msg%" else msg)
                    2 -> {}
                    0 -> done.complete("ok")
                    3 -> done.complete("fail:" + app.getString(R.string.inst_cancelled))
                    else -> done.complete("fail:" + msg.ifBlank { "ADB-phon: " + (data?.getString("status") ?: code.toString()) })
                }
            }
        }
        // в другой процесс уходит обычный ResultReceiver: подкласс с нашим именем ADB-phon не смог бы прочитать (BadParcelableException, найдено на A14)
        val plain = run {
            val pc = android.os.Parcel.obtain()
            try { rr.writeToParcel(pc, 0); pc.setDataPosition(0); ResultReceiver.CREATOR.createFromParcel(pc) } finally { pc.recycle() }
        }
        val i = Intent(ACTION).setComponent(cn).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(EXTRA_CALLER, app.packageName).putExtra(EXTRA_RESULT, plain)
        i.clipData = android.content.ClipData.newRawUri("", uri)
        val started = runCatching { app.startService(i) != null }.getOrDefault(false) ||
            runCatching { androidx.core.content.ContextCompat.startForegroundService(app, i); true }.getOrDefault(false)
        if (!started) return "unavailable"
        // до 4 минут: установка идёт в фоне у ADB-phon; нет ответа — решает проверка версии у вызывающего
        return withTimeoutOrNull(4 * 60_000L) { done.await() } ?: "fail:" + app.getString(R.string.inst_timeout_silent)
    }
}
