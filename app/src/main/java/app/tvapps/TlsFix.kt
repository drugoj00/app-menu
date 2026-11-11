package app.tvapps

import android.content.Context
import android.util.Log
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Корневые сертификаты Let's Encrypt (ISRG Root X1 и X2) для старых Android.
 *
 * Сервисы погоды, F-Droid и файлы релизов GitHub (release-assets.githubusercontent.com) используют Let's Encrypt, а на Android 7.1.0 и старше
 * этих корней в системе нет: любой запрос HTTPS заканчивается «Trust anchor for certification path not found», погода висит на «Загрузка…»,
 * обновление не скачивается. Здесь к системным корням ДОБАВЛЯЮТСЯ два публичных корня (проверка цепочки и имени сайта остаётся полной,
 * ничего не отключается). Системное хранилище опрашивается первым; свои корни используются, только если система отказала.
 */
object TlsFix {
    @Volatile private var done = false

    fun install(ctx: Context) {
        if (done) return
        done = true
        runCatching {
            val cf = CertificateFactory.getInstance("X.509")
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            listOf(R.raw.isrg_root_x1 to "isrg_x1", R.raw.isrg_root_x2 to "isrg_x2").forEach { (id, alias) ->
                ctx.resources.openRawResource(id).use { ks.setCertificateEntry(alias, cf.generateCertificate(it)) }
            }
            val sys = tm(null)
            val own = tm(ks)
            val composite = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = sys.checkClientTrusted(chain, authType)
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    try { sys.checkServerTrusted(chain, authType) } catch (e: CertificateException) { own.checkServerTrusted(chain, authType) }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = sys.acceptedIssuers + own.acceptedIssuers
            }
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, arrayOf<TrustManager>(composite), null)
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
        }.onFailure { Log.w("TVApps", "TlsFix: ${it.message}") }
    }

    private fun tm(ks: KeyStore?): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(ks)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
