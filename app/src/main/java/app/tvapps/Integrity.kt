package app.tvapps

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Проверка подлинности копии (только в release): отпечаток подписи этой программы сверяется с отпечатком нашего ключа,
 * зашитым при сборке. Если копию переделали и подписали чужим ключом, опасные действия (ADB и root, установка, смена
 * главного экрана) не работают, а в программе показывается предупреждение. Меню и запуск приложений остаются рабочими,
 * чтобы человек не остался без экрана. Обновление своим же ключом проверку проходит.
 */
object Integrity {
    @Volatile private var cached: Boolean? = null

    /** true: копия наша (или это отладочная сборка). */
    fun ok(ctx: Context? = null): Boolean {
        cached?.let { return it }
        if (BuildConfig.SIGNER_SHA256.isEmpty()) return true.also { cached = true }
        val c = ctx ?: App.instance ?: return true
        val r = runCatching { sign(c).equals(BuildConfig.SIGNER_SHA256, ignoreCase = true) }.getOrDefault(false)
        cached = r
        return r
    }

    private fun sign(ctx: Context): String {
        val pm = ctx.packageManager
        val bytes = if (Build.VERSION.SDK_INT >= 28) {
            val si = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory).first().toByteArray()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures.first().toByteArray()
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }
    }
}
