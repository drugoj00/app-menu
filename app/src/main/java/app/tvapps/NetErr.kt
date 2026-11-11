package app.tvapps

import android.content.Context
import android.util.Log

/** Последняя сетевая ошибка: чтобы вместо «Не удалось…» показать причину (сертификат, нет сети, время на устройстве). */
object NetErr {
    @Volatile var last: Throwable? = null
        private set

    fun set(e: Throwable) {
        last = e
        Log.w("TVApps", "сеть: ${e.javaClass.simpleName}: ${e.message}")
    }

    fun clear() { last = null }

    /** Короткая причина по-русски; пусто, если ошибок не было. */
    fun reason(ctx: Context): String {
        val e = last ?: return ""
        var tls = false
        var dns = false
        var c: Throwable? = e
        var n = 0
        while (c != null && n++ < 8) {
            if (c is javax.net.ssl.SSLException || c is java.security.cert.CertificateException) tls = true
            if (c is java.net.UnknownHostException || c is java.net.ConnectException || c is java.net.SocketTimeoutException) dns = true
            c = c.cause
        }
        return when {
            tls -> ctx.getString(R.string.net_err_tls)
            dns -> ctx.getString(R.string.net_err_dns)
            else -> e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
        }
    }
}
