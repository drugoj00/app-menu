package app.tvapps

import android.app.Application

class App : Application() {
    companion object { @Volatile var instance: App? = null }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        CacheBudget.trim(level)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // корни Let's Encrypt для Android 7.1.0 и старше (погода, обновления, F-Droid)
        TlsFix.install(this)
        // расписание фоновой проверки обновлений (если включено)
        Cfg.init(this)
        Lite.init(this)
        UpdSched.apply(this)
        // защита главного экрана: частые перезапуски при отключённом родном экране возвращают его
        HomeControl.guardOnProcessStart(this)
        // шифрование Conscrypt и ключ ADB создаются при первом обращении к ADB (Adb.ensure), а не при каждом запуске: экономит память
        Adb.init(this)
        // заставка по бездействию: если включена, служба и сторож поднимаются вместе с процессом программы
        runCatching { SaverGuard.startIfEnabled(this, "процесс программы") }
        Integrity.warm(this)
    }
}
