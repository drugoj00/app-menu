package app.tvapps

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // расписание фоновой проверки обновлений (если включено)
        Cfg.init(this)
        UpdSched.apply(this)
        // загрузка шифрования и ключа ADB занимает заметное время на слабых приставках: делаем в фоне,
        // экран при этом уже рисуется; подключение дожидается готовности (Adb.awaitReady)
        Thread {
            // TLS 1.3 с экспортом ключевого материала нужен для сопряжения и беспроводной отладки
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
            Adb.init(this)
        }.start()
    }
}
